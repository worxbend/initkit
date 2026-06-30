package binstaller.cli

import binstaller.core.BinaryInstallerService
import binstaller.core.ApplyParallelism
import binstaller.core.HttpTextClient
import binstaller.core.InstallerOptions
import binstaller.core.InstallerResult
import binstaller.core.LockedApplyMode
import binstaller.core.LockOptions
import binstaller.core.ResetState
import binstaller.core.ToolSelection
import binstaller.core.VerboseOutput
import picocli.CommandLine
import picocli.CommandLine.Option as CliOption
import picocli.CommandLine.Command
import picocli.CommandLine.ScopeType

import java.io.PrintWriter
import java.nio.file.Path
import java.util.concurrent.Callable

/** Picocli-backed command boundary for the `binstaller` process. */
object CliModule:
  /** Module path used by app and tests to identify the CLI layer. */
  def modulePath: Vector[String] = Vector("config", "core", "cli")

  /** Run the CLI with process stdout/stderr. */
  def run(args: Vector[String]): Int = run(
    args,
    PrintWriter(System.out, true),
    PrintWriter(System.err, true)
  )

  /** Run the CLI with injectable writers for tests or alternate launchers. */
  def run(args: Vector[String], out: PrintWriter, err: PrintWriter): Int =
    commandLine(productionService(err), out, err).execute(args*)

  /** Build the root command with an injectable core service. */
  def commandLine(
      service: BinaryInstallerService,
      out: PrintWriter,
      err: PrintWriter
  ): CommandLine =
    val root        = BinstallerCommand(out)
    val commandLine = CommandLine(root)
    commandLine.setOut(out)
    commandLine.setErr(err)
    commandLine.addSubcommand(
      "plan",
      subcommandLine(PlanCommand(root, service, out), out, err)
    )
    commandLine.addSubcommand(
      "apply",
      subcommandLine(ApplyCommand(root, service, out), out, err)
    )
    commandLine.addSubcommand(
      "versions",
      subcommandLine(VersionsCommand(root, service, out), out, err)
    )
    commandLine.addSubcommand(
      "lock",
      subcommandLine(LockCommand(root, service, out), out, err)
    )
    RootHelpLogo.install(commandLine)
    commandLine

  private def productionService(err: PrintWriter): BinaryInstallerService =
    BinaryInstallerService.resolving(HttpTextClient.jdk, TerminalSudoCredentialProvider(err))

  private def subcommandLine(
      command: Callable[Integer],
      out: PrintWriter,
      err: PrintWriter
  ): CommandLine =
    val commandLine = CommandLine(command)
    commandLine.setOut(out)
    commandLine.setErr(err)
    commandLine

private[cli] final case class GlobalOptions(
    configPath: Option[String],
    statePath: Option[String],
    resetState: ResetState,
    verboseOutput: VerboseOutput
)

private[cli] object GlobalOptions:

  def empty: GlobalOptions = GlobalOptions(
    configPath = None,
    statePath = None,
    resetState = ResetState.Disabled,
    verboseOutput = VerboseOutput.Disabled
  )

private[cli] object DefaultConfig:
  def path: String = Path.of("config.yaml").toAbsolutePath.normalize().toString

