package initkit.tui

import dev.tamboui.layout.Flex.SPACE_BETWEEN
import dev.tamboui.style.Color
import dev.tamboui.toolkit.Toolkit.{column, panel, row, scrollable, spacer, text}
import dev.tamboui.toolkit.element.Element
import dev.tamboui.toolkit.elements.Panel
import dev.tamboui.toolkit.elements.TextElement
import dev.tamboui.toolkit.event.EventResult
import dev.tamboui.toolkit.app.ToolkitRunner
import dev.tamboui.tui.event.KeyEvent
import dev.tamboui.widgets.block.BorderType
import ox.*
import scala.util.control.NonFatal

object TambouiApp:
  def run(launch: TuiLaunchModel): Unit =
    val runner = ToolkitRunner.create()
    supervised:
      val state = TuiAppState(launch)
      try
        runner.run { () =>
          TuiRenderer.render(state, runner)
        }
      finally
        runner.close()

private[tui] final class TuiAppState private (
    private var current: TuiViewModel,
    private var context: TuiExecutionContext,
    private var log: Vector[String],
    private var status: TuiWorkStatus,
    private var confirmQuit: Boolean,
    executionRunner: TuiExecutionRunner
):
  def model: TuiViewModel =
    current

  def logLines: Vector[String] =
    log

  def workStatus: TuiWorkStatus =
    status

  def quitConfirmationVisible: Boolean =
    confirmQuit

  def moveFocus(delta: Int): Unit =
    whenIdle:
      current = current.moveFocus(delta)

  def focusFirst(): Unit =
    whenIdle:
      current = current.focusFirst

  def focusLast(): Unit =
    whenIdle:
      current = current.focusLast

  def toggleFocused(): Unit =
    whenIdle:
      current = current.toggleFocused

  def selectAllRunnable(): Unit =
    whenIdle:
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

  def confirmQuitIfVisible(runner: ToolkitRunner): Unit =
    if confirmQuit then runner.quit()

  def cancelQuitConfirmation(): Unit =
    if confirmQuit then
      confirmQuit = false
      appendLog("quit cancelled")

  private def finish(action: TuiExecutionAction, report: Either[String, TuiExecutionReport]): Unit =
    report match
      case Left(message) =>
        fail(action, message)
      case Right(value) =>
        context = context.withState(value.result.state)
        current = rebuildModel(value.result.state, value.action)
        log = (log ++ value.logLines).takeRight(TuiAppState.MaxLogLines)
        status = TuiWorkStatus.Idle
        confirmQuit = false

  private def fail(action: TuiExecutionAction, message: String): Unit =
    appendLog(s"failed: ${action.label}: $message")
    status = TuiWorkStatus.Idle
    confirmQuit = false

  private def rebuildModel(state: initkit.core.ExecutionState, action: TuiExecutionAction): TuiViewModel =
    TuiViewModel.from(
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

  private def whenIdle(action: => Unit): Unit =
    if !isRunning then action

  private def isRunning: Boolean =
    status match
      case TuiWorkStatus.Running(_) => true
      case TuiWorkStatus.Idle       => false

private[tui] object TuiAppState:
  private val MaxLogLines: Int = 200

  def apply(launch: TuiLaunchModel): TuiAppState =
    new TuiAppState(
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

  def label: String =
    this match
      case Idle                 => "idle"
      case Running(description) => description

private[tui] object TuiRenderer:
  def render(state: TuiAppState, runner: ToolkitRunner)(using Ox): Element =
    val model = state.model

    column(
      statusBar(model, state).length(1),
      checklistPane(model).fill(4),
      detailsPane(model).fill(2),
      outputPane(state).fill(1),
      keyHints(state).length(1)
    ).spacing(1)
      .id("initkit-tui")
      .focusable()
      .onKeyEvent(event => handleKey(event, state, runner))

  private def handleKey(event: KeyEvent, state: TuiAppState, runner: ToolkitRunner)(using Ox): EventResult =
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

  private def checklistPane(model: TuiViewModel): Panel =
    val children = TuiTextLayout.checklistLines(model).map(checklistLineElement)
    framedPanel("Plan checklist", scrollable(children*)).fill()

  private def checklistLineElement(line: TuiChecklistLine): Element =
    val element = text(line.text).ellipsis()
    styleChecklistLine(element, line)

  private def styleChecklistLine(element: TextElement, line: TuiChecklistLine): Element =
    val colored = line.status match
      case TuiPlanRowStatus.Runnable if line.focused => element.cyan().bold()
      case TuiPlanRowStatus.Runnable                 => element.white()
      case TuiPlanRowStatus.Skipped                  => element.yellow().dim()
      case TuiPlanRowStatus.Completed                => element.green()
      case TuiPlanRowStatus.Interrupted              => element.yellow()
      case TuiPlanRowStatus.Failed                   => element.red()
      case TuiPlanRowStatus.Running                  => element.cyan()

    if line.focused then colored.bg(Color.indexed(236)) else colored

  private def detailsPane(model: TuiViewModel): Panel =
    framedPanel(
      "Details",
      column(TuiTextLayout.detailsLines(model).map(line => text(line).ellipsis())*)
    ).fill()

  private def outputPane(state: TuiAppState): Panel =
    framedPanel(
      "Log",
      column(state.logLines.map(line => text(line).dim().ellipsis())*)
    ).fill()

  private def keyHints(state: TuiAppState): TextElement =
    text(TuiTextLayout.keyHints(state.quitConfirmationVisible)).gray().ellipsis()

  private def framedPanel(title: String, child: Element): Panel =
    panel(title, child)
      .borderType(BorderType.PLAIN)
      .borderColor(Color.DARK_GRAY)
      .focusedBorderColor(Color.CYAN)
      .titleEllipsis()
      .padding(0)

final case class TuiChecklistLine(
    text: String,
    status: TuiPlanRowStatus,
    focused: Boolean,
    selectable: Boolean
)

object TuiTextLayout:
  def keyHints(confirmQuit: Boolean = false): String =
    if confirmQuit then "Work is running. Quit anyway? y confirm  n cancel"
    else "Up/Down focus  Space toggle  a select all  p preview  r run  R all  e resume  d details  q quit"

  def statusLine(model: TuiViewModel): String =
    statusLine(model, TuiWorkStatus.Idle, confirmQuit = false)

  def statusLine(model: TuiViewModel, status: TuiWorkStatus, confirmQuit: Boolean): String =
    val profile = model.profile.name
    val mode = model.runMode.toString.toLowerCase
    val selected = s"${model.counts.selected}/${model.counts.runnable} selected"
    val skipped = s"${model.counts.skipped} skipped"
    val completed = s"${model.counts.completed} completed"
    val host = s"${model.host.family}${model.host.distribution.map("/" + _).getOrElse("")} ${model.host.architecture}"
    val work = if confirmQuit then "confirm quit" else status.label
    s" initkit | $profile | $mode | $work | $selected | $skipped | $completed | $host "

  def checklistLines(model: TuiViewModel): Vector[TuiChecklistLine] =
    if model.rows.isEmpty then
      Vector(TuiChecklistLine("  [ ] no plan entries", TuiPlanRowStatus.Skipped, focused = false, selectable = false))
    else
      model.rows.flatMap: row =>
        val focused = model.focusedIndex.contains(row.index)
        val main = TuiChecklistLine(rowText(row, focused), row.status, focused, row.selectable)
        val reasons = row.reasons.map(reason =>
          TuiChecklistLine(s"      reason: $reason", row.status, focused = false, selectable = false)
        )
        main +: reasons

  def detailsLines(model: TuiViewModel): Vector[String] =
    model.focusedRow match
      case None =>
        Vector("No plan entry focused.")
      case Some(row) =>
        Vector(
          s"name: ${row.name}",
          s"kind: ${row.kind}",
          s"status: ${statusLabel(row.status)}",
          s"selected: ${if row.selected then "yes" else "no"}",
          s"description: ${row.description.getOrElse("none")}"
        ) ++ row.reasons.map(reason => s"reason: $reason") ++ interruptLines(row)

  def outputLines(model: TuiViewModel): Vector[String] =
    Vector(
      s"mode: ${model.runMode.toString.toLowerCase}",
      s"state: ${model.stateFile.status.toString.toLowerCase} ${model.stateFile.path}",
      s"resume: last=${model.stateFile.lastCompleted.getOrElse("none")} next=${model.stateFile.nextPlanEntry.getOrElse("none")}",
      s"ready: ${model.selectedEntryNames.size} selected runnable entries"
    )

  private def rowText(row: TuiPlanRow, focused: Boolean): String =
    val focus = if focused then ">" else " "
    val marker = markerFor(row)
    val disabled = if row.selectable then "" else " disabled"
    val description = row.description.map(value => s" - $value").getOrElse("")
    s"$focus $marker ${statusLabel(row.status)} ${row.name} (${row.kind})$disabled$description"

  private def markerFor(row: TuiPlanRow): String =
    row.status match
      case TuiPlanRowStatus.Runnable if row.selected => "[x]"
      case TuiPlanRowStatus.Runnable                 => "[ ]"
      case TuiPlanRowStatus.Completed                => "[=]"
      case TuiPlanRowStatus.Failed                   => "[!]"
      case TuiPlanRowStatus.Interrupted              => "[!]"
      case TuiPlanRowStatus.Running                  => "[~]"
      case TuiPlanRowStatus.Skipped                  => "[-]"

  private def statusLabel(status: TuiPlanRowStatus): String =
    status match
      case TuiPlanRowStatus.Runnable    => "run"
      case TuiPlanRowStatus.Skipped     => "skip"
      case TuiPlanRowStatus.Completed   => "done"
      case TuiPlanRowStatus.Interrupted => "stop"
      case TuiPlanRowStatus.Failed      => "fail"
      case TuiPlanRowStatus.Running     => "busy"

  private def interruptLines(row: TuiPlanRow): Vector[String] =
    row.interrupt match
      case None => Vector.empty
      case Some(marker) =>
        Vector(
          s"interrupt: ${marker.reason}",
          s"interrupt state: ${marker.statePath}",
          s"resume from: ${marker.resumeFrom.map(_.toString).getOrElse("default")}",
          s"exit code: ${marker.exitCode.map(_.toString).getOrElse("default")}"
        ) ++ marker.instructions.map(instruction => s"instruction: $instruction")
