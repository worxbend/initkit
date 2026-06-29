package binstaller.tui

import binstaller.config.SymlinkPrivilege
import binstaller.core.CoreModule
import binstaller.core.HttpTextClient
import binstaller.core.InstallerOptions
import binstaller.core.InstallerResult
import binstaller.core.ResolvedArchive
import binstaller.core.ResolvedDownload
import binstaller.core.ResolvedExtractMapping
import binstaller.core.ResolvedPlanSnapshot
import binstaller.core.ResolvedSymlink
import binstaller.core.ResolvedTool
import binstaller.core.ResolvedVersion
import binstaller.core.ResolvePlanError

import java.io.FileInputStream
import java.io.InputStream
import java.io.PrintWriter
import scala.sys.process.Process

object TuiModule:
  def modulePath: Vector[String] = CoreModule.modulePath :+ "tui"

  def start(request: TuiRequest): InstallerResult =
    startInteractive(request, HttpTextClient.jdk, PlanningTuiSettings.default, SystemTuiTerminal())

  def start(
      request: TuiRequest,
      httpTextClient: HttpTextClient,
      settings: PlanningTuiSettings
  ): InstallerResult = ResolvedPlanSnapshot.resolve(request.options, httpTextClient) match
    case Left(error) => InstallerResult(
        s"binstaller ${request.mode.commandName} --tui" +: ResolvePlanError.renderLines(error),
        1
      )
    case Right(snapshot) =>
      val model = PlanningTuiModel.fromSnapshot(snapshot, request, settings)
      InstallerResult(PlanningTuiRenderer.render(model), 0)

  def startInteractive(
      request: TuiRequest,
      httpTextClient: HttpTextClient,
      settings: PlanningTuiSettings,
      terminal: TuiTerminal
  ): InstallerResult = ResolvedPlanSnapshot.resolve(request.options, httpTextClient) match
    case Left(error) => InstallerResult(
        s"binstaller ${request.mode.commandName} --tui" +: ResolvePlanError.renderLines(error),
        1
      )
    case Right(snapshot) =>
      val initial = PlanningTuiState.initial(snapshot, request, settings)
      if terminal.isInteractive then PlanningTuiSession.run(initial, terminal)
      else
        val lines = PlanningTuiRenderer.render(initial.toModel) :+
          "non-interactive terminal detected; rendered a static TUI frame"
        InstallerResult(lines, 0)

enum TuiMode:
  case Plan, Apply

  def commandName: String = this match
    case Plan  => "plan"
    case Apply => "apply"

final case class TuiRequest(mode: TuiMode, options: InstallerOptions)

final case class TuiViewport(width: Int, height: Int)

object TuiViewport:
  val default: TuiViewport = TuiViewport(120, 36)

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

object PlanningTuiSettings:

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

final case class PlanningTuiDetail(
    name: String,
    lines: Vector[String]
)

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

