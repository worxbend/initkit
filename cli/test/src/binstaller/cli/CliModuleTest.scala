package binstaller.cli

import utest.*

import java.io.PrintWriter
import java.io.StringWriter

object CliModuleTest extends TestSuite:

  val tests: Tests = Tests:
    test("module path includes upstream modules"):
      assert(CliModule.modulePath == Vector("config", "core", "cli"))

    test("help describes the binstaller binary installer"):
      val result = runCli(Vector("--help"))

      assert(result.exitCode == 0)
      assert(result.out.contains("binstaller"))
      assert(result.out.contains("binary installer"))

    test("help lists supported commands"):
      val result = runCli(Vector("--help"))

      assert(result.out.contains("plan"))
      assert(result.out.contains("apply"))
      assert(result.out.contains("versions"))

    test("help omits out-of-scope first-class commands"):
      val result = runCli(Vector("--help"))

      assert(!result.out.contains("apt"))
      assert(!result.out.contains("dotfiles"))
      assert(!result.out.contains("Nerd Fonts"))
      assert(!result.out.contains("TUI"))

    test("plan requires config"):
      val result = runCli(Vector("plan"))

      assert(result.exitCode != 0)
      assert(result.err.trim == "Missing required option: --config")

    test("apply requires config"):
      val result = runCli(Vector("apply"))

      assert(result.exitCode != 0)
      assert(result.err.trim == "Missing required option: --config")

    test("versions requires config"):
      val result = runCli(Vector("versions"))

      assert(result.exitCode != 0)
      assert(result.err.trim == "Missing required option: --config")

  private def runCli(args: Vector[String]): CliRunResult =
    val outBuffer = StringWriter()
    val errBuffer = StringWriter()
    val exitCode  = CliModule.run(
      args,
      PrintWriter(outBuffer, true),
      PrintWriter(errBuffer, true)
    )
    CliRunResult(exitCode, outBuffer.toString, errBuffer.toString)

private final case class CliRunResult(exitCode: Int, out: String, err: String)
