package binstaller.config

import utest.*

import java.nio.file.Files
import java.nio.file.Path

object ConfigModuleTest extends TestSuite:

  val tests: Tests = Tests:
    test("module exposes its name"):
      assert(ConfigModule.moduleName == "config")

    test("config example loads into typed manifest"):
      val result = ConfigModule.load(exampleConfigPath)

      result match
        case Right(profile) =>
          assert(profile.apiVersion == ApiVersion.V1Alpha1)
          assert(profile.kind == ManifestKind.BinaryDistributionProfile)
          assert(profile.metadata.name == "developer-binaries")
          assert(profile.spec.plan.size == 15)
          assert(profile.spec.versions.contains("kubectl"))
          assert(profile.spec.policy.allowSudoSymlinks == AllowSudoSymlinks.Enabled)
        case Left(error) => abort(s"expected valid example config, got $error")

    test("invalid manifest values aggregate validation errors with YAML paths"):
      val errors = validationErrors(invalidValuesYaml)

      assert(errors.exists(errorAt("apiVersion")))
      assert(errors.exists(errorAt("kind")))
      assert(errors.exists(errorAt("spec.plan[0].kind")))
      assert(errors.exists(errorAt("spec.plan[0].spec.download.archive.type")))
      assert(errors.exists(errorAt("spec.plan[0].spec.installer.shell")))
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

  private def validationErrors(yaml: String): Vector[ValidationError] =
    ConfigModule.loadString(yaml) match
      case Left(ConfigLoadError.ValidationFailed(errors)) => errors
      case Left(error)    => abort(s"expected validation errors, got $error")
      case Right(profile) => abort(s"expected validation errors, got $profile")

  private def errorAt(path: String)(error: ValidationError): Boolean = error.path == path

  private def abort(message: String): Nothing = throw java.lang.AssertionError(message)

  private def exampleConfigPath: Path = upwardPaths(Path.of("").toAbsolutePath)
    .map(_.resolve("config.example.yaml"))
    .find(Files.exists(_))
    .getOrElse(abort("could not locate config.example.yaml"))

  private def upwardPaths(start: Path): Iterator[Path] =
    Iterator.iterate(start)(_.getParent).takeWhile(_ != null)

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
                                            |      kind: shell-script
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
                                            |          shell: zsh
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
