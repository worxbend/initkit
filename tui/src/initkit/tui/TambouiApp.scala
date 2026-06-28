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

object TambouiApp:
  def run(initialModel: TuiViewModel): Unit =
    val state = TuiAppState(initialModel)
    val runner = ToolkitRunner.create()
    try
      runner.run { () =>
        TuiRenderer.render(state, runner)
      }
    finally
      runner.close()

private[tui] final case class TuiAppState(private var current: TuiViewModel):
  def model: TuiViewModel =
    current

  def moveFocus(delta: Int): Unit =
    current = current.moveFocus(delta)

  def focusFirst(): Unit =
    current = current.focusFirst

  def focusLast(): Unit =
    current = current.focusLast

  def toggleFocused(): Unit =
    current = current.toggleFocused

  def selectAllRunnable(): Unit =
    current = current.selectAllRunnable

private[tui] object TuiRenderer:
  def render(state: TuiAppState, runner: ToolkitRunner): Element =
    val model = state.model

    column(
      statusBar(model).length(1),
      checklistPane(model).fill(4),
      detailsPane(model).fill(2),
      outputPane(model).fill(1),
      keyHints().length(1)
    ).spacing(1)
      .id("initkit-tui")
      .focusable()
      .onKeyEvent(event => handleKey(event, state, runner))

  private def handleKey(event: KeyEvent, state: TuiAppState, runner: ToolkitRunner): EventResult =
    if event.isQuit() then
      runner.quit()
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
    else EventResult.UNHANDLED

  private def statusBar(model: TuiViewModel): TextElement =
    val line = TuiTextLayout.statusLine(model)
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

  private def outputPane(model: TuiViewModel): Panel =
    framedPanel(
      "Output",
      column(TuiTextLayout.outputLines(model).map(line => text(line).dim().ellipsis())*)
    ).fill()

  private def keyHints(): TextElement =
    text(TuiTextLayout.keyHints).gray().ellipsis()

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
  val keyHints: String = "Up/Down focus  Space/Enter toggle  a select all  q quit"

  def statusLine(model: TuiViewModel): String =
    val profile = model.profile.name
    val mode = model.runMode.toString.toLowerCase
    val selected = s"${model.counts.selected}/${model.counts.runnable} selected"
    val skipped = s"${model.counts.skipped} skipped"
    val completed = s"${model.counts.completed} completed"
    val host = s"${model.host.family}${model.host.distribution.map("/" + _).getOrElse("")} ${model.host.architecture}"
    s" initkit | $profile | $mode | $selected | $skipped | $completed | $host "

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
