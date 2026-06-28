package initkit.config

import java.nio.file.{Files, Path}

import utest.*

object ManifestLoaderTests extends TestSuite:
  val tests: Tests = Tests:
    test("loads config example into typed manifest fields"):
      val manifest = loadExample()

      assert(manifest.apiVersion.contains("initkit.io/v1alpha1"))
      assert(manifest.kind.contains("WorkstationProfile"))
      assert(manifest.metadata.name.contains("developer-workstation"))
      assert(manifest.metadata.labels("role") == "development")
      assert(manifest.spec.policy.flatMap(_.dryRun).contains(false))
      assert(manifest.spec.target.flatMap(_.os).flatMap(_.distribution).contains("ubuntu"))
      assert(manifest.spec.vars("stateFile").contains("developer-workstation.state.json"))
      assert(manifest.spec.sources.flatMap(_.apt).nonEmpty)
      assert(manifest.spec.plan.size == 16)

    test("preserves kind specific plan spec as raw yaml"):
      val manifest = loadExample()
      val entry = manifest.spec.plan.find(_.name.contains("direct-binaries")).get

      assert(entry.kind.contains("binary-downloads"))
      entry.spec match
        case Some(RawYaml.MappingValue(fields)) =>
          fields("items") match
            case RawYaml.SequenceValue(items) =>
              assert(items.size == 4)
              assert(items.head.asMapping.flatMap(_("name").asString).contains("kubectl"))
            case other => fail(s"expected items sequence, found $other")
        case other => fail(s"expected raw mapping spec, found $other")

    test("loads common execution and condition data"):
      val manifest = loadExample()
      val entry = manifest.spec.plan.find(_.name.contains("apt-base-cli")).get

      assert(entry.execution.flatMap(_.mode).contains("sequential"))
      assert(entry.execution.exists(_.locks == Vector("system-package-manager")))
      assert(entry.when.flatMap(_.os).flatMap(_.family).contains(MatchExpression.Exact("linux")))
      assert(
        entry.when
          .flatMap(_.os)
          .flatMap(_.distribution)
          .contains(MatchExpression.OneOf(Vector("debian", "ubuntu")))
      )

    test("reports yaml parse errors with config path"):
      val tmp = os.temp.dir()
      try
        val config = tmp / "broken.yaml"
        os.write(config, "apiVersion: [broken\n")

        val result = ManifestLoader.load(config.toNIO)

        assert(result.isLeft)
        val message = result.left.toOption.get.message
        assert(message.contains(config.toNIO.toAbsolutePath.normalize.toString))
        assert(message.contains("while parsing"))
      finally os.remove.all(tmp)

  private def loadExample(): Manifest =
    ManifestLoader.load(exampleConfigPath) match
      case Right(manifest) => manifest
      case Left(error)     => fail(error.message)

  private def exampleConfigPath: Path =
    Iterator
      .iterate(os.pwd.toNIO.toAbsolutePath.normalize)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("config.example.yaml"))
      .find(Files.isRegularFile(_))
      .getOrElse(throw new java.lang.AssertionError("config.example.yaml fixture not found"))

  private def fail(message: String): Nothing =
    throw new java.lang.AssertionError(message)
