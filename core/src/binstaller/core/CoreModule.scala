package binstaller.core

import binstaller.config.ConfigModule

object CoreModule:
  def modulePath: Vector[String] = Vector(ConfigModule.moduleName, "core")

enum ResetState:
  case Enabled, Disabled

object ResetState:
  def fromFlag(value: Boolean): ResetState = if value then Enabled else Disabled

enum VerboseOutput:
  case Enabled, Disabled

object VerboseOutput:
  def fromFlag(value: Boolean): VerboseOutput = if value then Enabled else Disabled

final case class InstallerOptions(
    configPath: String,
    statePath: Option[String],
    resetState: ResetState,
    verboseOutput: VerboseOutput
)

final case class InstallerResult(lines: Vector[String], exitCode: Int)

trait BinaryInstallerService:
  def plan(options: InstallerOptions): InstallerResult
  def apply(options: InstallerOptions): InstallerResult
  def versions(options: InstallerOptions): InstallerResult

object BinaryInstallerService:
  def placeholder: BinaryInstallerService = PlaceholderBinaryInstallerService

private object PlaceholderBinaryInstallerService extends BinaryInstallerService:
  def plan(options: InstallerOptions): InstallerResult = placeholderResult("plan", options)

  def apply(options: InstallerOptions): InstallerResult = placeholderResult("apply", options)

  def versions(options: InstallerOptions): InstallerResult = placeholderResult("versions", options)

  private def placeholderResult(command: String, options: InstallerOptions): InstallerResult =
    InstallerResult(
      Vector(s"binstaller $command placeholder for ${options.configPath}"),
      0
    )
