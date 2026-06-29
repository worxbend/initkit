package binstaller.core

import binstaller.config.ArchiveType
import binstaller.config.AllowSudoSymlinks
import binstaller.config.BinaryDistributionProfile
import binstaller.config.BinaryToolSpec
import binstaller.config.ChecksumSpec
import binstaller.config.ConfigModule
import binstaller.config.DownloadSpec
import binstaller.config.DynamicVersionKind
import binstaller.config.ExecutableMode
import binstaller.config.ExtractMapping
import binstaller.config.PlanEntry
import binstaller.config.SymlinkPrivilege
import binstaller.config.VersionResolverKind
import binstaller.config.VersionSource

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import ox.fork
import ox.supervised
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using
import upickle.default.*

/** Public module metadata for core planning and apply behavior. */
object CoreModule:
  /** Module path used by downstream modules to report dependency lineage. */
  def modulePath: Vector[String] = Vector(ConfigModule.moduleName, "core")

/** Whether apply should ignore a saved execution state file. */
enum ResetState:
  case Enabled, Disabled

/** Helpers for converting CLI flags into reset-state policy. */
object ResetState:
  /** Convert a boolean CLI flag into [[ResetState]]. */
  def fromFlag(value: Boolean): ResetState = if value then Enabled else Disabled

/** Whether command diagnostics and detailed operation lines should be emitted. */
enum VerboseOutput:
  case Enabled, Disabled

/** Helpers for converting CLI flags into verbose-output policy. */
object VerboseOutput:
  /** Convert a boolean CLI flag into [[VerboseOutput]]. */
  def fromFlag(value: Boolean): VerboseOutput = if value then Enabled else Disabled

/** Runtime options shared by plan, apply, versions, CLI, and TUI entrypoints. */
final case class InstallerOptions(
    configPath: String,
    statePath: Option[String],
    resetState: ResetState,
    verboseOutput: VerboseOutput,
    selection: ToolSelection = ToolSelection.all,
    dryRun: DryRunMode = DryRunMode.Disabled,
    applyConfirmation: ApplyConfirmation = ApplyConfirmation.Disabled
)

/** Rendered command result and process exit code. */
final case class InstallerResult(lines: Vector[String], exitCode: Int)

/** Tool selection requested by `--only` and `--skip`. */
final case class ToolSelection(only: Vector[String], skip: Vector[String])

/** Tool-selection constructors. */
object ToolSelection:
  /** Select every resolved tool. */
  def all: ToolSelection = ToolSelection(Vector.empty, Vector.empty)

/** Whether apply should render planned operations instead of changing files. */
enum DryRunMode:
  case Enabled, Disabled

/** Helpers for converting CLI flags into dry-run mode. */
object DryRunMode:
  /** Convert a boolean CLI flag into [[DryRunMode]]. */
  def fromFlag(value: Boolean): DryRunMode = if value then Enabled else Disabled

/** Whether the user confirmed non-dry-run apply side effects. */
enum ApplyConfirmation:
  case Enabled, Disabled

/** Helpers for converting CLI flags into apply confirmation. */
object ApplyConfirmation:
  /** Convert a boolean CLI flag into [[ApplyConfirmation]]. */
  def fromFlag(value: Boolean): ApplyConfirmation = if value then Enabled else Disabled

/** Boundary service consumed by CLI and TUI renderers. */
trait BinaryInstallerService:

  /** Render a script-friendly install plan without events. */
  def plan(options: InstallerOptions): InstallerResult =
    planWithEvents(options, InstallerEventObserver.none)

  /** Render a plan while emitting renderer-agnostic lifecycle events. */
  def planWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult

  /** Apply a plan without progress or lifecycle observers. */
  def apply(options: InstallerOptions): InstallerResult =
    applyWithEvents(options, InstallerEventObserver.none)

  /** Apply a plan while adapting download-only progress observers. */
  def applyWithProgress(
      options: InstallerOptions,
      progressObserver: BinaryDownloadProgressObserver
  ): InstallerResult = applyWithEvents(
    options,
    InstallerEventObserver.fromDownloadProgress(progressObserver)
  )

  /** Apply a plan while emitting renderer-agnostic lifecycle events. */
  def applyWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult

  /** Resolve and render the configured version sources. */
  def versions(options: InstallerOptions): InstallerResult

/** Constructors for production and test service implementations. */
object BinaryInstallerService:
  /** Minimal placeholder used by early wiring tests. */
  def placeholder: BinaryInstallerService = PlaceholderBinaryInstallerService

  /** Create the production resolving service with the default installer and cwd state store. */
  def resolving(httpTextClient: HttpTextClient): BinaryInstallerService =
    resolving(httpTextClient, DirectBinaryInstaller.default)

  /** Create a resolving service with an injected installer and the default cwd state store. */
  def resolving(
      httpTextClient: HttpTextClient,
      installer: DirectBinaryInstaller
  ): BinaryInstallerService = ResolvingBinaryInstallerService(
    httpTextClient,
    ResolutionOptions.fromEnvironment(),
    installer,
    ApplyStateStore.cwd
  )

  /** Create a resolving service with injectable installer and state storage boundaries. */
  def resolving(
      httpTextClient: HttpTextClient,
      installer: DirectBinaryInstaller,
      stateStore: ApplyStateStore
  ): BinaryInstallerService = ResolvingBinaryInstallerService(
    httpTextClient,
    ResolutionOptions.fromEnvironment(),
    installer,
    stateStore
  )

/** Values that must be redacted when raw runtime data reaches output surfaces. */
final case class SensitiveValueRedactions(values: Vector[String]):

  /** Replace every configured sensitive value with `<redacted>`. */
  def redact(value: String): String = values.foldLeft(value): (current, secret) =>
    current.replace(secret, "<redacted>")

/** Redaction policy constructors. */
object SensitiveValueRedactions:
  /** Redaction policy that does not hide any values. */
  val empty: SensitiveValueRedactions = SensitiveValueRedactions(Vector.empty)

  /** Derive sensitive values from environment-like variables by inspecting variable names. */
  def fromRuntimeVariables(values: Map[String, String]): SensitiveValueRedactions =
    val redactedValues = values.toVector.collect:
      case (name, value) if isSensitiveName(name) && value.length >= 4 => value
    SensitiveValueRedactions(redactedValues.distinct.sortBy(value => -value.length))

  private def isSensitiveName(name: String): Boolean =
    val upper = name.toUpperCase(java.util.Locale.ROOT)
    Vector(
      "TOKEN",
      "SECRET",
      "PASSWORD",
      "PASS",
      "API_KEY",
      "ACCESS_KEY",
      "PRIVATE_KEY",
      "CREDENTIAL",
      "AUTHORIZATION",
      "BEARER",
      "SESSION",
      "COOKIE"
    ).exists(upper.contains)

/** Display-safety helpers for terminal text, diagnostics, and env rendering. */
object RenderSafety:

  /** Redact sensitive values and replace terminal-control characters in a display string. */
  def display(
      value: String,
      redactions: SensitiveValueRedactions = SensitiveValueRedactions.empty
  ): String = scrubControls(redactions.redact(value))

  /** Apply [[display]] to each line. */
  def displayLines(
      lines: Vector[String],
      redactions: SensitiveValueRedactions = SensitiveValueRedactions.empty
  ): Vector[String] = lines.map(display(_, redactions))

  /** Render a single terminal row by removing embedded line breaks and tabs. */
  def terminalLine(
      value: String,
      redactions: SensitiveValueRedactions = SensitiveValueRedactions.empty
  ): String = display(value, redactions)
    .replace('\n', ' ')
    .replace('\t', ' ')

  /** Render an environment value for diagnostics without exposing likely secrets. */
  def envValue(name: String, value: String): String =
    val safeNames = Set("PATH", "HOME", "LANG", "LC_ALL", "SHELL", "TERM", "TMPDIR", "USER")
    if safeNames(name) || name.startsWith("LC_") then display(value)
    else "<redacted>"

  private def scrubControls(value: String): String = value.map:
    case '\n'                                => '\n'
    case '\t'                                => ' '
    case ch if ch < ' ' || ch == 0x7f.toChar => '?'
    case ch                                  => ch

/** Coarse lifecycle phases emitted by plan/apply execution. */
enum InstallerPhase:
  case Resolving
  case Planning
  case LoadingState
  case Downloading
  case VerifyingChecksum
  case Staging
  case ApplyingModes
  case ReplacingInstall
  case VerifyingExecutables
  case CreatingSymlinks
  case SavingState

/** Terminal result state for an individual tool. */
enum ToolResultStatus:
  case Completed, Failed

/** Aggregate apply run status. */
enum InstallerRunStatus:
  case Succeeded, Failed

/** Progress state for a single download event. */
enum DownloadProgressStatus:
  case Started, Advanced, Finished

/** Renderer-agnostic event contract shared by CLI progress and TUI execution views. */
enum InstallerEvent:
  case ResolvingStarted(configPath: String, elapsedTime: Duration)
  case PlanReady(toolCount: Int, stateFilePath: Option[String], elapsedTime: Duration)
  case ToolStarted(toolName: String, phase: InstallerPhase, elapsedTime: Duration)
  case ToolPhaseChanged(toolName: String, phase: InstallerPhase, elapsedTime: Duration)

  case DownloadProgress(
      toolName: String,
      url: String,
      downloadedBytes: Long,
      totalBytes: Option[Long],
      status: DownloadProgressStatus,
      elapsedTime: Duration
  )

  case LogLine(toolName: Option[String], line: String, elapsedTime: Duration)

  case ToolResult(
      toolName: String,
      status: ToolResultStatus,
      installDir: Option[String],
      failureSummary: Option[String],
      elapsedTime: Duration
  )

  case ToolSkipped(
      toolName: String,
      reason: String,
      stateFilePath: Option[String],
      elapsedTime: Duration
  )

  case Summary(
      status: InstallerRunStatus,
      installed: Int,
      failed: Int,
      skipped: Int,
      exitCode: Int,
      stateFilePath: Option[String],
      elapsedTime: Duration
  )

/** Observer for renderer-agnostic installer events. */
trait InstallerEventObserver:
  /** Receive one installer lifecycle event. */
  def onEvent(event: InstallerEvent): Unit

/** Installer event observer constructors and adapters. */
object InstallerEventObserver:
  /** Observer that ignores all installer events. */
  val none: InstallerEventObserver = _ => ()

  /** Adapt structured installer events to the legacy download-progress observer shape. */
  def fromDownloadProgress(
      progressObserver: BinaryDownloadProgressObserver
  ): InstallerEventObserver = event =>
    event match
      case InstallerEvent.DownloadProgress(_, url, downloadedBytes, totalBytes, status, _) =>
        status match
          case DownloadProgressStatus.Started =>
            progressObserver.onProgress(BinaryDownloadProgress.Started(url, totalBytes))
          case DownloadProgressStatus.Advanced => progressObserver.onProgress(
              BinaryDownloadProgress.Advanced(url, downloadedBytes, totalBytes)
            )
          case DownloadProgressStatus.Finished => progressObserver.onProgress(
              BinaryDownloadProgress.Finished(url, downloadedBytes, totalBytes)
            )
      case _ => ()

/** Structured process invocation. `argv` is passed directly, never through shell text. */
final case class CommandSpec(argv: Vector[String], cwd: Path, env: Map[String, String])

/** Captured stdout and stderr from a bounded process execution. */
final case class CommandOutput(stdout: String, stderr: String):
  /** Whether either stream contained captured output. */
  def hasOutput: Boolean = stdout.nonEmpty || stderr.nonEmpty

/** Command-output constructors. */
object CommandOutput:
  /** Empty command output. */
  val empty: CommandOutput = CommandOutput("", "")

/** Expected process execution failure with the structured command that produced it. */
final case class CommandExecutionError(
    spec: CommandSpec,
    message: String,
    exitCode: Option[Int],
    output: CommandOutput = CommandOutput.empty
)

