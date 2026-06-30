package binstaller.tui

import binstaller.config.SymlinkPrivilege
import binstaller.core.BinaryInstallerService
import binstaller.core.BinaryDownloadClient
import binstaller.core.CommandExecutor
import binstaller.core.CoreModule
import binstaller.core.DirectBinaryInstaller
import binstaller.core.DownloadProgressStatus
import binstaller.core.HttpTextClient
import binstaller.core.InstallFileSystem
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
import binstaller.core.SensitiveValueRedactions
import binstaller.core.SudoCredentialProvider
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
        interactiveService(
          httpTextClient,
          new TerminalSudoCredentialProvider(terminal, SensitiveValueRedactions.empty)
        ),
        ExecutionTuiSettings.fromPlanning(settings),
        terminal
      )

  private def startPlanning(
      request: TuiRequest,
      httpTextClient: HttpTextClient,
      settings: PlanningTuiSettings
  ): InstallerResult = ResolvedPlanSnapshot.resolve(request.options, httpTextClient) match
    case Left(error) =>
      val failure = TuiFailure.fromResolvePlanError(s"binstaller ${request.entrypointName}", error)
      InstallerResult(TuiFailureScreen.render(failure, settings.viewport), 1)
    case Right(snapshot) =>
      val model = PlanningTuiModel.fromAppState(TuiAppState.initial(snapshot, settings))
      InstallerResult(PlanningTuiRenderer.render(model), 0)

  private def startPlanningInteractive(
      request: TuiRequest,
      httpTextClient: HttpTextClient,
      settings: PlanningTuiSettings,
      terminal: TuiTerminal
  ): InstallerResult = ResolvedPlanSnapshot.resolve(request.options, httpTextClient) match
    case Left(error) =>
      val failure = TuiFailure.fromResolvePlanError(s"binstaller ${request.entrypointName}", error)
      InstallerResult(TuiFailureScreen.render(failure, settings.viewport), 1)
    case Right(snapshot) =>
      val initial = PlanningTuiState.initial(snapshot, settings)
      val service = interactiveService(
        httpTextClient,
        new TerminalSudoCredentialProvider(terminal, snapshot.plan.redactions)
      )
      val actions = TuiAppActions.fromService(
        request.options,
        service
      )
      if terminal.isInteractive then PlanningTuiSession.run(initial, terminal, actions)
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
        try
          terminal.open()
          observer.renderCurrent()
          val result = service.applyWithEvents(request.options, observer)
          observer.finish(result)
          result.copy(lines = Vector.empty)
        finally terminal.close()
      catch
        case NonFatal(error) =>
          val failure = TuiFailure.terminal(s"binstaller ${request.entrypointName}", error)
          InstallerResult(TuiFailureScreen.render(failure, settings.viewport), 1)

  private def interactiveService(
      httpTextClient: HttpTextClient,
      sudoCredentials: SudoCredentialProvider
  ): BinaryInstallerService =
    val installer = DirectBinaryInstaller(
      BinaryDownloadClient.jdk,
      InstallFileSystem.nio,
      CommandExecutor.process,
      sudoCredentials
    )
    BinaryInstallerService.resolving(httpTextClient, installer)

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
    checked: Boolean,
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
    infoOutput: Option[TuiInfoOutput],
    helpOpen: Boolean,
    modal: Option[TuiModal],
    modalScroll: Int,
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
          checked = state.selection.contains(entry.name),
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
      infoOutput = state.infoOutput,
      helpOpen = state.helpOpen,
      modal = state.modal,
      modalScroll = state.modalScroll,
      footer = footerText(state.snapshot, visibleEntries.map(_.tool)),
      keybar =
        "p plan | d dry-run | r apply | tab focus | enter details | l logs | space | a/c/i | / filter | ? help | q quit"
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
      checked: Boolean,
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
      checked = checked,
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
        "download final url: observed during dry-run/apply download boundary",
        "download provenance: initial manifest URL; redirects are reported after execution",
        s"download file: ${joinPath(tool.installDir, tool.download.filename)}",
        s"checksum status: ${checksumStatusSummary(tool.download)}",
        s"checksum: ${checksumDetail(tool.download)}"
      ) ++ versionProvenanceLines(tool.version) ++
        archiveLines(tool.download.archive) ++
        symlinkLines(tool) ++
        Vector(s"sudo risk: ${sudoRiskDetail(tool)}") ++
        dryRunPreview(tool),
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
    case ResolvedVersion.Concrete(_, Some(provenance)) => Vector(
        s"version resolver initial url: ${provenance.initialUrl}",
        s"version resolver final url: ${provenance.finalUrl}",
        s"version resolver provenance: ${if provenance.redirected then "redirected" else "direct"}"
      ) ++
        Option
          .when(provenance.redirected)(
            s"version resolver redirects: ${UrlProvenance.redirectChainForDisplay(provenance)}"
          )
          .toVector
    case ResolvedVersion.Concrete(_, None) =>
      Vector("version resolver provenance: static manifest value")
    case ResolvedVersion.DynamicLatestUrl(note) => Vector(
        s"version resolver provenance: dynamic latest-url${note.fold("")(value => s" ($value)")}"
      )

  private def archiveLines(archive: Option[ResolvedArchive]): Vector[String] = archive match
    case None        => Vector("archive: none", "archive mappings: none")
    case Some(value) => Vector(s"archive: ${value.original.archiveType.value}") ++
        Vector("archive mappings:") ++
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

  private def sudoRiskDetail(tool: ResolvedTool): String =
    if tool.symlinks.exists(_.privilege == SymlinkPrivilege.Sudo) then
      "yes - sudo symlink creation may prompt for elevated privileges"
    else "no"

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

  private def checksumStatusSummary(download: ResolvedDownload): String = download.checksum match
    case Some(value) if ResolvedChecksum.isDiscovered(value) =>
      s"discovered ${value.algorithm.value}"
    case Some(value) => s"configured ${value.algorithm.value}"
    case None        => "missing"

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
    logs: Vector[String],
    candidateNames: Vector[String] = Vector.empty,
    redactions: SensitiveValueRedactions = SensitiveValueRedactions.empty
)

