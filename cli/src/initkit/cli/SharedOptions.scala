package initkit.cli

import java.nio.file.{Files, Path, Paths}
import scala.util.Try

import picocli.CommandLine.Option as CliOption

final class SharedOptions:
  @CliOption(
    names = Array("--config"),
    paramLabel = "PATH",
    description = Array("YAML manifest path. Defaults to config.yaml.")
  )
  private var config: String = "config.yaml"

  @CliOption(
    names = Array("--state"),
    paramLabel = "PATH",
    description = Array("Read and write execution state in a separate JSON file.")
  )
  private var state: String = ""

  @CliOption(
    names = Array("--reset-state"),
    description = Array("Ignore and overwrite any existing execution state file.")
  )
  private var resetStateValue: Boolean = false

  def configFile: Either[String, Path] =
    normalizePath(config).flatMap { path =>
      if Files.isRegularFile(path) then Right(path)
      else Left(s"Config file not found: $path")
    }

  def stateFile: Either[String, Option[Path]] =
    if state.trim.isEmpty then Right(None)
    else normalizePath(state).map(Some(_))

  def resetState: Boolean =
    resetStateValue

  private def normalizePath(value: String): Either[String, Path] =
    Try(Paths.get(value).toAbsolutePath.normalize()).toEither.left.map { error =>
      s"Invalid path '$value': ${error.getMessage}"
    }
