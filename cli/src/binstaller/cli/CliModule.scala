package binstaller.cli

import binstaller.core.BinaryInstallerService
import binstaller.core.DownloadProgressStatus
import binstaller.core.HttpTextClient
import binstaller.core.InstallerEvent
import binstaller.core.InstallerEventObserver
import binstaller.core.InstallerOptions
import binstaller.core.InstallerResult
import binstaller.core.InstallerRunStatus
import binstaller.core.ApplyConfirmation
import binstaller.core.LockedApplyMode
import binstaller.core.LockOptions
import binstaller.core.ResetState
import binstaller.core.RenderSafety
import binstaller.core.SudoCredentialError
import binstaller.core.SudoCredentialProvider
import binstaller.core.SudoCredentialRequest
import binstaller.core.SudoPassword
import binstaller.core.ToolSelection
import binstaller.core.VerboseOutput
import picocli.CommandLine
import picocli.CommandLine.IHelpSectionRenderer
import picocli.CommandLine.Option as CliOption
import picocli.CommandLine.Command
import picocli.CommandLine.Help
import picocli.CommandLine.Model.UsageMessageSpec
import picocli.CommandLine.ScopeType

import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap
import java.net.URI
import java.util.concurrent.Callable
import scala.collection.mutable.ArrayBuffer

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

private final class TerminalSudoCredentialProvider(err: PrintWriter) extends SudoCredentialProvider:

  def requestSudoPassword(
      request: SudoCredentialRequest
  ): Either[SudoCredentialError, SudoPassword] = Option(System.console()) match
    case None          => requestFromDevTty(request.operation)
    case Some(console) =>
      err.println(s"sudo password required for ${request.operation}")
      err.flush()
      passwordFromChars(Option(console.readPassword("sudo password: ")))

  private def requestFromDevTty(operation: String): Either[SudoCredentialError, SudoPassword] =
    val tty = Path.of("/dev/tty")
    if !Files.isReadable(tty) || !Files.isWritable(tty) then
      Left(SudoCredentialError.Unavailable(
        "sudo credentials required, but no interactive terminal is available"
      ))
    else
      try
        val input = FileInputStream(tty.toFile)
        try
          val output = FileOutputStream(tty.toFile)
          try readPasswordFromDevTty(operation, input, output)
          finally output.close()
        finally input.close()
      catch
        case _: Exception => Left(SudoCredentialError.Unavailable(
            "sudo credentials required, but terminal password input is unavailable"
          ))

  private def readPasswordFromDevTty(
      operation: String,
      input: FileInputStream,
      output: FileOutputStream
  ): Either[SudoCredentialError, SudoPassword] =
    if !setDevTtyEcho(enabled = false) then
      Left(SudoCredentialError.Unavailable(
        "sudo credentials required, but terminal password input is unavailable"
      ))
    else
      try
        output.write(
          s"sudo password required for $operation\nsudo password: ".getBytes(StandardCharsets.UTF_8)
        )
        output.flush()
        val bytes = readPasswordBytes(input)
        output.write('\n')
        output.flush()
        passwordFromBytes(bytes)
      finally
        val _ = setDevTtyEcho(enabled = true)

  private def readPasswordBytes(input: FileInputStream): Array[Byte] =
    val bytes = ArrayBuffer.empty[Byte]
    var done  = false
    while !done do
      input.read() match
        case -1                  => done = true
        case '\n' | '\r'         => done = true
        case value if value >= 0 => bytes += value.toByte
        case _                   => done = true
    bytes.toArray

  private def passwordFromBytes(bytes: Array[Byte]): Either[SudoCredentialError, SudoPassword] =
    try passwordFromChars(Some(String(bytes, StandardCharsets.UTF_8).toCharArray))
    finally java.util.Arrays.fill(bytes, 0.toByte)

  private def passwordFromChars(charsOption: Option[Array[Char]])
      : Either[SudoCredentialError, SudoPassword] = charsOption match
    case Some(chars) =>
      try
        val password = String(chars)
        if password.isEmpty then Left(SudoCredentialError.Canceled)
        else Right(SudoPassword.fromString(password))
      finally java.util.Arrays.fill(chars, '\u0000')
    case None => Left(SudoCredentialError.Canceled)

  private def setDevTtyEcho(enabled: Boolean): Boolean =
    val mode = if enabled then "echo" else "-echo"
    try
      val process = ProcessBuilder("sh", "-c", s"stty $mode < /dev/tty")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
      process.waitFor() == 0
    catch
      case _: Exception => false