/** Execution settings constructors. */
object ExecutionTuiSettings:

  /** Derive execution settings from planning settings so CLI flags configure both views. */
  def fromPlanning(settings: PlanningTuiSettings): ExecutionTuiSettings = ExecutionTuiSettings(
    viewport = settings.viewport,
    appVersion = settings.appVersion,
    hostSummary = settings.hostSummary,
    spinnerFrame = 0,
    logs = settings.logs,
    candidateNames = Vector.empty,
    redactions = SensitiveValueRedactions.empty
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
    elapsedTime: Duration,
    failure: Option[TuiFailure] = None
)

/** Complete deterministic model consumed by [[ExecutionTuiRenderer]]. */
final case class ExecutionTuiModel(
    viewport: TuiViewport,
    header: ExecutionTuiHeader,
    active: Option[ExecutionActiveTool],
    rows: Vector[ExecutionToolRow],
    logs: Vector[String],
    logScroll: Int,
    dryRunLines: Vector[String],
    failureOutput: Option[TuiFailure],
    summary: Option[String],
    spinnerFrame: Int,
    focusedPane: TuiPane,
    focusedRowIndex: Int,
    keybar: String,
    modal: Option[TuiModal],
    modalScroll: Int
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
    focusedRowIndex: Int,
    logs: Vector[String],
    logScroll: Int,
    dryRunLines: Vector[String],
    failureOutput: Option[TuiFailure],
    summary: Option[InstallerEvent.Summary],
    elapsedTime: Duration,
    redactions: SensitiveValueRedactions,
    modal: Option[TuiModal],
    modalScroll: Int
):

  /** Convert accumulated execution state into a deterministic render model. */
  def toModel: ExecutionTuiModel =
    val summaryLine = summary.map(summaryText)
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
      logScroll = logScroll,
      dryRunLines = dryRunLines,
      failureOutput = failureOutput,
      summary = summaryLine,
      spinnerFrame = spinnerFrame,
      focusedPane = TuiPane.Plan,
      focusedRowIndex = focusedRowIndex,
      keybar = "Enter root cause | l logs | Tab focus | terminal restored after apply completes",
      modal = modal,
      modalScroll = modalScroll
    )

  /** Failed row detail for the currently focused execution row. */
  def focusedFailure: Option[TuiFailure] = rows
    .lift(focusedRowIndex)
    .filter(_.status == PlanningTuiStatus.Failed)
    .flatMap(_.failure)

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
      case InstallerEvent.PlanReady(toolNames, statePath, elapsed) =>
        val nextRows = seedRows(toolNames)
        copy(
          spinnerFrame = nextFrame,
          stateFilePath = statePath,
          active = Some(ExecutionActiveTool(
            s"${toolNames.size} tool${if toolNames.size == 1 then "" else "s"}",
            InstallerPhase.Planning,
            None,
            None,
            elapsed
          )),
          rows = nextRows,
          focusedRowIndex = clampFocusedIndex(focusedRowIndex, nextRows),
          logs = appendLog(
            s"plan ready: ${toolNames.size} tool${if toolNames.size == 1 then "" else "s"}"
          ),
          elapsedTime = elapsed
        )
      case InstallerEvent.ToolStarted(toolName, phase, elapsed) =>
        val nextRows = upsertRow(activeRow(toolName, phase, None, None, elapsed))
        copy(
          spinnerFrame = nextFrame,
          active = Some(ExecutionActiveTool(toolName, phase, None, None, elapsed)),
          rows = nextRows,
          focusedRowIndex = focusIndexFor(toolName, nextRows),
          logs = appendLog(s"$toolName: started ${phaseText(phase)}"),
          elapsedTime = elapsed
        )
      case InstallerEvent.ToolPhaseChanged(toolName, phase, elapsed) =>
        val current  = currentActive(toolName, phase, elapsed)
        val nextRows = upsertRow(activeRow(
          toolName,
          phase,
          current.downloadedBytes,
          current.totalBytes,
          elapsed
        ))
        copy(
          spinnerFrame = nextFrame,
          active = Some(current),
          rows = nextRows,
          focusedRowIndex = focusIndexFor(toolName, nextRows),
          logs = appendLog(s"$toolName: ${phaseText(phase)}"),
          elapsedTime = elapsed
        )
      case InstallerEvent.DownloadProgress(toolName, _, downloaded, total, status, elapsed) =>
        val statusText = status match
          case DownloadProgressStatus.Started  => "download started"
          case DownloadProgressStatus.Advanced => "download advanced"
          case DownloadProgressStatus.Finished => "download finished"
        val nextLogs = status match
          case DownloadProgressStatus.Advanced => logs
          case _                               => appendLog(
              s"$toolName: $statusText ${ExecutionTuiRenderer.byteText(downloaded, total)}"
            )
        val nextRows = upsertRow(activeRow(
          toolName,
          InstallerPhase.Downloading,
          Some(downloaded),
          total,
          elapsed
        ))
        copy(
          spinnerFrame = nextFrame,
          active = Some(ExecutionActiveTool(
            toolName,
            InstallerPhase.Downloading,
            Some(downloaded),
            total,
            elapsed
          )),
          rows = nextRows,
          focusedRowIndex = focusIndexFor(toolName, nextRows),
          logs = nextLogs,
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
        val failure = failureSummary.map(summary =>
          TuiFailure.fromResult(
            "Tool failed",
            categoryForRequest,
            actionForRequest,
            InstallerResult(Vector(s"failed $toolName: $summary"), 1),
            Some(toolName),
            installDir,
            redactions,
            Some(elapsed)
          )
        )
        val nextRows =
          upsertRow(ExecutionToolRow(toolName, rowStatus, rowSummary, elapsed, failure))
        copy(
          spinnerFrame = nextFrame,
          active = None,
          rows = nextRows,
          focusedRowIndex = focusIndexFor(toolName, nextRows),
          logs = appendLog(s"${resultPrefix(rowStatus)} $toolName: $rowSummary"),
          elapsedTime = elapsed
        )
      case InstallerEvent.ToolSkipped(toolName, reason, statePath, elapsed) =>
        val nextRows = upsertRow(ExecutionToolRow(
          toolName,
          PlanningTuiStatus.Skipped,
          reason,
          elapsed
        ))
        copy(
          spinnerFrame = nextFrame,
          stateFilePath = statePath.orElse(stateFilePath),
          active = None,
          rows = nextRows,
          focusedRowIndex = focusIndexFor(toolName, nextRows),
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
    val failure = Option.when(result.exitCode != 0)(
      TuiFailure.fromResult(
        resultFailureTitle,
        categoryForRequest,
        actionForRequest,
        result,
        None,
        stateFilePath,
        redactions,
        Option.when(elapsedTime != Duration.ZERO)(elapsedTime)
      )
    )
    val nextRows = enrichFailedRows(result)
    copy(
      rows = nextRows,
      focusedRowIndex = clampFocusedIndex(focusedRowIndex, nextRows),
      dryRunLines = RenderSafety.displayLines(dryRunResultLines, redactions),
      logs =
        (logs ++ RenderSafety.displayLines(failureLogs, redactions) ++
          failure.toVector.flatMap(value => value.title +: value.renderLines)).takeRight(80),
      failureOutput = failure,
      modal = None,
      modalScroll = 0
    )

  /** Handle terminal-local inputs relevant to execution rendering. */
  def handle(input: TuiInput): ExecutionTuiState = handle(input, TuiPane.Plan)

  /** Handle terminal-local inputs, routing scroll keys to logs when log focus is active. */
  def handle(input: TuiInput, focusedPane: TuiPane): ExecutionTuiState = input match
    case TuiInput.Resize(value)                         => copy(viewport = value)
    case TuiInput.Up if focusedPane == TuiPane.Logs     => scrollLogs(-1)
    case TuiInput.Down if focusedPane == TuiPane.Logs   => scrollLogs(1)
    case TuiInput.PageUp if focusedPane == TuiPane.Logs =>
      scrollLogs(-ExecutionTuiLayout.forViewport(viewport).logBodyHeight)
    case TuiInput.PageDown if focusedPane == TuiPane.Logs =>
      scrollLogs(ExecutionTuiLayout.forViewport(viewport).logBodyHeight)
    case TuiInput.Home if focusedPane == TuiPane.Logs           => copy(logScroll = 0)
    case TuiInput.End if focusedPane == TuiPane.Logs            => copy(logScroll = maxLogScroll)
    case TuiInput.MouseWheelUp if focusedPane == TuiPane.Logs   => scrollLogs(-1)
    case TuiInput.MouseWheelDown if focusedPane == TuiPane.Logs => scrollLogs(1)
    case TuiInput.Up                                            => moveFocus(-1)
    case TuiInput.Down                                          => moveFocus(1)
    case TuiInput.PageUp   => moveFocus(-ExecutionTuiLayout.forViewport(viewport).rowBodyHeight)
    case TuiInput.PageDown => moveFocus(ExecutionTuiLayout.forViewport(viewport).rowBodyHeight)
    case TuiInput.Home     => copy(focusedRowIndex = 0)
    case TuiInput.End      => copy(focusedRowIndex = (rows.size - 1).max(0))
    case _                 => this

  private def currentActive(
      toolName: String,
      phase: InstallerPhase,
      elapsed: Duration
  ): ExecutionActiveTool = active.filter(_.name == toolName) match
    case Some(value) => value.copy(phase = phase, elapsedTime = elapsed)
    case None        => ExecutionActiveTool(toolName, phase, None, None, elapsed)

  private def appendLog(line: String): Vector[String] = (logs :+ line).takeRight(80)

  private def summaryText(value: InstallerEvent.Summary): String =
    val status = value.status match
      case InstallerRunStatus.Succeeded => "succeeded"
      case InstallerRunStatus.Failed    => "failed"
    val remaining = rows.count(row =>
      row.status == PlanningTuiStatus.Inactive || row.status == PlanningTuiStatus.Active
    )
    val interrupted = if value.status == InstallerRunStatus.Failed then remaining else 0
    val rowCounts   =
      if rows.nonEmpty then s" | remaining $remaining | interrupted $interrupted"
      else ""
    s"$status | completed ${value.installed} | failed ${value.failed} | " +
      s"skipped ${value.skipped}$rowCounts | exit ${value.exitCode} | " +
      s"elapsed ${ExecutionTuiRenderer.formatDuration(value.elapsedTime)}"

  private def seedRows(toolNames: Vector[String]): Vector[ExecutionToolRow] =
    if toolNames.isEmpty then rows
    else
      val existing = rows.map(row => row.name -> row).toMap
      toolNames.map(name =>
        existing.getOrElse(
          name,
          ExecutionToolRow(
            name,
            PlanningTuiStatus.Inactive,
            "pending",
            Duration.ZERO
          )
        )
      )

  private def upsertRow(row: ExecutionToolRow): Vector[ExecutionToolRow] =
    rows.indexWhere(_.name == row.name) match
      case -1    => rows :+ row
      case index => rows.updated(index, row)

  private def activeRow(
      toolName: String,
      phase: InstallerPhase,
      downloadedBytes: Option[Long],
      totalBytes: Option[Long],
      elapsed: Duration
  ): ExecutionToolRow = ExecutionToolRow(
    toolName,
    PlanningTuiStatus.Active,
    s"${phaseText(phase)} ${ExecutionTuiRenderer.progressText(downloadedBytes, totalBytes)}",
    elapsed
  )

  private def moveFocus(delta: Int): ExecutionTuiState = copy(focusedRowIndex =
    (focusedRowIndex + delta).max(0).min((rows.size - 1).max(0))
  )

  private def scrollLogs(delta: Int): ExecutionTuiState = copy(logScroll =
    (logScroll + delta).max(0).min(maxLogScroll)
  )

  private def maxLogScroll: Int =
    val layout = ExecutionTuiLayout.forViewport(viewport)
    (logs.size - layout.logBodyHeight).max(0)

  private def focusIndexFor(toolName: String, nextRows: Vector[ExecutionToolRow]): Int =
    nextRows.indexWhere(_.name == toolName) match
      case -1    => focusedRowIndex
      case index => index

  private def clampFocusedIndex(index: Int, nextRows: Vector[ExecutionToolRow]): Int =
    index.max(0).min((nextRows.size - 1).max(0))

  private def resultPrefix(status: PlanningTuiStatus): String = status match
    case PlanningTuiStatus.Completed => "ok"
    case PlanningTuiStatus.Failed    => "fail"
    case PlanningTuiStatus.Skipped   => "skip"
    case _                           => status.label

  private def phaseText(phase: InstallerPhase): String = phase.toString

  private def enrichFailedRows(result: InstallerResult): Vector[ExecutionToolRow] =
    if result.exitCode == 0 then rows
    else
      rows.map:
        case row if row.status == PlanningTuiStatus.Failed =>
          row.copy(failure =
            Some(TuiFailure.fromResult(
              "Tool failed",
              categoryForRequest,
              actionForRequest,
              result,
              Some(row.name),
              stateFilePath,
              redactions,
              Option.when(row.elapsedTime != Duration.ZERO)(row.elapsedTime)
            ))
          )
        case row => row

  private def categoryForRequest: TuiFailureCategory =
    if request.options.dryRun == binstaller.core.DryRunMode.Enabled then TuiFailureCategory.DryRun
    else TuiFailureCategory.Apply

  private def actionForRequest: String =
    if request.options.dryRun == binstaller.core.DryRunMode.Enabled then "dry-run"
    else "apply"

  private def resultFailureTitle: String =
    if request.options.dryRun == binstaller.core.DryRunMode.Enabled then "Dry run failed"
    else "Apply failed"

/** Execution state constructors. */
object ExecutionTuiState:

  /** Initial execution state before any core events arrive. */
  def initial(request: TuiRequest, settings: ExecutionTuiSettings): ExecutionTuiState =
    val candidateRows = settings.candidateNames.map(name =>
      ExecutionToolRow(name, PlanningTuiStatus.Inactive, "pending", Duration.ZERO)
    )
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
      rows = candidateRows,
      focusedRowIndex = 0,
      logs = settings.logs,
      logScroll = 0,
      dryRunLines = Vector.empty,
      failureOutput = None,
      summary = None,
      elapsedTime = Duration.ZERO,
      redactions = settings.redactions,
      modal = None,
      modalScroll = 0
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

private[tui] object TuiModalRenderer:

  def render(
      value: Option[TuiModal],
      width: Int,
      scroll: Int = 0,
      height: Int = Int.MaxValue
  ): Vector[String] = value match
    case None                               => Vector.empty
    case Some(TuiModal.Help)                => help(width)
    case Some(TuiModal.ConfirmApply(names)) => Vector(
        separator(width),
        PlanningTuiStatus.style(PlanningTuiStatus.Warning, fit("Confirm real apply", width)),
        fit(
          s"Apply will install ${names.size} selected entr${
              if names.size == 1 then "y" else "ies"
            }: ${names.mkString(", ")}",
          width
        ),
        fit("Press Enter to apply now, or Escape/n to cancel.", width),
        separator(width)
      )
    case Some(TuiModal.PasswordPrompt(prompt)) => titled(
        "Sudo password required",
        passwordPromptLines(prompt),
        PlanningTuiStatus.Warning,
        width
      )
    case Some(TuiModal.Message(title, lines)) => titled(
        title,
        lines,
        PlanningTuiStatus.Active,
        width
      )
    case Some(TuiModal.Error(failure)) => titled(
        s"Error: ${failure.title}",
        visibleModalLines(failure.renderLines, scroll, height),
        PlanningTuiStatus.Failed,
        width
      )
    case Some(TuiModal.RootCause(failure)) => titled(
        s"Root cause: ${failure.toolName.getOrElse(failure.title)}",
        visibleModalLines(failure.renderLines, scroll, height),
        PlanningTuiStatus.Failed,
        width
      )

  def modalLines(value: TuiModal): Vector[String] = value match
    case TuiModal.Help => Vector(
        "Tab focus | Space select | a toggle all | c clear | i invert",
        "p plan preview | d dry-run | r apply | l logs | / filter | q quit",
        "Enter focuses selected entry details or confirms modal actions.",
        "Esc closes modal/filter.",
        "q or Ctrl+C exits after restoring the terminal."
      )
    case TuiModal.ConfirmApply(names)    => names
    case TuiModal.PasswordPrompt(prompt) => passwordPromptLines(prompt)
    case TuiModal.Message(title, lines)  => title +: lines
    case TuiModal.Error(failure)         => failure.renderLines
    case TuiModal.RootCause(failure)     => failure.renderLines

  private def visibleModalLines(
      lines: Vector[String],
      scroll: Int,
      height: Int
  ): Vector[String] =
    if height == Int.MaxValue then lines
    else
      val clippedScroll = scroll.max(0).min((lines.size - height).max(0))
      lines.slice(clippedScroll, clippedScroll + height)

  private def passwordPromptLines(prompt: TuiPasswordPromptView): Vector[String] = Vector(
    s"operation: ${prompt.operation}",
    s"tool: ${prompt.toolName}"
  ) ++
    prompt.destinationPath.map(value => s"destination: $value").toVector ++
    prompt.targetPath.map(value => s"target: $value").toVector ++
    Vector(
      s"password: ${"*" * prompt.maskedLength}",
      "Press Enter to submit. Escape, Ctrl+C, or /cancel then Enter cancels this operation."
    )

  private def titled(
      title: String,
      lines: Vector[String],
      status: PlanningTuiStatus,
      width: Int
  ): Vector[String] = Vector(
    separator(width),
    PlanningTuiStatus.style(status, fit(title, width))
  ) ++ lines.map(fit(_, width)) ++ Vector(separator(width))

  private def help(width: Int): Vector[String] = Vector(
    separator(width),
    PlanningTuiStatus.style(PlanningTuiStatus.Active, fit("Help", width)),
    fit("Tab cycles plan, details, and logs; Shift+Tab or b cycles backward.", width),
    fit(
      "Plan focus: Up/Down selects rows, PageUp/PageDown jumps, Home/End moves to edges.",
      width
    ),
    fit("Enter focuses selected entry details; l focuses logs.", width),
    fit("p previews the selected entries without installing or writing state.", width),
    fit("d runs dry-run apply for selected entries and keeps the final summary visible.", width),
    fit("r opens a confirmation prompt before applying selected entries.", width),
    fit("Details/log focus: arrows, PageUp/PageDown, Home/End, and mouse wheel scroll.", width),
    fit("/ edits the filter, Enter applies it, Escape cancels editing or closes modals.", width),
    fit("q or Ctrl+C exits after restoring the terminal.", width),
    separator(width)
  )

  private def separator(width: Int): String = "-" * width

  private def fit(value: String, width: Int): String = cell(value, width)

  private def cell(value: String, width: Int): String =
    val clipped = truncate(RenderSafety.terminalLine(value), width)
    clipped + (" " * (width - clipped.length).max(0))

  private def truncate(value: String, width: Int): String =
    if width <= 0 then ""
    else if value.length <= width then value
    else if width == 1 then "."
    else s"${value.take(width - 1)}."

/** Deterministic renderer for apply execution TUI frames. */
object ExecutionTuiRenderer:

  /** Render an execution model into terminal rows. */
  def render(model: ExecutionTuiModel): Vector[String] =
    val width  = model.viewport.width.max(1)
    val layout = ExecutionTuiLayout.forViewport(model.viewport)
    header(model, layout, width) ++
      executionTable(
        model.active,
        model.rows,
        model.spinnerFrame,
        model.focusedRowIndex,
        layout,
        width
      ) ++
      infoPanel(model, layout, width) ++
      footer(model, layout, width)

  /** Format elapsed time for compact terminal display. */
  def formatDuration(duration: Duration): String =
    val millis = duration.toMillis.max(0L)
    if millis < 1000L then s"${millis}ms"
    else f"${millis.toDouble / 1000.0}%.1fs"

  /** Format downloaded/total byte progress for display. */
  def byteText(downloadedBytes: Long, totalBytes: Option[Long]): String = totalBytes match
    case Some(total) => s"${formatBytes(downloadedBytes)}/${formatBytes(total)}"
    case None        => formatBytes(downloadedBytes)

  private def header(
      model: ExecutionTuiModel,
      layout: ExecutionTuiLayout,
      width: Int
  ): Vector[String] =
    val header = model.header
    val lines  = Vector(
      PlanningTuiStatus.style(
        PlanningTuiStatus.Active,
        fit(
          s"🚀 ${header.appName} ${header.appVersion} | ${header.mode} | elapsed ${header.elapsedText}",
          width
        )
      ),
      fit(s"config ${header.configPath}", width),
      fit(
        s"state ${header.stateFilePath.getOrElse("not configured")} | host ${header.hostSummary}",
        width
      ),
      ""
    )
    lines.take(layout.headerHeight)

  private def executionTable(
      active: Option[ExecutionActiveTool],
      rows: Vector[ExecutionToolRow],
      spinnerFrame: Int,
      focusedRowIndex: Int,
      layout: ExecutionTuiLayout,
      width: Int
  ): Vector[String] =
    val tableWidth = contentWidth(width)
    if width < 44 then executionCandidateList(active, rows, focusedRowIndex, layout, tableWidth)
    else
      val columns       = executionColumns(tableWidth)
      val headerColumns = s"${cell("#", columns.checkbox)} ${cell("name", columns.name)} " +
        s"${cell("version", columns.version)} ${cell("checksum", columns.checksum)} " +
        cell("status", columns.status)
      val allRows      = tableRows(active, rows, spinnerFrame)
      val firstVisible = windowStart(
        focusedRowIndex.min((allRows.size - 1).max(0)),
        allRows.size,
        layout.rowBodyHeight
      )
      val visible = allRows.slice(firstVisible, firstVisible + layout.rowBodyHeight)
      val body    =
        if visible.isEmpty then Vector(panelLine("waiting for selected entries...", tableWidth))
        else
          visible.zipWithIndex.map: (row, offset) =>
            executionRowLine(row, columns, tableWidth, firstVisible + offset == focusedRowIndex)
      val title = active match
        case Some(_) => "Apply progress / Execution [active]"
        case None    => "Apply progress"
      Vector(panelTop(title, tableWidth), panelLine(headerColumns, tableWidth)) ++ body ++
        Vector(panelBottom(tableWidth), "")

  private def executionCandidateList(
      active: Option[ExecutionActiveTool],
      rows: Vector[ExecutionToolRow],
      focusedRowIndex: Int,
      layout: ExecutionTuiLayout,
      width: Int
  ): Vector[String] =
    val allRows      = tableRows(active, rows, spinnerFrame = 0)
    val firstVisible = windowStart(
      focusedRowIndex.min((allRows.size - 1).max(0)),
      allRows.size,
      layout.rowBodyHeight
    )
    val visible = allRows.slice(firstVisible, firstVisible + layout.rowBodyHeight)
    val body    =
      if visible.isEmpty then Vector(panelLine("waiting...", width))
      else
        visible.zipWithIndex.map: (row, offset) =>
          val marker = if firstVisible + offset == focusedRowIndex then ">" else " "
          val state  = row.status match
            case PlanningTuiStatus.Active    => "active"
            case PlanningTuiStatus.Completed => "done"
            case PlanningTuiStatus.Failed    => "failed"
            case PlanningTuiStatus.Skipped   => "skipped"
            case _                           => "pending"
          PlanningTuiStatus.style(row.status, panelLine(s"$marker ${row.name} $state", width))
    Vector(panelTop("Execution", width)) ++ body ++ Vector(panelBottom(width), "")

  private def tableRows(
      active: Option[ExecutionActiveTool],
      rows: Vector[ExecutionToolRow],
      spinnerFrame: Int
  ): Vector[ExecutionTableRow] =
    if rows.isEmpty then active.toVector.map(activeTableRow(_, spinnerFrame))
    else rows.map(row => executionTableRow(row, active, spinnerFrame))

  private def executionTableRow(
      row: ExecutionToolRow,
      active: Option[ExecutionActiveTool],
      spinnerFrame: Int
  ): ExecutionTableRow = active.filter(_.name == row.name) match
    case Some(value) => activeTableRow(value, spinnerFrame)
    case None        => completedTableRow(row)

  private final case class ExecutionTableRow(
      name: String,
      status: PlanningTuiStatus,
      statusText: String
  )

  private def activeTableRow(active: ExecutionActiveTool, spinnerFrame: Int): ExecutionTableRow =
    ExecutionTableRow(
      active.name,
      PlanningTuiStatus.Active,
      s"activity | ⏳ ${spinner(spinnerFrame)} ${active.phase} " +
        progressText(active.downloadedBytes, active.totalBytes, spinnerFrame)
    )

  private def completedTableRow(row: ExecutionToolRow): ExecutionTableRow =
    val status = row.status match
      case PlanningTuiStatus.Completed => s"✅ ok installed ${formatDuration(row.elapsedTime)}"
      case PlanningTuiStatus.Failed    => s"❌ fail ${formatDuration(row.elapsedTime)}"
      case PlanningTuiStatus.Skipped   => s"⏭ skipped"
      case _                           => row.summary
    ExecutionTableRow(row.name, row.status, status)

  private def executionRowLine(
      row: ExecutionTableRow,
      columns: TableColumns,
      width: Int,
      focused: Boolean
  ): String =
    val marker   = if focused then ">" else " "
    val checkbox = "[x]"
    val plain    = s"$marker ${cell(checkbox, columns.checkbox)} " +
      s"${cell(row.name, columns.name)} ${cell("-", columns.version)} " +
      s"${cell("-", columns.checksum)} ${cell(row.statusText, columns.status)}"
    PlanningTuiStatus.style(row.status, panelLine(plain, width))

  private def infoPanel(
      model: ExecutionTuiModel,
      layout: ExecutionTuiLayout,
      width: Int
  ): Vector[String] =
    val panelWidth = contentWidth(width)
    val title      =
      if model.modal.nonEmpty then "🚨 root-cause details"
      else if model.failureOutput.nonEmpty then "🚨 error output"
      else if model.focusedPane == TuiPane.Logs then "📜 Logs"
      else if model.dryRunLines.nonEmpty then "ℹ️ Dry-run operations / Recent Logs"
      else "ℹ️ info bar"
    val modalLines = model.modal.map(value =>
      TuiModalRenderer.render(
        Some(value),
        panelWidth - 2,
        model.modalScroll,
        layout.infoBodyHeight
      )
    ).getOrElse(Vector.empty)
    val rawLines =
      if modalLines.nonEmpty then modalLines
      else if model.focusedPane == TuiPane.Logs then
        visibleText(
          model.logs,
          model.logScroll,
          layout.infoBodyHeight
        )
      else if model.failureOutput.nonEmpty then
        model.failureOutput.toVector.flatMap(value => value.title +: value.renderLines)
      else if model.dryRunLines.nonEmpty then dryRunInfoLines(model, layout.infoBodyHeight)
      else executionInfoLines(model).takeRight(layout.infoBodyHeight)
    val lines = if rawLines.isEmpty then Vector("No output yet.") else rawLines
    Vector(panelTop(title, panelWidth)) ++
      pad(lines, layout.infoBodyHeight).map(line => panelLine(line, panelWidth)) ++
      Vector(panelBottom(panelWidth), "")

  private def footer(
      model: ExecutionTuiModel,
      layout: ExecutionTuiLayout,
      width: Int
  ): Vector[String] =
    val status = model.summary.getOrElse("running")
    Vector(
      fit(status, width),
      PlanningTuiStatus.style(
        PlanningTuiStatus.Active,
        fit(
          "p plan | d dry-run | r apply | tab focus | l logs | space select | a toggle all | q quit",
          width
        )
      )
    ).take(layout.footerHeight)

  private def executionInfoLines(model: ExecutionTuiModel): Vector[String] =
    model.active.toVector.flatMap: active =>
      Vector(
        s"activity | current tool ${active.name}",
        s"phase ${active.phase} | elapsed ${formatDuration(active.elapsedTime)}",
        progressText(active.downloadedBytes, active.totalBytes, model.spinnerFrame)
      )
    ++ model.logs

  private def dryRunInfoLines(model: ExecutionTuiModel, height: Int): Vector[String] =
    val recentLogs     = model.logs.takeRight(3)
    val operationLimit = (height - recentLogs.size).max(1)
    model.dryRunLines.take(operationLimit) ++ recentLogs

  private def visibleText(lines: Vector[String], offset: Int, height: Int): Vector[String] =
    val clippedOffset = offset.max(0).min((lines.size - height).max(0))
    lines.slice(clippedOffset, clippedOffset + height)

  def progressText(
      downloadedBytes: Option[Long],
      totalBytes: Option[Long],
      frame: Int = 0
  ): String = downloadedBytes match
    case Some(downloaded) => totalBytes.filter(_ > 0L) match
        case Some(total) =>
          s"${progressBar(downloaded, total)} ${byteText(downloaded, Some(total))}"
        case None => s"progress ${indeterminateBar(frame)} ${byteText(downloaded, None)} / unknown"
    case None => s"progress ${indeterminateBar(frame)} bytes pending"

  private def progressBar(downloadedBytes: Long, totalBytes: Long): String =
    val width  = 18
    val ratio  = (downloadedBytes.toDouble / totalBytes.toDouble).max(0.0).min(1.0)
    val filled = (ratio * width).round.toInt
    val empty  = width - filled
    val pct    = (ratio * 100.0).round.toInt
    s"${"█" * filled}${"░" * empty} $pct%"

  private def indeterminateBar(frame: Int): String =
    val width  = 18
    val window = 5
    val start  = frame.abs % (width + window)
    val cells  = (0 until width).map: index =>
      if index >= start - window && index < start then "█"
      else "░"
    cells.mkString

  private def spinner(frame: Int): String = Vector("|", "/", "-", "\\")(frame.abs % 4)

  private def formatBytes(bytes: Long): String =
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    if bytes >= gib then f"${bytes / gib}%.1f GiB"
    else if bytes >= mib then f"${bytes / mib}%.1f MiB"
    else if bytes >= kib then f"${bytes / kib}%.1f KiB"
    else s"$bytes B"

  private def windowStart(selectedIndex: Int, total: Int, height: Int): Int =
    if total <= height then 0
    else
      val half = height / 2
      (selectedIndex - half).max(0).min(total - height)

  private def contentWidth(width: Int): Int = width.min(132).max(1)

  private final case class TableColumns(
      checkbox: Int,
      name: Int,
      version: Int,
      checksum: Int,
      status: Int
  )

  private def executionColumns(width: Int): TableColumns =
    val content  = (width - 8).max(20)
    val checkbox = 3
    val name     = if width < 70 then 14 else 18
    val version  = if width < 70 then 8 else 14
    val checksum = if width < 70 then 10 else 14
    val status   = (content - checkbox - name - version - checksum).max(18)
    TableColumns(checkbox, name, version, checksum, status)

  private def panelTop(title: String, width: Int): String =
    val label     = s" $title "
    val remaining = (width - label.length - 2).max(0)
    val left      = remaining / 2
    val right     = remaining - left
    fit(s"╭${"─" * left}$label${"─" * right}╮", width)

  private def panelBottom(width: Int): String = fit(s"╰${"─" * (width - 2).max(0)}╯", width)

  private def panelLine(value: String, width: Int): String =
    if width <= 2 then fit(value, width)
    else s"│${fit(value, width - 2)}│"

  private def pad(lines: Vector[String], height: Int): Vector[String] = lines.take(height) ++
    Vector.fill((height - lines.size).max(0))("")

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
    headerHeight: Int,
    rowBodyHeight: Int,
    dryRunBodyHeight: Int,
    logBodyHeight: Int,
    infoBodyHeight: Int,
    footerHeight: Int
)

