package initkit.config

import java.nio.file.Path

sealed trait ManifestLoadError:
  def path: Path
  def detail: String
  def message: String =
    s"$path: $detail"

object ManifestLoadError:
  final case class ReadFailure(path: Path, detail: String) extends ManifestLoadError
  final case class ParseFailure(path: Path, detail: String) extends ManifestLoadError
  final case class ShapeFailure(path: Path, detail: String) extends ManifestLoadError
  final case class ValidationFailure(path: Path, errors: Vector[ManifestValidationError]) extends ManifestLoadError:
    override def detail: String =
      if errors.isEmpty then "manifest validation failed"
      else errors.map(_.message).mkString("; ")