private object RootHelpLogo:

  def install(commandLine: CommandLine): Unit =
    val sectionMap = LinkedHashMap[String, IHelpSectionRenderer](commandLine.getHelpSectionMap)
    sectionMap.put(UsageMessageSpec.SECTION_KEY_HEADER, render)
    val _ = commandLine.getCommandSpec.usageMessage.sectionMap(sectionMap)

  private val render: IHelpSectionRenderer = new IHelpSectionRenderer:
    override def render(help: Help): String =
      if help.commandSpec.name == "binstaller" then logo else help.header()

  private def logo: String =
    val lines = Vector(
      " _     _           _        _ _           ",
      "| |__ (_)_ __  ___| |_ __ _| | | ___ _ __ ",
      "| '_ \\| | '_ \\/ __| __/ _` | | |/ _ \\ '__|",
      "| |_) | | | | \\__ \\ || (_| | | |  __/ |   ",
      "|_.__/|_|_| |_|___/\\__\\__,_|_|_|\\___|_|   "
    )
    val colors = Vector(fansi.Color.Cyan, fansi.Color.Blue, fansi.Color.Magenta)
    val title  = fansi.Bold.On(fansi.Color.Green("binary installer"))
    lines.zipWithIndex
      .map((line, index) => colors(index % colors.size)(line).toString)
      .appended(s"  ${title.toString}")
      .mkString("", "\n", "\n\n")

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
private final class PlanCommand(
    root: BinstallerCommand,
    service: BinaryInstallerService,
    out: PrintWriter,
    err: PrintWriter
) extends SelectableCommand(root, out, err):
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
private final class ApplyCommand(
    root: BinstallerCommand,
    service: BinaryInstallerService,
    out: PrintWriter,
    err: PrintWriter
) extends SelectableCommand(root, out, err):
  private var applyConfirmation: ApplyConfirmation = ApplyConfirmation.Disabled
  private var lockedApply: LockedApplyMode         = LockedApplyMode.Disabled
  private var lockPath: String                     = LockOptions.defaultOutputPath

  @CliOption(
    names = Array("--yes"),
    description = Array("Confirm apply actions, including sudo symlinks.")
  )
  def setApplyConfirmation(value: Boolean): Unit =
    applyConfirmation = ApplyConfirmation.fromFlag(value)

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

  override def call(): Integer = executeWithOptions(
    _.copy(
      selection = selection,
      applyConfirmation = applyConfirmation,
      lockPath = lockPath,
      lockedApply = lockedApply
    ),
    options =>
      val eventRenderer = CliApplyEventRenderer(out)
      val result        = service.applyWithEvents(options, eventRenderer)
      eventRenderer.finish()
      result.copy(lines = CliApplyOutput.colorLines(result.lines) ++ eventRenderer.summaryLines)
  )

private final class CliApplyEventRenderer(
    out: PrintWriter
) extends InstallerEventObserver:
  private val width                                   = 30
  private var lastBuckets: Map[String, Int]           = Map.empty
  private var activeLineLength: Int                   = 0
  private var summary: Option[InstallerEvent.Summary] = None

  def onEvent(event: InstallerEvent): Unit = event match
    case progress: InstallerEvent.DownloadProgress => renderProgress(progress)
    case value: InstallerEvent.Summary             => summary = Some(value)
    case _                                         => ()

  def finish(): Unit = if activeLineLength > 0 then
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
