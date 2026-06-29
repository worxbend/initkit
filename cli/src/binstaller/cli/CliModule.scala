package binstaller.cli

import binstaller.core.BinaryInstallerService
import binstaller.core.DownloadProgressStatus
import binstaller.core.DryRunMode
import binstaller.core.HttpTextClient
import binstaller.core.InstallerEvent
import binstaller.core.InstallerEventObserver
import binstaller.core.InstallerOptions
import binstaller.core.InstallerResult
import binstaller.core.InstallerRunStatus
import binstaller.core.ApplyConfirmation
import binstaller.core.LockOptions
import binstaller.core.ResetState
import binstaller.core.RenderSafety
import binstaller.core.ToolSelection
import binstaller.core.VerboseOutput
import binstaller.tui.TuiMode
import binstaller.tui.TuiModule
import binstaller.tui.TuiRequest
import picocli.CommandLine
import picocli.CommandLine.Option as CliOption
import picocli.CommandLine.Command
import picocli.CommandLine.ScopeType

import java.io.PrintWriter
import java.net.URI
import java.util.concurrent.Callable

/** Picocli-backed command boundary for the `binstaller` process. */
object CliModule:
  /** Module path used by app and tests to identify the CLI layer. */
  def modulePath: Vector[String] = TuiModule.modulePath :+ "cli"

  /** Run the CLI with process stdout/stderr. */
  def run(args: Vector[String]): Int = run(
    args,
    PrintWriter(System.out, true),
    PrintWriter(System.err, true)
  )

  /** Run the CLI with injectable writers for tests or alternate launchers. */
  def run(args: Vector[String], out: PrintWriter, err: PrintWriter): Int =
    commandLine(BinaryInstallerService.resolving(HttpTextClient.jdk), out, err).execute(args*)

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
      subcommandLine(PlanCommand(root, service, out, err), out, err)
    )
    commandLine.addSubcommand(
      "apply",
      subcommandLine(ApplyCommand(root, service, out, err), out, err)
    )
    commandLine.addSubcommand(
      "versions",
      subcommandLine(VersionsCommand(root, service, out, err), out, err)
    )
    commandLine.addSubcommand(
      "lock",
      subcommandLine(LockCommand(root, service, out, err), out, err)
    )
    commandLine

  private def subcommandLine(
      command: Callable[Integer],
      out: PrintWriter,
      err: PrintWriter
  ): CommandLine =
    val commandLine = CommandLine(command)
    commandLine.setOut(out)
    commandLine.setErr(err)
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
    executeWithOptions(identity, action)

  protected def executeWithOptions(
      amend: InstallerOptions => InstallerOptions,
      action: InstallerOptions => InstallerResult
  ): Integer = root.installerOptions match
    case Right(options) => render(action(amend(options)))
    case Left(message)  =>
      err.println(message)
      Integer.valueOf(2)

  private def render(result: InstallerResult): Integer =
    result.lines.foreach(out.println)
    Integer.valueOf(result.exitCode)

