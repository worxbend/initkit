package initkit.core

import java.time.Instant
import scala.collection.immutable.VectorMap

import initkit.config.*
import utest.*

object ExecutionContractsTests extends TestSuite:
  val tests: Tests = Tests:
    test("decodes package operations and dispatches to typed package installer methods"):
      val operation = decodedOperation(
        index = 0,
        entry = entry(
          name = "base",
          kind = "apt-packages",
          spec = RawYaml.MappingValue(
            VectorMap(
              "update" -> RawYaml.BooleanValue(true),
              "install" -> RawYaml.SequenceValue(
                Vector(RawYaml.StringValue("curl"), RawYaml.StringValue("git"))
              )
            )
          )
        )
      )

      val outcome = RecordingInstaller.install(operation, policy)

      assert(operation.summary.name == "base")
      assert(operation.summary.kind == "apt-packages")
      assert(outcome == PlanOperationOutcome.Completed(Vector("apt:curl,git")))

    test("decodes command operations and dispatches to typed installer methods"):
      val operation = decodedOperation(
        index = 1,
        entry = entry(
          name = "post-install",
          kind = "commands",
          spec = RawYaml.MappingValue(
            VectorMap(
              "items" -> RawYaml.SequenceValue(
                Vector(
                  RawYaml.MappingValue(
                    VectorMap(
                      "name" -> RawYaml.StringValue("hello"),
                      "run" -> RawYaml.StringValue("echo hello")
                    )
                  )
                )
              )
            )
          )
        )
      )

      val outcome = RecordingInstaller.install(operation, policy)

      assert(operation.summary.index == 1)
      assert(operation.summary.kind == "commands")
      assert(outcome == PlanOperationOutcome.Completed(Vector("commands:echo hello")))

    test("constructs plan events for representative operation outcomes"):
      val operation = summary("install-tools", "apt-packages")
      val failure = PlanFailure(operation, "apt failed", Some(100))
      val interrupt = PlanInterrupt(
        operation = operation,
        reason = "log out before continuing",
        statePath = Some("/tmp/initkit.state.json"),
        resumeFrom = Some(InterruptResumeFrom.Next),
        instructions = Vector("Log out", "Run initkit apply again"),
        exitCode = 75
      )
      val dryRun = DryRunOperationData(
        operation = operation,
        actions = Vector(
          DryRunAction.Command(
            argv = Vector("apt-get", "install", "curl"),
            shell = None,
            sudo = true,
            workingDirectory = None
          ),
          DryRunAction.StateWrite("/tmp/initkit.state.json", Some(InterruptResumeFrom.Next))
        )
      )
      val events = Vector(
        PlanEvent.Scheduled(operation, fixedInstant),
        PlanEvent.Started(operation, fixedInstant),
        PlanEvent.Skipped(operation, Vector("already completed in state"), fixedInstant),
        PlanEvent.Completed(operation, Vector("installed curl"), fixedInstant),
        PlanEvent.Failed(operation, failure, fixedInstant),
        PlanEvent.Interrupted(operation, interrupt, fixedInstant),
        PlanEvent.DryRunOperation(operation, dryRun, fixedInstant)
      )

      assert(events.collect { case event: PlanEvent.Scheduled => event }.size == 1)
      assert(events.collect { case event: PlanEvent.Started => event }.size == 1)
      assert(events.collect { case event: PlanEvent.Skipped => event }.size == 1)
      assert(events.collect { case event: PlanEvent.Completed => event }.size == 1)
      assert(events.collect { case event: PlanEvent.Failed => event }.size == 1)
      assert(events.collect { case event: PlanEvent.Interrupted => event }.size == 1)
      assert(events.collect { case event: PlanEvent.DryRunOperation => event }.head.data == dryRun)

    test("builds plan result counts from terminal events and remaining operations"):
      val completed = summary("done", "commands", index = 0)
      val skipped = summary("skipped", "apt-packages", index = 1)
      val failed = summary("failed", "commands", index = 2)
      val interrupted = summary("pause", "interrupt", index = 3)
      val remaining = summary("remaining", "commands", index = 4)
      val failure = PlanFailure(failed, "command failed", Some(1))
      val interrupt = PlanInterrupt(
        operation = interrupted,
        reason = "manual pause",
        statePath = Some("/tmp/state.json"),
        resumeFrom = Some(InterruptResumeFrom.Current),
        instructions = Vector("Resume this entry"),
        exitCode = 75
      )

      val result = PlanResult.fromEvents(
        events = Vector(
          PlanEvent.Completed(completed, Vector.empty, fixedInstant),
          PlanEvent.Skipped(skipped, Vector("matched --skip selector 'apt-packages'"), fixedInstant),
          PlanEvent.Failed(failed, failure, fixedInstant),
          PlanEvent.Interrupted(interrupted, interrupt, fixedInstant)
        ),
        remaining = Vector(remaining)
      )

      assert(result.counts == PlanResultCounts(completed = 1, skipped = 1, failed = 1, interrupted = 1, remaining = 1))
      assert(result.completed == Vector(completed))
      assert(result.skipped == Vector(PlanSkip(skipped, Vector("matched --skip selector 'apt-packages'"))))
      assert(result.failed == Vector(failure))
      assert(result.interrupted == Vector(interrupt))
      assert(result.remaining == Vector(remaining))

  private val fixedInstant: Instant =
    Instant.parse("2026-06-28T12:00:00Z")

  private val policy: ExecutionPolicy =
    ExecutionPolicy(
      mode = ExecutionRunMode.DryRun,
      continueOnError = false,
      requireSudo = true,
      reboot = RebootExecutionPolicy(allowed = false, prompt = true)
    )

  private object RecordingInstaller extends PlanOperationInstaller:
    override def installApt(
        operation: PackagePlanOperation[PackageSpec.Apt],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      PlanOperationOutcome.Completed(Vector(s"apt:${operation.spec.install.mkString(",")}"))

    override def installPacman(
        operation: PackagePlanOperation[PackageSpec.Pacman],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      unusedPackage(operation)

    override def installDnf(
        operation: PackagePlanOperation[PackageSpec.Dnf],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      unusedPackage(operation)

    override def installZypper(
        operation: PackagePlanOperation[PackageSpec.Zypper],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      unusedPackage(operation)

    override def installFlatpak(
        operation: PackagePlanOperation[PackageSpec.Flatpak],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      unusedPackage(operation)

    override def installSnap(
        operation: PackagePlanOperation[PackageSpec.Snap],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      unusedPackage(operation)

    override def installBinaryDownloads(
        operation: InstallerPlanOperation[InstallerSpec.BinaryDownloads],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      unusedInstaller(operation)

    override def installShellScripts(
        operation: InstallerPlanOperation[InstallerSpec.ShellScripts],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      unusedInstaller(operation)

    override def installNerdFonts(
        operation: InstallerPlanOperation[InstallerSpec.NerdFonts],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      unusedInstaller(operation)

    override def installDotfilesApply(
        operation: InstallerPlanOperation[InstallerSpec.DotfilesApply],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      unusedInstaller(operation)

    override def installInterrupt(
        operation: InstallerPlanOperation[InstallerSpec.Interrupt],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      unusedInstaller(operation)

    override def installCommands(
        operation: InstallerPlanOperation[InstallerSpec.Commands],
        policy: ExecutionPolicy
    ): PlanOperationOutcome =
      PlanOperationOutcome.Completed(Vector(s"commands:${operation.spec.items.head.run}"))

    private def unusedPackage(operation: PackagePlanOperation[PackageSpec]): PlanOperationOutcome =
      PlanOperationOutcome.Completed(Vector(s"unused:${operation.summary.name}"))

    private def unusedInstaller(operation: InstallerPlanOperation[InstallerSpec]): PlanOperationOutcome =
      PlanOperationOutcome.Completed(Vector(s"unused:${operation.summary.name}"))

  private def decodedOperation(index: Int, entry: PlanEntry): PlanOperation =
    PlanOperation.decode(index, entry) match
      case Right(operation) => operation
      case Left(errors)    => fail(errors.map(_.message).mkString("; "))

  private def entry(name: String, kind: String, spec: RawYaml): PlanEntry =
    PlanEntry(
      name = Some(name),
      kind = Some(kind),
      description = None,
      execution = Some(Execution(mode = Some("sequential"), maxConcurrency = None, failFast = None, locks = Vector.empty)),
      when = None,
      spec = Some(spec)
    )

  private def summary(
      name: String,
      kind: String,
      index: Int = 0
  ): PlanOperationSummary =
    PlanOperationSummary(index = index, name = name, kind = kind, description = None)

  private def fail(message: String): Nothing =
    throw new java.lang.AssertionError(message)
