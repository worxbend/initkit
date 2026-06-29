package binstaller.tui

import binstaller.core.InstallerResult
import binstaller.core.ResolvedPlanSnapshot
import binstaller.core.ResolvedTool
import binstaller.core.ToolSelection

/** High-level browsing mode owned by the interactive TUI application state. */
enum TuiBrowsingMode(val label: String):
  case Browsing    extends TuiBrowsingMode("browse")
  case PlanPreview extends TuiBrowsingMode("plan-preview")
  case DryRun      extends TuiBrowsingMode("dry-run")
  case Apply       extends TuiBrowsingMode("apply")

/** Modal dialog currently open above the TUI workspace. */
enum TuiModal:
  case Help
  case Message(title: String, lines: Vector[String])

/** One TUI-local plan entry, preserving the resolved core tool without using CLI selection. */
final case class TuiPlanEntry(index: Int, tool: ResolvedTool):

  /** Stable entry name used for filtering, details, and TUI-local selection. */
  def name: String = tool.name

/** Selection state owned by the TUI, independent from CLI `ToolSelection`. */
final case class TuiSelection(selectedToolNames: Set[String]):

  /** Whether the given tool name is selected in the TUI workspace. */
  def contains(toolName: String): Boolean = selectedToolNames.contains(toolName)

  /** Count selected entries that still exist in the current resolved snapshot. */
  def countEntries(entries: Vector[TuiPlanEntry]): Int =
    entries.count(entry => contains(entry.name))

/** Constructors for TUI-local selection state. */
object TuiSelection:

  /** Build a selection containing every provided tool name. */
  def all(toolNames: Vector[String]): TuiSelection = TuiSelection(toolNames.toSet)

  /** Build an empty TUI selection. */
  def empty: TuiSelection = TuiSelection(Set.empty)

/** Boundary conversion from TUI-local selection into core installer selection. */
object TuiCoreSelection:

  /** Convert selected TUI entries into the core `ToolSelection` used by plan/apply services. */
  def toToolSelection(
      selection: TuiSelection,
      entries: Vector[TuiPlanEntry]
  ): ToolSelection = ToolSelection(
    only = entries.filter(entry => selection.contains(entry.name)).map(_.name),
    skip = Vector.empty
  )

/** Header fields that remain visible while browsing or executing actions in the TUI. */
final case class TuiAppHeader(
    appName: String,
    appVersion: String,
    profileName: String,
    manifestKind: String,
    configPath: String,
    stateFilePath: Option[String],
    hostSummary: String,
    selectedCount: Int,
    totalCount: Int
):

  /** Compact selected/total text for the header. */
  def selectionText: String = s"selected $selectedCount / total $totalCount"

/** Filter state for committed and in-progress TUI entry filtering. */
final case class TuiAppFilter(value: Option[String], draft: Option[String]):

  /** Filter value currently applied to visible entries, including a draft while editing. */
  def active: Option[String] = draft.orElse(value)

  /** Whether the filter input is currently being edited. */
  def editing: Boolean = draft.isDefined

  /** Header display text for the current filter state. */
  def displayText: String =
    val current = active.filter(_.nonEmpty).getOrElse("")
    if editing then s"/$current"
    else if current.isEmpty then "none"
    else current

/** Constructors for TUI filter state. */
object TuiAppFilter:

  /** Build a non-editing filter from an optional committed value. */
  def committed(value: Option[String]): TuiAppFilter = TuiAppFilter(value, None)

  /** Build a filter from legacy planning renderer settings. */
  def fromSettings(settings: PlanningTuiSettings): TuiAppFilter = TuiAppFilter(
    value = settings.filter,
    draft = if settings.filterEditing then settings.filter.orElse(Some("")) else None
  )

