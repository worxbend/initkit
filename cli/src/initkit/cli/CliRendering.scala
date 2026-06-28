package initkit.cli

import java.io.PrintWriter
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.util.Try

import fansi.Color
import initkit.config.Manifest
import initkit.core.*
import initkit.host.HostFacts

enum CliColorMode:
  case Auto, Always, Never

object CliColorMode:
  def parse(value: String): Either[String, CliColorMode] =
    value.trim.toLowerCase match
      case "auto"   => Right(CliColorMode.Auto)
      case "always" => Right(CliColorMode.Always)
      case "never"  => Right(CliColorMode.Never)
      case other     => Left(s"Invalid --color value '$other': expected auto, always, or never")

final case class CliColorSettings(
    enabled: Boolean
)

object CliColorSettings:
  def resolve(
      mode: CliColorMode,
      noColor: Boolean,
      noColorEnvironment: Boolean,
      stdoutIsTerminal: Boolean
  ): CliColorSettings =
    val enabled =
      mode match
        case CliColorMode.Always => !noColor
        case CliColorMode.Never  => false
        case CliColorMode.Auto   => !noColor && !noColorEnvironment && stdoutIsTerminal

    CliColorSettings(enabled)

final class CliRenderer(settings: CliColorSettings):
  def section(value: String): String =
    styled(value, Color.Cyan.apply)

  def ok(value: String): String =
    styled(value, Color.Green.apply)

  def skipped(value: String): String =
    styled(value, Color.Yellow.apply)

  def failed(value: String): String =
    styled(value, Color.Red.apply)

  def muted(value: String): String =
    styled(value, Color.DarkGray.apply)

  def label(value: String): String =
    styled(value, Color.Blue.apply)

  private def styled(value: String, style: String => fansi.Str): String =
    if settings.enabled then style(value).render
    else value

final case class ApplyReport(
    manifest: Manifest,
    hostFacts: HostFacts,
    statePath: Path,
    state: ExecutionState,
    sourceSetup: SourceSetupPlan,
    selection: PlanSelection,
    engineResult: ExecutionEngineResult,
    dryRun: Boolean,
    resumed: Boolean,
    configPath: Path
)