private object CommandFailureDetails:

  def render(error: CommandExecutionError): String =
    render("command", error.spec, error.message, error.exitCode, error.output)

  def render(
      context: String,
      spec: CommandSpec,
      message: String,
      exitCode: Option[Int],
      output: CommandOutput
  ): String =
    val command = renderArgv(spec.argv)
    val details = Vector(s"  command: $command") ++
      Vector(s"  cwd: ${spec.cwd}") ++
      renderEnv(spec.env) ++
      exitCode.map(code => s"  exit code: $code").toVector ++
      renderOutputTail("stdout", output.stdout) ++
      renderOutputTail("stderr", output.stderr)
    (s"$context: $command: $message" +: details).mkString("\n")

  private def renderArgv(argv: Vector[String]): String = argv.map(shellQuote).mkString(" ")

  private def shellQuote(value: String): String = s"'${value.replace("'", "'\"'\"'")}'"

  private def renderEnv(env: Map[String, String]): Vector[String] =
    if env.isEmpty then Vector("  env: <empty>")
    else
      val rendered = env.toVector
        .sortBy((name, _) => name)
        .map((name, value) => s"$name=${renderEnvValue(name, value)}")
        .mkString(", ")
      Vector(s"  env: $rendered")

  private def renderEnvValue(name: String, value: String): String =
    RenderSafety.envValue(name, value)

  private def renderOutputTail(label: String, text: String): Vector[String] =
    val maxRenderedLines = 40
    val lines            = text.linesIterator.toVector.filterNot(_.isBlank)
    val omitted          =
      if lines.length > maxRenderedLines then
        Vector(s"  $label: ... omitted ${lines.length - maxRenderedLines} earlier line(s)")
      else Vector.empty
    omitted ++
      lines.takeRight(maxRenderedLines).map(line => s"  $label: ${RenderSafety.display(line)}")

/** Boundary for the few remaining process executions: sudo symlinks and tar.xz fallback. */
trait CommandExecutor:
  /** Run a structured command, returning expected process failures as data. */
  def run(spec: CommandSpec): Either[CommandExecutionError, Unit]

/** Process command executor constructors. */
object CommandExecutor:
  /** Process executor with the production timeout. */
  def process: CommandExecutor = processWithTimeout(Duration.ofMinutes(15))

  /** Process executor with an explicit timeout for tests and specialized runtimes. */
  def processWithTimeout(timeout: Duration): CommandExecutor = ProcessCommandExecutor(timeout)

/** Expected state-file failures during apply resume. */
enum ApplyStateError:
  case InvalidPath(path: String, message: String)
  case ReadFailed(path: Path, message: String)
  case WriteFailed(path: Path, message: String)
  case DecodeFailed(path: Path, message: String)

  case IncompatibleState(
      path: Path,
      expectedProfileName: String,
      actualProfileName: String,
      expectedFingerprint: String,
      actualFingerprint: String
  )

/** Serialized apply state tied to a profile name and manifest fingerprint. */
final case class ApplyState(
    schemaVersion: Int,
    profileName: String,
    manifestFingerprint: String,
    tools: Vector[ApplyStateTool]
)

/** Serialized status for a single tool in the apply state file. */
final case class ApplyStateTool(
    name: String,
    status: String,
    installDir: Option[String],
    message: Option[String],
    download: Option[UrlProvenance] = None
)

/** Apply-state JSON codecs and constructors. */
object ApplyState:
  /** Current apply-state schema version. */
  val schemaVersion: Int = 1

  /** JSON codec for individual tool state rows. */
  given ReadWriter[ApplyStateTool] = macroRW

  /** JSON codec for the complete apply state. */
  given ReadWriter[ApplyState] = macroRW

  /** Create an empty state file for a compatible profile and manifest fingerprint. */
  def empty(profileName: String, manifestFingerprint: String): ApplyState = ApplyState(
    schemaVersion,
    profileName,
    manifestFingerprint,
    Vector.empty
  )

/** Boundary for loading and atomically saving apply state. */
trait ApplyStateStore:
  /** Directory that owns relative state filenames. */
  def cwd: Path

  /** Load state if it exists. */
  def load(path: Path): Either[ApplyStateError, Option[ApplyState]]

  /** Persist state atomically where supported by the filesystem. */
  def save(path: Path, state: ApplyState): Either[ApplyStateError, Unit]

/** Apply-state storage constructors. */
object ApplyStateStore:
  /** State store rooted in the process current working directory. */
  def cwd: ApplyStateStore = nio(Path.of("").toAbsolutePath.normalize())

  /** NIO-backed state store rooted in an explicit directory. */
  def nio(directory: Path): ApplyStateStore =
    NioApplyStateStore(directory.toAbsolutePath.normalize())

/** Successful installation of a single tool. */
final case class ToolInstallSuccess(
    toolName: String,
    installDir: String,
    download: Option[UrlProvenance] = None
)

/** Terminal result emitted for state persistence and renderer summaries. */
enum TerminalToolResult:
  case Completed(toolName: String, installDir: String, download: Option[UrlProvenance] = None)
  case Failed(toolName: String, message: String)

/** Rendering helpers for terminal tool results. */
object TerminalToolResult:

  /** Render a terminal tool result with terminal safety and redaction applied. */
  def line(
      result: TerminalToolResult,
      redactions: SensitiveValueRedactions = SensitiveValueRedactions.empty
  ): String = result match
    case TerminalToolResult.Completed(toolName, installDir, _) =>
      RenderSafety.display(s"installed $toolName to $installDir", redactions)
    case TerminalToolResult.Failed(toolName, message) =>
      RenderSafety.display(s"failed $toolName: $message", redactions)

private final case class ObservedInstallResults(
    lines: Vector[String],
    results: Vector[TerminalToolResult],
    persistenceError: Option[String]
)

private[core] final case class InstallerEventContext(
    observer: InstallerEventObserver,
    startedAtNanos: Long
):
  def elapsedTime: Duration = Duration.ofNanos(System.nanoTime() - startedAtNanos)

  def emit(event: Duration => InstallerEvent): Unit = observer.onEvent(event(elapsedTime))

private[core] object InstallerEventContext:

  def start(observer: InstallerEventObserver): InstallerEventContext =
    InstallerEventContext(observer, System.nanoTime())

/** Expected failure before an apply run is allowed to perform side effects. */
enum ApplyPreflightError:
  case ConfirmationRequired
  case SudoSymlinkNotAllowed(toolName: String)
  case SudoSymlinkConfirmationRequired(toolName: String)

/** Expected failure while installing one tool. */
enum ToolInstallError:

  case DownloadFailed(
      toolName: String,
      url: String,
      message: String,
      provenance: Option[UrlProvenance] = None
  )

  case ChecksumMismatch(toolName: String, expected: String, actual: String)
  case StagingFailed(toolName: String, message: String)
  case ModeApplicationFailed(toolName: String, path: String, mode: String, message: String)
  case ReplacementFailed(toolName: String, message: String)
  case ArchiveExtractionFailed(toolName: String, message: String)
  case MissingExecutable(toolName: String, path: String)
  case SymlinkFailed(toolName: String, path: String, target: String, message: String)
  case SudoSymlinkNotAllowed(toolName: String)
  case SudoSymlinkConfirmationRequired(toolName: String)

/** POSIX executable mode parsed from a validated four-digit octal string. */
final case class ExecutableInstallMode(octal: String, numeric: Int):

  /** Convert numeric bits into POSIX file permissions. */
  def permissions: Set[PosixFilePermission] =
    val ownerRead    = permission(PosixFilePermission.OWNER_READ, 0x100)
    val ownerWrite   = permission(PosixFilePermission.OWNER_WRITE, 0x080)
    val ownerExecute = permission(PosixFilePermission.OWNER_EXECUTE, 0x040)
    val groupRead    = permission(PosixFilePermission.GROUP_READ, 0x020)
    val groupWrite   = permission(PosixFilePermission.GROUP_WRITE, 0x010)
    val groupExecute = permission(PosixFilePermission.GROUP_EXECUTE, 0x008)
    val otherRead    = permission(PosixFilePermission.OTHERS_READ, 0x004)
    val otherWrite   = permission(PosixFilePermission.OTHERS_WRITE, 0x002)
    val otherExecute = permission(PosixFilePermission.OTHERS_EXECUTE, 0x001)

    Vector(
      ownerRead,
      ownerWrite,
      ownerExecute,
      groupRead,
      groupWrite,
      groupExecute,
      otherRead,
      otherWrite,
      otherExecute
    ).flatten.toSet

  private def permission(
      permission: PosixFilePermission,
      bit: Int
  ): Option[PosixFilePermission] = if (numeric & bit) == bit then Some(permission) else None

/** Executable mode constructors. */
object ExecutableInstallMode:
  /** Default mode for installed executables. */
  val default: ExecutableInstallMode = fromOctal("0755")

  /** Convert an optional manifest mode into an executable install mode. */
  def fromConfig(mode: Option[ExecutableMode]): ExecutableInstallMode = mode match
    case Some(value) => fromOctal(value.value)
    case None        => default

  /** Parse a validated four-digit octal mode. */
  def fromOctal(value: String): ExecutableInstallMode =
    ExecutableInstallMode(value, Integer.parseInt(value, 8))

/** Request to apply a POSIX mode to an executable inside a staged install. */
final case class ExecutableModeRequest(path: String, mode: ExecutableInstallMode)

/** Staging directory paired with the final install directory it will replace. */
final case class StagedInstall(stagingDir: Path, installDir: Path)

/** Expected filesystem failure while staging or replacing an install. */
enum InstallFileSystemError:
  case StagingFailed(message: String)
  case ModeApplicationFailed(path: String, mode: String, message: String)
  case ReplacementFailed(message: String)

/** Filesystem boundary for staging artifacts before replacing a final install directory. */
trait InstallFileSystem:

  /** Stage a direct binary into a temporary install tree. */
  def stageDirectBinary(
      installDir: Path,
      createDirectories: Vector[String],
      executablePath: String,
      bytes: Array[Byte]
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall]

  /** Stage files selected from an archive into a temporary install tree. */
  def stageArchive(
      installDir: Path,
      createDirectories: Vector[String],
      archive: ResolvedArchive,
      bytes: Array[Byte],
      commandExecutor: CommandExecutor
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall]

  /** Apply requested executable modes inside the staged install tree. */
  def applyExecutableModes(
      stagedInstall: StagedInstall,
      executables: Vector[ExecutableModeRequest]
  ): Either[InstallFileSystemError.ModeApplicationFailed, Unit]

  /** Replace the final install directory with the staged install tree. */
  def replaceInstall(
      stagedInstall: StagedInstall
  ): Either[InstallFileSystemError.ReplacementFailed, Unit]

  /** Discard an unused staged install tree. */
  def discardStaged(stagedInstall: StagedInstall): Unit

/** Filesystem boundary constructors. */
object InstallFileSystem:
  /** NIO-backed filesystem implementation. */
  def nio: InstallFileSystem = NioInstallFileSystem

