package binstaller.tui

import binstaller.config.SymlinkPrivilege
import binstaller.core.BinaryInstallerService
import binstaller.core.CoreModule
import binstaller.core.DownloadProgressStatus
import binstaller.core.HttpTextClient
import binstaller.core.InstallerEvent
import binstaller.core.InstallerEventObserver
import binstaller.core.InstallerOptions
import binstaller.core.InstallerPhase
import binstaller.core.InstallerResult
import binstaller.core.InstallerRunStatus
import binstaller.core.ResolvedArchive
import binstaller.core.ResolvedDownload
import binstaller.core.ResolvedChecksum
import binstaller.core.ResolvedChecksumSource
import binstaller.core.ResolvedExtractMapping
import binstaller.core.ResolvedPlanSnapshot
import binstaller.core.ResolvedSymlink
import binstaller.core.ResolvedTool
import binstaller.core.ResolvedVersion
import binstaller.core.RenderSafety
import binstaller.core.ResolvePlanError
import binstaller.core.SensitiveValueRedactions
import binstaller.core.ToolResultStatus
import binstaller.core.UrlProvenance

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.control.NonFatal

/** Explicit terminal UI entrypoints for plan and apply workflows. */
object TuiModule:
  /** Module path used by CLI and tests to identify the TUI layer. */
  def modulePath: Vector[String] = CoreModule.modulePath :+ "tui"

  /** Start a TUI workflow with production HTTP and terminal dependencies. */
  def start(request: TuiRequest): InstallerResult =
    startInteractive(request, HttpTextClient.jdk, PlanningTuiSettings.default, SystemTuiTerminal())

  /** Render a deterministic non-interactive TUI frame with injected HTTP/settings. */
  def start(
      request: TuiRequest,
      httpTextClient: HttpTextClient,
      settings: PlanningTuiSettings
  ): InstallerResult = request.mode match
    case TuiMode.Plan  => startPlanning(request, httpTextClient, settings)
    case TuiMode.Apply => startApplyExecution(
        request,
        BinaryInstallerService.resolving(httpTextClient),
        ExecutionTuiSettings.fromPlanning(settings)
      )

  /** Start a TUI workflow through an injectable terminal boundary. */
  def startInteractive(
      request: TuiRequest,
      httpTextClient: HttpTextClient,
      settings: PlanningTuiSettings,
      terminal: TuiTerminal
  ): InstallerResult = request.mode match
    case TuiMode.Plan  => startPlanningInteractive(request, httpTextClient, settings, terminal)
    case TuiMode.Apply => startApplyExecutionInteractive(
        request,
        BinaryInstallerService.resolving(httpTextClient),
        ExecutionTuiSettings.fromPlanning(settings),
        terminal
      )

  private def startPlanning(
      request: TuiRequest,
      httpTextClient: HttpTextClient,
      settings: PlanningTuiSettings
  ): InstallerResult = ResolvedPlanSnapshot.resolve(request.options, httpTextClient) match
    case Left(error) => InstallerResult(
        s"binstaller ${request.entrypointName}" +: ResolvePlanError.renderLines(error),
        1
      )
    case Right(snapshot) =>
      val model = PlanningTuiModel.fromAppState(TuiAppState.initial(snapshot, settings))
      InstallerResult(PlanningTuiRenderer.render(model), 0)

  private def startPlanningInteractive(
      request: TuiRequest,
      httpTextClient: HttpTextClient,
      settings: PlanningTuiSettings,
      terminal: TuiTerminal
  ): InstallerResult = ResolvedPlanSnapshot.resolve(request.options, httpTextClient) match
    case Left(error) => InstallerResult(
        s"binstaller ${request.entrypointName}" +: ResolvePlanError.renderLines(error),
        1
      )
    case Right(snapshot) =>
      val initial = PlanningTuiState.initial(snapshot, settings)
      if terminal.isInteractive then PlanningTuiSession.run(initial, terminal)
      else
        val lines = PlanningTuiRenderer.render(initial.toModel) :+
          "non-interactive terminal detected; rendered a static TUI frame"
        InstallerResult(lines, 0)

  private def startApplyExecution(
      request: TuiRequest,
      service: BinaryInstallerService,
      settings: ExecutionTuiSettings
  ): InstallerResult =
    val observer = CollectingExecutionTuiObserver(request, settings)
    val result   = service.applyWithEvents(request.options, observer)
    val state    = observer.state.withResult(result)
    InstallerResult(ExecutionTuiRenderer.render(state.toModel), result.exitCode)

  private def startApplyExecutionInteractive(
      request: TuiRequest,
      service: BinaryInstallerService,
      settings: ExecutionTuiSettings,
      terminal: TuiTerminal
  ): InstallerResult =
    if !terminal.isInteractive then
      val result = startApplyExecution(request, service, settings)
      result.copy(lines =
        result.lines :+ "non-interactive terminal detected; rendered a static execution TUI frame"
      )
    else
      val observer = RenderingExecutionTuiObserver(request, settings, terminal)
      try
        terminal.open()
        observer.renderCurrent()
        val result = service.applyWithEvents(request.options, observer)
        observer.finish(result)
        result.copy(lines = Vector.empty)
      finally terminal.close()

/** TUI workflow mode selected by the CLI. */
enum TuiMode:
  case Plan, Apply

  /** Matching non-interactive command name. */
  def commandName: String = this match
    case Plan  => "plan"
    case Apply => "apply"

/** Request passed from CLI to the TUI layer. */
final case class TuiRequest(
    mode: TuiMode,
    options: InstallerOptions,
    entrypoint: Option[String] = None
):
  /** User-facing command label for diagnostics. */
  def entrypointName: String = entrypoint.getOrElse(s"${mode.commandName} --tui")

/** Terminal viewport used by deterministic renderers. */
final case class TuiViewport(width: Int, height: Int)

/** Viewport constructors. */
object TuiViewport:
  /** Default viewport used when terminal size cannot be detected. */
  val default: TuiViewport = TuiViewport(120, 36)

/** Pure planning-renderer settings used by static and interactive TUI paths. */
final case class PlanningTuiSettings(
    viewport: TuiViewport,
    appVersion: String,
    hostSummary: String,
    selectedIndex: Int,
    filter: Option[String],
    filterEditing: Boolean,
    focusedPane: TuiPane,
    detailScroll: Int,
    logScroll: Int,
    helpOpen: Boolean,
    logs: Vector[String]
)

/** Planning settings constructors. */
object PlanningTuiSettings:

  /** Default planning TUI settings for the production entrypoint. */
  def default: PlanningTuiSettings = PlanningTuiSettings(
    viewport = TuiViewport.default,
    appVersion = "dev",
    hostSummary = TuiHost.currentSummary,
    selectedIndex = 0,
    filter = None,
    filterEditing = false,
    focusedPane = TuiPane.Plan,
    detailScroll = 0,
    logScroll = 0,
    helpOpen = false,
    logs = Vector.empty
  )

/** Header data for the planning TUI frame. */
final case class PlanningTuiHeader(
    appName: String,
    appVersion: String,
    mode: String,
    manifestName: String,
    manifestKind: String,
    configPath: String,
    stateFilePath: Option[String],
    hostSummary: String,
    selectionText: String,
    filterText: String
)