object PlanningTuiModel:

  def fromSnapshot(
      snapshot: ResolvedPlanSnapshot,
      request: TuiRequest,
      settings: PlanningTuiSettings
  ): PlanningTuiModel =
    val visibleTools  = filterTools(snapshot.plan.tools, settings.filter)
    val selectedIndex = clampedIndex(settings.selectedIndex, visibleTools)
    val selectedTool  = visibleTools.lift(selectedIndex)
    val rows          = visibleTools.zipWithIndex.map:
      case (tool, index) => rowForTool(index, selected = index == selectedIndex, tool)
    val header = PlanningTuiHeader(
      appName = "binstaller",
      appVersion = settings.appVersion,
      mode = request.mode.commandName,
      manifestName = snapshot.profileName,
      manifestKind = snapshot.manifestKind,
      configPath = snapshot.configPath,
      stateFilePath = snapshot.stateFilePath,
      hostSummary = settings.hostSummary,
      selectionText = selectionText(selectedIndex, visibleTools),
      filterText = filterText(settings.filter, settings.filterEditing)
    )
    val logs   = settings.logs ++ defaultLogs(snapshot, visibleTools.size)
    val layout = PlanningTuiLayout.forViewport(settings.viewport)
    PlanningTuiModel(
      viewport = settings.viewport,
      header = header,
      focusedPane = settings.focusedPane,
      rows = rows,
      detail = selectedTool.map(detailForTool),
      detailScroll = clampScroll(
        settings.detailScroll,
        selectedTool.map(detailForTool).fold(0)(_.lines.size),
        layout.detailBodyHeight
      ),
      logs = logs,
      logScroll = clampScroll(settings.logScroll, logs.size, layout.logBodyHeight),
      helpOpen = settings.helpOpen,
      footer = footerText(snapshot, visibleTools),
      keybar = "tab focus | shift-tab/b back | arrows select/scroll | / filter | ? help | q quit"
    )

  def filterTools(
      tools: Vector[ResolvedTool],
      filter: Option[String]
  ): Vector[ResolvedTool] = filter.map(_.trim).filter(_.nonEmpty) match
    case None        => tools
    case Some(value) =>
      val needle = value.toLowerCase
      tools.filter: tool =>
        tool.name.toLowerCase.contains(needle) ||
          tool.description.exists(_.toLowerCase.contains(needle))

  private def clampedIndex(index: Int, tools: Vector[ResolvedTool]): Int =
    if tools.isEmpty then 0 else index.max(0).min(tools.size - 1)

  private def selectionText(index: Int, tools: Vector[ResolvedTool]): String =
    tools.lift(index) match
      case Some(tool) => s"${index + 1}/${tools.size} ${tool.name}"
      case None       => "none"

  private def filterText(filter: Option[String], editing: Boolean): String =
    val value = filter.filter(_.nonEmpty).getOrElse("")
    if editing then s"/$value"
    else if value.isEmpty then "none"
    else value

  private def clampScroll(offset: Int, total: Int, window: Int): Int =
    offset.max(0).min((total - window).max(0))

  private def rowForTool(index: Int, selected: Boolean, tool: ResolvedTool): PlanningTuiRow =
    val risks  = riskMarkers(tool)
    val status =
      if selected then PlanningTuiStatus.Active
      else if risks.nonEmpty then PlanningTuiStatus.Warning
      else PlanningTuiStatus.Inactive
    PlanningTuiRow(
      index = index + 1,
      selected = selected,
      status = status,
      name = tool.name,
      kind = "binary-tool",
      version = ResolvedVersion.render(tool.version),
      installDir = tool.installDir,
      checksumState = checksumState(tool.download),
      riskMarkers = risks
    )

  private def detailForTool(tool: ResolvedTool): PlanningTuiDetail = PlanningTuiDetail(
    name = tool.name,
    lines = Vector(
      s"name: ${tool.name}",
      s"description: ${tool.description.getOrElse("not provided")}",
      s"version: ${ResolvedVersion.render(tool.version)}",
      s"install dir: ${tool.installDir}",
      s"download url: ${tool.download.url}",
      s"download file: ${joinPath(tool.installDir, tool.download.filename)}",
      s"checksum: ${checksumDetail(tool.download)}"
    ) ++ archiveLines(tool.download.archive) ++ symlinkLines(tool) ++ dryRunPreview(tool)
  )

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

  private def defaultLogs(snapshot: ResolvedPlanSnapshot, visibleTools: Int): Vector[String] =
    Vector(
      s"resolved manifest ${snapshot.profileName}",
      s"loaded $visibleTools selected plan entr${if visibleTools == 1 then "y" else "ies"}",
      "downloads are not started in the planning TUI"
    )

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
    case ResolvedVersion.Concrete(_)         => Vector.empty
    case ResolvedVersion.DynamicLatestUrl(_) => Vector("dynamic-version")

  private def sudoRisk(tool: ResolvedTool): Vector[String] =
    if tool.symlinks.exists(_.privilege == SymlinkPrivilege.Sudo) then Vector("sudo")
    else Vector.empty

  private def checksumState(download: ResolvedDownload): String = download.checksum match
    case Some(value) => value.algorithm.value
    case None        => "missing"

  private def checksumDetail(download: ResolvedDownload): String = download.checksum match
    case Some(value) => s"${value.algorithm.value} ${value.value}"
    case None        => "not configured"

  private def checksumOperation(download: ResolvedDownload): String = download.checksum match
    case Some(value) => s"verify ${value.algorithm.value} checksum ${value.value}"
    case None        => "skip checksum verification because none is configured"

  private def absoluteOrInstallPath(installDir: String, path: String): String =
    if path.startsWith("/") then path else joinPath(installDir, path)

  private def joinPath(parent: String, child: String): String =
    if parent.endsWith("/") then s"$parent$child" else s"$parent/$child"

