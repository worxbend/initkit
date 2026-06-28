package initkit.core

import java.io.IOException
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}
import java.security.MessageDigest
import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import initkit.config.*
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
    var installed = 0
    var failures = Vector.empty[BinaryDownloadFailure]
    var stopped = false

    operation.spec.items.foreach: item =>
      if stopped then ()
      else
        installItem(item) match
          case Right(()) =>
            installed += 1
          case Left(failure) =>
            failures = failures :+ failure
            if operation.execution.failFast then stopped = true

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

  private def installItem(item: BinaryDownloadItem): Either[BinaryDownloadFailure, Unit] =
    item.archive match
      case Some(_) =>
        Left(BinaryDownloadFailure.ArchiveUnsupported(item.name))
      case None =>
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
      _ <- files.setMode(tempPath, item.mode).left.map(error => BinaryDownloadFailure.File(item.name, error.message))
      _ <- files.moveIntoPlace(tempPath, destination).left.map(error => BinaryDownloadFailure.File(item.name, error.message))
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
  case ArchiveUnsupported(itemName: String)
  case Download(itemName: String, detail: String)
  case ChecksumMismatch(itemName: String, expected: String, actual: String)
  case File(itemName: String, detail: String)

  def message: String =
    this match
      case ArchiveUnsupported(itemName) =>
        s"$itemName archive extraction is not supported yet"
      case Download(itemName, detail) =>
        s"$itemName download failed: $detail"
      case ChecksumMismatch(itemName, expected, actual) =>
        s"$itemName checksum mismatch: expected ${BinaryDownloadsExecutor.normalizeChecksum(expected)}, got $actual"
      case File(itemName, detail) =>
        s"$itemName file operation failed: $detail"

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
