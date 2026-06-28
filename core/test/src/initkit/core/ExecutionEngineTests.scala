package initkit.core

import java.nio.file.{Path, Paths}
import java.time.{Clock, Instant, ZoneOffset}
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.VectorMap

import initkit.config.*
import initkit.host.HostFacts
import utest.*

object ExecutionEngineTests extends TestSuite:
  val tests: Tests = Tests:
    test("runs runnable entries in manifest order and writes completed state"):
      val testManifest = manifest(
        Vector(
          commandEntry("first"),
          commandEntry("second"),
          commandEntry("third")
        )
      )
      val state = ExecutionState.initial(testManifest, fixedClock)
      val installer = RecordingInstaller()
      val stateWriter = RecordingStateWriter()

      val result = runEngine(testManifest, state, installer, stateWriter)

      assert(result.exitCode == 0)
      assert(installer.installedNames == Vector("first", "second", "third"))
      assert(completedEventNames(result.events) == Vector("first", "second", "third"))
      assert(ExecutionState.completedNames(result.state) == Set("first", "second", "third"))
      assert(result.state.nextPlanEntry.isEmpty)
      assert(stateWriter.writes.map(_._1) == Vector.fill(3)(defaultStatePath))

    test("emits skip events for condition-skipped and state-completed entries"):
      val testManifest = manifest(
        Vector(
          commandEntry("already"),
          commandEntry("fedora-only", distribution = Some(MatchExpression.Exact("fedora"))),
          commandEntry("remaining")
        )
      )
      val completedState = ExecutionState.markCompleted(
        ExecutionState.initial(testManifest, fixedClock),
        "already",
        laterInstant
      )
      val installer = RecordingInstaller()
      val stateWriter = RecordingStateWriter()

      val result = runEngine(
        testManifest,
        completedState,
        installer,
        stateWriter,
        hostFacts = HostFacts.fake(distribution = Some("ubuntu"))
      )

      assert(result.exitCode == 0)
      assert(installer.installedNames == Vector("remaining"))
      assert(skippedEventNames(result.events) == Vector("already", "fedora-only"))
      assert(result.events.collect { case PlanEvent.Skipped(_, reasons, _) => reasons }.flatten.contains("already completed in state"))
      assert(
        result.events
          .collect { case PlanEvent.Skipped(_, reasons, _) => reasons }
          .flatten
          .contains("host distribution is 'ubuntu', expected 'fedora'")
      )
      assert(result.state.entries.find(_.name.contains("fedora-only")).exists(_.status == PlanEntryStatus.Skipped))
      assert(ExecutionState.completedNames(result.state) == Set("already", "remaining"))

    test("stops execution on failure when continueOnError is false"):
      val testManifest = manifest(Vector(commandEntry("first"), commandEntry("second")))
      val state = ExecutionState.initial(testManifest, fixedClock)
      val failedSummary = summary("first", index = 0)
      val installer = RecordingInstaller(
        Map(
          "first" -> PlanOperationOutcome.Failed(
            PlanFailure(failedSummary, message = "first failed", exitCode = Some(42))
          )
        )
      )
      val stateWriter = RecordingStateWriter()

      val result = runEngine(testManifest, state, installer, stateWriter)

      assert(result.exitCode == 42)
      assert(installer.installedNames == Vector("first"))
      assert(result.result.failed.map(_.message) == Vector("first failed"))
      assert(result.result.remaining.map(_.name) == Vector("second"))
      assert(result.state.nextPlanEntry.contains("first"))

    test("continues after failures when continueOnError is true"):
      val testManifest = manifest(Vector(commandEntry("first"), commandEntry("second")))
      val state = ExecutionState.initial(testManifest, fixedClock)
      val failedSummary = summary("first", index = 0)
      val installer = RecordingInstaller(
        Map(
          "first" -> PlanOperationOutcome.Failed(
            PlanFailure(failedSummary, message = "first failed", exitCode = Some(42))
          )
        )
      )
      val stateWriter = RecordingStateWriter()

      val result = runEngine(
        testManifest,
        state,
        installer,
        stateWriter,
        policy = policy(continueOnError = true)
      )

      assert(result.exitCode == 1)
      assert(installer.installedNames == Vector("first", "second"))
      assert(result.result.failed.map(_.message) == Vector("first failed"))
      assert(result.result.completed.map(_.name) == Vector("second"))
      assert(result.result.remaining.isEmpty)
      assert(result.state.nextPlanEntry.isEmpty)

    test("dry-run emits operation data without completing or writing state"):
      val testManifest = manifest(Vector(commandEntry("preview")))
      val state = ExecutionState.initial(testManifest, fixedClock)
      val operationSummary = summary("preview", index = 0)
      val dryRunData = DryRunOperationData(
        operation = operationSummary,
        actions = Vector(DryRunAction.Command(Vector("echo", "preview"), None, sudo = false, None))
      )
      val installer = RecordingInstaller(Map("preview" -> PlanOperationOutcome.DryRun(dryRunData)))
      val stateWriter = RecordingStateWriter()

      val result = runEngine(
        testManifest,
        state,
        installer,
        stateWriter,
        policy = policy(mode = ExecutionRunMode.DryRun)
      )

      assert(result.exitCode == 0)
      assert(result.events.collect { case PlanEvent.DryRunOperation(_, data, _) => data } == Vector(dryRunData))
      assert(ExecutionState.completedNames(result.state).isEmpty)
      assert(stateWriter.writes.isEmpty)

    test("interrupt writes configured state, emits resume instructions, and returns configured exit code"):
      val interruptPath = Paths.get("/tmp/initkit-interrupt-state.json")
      val testManifest = manifest(
        Vector(
          commandEntry("before"),
          interruptEntry("pause", interruptPath.toString),
          commandEntry("after")
        )
      )
      val state = ExecutionState.initial(testManifest, fixedClock)
      val installer = RecordingInstaller()
      val stateWriter = RecordingStateWriter()

      val result = runEngine(testManifest, state, installer, stateWriter)

      assert(result.exitCode == 75)
      assert(installer.installedNames == Vector("before"))
      assert(result.result.interrupted.map(_.reason) == Vector("pause for shell refresh"))
      assert(result.result.interrupted.flatMap(_.instructions) == Vector("Log out", "Run initkit apply again", "Paused"))
      assert(result.result.remaining.map(_.name) == Vector("after"))
      assert(ExecutionState.completedNames(result.state) == Set("before", "pause"))
      assert(result.state.nextPlanEntry.contains("after"))
      assert(stateWriter.writes.map(_._1) == Vector(defaultStatePath, interruptPath))

  private val fixedInstant: Instant =
    Instant.parse("2026-06-28T12:00:00Z")

  private val laterInstant: Instant =
    Instant.parse("2026-06-28T12:05:00Z")

  private val fixedClock: Clock =
    Clock.fixed(fixedInstant, ZoneOffset.UTC)

  private val defaultStatePath: Path =
    Paths.get("/tmp/initkit-state.json")

  private def runEngine(
      testManifest: Manifest,
      state: ExecutionState,
      installer: RecordingInstaller,
      stateWriter: RecordingStateWriter,
      hostFacts: HostFacts = HostFacts.fake(),
      policy: ExecutionPolicy = policy()
  ): ExecutionEngineResult =
    val request = ExecutionEngineRequest(
      manifest = testManifest,
      selection = PlanSelectionRequest(),
      hostFacts = hostFacts,
      state = state,
      statePath = defaultStatePath,
      policy = policy
    )

    ExecutionEngine.run(request, installer, stateWriter, fixedClock) match
      case Right(result) => result
      case Left(error)   => fail(error.message)

  private def policy(
      mode: ExecutionRunMode = ExecutionRunMode.Apply,
      continueOnError: Boolean = false
  ): ExecutionPolicy =
    ExecutionPolicy(
      mode = mode,
      continueOnError = continueOnError,
      requireSudo = false,
      reboot = RebootExecutionPolicy(allowed = false, prompt = true)
    )

  private def completedEventNames(events: Vector[PlanEvent]): Vector[String] =
    events.collect { case PlanEvent.Completed(operation, _, _) => operation.name }

  private def skippedEventNames(events: Vector[PlanEvent]): Vector[String] =
    events.collect { case PlanEvent.Skipped(operation, _, _) => operation.name }

  private def manifest(entries: Vector[PlanEntry]): Manifest =
    Manifest(
      apiVersion = Some("initkit.io/v1alpha1"),
      kind = Some("WorkstationProfile"),
      metadata = Metadata(
        name = Some("developer-workstation"),
        labels = VectorMap.empty,
        annotations = VectorMap.empty
      ),
      spec = ManifestSpec(
        target = None,
        policy = None,
        vars = VectorMap.empty,
        sources = None,
        plan = entries
      )
    )

  private def commandEntry(
      name: String,
      distribution: Option[MatchExpression] = None
  ): PlanEntry =
    PlanEntry(
      name = Some(name),
      kind = Some("commands"),
      description = None,
      execution = Some(Execution(mode = Some("sequential"), maxConcurrency = None, failFast = None, locks = Vector.empty)),
      when = distribution.map(condition),
      spec = Some(
        RawYaml.MappingValue(
          VectorMap(
            "items" -> RawYaml.SequenceValue(
              Vector(
                RawYaml.MappingValue(
                  VectorMap(
                    "name" -> RawYaml.StringValue(name),
                    "run" -> RawYaml.StringValue(s"echo $name")
                  )
                )
              )
            )
          )
        )
      )
    )

  private def interruptEntry(name: String, statePath: String): PlanEntry =
    PlanEntry(
      name = Some(name),
      kind = Some("interrupt"),
      description = None,
      execution = Some(Execution(mode = Some("sequential"), maxConcurrency = None, failFast = None, locks = Vector.empty)),
      when = None,
      spec = Some(
        RawYaml.MappingValue(
          VectorMap(
            "reason" -> RawYaml.StringValue("pause for shell refresh"),
            "state" -> RawYaml.MappingValue(
              VectorMap(
                "path" -> RawYaml.StringValue(statePath),
                "format" -> RawYaml.StringValue("json"),
                "resumeFrom" -> RawYaml.StringValue("next")
              )
            ),
            "instructions" -> RawYaml.SequenceValue(
              Vector(RawYaml.StringValue("Log out"), RawYaml.StringValue("Run initkit apply again"))
            ),
            "exit" -> RawYaml.MappingValue(
              VectorMap(
                "code" -> RawYaml.IntegerValue(75),
                "message" -> RawYaml.StringValue("Paused")
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

  private def summary(name: String, index: Int): PlanOperationSummary =
    PlanOperationSummary(index = index, name = name, kind = "commands", description = None)

  private final class RecordingInstaller(
      outcomes: Map[String, PlanOperationOutcome] = Map.empty
  ) extends PlanOperationInstaller:
    private val installedNamesRef = AtomicReference(Vector.empty[String])

    def installedNames: Vector[String] =
      installedNamesRef.get()

    override def installApt(
        operation: PackagePlanOperation[PackageSpec.Apt],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      record(operation.summary)

    override def installPacman(
        operation: PackagePlanOperation[PackageSpec.Pacman],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      record(operation.summary)

    override def installDnf(
        operation: PackagePlanOperation[PackageSpec.Dnf],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      record(operation.summary)

    override def installZypper(
        operation: PackagePlanOperation[PackageSpec.Zypper],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      record(operation.summary)

    override def installFlatpak(
        operation: PackagePlanOperation[PackageSpec.Flatpak],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      record(operation.summary)

    override def installSnap(
        operation: PackagePlanOperation[PackageSpec.Snap],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      record(operation.summary)

    override def installBinaryDownloads(
        operation: InstallerPlanOperation[InstallerSpec.BinaryDownloads],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      record(operation.summary)

    override def installShellScripts(
        operation: InstallerPlanOperation[InstallerSpec.ShellScripts],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      record(operation.summary)

    override def installNerdFonts(
        operation: InstallerPlanOperation[InstallerSpec.NerdFonts],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      record(operation.summary)

    override def installDotfilesApply(
        operation: InstallerPlanOperation[InstallerSpec.DotfilesApply],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      record(operation.summary)

    override def installInterrupt(
        operation: InstallerPlanOperation[InstallerSpec.Interrupt],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      record(operation.summary)

    override def installCommands(
        operation: InstallerPlanOperation[InstallerSpec.Commands],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      record(operation.summary)

    private def record(operation: PlanOperationSummary): PlanOperationOutcome =
      installedNamesRef.set(installedNamesRef.get() :+ operation.name)
      outcomes.getOrElse(operation.name, PlanOperationOutcome.Completed(Vector(s"completed ${operation.name}")))

  private final class RecordingStateWriter extends ExecutionStateWriter:
    private val writesRef = AtomicReference(Vector.empty[(Path, ExecutionState)])

    def writes: Vector[(Path, ExecutionState)] =
      writesRef.get()

    override def write(path: Path, state: ExecutionState): Either[ExecutionStateError, Unit] =
      writesRef.set(writesRef.get() :+ (path -> state))
      Right(())

  private def fail(message: String): Nothing =
    throw new java.lang.AssertionError(message)
