package initkit.tui

import dev.tamboui.layout.Flex.SPACE_BETWEEN
import dev.tamboui.style.Color
import dev.tamboui.style.Style
import dev.tamboui.text.CharWidth
import dev.tamboui.text.CharWidth.TruncatePosition
import dev.tamboui.toolkit.Toolkit.{
  column,
  fill,
  length,
  panel,
  row,
  scrollable,
  spacer,
  table,
  text
}
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.elements.Panel
import dev.tamboui.toolkit.elements.TableElement
import dev.tamboui.toolkit.elements.TextElement
import dev.tamboui.toolkit.event.EventResult
import dev.tamboui.toolkit.app.ToolkitRunner
import dev.tamboui.tui.event.KeyEvent
import dev.tamboui.widgets.block.BorderType
import dev.tamboui.widgets.table.{Row as TableRow, TableState}
import ox.*
import scala.util.control.NonFatal

object TambouiApp:

  def run(launch: TuiLaunchModel): Unit =
    val runner = ToolkitRunner.create()
    supervised:
      val state = TuiAppState(launch)
      try runner.run { () => TuiRenderer.render(state, runner) }
      finally runner.close()

private[tui] final class TuiAppState private (
    private var current: TuiViewModel,
    private var context: TuiExecutionContext,
    private var log: Vector[String],
    private var status: TuiWorkStatus,
    private var confirmQuit: Boolean,
    executionRunner: TuiExecutionRunner
):
  private val planTableState: TableState = new TableState()

  def model: TuiViewModel = current

  def logLines: Vector[String] = log

  def workStatus: TuiWorkStatus = status

  def quitConfirmationVisible: Boolean = confirmQuit

  def syncedPlanTableState(): TableState =
    current.focusedRowPosition match
      case Some(position) => planTableState.select(position)
      case None           => planTableState.clearSelection()
    planTableState

  def moveFocus(delta: Int): Unit = whenIdle:
    current = current.moveFocus(delta)

  def focusFirst(): Unit = whenIdle:
    current = current.focusFirst

  def focusLast(): Unit = whenIdle:
    current = current.focusLast

  def toggleFocused(): Unit = whenIdle:
    current = current.toggleFocused

  def selectAllRunnable(): Unit = whenIdle:
    current = current.selectAllRunnable

  def detailsFocused(): Unit =
    val details = TuiTextLayout.detailsLines(current)
    appendLog(("details:" +: details.map(line => s"  $line"))*)

  def start(action: TuiExecutionAction, runner: ToolkitRunner)(using Ox): Unit =
    if isRunning then appendLog(s"already running: ${status.label}")
    else
      confirmQuit = false
      status = TuiWorkStatus.Running(action.label)
      appendLog(s"started: ${action.label}")

      forkDiscard:
        try
          val report = executionRunner.run(context, current, action)
          runner.runOnRenderThread(() => finish(action, report))
        catch
          case NonFatal(error) =>
            val message = Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
            runner.runOnRenderThread(() => fail(action, message))

  def requestQuit(runner: ToolkitRunner): Unit =
    if isRunning then
      confirmQuit = true
      appendLog("quit requested while work is running")
    else runner.quit()

  def confirmQuitIfVisible(runner: ToolkitRunner): Unit = if confirmQuit then runner.quit()

  def cancelQuitConfirmation(): Unit = if confirmQuit then
    confirmQuit = false
    appendLog("quit cancelled")

  private def finish(action: TuiExecutionAction, report: Either[String, TuiExecutionReport]): Unit =
    report match
      case Left(message) => fail(action, message)
      case Right(value)  =>
        context = context.withState(value.result.state)
        current = rebuildModel(value.result.state, value.action)
        log = (log ++ value.logLines).takeRight(TuiAppState.MaxLogLines)
        status = TuiWorkStatus.Idle
        confirmQuit = false

  private def fail(action: TuiExecutionAction, message: String): Unit =
    appendLog(s"failed: ${action.label}: $message")
    status = TuiWorkStatus.Idle
    confirmQuit = false

  private def rebuildModel(
      state: initkit.core.ExecutionState,
      action: TuiExecutionAction
  ): TuiViewModel = TuiViewModel.from(
    TuiViewModelRequest(
      manifest = context.manifest,
      hostFacts = context.hostFacts,
      state = state,
      stateFile = context.stateFile,
      selection = TuiSelectionInputs.fromOptions(current.selectedEntryNames, Vector.empty),
      dryRun = action.mode == initkit.core.ExecutionRunMode.DryRun
    )
  )

  private def appendLog(lines: String*): Unit =
    log = (log ++ lines).takeRight(TuiAppState.MaxLogLines)

  private def whenIdle(action: => Unit): Unit = if !isRunning then action

  private def isRunning: Boolean = status match
    case TuiWorkStatus.Running(_) => true
    case TuiWorkStatus.Idle       => false

