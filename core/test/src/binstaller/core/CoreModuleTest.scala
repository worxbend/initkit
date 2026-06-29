package binstaller.core

import binstaller.config.ChecksumAlgorithm
import binstaller.config.ChecksumSpec
import binstaller.config.ConfigModule
import binstaller.config.ExecutableMode
import binstaller.config.ValidationError
import utest.*

import java.nio.file.Files
import java.nio.file.Path

object CoreModuleTest extends TestSuite:

  val tests: Tests = Tests:
    test("module path includes config before core"):
      assert(CoreModule.modulePath == Vector("config", "core"))

    test("pinned versions interpolate into URLs and paths"):
      val plan = resolve(validPinnedYaml)

      val tool = onlyTool(plan)
      assert(tool.installDir == "/home/test/.apps/alpha-1.2.3")
      assert(tool.download.url == "https://example.invalid/alpha-1.2.3-x86_64.tar.gz")
      assert(tool.download.archive.exists(_.files.head.from == "alpha-1.2.3/alpha"))
      assert(tool.symlinks.head.target == "/home/test/.apps/alpha-1.2.3/bin/alpha")

    test("kubectl stable text resolves through a fake HTTP client"):
      val plan = resolve(kubectlResolverYaml, FakeHttpTextClient("v1.33.0"))

      val tool = onlyTool(plan)
      assert(tool.version == ResolvedVersion.Concrete("v1.33.0"))
      assert(tool.download.url == "https://dl.k8s.io/release/v1.33.0/bin/linux/amd64/kubectl")

    test("dynamic latest-url remains dynamic without a concrete version"):
      val plan = resolve(dynamicLatestUrlYaml)

      val tool = onlyTool(plan)
      assert(tool.version == ResolvedVersion.DynamicLatestUrl(Some("upstream latest endpoint")))
      assert(ResolvedVersion.render(tool.version) == "dynamic latest-url")
      assert(tool.download.url == "https://example.invalid/latest/download/beta")

    test("unresolved variables and missing version values produce validation-style errors"):
      val errors = resolveErrors(invalidVariablesYaml)

      assert(errors.exists(errorAt("spec.plan[0].spec.installDir")))
      assert(errors.exists(errorAt("spec.plan[1].spec.download.url")))
      assert(errors.exists(_.message.contains("unresolved variable 'MISSING'")))
      assert(errors.exists(_.message.contains("no concrete version is available")))

    test("shell command substitution is text and is never executed"):
      val plan = resolve(shellSyntaxYaml)

      val tool = onlyTool(plan)
      assert(tool.installDir == "/home/test/.apps/$(echo should-not-run)")
      assert(tool.installer.exists(_.args.head ==
        "/home/test/.apps/$(echo should-not-run)/script.sh"))

    test("direct binary install writes download bytes to first executable path"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-direct")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes),
        InstallFileSystem.nio
      )

      val result = installer.installTool(directTool(installDir))

      assert(result == Right(ToolInstallSuccess("alpha", installDir.toString)))
      assert(Files.readString(installDir.resolve("bin/alpha")) == "alpha-binary")

    test("sha256 mismatch fails before replacing an existing install"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-checksum")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val tool = directTool(
        installDir,
        checksum = Some(ChecksumSpec(ChecksumAlgorithm.Sha256, "0" * 64))
      )
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("replacement".getBytes),
        InstallFileSystem.nio
      )

      val result = installer.installTool(tool)

      assert(result.isLeft)
      assert(result.left.exists(_.isInstanceOf[ToolInstallError.ChecksumMismatch]))
      assert(Files.readString(existingFile) == "existing")

    test("executable modes use four-digit octal strings and default to 0755"):
      val fileSystem = RecordingInstallFileSystem()
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha".getBytes),
        fileSystem
      )
      val tool = directTool(
        Path.of("/tmp/alpha"),
        executables = Vector(
          ResolvedExecutable("bin/alpha", Some(ExecutableMode("0700"))),
          ResolvedExecutable("bin/helper", None)
        )
      )

      val result = installer.installTool(tool)

      assert(result == Right(ToolInstallSuccess("alpha", "/tmp/alpha")))
      assert(fileSystem.recordedModes.map(request => request.path -> request.mode.octal) ==
        Vector("bin/alpha" -> "0700", "bin/helper" -> "0755"))
      assert(fileSystem.recordedModes.map(_.mode.numeric) == Vector(448, 493))

    test("download failure preserves existing install and returns a typed error"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-download")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.failure("network unavailable"),
        InstallFileSystem.nio
      )

      val result = installer.installTool(directTool(installDir))

      assert(result ==
        Left(
          ToolInstallError.DownloadFailed(
            "alpha",
            "https://example.invalid/alpha",
            "network unavailable"
          )
        ))
      assert(Files.readString(existingFile) == "existing")

    test("staging failure preserves existing install and does not replace"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-staging")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val fileSystem = RecordingInstallFileSystem(stageFailure = Some("disk full"))
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("replacement".getBytes),
        fileSystem
      )

      val result = installer.installTool(directTool(installDir))

      assert(result == Left(ToolInstallError.StagingFailed("alpha", "disk full")))
      assert(fileSystem.replaceCalls == 0)
      assert(Files.readString(existingFile) == "existing")

    test("mode application failure preserves existing install and does not replace"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-mode")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val fileSystem = RecordingInstallFileSystem(modeFailure = Some("permission denied"))
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("replacement".getBytes),
        fileSystem
      )

      val result = installer.installTool(directTool(installDir))

      assert(result ==
        Left(
          ToolInstallError.ModeApplicationFailed(
            "alpha",
            "bin/alpha",
            "0755",
            "permission denied"
          )
        ))
      assert(fileSystem.replaceCalls == 0)
      assert(Files.readString(existingFile) == "existing")

    test("apply renders expected executor failures without throwing"):
      val tempRoot = Files.createTempDirectory("binstaller-core-cli-error")
      val config   = tempRoot.resolve("profile.yaml")
      Files.writeString(config, directBinaryYaml(tempRoot.resolve("alpha")))
      val service = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(
          FakeBinaryDownloadClient.failure("network unavailable"),
          InstallFileSystem.nio
        )
      )

      val result = service.apply(
        InstallerOptions(
          configPath = config.toString,
          statePath = None,
          resetState = ResetState.Disabled,
          verboseOutput = VerboseOutput.Disabled
        )
      )

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("download failed")))

  private def resolve(
      yaml: String,
      httpTextClient: HttpTextClient = FakeHttpTextClient("")
  ): ResolvedPlan =
    val profile = ConfigModule.loadString(yaml) match
      case Right(value) => value
      case Left(error)  => abort(s"expected valid config, got $error")

    PlanResolver.resolve(profile, testResolutionOptions, httpTextClient) match
      case Right(plan)                                     => plan
      case Left(ResolvePlanError.ValidationFailed(errors)) =>
        abort(s"expected resolved plan, got ${errors.mkString(", ")}")

  private def resolveErrors(yaml: String): Vector[ValidationError] =
    val profile = ConfigModule.loadString(yaml) match
      case Right(value) => value
      case Left(error)  => abort(s"expected config decode success, got $error")

    PlanResolver.resolve(profile, testResolutionOptions, FakeHttpTextClient("")) match
      case Left(ResolvePlanError.ValidationFailed(errors)) => errors
      case Right(plan) => abort(s"expected resolution errors, got $plan")

  private def onlyTool(plan: ResolvedPlan): ResolvedTool = plan.tools match
    case Vector(tool) => tool
    case other        => abort(s"expected one tool, got ${other.size}")

  private def errorAt(path: String)(error: ValidationError): Boolean = error.path == path

  private def abort(message: String): Nothing = throw java.lang.AssertionError(message)

  private def directTool(
      installDir: Path,
      checksum: Option[ChecksumSpec] = None,
      executables: Vector[ResolvedExecutable] = Vector(ResolvedExecutable("bin/alpha", None))
  ): ResolvedTool = ResolvedTool(
    name = "alpha",
    description = None,
    version = ResolvedVersion.Concrete("1.0.0"),
    installDir = installDir.toString,
    createDirectories = Vector("bin"),
    download = ResolvedDownload(
      url = "https://example.invalid/alpha",
      filename = "alpha",
      checksum = checksum,
      archive = None
    ),
    installer = None,
    executables = executables,
    symlinks = Vector.empty
  )

  private def directBinaryYaml(installDir: Path): String =
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: direct-apply
       |spec:
       |  policy:
       |    appsDir: "${installDir.getParent}"
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "$installDir"
       |        createDirectories:
       |          - bin
       |        download:
       |          url: https://example.invalid/alpha
       |          filename: alpha
       |        executables:
       |          - path: bin/alpha
       |""".stripMargin

  private val testResolutionOptions: ResolutionOptions = ResolutionOptions(
    Map("HOME" -> "/home/test")
  )

  private val validPinnedYaml: String =
    """
      |apiVersion: binstaller.io/v1alpha1
      |kind: BinaryDistributionProfile
      |metadata:
      |  name: pinned
      |spec:
      |  policy:
      |    appsDir: "${HOME}/.apps"
      |    allowSudoSymlinks: true
      |  vars:
      |    linuxArch: x86_64
      |  versions:
      |    alpha: "1.2.3"
      |  plan:
      |    - name: alpha
      |      kind: binary-tool
      |      spec:
      |        versionRef: alpha
      |        installDir: "${appsDir}/alpha-${version}"
      |        download:
      |          url: "https://example.invalid/alpha-${version}-${linuxArch}.tar.gz"
      |          filename: "alpha-${version}.tar.gz"
      |          archive:
      |            type: tar.gz
      |            extract:
      |              files:
      |                - from: "alpha-${version}/alpha"
      |                  to: bin/alpha
      |        executables:
      |          - path: bin/alpha
      |        symlinks:
      |          - path: bin/a
      |            target: "${installDir}/bin/alpha"
      |""".stripMargin

  private val kubectlResolverYaml: String =
    """
      |apiVersion: binstaller.io/v1alpha1
      |kind: BinaryDistributionProfile
      |metadata:
      |  name: kubectl
      |spec:
      |  policy:
      |    appsDir: "${HOME}/.apps"
      |  vars: {}
      |  versions:
      |    kubectl:
      |      resolver:
      |        type: http-text
      |        url: https://dl.k8s.io/release/stable.txt
      |  plan:
      |    - name: kubectl
      |      kind: binary-tool
      |      spec:
      |        versionRef: kubectl
      |        installDir: "${appsDir}/kubectl"
      |        download:
      |          url: "https://dl.k8s.io/release/${version}/bin/linux/amd64/kubectl"
      |          filename: kubectl
      |        executables:
      |          - path: bin/kubectl
      |""".stripMargin

  private val dynamicLatestUrlYaml: String =
    """
      |apiVersion: binstaller.io/v1alpha1
      |kind: BinaryDistributionProfile
      |metadata:
      |  name: dynamic
      |spec:
      |  policy:
      |    appsDir: "${HOME}/.apps"
      |  vars: {}
      |  versions:
      |    beta:
      |      dynamic:
      |        type: latest-url
      |        note: upstream latest endpoint
      |  plan:
      |    - name: beta
      |      kind: binary-tool
      |      spec:
      |        versionRef: beta
      |        installDir: "${appsDir}/beta"
      |        download:
      |          url: https://example.invalid/latest/download/beta
      |          filename: beta
      |        executables:
      |          - path: bin/beta
      |""".stripMargin

  private val invalidVariablesYaml: String =
    """
      |apiVersion: binstaller.io/v1alpha1
      |kind: BinaryDistributionProfile
      |metadata:
      |  name: invalid-vars
      |spec:
      |  policy:
      |    appsDir: "${HOME}/.apps"
      |  vars: {}
      |  versions:
      |    alpha: "1.0.0"
      |    beta:
      |      dynamic:
      |        type: latest-url
      |  plan:
      |    - name: alpha
      |      kind: binary-tool
      |      spec:
      |        versionRef: alpha
      |        installDir: "${appsDir}/${MISSING}"
      |        download:
      |          url: https://example.invalid/alpha
      |          filename: alpha
      |        executables:
      |          - path: bin/alpha
      |    - name: beta
      |      kind: binary-tool
      |      spec:
      |        versionRef: beta
      |        installDir: "${appsDir}/beta"
      |        download:
      |          url: "https://example.invalid/releases/${version}/beta"
      |          filename: beta
      |        executables:
      |          - path: bin/beta
      |""".stripMargin

  private val shellSyntaxYaml: String = """
                                          |apiVersion: binstaller.io/v1alpha1
                                          |kind: BinaryDistributionProfile
                                          |metadata:
                                          |  name: shell-text
                                          |spec:
                                          |  policy:
                                          |    appsDir: "${HOME}/.apps"
                                          |  vars:
                                          |    shellText: "$(echo should-not-run)"
                                          |  versions:
                                          |    script: "1.0.0"
                                          |  plan:
                                          |    - name: script
                                          |      kind: binary-tool
                                          |      spec:
                                          |        versionRef: script
                                          |        installDir: "${appsDir}/${shellText}"
                                          |        createDirectories:
                                          |          - bin
                                          |        download:
                                          |          url: https://example.invalid/script.sh
                                          |          filename: script.sh
                                          |        installer:
                                          |          shell: sh
                                          |          args:
                                          |            - "${downloadPath}"
                                          |          cleanup: true
                                          |        executables:
                                          |          - path: bin/script
                                          |""".stripMargin

private final class FakeHttpTextClient(text: String) extends HttpTextClient:

  def getText(url: String): Either[HttpTextError, String] =
    if url == "https://dl.k8s.io/release/stable.txt" then Right(text)
    else Left(HttpTextError(url, s"unexpected URL $url"))

private final class FakeBinaryDownloadClient(result: Either[BinaryDownloadError, Array[Byte]])
    extends BinaryDownloadClient:

  def download(url: String): Either[BinaryDownloadError, Array[Byte]] =
    result.left.map(error => error.copy(url = url))

private object FakeBinaryDownloadClient:

  def success(bytes: Array[Byte]): FakeBinaryDownloadClient = FakeBinaryDownloadClient(Right(bytes))

  def failure(message: String): FakeBinaryDownloadClient =
    FakeBinaryDownloadClient(Left(BinaryDownloadError("", message)))

private final class RecordingInstallFileSystem(
    stageFailure: Option[String] = None,
    modeFailure: Option[String] = None
) extends InstallFileSystem:

  private var modes: Vector[ExecutableModeRequest] = Vector.empty
  private var replacements: Int                    = 0

  def recordedModes: Vector[ExecutableModeRequest] = modes

  def replaceCalls: Int = replacements

  def stageDirectBinary(
      installDir: Path,
      createDirectories: Vector[String],
      executablePath: String,
      bytes: Array[Byte]
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall] = stageFailure match
    case Some(message) => Left(InstallFileSystemError.StagingFailed(message))
    case None          => Right(StagedInstall(Path.of("/tmp/staged-alpha"), installDir))

  def applyExecutableModes(
      stagedInstall: StagedInstall,
      executables: Vector[ExecutableModeRequest]
  ): Either[InstallFileSystemError.ModeApplicationFailed, Unit] =
    modes = executables
    modeFailure match
      case Some(message) =>
        val first = executables.head
        Left(
          InstallFileSystemError.ModeApplicationFailed(
            first.path,
            first.mode.octal,
            message
          )
        )
      case None => Right(())

  def replaceInstall(
      stagedInstall: StagedInstall
  ): Either[InstallFileSystemError.ReplacementFailed, Unit] =
    replacements = replacements + 1
    Right(())

  def discardStaged(stagedInstall: StagedInstall): Unit = ()