/** One rendered row in the planning TUI table. */
final case class PlanningTuiRow(
    index: Int,
    selected: Boolean,
    status: PlanningTuiStatus,
    name: String,
    kind: String,
    version: String,
    installDir: String,
    checksumState: String,
    riskMarkers: Vector[String]
)

/** Detail pane content for the selected planning row. */
final case class PlanningTuiDetail(
    name: String,
    lines: Vector[String]
)

/** Complete deterministic model consumed by [[PlanningTuiRenderer]]. */
final case class PlanningTuiModel(
    viewport: TuiViewport,
    header: PlanningTuiHeader,
    focusedPane: TuiPane,
    rows: Vector[PlanningTuiRow],
    detail: Option[PlanningTuiDetail],
    detailScroll: Int,
    logs: Vector[String],
    logScroll: Int,
    helpOpen: Boolean,
    footer: String,
    keybar: String
)

/** Planning TUI model derivation helpers. */
object PlanningTuiModel:

  /** Build a render model from a resolved plan snapshot and current UI settings. */
  def fromSnapshot(
      snapshot: ResolvedPlanSnapshot,
      settings: PlanningTuiSettings
  ): PlanningTuiModel = fromAppState(TuiAppState.initial(snapshot, settings))

  /** Build a render model from the unified TUI application state. */
  def fromAppState(state: TuiAppState): PlanningTuiModel =
    val visibleEntries = state.visibleEntries
    val selectedIndex  = clampedIndex(state.selectedIndex, visibleEntries)
    val selectedEntry  = visibleEntries.lift(selectedIndex)
    val rows           = visibleEntries.zipWithIndex.map:
      case (entry, index) => rowForTool(
          index,
          selected = index == selectedIndex,
          entry.tool,
          state.snapshot.plan.redactions
        )
    val header = PlanningTuiHeader(
      appName = state.header.appName,
      appVersion = state.header.appVersion,
      mode = state.mode.label,
      manifestName = state.header.profileName,
      manifestKind = state.header.manifestKind,
      configPath = state.header.configPath,
      stateFilePath = state.header.stateFilePath,
      hostSummary = state.header.hostSummary,
      selectionText = state.header.selectionText,
      filterText = state.filter.displayText
    )
    val logs   = state.logs
    val layout = PlanningTuiLayout.forViewport(state.viewport)
    PlanningTuiModel(
      viewport = state.viewport,
      header = header,
      focusedPane = state.focus,
      rows = rows,
      detail =
        selectedEntry.map(entry => detailForTool(entry.tool, state.snapshot.plan.redactions)),
      detailScroll = clampScroll(
        state.detailScroll,
        selectedEntry.map(entry => detailForTool(entry.tool, state.snapshot.plan.redactions)).fold(
          0
        )(_.lines.size),
        layout.detailBodyHeight
      ),
      logs = state.logs,
      logScroll = clampScroll(state.logScroll, logs.size, layout.logBodyHeight),
      helpOpen = state.helpOpen,
      footer = footerText(state.snapshot, visibleEntries.map(_.tool)),
      keybar = "tab focus | shift-tab/b back | arrows select/scroll | / filter | ? help | q quit"
    )

  /** Filter TUI plan entries by name or description using a case-insensitive contains match. */
  def filterEntries(
      entries: Vector[TuiPlanEntry],
      filter: Option[String]
  ): Vector[TuiPlanEntry] = filter.map(_.trim).filter(_.nonEmpty) match
    case None        => entries
    case Some(value) => entries.filter(entry => matchesFilter(entry.tool, value))

  /** Filter resolved tools by name or description using a case-insensitive contains match. */
  def filterTools(
      tools: Vector[ResolvedTool],
      filter: Option[String]
  ): Vector[ResolvedTool] = filter.map(_.trim).filter(_.nonEmpty) match
    case None        => tools
    case Some(value) => tools.filter(matchesFilter(_, value))

  private def matchesFilter(tool: ResolvedTool, value: String): Boolean =
    val needle = value.toLowerCase
    tool.name.toLowerCase.contains(needle) ||
    tool.description.exists(_.toLowerCase.contains(needle))

  private def clampedIndex(index: Int, entries: Vector[TuiPlanEntry]): Int =
    if entries.isEmpty then 0 else index.max(0).min(entries.size - 1)

  private def clampScroll(offset: Int, total: Int, window: Int): Int =
    offset.max(0).min((total - window).max(0))

  private def rowForTool(
      index: Int,
      selected: Boolean,
      tool: ResolvedTool,
      redactions: SensitiveValueRedactions
  ): PlanningTuiRow =
    val risks  = riskMarkers(tool)
    val status =
      if selected then PlanningTuiStatus.Active
      else if risks.nonEmpty then PlanningTuiStatus.Warning
      else PlanningTuiStatus.Inactive
    PlanningTuiRow(
      index = index + 1,
      selected = selected,
      status = status,
      name = safe(tool.name, redactions),
      kind = "binary-tool",
      version = safe(ResolvedVersion.render(tool.version), redactions),
      installDir = safe(tool.installDir, redactions),
      checksumState = checksumState(tool.download),
      riskMarkers = risks
    )

  private def detailForTool(
      tool: ResolvedTool,
      redactions: SensitiveValueRedactions
  ): PlanningTuiDetail = PlanningTuiDetail(
    name = safe(tool.name, redactions),
    lines = safeLines(
      Vector(
        s"name: ${tool.name}",
        s"description: ${tool.description.getOrElse("not provided")}",
        s"version: ${ResolvedVersion.render(tool.version)}",
        s"install dir: ${tool.installDir}",
        s"download url: ${tool.download.url}",
        s"download file: ${joinPath(tool.installDir, tool.download.filename)}",
        s"checksum: ${checksumDetail(tool.download)}"
      ) ++ versionProvenanceLines(tool.version) ++ archiveLines(tool.download.archive) ++
        symlinkLines(tool) ++ dryRunPreview(tool),
      redactions
    )
  )

  private def safe(value: String, redactions: SensitiveValueRedactions): String =
    RenderSafety.display(value, redactions)

  private def safeLines(
      lines: Vector[String],
      redactions: SensitiveValueRedactions
  ): Vector[String] = RenderSafety.displayLines(lines, redactions)

  private def versionProvenanceLines(version: ResolvedVersion): Vector[String] = version match
    case ResolvedVersion.Concrete(_, provenance) =>
      UrlProvenance.redirectDetailLines("version resolver", provenance)
    case ResolvedVersion.DynamicLatestUrl(_) => Vector.empty

  private def archiveLines(archive: Option[ResolvedArchive]): Vector[String] = archive match
    case None        => Vector("archive: none")
    case Some(value) => Vector(s"archive: ${value.original.archiveType.value}") ++
        mappingLines("archive file", value.files) ++
        mappingLines("archive directory", value.directories)

  private def mappingLines(
      label: String,
      mappings: Vector[ResolvedExtractMapping]
  ): Vector[String] =
    if mappings.isEmpty then Vector.empty
    else mappings.map(mapping => s"$label: ${mapping.from} -> ${mapping.to}")

  private def symlinkLines(tool: ResolvedTool): Vector[String] =
    if tool.symlinks.isEmpty then Vector("symlinks: none")
    else Vector("symlinks:") ++ tool.symlinks.map(renderSymlink(tool, _))

  private def renderSymlink(tool: ResolvedTool, symlink: ResolvedSymlink): String =
    val privilege = symlink.privilege match
      case SymlinkPrivilege.User => "local"
      case SymlinkPrivilege.Sudo => "sudo"
    s"  $privilege: ${absoluteOrInstallPath(tool.installDir, symlink.target)} -> " +
      absoluteOrInstallPath(tool.installDir, symlink.path)

  private def dryRunPreview(tool: ResolvedTool): Vector[String] =
    val strategy = tool.download.archive match
      case Some(archive) => s"extract ${archive.original.archiveType.value} archive mappings"
      case None          => tool.executables.headOption match
          case Some(executable) =>
            s"place direct binary at ${joinPath(tool.installDir, executable.path)}"
          case None => "no executable target configured"
    Vector(
      "dry-run operation preview:",
      s"  1. create install directory ${tool.installDir}",
      s"  2. download ${tool.download.url} to ${joinPath(tool.installDir, tool.download.filename)}",
      s"  3. ${checksumOperation(tool.download)}",
      s"  4. $strategy",
      "  5. apply executable modes and replace install directory"
    ) ++ tool.symlinks.zipWithIndex.map:
      case (symlink, index) => s"  ${index + 6}. ${symlinkCommand(tool, symlink)}"

  private def symlinkCommand(tool: ResolvedTool, symlink: ResolvedSymlink): String =
    val destination = absoluteOrInstallPath(tool.installDir, symlink.path)
    val target      = absoluteOrInstallPath(tool.installDir, symlink.target)
    symlink.privilege match
      case SymlinkPrivilege.User => s"ln -sfn '$target' '$destination'"
      case SymlinkPrivilege.Sudo => s"sudo ln -sfn '$target' '$destination'"

  private def footerText(snapshot: ResolvedPlanSnapshot, tools: Vector[ResolvedTool]): String =
    val sudoSymlinks     = tools.flatMap(_.symlinks).count(_.privilege == SymlinkPrivilege.Sudo)
    val missingChecksums = tools.count(_.download.checksum.isEmpty)
    val state            = snapshot.stateFilePath.getOrElse("not configured")
    s"tools ${tools.size} | sudo symlinks $sudoSymlinks | missing checksums $missingChecksums | state $state"

  private def riskMarkers(tool: ResolvedTool): Vector[String] =
    val checksumRisk = tool.download.checksum match
      case None    => Vector("no-checksum")
      case Some(_) => Vector.empty
    checksumRisk ++ dynamicVersionRisk(tool.version) ++ sudoRisk(tool)

  private def dynamicVersionRisk(version: ResolvedVersion): Vector[String] = version match
    case ResolvedVersion.Concrete(_, _)      => Vector.empty
    case ResolvedVersion.DynamicLatestUrl(_) => Vector("dynamic-version")

  private def sudoRisk(tool: ResolvedTool): Vector[String] =
    if tool.symlinks.exists(_.privilege == SymlinkPrivilege.Sudo) then Vector("sudo")
    else Vector.empty

  private def checksumState(download: ResolvedDownload): String = download.checksum match
    case Some(value) if ResolvedChecksum.isDiscovered(value) => s"${value.algorithm.value}*"
    case Some(value)                                         => value.algorithm.value
    case None                                                => "missing"

  private def checksumDetail(download: ResolvedDownload): String = download.checksum match
    case Some(value) => s"${value.algorithm.value} ${value.value} (${checksumStatus(value)})"
    case None        => "missing (not configured)"

  private def checksumOperation(download: ResolvedDownload): String = download.checksum match
    case Some(value) => s"verify ${value.algorithm.value} checksum ${value.value}"
    case None        => "skip checksum verification because none is configured"

  private def checksumStatus(checksum: ResolvedChecksum): String = checksum.source match
    case ResolvedChecksumSource.Configured                        => "configured"
    case ResolvedChecksumSource.Discovered(url, file, provenance) =>
      s"discovered from $url for $file" + UrlProvenance.redirectSuffix(Some(provenance))

  private def absoluteOrInstallPath(installDir: String, path: String): String =
    if path.startsWith("/") then path else joinPath(installDir, path)

  private def joinPath(parent: String, child: String): String =
    if parent.endsWith("/") then s"$parent$child" else s"$parent/$child"

