package binstaller.core

import binstaller.config.ArchiveType

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using

private[core] enum ArchiveEntryKind:
  case File, Directory

private[core] final case class ArchiveEntry(name: String, kind: ArchiveEntryKind)

private[core] final case class PlannedArchiveFile(source: String, target: Path)

private[core] object ArchiveExtractor:

  def extract(
      archive: ResolvedArchive,
      bytes: Array[Byte],
      stagingDir: Path,
      commandExecutor: CommandExecutor
  ): Either[String, Unit] = archive.original.archiveType match
    case ArchiveType.Zip   => extractZip(archive, bytes, stagingDir)
    case ArchiveType.TarGz => extractTarGz(archive, bytes, stagingDir)
    case ArchiveType.TarXz => extractTarXz(archive, bytes, stagingDir, commandExecutor)

  private def extractZip(
      archive: ResolvedArchive,
      bytes: Array[Byte],
      stagingDir: Path
  ): Either[String, Unit] = Try(indexZip(bytes)).toEither.left.map(_.getMessage).flatMap: entries =>
    planExtraction(entries, archive, stagingDir).flatMap: plannedFiles =>
      Try:
        Using.resource(ZipInputStream(ByteArrayInputStream(bytes))): zip =>
          var entry = zip.getNextEntry
          while entry != null do
            normalizedArchivePath(entry.getName).foreach: source =>
              plannedFiles.find(_.source == source).foreach: planned =>
                copyCurrentEntry(zip, planned.target)
            zip.closeEntry()
            entry = zip.getNextEntry
      match
        case Success(_)     => Right(())
        case Failure(error) => Left(error.getMessage)

  private def indexZip(bytes: Array[Byte]): Vector[ArchiveEntry] =
    Using.resource(ZipInputStream(ByteArrayInputStream(bytes))): zip =>
      Iterator
        .continually(zip.getNextEntry)
        .takeWhile(_ != null)
        .map: entry =>
          val source = normalizedArchivePath(entry.getName).fold(
            message => throw IllegalArgumentException(message),
            identity
          )
          val kind = if entry.isDirectory then ArchiveEntryKind.Directory else ArchiveEntryKind.File
          ArchiveEntry(source, kind)
        .toVector

  private def extractTarGz(
      archive: ResolvedArchive,
      bytes: Array[Byte],
      stagingDir: Path
  ): Either[String, Unit] = Try(indexTarGz(bytes)).toEither.left.map(_.getMessage).flatMap:
    entries =>
      planExtraction(entries, archive, stagingDir).flatMap: plannedFiles =>
        val plannedBySource = plannedFiles.map(file => file.source -> file.target).toMap
        Try:
          Using.resource(GZIPInputStream(ByteArrayInputStream(bytes))): input =>
            readTarEntries(input): (entry, content) =>
              plannedBySource.get(entry.name).foreach: target =>
                copyBounded(content, target, entry.size)
              if !plannedBySource.contains(entry.name) then
                val _ = skipFully(content, entry.size)
        match
          case Success(_)     => Right(())
          case Failure(error) => Left(error.getMessage)

  private def indexTarGz(bytes: Array[Byte]): Vector[ArchiveEntry] =
    Using.resource(GZIPInputStream(ByteArrayInputStream(bytes))): input =>
      val entries = Vector.newBuilder[ArchiveEntry]
      readTarEntries(input): (entry, content) =>
        val _ = skipFully(content, entry.size)
        entries += ArchiveEntry(entry.name, entry.kind)
      entries.result()

  private def extractTarXz(
      archive: ResolvedArchive,
      bytes: Array[Byte],
      stagingDir: Path,
      commandExecutor: CommandExecutor
  ): Either[String, Unit] =
    val archiveFile = Files.createTempFile(stagingDir, ".archive-", ".tar.xz")
    val extractDir  = Files.createTempDirectory(stagingDir, ".archive-extract-")
    Files.write(archiveFile, bytes)
    val spec = CommandSpec(
      Vector("tar", "-xJf", archiveFile.toString, "-C", extractDir.toString),
      stagingDir,
      CommandEnvironment.baseline
    )
    commandExecutor.run(spec) match
      case Left(error) =>
        deleteRecursively(extractDir)
        val _ = Files.deleteIfExists(archiveFile)
        Left(CommandFailureDetails.render(error))
      case Right(()) =>
        val result = Try(indexExtractedDirectory(extractDir)).toEither.left.map(_.getMessage)
          .flatMap: entries =>
            planExtraction(entries, archive, stagingDir).flatMap: plannedFiles =>
              Try:
                plannedFiles.foreach: planned =>
                  val source = extractDir.resolve(planned.source).normalize()
                  copyFile(source, planned.target)
              match
                case Success(_)     => Right(())
                case Failure(error) => Left(error.getMessage)
        deleteRecursively(extractDir)
        val _ = Files.deleteIfExists(archiveFile)
        result

  private def indexExtractedDirectory(root: Path): Vector[ArchiveEntry] =
    Using.resource(Files.walk(root)): stream =>
      stream
        .iterator()
        .asScala
        .toVector
        .filterNot(_ == root)
        .map: path =>
          val relative = root.relativize(path).toString.replace('\\', '/')
          val source   = normalizedArchivePath(relative).fold(
            message => throw IllegalArgumentException(message),
            identity
          )
          val kind =
            if Files.isDirectory(path) then ArchiveEntryKind.Directory else ArchiveEntryKind.File
          ArchiveEntry(source, kind)

  private def planExtraction(
      entries: Vector[ArchiveEntry],
      archive: ResolvedArchive,
      stagingDir: Path
  ): Either[String, Vector[PlannedArchiveFile]] =
    // Build the complete copy plan before writing selected members so duplicate sources and target
    // collisions fail without partially populating the staged install tree.
    rejectDuplicateArchiveSources(entries).flatMap: _ =>
      val fileMappings      = archive.files.map(planFileMapping(entries, stagingDir, _))
      val directoryMappings = archive.directories.map(planDirectoryMapping(entries, stagingDir, _))
      val planned           = (fileMappings ++ directoryMappings).foldLeft(
        Right(Vector.empty): Either[String, Vector[PlannedArchiveFile]]
      ): (acc, next) =>
        for
          current <- acc
          files   <- next
        yield current ++ files

      planned.flatMap(rejectDuplicateTargets)

  private def rejectDuplicateArchiveSources(entries: Vector[ArchiveEntry]): Either[String, Unit] =
    val duplicate = entries
      .groupBy(_.name)
      .collectFirst:
        case (source, values) if values.size > 1 => source
    duplicate match
      case Some(source) => Left(s"duplicate archive member: $source")
      case None         => Right(())

  private def planFileMapping(
      entries: Vector[ArchiveEntry],
      stagingDir: Path,
      mapping: ResolvedExtractMapping
  ): Either[String, Vector[PlannedArchiveFile]] =
    for
      source <- normalizedArchivePath(mapping.from)
      target <- resolveInside(stagingDir, mapping.to)
      _      <- entries.find(entry => entry.name == source && entry.kind == ArchiveEntryKind.File)
        .toRight(s"archive member not found: ${mapping.from}")
    yield Vector(PlannedArchiveFile(source, target))

  private def planDirectoryMapping(
      entries: Vector[ArchiveEntry],
      stagingDir: Path,
      mapping: ResolvedExtractMapping
  ): Either[String, Vector[PlannedArchiveFile]] =
    normalizedArchivePath(mapping.from).flatMap: source =>
      val prefix = s"$source/"
      val files  = entries.filter: entry =>
        entry.kind == ArchiveEntryKind.File && entry.name.startsWith(prefix)
      if files.isEmpty then Left(s"archive directory not found: ${mapping.from}")
      else
        val planned = files.map: entry =>
          val relative = entry.name.stripPrefix(prefix)
          val target   = joinArchivePath(mapping.to, relative)
          resolveInside(stagingDir, target).map: targetPath =>
            PlannedArchiveFile(entry.name, targetPath)
        collectEither(planned)

  private def rejectDuplicateTargets(
      plannedFiles: Vector[PlannedArchiveFile]
  ): Either[String, Vector[PlannedArchiveFile]] =
    val duplicate = plannedFiles
      .groupBy(_.target)
      .collectFirst:
        case (target, files) if files.size > 1 => target
    duplicate match
      case Some(target) => Left(s"multiple archive members map to $target")
      case None         => Right(plannedFiles)

  private final case class TarEntry(name: String, kind: ArchiveEntryKind, size: Long)

  private def readTarEntries(input: InputStream)(handle: (TarEntry, InputStream) => Unit): Unit =
    var header = readTarBlock(input)
    while header.exists(!_.forall(_ == 0.toByte)) do
      val current = header.get
      val entry   = tarEntry(current)
      handle(entry, input)
      val padding = tarPadding(entry.size)
      val _       = skipFully(input, padding)
      header = readTarBlock(input)

  private def tarEntry(header: Array[Byte]): TarEntry =
    val name     = tarString(header, 0, 100)
    val prefix   = tarString(header, 345, 155)
    val fullName = if prefix.isEmpty then name else s"$prefix/$name"
    val source   = normalizedArchivePath(fullName).fold(
      message => throw IllegalArgumentException(message),
      identity
    )
    val size = tarOctal(header, 124, 12)
    val kind = header(156).toChar match
      case 0 | '0' => ArchiveEntryKind.File
      case '5'     => ArchiveEntryKind.Directory
      // Links and special tar metadata are rejected because they can escape the apparent file tree
      // even when the entry name itself is relative.
      case '1' | '2' => throw IllegalArgumentException(s"unsafe archive link entry: $source")
      case other => throw IllegalArgumentException(s"unsupported tar entry type '$other': $source")
    TarEntry(source, kind, size)

  private def readTarBlock(input: InputStream): Option[Array[Byte]] =
    val buffer = Array.ofDim[Byte](512)
    var offset = 0
    while offset < buffer.length do
      val count = input.read(buffer, offset, buffer.length - offset)
      if count == -1 then return if offset == 0 then None else Some(buffer)
      offset = offset + count
    Some(buffer)

  private def tarString(header: Array[Byte], offset: Int, length: Int): String =
    val bytes = header.slice(offset, offset + length).takeWhile(_ != 0.toByte)
    new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim

  private def tarOctal(header: Array[Byte], offset: Int, length: Int): Long =
    val value = tarString(header, offset, length).trim
    if value.isEmpty then 0L else java.lang.Long.parseLong(value, 8)

  private def tarPadding(size: Long): Long =
    val remainder = size % 512L
    if remainder == 0L then 0L else 512L - remainder

  private def normalizedArchivePath(value: String): Either[String, String] =
    val path = value.stripSuffix("/")
    // Archive names are treated as POSIX-like relative paths independent of host OS. Backslash,
    // drive prefixes, absolute roots, controls, and `..` are rejected before copy planning.
    if path.isEmpty then Left("archive path must not be empty")
    else if path == "." then Right(path)
    else if path.exists(_ < ' ') then Left(s"archive path contains control character: $value")
    else if path.contains('\\') then Left(s"archive path contains backslash: $value")
    else if path.matches("^[A-Za-z]:.*") then Left(s"archive path is drive-prefixed: $value")
    else
      val nioPath = Path.of(path)
      if nioPath.isAbsolute then Left(s"archive path is absolute: $value")
      else
        val segments = path.split('/').toVector
        val unsafe   = segments.exists(_ == "..")
        if unsafe then Left(s"archive path escapes staging directory: $value")
        else
          val normalized = segments.filterNot(segment => segment.isEmpty || segment == ".")
          if normalized.isEmpty then Right(".")
          else Right(normalized.mkString("/"))

  private def resolveInside(root: Path, relative: String): Either[String, Path] =
    val clean = relative match
      case "" | "." => "."
      case other    => other
    validateRelativeTarget(clean).flatMap: _ =>
      val input = Path.of(clean)
      if input.isAbsolute then Left(s"path must be relative: $relative")
      else
        val normalizedRoot = root.toAbsolutePath.normalize()
        val resolved       = normalizedRoot.resolve(input).normalize()
        if resolved.startsWith(normalizedRoot) then Right(resolved)
        else Left(s"path escapes staging directory: $relative")

  private def validateRelativeTarget(value: String): Either[String, Unit] =
    if value == "." then Right(())
    else normalizedArchivePath(value).map(_ => ())

  private def collectEither[A](values: Vector[Either[String, A]]): Either[String, Vector[A]] =
    values.foldLeft(Right(Vector.empty): Either[String, Vector[A]]): (acc, next) =>
      for
        current <- acc
        value   <- next
      yield current :+ value

  private def joinArchivePath(parent: String, child: String): String = parent match
    case "" | "."                     => child
    case value if value.endsWith("/") => s"$value$child"
    case value                        => s"$value/$child"

  private def copyCurrentEntry(input: InputStream, target: Path): Unit =
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))
    val _ = Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)

  private def copyBounded(input: InputStream, target: Path, bytes: Long): Unit =
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))
    Using.resource(Files.newOutputStream(
      target,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    )): output =>
      val buffer    = Array.ofDim[Byte](8192)
      var remaining = bytes
      while remaining > 0 do
        val count = input.read(buffer, 0, math.min(buffer.length.toLong, remaining).toInt)
        if count == -1 then throw IllegalArgumentException("unexpected end of tar entry")
        output.write(buffer, 0, count)
        remaining = remaining - count

  private def copyFile(source: Path, target: Path): Unit =
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))
    val _ = Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)

  private def skipFully(input: InputStream, bytes: Long): Long =
    var remaining = bytes
    while remaining > 0 do
      val skipped = input.skip(remaining)
      if skipped <= 0 then
        if input.read() == -1 then return remaining
        else remaining = remaining - 1
      else remaining = remaining - skipped
    0L

  private def deleteRecursively(path: Path): Unit = if Files.exists(path) then
    Using.resource(Files.walk(path)): stream =>
      stream
        .iterator()
        .asScala
        .toVector
        .sortBy(_.getNameCount)
        .reverse
        .foreach(child => Try(Files.deleteIfExists(child)))
