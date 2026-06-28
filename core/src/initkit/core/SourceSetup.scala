package initkit.core

import java.nio.file.{Path, Paths}

import initkit.config.*
import initkit.host.HostFacts

final case class SourceSetupPlan(
    operations: Vector[SourceSetupOperation],
    skippedSections: Vector[SkippedSourceSection],
    aptUpdateBeforeInstall: Boolean
):
  def dryRunData(summary: PlanOperationSummary): DryRunOperationData =
    DryRunOperationData(
      operation = summary,
      actions = operations.map(_.dryRunAction) ++ updateBeforeInstallMessage
    )

  private def updateBeforeInstallMessage: Vector[DryRunAction] =
    Option
      .when(aptUpdateBeforeInstall)(
        DryRunAction.Message("apt package installs will run apt-get update before installing packages")
      )
      .toVector

enum SourceSetupOperation:
  case RunCommand(label: String, command: CommandSpec)
  case WriteFile(label: String, path: Path, content: String, mode: Option[String], sudo: Boolean)

  def dryRunAction: DryRunAction =
    this match
      case RunCommand(_, command) =>
        val redacted = command.redacted
        redacted.invocation match
          case RedactedCommandInvocation.Direct(argv) =>
            DryRunAction.Command(
              argv = argv,
              shell = None,
              sudo = redacted.sudo == SudoMode.Required,
              workingDirectory = redacted.cwd.map(_.toString)
            )
          case RedactedCommandInvocation.Shell(command, shell) =>
            DryRunAction.Command(
              argv = Vector(command),
              shell = Some(shell.mkString(" ")),
              sudo = redacted.sudo == SudoMode.Required,
              workingDirectory = redacted.cwd.map(_.toString)
            )
      case WriteFile(_, path, _, mode, _) =>
        DryRunAction.FileWrite(path.toString, mode, "package source configuration")

final case class SkippedSourceSection(
    section: String,
    reason: String
)