@Command(
  name = "binstaller",
  mixinStandardHelpOptions = true,
  sortOptions = false,
  synopsisSubcommandLabel = "COMMAND",
  description = Array("Inspect and apply binary installer manifests.")
)
private[cli] final class BinstallerCommand(out: PrintWriter) extends Callable[Integer]:
  private var globalOptions: GlobalOptions = GlobalOptions.empty

  @CliOption(
    names = Array("--config"),
    paramLabel = "FILE",
    scope = ScopeType.INHERIT,
    description = Array("YAML profile path. Default: cwd config.yaml.")
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

  def installerOptions: InstallerOptions = InstallerOptions(
    configPath = globalOptions.configPath.getOrElse(DefaultConfig.path),
    statePath = globalOptions.statePath,
    resetState = globalOptions.resetState,
    verboseOutput = globalOptions.verboseOutput
  )

  override def call(): Integer =
    out.println("binstaller - binary installer")
    out.println("Use --help to show commands.")
    Integer.valueOf(0)

private[cli] abstract class ConfiguredCommand(
    root: BinstallerCommand,
    out: PrintWriter
) extends Callable[Integer]:

  protected def execute(action: InstallerOptions => InstallerResult): Integer =
    executeWithOptions(identity, action)

  protected def executeRendered(
      action: InstallerOptions => InstallerResult,
      renderResult: InstallerResult => InstallerResult
  ): Integer = executeWithOptions(identity, action, renderResult)

  protected def executeWithOptions(
      amend: InstallerOptions => InstallerOptions,
      action: InstallerOptions => InstallerResult
  ): Integer = executeWithOptions(amend, action, identity)

  protected def executeWithOptions(
      amend: InstallerOptions => InstallerOptions,
      action: InstallerOptions => InstallerResult,
      renderResult: InstallerResult => InstallerResult
  ): Integer =
    val options = amend(root.installerOptions)
    render(renderResult(action(options)))

  private def render(result: InstallerResult): Integer =
    result.lines.foreach(out.println)
    Integer.valueOf(result.exitCode)

private[cli] abstract class SelectableCommand(
    root: BinstallerCommand,
    out: PrintWriter
) extends ConfiguredCommand(root, out):

  private var onlyTools: Vector[String]    = Vector.empty
  private var skippedTools: Vector[String] = Vector.empty

  @CliOption(
    names = Array("--only"),
    paramLabel = "TOOL",
    description = Array("Select only the named tool. May be repeated.")
  )
  def addOnlyTool(value: String): Unit = onlyTools = onlyTools :+ value

  @CliOption(
    names = Array("--skip"),
    paramLabel = "TOOL",
    description = Array("Omit the named tool. May be repeated.")
  )
  def addSkippedTool(value: String): Unit = skippedTools = skippedTools :+ value

  protected def selection: ToolSelection = ToolSelection(onlyTools, skippedTools)

@Command(
  name = "plan",
  mixinStandardHelpOptions = true,
  description = Array("Render the binary installer plan without changing files.")
)
private[cli] final class PlanCommand(
    root: BinstallerCommand,
    service: BinaryInstallerService,
    out: PrintWriter
) extends SelectableCommand(root, out):
  private var lockedApply: LockedApplyMode = LockedApplyMode.Disabled
  private var lockPath: String             = LockOptions.defaultOutputPath

  @CliOption(
    names = Array("--locked"),
    description = Array("Require a compatible JSON lock file before rendering.")
  )
  def setLockedApply(value: Boolean): Unit = lockedApply = LockedApplyMode.fromFlag(value)

  @CliOption(
    names = Array("--lock-file"),
    paramLabel = "FILE",
    description = Array("Path to the JSON lock file used by --locked.")
  )
  def setLockPath(value: String): Unit = lockPath = value

  override def call(): Integer = executeWithOptions(
    _.copy(
      selection = selection,
      lockPath = lockPath,
      lockedApply = lockedApply
    ),
    service.plan
  )

@Command(
  name = "apply",
  mixinStandardHelpOptions = true,
  description = Array("Apply the binary installer plan.")
)
private[cli] final class ApplyCommand(
    root: BinstallerCommand,
    service: BinaryInstallerService,
    out: PrintWriter
) extends SelectableCommand(root, out):
  private var lockedApply: LockedApplyMode       = LockedApplyMode.Disabled
  private var lockPath: String                   = LockOptions.defaultOutputPath
  private var applyParallelism: ApplyParallelism = ApplyParallelism.default

  @CliOption(
    names = Array("--locked"),
    description = Array("Require a compatible JSON lock file before applying.")
  )
  def setLockedApply(value: Boolean): Unit = lockedApply = LockedApplyMode.fromFlag(value)

  @CliOption(
    names = Array("--lock-file"),
    paramLabel = "FILE",
    description = Array("Path to the JSON lock file used by --locked.")
  )
  def setLockPath(value: String): Unit = lockPath = value

  @CliOption(
    names = Array("--parallelism"),
    paramLabel = "N",
    description = Array("Number of tools to download and stage concurrently. Default: 4.")
  )
  def setParallelism(value: Int): Unit = ApplyParallelism.fromInt(value) match
    case Right(parallelism) => applyParallelism = parallelism
    case Left(message)      => throw IllegalArgumentException(message)

  override def call(): Integer = executeWithOptions(
    _.copy(
      selection = selection,
      lockPath = lockPath,
      lockedApply = lockedApply,
      applyParallelism = applyParallelism
    ),
    options =>
      val eventRenderer = CliApplyEventRenderer(out)
      val result        = service.applyWithEvents(options, eventRenderer)
      eventRenderer.finish()
      result.copy(lines = CliApplyOutput.colorLines(result.lines) ++ eventRenderer.summaryLines)
  )

@Command(
  name = "versions",
  mixinStandardHelpOptions = true,
  description = Array("Resolve and print binary tool versions.")
)
private[cli] final class VersionsCommand(
    root: BinstallerCommand,
    service: BinaryInstallerService,
    out: PrintWriter
) extends ConfiguredCommand(root, out):

  override def call(): Integer = executeRendered(
    service.versions,
    result =>
      if result.exitCode == 0 then result.copy(lines = CliVersionsOutput.colorLines(result.lines))
      else result
  )

@Command(
  name = "lock",
  mixinStandardHelpOptions = true,
  description = Array("Resolve and write a JSON lock file without installing tools.")
)
private[cli] final class LockCommand(
    root: BinstallerCommand,
    service: BinaryInstallerService,
    out: PrintWriter
) extends SelectableCommand(root, out):
  private var outputPath: String = LockOptions.defaultOutputPath

  @CliOption(
    names = Array("--output"),
    paramLabel = "FILE",
    description = Array("Path to the JSON lock file to write.")
  )
  def setOutputPath(value: String): Unit = outputPath = value

  override def call(): Integer = executeWithOptions(
    _.copy(selection = selection),
    options => service.lock(options, LockOptions(outputPath))
  )
