package initkit.core

import java.nio.file.Paths

import initkit.config.*
import initkit.host.HostFacts
import utest.*

object SourceSetupGeneratorTests extends TestSuite:
  val tests: Tests = Tests:
    test("generates apt source setup and apt update marker for apt hosts"):
      val plan = SourceSetupGenerator.generate(Some(allSources), aptHost, sudoPolicy)

      assert(plan.aptUpdateBeforeInstall)
      assert(plan.skippedSections.map(_.section) == Vector("dnf", "zypper"))
      assert(plan.operations.size == 3)

      val aptKey = plan.operations.collectFirst { case operation @ SourceSetupOperation.RunCommand(_, _) => operation }.get
      val aptFile = plan.operations.collectFirst { case operation @ SourceSetupOperation.WriteFile(_, _, _, _, _) => operation }.get
      val dryRun = plan.dryRunData(sourceSetupSummary)

      assert(aptKey.command.redacted.sudo == SudoMode.Required)
      assert(aptFile.path == Paths.get("/etc/apt/sources.list.d/docker.list"))
      assert(aptFile.content == "deb [arch=amd64] https://download.docker.com/linux/ubuntu noble stable\n")
      assert(dryRun.actions.exists(_ == DryRunAction.FileWrite("/etc/apt/sources.list.d/docker.list", Some("0644"), "package source configuration")))
      assert(dryRun.actions.exists(_ == DryRunAction.Message("apt package installs will run apt-get update before installing packages")))

    test("skips apt and zypper source sections for dnf hosts"):
      val plan = SourceSetupGenerator.generate(Some(allSources), dnfHost, sudoPolicy)

      assert(!plan.aptUpdateBeforeInstall)
      assert(plan.skippedSections.map(_.section) == Vector("apt", "zypper", "flatpak"))
      assert(plan.operations.size == 1)

      val dnfFile = plan.operations.collectFirst { case operation @ SourceSetupOperation.WriteFile(_, _, _, _, _) => operation }.get

      assert(dnfFile.path == Paths.get("/etc/yum.repos.d/vscode.repo"))
      assert(dnfFile.content.contains("[vscode]"))
      assert(dnfFile.content.contains("gpgkey=https://packages.microsoft.com/keys/microsoft.asc"))

    test("generates zypper addrepo commands only for zypper hosts"):
      val plan = SourceSetupGenerator.generate(Some(allSources.copy(flatpak = None)), zypperHost, sudoPolicy)

      assert(plan.skippedSections.map(_.section) == Vector("apt", "dnf"))
      assert(plan.operations.size == 1)

      val command = commandArgv(plan.operations.head)

      assert(command == Vector("zypper", "addrepo", "--refresh", "https://download.docker.com/linux/sles/docker-ce.repo", "docker"))

    test("flatpak remote setup uses if-not-exists when requested"):
      val plan = SourceSetupGenerator.generate(Some(Sources(None, None, None, allSources.flatpak, raw = RawYaml.NullValue)), aptHost, sudoPolicy)

      assert(plan.skippedSections.isEmpty)
      assert(plan.operations.size == 1)
      assert(commandArgv(plan.operations.head) == Vector("flatpak", "remote-add", "--if-not-exists", "flathub", "https://flathub.org/repo/flathub.flatpakrepo"))

    test("dry-run data exposes source setup commands"):
      val plan = SourceSetupGenerator.generate(Some(allSources.copy(apt = None, dnf = None, zypper = None)), aptHost, sudoPolicy)
      val actions = plan.dryRunData(sourceSetupSummary).actions

      assert(actions == Vector(DryRunAction.Command(Vector("flatpak", "remote-add", "--if-not-exists", "flathub", "https://flathub.org/repo/flathub.flatpakrepo"), None, sudo = true, None)))

  private val sourceSetupSummary: PlanOperationSummary =
    PlanOperationSummary(index = -1, name = "source-setup", kind = "sources", description = Some("Configure package sources"))

  private val sudoPolicy: ExecutionPolicy =
    ExecutionPolicy(
      mode = ExecutionRunMode.DryRun,
      continueOnError = false,
      requireSudo = true,
      reboot = RebootExecutionPolicy(allowed = false, prompt = true)
    )

  private val aptHost: HostFacts =
    HostFacts.fake(distribution = Some("ubuntu"), commands = Set("apt-get", "flatpak"))

  private val dnfHost: HostFacts =
    HostFacts.fake(distribution = Some("fedora"), commands = Set("dnf"))

  private val zypperHost: HostFacts =
    HostFacts.fake(distribution = Some("opensuse-leap"), commands = Set("zypper"))

  private val allSources: Sources =
    Sources(
      apt = Some(
        AptSources(
          repositories = Vector(
            AptRepository(
              name = "docker",
              keyUrl = Some("https://download.docker.com/linux/ubuntu/gpg"),
              source = "deb [arch=amd64] https://download.docker.com/linux/ubuntu noble stable"
            )
          ),
          updateBeforeInstall = Some(true)
        )
      ),
      dnf = Some(
        DnfSources(
          repositories = Vector(
            DnfRepository(
              name = "vscode",
              description = Some("Visual Studio Code"),
              baseUrl = "https://packages.microsoft.com/yumrepos/vscode",
              gpgKey = Some("https://packages.microsoft.com/keys/microsoft.asc")
            )
          )
        )
      ),
      zypper = Some(
        ZypperSources(
          repositories = Vector(
            ZypperRepository(
              name = "docker",
              url = "https://download.docker.com/linux/sles/docker-ce.repo",
              autoRefresh = Some(true)
            )
          )
        )
      ),
      flatpak = Some(
        FlatpakSources(
          remotes = Vector(
            FlatpakRemote(
              name = "flathub",
              url = "https://flathub.org/repo/flathub.flatpakrepo",
              ifMissing = Some(true)
            )
          )
        )
      ),
      raw = RawYaml.NullValue
    )

  private def commandArgv(operation: SourceSetupOperation): Vector[String] =
    operation match
      case SourceSetupOperation.RunCommand(_, command) =>
        command.redacted.invocation match
          case RedactedCommandInvocation.Direct(argv) => argv
          case RedactedCommandInvocation.Shell(command, shell) =>
            shell :+ command
      case other =>
        throw new java.lang.AssertionError(s"expected command operation, got $other")
