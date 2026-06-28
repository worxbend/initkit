package initkit.core

import java.nio.file.{Files, Path}
import scala.collection.immutable.VectorMap
import scala.concurrent.duration.Duration

import initkit.config.*
import utest.*

object PackageManagerInstallersTests extends TestSuite:
  val tests: Tests = Tests:
    test("generates apt commands from config example"):
      val baseCommands = packageCommands("apt-base-cli")
      val containerCommands = packageCommands("apt-containers")

      assert(argvs(baseCommands) == Vector(
        Vector("apt-get", "update")
      ) ++ perPackage(Vector("apt-get", "install", "-y"), baseAptPackages))
      assert(argvs(containerCommands) == perPackage(
        Vector("apt-get", "install", "-y"),
        Vector("docker-ce", "docker-ce-cli", "containerd.io", "docker-buildx-plugin", "docker-compose-plugin")
      ))
      assert(baseCommands.forall(_.sudo == SudoMode.Required))
      assert(baseCommands.forall(_.env == noninteractiveAptEnv))

    test("apt source setup update marker adds apt-get update before package installs"):
      val commands = PackageManagerInstallers.commandSpecs(
        packagePlanOperation("apt-containers"),
        applyPolicy,
        aptUpdateBeforeInstall = true
      )

      assert(argvs(commands).head == Vector("apt-get", "update"))
      assert(argvs(commands).drop(1) == perPackage(
        Vector("apt-get", "install", "-y"),
        Vector("docker-ce", "docker-ce-cli", "containerd.io", "docker-buildx-plugin", "docker-compose-plugin")
      ))

    test("generates pacman commands from config example"):
      val baseCommands = packageCommands("pacman-base-cli")
      val containerCommands = packageCommands("pacman-containers")

      assert(argvs(baseCommands) == Vector(
        Vector("pacman", "-Sy", "--noconfirm")
      ) ++ perPackage(Vector("pacman", "-S", "--needed", "--noconfirm"), basePacmanPackages))
      assert(argvs(containerCommands) == perPackage(
        Vector("pacman", "-S", "--needed", "--noconfirm"),
        Vector("docker", "docker-buildx", "docker-compose")
      ))
      assert(baseCommands.forall(_.sudo == SudoMode.Required))

    test("generates dnf commands from config example"):
      val commands = packageCommands("dnf-base-cli")

      assert(argvs(commands) == perPackage(Vector("dnf", "install", "-y"), baseDnfPackages))
      assert(commands.forall(_.sudo == SudoMode.Required))

    test("generates zypper commands from config example"):
      val baseCommands = packageCommands("zypper-base-cli")
      val containerCommands = packageCommands("zypper-containers")

      assert(argvs(baseCommands) == Vector(
        Vector("zypper", "--non-interactive", "refresh")
      ) ++ perPackage(Vector("zypper", "--non-interactive", "install", "-y"), baseZypperPackages))
      assert(argvs(containerCommands) == perPackage(
        Vector("zypper", "--non-interactive", "install", "-y"),
        Vector("docker-ce", "docker-ce-cli", "containerd.io")
      ))
      assert(baseCommands.forall(_.sudo == SudoMode.Required))

    test("generates flatpak commands from config example"):
      val commands = packageCommands("flatpak-desktop-apps")

      assert(argvs(commands) == perPackage(
        Vector("flatpak", "install", "-y", "--system", "flathub"),
        Vector("com.slack.Slack", "com.spotify.Client", "org.mozilla.firefox")
      ))
      assert(commands.forall(_.sudo == SudoMode.Required))

    test("generates snap commands from config example"):
      val commands = packageCommands("snap-desktop-apps")

      assert(argvs(commands) == Vector(
        Vector("snap", "install", "code", "--classic"),
        Vector("snap", "install", "postman")
      ))
      assert(commands.forall(_.sudo == SudoMode.Required))

    test("dry-run returns command previews without executing package commands"):
      val executor = FakeCommandExecutor(Vector.empty)
      val installer = new PackageManagerInstallers(executor)
      val operation = planOperation("dnf-base-cli")
      val outcome = installer.install(operation, dryRunPolicy)

      val dryRun = outcome match
        case PlanOperationOutcome.DryRun(data) => data
        case other                            => fail(s"expected dry-run outcome, got $other")

      assert(executor.calls.isEmpty)
      assert(dryRun.actions == perPackage(Vector("dnf", "install", "-y"), baseDnfPackages).map(dryRunCommand))

    test("apply mode runs package commands through the command executor"):
      val operation = planOperation("snap-desktop-apps")
      val commands = PackageManagerInstallers.commandSpecs(packagePlanOperation("snap-desktop-apps"), applyPolicy)
      val executor = FakeCommandExecutor(
        commands.map(command => FakeCommandResponse(command, CommandResultData.exited(0, duration = Duration.Zero)))
      )
      val installer = new PackageManagerInstallers(executor)
      val outcome = installer.install(operation, applyPolicy)

      assert(outcome == PlanOperationOutcome.Completed(Vector("ran 2 package command(s)")))
      assert(executor.calls == commands)

    test("apply mode attempts later package commands after an item failure"):
      val operation = planOperation("dnf-base-cli")
      val commands = PackageManagerInstallers.commandSpecs(packagePlanOperation("dnf-base-cli"), applyPolicy)
      val executor = FakeCommandExecutor(
        commands.zipWithIndex.map: (command, index) =>
          val exitCode = if index == 2 then 1 else 0
          FakeCommandResponse(command, CommandResultData.exited(exitCode, duration = Duration.Zero))
      )
      val installer = new PackageManagerInstallers(executor)
      val outcome = installer.install(operation, applyPolicy)

      val failure = outcome match
        case PlanOperationOutcome.Failed(value) => value
        case other                              => fail(s"expected failed outcome, got $other")

      assert(executor.calls == commands)
      assert(failure.exitCode == Some(1))
      assert(failure.message.contains("1 of 8 package command(s) failed"))

    test("sudo is disabled when requireSudo is false"):
      val commands = packageCommands("flatpak-desktop-apps", policy = applyPolicy.copy(requireSudo = false))

      assert(commands.forall(_.sudo == SudoMode.Disabled))

  private val noninteractiveAptEnv: VectorMap[String, CommandEnvironmentValue] =
    VectorMap("DEBIAN_FRONTEND" -> CommandEnvironmentValue("noninteractive"))

  private val baseAptPackages: Vector[String] =
    Vector("build-essential", "ca-certificates", "curl", "git", "gnupg", "jq", "unzip", "zsh")

  private val basePacmanPackages: Vector[String] =
    Vector("base-devel", "ca-certificates", "curl", "git", "gnupg", "jq", "unzip", "zsh")

  private val baseDnfPackages: Vector[String] =
    Vector("@development-tools", "ca-certificates", "curl", "git", "gnupg2", "jq", "unzip", "zsh")

  private val baseZypperPackages: Vector[String] =
    Vector("patterns-devel-base-devel_basis", "ca-certificates", "curl", "git", "gpg2", "jq", "unzip", "zsh")

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

  private def packageCommands(
      name: String,
      policy: ExecutionPolicy = applyPolicy
  ): Vector[CommandSpec] =
    PackageManagerInstallers.commandSpecs(packagePlanOperation(name), policy)

  private def packagePlanOperation(name: String): PackagePlanOperation[? <: PackageSpec] =
    planOperation(name) match
      case PlanOperation.AptPackages(operation)     => operation
      case PlanOperation.PacmanPackages(operation)  => operation
      case PlanOperation.DnfPackages(operation)     => operation
      case PlanOperation.ZypperPackages(operation)  => operation
      case PlanOperation.FlatpakPackages(operation) => operation
      case PlanOperation.SnapPackages(operation)    => operation
      case other                                    => fail(s"plan entry '$name' is not a package operation: $other")

  private def planOperation(name: String): PlanOperation =
    val (entry, index) = manifest.spec.plan.zipWithIndex
      .find((entry, _) => entry.name.contains(name))
      .getOrElse(fail(s"plan entry '$name' not found"))

    PlanOperation.decode(index, entry) match
      case Right(operation) => operation
      case Left(errors)     => fail(errors.map(_.message).mkString("; "))

  private def argvs(commands: Vector[CommandSpec]): Vector[Vector[String]] =
    commands.map: command =>
      command.invocation match
        case CommandInvocation.Direct(argv) => argv.map(_.value)
        case CommandInvocation.Shell(_, _)  => fail("expected direct command")

  private def perPackage(prefix: Vector[String], packageNames: Vector[String]): Vector[Vector[String]] =
    packageNames.map(packageName => prefix :+ packageName)

  private def dryRunCommand(argv: Vector[String]): DryRunAction =
    DryRunAction.Command(
      argv = argv,
      shell = None,
      sudo = true,
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
