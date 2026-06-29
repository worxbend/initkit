package binstaller.cli

import binstaller.core.BinaryInstallerService
import binstaller.core.CoreModule
import binstaller.core.InstallerOptions
import binstaller.core.InstallerResult
import binstaller.core.ResetState
import binstaller.core.VerboseOutput
import picocli.CommandLine
import picocli.CommandLine.Option as CliOption
import picocli.CommandLine.Command
import picocli.CommandLine.ScopeType

import java.io.PrintWriter
import java.util.concurrent.Callable

object CliModule:
  def modulePath: Vector[String] = CoreModule.modulePath :+ "cli"

  def run(args: Vector[String]): Int = run(
    args,
    PrintWriter(System.out, true),
    PrintWriter(System.err, true)
  )

  def run(args: Vector[String], out: PrintWriter, err: PrintWriter): Int =
    commandLine(BinaryInstallerService.placeholder, out, err).execute(args*)

  def commandLine(
      service: BinaryInstallerService,
      out: PrintWriter,
      err: PrintWriter
  ): CommandLine =
    val root        = BinstallerCommand(out)
    val commandLine = CommandLine(root)
    commandLine.setOut(out)
    commandLine.setErr(err)
    commandLine.addSubcommand("plan", PlanCommand(root, service, out, err))
    commandLine.addSubcommand("apply", ApplyCommand(root, service, out, err))
    commandLine.addSubcommand("versions", VersionsCommand(root, service, out, err))
    commandLine

private final case class GlobalOptions(
    configPath: Option[String],
    statePath: Option[String],
    resetState: ResetState,
    verboseOutput: VerboseOutput
)

private object GlobalOptions:

  def empty: GlobalOptions = GlobalOptions(
    configPath = None,
    statePath = None,
    resetState = ResetState.Disabled,
    verboseOutput = VerboseOutput.Disabled
  )

@Command(
  name = "binstaller",
  mixinStandardHelpOptions = true,
  sortOptions = false,
  synopsisSubcommandLabel = "COMMAND",
  description = Array("Inspect and apply binary installer manifests.")
)
private final class BinstallerCommand(out: PrintWriter) extends Callable[Integer]:
  private var globalOptions: GlobalOptions = GlobalOptions.empty

  @CliOption(
    names = Array("--config"),
    paramLabel = "FILE",
    scope = ScopeType.INHERIT,
    description = Array("Path to the binstaller YAML profile.")
  )
  def setConfigPath(value: String): Unit =
    globalOptions = globalOptions.copy(configPath = Some(value))

  @CliOption(
    names = Array("--state"),
    paramLabel = "FILE",
    scope = ScopeType.INHERIT,
    description = Array("Path to the execution state file.")
  )
  def setStatePath(value: String): Unit =
    globalOptions = globalOptions.copy(statePath = Some(value))

  @CliOption(
    names = Array("--reset-state"),
    scope = ScopeType.INHERIT,
    description = Array("Ignore any saved execution state.")
  )
  def setResetState(value: Boolean): Unit =
    globalOptions = globalOptions.copy(resetState = ResetState.fromFlag(value))

  @CliOption(
    names = Array("--verbose"),
    scope = ScopeType.INHERIT,
    description = Array("Show additional command diagnostics.")
  )
  def setVerboseOutput(value: Boolean): Unit =
    globalOptions = globalOptions.copy(verboseOutput = VerboseOutput.fromFlag(value))

  def installerOptions: Either[String, InstallerOptions] = globalOptions.configPath match
    case Some(configPath) => Right(
        InstallerOptions(
          configPath = configPath,
          statePath = globalOptions.statePath,
          resetState = globalOptions.resetState,
          verboseOutput = globalOptions.verboseOutput
        )
      )
    case None => Left("Missing required option: --config")

  override def call(): Integer =
    out.println("binstaller - binary installer")
    out.println("Use --help to show commands.")
    Integer.valueOf(0)

private abstract class ConfiguredCommand(
    root: BinstallerCommand,
    out: PrintWriter,
    err: PrintWriter
) extends Callable[Integer]:

  protected def execute(action: InstallerOptions => InstallerResult): Integer =
    root.installerOptions match
      case Right(options) => render(action(options))
      case Left(message)  =>
        err.println(message)
        Integer.valueOf(2)

  private def render(result: InstallerResult): Integer =
    result.lines.foreach(out.println)
    Integer.valueOf(result.exitCode)

@Command(
  name = "plan",
  description = Array("Render the binary installer plan without changing files.")
)
private final class PlanCommand(
    root: BinstallerCommand,
    service: BinaryInstallerService,
    out: PrintWriter,
    err: PrintWriter
) extends ConfiguredCommand(root, out, err):
  override def call(): Integer = execute(service.plan)

@Command(
  name = "apply",
  description = Array("Apply the binary installer plan.")
)
private final class ApplyCommand(
    root: BinstallerCommand,
    service: BinaryInstallerService,
    out: PrintWriter,
    err: PrintWriter
) extends ConfiguredCommand(root, out, err):
  override def call(): Integer = execute(service.apply)

@Command(
  name = "versions",
  description = Array("Resolve and print binary tool versions.")
)
private final class VersionsCommand(
    root: BinstallerCommand,
    service: BinaryInstallerService,
    out: PrintWriter,
    err: PrintWriter
) extends ConfiguredCommand(root, out, err):
  override def call(): Integer = execute(service.versions)
