package initkit.tui

import java.nio.file.Path

import initkit.config.*
import initkit.core.{ConditionEvaluator, ExecutionRunMode, ExecutionState as CoreExecutionState, PlanEntryState, PlanEntryStatus}
import initkit.host.HostFacts

final case class TuiViewModelRequest(
    manifest: Manifest,
    hostFacts: HostFacts,
    state: CoreExecutionState,
    stateFile: TuiStateFileInput,
    selection: TuiSelectionInputs,
    dryRun: Boolean
)

final case class TuiStateFileInput(
    path: Path,
    existedBeforeLoad: Boolean,
    resetRequested: Boolean
)

final case class TuiSelectionInputs(
    select: Vector[String] = Vector.empty,
    skip: Vector[String] = Vector.empty
)

object TuiSelectionInputs:
  def fromOptions(select: Iterable[String], skip: Iterable[String]): TuiSelectionInputs =
    TuiSelectionInputs(
      select = normalize(select),
      skip = normalize(skip)
    )

  private def normalize(values: Iterable[String]): Vector[String] =
    values.iterator.map(_.trim).filter(_.nonEmpty).toVector

final case class TuiViewModel(
    profile: TuiProfileView,
    host: TuiHostView,
    stateFile: TuiStateFileView,
    rows: Vector[TuiPlanRow],
    focusedIndex: Option[Int],
    counts: TuiPlanCounts,
    runMode: ExecutionRunMode
):
  def selectedEntryNames: Vector[String] =
    rows.collect { case row if row.selected => row.name }

  def toggleFocused: TuiViewModel =
    focusedIndex match
      case Some(index) if rows.isDefinedAt(index) && rows(index).selectable =>
        copy(rows = rows.updated(index, rows(index).copy(selected = !rows(index).selected))).refreshCounts
      case _ =>
        this

  def selectAllRunnable: TuiViewModel =
    copy(rows = rows.map(row => if row.selectable then row.copy(selected = true) else row)).refreshCounts

  private def refreshCounts: TuiViewModel =
    copy(counts = TuiPlanCounts.fromRows(rows))

object TuiViewModel:
  def from(request: TuiViewModelRequest): TuiViewModel =
    val rows = buildRows(request)

    TuiViewModel(
      profile = TuiProfileView.fromManifest(request.manifest),
      host = TuiHostView.fromHostFacts(request.hostFacts),
      stateFile = TuiStateFileView.fromInput(request.stateFile, request.state),
      rows = rows,
      focusedIndex = rows.headOption.map(_.index),
      counts = TuiPlanCounts.fromRows(rows),
      runMode = if request.dryRun then ExecutionRunMode.DryRun else ExecutionRunMode.Apply
    )

  private def buildRows(request: TuiViewModelRequest): Vector[TuiPlanRow] =
    val stateByIndex = request.state.entries.map(entry => entry.index -> entry).toMap

    request.manifest.spec.plan.zipWithIndex.map: (entry, index) =>
      rowFor(
        entry = entry,
        index = index,
        state = stateByIndex.get(index),
        hostFacts = request.hostFacts,
        selection = request.selection
      )

  private def rowFor(
      entry: PlanEntry,
      index: Int,
      state: Option[PlanEntryState],
      hostFacts: HostFacts,
      selection: TuiSelectionInputs
  ): TuiPlanRow =
    val conditionReasons = conditionSkipReasons(entry, hostFacts)
    val status = statusFor(state, conditionReasons)
    val selectable = status == TuiPlanRowStatus.Runnable
    val selected = selectable && initialSelection(entry, selection)

    TuiPlanRow(
      index = index,
      name = entry.name.getOrElse("<unnamed>"),
      kind = entry.kind.getOrElse("<unknown>"),
      description = entry.description,
      status = status,
      selected = selected,
      selectable = selectable,
      reasons = reasonsFor(status, state, conditionReasons),
      interrupt = interruptMarker(index, entry)
    )

  private def conditionSkipReasons(entry: PlanEntry, hostFacts: HostFacts): Vector[String] =
    val result = ConditionEvaluator.evaluate(entry.when, hostFacts)
    if result.matched then Vector.empty
    else result.skipReasons.map(_.message)

  private def statusFor(
      state: Option[PlanEntryState],
      conditionReasons: Vector[String]
  ): TuiPlanRowStatus =
    state.map(_.status) match
      case Some(PlanEntryStatus.Completed)   => TuiPlanRowStatus.Completed
      case Some(PlanEntryStatus.Interrupted) => TuiPlanRowStatus.Interrupted
      case Some(PlanEntryStatus.Failed)      => TuiPlanRowStatus.Failed
      case Some(PlanEntryStatus.Running)     => TuiPlanRowStatus.Running
      case Some(PlanEntryStatus.Skipped)     => TuiPlanRowStatus.Skipped
      case _ if conditionReasons.nonEmpty    => TuiPlanRowStatus.Skipped
      case _                                 => TuiPlanRowStatus.Runnable

  private def initialSelection(entry: PlanEntry, selection: TuiSelectionInputs): Boolean =
    val selectedByInput =
      selection.select.isEmpty || selection.select.exists(selector => matchesSelector(entry, selector))
    val skippedByInput = selection.skip.exists(selector => matchesSelector(entry, selector))

    selectedByInput && !skippedByInput

  private def matchesSelector(entry: PlanEntry, selector: String): Boolean =
    entry.name.contains(selector) || entry.kind.contains(selector)

  private def reasonsFor(
      status: TuiPlanRowStatus,
      state: Option[PlanEntryState],
      conditionReasons: Vector[String]
  ): Vector[String] =
    status match
      case TuiPlanRowStatus.Skipped =>
        state.flatMap(_.message).map(Vector(_)).getOrElse(conditionReasons)
      case TuiPlanRowStatus.Completed =>
        Vector("already completed in state")
      case TuiPlanRowStatus.Interrupted =>
        state.flatMap(_.message).toVector
      case TuiPlanRowStatus.Failed =>
        state.flatMap(_.message).toVector
      case _ =>
        Vector.empty

  private def interruptMarker(index: Int, entry: PlanEntry): Option[TuiInterruptMarker] =
    InstallerSpecDecoder.decode(entry, index).toOption.collect:
      case InstallerSpec.Interrupt(reason, state, instructions, exit) =>
        TuiInterruptMarker(
          reason = reason,
          statePath = state.path,
          resumeFrom = state.resumeFrom,
          instructions = instructions,
          exitCode = exit.code
        )

