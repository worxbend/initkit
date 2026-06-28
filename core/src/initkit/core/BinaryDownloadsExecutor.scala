package initkit.core

import java.io.{EOFException, IOException, InputStream, OutputStream}
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import initkit.config.*
import ox.channels.{BufferCapacity, ChannelClosedException}
import ox.flow.Flow
import sttp.client4.*
import sttp.model.Uri

final class BinaryDownloadsExecutor(
    httpClient: BinaryDownloadHttpClient = BinaryDownloadHttpClient.Sttp,
    files: BinaryDownloadFiles = BinaryDownloadFiles.Jvm,
    config: BinaryDownloadHttpConfig = BinaryDownloadHttpConfig.default
):
  def install(
      operation: InstallerPlanOperation[InstallerSpec.BinaryDownloads],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    policy.mode match
      case ExecutionRunMode.DryRun =>
        PlanOperationOutcome.DryRun(BinaryDownloadsExecutor.dryRunData(operation, files))
      case ExecutionRunMode.Apply =>
        applyDownloads(operation)

  private def applyDownloads(
      operation: InstallerPlanOperation[InstallerSpec.BinaryDownloads]
  ): PlanOperationOutcome =
    val results = runItems(operation)
    val installed = results.count(_.isRight)
    val failures = results.collect { case Left(failure) => failure }

    failures.headOption match
      case Some(firstFailure) =>
        PlanOperationOutcome.Failed(
          PlanFailure(
            operation = operation.summary,
            message = s"${failures.size} binary download item(s) failed in plan entry " +
              s"'${operation.summary.name}'; first failure: ${firstFailure.message}",
            exitCode = None
          )
        )
      case None =>
        PlanOperationOutcome.Completed(Vector(completionDetail(installed)))

  private def runItems(
      operation: InstallerPlanOperation[InstallerSpec.BinaryDownloads]
  ): Vector[Either[BinaryDownloadFailure, Unit]] =
    operation.execution.mode match
      case PlanEntryExecutionMode.Sequential =>
        runItemsSequentially(operation.spec.items, operation.execution.failFast)
      case PlanEntryExecutionMode.Parallel =>
        runItemsInParallel(operation.spec.items, operation.execution.maxConcurrency, operation.execution.failFast)

  private def runItemsSequentially(
      items: Vector[BinaryDownloadItem],
      failFast: Boolean
  ): Vector[Either[BinaryDownloadFailure, Unit]] =
    var stopped = false
    items.flatMap: item =>
      if stopped then Vector.empty
      else
        val result = installItem(item)
        result match
          case Left(_) if failFast => stopped = true
          case _                   => ()
        Vector(result)

  private def runItemsInParallel(
      items: Vector[BinaryDownloadItem],
      maxConcurrency: Int,
      failFast: Boolean
  ): Vector[Either[BinaryDownloadFailure, Unit]] =
    given BufferCapacity = BufferCapacity(maxConcurrency)

    if failFast then
      try
        Flow
          .fromIterable(items)
          .mapParUnordered(maxConcurrency): item =>
            installItem(item) match
              case right @ Right(_) => right
              case Left(failure)    => throw new BinaryDownloadFailFast(failure)
          .runToList()
          .toVector
      catch
        case error: BinaryDownloadFailFast =>
          Vector(Left(error.failure))
        case ChannelClosedException.Error(error: BinaryDownloadFailFast) =>
          Vector(Left(error.failure))
    else
      Flow
        .fromIterable(items)
        .mapParUnordered(maxConcurrency)(installItem)
        .runToList()
        .toVector

  private def installItem(item: BinaryDownloadItem): Either[BinaryDownloadFailure, Unit] =
    val destination = Path.of(item.destination)
    files.createTempDownload(item.name, destination) match
      case Left(error) =>
        Left(BinaryDownloadFailure.File(item.name, error.message))
      case Right(tempPath) =>
        try downloadVerifyAndInstall(item, tempPath, destination)
        finally files.deleteIfExists(tempPath)

  private def downloadVerifyAndInstall(
      item: BinaryDownloadItem,
      tempPath: Path,
      destination: Path
  ): Either[BinaryDownloadFailure, Unit] =
    for
      _ <- download(item, tempPath)
      _ <- verifyChecksum(item, tempPath)
      _ <- installDownloadedItem(item, tempPath, destination)
    yield ()

  private def installDownloadedItem(
      item: BinaryDownloadItem,
      downloadedPath: Path,
      destination: Path
  ): Either[BinaryDownloadFailure, Unit] =
    item.archive match
      case None =>
        installFile(item, downloadedPath, destination)
      case Some(archive) =>
        files.createTempDownload(s"${item.name}-archive-member", destination) match
          case Left(error) =>
            Left(BinaryDownloadFailure.File(item.name, error.message))
          case Right(extractedPath) =>
            try
              for
                _ <- BinaryTarGzExtractor
                  .extractSelectedFile(downloadedPath, archive, extractedPath)
                  .left
                  .map(error => BinaryDownloadFailure.Archive(item.name, error.message))
                _ <- installFile(item, extractedPath, destination)
              yield ()
            finally files.deleteIfExists(extractedPath)

  private def installFile(
      item: BinaryDownloadItem,
      source: Path,
      destination: Path
  ): Either[BinaryDownloadFailure, Unit] =
    for
      _ <- files.setMode(source, item.mode).left.map(error => BinaryDownloadFailure.File(item.name, error.message))
      _ <- files.moveIntoPlace(source, destination).left.map(error => BinaryDownloadFailure.File(item.name, error.message))
    yield ()

  private def download(item: BinaryDownloadItem, tempPath: Path): Either[BinaryDownloadFailure, Unit] =
    httpClient.download(item.url, tempPath, config) match
      case Right(_) =>
        Right(())
      case Left(error) =>
        Left(BinaryDownloadFailure.Download(item.name, error.message))

  private def verifyChecksum(item: BinaryDownloadItem, tempPath: Path): Either[BinaryDownloadFailure, Unit] =
    item.checksum match
      case None =>
        Right(())
      case Some(checksum) =>
        BinaryDownloadsExecutor.checksumHex(tempPath, checksum.algorithm) match
          case Right(actual) if actual == BinaryDownloadsExecutor.normalizeChecksum(checksum.value) =>
            Right(())
          case Right(actual) =>
            Left(BinaryDownloadFailure.ChecksumMismatch(item.name, checksum.value, actual))
          case Left(error) =>
            Left(BinaryDownloadFailure.File(item.name, error.message))

  private def completionDetail(installed: Int): String =
    installed match
      case 0 => "no binary downloads installed"
      case 1 => "installed 1 binary download"
      case _ => s"installed $installed binary downloads"

private enum BinaryDownloadFailure:
  case Archive(itemName: String, detail: String)
  case Download(itemName: String, detail: String)
  case ChecksumMismatch(itemName: String, expected: String, actual: String)
  case File(itemName: String, detail: String)

  def message: String =
    this match
      case Archive(itemName, detail) =>
        s"$itemName archive extraction failed: $detail"
      case Download(itemName, detail) =>
        s"$itemName download failed: $detail"
      case ChecksumMismatch(itemName, expected, actual) =>
        s"$itemName checksum mismatch: expected ${BinaryDownloadsExecutor.normalizeChecksum(expected)}, got $actual"
      case File(itemName, detail) =>
        s"$itemName file operation failed: $detail"

private final class BinaryDownloadFailFast(val failure: BinaryDownloadFailure)
    extends RuntimeException(failure.message, null, false, false)

private final case class BinaryTarGzExtractionError(message: String)

private object BinaryTarGzExtractor:
  private val TarBlockSize = 512
  private val TarNameOffset = 0
  private val TarNameLength = 100
  private val TarSizeOffset = 124
  private val TarSizeLength = 12
  private val TarTypeFlagOffset = 156
  private val TarPrefixOffset = 345
  private val TarPrefixLength = 155
  private val RegularFileTypes = Set(0.toByte, '0'.toByte)

  def extractSelectedFile(
      archivePath: Path,
      archive: Archive,
      destination: Path
  ): Either[BinaryTarGzExtractionError, Unit] =
    archive.archiveType match
      case ArchiveType.TarGz =>
        extractTarGzFile(archivePath, archive, destination)

  private def extractTarGzFile(
      archivePath: Path,
      archive: Archive,
      destination: Path
  ): Either[BinaryTarGzExtractionError, Unit] =
    val selectedPath = normalizeTarPath(archive.path)
    val stripComponents = archive.stripComponents.getOrElse(0)

    extractMatchingEntry(archivePath, destination, entry => entry.strippedName(stripComponents) == selectedPath)
      .flatMap:
        case SelectedTarEntry.Found =>
          Right(())
        case SelectedTarEntry.NotFound =>
          extractMatchingEntry(archivePath, destination, entry => entry.name == selectedPath).flatMap:
            case SelectedTarEntry.Found =>
              Right(())
            case SelectedTarEntry.NotFound =>
              Left(BinaryTarGzExtractionError(s"archive member '${archive.path}' was not found"))

  private def extractMatchingEntry(
      archivePath: Path,
      destination: Path,
      matches: TarEntry => Boolean
  ): Either[BinaryTarGzExtractionError, SelectedTarEntry] =
    try
      val input = GZIPInputStream(Files.newInputStream(archivePath))
      try readTar(input, destination, matches)
      finally input.close()
    catch
      case error: IOException =>
        Left(BinaryTarGzExtractionError(Option(error.getMessage).getOrElse(error.getClass.getName)))
      case error: SecurityException =>
        Left(BinaryTarGzExtractionError(Option(error.getMessage).getOrElse(error.getClass.getName)))

  private def readTar(
      input: InputStream,
      destination: Path,
      matches: TarEntry => Boolean
  ): Either[BinaryTarGzExtractionError, SelectedTarEntry] =
    val header = Array.ofDim[Byte](TarBlockSize)
    var done = false
    var selected = false
    var failure: Option[BinaryTarGzExtractionError] = None

    while !done do
      readHeader(input, header) match
        case HeaderRead.EndOfArchive =>
          done = true
        case HeaderRead.Header =>
          val entry = TarEntry.fromHeader(header)
          if matches(entry) then
            if entry.isRegularFile then
              copyEntry(input, destination, entry.size)
              selected = true
              done = true
            else
              failure = Some(BinaryTarGzExtractionError(s"archive member '${entry.name}' is not a regular file"))
              done = true
          else skipEntry(input, entry.size)

    failure match
      case Some(error) =>
        Left(error)
      case None if selected =>
        Right(SelectedTarEntry.Found)
      case None =>
        Right(SelectedTarEntry.NotFound)

  private def readHeader(input: InputStream, header: Array[Byte]): HeaderRead =
    readFullyOrEof(input, header) match
      case 0 =>
        HeaderRead.EndOfArchive
      case TarBlockSize if header.forall(_ == 0.toByte) =>
        HeaderRead.EndOfArchive
      case TarBlockSize =>
        HeaderRead.Header
      case read =>
        throw EOFException(s"truncated tar header after $read byte(s)")

  private def readFullyOrEof(input: InputStream, bytes: Array[Byte]): Int =
    var offset = 0
    var read = input.read(bytes, offset, bytes.length - offset)
    while read > 0 && offset + read < bytes.length do
      offset += read
      read = input.read(bytes, offset, bytes.length - offset)

    if read > 0 then offset + read
    else offset

  private def copyEntry(input: InputStream, destination: Path, size: Long): Unit =
    val parent = Option(destination.toAbsolutePath.normalize().getParent).getOrElse(Path.of(".").toAbsolutePath.normalize())
    Files.createDirectories(parent)
    val output = Files.newOutputStream(destination)
    try copyLimited(input, output, size)
    finally output.close()

  private def copyLimited(input: InputStream, output: OutputStream, size: Long): Unit =
    val buffer = Array.ofDim[Byte](8192)
    var remaining = size
    while remaining > 0 do
      val read = input.read(buffer, 0, math.min(buffer.length.toLong, remaining).toInt)
      if read < 0 then throw EOFException("truncated tar entry")
      output.write(buffer, 0, read)
      remaining -= read

  private def skipEntry(input: InputStream, size: Long): Unit =
    var remaining = size + tarPadding(size)
    while remaining > 0 do
      val skipped = input.skip(remaining)
      if skipped > 0 then remaining -= skipped
      else if input.read() >= 0 then remaining -= 1
      else throw EOFException("truncated tar entry")

  private def tarPadding(size: Long): Long =
    val remainder = size % TarBlockSize
    if remainder == 0 then 0
    else TarBlockSize - remainder

  private def normalizeTarPath(value: String): String =
    value
      .replace('\\', '/')
      .split("/")
      .filter(component => component.nonEmpty && component != ".")
      .mkString("/")

  private def parseName(header: Array[Byte]): String =
    val name = parseString(header, TarNameOffset, TarNameLength)
    val prefix = parseString(header, TarPrefixOffset, TarPrefixLength)
    normalizeTarPath(Option.when(prefix.nonEmpty)(s"$prefix/$name").getOrElse(name))

  private def parseString(header: Array[Byte], offset: Int, length: Int): String =
    val bytes = header.slice(offset, offset + length).takeWhile(_ != 0.toByte)
    new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim

  private def parseOctal(header: Array[Byte], offset: Int, length: Int): Long =
    val value = parseString(header, offset, length).filter(char => char >= '0' && char <= '7')
    if value.isEmpty then 0L
    else java.lang.Long.parseLong(value, 8)

  private final case class TarEntry(name: String, size: Long, typeFlag: Byte):
    def isRegularFile: Boolean =
      RegularFileTypes.contains(typeFlag)

    def strippedName(stripComponents: Int): String =
      name.split("/").drop(stripComponents).mkString("/")

  private object TarEntry:
    def fromHeader(header: Array[Byte]): TarEntry =
      TarEntry(
        name = parseName(header),
        size = parseOctal(header, TarSizeOffset, TarSizeLength),
        typeFlag = header(TarTypeFlagOffset)
      )

  private enum HeaderRead:
    case Header, EndOfArchive

  private enum SelectedTarEntry:
    case Found, NotFound

trait BinaryDownloadHttpClient:
  def download(
      url: String,
      destination: Path,
      config: BinaryDownloadHttpConfig
  ): Either[BinaryDownloadHttpError, BinaryDownloadHttpResponse]

final case class BinaryDownloadHttpConfig(
    connectionTimeout: FiniteDuration,
    readTimeout: FiniteDuration
)

object BinaryDownloadHttpConfig:
  val default: BinaryDownloadHttpConfig =
    BinaryDownloadHttpConfig(connectionTimeout = 30.seconds, readTimeout = 5.minutes)

final case class BinaryDownloadHttpResponse(
    statusCode: Int,
    contentLength: Option[Long]
)

enum BinaryDownloadHttpError:
  case InvalidUrl(url: String, detail: String)
  case HttpStatus(url: String, statusCode: Int, body: String)
  case Transport(url: String, detail: String)
  case Interrupted(url: String)

  def message: String =
    this match
      case InvalidUrl(url, detail) =>
        s"invalid URL '$url': $detail"
      case HttpStatus(url, statusCode, body) if body.trim.nonEmpty =>
        s"HTTP $statusCode from $url: ${body.trim.take(200)}"
      case HttpStatus(url, statusCode, _) =>
        s"HTTP $statusCode from $url"
      case Transport(url, detail) =>
        s"failed to download $url: $detail"
      case Interrupted(url) =>
        s"interrupted while downloading $url"

object BinaryDownloadHttpClient:
  val Sttp: BinaryDownloadHttpClient =
    new BinaryDownloadHttpClient:
      override def download(
          url: String,
          destination: Path,
          config: BinaryDownloadHttpConfig
      ): Either[BinaryDownloadHttpError, BinaryDownloadHttpResponse] =
        val backend = DefaultSyncBackend(
          options = BackendOptions.connectionTimeout(config.connectionTimeout)
        )

        try
          Uri.parse(url) match
            case Left(error) =>
              Left(BinaryDownloadHttpError.InvalidUrl(url, error))
            case Right(uri) =>
              val response =
                basicRequest
                  .get(uri)
                  .readTimeout(config.readTimeout)
                  .response(asPath(destination))
                  .send(backend)

              response.body match
                case Right(_) =>
                  Right(BinaryDownloadHttpResponse(response.code.code, contentLength(response)))
                case Left(body) =>
                  Files.deleteIfExists(destination)
                  Left(BinaryDownloadHttpError.HttpStatus(url, response.code.code, body))
        catch
          case error: IllegalArgumentException =>
            Left(BinaryDownloadHttpError.InvalidUrl(url, error.getMessage))
          case error: InterruptedException =>
            Thread.currentThread().interrupt()
            Left(BinaryDownloadHttpError.Interrupted(url))
          case NonFatal(error) =>
            Left(BinaryDownloadHttpError.Transport(url, Option(error.getMessage).getOrElse(error.getClass.getName)))
        finally backend.close()

      private def contentLength(response: Response[Either[String, Path]]): Option[Long] =
        response.header("Content-Length").flatMap(_.toLongOption)

trait BinaryDownloadFiles:
  def createTempDownload(itemName: String, destination: Path): Either[BinaryDownloadFileError, Path]
  def previewTempDownloadPath(itemName: String, destination: Path): Path
  def setMode(path: Path, mode: String): Either[BinaryDownloadFileError, Unit]
  def moveIntoPlace(source: Path, destination: Path): Either[BinaryDownloadFileError, Unit]
  def deleteIfExists(path: Path): Unit

final case class BinaryDownloadFileError(message: String)

object BinaryDownloadFiles:
  val Jvm: BinaryDownloadFiles =
    new BinaryDownloadFiles:
      override def createTempDownload(itemName: String, destination: Path): Either[BinaryDownloadFileError, Path] =
        safely:
          val absoluteDestination = destination.toAbsolutePath.normalize()
          val parent = Option(absoluteDestination.getParent).getOrElse(Path.of(".").toAbsolutePath.normalize())
          Files.createDirectories(parent)
          Files.createTempFile(parent, ".initkit-" + safeName(itemName) + "-", ".download")

      override def previewTempDownloadPath(itemName: String, destination: Path): Path =
        val parent = Option(destination.getParent).getOrElse(Path.of("<cwd>"))
        parent.resolve(s".initkit-${safeName(itemName)}.download")

      override def setMode(path: Path, mode: String): Either[BinaryDownloadFileError, Unit] =
        BinaryDownloadsExecutor.permissionsFromMode(mode) match
          case Left(error) =>
            Left(BinaryDownloadFileError(error))
          case Right(permissions) =>
            safely:
              Files.setPosixFilePermissions(path, permissions.asJava)
              ()

      override def moveIntoPlace(source: Path, destination: Path): Either[BinaryDownloadFileError, Unit] =
        safely:
          val absoluteDestination = destination.toAbsolutePath.normalize()
          val parent = Option(absoluteDestination.getParent).getOrElse(Path.of(".").toAbsolutePath.normalize())
          Files.createDirectories(parent)
          try Files.move(source, absoluteDestination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
          catch case _: AtomicMoveNotSupportedException =>
            Files.move(source, absoluteDestination, StandardCopyOption.REPLACE_EXISTING)
          ()

      override def deleteIfExists(path: Path): Unit =
        try Files.deleteIfExists(path)
        catch case _: IOException | _: SecurityException => ()

      private def safely[A](body: => A): Either[BinaryDownloadFileError, A] =
        try Right(body)
        catch
          case error: IOException =>
            Left(BinaryDownloadFileError(error.getMessage))
          case error: SecurityException =>
            Left(BinaryDownloadFileError(error.getMessage))
          case error: UnsupportedOperationException =>
            Left(BinaryDownloadFileError(error.getMessage))

      private def safeName(value: String): String =
        value.map:
          case char if char.isLetterOrDigit || char == '-' || char == '_' => char
          case _                                                          => '-'
        .mkString

object BinaryDownloadsExecutor:
  def dryRunData(
      operation: InstallerPlanOperation[InstallerSpec.BinaryDownloads],
      files: BinaryDownloadFiles
  ): DryRunOperationData =
    DryRunOperationData(operation.summary, operation.spec.items.flatMap(item => dryRunActions(item, files)))

  def checksumHex(path: Path, algorithm: ChecksumAlgorithm): Either[BinaryDownloadFileError, String] =
    val digestName = algorithm match
      case ChecksumAlgorithm.Sha256 => "SHA-256"
      case ChecksumAlgorithm.Sha512 => "SHA-512"

    try
      val digest = MessageDigest.getInstance(digestName)
      val input = Files.newInputStream(path)
      try
        val buffer = Array.ofDim[Byte](8192)
        var read = input.read(buffer)
        while read >= 0 do
          if read > 0 then digest.update(buffer, 0, read)
          read = input.read(buffer)
      finally input.close()

      Right(digest.digest().map(byte => f"${byte & 0xff}%02x").mkString)
    catch
      case error: IOException =>
        Left(BinaryDownloadFileError(error.getMessage))
      case error: SecurityException =>
        Left(BinaryDownloadFileError(error.getMessage))

  def normalizeChecksum(value: String): String =
    value.trim.toLowerCase

  def permissionsFromMode(mode: String): Either[String, Set[PosixFilePermission]] =
    val trimmed = mode.trim
    val withoutLeadingZero = if trimmed.length == 4 && trimmed.startsWith("0") then trimmed.drop(1) else trimmed

    if !withoutLeadingZero.matches("[0-7]{3}") then
      Left(s"unsupported file mode '$mode'; expected three octal permission digits such as 755")
    else
      val value = Integer.parseInt(withoutLeadingZero, 8)
      Right(posixPermissions(value))

  private def dryRunActions(item: BinaryDownloadItem, files: BinaryDownloadFiles): Vector[DryRunAction] =
    val destination = Path.of(item.destination)
    val tempPath = files.previewTempDownloadPath(item.name, destination)
    val checksum = item.checksum.map(value => s" with ${checksumName(value.algorithm)} verification").getOrElse("")
    val archiveSuffix = item.archive.map(_ => " archive").getOrElse("")

    Vector(
      DryRunAction.Message(s"download binary$archiveSuffix '${item.name}' from ${item.url} to $tempPath$checksum"),
      DryRunAction.FileWrite(item.destination, Some(item.mode), s"install binary download '${item.name}'")
    )

  private def checksumName(algorithm: ChecksumAlgorithm): String =
    algorithm match
      case ChecksumAlgorithm.Sha256 => "sha256"
      case ChecksumAlgorithm.Sha512 => "sha512"

  private def posixPermissions(value: Int): Set[PosixFilePermission] =
    val permissions = Set.newBuilder[PosixFilePermission]

    addPermission(permissions, value, 0x100, PosixFilePermission.OWNER_READ)
    addPermission(permissions, value, 0x080, PosixFilePermission.OWNER_WRITE)
    addPermission(permissions, value, 0x040, PosixFilePermission.OWNER_EXECUTE)
    addPermission(permissions, value, 0x020, PosixFilePermission.GROUP_READ)
    addPermission(permissions, value, 0x010, PosixFilePermission.GROUP_WRITE)
    addPermission(permissions, value, 0x008, PosixFilePermission.GROUP_EXECUTE)
    addPermission(permissions, value, 0x004, PosixFilePermission.OTHERS_READ)
    addPermission(permissions, value, 0x002, PosixFilePermission.OTHERS_WRITE)
    addPermission(permissions, value, 0x001, PosixFilePermission.OTHERS_EXECUTE)

    permissions.result()

  private def addPermission(
      permissions: mutable.Builder[PosixFilePermission, Set[PosixFilePermission]],
      value: Int,
      bit: Int,
      permission: PosixFilePermission
  ): Unit =
    if (value & bit) != 0 then permissions += permission
