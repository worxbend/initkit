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
  case WriteFailed(path: Path, message: String)

/** Rendering helpers for lock-file failures. */
object LockFileError:

  /** Render a lock-file failure into a concise user-facing line. */
  def render(error: LockFileError): String = error match
    case LockFileError.WriteFailed(path, message) => s"lock write failed for $path: $message"

/** Runtime options specific to the `lock` command. */
final case class LockOptions(outputPath: String)

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

/** Serialized checksum metadata copied from the manifest. */
final case class LockFileChecksum(algorithm: String, value: String)

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
  /** Persist a lock file atomically where supported by the filesystem. */
  def save(path: Path, lockFile: LockFile): Either[LockFileError, Unit]

/** Lock-file storage constructors. */
object LockFileStore:
  /** NIO-backed lock-file storage. */
  def nio: LockFileStore = NioLockFileStore

private object NioLockFileStore extends LockFileStore:

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
