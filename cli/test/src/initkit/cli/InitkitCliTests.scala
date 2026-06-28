package initkit.cli

import java.io.{PrintWriter, StringWriter}
import java.nio.file.Files
import java.nio.file.Paths
import java.time.{Clock, Instant, ZoneOffset}
import scala.collection.immutable.VectorMap

import initkit.config.Manifest
import initkit.core.{ExecutionState, ExecutionStateStore, ManifestVariableResolver, RuntimeVariables}
import initkit.host.HostFacts
import initkit.tui.TuiPlanRowStatus
import picocli.CommandLine
import utest.*

object InitkitCliTests extends TestSuite:
  private val fixedClock: Clock =
    Clock.fixed(Instant.parse("2026-06-28T12:00:00Z"), ZoneOffset.UTC)

  val tests: Tests = Tests:
    test("prints root help through picocli"):
      val result = runCli("--help")

      assert(result.exitCode == CommandLine.ExitCode.OK)
      assert(result.out.contains("Usage: initkit"))
      assert(result.out.contains("apply"))
      assert(result.out.contains("info"))
      assert(result.out.contains("tui"))

    test("prints root version through picocli"):
      val result = runCli("--version")

      assert(result.exitCode == CommandLine.ExitCode.OK)
      assert(result.out.trim == "initkit 0.1.0")

    test("apply reports missing config without stacktrace"):
      val tmp = os.temp.dir()
      try
        val missing = tmp / "missing.yaml"

        val result = runCli("apply", "--config", missing.toString, "--dry-run")

        assert(result.exitCode == CommandLine.ExitCode.USAGE)
        assert(result.err.contains("Config file not found:"))
        assert(!result.err.contains("Exception"))
        assert(!result.err.contains("at initkit."))
      finally os.remove.all(tmp)

    test("tui reports missing config without stacktrace"):
      val tmp = os.temp.dir()
      try
        val missing = tmp / "missing.yaml"

        val result = runCli("tui", "--config", missing.toString, "--dry-run", "--select", "apt-packages")

        assert(result.exitCode == CommandLine.ExitCode.USAGE)
        assert(result.err.contains("Config file not found:"))
        assert(!result.err.contains("Exception"))
        assert(!result.err.contains("at initkit."))
      finally os.remove.all(tmp)

    test("apply dry-runs the example config with shared and selection options"):
      val tmp = os.temp.dir()
      try
        val state = tmp / "state.json"
        val config = exampleConfig

        val result = runCli(
          "apply",
          "--config",
          config.toString,
          "--state",
          state.toString,
          "--reset-state",
          "--dry-run",
          "--yes",
          "--only",
          "apt-base-cli",
          "--skip",
          "snap-packages"
        )

        assert(result.exitCode == CommandLine.ExitCode.OK)
        assert(result.out.contains("manifest: developer-workstation"))
        assert(result.out.contains(s"config: ${config.toNIO.toAbsolutePath.normalize}"))
        assert(result.out.contains(s"state: ${state.toNIO.toAbsolutePath.normalize}"))
        assert(result.out.contains("mode: dry-run"))
        assert(result.out.contains("host:"))
        assert(result.out.contains("Selected entries"))
        assert(result.out.contains("Skipped entries"))
        assert(result.out.contains("Operations"))
        assert(result.out.contains("Summary"))
        assert(!hasAnsi(result.out))
        assert(!os.exists(state))
      finally os.remove.all(tmp)

    test("apply color never suppresses ANSI escapes"):
      val result = runCli("apply", "--config", exampleConfig.toString, "--dry-run", "--color", "never", "--only", "post-install")

      assert(result.exitCode == CommandLine.ExitCode.OK)
      assert(!hasAnsi(result.out))

    test("apply no-color suppresses forced ANSI escapes"):
      val result = runCli(
        "apply",
        "--config",
        exampleConfig.toString,
        "--dry-run",
        "--color",
        "always",
        "--no-color",
        "--only",
        "post-install"
      )

      assert(result.exitCode == CommandLine.ExitCode.OK)
      assert(!hasAnsi(result.out))

    test("apply color always emits fansi styled output"):
      val result = runCli("apply", "--config", exampleConfig.toString, "--dry-run", "--color", "always", "--only", "post-install")

      assert(result.exitCode == CommandLine.ExitCode.OK)
      assert(hasAnsi(result.out))

    test("NO_COLOR disables auto color"):
      val settings = CliColorSettings.resolve(
        mode = CliColorMode.Auto,
        noColor = false,
        noColorEnvironment = true,
        stdoutIsTerminal = true
      )

      assert(!settings.enabled)

    test("debug log adds diagnostics without replacing stdout"):
      val tmp = os.temp.dir()
      try
        val debugLog = tmp / "debug.log"

        val result = runCli(
          "apply",
          "--config",
          exampleConfig.toString,
          "--dry-run",
          "--only",
          "post-install",
          "--debug-log",
          debugLog.toString
        )

        assert(result.exitCode == CommandLine.ExitCode.OK)
        assert(result.out.contains("manifest: developer-workstation"))
        assert(result.out.contains("Summary"))
        assert(os.exists(debugLog))

        val debug = os.read(debugLog)
        assert(debug.contains("manifest_name=developer-workstation"))
        assert(debug.contains("dry_run=true"))
        assert(debug.contains("events="))
        assert(!hasAnsi(debug))
      finally os.remove.all(tmp)

    test("debug writes diagnostics to stderr without replacing stdout"):
      val result = runCli(
        "apply",
        "--config",
        exampleConfig.toString,
        "--dry-run",
        "--only",
        "post-install",
        "--debug"
      )

      assert(result.exitCode == CommandLine.ExitCode.OK)
      assert(result.out.contains("manifest: developer-workstation"))
      assert(result.out.contains("Summary"))
      assert(result.err.contains("apply command started"))
      assert(result.err.contains("manifest_name=developer-workstation"))
      assert(!hasAnsi(result.err))

    test("tui model loader reads example manifest and completed state"):
      val tmp = os.temp.dir()
      try
        val hostFacts = HostFacts.fake(distribution = Some("ubuntu"))
        val manifest = loadExampleManifest(hostFacts)
        val statePath = tmp / "state.json"
        val completedState = ExecutionState.markCompleted(
          ExecutionState.initial(manifest, fixedClock),
          "apt-base-cli",
          fixedClock.instant()
        )

        requireRight(ExecutionStateStore.write(statePath.toNIO, completedState))

        val viewModel = requireRight(
          TuiCommandModelLoader.load(
            TuiCommandModelOptions(
              configPath = exampleConfig.toNIO,
              statePath = Some(statePath.toNIO),
              resetState = false,
              dryRun = true,
              selectedValues = Vector("apt-packages"),
              skipValues = Vector.empty,
              hostFacts = hostFacts,
              clock = fixedClock
            )
          )
        )

        assert(viewModel.profile.name == "developer-workstation")
        assert(viewModel.stateFile.status.toString == "Existing")
        assert(viewModel.rows.exists(row => row.name == "apt-base-cli" && row.status == TuiPlanRowStatus.Completed))
        assert(viewModel.rows.exists(row => row.name == "apt-containers" && row.selected))
        assert(viewModel.rows.exists(row => row.name == "pacman-base-cli" && row.status == TuiPlanRowStatus.Skipped))
      finally os.remove.all(tmp)

  private def runCli(args: String*): CliResult =
    val out = new StringWriter()
    val err = new StringWriter()
    val commandLine = InitkitCli.commandLine()
    commandLine.setOut(new PrintWriter(out))
    commandLine.setErr(new PrintWriter(err))

    val exitCode = commandLine.execute(args*)

    CliResult(exitCode, out.toString, err.toString)

  private def hasAnsi(value: String): Boolean =
    value.contains("\u001b[")

  private def exampleConfig: os.Path =
    val path = Iterator
      .iterate(Paths.get("").toAbsolutePath.normalize())(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("config.example.yaml"))
      .find(Files.isRegularFile(_))
      .getOrElse(throw new java.lang.AssertionError("config.example.yaml fixture not found"))

    os.Path(path)

  private def loadExampleManifest(hostFacts: HostFacts): Manifest =
    ManifestVariableResolver
      .loadValidatedResolved(exampleConfig.toNIO, runtimeVariables, hostFacts)
      .fold(error => throw new java.lang.AssertionError(error.message), identity)

  private def runtimeVariables: RuntimeVariables =
    RuntimeVariables(
      VectorMap.from(
        Vector(
          "HOME" -> sys.env.getOrElse("HOME", System.getProperty("user.home", "")),
          "USER" -> sys.env.getOrElse("USER", System.getProperty("user.name", ""))
        ).filter(_._2.nonEmpty)
      )
    )

  private def requireRight[A](value: Either[?, A]): A =
    value.fold(error => throw new java.lang.AssertionError(error.toString), identity)

  private final case class CliResult(exitCode: Int, out: String, err: String)