private abstract class SelectableCommand(
    root: BinstallerCommand,
    out: PrintWriter,
    err: PrintWriter
) extends ConfiguredCommand(root, out, err):

  private var onlyTools: Vector[String]    = Vector.empty
  private var skippedTools: Vector[String] = Vector.empty

  @CliOption(
    names = Array("--only"),
    paramLabel = "TOOL",
    description = Array("Render only the named tool. May be repeated.")
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
private final class PlanCommand(
    root: BinstallerCommand,
    service: BinaryInstallerService,
    out: PrintWriter,
    err: PrintWriter
) extends SelectableCommand(root, out, err):
  private var tui: Boolean = false

  @CliOption(
    names = Array("--tui"),
    description = Array(
      "Open the explicit planning TUI entrypoint. Default plan output remains script-friendly."
    )
  )
  def setTui(value: Boolean): Unit = tui = value

  override def call(): Integer = executeWithOptions(
    _.copy(selection = selection, dryRun = DryRunMode.Enabled),
    options =>
      if tui then TuiModule.start(TuiRequest(TuiMode.Plan, options))
      else service.plan(options)
  )

@Command(
  name = "apply",
  mixinStandardHelpOptions = true,
  description = Array("Apply the binary installer plan.")
)
private final class ApplyCommand(
    root: BinstallerCommand,
    service: BinaryInstallerService,
    out: PrintWriter,
    err: PrintWriter
) extends SelectableCommand(root, out, err):
  private var dryRun: DryRunMode                   = DryRunMode.Disabled
  private var applyConfirmation: ApplyConfirmation = ApplyConfirmation.Disabled
  private var tui: Boolean                         = false

  @CliOption(
    names = Array("--dry-run"),
    description = Array("Render the apply plan without changing files.")
  )
  def setDryRun(value: Boolean): Unit = dryRun = DryRunMode.fromFlag(value)

  @CliOption(
    names = Array("--yes"),
    description = Array("Confirm non-dry-run apply actions, including sudo symlinks.")
  )
  def setApplyConfirmation(value: Boolean): Unit =
    applyConfirmation = ApplyConfirmation.fromFlag(value)

  @CliOption(
    names = Array("--tui"),
    description = Array(
      "Open the explicit apply TUI entrypoint. Default apply output remains script-friendly."
    )
  )
  def setTui(value: Boolean): Unit = tui = value

  override def call(): Integer = executeWithOptions(
    _.copy(selection = selection, dryRun = dryRun, applyConfirmation = applyConfirmation),
    options =>
      if tui then TuiModule.start(TuiRequest(TuiMode.Apply, options))
      else
        val eventRenderer =
          CliApplyEventRenderer(out, enabled = options.dryRun == DryRunMode.Disabled)
        val result = service.applyWithEvents(options, eventRenderer)
        eventRenderer.finish()
        result.copy(lines = CliApplyOutput.colorLines(result.lines) ++ eventRenderer.summaryLines)
  )

private final class CliApplyEventRenderer(
    out: PrintWriter,
    enabled: Boolean
) extends InstallerEventObserver:
  private val width                                   = 30
  private var lastBuckets: Map[String, Int]           = Map.empty
  private var activeLineLength: Int                   = 0
  private var summary: Option[InstallerEvent.Summary] = None

  def onEvent(event: InstallerEvent): Unit = event match
    case progress: InstallerEvent.DownloadProgress if enabled => renderProgress(progress)
    case value: InstallerEvent.Summary                        => summary = Some(value)
    case _                                                    => ()

  def finish(): Unit = if enabled && activeLineLength > 0 then
    out.print(s"\r${" " * activeLineLength}\r")
    out.flush()
    activeLineLength = 0

  def summaryLines: Vector[String] = summary match
    case Some(value) => CliApplyOutput.summary(value)
    case None        => Vector.empty

  private def renderProgress(progress: InstallerEvent.DownloadProgress): Unit =
    progress.status match
      case DownloadProgressStatus.Started =>
        lastBuckets = lastBuckets.updated(progress.url, -1)
        renderInPlace(renderActive(progress.url, downloadedBytes = 0L, progress.totalBytes))
      case DownloadProgressStatus.Advanced =>
        val bucket = progressBucket(progress.downloadedBytes, progress.totalBytes)
        if bucket != lastBuckets.getOrElse(progress.url, -1) then
          lastBuckets = lastBuckets.updated(progress.url, bucket)
          renderInPlace(renderActive(progress.url, progress.downloadedBytes, progress.totalBytes))
      case DownloadProgressStatus.Finished =>
        lastBuckets = lastBuckets.updated(progress.url, 100)
        renderCompleted(
          renderCompletedLine(progress.url, progress.downloadedBytes, progress.totalBytes)
        )

  private def progressBucket(downloadedBytes: Long, totalBytes: Option[Long]): Int =
    totalBytes.filter(_ > 0L) match
      case Some(total) => ((downloadedBytes.toDouble / total.toDouble) * 100.0).floor.toInt
      case None        => (downloadedBytes / (1024L * 1024L)).toInt

  private def renderActive(
      url: String,
      downloadedBytes: Long,
      totalBytes: Option[Long]
  ): ProgressLine =
    val label = fileName(url)
    val bar   = progressBar(downloadedBytes, totalBytes)
    val bytes = byteText(downloadedBytes, totalBytes)
    // Progress text is rendered in-place only for the CLI surface; URL-derived labels are scrubbed
    // before they reach the terminal row.
    val plain  = s"⬇ downloading $label ${bar.plain} $bytes"
    val styled = s"${fansi.Color.Cyan("⬇ downloading").toString} " +
      s"${fansi.Color.Yellow(label).toString} ${bar.styled} " +
      fansi.Color.Cyan(bytes).toString
    ProgressLine(plain, styled)

  private def renderCompletedLine(
      url: String,
      downloadedBytes: Long,
      totalBytes: Option[Long]
  ): ProgressLine =
    val label  = fileName(url)
    val bar    = progressBar(downloadedBytes, totalBytes)
    val bytes  = byteText(downloadedBytes, totalBytes)
    val plain  = s"✅ completed $label ${bar.plain} $bytes"
    val styled = fansi.Color.Green(s"✅ completed $label").toString +
      s" ${bar.styled} ${fansi.Color.Green(bytes).toString}"
    ProgressLine(plain, styled)

  private def renderInPlace(line: ProgressLine): Unit =
    val padding = " " * (activeLineLength - line.visibleLength).max(0)
    out.print(s"\r${line.styled}$padding")
    out.flush()
    activeLineLength = line.visibleLength

  private def renderCompleted(line: ProgressLine): Unit =
    val padding = " " * (activeLineLength - line.visibleLength).max(0)
    out.print(s"\r${line.styled}$padding\n")
    out.flush()
    activeLineLength = 0

  private def progressBar(downloadedBytes: Long, totalBytes: Option[Long]): ProgressLine =
    totalBytes.filter(_ > 0L) match
      case Some(total) =>
        val ratio  = (downloadedBytes.toDouble / total.toDouble).max(0.0).min(1.0)
        val filled = (ratio * width).round.toInt
        val pct    = (ratio * 100.0).round.toInt
        val empty  = width - filled
        val plain  = s"[${"█" * filled}${"░" * empty}] $pct%"
        val styled = s"[${barColor(ratio)("█" * filled).toString}" +
          s"${fansi.Color.Blue("░" * empty).toString}] ${percentColor(pct)(s"$pct%").toString}"
        ProgressLine(plain, styled)
      case None =>
        val plain = s"[${"█" * width}]"
        ProgressLine(plain, fansi.Color.Magenta(plain).toString)

  private def barColor(ratio: Double): fansi.Attrs =
    if ratio >= 1.0 then fansi.Color.Green
    else if ratio >= 0.7 then fansi.Color.Yellow
    else fansi.Color.Cyan

  private def percentColor(percent: Int): fansi.Attrs =
    if percent >= 100 then fansi.Color.Green
    else if percent >= 70 then fansi.Color.Yellow
    else fansi.Color.Cyan

  private def byteText(downloadedBytes: Long, totalBytes: Option[Long]): String = totalBytes match
    case Some(total) => s"${formatBytes(downloadedBytes)}/${formatBytes(total)}"
    case None        => formatBytes(downloadedBytes)

  private def formatBytes(bytes: Long): String =
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    if bytes >= gib then f"${bytes / gib}%.1f GiB"
    else if bytes >= mib then f"${bytes / mib}%.1f MiB"
    else if bytes >= kib then f"${bytes / kib}%.1f KiB"
    else s"$bytes B"

  private def fileName(url: String): String =
    val fallback = "download"
    scala.util.Try(URI.create(url).getPath)
      .toOption
      .flatMap(path => Option(path).map(_.split('/').toVector.filter(_.nonEmpty).lastOption))
      .flatten
      .map(RenderSafety.terminalLine(_))
      .getOrElse(fallback)

private final case class ProgressLine(plain: String, styled: String):
  def visibleLength: Int = plain.length

private object CliApplyOutput:

  def colorLines(lines: Vector[String]): Vector[String] = lines.map(colorLine)

  private def colorLine(line: String): String =
    if line.startsWith("installed ") then fansi.Color.Green(line).toString
    else if line.startsWith("failed ") then fansi.Color.Red(line).toString
    else line

  def summary(event: InstallerEvent.Summary): Vector[String] =
    val status =
      if event.status == InstallerRunStatus.Succeeded then
        fansi.Color.Green("🎉 apply completed successfully").toString
      else fansi.Color.Red("💥 apply finished with errors").toString
    Vector(
      "",
      fansi.Color.Magenta("✨ Summary").toString,
      s"  ${fansi.Color.Green(s"✅ installed: ${event.installed}").toString}",
      s"  ${fansi.Color.Red(s"❌ failed: ${event.failed}").toString}",
      s"  ${fansi.Color.Yellow(s"⏭ skipped: ${event.skipped}").toString}",
      s"  ${fansi.Color.Cyan(s"🚦 exit code: ${event.exitCode}").toString}",
      s"  $status"
    )

@Command(
  name = "versions",
  mixinStandardHelpOptions = true,
  description = Array("Resolve and print binary tool versions.")
)
private final class VersionsCommand(
    root: BinstallerCommand,
    service: BinaryInstallerService,
    out: PrintWriter,
    err: PrintWriter
) extends ConfiguredCommand(root, out, err):
  override def call(): Integer = execute(service.versions)

@Command(
  name = "lock",
  mixinStandardHelpOptions = true,
  description = Array("Resolve and write a JSON lock file without installing tools.")
)
private final class LockCommand(
    root: BinstallerCommand,
    service: BinaryInstallerService,
    out: PrintWriter,
    err: PrintWriter
) extends SelectableCommand(root, out, err):
  private var outputPath: String = "binstaller.lock.json"

  @CliOption(
    names = Array("--output"),
    paramLabel = "FILE",
    description = Array("Path to the JSON lock file to write.")
  )
  def setOutputPath(value: String): Unit = outputPath = value

  override def call(): Integer = executeWithOptions(
    _.copy(selection = selection, dryRun = DryRunMode.Enabled),
    options => service.lock(options, LockOptions(outputPath))
  )