/** Pure execution-renderer settings derived from planning settings. */
final case class ExecutionTuiSettings(
    viewport: TuiViewport,
    appVersion: String,
    hostSummary: String,
    spinnerFrame: Int,
    logs: Vector[String]
)

/** Execution settings constructors. */
object ExecutionTuiSettings:

  /** Derive execution settings from planning settings so CLI flags configure both views. */
  def fromPlanning(settings: PlanningTuiSettings): ExecutionTuiSettings = ExecutionTuiSettings(
    viewport = settings.viewport,
    appVersion = settings.appVersion,
    hostSummary = settings.hostSummary,
    spinnerFrame = 0,
    logs = settings.logs
  )

/** Header data for the apply execution TUI frame. */
final case class ExecutionTuiHeader(
    appName: String,
    appVersion: String,
    mode: String,
    configPath: String,
    stateFilePath: Option[String],
    hostSummary: String,
    elapsedText: String
)

/** Current active tool shown in the execution TUI. */
final case class ExecutionActiveTool(
    name: String,
    phase: InstallerPhase,
    downloadedBytes: Option[Long],
    totalBytes: Option[Long],
    elapsedTime: Duration
)

/** Completed, failed, or skipped tool row in the execution TUI. */
final case class ExecutionToolRow(
    name: String,
    status: PlanningTuiStatus,
    summary: String,
    elapsedTime: Duration
)

/** Complete deterministic model consumed by [[ExecutionTuiRenderer]]. */
final case class ExecutionTuiModel(
    viewport: TuiViewport,
    header: ExecutionTuiHeader,
    active: Option[ExecutionActiveTool],
    rows: Vector[ExecutionToolRow],
    logs: Vector[String],
    dryRunLines: Vector[String],
    summary: Option[String],
    spinnerFrame: Int,
    keybar: String
)

