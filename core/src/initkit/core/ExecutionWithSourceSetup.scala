package initkit.core

import java.time.Clock

import initkit.config.PackageSpecDecoder

object ExecutionWithSourceSetup:
  def run(
      request: ExecutionEngineRequest,
      installer: PlanOperationInstaller,
      sourceSetup: SourceSetupPlan,
      sourceSetupExecutor: SourceSetupExecutor,
      stateWriter: ExecutionStateWriter,
      clock: Clock
  ): Either[ExecutionEngineError, ExecutionEngineResult] =
    if shouldExecuteSourceSetup(request, sourceSetup) then
      executeSourceSetup(sourceSetup, sourceSetupExecutor, request.policy, clock) match
        case SourceSetupStep.Continue(events) =>
          ExecutionEngine
            .run(request, installer, stateWriter, clock)
            .map(prependEvents(events, _))
        case SourceSetupStep.Stop(events, exitCode) =>
          Right(
            ExecutionEngineResult(
              events = events,
              state = request.state,
              result = PlanResult.fromEvents(events, selectedSummaries(request)),
              exitCode = exitCode
            )
          )
    else ExecutionEngine.run(request, installer, stateWriter, clock)

  private def shouldExecuteSourceSetup(
      request: ExecutionEngineRequest,
      sourceSetup: SourceSetupPlan
  ): Boolean =
    selectedHasPackageEntry(request) &&
      (sourceSetup.operations.nonEmpty || request.policy.mode == ExecutionRunMode.DryRun && sourceSetup.aptUpdateBeforeInstall)

  private def selectedHasPackageEntry(request: ExecutionEngineRequest): Boolean =
    selectedEntries(request).exists:
      case SelectedSourceSetupEntry.Runnable(entry) =>
        entry.entry.kind.exists(PackageSpecDecoder.isPackageKind)
      case SelectedSourceSetupEntry.Skipped(_) =>
        false

  private def selectedSummaries(request: ExecutionEngineRequest): Vector[PlanOperationSummary] =
    selectedEntries(request).flatMap:
      case SelectedSourceSetupEntry.Runnable(entry) =>
        PlanOperationSummary.fromPlanEntry(entry.index, entry.entry).toOption
      case SelectedSourceSetupEntry.Skipped(entry) =>
        PlanOperationSummary.fromPlanEntry(entry.index, entry.entry).toOption

  private def selectedEntries(request: ExecutionEngineRequest): Vector[SelectedSourceSetupEntry] =
    val requestWithCompleted = request.selection.copy(
      completed = request.selection.completed ++ ExecutionState.completedNames(request.state)
    )
    val selection = PlanSelector.select(request.manifest, requestWithCompleted, request.hostFacts)

    (selection.skipped.map(SelectedSourceSetupEntry.Skipped.apply) ++
      selection.runnable.map(SelectedSourceSetupEntry.Runnable.apply)).sortBy(_.index)

  private def executeSourceSetup(
      sourceSetup: SourceSetupPlan,
      sourceSetupExecutor: SourceSetupExecutor,
      policy: ExecutionPolicy,
      clock: Clock
  ): SourceSetupStep =
    val summary = SourceSetupExecutor.Summary
    val scheduledAt = clock.instant()
    val startedAt = clock.instant()
    val startEvents = Vector(
      PlanEvent.Scheduled(summary, scheduledAt),
      PlanEvent.Started(summary, startedAt)
    )

    sourceSetupExecutor.execute(sourceSetup, policy, summary) match
      case PlanOperationOutcome.Completed(details) =>
        SourceSetupStep.Continue(startEvents :+ PlanEvent.Completed(summary, details, clock.instant()))
      case PlanOperationOutcome.DryRun(data) =>
        SourceSetupStep.Continue(startEvents :+ PlanEvent.DryRunOperation(summary, data, clock.instant()))
      case PlanOperationOutcome.Failed(failure) =>
        SourceSetupStep.Stop(
          events = startEvents :+ PlanEvent.Failed(summary, failure, clock.instant()),
          exitCode = failure.exitCode.getOrElse(ExecutionEngine.FailureExitCode)
        )
      case PlanOperationOutcome.Interrupted(interrupt) =>
        SourceSetupStep.Stop(
          events = startEvents :+ PlanEvent.Interrupted(summary, interrupt, clock.instant()),
          exitCode = interrupt.exitCode
        )

  private def prependEvents(
      events: Vector[PlanEvent],
      result: ExecutionEngineResult
  ): ExecutionEngineResult =
    val combined = events ++ result.events
    result.copy(
      events = combined,
      result = PlanResult.fromEvents(combined, result.result.remaining)
    )

private enum SelectedSourceSetupEntry:
  case Runnable(entry: RunnablePlanEntry)
  case Skipped(entry: SkippedPlanEntry)

  def index: Int =
    this match
      case Runnable(entry) => entry.index
      case Skipped(entry)  => entry.index

private enum SourceSetupStep:
  case Continue(events: Vector[PlanEvent])
  case Stop(events: Vector[PlanEvent], exitCode: Int)
