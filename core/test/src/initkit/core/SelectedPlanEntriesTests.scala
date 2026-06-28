package initkit.core

import java.nio.file.Path
import java.time.{Clock, Instant, ZoneOffset}
import scala.collection.immutable.VectorMap

import initkit.config.*
import initkit.host.HostFacts
import utest.*

object SelectedPlanEntriesTests extends TestSuite:

  val tests: Tests = Tests:
    test("merges completed state and preserves manifest order across runnable and skipped entries"):
      val testManifest = manifest(Vector(
        entry("first", "commands"),
        entry("already", "commands"),
        entry("fedora-only", "commands", distribution = Some(MatchExpression.Exact("fedora"))),
        entry("skipped-by-filter", "commands")
      ))
      val state = ExecutionState.markCompleted(
        ExecutionState.initial(testManifest, fixedClock),
        "already",
        fixedClock.instant()
      )
      val selected = SelectedPlanEntries.fromRequest(request(
        testManifest,
        state,
        PlanSelectionRequest(skip = Vector("skipped-by-filter")),
        HostFacts.fake(distribution = Some("ubuntu"))
      ))

      assert(entryNames(selected) == Vector("first", "already", "fedora-only", "skipped-by-filter"))
      assert(entryCases(selected) == Vector("runnable", "skipped", "skipped", "skipped"))
      assert(selected.summaries.map(_.name) ==
        Vector("first", "already", "fedora-only", "skipped-by-filter"))

    test("runnable package predicate ignores package entries skipped from completed state"):
      val testManifest = manifest(Vector(
        entry("already", "apt-packages"),
        entry("command", "commands")
      ))
      val state = ExecutionState.markCompleted(
        ExecutionState.initial(testManifest, fixedClock),
        "already",
        fixedClock.instant()
      )
      val selected = SelectedPlanEntries.fromRequest(request(testManifest, state))

      assert(!selected.existsRunnable(entry =>
        entry.entry.kind.exists(PackageSpecDecoder.isSourceSetupPackageKind)
      ))

  private val fixedClock: Clock = Clock.fixed(
    Instant.parse("2026-06-29T12:00:00Z"),
    ZoneOffset.UTC
  )

  private def request(
      testManifest: Manifest,
      state: ExecutionState,
      selection: PlanSelectionRequest = PlanSelectionRequest(),
      hostFacts: HostFacts = HostFacts.fake()
  ): ExecutionEngineRequest = ExecutionEngineRequest(
    manifest = testManifest,
    selection = selection,
    hostFacts = hostFacts,
    state = state,
    statePath = Path.of("/tmp/initkit-selected-plan-entries-state.json"),
    policy = ExecutionPolicy(
      mode = ExecutionRunMode.Apply,
      continueOnError = false,
      requireSudo = false,
      reboot = RebootExecutionPolicy(allowed = false, prompt = true)
    )
  )

  private def entryNames(selected: SelectedPlanEntries): Vector[String] =
    selected.entries.flatMap(_.planEntry.name)

  private def entryCases(selected: SelectedPlanEntries): Vector[String] = selected.entries.map:
    case SelectedPlanEntry.Runnable(_) => "runnable"
    case SelectedPlanEntry.Skipped(_)  => "skipped"

  private def manifest(entries: Vector[PlanEntry]): Manifest = Manifest(
    apiVersion = Some("initkit.io/v1alpha1"),
    kind = Some("WorkstationProfile"),
    metadata = Metadata(Some("developer-workstation"), VectorMap.empty, VectorMap.empty),
    spec = ManifestSpec(
      target = None,
      policy = None,
      vars = VectorMap.empty,
      sources = None,
      plan = entries
    )
  )

  private def entry(
      name: String,
      kind: String,
      distribution: Option[MatchExpression] = None
  ): PlanEntry = PlanEntry(
    name = Some(name),
    kind = Some(kind),
    description = None,
    execution = Some(Execution(
      mode = Some("sequential"),
      maxConcurrency = None,
      failFast = None,
      locks = Vector.empty
    )),
    when = distribution.map(condition),
    spec = Some(RawYaml.MappingValue(VectorMap.empty))
  )

  private def condition(distribution: MatchExpression): Condition = Condition(
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