object ApplyReporter:
  def print(report: ApplyReport, out: PrintWriter, renderer: CliRenderer): Unit =
    printHeader(report, out, renderer)
    printHostFacts(report.hostFacts, out, renderer)
    printState(report, out, renderer)
    printSourceSetup(report.sourceSetup, out, renderer)
    printSelection(report.selection, out, renderer)
    printOperations(report.engineResult.events, out, renderer)
    printSummary(report.engineResult.result, report.engineResult.exitCode, out, renderer)

  def debugLines(report: ApplyReport): Vector[String] =
    Vector(
      s"run_id=${Instant.now()}",
      s"config_path=${report.configPath}",
      s"manifest_name=${report.manifest.metadata.name.getOrElse("<unnamed>")}",
      s"host_family=${report.hostFacts.os.family}",
      s"host_distribution=${report.hostFacts.os.distribution.getOrElse("<unknown>")}",
      s"host_version=${report.hostFacts.os.version.getOrElse("<unknown>")}",
      s"host_codename=${report.hostFacts.os.codename.getOrElse("<unknown>")}",
      s"host_architecture=${report.hostFacts.architecture}",
      s"state_path=${report.statePath}",
      s"resumed=${report.resumed}",
      s"dry_run=${report.dryRun}",
      s"selected=${report.selection.runnable.map(_.entry.name.getOrElse("<unnamed>")).mkString(",")}",
      s"skipped=${report.selection.skipped.map(skippedDebug).mkString("|")}",
      s"source_operations=${report.sourceSetup.operations.size}",
      s"events=${report.engineResult.events.map(eventDebug).mkString("|")}",
      s"summary=${summaryDebug(report.engineResult.result)} exit_code=${report.engineResult.exitCode}"
    ).map(CommandRedactor.redactText)

  private def printHeader(report: ApplyReport, out: PrintWriter, renderer: CliRenderer): Unit =
    out.println(renderer.section("initkit apply"))
    out.println(s"${renderer.label("manifest")}: ${report.manifest.metadata.name.getOrElse("<unnamed>")}")
    out.println(s"${renderer.label("config")}: ${report.configPath}")
    out.println(s"${renderer.label("mode")}: ${if report.dryRun then "dry-run" else "apply"}")

  private def printHostFacts(hostFacts: HostFacts, out: PrintWriter, renderer: CliRenderer): Unit =
    val os = hostFacts.os
    val parts = Vector(
      Some(s"family=${os.family}"),
      os.distribution.map(value => s"distribution=$value"),
      os.version.map(value => s"version=$value"),
      os.codename.map(value => s"codename=$value"),
      Some(s"architecture=${hostFacts.architecture}")
    ).flatten

    out.println(s"${renderer.label("host")}: ${parts.mkString(", ")}")

  private def printState(report: ApplyReport, out: PrintWriter, renderer: CliRenderer): Unit =
    val resumePoint = ExecutionState.resumePoint(report.state)
    val resumed = if report.resumed then "yes" else "no"
    out.println(s"${renderer.label("state")}: ${report.statePath}")
    out.println(s"${renderer.label("resumed")}: $resumed")
    out.println(s"${renderer.label("next")}: ${resumePoint.nextPlanEntry.getOrElse("<none>")}")

  private def printSourceSetup(plan: SourceSetupPlan, out: PrintWriter, renderer: CliRenderer): Unit =
    out.println()
    out.println(renderer.section("Source setup"))
    if plan.operations.isEmpty && plan.skippedSections.isEmpty then out.println("  no source setup operations")
    else
      plan.operations.foreach(operation => out.println(s"  ${operationLine(operation, renderer)}"))
      plan.skippedSections.foreach(section =>
        out.println(s"  ${renderer.skipped("skip")} ${section.section}: ${section.reason}")
      )
      if plan.aptUpdateBeforeInstall then
        out.println(s"  ${renderer.muted("note")} apt package installs will run apt-get update before packages")

  private def printSelection(selection: PlanSelection, out: PrintWriter, renderer: CliRenderer): Unit =
    out.println()
    out.println(renderer.section("Selected entries"))
    if selection.runnable.isEmpty then out.println("  <none>")
    else selection.runnable.foreach: entry =>
      out.println(s"  ${renderer.ok("run")} ${entry.entry.name.getOrElse("<unnamed>")} (${entry.entry.kind.getOrElse("<unknown>")})")

    out.println()
    out.println(renderer.section("Skipped entries"))
    if selection.skipped.isEmpty then out.println("  <none>")
    else selection.skipped.foreach: entry =>
      val name = entry.entry.name.getOrElse("<unnamed>")
      val kind = entry.entry.kind.getOrElse("<unknown>")
      out.println(s"  ${renderer.skipped("skip")} $name ($kind): ${entry.userFacingReasons.mkString("; ")}")

  private def printOperations(events: Vector[PlanEvent], out: PrintWriter, renderer: CliRenderer): Unit =
    val dryRunEvents = events.collect { case PlanEvent.DryRunOperation(_, data, _) => data }

    out.println()
    out.println(renderer.section("Operations"))
    if dryRunEvents.isEmpty then out.println("  no dry-run operations")
    else dryRunEvents.foreach(data => printDryRunData(data, out, renderer))

  private def printDryRunData(data: DryRunOperationData, out: PrintWriter, renderer: CliRenderer): Unit =
    out.println(s"  ${renderer.ok("plan")} ${data.operation.name} (${data.operation.kind})")
    if data.actions.isEmpty then out.println("    no operations")
    else data.actions.foreach(action => out.println(s"    ${actionLine(action, renderer)}"))

  private def printSummary(
      result: PlanResult,
      exitCode: Int,
      out: PrintWriter,
      renderer: CliRenderer
  ): Unit =
    val counts = result.counts
    val status =
      if result.failed.nonEmpty then renderer.failed("failed")
      else if result.interrupted.nonEmpty then renderer.skipped("interrupted")
      else renderer.ok("ok")

    out.println()
    out.println(renderer.section("Summary"))
    out.println(
      s"  status=$status completed=${counts.completed} skipped=${counts.skipped} " +
        s"failed=${counts.failed} interrupted=${counts.interrupted} remaining=${counts.remaining} exitCode=$exitCode"
    )

  private def operationLine(operation: SourceSetupOperation, renderer: CliRenderer): String =
    operation match
      case SourceSetupOperation.RunCommand(label, command) =>
        s"${renderer.ok("command")} $label: ${describeCommand(command.redacted)}"
      case SourceSetupOperation.WriteFile(label, path, _, mode, sudo) =>
        val prefix = if sudo then "sudo " else ""
        val modeText = mode.map(value => s" mode=$value").getOrElse("")
        s"${renderer.ok("write")} $label: ${prefix}${path}$modeText"

  private def actionLine(action: DryRunAction, renderer: CliRenderer): String =
    action match
      case DryRunAction.Command(argv, shell, sudo, workingDirectory, stdinFile) =>
        val prefix = if sudo then "sudo " else ""
        val shellPrefix = shell.map(value => s"$value ").getOrElse("")
        val cwd = workingDirectory.map(value => s" cwd=$value").getOrElse("")
        val input = stdinFile.map(value => s" < $value").getOrElse("")
        s"${renderer.ok("command")} $prefix$shellPrefix${argv.mkString(" ")}$input$cwd"
      case DryRunAction.FileWrite(path, mode, description) =>
        val modeText = mode.map(value => s" mode=$value").getOrElse("")
        s"${renderer.ok("write")} $path$modeText ($description)"
      case DryRunAction.StateWrite(path, resumeFrom) =>
        val resume = resumeFrom.map(value => s" resumeFrom=$value").getOrElse("")
        s"${renderer.ok("state")} write $path$resume"
      case DryRunAction.Message(text) =>
        s"${renderer.muted("note")} $text"

  private def describeCommand(command: RedactedCommandSpec): String =
    val base =
      command.invocation match
        case RedactedCommandInvocation.Direct(argv) =>
          argv.mkString(" ")
        case RedactedCommandInvocation.Shell(commandText, shell) =>
          (shell :+ commandText).mkString(" ")

    val sudo = if command.sudo == SudoMode.Required then "sudo " else ""
    val input = command.stdinFile.map(path => s" < $path").getOrElse("")
    s"$sudo$base$input"

  private def skippedDebug(entry: SkippedPlanEntry): String =
    s"${entry.entry.name.getOrElse("<unnamed>")}:${entry.userFacingReasons.mkString(";")}"

  private def eventDebug(event: PlanEvent): String =
    event match
      case PlanEvent.Scheduled(operation, _) =>
        s"scheduled:${operation.name}"
      case PlanEvent.Started(operation, _) =>
        s"started:${operation.name}"
      case PlanEvent.Skipped(operation, reasons, _) =>
        s"skipped:${operation.name}:${reasons.mkString(";")}"
      case PlanEvent.Completed(operation, details, _) =>
        s"completed:${operation.name}:${details.mkString(";")}"
      case PlanEvent.Failed(operation, failure, _) =>
        s"failed:${operation.name}:${failure.message}:exit=${failure.exitCode.getOrElse("")}"
      case PlanEvent.Interrupted(operation, interrupt, _) =>
        s"interrupted:${operation.name}:${interrupt.reason}:exit=${interrupt.exitCode}"
      case PlanEvent.DryRunOperation(operation, data, _) =>
        s"dry_run:${operation.name}:actions=${data.actions.map(actionDebug).mkString(";")}"

  private def actionDebug(action: DryRunAction): String =
    action match
      case DryRunAction.Command(argv, shell, sudo, workingDirectory, stdinFile) =>
        s"command argv=${argv.mkString(" ")} shell=${shell.getOrElse("")} sudo=$sudo cwd=${workingDirectory.getOrElse("")} stdin=${stdinFile.getOrElse("")}"
      case DryRunAction.FileWrite(path, mode, description) =>
        s"file path=$path mode=${mode.getOrElse("")} description=$description"
      case DryRunAction.StateWrite(path, resumeFrom) =>
        s"state path=$path resumeFrom=${resumeFrom.map(_.toString).getOrElse("")}"
      case DryRunAction.Message(text) =>
        s"message $text"

  private def summaryDebug(result: PlanResult): String =
    val counts = result.counts
    s"completed=${counts.completed} skipped=${counts.skipped} failed=${counts.failed} interrupted=${counts.interrupted} remaining=${counts.remaining}"