/** Event-accumulating execution state for live and static apply TUI rendering. */
final case class ExecutionTuiState(
    request: TuiRequest,
    viewport: TuiViewport,
    appVersion: String,
    hostSummary: String,
    spinnerFrame: Int,
    stateFilePath: Option[String],
    active: Option[ExecutionActiveTool],
    rows: Vector[ExecutionToolRow],
    logs: Vector[String],
    dryRunLines: Vector[String],
    summary: Option[InstallerEvent.Summary],
    elapsedTime: Duration
):

  /** Convert accumulated execution state into a deterministic render model. */
  def toModel: ExecutionTuiModel =
    val summaryLine = summary.map: value =>
      val status = value.status match
        case InstallerRunStatus.Succeeded => "succeeded"
        case InstallerRunStatus.Failed    => "failed"
      s"$status | installed ${value.installed} | failed ${value.failed} | " +
        s"skipped ${value.skipped} | exit ${value.exitCode}"
    ExecutionTuiModel(
      viewport = viewport,
      header = ExecutionTuiHeader(
        appName = "binstaller",
        appVersion = appVersion,
        mode = request.mode.commandName,
        configPath = request.options.configPath,
        stateFilePath = stateFilePath,
        hostSummary = hostSummary,
        elapsedText = ExecutionTuiRenderer.formatDuration(elapsedTime)
      ),
      active = active,
      rows = rows,
      logs = logs,
      dryRunLines = dryRunLines,
      summary = summaryLine,
      spinnerFrame = spinnerFrame,
      keybar = "terminal restored after apply completes"
    )

  /** Incorporate one renderer-agnostic core event into execution UI state. */
  def onEvent(event: InstallerEvent): ExecutionTuiState =
    val nextFrame = spinnerFrame + 1
    event match
      case InstallerEvent.ResolvingStarted(_, elapsed) => copy(
          spinnerFrame = nextFrame,
          active = Some(ExecutionActiveTool(
            "manifest",
            InstallerPhase.Resolving,
            None,
            None,
            elapsed
          )),
          elapsedTime = elapsed
        )
      case InstallerEvent.PlanReady(toolCount, statePath, elapsed) => copy(
          spinnerFrame = nextFrame,
          stateFilePath = statePath,
          active = Some(ExecutionActiveTool(
            s"$toolCount tool${if toolCount == 1 then "" else "s"}",
            InstallerPhase.Planning,
            None,
            None,
            elapsed
          )),
          logs = appendLog(s"plan ready: $toolCount tool${if toolCount == 1 then "" else "s"}"),
          elapsedTime = elapsed
        )
      case InstallerEvent.ToolStarted(toolName, phase, elapsed) => copy(
          spinnerFrame = nextFrame,
          active = Some(ExecutionActiveTool(toolName, phase, None, None, elapsed)),
          logs = appendLog(s"$toolName: started ${phaseText(phase)}"),
          elapsedTime = elapsed
        )
      case InstallerEvent.ToolPhaseChanged(toolName, phase, elapsed) => copy(
          spinnerFrame = nextFrame,
          active = Some(currentActive(toolName, phase, elapsed)),
          logs = appendLog(s"$toolName: ${phaseText(phase)}"),
          elapsedTime = elapsed
        )
      case InstallerEvent.DownloadProgress(toolName, _, downloaded, total, status, elapsed) =>
        val statusText = status match
          case DownloadProgressStatus.Started  => "download started"
          case DownloadProgressStatus.Advanced => "download advanced"
          case DownloadProgressStatus.Finished => "download finished"
        copy(
          spinnerFrame = nextFrame,
          active = Some(ExecutionActiveTool(
            toolName,
            InstallerPhase.Downloading,
            Some(downloaded),
            total,
            elapsed
          )),
          logs = appendLog(
            s"$toolName: $statusText ${ExecutionTuiRenderer.byteText(downloaded, total)}"
          ),
          elapsedTime = elapsed
        )
      case InstallerEvent.LogLine(toolName, line, elapsed) =>
        val prefix = toolName.map(name => s"$name: ").getOrElse("")
        copy(
          spinnerFrame = nextFrame,
          logs = appendLog(prefix + line),
          elapsedTime = elapsed
        )
      case InstallerEvent.ToolResult(toolName, status, installDir, failureSummary, elapsed) =>
        val rowStatus = status match
          case ToolResultStatus.Completed => PlanningTuiStatus.Completed
          case ToolResultStatus.Failed    => PlanningTuiStatus.Failed
        val rowSummary = status match
          case ToolResultStatus.Completed =>
            installDir.map(path => s"installed to $path").getOrElse("completed")
          case ToolResultStatus.Failed => failureSummary.getOrElse("failed")
        copy(
          spinnerFrame = nextFrame,
          active = None,
          rows = rows :+ ExecutionToolRow(toolName, rowStatus, rowSummary, elapsed),
          logs = appendLog(s"$toolName: $rowSummary"),
          elapsedTime = elapsed
        )
      case InstallerEvent.ToolSkipped(toolName, reason, statePath, elapsed) => copy(
          spinnerFrame = nextFrame,
          stateFilePath = statePath.orElse(stateFilePath),
          active = None,
          rows = rows :+ ExecutionToolRow(toolName, PlanningTuiStatus.Skipped, reason, elapsed),
          logs = appendLog(s"$toolName: skipped - $reason"),
          elapsedTime = elapsed
        )
      case value @ InstallerEvent.Summary(_, _, _, _, _, statePath, elapsed) => copy(
          spinnerFrame = nextFrame,
          active = None,
          stateFilePath = statePath.orElse(stateFilePath),
          summary = Some(value),
          elapsedTime = elapsed
        )

  /** Attach final command output, preserving dry-run lines and pre-tool failures. */
  def withResult(result: InstallerResult): ExecutionTuiState =
    val dryRunResultLines =
      if request.options.dryRun == binstaller.core.DryRunMode.Enabled then result.lines
      else Vector.empty
    val failureLogs =
      if result.exitCode != 0 && rows.isEmpty then result.lines
      else Vector.empty
    copy(
      dryRunLines = dryRunResultLines,
      logs = (logs ++ failureLogs).takeRight(80)
    )

  /** Handle terminal-local inputs relevant to execution rendering. */
  def handle(input: TuiInput): ExecutionTuiState = input match
    case TuiInput.Resize(value) => copy(viewport = value)
    case _                      => this

  private def currentActive(
      toolName: String,
      phase: InstallerPhase,
      elapsed: Duration
  ): ExecutionActiveTool = active.filter(_.name == toolName) match
    case Some(value) => value.copy(phase = phase, elapsedTime = elapsed)
    case None        => ExecutionActiveTool(toolName, phase, None, None, elapsed)

  private def appendLog(line: String): Vector[String] = (logs :+ line).takeRight(80)

  private def phaseText(phase: InstallerPhase): String = phase.toString

/** Execution state constructors. */
object ExecutionTuiState:

  /** Initial execution state before any core events arrive. */
  def initial(request: TuiRequest, settings: ExecutionTuiSettings): ExecutionTuiState =
    ExecutionTuiState(
      request = request,
      viewport = settings.viewport,
      appVersion = settings.appVersion,
      hostSummary = settings.hostSummary,
      spinnerFrame = settings.spinnerFrame,
      stateFilePath = None,
      active = Some(ExecutionActiveTool(
        "manifest",
        InstallerPhase.Resolving,
        None,
        None,
        Duration.ZERO
      )),
      rows = Vector.empty,
      logs = settings.logs,
      dryRunLines = Vector.empty,
      summary = None,
      elapsedTime = Duration.ZERO
    )

