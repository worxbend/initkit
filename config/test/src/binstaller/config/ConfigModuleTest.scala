package binstaller.config

import utest.*

import java.nio.file.Files
import java.nio.file.Path

object ConfigModuleTest extends TestSuite:

  val tests: Tests = Tests:
    test("module exposes its name"):
      assert(ConfigModule.moduleName == "config")

    test("config example loads into typed manifest"):
      val profile = exampleProfile

      assert(profile.apiVersion == ApiVersion.V1Alpha1)
      assert(profile.kind == ManifestKind.BinaryDistributionProfile)
      assert(profile.metadata.name == "developer-binaries")
      assert(profile.spec.plan.size == 15)
      assert(profile.spec.versions.contains("kubectl"))
      assert(profile.spec.policy.allowSudoSymlinks == AllowSudoSymlinks.Enabled)

    test("config example locks binary tool order and version sources"):
      val profile = exampleProfile

      assert(profile.spec.plan.map(_.name) == exampleToolNames)
      assert(profile.spec.versions.keySet == exampleToolNames.toSet)
      assert(pinnedVersions(profile) == expectedPinnedVersions)
      assert(dynamicLatestUrlNames(profile) == expectedDynamicLatestUrlNames)
      assert(profile.spec.versions("kubectl") ==
        VersionSource.Resolver(
          VersionResolverKind.HttpText,
          "https://dl.k8s.io/release/stable.txt"
        ))

    test("config example keeps install directories under appsDir and records checksums"):
      val profile = exampleProfile

      assert(profile.spec.plan.map(entry => entry.name -> entry.spec.installDir) ==
        exampleToolNames.map(name => name -> s"$${appsDir}/$name"))
      assert(checksumFor(profile, "helm") ==
        Some(ChecksumSpec(
          ChecksumAlgorithm.Sha256,
          "0a745198de24545d0055cd8414bc8d2ba10363ef5f5d38369ea1b399671cc083"
        )))
      assert(checksumFor(profile, "kustomize") ==
        Some(ChecksumSpec(
          ChecksumAlgorithm.Sha256,
          "029a7f0f4e1932c52a0476cf02a0fd855c0bb85694b82c338fc648dcb53a819d"
        )))
      assert(checksumFor(profile, "dotbot") ==
        Some(ChecksumSpec(
          ChecksumAlgorithm.Sha256,
          "45d49e064d8684926fed97ad051c6ecebbf796a3c709edaa7a4a166b2978633d"
        )))
      assert(checksumFor(profile, "nerd-font-installer") ==
        Some(ChecksumSpec(
          ChecksumAlgorithm.Sha256,
          "25c70bcf327930282823fa6abecc54f14f53fb44b01f988187a07218b711a1a7"
        )))

    test("config example installs Helm from archive, not installer script"):
      val helm    = toolNamed(exampleProfile, "helm")
      val archive = helm.spec.download.archive.getOrElse(abort("missing Helm archive spec"))

      assert(helm.spec.download.url == "https://get.helm.sh/helm-${version}-linux-amd64.tar.gz")
      assert(archive.archiveType == ArchiveType.TarGz)
      assert(archive.extract.files.map(mapping => mapping.from -> mapping.to) ==
        Vector("linux-amd64/helm" -> "bin/helm"))
      assert(helm.spec.executables.map(_.path) == Vector("bin/helm"))

    test("config example installs Kustomize from archive, not installer script"):
      val kustomize = toolNamed(exampleProfile, "kustomize")
      val archive   =
        kustomize.spec.download.archive.getOrElse(abort("missing Kustomize archive spec"))

      assert(
        kustomize.spec.download.url ==
          "https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize/${version}/kustomize_${version}_linux_amd64.tar.gz"
      )
      assert(archive.archiveType == ArchiveType.TarGz)
      assert(archive.extract.files.map(mapping => mapping.from -> mapping.to) ==
        Vector("kustomize" -> "bin/kustomize"))
      assert(kustomize.spec.executables.map(_.path) == Vector("bin/kustomize"))

    test("config example includes Neovim sudo symlinks gated by policy"):
      val profile      = exampleProfile
      val neovim       = toolNamed(profile, "neovim")
      val sudoSymlinks = neovim.spec.symlinks.filter(_.privilege == SymlinkPrivilege.Sudo)

      assert(sudoSymlinks.map(_.path) == Vector(
        "/usr/local/bin/neovim",
        "/usr/local/bin/vim",
        "/usr/local/bin/nvim",
        "/usr/bin/nvim",
        "/usr/bin/neovim"
      ))
      assert(sudoSymlinks.map(_.target).distinct == Vector("${appsDir}/neovim/bin/nvim"))
      assert(exampleWithSudoPolicy(allowSudoSymlinks = true).isRight)
      val expectedErrorPaths = Vector.range(2, 7)
        .map(index =>
          s"spec.plan[${exampleToolNames.indexOf("neovim")}].spec.symlinks[$index].sudo"
        )
      val rejectedErrors = exampleWithSudoPolicy(allowSudoSymlinks = false) match
        case Left(ConfigLoadError.ValidationFailed(errors)) => errors
        case Left(error)    => abort(s"expected sudo policy validation errors, got $error")
        case Right(profile) => abort(s"expected sudo policy validation errors, got $profile")
      assert(rejectedErrors.map(_.path) == expectedErrorPaths)
      assert(rejectedErrors.forall(_.message.contains("neovim")))

    test("invalid manifest values aggregate validation errors with YAML paths"):
      val errors = validationErrors(invalidValuesYaml)

      assert(errors.exists(errorAt("apiVersion")))
      assert(errors.exists(errorAt("kind")))
      assert(errors.exists(errorAt("spec.plan[0].kind")))
      assert(errors.exists(errorAt("spec.plan[0].spec.download.archive.type")))
      assert(errors.exists(errorAt("spec.plan[0].spec.installer")))
      assert(errors.exists(errorAt("spec.plan[0].spec.executables[0].mode")))

    test("duplicate tool names report every duplicated name"):
      val errors   = validationErrors(duplicateNamesYaml)
      val messages = errors.map(_.message).mkString("\n")

      assert(messages.contains("duplicate tool name 'alpha'"))
      assert(messages.contains("duplicate tool name 'beta'"))

    test("unknown version refs report tool name and field path"):
      val errors = validationErrors(unknownVersionYaml)

      assert(
        errors.contains(
          ValidationError(
            "spec.plan[0].spec.versionRef",
            "tool 'alpha' references unknown version 'missing'"
          )
        )
      )

    test("sudo symlinks fail unless allowed by policy"):
      val rejected = validationErrors(sudoSymlinkYaml(allowSudoSymlinks = false))
      val accepted = ConfigModule.loadString(sudoSymlinkYaml(allowSudoSymlinks = true))

      assert(rejected.exists(errorAt("spec.plan[0].spec.symlinks[0].sudo")))
      assert(accepted.isRight)

    test("installer blocks are rejected"):
      val errors = validationErrors(unsupportedInstallerYaml)

      assert(errors.contains(
        ValidationError(
          "spec.plan[0].spec.installer",
          "installer scripts are not supported; use direct binary or archive download"
        )
      ))

  private def validationErrors(yaml: String): Vector[ValidationError] =
    ConfigModule.loadString(yaml) match
      case Left(ConfigLoadError.ValidationFailed(errors)) => errors
      case Left(error)    => abort(s"expected validation errors, got $error")
      case Right(profile) => abort(s"expected validation errors, got $profile")

  private def exampleProfile: BinaryDistributionProfile = ConfigModule.load(exampleConfigPath) match
    case Right(profile) => profile
    case Left(error)    => abort(s"expected valid example config, got $error")

  private def pinnedVersions(profile: BinaryDistributionProfile): Vector[(String, String)] =
    exampleToolNames.flatMap: name =>
      profile.spec.versions.get(name).collect:
        case VersionSource.Pinned(value) => name -> value

  private def dynamicLatestUrlNames(profile: BinaryDistributionProfile): Vector[String] =
    exampleToolNames.filter: name =>
      profile.spec.versions.get(name).exists:
        case VersionSource.Dynamic(DynamicVersionKind.LatestUrl, _) => true
        case _                                                      => false

  private def checksumFor(
      profile: BinaryDistributionProfile,
      toolName: String
  ): Option[ChecksumSpec] = toolNamed(profile, toolName).spec.download.checksum

  private def toolNamed(profile: BinaryDistributionProfile, name: String): PlanEntry =
    profile.spec.plan.find(_.name == name).getOrElse(abort(s"missing tool $name"))

  private def exampleWithSudoPolicy(
      allowSudoSymlinks: Boolean
  ): Either[ConfigLoadError, BinaryDistributionProfile] =
    val yaml = Files.readString(exampleConfigPath).replace(
      "allowSudoSymlinks: true",
      s"allowSudoSymlinks: $allowSudoSymlinks"
    )
    ConfigModule.loadString(yaml)

  private def errorAt(path: String)(error: ValidationError): Boolean = error.path == path

  private def abort(message: String): Nothing = throw java.lang.AssertionError(message)

  private def exampleConfigPath: Path = upwardPaths(Path.of("").toAbsolutePath)
    .map(_.resolve("config.example.yaml"))
    .find(Files.exists(_))
    .getOrElse(abort("could not locate config.example.yaml"))

  private def upwardPaths(start: Path): Iterator[Path] =
    Iterator.iterate(start)(_.getParent).takeWhile(_ != null)

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

  private val expectedPinnedVersions: Vector[(String, String)] = Vector(
    "yazi"                -> "v26.5.6",
    "zig"                 -> "0.15.2",
    "kind"                -> "0.31.0",
    "zellij"              -> "0.44.1",
    "helm"                -> "v3.21.2",
    "kustomize"           -> "v5.8.1",
    "lazygit"             -> "0.61.0",
    "jujutsu"             -> "0.40.0",
    "dotbot"              -> "v0.3.0",
    "nerd-font-installer" -> "v1.0.6"
  )

  private val expectedDynamicLatestUrlNames: Vector[String] = Vector(
    "minikube",
    "xplr",
    "neovide",
    "neovim"
  )

  private val invalidValuesYaml: String = """
                                            |apiVersion: example.invalid/v1
                                            |kind: WrongKind
                                            |metadata:
                                            |  name: invalid-values
                                            |spec:
                                            |  policy:
                                            |    appsDir: "${HOME}/.apps"
                                            |    allowSudoSymlinks: true
                                            |  vars: {}
                                            |  versions:
                                            |    alpha: "1.0.0"
                                            |  plan:
                                            |    - name: alpha
                                            |      kind: legacy-script
                                            |      spec:
                                            |        versionRef: alpha
                                            |        installDir: "${appsDir}/alpha"
                                            |        download:
                                            |          url: https://example.invalid/alpha.tar.rar
                                            |          filename: alpha.tar.rar
                                            |          archive:
                                            |            type: rar
                                            |            extract:
                                            |              files:
                                            |                - from: alpha
                                            |                  to: bin/alpha
                                            |        installer:
                                            |          shell: fish
                                            |          args: []
                                            |        executables:
                                            |          - path: bin/alpha
                                            |            mode: "755"
                                            |""".stripMargin

  private val duplicateNamesYaml: String = """
                                             |apiVersion: binstaller.io/v1alpha1
                                             |kind: BinaryDistributionProfile
                                             |metadata:
                                             |  name: duplicate-names
                                             |spec:
                                             |  policy:
                                             |    appsDir: "${HOME}/.apps"
                                             |  vars: {}
                                             |  versions:
                                             |    alpha: "1.0.0"
                                             |    beta: "2.0.0"
                                             |  plan:
                                             |    - name: alpha
                                             |      kind: binary-tool
                                             |      spec:
                                             |        versionRef: alpha
                                             |        installDir: "${appsDir}/alpha"
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
                                             |    - name: alpha
                                             |      kind: binary-tool
                                             |      spec:
                                             |        versionRef: alpha
                                             |        installDir: "${appsDir}/alpha-again"
                                             |        download:
                                             |          url: https://example.invalid/alpha-again
                                             |          filename: alpha-again
                                             |        executables:
                                             |          - path: bin/alpha
                                             |    - name: beta
                                             |      kind: binary-tool
                                             |      spec:
                                             |        versionRef: beta
                                             |        installDir: "${appsDir}/beta-again"
                                             |        download:
                                             |          url: https://example.invalid/beta-again
                                             |          filename: beta-again
                                             |        executables:
                                             |          - path: bin/beta
                                             |""".stripMargin

  private val unknownVersionYaml: String = """
                                             |apiVersion: binstaller.io/v1alpha1
                                             |kind: BinaryDistributionProfile
                                             |metadata:
                                             |  name: unknown-version
                                             |spec:
                                             |  policy:
                                             |    appsDir: "${HOME}/.apps"
                                             |  vars: {}
                                             |  versions:
                                             |    known: "1.0.0"
                                             |  plan:
                                             |    - name: alpha
                                             |      kind: binary-tool
                                             |      spec:
                                             |        versionRef: missing
                                             |        installDir: "${appsDir}/alpha"
                                             |        download:
                                             |          url: https://example.invalid/alpha
                                             |          filename: alpha
                                             |        executables:
                                             |          - path: bin/alpha
                                             |""".stripMargin

  private val unsupportedInstallerYaml: String =
    """
      |apiVersion: binstaller.io/v1alpha1
      |kind: BinaryDistributionProfile
      |metadata:
      |  name: unsupported-installer
      |spec:
      |  policy:
      |    appsDir: "${HOME}/.apps"
      |  vars: {}
      |  versions:
      |    alpha: "1.0.0"
      |  plan:
      |    - name: alpha
      |      kind: binary-tool
      |      spec:
      |        versionRef: alpha
      |        installDir: "${appsDir}/alpha"
      |        download:
      |          url: https://example.invalid/install.sh
      |          filename: install.sh
      |        installer:
      |          shell: sh
      |          args:
      |            - "${downloadPath}"
      |        executables:
      |          - path: bin/alpha
      |""".stripMargin

  private def sudoSymlinkYaml(allowSudoSymlinks: Boolean): String =
    s"""
       |apiVersion: binstaller.io/v1alpha1
       |kind: BinaryDistributionProfile
       |metadata:
       |  name: sudo-symlink
       |spec:
       |  policy:
       |    appsDir: "$${HOME}/.apps"
       |    allowSudoSymlinks: $allowSudoSymlinks
       |  vars: {}
       |  versions:
       |    alpha: "1.0.0"
       |  plan:
       |    - name: alpha
       |      kind: binary-tool
       |      spec:
       |        versionRef: alpha
       |        installDir: "$${appsDir}/alpha"
       |        download:
       |          url: https://example.invalid/alpha
       |          filename: alpha
       |        executables:
       |          - path: bin/alpha
       |        symlinks:
       |          - path: /usr/local/bin/alpha
       |            target: "$${appsDir}/alpha/bin/alpha"
       |            sudo: true
       |""".stripMargin