enum TuiPane(val label: String):
  case Plan    extends TuiPane("plan")
  case Details extends TuiPane("details")
  case Logs    extends TuiPane("logs")

  def next: TuiPane = this match
    case Plan    => Details
    case Details => Logs
    case Logs    => Plan

  def previous: TuiPane = this match
    case Plan    => Logs
    case Details => Plan
    case Logs    => Details

enum TuiInput:
  case Tab, BackTab, Up, Down, Left, Right, PageUp, PageDown, Home, End
  case Slash, Question, Enter, Escape, Backspace, Quit, CtrlC
  case Character(value: Char)
  case Resize(viewport: TuiViewport)
  case MouseWheelUp, MouseWheelDown
  case Unknown

final case class PlanningTuiState(
    snapshot: ResolvedPlanSnapshot,
    request: TuiRequest,
    viewport: TuiViewport,
    appVersion: String,
    hostSummary: String,
    selectedIndex: Int,
    filter: Option[String],
    filterDraft: Option[String],
    focusedPane: TuiPane,
    detailScroll: Int,
    logScroll: Int,
    helpOpen: Boolean,
    logs: Vector[String],
    exitRequested: Boolean
):

  def toModel: PlanningTuiModel = PlanningTuiModel.fromSnapshot(
    snapshot,
    request,
    PlanningTuiSettings(
      viewport = viewport,
      appVersion = appVersion,
      hostSummary = hostSummary,
      selectedIndex = selectedIndex,
      filter = activeFilter,
      filterEditing = filterDraft.isDefined,
      focusedPane = focusedPane,
      detailScroll = detailScroll,
      logScroll = logScroll,
      helpOpen = helpOpen,
      logs = logs
    )
  )

  def handle(input: TuiInput): PlanningTuiState =
    if exitRequested then this
    else
      filterDraft match
        case Some(_) => handleFilterInput(input)
        case None    => handleNavigationInput(input)

  private def handleFilterInput(input: TuiInput): PlanningTuiState = input match
    case TuiInput.Enter     => commitFilter
    case TuiInput.Escape    => copy(filterDraft = None)
    case TuiInput.Backspace =>
      val draft = filterDraft.getOrElse("")
      copy(filterDraft = Some(draft.dropRight(1))).clampSelection
    case TuiInput.CtrlC                                => copy(exitRequested = true)
    case TuiInput.Character(value) if !value.isControl =>
      copy(filterDraft = Some(filterDraft.getOrElse("") + value)).clampSelection
    case TuiInput.Resize(value) => copy(viewport = value).clampScrolls
    case _                      => this

  private def handleNavigationInput(input: TuiInput): PlanningTuiState = input match
    case TuiInput.Tab                   => copy(focusedPane = focusedPane.next)
    case TuiInput.BackTab               => copy(focusedPane = focusedPane.previous)
    case TuiInput.Character('b')        => copy(focusedPane = focusedPane.previous)
    case TuiInput.Up                    => handleDirectional(-1)
    case TuiInput.Down                  => handleDirectional(1)
    case TuiInput.PageUp                => handlePage(-1)
    case TuiInput.PageDown              => handlePage(1)
    case TuiInput.Home                  => handleHome
    case TuiInput.End                   => handleEnd
    case TuiInput.Left                  => copy(focusedPane = focusedPane.previous)
    case TuiInput.Right                 => copy(focusedPane = focusedPane.next)
    case TuiInput.Slash                 => copy(filterDraft = Some(filter.getOrElse("")))
    case TuiInput.Question              => copy(helpOpen = !helpOpen)
    case TuiInput.Escape                => copy(helpOpen = false)
    case TuiInput.Quit | TuiInput.CtrlC => copy(exitRequested = true)
    case TuiInput.Resize(value)         => copy(viewport = value).clampScrolls
    case TuiInput.MouseWheelUp          => scrollFocused(-1)
    case TuiInput.MouseWheelDown        => scrollFocused(1)
    case TuiInput.Character(_) | TuiInput.Enter | TuiInput.Backspace | TuiInput.Unknown => this

  private def handleDirectional(delta: Int): PlanningTuiState = focusedPane match
    case TuiPane.Plan => copy(
        selectedIndex = (selectedIndex + delta).max(0).min((visibleToolCount - 1).max(0)),
        detailScroll = 0
      )
    case TuiPane.Details => scrollDetails(delta)
    case TuiPane.Logs    => scrollLogs(delta)

  private def handlePage(direction: Int): PlanningTuiState =
    val layout = PlanningTuiLayout.forViewport(viewport)
    focusedPane match
      case TuiPane.Plan => copy(
          selectedIndex = (selectedIndex + direction * layout.tableBodyHeight)
            .max(0)
            .min((visibleToolCount - 1).max(0)),
          detailScroll = 0
        )
      case TuiPane.Details => scrollDetails(direction * layout.detailBodyHeight)
      case TuiPane.Logs    => scrollLogs(direction * layout.logBodyHeight)

  private def handleHome: PlanningTuiState = focusedPane match
    case TuiPane.Plan    => copy(selectedIndex = 0, detailScroll = 0)
    case TuiPane.Details => copy(detailScroll = 0)
    case TuiPane.Logs    => copy(logScroll = 0)

  private def handleEnd: PlanningTuiState = focusedPane match
    case TuiPane.Plan    => copy(selectedIndex = (visibleToolCount - 1).max(0), detailScroll = 0)
    case TuiPane.Details =>
      val layout = PlanningTuiLayout.forViewport(viewport)
      copy(detailScroll = maxDetailScroll(layout))
    case TuiPane.Logs =>
      val layout = PlanningTuiLayout.forViewport(viewport)
      copy(logScroll = maxLogScroll(layout))

  private def scrollFocused(delta: Int): PlanningTuiState = focusedPane match
    case TuiPane.Plan    => this
    case TuiPane.Details => scrollDetails(delta)
    case TuiPane.Logs    => scrollLogs(delta)

  private def scrollDetails(delta: Int): PlanningTuiState =
    val layout = PlanningTuiLayout.forViewport(viewport)
    copy(detailScroll = (detailScroll + delta).max(0).min(maxDetailScroll(layout)))

  private def scrollLogs(delta: Int): PlanningTuiState =
    val layout = PlanningTuiLayout.forViewport(viewport)
    copy(logScroll = (logScroll + delta).max(0).min(maxLogScroll(layout)))

  private def commitFilter: PlanningTuiState =
    val committed = filterDraft.flatMap(value => Option(value.trim).filter(_.nonEmpty))
    copy(filter = committed, filterDraft = None).clampSelection

  private def clampSelection: PlanningTuiState =
    copy(selectedIndex = selectedIndex.max(0).min((visibleToolCount - 1).max(0))).clampScrolls

  def clampScrolls: PlanningTuiState =
    val layout = PlanningTuiLayout.forViewport(viewport)
    copy(
      detailScroll = detailScroll.max(0).min(maxDetailScroll(layout)),
      logScroll = logScroll.max(0).min(maxLogScroll(layout))
    )

  private def activeFilter: Option[String] = filterDraft.orElse(filter)

  private def visibleToolCount: Int =
    PlanningTuiModel.filterTools(snapshot.plan.tools, activeFilter).size

  private def selectedDetailLines: Int = toModel.detail.fold(0)(_.lines.size)

  private def visibleLogLines: Int = toModel.logs.size

  private def maxDetailScroll(layout: PlanningTuiLayout): Int =
    (selectedDetailLines - layout.detailBodyHeight).max(0)

  private def maxLogScroll(layout: PlanningTuiLayout): Int =
    (visibleLogLines - layout.logBodyHeight).max(0)