/** Installer that applies resolved direct-binary and archive-backed tools. */
final class DirectBinaryInstaller(
    downloadClient: BinaryDownloadClient,
    fileSystem: InstallFileSystem,
    commandExecutor: CommandExecutor = CommandExecutor.process
):

  /** Install every tool in a plan and render terminal result lines. */
  def installPlan(
      plan: ResolvedPlan,
      applyConfirmation: ApplyConfirmation = ApplyConfirmation.Disabled,
      verboseOutput: VerboseOutput = VerboseOutput.Disabled,
      progressObserver: BinaryDownloadProgressObserver = BinaryDownloadProgressObserver.none
  ): InstallerResult = installPlanWithObserver(
    plan,
    applyConfirmation,
    verboseOutput,
    _ => Right(()),
    InstallerEventContext.start(InstallerEventObserver.fromDownloadProgress(progressObserver))
  )

  private[core] def installPlanWithObserver(
      plan: ResolvedPlan,
      applyConfirmation: ApplyConfirmation,
      verboseOutput: VerboseOutput,
      terminalObserver: TerminalToolResult => Either[String, Unit],
      eventContext: InstallerEventContext
  ): InstallerResult = preflight(plan, applyConfirmation) match
    case Some(error) => InstallerResult(Vector(renderPreflightError(error)), 1)
    case None        =>
      val observed = installTools(
        plan.policy,
        plan.tools,
        plan.redactions,
        verboseOutput,
        terminalObserver,
        eventContext
      )
      val lines = observed.lines ++
        observed.persistenceError.map(message => s"state write failed: $message").toVector
      val exitCode =
        if observed.results.exists(_.isInstanceOf[TerminalToolResult.Failed]) ||
          observed.persistenceError.nonEmpty
        then 1
        else 0

      InstallerResult(lines, exitCode)

  private def preflight(
      plan: ResolvedPlan,
      applyConfirmation: ApplyConfirmation
  ): Option[ApplyPreflightError] = applyConfirmation match
    case ApplyConfirmation.Disabled
        if plan.policy.requireConfirmation == RequireConfirmation.Enabled =>
      Some(ApplyPreflightError.ConfirmationRequired)
    case _ => sudoPreflight(plan, applyConfirmation)

  private def sudoPreflight(
      plan: ResolvedPlan,
      applyConfirmation: ApplyConfirmation
  ): Option[ApplyPreflightError] = plan.tools
    .find(_.symlinks.exists(_.privilege == SymlinkPrivilege.Sudo))
    .flatMap: tool =>
      plan.policy.allowSudoSymlinks match
        case AllowSudoSymlinks.Disabled =>
          Some(ApplyPreflightError.SudoSymlinkNotAllowed(tool.name))
        case AllowSudoSymlinks.Enabled => applyConfirmation match
            case ApplyConfirmation.Enabled  => None
            case ApplyConfirmation.Disabled =>
              Some(ApplyPreflightError.SudoSymlinkConfirmationRequired(tool.name))

  private def installTools(
      policy: ResolvedPolicy,
      tools: Vector[ResolvedTool],
      redactions: SensitiveValueRedactions,
      verboseOutput: VerboseOutput,
      terminalObserver: TerminalToolResult => Either[String, Unit],
      eventContext: InstallerEventContext
  ): ObservedInstallResults = tools.headOption match
    case None       => ObservedInstallResults(Vector.empty, Vector.empty, None)
    case Some(tool) =>
      val verbose = verboseLines(tool, verboseOutput, redactions)
      verbose.foreach(line =>
        eventContext.emit(InstallerEvent.LogLine(Some(tool.name), line, _))
      )
      eventContext.emit(InstallerEvent.ToolStarted(tool.name, InstallerPhase.Downloading, _))
      val result   = installTool(policy, tool, eventContext, redactions)
      val terminal = terminalResult(result, redactions)
      eventContext.emit(toolResultEvent(terminal))
      val terminalLines = renderedTerminalLines(terminal, redactions)
      terminalObserver(terminal) match
        case Left(message) => ObservedInstallResults(
            verbose ++ terminalLines,
            Vector(terminal),
            Some(RenderSafety.display(message, redactions))
          )
        case Right(()) => result match
            case Left(_) if policy.continueOnError == ContinueOnError.Disabled =>
              ObservedInstallResults(
                verbose ++ terminalLines,
                Vector(terminal),
                None
              )
            case Left(_) =>
              val rest = installTools(
                policy,
                tools.tail,
                redactions,
                verboseOutput,
                terminalObserver,
                eventContext
              )
              rest.copy(
                lines = verbose ++
                  (terminalLines ++ rest.lines),
                results = terminal +: rest.results
              )
            case Right(_) =>
              val rest = installTools(
                policy,
                tools.tail,
                redactions,
                verboseOutput,
                terminalObserver,
                eventContext
              )
              rest.copy(
                lines = verbose ++
                  (terminalLines ++ rest.lines),
                results = terminal +: rest.results
              )

  private def renderedTerminalLines(
      terminal: TerminalToolResult,
      redactions: SensitiveValueRedactions
  ): Vector[String] = terminal match
    case TerminalToolResult.Completed(_, _, provenance) =>
      UrlProvenance.redirectDetailLines("download", provenance, redactions) :+
        TerminalToolResult.line(terminal, redactions)
    case TerminalToolResult.Failed(_, _) => Vector(TerminalToolResult.line(terminal, redactions))

  /** Install a single tool without sudo symlink support. Intended for focused tests and helpers. */
  def installTool(tool: ResolvedTool): Either[ToolInstallError, ToolInstallSuccess] =
    val policy = ResolvedPolicy(
      tool.installDir,
      None,
      AllowSudoSymlinks.Disabled,
      RequireConfirmation.Disabled,
      ContinueOnError.Disabled
    )
    if tool.symlinks.exists(_.privilege == SymlinkPrivilege.Sudo) then
      Left(ToolInstallError.SudoSymlinkNotAllowed(tool.name))
    else
      installTool(
        policy,
        tool,
        InstallerEventContext.start(InstallerEventObserver.none),
        SensitiveValueRedactions.empty
      )

  private def installTool(
      policy: ResolvedPolicy,
      tool: ResolvedTool,
      eventContext: InstallerEventContext,
      redactions: SensitiveValueRedactions
  ): Either[ToolInstallError, ToolInstallSuccess] =
    installWithoutPreflight(policy, tool, eventContext, redactions)

  private def terminalResult(
      result: Either[ToolInstallError, ToolInstallSuccess],
      redactions: SensitiveValueRedactions
  ): TerminalToolResult = result match
    case Right(success) =>
      TerminalToolResult.Completed(success.toolName, success.installDir, success.download)
    case Left(error) => TerminalToolResult.Failed(
        toolName(error),
        renderInstallError(error, redactions)
      )

  private def toolResultEvent(
      result: TerminalToolResult
  )(elapsedTime: Duration): InstallerEvent = result match
    case TerminalToolResult.Completed(toolName, installDir, _) => InstallerEvent.ToolResult(
        toolName,
        ToolResultStatus.Completed,
        Some(installDir),
        None,
        elapsedTime
      )
    case TerminalToolResult.Failed(toolName, message) => InstallerEvent.ToolResult(
        toolName,
        ToolResultStatus.Failed,
        None,
        Some(rootCauseSummary(message)),
        elapsedTime
      )

  private def rootCauseSummary(message: String): String =
    message.linesIterator.nextOption.getOrElse(message)

  private def verboseLines(
      tool: ResolvedTool,
      verboseOutput: VerboseOutput,
      redactions: SensitiveValueRedactions
  ): Vector[String] = verboseOutput match
    case VerboseOutput.Disabled => Vector.empty
    case VerboseOutput.Enabled  =>
      val downloadLine   = s"verbose ${tool.name}: download ${tool.download.url}"
      val extractionLine = tool.download.archive match
        case Some(archive) => Some(
            s"verbose ${tool.name}: extract ${archive.original.archiveType.value} ${tool.download.filename}"
          )
        case None => None
      RenderSafety.displayLines(Vector(downloadLine) ++ extractionLine.toVector, redactions)

  private def installWithoutPreflight(
      policy: ResolvedPolicy,
      tool: ResolvedTool,
      eventContext: InstallerEventContext,
      redactions: SensitiveValueRedactions
  ): Either[ToolInstallError, ToolInstallSuccess] =
    installDownloadedBinaryOrArchive(policy, tool, eventContext, redactions)

  private def installDownloadedBinaryOrArchive(
      policy: ResolvedPolicy,
      tool: ResolvedTool,
      eventContext: InstallerEventContext,
      redactions: SensitiveValueRedactions
  ): Either[ToolInstallError, ToolInstallSuccess] =
    for
      downloadResult <- download(tool, eventContext, redactions)
      bytes = downloadResult.bytes
      // Integrity is checked before staging/replacement so a bad artifact cannot overwrite a
      // previously working install.
      _ <-
        withPhase(tool, InstallerPhase.VerifyingChecksum, eventContext)(verifyChecksum(tool, bytes))
      staged <- withPhase(tool, InstallerPhase.Staging, eventContext)(stage(tool, bytes))
      _      <- withPhase(tool, InstallerPhase.VerifyingExecutables, eventContext)(
        verifyStagedExecutables(tool, staged)
      )
      _ <- withPhase(tool, InstallerPhase.ApplyingModes, eventContext)(applyModes(tool, staged))
      _ <- withPhase(tool, InstallerPhase.ReplacingInstall, eventContext)(replace(tool, staged))
      _ <- withPhase(tool, InstallerPhase.VerifyingExecutables, eventContext)(
        verifyExecutables(tool)
      )
      _ <- withPhase(tool, InstallerPhase.CreatingSymlinks, eventContext)(
        createSymlinks(policy, tool)
      )
    yield ToolInstallSuccess(tool.name, tool.installDir, Some(downloadResult.provenance))

  private def download(
      tool: ResolvedTool,
      eventContext: InstallerEventContext,
      redactions: SensitiveValueRedactions
  ): Either[ToolInstallError, BinaryDownloadResult] = downloadClient.downloadWithProvenance(
    tool.download.url,
    downloadProgressObserver(tool, eventContext, redactions)
  ).left.map: error =>
    ToolInstallError.DownloadFailed(
      tool.name,
      RenderSafety.display(error.url, redactions),
      RenderSafety.display(error.message, redactions),
      error.provenance
    )

  private def downloadProgressObserver(
      tool: ResolvedTool,
      eventContext: InstallerEventContext,
      redactions: SensitiveValueRedactions
  ): BinaryDownloadProgressObserver = new BinaryDownloadProgressObserver:
    def onProgress(progress: BinaryDownloadProgress): Unit = progress match
      case BinaryDownloadProgress.Started(url, totalBytes) =>
        eventContext.emit(InstallerEvent.DownloadProgress(
          tool.name,
          RenderSafety.display(url, redactions),
          0L,
          totalBytes,
          DownloadProgressStatus.Started,
          _
        ))
      case BinaryDownloadProgress.Advanced(url, downloadedBytes, totalBytes) =>
        eventContext.emit(InstallerEvent.DownloadProgress(
          tool.name,
          RenderSafety.display(url, redactions),
          downloadedBytes,
          totalBytes,
          DownloadProgressStatus.Advanced,
          _
        ))
      case BinaryDownloadProgress.Finished(url, downloadedBytes, totalBytes) =>
        eventContext.emit(InstallerEvent.DownloadProgress(
          tool.name,
          RenderSafety.display(url, redactions),
          downloadedBytes,
          totalBytes,
          DownloadProgressStatus.Finished,
          _
        ))

  private def withPhase[A](
      tool: ResolvedTool,
      phase: InstallerPhase,
      eventContext: InstallerEventContext
  )(result: => Either[ToolInstallError, A]): Either[ToolInstallError, A] =
    eventContext.emit(InstallerEvent.ToolPhaseChanged(tool.name, phase, _))
    result

  private def verifyChecksum(
      tool: ResolvedTool,
      bytes: Array[Byte]
  ): Either[ToolInstallError, Unit] = tool.download.checksum match
    case None           => Right(())
    case Some(checksum) =>
      val actual = Sha256.digest(bytes)
      if actual.equalsIgnoreCase(checksum.value) then Right(())
      else Left(ToolInstallError.ChecksumMismatch(tool.name, checksum.value, actual))

  private def stage(
      tool: ResolvedTool,
      bytes: Array[Byte]
  ): Either[ToolInstallError, StagedInstall] = tool.download.archive match
    case Some(archive) => fileSystem
        .stageArchive(
          Path.of(tool.installDir),
          tool.createDirectories,
          archive,
          bytes,
          commandExecutor
        )
        .left
        .map(error => ToolInstallError.ArchiveExtractionFailed(tool.name, error.message))
    case None => tool.executables.headOption match
        case None                  => Left(ToolInstallError.MissingExecutable(tool.name, "<none>"))
        case Some(firstExecutable) => fileSystem
            .stageDirectBinary(
              Path.of(tool.installDir),
              tool.createDirectories,
              firstExecutable.path,
              bytes
            )
            .left
            .map(error => ToolInstallError.StagingFailed(tool.name, error.message))

  private def applyModes(
      tool: ResolvedTool,
      stagedInstall: StagedInstall
  ): Either[ToolInstallError, Unit] =
    val modes = tool.executables.map: executable =>
      ExecutableModeRequest(executable.path, ExecutableInstallMode.fromConfig(executable.mode))

    fileSystem.applyExecutableModes(stagedInstall, modes).left.map: error =>
      ToolInstallError.ModeApplicationFailed(tool.name, error.path, error.mode, error.message)

  private def replace(
      tool: ResolvedTool,
      stagedInstall: StagedInstall
  ): Either[ToolInstallError, Unit] = fileSystem.replaceInstall(stagedInstall).left.map: error =>
    ToolInstallError.ReplacementFailed(tool.name, error.message)

  private def verifyExecutables(tool: ResolvedTool): Either[ToolInstallError, Unit] =
    tool.executables
      .map: executable =>
        resolveInsideInstall(tool, executable.path).flatMap: path =>
          if Files.isRegularFile(path) then Right(())
          else Left(ToolInstallError.MissingExecutable(tool.name, executable.path))
      .collectFirst:
        case Left(error) => error
    match
      case Some(error) => Left(error)
      case None        => Right(())

  private def verifyStagedExecutables(
      tool: ResolvedTool,
      stagedInstall: StagedInstall
  ): Either[ToolInstallError, Unit] = tool.executables
    .map: executable =>
      resolveInsideStaging(tool, stagedInstall, executable.path).flatMap: path =>
        if Files.isRegularFile(path) then Right(())
        else Left(ToolInstallError.MissingExecutable(tool.name, executable.path))
    .collectFirst:
      case Left(error) => error
  match
    case Some(error) => Left(error)
    case None        => Right(())

  private def resolveInsideStaging(
      tool: ResolvedTool,
      stagedInstall: StagedInstall,
      relative: String
  ): Either[ToolInstallError, Path] =
    val input      = Path.of(relative)
    val stagingDir = stagedInstall.stagingDir.toAbsolutePath.normalize()
    if input.isAbsolute then
      Left(ToolInstallError.StagingFailed(tool.name, s"path must be relative: $relative"))
    else
      val resolved = stagingDir.resolve(input).normalize()
      if resolved.startsWith(stagingDir) then Right(resolved)
      else Left(ToolInstallError.StagingFailed(tool.name, s"path escapes installDir: $relative"))

  private def createSymlinks(
      policy: ResolvedPolicy,
      tool: ResolvedTool
  ): Either[ToolInstallError, Unit] =
    val writes = tool.symlinks.map: symlink =>
      symlink.privilege match
        case SymlinkPrivilege.User => createLocalSymlink(tool, symlink)
        case SymlinkPrivilege.Sudo => createSudoSymlink(policy, tool, symlink)
    writes.collectFirst:
      case Left(error) => error
    match
      case Some(error) => Left(error)
      case None        => Right(())

  private def createLocalSymlink(
      tool: ResolvedTool,
      symlink: ResolvedSymlink
  ): Either[ToolInstallError, Unit] =
    for
      path   <- resolveInsideInstall(tool, symlink.path)
      target <- resolveSymlinkTarget(tool, symlink)
      _      <- Try:
        Option(path.getParent).foreach(parent => Files.createDirectories(parent))
        val _ = Files.deleteIfExists(path)
        Files.createSymbolicLink(path, target)
      match
        case Success(_)     => Right(())
        case Failure(error) => Left(ToolInstallError.SymlinkFailed(
            tool.name,
            path.toString,
            target.toString,
            error.getMessage
          ))
    yield ()

  private def createSudoSymlink(
      policy: ResolvedPolicy,
      tool: ResolvedTool,
      symlink: ResolvedSymlink
  ): Either[ToolInstallError, Unit] = policy.allowSudoSymlinks match
    case AllowSudoSymlinks.Disabled => Left(ToolInstallError.SudoSymlinkNotAllowed(tool.name))
    case AllowSudoSymlinks.Enabled  =>
      val path = Path.of(symlink.path)
      // Privileged writes must name an absolute destination. Relative sudo paths would depend on
      // process cwd and make dry-run output misleading.
      if !path.isAbsolute then
        Left(
          ToolInstallError.SymlinkFailed(
            tool.name,
            symlink.path,
            symlink.target,
            "sudo symlink path must be absolute"
          )
        )
      else
        resolveSymlinkTarget(tool, symlink).flatMap: target =>
          // Sudo is reached only through this fixed argv shape; manifest values are data args, not
          // shell text.
          val spec = CommandSpec(
            Vector("sudo", "ln", "-sfn", target.toString, path.toString),
            Path.of(tool.installDir).toAbsolutePath.normalize(),
            CommandEnvironment.baseline
          )
          commandExecutor.run(spec).left.map: error =>
            ToolInstallError.SymlinkFailed(
              tool.name,
              path.toString,
              target.toString,
              CommandFailureDetails.render(error)
            )

  private def resolveSymlinkTarget(
      tool: ResolvedTool,
      symlink: ResolvedSymlink
  ): Either[ToolInstallError, Path] =
    val installDir = Path.of(tool.installDir).toAbsolutePath.normalize()
    val rawTarget  = Path.of(symlink.target)
    val target     =
      if rawTarget.isAbsolute then rawTarget.toAbsolutePath.normalize()
      else installDir.resolve(rawTarget).normalize()
    // Symlink targets are confined to the installed tool tree so a manifest cannot expose arbitrary
    // user files through local or sudo links.
    if target.startsWith(installDir) then Right(target)
    else
      Left(
        ToolInstallError.SymlinkFailed(
          tool.name,
          symlink.path,
          symlink.target,
          "symlink target must resolve inside installDir"
        )
      )

  private def resolveInsideInstall(
      tool: ResolvedTool,
      relative: String
  ): Either[ToolInstallError, Path] =
    val input      = Path.of(relative)
    val installDir = Path.of(tool.installDir).toAbsolutePath.normalize()
    if input.isAbsolute then
      Left(ToolInstallError.StagingFailed(tool.name, s"path must be relative: $relative"))
    else
      val resolved = installDir.resolve(input).normalize()
      if resolved.startsWith(installDir) then Right(resolved)
      else Left(ToolInstallError.StagingFailed(tool.name, s"path escapes installDir: $relative"))

  private def toolName(error: ToolInstallError): String = error match
    case ToolInstallError.DownloadFailed(toolName, _, _, _)         => toolName
    case ToolInstallError.ChecksumMismatch(toolName, _, _)          => toolName
    case ToolInstallError.StagingFailed(toolName, _)                => toolName
    case ToolInstallError.ModeApplicationFailed(toolName, _, _, _)  => toolName
    case ToolInstallError.ReplacementFailed(toolName, _)            => toolName
    case ToolInstallError.ArchiveExtractionFailed(toolName, _)      => toolName
    case ToolInstallError.MissingExecutable(toolName, _)            => toolName
    case ToolInstallError.SymlinkFailed(toolName, _, _, _)          => toolName
    case ToolInstallError.SudoSymlinkNotAllowed(toolName)           => toolName
    case ToolInstallError.SudoSymlinkConfirmationRequired(toolName) => toolName

  private def renderInstallError(
      error: ToolInstallError,
      redactions: SensitiveValueRedactions
  ): String = error match
    case ToolInstallError.DownloadFailed(toolName, url, message, provenance) => detailBlock(
        s"download: $url: $message",
        Vector("tool" -> toolName, "url" -> url, "message" -> message) ++
          redirectDetailPairs("download", provenance),
        redactions
      )
    case ToolInstallError.ChecksumMismatch(toolName, expected, actual) => detailBlock(
        s"checksum: sha256 expected $expected, got $actual",
        Vector(
          "tool"            -> toolName,
          "expected sha256" -> expected,
          "actual sha256"   -> actual,
          "suggestion" -> "verify the downloaded artifact before updating the manifest checksum"
        ),
        redactions
      )
    case ToolInstallError.StagingFailed(toolName, message) => detailBlock(
        s"staging: $message",
        Vector("tool" -> toolName, "message" -> message),
        redactions
      )
    case ToolInstallError.ModeApplicationFailed(toolName, path, mode, message) => detailBlock(
        s"mode: $mode for $path: $message",
        Vector("tool" -> toolName, "path" -> path, "mode" -> mode, "message" -> message),
        redactions
      )
    case ToolInstallError.ReplacementFailed(toolName, message) => detailBlock(
        s"replacement: $message",
        Vector("tool" -> toolName, "message" -> message),
        redactions
      )
    case ToolInstallError.ArchiveExtractionFailed(toolName, message) => detailBlock(
        s"archive extraction: $message",
        Vector("tool" -> toolName, "message" -> message),
        redactions
      )
    case ToolInstallError.MissingExecutable(toolName, path) => detailBlock(
        s"verify executable: missing $path",
        Vector("tool" -> toolName, "expected path" -> path),
        redactions
      )
    case ToolInstallError.SymlinkFailed(toolName, path, target, message) => detailBlock(
        s"symlink: $target -> $path: $message",
        Vector("tool" -> toolName, "path" -> path, "target" -> target, "message" -> message),
        redactions
      )
    case ToolInstallError.SudoSymlinkNotAllowed(_) =>
      "sudo symlinks are not allowed by policy.allowSudoSymlinks"
    case ToolInstallError.SudoSymlinkConfirmationRequired(_) =>
      "sudo symlinks require apply confirmation; rerun apply with --yes"

  private def detailBlock(
      summary: String,
      details: Vector[(String, String)],
      redactions: SensitiveValueRedactions
  ): String =
    val lines = summary +: details.map((name, value) => s"  $name: $value")
    RenderSafety.displayLines(lines, redactions).mkString("\n")

  private def redirectDetailPairs(
      label: String,
      provenance: Option[UrlProvenance]
  ): Vector[(String, String)] = provenance.filter(_.redirected) match
    case Some(value) => Vector(
        s"$label initial url" -> value.initialUrl,
        s"$label final url"   -> value.finalUrl,
        s"$label redirects"   -> UrlProvenance.redirectChainForDisplay(value)
      )
    case None => Vector.empty

  private def renderPreflightError(error: ApplyPreflightError): String = error match
    case ApplyPreflightError.ConfirmationRequired =>
      "apply requires confirmation by policy.requireConfirmation; rerun apply with --yes"
    case ApplyPreflightError.SudoSymlinkNotAllowed(toolName) =>
      s"failed $toolName: sudo symlinks are not allowed by policy.allowSudoSymlinks"
    case ApplyPreflightError.SudoSymlinkConfirmationRequired(toolName) =>
      s"failed $toolName: sudo symlinks require apply confirmation; rerun apply with --yes"