private final class CollectingExecutionTuiObserver(
    request: TuiRequest,
    settings: ExecutionTuiSettings
) extends InstallerEventObserver:
  private var currentState: ExecutionTuiState = ExecutionTuiState.initial(request, settings)

  def state: ExecutionTuiState = currentState

  def onEvent(event: InstallerEvent): Unit = currentState = currentState.onEvent(event)

private final class RenderingExecutionTuiObserver(
    request: TuiRequest,
    settings: ExecutionTuiSettings,
    terminal: TuiTerminal
) extends InstallerEventObserver:

  private var currentState: ExecutionTuiState =
    ExecutionTuiState.initial(request, settings.copy(viewport = terminal.viewport))

  def onEvent(event: InstallerEvent): Unit =
    currentState = currentState.onEvent(event)
    renderCurrent()

  def renderCurrent(): Unit =
    currentState = currentState.handle(TuiInput.Resize(terminal.viewport))
    terminal.render(ExecutionTuiRenderer.render(currentState.toModel))

  def finish(result: InstallerResult): Unit =
    currentState = currentState.withResult(result)
    renderCurrent()

/** Deterministic renderer for apply execution TUI frames. */
object ExecutionTuiRenderer:

  /** Render an execution model into terminal rows. */
  def render(model: ExecutionTuiModel): Vector[String] =
    val width  = model.viewport.width.max(1)
    val layout = ExecutionTuiLayout.forViewport(model.viewport)
    header(model, width) ++
      active(model.active, model.spinnerFrame, width) ++
      resultRows(model.rows, layout, width) ++
      dryRun(model.dryRunLines, width) ++
      logs(model.logs, layout, width) ++
      footer(model, width)

  /** Format elapsed time for compact terminal display. */
  def formatDuration(duration: Duration): String =
    val millis = duration.toMillis.max(0L)
    if millis < 1000L then s"${millis}ms"
    else f"${millis.toDouble / 1000.0}%.1fs"

  /** Format downloaded/total byte progress for display. */
  def byteText(downloadedBytes: Long, totalBytes: Option[Long]): String = totalBytes match
    case Some(total) => s"${formatBytes(downloadedBytes)}/${formatBytes(total)}"
    case None        => formatBytes(downloadedBytes)

  private def header(model: ExecutionTuiModel, width: Int): Vector[String] =
    val header = model.header
    Vector(
      PlanningTuiStatus.style(
        PlanningTuiStatus.Active,
        fit(
          s"${header.appName} ${header.appVersion} | mode ${header.mode} execution | " +
            s"elapsed ${header.elapsedText}",
          width
        )
      ),
      fit(s"config ${header.configPath}", width),
      fit(s"state ${header.stateFilePath.getOrElse("not configured")}", width),
      fit(s"host ${header.hostSummary}", width),
      separator(width)
    )

  private def active(
      active: Option[ExecutionActiveTool],
      spinnerFrame: Int,
      width: Int
  ): Vector[String] =
    val lines = active match
      case Some(value) =>
        val progress = progressText(value.downloadedBytes, value.totalBytes)
        Vector(
          paneTitle("Execution", active = true, width),
          fit(
            s"${spinner(spinnerFrame)} current tool ${value.name} | phase ${value.phase} | " +
              s"elapsed ${formatDuration(value.elapsedTime)}",
            width
          ),
          fit(progress, width)
        )
      case None => Vector(
          paneTitle("Execution", active = false, width),
          fit("no active tool", width)
        )
    lines :+ separator(width)

  private def resultRows(
      rows: Vector[ExecutionToolRow],
      layout: ExecutionTuiLayout,
      width: Int
  ): Vector[String] =
    val visible = rows.takeRight(layout.rowBodyHeight)
    val body    =
      if visible.isEmpty then Vector(fit("no completed tools yet", width))
      else visible.map(rowLine(_, width))
    Vector(paneTitle("Completed / Failed", active = false, width)) ++ body ++
      Vector(separator(width))

  private def rowLine(row: ExecutionToolRow, width: Int): String =
    val prefix = row.status match
      case PlanningTuiStatus.Completed => "ok"
      case PlanningTuiStatus.Failed    => "fail"
      case PlanningTuiStatus.Skipped   => "skip"
      case _                           => row.status.label
    val plain = fit(
      s"$prefix ${cell(row.name, 18)} ${cell(formatDuration(row.elapsedTime), 8)} ${row.summary}",
      width
    )
    PlanningTuiStatus.style(row.status, plain)

  private def dryRun(
      lines: Vector[String],
      width: Int
  ): Vector[String] =
    if lines.isEmpty then Vector.empty
    else
      val body = lines.map(line => fit(line, width))
      Vector(paneTitle("Dry-run operations", active = false, width)) ++ body ++
        Vector(separator(width))

  private def logs(lines: Vector[String], layout: ExecutionTuiLayout, width: Int): Vector[String] =
    val visible = lines.takeRight(layout.logBodyHeight)
    val body    =
      if visible.isEmpty then Vector(fit("no recent log lines", width))
      else visible.map(line => fit(line, width))
    Vector(paneTitle("Recent Logs", active = false, width)) ++ body ++ Vector(separator(width))

  private def footer(model: ExecutionTuiModel, width: Int): Vector[String] =
    val status = model.summary.getOrElse("running")
    Vector(
      fit(status, width),
      PlanningTuiStatus.style(PlanningTuiStatus.Active, fit(model.keybar, width))
    )

  private def progressText(downloadedBytes: Option[Long], totalBytes: Option[Long]): String =
    downloadedBytes match
      case Some(downloaded) => totalBytes.filter(_ > 0L) match
          case Some(total) =>
            s"${progressBar(downloaded, total)} ${byteText(downloaded, Some(total))}"
          case None => s"progress bytes ${byteText(downloaded, None)}"
      case None => "progress waiting for tool events"

  private def progressBar(downloadedBytes: Long, totalBytes: Long): String =
    val width  = 28
    val ratio  = (downloadedBytes.toDouble / totalBytes.toDouble).max(0.0).min(1.0)
    val filled = (ratio * width).round.toInt
    val empty  = width - filled
    val pct    = (ratio * 100.0).round.toInt
    s"[${"█" * filled}${"░" * empty}] $pct%"

  private def spinner(frame: Int): String = Vector("|", "/", "-", "\\")(frame.abs % 4)

  private def paneTitle(title: String, active: Boolean, width: Int): String =
    val label = if active then s"$title [active]" else s"$title [idle]"
    val line  = fit(label, width)
    if active then PlanningTuiStatus.style(PlanningTuiStatus.Active, line) else line

  private def formatBytes(bytes: Long): String =
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    if bytes >= gib then f"${bytes / gib}%.1f GiB"
    else if bytes >= mib then f"${bytes / mib}%.1f MiB"
    else if bytes >= kib then f"${bytes / kib}%.1f KiB"
    else s"$bytes B"

  private def separator(width: Int): String = "-" * width

  private def cell(value: String, width: Int): String =
    val clipped = truncate(RenderSafety.terminalLine(value), width)
    clipped + (" " * (width - clipped.length).max(0))

  private def fit(value: String, width: Int): String = cell(value, width)

  private def truncate(value: String, width: Int): String =
    if width <= 0 then ""
    else if value.length <= width then value
    else if width == 1 then "…"
    else s"${value.take(width - 1)}…"

