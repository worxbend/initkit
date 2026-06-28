package initkit.core

import java.nio.file.{Files, Path}
import java.time.{Clock, Instant, ZoneOffset}
import scala.collection.immutable.VectorMap
import scala.jdk.StreamConverters.*

import initkit.config.Manifest
import initkit.host.HostFacts
import utest.*

object PublicExampleRegressionTests extends TestSuite:

  val tests: Tests = Tests:
    test("ubuntu example selects matching entries and previews apt sources plus binary symlink"):
      withDryRun(
        "ubuntu.yaml",
        HostFacts.fake(
          distribution = Some("ubuntu"),
          version = Some("24.04"),
          codename = Some("noble"),
          commands = Set("apt-get", "flatpak")
        )
      ): result =>
        assert(result.selectedNames.contains("ubuntu-base-cli"))
        assert(result.actions.exists(fileWrite("/etc/apt/sources.list.d/docker.list")))
        assert(result.actions.exists(commandContaining(Vector(
          "ln",
          "-sfn",
          result.homeBin("delta-0.18.2"),
          result.homeBin("delta")
        ))))
        assert(result.commandExecutor.calls.isEmpty)
        assert(!Files.exists(result.statePath))

    test(
      "fedora example selects matching entries and previews dnf source setup plus cargo packages"
    ):
      withDryRun(
        "fedora.yaml",
        HostFacts.fake(distribution = Some("fedora"), commands = Set("dnf", "flatpak"))
      ): result =>
        assert(result.selectedNames.contains("fedora-base-cli"))
        assert(result.actions.exists(fileWrite("/etc/yum.repos.d/vscode.repo")))
        assert(result.actions.exists(commandContaining(Vector(
          "cargo",
          "binstall",
          "-y",
          "ripgrep"
        ))))
        assert(result.commandExecutor.calls.isEmpty)
        assert(!Files.exists(result.statePath))

    test("endeavouros example selects matching entries and previews pacman plus AUR packages"):
      withDryRun(
        "endeavouros.yaml",
        HostFacts.fake(
          distribution = Some("endeavouros"),
          version = None,
          codename = None,
          commands = Set("pacman", "yay")
        )
      ): result =>
        assert(result.selectedNames.contains("arch-base-cli"))
        assert(result.actions.exists(commandContaining(Vector("pacman", "-Syu", "--noconfirm"))))
        assert(result.actions.exists(commandContaining(Vector(
          "yay",
          "-S",
          "--needed",
          "--noconfirm",
          "visual-studio-code-bin"
        ))))
        assert(result.commandExecutor.calls.isEmpty)
        assert(!Files.exists(result.statePath))

    test(
      "opensuse tumbleweed example selects matching entries and previews zypper plus root file write"
    ):
      withDryRun(
        "opensuse-tumbleweed.yaml",
        HostFacts.fake(
          distribution = Some("opensuse-tumbleweed"),
          version = None,
          codename = None,
          commands = Set("zypper", "flatpak")
        )
      ): result =>
        assert(result.selectedNames.contains("tumbleweed-base-cli"))
        assert(result.actions.exists(commandContaining(Vector("zypper", "addrepo", "--refresh"))))
        assert(result.actions.exists(fileWrite("/etc/sddm.conf.d/wayland.conf")))
        assert(result.actions.exists(commandTextContaining("'sdk' 'install' 'java' '21.0.4-tem'")))
        assert(result.commandExecutor.calls.isEmpty)
        assert(!Files.exists(result.statePath))

  private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-06-29T09:00:00Z"), ZoneOffset.UTC)

  private def withDryRun(
      exampleName: String,
      hostFacts: HostFacts
  )(assertions: ExampleDryRunResult => Unit): Unit =
    val tmp = Files.createTempDirectory("initkit-public-example-")
    try
      val manifest = loadResolvedExample(exampleName, tmp.resolve("home"), hostFacts)
      val policy = ExecutionPolicy.fromManifest(manifest.spec.policy, Some(ExecutionRunMode.DryRun))
      val selected  = PlanSelector.select(manifest, PlanSelectionRequest(), hostFacts)
      val state     = ExecutionState.initial(manifest, fixedClock)
      val statePath = tmp
        .resolve(exampleName.stripSuffix(".yaml") + ".state.json")
        .toAbsolutePath
        .normalize()
      val commandExecutor = FakeCommandExecutor(Vector.empty)
      val sourceSetup     = SourceSetupGenerator.generate(manifest.spec.sources, hostFacts, policy)

      assert(selected.runnable.nonEmpty)

      val result = ExecutionWithSourceSetup
        .run(
          request = ExecutionEngineRequest(
            manifest = manifest,
            selection = PlanSelectionRequest(),
            hostFacts = hostFacts,
            state = state,
            statePath = statePath,
            policy = policy
          ),
          installer = new PackageManagerInstallers(commandExecutor, hostFacts = hostFacts),
          sourceSetup = sourceSetup,
          sourceSetupExecutor = SourceSetupExecutor(commandExecutor),
          stateWriter = ExecutionStateWriter.live,
          clock = fixedClock
        )
        .fold(error => fail(error.message), identity)

      assert(result.exitCode == ExecutionEngine.SuccessExitCode)
      assert(result.result.failed.isEmpty)
      assert(result.result.interrupted.isEmpty)

      assertions(ExampleDryRunResult(
        manifest = manifest,
        selectedNames = selected.runnable.flatMap(_.entry.name),
        actions = dryRunActions(result),
        statePath = statePath,
        commandExecutor = commandExecutor
      ))
    finally deleteRecursively(tmp)

  private def loadResolvedExample(
      exampleName: String,
      home: Path,
      hostFacts: HostFacts
  ): Manifest = ManifestVariableResolver
    .loadValidatedResolved(
      examplePath(exampleName),
      RuntimeVariables(VectorMap("HOME" -> home.toString, "USER" -> "initkit-user")),
      hostFacts
    )
    .fold(error => fail(error.message), identity)

  private def dryRunActions(result: ExecutionEngineResult): Vector[DryRunAction] =
    result.events.collect { case PlanEvent.DryRunOperation(_, data, _) => data.actions }.flatten

  private def fileWrite(path: String)(action: DryRunAction): Boolean = action match
    case DryRunAction.FileWrite(value, _, _) => value == path
    case _                                   => false

  private def commandContaining(expected: Vector[String])(action: DryRunAction): Boolean =
    action match
      case DryRunAction.Command(argv, _, _, _, _) => containsSlice(argv, expected)
      case _                                      => false

  private def commandTextContaining(expected: String)(action: DryRunAction): Boolean = action match
    case DryRunAction.Command(argv, shell, _, _, _) => argv.exists(_.contains(expected)) ||
      shell.exists(_.contains(expected))
    case _ => false

  private def containsSlice(values: Vector[String], expected: Vector[String]): Boolean =
    expected.isEmpty || values.sliding(expected.size).exists(_ == expected)

  private def examplePath(exampleName: String): Path =
    val path = Iterator
      .iterate(Path.of("").toAbsolutePath.normalize())(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("docs").resolve("examples").resolve(exampleName))
      .find(Files.isRegularFile(_))
      .getOrElse(throw new java.lang.AssertionError(
        s"docs example fixture not found: $exampleName"
      ))

    path

  private def deleteRecursively(path: Path): Unit = if Files.exists(path) then
    val paths = Files.walk(path)
    try paths.toScala(Vector).sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
    finally paths.close()

  private def fail(message: String): Nothing = throw new java.lang.AssertionError(message)

  private final case class ExampleDryRunResult(
      manifest: Manifest,
      selectedNames: Vector[String],
      actions: Vector[DryRunAction],
      statePath: Path,
      commandExecutor: FakeCommandExecutor
  ):
    def homeBin(name: String): String = manifest.spec.vars("binDir") + "/" + name
