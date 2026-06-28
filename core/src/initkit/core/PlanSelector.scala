package initkit.core

import initkit.config.{Manifest, PlanEntry}
import initkit.host.HostFacts

object PlanSelector:
  def select(
      manifest: Manifest,
      request: PlanSelectionRequest,
      hostFacts: HostFacts
  ): PlanSelection =
    select(manifest.spec.plan, request, hostFacts)

  def select(
      plan: Vector[PlanEntry],
      request: PlanSelectionRequest,
      hostFacts: HostFacts
  ): PlanSelection =
    val selected = plan.zipWithIndex.map: (entry, index) =>
      evaluateEntry(entry, index, request, hostFacts)

    PlanSelection(
      runnable = selected.collect { case entry: RunnablePlanEntry => entry },
      skipped = selected.collect { case entry: SkippedPlanEntry => entry }
    )

  private def evaluateEntry(
      entry: PlanEntry,
      index: Int,
      request: PlanSelectionRequest,
      hostFacts: HostFacts
  ): PlanSelectionEntry =
    val reasons =
      onlyReason(entry, request) ++
        skipReason(entry, request) ++
        conditionReasons(entry, hostFacts) ++
        completedReason(entry, request)

    if reasons.isEmpty then RunnablePlanEntry(index, entry)
    else SkippedPlanEntry(index, entry, reasons)

  private def onlyReason(
      entry: PlanEntry,
      request: PlanSelectionRequest
  ): Vector[PlanSkipReason] =
    Option
      .when(request.only.nonEmpty && !matchesAnySelector(entry, request.only)):
        PlanSkipReason.NotMatchedByOnly(request.only)
      .toVector

  private def skipReason(
      entry: PlanEntry,
      request: PlanSelectionRequest
  ): Vector[PlanSkipReason] =
    request.skip
      .find(selector => matchesSelector(entry, selector))
      .map(selector => PlanSkipReason.SkippedByFilter(selector))
      .toVector

  private def conditionReasons(
      entry: PlanEntry,
      hostFacts: HostFacts
  ): Vector[PlanSkipReason] =
    val result = ConditionEvaluator.evaluate(entry.when, hostFacts)
    Option
      .when(!result.matched):
        PlanSkipReason.ConditionFailed(result.skipReasons)
      .toVector

  private def completedReason(
      entry: PlanEntry,
      request: PlanSelectionRequest
  ): Vector[PlanSkipReason] =
    entry.name
      .filter(request.completed.contains)
      .map(name => PlanSkipReason.AlreadyCompleted(name))
      .toVector

  private def matchesAnySelector(entry: PlanEntry, selectors: Vector[String]): Boolean =
    selectors.exists(selector => matchesSelector(entry, selector))

  private def matchesSelector(entry: PlanEntry, selector: String): Boolean =
    entry.name.contains(selector) || entry.kind.contains(selector)

sealed trait PlanSelectionEntry:
  def index: Int
  def entry: PlanEntry

final case class RunnablePlanEntry(index: Int, entry: PlanEntry) extends PlanSelectionEntry

final case class SkippedPlanEntry(
    index: Int,
    entry: PlanEntry,
    reasons: Vector[PlanSkipReason]
) extends PlanSelectionEntry:
  def userFacingReasons: Vector[String] =
    reasons.flatMap(_.messages)

final case class PlanSelection(
    runnable: Vector[RunnablePlanEntry],
    skipped: Vector[SkippedPlanEntry]
)

final case class PlanSelectionRequest(
    only: Vector[String] = Vector.empty,
    skip: Vector[String] = Vector.empty,
    completed: Set[String] = Set.empty
)

object PlanSelectionRequest:
  def fromFilters(
      only: Iterable[String],
      skip: Iterable[String],
      completed: Iterable[String]
  ): PlanSelectionRequest =
    PlanSelectionRequest(
      only = normalizeSelectors(only),
      skip = normalizeSelectors(skip),
      completed = completed.iterator.map(_.trim).filter(_.nonEmpty).toSet
    )

  private def normalizeSelectors(values: Iterable[String]): Vector[String] =
    values.iterator.map(_.trim).filter(_.nonEmpty).toVector

enum PlanSkipReason:
  case NotMatchedByOnly(selectors: Vector[String])
  case SkippedByFilter(selector: String)
  case ConditionFailed(reasons: Vector[ConditionSkipReason])
  case AlreadyCompleted(entryName: String)

  def messages: Vector[String] =
    this match
      case NotMatchedByOnly(selectors) =>
        Vector(s"did not match --only selector ${describeSelectors(selectors)}")
      case SkippedByFilter(selector) =>
        Vector(s"matched --skip selector '$selector'")
      case ConditionFailed(reasons) =>
        reasons.map(_.message)
      case AlreadyCompleted(_) =>
        Vector("already completed in state")

  private def describeSelectors(selectors: Vector[String]): String =
    selectors.map(selector => s"'$selector'").mkString("one of ", ", ", "")
