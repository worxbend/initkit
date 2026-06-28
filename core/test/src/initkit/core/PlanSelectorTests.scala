package initkit.core

import initkit.config.{Condition, Execution, MatchExpression, OsCondition, PlanEntry, RawYaml}
import initkit.host.HostFacts
import scala.collection.immutable.VectorMap
import utest.*

object PlanSelectorTests extends TestSuite:
  val tests: Tests = Tests:
    test("only matches entries by name or kind"):
      val selection = PlanSelector.select(
        Vector(
          entry("apt-base", "apt-packages"),
          entry("containers", "snap-packages"),
          entry("shell", "commands")
        ),
        PlanSelectionRequest.fromFilters(
          only = Vector("apt-base", "snap-packages"),
          skip = Vector.empty,
          completed = Vector.empty
        ),
        HostFacts.fake()
      )

      assert(runnableNames(selection) == Vector("apt-base", "containers"))
      assert(skippedNames(selection) == Vector("shell"))
      assert(
        selection.skipped.head.reasons == Vector(
          PlanSkipReason.NotMatchedByOnly(Vector("apt-base", "snap-packages"))
        )
      )

    test("skip excludes entries by name or kind"):
      val selection = PlanSelector.select(
        Vector(
          entry("apt-base", "apt-packages"),
          entry("containers", "snap-packages"),
          entry("shell", "commands")
        ),
        PlanSelectionRequest.fromFilters(
          only = Vector.empty,
          skip = Vector("apt-packages", "containers"),
          completed = Vector.empty
        ),
        HostFacts.fake()
      )

      assert(runnableNames(selection) == Vector("shell"))
      assert(skippedNames(selection) == Vector("apt-base", "containers"))
      assert(selection.skipped.flatMap(_.userFacingReasons) == Vector(
        "matched --skip selector 'apt-packages'",
        "matched --skip selector 'containers'"
      ))

    test("condition and completed skips remain reportable"):
      val selection = PlanSelector.select(
        Vector(
          entry("ubuntu-tools", "apt-packages", distribution = Some(MatchExpression.Exact("ubuntu"))),
          entry("fedora-tools", "dnf-packages", distribution = Some(MatchExpression.Exact("fedora"))),
          entry("finished", "commands")
        ),
        PlanSelectionRequest.fromFilters(
          only = Vector.empty,
          skip = Vector.empty,
          completed = Vector("finished")
        ),
        HostFacts.fake(distribution = Some("ubuntu"))
      )

      assert(runnableNames(selection) == Vector("ubuntu-tools"))
      assert(skippedNames(selection) == Vector("fedora-tools", "finished"))
      assert(selection.skipped.head.reasons == Vector(
        PlanSkipReason.ConditionFailed(
          Vector(
            ConditionSkipReason.OsMismatch(
              field = "distribution",
              expected = ConditionExpectation.Exact("fedora"),
              actual = Some("ubuntu")
            )
          )
        )
      ))
      assert(selection.skipped.last.reasons == Vector(PlanSkipReason.AlreadyCompleted("finished")))

    test("selected runnable entries preserve manifest order"):
      val selection = PlanSelector.select(
        Vector(
          entry("first", "commands"),
          entry("second", "apt-packages"),
          entry("third", "commands"),
          entry("fourth", "commands")
        ),
        PlanSelectionRequest.fromFilters(
          only = Vector("commands"),
          skip = Vector("third"),
          completed = Vector.empty
        ),
        HostFacts.fake()
      )

      assert(selection.runnable.map(_.index) == Vector(0, 3))
      assert(runnableNames(selection) == Vector("first", "fourth"))

  private def runnableNames(selection: PlanSelection): Vector[String] =
    selection.runnable.flatMap(_.entry.name)

  private def skippedNames(selection: PlanSelection): Vector[String] =
    selection.skipped.flatMap(_.entry.name)

  private def entry(
      name: String,
      kind: String,
      distribution: Option[MatchExpression] = None
  ): PlanEntry =
    PlanEntry(
      name = Some(name),
      kind = Some(kind),
      description = None,
      execution = Some(
        Execution(mode = Some("sequential"), maxConcurrency = None, failFast = None, locks = Vector.empty)
      ),
      when = distribution.map(condition),
      spec = Some(RawYaml.MappingValue(VectorMap.empty))
    )

  private def condition(distribution: MatchExpression): Condition =
    Condition(
      os = Some(
        OsCondition(
          family = None,
          distribution = Some(distribution),
          version = None,
          codename = None,
          architecture = None,
          desktop = None,
          raw = RawYaml.MappingValue(VectorMap.empty)
        )
      ),
      commandExists = None,
      raw = RawYaml.MappingValue(VectorMap.empty)
    )
