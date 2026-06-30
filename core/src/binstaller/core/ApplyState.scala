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

/** Expected state-file failures during apply resume. */
enum ApplyStateError:
  case InvalidPath(path: String, message: String)
  case ReadFailed(path: Path, message: String)
  case WriteFailed(path: Path, message: String)
  case DecodeFailed(path: Path, message: String)

  case IncompatibleState(
      path: Path,
      expectedProfileName: String,
      actualProfileName: String,
      expectedFingerprint: String,
      actualFingerprint: String
  )

/** Rendering helpers for expected state-file failures. */
object ApplyStateError:

  /** Render a state failure into a concise user-facing line. */
  def render(error: ApplyStateError): String = error match
    case ApplyStateError.InvalidPath(path, message)  => s"state path '$path' is invalid: $message"
    case ApplyStateError.ReadFailed(path, message)   => s"state read failed for $path: $message"
    case ApplyStateError.WriteFailed(path, message)  => s"state write failed for $path: $message"
    case ApplyStateError.DecodeFailed(path, message) => s"state decode failed for $path: $message"
    case ApplyStateError.IncompatibleState(
          path,
          expectedProfileName,
          actualProfileName,
          expectedFingerprint,
          actualFingerprint
        ) =>
      s"state file $path does not match this manifest: expected profile '$expectedProfileName' " +
        s"with fingerprint $expectedFingerprint, found profile '$actualProfileName' with " +
        s"fingerprint $actualFingerprint; rerun with --reset-state to ignore saved state"

/** Serialized apply state tied to a profile name and manifest fingerprint. */
final case class ApplyState(
    schemaVersion: Int,
    profileName: String,
    manifestFingerprint: String,
    tools: Vector[ApplyStateTool]
)

/** Serialized status for a single tool in the apply state file. */
final case class ApplyStateTool(
    name: String,
    status: String,
    installDir: Option[String],
    message: Option[String],
    download: Option[UrlProvenance] = None
)

/** Apply-state JSON codecs and constructors. */
object ApplyState:
  /** Current apply-state schema version. */
  val schemaVersion: Int = 1

  /** JSON codec for individual tool state rows. */
  given ReadWriter[ApplyStateTool] = macroRW

  /** JSON codec for the complete apply state. */
  given ReadWriter[ApplyState] = macroRW

  /** Create an empty state file for a compatible profile and manifest fingerprint. */
  def empty(profileName: String, manifestFingerprint: String): ApplyState = ApplyState(
    schemaVersion,
    profileName,
    manifestFingerprint,
    Vector.empty
  )

/** Boundary for loading and atomically saving apply state. */
trait ApplyStateStore:
  /** Directory that owns relative state filenames. */
  def cwd: Path

  /** Load state if it exists. */
  def load(path: Path): Either[ApplyStateError, Option[ApplyState]]

  /** Persist state atomically where supported by the filesystem. */
  def save(path: Path, state: ApplyState): Either[ApplyStateError, Unit]

/** Apply-state storage constructors. */
object ApplyStateStore:
  /** State store rooted in the process current working directory. */
  def cwd: ApplyStateStore = nio(Path.of("").toAbsolutePath.normalize())

  /** NIO-backed state store rooted in an explicit directory. */
  def nio(directory: Path): ApplyStateStore =
    NioApplyStateStore(directory.toAbsolutePath.normalize())

private[core] object StatePathResolver:

  def resolve(rawPath: String, cwd: Path): Either[ApplyStateError.InvalidPath, Path] =
    val path = Path.of(rawPath)
    if rawPath.trim.isEmpty then invalid(rawPath, "state filename must not be empty")
    else if path.isAbsolute then invalid(rawPath, "absolute state paths are not allowed")
    else if path.getNameCount != 1 then
      invalid(rawPath, "state path must be a filename in the current working directory")
    else
      val resolved = cwd.toAbsolutePath.normalize().resolve(path).normalize()
      if resolved.getParent == cwd.toAbsolutePath.normalize() then Right(resolved)
      else invalid(rawPath, "state path must stay in the current working directory")

  private def invalid(
      path: String,
      message: String
  ): Either[ApplyStateError.InvalidPath, Path] = Left(ApplyStateError.InvalidPath(path, message))

private[core] final class NioApplyStateStore(val cwd: Path) extends ApplyStateStore:

  def load(path: Path): Either[ApplyStateError, Option[ApplyState]] =
    if !Files.exists(path) then Right(None)
    else
      Try(read[ApplyState](Files.readString(path))) match
        case Success(state)                     => Right(Some(state))
        case Failure(error: upickle.core.Abort) =>
          Left(ApplyStateError.DecodeFailed(path, error.getMessage))
        case Failure(error) => Left(ApplyStateError.ReadFailed(path, error.getMessage))

  def save(path: Path, state: ApplyState): Either[ApplyStateError, Unit] =
    val tmp = cwd.resolve(s".${path.getFileName}.tmp-${UUID.randomUUID()}")
    Try:
      Files.createDirectories(cwd)
      // Write to a unique temp file first; a partial state write must never look like a valid
      // resume checkpoint.
      val _ = Files.writeString(
        tmp,
        write(state, indent = 2),
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE
      )
      val _ = Files.move(
        tmp,
        path,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
    match
      case Success(_)     => Right(())
      case Failure(error) =>
        val _ = Files.deleteIfExists(tmp)
        Left(ApplyStateError.WriteFailed(path, error.getMessage))
