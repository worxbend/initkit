package initkit.core

import java.nio.file.{Files, Path}

import initkit.config.Manifest
import initkit.host.HostFacts
import utest.*

object ResolutionCheckpointTests extends TestSuite:
  val tests: Tests = Tests:
    test("loads resolves and selects config example for an Ubuntu host"):
      val hostFacts = HostFacts.fake(
        distribution = Some("ubuntu"),
        version = Some("24.04"),
        codename = Some("noble"),
        architecture = "amd64",
        commands = Set("systemctl")
      )
      val manifest = loadResolvedExample(hostFacts)

      assert(manifest.metadata.name == Some("developer-workstation"))
      assert(manifest.spec.vars("home") == "/home/initkit-user")
      assert(
        manifest.spec.vars("stateFile") == "/home/initkit-user/.local/state/initkit/developer-workstation.state.json"
      )

      val selection = PlanSelector.select(
        manifest,
        PlanSelectionRequest.fromFilters(
          only = Vector.empty,
          skip = Vector.empty,
          completed = Vector.empty
        ),
        hostFacts
      )

      assert(runnableNames(selection) == Vector(
        "apt-base-cli",
        "relogin-after-shell-install",
        "apt-containers",
        "flatpak-desktop-apps",
        "snap-desktop-apps",
        "direct-binaries",
        "language-toolchains",
        "install-nerd-fonts",
        "apply-dotfiles",
        "post-install"
      ))
      assert(skippedNames(selection) == Vector(
        "pacman-base-cli",
        "dnf-base-cli",
        "zypper-base-cli",
        "pacman-containers",
        "dnf-containers",
        "zypper-containers"
      ))
      assert(selection.skipped.forall(_.userFacingReasons.nonEmpty))

  private def loadResolvedExample(hostFacts: HostFacts): Manifest =
    ManifestVariableResolver.loadValidatedResolved(
      exampleConfigPath,
      RuntimeVariables.from("HOME" -> "/home/initkit-user", "USER" -> "initkit-user"),
      hostFacts
    ) match
      case Right(manifest) => manifest
      case Left(error)     => fail(error.message)

  private def runnableNames(selection: PlanSelection): Vector[String] =
    selection.runnable.flatMap(_.entry.name)

  private def skippedNames(selection: PlanSelection): Vector[String] =
    selection.skipped.flatMap(_.entry.name)

  private def exampleConfigPath: Path =
    Iterator
      .iterate(Path.of("").toAbsolutePath.normalize())(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("config.example.yaml"))
      .find(Files.isRegularFile(_))
      .getOrElse(throw new java.lang.AssertionError("config.example.yaml fixture not found"))

  private def fail(message: String): Nothing =
    throw new java.lang.AssertionError(message)
