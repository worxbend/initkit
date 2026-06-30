package binstaller.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import upickle.default.*

/** Expected lock-file write failure. */
enum LockFileError:
  case Missing(path: Path)
  case ReadFailed(path: Path, message: String)
  case DecodeFailed(path: Path, message: String)
  case WriteFailed(path: Path, message: String)

/** Rendering helpers for lock-file failures. */
object LockFileError:

  /** Render a lock-file failure into a concise user-facing line. */
  def render(error: LockFileError): String = error match
    case LockFileError.Missing(path) =>
      s"lock file $path is missing; run `binstaller lock --config <file>` first or omit --locked"
    case LockFileError.ReadFailed(path, message)   => s"lock read failed for $path: $message"
    case LockFileError.DecodeFailed(path, message) => s"lock decode failed for $path: $message"
    case LockFileError.WriteFailed(path, message)  => s"lock write failed for $path: $message"

/** Runtime options specific to the `lock` command. */
final case class LockOptions(outputPath: String)

/** Lock command option defaults. */
object LockOptions:
  /** Default lock file path shared by `lock` and `apply --locked`. */
  val defaultOutputPath: String = "binstaller.lock.json"

/** Serialized lock file tied to one profile and manifest fingerprint. */
final case class LockFile(
    schemaVersion: Int,
    profileName: String,
    manifestFingerprint: String,
    tools: Vector[LockFileTool]
)

/** Serialized lock metadata for one resolved tool. */
final case class LockFileTool(
    name: String,
    resolvedVersion: Option[String],
    versionProvenance: Option[UrlProvenance],
    downloadProvenance: UrlProvenance,
    sizeBytes: Option[Long],
    checksum: Option[LockFileChecksum],
    dynamicSource: Boolean
)

/** Serialized checksum metadata copied from the manifest or a typed discovery source. */
final case class LockFileChecksum(
    algorithm: String,
    value: String,
    source: String,
    discoveryUrl: Option[String],
    discoveryFile: Option[String],
    discoveryProvenance: Option[UrlProvenance]
)

/** Lock-file checksum constructors and summaries. */
object LockFileChecksum:

  /** Build a configured checksum entry for legacy call sites and tests. */
  def apply(algorithm: String, value: String): LockFileChecksum =
    LockFileChecksum(algorithm, value, "configured", None, None, None)

  /** Convert resolved checksum provenance into lock-file metadata. */
  def fromResolved(checksum: ResolvedChecksum): LockFileChecksum = checksum.source match
    case ResolvedChecksumSource.Configured =>
      LockFileChecksum(checksum.algorithm.value, checksum.value)
    case ResolvedChecksumSource.Discovered(url, file, provenance) => LockFileChecksum(
        checksum.algorithm.value,
        checksum.value,
        "discovered",
        Some(url),
        Some(file),
        Some(provenance)
      )

  /** Summarize checksum states across lock-file tools. */
  def summary(tools: Vector[LockFileTool]): String =
    val configured = tools.count(_.checksum.exists(_.source == "configured"))
    val discovered = tools.count(_.checksum.exists(_.source == "discovered"))
    val missing    = tools.count(_.checksum.isEmpty)
    s"configured $configured, discovered $discovered, missing $missing"

/** Lock-file JSON codecs and constructors. */
object LockFile:
  /** Current lock-file schema version. */
  val schemaVersion: Int = 1

  /** JSON codec for checksum metadata. */
  given ReadWriter[LockFileChecksum] = macroRW

  /** JSON codec for tool lock entries. */
  given ReadWriter[LockFileTool] = macroRW

  /** JSON codec for complete lock files. */
  given ReadWriter[LockFile] = macroRW

/** Boundary for atomically saving lock files. */
trait LockFileStore:
  /** Load a lock file. */
  def load(path: Path): Either[LockFileError, LockFile]

  /** Persist a lock file atomically where supported by the filesystem. */
  def save(path: Path, lockFile: LockFile): Either[LockFileError, Unit]

/** Lock-file storage constructors. */
object LockFileStore:
  /** NIO-backed lock-file storage. */
  def nio: LockFileStore = NioLockFileStore

private[core] object NioLockFileStore extends LockFileStore:

  def load(path: Path): Either[LockFileError, LockFile] =
    val normalized = path.toAbsolutePath.normalize()
    if !Files.exists(normalized) then Left(LockFileError.Missing(normalized))
    else
      Try(read[LockFile](Files.readString(normalized))) match
        case Success(lockFile)                  => Right(lockFile)
        case Failure(error: upickle.core.Abort) =>
          Left(LockFileError.DecodeFailed(normalized, error.getMessage))
        case Failure(error) => Left(LockFileError.ReadFailed(normalized, error.getMessage))

  def save(path: Path, lockFile: LockFile): Either[LockFileError, Unit] =
    val normalized = path.toAbsolutePath.normalize()
    val parent     = Option(normalized.getParent).getOrElse(Path.of("").toAbsolutePath.normalize())
    val tmp        = parent.resolve(s".${normalized.getFileName}.tmp-${UUID.randomUUID()}")
    Try:
      Files.createDirectories(parent)
      val _ = Files.writeString(
        tmp,
        write(lockFile, indent = 2),
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE
      )
      val _ = Files.move(
        tmp,
        normalized,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
    match
      case Success(_)     => Right(())
      case Failure(error) =>
        val _ = Files.deleteIfExists(tmp)
        Left(LockFileError.WriteFailed(normalized, error.getMessage))