/** Pure application state for the interactive TUI workspace. */
final case class TuiAppState(
    snapshot: ResolvedPlanSnapshot,
    header: TuiAppHeader,
    mode: TuiBrowsingMode,
    entries: Vector[TuiPlanEntry],
    selection: TuiSelection,
    focus: TuiPane,
    selectedIndex: Int,
    filter: TuiAppFilter,
    modal: Option[TuiModal],
    logs: Vector[String],
    executionState: Option[ExecutionTuiState],
    viewport: TuiViewport,
    detailScroll: Int,
    logScroll: Int,
    exitRequested: Boolean
):

  /** Selected tool names owned by the TUI selection model. */
  def selectedToolNames: Set[String] = selection.selectedToolNames

  /** Number of selected entries that still exist in the resolved snapshot. */
  def selectedCount: Int = selection.countEntries(entries)

  /** Number of entries available in the resolved snapshot. */
  def totalCount: Int = entries.size

  /** Entries visible under the current committed or draft filter. */
  def visibleEntries: Vector[TuiPlanEntry] = PlanningTuiModel.filterEntries(entries, filter.active)

  /** Entry currently highlighted in the visible entry list. */
  def activeEntry: Option[TuiPlanEntry] = visibleEntries.lift(selectedIndex)

  /** Filter value currently applied to visible entries. */
  def activeFilter: Option[String] = filter.active

  /** Whether the help modal is currently open. */
  def helpOpen: Boolean = modal.contains(TuiModal.Help)

  /** Return a state with selection counts refreshed in the header. */
  def refreshHeader: TuiAppState = copy(
    header = header.copy(selectedCount = selectedCount, totalCount = totalCount)
  )

  /** Return a state with a new TUI-local selection and refreshed header counts. */
  def withSelection(value: TuiSelection): TuiAppState = copy(selection = value).refreshHeader

/** Constructors for the pure TUI application state. */
object TuiAppState:

  /** Build initial application state from one resolved plan snapshot and renderer settings. */
  def initial(
      snapshot: ResolvedPlanSnapshot,
      settings: PlanningTuiSettings
  ): TuiAppState =
    val entries =
      snapshot.plan.tools.zipWithIndex.map((tool, index) => TuiPlanEntry(index + 1, tool))
    val selection = TuiSelection.all(entries.map(_.name))
    val header    = TuiAppHeader(
      appName = "binstaller",
      appVersion = settings.appVersion,
      profileName = snapshot.profileName,
      manifestKind = snapshot.manifestKind,
      configPath = snapshot.configPath,
      stateFilePath = snapshot.stateFilePath,
      hostSummary = settings.hostSummary,
      selectedCount = 0,
      totalCount = entries.size
    )
    TuiAppState(
      snapshot = snapshot,
      header = header,
      mode = TuiBrowsingMode.Browsing,
      entries = entries,
      selection = selection,
      focus = settings.focusedPane,
      selectedIndex = settings.selectedIndex,
      filter = TuiAppFilter.fromSettings(settings),
      modal = if settings.helpOpen then Some(TuiModal.Help) else None,
      logs = settings.logs ++ defaultLogs(snapshot, entries.size),
      executionState = None,
      viewport = settings.viewport,
      detailScroll = settings.detailScroll,
      logScroll = settings.logScroll,
      exitRequested = false
    ).refreshHeader

  private def defaultLogs(snapshot: ResolvedPlanSnapshot, visibleTools: Int): Vector[String] =
    Vector(
      s"resolved manifest ${snapshot.profileName}",
      s"loaded $visibleTools plan entr${if visibleTools == 1 then "y" else "ies"}",
      "downloads are not started in browsing mode"
    )