object PlanningTuiState:

  def initial(
      snapshot: ResolvedPlanSnapshot,
      request: TuiRequest,
      settings: PlanningTuiSettings
  ): PlanningTuiState = PlanningTuiState(
    snapshot = snapshot,
    request = request,
    viewport = settings.viewport,
    appVersion = settings.appVersion,
    hostSummary = settings.hostSummary,
    selectedIndex = settings.selectedIndex,
    filter = settings.filter,
    filterDraft = if settings.filterEditing then settings.filter.orElse(Some("")) else None,
    focusedPane = settings.focusedPane,
    detailScroll = settings.detailScroll,
    logScroll = settings.logScroll,
    helpOpen = settings.helpOpen,
    logs = settings.logs,
    exitRequested = false
  ).clampSelection

final case class PlanningTuiLayout(
    tableBodyHeight: Int,
    detailBodyHeight: Int,
    logBodyHeight: Int
)

object PlanningTuiLayout:

  def forViewport(viewport: TuiViewport): PlanningTuiLayout =
    val usable = (viewport.height.max(18) - 14).max(4)
    val table  = usable.min(7).max(3)
    val detail = ((usable - table) / 2).min(6).max(2)
    val logs   = (usable - table - detail).max(3)
    PlanningTuiLayout(table, detail, logs)