/** Viewport-derived body heights for execution panes. */
final case class ExecutionTuiLayout(
    rowBodyHeight: Int,
    dryRunBodyHeight: Int,
    logBodyHeight: Int
)

/** Execution layout constructors. */
object ExecutionTuiLayout:

  /** Calculate execution pane heights from the current viewport. */
  def forViewport(viewport: TuiViewport): ExecutionTuiLayout =
    val usable = (viewport.height.max(18) - 15).max(6)
    val rows   = usable.min(6).max(2)
    val dryRun = (((usable - rows) * 2) / 3).max(0).min(30)
    val logs   = (usable - rows - dryRun).max(3)
    ExecutionTuiLayout(rows, dryRun, logs)

/** Focusable panes in the planning TUI. */
enum TuiPane(val label: String):
  case Plan    extends TuiPane("plan")
  case Details extends TuiPane("details")
  case Logs    extends TuiPane("logs")

  /** Next pane in forward focus order. */
  def next: TuiPane = this match
    case Plan    => Details
    case Details => Logs
    case Logs    => Plan

  /** Previous pane in backward focus order. */
  def previous: TuiPane = this match
    case Plan    => Logs
    case Details => Plan
    case Logs    => Details

/** Parsed keyboard, resize, and mouse-wheel inputs consumed by the planning state machine. */
enum TuiInput:
  case Tab, BackTab, Up, Down, Left, Right, PageUp, PageDown, Home, End
  case Slash, Question, Enter, Escape, Backspace, Quit, CtrlC
  case Character(value: Char)
  case Resize(viewport: TuiViewport)
  case MouseWheelUp, MouseWheelDown
  case Unknown

/** Compatibility wrapper for planning paths backed by the unified [[TuiAppState]]. */
final case class PlanningTuiState(appState: TuiAppState):

  /** Convert interaction state into a deterministic render model. */
  def toModel: PlanningTuiModel = PlanningTuiModel.fromAppState(appState)

  /** Current terminal viewport. */
  def viewport: TuiViewport = appState.viewport

  /** Current highlighted entry index in the visible list. */
  def selectedIndex: Int = appState.selectedIndex

  /** Current focused pane. */
  def focusedPane: TuiPane = appState.focus

  /** Current detail-pane scroll offset. */
  def detailScroll: Int = appState.detailScroll

  /** Current log-pane scroll offset. */
  def logScroll: Int = appState.logScroll

  /** Whether the help modal is currently visible. */
  def helpOpen: Boolean = appState.helpOpen

  /** Whether the planning session has requested exit. */
  def exitRequested: Boolean = appState.exitRequested

  /** Handle one parsed input event. */
  def handle(input: TuiInput): PlanningTuiState = PlanningTuiState(
    TuiAppController.handle(appState, input)
  )

  /** Clamp scroll offsets to the currently visible detail/log windows. */
  def clampScrolls: PlanningTuiState = PlanningTuiState(TuiAppController.clamp(appState))

  /** Return a state focused on the given pane. */
  def withFocus(pane: TuiPane): PlanningTuiState = PlanningTuiState(appState.copy(focus = pane))

  /** Return a state with an explicit log-pane scroll offset. */
  def withLogScroll(offset: Int): PlanningTuiState =
    PlanningTuiState(appState.copy(logScroll = offset))

/** Planning interaction-state constructors. */
object PlanningTuiState:

  /** Build initial state from a resolved snapshot and renderer settings. */
  def initial(
      snapshot: ResolvedPlanSnapshot,
      settings: PlanningTuiSettings
  ): PlanningTuiState =
    PlanningTuiState(TuiAppController.clamp(TuiAppState.initial(snapshot, settings)))

/** Viewport-derived body heights for planning panes. */
final case class PlanningTuiLayout(
    tableBodyHeight: Int,
    detailBodyHeight: Int,
    logBodyHeight: Int
)

/** Planning layout constructors. */
object PlanningTuiLayout:

  /** Calculate planning pane heights from the current viewport. */
  def forViewport(viewport: TuiViewport): PlanningTuiLayout =
    val usable = (viewport.height.max(18) - 14).max(4)
    val table  = usable.min(7).max(3)
    val detail = ((usable - table) / 2).min(6).max(2)
    val logs   = (usable - table - detail).max(3)
    PlanningTuiLayout(table, detail, logs)

/** Interactive planning TUI session runner. */
object PlanningTuiSession:

  /** Run a deterministic input sequence without touching the terminal. */
  def run(initial: PlanningTuiState, inputs: Vector[TuiInput]): PlanningTuiState =
    PlanningTuiState(TuiAppRunner.run(initial.appState, inputs))

  /** Run against a terminal boundary, restoring terminal state on exit or failure. */
  def run(initial: PlanningTuiState, terminal: TuiTerminal): InstallerResult =
    TuiAppRunner.run(initial.appState, terminal)

/** Terminal boundary used by the interactive TUI session. */
trait TuiTerminal:
  /** Whether this terminal can safely enter an interactive alternate-screen session. */
  def isInteractive: Boolean

  /** Current terminal viewport. */
  def viewport: TuiViewport

  /** Enter raw/alternate-screen mode. */
  def open(): Unit

  /** Render one frame. */
  def render(lines: Vector[String]): Unit

  /** Read one input event, or `None` when the session should exit. */
  def readInput(): Option[TuiInput]

  /** Restore terminal state. Must be idempotent for defensive cleanup. */
  def close(): Unit

private[tui] final class SystemTuiTerminal(
    backend: TuiTerminalBackend = SystemTuiTerminalBackend(),
    out: PrintWriter = PrintWriter(System.out, true)
) extends TuiTerminal:
  private var input: InputStream              = InputStream.nullInputStream()
  private var previousTerminalState: String   = ""
  private var observedViewport: TuiViewport   = TuiViewport.default
  private var terminalOpened: Boolean         = false
  private var rawTerminalStateActive: Boolean = false
  private var inputOpened: Boolean            = false

  def isInteractive: Boolean = System.console() != null

  def viewport: TuiViewport =
    val current = backend.readSize().getOrElse(observedViewport)
    observedViewport = current
    current

  def open(): Unit = if !terminalOpened && !rawTerminalStateActive then
    previousTerminalState = backend.readTerminalState().getOrElse("")
    if backend.enterRawMode() then
      rawTerminalStateActive = true
      try
        input = backend.openInput()
        inputOpened = true
        terminalOpened = true
        // Enter alternate screen, hide the cursor, and enable mouse reporting as one
        // terminal boundary; close() reverses these sequences in a finally block.
        out.print("\u001b[?1049h\u001b[?25l\u001b[?1000h\u001b[?1006h")
        out.flush()
      catch
        case NonFatal(error) =>
          close()
          throw error
    else
      restoreTerminalState()
      throw IllegalStateException("failed to enter raw terminal mode")

  def render(lines: Vector[String]): Unit =
    out.print("\u001b[H\u001b[2J")
    out.print(lines.mkString("\r\n"))
    out.flush()

  def readInput(): Option[TuiInput] = readResizeInput().orElse:
    val first = input.read()
    if first < 0 then readResizeInput().orElse(Some(TuiInput.Unknown))
    else Some(TuiInputParser.parse(first, input))

  def close(): Unit =
    if terminalOpened then
      val _ = Try:
        out.print("\u001b[?1006l\u001b[?1000l\u001b[?25h\u001b[?1049l")
        out.flush()
      terminalOpened = false
    closeInput()
    restoreTerminalState()

  private def closeInput(): Unit = if inputOpened then
    val _ = Try(input.close())
    input = InputStream.nullInputStream()
    inputOpened = false

  private def restoreTerminalState(): Unit = if rawTerminalStateActive then
    if previousTerminalState.nonEmpty then
      val _ = backend.restoreTerminalState(previousTerminalState)
    rawTerminalStateActive = false
    previousTerminalState = ""

  private def readResizeInput(): Option[TuiInput] = backend.readSize() match
    case Some(current) if current != observedViewport =>
      observedViewport = current
      Some(TuiInput.Resize(current))
    case Some(current) =>
      observedViewport = current
      None
    case None => None

