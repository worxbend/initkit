package initkit.core

import java.nio.file.{Files, Path}
import java.util.Comparator

import initkit.config.*
import initkit.host.HostFacts
import utest.*

object ManifestVariableResolverTests extends TestSuite:
  val tests: Tests = Tests:
    test("resolves runtime and nested spec variables in config example"):
      val home = "/home/initkit-user"
      val manifest = loadResolvedExample(home = home, user = "initkit-user")

      assert(manifest.spec.vars("user") == "initkit-user")
      assert(manifest.spec.vars("home") == home)
      assert(manifest.spec.vars("binDir") == s"$home/.local/bin")
      assert(manifest.spec.vars("stateFile") == s"$home/.local/state/initkit/developer-workstation.state.json")

      val nerdFonts = installerSpec(manifest, "install-nerd-fonts")
      nerdFonts match
        case InstallerSpec.NerdFonts(tool, config, _) =>
          assert(tool.path == s"$home/.local/bin/nerdfont-install")
          assert(tool.args == Vector("-config", s"$home/.config/nerd-config-installer/config.yaml"))
          assert(config.path == s"$home/.config/nerd-config-installer/config.yaml")
          assert(config.content.exists(destinationFromGeneratedConfig(_) == Some(s"$home/.local/share/fonts/NerdFonts")))
        case other => fail(s"expected nerd-fonts spec, found $other")

    test("resolves repeated variables and host facts in nested plan values"):
      withTempConfig(
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        spec:
          vars:
            root: "${HOME}/workspace"
            cache: "${root}/.cache"
          plan:
            - name: commands
              kind: commands
              spec:
                items:
                  - name: repeated
                    run: "install ${cache} ${cache} for ${host.architecture}"
        """
      ): config =>
        val manifest = loadResolved(config, home = "/home/alex", user = "alex")
        val commands = installerSpec(manifest, "commands")

        commands match
          case InstallerSpec.Commands(items) =>
            assert(items.head.run == "install /home/alex/workspace/.cache /home/alex/workspace/.cache for amd64")
          case other => fail(s"expected commands spec, found $other")

    test("reports unresolved variables with plan entry context"):
      withTempConfig(
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        spec:
          plan:
            - name: broken-command
              kind: commands
              spec:
                items:
                  - name: missing
                    run: "echo ${missingValue}"
        """
      ): config =>
        val result = ManifestVariableResolver.loadValidatedResolved(
          config,
          RuntimeVariables.from("HOME" -> "/home/alex", "USER" -> "alex"),
          HostFacts.fake()
        )

        assert(result.isLeft)
        result.left.toOption.get match
          case error: ManifestLoadError.ValidationFailure =>
            assert(error.path == config.toAbsolutePath.normalize())
            assert(
              error.errors.exists(_.message.contains("spec.plan[0].spec.items[0].run: unresolved variable '${missingValue}'"))
            )
            assert(error.errors.exists(_.message.contains("plan entry 'broken-command'")))
          case other => fail(s"expected validation failure, found $other")

    test("does not execute shell expansion or commands"):
      val tmp = Files.createTempDirectory("initkit-variable-resolution")
      try
        val touched = tmp.resolve("should-not-exist")
        val config = tmp.resolve("config.yaml")
        Files.writeString(
          config,
          stripYaml(s"""
          apiVersion: initkit.io/v1alpha1
          kind: WorkstationProfile
          spec:
            plan:
              - name: no-shell
                kind: commands
                spec:
                  items:
                    - name: literal-command-text
                      run: "$${HOME}/bin $$(touch $touched) `touch $touched`"
          """)
        )

        val manifest = loadResolved(config, home = "/home/alex", user = "alex")
        val commands = installerSpec(manifest, "no-shell")

        commands match
          case InstallerSpec.Commands(items) =>
            assert(items.head.run == s"/home/alex/bin $$(touch $touched) `touch $touched`")
            assert(!Files.exists(touched))
          case other => fail(s"expected commands spec, found $other")
      finally deleteRecursively(tmp)

  private def loadResolvedExample(home: String, user: String): Manifest =
    loadResolved(exampleConfigPath, home, user)

  private def loadResolved(config: Path, home: String, user: String): Manifest =
    ManifestVariableResolver.loadValidatedResolved(
      config,
      RuntimeVariables.from("HOME" -> home, "USER" -> user),
      HostFacts.fake(architecture = "amd64")
    ) match
      case Right(manifest) => manifest
      case Left(error)     => fail(error.message)

  private def installerSpec(manifest: Manifest, name: String): InstallerSpec =
    val index = manifest.spec.plan.indexWhere(_.name.contains(name))
    assert(index >= 0)
    InstallerSpecDecoder.decode(manifest.spec.plan(index), index) match
      case Right(spec)   => spec
      case Left(errors)  => fail(errors.map(_.message).mkString("; "))

  private def destinationFromGeneratedConfig(raw: RawYaml): Option[String] =
    for
      fields <- raw.asMapping
      destination <- fields.get("destination").flatMap(_.asString)
    yield destination

  private def withTempConfig(source: String)(run: Path => Unit): Unit =
    val tmp = Files.createTempDirectory("initkit-variable-resolution")
    try
      val config = tmp.resolve("config.yaml")
      Files.writeString(config, stripYaml(source))
      run(config)
    finally deleteRecursively(tmp)

  private def exampleConfigPath: Path =
    Iterator
      .iterate(Path.of("").toAbsolutePath.normalize())(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("config.example.yaml"))
      .find(Files.isRegularFile(_))
      .getOrElse(throw new java.lang.AssertionError("config.example.yaml fixture not found"))

  private def stripYaml(source: String): String =
    val lines = source.replace("\r\n", "\n").split("\n").toVector
    val nonEmpty = lines.dropWhile(_.trim.isEmpty).reverse.dropWhile(_.trim.isEmpty).reverse
    val indent = nonEmpty.filter(_.trim.nonEmpty).map(_.takeWhile(_ == ' ').length).minOption.getOrElse(0)
    nonEmpty.map(_.drop(indent)).mkString("\n")

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      val paths = Files.walk(path)
      try paths.sorted(Comparator.reverseOrder()).forEach(Files.delete)
      finally paths.close()

  private def fail(message: String): Nothing =
    throw new java.lang.AssertionError(message)