/** Constructors for the production binary installer. */
object DirectBinaryInstaller:

  /** Production installer wired to JDK downloads, NIO staging, and bounded process execution. */
  def default: DirectBinaryInstaller =
    DirectBinaryInstaller(BinaryDownloadClient.jdk, NioInstallFileSystem, CommandExecutor.process)

private final case class PreparedPlan(
    profile: BinaryDistributionProfile,
    profileName: String,
    manifestFingerprint: String,
    plan: ResolvedPlan
)

private object StatefulApplyRunner:

  def run(
      options: InstallerOptions,
      prepared: PreparedPlan,
      installer: DirectBinaryInstaller,
      stateStore: ApplyStateStore,
      eventContext: InstallerEventContext
  ): InstallerResult = confirmationPreflight(options, prepared.plan) match
    case Some(result) => result
    case None => runAfterConfirmation(options, prepared, installer, stateStore, eventContext)

  private def confirmationPreflight(
      options: InstallerOptions,
      plan: ResolvedPlan
  ): Option[InstallerResult] = options.applyConfirmation match
    case ApplyConfirmation.Disabled
        if plan.policy.requireConfirmation == RequireConfirmation.Enabled =>
      Some(InstallerResult(
        Vector(
          "apply requires confirmation by policy.requireConfirmation; rerun apply with --yes"
        ),
        1
      ))
    case _ => None

  private def runAfterConfirmation(
      options: InstallerOptions,
      prepared: PreparedPlan,
      installer: DirectBinaryInstaller,
      stateStore: ApplyStateStore,
      eventContext: InstallerEventContext
  ): InstallerResult = statePath(options, prepared.plan) match
    case None => installer.installPlanWithObserver(
        prepared.plan,
        options.applyConfirmation,
        options.verboseOutput,
        _ => Right(()),
        eventContext
      )
    case Some(path) =>
      eventContext.emit(InstallerEvent.LogLine(
        None,
        RenderSafety.display(s"state file: $path", prepared.plan.redactions),
        _
      ))
      eventContext.emit(InstallerEvent.ToolPhaseChanged("state", InstallerPhase.LoadingState, _))
      loadInitialState(path, options.resetState, prepared, stateStore) match
        case Left(error) => InstallerResult(
            Vector(RenderSafety.display(
              renderStateError(error),
              prepared.plan.redactions
            )),
            1
          )
        case Right((statePath, state)) =>
          runWithState(statePath, state, prepared, options, installer, stateStore, eventContext)

  private def statePath(options: InstallerOptions, plan: ResolvedPlan): Option[String] =
    options.statePath.orElse(plan.policy.stateFile)

  private def loadInitialState(
      rawPath: String,
      resetState: ResetState,
      prepared: PreparedPlan,
      stateStore: ApplyStateStore
  ): Either[ApplyStateError, (Path, ApplyState)] =
    for
      // State files are intentionally CWD-local filenames only; this prevents a profile or CLI
      // option from writing outside the working directory or targeting an install path.
      path  <- StatePathResolver.resolve(rawPath, stateStore.cwd)
      state <- resetState match
        case ResetState.Enabled => Right(
            ApplyState.empty(prepared.profileName, prepared.manifestFingerprint)
          )
        case ResetState.Disabled => stateStore.load(path).flatMap:
            case None => Right(ApplyState.empty(prepared.profileName, prepared.manifestFingerprint))
            case Some(state) => validateState(path, state, prepared)
    yield path -> state

  private def validateState(
      path: Path,
      state: ApplyState,
      prepared: PreparedPlan
  ): Either[ApplyStateError, ApplyState] =
    if state.profileName == prepared.profileName &&
      state.manifestFingerprint == prepared.manifestFingerprint
    then Right(state)
    else
      Left(
        ApplyStateError.IncompatibleState(
          path,
          prepared.profileName,
          state.profileName,
          prepared.manifestFingerprint,
          state.manifestFingerprint
        )
      )

  private def runWithState(
      path: Path,
      state: ApplyState,
      prepared: PreparedPlan,
      options: InstallerOptions,
      installer: DirectBinaryInstaller,
      stateStore: ApplyStateStore,
      eventContext: InstallerEventContext
  ): InstallerResult =
    val completed    = completedToolNames(state)
    val pendingTools = prepared.plan.tools.filterNot(tool => completed(tool.name))
    val skippedLines = prepared.plan.tools
      .filter(tool => completed(tool.name))
      .map: tool =>
        eventContext.emit(InstallerEvent.ToolSkipped(
          tool.name,
          "already completed in state",
          Some(path.toString),
          _
        ))
        s"skipped ${tool.name}: already completed in state"
    val pendingPlan  = prepared.plan.copy(tools = pendingTools)
    var currentState = state
    val terminalObserver: TerminalToolResult => Either[String, Unit] = terminal =>
      eventContext.emit(InstallerEvent.ToolPhaseChanged(
        toolName(terminal),
        InstallerPhase.SavingState,
        _
      ))
      currentState = updateState(currentState, terminal)
      stateStore.save(path, currentState).left.map(error =>
        RenderSafety.display(renderStateError(error), prepared.plan.redactions)
      )
    val result = installer.installPlanWithObserver(
      pendingPlan,
      options.applyConfirmation,
      options.verboseOutput,
      terminalObserver,
      eventContext
    )

    result.copy(lines = skippedLines ++ result.lines)

  private def completedToolNames(state: ApplyState): Set[String] = state.tools.collect:
    case tool if tool.status == "completed" => tool.name
  .toSet

  private def updateState(state: ApplyState, result: TerminalToolResult): ApplyState =
    val updatedTool = result match
      case TerminalToolResult.Completed(toolName, installDir, download) =>
        ApplyStateTool(toolName, "completed", Some(installDir), None, download)
      case TerminalToolResult.Failed(toolName, message) =>
        ApplyStateTool(toolName, "failed", None, Some(message))
    state.copy(tools = replaceTool(state.tools, updatedTool))

  private def toolName(result: TerminalToolResult): String = result match
    case TerminalToolResult.Completed(toolName, _, _) => toolName
    case TerminalToolResult.Failed(toolName, _)       => toolName

  private def replaceTool(
      tools: Vector[ApplyStateTool],
      updated: ApplyStateTool
  ): Vector[ApplyStateTool] =
    val withoutCurrent = tools.filterNot(_.name == updated.name)
    withoutCurrent :+ updated

  private def renderStateError(error: ApplyStateError): String = error match
    case ApplyStateError.InvalidPath(path, message)  => s"state path '$path' is invalid: $message"
    case ApplyStateError.ReadFailed(path, message)   => s"state read failed for $path: $message"
    case ApplyStateError.WriteFailed(path, message)  => s"state write failed for $path: $message"
    case ApplyStateError.DecodeFailed(path, message) => s"state decode failed for $path: $message"
    case ApplyStateError.IncompatibleState(
          path,
          expectedProfileName,
          actualProfileName,
          expectedFingerprint,
          actualFingerprint
        ) =>
      s"state file $path does not match this manifest: expected profile '$expectedProfileName' " +
        s"with fingerprint $expectedFingerprint, found profile '$actualProfileName' with " +
        s"fingerprint $actualFingerprint; rerun with --reset-state to ignore saved state"