object SourceSetupGenerator:
  def generate(
      sources: Option[Sources],
      hostFacts: HostFacts,
      policy: ExecutionPolicy
  ): SourceSetupPlan =
    sources match
      case Some(value) =>
        val apt = generateApt(value.apt, hostFacts, policy)
        val dnf = generateDnf(value.dnf, hostFacts, policy)
        val zypper = generateZypper(value.zypper, hostFacts, policy)
        val flatpak = generateFlatpak(value.flatpak, hostFacts, policy)

        SourceSetupPlan(
          operations = apt.operations ++ dnf.operations ++ zypper.operations ++ flatpak.operations,
          skippedSections = apt.skippedSections ++ dnf.skippedSections ++ zypper.skippedSections ++ flatpak.skippedSections,
          aptUpdateBeforeInstall = apt.aptUpdateBeforeInstall
        )
      case None =>
        SourceSetupPlan(Vector.empty, Vector.empty, aptUpdateBeforeInstall = false)

  private def generateApt(
      sources: Option[AptSources],
      hostFacts: HostFacts,
      policy: ExecutionPolicy
  ): SourceSetupPlan =
    generateSystemPackageManagerSection(
      section = "apt",
      expected = SystemPackageManager.Apt,
      sources = sources,
      hostFacts = hostFacts,
      operations = aptOperations(_, policy),
      aptUpdateBeforeInstall = _.updateBeforeInstall.contains(true)
    )

  private def aptOperations(sources: AptSources, policy: ExecutionPolicy): Vector[SourceSetupOperation] =
    sources.repositories.flatMap: repository =>
      repository.keyUrl.toVector.map(aptKeyOperation(repository, _, policy)) :+
        SourceSetupOperation.WriteFile(
          label = s"Write apt source ${repository.name}",
          path = Paths.get(s"/etc/apt/sources.list.d/${repository.name}.list"),
          content = s"${repository.source}\n",
          mode = Some("0644"),
          sudo = policy.requireSudo
        )

  private def aptKeyOperation(repository: AptRepository, keyUrl: String, policy: ExecutionPolicy): SourceSetupOperation =
    SourceSetupOperation.RunCommand(
      label = s"Install apt GPG key ${repository.name}",
      command = CommandSpec.shell(
        command = CommandArgument(
          s"install -d -m 0755 /etc/apt/keyrings && curl -fsSL ${shellQuote(keyUrl)} | gpg --dearmor -o ${shellQuote(s"/etc/apt/keyrings/${repository.name}.gpg")}"
        ),
        sudo = sudoMode(policy)
      )
    )

  private def generateDnf(
      sources: Option[DnfSources],
      hostFacts: HostFacts,
      policy: ExecutionPolicy
  ): SourceSetupPlan =
    generateSystemPackageManagerSection(
      section = "dnf",
      expected = SystemPackageManager.Dnf,
      sources = sources,
      hostFacts = hostFacts,
      operations = dnfOperations(_, policy),
      aptUpdateBeforeInstall = _ => false
    )

  private def dnfOperations(sources: DnfSources, policy: ExecutionPolicy): Vector[SourceSetupOperation] =
    sources.repositories.map: repository =>
      SourceSetupOperation.WriteFile(
        label = s"Write dnf source ${repository.name}",
        path = Paths.get(s"/etc/yum.repos.d/${repository.name}.repo"),
        content = dnfRepositoryFile(repository),
        mode = Some("0644"),
        sudo = policy.requireSudo
      )

  private def dnfRepositoryFile(repository: DnfRepository): String =
    val lines = Vector(
      s"[${repository.name}]",
      s"name=${repository.description.getOrElse(repository.name)}",
      s"baseurl=${repository.baseUrl}",
      "enabled=1",
      s"gpgcheck=${if repository.gpgKey.isDefined then "1" else "0"}"
    ) ++ repository.gpgKey.map(key => s"gpgkey=$key").toVector

    lines.mkString("", "\n", "\n")

  private def generateZypper(
      sources: Option[ZypperSources],
      hostFacts: HostFacts,
      policy: ExecutionPolicy
  ): SourceSetupPlan =
    generateSystemPackageManagerSection(
      section = "zypper",
      expected = SystemPackageManager.Zypper,
      sources = sources,
      hostFacts = hostFacts,
      operations = zypperOperations(_, policy),
      aptUpdateBeforeInstall = _ => false
    )

  private def zypperOperations(sources: ZypperSources, policy: ExecutionPolicy): Vector[SourceSetupOperation] =
    sources.repositories.map: repository =>
      val refresh = Option.when(repository.autoRefresh.contains(true))(CommandArgument("--refresh")).toVector
      SourceSetupOperation.RunCommand(
        label = s"Add zypper source ${repository.name}",
        command = CommandSpec.direct(
          argv = Vector(CommandArgument("zypper"), CommandArgument("addrepo")) ++
            refresh ++
            Vector(CommandArgument(repository.url), CommandArgument(repository.name)),
          sudo = sudoMode(policy)
        )
      )

  private def generateFlatpak(
      sources: Option[FlatpakSources],
      hostFacts: HostFacts,
      policy: ExecutionPolicy
  ): SourceSetupPlan =
    sources match
      case Some(value) if hostFacts.commandExists("flatpak") =>
        SourceSetupPlan(flatpakOperations(value, policy), Vector.empty, aptUpdateBeforeInstall = false)
      case Some(_) =>
        SourceSetupPlan(
          Vector.empty,
          Vector(SkippedSourceSection("flatpak", "flatpak command is not available on the active host")),
          aptUpdateBeforeInstall = false
        )
      case None =>
        SourceSetupPlan(Vector.empty, Vector.empty, aptUpdateBeforeInstall = false)

  private def flatpakOperations(sources: FlatpakSources, policy: ExecutionPolicy): Vector[SourceSetupOperation] =
    sources.remotes.map: remote =>
      SourceSetupOperation.RunCommand(
        label = s"Add flatpak remote ${remote.name}",
        command = CommandSpec.direct(
          argv = Vector(CommandArgument("flatpak"), CommandArgument("remote-add")) ++
            Option.when(remote.ifMissing.getOrElse(true))(CommandArgument("--if-not-exists")).toVector ++
            Vector(CommandArgument(remote.name), CommandArgument(remote.url)),
          sudo = sudoMode(policy)
        )
      )

  private def generateSystemPackageManagerSection[A](
      section: String,
      expected: SystemPackageManager,
      sources: Option[A],
      hostFacts: HostFacts,
      operations: A => Vector[SourceSetupOperation],
      aptUpdateBeforeInstall: A => Boolean
  ): SourceSetupPlan =
    sources match
      case Some(value) if activeSystemPackageManager(hostFacts).contains(expected) =>
        SourceSetupPlan(operations(value), Vector.empty, aptUpdateBeforeInstall(value))
      case Some(_) =>
        SourceSetupPlan(
          Vector.empty,
          Vector(SkippedSourceSection(section, s"$section sources do not match the active host package manager")),
          aptUpdateBeforeInstall = false
        )
      case None =>
        SourceSetupPlan(Vector.empty, Vector.empty, aptUpdateBeforeInstall = false)

  private def activeSystemPackageManager(hostFacts: HostFacts): Option[SystemPackageManager] =
    if hostFacts.commandExists("apt-get") then Some(SystemPackageManager.Apt)
    else if hostFacts.commandExists("dnf") then Some(SystemPackageManager.Dnf)
    else if hostFacts.commandExists("zypper") then Some(SystemPackageManager.Zypper)
    else
      hostFacts.os.distribution match
        case Some("ubuntu" | "debian" | "linuxmint" | "pop") =>
          Some(SystemPackageManager.Apt)
        case Some("fedora" | "rhel" | "centos" | "rocky" | "almalinux") =>
          Some(SystemPackageManager.Dnf)
        case Some("opensuse" | "opensuse-leap" | "opensuse-tumbleweed" | "sles") =>
          Some(SystemPackageManager.Zypper)
        case _ =>
          None

  private def sudoMode(policy: ExecutionPolicy): SudoMode =
    if policy.requireSudo then SudoMode.Required
    else SudoMode.Disabled

  private def shellQuote(value: String): String =
    s"'${value.replace("'", "'\"'\"'")}'"

private enum SystemPackageManager:
  case Apt, Dnf, Zypper
