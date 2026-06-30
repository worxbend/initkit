package binstaller.core

import binstaller.config.ChecksumAlgorithm
import binstaller.config.ConfigModule
import binstaller.config.ExecutableMode
import binstaller.config.ArchiveExtract
import binstaller.config.ArchiveSpec
import binstaller.config.ArchiveType
import binstaller.config.AllowSudoSymlinks
import binstaller.config.ExtractMapping
import binstaller.config.ValidationError
import binstaller.config.SymlinkPrivilege
import utest.*

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.zip.GZIPOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import scala.jdk.CollectionConverters.*
import scala.util.Using
import upickle.default.read
import upickle.default.write

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
      assert(ResolvedVersion.render(tool.version) == "v1.33.0")
      assert(tool.download.url == "https://dl.k8s.io/release/v1.33.0/bin/linux/amd64/kubectl")

    test("JDK http-text client records direct no-redirect provenance"):
      val response = FakeHttpResponse[String](
        responseUri = "https://example.invalid/stable.txt",
        responseStatusCode = 200,
        responseBody = "v1.0.0"
      )
      val client = JdkHttpTextClient(StaticHttpClient(response))

      val result = client.getTextWithProvenance("https://example.invalid/stable.txt")

      result match
        case Right(value) =>
          assert(value.text == "v1.0.0")
          assert(value.provenance.initialUrl == "https://example.invalid/stable.txt")
          assert(value.provenance.finalUrl == "https://example.invalid/stable.txt")
          assert(value.provenance.redirects.isEmpty)
        case Left(error) => abort(s"expected text response, got $error")

    test("JDK http-text client records multiple redirects"):
      val first = FakeHttpResponse[String](
        responseUri = "https://example.invalid/stable.txt",
        responseStatusCode = 302,
        responseBody = ""
      )
      val second = FakeHttpResponse[String](
        responseUri = "https://cdn.example.invalid/releases/stable.txt",
        responseStatusCode = 301,
        responseBody = "",
        previous = Some(first)
      )
      val finalResponse = FakeHttpResponse[String](
        responseUri = "https://mirror.example.invalid/releases/stable.txt",
        responseStatusCode = 200,
        responseBody = "v1.0.1",
        previous = Some(second)
      )
      val client = JdkHttpTextClient(StaticHttpClient(finalResponse))

      val result = client.getTextWithProvenance("https://example.invalid/stable.txt")

      result match
        case Right(value) =>
          assert(value.text == "v1.0.1")
          assert(value.provenance.initialUrl == "https://example.invalid/stable.txt")
          assert(value.provenance.finalUrl == "https://mirror.example.invalid/releases/stable.txt")
          assert(value.provenance.redirects.map(_.statusCode) == Vector(302, 301))
          assert(value.provenance.redirects.map(_.from) ==
            Vector(
              "https://example.invalid/stable.txt",
              "https://cdn.example.invalid/releases/stable.txt"
            ))
          assert(value.provenance.redirects.map(_.to) ==
            Vector(
              "https://cdn.example.invalid/releases/stable.txt",
              "https://mirror.example.invalid/releases/stable.txt"
            ))
        case Left(error) => abort(s"expected text response, got $error")

    test("dynamic latest-url remains dynamic without a concrete version"):
      val plan = resolve(dynamicLatestUrlYaml)

      val tool = onlyTool(plan)
      assert(tool.version == ResolvedVersion.DynamicLatestUrl(Some("upstream latest endpoint")))
      assert(ResolvedVersion.render(tool.version) == "dynamic latest-url")
      assert(tool.download.url == "https://example.invalid/latest/download/beta")

    test("strict policy rejects dynamic versions missing checksums and tar.xz fallback"):
      val errors = resolveErrors(strictPolicyYaml())

      assert(errors.exists(error =>
        error.path == "spec.versions.alpha.dynamic.type" &&
          error.message.contains("strict-policy[dynamic-latest-url]") &&
          error.message.contains("suggestion[dynamic-latest-url]")
      ))
      assert(errors.exists(error =>
        error.path == "spec.plan[0].spec.download.checksum" &&
          error.message.contains("strict-policy[missing-checksum]") &&
          error.message.contains("suggestion[missing-checksum]")
      ))
      assert(errors.exists(error =>
        error.path == "spec.plan[0].spec.download.archive.type" &&
          error.message.contains("strict-policy[tar-xz-fallback]") &&
          error.message.contains("suggestion[tar-xz-fallback]")
      ))

    test("strict policy permits risky behavior only through explicit overrides"):
      val plan = resolve(strictPolicyYaml(
        """allowDynamicLatestUrls: true
          |    allowMissingChecksums: true
          |    allowTarXzFallback: true""".stripMargin
      ))

      val tool = onlyTool(plan)
      assert(plan.policy.mode == binstaller.config.PolicyMode.Strict)
      assert(plan.policy.allowDynamicLatestUrls == PolicyAllowance.Allowed)
      assert(plan.policy.allowMissingChecksums == PolicyAllowance.Allowed)
      assert(plan.policy.allowTarXzFallback == PolicyAllowance.Allowed)
      assert(tool.download.archive.exists(_.original.archiveType == ArchiveType.TarXz))

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
      assert(tool.download.url == "https://example.invalid/alpha")

    test("non-https version and download URLs fail resolution"):
      val errors = resolveErrors(insecureUrlYaml)

      assert(errors.exists(error =>
        error.path == "spec.versions.alpha.resolver.url" &&
          error.message.contains("URL must use https")
      ))
      assert(errors.exists(error =>
        error.path == "spec.plan[0].spec.download.url" &&
          error.message.contains("URL must use https")
      ))

    test("install directories must stay inside appsDir and not overlap"):
      val errors = resolveErrors(unsafeInstallDirYaml)

      assert(errors.exists(error =>
        error.path == "spec.plan[0].spec.installDir" &&
          error.message.contains("inside spec.policy.appsDir")
      ))
      assert(errors.exists(error =>
        error.path == "spec.plan[2].spec.installDir" &&
          error.message.contains("nested inside tool 'beta'")
      ))

    test("interpolated path fields are revalidated after variables resolve"):
      val errors = resolveErrors(unsafeInterpolatedPathsYaml)

      assert(errors.exists(errorAt("spec.policy.stateFile")))
      assert(errors.exists(errorAt("spec.plan[0].spec.createDirectories[0]")))
      assert(errors.exists(errorAt("spec.plan[0].spec.download.filename")))
      assert(errors.exists(errorAt("spec.plan[0].spec.download.archive.extract.files[0].to")))
      assert(errors.exists(errorAt("spec.plan[0].spec.executables[0].path")))
      assert(errors.exists(errorAt("spec.plan[0].spec.symlinks[0].path")))
      assert(errors.exists(errorAt("spec.plan[0].spec.symlinks[0].target")))
      assert(errors.exists(errorAt("spec.plan[1].spec.installDir")))
      assert(errors.exists(errorAt("spec.plan[2].spec.installDir")))
      assert(errors.exists(_.message.contains("control")))
      assert(errors.exists(_.message.contains("traversal")))
      assert(errors.exists(_.message.contains("relative")))
      assert(errors.exists(_.message.contains("inside spec.policy.appsDir")))

    test("example config resolves expected install directories under appsDir"):
      val plan = resolveExampleConfig(FakeHttpTextClient("v1.33.0"))

      assert(plan.policy.appsDir == "/home/test/.apps")
      assert(plan.tools.map(tool => tool.name -> tool.installDir) ==
        exampleToolNames.map(name => name -> s"/home/test/.apps/$name"))
      assert(plan.tools.forall(_.installDir.startsWith(s"${plan.policy.appsDir}/")))

    test("direct binary install writes download bytes to first executable path"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-direct")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes),
        InstallFileSystem.nio
      )

      val result = installer.installTool(directTool(installDir))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "alpha-binary")

    test("sha256 mismatch fails before replacing an existing install"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-checksum")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val tool = directTool(
        installDir,
        checksum = Some(ResolvedChecksum(
          ChecksumAlgorithm.Sha256,
          "0" * 64,
          ResolvedChecksumSource.Configured
        ))
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
      val fileSystem = RecordingInstallFileSystem(stagedFiles = Vector("bin/alpha", "bin/helper"))
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

      assertInstallSuccess(result, "/tmp/alpha")
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

    test("JDK binary download client records direct no-redirect provenance"):
      val response = FakeHttpResponse[ByteArrayInputStream](
        responseUri = "https://example.invalid/alpha",
        responseStatusCode = 200,
        responseBody = ByteArrayInputStream("alpha".getBytes(StandardCharsets.UTF_8)),
        responseHeaders = Map("Content-Length" -> Vector("5"))
      )
      val client = JdkBinaryDownloadClient(StaticHttpClient(response))

      val result = client.downloadWithProvenance("https://example.invalid/alpha")

      result match
        case Right(value) =>
          assert(String(value.bytes, StandardCharsets.UTF_8) == "alpha")
          assert(value.provenance == UrlProvenance.direct("https://example.invalid/alpha"))
        case Left(error) => abort(s"expected binary response, got $error")

    test("JDK binary download client records redirects and emits final URL progress"):
      val first = FakeHttpResponse[ByteArrayInputStream](
        responseUri = "https://example.invalid/alpha",
        responseStatusCode = 302,
        responseBody = ByteArrayInputStream(Array.emptyByteArray)
      )
      val finalResponse = FakeHttpResponse[ByteArrayInputStream](
        responseUri = "https://cdn.example.invalid/alpha",
        responseStatusCode = 200,
        responseBody = ByteArrayInputStream("alpha".getBytes(StandardCharsets.UTF_8)),
        previous = Some(first),
        responseHeaders = Map("Content-Length" -> Vector("5"))
      )
      val progress = RecordingBinaryDownloadProgressObserver()
      val client   = JdkBinaryDownloadClient(StaticHttpClient(finalResponse))

      val result = client.downloadWithProvenance("https://example.invalid/alpha", progress)

      result match
        case Right(value) =>
          assert(String(value.bytes, StandardCharsets.UTF_8) == "alpha")
          assert(value.provenance.initialUrl == "https://example.invalid/alpha")
          assert(value.provenance.finalUrl == "https://cdn.example.invalid/alpha")
          assert(value.provenance.redirects ==
            Vector(UrlRedirectHop(
              "https://example.invalid/alpha",
              "https://cdn.example.invalid/alpha",
              302
            )))
          assert(progress.urls.distinct == Vector("https://cdn.example.invalid/alpha"))
        case Left(error) => abort(s"expected binary response, got $error")

    test("bounded body reader rejects oversized content length before buffering"):
      val result = BoundedBinaryBodyReader.read(
        "https://example.invalid/alpha",
        ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)),
        Some(11L),
        BinaryDownloadLimits(maxBytes = 10L, bodyTimeout = Duration.ofSeconds(5)),
        BinaryDownloadProgressObserver.none
      )

      assert(result.left.exists(_.message.contains("exceeds max allowed 10 bytes")))

    test("bounded body reader stops downloads that exceed max size without content length"):
      val result = BoundedBinaryBodyReader.read(
        "https://example.invalid/alpha",
        ByteArrayInputStream("oversized-body".getBytes(StandardCharsets.UTF_8)),
        None,
        BinaryDownloadLimits(maxBytes = 4L, bodyTimeout = Duration.ofSeconds(5)),
        BinaryDownloadProgressObserver.none
      )

      assert(result.left.exists(_.message.contains("exceeds max allowed 4 bytes")))

    test("bounded body reader fails when body read exceeds deadline"):
      var now    = 0L
      val result = BoundedBinaryBodyReader.read(
        "https://example.invalid/alpha",
        ByteArrayInputStream("abcdef".getBytes(StandardCharsets.UTF_8)),
        None,
        BinaryDownloadLimits(maxBytes = 1024L, bodyTimeout = Duration.ofNanos(1)),
        BinaryDownloadProgressObserver.none,
        nowNanos = () =>
          now = now + 2L
          now
      )

      assert(result.left.exists(_.message.contains("download body timed out")))

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
          verboseOutput = VerboseOutput.Disabled,
          applyConfirmation = ApplyConfirmation.Enabled
        )
      )

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("failed alpha: download:")))
      assert(result.lines.exists(_.contains("network unavailable")))
      assert(!result.lines.exists(_.contains("Exception")))
      assert(!result.lines.exists(_.contains("at binstaller.")))

    test("invalid config reports every aggregated validation error concisely"):
      val tempRoot = Files.createTempDirectory("binstaller-core-invalid-config")
      val config   = writeConfig(tempRoot, invalidConfigYaml(tempRoot))
      val service  = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result = service.plan(applyOptions(config))

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.startsWith("apiVersion: unsupported value")))
      assert(result.lines.exists(_.startsWith("kind: unsupported value")))
      assert(
        result.lines.exists(_.startsWith("spec.policy.continueOnError: value must be a boolean"))
      )
      assert(!result.lines.exists(_.contains("ValidationFailed")))
      assert(!result.lines.exists(_.contains("Exception")))

    test("versions output includes package version summary table"):
      val service = BinaryInstallerService.resolving(FakeHttpTextClient("v1.34.0"))
      val result  = service.versions(
        applyOptions(exampleConfigPath).copy(applyConfirmation = ApplyConfirmation.Disabled)
      )

      assert(result.exitCode == 0)
      assert(result.lines.exists(line =>
        line.startsWith("package") && line.endsWith("newer version")
      ))
      assert(versionSummaryRowExists(result.lines, "yazi", "v26.5.6", "-"))
      assert(versionSummaryRowExists(result.lines, "helm", "v3.21.2", "-"))
      assert(versionSummaryRowExists(result.lines, "kustomize", "v5.8.1", "-"))
      assert(versionSummaryRowExists(result.lines, "kubectl", "v1.34.0", "-"))
      assert(versionSummaryRowExists(result.lines, "minikube", "dynamic latest-url", "-"))
      assert(!result.lines.exists(_.contains("https://")))
      assert(!result.lines.exists(_.contains("final url")))

    test("versions output reports newer GitHub release for pinned downloads"):
      val tempRoot = Files.createTempDirectory("binstaller-core-github-latest")
      val config   = writeConfig(tempRoot, githubReleaseYaml(tempRoot, "0.40.0"))
      val service  = BinaryInstallerService.resolving(
        RoutingHttpTextClient(Map(
          "https://api.github.com/repos/jj-vcs/jj/releases/latest" -> Right(HttpTextResponse(
            """{"tag_name":"v0.41.0"}""",
            UrlProvenance.direct("https://api.github.com/repos/jj-vcs/jj/releases/latest")
          ))
        ))
      )

      val result = service.versions(applyOptions(config))

      assert(result.exitCode == 0)
      assert(versionSummaryRowExists(result.lines, "jujutsu", "0.40.0", "v0.41.0"))
      assert(!result.lines.exists(_.contains("github:")))

    test("versions output omits unavailable GitHub release metadata without failing"):
      val tempRoot = Files.createTempDirectory("binstaller-core-github-unavailable")
      val config   = writeConfig(tempRoot, githubReleaseYaml(tempRoot, "0.40.0"))
      val service  = BinaryInstallerService.resolving(
        RoutingHttpTextClient(Map(
          "https://api.github.com/repos/jj-vcs/jj/releases/latest" ->
            Left(HttpTextError(
              "https://api.github.com/repos/jj-vcs/jj/releases/latest",
              "HTTP 403"
            ))
        ))
      )

      val result = service.versions(applyOptions(config))

      assert(result.exitCode == 0)
      assert(versionSummaryRowExists(result.lines, "jujutsu", "0.40.0", "-"))
      assert(!result.lines.exists(_.contains("HTTP 403")))

    test("lock writes pinned http-text and dynamic source metadata without apply state"):
      val tempRoot = Files.createTempDirectory("binstaller-core-lock")
      val config   = writeConfig(tempRoot, lockYaml(tempRoot))
      val lockPath = tempRoot.resolve("resolved.lock.json")
      val service  = BinaryInstallerService.resolving(
        LockHttpTextClient(
          "2.0.0",
          UrlProvenance(
            "https://example.invalid/beta-version",
            "https://cdn.example.invalid/beta-version",
            Vector(UrlRedirectHop(
              "https://example.invalid/beta-version",
              "https://cdn.example.invalid/beta-version",
              302
            ))
          )
        ),
        DirectBinaryInstaller(
          FakeBinaryDownloadClient.failure("lock must not download"),
          InstallFileSystem.nio
        ),
        ApplyStateStore.nio(tempRoot),
        RoutingBinaryMetadataClient(Map(
          "https://example.invalid/alpha-1.0.0" -> BinaryMetadata(
            Some(11L),
            UrlProvenance.direct("https://example.invalid/alpha-1.0.0")
          ),
          "https://example.invalid/beta-2.0.0" -> BinaryMetadata(
            Some(22L),
            UrlProvenance(
              "https://example.invalid/beta-2.0.0",
              "https://cdn.example.invalid/beta-2.0.0",
              Vector(UrlRedirectHop(
                "https://example.invalid/beta-2.0.0",
                "https://cdn.example.invalid/beta-2.0.0",
                301
              ))
            )
          ),
          "https://example.invalid/latest/gamma" -> BinaryMetadata(
            None,
            UrlProvenance.direct("https://example.invalid/latest/gamma")
          )
        )),
        LockFileStore.nio
      )

      val result = service.lock(applyOptions(config), LockOptions(lockPath.toString))
      val lock   = read[LockFile](Files.readString(lockPath))
      val tools  = lock.tools.map(tool => tool.name -> tool).toMap

      assert(result.exitCode == 0)
      assert(result.lines.exists(_.contains("wrote lock file")))
      assert(lock.schemaVersion == LockFile.schemaVersion)
      assert(lock.profileName == "lock-profile")
      assert(lock.manifestFingerprint.nonEmpty)
      assert(lock.tools.map(_.name) == Vector("alpha", "beta", "gamma"))
      assert(tools("alpha").resolvedVersion.contains("1.0.0"))
      assert(tools("alpha").versionProvenance.isEmpty)
      assert(tools("alpha").downloadProvenance.finalUrl == "https://example.invalid/alpha-1.0.0")
      assert(tools("alpha").sizeBytes.contains(11L))
      assert(tools("alpha").checksum.contains(LockFileChecksum("sha256", "a" * 64)))
      assert(!tools("alpha").dynamicSource)
      assert(tools("beta").resolvedVersion.contains("2.0.0"))
      assert(tools("beta").versionProvenance.exists(_.finalUrl ==
        "https://cdn.example.invalid/beta-version"))
      assert(tools("beta").downloadProvenance.finalUrl == "https://cdn.example.invalid/beta-2.0.0")
      assert(tools("beta").sizeBytes.contains(22L))
      assert(!tools("beta").dynamicSource)
      assert(tools("gamma").resolvedVersion.isEmpty)
      assert(tools("gamma").versionProvenance.isEmpty)
      assert(tools("gamma").sizeBytes.isEmpty)
      assert(tools("gamma").checksum.isEmpty)
      assert(tools("gamma").dynamicSource)
      assert(!Files.exists(tempRoot.resolve("lock.state.json")))
      assert(!Files.exists(tempRoot.resolve("apps")))

    test("discovered checksum succeeds and is visible in plan versions and lock output"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-checksum-discovered")
      val artifactBytes   = "alpha-binary".getBytes(StandardCharsets.UTF_8)
      val artifactHash    = sha256(artifactBytes)
      val checksumFileUrl = "https://example.invalid/releases/1.0.0/SHA256SUMS"
      val config          = writeConfig(tempRoot, checksumDiscoveryYaml(tempRoot, checksumFileUrl))
      val lockPath        = tempRoot.resolve("checksum.lock.json")
      val service         = BinaryInstallerService.resolving(
        RoutingHttpTextClient(Map(
          checksumFileUrl -> Right(HttpTextResponse(
            s"$artifactHash  alpha-1.0.0.tar.gz\n",
            UrlProvenance.direct(checksumFileUrl)
          ))
        )),
        DirectBinaryInstaller(
          RoutingBinaryDownloadClient(Map(
            "https://example.invalid/releases/1.0.0/alpha-1.0.0.tar.gz" -> Right(artifactBytes)
          )),
          InstallFileSystem.nio
        ),
        ApplyStateStore.nio(tempRoot),
        RoutingBinaryMetadataClient(Map(
          "https://example.invalid/releases/1.0.0/alpha-1.0.0.tar.gz" -> BinaryMetadata(
            Some(artifactBytes.length.toLong),
            UrlProvenance.direct("https://example.invalid/releases/1.0.0/alpha-1.0.0.tar.gz")
          )
        )),
        LockFileStore.nio
      )

      val planResult     = service.plan(applyOptions(config))
      val versionsResult = service.versions(applyOptions(config))
      val applyResult    = service.apply(applyOptions(config))
      val lockResult     = service.lock(applyOptions(config), LockOptions(lockPath.toString))
      val lock           = read[LockFile](Files.readString(lockPath))

      assert(planResult.exitCode == 0)
      assert(planResult.lines.exists(_.contains(s"checksum: sha256 $artifactHash (discovered")))
      assert(planResult.lines.exists(_.contains(checksumFileUrl)))
      assert(versionsResult.exitCode == 0)
      assert(versionSummaryRowExists(versionsResult.lines, "alpha", "1.0.0", "-"))
      assert(!versionsResult.lines.exists(_.contains("checksums:")))
      assert(applyResult.exitCode == 0)
      assert(Files.readString(tempRoot.resolve("apps/alpha/bin/alpha")) == "alpha-binary")
      assert(lockResult.exitCode == 0)
      assert(lockResult.lines.exists(_.contains(
        "checksums: configured 0, discovered 1, missing 0"
      )))
      assert(lock.tools.head.checksum.exists(checksum =>
        checksum.source == "discovered" &&
          checksum.discoveryUrl.contains(checksumFileUrl) &&
          checksum.discoveryFile.contains("alpha-1.0.0.tar.gz")
      ))

    test("missing checksum file fails resolution with a typed diagnostic"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-checksum-missing-file")
      val checksumFileUrl = "https://example.invalid/releases/1.0.0/SHA256SUMS"
      val config          = writeConfig(tempRoot, checksumDiscoveryYaml(tempRoot, checksumFileUrl))
      val service         = BinaryInstallerService.resolving(
        RoutingHttpTextClient(Map(
          checksumFileUrl -> Left(HttpTextError(checksumFileUrl, "HTTP 404"))
        ))
      )

      val result = service.plan(applyOptions(config))

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("checksum discovery failed: HTTP 404")))
      assert(result.lines.exists(_.contains("spec.plan[0].spec.download.checksum.discover.url")))

    test("mismatched discovered checksum fails before replacement"):
      val tempRoot = Files.createTempDirectory("binstaller-core-checksum-discovered-mismatch")
      val checksumFileUrl = "https://example.invalid/releases/1.0.0/SHA256SUMS"
      val config          = writeConfig(tempRoot, checksumDiscoveryYaml(tempRoot, checksumFileUrl))
      val existingFile    = tempRoot.resolve("apps/alpha/bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val service = BinaryInstallerService.resolving(
        RoutingHttpTextClient(Map(
          checksumFileUrl -> Right(HttpTextResponse(
            s"${"0" * 64}  alpha-1.0.0.tar.gz\n",
            UrlProvenance.direct(checksumFileUrl)
          ))
        )),
        DirectBinaryInstaller(
          RoutingBinaryDownloadClient(Map(
            "https://example.invalid/releases/1.0.0/alpha-1.0.0.tar.gz" ->
              Right("replacement".getBytes(StandardCharsets.UTF_8))
          )),
          InstallFileSystem.nio
        ),
        ApplyStateStore.nio(tempRoot)
      )

      val result = service.apply(applyOptions(config))
      val output = result.lines.mkString("\n")

      assert(result.exitCode == 1)
      assert(output.contains("checksum: sha256 expected"))
      assert(output.contains("checksum source: discovered"))
      assert(output.contains(checksumFileUrl))
      assert(Files.readString(existingFile) == "existing")

    test("locked plan validates lock and renders locked provenance without writes"):
      val tempRoot = Files.createTempDirectory("binstaller-core-locked-plan")
      val config   = writeConfig(tempRoot, lockYaml(tempRoot))
      val lockPath = tempRoot.resolve("binstaller.lock.json")
      writeLock(lockPath, currentLockFile(config, dynamicSize = Some(33L)))
      val service = lockedApplyService(
        tempRoot,
        dynamicSize = Some(33L),
        installer = DirectBinaryInstaller(
          FakeBinaryDownloadClient.failure("locked plan must not download"),
          InstallFileSystem.nio
        )
      )

      val result = service.plan(
        applyOptions(config).copy(
          lockPath = lockPath.toString,
          lockedApply = LockedApplyMode.Enabled
        )
      )

      assert(result.exitCode == 0)
      assert(result.lines.exists(_.startsWith("lock file: ")))
      assert(result.lines.exists(_.contains("(validated)")))
      assert(result.lines.exists(
        _.contains("locked download final url: https://cdn.example.invalid/beta-2.0.0")
      ))
      assert(result.lines.exists(
        _.contains("locked version final url: https://cdn.example.invalid/beta-version")
      ))
      assert(!Files.exists(tempRoot.resolve("apps")))
      assert(!Files.exists(tempRoot.resolve("lock.state.json")))

    test("locked apply rejects stale manifest fingerprint before install"):
      val tempRoot  = Files.createTempDirectory("binstaller-core-locked-stale")
      val config    = writeConfig(tempRoot, lockYaml(tempRoot))
      val lockPath  = tempRoot.resolve("binstaller.lock.json")
      val staleLock = currentLockFile(config, dynamicSize = Some(33L)).copy(
        manifestFingerprint = "stale-fingerprint"
      )
      writeLock(lockPath, staleLock)
      val service = lockedApplyService(tempRoot, dynamicSize = Some(33L))

      val result = service.apply(
        applyOptions(config).copy(
          applyConfirmation = ApplyConfirmation.Enabled,
          lockPath = lockPath.toString,
          lockedApply = LockedApplyMode.Enabled
        )
      )

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("manifest fingerprint changed")))
      assert(!Files.exists(tempRoot.resolve("apps/alpha")))
      assert(!Files.exists(tempRoot.resolve("lock.state.json")))

    test("locked apply rejects download provenance drift before install"):
      val tempRoot  = Files.createTempDirectory("binstaller-core-locked-url-drift")
      val config    = writeConfig(tempRoot, lockYaml(tempRoot))
      val lockPath  = tempRoot.resolve("binstaller.lock.json")
      val staleBeta = currentLockFile(config, dynamicSize = Some(33L)).tools.map:
        case tool if tool.name == "beta" =>
          tool.copy(downloadProvenance =
            UrlProvenance(
              "https://example.invalid/beta-2.0.0",
              "https://old-cdn.example.invalid/beta-2.0.0",
              Vector(UrlRedirectHop(
                "https://example.invalid/beta-2.0.0",
                "https://old-cdn.example.invalid/beta-2.0.0",
                301
              ))
            )
          )
        case tool => tool
      writeLock(lockPath, currentLockFile(config, dynamicSize = Some(33L)).copy(tools = staleBeta))
      val service = lockedApplyService(tempRoot, dynamicSize = Some(33L))

      val result = service.apply(
        applyOptions(config).copy(
          applyConfirmation = ApplyConfirmation.Enabled,
          lockPath = lockPath.toString,
          lockedApply = LockedApplyMode.Enabled
        )
      )

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("download provenance changed")))
      assert(!Files.exists(tempRoot.resolve("apps/beta")))
      assert(!Files.exists(tempRoot.resolve("lock.state.json")))

    test("locked apply rejects missing dynamic lock data"):
      val tempRoot = Files.createTempDirectory("binstaller-core-locked-dynamic-missing")
      val config   = writeConfig(tempRoot, lockYaml(tempRoot))
      val lockPath = tempRoot.resolve("binstaller.lock.json")
      writeLock(lockPath, currentLockFile(config, dynamicSize = None))
      val service = lockedApplyService(tempRoot, dynamicSize = Some(33L))

      val result = service.plan(
        applyOptions(config).copy(
          lockPath = lockPath.toString,
          lockedApply = LockedApplyMode.Enabled
        )
      )

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("incomplete dynamic lock data")))
      assert(!Files.exists(tempRoot.resolve("apps")))
      assert(!Files.exists(tempRoot.resolve("lock.state.json")))

    test("locked apply rejects missing lock before install"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-locked-missing")
      val installDir = tempRoot.resolve("alpha")
      val config     = writeConfig(tempRoot, directBinaryYaml(installDir))
      val lockPath   = tempRoot.resolve("missing.lock.json")
      val service    = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result = service.apply(
        applyOptions(config).copy(
          applyConfirmation = ApplyConfirmation.Enabled,
          lockPath = lockPath.toString,
          lockedApply = LockedApplyMode.Enabled
        )
      )

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("is missing")))
      assert(!Files.exists(installDir))

    test("apply requires yes when policy requireConfirmation is true"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-confirm")
      val installDir = tempRoot.resolve("alpha")
      val config     = writeConfig(tempRoot, directBinaryYaml(installDir))
      val service    = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result =
        service.apply(applyOptions(config).copy(applyConfirmation = ApplyConfirmation.Disabled))

      assert(result.exitCode == 1)
      assert(result.lines == Vector(
        "apply requires confirmation by policy.requireConfirmation; rerun apply with --yes"
      ))
      assert(!Files.exists(installDir))

    test("continueOnError false stops apply after the first failed tool"):
      val tempRoot = Files.createTempDirectory("binstaller-core-stop-on-error")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "stop.state.json"))
      val service  = statefulService(
        tempRoot,
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Left("network unavailable"),
          "https://example.invalid/beta"  -> Right("beta".getBytes(StandardCharsets.UTF_8))
        ))
      )

      val result = service.apply(applyOptions(config))

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.startsWith("failed alpha: download:")))
      assert(!result.lines.exists(_.contains("installed beta")))
      assert(!Files.exists(tempRoot.resolve("apps/beta")))

    test("continueOnError true continues apply after failed tools"):
      val tempRoot = Files.createTempDirectory("binstaller-core-continue-on-error")
      val config   = writeConfig(
        tempRoot,
        twoToolYaml(tempRoot, "continue.state.json", continueOnError = true)
      )
      val service = statefulService(
        tempRoot,
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Left("network unavailable"),
          "https://example.invalid/beta"  -> Right("beta".getBytes(StandardCharsets.UTF_8))
        ))
      )

      val result = service.apply(applyOptions(config))

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.startsWith("failed alpha: download:")))
      assert(result.lines.exists(_.contains("installed beta")))
      assert(Files.isRegularFile(tempRoot.resolve("apps/beta/bin/beta")))

    test("zip archive file mapping lands at configured relative target path"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-zip")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(zipArchive(Vector("pkg/alpha" -> "zip-alpha"))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.Zip,
        files = Vector("pkg/alpha" -> "bin/alpha")
      ))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "zip-alpha")

    test("tar.gz archive file mapping lands at configured relative target path"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-targz")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(tarGzArchive(Vector("pkg/alpha" -> "tar-alpha"))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        files = Vector("pkg/alpha" -> "bin/alpha")
      ))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "tar-alpha")

    test("tar.gz directory mapping moves extracted root directory into install root"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-targz-dir")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(tarGzArchive(Vector(
          "alpha-root/bin/alpha"    -> "alpha",
          "alpha-root/share/readme" -> "docs"
        ))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        directories = Vector("alpha-root" -> ".")
      ))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/alpha")) == "alpha")
      assert(Files.readString(installDir.resolve("share/readme")) == "docs")

    test("tar.gz root directory entries do not fail extraction"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-targz-root-dir")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(tarGzArchiveWithDirectories(
          directories = Vector("./"),
          files = Vector("./jj" -> "jujutsu")
        )),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        files = Vector("jj" -> "bin/jj"),
        executable = "bin/jj"
      ))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/jj")) == "jujutsu")

    test("archive entries that escape staging are rejected and preserve existing install"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-zip-slip")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(zipArchive(Vector("../evil" -> "bad"))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.Zip,
        files = Vector("../evil" -> "bin/alpha")
      ))

      assert(result.left.exists(_.isInstanceOf[ToolInstallError.ArchiveExtractionFailed]))
      assert(Files.readString(existingFile) == "existing")

    test("duplicate zip archive members are rejected before replacement"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-zip-duplicate")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(zipArchiveWithDuplicateLocalEntries(Vector(
          "pkg/alpha" -> "first",
          "pkg/alpha" -> "second"
        ))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.Zip,
        files = Vector("pkg/alpha" -> "bin/alpha")
      ))

      assert(result.left.exists:
        case ToolInstallError.ArchiveExtractionFailed(_, message) =>
          message.contains("duplicate archive member")
        case _ => false)
      assert(Files.readString(existingFile) == "existing")

    test("tar.gz hardlink metadata is rejected before replacement"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-targz-hardlink")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success(tarGzArchiveWithEntryTypes(Vector(
          ("pkg/alpha", "", '1')
        ))),
        InstallFileSystem.nio
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarGz,
        files = Vector("pkg/alpha" -> "bin/alpha")
      ))

      assert(result.left.exists:
        case ToolInstallError.ArchiveExtractionFailed(_, message) =>
          message.contains("unsafe archive link entry")
        case _ => false)
      assert(Files.readString(existingFile) == "existing")

    test("tar.xz extraction runs through a structured command spec"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-tarxz")
      val installDir      = tempRoot.resolve("zig")
      val commandExecutor = FakeArchiveCommandExecutor("zig-root/bin/zig", "zig")
      val installer       = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("tar-xz-bytes".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor
      )

      val result = installer.installTool(archiveTool(
        installDir,
        ArchiveType.TarXz,
        directories = Vector("zig-root" -> "."),
        executable = "bin/zig"
      ))

      assertInstallSuccess(result, installDir.toString)
      assert(Files.readString(installDir.resolve("bin/zig")) == "zig")
      assert(commandExecutor.commands.map(_.argv.take(2)) == Vector(Vector("tar", "-xJf")))
      assert(commandExecutor.commands.exists(_.argv.contains("-C")))

    test("process command executor times out long-running commands"):
      val tempRoot = Files.createTempDirectory("binstaller-core-process-timeout")
      val executor = CommandExecutor.processWithTimeout(Duration.ofMillis(100))

      val result = executor.run(CommandSpec(
        Vector("sh", "-c", "sleep 2"),
        tempRoot,
        Map("PATH" -> sys.env.getOrElse("PATH", "/usr/bin:/bin"))
      ))

      assert(result.left.exists(_.message.contains("timed out")))

    test("process command executor captures stdout and stderr on failure"):
      val tempRoot = Files.createTempDirectory("binstaller-core-process-output")
      val executor = CommandExecutor.processWithTimeout(Duration.ofSeconds(5))

      val result = executor.run(CommandSpec(
        Vector("sh", "-c", "printf 'stdout-line\\n'; printf 'stderr-line\\n' >&2; exit 7"),
        tempRoot,
        Map("PATH" -> sys.env.getOrElse("PATH", "/usr/bin:/bin"))
      ))

      result match
        case Left(error) =>
          assert(error.exitCode.contains(7))
          assert(error.output.stdout.contains("stdout-line"))
          assert(error.output.stderr.contains("stderr-line"))
        case Right(()) => abort("expected command failure")

    test("apply errors redact sensitive runtime values and scrub terminal controls"):
      val secret = "secret-token-value"
      val plan   = ResolvedPlan(
        ResolvedPolicy(
          "/tmp/apps",
          None,
          AllowSudoSymlinks.Disabled,
          RequireConfirmation.Disabled,
          ContinueOnError.Disabled
        ),
        Vector(directTool(Path.of("/tmp/apps/alpha")).copy(download =
          ResolvedDownload(
            url = s"https://example.invalid/$secret/alpha",
            filename = "alpha",
            checksum = None,
            archive = None
          )
        )),
        SensitiveValueRedactions(Vector(secret))
      )
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.failure(s"network \u001b[31m failure for $secret"),
        InstallFileSystem.nio
      )

      val result = installer.installPlan(plan, ApplyConfirmation.Enabled)
      val output = result.lines.mkString("\n")

      assert(result.exitCode == 1)
      assert(!output.contains(secret))
      assert(!output.contains("\u001b"))
      assert(output.contains("<redacted>"))

    test("checksum mismatch diagnostics redact discovered checksum source"):
      val secret = "secret-token-value"
      val plan   = ResolvedPlan(
        ResolvedPolicy(
          "/tmp/apps",
          None,
          AllowSudoSymlinks.Disabled,
          RequireConfirmation.Disabled,
          ContinueOnError.Disabled
        ),
        Vector(directTool(Path.of("/tmp/apps/alpha")).copy(download =
          ResolvedDownload(
            url = "https://example.invalid/alpha",
            filename = "alpha",
            checksum = Some(ResolvedChecksum(
              ChecksumAlgorithm.Sha256,
              "0" * 64,
              ResolvedChecksumSource.Discovered(
                s"https://example.invalid/$secret/SHA256SUMS",
                "alpha",
                UrlProvenance.direct(s"https://example.invalid/$secret/SHA256SUMS")
              )
            )),
            archive = None
          )
        )),
        SensitiveValueRedactions(Vector(secret))
      )
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("replacement".getBytes(StandardCharsets.UTF_8)),
        RecordingInstallFileSystem(stagedFiles = Vector("bin/alpha"))
      )

      val result = installer.installPlan(plan, ApplyConfirmation.Enabled)
      val output = result.lines.mkString("\n")

      assert(result.exitCode == 1)
      assert(output.contains(
        "checksum source: discovered from https://example.invalid/<redacted>/SHA256SUMS"
      ))
      assert(!output.contains(secret))

    test("apply output and state record redirected download provenance"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-download-redirect-state")
      val config     = writeConfig(tempRoot, directBinaryYaml(tempRoot.resolve("alpha")))
      val stateStore = RecordingApplyStateStore(ApplyStateStore.nio(tempRoot))
      val download   = UrlProvenance(
        "https://example.invalid/alpha",
        "https://cdn.example.invalid/alpha",
        Vector(UrlRedirectHop(
          "https://example.invalid/alpha",
          "https://cdn.example.invalid/alpha",
          302
        ))
      )
      val service = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(RedirectingBinaryDownloadClient(download), InstallFileSystem.nio),
        stateStore
      )

      val result = service.apply(applyOptions(config).copy(statePath = Some("redirect.state.json")))

      assert(result.exitCode == 0)
      assert(result.lines.exists(_ == "download final url: https://cdn.example.invalid/alpha"))
      assert(result.lines.exists(_.contains(
        "download redirects: 302 https://example.invalid/alpha -> https://cdn.example.invalid/alpha"
      )))
      assert(stateStore.savedStates.last.tools.head.download.contains(download))

    test("apply output redacts sensitive redirected URLs"):
      val secret   = "secret-token-value"
      val download = UrlProvenance(
        "https://example.invalid/alpha",
        s"https://cdn.example.invalid/$secret/alpha",
        Vector(UrlRedirectHop(
          "https://example.invalid/alpha",
          s"https://cdn.example.invalid/$secret/alpha",
          302
        ))
      )
      val plan = ResolvedPlan(
        ResolvedPolicy(
          "/tmp/apps",
          None,
          AllowSudoSymlinks.Disabled,
          RequireConfirmation.Disabled,
          ContinueOnError.Disabled
        ),
        Vector(directTool(Path.of("/tmp/apps/alpha"))),
        SensitiveValueRedactions(Vector(secret))
      )
      val installer = DirectBinaryInstaller(
        RedirectingBinaryDownloadClient(download),
        RecordingInstallFileSystem(stagedFiles = Vector("bin/alpha"))
      )

      val result = installer.installPlan(plan, ApplyConfirmation.Enabled)
      val output = result.lines.mkString("\n")

      assert(result.exitCode == 0)
      assert(output.contains("download final url: https://cdn.example.invalid/<redacted>/alpha"))
      assert(!output.contains(secret))
      assert(output.contains("<redacted>"))

    test("failed replacement restores previous install directory"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-rollback")
      val installDir   = tempRoot.resolve("alpha")
      val existingFile = installDir.resolve("bin/alpha")
      Files.createDirectories(existingFile.getParent)
      Files.writeString(existingFile, "existing")
      val missingStaging = tempRoot.resolve("missing-stage")

      val result = InstallFileSystem.nio.replaceInstall(StagedInstall(missingStaging, installDir))

      assert(result.isLeft)
      assert(Files.readString(existingFile) == "existing")
      assert(!Using.resource(Files.list(tempRoot)): stream =>
        stream.iterator().asScala.exists(_.getFileName.toString.contains(".backup-")))

    test("direct install verifies expected executables"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-direct-missing")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio
      )

      val result = installer.installTool(directTool(
        installDir,
        executables = Vector(
          ResolvedExecutable("bin/alpha", None),
          ResolvedExecutable("bin/missing", None)
        )
      ))

      assert(result == Left(ToolInstallError.MissingExecutable("alpha", "bin/missing")))

    test("local symlinks are created under installDir with targets resolved from installDir"):
      val tempRoot   = Files.createTempDirectory("binstaller-core-local-symlink")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio
      )
      val tool = directTool(
        installDir,
        symlinks = Vector(ResolvedSymlink("bin/a", "bin/alpha", SymlinkPrivilege.User))
      )

      val result = installer.installTool(tool)

      assertInstallSuccess(result, installDir.toString)
      assert(Files.isSymbolicLink(installDir.resolve("bin/a")))
      assert(Files.readSymbolicLink(installDir.resolve("bin/a")) ==
        installDir.toAbsolutePath.normalize().resolve("bin/alpha"))

    test("sudo symlink apply requires policy and apply confirmation before writes"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-sudo-gate")
      val installDir      = tempRoot.resolve("alpha")
      val commandExecutor = RecordingCommandExecutor()
      val installer       = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor
      )
      val plan = ResolvedPlan(
        ResolvedPolicy(
          tempRoot.toString,
          None,
          AllowSudoSymlinks.Enabled,
          RequireConfirmation.Disabled,
          ContinueOnError.Disabled
        ),
        Vector(sudoSymlinkTool(installDir))
      )

      val result = installer.installPlan(plan, ApplyConfirmation.Disabled)

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("--yes")))
      assert(commandExecutor.commands.isEmpty)
      assert(!Files.exists(installDir))

    test("sudo symlink apply uses structured argv after policy and confirmation"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-sudo-apply")
      val installDir      = tempRoot.resolve("alpha")
      val commandExecutor = RecordingCommandExecutor()
      val credentials     =
        RecordingSudoCredentialProvider(Right(SudoPassword.fromString("unused-secret")))
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor,
        credentials
      )
      val plan = ResolvedPlan(
        ResolvedPolicy(
          tempRoot.toString,
          None,
          AllowSudoSymlinks.Enabled,
          RequireConfirmation.Disabled,
          ContinueOnError.Disabled
        ),
        Vector(sudoSymlinkTool(installDir))
      )

      val result = installer.installPlan(plan, ApplyConfirmation.Enabled)

      assert(result.exitCode == 0)
      assert(credentials.requests.isEmpty)
      assert(commandExecutor.commands.map(_.argv) == Vector(
        Vector("sudo", "-n", "true"),
        Vector(
          "sudo",
          "-n",
          "ln",
          "-sfn",
          installDir.toAbsolutePath.normalize().resolve("bin/alpha").toString,
          "/usr/local/bin/alpha"
        )
      ))
      assert(commandExecutor.commands.forall(_.env == CommandEnvironment.baseline))

    test("sudo symlink apply requests credentials when sudo cache is unavailable"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-sudo-credentials")
      val installDir      = tempRoot.resolve("alpha")
      val password        = "core-test-password"
      val commandExecutor =
        SequencedCommandExecutor(Vector(Left("sudo password required"), Right(())))
      val credentials = RecordingSudoCredentialProvider(Right(SudoPassword.fromString(password)))
      val installer   = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor,
        credentials
      )
      val plan = ResolvedPlan(
        ResolvedPolicy(
          tempRoot.toString,
          None,
          AllowSudoSymlinks.Enabled,
          RequireConfirmation.Disabled,
          ContinueOnError.Disabled
        ),
        Vector(sudoSymlinkTool(installDir))
      )

      val result = installer.installPlan(plan, ApplyConfirmation.Enabled)

      assert(result.exitCode == 0)
      assert(credentials.requests == Vector(SudoCredentialRequest(
        "alpha",
        "/usr/local/bin/alpha",
        installDir.toAbsolutePath.normalize().resolve("bin/alpha").toString,
        s"create sudo symlink ${installDir.toAbsolutePath.normalize().resolve("bin/alpha")} -> /usr/local/bin/alpha"
      )))
      assert(commandExecutor.commands.map(_.argv) == Vector(
        Vector("sudo", "-n", "true"),
        Vector(
          "sudo",
          "-S",
          "-p",
          "",
          "ln",
          "-sfn",
          installDir.toAbsolutePath.normalize().resolve("bin/alpha").toString,
          "/usr/local/bin/alpha"
        )
      ))
      assert(!commandExecutor.commands.exists(_.argv.contains(password)))
      assert(!commandExecutor.commands.map(_.toString).exists(_.contains(password)))

    test("sudo credential cancellation fails current operation and continues when policy allows"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-sudo-cancel")
      val alphaInstall    = tempRoot.resolve("alpha")
      val betaInstall     = tempRoot.resolve("beta")
      val commandExecutor = SequencedCommandExecutor(Vector(Left("sudo password required")))
      val credentials     = RecordingSudoCredentialProvider(Left(SudoCredentialError.Canceled))
      val installer       = DirectBinaryInstaller(
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Right("alpha-binary".getBytes(StandardCharsets.UTF_8)),
          "https://example.invalid/beta"  -> Right("beta-binary".getBytes(StandardCharsets.UTF_8))
        )),
        InstallFileSystem.nio,
        commandExecutor,
        credentials
      )
      val beta = directTool(betaInstall).copy(name = "beta")
      val plan = ResolvedPlan(
        ResolvedPolicy(
          tempRoot.toString,
          None,
          AllowSudoSymlinks.Enabled,
          RequireConfirmation.Disabled,
          ContinueOnError.Enabled
        ),
        Vector(sudoSymlinkTool(alphaInstall), beta)
      )

      val result = installer.installPlan(plan, ApplyConfirmation.Enabled)

      assert(result.exitCode == 1)
      assert(result.lines.exists(_.contains("sudo credentials canceled")))
      assert(result.lines.exists(_.contains("installed beta")))
      assert(credentials.requests.map(_.toolName) == Vector("alpha"))

    test("sudo command failure rendering redacts password from diagnostics"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-sudo-redaction")
      val installDir      = tempRoot.resolve("alpha")
      val password        = "super-secret-password"
      val commandExecutor = PasswordLeakingCommandExecutor(password)
      val credentials = RecordingSudoCredentialProvider(Right(SudoPassword.fromString(password)))
      val installer   = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor,
        credentials
      )
      val plan = ResolvedPlan(
        ResolvedPolicy(
          tempRoot.toString,
          None,
          AllowSudoSymlinks.Enabled,
          RequireConfirmation.Disabled,
          ContinueOnError.Disabled
        ),
        Vector(sudoSymlinkTool(installDir))
      )

      val result = installer.installPlan(plan, ApplyConfirmation.Enabled)

      assert(result.exitCode == 1)
      assert(result.lines.mkString("\n").contains("<redacted>"))
      assert(!result.lines.mkString("\n").contains(password))
      assert(!commandExecutor.commands.exists(_.argv.contains(password)))
      assert(!commandExecutor.commands.map(_.toString).exists(_.contains(password)))

    test("sudo password is redacted from events and apply state"):
      val tempRoot        = Files.createTempDirectory("binstaller-core-sudo-state-redaction")
      val installDir      = tempRoot.resolve("alpha")
      val stateFile       = "sudo-redaction.state.json"
      val password        = "state-secret-password"
      val commandExecutor = PasswordLeakingCommandExecutor(password)
      val credentials = RecordingSudoCredentialProvider(Right(SudoPassword.fromString(password)))
      val installer   = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor,
        credentials
      )
      val service = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        installer,
        ApplyStateStore.nio(tempRoot)
      )
      val config   = writeConfig(tempRoot, sudoSymlinkYaml(tempRoot, installDir, stateFile))
      val observer = RecordingInstallerEventObserver()

      val result   = service.applyWithEvents(applyOptions(config), observer)
      val state    = loadState(tempRoot, stateFile)
      val rendered =
        (result.lines ++
          observer.events.map(_.toString) ++
          state.tools.flatMap(_.message)).mkString("\n")

      assert(result.exitCode == 1)
      assert(rendered.contains("<redacted>"))
      assert(!rendered.contains(password))
      assert(!commandExecutor.commands.exists(_.argv.contains(password)))

    test("completed state entries are skipped and failed entries are retried"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-state-resume")
      val config       = writeConfig(tempRoot, twoToolYaml(tempRoot, "resume.state.json"))
      val firstService = statefulService(
        tempRoot,
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Right("alpha".getBytes(StandardCharsets.UTF_8)),
          "https://example.invalid/beta"  -> Left("network unavailable")
        ))
      )

      val firstResult = firstService.apply(applyOptions(config))

      assert(firstResult.exitCode == 1)
      assert(Files.isRegularFile(tempRoot.resolve("apps/alpha/bin/alpha")))
      assert(!Files.exists(tempRoot.resolve("apps/beta")))

      val secondService = statefulService(tempRoot, RoutingBinaryDownloadClient.success)
      val skippedResult = secondService.apply(
        applyOptions(config).copy(selection = ToolSelection(Vector.empty, Vector("beta")))
      )

      assert(skippedResult.exitCode == 0)
      assert(skippedResult.lines == Vector("skipped alpha: already completed in state"))
      assert(!Files.exists(tempRoot.resolve("apps/beta")))

      val retryResult = secondService.apply(applyOptions(config))
      val state       = loadState(tempRoot, "resume.state.json")

      assert(retryResult.exitCode == 0)
      assert(retryResult.lines.exists(_.contains("skipped alpha")))
      assert(retryResult.lines.exists(_.contains("installed beta")))
      assert(Files.isRegularFile(tempRoot.resolve("apps/beta/bin/beta")))
      assert(state.tools.map(tool => tool.name -> tool.status) ==
        Vector("alpha" -> "completed", "beta" -> "completed"))
      assert(!hasTempStateFile(tempRoot, "resume.state.json"))

    test("incompatible state fails clearly unless reset-state is enabled"):
      val tempRoot  = Files.createTempDirectory("binstaller-core-state-reset")
      val config    = writeConfig(tempRoot, twoToolYaml(tempRoot, "mismatch.state.json"))
      val store     = ApplyStateStore.nio(tempRoot)
      val statePath = tempRoot.resolve("mismatch.state.json")
      store.save(
        statePath,
        ApplyState.empty("other-profile", "other-fingerprint")
      ) match
        case Right(())   => ()
        case Left(error) => abort(s"failed to seed state: $error")
      val service = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val mismatchResult = service.apply(applyOptions(config))
      val resetResult    = service.apply(applyOptions(config).copy(resetState = ResetState.Enabled))

      assert(mismatchResult.exitCode == 1)
      assert(mismatchResult.lines.exists(_.contains("does not match this manifest")))
      assert(mismatchResult.lines.exists(_.contains("--reset-state")))
      assert(resetResult.exitCode == 0)
      assert(loadState(tempRoot, "mismatch.state.json").profileName == "resume-profile")

    test("state paths must be cwd-local filenames"):
      val tempRoot = Files.createTempDirectory("binstaller-core-state-path")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "valid.state.json"))
      val service  = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val absoluteResult = service.apply(
        applyOptions(config).copy(statePath =
          Some(tempRoot.resolve("absolute.state.json").toString)
        )
      )
      val nestedResult = service.apply(
        applyOptions(config).copy(statePath = Some("nested/state.json"))
      )

      assert(absoluteResult.exitCode == 1)
      assert(absoluteResult.lines.exists(_.contains("absolute state paths are not allowed")))
      assert(nestedResult.exitCode == 1)
      assert(nestedResult.lines.exists(_.contains("current working directory")))
      assert(!Files.exists(tempRoot.resolve("apps")))

    test("state is saved after each terminal tool result"):
      val tempRoot = Files.createTempDirectory("binstaller-core-state-writes")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "writes.state.json"))
      val store    = RecordingApplyStateStore(ApplyStateStore.nio(tempRoot))
      val service  = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(RoutingBinaryDownloadClient.success, InstallFileSystem.nio),
        store
      )

      val result = service.apply(applyOptions(config))

      assert(result.exitCode == 0)
      assert(store.savedStates.size == 2)
      assert(store.savedStates.map(_.tools.map(tool => tool.name -> tool.status)) ==
        Vector(
          Vector("alpha" -> "completed"),
          Vector("alpha" -> "completed", "beta" -> "completed")
        ))

    test("plan emits resolving plan-ready and summary events in order"):
      val tempRoot = Files.createTempDirectory("binstaller-core-events-plan")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "plan.state.json"))
      val observer = RecordingInstallerEventObserver()
      val service  = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result = service.planWithEvents(applyOptions(config), observer)

      assert(result.exitCode == 0)
      assert(eventIndex(observer.events, { case InstallerEvent.ResolvingStarted(_, _) => true }) <
        eventIndex(
          observer.events,
          {
            case InstallerEvent.PlanReady(Vector("alpha", "beta"), Some(_), _) => true
          }
        ))
      assert(eventIndex(
        observer.events,
        {
          case InstallerEvent.PlanReady(Vector("alpha", "beta"), Some(_), _) => true
        }
      ) <
        eventIndex(
          observer.events,
          {
            case InstallerEvent.Summary(InstallerRunStatus.Succeeded, 0, 0, 0, 0, Some(_), _) =>
              true
          }
        ))

    test("successful apply emits tool start progress result and summary in order"):
      val tempRoot = Files.createTempDirectory("binstaller-core-events-success")
      val config   = writeConfig(tempRoot, directBinaryYaml(tempRoot.resolve("alpha")))
      val observer = RecordingInstallerEventObserver()
      val service  = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(
          ProgressingBinaryDownloadClient("alpha-binary".getBytes(StandardCharsets.UTF_8)),
          InstallFileSystem.nio
        ),
        ApplyStateStore.nio(tempRoot)
      )

      val result = service.applyWithEvents(applyOptions(config), observer)

      assert(result.exitCode == 0)
      assert(eventIndex(
        observer.events,
        {
          case InstallerEvent.ToolStarted("alpha", InstallerPhase.Downloading, _) => true
        }
      ) < eventIndex(
        observer.events,
        {
          case InstallerEvent.DownloadProgress(
                "alpha",
                "https://example.invalid/alpha",
                _,
                Some(_),
                DownloadProgressStatus.Advanced,
                _
              ) => true
        }
      ))
      assert(eventIndex(
        observer.events,
        {
          case InstallerEvent.DownloadProgress(_, _, _, _, DownloadProgressStatus.Finished, _) =>
            true
        }
      ) < eventIndex(
        observer.events,
        {
          case InstallerEvent.ToolResult("alpha", ToolResultStatus.Completed, Some(_), None, _) =>
            true
        }
      ))
      assert(eventIndex(
        observer.events,
        {
          case InstallerEvent.ToolResult("alpha", ToolResultStatus.Completed, Some(_), None, _) =>
            true
        }
      ) < eventIndex(
        observer.events,
        {
          case InstallerEvent.Summary(InstallerRunStatus.Succeeded, 1, 0, 0, 0, None, _) => true
        }
      ))

    test("failed apply emits failed result with root-cause summary"):
      val tempRoot = Files.createTempDirectory("binstaller-core-events-failed")
      val config   = writeConfig(tempRoot, directBinaryYaml(tempRoot.resolve("alpha")))
      val observer = RecordingInstallerEventObserver()
      val service  = statefulService(tempRoot, FakeBinaryDownloadClient.failure("network down"))

      val result = service.applyWithEvents(applyOptions(config), observer)

      assert(result.exitCode == 1)
      assert(observer.events.exists:
        case InstallerEvent.ToolResult(
              "alpha",
              ToolResultStatus.Failed,
              None,
              Some(summary),
              _
            ) => summary.contains("download:") && summary.contains("network down")
        case _ => false)
      assert(observer.events.exists:
        case InstallerEvent.Summary(InstallerRunStatus.Failed, 0, 1, 0, 1, None, _) => true
        case _                                                                      => false)

    test("completed state entries emit skipped events with state file path"):
      val tempRoot     = Files.createTempDirectory("binstaller-core-events-skipped")
      val config       = writeConfig(tempRoot, twoToolYaml(tempRoot, "resume.state.json"))
      val firstService = statefulService(
        tempRoot,
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Right("alpha".getBytes(StandardCharsets.UTF_8)),
          "https://example.invalid/beta"  -> Left("network unavailable")
        ))
      )
      val _             = firstService.apply(applyOptions(config))
      val observer      = RecordingInstallerEventObserver()
      val secondService = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result = secondService.applyWithEvents(
        applyOptions(config).copy(selection = ToolSelection(Vector.empty, Vector("beta"))),
        observer
      )

      assert(result.exitCode == 0)
      val skipIndex = eventIndex(
        observer.events,
        {
          case InstallerEvent.ToolSkipped("alpha", "already completed in state", Some(path), _) =>
            path.endsWith("resume.state.json")
        }
      )
      val summaryIndex = eventIndex(
        observer.events,
        {
          case InstallerEvent.Summary(InstallerRunStatus.Succeeded, 0, 0, 1, 0, Some(_), _) => true
        }
      )
      assert(skipIndex < summaryIndex)

    test("continue-on-error emits failed then completed results before failed summary"):
      val tempRoot = Files.createTempDirectory("binstaller-core-events-continue")
      val config   = writeConfig(
        tempRoot,
        twoToolYaml(tempRoot, "continue.state.json", continueOnError = true)
      )
      val observer = RecordingInstallerEventObserver()
      val service  = statefulService(
        tempRoot,
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Left("network unavailable"),
          "https://example.invalid/beta"  -> Right("beta".getBytes(StandardCharsets.UTF_8))
        ))
      )

      val result = service.applyWithEvents(applyOptions(config), observer)

      assert(result.exitCode == 1)
      assert(eventIndex(
        observer.events,
        {
          case InstallerEvent.ToolResult("alpha", ToolResultStatus.Failed, None, Some(_), _) => true
        }
      ) < eventIndex(
        observer.events,
        {
          case InstallerEvent.ToolResult("beta", ToolResultStatus.Completed, Some(_), None, _) =>
            true
        }
      ))
      assert(eventIndex(
        observer.events,
        {
          case InstallerEvent.ToolResult("beta", ToolResultStatus.Completed, Some(_), None, _) =>
            true
        }
      ) < eventIndex(
        observer.events,
        {
          case InstallerEvent.Summary(InstallerRunStatus.Failed, 1, 1, 0, 1, Some(_), _) => true
        }
      ))

    test("plan renders strict policy failures with typed suggestions"):
      val tempRoot = Files.createTempDirectory("binstaller-core-strict-policy-output")
      val config   = writeConfig(tempRoot, strictPolicyYaml())
      val service  = BinaryInstallerService.resolving(FakeHttpTextClient(""))

      val planResult = service.plan(InstallerOptions(
        configPath = config.toString,
        statePath = None,
        resetState = ResetState.Disabled,
        verboseOutput = VerboseOutput.Disabled
      ))

      assert(planResult.exitCode == 1)
      assert(planResult.lines.exists(_.contains("strict-policy[missing-checksum]")))
      assert(planResult.lines.exists(_.contains("suggestion[missing-checksum]")))
      assert(planResult.lines.exists(_.contains("strict-policy[tar-xz-fallback]")))
      assert(planResult.lines.exists(_.contains("suggestion[tar-xz-fallback]")))

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

  private def resolveExampleConfig(httpTextClient: HttpTextClient): ResolvedPlan =
    val profile = ConfigModule.load(exampleConfigPath) match
      case Right(value) => value
      case Left(error)  => abort(s"expected valid example config, got $error")

    PlanResolver.resolve(profile, testResolutionOptions, httpTextClient) match
      case Right(plan)                                     => plan
      case Left(ResolvePlanError.ValidationFailed(errors)) =>
        abort(s"expected resolved example config, got ${errors.mkString(", ")}")

  private def onlyTool(plan: ResolvedPlan): ResolvedTool = plan.tools match
    case Vector(tool) => tool
    case other        => abort(s"expected one tool, got ${other.size}")

  private def assertInstallSuccess(
      result: Either[ToolInstallError, ToolInstallSuccess],
      installDir: String
  ): Unit = result match
    case Right(success) =>
      assert(success.toolName == "alpha")
      assert(success.installDir == installDir)
    case Left(error) => abort(s"expected install success, got $error")

  private def errorAt(path: String)(error: ValidationError): Boolean = error.path == path

  private def versionSummaryRowExists(
      lines: Vector[String],
      packageName: String,
      version: String,
      newerVersion: String
  ): Boolean = lines.exists(line =>
    line.startsWith(packageName) &&
      line.contains(version) &&
      line.endsWith(newerVersion)
  )

  private def eventIndex(
      events: Vector[InstallerEvent],
      matches: PartialFunction[InstallerEvent, Boolean]
  ): Int =
    val index = events.indexWhere(event => matches.applyOrElse(event, (_: InstallerEvent) => false))
    if index >= 0 then index
    else abort(s"event not found in ${events.mkString(", ")}")

  private def abort(message: String): Nothing = throw java.lang.AssertionError(message)

  private def statefulService(
      cwd: Path,
      downloadClient: BinaryDownloadClient
  ): BinaryInstallerService = BinaryInstallerService.resolving(
    FakeHttpTextClient(""),
    DirectBinaryInstaller(downloadClient, InstallFileSystem.nio),
    ApplyStateStore.nio(cwd)
  )

  private def lockedApplyService(
      tempRoot: Path,
      dynamicSize: Option[Long],
      installer: DirectBinaryInstaller = DirectBinaryInstaller(
        RoutingBinaryDownloadClient.success,
        InstallFileSystem.nio
      )
  ): BinaryInstallerService = BinaryInstallerService.resolving(
    LockHttpTextClient("2.0.0", betaVersionProvenance),
    installer,
    ApplyStateStore.nio(tempRoot),
    lockMetadataClient(dynamicSize),
    LockFileStore.nio
  )

  private def applyOptions(config: Path): InstallerOptions = InstallerOptions(
    configPath = config.toString,
    statePath = None,
    resetState = ResetState.Disabled,
    verboseOutput = VerboseOutput.Disabled,
    applyConfirmation = ApplyConfirmation.Enabled
  )

  private def writeLock(path: Path, lockFile: LockFile): Unit =
    val _ = Files.writeString(path, write(lockFile, indent = 2))

  private def currentLockFile(config: Path, dynamicSize: Option[Long]): LockFile =
    val profile = ConfigModule.load(config.toString) match
      case Right(value) => value
      case Left(error)  => abort(s"expected valid config, got $error")
    LockFile(
      LockFile.schemaVersion,
      profile.metadata.name,
      ManifestFingerprint.profile(profile),
      Vector(
        LockFileTool(
          name = "alpha",
          resolvedVersion = Some("1.0.0"),
          versionProvenance = None,
          downloadProvenance = UrlProvenance.direct("https://example.invalid/alpha-1.0.0"),
          sizeBytes = Some(11L),
          checksum = Some(LockFileChecksum("sha256", "a" * 64)),
          dynamicSource = false
        ),
        LockFileTool(
          name = "beta",
          resolvedVersion = Some("2.0.0"),
          versionProvenance = Some(betaVersionProvenance),
          downloadProvenance = betaDownloadProvenance,
          sizeBytes = Some(22L),
          checksum = None,
          dynamicSource = false
        ),
        LockFileTool(
          name = "gamma",
          resolvedVersion = None,
          versionProvenance = None,
          downloadProvenance = UrlProvenance.direct("https://example.invalid/latest/gamma"),
          sizeBytes = dynamicSize,
          checksum = None,
          dynamicSource = true
        )
      )
    )

  private def lockMetadataClient(dynamicSize: Option[Long]): BinaryMetadataClient =
    RoutingBinaryMetadataClient(Map(
      "https://example.invalid/alpha-1.0.0" -> BinaryMetadata(
        Some(11L),
        UrlProvenance.direct("https://example.invalid/alpha-1.0.0")
      ),
      "https://example.invalid/beta-2.0.0"   -> BinaryMetadata(Some(22L), betaDownloadProvenance),
      "https://example.invalid/latest/gamma" -> BinaryMetadata(
        dynamicSize,
        UrlProvenance.direct("https://example.invalid/latest/gamma")
      )
    ))

  private val betaVersionProvenance: UrlProvenance = UrlProvenance(
    "https://example.invalid/beta-version",
    "https://cdn.example.invalid/beta-version",
    Vector(UrlRedirectHop(
      "https://example.invalid/beta-version",
      "https://cdn.example.invalid/beta-version",
      302
    ))
  )

  private val betaDownloadProvenance: UrlProvenance = UrlProvenance(
    "https://example.invalid/beta-2.0.0",
    "https://cdn.example.invalid/beta-2.0.0",
    Vector(UrlRedirectHop(
      "https://example.invalid/beta-2.0.0",
      "https://cdn.example.invalid/beta-2.0.0",
      301
    ))
  )

  private def writeConfig(tempRoot: Path, content: String): Path =
    val config = tempRoot.resolve("profile.yaml")
    Files.writeString(config, content)
    config

  private def loadState(tempRoot: Path, name: String): ApplyState =
    ApplyStateStore.nio(tempRoot).load(tempRoot.resolve(name)) match
      case Right(Some(state)) => state
      case other              => abort(s"expected saved state, got $other")

  private def hasTempStateFile(
      tempRoot: Path,
      name: String
  ): Boolean = Using.resource(Files.list(tempRoot)): stream =>
    stream
      .iterator()
      .asScala
      .exists(path => path.getFileName.toString.startsWith(s".$name.tmp-"))

  private def sha256(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    digest.map(byte => f"${byte & 0xff}%02x").mkString

  private def exampleConfigPath: Path = repoRootCandidates
    .map(_.resolve("config.example.yaml"))
    .find(Files.exists(_))
    .getOrElse(abort("could not locate config.example.yaml"))

  private def repoRootCandidates: Iterator[Path] =
    sys.props.get("binstaller.repoRoot").iterator.map(Path.of(_).toAbsolutePath) ++
      upwardPaths(Path.of("").toAbsolutePath)

  private def upwardPaths(start: Path): Iterator[Path] =
    Iterator.iterate(start)(_.getParent).takeWhile(_ != null)

  private def directTool(
      installDir: Path,
      checksum: Option[ResolvedChecksum] = None,
      executables: Vector[ResolvedExecutable] = Vector(ResolvedExecutable("bin/alpha", None)),
      symlinks: Vector[ResolvedSymlink] = Vector.empty
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
    executables = executables,
    symlinks = symlinks
  )

  private def sudoSymlinkTool(installDir: Path): ResolvedTool = directTool(
    installDir,
    symlinks = Vector(
      ResolvedSymlink("/usr/local/bin/alpha", "bin/alpha", SymlinkPrivilege.Sudo)
    )
  )

  private def sudoSymlinkYaml(
      tempRoot: Path,
      installDir: Path,
      stateFile: String
  ): String = s"""
                 |apiVersion: binstaller.io/v1alpha1
                 |kind: BinaryDistributionProfile
                 |metadata:
                 |  name: sudo-redaction
                 |spec:
                 |  policy:
                 |    appsDir: "$tempRoot"
                 |    stateFile: "$stateFile"
                 |    allowSudoSymlinks: true
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
                 |          url: "https://example.invalid/alpha"
                 |          filename: alpha
                 |        executables:
                 |          - path: bin/alpha
                 |        symlinks:
                 |          - path: /usr/local/bin/alpha
                 |            target: "$installDir/bin/alpha"
                 |            sudo: true
                 |""".stripMargin

  private def archiveTool(
      installDir: Path,
      archiveType: ArchiveType,
      files: Vector[(String, String)] = Vector.empty,
      directories: Vector[(String, String)] = Vector.empty,
      executable: String = "bin/alpha"
  ): ResolvedTool = ResolvedTool(
    name = "alpha",
    description = None,
    version = ResolvedVersion.Concrete("1.0.0"),
    installDir = installDir.toString,
    createDirectories = Vector.empty,
    download = ResolvedDownload(
      url = "https://example.invalid/alpha-archive",
      filename = "alpha-archive",
      checksum = None,
      archive = Some(
        ResolvedArchive(
          ArchiveSpec(
            archiveType,
            ArchiveExtract(
              files.map((from, to) => ExtractMapping(from, to)),
              directories.map((from, to) => ExtractMapping(from, to))
            )
          ),
          files.map((from, to) => ResolvedExtractMapping(from, to)),
          directories.map((from, to) => ResolvedExtractMapping(from, to))
        )
      )
    ),
    executables = Vector(ResolvedExecutable(executable, None)),
    symlinks = Vector.empty
  )

  private def zipArchive(entries: Vector[(String, String)]): Array[Byte] =
    val output = ByteArrayOutputStream()
    Using.resource(ZipOutputStream(output)): zip =>
      entries.foreach:
        case (name, content) =>
          zip.putNextEntry(ZipEntry(name))
          zip.write(content.getBytes(StandardCharsets.UTF_8))
          zip.closeEntry()
    output.toByteArray

  private def zipArchiveWithDuplicateLocalEntries(entries: Vector[(String, String)]): Array[Byte] =
    val output = ByteArrayOutputStream()
    entries.foreach:
      case (name, content) =>
        val nameBytes    = name.getBytes(StandardCharsets.UTF_8)
        val contentBytes = content.getBytes(StandardCharsets.UTF_8)
        val crc          = CRC32()
        crc.update(contentBytes)
        writeLittleInt(output, 0x04034b50)
        writeLittleShort(output, 20)
        writeLittleShort(output, 0)
        writeLittleShort(output, 0)
        writeLittleShort(output, 0)
        writeLittleShort(output, 0)
        writeLittleInt(output, crc.getValue.toInt)
        writeLittleInt(output, contentBytes.length)
        writeLittleInt(output, contentBytes.length)
        writeLittleShort(output, nameBytes.length)
        writeLittleShort(output, 0)
        output.write(nameBytes)
        output.write(contentBytes)
    output.toByteArray

  private def writeLittleShort(output: ByteArrayOutputStream, value: Int): Unit =
    output.write(value & 0xff)
    output.write((value >>> 8) & 0xff)

  private def writeLittleInt(output: ByteArrayOutputStream, value: Int): Unit =
    writeLittleShort(output, value & 0xffff)
    writeLittleShort(output, (value >>> 16) & 0xffff)

  private def tarGzArchive(entries: Vector[(String, String)]): Array[Byte] =
    val output = ByteArrayOutputStream()
    val gzip   = GZIPOutputStream(output)
    entries.foreach:
      case (name, content) =>
        val bytes = content.getBytes(StandardCharsets.UTF_8)
        gzip.write(tarHeader(name, bytes.length, '0'))
        gzip.write(bytes)
        val padding = (512 - (bytes.length % 512)) % 512
        gzip.write(Array.fill[Byte](padding)(0))
    gzip.write(Array.fill[Byte](1024)(0))
    gzip.close()
    output.toByteArray

  private def tarGzArchiveWithDirectories(
      directories: Vector[String],
      files: Vector[(String, String)]
  ): Array[Byte] =
    val output = ByteArrayOutputStream()
    val gzip   = GZIPOutputStream(output)
    directories.foreach: name =>
      gzip.write(tarHeader(name, 0, '5'))
    files.foreach:
      case (name, content) =>
        val bytes = content.getBytes(StandardCharsets.UTF_8)
        gzip.write(tarHeader(name, bytes.length, '0'))
        gzip.write(bytes)
        val padding = (512 - (bytes.length % 512)) % 512
        gzip.write(Array.fill[Byte](padding)(0))
    gzip.write(Array.fill[Byte](1024)(0))
    gzip.close()
    output.toByteArray

  private def tarGzArchiveWithEntryTypes(entries: Vector[(String, String, Char)]): Array[Byte] =
    val output = ByteArrayOutputStream()
    val gzip   = GZIPOutputStream(output)
    entries.foreach:
      case (name, content, entryType) =>
        val bytes = content.getBytes(StandardCharsets.UTF_8)
        gzip.write(tarHeader(name, bytes.length, entryType))
        if entryType == '0' then
          gzip.write(bytes)
          val padding = (512 - (bytes.length % 512)) % 512
          gzip.write(Array.fill[Byte](padding)(0))
    gzip.write(Array.fill[Byte](1024)(0))
    gzip.close()
    output.toByteArray

  private def tarHeader(name: String, size: Int, entryType: Char): Array[Byte] =
    val header = Array.fill[Byte](512)(0)
    writeTarField(header, 0, 100, name)
    writeTarField(header, 124, 12, f"$size%011o")
    header(156) = entryType.toByte
    header

  private def writeTarField(header: Array[Byte], offset: Int, length: Int, value: String): Unit =
    val bytes = value.getBytes(StandardCharsets.UTF_8)
    Array.copy(bytes, 0, header, offset, math.min(bytes.length, length))

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

  private def twoToolYaml(
      tempRoot: Path,
      stateFile: String,
      continueOnError: Boolean = false
  ): String =
    val appsDir = tempRoot.resolve("apps")
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: resume-profile
       |spec:
       |  policy:
       |    appsDir: "$appsDir"
       |    stateFile: "$stateFile"
       |    continueOnError: $continueOnError
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |    beta: "1.0.0"
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "$appsDir/alpha"
       |        createDirectories:
       |          - bin
       |        download:
       |          url: https://example.invalid/alpha
       |          filename: alpha
       |        executables:
       |          - path: bin/alpha
       |    - name: beta
       |      kind: binary-tool
       |      spec:
       |        versionRef: beta
       |        installDir: "$appsDir/beta"
       |        createDirectories:
       |          - bin
       |        download:
       |          url: https://example.invalid/beta
       |          filename: beta
       |        executables:
       |          - path: bin/beta
       |""".stripMargin

  private def invalidConfigYaml(tempRoot: Path): String =
    s"""
       |apiVersion: wrong.example/v1
       |kind: WrongKind
       |metadata:
       |  name: invalid
       |spec:
       |  policy:
       |    appsDir: "${tempRoot.resolve("apps")}"
       |    continueOnError: no
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "${tempRoot.resolve("apps/alpha")}"
       |        download:
       |          url: https://example.invalid/alpha
       |          filename: alpha
       |        executables:
       |          - path: bin/alpha
       |""".stripMargin

  private val testResolutionOptions: ResolutionOptions = ResolutionOptions(
    Map("HOME" -> "/home/test")
  )

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

  private def lockYaml(tempRoot: Path): String =
    val appsDir = tempRoot.resolve("apps")
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: lock-profile
       |spec:
       |  policy:
       |    appsDir: "$appsDir"
       |    stateFile: lock.state.json
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |    beta:
       |      resolver:
       |        type: http-text
       |        url: https://example.invalid/beta-version
       |    gamma:
       |      dynamic:
       |        type: latest-url
       |        note: latest endpoint
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "$appsDir/alpha"
       |        download:
       |          url: https://example.invalid/alpha-$${version}
       |          filename: alpha
       |          checksum:
       |            algorithm: sha256
       |            value: ${"a" * 64}
       |        executables:
       |          - path: bin/alpha
       |    - name: beta
       |      kind: binary-tool
       |      spec:
       |        versionRef: beta
       |        installDir: "$appsDir/beta"
       |        download:
       |          url: https://example.invalid/beta-$${version}
       |          filename: beta
       |        executables:
       |          - path: bin/beta
       |    - name: gamma
       |      kind: binary-tool
       |      spec:
       |        versionRef: gamma
       |        installDir: "$appsDir/gamma"
       |        download:
       |          url: https://example.invalid/latest/gamma
       |          filename: gamma
       |        executables:
       |          - path: bin/gamma
       |""".stripMargin

  private def githubReleaseYaml(tempRoot: Path, version: String): String =
    val appsDir = tempRoot.resolve("apps")
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: github-latest
       |spec:
       |  policy:
       |    appsDir: "$appsDir"
       |  vars:
       |    muslTarget: x86_64-unknown-linux-musl
       |  versions:
       |    jujutsu: "$version"
       |  plan:
       |    - name: jujutsu
       |      kind: binary-tool
       |      spec:
       |        versionRef: jujutsu
       |        installDir: "$appsDir/jj"
       |        download:
       |          url: "https://github.com/jj-vcs/jj/releases/download/v$version/jj-v$version-$${muslTarget}.tar.gz"
       |          filename: jj.tar.gz
       |          archive:
       |            type: tar.gz
       |            extract:
       |              files:
       |                - from: jj
       |                  to: bin/jj
       |        executables:
       |          - path: bin/jj
       |""".stripMargin

  private def checksumDiscoveryYaml(tempRoot: Path, checksumFileUrl: String): String =
    val appsDir = tempRoot.resolve("apps")
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: checksum-discovery
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
       |        createDirectories:
       |          - bin
       |        download:
       |          url: https://example.invalid/releases/$${version}/alpha-$${version}.tar.gz
       |          filename: alpha-$${version}.tar.gz
       |          checksum:
       |            algorithm: sha256
       |            discover:
       |              type: sha256sum
       |              url: $checksumFileUrl
       |        executables:
       |          - path: bin/alpha
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

  private def strictPolicyYaml(overrides: String = ""): String =
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: strict-policy
       |spec:
       |  policy:
       |    mode: strict
       |    appsDir: "$${HOME}/.apps"
       |    $overrides
       |  vars: {}
       |  versions:
       |    alpha:
       |      dynamic:
       |        type: latest-url
       |        note: upstream latest endpoint
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "$${appsDir}/alpha"
       |        download:
       |          url: https://example.invalid/latest/download/alpha.tar.xz
       |          filename: alpha.tar.xz
       |          archive:
       |            type: tar.xz
       |            extract:
       |              files:
       |                - from: alpha
       |                  to: bin/alpha
       |        executables:
       |          - path: bin/alpha
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
                                          |    alpha: "1.0.0"
                                          |  plan:
                                          |    - name: alpha
                                          |      kind: binary-tool
                                          |      spec:
                                          |        versionRef: alpha
                                          |        installDir: "${appsDir}/${shellText}"
                                          |        createDirectories:
                                          |          - bin
                                          |        download:
                                          |          url: "https://example.invalid/alpha"
                                          |          filename: alpha
                                          |        executables:
                                          |          - path: bin/alpha
                                          |""".stripMargin

  private val insecureUrlYaml: String = """
                                          |apiVersion: binstaller.io/v1alpha1
                                          |kind: BinaryDistributionProfile
                                          |metadata:
                                          |  name: insecure
                                          |spec:
                                          |  policy:
                                          |    appsDir: "${HOME}/.apps"
                                          |  vars: {}
                                          |  versions:
                                          |    alpha:
                                          |      resolver:
                                          |        type: http-text
                                          |        url: http://example.invalid/stable.txt
                                          |  plan:
                                          |    - name: alpha
                                          |      kind: binary-tool
                                          |      spec:
                                          |        versionRef: alpha
                                          |        installDir: "${appsDir}/alpha"
                                          |        download:
                                          |          url: http://example.invalid/alpha
                                          |          filename: alpha
                                          |        executables:
                                          |          - path: bin/alpha
                                          |""".stripMargin

  private val unsafeInstallDirYaml: String = """
                                               |apiVersion: binstaller.io/v1alpha1
                                               |kind: BinaryDistributionProfile
                                               |metadata:
                                               |  name: unsafe-install-dir
                                               |spec:
                                               |  policy:
                                               |    appsDir: "${HOME}/.apps"
                                               |  vars: {}
                                               |  versions:
                                               |    alpha: "1.0.0"
                                               |    beta: "1.0.0"
                                               |    gamma: "1.0.0"
                                               |  plan:
                                               |    - name: alpha
                                               |      kind: binary-tool
                                               |      spec:
                                               |        versionRef: alpha
                                               |        installDir: /tmp/alpha
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
                                               |          url: https://example.invalid/beta
                                               |          filename: beta
                                               |        executables:
                                               |          - path: bin/beta
                                               |    - name: gamma
                                               |      kind: binary-tool
                                               |      spec:
                                               |        versionRef: gamma
                                               |        installDir: "${appsDir}/beta/nested"
                                               |        download:
                                               |          url: https://example.invalid/gamma
                                               |          filename: gamma
                                               |        executables:
                                               |          - path: bin/gamma
                                               |""".stripMargin

  private val unsafeInterpolatedPathsYaml: String =
    """
      |apiVersion: binstaller.io/v1alpha1
      |kind: BinaryDistributionProfile
      |metadata:
      |  name: unsafe-interpolated-paths
      |spec:
      |  policy:
      |    appsDir: "${HOME}/.apps"
      |    stateFile: "${badTraversal}"
      |    allowSudoSymlinks: true
      |  vars:
      |    badAbsolute: /tmp/binstaller-escape
      |    badTraversal: "../escape"
      |    badControl: "alpha\a"
      |  versions:
      |    alpha: "1.0.0"
      |  plan:
      |    - name: alpha
      |      kind: binary-tool
      |      spec:
      |        versionRef: alpha
      |        installDir: "${appsDir}/alpha"
      |        createDirectories:
      |          - "bin/${badTraversal}"
      |        download:
      |          url: https://example.invalid/alpha
      |          filename: "${badControl}"
      |          archive:
      |            type: tar.gz
      |            extract:
      |              files:
      |                - from: alpha
      |                  to: "bin/${badTraversal}"
      |        executables:
      |          - path: "${badAbsolute}"
      |        symlinks:
      |          - path: "bin/${badTraversal}"
      |            target: "${badAbsolute}/alpha"
      |    - name: beta
      |      kind: binary-tool
      |      spec:
      |        versionRef: alpha
      |        installDir: "${appsDir}/${badTraversal}"
      |        download:
      |          url: https://example.invalid/beta
      |          filename: beta
      |        executables:
      |          - path: bin/beta
      |    - name: gamma
      |      kind: binary-tool
      |      spec:
      |        versionRef: alpha
      |        installDir: "${badAbsolute}/gamma"
      |        download:
      |          url: https://example.invalid/gamma
      |          filename: gamma
      |        executables:
      |          - path: bin/gamma
      |""".stripMargin

private final class FakeHttpTextClient(text: String) extends HttpTextClient:

  def getText(url: String): Either[HttpTextError, String] =
    if url == "https://dl.k8s.io/release/stable.txt" then Right(text)
    else Left(HttpTextError(url, s"unexpected URL $url"))

private final class RoutingHttpTextClient(
    responses: Map[String, Either[HttpTextError, HttpTextResponse]]
) extends HttpTextClient:

  def getText(url: String): Either[HttpTextError, String] = getTextWithProvenance(url).map(_.text)

  override def getTextWithProvenance(url: String): Either[HttpTextError, HttpTextResponse] =
    responses.getOrElse(url, Left(HttpTextError(url, s"unexpected URL $url")))

private final class LockHttpTextClient(text: String, provenance: UrlProvenance)
    extends HttpTextClient:

  def getText(url: String): Either[HttpTextError, String] = getTextWithProvenance(url).map(_.text)

  override def getTextWithProvenance(url: String): Either[HttpTextError, HttpTextResponse] =
    if url == provenance.initialUrl then Right(HttpTextResponse(text, provenance))
    else Left(HttpTextError(url, s"unexpected URL $url"))

private final class FakeBinaryDownloadClient(result: Either[BinaryDownloadError, Array[Byte]])
    extends BinaryDownloadClient:

  def download(url: String): Either[BinaryDownloadError, Array[Byte]] =
    result.left.map(error => error.copy(url = url))

private object FakeBinaryDownloadClient:

  def success(bytes: Array[Byte]): FakeBinaryDownloadClient = FakeBinaryDownloadClient(Right(bytes))

  def failure(message: String): FakeBinaryDownloadClient =
    FakeBinaryDownloadClient(Left(BinaryDownloadError("", message)))

private final class ProgressingBinaryDownloadClient(bytes: Array[Byte])
    extends BinaryDownloadClient:

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

private final class RedirectingBinaryDownloadClient(provenance: UrlProvenance)
    extends BinaryDownloadClient:

  def download(url: String): Either[BinaryDownloadError, Array[Byte]] =
    Right("alpha".getBytes(StandardCharsets.UTF_8))

  override def downloadWithProvenance(
      url: String,
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, BinaryDownloadResult] =
    val bytes = "alpha".getBytes(StandardCharsets.UTF_8)
    progressObserver.onProgress(BinaryDownloadProgress.Started(provenance.finalUrl, Some(5L)))
    progressObserver.onProgress(BinaryDownloadProgress.Advanced(provenance.finalUrl, 5L, Some(5L)))
    progressObserver.onProgress(BinaryDownloadProgress.Finished(provenance.finalUrl, 5L, Some(5L)))
    Right(BinaryDownloadResult(bytes, provenance))

private final class RecordingBinaryDownloadProgressObserver extends BinaryDownloadProgressObserver:
  private var recordedUrls: Vector[String] = Vector.empty

  def urls: Vector[String] = recordedUrls

  def onProgress(progress: BinaryDownloadProgress): Unit =
    val url = progress match
      case BinaryDownloadProgress.Started(value, _)     => value
      case BinaryDownloadProgress.Advanced(value, _, _) => value
      case BinaryDownloadProgress.Finished(value, _, _) => value
    recordedUrls = recordedUrls :+ url

private final class RoutingBinaryDownloadClient(
    results: Map[String, Either[String, Array[Byte]]]
) extends BinaryDownloadClient:

  def download(url: String): Either[BinaryDownloadError, Array[Byte]] = results
    .getOrElse(url, Left(s"unexpected URL $url"))
    .left
    .map(message => BinaryDownloadError(url, message))

private object RoutingBinaryDownloadClient:

  def success: RoutingBinaryDownloadClient = RoutingBinaryDownloadClient(Map(
    "https://example.invalid/alpha" -> Right("alpha".getBytes(StandardCharsets.UTF_8)),
    "https://example.invalid/beta"  -> Right("beta".getBytes(StandardCharsets.UTF_8))
  ))

private final class RoutingBinaryMetadataClient(results: Map[String, BinaryMetadata])
    extends BinaryMetadataClient:

  def metadata(url: String): Either[BinaryMetadataError, BinaryMetadata] = results
    .get(url)
    .toRight(BinaryMetadataError(url, s"unexpected URL $url"))

private final class RecordingInstallerEventObserver extends InstallerEventObserver:

  private var recordedEvents: Vector[InstallerEvent] = Vector.empty

  def events: Vector[InstallerEvent] = recordedEvents

  def onEvent(event: InstallerEvent): Unit = recordedEvents = recordedEvents :+ event

private final class RecordingApplyStateStore(delegate: ApplyStateStore) extends ApplyStateStore:

  private var states: Vector[ApplyState] = Vector.empty

  def savedStates: Vector[ApplyState] = states

  def cwd: Path = delegate.cwd

  def load(path: Path): Either[ApplyStateError, Option[ApplyState]] = delegate.load(path)

  def save(path: Path, state: ApplyState): Either[ApplyStateError, Unit] =
    states = states :+ state
    delegate.save(path, state)

private final class StaticHttpClient[T](response: HttpResponse[T]) extends HttpClient:

  override def cookieHandler(): Optional[CookieHandler] = Optional.empty()

  override def connectTimeout(): Optional[Duration] = Optional.empty()

  override def followRedirects(): HttpClient.Redirect = HttpClient.Redirect.NORMAL

  override def proxy(): Optional[ProxySelector] = Optional.empty()

  override def sslContext(): SSLContext = SSLContext.getDefault

  override def sslParameters(): SSLParameters = SSLParameters()

  override def authenticator(): Optional[Authenticator] = Optional.empty()

  override def version(): HttpClient.Version = HttpClient.Version.HTTP_1_1

  override def executor(): Optional[Executor] = Optional.empty()

  override def send[A](
      request: HttpRequest,
      responseBodyHandler: HttpResponse.BodyHandler[A]
  ): HttpResponse[A] = response.asInstanceOf[HttpResponse[A]]

  override def sendAsync[A](
      request: HttpRequest,
      responseBodyHandler: HttpResponse.BodyHandler[A]
  ): CompletableFuture[HttpResponse[A]] =
    CompletableFuture.completedFuture(send(request, responseBodyHandler))

  override def sendAsync[A](
      request: HttpRequest,
      responseBodyHandler: HttpResponse.BodyHandler[A],
      pushPromiseHandler: HttpResponse.PushPromiseHandler[A]
  ): CompletableFuture[HttpResponse[A]] =
    CompletableFuture.completedFuture(send(request, responseBodyHandler))

  override def newWebSocketBuilder(): WebSocket.Builder =
    throw UnsupportedOperationException("websocket not used in tests")

private final case class FakeHttpResponse[T](
    responseUri: String,
    responseStatusCode: Int,
    responseBody: T,
    previous: Option[HttpResponse[T]] = None,
    responseHeaders: Map[String, Vector[String]] = Map.empty
) extends HttpResponse[T]:

  def statusCode(): Int = responseStatusCode

  def request(): HttpRequest = HttpRequest.newBuilder(URI.create(responseUri)).build()

  def previousResponse(): Optional[HttpResponse[T]] = previous match
    case Some(response) => Optional.of(response)
    case None           => Optional.empty()

  def headers(): HttpHeaders = HttpHeaders.of(
    responseHeaders.view.mapValues(_.asJava).toMap.asJava,
    (_, _) => true
  )

  def body(): T = responseBody

  def sslSession(): Optional[SSLSession] = Optional.empty()

  def uri(): URI = URI.create(responseUri)

  def version(): HttpClient.Version = HttpClient.Version.HTTP_1_1

private final class FakeArchiveCommandExecutor(path: String, content: String)
    extends CommandExecutor:

  private var recordedCommands: Vector[CommandSpec] = Vector.empty

  def commands: Vector[CommandSpec] = recordedCommands

  def run(spec: CommandSpec): Either[CommandExecutionError, Unit] =
    recordedCommands = recordedCommands :+ spec
    val extractDir = spec.argv.dropWhile(_ != "-C").drop(1).headOption.map(Path.of(_))
    extractDir match
      case Some(directory) =>
        val target = directory.resolve(path)
        Files.createDirectories(target.getParent)
        Files.writeString(target, content)
        Right(())
      case None => Left(CommandExecutionError(spec, "missing -C extraction directory", None))

private final class RecordingCommandExecutor(result: Either[String, Unit] = Right(()))
    extends CommandExecutor:

  private var recordedCommands: Vector[CommandSpec] = Vector.empty

  def commands: Vector[CommandSpec] = recordedCommands

  def run(spec: CommandSpec): Either[CommandExecutionError, Unit] =
    recordedCommands = recordedCommands :+ spec
    result.left.map(message => CommandExecutionError(spec, message, None))

private final class SequencedCommandExecutor(results: Vector[Either[String, Unit]])
    extends CommandExecutor:

  private var recordedCommands: Vector[CommandSpec] = Vector.empty

  def commands: Vector[CommandSpec] = recordedCommands

  def run(spec: CommandSpec): Either[CommandExecutionError, Unit] =
    recordedCommands = recordedCommands :+ spec
    val index  = recordedCommands.size - 1
    val result = results.lift(index).getOrElse(Right(()))
    result.left.map(message => CommandExecutionError(spec, message, None))

private final class PasswordLeakingCommandExecutor(password: String) extends CommandExecutor:

  private var recordedCommands: Vector[CommandSpec] = Vector.empty

  def commands: Vector[CommandSpec] = recordedCommands

  def run(spec: CommandSpec): Either[CommandExecutionError, Unit] =
    recordedCommands = recordedCommands :+ spec
    if spec.argv == Vector("sudo", "-n", "true") then
      Left(CommandExecutionError(spec, "sudo password required", Some(1)))
    else
      Left(CommandExecutionError(
        spec,
        s"authentication failed with $password",
        Some(1),
        CommandOutput(s"stdout $password", s"stderr $password")
      ))

private final class RecordingSudoCredentialProvider(
    result: Either[SudoCredentialError, SudoPassword]
) extends SudoCredentialProvider:

  private var recordedRequests: Vector[SudoCredentialRequest] = Vector.empty

  def requests: Vector[SudoCredentialRequest] = recordedRequests

  def requestSudoPassword(
      request: SudoCredentialRequest
  ): Either[SudoCredentialError, SudoPassword] =
    recordedRequests = recordedRequests :+ request
    result

private final class RecordingInstallFileSystem(
    stageFailure: Option[String] = None,
    modeFailure: Option[String] = None,
    stagedFiles: Vector[String] = Vector("bin/alpha")
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
    case None          => stageSuccess(installDir)

  def stageArchive(
      installDir: Path,
      createDirectories: Vector[String],
      archive: ResolvedArchive,
      bytes: Array[Byte],
      commandExecutor: CommandExecutor
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall] = stageFailure match
    case Some(message) => Left(InstallFileSystemError.StagingFailed(message))
    case None          => stageSuccess(installDir)

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
    modes.foreach: mode =>
      val target = stagedInstall.installDir.resolve(mode.path)
      Files.createDirectories(target.getParent)
      Files.writeString(target, "installed")
    Right(())

  def discardStaged(stagedInstall: StagedInstall): Unit = ()

  private def stageSuccess(
      installDir: Path
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall] =
    try
      val stagingDir = Files.createTempDirectory("binstaller-recording-stage")
      stagedFiles.foreach: file =>
        val target = stagingDir.resolve(file)
        Files.createDirectories(target.getParent)
        Files.writeString(target, "staged")
      Right(StagedInstall(stagingDir, installDir))
    catch
      case error: Exception => Left(InstallFileSystemError.StagingFailed(error.getMessage))