private object StatePathResolver:

  def resolve(rawPath: String, cwd: Path): Either[ApplyStateError.InvalidPath, Path] =
    val path = Path.of(rawPath)
    if rawPath.trim.isEmpty then invalid(rawPath, "state filename must not be empty")
    else if path.isAbsolute then invalid(rawPath, "absolute state paths are not allowed")
    else if path.getNameCount != 1 then
      invalid(rawPath, "state path must be a filename in the current working directory")
    else
      val resolved = cwd.toAbsolutePath.normalize().resolve(path).normalize()
      if resolved.getParent == cwd.toAbsolutePath.normalize() then Right(resolved)
      else invalid(rawPath, "state path must stay in the current working directory")

  private def invalid(
      path: String,
      message: String
  ): Either[ApplyStateError.InvalidPath, Path] = Left(ApplyStateError.InvalidPath(path, message))

private final class NioApplyStateStore(val cwd: Path) extends ApplyStateStore:

  def load(path: Path): Either[ApplyStateError, Option[ApplyState]] =
    if !Files.exists(path) then Right(None)
    else
      Try(read[ApplyState](Files.readString(path))) match
        case Success(state)                     => Right(Some(state))
        case Failure(error: upickle.core.Abort) =>
          Left(ApplyStateError.DecodeFailed(path, error.getMessage))
        case Failure(error) => Left(ApplyStateError.ReadFailed(path, error.getMessage))

  def save(path: Path, state: ApplyState): Either[ApplyStateError, Unit] =
    val tmp = cwd.resolve(s".${path.getFileName}.tmp-${UUID.randomUUID()}")
    Try:
      Files.createDirectories(cwd)
      // Write to a unique temp file first; a partial state write must never look like a valid
      // resume checkpoint.
      val _ = Files.writeString(
        tmp,
        write(state, indent = 2),
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE
      )
      val _ = Files.move(
        tmp,
        path,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
    match
      case Success(_)     => Right(())
      case Failure(error) =>
        val _ = Files.deleteIfExists(tmp)
        Left(ApplyStateError.WriteFailed(path, error.getMessage))

private object ManifestFingerprint:

  def profile(profile: BinaryDistributionProfile): String =
    Sha256.digest(canonicalProfile(profile).getBytes(StandardCharsets.UTF_8))

  private def canonicalProfile(profile: BinaryDistributionProfile): String =
    val builder = StringBuilder()
    append(builder, "apiVersion", profile.apiVersion.value)
    append(builder, "kind", profile.kind.value)
    append(builder, "metadata.name", profile.metadata.name)
    appendMap(builder, "metadata.labels", profile.metadata.labels)
    appendMap(builder, "metadata.annotations", profile.metadata.annotations)
    appendPolicy(builder, profile.spec.policy)
    appendMap(builder, "spec.vars", profile.spec.vars)
    appendVersions(builder, profile.spec.versions)
    appendPlan(builder, profile.spec.plan)
    builder.result()

  private def appendPolicy(builder: StringBuilder, policy: binstaller.config.InstallPolicy): Unit =
    append(builder, "spec.policy.dryRun", policy.dryRun.toString)
    append(builder, "spec.policy.continueOnError", policy.continueOnError.toString)
    append(builder, "spec.policy.appsDir", policy.appsDir)
    append(builder, "spec.policy.cleanInstall", policy.cleanInstall.toString)
    append(builder, "spec.policy.requireConfirmation", policy.requireConfirmation.toString)
    append(builder, "spec.policy.allowSudoSymlinks", policy.allowSudoSymlinks.toString)
    append(builder, "spec.policy.stateFile", policy.stateFile.getOrElse(""))

  private def appendVersions(
      builder: StringBuilder,
      versions: Map[String, VersionSource]
  ): Unit = versions.toVector.sortBy(_._1).foreach:
    case (name, source) => source match
        case VersionSource.Pinned(value)       => append(builder, s"spec.versions.$name", value)
        case VersionSource.Dynamic(kind, note) =>
          append(builder, s"spec.versions.$name.dynamic.type", kind.value)
          append(builder, s"spec.versions.$name.dynamic.note", note.getOrElse(""))
        case VersionSource.Resolver(kind, url) =>
          append(builder, s"spec.versions.$name.resolver.type", kind.value)
          append(builder, s"spec.versions.$name.resolver.url", url)

  private def appendPlan(builder: StringBuilder, plan: Vector[PlanEntry]): Unit =
    plan.zipWithIndex.foreach:
      case (entry, index) =>
        val base = s"spec.plan[$index]"
        append(builder, s"$base.name", entry.name)
        append(builder, s"$base.kind", entry.kind.value)
        append(builder, s"$base.description", entry.description.getOrElse(""))
        append(
          builder,
          s"$base.when.os.family",
          entry.when.flatMap(_.os).flatMap(_.family).getOrElse("")
        )
        append(
          builder,
          s"$base.when.architecture",
          entry.when.flatMap(_.architecture).getOrElse("")
        )
        appendToolSpec(builder, s"$base.spec", entry.spec)

  private def appendToolSpec(
      builder: StringBuilder,
      base: String,
      spec: BinaryToolSpec
  ): Unit =
    append(builder, s"$base.versionRef", spec.versionRef)
    append(builder, s"$base.installDir", spec.installDir)
    appendVector(builder, s"$base.createDirectories", spec.createDirectories)
    appendDownload(builder, s"$base.download", spec.download)
    appendExecutables(builder, s"$base.executables", spec.executables)
    appendSymlinks(builder, s"$base.symlinks", spec.symlinks)

  private def appendDownload(builder: StringBuilder, base: String, download: DownloadSpec): Unit =
    append(builder, s"$base.url", download.url)
    append(builder, s"$base.filename", download.filename)
    download.checksum.foreach: checksum =>
      append(builder, s"$base.checksum.algorithm", checksum.algorithm.value)
      append(builder, s"$base.checksum.value", checksum.value)
    download.archive.foreach: archive =>
      append(builder, s"$base.archive.type", archive.archiveType.value)
      appendMappings(builder, s"$base.archive.extract.files", archive.extract.files)
      appendMappings(builder, s"$base.archive.extract.directories", archive.extract.directories)

  private def appendExecutables(
      builder: StringBuilder,
      base: String,
      executables: Vector[binstaller.config.ExecutableSpec]
  ): Unit = executables.zipWithIndex.foreach:
    case (executable, index) =>
      append(builder, s"$base[$index].path", executable.path)
      append(builder, s"$base[$index].mode", executable.mode.map(_.value).getOrElse(""))

  private def appendSymlinks(
      builder: StringBuilder,
      base: String,
      symlinks: Vector[binstaller.config.SymlinkSpec]
  ): Unit = symlinks.zipWithIndex.foreach:
    case (symlink, index) =>
      append(builder, s"$base[$index].path", symlink.path)
      append(builder, s"$base[$index].target", symlink.target)
      append(builder, s"$base[$index].privilege", symlink.privilege.toString)

  private def appendMappings(
      builder: StringBuilder,
      base: String,
      mappings: Vector[ExtractMapping]
  ): Unit = mappings.zipWithIndex.foreach:
    case (mapping, index) =>
      append(builder, s"$base[$index].from", mapping.from)
      append(builder, s"$base[$index].to", mapping.to)

  private def appendMap(builder: StringBuilder, base: String, values: Map[String, String]): Unit =
    values.toVector.sortBy(_._1).foreach:
      case (name, value) => append(builder, s"$base.$name", value)

  private def appendVector(builder: StringBuilder, base: String, values: Vector[String]): Unit =
    values.zipWithIndex.foreach:
      case (value, index) => append(builder, s"$base[$index]", value)

  private def append(builder: StringBuilder, key: String, value: String): Unit =
    val _ = builder.append(key.length).append(':').append(key).append('=')
    val _ = builder.append(value.length).append(':').append(value).append('\n')

private object PlaceholderBinaryInstallerService extends BinaryInstallerService:

  def planWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult = placeholderResult("plan", options)

  def applyWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult = placeholderResult("apply", options)

  def versions(options: InstallerOptions): InstallerResult = placeholderResult("versions", options)

  private def placeholderResult(command: String, options: InstallerOptions): InstallerResult =
    InstallerResult(
      Vector(s"binstaller $command placeholder for ${options.configPath}"),
      0
    )

private final class ResolvingBinaryInstallerService(
    httpTextClient: HttpTextClient,
    resolutionOptions: ResolutionOptions,
    installer: DirectBinaryInstaller,
    stateStore: ApplyStateStore
) extends BinaryInstallerService:

  def planWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult =
    val eventContext = InstallerEventContext.start(eventObserver)
    renderSelectedPlanWithEvents(options, PlanRenderCommand.Plan, eventContext)

  override def applyWithProgress(
      options: InstallerOptions,
      progressObserver: BinaryDownloadProgressObserver
  ): InstallerResult =
    applyWithEvents(options, InstallerEventObserver.fromDownloadProgress(progressObserver))

  def applyWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult =
    val eventContext = InstallerEventContext.start(eventObserver)
    if options.dryRun == DryRunMode.Enabled then
      renderSelectedPlanWithEvents(options, PlanRenderCommand.ApplyDryRun, eventContext)
    else
      eventContext.emit(InstallerEvent.ResolvingStarted(options.configPath, _))
      resolveSelectedPreparedPlan(options) match
        case Left(error) =>
          val result = renderError(error)
          emitSummary(result, stateFilePath = None, eventContext)
          result
        case Right(prepared) =>
          val statePath = configuredStatePath(options, prepared.plan)
          eventContext.emit(InstallerEvent.PlanReady(
            prepared.plan.tools.size,
            statePath,
            _
          ))
          val result =
            StatefulApplyRunner.run(options, prepared, installer, stateStore, eventContext)
          emitSummary(result, statePath, eventContext)
          result

  def versions(options: InstallerOptions): InstallerResult =
    resolveFromOptions(options).fold(renderError, renderVersions)

  private def renderSelectedPlanWithEvents(
      options: InstallerOptions,
      command: PlanRenderCommand,
      eventContext: InstallerEventContext
  ): InstallerResult =
    eventContext.emit(InstallerEvent.ResolvingStarted(options.configPath, _))
    resolveSelectedPlan(options) match
      case Left(error) =>
        val result = renderError(error)
        emitSummary(result, stateFilePath = None, eventContext)
        result
      case Right(plan) =>
        val statePath = configuredStatePath(options, plan)
        eventContext.emit(InstallerEvent.PlanReady(plan.tools.size, statePath, _))
        val result = PlanRenderer.render(plan, command)
        emitSummary(result, statePath, eventContext)
        result

  private def resolveSelectedPlan(
      options: InstallerOptions
  ): Either[ResolvePlanError, ResolvedPlan] = resolveSelectedPreparedPlan(options).map(_.plan)

  private def resolveSelectedPreparedPlan(
      options: InstallerOptions
  ): Either[ResolvePlanError, PreparedPlan] = resolveFromOptions(options).flatMap: prepared =>
    ToolSelector.select(prepared.plan, options.selection).map: selected =>
      prepared.copy(plan = selected)

  private def resolveFromOptions(
      options: InstallerOptions
  ): Either[ResolvePlanError, PreparedPlan] = ConfigModule.load(options.configPath) match
    case Left(error)    => Left(ResolvePlanError.ConfigLoadFailed(error))
    case Right(profile) => PlanResolver.resolve(profile, resolutionOptions, httpTextClient).map:
        plan =>
          PreparedPlan(
            profile,
            profile.metadata.name,
            ManifestFingerprint.profile(profile),
            plan
          )

  private def configuredStatePath(
      options: InstallerOptions,
      plan: ResolvedPlan
  ): Option[String] = options.statePath.orElse(plan.policy.stateFile)

  private def emitSummary(
      result: InstallerResult,
      stateFilePath: Option[String],
      eventContext: InstallerEventContext
  ): Unit =
    val status =
      if result.exitCode == 0 then InstallerRunStatus.Succeeded
      else InstallerRunStatus.Failed
    eventContext.emit(InstallerEvent.Summary(
      status,
      installed = result.lines.count(_.startsWith("installed ")),
      failed = result.lines.count(_.startsWith("failed ")),
      skipped = result.lines.count(_.startsWith("skipped ")),
      exitCode = result.exitCode,
      stateFilePath = stateFilePath,
      _
    ))

  private def renderVersions(prepared: PreparedPlan): InstallerResult =
    val references = versionReferences(prepared.profile)
    val resolved   = prepared.plan.tools.map(tool => tool.name -> tool.version).toMap
    val lines      = prepared.profile.spec.versions.toVector.sortBy(_._1).map:
      case (name, source) =>
        val tools = references.getOrElse(name, Vector.empty)
        renderVersionSource(name, source, resolved.get(name), tools)
    InstallerResult(
      RenderSafety.displayLines("binstaller versions" +: lines, prepared.plan.redactions),
      0
    )

  private def versionReferences(
      profile: BinaryDistributionProfile
  ): Map[String, Vector[String]] = profile.spec.plan
    .groupBy(_.spec.versionRef)
    .view
    .mapValues(_.map(_.name))
    .toMap

  private def renderVersionSource(
      name: String,
      source: VersionSource,
      resolved: Option[ResolvedVersion],
      tools: Vector[String]
  ): String =
    val toolList = if tools.isEmpty then "none" else tools.mkString(", ")
    source match
      case VersionSource.Pinned(value) => s"pinned $name: $value (tools: $toolList)"
      case VersionSource.Dynamic(DynamicVersionKind.LatestUrl, note) =>
        val suffix = note.map(value => s" - $value").getOrElse("")
        s"dynamic $name: dynamic latest-url (tools: $toolList)$suffix"
      case VersionSource.Resolver(VersionResolverKind.HttpText, url) =>
        val value      = resolved.map(ResolvedVersion.render).getOrElse("<unresolved>")
        val provenance = resolved.flatMap:
          case ResolvedVersion.Concrete(_, value)  => value
          case ResolvedVersion.DynamicLatestUrl(_) => None
        s"resolved $name: $value from $url${UrlProvenance.redirectSuffix(provenance)} " +
          s"(tools: $toolList)"

  private def renderError(error: ResolvePlanError): InstallerResult =
    InstallerResult(ResolvePlanError.renderLines(error), 1)

private object ToolSelector:

  def select(
      plan: ResolvedPlan,
      selection: ToolSelection
  ): Either[ResolvePlanError.SelectionFailed, ResolvedPlan] =
    val toolNames = plan.tools.map(_.name).toSet
    val unknown   = (selection.only ++ selection.skip)
      .distinct
      .filterNot(toolNames.contains)
      .map(name => s"unknown tool '$name'")

    if unknown.nonEmpty then Left(ResolvePlanError.SelectionFailed(unknown))
    else Right(plan.copy(tools = selectedTools(plan.tools, selection)))

  private def selectedTools(
      tools: Vector[ResolvedTool],
      selection: ToolSelection
  ): Vector[ResolvedTool] =
    val onlyNames = selection.only.toSet
    val skipNames = selection.skip.toSet
    val included  =
      if onlyNames.isEmpty then tools
      else tools.filter(tool => onlyNames(tool.name))

    included.filterNot(tool => skipNames(tool.name))

private enum PlanRenderCommand:
  case Plan, ApplyDryRun

private object PlanRenderer:

  def render(plan: ResolvedPlan, command: PlanRenderCommand): InstallerResult = InstallerResult(
    RenderSafety.displayLines(
      header(plan, command) ++ plan.tools.zipWithIndex.flatMap(renderTool),
      plan.redactions
    ),
    0
  )

  private def header(plan: ResolvedPlan, command: PlanRenderCommand): Vector[String] =
    val sudoSymlinkCount =
      plan.tools.flatMap(_.symlinks).count(_.privilege == SymlinkPrivilege.Sudo)
    val stateLine = plan.policy.stateFile match
      case Some(path) => s"state file: $path (not created)"
      case None       => "state file: not configured"
    val sudoLine =
      if sudoSymlinkCount == 0 then "sudo risk: none"
      else
        s"sudo risk: YES - $sudoSymlinkCount sudo symlink command(s) require elevated privileges"
    val title = command match
      case PlanRenderCommand.Plan        => "binstaller plan (dry-run)"
      case PlanRenderCommand.ApplyDryRun => "binstaller apply --dry-run"

    Vector(
      title,
      s"tools: ${plan.tools.size}",
      s"apps dir: ${plan.policy.appsDir} (not created)",
      stateLine,
      "filesystem: no changes will be made",
      sudoLine
    )

  private def renderTool(indexedTool: (ResolvedTool, Int)): Vector[String] =
    val (tool, index) = indexedTool
    Vector(
      "",
      s"${index + 1}. ${tool.name}",
      s"   destination: ${tool.installDir}",
      s"   version: ${renderVersion(tool.version)}",
      s"   download: ${tool.download.url}",
      s"   download file: ${joinPath(tool.installDir, tool.download.filename)}",
      s"   checksum: ${renderChecksum(tool.download.checksum)}"
    ) ++ renderCreateDirectories(tool) ++ renderStrategy(tool) ++ renderExecutables(tool) ++
      renderSymlinks(tool)

  private def renderVersion(version: ResolvedVersion): String = version match
    case ResolvedVersion.Concrete(value, provenance) =>
      s"concrete $value${UrlProvenance.redirectSuffix(provenance)}"
    case ResolvedVersion.DynamicLatestUrl(_) => "dynamic latest-url"

  private def renderChecksum(checksum: Option[ChecksumSpec]): String = checksum match
    case Some(value) => s"${value.algorithm.value} ${value.value}"
    case None        => "not configured"

  private def renderCreateDirectories(tool: ResolvedTool): Vector[String] =
    if tool.createDirectories.isEmpty then Vector.empty
    else
      Vector("   create directories:") ++
        tool.createDirectories.map(path => s"     ${joinPath(tool.installDir, path)}")

  private def renderStrategy(tool: ResolvedTool): Vector[String] =
    val archiveLines = tool.download.archive match
      case Some(archive) => renderArchive(archive)
      case None          => Vector("   archive: none")
    val directLine =
      if tool.download.archive.isEmpty then
        Vector("   strategy: direct binary download")
      else Vector.empty

    directLine ++ archiveLines

  private def renderArchive(archive: ResolvedArchive): Vector[String] =
    Vector(s"   archive: ${archive.original.archiveType.value}") ++
      archive.files.map(mapping => s"     file ${mapping.from} -> ${mapping.to}") ++
      archive.directories.map(mapping => s"     directory ${mapping.from} -> ${mapping.to}")

  private def renderExecutables(tool: ResolvedTool): Vector[String] =
    if tool.executables.isEmpty then Vector("   executables: none")
    else
      Vector("   executables:") ++ tool.executables.map: executable =>
        val mode = executable.mode.map(value => s" mode ${value.value}").getOrElse("")
        s"     ${joinPath(tool.installDir, executable.path)}$mode"

  private def renderSymlinks(tool: ResolvedTool): Vector[String] =
    if tool.symlinks.isEmpty then Vector("   symlinks: none")
    else Vector("   symlinks:") ++ tool.symlinks.map(renderSymlinkCommand(tool, _))

  private def renderSymlinkCommand(tool: ResolvedTool, symlink: ResolvedSymlink): String =
    val destination = absoluteOrInstallPath(tool.installDir, symlink.path)
    val target      = absoluteOrInstallPath(tool.installDir, symlink.target)
    val command     = symlink.privilege match
      case SymlinkPrivilege.User => s"ln -sfn ${shellQuote(target)} ${shellQuote(destination)}"
      case SymlinkPrivilege.Sudo => s"sudo ln -sfn ${shellQuote(target)} ${shellQuote(destination)}"
    val risk = symlink.privilege match
      case SymlinkPrivilege.User => "local"
      case SymlinkPrivilege.Sudo => "sudo risk"
    s"     [$risk] $command"

  private def absoluteOrInstallPath(installDir: String, path: String): String =
    if path.startsWith("/") then path else joinPath(installDir, path)

  private def joinPath(parent: String, child: String): String =
    if parent.endsWith("/") then s"$parent$child" else s"$parent/$child"

  private def shellQuote(value: String): String = s"'${value.replace("'", "'\"'\"'")}'"