object PlanningTuiSession:

  def run(initial: PlanningTuiState, inputs: Vector[TuiInput]): PlanningTuiState =
    inputs.foldLeft(initial): (state, input) =>
      if state.exitRequested then state else state.handle(input)

  def run(initial: PlanningTuiState, terminal: TuiTerminal): InstallerResult =
    var state = initial.copy(viewport = terminal.viewport).clampScrolls
    terminal.open()
    try
      while !state.exitRequested do
        terminal.render(PlanningTuiRenderer.render(state.toModel))
        terminal.readInput() match
          case Some(input) => state = state.handle(input)
          case None        => state = state.copy(exitRequested = true)
      InstallerResult(Vector.empty, 0)
    finally terminal.close()

trait TuiTerminal:
  def isInteractive: Boolean
  def viewport: TuiViewport
  def open(): Unit
  def render(lines: Vector[String]): Unit
  def readInput(): Option[TuiInput]
  def close(): Unit

private final class SystemTuiTerminal extends TuiTerminal:
  private val out                  = PrintWriter(System.out, true)
  private var input: InputStream   = InputStream.nullInputStream()
  private var previousStty: String = ""
  private var terminalOpened       = false

  def isInteractive: Boolean = System.console() != null

  def viewport: TuiViewport = readSize().getOrElse(TuiViewport.default)

  def open(): Unit =
    previousStty = runStty(Vector("-g")).getOrElse("")
    val _ = runStty(Vector("raw", "-echo"))
    input = FileInputStream("/dev/tty")
    terminalOpened = true
    out.print("\u001b[?1049h\u001b[?25l\u001b[?1000h\u001b[?1006h")
    out.flush()

  def render(lines: Vector[String]): Unit =
    out.print("\u001b[H\u001b[2J")
    out.print(lines.mkString("\r\n"))
    out.flush()

  def readInput(): Option[TuiInput] =
    val first = input.read()
    if first < 0 then None else Some(TuiInputParser.parse(first, input))

  def close(): Unit = if terminalOpened then
    out.print("\u001b[?1006l\u001b[?1000l\u001b[?25h\u001b[?1049l")
    out.flush()
    if previousStty.nonEmpty then
      val _ = runStty(Vector(previousStty))
    input.close()
    terminalOpened = false

  private def readSize(): Option[TuiViewport] = runStty(Vector("size")).flatMap: value =>
    value.trim.split("\\s+").toVector match
      case Vector(rows, columns) =>
        for
          width  <- columns.toIntOption
          height <- rows.toIntOption
        yield TuiViewport(width, height)
      case _ => None

  private def runStty(args: Vector[String]): Option[String] =
    val command = (Vector("stty") ++ args).map(shellQuote).mkString(" ") + " < /dev/tty"
    scala.util.Try(Process(Vector("sh", "-c", command)).!!).toOption

  private def shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

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

enum PlanningTuiStatus(val label: String):
  case Completed extends PlanningTuiStatus("completed")
  case Failed    extends PlanningTuiStatus("failed")
  case Warning   extends PlanningTuiStatus("warning")
  case Active    extends PlanningTuiStatus("active")
  case Skipped   extends PlanningTuiStatus("skipped")
  case Inactive  extends PlanningTuiStatus("inactive")

object PlanningTuiStatus:

  val legendOrder: Vector[PlanningTuiStatus] = Vector(
    Completed,
    Failed,
    Warning,
    Active,
    Skipped,
    Inactive
  )

  def style(status: PlanningTuiStatus, value: String): String = status match
    case Completed => fansi.Color.Green(value).toString
    case Failed    => fansi.Color.Red(value).toString
    case Warning   => fansi.Color.Yellow(value).toString
    case Active    => fansi.Color.Cyan(value).toString
    case Skipped   => fansi.Color.LightGray(value).toString
    case Inactive  => value

object PlanningTuiRenderer:

  def render(model: PlanningTuiModel): Vector[String] =
    val width  = model.viewport.width.max(48)
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
      s"config ${header.configPath}",
      s"state ${header.stateFilePath.getOrElse("not configured")}",
      fit(
        s"host ${header.hostSummary} | selection ${header.selectionText} | filter ${header.filterText}",
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
      .map(status => PlanningTuiStatus.style(status, status.label))
      .mkString(" ")
    Vector(
      fit(model.footer, width),
      s"status $legend",
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
      case (line, marker) => fit(line, width - 2) + " " + marker

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
    val clipped = truncate(value, width)
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
