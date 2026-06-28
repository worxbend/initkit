package initkit.tui

import java.nio.file.Path
import java.time.{Clock, Instant, ZoneOffset}
import scala.collection.immutable.VectorMap

import initkit.config.*
import initkit.core.{ExecutionState, PlanEntryStatus}
import initkit.host.HostFacts
import utest.*

object TuiViewModelTests extends TestSuite:
  private val clock: Clock =
    Clock.fixed(Instant.parse("2026-06-28T12:00:00Z"), ZoneOffset.UTC)

  val tests: Tests = Tests:
    test("builds ordered rows with profile host state and all statuses"):
      val viewModel = buildViewModel()

      assert(viewModel.profile.name == "developer-workstation")
      assert(viewModel.profile.target.flatMap(_.distribution) == Some("ubuntu"))
      assert(viewModel.host.distribution == Some("ubuntu"))
      assert(viewModel.stateFile.status == TuiStateFileStatus.Existing)
      assert(viewModel.stateFile.lastCompleted == Some("completed"))
      assert(viewModel.rows.map(_.name) == Vector(
        "runnable",
        "fedora-only",
        "completed",
        "pause",
        "failed",
        "running"
      ))
      assert(viewModel.rows.map(_.status) == Vector(
        TuiPlanRowStatus.Runnable,
        TuiPlanRowStatus.Skipped,
        TuiPlanRowStatus.Completed,
        TuiPlanRowStatus.Interrupted,
        TuiPlanRowStatus.Failed,
        TuiPlanRowStatus.Running
      ))
      assert(viewModel.counts.runnable == 1)
      assert(viewModel.counts.skipped == 1)
      assert(viewModel.counts.completed == 1)
      assert(viewModel.counts.interrupted == 1)
      assert(viewModel.counts.failed == 1)
      assert(viewModel.counts.running == 1)

    test("includes condition skip reasons and interrupt markers"):
      val viewModel = buildViewModel()
      val skipped = viewModel.rows(1)
      val pause = viewModel.rows(3)

      assert(skipped.reasons == Vector("host distribution is 'ubuntu', expected 'fedora'"))
      assert(pause.interrupt.contains(
        TuiInterruptMarker(
          reason = "restart the shell",
          statePath = "/tmp/initkit.state.json",
          resumeFrom = Some(InterruptResumeFrom.Next),
          instructions = Vector("Open a new terminal."),
          exitCode = Some(75)
        )
      ))

    test("initial selection applies select and skip inputs only to runnable rows"):
      val viewModel = buildViewModel(
        selection = TuiSelectionInputs.fromOptions(
          select = Vector("commands"),
          skip = Vector("runnable")
        )
      )

      assert(viewModel.rows.head.status == TuiPlanRowStatus.Runnable)
      assert(!viewModel.rows.head.selected)
      assert(!viewModel.rows.exists(row => row.status != TuiPlanRowStatus.Runnable && row.selected))
      assert(viewModel.counts.selected == 0)

    test("focused toggle changes selectable row selection only"):
      val toggledRunnable = buildViewModel().toggleFocused
      val focusedSkipped = buildViewModel().copy(focusedIndex = Some(1)).toggleFocused

      assert(!toggledRunnable.rows.head.selected)
      assert(toggledRunnable.counts.selected == 0)
      assert(!focusedSkipped.rows(1).selected)
      assert(focusedSkipped.rows.head.selected)

    test("select all runnable selects every selectable row"):
      val viewModel = buildViewModel(
        selection = TuiSelectionInputs.fromOptions(select = Vector("does-not-match"), skip = Vector.empty)
      )

      val selected = viewModel.selectAllRunnable

      assert(!viewModel.rows.head.selected)
      assert(selected.rows.head.selected)
      assert(selected.selectedEntryNames == Vector("runnable"))
      assert(selected.counts.selected == 1)

  private def buildViewModel(
      selection: TuiSelectionInputs = TuiSelectionInputs()
  ): TuiViewModel =
    val manifest = workstationManifest()
    val state = stateWithStatuses(manifest)

    TuiViewModel.from(
      TuiViewModelRequest(
        manifest = manifest,
        hostFacts = HostFacts.fake(distribution = Some("ubuntu")),
        state = state,
        stateFile = TuiStateFileInput(
          path = Path.of("/tmp/developer-workstation.state.json"),
          existedBeforeLoad = true,
          resetRequested = false
        ),
        selection = selection,
        dryRun = true
      )
    )

  private def stateWithStatuses(manifest: Manifest): ExecutionState =
    val started = ExecutionState.markStarted(
      ExecutionState.markFailed(
        ExecutionState.markInterrupted(
          ExecutionState.markCompleted(ExecutionState.initial(manifest, clock), "completed", clock.instant()),
          "pause",
          "restart the shell",
          Some(InterruptResumeFrom.Current),
          clock.instant()
        ),
        "failed",
        "command failed",
        clock.instant(),
        continueAfterFailure = true
      ),
      "running",
      clock.instant()
    )

    assert(started.entries.exists(entry => entry.status == PlanEntryStatus.Running))
    started

  private def workstationManifest(): Manifest =
    Manifest(
      apiVersion = Some("initkit.io/v1alpha1"),
      kind = Some("WorkstationProfile"),
      metadata = Metadata(Some("developer-workstation"), VectorMap.empty, VectorMap.empty),
      spec = ManifestSpec(
        target = Some(
          Target(
            Some(
              TargetOs(
                family = Some("linux"),
                distribution = Some("ubuntu"),
                version = Some("24.04"),
                codename = Some("noble"),
                architecture = Some("amd64"),
                desktop = None
              )
            )
          )
        ),
        policy = Some(Policy(dryRun = Some(true), continueOnError = None, requireSudo = None, reboot = None)),
        vars = VectorMap.empty,
        sources = None,
        plan = Vector(
          entry("runnable", "commands"),
          entry("fedora-only", "commands", distribution = Some(MatchExpression.Exact("fedora"))),
          entry("completed", "commands"),
          interruptEntry("pause"),
          entry("failed", "commands"),
          entry("running", "commands")
        )
      )
    )

  private def entry(
      name: String,
      kind: String,
      distribution: Option[MatchExpression] = None
  ): PlanEntry =
    PlanEntry(
      name = Some(name),
      kind = Some(kind),
      description = Some(s"$name description"),
      execution = Some(Execution(Some("sequential"), maxConcurrency = None, failFast = None, locks = Vector.empty)),
      when = distribution.map(condition),
      spec = Some(RawYaml.MappingValue(VectorMap.empty))
    )

  private def interruptEntry(name: String): PlanEntry =
    PlanEntry(
      name = Some(name),
      kind = Some("interrupt"),
      description = Some("pause for shell restart"),
      execution = Some(Execution(Some("sequential"), maxConcurrency = None, failFast = None, locks = Vector.empty)),
      when = None,
      spec = Some(
        RawYaml.MappingValue(
          VectorMap(
            "reason" -> RawYaml.StringValue("restart the shell"),
            "state" -> RawYaml.MappingValue(
              VectorMap(
                "path" -> RawYaml.StringValue("/tmp/initkit.state.json"),
                "format" -> RawYaml.StringValue("json"),
                "resumeFrom" -> RawYaml.StringValue("next")
              )
            ),
            "instructions" -> RawYaml.SequenceValue(Vector(RawYaml.StringValue("Open a new terminal."))),
            "exit" -> RawYaml.MappingValue(
              VectorMap(
                "code" -> RawYaml.IntegerValue(75)
              )
            )
          )
        )
      )
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
