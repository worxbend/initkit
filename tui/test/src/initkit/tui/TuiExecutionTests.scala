package initkit.tui

import java.nio.file.Path
import java.time.{Clock, Instant, ZoneOffset}
import scala.collection.immutable.VectorMap
import scala.concurrent.duration.Duration

import initkit.config.*
import initkit.core.*
import initkit.host.HostFacts
import utest.*

object TuiExecutionTests extends TestSuite:
  private val clock: Clock =
    Clock.fixed(Instant.parse("2026-06-28T12:00:00Z"), ZoneOffset.UTC)

  val tests: Tests = Tests:
    test("preview selected uses engine dry-run operation generation"):
      val fixture = executionFixture(select = Vector("post-install"))
      val runner = TuiExecutionRunner(_ => FakeCommandExecutor(Vector.empty), RecordingStateWriter())

      val report = requireRight(runner.run(fixture.context, fixture.model, TuiExecutionAction.PreviewSelected))

      assert(report.result.events.exists:
        case PlanEvent.DryRunOperation(operation, data, _) =>
          operation.name == "post-install" && data.actions.exists:
            case DryRunAction.Command(argv, shell, _, _, _) =>
              argv == Vector("echo hello") && shell.contains("/bin/sh -c")
            case _ => false
        case _ => false
      )
      assert(report.logLines.exists(_.contains("dry-run: post-install")))
      assert(report.logLines.exists(_.contains("command /bin/sh -c echo hello")))

    test("run selected uses shared execution engine and logs command output"):
      val command = commandSpec("echo hello")
      val fakeExecutor = FakeCommandExecutor(
        Vector(FakeCommandResponse(command, CommandResultData.exited(0, stdout = "hello\n", stderr = "warn\n", duration = Duration.Zero)))
      )
      val writer = RecordingStateWriter()
      val fixture = executionFixture(select = Vector("post-install"))
      val runner = TuiExecutionRunner(_ => fakeExecutor, writer)

      val report = requireRight(runner.run(fixture.context, fixture.model, TuiExecutionAction.RunSelected))

      assert(fakeExecutor.calls == Vector(command))
      assert(writer.writes.exists(_._2.lastCompleted.contains("post-install")))
      assert(report.result.result.completed.map(_.name) == Vector("post-install"))
      assert(report.logLines.exists(_.contains("stdout: hello")))
      assert(report.logLines.exists(_.contains("stderr: warn")))
      assert(report.logLines.exists(_.contains("summary: completed=1")))

    test("apply actions run source setup before package commands"):
      val actions = Vector(
        TuiExecutionAction.RunSelected,
        TuiExecutionAction.RunAllMatching,
        TuiExecutionAction.Resume
      )

      actions.foreach: action =>
        val fixture = packageExecutionFixture(select = Vector("apt-base-cli"))
        val sourceCommand = directCommandSpec("setup-source")
        val packageCommand = packageCommands(fixture.context.manifest).head
        val fakeExecutor = FakeCommandExecutor(
          Vector(sourceCommand, packageCommand).map(command =>
            FakeCommandResponse(command, CommandResultData.exited(0, duration = Duration.Zero))
          )
        )
        val writer = RecordingStateWriter()
        val runner = TuiExecutionRunner(_ => fakeExecutor, writer)
        val context = fixture.context.copy(
          sourceSetup = SourceSetupPlan(
            Vector(SourceSetupOperation.RunCommand("Add source", sourceCommand)),
            Vector.empty,
            aptUpdateBeforeInstall = false
          )
        )

        val report = requireRight(runner.run(context, fixture.model, action))

        assert(fakeExecutor.calls == Vector(sourceCommand, packageCommand))
        assert(report.result.events.collect { case PlanEvent.Completed(operation, _, _) => operation.name } == Vector(
          "source-setup",
          "apt-base-cli"
        ))
        assert(report.logLines.exists(_.contains("completed: source-setup")))

    test("source setup failure stops TUI package execution"):
      val fixture = packageExecutionFixture(select = Vector("apt-base-cli"))
      val sourceCommand = directCommandSpec("setup-source")
      val fakeExecutor = FakeCommandExecutor(
        Vector(FakeCommandResponse(sourceCommand, CommandResultData.exited(5, duration = Duration.Zero)))
      )
      val writer = RecordingStateWriter()
      val runner = TuiExecutionRunner(_ => fakeExecutor, writer)
      val context = fixture.context.copy(
        sourceSetup = SourceSetupPlan(
          Vector(SourceSetupOperation.RunCommand("Add source", sourceCommand)),
          Vector.empty,
          aptUpdateBeforeInstall = false
        )
      )

      val report = requireRight(runner.run(context, fixture.model, TuiExecutionAction.RunSelected))

      assert(fakeExecutor.calls == Vector(sourceCommand))
      assert(writer.writes.isEmpty)
      assert(report.result.exitCode == 5)
      assert(report.logLines.exists(_.contains("failed: source-setup")))
      assert(report.logLines.exists(_.contains("summary: completed=0 skipped=0 failed=1")))

    test("interrupt entries write state and log resume instructions"):
      val writer = RecordingStateWriter()
      val fixture = executionFixture(select = Vector("pause"))
      val runner = TuiExecutionRunner(_ => FakeCommandExecutor(Vector.empty), writer)

      val report = requireRight(runner.run(fixture.context, fixture.model, TuiExecutionAction.RunSelected))

      assert(report.result.result.interrupted.map(_.operation.name) == Vector("pause"))
      assert(writer.writes.exists(_._1 == Path.of("/tmp/initkit-tui-interrupt.state.json")))
      assert(report.logLines.exists(_.contains("state written: /tmp/initkit-tui-interrupt.state.json")))
      assert(report.logLines.exists(_.contains("resume: initkit tui --config /tmp/initkit-profile.yaml --state /tmp/initkit-tui-interrupt.state.json")))
      assert(report.logLines.exists(_.contains("instruction: Open a new terminal.")))

    test("quit confirmation text is shown while work is running"):
      val fixture = executionFixture(select = Vector("post-install"))

      assert(TuiTextLayout.keyHints(confirmQuit = true).contains("Quit anyway?"))
      assert(TuiTextLayout.statusLine(fixture.model, TuiWorkStatus.Running("run selected"), confirmQuit = true).contains("confirm quit"))

  private def executionFixture(select: Vector[String]): ExecutionFixture =
    val manifest = workstationManifest()
    val state = ExecutionState.initial(manifest, clock)
    val stateFile = TuiStateFileInput(
      path = Path.of("/tmp/initkit-tui.state.json"),
      existedBeforeLoad = false,
      resetRequested = false
    )
    val model = TuiViewModel.from(
      TuiViewModelRequest(
        manifest = manifest,
        hostFacts = HostFacts.fake(distribution = Some("ubuntu")),
        state = state,
        stateFile = stateFile,
        selection = TuiSelectionInputs.fromOptions(select, Vector.empty),
        dryRun = true
      )
    )
    val context = TuiExecutionContext(
      manifest = manifest,
      hostFacts = HostFacts.fake(distribution = Some("ubuntu")),
      statePath = Path.of("/tmp/initkit-tui.state.json"),
      stateFile = stateFile,
      state = state,
      sourceSetup = SourceSetupPlan(Vector.empty, Vector.empty, aptUpdateBeforeInstall = false),
      configPath = Path.of("/tmp/initkit-profile.yaml"),
      clock = clock
    )

    ExecutionFixture(model, context)

  private def packageExecutionFixture(select: Vector[String]): ExecutionFixture =
    val manifest = packageManifest()
    val state = ExecutionState.initial(manifest, clock)
    val stateFile = TuiStateFileInput(
      path = Path.of("/tmp/initkit-tui.state.json"),
      existedBeforeLoad = false,
      resetRequested = false
    )
    val model = TuiViewModel.from(
      TuiViewModelRequest(
        manifest = manifest,
        hostFacts = HostFacts.fake(distribution = Some("ubuntu")),
        state = state,
        stateFile = stateFile,
        selection = TuiSelectionInputs.fromOptions(select, Vector.empty),
        dryRun = true
      )
    )
    val context = TuiExecutionContext(
      manifest = manifest,
      hostFacts = HostFacts.fake(distribution = Some("ubuntu")),
      statePath = Path.of("/tmp/initkit-tui.state.json"),
      stateFile = stateFile,
      state = state,
      sourceSetup = SourceSetupPlan(Vector.empty, Vector.empty, aptUpdateBeforeInstall = false),
      configPath = Path.of("/tmp/initkit-profile.yaml"),
      clock = clock
    )

    ExecutionFixture(model, context)

  private def workstationManifest(): Manifest =
    Manifest(
      apiVersion = Some("initkit.io/v1alpha1"),
      kind = Some("WorkstationProfile"),
      metadata = Metadata(Some("developer-workstation"), VectorMap.empty, VectorMap.empty),
      spec = ManifestSpec(
        target = None,
        policy = Some(Policy(dryRun = Some(true), continueOnError = None, requireSudo = None, reboot = None)),
        vars = VectorMap.empty,
        sources = None,
        plan = Vector(
          commandEntry("post-install"),
          interruptEntry("pause")
        )
      )
    )

  private def packageManifest(): Manifest =
    Manifest(
      apiVersion = Some("initkit.io/v1alpha1"),
      kind = Some("WorkstationProfile"),
      metadata = Metadata(Some("developer-workstation"), VectorMap.empty, VectorMap.empty),
      spec = ManifestSpec(
        target = None,
        policy = Some(Policy(dryRun = Some(true), continueOnError = None, requireSudo = None, reboot = None)),
        vars = VectorMap.empty,
        sources = None,
        plan = Vector(aptEntry("apt-base-cli"))
      )
    )

  private def aptEntry(name: String): PlanEntry =
    PlanEntry(
      name = Some(name),
      kind = Some("apt-packages"),
      description = Some("install apt packages"),
      execution = Some(Execution(Some("sequential"), maxConcurrency = None, failFast = None, locks = Vector.empty)),
      when = None,
      spec = Some(
        RawYaml.MappingValue(
          VectorMap(
            "install" -> RawYaml.SequenceValue(Vector(RawYaml.StringValue("curl")))
          )
        )
      )
    )

  private def commandEntry(name: String): PlanEntry =
    PlanEntry(
      name = Some(name),
      kind = Some("commands"),
      description = Some("run a command"),
      execution = Some(Execution(Some("sequential"), maxConcurrency = None, failFast = None, locks = Vector.empty)),
      when = None,
      spec = Some(
        RawYaml.MappingValue(
          VectorMap(
            "items" -> RawYaml.SequenceValue(
              Vector(
                RawYaml.MappingValue(
                  VectorMap(
                    "name" -> RawYaml.StringValue("say-hello"),
                    "run" -> RawYaml.StringValue("echo hello"),
                    "sudo" -> RawYaml.BooleanValue(false)
                  )
                )
              )
            )
          )
        )
      )
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
                "path" -> RawYaml.StringValue("/tmp/initkit-tui-interrupt.state.json"),
                "format" -> RawYaml.StringValue("json"),
                "resumeFrom" -> RawYaml.StringValue("next")
              )
            ),
            "instructions" -> RawYaml.SequenceValue(Vector(RawYaml.StringValue("Open a new terminal."))),
            "exit" -> RawYaml.MappingValue(VectorMap("code" -> RawYaml.IntegerValue(75)))
          )
        )
      )
    )

  private def commandSpec(command: String): CommandSpec =
    CommandSpec.shell(CommandArgument(command), sudo = SudoMode.Disabled)

  private def directCommandSpec(command: String): CommandSpec =
    CommandSpec.direct(Vector(CommandArgument(command)), sudo = SudoMode.Disabled)

  private def packageCommands(manifest: Manifest): Vector[CommandSpec] =
    PlanOperation.decode(0, manifest.spec.plan.head) match
      case Right(PlanOperation.AptPackages(operation)) =>
        PackageManagerInstallers.commandSpecs(
          operation,
          ExecutionPolicy(
            mode = ExecutionRunMode.Apply,
            continueOnError = false,
            requireSudo = false,
            reboot = RebootExecutionPolicy(allowed = false, prompt = true)
          )
        )
      case Right(other) =>
        throw new java.lang.AssertionError(s"expected apt operation, got $other")
      case Left(errors) =>
        throw new java.lang.AssertionError(errors.map(_.message).mkString("; "))

  private def requireRight[A](value: Either[String, A]): A =
    value.fold(error => throw new java.lang.AssertionError(error), identity)

  private final case class ExecutionFixture(
      model: TuiViewModel,
      context: TuiExecutionContext
  )

  private final class RecordingStateWriter extends ExecutionStateWriter:
    private var recorded: Vector[(Path, ExecutionState)] = Vector.empty

    def writes: Vector[(Path, ExecutionState)] =
      recorded

    override def write(path: Path, state: ExecutionState): Either[ExecutionStateError, Unit] =
      recorded = recorded :+ (path -> state)
      Right(())
