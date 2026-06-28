package initkit.core

import initkit.config.PlanEntry

private[core] final case class SelectedPlanEntries(entries: Vector[SelectedPlanEntry]):

  def size: Int = entries.size

  def apply(index: Int): SelectedPlanEntry = entries(index)

  def afterIndex(index: Int): SelectedPlanEntries =
    SelectedPlanEntries(entries.filter(_.index > index))

  def existsRunnable(predicate: RunnablePlanEntry => Boolean): Boolean = entries.exists:
    case SelectedPlanEntry.Runnable(entry) => predicate(entry)
    case SelectedPlanEntry.Skipped(_)      => false

  def summaries: Vector[PlanOperationSummary] = entries.flatMap(_.summary)

private[core] object SelectedPlanEntries:

  def fromRequest(request: ExecutionEngineRequest): SelectedPlanEntries =
    val requestWithCompleted = request.selection.copy(
      completed = request.selection.completed ++ ExecutionState.completedNames(request.state)
    )
    val selection = PlanSelector.select(request.manifest, requestWithCompleted, request.hostFacts)

    SelectedPlanEntries(
      (selection.skipped.map(SelectedPlanEntry.Skipped.apply) ++
        selection.runnable.map(SelectedPlanEntry.Runnable.apply)).sortBy(_.index)
    )

private[core] enum SelectedPlanEntry:
  case Runnable(entry: RunnablePlanEntry)
  case Skipped(entry: SkippedPlanEntry)

  def index: Int = this match
    case Runnable(entry) => entry.index
    case Skipped(entry)  => entry.index

  def planEntry: PlanEntry = this match
    case Runnable(entry) => entry.entry
    case Skipped(entry)  => entry.entry

  def summary: Option[PlanOperationSummary] =
    PlanOperationSummary.fromPlanEntry(index, planEntry).toOption
