package initkit.core

import java.nio.file.{Path, Paths}
import java.time.Clock

import initkit.config.{InstallerSpec, InterruptResumeFrom, Manifest, ManifestValidationError}
import initkit.host.HostFacts

final case class ExecutionEngineRequest(
    manifest: Manifest,
    selection: PlanSelectionRequest,
    hostFacts: HostFacts,
    state: ExecutionState,
    statePath: Path,
    policy: ExecutionPolicy
)

final case class ExecutionEngineResult(
    events: Vector[PlanEvent],
    state: ExecutionState,
    result: PlanResult,
    exitCode: Int
)

trait ExecutionStateWriter:
  def write(path: Path, state: ExecutionState): Either[ExecutionStateError, Unit]

object ExecutionStateWriter:
  val live: ExecutionStateWriter = (path: Path, state: ExecutionState) => ExecutionStateStore.write(path, state)

enum ExecutionEngineError:
  case InvalidPlan(errors: Vector[ManifestValidationError])
  case StateWriteFailed(error: ExecutionStateError)

  def message: String =
    this match
      case InvalidPlan(errors) =>
        errors.map(_.message).mkString("; ")
      case StateWriteFailed(error) =>
        error.message

object ExecutionEngine:
  val SuccessExitCode: Int = 0
  val FailureExitCode: Int = 1
  val DefaultInterruptExitCode: Int = 75

  def run(
      request: ExecutionEngineRequest,
      installer: PlanOperationInstaller,
      stateWriter: ExecutionStateWriter,
      clock: Clock
  ): Either[ExecutionEngineError, ExecutionEngineResult] =
    val selected = orderedSelection(request)
    executeSelectedEntries(selected, request, installer, stateWriter, clock)

  private def orderedSelection(request: ExecutionEngineRequest): Vector[SelectedExecutionEntry] =
    val requestWithCompleted = request.selection.copy(
      completed = request.selection.completed ++ ExecutionState.completedNames(request.state)
    )
    val selection = PlanSelector.select(request.manifest, requestWithCompleted, request.hostFacts)

    (selection.skipped.map(SelectedExecutionEntry.Skipped.apply) ++
      selection.runnable.map(SelectedExecutionEntry.Runnable.apply)).sortBy(_.index)

  private def executeSelectedEntries(
      selected: Vector[SelectedExecutionEntry],
      request: ExecutionEngineRequest,
      installer: PlanOperationInstaller,
      stateWriter: ExecutionStateWriter,
      clock: Clock
  ): Either[ExecutionEngineError, ExecutionEngineResult] =
    var state = request.state
    var events = Vector.empty[PlanEvent]
    var stoppedAt = Option.empty[Int]
    var stopExitCode = Option.empty[Int]
    var index = 0

    while index < selected.size && stoppedAt.isEmpty do
      val outcome = selected(index) match
        case SelectedExecutionEntry.Skipped(entry) =>
          handleSkipped(entry, state, request, stateWriter, clock)
        case SelectedExecutionEntry.Runnable(entry) =>
          handleRunnable(entry, state, request, installer, stateWriter, clock)

      outcome match
        case Left(error) => return Left(error)
        case Right(step) =>
          state = step.state
          events = events ++ step.events
          step.stopExitCode.foreach: exitCode =>
            stoppedAt = Some(selected(index).index)
            stopExitCode = Some(exitCode)

      index = index + 1

    val remaining = stoppedAt.toVector.flatMap: stoppedIndex =>
      selected.filter(_.index > stoppedIndex).flatMap(summaryOption)
    val result = PlanResult.fromEvents(events, remaining)
    val exitCode = stopExitCode.getOrElse(exitCodeForCompletedRun(result))

    Right(ExecutionEngineResult(events, state, result, exitCode))

  private def handleSkipped(
      skipped: SkippedPlanEntry,
      state: ExecutionState,
      request: ExecutionEngineRequest,
      stateWriter: ExecutionStateWriter,
      clock: Clock
  ): Either[ExecutionEngineError, EngineStep] =
    PlanOperationSummary.fromPlanEntry(skipped.index, skipped.entry) match
      case Left(errors) => Left(ExecutionEngineError.InvalidPlan(errors))
      case Right(summary) =>
        val at = clock.instant()
        val events = Vector(
          PlanEvent.Scheduled(summary, at),
          PlanEvent.Skipped(summary, skipped.userFacingReasons, at)
        )

        if skipped.reasons.exists(_.isInstanceOf[PlanSkipReason.AlreadyCompleted]) then
          Right(EngineStep(state, events, stopExitCode = None))
        else
          val nextState = ExecutionState.markSkipped(state, summary.name, skipped.userFacingReasons, at)
          writeState(request.statePath, nextState, stateWriter).map(_ =>
            EngineStep(nextState, events, stopExitCode = None)
          )

  private def handleRunnable(
      runnable: RunnablePlanEntry,
      state: ExecutionState,
      request: ExecutionEngineRequest,
      installer: PlanOperationInstaller,
      stateWriter: ExecutionStateWriter,
      clock: Clock
  ): Either[ExecutionEngineError, EngineStep] =
    PlanOperation.decode(runnable) match
      case Left(errors) => Left(ExecutionEngineError.InvalidPlan(errors))
      case Right(operation) =>
        val scheduledAt = clock.instant()
        val startedAt = clock.instant()
        val startedState = ExecutionState.markStarted(state, operation.summary.name, startedAt)
        val startEvents = Vector(
          PlanEvent.Scheduled(operation.summary, scheduledAt),
          PlanEvent.Started(operation.summary, startedAt)
        )

        operation match
          case PlanOperation.Interrupt(interrupt) if request.policy.mode == ExecutionRunMode.Apply =>
            applyManifestInterrupt(interrupt, startedState, startEvents, stateWriter, clock)
          case PlanOperation.Interrupt(interrupt) if request.policy.mode == ExecutionRunMode.DryRun =>
            dryRunManifestInterrupt(interrupt, startedState, startEvents, clock)
          case _ =>
            applyInstallerOutcome(operation, installer.install(operation, request.policy), startedState, startEvents, request, stateWriter, clock)

  private def applyInstallerOutcome(
      operation: PlanOperation,
      outcome: PlanOperationOutcome,
      state: ExecutionState,
      startEvents: Vector[PlanEvent],
      request: ExecutionEngineRequest,
      stateWriter: ExecutionStateWriter,
      clock: Clock
  ): Either[ExecutionEngineError, EngineStep] =
    outcome match
      case PlanOperationOutcome.Completed(details) =>
        val at = clock.instant()
        val nextState = ExecutionState.markCompleted(state, operation.summary.name, at)
        val events = startEvents :+ PlanEvent.Completed(operation.summary, details, at)
        writeState(request.statePath, nextState, stateWriter).map(_ =>
          EngineStep(nextState, events, stopExitCode = None)
        )
      case PlanOperationOutcome.Failed(failure) =>
        val at = clock.instant()
        val nextState = ExecutionState.markFailed(
          state,
          operation.summary.name,
          failure.message,
          at,
          continueAfterFailure = request.policy.continueOnError
        )
        val events = startEvents :+ PlanEvent.Failed(operation.summary, failure, at)
        writeState(request.statePath, nextState, stateWriter).map(_ =>
          EngineStep(
            nextState,
            events,
            stopExitCode = Option.when(!request.policy.continueOnError)(failure.exitCode.getOrElse(FailureExitCode))
          )
        )
      case PlanOperationOutcome.Interrupted(interrupt) =>
        val at = clock.instant()
        val nextState = ExecutionState.markInterrupted(
          state,
          operation.summary.name,
          interrupt.reason,
          interrupt.resumeFrom,
          at
        )
        val events = startEvents :+ PlanEvent.Interrupted(operation.summary, interrupt, at)
        writeState(statePathFor(interrupt.statePath, request.statePath), nextState, stateWriter).map(_ =>
          EngineStep(nextState, events, stopExitCode = Some(interrupt.exitCode))
        )
      case PlanOperationOutcome.DryRun(data) =>
        val at = clock.instant()
        val events = startEvents :+ PlanEvent.DryRunOperation(operation.summary, data, at)
        Right(EngineStep(state, events, stopExitCode = None))

  private def applyManifestInterrupt(
      interrupt: InstallerPlanOperation[InstallerSpec.Interrupt],
      state: ExecutionState,
      startEvents: Vector[PlanEvent],
      stateWriter: ExecutionStateWriter,
      clock: Clock
  ): Either[ExecutionEngineError, EngineStep] =
    val spec = interrupt.spec
    val at = clock.instant()
    val planInterrupt = PlanInterrupt(
      operation = interrupt.summary,
      reason = spec.reason,
      statePath = Some(spec.state.path),
      resumeFrom = spec.state.resumeFrom,
      instructions = spec.instructions ++ spec.exit.message.toVector,
      exitCode = spec.exit.code.getOrElse(DefaultInterruptExitCode)
    )
    val nextState = ExecutionState.markInterrupted(
      state,
      interrupt.summary.name,
      spec.reason,
      spec.state.resumeFrom,
      at
    )
    val events = startEvents :+ PlanEvent.Interrupted(interrupt.summary, planInterrupt, at)

    writeState(Paths.get(spec.state.path), nextState, stateWriter).map(_ =>
      EngineStep(nextState, events, stopExitCode = Some(planInterrupt.exitCode))
    )

  private def dryRunManifestInterrupt(
      interrupt: InstallerPlanOperation[InstallerSpec.Interrupt],
      state: ExecutionState,
      startEvents: Vector[PlanEvent],
      clock: Clock
  ): Either[ExecutionEngineError, EngineStep] =
    val spec = interrupt.spec
    val at = clock.instant()
    val data = DryRunOperationData(
      operation = interrupt.summary,
      actions = Vector(
        DryRunAction.Message(spec.reason),
        DryRunAction.StateWrite(spec.state.path, spec.state.resumeFrom)
      ) ++ spec.instructions.map(DryRunAction.Message.apply)
    )

    Right(EngineStep(state, startEvents :+ PlanEvent.DryRunOperation(interrupt.summary, data, at), None))

  private def writeState(
      path: Path,
      state: ExecutionState,
      stateWriter: ExecutionStateWriter
  ): Either[ExecutionEngineError, Unit] =
    stateWriter.write(path, state).left.map(ExecutionEngineError.StateWriteFailed.apply)

  private def statePathFor(path: Option[String], defaultPath: Path): Path =
    path.map(Paths.get(_)).getOrElse(defaultPath)

  private def exitCodeForCompletedRun(result: PlanResult): Int =
    if result.failed.nonEmpty then FailureExitCode
    else SuccessExitCode

  private def summaryOption(entry: SelectedExecutionEntry): Option[PlanOperationSummary] =
    val planEntry = entry match
      case SelectedExecutionEntry.Runnable(runnable) => runnable.entry
      case SelectedExecutionEntry.Skipped(skipped)   => skipped.entry

    PlanOperationSummary.fromPlanEntry(entry.index, planEntry).toOption

private enum SelectedExecutionEntry:
  case Runnable(entry: RunnablePlanEntry)
  case Skipped(entry: SkippedPlanEntry)

  def index: Int =
    this match
      case Runnable(entry) => entry.index
      case Skipped(entry)  => entry.index

private final case class EngineStep(
    state: ExecutionState,
    events: Vector[PlanEvent],
    stopExitCode: Option[Int]
)