final case class TuiProfileView(
    name: String,
    apiVersion: String,
    kind: String,
    target: Option[TuiTargetView],
    dryRunDefault: Option[Boolean]
)

object TuiProfileView:
  def fromManifest(manifest: Manifest): TuiProfileView =
    TuiProfileView(
      name = manifest.metadata.name.getOrElse("<unnamed>"),
      apiVersion = manifest.apiVersion.getOrElse("<unknown>"),
      kind = manifest.kind.getOrElse("<unknown>"),
      target = manifest.spec.target.flatMap(_.os).map(TuiTargetView.fromTargetOs),
      dryRunDefault = manifest.spec.policy.flatMap(_.dryRun)
    )

final case class TuiTargetView(
    family: Option[String],
    distribution: Option[String],
    version: Option[String],
    codename: Option[String],
    architecture: Option[String],
    desktop: Option[String]
)

object TuiTargetView:
  def fromTargetOs(os: TargetOs): TuiTargetView =
    TuiTargetView(
      family = os.family,
      distribution = os.distribution,
      version = os.version,
      codename = os.codename,
      architecture = os.architecture,
      desktop = os.desktop
    )

final case class TuiHostView(
    family: String,
    distribution: Option[String],
    version: Option[String],
    codename: Option[String],
    architecture: String
)

object TuiHostView:
  def fromHostFacts(hostFacts: HostFacts): TuiHostView =
    TuiHostView(
      family = hostFacts.os.family,
      distribution = hostFacts.os.distribution,
      version = hostFacts.os.version,
      codename = hostFacts.os.codename,
      architecture = hostFacts.architecture
    )

final case class TuiStateFileView(
    path: String,
    status: TuiStateFileStatus,
    lastCompleted: Option[String],
    nextPlanEntry: Option[String],
    completedEntries: Int
)

object TuiStateFileView:
  def fromInput(input: TuiStateFileInput, state: CoreExecutionState): TuiStateFileView =
    TuiStateFileView(
      path = input.path.toAbsolutePath.normalize().toString,
      status = statusFor(input),
      lastCompleted = state.lastCompleted,
      nextPlanEntry = state.nextPlanEntry,
      completedEntries = CoreExecutionState.completedNames(state).size
    )

  private def statusFor(input: TuiStateFileInput): TuiStateFileStatus =
    if input.resetRequested then TuiStateFileStatus.ResetRequested
    else if input.existedBeforeLoad then TuiStateFileStatus.Existing
    else TuiStateFileStatus.New

enum TuiStateFileStatus:
  case New, Existing, ResetRequested

final case class TuiPlanRow(
    index: Int,
    name: String,
    kind: String,
    description: Option[String],
    status: TuiPlanRowStatus,
    selected: Boolean,
    selectable: Boolean,
    reasons: Vector[String],
    interrupt: Option[TuiInterruptMarker]
)

enum TuiPlanRowStatus:
  case Runnable, Skipped, Completed, Interrupted, Failed, Running

final case class TuiInterruptMarker(
    reason: String,
    statePath: String,
    resumeFrom: Option[InterruptResumeFrom],
    instructions: Vector[String],
    exitCode: Option[Int]
)

final case class TuiPlanCounts(
    runnable: Int,
    selected: Int,
    skipped: Int,
    completed: Int,
    interrupted: Int,
    failed: Int,
    running: Int
)

object TuiPlanCounts:
  def fromRows(rows: Vector[TuiPlanRow]): TuiPlanCounts =
    TuiPlanCounts(
      runnable = count(rows, TuiPlanRowStatus.Runnable),
      selected = rows.count(_.selected),
      skipped = count(rows, TuiPlanRowStatus.Skipped),
      completed = count(rows, TuiPlanRowStatus.Completed),
      interrupted = count(rows, TuiPlanRowStatus.Interrupted),
      failed = count(rows, TuiPlanRowStatus.Failed),
      running = count(rows, TuiPlanRowStatus.Running)
    )

  private def count(rows: Vector[TuiPlanRow], status: TuiPlanRowStatus): Int =
    rows.count(_.status == status)