/** Pure controller for TUI app-state transitions. */
object TuiAppController:

  /** Handle one parsed input event and return the next immutable TUI app state. */
  def handle(state: TuiAppState, input: TuiInput): TuiAppState =
    if state.exitRequested then state
    else if state.filter.editing then handleFilterInput(state, input)
    else handleBrowsingInput(state, input)

  /** Clamp scroll offsets and selected row to the current viewport and filter. */
  def clamp(state: TuiAppState): TuiAppState = clampScrolls(clampSelection(state))

  private def handleFilterInput(state: TuiAppState, input: TuiInput): TuiAppState = input match
    case TuiInput.Enter     => commitFilter(state)
    case TuiInput.Escape    => state.copy(filter = state.filter.copy(draft = None))
    case TuiInput.Backspace =>
      val draft = state.filter.draft.getOrElse("")
      clampSelection(state.copy(filter = state.filter.copy(draft = Some(draft.dropRight(1)))))
    case TuiInput.CtrlC                                => state.copy(exitRequested = true)
    case TuiInput.Character(value) if !value.isControl =>
      val nextDraft = state.filter.draft.getOrElse("") + value
      clampSelection(state.copy(filter = state.filter.copy(draft = Some(nextDraft))))
    case TuiInput.Resize(value) => clampScrolls(state.copy(viewport = value))
    case _                      => state

  private def handleBrowsingInput(state: TuiAppState, input: TuiInput): TuiAppState = input match
    case TuiInput.Tab            => state.copy(focus = state.focus.next)
    case TuiInput.BackTab        => state.copy(focus = state.focus.previous)
    case TuiInput.Character('b') => state.copy(focus = state.focus.previous)
    case TuiInput.Up             => handleDirectional(state, -1)
    case TuiInput.Down           => handleDirectional(state, 1)
    case TuiInput.PageUp         => handlePage(state, -1)
    case TuiInput.PageDown       => handlePage(state, 1)
    case TuiInput.Home           => handleHome(state)
    case TuiInput.End            => handleEnd(state)
    case TuiInput.Left           => state.copy(focus = state.focus.previous)
    case TuiInput.Right          => state.copy(focus = state.focus.next)
    case TuiInput.Slash          =>
      state.copy(filter = state.filter.copy(draft = Some(state.filter.value.getOrElse(""))))
    case TuiInput.Question              => toggleHelp(state)
    case TuiInput.Escape                => state.copy(modal = None)
    case TuiInput.Quit | TuiInput.CtrlC => state.copy(exitRequested = true)
    case TuiInput.Resize(value)         => clampScrolls(state.copy(viewport = value))
    case TuiInput.MouseWheelUp          => scrollFocused(state, -1)
    case TuiInput.MouseWheelDown        => scrollFocused(state, 1)
    case TuiInput.Character(_) | TuiInput.Enter | TuiInput.Backspace | TuiInput.Unknown => state

  private def handleDirectional(state: TuiAppState, delta: Int): TuiAppState = state.focus match
    case TuiPane.Plan => state.copy(
        selectedIndex = (state.selectedIndex + delta).max(
          0
        ).min((visibleEntryCount(state) - 1).max(0)),
        detailScroll = 0
      )
    case TuiPane.Details => scrollDetails(state, delta)
    case TuiPane.Logs    => scrollLogs(state, delta)

  private def handlePage(state: TuiAppState, direction: Int): TuiAppState =
    val layout = PlanningTuiLayout.forViewport(state.viewport)
    state.focus match
      case TuiPane.Plan => state.copy(
          selectedIndex = (state.selectedIndex + direction * layout.tableBodyHeight)
            .max(0)
            .min((visibleEntryCount(state) - 1).max(0)),
          detailScroll = 0
        )
      case TuiPane.Details => scrollDetails(state, direction * layout.detailBodyHeight)
      case TuiPane.Logs    => scrollLogs(state, direction * layout.logBodyHeight)

  private def handleHome(state: TuiAppState): TuiAppState = state.focus match
    case TuiPane.Plan    => state.copy(selectedIndex = 0, detailScroll = 0)
    case TuiPane.Details => state.copy(detailScroll = 0)
    case TuiPane.Logs    => state.copy(logScroll = 0)

  private def handleEnd(state: TuiAppState): TuiAppState = state.focus match
    case TuiPane.Plan =>
      state.copy(selectedIndex = (visibleEntryCount(state) - 1).max(0), detailScroll = 0)
    case TuiPane.Details =>
      val layout = PlanningTuiLayout.forViewport(state.viewport)
      state.copy(detailScroll = maxDetailScroll(state, layout))
    case TuiPane.Logs =>
      val layout = PlanningTuiLayout.forViewport(state.viewport)
      state.copy(logScroll = maxLogScroll(state, layout))

  private def scrollFocused(state: TuiAppState, delta: Int): TuiAppState = state.focus match
    case TuiPane.Plan    => state
    case TuiPane.Details => scrollDetails(state, delta)
    case TuiPane.Logs    => scrollLogs(state, delta)

  private def scrollDetails(state: TuiAppState, delta: Int): TuiAppState =
    val layout = PlanningTuiLayout.forViewport(state.viewport)
    state.copy(detailScroll =
      (state.detailScroll + delta).max(0).min(maxDetailScroll(state, layout))
    )

  private def scrollLogs(state: TuiAppState, delta: Int): TuiAppState =
    val layout = PlanningTuiLayout.forViewport(state.viewport)
    state.copy(logScroll = (state.logScroll + delta).max(0).min(maxLogScroll(state, layout)))

  private def commitFilter(state: TuiAppState): TuiAppState =
    val committed = state.filter.draft.flatMap(value => Option(value.trim).filter(_.nonEmpty))
    clampSelection(state.copy(filter = TuiAppFilter.committed(committed)))

  private def toggleHelp(state: TuiAppState): TuiAppState =
    val next = state.modal match
      case Some(TuiModal.Help) => None
      case _                   => Some(TuiModal.Help)
    state.copy(modal = next)

  private def clampSelection(state: TuiAppState): TuiAppState = state.copy(selectedIndex =
    state.selectedIndex.max(0).min((visibleEntryCount(state) - 1).max(0))
  )

  private def clampScrolls(state: TuiAppState): TuiAppState =
    val layout = PlanningTuiLayout.forViewport(state.viewport)
    state.copy(
      detailScroll = state.detailScroll.max(0).min(maxDetailScroll(state, layout)),
      logScroll = state.logScroll.max(0).min(maxLogScroll(state, layout))
    )

  private def visibleEntryCount(state: TuiAppState): Int = state.visibleEntries.size

  private def selectedDetailLines(state: TuiAppState): Int =
    PlanningTuiModel.fromAppState(state).detail.fold(0)(_.lines.size)

  private def visibleLogLines(state: TuiAppState): Int = state.logs.size

  private def maxDetailScroll(state: TuiAppState, layout: PlanningTuiLayout): Int =
    (selectedDetailLines(state) - layout.detailBodyHeight).max(0)

  private def maxLogScroll(state: TuiAppState, layout: PlanningTuiLayout): Int =
    (visibleLogLines(state) - layout.logBodyHeight).max(0)

/** Runner helpers for pure and terminal-backed TUI app-state sessions. */
object TuiAppRunner:

  /** Run a deterministic input sequence without touching the terminal. */
  def run(initial: TuiAppState, inputs: Vector[TuiInput]): TuiAppState = inputs.foldLeft(initial):
    (state, input) =>
      if state.exitRequested then state else TuiAppController.handle(state, input)

  /** Run the app state against a terminal boundary, restoring terminal state on exit or failure. */
  def run(initial: TuiAppState, terminal: TuiTerminal): InstallerResult =
    var state = TuiAppController.handle(initial, TuiInput.Resize(terminal.viewport))
    try
      terminal.open()
      while !state.exitRequested do
        terminal.render(PlanningTuiRenderer.render(PlanningTuiModel.fromAppState(state)))
        terminal.readInput() match
          case Some(input) => state = TuiAppController.handle(state, input)
          case None        => state = state.copy(exitRequested = true)
      InstallerResult(Vector.empty, 0)
    finally terminal.close()