private[tui] trait TuiTerminalBackend:
  def readSize(): Option[TuiViewport]

  def readTerminalState(): Option[String]

  def enterRawMode(): Boolean

  def restoreTerminalState(state: String): Boolean

  def openInput(): InputStream

private[tui] final case class TuiProcessSpec(argv: Vector[String], inputFile: File)

private[tui] trait TuiProcessRunner:
  def run(spec: TuiProcessSpec): Option[String]

private[tui] final class SystemTuiProcessRunner(timeout: Duration = Duration.ofSeconds(2))
    extends TuiProcessRunner:

  def run(spec: TuiProcessSpec): Option[String] = Try:
    val builder = ProcessBuilder(spec.argv.asJava)
    builder.redirectInput(spec.inputFile)
    builder.redirectError(ProcessBuilder.Redirect.DISCARD)
    val process = builder.start()
    if process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS) then
      val output = readProcessOutput(process)
      if process.exitValue() == 0 then Some(output) else None
    else
      process.destroyForcibly()
      None
  .toOption.flatten

  private def readProcessOutput(process: Process): String =
    val buffer = ByteArrayOutputStream()
    process.getInputStream.transferTo(buffer)
    buffer.toString(StandardCharsets.UTF_8)

private[tui] final class SystemTuiTerminalBackend(
    processRunner: TuiProcessRunner = SystemTuiProcessRunner(),
    tty: File = File("/dev/tty")
) extends TuiTerminalBackend:

  def readSize(): Option[TuiViewport] = runStty(Vector("size")).flatMap: value =>
    value.trim.split("\\s+").toVector match
      case Vector(rows, columns) =>
        for
          width  <- columns.toIntOption
          height <- rows.toIntOption
        yield TuiViewport(width, height)
      case _ => None

  def readTerminalState(): Option[String] = runStty(Vector("-g")).map(_.trim).filter(_.nonEmpty)

  def enterRawMode(): Boolean = runStty(Vector("raw", "-echo", "min", "0", "time", "1")).isDefined

  def restoreTerminalState(state: String): Boolean = runStty(Vector(state)).isDefined

  def openInput(): InputStream = FileInputStream(tty)

  private def runStty(args: Vector[String]): Option[String] =
    processRunner.run(TuiProcessSpec(Vector("stty") ++ args, tty))

private object TuiInputParser:

  def parse(first: Int, input: InputStream): TuiInput = first match
    case 3                                    => TuiInput.CtrlC
    case 9                                    => TuiInput.Tab
    case 10 | 13                              => TuiInput.Enter
    case 27                                   => parseEscape(input)
    case 47                                   => TuiInput.Slash
    case 63                                   => TuiInput.Question
    case 113                                  => TuiInput.Quit
    case 127 | 8                              => TuiInput.Backspace
    case value if value >= 32 && value <= 126 => TuiInput.Character(value.toChar)
    case _                                    => TuiInput.Unknown

  private def parseEscape(input: InputStream): TuiInput = readAvailable(input) match
    case Vector(91, 65)                            => TuiInput.Up
    case Vector(91, 66)                            => TuiInput.Down
    case Vector(91, 67)                            => TuiInput.Right
    case Vector(91, 68)                            => TuiInput.Left
    case Vector(91, 72)                            => TuiInput.Home
    case Vector(91, 70)                            => TuiInput.End
    case Vector(91, 90)                            => TuiInput.BackTab
    case Vector(91, 53, 126)                       => TuiInput.PageUp
    case Vector(91, 54, 126)                       => TuiInput.PageDown
    case bytes if bytes.startsWith(Vector(91, 60)) => parseMouse(bytes)
    case _                                         => TuiInput.Escape

  private def readAvailable(input: InputStream): Vector[Int] =
    Thread.sleep(2)
    var buffer = Vector.empty[Int]
    while input.available() > 0 do buffer = buffer :+ input.read()
    buffer

  private def parseMouse(bytes: Vector[Int]): TuiInput =
    val text = bytes.map(_.toChar).mkString
    val code = text
      .drop(2)
      .takeWhile(_ != ';')
      .toIntOption
    code match
      case Some(64) => TuiInput.MouseWheelUp
      case Some(65) => TuiInput.MouseWheelDown
      case _        => TuiInput.Unknown

/** Status labels and color categories shared by planning and execution TUI rows. */
enum PlanningTuiStatus(val label: String):
  case Completed extends PlanningTuiStatus("completed")
  case Failed    extends PlanningTuiStatus("failed")
  case Warning   extends PlanningTuiStatus("warning")
  case Active    extends PlanningTuiStatus("active")
  case Skipped   extends PlanningTuiStatus("skipped")
  case Inactive  extends PlanningTuiStatus("inactive")

/** Status ordering and styling helpers. */
object PlanningTuiStatus:

  /** Stable legend ordering used by the planning renderer. */
  val legendOrder: Vector[PlanningTuiStatus] = Vector(
    Completed,
    Failed,
    Warning,
    Active,
    Skipped,
    Inactive
  )

  /** Apply ANSI color styling for one status category. */
  def style(status: PlanningTuiStatus, value: String): String = status match
    case Completed => fansi.Color.Green(value).toString
    case Failed    => fansi.Color.Red(value).toString
    case Warning   => fansi.Color.Yellow(value).toString
    case Active    => fansi.Color.Cyan(value).toString
    case Skipped   => fansi.Color.LightGray(value).toString
    case Inactive  => value

