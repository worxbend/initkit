package binstaller.core

import java.time.Duration

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

private[core] final case class InstallerEventContext(
    observer: InstallerEventObserver,
    startedAtNanos: Long
):
  def elapsedTime: Duration = Duration.ofNanos(System.nanoTime() - startedAtNanos)

  def emit(event: Duration => InstallerEvent): Unit = observer.onEvent(event(elapsedTime))

private[core] object InstallerEventContext:

  def start(observer: InstallerEventObserver): InstallerEventContext =
    InstallerEventContext(observer, System.nanoTime())

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
