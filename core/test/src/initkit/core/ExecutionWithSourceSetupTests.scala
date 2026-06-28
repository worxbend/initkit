package initkit.core

import java.nio.file.Path
import java.time.{Clock, Instant, ZoneOffset}
import scala.collection.immutable.VectorMap
import scala.concurrent.duration.Duration

import initkit.config.*
import initkit.host.HostFacts
import utest.*

object ExecutionWithSourceSetupTests extends TestSuite:
  val tests: Tests = Tests:
    test("apply mode runs source setup before selected package commands"):
      val testManifest = manifest(Vector(aptEntry("apt-base-cli")))
      val state = ExecutionState.initial(testManifest, fixedClock)
      val sourceCommand = commandSpec("setup-source")
      val packageCommand = packageCommands(testManifest).head
      val executor = FakeCommandExecutor(
        Vector(sourceCommand, packageCommand).map(command =>
          FakeCommandResponse(command, CommandResultData.exited(0, duration = Duration.Zero))
        )
      )
      val writer = RecordingStateWriter()

      val result = runWithSourceSetup(testManifest, state, executor, writer, sourceCommand, applyPolicy)

      assert(result.exitCode == 0)
      assert(executor.calls == Vector(sourceCommand, packageCommand))
      assert(result.events.collect { case PlanEvent.Completed(operation, _, _) => operation.name } == Vector("source-setup", "apt-base-cli"))
      assert(writer.writes.map(_._1) == Vector(defaultStatePath))

    test("source setup failure stops package execution"):
      val testManifest = manifest(Vector(aptEntry("apt-base-cli")))
      val state = ExecutionState.initial(testManifest, fixedClock)
      val sourceCommand = commandSpec("setup-source")
      val executor = FakeCommandExecutor(
        Vector(FakeCommandResponse(sourceCommand, CommandResultData.exited(9, duration = Duration.Zero)))
      )
      val writer = RecordingStateWriter()

      val result = runWithSourceSetup(testManifest, state, executor, writer, sourceCommand, applyPolicy)

      assert(result.exitCode == 9)
      assert(executor.calls == Vector(sourceCommand))
      assert(result.result.failed.map(_.operation.name) == Vector("source-setup"))
      assert(result.result.remaining.map(_.name) == Vector("apt-base-cli"))
      assert(writer.writes.isEmpty)

    test("dry-run previews source setup and packages without commands or state writes"):
      val testManifest = manifest(Vector(aptEntry("apt-base-cli")))
      val state = ExecutionState.initial(testManifest, fixedClock)
      val sourceCommand = commandSpec("setup-source")
      val executor = FakeCommandExecutor(Vector.empty)
      val writer = RecordingStateWriter()

      val result = runWithSourceSetup(testManifest, state, executor, writer, sourceCommand, dryRunPolicy)

      assert(result.exitCode == 0)
      assert(executor.calls.isEmpty)
      assert(writer.writes.isEmpty)
      assert(result.events.collect { case PlanEvent.DryRunOperation(operation, _, _) => operation.name } == Vector("source-setup", "apt-base-cli"))
      assert(ExecutionState.completedNames(result.state).isEmpty)

  private val fixedClock: Clock =
    Clock.fixed(Instant.parse("2026-06-28T12:00:00Z"), ZoneOffset.UTC)

  private val defaultStatePath: Path =
    Path.of("/tmp/initkit-source-setup-state.json")

  private val dryRunPolicy: ExecutionPolicy =
    ExecutionPolicy(
      mode = ExecutionRunMode.DryRun,
      continueOnError = false,
      requireSudo = false,
      reboot = RebootExecutionPolicy(allowed = false, prompt = true)
    )

  private val applyPolicy: ExecutionPolicy =
    dryRunPolicy.copy(mode = ExecutionRunMode.Apply)

  private def runWithSourceSetup(
      testManifest: Manifest,
      state: ExecutionState,
      executor: FakeCommandExecutor,
      writer: RecordingStateWriter,
      sourceCommand: CommandSpec,
      policy: ExecutionPolicy
  ): ExecutionEngineResult =
    val request = ExecutionEngineRequest(
      manifest = testManifest,
      selection = PlanSelectionRequest(),
      hostFacts = HostFacts.fake(distribution = Some("ubuntu")),
      state = state,
      statePath = defaultStatePath,
      policy = policy
    )
    val sourceSetup = SourceSetupPlan(
      operations = Vector(SourceSetupOperation.RunCommand("Add source", sourceCommand)),
      skippedSections = Vector.empty,
      aptUpdateBeforeInstall = false
    )

    ExecutionWithSourceSetup
      .run(
        request = request,
        installer = new PackageManagerInstallers(executor),
        sourceSetup = sourceSetup,
        sourceSetupExecutor = SourceSetupExecutor(executor),
        stateWriter = writer,
        clock = fixedClock
      )
      .fold(error => fail(error.message), identity)

  private def packageCommands(testManifest: Manifest): Vector[CommandSpec] =
    PlanOperation.decode(0, testManifest.spec.plan.head) match
      case Right(PlanOperation.AptPackages(operation)) =>
        PackageManagerInstallers.commandSpecs(operation, applyPolicy)
      case Right(other) =>
        fail(s"expected apt operation, got $other")
      case Left(errors) =>
        fail(errors.map(_.message).mkString("; "))

  private def commandSpec(name: String): CommandSpec =
    CommandSpec.direct(Vector(CommandArgument(name)), sudo = SudoMode.Disabled)

  private def manifest(entries: Vector[PlanEntry]): Manifest =
    Manifest(
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

  private def aptEntry(name: String): PlanEntry =
    PlanEntry(
      name = Some(name),
      kind = Some("apt-packages"),
      description = None,
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

  private final class RecordingStateWriter extends ExecutionStateWriter:
    private var recorded: Vector[(Path, ExecutionState)] = Vector.empty

    def writes: Vector[(Path, ExecutionState)] =
      recorded

    override def write(path: Path, state: ExecutionState): Either[ExecutionStateError, Unit] =
      recorded = recorded :+ (path -> state)
      Right(())

  private def fail(message: String): Nothing =
    throw new java.lang.AssertionError(message)