/** Execution layout constructors. */
object ExecutionTuiLayout:

  /** Calculate execution pane heights from the current viewport. */
  def forViewport(viewport: TuiViewport): ExecutionTuiLayout =
    val height        = viewport.height.max(1)
    val header        = if height < 20 then 2 else 4
    val footer        = if height < 20 then 1 else 2
    val fixed         = header + footer + 7
    val bodies        = (height - fixed).max(2)
    val preferredInfo =
      if height >= 34 then bodies.min(16).max(5)
      else (bodies - (bodies / 3)).max(1)
    val info = preferredInfo.min(bodies - 1).max(1)
    val rows = (bodies - info).max(1)
    ExecutionTuiLayout(header, rows, info, info, info, footer)

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
    headerHeight: Int,
    tableBodyHeight: Int,
    detailBodyHeight: Int,
    logBodyHeight: Int,
    infoBodyHeight: Int,
    footerHeight: Int
)

/** Planning layout constructors. */
object PlanningTuiLayout:

  /** Calculate planning pane heights from the current viewport. */
  def forViewport(viewport: TuiViewport): PlanningTuiLayout =
    val height        = viewport.height.max(1)
    val header        = if height < 24 then 2 else 6
    val footer        = if height < 24 then 1 else 3
    val fixed         = header + footer + 7
    val bodies        = (height - fixed).max(2)
    val preferredInfo =
      if height >= 34 then bodies.min(16).max(5)
      else (bodies - (bodies / 3)).max(1)
    val info  = preferredInfo.min(bodies - 1).max(1)
    val table = (bodies - info).max(1)
    PlanningTuiLayout(header, table, info, info, info, footer)