private object CommandEnvironment:
  val baseline: Map[String, String] = Map("PATH" -> sys.env.getOrElse("PATH", "/usr/bin:/bin"))

private final class ProcessCommandExecutor(timeout: Duration) extends CommandExecutor:
  private val capturedOutputLimitBytes = 64 * 1024

  def run(spec: CommandSpec): Either[CommandExecutionError, Unit] = Try:
    val builder = ProcessBuilder(spec.argv.asJava)
    val _       = builder.directory(spec.cwd.toFile)
    val env     = builder.environment()
    // Commands receive only the modeled environment. Parent secrets must not leak into sudo/tar
    // process boundaries or later diagnostics.
    env.clear()
    spec.env.foreach:
      case (name, value) => val _ = env.put(name, value)
    val process = builder.start()
    supervised:
      val stdout = fork(readBounded(process.getInputStream))
      val stderr = fork(readBounded(process.getErrorStream))
      if process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS) then
        val exit   = process.exitValue()
        val output = CommandOutput(stdout.join(), stderr.join())
        if exit == 0 then Right(())
        else
          Left(
            CommandExecutionError(spec, s"command exited with status $exit", Some(exit), output)
          )
      else
        val _      = process.destroyForcibly()
        val _      = process.waitFor(5, TimeUnit.SECONDS)
        val output = CommandOutput(stdout.join(), stderr.join())
        Left(
          CommandExecutionError(
            spec,
            s"command timed out after ${timeout.toSeconds}s",
            None,
            output
          )
        )
  match
    case Success(result) => result
    case Failure(error)  => Left(CommandExecutionError(spec, error.getMessage, None))

  private def readBounded(input: InputStream): String = Using.resource(input): stream =>
    val output  = ByteArrayOutputStream()
    val buffer  = Array.ofDim[Byte](8 * 1024)
    var read    = stream.read(buffer)
    var stored  = 0
    var clipped = false
    while read != -1 do
      val remaining = capturedOutputLimitBytes - stored
      if remaining > 0 then
        val writable = read.min(remaining)
        output.write(buffer, 0, writable)
        stored += writable
        clipped = clipped || writable < read
      else clipped = true
      read = stream.read(buffer)
    val suffix =
      if clipped then "\n... output truncated after 65536 bytes ..."
      else ""
    output.toString(StandardCharsets.UTF_8) + suffix

