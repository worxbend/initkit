package binstaller.core

import binstaller.config.ValidationError

import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

private[core] object ResolvedPathValidator:

  def stateFile(value: String, path: String): Vector[ValidationError] =
    filename(value, path, "state filename")

  def downloadFilename(value: String, path: String): Vector[ValidationError] =
    filename(value, path, "download filename")

  def archivePath(value: String, path: String, label: String): Vector[ValidationError] =
    relativePath(value, path, label, allowCurrentDirectory = true)

  def installRelativePath(value: String, path: String, label: String): Vector[ValidationError] =
    relativePath(value, path, label, allowCurrentDirectory = false)

  def externalPath(value: String, path: String, label: String): Vector[ValidationError] =
    pathSyntax(value, path, label)

  def symlinkTarget(
      value: String,
      path: String,
      installDir: String
  ): Vector[ValidationError] =
    val syntaxErrors = pathSyntax(value, path, "symlink target")
    if syntaxErrors.nonEmpty then syntaxErrors
    else
      Try:
        val installRoot = Path.of(installDir).toAbsolutePath.normalize()
        val rawTarget   = Path.of(value)
        val target      =
          if rawTarget.isAbsolute then rawTarget.toAbsolutePath.normalize()
          else installRoot.resolve(rawTarget).normalize()
        installRoot -> target
      match
        case Failure(error) =>
          Vector(ValidationError(path, s"invalid symlink target: ${error.getMessage}"))
        case Success((installRoot, target)) if !target.startsWith(installRoot) =>
          Vector(ValidationError(path, "symlink target must resolve inside installDir"))
        case Success(_) => Vector.empty

  def pathSyntax(value: String, path: String, label: String): Vector[ValidationError] =
    if value.trim.isEmpty then Vector(ValidationError(path, s"$label must not be empty"))
    else if value.exists(Character.isISOControl) then
      Vector(ValidationError(path, s"$label must not contain control characters"))
    else if value.contains('\\') then
      Vector(ValidationError(path, s"$label must not contain backslashes"))
    else if value.matches("^[A-Za-z]:.*") then
      Vector(ValidationError(path, s"$label must not be drive-prefixed"))
    else if hasTraversalSegment(value) then
      Vector(ValidationError(path, s"$label must not contain traversal segments"))
    else Vector.empty

  private def filename(value: String, path: String, label: String): Vector[ValidationError] =
    val syntaxErrors = pathSyntax(value, path, label)
    if syntaxErrors.nonEmpty then syntaxErrors
    else if value.contains('/') then
      Vector(ValidationError(path, s"$label must be a filename, not a path"))
    else if value == "." || value == ".." then
      Vector(ValidationError(path, s"$label must not be a traversal segment"))
    else
      Try(Path.of(value)) match
        case Failure(error) => Vector(ValidationError(path, s"invalid $label: ${error.getMessage}"))
        case Success(file) if file.isAbsolute || file.getNameCount != 1 =>
          Vector(ValidationError(path, s"$label must be a filename in the current directory"))
        case Success(_) => Vector.empty

  private def relativePath(
      value: String,
      path: String,
      label: String,
      allowCurrentDirectory: Boolean
  ): Vector[ValidationError] =
    val syntaxErrors = pathSyntax(value, path, label)
    if syntaxErrors.nonEmpty then syntaxErrors
    else if value == "." && allowCurrentDirectory then Vector.empty
    else if value == "." then Vector(ValidationError(path, s"$label must not be current directory"))
    else
      Try(Path.of(value)) match
        case Failure(error) => Vector(ValidationError(path, s"invalid $label: ${error.getMessage}"))
        case Success(relative) if relative.isAbsolute =>
          Vector(ValidationError(path, s"$label must be relative"))
        case Success(_) => Vector.empty

  private def hasTraversalSegment(value: String): Boolean =
    Try(Path.of(value).iterator().asScala.exists(_.toString == "..")).getOrElse(false)