private[tui] object TuiAppState:
  private val MaxLogLines: Int = 200

  def apply(launch: TuiLaunchModel): TuiAppState = new TuiAppState(
    current = launch.viewModel,
    context = launch.context,
    log = TuiTextLayout.outputLines(launch.viewModel),
    status = TuiWorkStatus.Idle,
    confirmQuit = false,
    executionRunner = TuiExecutionRunner()
  )

enum TuiWorkStatus:
  case Idle
  case Running(description: String)

  def label: String = this match
    case Idle                 => "idle"
    case Running(description) => description

private[tui] object TuiRenderer:

  def render(state: TuiAppState, runner: ToolkitRunner)(using Ox): Element =
    val model = state.model

    column(
      statusBar(model, state).length(1),
      checklistPane(model, state).fill(4),
      detailsPane(model).fill(2),
      outputPane(state).fill(1),
      keyHints(state).length(1)
    ).spacing(1)
      .id("initkit-tui")
      .focusable()
      .onKeyEvent(event => handleKey(event, state, runner))

  private def handleKey(event: KeyEvent, state: TuiAppState, runner: ToolkitRunner)(using
      Ox
  ): EventResult =
    if state.quitConfirmationVisible && event.isCharIgnoreCase('y') then
      state.confirmQuitIfVisible(runner)
      EventResult.HANDLED
    else if state.quitConfirmationVisible && event.isCharIgnoreCase('n') then
      state.cancelQuitConfirmation()
      EventResult.HANDLED
    else if event.isQuit() then
      state.requestQuit(runner)
      EventResult.HANDLED
    else if event.isUp() then
      state.moveFocus(-1)
      EventResult.HANDLED
    else if event.isDown() then
      state.moveFocus(1)
      EventResult.HANDLED
    else if event.isHome() then
      state.focusFirst()
      EventResult.HANDLED
    else if event.isEnd() then
      state.focusLast()
      EventResult.HANDLED
    else if event.isSelect() then
      state.toggleFocused()
      EventResult.HANDLED
    else if event.isCharIgnoreCase('a') then
      state.selectAllRunnable()
      EventResult.HANDLED
    else if event.isCharIgnoreCase('p') then
      state.start(TuiExecutionAction.PreviewSelected, runner)
      EventResult.HANDLED
    else if event.isChar('r') then
      state.start(TuiExecutionAction.RunSelected, runner)
      EventResult.HANDLED
    else if event.isChar('R') then
      state.start(TuiExecutionAction.RunAllMatching, runner)
      EventResult.HANDLED
    else if event.isCharIgnoreCase('e') then
      state.start(TuiExecutionAction.Resume, runner)
      EventResult.HANDLED
    else if event.isCharIgnoreCase('d') then
      state.detailsFocused()
      EventResult.HANDLED
    else EventResult.UNHANDLED

  private def statusBar(model: TuiViewModel, state: TuiAppState): TextElement =
    val line = TuiTextLayout.statusLine(model, state.workStatus, state.quitConfirmationVisible)
    text(line).white().bg(Color.indexed(235)).bold().ellipsis()

  private def checklistPane(model: TuiViewModel, state: TuiAppState): Panel =
    framedPanel("[ Plan ]", planTable(model, state = Some(state.syncedPlanTableState()))).fill()

  private def detailsPane(model: TuiViewModel): Panel = framedPanel(
    "[ Details ]",
    scrollable(TuiTextLayout.detailsLines(model).map(line => text(line).ellipsis())*)
  ).fill()

  private def outputPane(state: TuiAppState): Panel = framedPanel(
    "[ Log ]",
    scrollable(state.logLines.map(line => text(line).dim().ellipsis())*)
  ).fill()

  private def keyHints(state: TuiAppState): TextElement =
    text(TuiTextLayout.keyHints(state.quitConfirmationVisible)).gray().ellipsis()

  private def planTable(model: TuiViewModel, state: Option[TableState]): TableElement =
    val rows    = TuiTextLayout.planTableRows(model, TuiPlanTableWidths.Render).map(tableRow)
    val element = table()
      .header("Sel", "#", "Name", "Kind", "Status", "Mode")
      .rows(rows*)
      .widths(length(4), length(4), fill(), length(18), length(10), length(10))
      .columnSpacing(1)
      .highlightSymbol("")
      .highlightStyle(Style.EMPTY.bg(Color.indexed(236)).bold())

    state.map(element.state).getOrElse(element)

  private def tableRow(row: TuiPlanTableRow): TableRow = TableRow
    .from(row.selection, row.index, row.name, row.kind, row.status, row.executionMode)
    .style(statusStyle(row.statusKind, row.focused))

  private def statusStyle(status: TuiPlanRowStatus, focused: Boolean): Style =
    val base = status match
      case TuiPlanRowStatus.Runnable    => Style.EMPTY.white()
      case TuiPlanRowStatus.Skipped     => Style.EMPTY.yellow().dim()
      case TuiPlanRowStatus.Completed   => Style.EMPTY.green()
      case TuiPlanRowStatus.Interrupted => Style.EMPTY.yellow().bold()
      case TuiPlanRowStatus.Failed      => Style.EMPTY.red().bold()
      case TuiPlanRowStatus.Running     => Style.EMPTY.cyan().bold()

    if focused then base.bg(Color.indexed(236)) else base

  private def framedPanel(title: String, child: Element): Panel = panel(title, child)
    .borderType(BorderType.ROUNDED)
    .borderColor(Color.DARK_GRAY)
    .focusedBorderColor(Color.CYAN)
    .titleEllipsis()
    .padding(0)

