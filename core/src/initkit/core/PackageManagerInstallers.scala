package initkit.core

import scala.collection.immutable.VectorMap

import initkit.config.*
import initkit.host.HostFacts

final class PackageManagerInstallers(
    commandExecutor: CommandExecutor,
    aptUpdateBeforeInstall: Boolean = false,
    hostFacts: HostFacts = HostFacts.fake()
) extends PlanOperationInstaller:
  override def installApt(
      operation: PackagePlanOperation[PackageSpec.Apt],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    installPackageCommands(
      operation.summary,
      PackageManagerInstallers.commandSpecs(operation, policy, aptUpdateBeforeInstall),
      policy
    )

  override def installPacman(
      operation: PackagePlanOperation[PackageSpec.Pacman],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    installPackageCommands(
      operation.summary,
      PackageManagerInstallers.commandSpecs(operation, policy, aptUpdateBeforeInstall),
      policy
    )

  override def installDnf(
      operation: PackagePlanOperation[PackageSpec.Dnf],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    installPackageCommands(
      operation.summary,
      PackageManagerInstallers.commandSpecs(operation, policy, aptUpdateBeforeInstall),
      policy
    )

  override def installZypper(
      operation: PackagePlanOperation[PackageSpec.Zypper],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    installPackageCommands(
      operation.summary,
      PackageManagerInstallers.commandSpecs(operation, policy, aptUpdateBeforeInstall),
      policy
    )

  override def installFlatpak(
      operation: PackagePlanOperation[PackageSpec.Flatpak],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    installPackageCommands(
      operation.summary,
      PackageManagerInstallers.commandSpecs(operation, policy, aptUpdateBeforeInstall),
      policy
    )

  override def installSnap(
      operation: PackagePlanOperation[PackageSpec.Snap],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    installPackageCommands(
      operation.summary,
      PackageManagerInstallers.commandSpecs(operation, policy, aptUpdateBeforeInstall),
      policy
    )

  override def installBinaryDownloads(
      operation: InstallerPlanOperation[InstallerSpec.BinaryDownloads],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    unsupported(operation.summary)

  override def installShellScripts(
      operation: InstallerPlanOperation[InstallerSpec.ShellScripts],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    unsupported(operation.summary)

  override def installNerdFonts(
      operation: InstallerPlanOperation[InstallerSpec.NerdFonts],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    unsupported(operation.summary)

  override def installDotfilesApply(
      operation: InstallerPlanOperation[InstallerSpec.DotfilesApply],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    unsupported(operation.summary)

  override def installInterrupt(
      operation: InstallerPlanOperation[InstallerSpec.Interrupt],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    unsupported(operation.summary)

  override def installCommands(
      operation: InstallerPlanOperation[InstallerSpec.Commands],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    new CommandsExecutor(commandExecutor, hostFacts).install(operation, policy)

  private def installPackageCommands(
      summary: PlanOperationSummary,
      commands: Vector[CommandSpec],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    policy.mode match
      case ExecutionRunMode.DryRun =>
        PlanOperationOutcome.DryRun(PackageManagerInstallers.dryRunData(summary, commands))
      case ExecutionRunMode.Apply =>
        commands match
          case _ if commands.isEmpty =>
            PlanOperationOutcome.Completed(Vector("no package commands generated"))
          case _ =>
            val results = runAll(commands)
            val failures = results.filter(!_.succeeded)
            failures.headOption match
              case Some(firstFailure) =>
                PlanOperationOutcome.Failed(
                  PlanFailure(
                    operation = summary,
                    message = s"${failures.size} of ${results.size} package command(s) failed; first failure: " +
                      s"${PackageManagerInstallers.describe(firstFailure.spec)} " +
                      s"(${describeTermination(firstFailure.termination)})",
                    exitCode = firstFailure.exitCode
                  )
                )
              case None =>
                PlanOperationOutcome.Completed(Vector(s"ran ${results.size} package command(s)"))

  private def runAll(commands: Vector[CommandSpec]): Vector[CommandResult] =
    commands.map(commandExecutor.run)

  private def describeTermination(termination: CommandTermination): String =
    termination match
      case CommandTermination.Exited(code) =>
        s"exit code $code"
      case CommandTermination.TimedOut(after) =>
        s"timed out after $after"
      case CommandTermination.Cancelled(message) =>
        s"cancelled: $message"
      case CommandTermination.FailedToStart(message) =>
        s"failed to start: $message"

  private def unsupported(summary: PlanOperationSummary): PlanOperationOutcome =
    PlanOperationOutcome.Failed(
      PlanFailure(summary, "operation is not supported by the package manager installer", exitCode = None)
    )

object PackageManagerInstallers:
  def commandSpecs(
      operation: PackagePlanOperation[? <: PackageSpec],
      policy: ExecutionPolicy,
      aptUpdateBeforeInstall: Boolean = false
  ): Vector[CommandSpec] =
    operation.spec match
      case spec: PackageSpec.Apt =>
        aptCommandSpecs(spec, policy, aptUpdateBeforeInstall)
      case spec: PackageSpec.Pacman =>
        pacmanCommandSpecs(spec, policy)
      case spec: PackageSpec.Dnf =>
        dnfCommandSpecs(spec, policy)
      case spec: PackageSpec.Zypper =>
        zypperCommandSpecs(spec, policy)
      case spec: PackageSpec.Flatpak =>
        flatpakCommandSpecs(spec, policy)
      case spec: PackageSpec.Snap =>
        snapCommandSpecs(spec, policy)

  def dryRunData(
      summary: PlanOperationSummary,
      commands: Vector[CommandSpec]
  ): DryRunOperationData =
    DryRunOperationData(summary, commands.map(dryRunAction))

  def describe(spec: CommandSpec): String =
    spec.redacted.invocation match
      case RedactedCommandInvocation.Direct(argv) =>
        argv.mkString(" ")
      case RedactedCommandInvocation.Shell(command, shell) =>
        (shell :+ command).mkString(" ")

  private def aptCommandSpecs(
      spec: PackageSpec.Apt,
      policy: ExecutionPolicy,
      aptUpdateBeforeInstall: Boolean
  ): Vector[CommandSpec] =
    Option
      .when(spec.update.contains(true) || aptUpdateBeforeInstall)(aptGet(Vector("update"), policy))
      .toVector ++
      spec.install.map(packageName => aptGet(Vector("install", "-y", packageName), policy))

  private def pacmanCommandSpecs(
      spec: PackageSpec.Pacman,
      policy: ExecutionPolicy
  ): Vector[CommandSpec] =
    val installCommand =
      if spec.sync.contains(true) then Vector("-Sy", "--noconfirm")
      else Vector("-S", "--needed", "--noconfirm")

    val syncCommand = Option.when(spec.sync.contains(true))(direct(Vector("pacman") ++ installCommand, policy)).toVector
    val installCommands = spec.install.map: packageName =>
      direct(Vector("pacman", "-S", "--needed", "--noconfirm", packageName), policy)

    syncCommand ++ installCommands

  private def dnfCommandSpecs(
      spec: PackageSpec.Dnf,
      policy: ExecutionPolicy
  ): Vector[CommandSpec] =
    spec.install.map(packageName => direct(Vector("dnf", "install", "-y", packageName), policy))

  private def zypperCommandSpecs(
      spec: PackageSpec.Zypper,
      policy: ExecutionPolicy
  ): Vector[CommandSpec] =
    Option
      .when(spec.refresh.contains(true))(direct(Vector("zypper", "--non-interactive", "refresh"), policy))
      .toVector ++
      spec.install.map: packageName =>
        direct(Vector("zypper", "--non-interactive", "install", "-y", packageName), policy)

  private def flatpakCommandSpecs(
      spec: PackageSpec.Flatpak,
      policy: ExecutionPolicy
  ): Vector[CommandSpec] =
    val scope =
      spec.system match
        case Some(true)  => Vector("--system")
        case Some(false) => Vector("--user")
        case None        => Vector.empty

    spec.install.map: packageName =>
      direct(
        Vector("flatpak", "install", "-y") ++ scope ++ spec.remote.toVector :+ packageName,
        policy
      )

  private def snapCommandSpecs(
      spec: PackageSpec.Snap,
      policy: ExecutionPolicy
  ): Vector[CommandSpec] =
    spec.install.map: item =>
      direct(
        Vector("snap", "install", item.name) ++ Option.when(item.classic.contains(true))("--classic").toVector,
        policy
      )

  private def aptGet(
      arguments: Vector[String],
      policy: ExecutionPolicy
  ): CommandSpec =
    direct(
      Vector("apt-get") ++ arguments,
      policy,
      env = VectorMap("DEBIAN_FRONTEND" -> CommandEnvironmentValue("noninteractive"))
    )

  private def direct(
      values: Vector[String],
      policy: ExecutionPolicy,
      env: VectorMap[String, CommandEnvironmentValue] = VectorMap.empty
  ): CommandSpec =
    CommandSpec.direct(
      argv = values.map(CommandArgument(_)),
      env = env,
      sudo = sudoMode(policy)
    )

  private def sudoMode(policy: ExecutionPolicy): SudoMode =
    if policy.requireSudo then SudoMode.Required
    else SudoMode.Disabled

  private def dryRunAction(command: CommandSpec): DryRunAction =
    command.redacted.invocation match
      case RedactedCommandInvocation.Direct(argv) =>
        DryRunAction.Command(
          argv = argv,
          shell = None,
          sudo = command.sudo == SudoMode.Required,
          workingDirectory = command.cwd.map(_.toString)
        )
      case RedactedCommandInvocation.Shell(commandText, shell) =>
        DryRunAction.Command(
          argv = Vector(commandText),
          shell = Some(shell.mkString(" ")),
          sudo = command.sudo == SudoMode.Required,
          workingDirectory = command.cwd.map(_.toString)
        )
