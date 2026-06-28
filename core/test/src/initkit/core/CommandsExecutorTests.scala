package initkit.core

import java.nio.file.{Files, Path}
import scala.concurrent.duration.Duration

import initkit.config.*
import initkit.host.HostFacts
import utest.*

object CommandsExecutorTests extends TestSuite:
  val tests: Tests = Tests:
    test("generates shell-mode command specs from config example"):
      val commands = runnableCommands(hostWithSystemctl, applyPolicy)

      assert(shellCommands(commands) == Vector(
        "systemctl enable --now docker",
        "usermod -aG docker ${user}",
        "mkdir -p ${binDir}"
      ))
      assert(commands.map(_.sudo) == Vector(SudoMode.Required, SudoMode.Required, SudoMode.Disabled))

    test("item-level commandExists skips only the matching command"):
      val commands = runnableCommands(hostWithoutSystemctl, applyPolicy)
      val skipped = skippedSteps(hostWithoutSystemctl, applyPolicy)

      assert(shellCommands(commands) == Vector(
        "usermod -aG docker ${user}",
        "mkdir -p ${binDir}"
      ))
      assert(skipped.map(_.item.name) == Vector("enable-docker"))
      assert(skipped.head.reasons == Vector("required command 'systemctl' is not available"))

    test("apply mode executes runnable commands sequentially"):
      val operation = commandsOperation("post-install")
      val commands = runnableCommands(hostWithSystemctl, applyPolicy)
      val executor = FakeCommandExecutor(
        commands.map(command => FakeCommandResponse(command, CommandResultData.exited(0, duration = Duration.Zero)))
      )
      val installer = new PackageManagerInstallers(executor, hostFacts = hostWithSystemctl)
      val outcome = installer.install(operation, applyPolicy)

      assert(outcome == PlanOperationOutcome.Completed(Vector("ran 3 command(s)")))
      assert(executor.calls == commands)

    test("apply mode does not execute skipped command items"):
      val operation = commandsOperation("post-install")
      val commands = runnableCommands(hostWithoutSystemctl, applyPolicy)
      val executor = FakeCommandExecutor(
        commands.map(command => FakeCommandResponse(command, CommandResultData.exited(0, duration = Duration.Zero)))
      )
      val installer = new PackageManagerInstallers(executor, hostFacts = hostWithoutSystemctl)
      val outcome = installer.install(operation, applyPolicy)

      assert(outcome == PlanOperationOutcome.Completed(Vector("ran 2 command(s), skipped 1 command(s)")))
      assert(executor.calls == commands)

    test("dry-run previews shell command text without executing"):
      val operation = commandsOperation("post-install")
      val executor = FakeCommandExecutor(Vector.empty)
      val installer = new PackageManagerInstallers(executor, hostFacts = hostWithoutSystemctl)
      val outcome = installer.install(operation, dryRunPolicy)

      val dryRun = outcome match
        case PlanOperationOutcome.DryRun(data) => data
        case other                            => fail(s"expected dry-run outcome, got $other")

      assert(executor.calls.isEmpty)
      assert(dryRun.actions == Vector(
        DryRunAction.Message("skip command 'enable-docker': required command 'systemctl' is not available"),
        dryRunShell("usermod -aG docker ${user}", sudo = true),
        dryRunShell("mkdir -p ${binDir}", sudo = false)
      ))

  private val hostWithSystemctl: HostFacts =
    HostFacts.fake(commands = Set("systemctl"))

  private val hostWithoutSystemctl: HostFacts =
    HostFacts.fake(commands = Set.empty)

  private val dryRunPolicy: ExecutionPolicy =
    ExecutionPolicy(
      mode = ExecutionRunMode.DryRun,
      continueOnError = false,
      requireSudo = true,
      reboot = RebootExecutionPolicy(allowed = false, prompt = true)
    )

  private val applyPolicy: ExecutionPolicy =
    dryRunPolicy.copy(mode = ExecutionRunMode.Apply)

  private lazy val manifest: Manifest =
    ManifestLoader.loadValidated(exampleConfigPath) match
      case Right(value) => value
      case Left(error)  => fail(error.message)

  private def commandsOperation(name: String): PlanOperation =
    planOperation(name)

  private def commandsPlanOperation(name: String): InstallerPlanOperation[InstallerSpec.Commands] =
    planOperation(name) match
      case PlanOperation.Commands(operation) => operation
      case other                            => fail(s"plan entry '$name' is not a commands operation: $other")

  private def planOperation(name: String): PlanOperation =
    val (entry, index) = manifest.spec.plan.zipWithIndex
      .find((entry, _) => entry.name.contains(name))
      .getOrElse(fail(s"plan entry '$name' not found"))

    PlanOperation.decode(index, entry) match
      case Right(operation) => operation
      case Left(errors)     => fail(errors.map(_.message).mkString("; "))

  private def runnableCommands(
      hostFacts: HostFacts,
      policy: ExecutionPolicy
  ): Vector[CommandSpec] =
    CommandsExecutor
      .executionSteps(commandsPlanOperation("post-install"), policy, hostFacts)
      .collect:
        case CommandExecutionStep.Run(_, command) => command

  private def skippedSteps(
      hostFacts: HostFacts,
      policy: ExecutionPolicy
  ): Vector[CommandExecutionStep.Skipped] =
    CommandsExecutor
      .executionSteps(commandsPlanOperation("post-install"), policy, hostFacts)
      .collect:
        case step: CommandExecutionStep.Skipped => step

  private def shellCommands(commands: Vector[CommandSpec]): Vector[String] =
    commands.map: command =>
      command.invocation match
        case CommandInvocation.Shell(argument, shell) =>
          assert(shell == Vector("/bin/sh", "-c"))
          argument.value
        case CommandInvocation.Direct(_) =>
          fail("expected shell command")

  private def dryRunShell(command: String, sudo: Boolean): DryRunAction =
    DryRunAction.Command(
      argv = Vector(command),
      shell = Some("/bin/sh -c"),
      sudo = sudo,
      workingDirectory = None
    )

  private def exampleConfigPath: Path =
    Iterator
      .iterate(Path.of("").toAbsolutePath.normalize())(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("config.example.yaml"))
      .find(Files.isRegularFile(_))
      .getOrElse(throw new java.lang.AssertionError("config.example.yaml fixture not found"))

  private def fail(message: String): Nothing =
    throw new java.lang.AssertionError(message)
