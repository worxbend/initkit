package binstaller.cli

import binstaller.core.BinaryInstallerService
import binstaller.core.BinaryDownloadClient
import binstaller.core.BinaryDownloadError
import binstaller.core.BinaryDownloadProgress
import binstaller.core.BinaryDownloadProgressObserver
import binstaller.core.DirectBinaryInstaller
import binstaller.core.HttpTextClient
import binstaller.core.HttpTextError
import binstaller.core.InstallFileSystem
import binstaller.core.InstallerOptions
import binstaller.core.InstallerResult
import binstaller.core.ResetState
import utest.*

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

object CliModuleTest extends TestSuite:

  val tests: Tests = Tests:
    test("module path includes upstream modules"):
      assert(CliModule.modulePath == Vector("config", "core", "tui", "cli"))

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

    test("plan help advertises explicit tui entrypoint"):
      val result = runCli(Vector("plan", "--help"))

      assert(result.exitCode == 0)
      assert(result.out.contains("--tui"))
      assert(result.out.contains("explicit planning TUI entrypoint"))
      assert(result.out.contains("script-friendly"))

    test("apply help advertises explicit tui entrypoint"):
      val result = runCli(Vector("apply", "--help"))

      assert(result.exitCode == 0)
      assert(result.out.contains("--tui"))
      assert(result.out.contains("explicit apply TUI entrypoint"))
      assert(result.out.contains("script-friendly"))

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

    test("apply forwards state override and reset-state"):
      val service = RecordingInstallerService()
      val result  = runCli(
        Vector(
          "apply",
          "--config",
          "profile.yaml",
          "--state",
          "custom.state.json",
          "--reset-state"
        ),
        service
      )

      assert(result.exitCode == 0)
      assert(service.applyOptions.exists(_.statePath.contains("custom.state.json")))
      assert(service.applyOptions.exists(_.resetState == ResetState.Enabled))

    test("plan tui is explicit and does not call plan service"):
      val service = RecordingInstallerService()
      val result  = runCli(Vector("plan", "--config", "profile.yaml", "--tui"), service)

      assert(result.exitCode == 1)
      assert(result.out.contains("binstaller plan --tui is not implemented yet."))
      assert(result.out.contains("non-interactive"))
      assert(service.planOptions.isEmpty)

    test("apply tui is explicit and does not call apply service"):
      val service = RecordingInstallerService()
      val result  = runCli(Vector("apply", "--config", "profile.yaml", "--tui"), service)

      assert(result.exitCode == 1)
      assert(result.out.contains("binstaller apply --tui is not implemented yet."))
      assert(result.out.contains("apply --dry-run"))
      assert(service.applyOptions.isEmpty)

    test("plan prints all example tools in manifest order"):
      val result = runCli(
        Vector("plan", "--config", configExamplePath.toString),
        resolvingService
      )

      assert(result.exitCode == 0)
      assert(renderedToolNames(result.out) == exampleToolNames)

    test("plan only selection prints one requested tool"):
      val result = runCli(
        Vector("plan", "--config", configExamplePath.toString, "--only", "yazi"),
        resolvingService
      )

      assert(result.exitCode == 0)
      assert(result.out.contains("tools: 1"))
      assert(renderedToolNames(result.out) == Vector("yazi"))

    test("plan skip selection omits the requested tool and preserves order"):
      val result = runCli(
        Vector("plan", "--config", configExamplePath.toString, "--skip", "neovim"),
        resolvingService
      )

      assert(result.exitCode == 0)
      assert(renderedToolNames(result.out) == exampleToolNames.filterNot(_ == "neovim"))

    test("apply dry-run renders every sudo symlink command and marks sudo risk"):
      val result = runCli(
        Vector("apply", "--dry-run", "--config", configExamplePath.toString, "--only", "neovim"),
        resolvingService
      )

      assert(result.exitCode == 0)
      assert(result.out.contains("sudo risk: YES"))
      assert(result.out.linesIterator.count(_.contains("sudo ln -sfn")) == 5)

    test("apply dry-run renders local and sudo symlink actions without executing them"):
      val tempRoot = Files.createTempDirectory("binstaller-cli-dry-symlinks")
      val appsDir  = tempRoot.resolve("apps")
      val config   = writeConfig(tempRoot, noWriteYaml(appsDir, tempRoot.resolve("state.json")))

      val result = runCli(
        Vector("apply", "--dry-run", "--config", config.toString),
        resolvingService
      )

      assert(result.exitCode == 0)
      assert(result.out.contains("[local] ln -sfn"))
      assert(result.out.contains("[sudo risk] sudo ln -sfn"))
      assert(!Files.exists(appsDir))

    test("plan and apply dry-run do not create install or state paths"):
      val tempRoot  = Files.createTempDirectory("binstaller-cli-test")
      val appsDir   = tempRoot.resolve("apps")
      val stateFile = tempRoot.resolve("state.json")
      val config    = writeConfig(tempRoot, noWriteYaml(appsDir, stateFile))

      val planResult   = runCli(Vector("plan", "--config", config.toString), resolvingService)
      val dryRunResult = runCli(
        Vector("apply", "--dry-run", "--config", config.toString),
        resolvingService
      )

      assert(planResult.exitCode == 0)
      assert(dryRunResult.exitCode == 0)
      assert(!Files.exists(appsDir))
      assert(!Files.exists(stateFile))
      assert(!Files.exists(appsDir.resolve("alpha")))

    test("non dry-run apply requires yes when confirmation policy is enabled"):
      val tempRoot = Files.createTempDirectory("binstaller-cli-confirm")
      val appsDir  = tempRoot.resolve("apps")
      val config   = writeConfig(tempRoot, noWriteYaml(appsDir, tempRoot.resolve("state.json")))

      val result = runCli(
        Vector("apply", "--config", config.toString),
        resolvingService
      )

      assert(result.exitCode == 1)
      assert(result.out.contains("policy.requireConfirmation"))
      assert(result.out.contains("--yes"))
      assert(!result.out.contains("Exception"))
      assert(!Files.exists(appsDir))

    test("apply renders download progress bar in place"):
      val tempRoot = Files.createTempDirectory("binstaller-cli-progress")
      val appsDir  = tempRoot.resolve("apps")
      val config   = writeConfig(tempRoot, progressYaml(appsDir))
      val service  = BinaryInstallerService.resolving(
        FakeHttpTextClient("v1.34.0"),
        DirectBinaryInstaller(
          ProgressBinaryDownloadClient("alpha-binary".getBytes),
          InstallFileSystem.nio
        )
      )

      val result = runCli(
        Vector("apply", "--config", config.toString, "--yes"),
        service
      )

      assert(result.exitCode == 0)
      val plainOutput = stripAnsi(result.out)
      assert(plainOutput.contains("\r⬇ downloading alpha"))
      assert(plainOutput.contains("[███████████████░░░░░░░░░░░░░░░] 50%"))
      assert(plainOutput.contains("\r✅ completed alpha [██████████████████████████████] 100%"))
      assert(!plainOutput.contains("\n⬇ downloading alpha"))
      assert(plainOutput.contains("installed alpha"))
      assert(plainOutput.contains("✨ Summary"))
      assert(plainOutput.contains("✅ installed: 1"))
      assert(plainOutput.contains("🎉 apply completed successfully"))
      assert(result.out.contains("\u001b["))
      assert(Files.readString(appsDir.resolve("alpha/bin/alpha")) == "alpha-binary")

  private def runCli(
      args: Vector[String],
      service: BinaryInstallerService = BinaryInstallerService.placeholder
  ): CliRunResult =
    val outBuffer = StringWriter()
    val errBuffer = StringWriter()
    val out       = PrintWriter(outBuffer, true)
    val err       = PrintWriter(errBuffer, true)
    val exitCode  = CliModule.commandLine(service, out, err).execute(args*)
    CliRunResult(exitCode, outBuffer.toString, errBuffer.toString)

  private def renderedToolNames(output: String): Vector[String] =
    stripAnsi(output).linesIterator.toVector.collect:
      case ToolHeading(name) => name

  private def stripAnsi(output: String): String = output.replaceAll("\u001b\\[[;\\d]*m", "")

  private def writeConfig(tempRoot: Path, content: String): Path =
    val path = tempRoot.resolve("profile.yaml")
    Files.writeString(path, content)
    path

  private def noWriteYaml(appsDir: Path, stateFile: Path): String =
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: no-writes
       |spec:
       |  policy:
       |    appsDir: "$appsDir"
       |    stateFile: "$stateFile"
       |    allowSudoSymlinks: true
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "$appsDir/alpha"
       |        download:
       |          url: https://example.invalid/alpha
       |          filename: alpha
       |        executables:
       |          - path: bin/alpha
       |        symlinks:
       |          - path: bin/a
       |            target: bin/alpha
       |          - path: /usr/local/bin/alpha
       |            target: "$appsDir/alpha/bin/alpha"
       |            sudo: true
       |""".stripMargin

  private def progressYaml(appsDir: Path): String = s"""
                                                       |apiVersion: binstaller.io/v1alpha1
                                                       |kind: BinaryDistributionProfile
                                                       |metadata:
                                                       |  name: progress
                                                       |spec:
                                                       |  policy:
                                                       |    appsDir: "$appsDir"
                                                       |  vars: {}
                                                       |  versions:
                                                       |    alpha: "1.0.0"
                                                       |  plan:
                                                       |    - name: alpha
                                                       |      kind: binary-tool
                                                       |      spec:
                                                       |        versionRef: alpha
                                                       |        installDir: "$appsDir/alpha"
                                                       |        download:
                                                       |          url: https://example.invalid/alpha
                                                       |          filename: alpha
                                                       |        executables:
                                                       |          - path: bin/alpha
                                                       |""".stripMargin

  private def findRepoFile(name: String): Path = Iterator
    .iterate(Path.of("").toAbsolutePath)(_.getParent)
    .takeWhile(_ != null)
    .map(_.resolve(name))
    .find(Files.isRegularFile(_))
    .getOrElse(
      throw java.lang.AssertionError(s"could not find $name from ${Path.of("").toAbsolutePath}")
    )

  private val ToolHeading = """^\d+\. (\S+)$""".r

  private val configExamplePath: Path = findRepoFile("config.example.yaml")

  private val resolvingService: BinaryInstallerService =
    BinaryInstallerService.resolving(FakeHttpTextClient("v1.34.0"))

  private val exampleToolNames: Vector[String] = Vector(
    "yazi",
    "zig",
    "minikube",
    "xplr",
    "kind",
    "zellij",
    "helm",
    "kubectl",
    "kustomize",
    "neovide",
    "neovim",
    "lazygit",
    "jujutsu",
    "dotbot",
    "nerd-font-installer"
  )

private final case class CliRunResult(exitCode: Int, out: String, err: String)

private final class FakeHttpTextClient(text: String) extends HttpTextClient:

  def getText(url: String): Either[HttpTextError, String] =
    if url == "https://dl.k8s.io/release/stable.txt" then Right(text)
    else Left(HttpTextError(url, s"unexpected URL $url"))

private final class ProgressBinaryDownloadClient(bytes: Array[Byte]) extends BinaryDownloadClient:

  def download(url: String): Either[BinaryDownloadError, Array[Byte]] = Right(bytes)

  override def download(
      url: String,
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, Array[Byte]] =
    val halfway = bytes.length.toLong / 2L
    val total   = Some(bytes.length.toLong)
    progressObserver.onProgress(BinaryDownloadProgress.Started(url, total))
    progressObserver.onProgress(BinaryDownloadProgress.Advanced(url, halfway, total))
    progressObserver.onProgress(BinaryDownloadProgress.Finished(url, bytes.length.toLong, total))
    Right(bytes)

private final class RecordingInstallerService extends BinaryInstallerService:

  private var recordedPlanOptions: Option[InstallerOptions]  = None
  private var recordedApplyOptions: Option[InstallerOptions] = None

  def planOptions: Option[InstallerOptions] = recordedPlanOptions

  def applyOptions: Option[InstallerOptions] = recordedApplyOptions

  def plan(options: InstallerOptions): InstallerResult =
    recordedPlanOptions = Some(options)
    InstallerResult(Vector("plan"), 0)

  def apply(options: InstallerOptions): InstallerResult =
    recordedApplyOptions = Some(options)
    InstallerResult(Vector("apply"), 0)

  def versions(options: InstallerOptions): InstallerResult = InstallerResult(Vector("versions"), 0)
