package initkit.core

import scala.collection.immutable.VectorMap

import initkit.config.*
import initkit.host.HostFacts

final class PackageManagerInstallers(
    commandExecutor: CommandExecutor,
    aptUpdateBeforeInstall: Boolean = false,
    hostFacts: HostFacts = HostFacts.fake(),
    binaryDownloadHttpClient: BinaryDownloadHttpClient = BinaryDownloadHttpClient.Sttp,
    binaryDownloadFiles: BinaryDownloadFiles = BinaryDownloadFiles.Jvm,
    binaryDownloadHttpConfig: BinaryDownloadHttpConfig = BinaryDownloadHttpConfig.default,
    shellScriptDownloader: ShellScriptDownloader = ShellScriptDownloader.Jdk,
    shellScriptFiles: ShellScriptFiles = ShellScriptFiles.Jvm,
    nerdFontsFiles: NerdFontsFiles = NerdFontsFiles.Jvm,
    dotfilesFiles: DotfilesFiles = DotfilesFiles.Jvm
) extends PlanOperationInstaller:

  override def installApt(
      operation: PackagePlanOperation[PackageSpec.Apt],
      policy: ExecutionPolicy
  ): PlanOperationOutcome = installPackageOperation(operation, policy)

  override def installPacman(
      operation: PackagePlanOperation[PackageSpec.Pacman],
      policy: ExecutionPolicy
  ): PlanOperationOutcome = installPackageOperation(operation, policy)

  override def installDnf(
      operation: PackagePlanOperation[PackageSpec.Dnf],
      policy: ExecutionPolicy
  ): PlanOperationOutcome = installPackageOperation(operation, policy)

  override def installZypper(
      operation: PackagePlanOperation[PackageSpec.Zypper],
      policy: ExecutionPolicy
  ): PlanOperationOutcome = installPackageOperation(operation, policy)

  override def installFlatpak(
      operation: PackagePlanOperation[PackageSpec.Flatpak],
      policy: ExecutionPolicy
  ): PlanOperationOutcome = installPackageOperation(operation, policy)

  override def installSnap(
      operation: PackagePlanOperation[PackageSpec.Snap],
      policy: ExecutionPolicy
  ): PlanOperationOutcome = installPackageOperation(operation, policy)

  override def installAur(
      operation: PackagePlanOperation[PackageSpec.Aur],
      policy: ExecutionPolicy
  ): PlanOperationOutcome = installPackageOperation(operation, policy)

  override def installCargo(
      operation: PackagePlanOperation[PackageSpec.Cargo],
      policy: ExecutionPolicy
  ): PlanOperationOutcome = installPackageOperation(operation, policy)

  override def installSdkman(
      operation: PackagePlanOperation[PackageSpec.Sdkman],
      policy: ExecutionPolicy
  ): PlanOperationOutcome = installPackageOperation(operation, policy)

  override def installBinaryDownloads(
      operation: InstallerPlanOperation[InstallerSpec.BinaryDownloads],
      policy: ExecutionPolicy
  ): PlanOperationOutcome = new BinaryDownloadsExecutor(
    binaryDownloadHttpClient,
    binaryDownloadFiles,
    binaryDownloadHttpConfig,
    commandExecutor
  )
    .install(operation, policy)

  override def installShellScripts(
      operation: InstallerPlanOperation[InstallerSpec.ShellScripts],
      policy: ExecutionPolicy
  ): PlanOperationOutcome = new ShellScriptsExecutor(
    commandExecutor,
    shellScriptDownloader,
    shellScriptFiles
  ).install(operation, policy)

  override def installNerdFonts(
      operation: InstallerPlanOperation[InstallerSpec.NerdFonts],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    new NerdFontsExecutor(commandExecutor, nerdFontsFiles).install(operation, policy)

  override def installDotfilesApply(
      operation: InstallerPlanOperation[InstallerSpec.DotfilesApply],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    new DotfilesExecutor(commandExecutor, dotfilesFiles).install(operation, policy)

  override def installFileWrites(
      operation: InstallerPlanOperation[InstallerSpec.FileWrites],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    new FileWritesExecutor(commandExecutor, hostFacts).install(operation, policy)

  override def installInterrupt(
      operation: InstallerPlanOperation[InstallerSpec.Interrupt],
      policy: ExecutionPolicy
  ): PlanOperationOutcome = unsupported(operation.summary)

  override def installCommands(
      operation: InstallerPlanOperation[InstallerSpec.Commands],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    new CommandsExecutor(commandExecutor, hostFacts).install(operation, policy)

  private def installPackageOperation(
      operation: PackagePlanOperation[? <: PackageSpec],
      policy: ExecutionPolicy
  ): PlanOperationOutcome = installPackageCommands(
    operation.summary,
    PackageManagerInstallers.commandSpecs(operation, policy, aptUpdateBeforeInstall),
    policy
  )

  private def installPackageCommands(
      summary: PlanOperationSummary,
      commands: Vector[CommandSpec],
      policy: ExecutionPolicy
  ): PlanOperationOutcome = policy.mode match
    case ExecutionRunMode.DryRun =>
      PlanOperationOutcome.DryRun(PackageManagerInstallers.dryRunData(summary, commands))
    case ExecutionRunMode.Apply => commands match
        case _ if commands.isEmpty =>
          PlanOperationOutcome.Completed(Vector("no package commands generated"))
        case _ =>
          val results  = runAll(commands)
          val failures = results.filter(!_.succeeded)
          failures.headOption match
            case Some(firstFailure) => PlanOperationOutcome.Failed(
                PlanFailure(
                  operation = summary,
                  message =
                    s"${failures.size} of ${results.size} package command(s) failed; first failure: " +
                      s"${PackageManagerInstallers.describe(firstFailure.spec)} " +
                      s"(${describeTermination(firstFailure.termination)})",
                  exitCode = firstFailure.exitCode
                )
              )
            case None =>
              PlanOperationOutcome.Completed(Vector(s"ran ${results.size} package command(s)"))

  private def runAll(commands: Vector[CommandSpec]): Vector[CommandResult] =
    commands.map(commandExecutor.run)

  private def describeTermination(termination: CommandTermination): String = termination match
    case CommandTermination.Exited(code)           => s"exit code $code"
    case CommandTermination.TimedOut(after)        => s"timed out after $after"
    case CommandTermination.Cancelled(message)     => s"cancelled: $message"
    case CommandTermination.FailedToStart(message) => s"failed to start: $message"

  private def unsupported(summary: PlanOperationSummary): PlanOperationOutcome =
    PlanOperationOutcome.Failed(
      PlanFailure(
        summary,
        "operation is not supported by the package manager installer",
        exitCode = None
      )
    )

object PackageManagerInstallers:

  def commandSpecs(
      operation: PackagePlanOperation[? <: PackageSpec],
      policy: ExecutionPolicy,
      aptUpdateBeforeInstall: Boolean = false
  ): Vector[CommandSpec] = operation.spec match
    case spec: PackageSpec.Apt     => aptCommandSpecs(spec, policy, aptUpdateBeforeInstall)
    case spec: PackageSpec.Pacman  => pacmanCommandSpecs(spec, policy)
    case spec: PackageSpec.Dnf     => dnfCommandSpecs(spec, policy)
    case spec: PackageSpec.Zypper  => zypperCommandSpecs(spec, policy)
    case spec: PackageSpec.Flatpak => flatpakCommandSpecs(spec, policy)
    case spec: PackageSpec.Snap    => snapCommandSpecs(spec, policy)
    case spec: PackageSpec.Aur     => aurCommandSpecs(spec)
    case spec: PackageSpec.Cargo   => cargoCommandSpecs(spec)
    case spec: PackageSpec.Sdkman  => sdkmanCommandSpecs(spec)

  def dryRunData(
      summary: PlanOperationSummary,
      commands: Vector[CommandSpec]
  ): DryRunOperationData = DryRunOperationData(summary, commands.map(dryRunAction))

  def describe(spec: CommandSpec): String = spec.redacted.invocation match
    case RedactedCommandInvocation.Direct(argv)          => argv.mkString(" ")
    case RedactedCommandInvocation.Shell(command, shell) => (shell :+ command).mkString(" ")

  private def aptCommandSpecs(
      spec: PackageSpec.Apt,
      policy: ExecutionPolicy,
      aptUpdateBeforeInstall: Boolean
  ): Vector[CommandSpec] = Option
    .when(spec.update.contains(true) || aptUpdateBeforeInstall)(aptGet(Vector("update"), policy))
    .toVector ++ aptActionCommandSpecs(spec.actions, policy) ++
    spec.install.map(packageName => aptGet(Vector("install", "-y", packageName), policy))

  private def pacmanCommandSpecs(
      spec: PackageSpec.Pacman,
      policy: ExecutionPolicy
  ): Vector[CommandSpec] =
    val installCommand =
      if spec.sync.contains(true) then Vector("-Sy", "--noconfirm")
      else Vector("-S", "--needed", "--noconfirm")

    val syncCommand = Option.when(spec.sync.contains(true))(direct(
      Vector("pacman") ++ installCommand,
      policy
    )).toVector
    val installCommands = spec.install.map: packageName =>
      direct(Vector("pacman", "-S", "--needed", "--noconfirm", packageName), policy)

    syncCommand ++ pacmanActionCommandSpecs(spec.actions, policy) ++ installCommands

  private def dnfCommandSpecs(
      spec: PackageSpec.Dnf,
      policy: ExecutionPolicy
  ): Vector[CommandSpec] = dnfActionCommandSpecs(spec.actions, policy) ++
    spec.install.map(packageName => direct(Vector("dnf", "install", "-y", packageName), policy))

  private def zypperCommandSpecs(
      spec: PackageSpec.Zypper,
      policy: ExecutionPolicy
  ): Vector[CommandSpec] = Option
    .when(spec.refresh.contains(true))(direct(
      Vector("zypper", "--non-interactive", "refresh"),
      policy
    ))
    .toVector ++ zypperActionCommandSpecs(spec.actions, policy) ++
    spec.install.map: packageName =>
      direct(Vector("zypper", "--non-interactive", "install", "-y", packageName), policy)

  private def flatpakCommandSpecs(
      spec: PackageSpec.Flatpak,
      policy: ExecutionPolicy
  ): Vector[CommandSpec] =
    val scope = spec.system match
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
  ): Vector[CommandSpec] = spec.install.map: item =>
    direct(
      Vector("snap", "install", item.name) ++
        Option.when(item.classic.contains(true))("--classic").toVector,
      policy
    )

  private def aurCommandSpecs(spec: PackageSpec.Aur): Vector[CommandSpec] =
    val helper = spec.helper.getOrElse("paru")
    spec.install.map(packageName =>
      CommandSpec.direct(
        Vector(helper, "-S", "--needed", "--noconfirm", packageName).map(CommandArgument(_))
      )
    )

  private def cargoCommandSpecs(spec: PackageSpec.Cargo): Vector[CommandSpec] = spec.installer match
    case Some("cargo") | Some("cargo-install") => spec.install.map(packageName =>
        CommandSpec.direct(Vector("cargo", "install", packageName).map(CommandArgument(_)))
      )
    case Some("cargo-binstall") | None => spec.install.map(packageName =>
        CommandSpec.direct(
          Vector("cargo", "binstall", "-y", packageName).map(CommandArgument(_))
        )
      )
    case Some(other) => spec.install.map(packageName =>
        CommandSpec.direct(Vector(other, packageName).map(CommandArgument(_)))
      )

  private def sdkmanCommandSpecs(spec: PackageSpec.Sdkman): Vector[CommandSpec] =
    spec.install.map: item =>
      val install = (Vector("sdk", "install", item.candidate) ++ item.version.toVector)
        .map(shellQuote)
        .mkString(" ")
      CommandSpec.shell(
        command = CommandArgument(
          s"""source "$${SDKMAN_DIR:-$${HOME}/.sdkman}/bin/sdkman-init.sh" && $install"""
        ),
        shell = Vector("bash", "-lc")
      )

  private def aptActionCommandSpecs(
      actions: Vector[PackageAction],
      policy: ExecutionPolicy
  ): Vector[CommandSpec] = actions.map: action =>
    action.action match
      case "update"       => aptGet(Vector("update") ++ action.args, policy)
      case "upgrade"      => aptGet(Vector("upgrade", "-y") ++ action.args, policy)
      case "dist-upgrade" => aptGet(Vector("dist-upgrade", "-y") ++ action.args, policy)
      case other          => aptGet(Vector(other) ++ action.args, policy)

  private def pacmanActionCommandSpecs(
      actions: Vector[PackageAction],
      policy: ExecutionPolicy
  ): Vector[CommandSpec] = actions.map: action =>
    action.action match
      case "sync-upgrade" | "syu" | "upgrade" =>
        direct(Vector("pacman", "-Syu", "--noconfirm") ++ action.args, policy)
      case other => direct(Vector("pacman", other) ++ action.args, policy)

  private def dnfActionCommandSpecs(
      actions: Vector[PackageAction],
      policy: ExecutionPolicy
  ): Vector[CommandSpec] = actions.map: action =>
    action.action match
      case "check-update" =>
        direct(Vector("dnf", "check-update") ++ action.args, policy, allowedExitCodes = Set(0, 100))
      case "upgrade" => direct(Vector("dnf", "upgrade", "-y") ++ action.args, policy)
      case "swap"    => direct(Vector("dnf", "swap", "-y") ++ action.args, policy)
      case "groupupdate" | "group-update" =>
        direct(Vector("dnf", "groupupdate", "-y") ++ action.args, policy)
      case other => direct(Vector("dnf", other) ++ action.args, policy)

  private def zypperActionCommandSpecs(
      actions: Vector[PackageAction],
      policy: ExecutionPolicy
  ): Vector[CommandSpec] = actions.map: action =>
    action.action match
      case "refresh" =>
        direct(Vector("zypper", "--non-interactive", "refresh") ++ action.args, policy)
      case "update" =>
        direct(Vector("zypper", "--non-interactive", "update", "-y") ++ action.args, policy)
      case "dup" =>
        direct(Vector("zypper", "--non-interactive", "dup", "-y") ++ action.args, policy)
      case "dup-from" => direct(
          Vector("zypper", "--non-interactive", "dup", "-y", "--from") ++ action.args,
          policy
        )
      case other => direct(Vector("zypper", "--non-interactive", other) ++ action.args, policy)

  private def aptGet(
      arguments: Vector[String],
      policy: ExecutionPolicy
  ): CommandSpec = direct(
    Vector("apt-get") ++ arguments,
    policy,
    env = VectorMap("DEBIAN_FRONTEND" -> CommandEnvironmentValue("noninteractive"))
  )

  private def direct(
      values: Vector[String],
      policy: ExecutionPolicy,
      env: VectorMap[String, CommandEnvironmentValue] = VectorMap.empty,
      allowedExitCodes: Set[Int] = Set(0)
  ): CommandSpec = CommandSpec.direct(
    argv = values.map(CommandArgument(_)),
    env = env,
    sudo = sudoMode(policy),
    allowedExitCodes = allowedExitCodes
  )

  private def sudoMode(policy: ExecutionPolicy): SudoMode =
    if policy.requireSudo then SudoMode.Required
    else SudoMode.Disabled

  private def dryRunAction(command: CommandSpec): DryRunAction = command.redacted.invocation match
    case RedactedCommandInvocation.Direct(argv) => DryRunAction.Command(
        argv = argv,
        shell = None,
        sudo = command.sudo == SudoMode.Required,
        workingDirectory = command.cwd.map(_.toString)
      )
    case RedactedCommandInvocation.Shell(commandText, shell) => DryRunAction.Command(
        argv = Vector(commandText),
        shell = Some(shell.mkString(" ")),
        sudo = command.sudo == SudoMode.Required,
        workingDirectory = command.cwd.map(_.toString)
      )

  private def shellQuote(value: String): String = s"'${value.replace("'", "'\"'\"'")}'"