/** Interactive planning TUI session runner. */
object PlanningTuiSession:

  /** Run a deterministic input sequence without touching the terminal. */
  def run(initial: PlanningTuiState, inputs: Vector[TuiInput]): PlanningTuiState =
    PlanningTuiState(TuiAppRunner.run(initial.appState, inputs))

  /** Run a deterministic input sequence with side-effecting TUI actions. */
  def run(
      initial: PlanningTuiState,
      inputs: Vector[TuiInput],
      actions: TuiAppActions
  ): PlanningTuiState = PlanningTuiState(TuiAppRunner.run(initial.appState, inputs, actions))

  /** Run against a terminal boundary, restoring terminal state on exit or failure. */
  def run(initial: PlanningTuiState, terminal: TuiTerminal): InstallerResult =
    TuiAppRunner.run(initial.appState, terminal)

  /** Run against a terminal boundary with side-effecting TUI actions. */
  def run(
      initial: PlanningTuiState,
      terminal: TuiTerminal,
      actions: TuiAppActions
  ): InstallerResult = TuiAppRunner.run(initial.appState, terminal, actions)

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
    header(model, layout, width) ++
      table(model.rows, layout, width, model.focusedPane == TuiPane.Plan) ++
      infoPanel(model, layout, width) ++
      footer(model, layout, width)

  private def header(
      model: PlanningTuiModel,
      layout: PlanningTuiLayout,
      width: Int
  ): Vector[String] =
    val header = model.header
    val lines  = Vector(
      PlanningTuiStatus.style(
        PlanningTuiStatus.Active,
        fit(
          s"📦 ${header.appName} ${header.appVersion} | mode ${header.mode} | " +
            s"action [${header.mode}] | ${header.manifestName} | ${header.selectionText}",
          width
        )
      ),
      fit(
        s"profile ${header.manifestName} (${header.manifestKind}) | " +
          s"manifest ${header.manifestName} (${header.manifestKind})",
        width
      ),
      fit(s"config ${header.configPath}", width),
      fit(s"state ${header.stateFilePath.getOrElse("not configured")}", width),
      fit(
        s"host ${header.hostSummary} | mode chip ${header.mode} | filter ${header.filterText}",
        width
      ),
      ""
    )
    lines.take(layout.headerHeight)

  private def table(
      rows: Vector[PlanningTuiRow],
      layout: PlanningTuiLayout,
      width: Int,
      focused: Boolean
  ): Vector[String] =
    val tableWidth    = contentWidth(width)
    val columns       = planningColumns(tableWidth)
    val headerColumns = s"${cell("#", columns.checkbox)} ${cell("name", columns.name)} " +
      s"${cell("version", columns.version)} ${cell("checksum", columns.checksum)} " +
      cell("status", columns.status)
    val selectedIndex = rows.indexWhere(_.selected).max(0)
    val firstVisible  = windowStart(selectedIndex, rows.size, layout.tableBodyHeight)
    val visibleRows   = rows.slice(firstVisible, firstVisible + layout.tableBodyHeight)
    val body          =
      if rows.isEmpty then Vector(panelLine("no plan entries match the active filter", tableWidth))
      else visibleRows.map(rowLine(_, columns, tableWidth))
    val title = if focused then "Plan [focus] table" else "Plan table"
    Vector(panelTop(title, tableWidth), panelLine(headerColumns, tableWidth)) ++ body ++
      Vector(panelBottom(tableWidth), "")

  private def rowLine(row: PlanningTuiRow, columns: TableColumns, width: Int): String =
    val marker   = if row.selected then ">" else " "
    val checkbox = if row.checked then "[x]" else "[ ]"
    val status   = planningStatusText(row)
    val plain    = s"$marker ${cell(checkbox, columns.checkbox)} " +
      s"${cell(row.name, columns.name)} ${cell(row.version, columns.version)} " +
      s"${cell(row.checksumState, columns.checksum)} ${cell(status, columns.status)}"
    PlanningTuiStatus.style(row.status, panelLine(plain, width))

  private def planningStatusText(row: PlanningTuiRow): String =
    if row.checked then "✅ selected"
    else if row.riskMarkers.contains("no-checksum") then "⚠️ no checksum"
    else "○ not selected"

  private def infoPanel(
      model: PlanningTuiModel,
      layout: PlanningTuiLayout,
      width: Int
  ): Vector[String] =
    val panelWidth = contentWidth(width)
    val title      = model.modal match
      case Some(TuiModal.Help)                                 => "ℹ️ Help"
      case Some(TuiModal.Error(_))                             => "🚨 error output"
      case Some(TuiModal.RootCause(_))                         => "🚨 root-cause details"
      case Some(_)                                             => "ℹ️ info"
      case None if model.focusedPane == TuiPane.Logs           => "📜 Logs"
      case None if model.infoOutput.exists(_.failure.nonEmpty) => "🚨 error output"
      case None if model.infoOutput.nonEmpty                   =>
        model.infoOutput.map(output => s"ℹ️ ${output.title}").getOrElse("ℹ️ info")
      case None if model.focusedPane == TuiPane.Details =>
        model.detail.map(value => s"ℹ️ Details: ${value.name}").getOrElse("ℹ️ info")
      case None => model.detail.map(value => s"ℹ️ Details: ${value.name}").getOrElse("ℹ️ info")
    val rawLines = model.modal match
      case Some(value) =>
        visibleText(modalInfoLines(value), model.modalScroll, layout.infoBodyHeight)
      case None if model.focusedPane == TuiPane.Logs =>
        visibleText(model.logs, model.logScroll, layout.infoBodyHeight)
      case None if model.infoOutput.nonEmpty =>
        model.infoOutput.map(_.lines.take(layout.infoBodyHeight)).getOrElse(Vector.empty)
      case None if model.focusedPane == TuiPane.Details =>
        visibleText(
          model.detail.map(_.lines).getOrElse(Vector("Select an entry to inspect details.")),
          model.detailScroll,
          layout.infoBodyHeight
        )
      case None => visibleText(
          model.detail.map(_.lines).getOrElse(Vector("Select an entry to inspect details.")),
          model.detailScroll,
          layout.infoBodyHeight
        )
    val lines = if rawLines.isEmpty then Vector("") else rawLines
    Vector(panelTop(title, panelWidth)) ++
      paddedInfo(lines, layout.infoBodyHeight, panelWidth) ++
      Vector(panelBottom(panelWidth), "")

  private def modalInfoLines(value: TuiModal): Vector[String] = value match
    case TuiModal.Help => Vector(
        "Tab focus | Space select | a toggle all | c clear | i invert",
        "p plan preview | d dry-run | r apply | l logs | / filter | q quit",
        "Enter focuses selected entry details or confirms modal actions.",
        "Esc closes modal/filter.",
        "q or Ctrl+C exits after restoring the terminal."
      )
    case TuiModal.ConfirmApply(names) => Vector(
        "Confirm real apply",
        s"Apply will install ${names.size} selected entr${
            if names.size == 1 then "y" else "ies"
          }: ${names.mkString(", ")}",
        s"⚠️ Apply ${names.size} selected entr${if names.size == 1 then "y" else "ies"}:",
        names.mkString(", "),
        "Press Enter to apply now, or Escape/n to cancel."
      )
    case TuiModal.Message(title, lines) => title +: lines
    case TuiModal.Error(failure)        => failure.title +: failure.renderLines
    case TuiModal.RootCause(failure)    =>
      s"Root cause: ${failure.toolName.getOrElse(failure.title)}" +: failure.renderLines
    case TuiModal.PasswordPrompt(prompt) => Vector(
        "Sudo password required",
        s"operation: ${prompt.operation}",
        s"tool: ${prompt.toolName}"
      ) ++
        prompt.destinationPath.map(value => s"destination: $value").toVector ++
        prompt.targetPath.map(value => s"target: $value").toVector ++
        Vector(
          s"password: ${"*" * prompt.maskedLength}",
          "Enter submits; Escape, Ctrl+C, or /cancel cancels this operation."
        )

  private def visibleText(lines: Vector[String], offset: Int, height: Int): Vector[String] =
    val clippedOffset = offset.max(0).min((lines.size - height).max(0))
    lines.slice(clippedOffset, clippedOffset + height)

  private def paddedInfo(lines: Vector[String], height: Int, width: Int): Vector[String] =
    val padded = lines.take(height) ++ Vector.fill((height - lines.size).max(0))("")
    padded.map(line => panelLine(line, width))

  private def footer(
      model: PlanningTuiModel,
      layout: PlanningTuiLayout,
      width: Int
  ): Vector[String] =
    val legend = PlanningTuiStatus.legendOrder.map(_.label).mkString(" ")
    Vector(
      PlanningTuiStatus.style(PlanningTuiStatus.Active, fit(model.keybar, width)),
      fit(model.footer, width),
      fit(s"status $legend", width)
    ).take(layout.footerHeight)

  private def contentWidth(width: Int): Int = width.min(132).max(1)

  private def panelTop(title: String, width: Int): String =
    val label     = s" $title "
    val remaining = (width - label.length - 2).max(0)
    val left      = remaining / 2
    val right     = remaining - left
    fit(s"╭${"─" * left}$label${"─" * right}╮", width)

  private def panelBottom(width: Int): String = fit(s"╰${"─" * (width - 2).max(0)}╯", width)

  private def panelLine(value: String, width: Int): String =
    if width <= 2 then fit(value, width)
    else s"│${fit(value, width - 2)}│"

  private final case class TableColumns(
      checkbox: Int,
      name: Int,
      version: Int,
      checksum: Int,
      status: Int
  )

  private def planningColumns(width: Int): TableColumns =
    val content  = (width - 8).max(20)
    val checkbox = 3
    val name     = if width < 70 then 14 else 18
    val version  = if width < 70 then 10 else 16
    val checksum = if width < 70 then 12 else 14
    val status   = (content - checkbox - name - version - checksum).max(16)
    TableColumns(checkbox, name, version, checksum, status)

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