private enum ArchiveEntryKind:
  case File, Directory

private final case class ArchiveEntry(name: String, kind: ArchiveEntryKind)

private final case class PlannedArchiveFile(source: String, target: Path)

private object ArchiveExtractor:

  def extract(
      archive: ResolvedArchive,
      bytes: Array[Byte],
      stagingDir: Path,
      commandExecutor: CommandExecutor
  ): Either[String, Unit] = archive.original.archiveType match
    case ArchiveType.Zip   => extractZip(archive, bytes, stagingDir)
    case ArchiveType.TarGz => extractTarGz(archive, bytes, stagingDir)
    case ArchiveType.TarXz => extractTarXz(archive, bytes, stagingDir, commandExecutor)

  private def extractZip(
      archive: ResolvedArchive,
      bytes: Array[Byte],
      stagingDir: Path
  ): Either[String, Unit] = Try(indexZip(bytes)).toEither.left.map(_.getMessage).flatMap: entries =>
    planExtraction(entries, archive, stagingDir).flatMap: plannedFiles =>
      Try:
        Using.resource(ZipInputStream(ByteArrayInputStream(bytes))): zip =>
          var entry = zip.getNextEntry
          while entry != null do
            normalizedArchivePath(entry.getName).foreach: source =>
              plannedFiles.find(_.source == source).foreach: planned =>
                copyCurrentEntry(zip, planned.target)
            zip.closeEntry()
            entry = zip.getNextEntry
      match
        case Success(_)     => Right(())
        case Failure(error) => Left(error.getMessage)

  private def indexZip(bytes: Array[Byte]): Vector[ArchiveEntry] =
    Using.resource(ZipInputStream(ByteArrayInputStream(bytes))): zip =>
      Iterator
        .continually(zip.getNextEntry)
        .takeWhile(_ != null)
        .map: entry =>
          val source = normalizedArchivePath(entry.getName).fold(
            message => throw IllegalArgumentException(message),
            identity
          )
          val kind = if entry.isDirectory then ArchiveEntryKind.Directory else ArchiveEntryKind.File
          ArchiveEntry(source, kind)
        .toVector

  private def extractTarGz(
      archive: ResolvedArchive,
      bytes: Array[Byte],
      stagingDir: Path
  ): Either[String, Unit] = Try(indexTarGz(bytes)).toEither.left.map(_.getMessage).flatMap:
    entries =>
      planExtraction(entries, archive, stagingDir).flatMap: plannedFiles =>
        val plannedBySource = plannedFiles.map(file => file.source -> file.target).toMap
        Try:
          Using.resource(GZIPInputStream(ByteArrayInputStream(bytes))): input =>
            readTarEntries(input): (entry, content) =>
              plannedBySource.get(entry.name).foreach: target =>
                copyBounded(content, target, entry.size)
              if !plannedBySource.contains(entry.name) then
                val _ = skipFully(content, entry.size)
        match
          case Success(_)     => Right(())
          case Failure(error) => Left(error.getMessage)

  private def indexTarGz(bytes: Array[Byte]): Vector[ArchiveEntry] =
    Using.resource(GZIPInputStream(ByteArrayInputStream(bytes))): input =>
      val entries = Vector.newBuilder[ArchiveEntry]
      readTarEntries(input): (entry, content) =>
        val _ = skipFully(content, entry.size)
        entries += ArchiveEntry(entry.name, entry.kind)
      entries.result()

  private def extractTarXz(
      archive: ResolvedArchive,
      bytes: Array[Byte],
      stagingDir: Path,
      commandExecutor: CommandExecutor
  ): Either[String, Unit] =
    val archiveFile = Files.createTempFile(stagingDir, ".archive-", ".tar.xz")
    val extractDir  = Files.createTempDirectory(stagingDir, ".archive-extract-")
    Files.write(archiveFile, bytes)
    val spec = CommandSpec(
      Vector("tar", "-xJf", archiveFile.toString, "-C", extractDir.toString),
      stagingDir,
      CommandEnvironment.baseline
    )
    commandExecutor.run(spec) match
      case Left(error) =>
        deleteRecursively(extractDir)
        val _ = Files.deleteIfExists(archiveFile)
        Left(CommandFailureDetails.render(error))
      case Right(()) =>
        val result = Try(indexExtractedDirectory(extractDir)).toEither.left.map(_.getMessage)
          .flatMap: entries =>
            planExtraction(entries, archive, stagingDir).flatMap: plannedFiles =>
              Try:
                plannedFiles.foreach: planned =>
                  val source = extractDir.resolve(planned.source).normalize()
                  copyFile(source, planned.target)
              match
                case Success(_)     => Right(())
                case Failure(error) => Left(error.getMessage)
        deleteRecursively(extractDir)
        val _ = Files.deleteIfExists(archiveFile)
        result

  private def indexExtractedDirectory(root: Path): Vector[ArchiveEntry] =
    Using.resource(Files.walk(root)): stream =>
      stream
        .iterator()
        .asScala
        .toVector
        .filterNot(_ == root)
        .map: path =>
          val relative = root.relativize(path).toString.replace('\\', '/')
          val source   = normalizedArchivePath(relative).fold(
            message => throw IllegalArgumentException(message),
            identity
          )
          val kind =
            if Files.isDirectory(path) then ArchiveEntryKind.Directory else ArchiveEntryKind.File
          ArchiveEntry(source, kind)

  private def planExtraction(
      entries: Vector[ArchiveEntry],
      archive: ResolvedArchive,
      stagingDir: Path
  ): Either[String, Vector[PlannedArchiveFile]] =
    // Build the complete copy plan before writing selected members so duplicate sources and target
    // collisions fail without partially populating the staged install tree.
    rejectDuplicateArchiveSources(entries).flatMap: _ =>
      val fileMappings      = archive.files.map(planFileMapping(entries, stagingDir, _))
      val directoryMappings = archive.directories.map(planDirectoryMapping(entries, stagingDir, _))
      val planned           = (fileMappings ++ directoryMappings).foldLeft(
        Right(Vector.empty): Either[String, Vector[PlannedArchiveFile]]
      ): (acc, next) =>
        for
          current <- acc
          files   <- next
        yield current ++ files

      planned.flatMap(rejectDuplicateTargets)

  private def rejectDuplicateArchiveSources(entries: Vector[ArchiveEntry]): Either[String, Unit] =
    val duplicate = entries
      .groupBy(_.name)
      .collectFirst:
        case (source, values) if values.size > 1 => source
    duplicate match
      case Some(source) => Left(s"duplicate archive member: $source")
      case None         => Right(())

  private def planFileMapping(
      entries: Vector[ArchiveEntry],
      stagingDir: Path,
      mapping: ResolvedExtractMapping
  ): Either[String, Vector[PlannedArchiveFile]] =
    for
      source <- normalizedArchivePath(mapping.from)
      target <- resolveInside(stagingDir, mapping.to)
      _      <- entries.find(entry => entry.name == source && entry.kind == ArchiveEntryKind.File)
        .toRight(s"archive member not found: ${mapping.from}")
    yield Vector(PlannedArchiveFile(source, target))

  private def planDirectoryMapping(
      entries: Vector[ArchiveEntry],
      stagingDir: Path,
      mapping: ResolvedExtractMapping
  ): Either[String, Vector[PlannedArchiveFile]] =
    normalizedArchivePath(mapping.from).flatMap: source =>
      val prefix = s"$source/"
      val files  = entries.filter: entry =>
        entry.kind == ArchiveEntryKind.File && entry.name.startsWith(prefix)
      if files.isEmpty then Left(s"archive directory not found: ${mapping.from}")
      else
        val planned = files.map: entry =>
          val relative = entry.name.stripPrefix(prefix)
          val target   = joinArchivePath(mapping.to, relative)
          resolveInside(stagingDir, target).map: targetPath =>
            PlannedArchiveFile(entry.name, targetPath)
        collectEither(planned)

  private def rejectDuplicateTargets(
      plannedFiles: Vector[PlannedArchiveFile]
  ): Either[String, Vector[PlannedArchiveFile]] =
    val duplicate = plannedFiles
      .groupBy(_.target)
      .collectFirst:
        case (target, files) if files.size > 1 => target
    duplicate match
      case Some(target) => Left(s"multiple archive members map to $target")
      case None         => Right(plannedFiles)

  private final case class TarEntry(name: String, kind: ArchiveEntryKind, size: Long)

  private def readTarEntries(input: InputStream)(handle: (TarEntry, InputStream) => Unit): Unit =
    var header = readTarBlock(input)
    while header.exists(!_.forall(_ == 0.toByte)) do
      val current = header.get
      val entry   = tarEntry(current)
      handle(entry, input)
      val padding = tarPadding(entry.size)
      val _       = skipFully(input, padding)
      header = readTarBlock(input)

  private def tarEntry(header: Array[Byte]): TarEntry =
    val name     = tarString(header, 0, 100)
    val prefix   = tarString(header, 345, 155)
    val fullName = if prefix.isEmpty then name else s"$prefix/$name"
    val source   = normalizedArchivePath(fullName).fold(
      message => throw IllegalArgumentException(message),
      identity
    )
    val size = tarOctal(header, 124, 12)
    val kind = header(156).toChar match
      case 0 | '0' => ArchiveEntryKind.File
      case '5'     => ArchiveEntryKind.Directory
      // Links and special tar metadata are rejected because they can escape the apparent file tree
      // even when the entry name itself is relative.
      case '1' | '2' => throw IllegalArgumentException(s"unsafe archive link entry: $source")
      case other => throw IllegalArgumentException(s"unsupported tar entry type '$other': $source")
    TarEntry(source, kind, size)

  private def readTarBlock(input: InputStream): Option[Array[Byte]] =
    val buffer = Array.ofDim[Byte](512)
    var offset = 0
    while offset < buffer.length do
      val count = input.read(buffer, offset, buffer.length - offset)
      if count == -1 then return if offset == 0 then None else Some(buffer)
      offset = offset + count
    Some(buffer)

  private def tarString(header: Array[Byte], offset: Int, length: Int): String =
    val bytes = header.slice(offset, offset + length).takeWhile(_ != 0.toByte)
    new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim

  private def tarOctal(header: Array[Byte], offset: Int, length: Int): Long =
    val value = tarString(header, offset, length).trim
    if value.isEmpty then 0L else java.lang.Long.parseLong(value, 8)

  private def tarPadding(size: Long): Long =
    val remainder = size % 512L
    if remainder == 0L then 0L else 512L - remainder

  private def normalizedArchivePath(value: String): Either[String, String] =
    val path = value.stripSuffix("/")
    // Archive names are treated as POSIX-like relative paths independent of host OS. Backslash,
    // drive prefixes, absolute roots, controls, and `..` are rejected before copy planning.
    if path.isEmpty then Left("archive path must not be empty")
    else if path == "." then Right(path)
    else if path.exists(_ < ' ') then Left(s"archive path contains control character: $value")
    else if path.contains('\\') then Left(s"archive path contains backslash: $value")
    else if path.matches("^[A-Za-z]:.*") then Left(s"archive path is drive-prefixed: $value")
    else
      val nioPath = Path.of(path)
      if nioPath.isAbsolute then Left(s"archive path is absolute: $value")
      else
        val segments = path.split('/').toVector
        val unsafe   = segments.exists(_ == "..")
        if unsafe then Left(s"archive path escapes staging directory: $value")
        else
          val normalized = segments.filterNot(segment => segment.isEmpty || segment == ".")
          if normalized.isEmpty then Right(".")
          else Right(normalized.mkString("/"))

  private def resolveInside(root: Path, relative: String): Either[String, Path] =
    val clean = relative match
      case "" | "." => "."
      case other    => other
    validateRelativeTarget(clean).flatMap: _ =>
      val input = Path.of(clean)
      if input.isAbsolute then Left(s"path must be relative: $relative")
      else
        val normalizedRoot = root.toAbsolutePath.normalize()
        val resolved       = normalizedRoot.resolve(input).normalize()
        if resolved.startsWith(normalizedRoot) then Right(resolved)
        else Left(s"path escapes staging directory: $relative")

  private def validateRelativeTarget(value: String): Either[String, Unit] =
    if value == "." then Right(())
    else normalizedArchivePath(value).map(_ => ())

  private def collectEither[A](values: Vector[Either[String, A]]): Either[String, Vector[A]] =
    values.foldLeft(Right(Vector.empty): Either[String, Vector[A]]): (acc, next) =>
      for
        current <- acc
        value   <- next
      yield current :+ value

  private def joinArchivePath(parent: String, child: String): String = parent match
    case "" | "."                     => child
    case value if value.endsWith("/") => s"$value$child"
    case value                        => s"$value/$child"

  private def copyCurrentEntry(input: InputStream, target: Path): Unit =
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))
    val _ = Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)

  private def copyBounded(input: InputStream, target: Path, bytes: Long): Unit =
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))
    Using.resource(Files.newOutputStream(
      target,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    )): output =>
      val buffer    = Array.ofDim[Byte](8192)
      var remaining = bytes
      while remaining > 0 do
        val count = input.read(buffer, 0, math.min(buffer.length.toLong, remaining).toInt)
        if count == -1 then throw IllegalArgumentException("unexpected end of tar entry")
        output.write(buffer, 0, count)
        remaining = remaining - count

  private def copyFile(source: Path, target: Path): Unit =
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))
    val _ = Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)

  private def skipFully(input: InputStream, bytes: Long): Long =
    var remaining = bytes
    while remaining > 0 do
      val skipped = input.skip(remaining)
      if skipped <= 0 then
        if input.read() == -1 then return remaining
        else remaining = remaining - 1
      else remaining = remaining - skipped
    0L

  private def deleteRecursively(path: Path): Unit = if Files.exists(path) then
    Using.resource(Files.walk(path)): stream =>
      stream
        .iterator()
        .asScala
        .toVector
        .sortBy(_.getNameCount)
        .reverse
        .foreach(child => Try(Files.deleteIfExists(child)))