final class CliDebugLogger private (
    writer: Option[PrintWriter],
    closeWriter: Boolean
):
  def enabled: Boolean =
    writer.isDefined

  def line(value: String): Unit =
    writer.foreach: target =>
      target.println(s"${Instant.now()} ${CommandRedactor.redactText(value)}")
      target.flush()

  def lines(values: Iterable[String]): Unit =
    values.foreach(line)

  def close(): Unit =
    if closeWriter then writer.foreach(_.close())

object CliDebugLogger:
  def disabled: CliDebugLogger =
    new CliDebugLogger(None, closeWriter = false)

  def fromOptions(
      debug: Boolean,
      debugLog: Option[Path],
      err: PrintWriter
  ): Either[String, CliDebugLogger] =
    if !debug && debugLog.isEmpty then Right(disabled)
    else
      debugLog match
        case Some(path) =>
          openFile(path).map(writer => new CliDebugLogger(Some(writer), closeWriter = true))
        case None =>
          Right(new CliDebugLogger(Some(err), closeWriter = false))

  private def openFile(path: Path): Either[String, PrintWriter] =
    val normalized = path.toAbsolutePath.normalize()
    Try:
      val parent = normalized.getParent
      if parent != null then Files.createDirectories(parent)
      PrintWriter(Files.newBufferedWriter(normalized))
    .toEither
      .left
      .map(error => s"Could not open debug log '$normalized': ${error.getMessage}")
