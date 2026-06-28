package initkit.core

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.Duration

import initkit.config.*
import utest.*

object DotfilesExecutorTests extends TestSuite:
  val tests: Tests = Tests:
    test("command generation includes clone update checkout preview and apply"):
      val operation = dotfilesOperation("apply-dotfiles")
      val commands = DotfilesExecutor.commandSpecs(operation)

      assert(argvs(commands) == Vector(
        Vector("git", "clone", "https://github.com/w0rxbend/system-bootstrap.git", "${dotfilesDir}"),
        Vector("git", "-C", "${dotfilesDir}", "pull", "--ff-only"),
        Vector("git", "-C", "${dotfilesDir}", "checkout", "main"),
        Vector("${binDir}/dotbot-go", "-d", "${dotfilesDir}", "-c", "${dotfilesConfig}", "--dry-run"),
        Vector("${binDir}/dotbot-go", "-d", "${dotfilesDir}", "-c", "${dotfilesConfig}")
      ))
      assert(commands.forall(_.sudo == SudoMode.Disabled))
      assert(DotfilesExecutor.setupCommandSpecs(operation.spec.copy(repository = operation.spec.repository.copy(update = Some(false))), includeClone = true).map(argv) == Vector(
        Vector("git", "clone", "https://github.com/w0rxbend/system-bootstrap.git", "${dotfilesDir}"),
        Vector("git", "-C", "${dotfilesDir}", "checkout", "main")
      ))

    test("dry-run shows clone update checkout preview and apply commands without mutating"):
      withTempDir: tempDir =>
        val operation = tempOperation(tempDir, createConfig = false)
        val executor = FakeCommandExecutor(Vector.empty)
        val installer = new PackageManagerInstallers(executor)

        val outcome = installer.install(PlanOperation.DotfilesApply(operation), dryRunPolicy)
        val dryRun = outcome match
          case PlanOperationOutcome.DryRun(data) => data
          case other                            => fail(s"expected dry-run outcome, got $other")

        assert(executor.calls.isEmpty)
        assert(!Files.exists(operation.spec.repository.destinationPath))
        assert(dryRun.actions == Vector(
          DryRunAction.Command(Vector("git", "clone", operation.spec.repository.url, operation.spec.repository.destination), None, false, None),
          DryRunAction.Command(Vector("git", "-C", operation.spec.repository.destination, "pull", "--ff-only"), None, false, None),
          DryRunAction.Command(Vector("git", "-C", operation.spec.repository.destination, "checkout", "main"), None, false, None),
          DryRunAction.Command(Vector("/tmp/dotbot-go", "-d", operation.spec.repository.destination, "-c", operation.spec.config.path, "--dry-run"), None, false, None),
          DryRunAction.Command(Vector("/tmp/dotbot-go", "-d", operation.spec.repository.destination, "-c", operation.spec.config.path), None, false, None),
          DryRunAction.Message(s"verify dotfiles config exists at ${operation.spec.config.path} after checkout")
        ))

    test("apply clones missing repo then updates checkouts verifies config and previews before apply"):
      withTempDir: tempDir =>
        val operation = tempOperation(tempDir, createConfig = true)
        val commands = DotfilesExecutor.commandSpecs(operation)
        val executor = FakeCommandExecutor(
          commands.map(command => FakeCommandResponse(command, CommandResultData.exited(0, duration = Duration.Zero)))
        )
        val installer = new PackageManagerInstallers(executor)

        val outcome = installer.install(PlanOperation.DotfilesApply(operation), applyPolicy)

        assert(outcome == PlanOperationOutcome.Completed(Vector("ran 3 dotfiles repository commands; ran 2 dotfiles apply commands")))
        assert(executor.calls == commands)

    test("missing config fails clearly after checkout before preview and apply"):
      withTempDir: tempDir =>
        val operation = tempOperation(tempDir, createConfig = false)
        val setupCommands = DotfilesExecutor.setupCommandSpecs(operation.spec, includeClone = true)
        val executor = FakeCommandExecutor(
          setupCommands.map(command => FakeCommandResponse(command, CommandResultData.exited(0, duration = Duration.Zero)))
        )
        val installer = new PackageManagerInstallers(executor)

        val outcome = installer.install(PlanOperation.DotfilesApply(operation), applyPolicy)
        val failure = outcome match
          case PlanOperationOutcome.Failed(value) => value
          case other                              => fail(s"expected failed outcome, got $other")

        assert(executor.calls == setupCommands)
        assert(failure.exitCode.isEmpty)
        assert(failure.message.contains("dotfiles config missing after checkout"))
        assert(failure.message.contains(operation.spec.config.path))
        assert(failure.message.contains("https://example.test/dotbot.yaml"))

  extension (repository: GitRepository)
    private def destinationPath: Path =
      Path.of(repository.destination)

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

  private def dotfilesOperation(name: String): InstallerPlanOperation[InstallerSpec.DotfilesApply] =
    val (entry, index) = manifest.spec.plan.zipWithIndex
      .find((entry, _) => entry.name.contains(name))
      .getOrElse(fail(s"plan entry '$name' not found"))

    PlanOperation.decode(index, entry) match
      case Right(PlanOperation.DotfilesApply(operation)) => operation
      case Right(other)                                  => fail(s"plan entry '$name' is not a dotfiles operation: $other")
      case Left(errors)                                  => fail(errors.map(_.message).mkString("; "))

  private def tempOperation(tempDir: Path, createConfig: Boolean): InstallerPlanOperation[InstallerSpec.DotfilesApply] =
    val destination = tempDir.resolve("repo")
    val configPath = tempDir.resolve("dotbot").resolve("install.conf.yaml")
    if createConfig then
      Files.createDirectories(configPath.getParent)
      Files.writeString(configPath, "tasks: []\n", StandardCharsets.UTF_8)

    dotfilesOperation("apply-dotfiles").copy(
      spec = InstallerSpec.DotfilesApply(
        tool = ToolInvocation("/tmp/dotbot-go", Vector("-d", destination.toString, "-c", configPath.toString)),
        repository = GitRepository(
          url = "https://github.com/example/dotfiles.git",
          ref = Some("main"),
          destination = destination.toString,
          update = Some(true)
        ),
        config = DotfilesConfig(configPath.toString, Some("https://example.test/dotbot.yaml")),
        preview = Some(PreviewInvocation(enabled = Some(true), args = Vector("--dry-run")))
      )
    )

  private def argvs(commands: Vector[CommandSpec]): Vector[Vector[String]] =
    commands.map(argv)

  private def argv(command: CommandSpec): Vector[String] =
    command.invocation match
      case CommandInvocation.Direct(argv) => argv.map(_.value)
      case CommandInvocation.Shell(_, _)  => fail("expected direct command")

  private def withTempDir[A](test: Path => A): A =
    val tempDir = Files.createTempDirectory("initkit-dotfiles-test-")
    try test(tempDir)
    finally deleteRecursively(tempDir)

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      val stream = Files.walk(path)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists)
      finally stream.close()

  private def exampleConfigPath: Path =
    Iterator
      .iterate(Path.of("").toAbsolutePath.normalize())(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("config.example.yaml"))
      .find(Files.isRegularFile(_))
      .getOrElse(throw new java.lang.AssertionError("config.example.yaml fixture not found"))

  private def fail(message: String): Nothing =
    throw new java.lang.AssertionError(message)
