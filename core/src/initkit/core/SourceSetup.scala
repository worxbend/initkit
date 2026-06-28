package initkit.core

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

import initkit.config.*
import initkit.host.HostFacts

final case class SourceSetupPlan(
    operations: Vector[SourceSetupOperation],
    skippedSections: Vector[SkippedSourceSection],
    aptUpdateBeforeInstall: Boolean
):

  def dryRunData(summary: PlanOperationSummary): DryRunOperationData = DryRunOperationData(
    operation = summary,
    actions = operations.map(_.dryRunAction) ++ updateBeforeInstallMessage
  )

  private def updateBeforeInstallMessage: Vector[DryRunAction] = Option
    .when(aptUpdateBeforeInstall)(
      DryRunAction.Message(
        "apt package installs will run apt-get update before installing packages"
      )
    )
    .toVector

enum SourceSetupOperation:
  case RunCommand(label: String, command: CommandSpec)
  case WriteFile(label: String, path: Path, content: String, mode: Option[String], sudo: Boolean)

  def dryRunAction: DryRunAction = this match
    case RunCommand(_, command) =>
      val redacted = command.redacted
      redacted.invocation match
        case RedactedCommandInvocation.Direct(argv) => DryRunAction.Command(
            argv = argv,
            shell = None,
            sudo = redacted.sudo == SudoMode.Required,
            workingDirectory = redacted.cwd.map(_.toString)
          )
        case RedactedCommandInvocation.Shell(command, shell) => DryRunAction.Command(
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

final class SourceSetupExecutor(
    commandExecutor: CommandExecutor,
    files: SourceSetupFiles = SourceSetupFiles.Jvm
):

  def execute(
      plan: SourceSetupPlan,
      policy: ExecutionPolicy,
      summary: PlanOperationSummary = SourceSetupExecutor.Summary
  ): PlanOperationOutcome = policy.mode match
    case ExecutionRunMode.DryRun => PlanOperationOutcome.DryRun(plan.dryRunData(summary))
    case ExecutionRunMode.Apply  => applyOperations(plan.operations, summary)

  private def applyOperations(
      operations: Vector[SourceSetupOperation],
      summary: PlanOperationSummary
  ): PlanOperationOutcome =
    val details = Vector.newBuilder[String]
    var failure = Option.empty[SourceSetupFailure]
    var index   = 0

    while index < operations.size && failure.isEmpty do
      runOperation(operations(index)) match
        case Right(detail) => details += detail
        case Left(value)   => failure = Some(value)
      index = index + 1

    failure match
      case Some(value) =>
        PlanOperationOutcome.Failed(PlanFailure(summary, value.message, value.exitCode))
      case None =>
        val result = details.result()
        PlanOperationOutcome.Completed(
          if result.isEmpty then Vector("no source setup operations generated")
          else result
        )

  private def runOperation(operation: SourceSetupOperation): Either[SourceSetupFailure, String] =
    operation match
      case SourceSetupOperation.RunCommand(label, command) =>
        val result = commandExecutor.run(command)
        if result.succeeded then Right(s"ran source setup command '$label'")
        else Left(SourceSetupFailure.Command(label, result))
      case SourceSetupOperation.WriteFile(label, path, content, mode, sudo) =>
        files.writeFile(path, content, mode, sudo) match
          case Right(_)    => Right(writeDetail(label, path, mode, sudo))
          case Left(error) => Left(SourceSetupFailure.File(label, path, sudo, error.message))

  private def writeDetail(label: String, path: Path, mode: Option[String], sudo: Boolean): String =
    val modeText = mode.map(value => s" mode=$value").getOrElse("")
    val sudoText = if sudo then " (sudo requested)" else ""
    s"wrote source setup file '$label' to $path$modeText$sudoText"

private enum SourceSetupFailure:
  case Command(label: String, result: CommandResult)
  case File(label: String, path: Path, sudo: Boolean, detail: String)

  def message: String = this match
    case Command(label, result) =>
      s"source setup command '$label' failed: ${CommandsExecutor.describe(result.spec)} " +
        s"(${CommandsExecutor.describeTermination(result.termination)})"
    case File(label, path, sudo, detail) =>
      val sudoText = if sudo then " (sudo requested)" else ""
      s"source setup file write '$label' failed for $path$sudoText: $detail"

  def exitCode: Option[Int] = this match
    case Command(_, result) => result.exitCode
    case File(_, _, _, _)   => None

trait SourceSetupFiles:

  def writeFile(
      path: Path,
      content: String,
      mode: Option[String],
      sudo: Boolean
  ): Either[SourceSetupFileError, Unit]

final case class SourceSetupFileError(message: String)

object SourceSetupFiles:

  val Jvm: SourceSetupFiles = new SourceSetupFiles:
    override def writeFile(
        path: Path,
        content: String,
        mode: Option[String],
        sudo: Boolean
    ): Either[SourceSetupFileError, Unit] = mode match
      case Some(value) => BinaryDownloadsExecutor.permissionsFromMode(value) match
          case Left(error)        => Left(SourceSetupFileError(error))
          case Right(permissions) => safely:
              writeString(path, content)
              Files.setPosixFilePermissions(path.toAbsolutePath.normalize(), permissions.asJava)
              ()
      case None => safely(writeString(path, content))

    private def writeString(path: Path, content: String): Unit =
      val absolutePath = path.toAbsolutePath.normalize()
      Option(absolutePath.getParent).foreach(Files.createDirectories(_))
      Files.writeString(absolutePath, content, StandardCharsets.UTF_8)
      ()

    private def safely[A](body: => A): Either[SourceSetupFileError, A] =
      try Right(body)
      catch
        case error: IOException                   => Left(SourceSetupFileError(safeMessage(error)))
        case error: SecurityException             => Left(SourceSetupFileError(safeMessage(error)))
        case error: UnsupportedOperationException => Left(SourceSetupFileError(safeMessage(error)))

    private def safeMessage(error: Throwable): String =
      Option(error.getMessage).getOrElse(error.getClass.getName)

object SourceSetupExecutor:

  val Summary: PlanOperationSummary = PlanOperationSummary(
    index = -1,
    name = "source-setup",
    kind = "sources",
    description = Some("Configure package sources")
  )

object SourceSetupGenerator:

  def generate(
      sources: Option[Sources],
      hostFacts: HostFacts,
      policy: ExecutionPolicy
  ): SourceSetupPlan = sources match
    case Some(value) =>
      val apt     = generateApt(value.apt, hostFacts, policy)
      val dnf     = generateDnf(value.dnf, hostFacts, policy)
      val zypper  = generateZypper(value.zypper, hostFacts, policy)
      val flatpak = generateFlatpak(value.flatpak, hostFacts, policy)

      SourceSetupPlan(
        operations = apt.operations ++ dnf.operations ++ zypper.operations ++ flatpak.operations,
        skippedSections = apt.skippedSections ++ dnf.skippedSections ++ zypper.skippedSections ++
          flatpak.skippedSections,
        aptUpdateBeforeInstall = apt.aptUpdateBeforeInstall
      )
    case None => SourceSetupPlan(Vector.empty, Vector.empty, aptUpdateBeforeInstall = false)

  private def generateApt(
      sources: Option[AptSources],
      hostFacts: HostFacts,
      policy: ExecutionPolicy
  ): SourceSetupPlan = generateSystemPackageManagerSection(
    section = "apt",
    expected = SystemPackageManager.Apt,
    sources = sources,
    hostFacts = hostFacts,
    operations = aptOperations(_, policy),
    aptUpdateBeforeInstall = _.updateBeforeInstall.contains(true)
  )

  private def aptOperations(
      sources: AptSources,
      policy: ExecutionPolicy
  ): Vector[SourceSetupOperation] = sources.repositories.flatMap: repository =>
    repository.keyUrl.toVector.map(aptKeyOperation(repository, _, policy)) :+
      SourceSetupOperation.WriteFile(
        label = s"Write apt source ${repository.name}",
        path = Paths.get(s"/etc/apt/sources.list.d/${repository.name}.list"),
        content = s"${repository.source}\n",
        mode = Some("0644"),
        sudo = policy.requireSudo
      )

  private def aptKeyOperation(
      repository: AptRepository,
      keyUrl: String,
      policy: ExecutionPolicy
  ): SourceSetupOperation = SourceSetupOperation.RunCommand(
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
  ): SourceSetupPlan = generateSystemPackageManagerSection(
    section = "dnf",
    expected = SystemPackageManager.Dnf,
    sources = sources,
    hostFacts = hostFacts,
    operations = dnfOperations(_, policy),
    aptUpdateBeforeInstall = _ => false
  )

  private def dnfOperations(
      sources: DnfSources,
      policy: ExecutionPolicy
  ): Vector[SourceSetupOperation] =
    val keys     = sources.keyImports.map(rpmKeyImportOperation(_, policy))
    val releases = sources.releasePackages.map(dnfReleasePackageOperation(_, policy))
    val repos    = sources.repositories.map: repository =>
      SourceSetupOperation.WriteFile(
        label = s"Write dnf source ${repository.name}",
        path = Paths.get(s"/etc/yum.repos.d/${repository.name}.repo"),
        content = dnfRepositoryFile(repository),
        mode = Some("0644"),
        sudo = policy.requireSudo
      )
    val commands = sources.commands.map(sourceCommandOperation(_, policy))
    keys ++ releases ++ repos ++ commands

  private def rpmKeyImportOperation(
      key: GpgKeyImport,
      policy: ExecutionPolicy
  ): SourceSetupOperation = SourceSetupOperation.RunCommand(
    label = s"Import RPM GPG key ${key.name}",
    command = CommandSpec.direct(
      argv = Vector("rpm", "--import", key.url).map(CommandArgument(_)),
      sudo = sudoMode(policy)
    )
  )

  private def dnfReleasePackageOperation(
      releasePackage: ReleasePackage,
      policy: ExecutionPolicy
  ): SourceSetupOperation = SourceSetupOperation.RunCommand(
    label = s"Install dnf release package ${releasePackage.name}",
    command = CommandSpec.direct(
      argv = Vector("dnf", "install", "-y", releasePackage.url).map(CommandArgument(_)),
      sudo = sudoMode(policy)
    )
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
  ): SourceSetupPlan = generateSystemPackageManagerSection(
    section = "zypper",
    expected = SystemPackageManager.Zypper,
    sources = sources,
    hostFacts = hostFacts,
    operations = zypperOperations(_, policy),
    aptUpdateBeforeInstall = _ => false
  )

  private def zypperOperations(
      sources: ZypperSources,
      policy: ExecutionPolicy
  ): Vector[SourceSetupOperation] =
    val keys  = sources.keyImports.map(rpmKeyImportOperation(_, policy))
    val repos = sources.repositories.map: repository =>
      val refresh =
        Option.when(repository.autoRefresh.contains(true))(CommandArgument("--refresh")).toVector
      SourceSetupOperation.RunCommand(
        label = s"Add zypper source ${repository.name}",
        command = CommandSpec.direct(
          argv = Vector(CommandArgument("zypper"), CommandArgument("addrepo")) ++
            refresh ++
            Vector(CommandArgument(repository.url), CommandArgument(repository.name)),
          sudo = sudoMode(policy)
        )
      )
    val commands = sources.commands.map(sourceCommandOperation(_, policy))
    keys ++ repos ++ commands

  private def sourceCommandOperation(
      command: SourceCommand,
      policy: ExecutionPolicy
  ): SourceSetupOperation = SourceSetupOperation.RunCommand(
    label = command.name,
    command = CommandSpec.shell(
      CommandArgument(command.run),
      sudo =
        if command.sudo.getOrElse(policy.requireSudo) then SudoMode.Required else SudoMode.Disabled
    )
  )

  private def generateFlatpak(
      sources: Option[FlatpakSources],
      hostFacts: HostFacts,
      policy: ExecutionPolicy
  ): SourceSetupPlan = sources match
    case Some(value) if hostFacts.commandExists("flatpak") =>
      SourceSetupPlan(
        flatpakOperations(value, policy),
        Vector.empty,
        aptUpdateBeforeInstall = false
      )
    case Some(_) => SourceSetupPlan(
        Vector.empty,
        Vector(SkippedSourceSection(
          "flatpak",
          "flatpak command is not available on the active host"
        )),
        aptUpdateBeforeInstall = false
      )
    case None => SourceSetupPlan(Vector.empty, Vector.empty, aptUpdateBeforeInstall = false)

  private def flatpakOperations(
      sources: FlatpakSources,
      policy: ExecutionPolicy
  ): Vector[SourceSetupOperation] = sources.remotes.map: remote =>
    SourceSetupOperation.RunCommand(
      label = s"Add flatpak remote ${remote.name}",
      command = CommandSpec.direct(
        argv = Vector(CommandArgument("flatpak"), CommandArgument("remote-add")) ++
          Option.when(
            remote.ifMissing.getOrElse(true)
          )(CommandArgument("--if-not-exists")).toVector ++
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
  ): SourceSetupPlan = sources match
    case Some(value) if activeSystemPackageManager(hostFacts).contains(expected) =>
      SourceSetupPlan(operations(value), Vector.empty, aptUpdateBeforeInstall(value))
    case Some(_) => SourceSetupPlan(
        Vector.empty,
        Vector(SkippedSourceSection(
          section,
          s"$section sources do not match the active host package manager"
        )),
        aptUpdateBeforeInstall = false
      )
    case None => SourceSetupPlan(Vector.empty, Vector.empty, aptUpdateBeforeInstall = false)

  private def activeSystemPackageManager(hostFacts: HostFacts): Option[SystemPackageManager] =
    if hostFacts.commandExists("apt-get") then Some(SystemPackageManager.Apt)
    else if hostFacts.commandExists("dnf") then Some(SystemPackageManager.Dnf)
    else if hostFacts.commandExists("zypper") then Some(SystemPackageManager.Zypper)
    else
      hostFacts.os.distribution match
        case Some("ubuntu" | "debian" | "linuxmint" | "pop") => Some(SystemPackageManager.Apt)
        case Some("fedora" | "rhel" | "centos" | "rocky" | "almalinux") =>
          Some(SystemPackageManager.Dnf)
        case Some("opensuse" | "opensuse-leap" | "opensuse-tumbleweed" | "sles") =>
          Some(SystemPackageManager.Zypper)
        case _ => None

  private def sudoMode(policy: ExecutionPolicy): SudoMode =
    if policy.requireSudo then SudoMode.Required
    else SudoMode.Disabled

  private def shellQuote(value: String): String = s"'${value.replace("'", "'\"'\"'")}'"

private enum SystemPackageManager:
  case Apt, Dnf, Zypper