private object Sha256:

  def digest(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    digest.map(byte => f"${byte & 0xff}%02x").mkString

private object NioInstallFileSystem extends InstallFileSystem:

  def stageDirectBinary(
      installDir: Path,
      createDirectories: Vector[String],
      executablePath: String,
      bytes: Array[Byte]
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall] =
    val normalizedInstallDir = installDir.toAbsolutePath.normalize()
    createStagingDirectory(normalizedInstallDir).flatMap: stagedInstall =>
      writeStagedDirectBinary(stagedInstall, createDirectories, executablePath, bytes) match
        case Right(())   => Right(stagedInstall)
        case Left(error) =>
          discardStaged(stagedInstall)
          Left(error)

  def stageArchive(
      installDir: Path,
      createDirectories: Vector[String],
      archive: ResolvedArchive,
      bytes: Array[Byte],
      commandExecutor: CommandExecutor
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall] =
    val normalizedInstallDir = installDir.toAbsolutePath.normalize()
    createStagingDirectory(normalizedInstallDir).flatMap: stagedInstall =>
      val result: Either[InstallFileSystemError.StagingFailed, Unit] =
        stageCreateDirectories(stagedInstall, createDirectories) match
          case Left(error) => Left(error)
          case Right(())   =>
            ArchiveExtractor.extract(archive, bytes, stagedInstall.stagingDir, commandExecutor)
              .left
              .map(message => InstallFileSystemError.StagingFailed(message))
      result match
        case Right(())   => Right(stagedInstall)
        case Left(error) =>
          discardStaged(stagedInstall)
          Left(error)

  def applyExecutableModes(
      stagedInstall: StagedInstall,
      executables: Vector[ExecutableModeRequest]
  ): Either[InstallFileSystemError.ModeApplicationFailed, Unit] =
    val errors: Vector[Either[InstallFileSystemError.ModeApplicationFailed, Unit]] =
      executables.map(applyExecutableMode(stagedInstall, _))

    errors.collectFirst:
      case Left(error) => error
    match
      case Some(error) =>
        discardStaged(stagedInstall)
        Left(error)
      case None => Right(())

  def replaceInstall(
      stagedInstall: StagedInstall
  ): Either[InstallFileSystemError.ReplacementFailed, Unit] =
    replaceInstallDirectory(stagedInstall) match
      case Right(())   => Right(())
      case Left(error) =>
        discardStaged(stagedInstall)
        Left(error)

  def discardStaged(stagedInstall: StagedInstall): Unit =
    deleteRecursively(stagedInstall.stagingDir)

  private def createStagingDirectory(
      installDir: Path
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall] = Try:
    val parent = Option(installDir.getParent).getOrElse(Path.of("").toAbsolutePath.normalize())
    val _      = Files.createDirectories(parent)
    // Staging lives next to the final install so the later move can be as atomic as the filesystem
    // allows and avoids cross-filesystem replacement surprises.
    val prefix = s".${Option(installDir.getFileName).map(_.toString).getOrElse("install")}.stage-"
    StagedInstall(Files.createTempDirectory(parent, prefix), installDir)
  match
    case Success(stagedInstall) => Right(stagedInstall)
    case Failure(error)         => Left(InstallFileSystemError.StagingFailed(error.getMessage))

  private def writeStagedDirectBinary(
      stagedInstall: StagedInstall,
      createDirectories: Vector[String],
      executablePath: String,
      bytes: Array[Byte]
  ): Either[InstallFileSystemError.StagingFailed, Unit] =
    stageCreateDirectories(stagedInstall, createDirectories).flatMap: _ =>
      val binaryWrite: Either[String, Unit] =
        resolveInside(stagedInstall.stagingDir, executablePath).flatMap: path =>
          writeBinary(path, bytes)

      binaryWrite.left.map(InstallFileSystemError.StagingFailed.apply)

  private def stageCreateDirectories(
      stagedInstall: StagedInstall,
      createDirectories: Vector[String]
  ): Either[InstallFileSystemError.StagingFailed, Unit] =
    val directoryWrites: Vector[Either[String, Unit]] = createDirectories.map: directory =>
      resolveInside(stagedInstall.stagingDir, directory).flatMap: path =>
        Try(Files.createDirectories(path)) match
          case Success(_)     => Right(())
          case Failure(error) => Left(error.getMessage)

    val failures = directoryWrites.flatMap(stagingFailure)

    failures.headOption match
      case Some(error) => Left(error)
      case None        => Right(())

  private def stagingFailure(
      result: Either[String, Unit]
  ): Option[InstallFileSystemError.StagingFailed] = result match
    case Left(message) => Some(InstallFileSystemError.StagingFailed(message))
    case Right(())     => None

  private def applyExecutableMode(
      stagedInstall: StagedInstall,
      executable: ExecutableModeRequest
  ): Either[InstallFileSystemError.ModeApplicationFailed, Unit] =
    val executablePath = stagedInstall.stagingDir.resolve(executable.path).normalize()
    // Mode changes are confined to the staged tree, never the previous live install.
    if !executablePath.startsWith(stagedInstall.stagingDir) then
      Left(
        InstallFileSystemError.ModeApplicationFailed(
          executable.path,
          executable.mode.octal,
          "path escapes staging directory"
        )
      )
    else
      Try(
        Files.setPosixFilePermissions(executablePath, executable.mode.permissions.asJava)
      ) match
        case Success(_)     => Right(())
        case Failure(error) => Left(
            InstallFileSystemError.ModeApplicationFailed(
              executable.path,
              executable.mode.octal,
              error.getMessage
            )
          )

  private def writeBinary(path: Path, bytes: Array[Byte]): Either[String, Unit] = Try:
    Option(path.getParent).foreach: parent =>
      Files.createDirectories(parent)
    val _ = Files.write(
      path,
      bytes,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    )
  match
    case Success(_)     => Right(())
    case Failure(error) => Left(error.getMessage)

  private def resolveInside(root: Path, relative: String): Either[String, Path] =
    val input = Path.of(relative)
    if input.isAbsolute then Left(s"path must be relative: $relative")
    else
      val resolved = root.resolve(input).normalize()
      if resolved.startsWith(root) then Right(resolved)
      else Left(s"path escapes staging directory: $relative")

  private def replaceInstallDirectory(
      stagedInstall: StagedInstall
  ): Either[InstallFileSystemError.ReplacementFailed, Unit] =
    val installDir = stagedInstall.installDir
    val parent     = Option(installDir.getParent).getOrElse(Path.of("").toAbsolutePath.normalize())
    val backupPrefix =
      s".${Option(installDir.getFileName).map(_.toString).getOrElse("install")}.backup-"

    val prepared = Try:
      val backupDir = Files.createTempDirectory(parent, backupPrefix)
      Files.delete(backupDir)
      backupDir

    prepared match
      case Failure(error)     => Left(InstallFileSystemError.ReplacementFailed(error.getMessage))
      case Success(backupDir) => replaceWithBackup(stagedInstall, installDir, backupDir)

  private def replaceWithBackup(
      stagedInstall: StagedInstall,
      installDir: Path,
      backupDir: Path
  ): Either[InstallFileSystemError.ReplacementFailed, Unit] =
    val hadExisting = Files.exists(installDir)
    val result      = Try:
      if hadExisting then
        val _ = Files.move(installDir, backupDir, StandardCopyOption.REPLACE_EXISTING)
      val _ = Files.move(stagedInstall.stagingDir, installDir, StandardCopyOption.REPLACE_EXISTING)

    result match
      case Success(_) =>
        deleteRecursively(backupDir)
        Right(())
      case Failure(error) =>
        // If the final move fails after moving the old install aside, attempt to restore it so a
        // failed upgrade does not silently leave the tool missing.
        val restoreError = restoreBackup(installDir, backupDir, hadExisting)
        val message      = restoreError match
          case Some(restore) => s"${error.getMessage}; rollback failed: $restore"
          case None          => error.getMessage
        Left(InstallFileSystemError.ReplacementFailed(message))

  private def restoreBackup(
      installDir: Path,
      backupDir: Path,
      hadExisting: Boolean
  ): Option[String] =
    if !hadExisting || !Files.exists(backupDir) then None
    else
      Try:
        if Files.exists(installDir) then deleteRecursively(installDir)
        val _ = Files.move(backupDir, installDir, StandardCopyOption.REPLACE_EXISTING)
      match
        case Success(_)     => None
        case Failure(error) => Some(error.getMessage)

  private def deleteRecursively(path: Path): Unit = if Files.exists(path) then
    Using.resource(Files.walk(path)): stream =>
      stream
        .iterator()
        .asScala
        .toVector
        .sortBy(_.getNameCount)
        .reverse
        .foreach(child => Try(Files.deleteIfExists(child)))