/** Deterministic renderer for planning TUI frames. */
object PlanningTuiRenderer:

  /** Render a planning model into terminal rows. */
  def render(model: PlanningTuiModel): Vector[String] =
    val width  = model.viewport.width.max(1)
    val layout = PlanningTuiLayout.forViewport(model.viewport)
    header(model, width) ++
      table(model.rows, layout, width, model.focusedPane == TuiPane.Plan) ++
      detail(
        model.detail,
        model.detailScroll,
        layout,
        width,
        model.focusedPane == TuiPane.Details
      ) ++
      logs(model.logs, model.logScroll, layout, width, model.focusedPane == TuiPane.Logs) ++
      help(model.helpOpen, width) ++
      footer(model, width)

  private def header(model: PlanningTuiModel, width: Int): Vector[String] =
    val header = model.header
    Vector(
      PlanningTuiStatus.style(
        PlanningTuiStatus.Active,
        fit(
          s"${header.appName} ${header.appVersion} | mode ${header.mode} | " +
            s"manifest ${header.manifestName} (${header.manifestKind})",
          width
        )
      ),
      fit(s"config ${header.configPath}", width),
      fit(s"state ${header.stateFilePath.getOrElse("not configured")}", width),
      fit(
        s"host ${header.hostSummary} | ${header.selectionText} | filter ${header.filterText}",
        width
      ),
      separator(width)
    )

  private def table(
      rows: Vector[PlanningTuiRow],
      layout: PlanningTuiLayout,
      width: Int,
      focused: Boolean
  ): Vector[String] =
    val header = s"${cell("#", 4)} ${cell("status", 10)} ${cell("name", 20)} ${cell("kind", 12)} " +
      s"${cell("version", 18)} ${cell("install dir", 26)} ${cell("checksum", 10)} risk"
    val selectedIndex = rows.indexWhere(_.selected).max(0)
    val firstVisible  = windowStart(selectedIndex, rows.size, layout.tableBodyHeight)
    val visibleRows   = rows.slice(firstVisible, firstVisible + layout.tableBodyHeight)
    val body          =
      if rows.isEmpty then Vector(fit("no plan entries match the active filter", width))
      else visibleRows.map(rowLine(_, width))
    Vector(paneTitle("Plan", focused, None, width), fit(header, width)) ++ body ++
      Vector(separator(width))

  private def rowLine(row: PlanningTuiRow, width: Int): String =
    val marker = if row.selected then ">" else " "
    val risks  = if row.riskMarkers.isEmpty then "none" else row.riskMarkers.mkString(",")
    val plain  = s"$marker ${cell(row.index.toString, 3)} ${cell(row.status.label, 10)} " +
      s"${cell(row.name, 20)} ${cell(row.kind, 12)} ${cell(row.version, 18)} " +
      s"${cell(row.installDir, 26)} ${cell(row.checksumState, 10)} ${truncate(risks, 20)}"
    PlanningTuiStatus.style(row.status, fit(plain, width))

  private def detail(
      detail: Option[PlanningTuiDetail],
      offset: Int,
      layout: PlanningTuiLayout,
      width: Int,
      focused: Boolean
  ): Vector[String] =
    val title = detail.map(value => s"Details: ${value.name}").getOrElse("Details")
    val lines = detail.map(_.lines).getOrElse(Vector("no selected entry"))
    Vector(paneTitle(
      title,
      focused,
      scrollLabel(lines.size, offset, layout.detailBodyHeight),
      width
    )) ++
      scrollBody(lines, offset, layout.detailBodyHeight, width) ++
      Vector(separator(width))

  private def logs(
      lines: Vector[String],
      offset: Int,
      layout: PlanningTuiLayout,
      width: Int,
      focused: Boolean
  ): Vector[String] = Vector(paneTitle(
    "Logs",
    focused,
    scrollLabel(lines.size, offset, layout.logBodyHeight),
    width
  )) ++
    scrollBody(lines, offset, layout.logBodyHeight, width) ++
    Vector(separator(width))

  private def help(open: Boolean, width: Int): Vector[String] =
    if !open then Vector.empty
    else
      Vector(
        separator(width),
        PlanningTuiStatus.style(PlanningTuiStatus.Active, fit("Help", width)),
        fit("Tab cycles plan, details, and logs; Shift+Tab or b cycles backward.", width),
        fit(
          "Plan focus: Up/Down selects rows, PageUp/PageDown jumps, Home/End moves to edges.",
          width
        ),
        fit("Details/log focus: arrows, PageUp/PageDown, Home/End, and mouse wheel scroll.", width),
        fit("/ edits the filter, Enter applies it, Escape cancels editing or closes help.", width),
        fit("q or Ctrl+C exits after restoring the terminal.", width),
        separator(width)
      )

  private def footer(model: PlanningTuiModel, width: Int): Vector[String] =
    val legend = PlanningTuiStatus.legendOrder
      .map(_.label)
      .mkString(" ")
    Vector(
      fit(model.footer, width),
      fit(s"status $legend", width),
      PlanningTuiStatus.style(PlanningTuiStatus.Active, fit(model.keybar, width))
    )

  private def separator(width: Int): String = "-" * width

  private def paneTitle(
      title: String,
      focused: Boolean,
      scroll: Option[String],
      width: Int
  ): String =
    val focus = if focused then "focus" else "idle"
    val text  = scroll match
      case Some(value) => s"$title [$focus] $value"
      case None        => s"$title [$focus]"
    val line = fit(text, width)
    if focused then PlanningTuiStatus.style(PlanningTuiStatus.Active, line) else line

  private def scrollLabel(total: Int, offset: Int, window: Int): Option[String] =
    if total <= window then None
    else Some(s"scroll ${offset + 1}-${(offset + window).min(total)}/$total")

  private def scrollBody(
      lines: Vector[String],
      offset: Int,
      height: Int,
      width: Int
  ): Vector[String] =
    val clippedOffset = offset.max(0).min((lines.size - height).max(0))
    val visible       = lines.slice(clippedOffset, clippedOffset + height)
    val padded        = visible ++ Vector.fill((height - visible.size).max(0))("")
    val markers       = scrollbarMarkers(lines.size, clippedOffset, height)
    padded.zip(markers).map:
      case (line, marker) =>
        if width <= 2 then fit(line, width)
        else fit(line, width - 2) + " " + marker

  private def scrollbarMarkers(total: Int, offset: Int, height: Int): Vector[String] =
    if total <= height then Vector.fill(height)(" ")
    else
      val travel     = (height - 1).max(1)
      val maxOffset  = (total - height).max(1)
      val thumbIndex = Math.round(offset.toDouble / maxOffset.toDouble * travel).toInt
      Vector.tabulate(height): index =>
        if index == thumbIndex then "█" else "│"

  private def windowStart(selectedIndex: Int, total: Int, height: Int): Int =
    val maxStart = (total - height).max(0)
    (selectedIndex - height + 1).max(0).min(maxStart)

  private def cell(value: String, width: Int): String =
    val clipped = truncate(RenderSafety.terminalLine(value), width)
    clipped + (" " * (width - clipped.length).max(0))

  private def fit(value: String, width: Int): String = cell(value, width)

  private def truncate(value: String, width: Int): String =
    if width <= 0 then ""
    else if value.length <= width then value
    else if width == 1 then "…"
    else s"${value.take(width - 1)}…"

private object TuiHost:

  def currentSummary: String =
    val os   = sys.props.getOrElse("os.name", "unknown-os")
    val arch = sys.props.getOrElse("os.arch", "unknown-arch")
    s"$os/$arch"
