package initkit.cli

import java.io.{PrintWriter, StringWriter}

import picocli.CommandLine
import utest.*

object InitkitCliTests extends TestSuite:
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

    test("apply parses shared and selection options"):
      val tmp = os.temp.dir()
      try
        val config = tmp / "config.yaml"
        val state = tmp / "state.json"
        os.write(config, "apiVersion: initkit.io/v1alpha1\n")

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
        assert(result.out.contains(s"config: ${config.toNIO.toAbsolutePath.normalize}"))
        assert(result.out.contains(s"state: ${state.toNIO.toAbsolutePath.normalize}"))
        assert(result.out.contains("reset-state: true"))
        assert(result.out.contains("dry-run: true"))
        assert(result.out.contains("yes: true"))
        assert(result.out.contains("only: apt-base-cli"))
        assert(result.out.contains("skip: snap-packages"))
      finally os.remove.all(tmp)

  private def runCli(args: String*): CliResult =
    val out = new StringWriter()
    val err = new StringWriter()
    val commandLine = InitkitCli.commandLine()
    commandLine.setOut(new PrintWriter(out))
    commandLine.setErr(new PrintWriter(err))

    val exitCode = commandLine.execute(args*)

    CliResult(exitCode, out.toString, err.toString)

  private final case class CliResult(exitCode: Int, out: String, err: String)