final case class TuiPlanTableRow(
    selection: String,
    index: String,
    name: String,
    kind: String,
    status: String,
    executionMode: String,
    statusKind: TuiPlanRowStatus,
    focused: Boolean,
    selectable: Boolean
)

final case class TuiPlanTableWidths(
    selection: Int,
    index: Int,
    name: Int,
    kind: Int,
    status: Int,
    executionMode: Int
)

object TuiPlanTableWidths:

  val Render: TuiPlanTableWidths = TuiPlanTableWidths(
    selection = 4,
    index = 4,
    name = 32,
    kind = 18,
    status = 10,
    executionMode = 10
  )

  val Narrow: TuiPlanTableWidths = TuiPlanTableWidths(
    selection = 4,
    index = 3,
    name = 10,
    kind = 8,
    status = 7,
    executionMode = 6
  )

object TuiTextLayout:

  def keyHints(confirmQuit: Boolean = false): String =
    if confirmQuit then "Work is running. Quit anyway? y confirm  n cancel"
    else
      "Up/Down focus  Space toggle  a select all  p preview  r run  R all  e resume  d details  q quit"

  def statusLine(model: TuiViewModel): String =
    statusLine(model, TuiWorkStatus.Idle, confirmQuit = false)

  def statusLine(model: TuiViewModel, status: TuiWorkStatus, confirmQuit: Boolean): String =
    val profile   = model.profile.name
    val mode      = model.runMode.toString.toLowerCase
    val selected  = s"${model.counts.selected}/${model.counts.runnable} selected"
    val skipped   = s"${model.counts.skipped} skipped"
    val completed = s"${model.counts.completed} completed"
    val host      =
      s"${model.host.family}${model.host.distribution.map("/" + _).getOrElse("")} ${model.host.architecture}"
    val work = if confirmQuit then "confirm quit" else status.label
    s" initkit | $profile | $mode | $work | $selected | $skipped | $completed | $host "

  def planTableRows(
      model: TuiViewModel,
      widths: TuiPlanTableWidths = TuiPlanTableWidths.Render
  ): Vector[TuiPlanTableRow] =
    if model.rows.isEmpty then
      Vector(TuiPlanTableRow(
        selection = "---",
        index = "--",
        name = fit("no plan entries", widths.name),
        kind = fit("", widths.kind),
        status = fit("skip -", widths.status),
        executionMode = fit("", widths.executionMode),
        statusKind = TuiPlanRowStatus.Skipped,
        focused = false,
        selectable = false
      ))
    else
      model.rows.map: row =>
        val focused = model.focusedIndex.contains(row.index)
        TuiPlanTableRow(
          selection = fit(selectionMarker(row, focused), widths.selection),
          index = fit(f"${row.index + 1}%02d", widths.index),
          name = fit(row.name, widths.name),
          kind = fit(row.kind, widths.kind),
          status = fit(statusLabel(row.status), widths.status),
          executionMode = fit(row.executionMode, widths.executionMode),
          statusKind = row.status,
          focused = focused,
          selectable = row.selectable
        )

  def renderPlanTableLines(
      model: TuiViewModel,
      widths: TuiPlanTableWidths = TuiPlanTableWidths.Render
  ): Vector[String] =
    val header = renderPlanTableLine(
      Vector("Sel", "#", "Name", "Kind", "Status", "Mode"),
      widths
    )
    header +: planTableRows(model, widths).map(row =>
      renderPlanTableLine(
        Vector(row.selection, row.index, row.name, row.kind, row.status, row.executionMode),
        widths
      )
    )

  def detailsLines(model: TuiViewModel): Vector[String] = model.focusedRow match
    case None      => Vector("No plan entry focused.")
    case Some(row) => Vector(
        s"name: ${row.name}",
        s"kind: ${row.kind}",
        s"status: ${statusLabel(row.status)}",
        s"execution: ${row.executionMode}",
        s"selected: ${if row.selected then "yes" else "no"}",
        s"description: ${row.description.getOrElse("none")}"
      ) ++ row.reasons.map(reason => s"reason: $reason") ++ interruptLines(row)

  def outputLines(model: TuiViewModel): Vector[String] = Vector(
    s"mode: ${model.runMode.toString.toLowerCase}",
    s"state: ${model.stateFile.status.toString.toLowerCase} ${model.stateFile.path}",
    s"resume: last=${model.stateFile.lastCompleted.getOrElse("none")} next=${model.stateFile.nextPlanEntry.getOrElse("none")}",
    s"ready: ${model.selectedEntryNames.size} selected runnable entries"
  )

  private def renderPlanTableLine(cells: Vector[String], widths: TuiPlanTableWidths): String =
    Vector(
      pad(cells(0), widths.selection),
      pad(cells(1), widths.index),
      pad(cells(2), widths.name),
      pad(cells(3), widths.kind),
      pad(cells(4), widths.status),
      pad(cells(5), widths.executionMode)
    ).mkString(" ")

  private def pad(value: String, width: Int): String =
    val fitted = fit(value, width)
    fitted + " " * (width - CharWidth.of(fitted)).max(0)

  private def fit(value: String, width: Int): String =
    if width <= 0 then ""
    else CharWidth.truncateWithEllipsis(value, width, TruncatePosition.END)

  private def selectionMarker(row: TuiPlanRow, focused: Boolean): String =
    val focus  = if focused then ">" else " "
    val marker =
      if row.selected then "[x]"
      else if row.selectable then "[ ]"
      else "---"
    s"$focus$marker"

  private def statusLabel(status: TuiPlanRowStatus): String = status match
    case TuiPlanRowStatus.Runnable    => "run *"
    case TuiPlanRowStatus.Skipped     => "skip -"
    case TuiPlanRowStatus.Completed   => "done ="
    case TuiPlanRowStatus.Interrupted => "stop !"
    case TuiPlanRowStatus.Failed      => "fail x"
    case TuiPlanRowStatus.Running     => "busy ~"

  private def interruptLines(row: TuiPlanRow): Vector[String] = row.interrupt match
    case None         => Vector.empty
    case Some(marker) => Vector(
        s"interrupt: ${marker.reason}",
        s"interrupt state: ${marker.statePath}",
        s"resume from: ${marker.resumeFrom.map(_.toString).getOrElse("default")}",
        s"exit code: ${marker.exitCode.map(_.toString).getOrElse("default")}"
      ) ++ marker.instructions.map(instruction => s"instruction: $instruction")
