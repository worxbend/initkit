package initkit.tui

import java.nio.file.{Path, Paths}
import java.time.Clock
import scala.util.control.NonFatal

import initkit.config.Manifest
import initkit.core.*
import initkit.host.HostFacts

final case class TuiLaunchModel(
    viewModel: TuiViewModel,
    context: TuiExecutionContext
)

final case class TuiExecutionContext(
    manifest: Manifest,
    hostFacts: HostFacts,
    statePath: Path,
    stateFile: TuiStateFileInput,
    state: ExecutionState,
    sourceSetup: SourceSetupPlan,
    configPath: Path,
    clock: Clock
):
  def withState(nextState: ExecutionState): TuiExecutionContext =
    copy(state = nextState)

enum TuiExecutionAction:
  case PreviewSelected, RunSelected, RunAllMatching, Resume

  def label: String =
    this match
      case PreviewSelected => "preview selected"
      case RunSelected     => "run selected"
      case RunAllMatching  => "run all matching"
      case Resume          => "resume"

  def mode: ExecutionRunMode =
    this match
      case PreviewSelected => ExecutionRunMode.DryRun
      case RunSelected     => ExecutionRunMode.Apply
      case RunAllMatching  => ExecutionRunMode.Apply
      case Resume          => ExecutionRunMode.Apply

final case class TuiExecutionReport(
    action: TuiExecutionAction,
    selectedNames: Vector[String],
    result: ExecutionEngineResult,
    logLines: Vector[String]
)

final class TuiExecutionRunner(
    commandExecutorFactory: ExecutionRunMode => CommandExecutor = TuiExecutionRunner.defaultCommandExecutor,
    stateWriter: ExecutionStateWriter = ExecutionStateWriter.live,
    sourceSetupFiles: SourceSetupFiles = SourceSetupFiles.Jvm
):
  def run(
      context: TuiExecutionContext,
      model: TuiViewModel,
      action: TuiExecutionAction
  ): Either[String, TuiExecutionReport] =
    val selectedNames = selectedNamesFor(model, action)
    val policy = ExecutionPolicy.fromManifest(context.manifest.spec.policy, Some(action.mode))
    val commandLog = CommandLogBuffer()
    val commandExecutor = LoggingCommandExecutor(commandExecutorFactory(action.mode), commandLog)
    val installer = new PackageManagerInstallers(
      commandExecutor = commandExecutor,
      aptUpdateBeforeInstall = context.sourceSetup.aptUpdateBeforeInstall,
      hostFacts = context.hostFacts
    )
    val sourceSetupExecutor = SourceSetupExecutor(commandExecutor, sourceSetupFiles)
    val request = ExecutionEngineRequest(
      manifest = context.manifest,
      selection = PlanSelectionRequest.fromFilters(
        only = selectedNames,
        skip = Vector.empty,
        completed = ExecutionState.completedNames(context.state)
      ),
      hostFacts = context.hostFacts,
      state = context.state,
      statePath = context.statePath,
      policy = policy
    )

    ExecutionWithSourceSetup
      .run(request, installer, context.sourceSetup, sourceSetupExecutor, stateWriter, context.clock)
      .left
      .map(_.message)
      .map: result =>
        TuiExecutionReport(
          action = action,
          selectedNames = selectedNames,
          result = result,
          logLines = TuiExecutionLog.lines(context, action, selectedNames, result, commandLog.lines)
        )

  private def selectedNamesFor(model: TuiViewModel, action: TuiExecutionAction): Vector[String] =
    action match
      case TuiExecutionAction.PreviewSelected | TuiExecutionAction.RunSelected =>
        model.selectedEntryNames
      case TuiExecutionAction.RunAllMatching | TuiExecutionAction.Resume =>
        model.rows.collect { case row if row.selectable => row.name }

private[tui] object TuiExecutionRunner:
  def defaultCommandExecutor(mode: ExecutionRunMode): CommandExecutor =
    val commandMode =
      if mode == ExecutionRunMode.DryRun then CommandRunMode.DryRun
      else CommandRunMode.Apply

    new ProcessCommandRunner(SudoStrategy.Passthrough, mode = commandMode, clock = Clock.systemUTC())

private[tui] final class LoggingCommandExecutor(
    delegate: CommandExecutor,
    log: CommandLogBuffer
) extends CommandExecutor:
  override def run(spec: CommandSpec): CommandResult =
    log.append(s"command: ${TuiExecutionLog.commandLine(spec)}")
    val result = delegate.run(spec)
    log.append(s"command result: ${TuiExecutionLog.describeTermination(result.termination)}")
    appendStream("stdout", result.stdout)
    appendStream("stderr", result.stderr)
    result

  private def appendStream(name: String, value: String): Unit =
    value.linesIterator.filter(_.nonEmpty).foreach(line => log.append(s"$name: $line"))

private[tui] final class CommandLogBuffer private (private var current: Vector[String]):
  def append(line: String): Unit =
    current = current :+ CommandRedactor.redactText(line)

  def lines: Vector[String] =
    current

private[tui] object CommandLogBuffer:
  def apply(): CommandLogBuffer =
    new CommandLogBuffer(Vector.empty)

private[tui] object TuiExecutionLog:
  def lines(
      context: TuiExecutionContext,
      action: TuiExecutionAction,
      selectedNames: Vector[String],
      result: ExecutionEngineResult,
      commandLines: Vector[String]
  ): Vector[String] =
    Vector(
      s"action: ${action.label}",
      s"mode: ${action.mode.toString.toLowerCase}",
      s"selected: ${if selectedNames.isEmpty then "<none>" else selectedNames.mkString(", ")}"
    ) ++ sourceSetupLines(context.sourceSetup) ++ commandLines ++ eventLines(context, result.events) ++ summaryLines(result)

  def commandLine(spec: CommandSpec): String =
    val command = spec.redacted
    val base =
      command.invocation match
        case RedactedCommandInvocation.Direct(argv) =>
          argv.mkString(" ")
        case RedactedCommandInvocation.Shell(commandText, shell) =>
          (shell :+ commandText).mkString(" ")

    val sudo = if command.sudo == SudoMode.Required then "sudo " else ""
    val input = command.stdinFile.map(path => s" < $path").getOrElse("")
    val cwd = command.cwd.map(path => s" cwd=$path").getOrElse("")
    s"$sudo$base$input$cwd"

  def describeTermination(termination: CommandTermination): String =
    termination match
      case CommandTermination.Exited(code) =>
        s"exit code $code"
      case CommandTermination.TimedOut(after) =>
        s"timed out after $after"
      case CommandTermination.Cancelled(message) =>
        s"cancelled: $message"
      case CommandTermination.FailedToStart(message) =>
        s"failed to start: $message"

  private def sourceSetupLines(sourceSetup: SourceSetupPlan): Vector[String] =
    val operations =
      sourceSetup.operations.map(operation => s"source setup: ${sourceSetupOperation(operation)}")
    val skipped =
      sourceSetup.skippedSections.map(section => s"source setup skip: ${section.section}: ${section.reason}")
    val aptUpdate =
      Option
        .when(sourceSetup.aptUpdateBeforeInstall)("source setup: apt package installs will run apt-get update")
        .toVector

    operations ++ skipped ++ aptUpdate

  private def sourceSetupOperation(operation: SourceSetupOperation): String =
    operation match
      case SourceSetupOperation.RunCommand(label, command) =>
        s"command $label: ${commandLine(command)}"
      case SourceSetupOperation.WriteFile(label, path, _, mode, sudo) =>
        val prefix = if sudo then "sudo " else ""
        val modeText = mode.map(value => s" mode=$value").getOrElse("")
        s"write $label: $prefix$path$modeText"

  private def eventLines(context: TuiExecutionContext, events: Vector[PlanEvent]): Vector[String] =
    events.flatMap:
      case PlanEvent.Scheduled(operation, _) =>
        Vector(s"scheduled: ${operation.name} (${operation.kind})")
      case PlanEvent.Started(operation, _) =>
        Vector(s"started: ${operation.name}")
      case PlanEvent.Skipped(operation, reasons, _) =>
        Vector(s"skipped: ${operation.name}: ${reasons.mkString("; ")}")
      case PlanEvent.Completed(operation, details, _) =>
        Vector(s"completed: ${operation.name}") ++ details.map(detail => s"  $detail")
      case PlanEvent.Failed(operation, failure, _) =>
        Vector(s"failed: ${operation.name}: ${failure.message}")
      case PlanEvent.Interrupted(operation, interrupt, _) =>
        interruptLines(context, operation, interrupt)
      case PlanEvent.DryRunOperation(operation, data, _) =>
        Vector(s"dry-run: ${operation.name} (${operation.kind})") ++ data.actions.map(action => s"  ${actionLine(action)}")

  private def interruptLines(
      context: TuiExecutionContext,
      operation: PlanOperationSummary,
      interrupt: PlanInterrupt
  ): Vector[String] =
    val statePath = interrupt.statePath.map(Paths.get(_)).getOrElse(context.statePath)

    Vector(
      s"interrupted: ${operation.name}: ${interrupt.reason}",
      s"state written: $statePath",
      s"resume: initkit tui --config ${context.configPath} --state $statePath"
    ) ++ interrupt.resumeFrom.map(value => s"resume from: $value").toVector ++
      interrupt.instructions.map(instruction => s"instruction: $instruction")

  private def actionLine(action: DryRunAction): String =
    action match
      case DryRunAction.Command(argv, shell, sudo, workingDirectory, stdinFile) =>
        val prefix = if sudo then "sudo " else ""
        val shellPrefix = shell.map(value => s"$value ").getOrElse("")
        val cwd = workingDirectory.map(value => s" cwd=$value").getOrElse("")
        val input = stdinFile.map(value => s" < $value").getOrElse("")
        s"command $prefix$shellPrefix${argv.mkString(" ")}$input$cwd"
      case DryRunAction.FileWrite(path, mode, description) =>
        val modeText = mode.map(value => s" mode=$value").getOrElse("")
        s"write $path$modeText ($description)"
      case DryRunAction.StateWrite(path, resumeFrom) =>
        val resume = resumeFrom.map(value => s" resumeFrom=$value").getOrElse("")
        s"state write $path$resume"
      case DryRunAction.Message(text) =>
        s"note $text"

  private def summaryLines(result: ExecutionEngineResult): Vector[String] =
    val counts = result.result.counts
    Vector(
      s"summary: completed=${counts.completed} skipped=${counts.skipped} failed=${counts.failed} " +
        s"interrupted=${counts.interrupted} remaining=${counts.remaining} exitCode=${result.exitCode}"
    )
