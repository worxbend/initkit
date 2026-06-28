package initkit.config

import utest.*

object ManifestValidatorTests extends TestSuite:
  val tests: Tests = Tests:
    test("validates the example manifest"):
      val result = ManifestLoader.loadValidated(ManifestLoaderTests.exampleConfigPath)

      assert(result.isRight)

    test("reports unsupported top level api version and kind"):
      val errors = validateYaml(
        """
        apiVersion: initkit.io/v1beta1
        kind: OtherProfile
        spec:
          plan:
            - name: base
              kind: apt-packages
              spec:
                install:
                  - curl
        """
      )

      assert(errors.exists(_.message.contains("apiVersion: unsupported apiVersion 'initkit.io/v1beta1'")))
      assert(errors.exists(_.message.contains("kind: unsupported kind 'OtherProfile'")))

    test("reports missing and duplicate plan names with plan entry context"):
      val errors = validateYaml(
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        spec:
          plan:
            - kind: apt-packages
              spec:
                install:
                  - curl
            - name: base
              kind: apt-packages
              spec:
                install:
                  - git
            - name: base
              kind: pacman-packages
              spec:
                install:
                  - git
        """
      )

      assert(errors.exists(_.message == "spec.plan[0].name: is required"))
      assert(errors.exists(_.message.contains("spec.plan[2].name: duplicate plan name 'base'")))
      assert(errors.exists(_.message.contains("spec.plan[1].name")))

    test("reports unknown plan kinds and invalid execution settings"):
      val errors = validateYaml(
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        spec:
          plan:
            - name: broken
              kind: custom-installer
              execution:
                mode: sideways
                maxConcurrency: 0
              spec: {}
        """
      )

      assert(errors.exists(_.message.contains("spec.plan[0].kind: unsupported plan kind 'custom-installer'")))
      assert(errors.exists(_.message.contains("spec.plan[0].execution.mode: unsupported execution mode 'sideways'")))
      assert(errors.exists(_.message.contains("spec.plan[0].execution.maxConcurrency: must be at least 1")))

    test("rejects sequential max concurrency greater than one"):
      val errors = validateYaml(
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        spec:
          plan:
            - name: base
              kind: apt-packages
              execution:
                mode: sequential
                maxConcurrency: 2
              spec:
                install:
                  - curl
        """
      )

      assert(
        errors.exists(
          _.message.contains("spec.plan[0].execution.maxConcurrency: can only be greater than 1")
        )
      )

    test("accepts omitted execution settings using defaults"):
      val result = loadValidatedYaml(
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        spec:
          plan:
            - name: base
              kind: apt-packages
              spec:
                install:
                  - curl
        """
      )

      assert(result.isRight)

    test("rejects empty package install lists"):
      val errors = validateYaml(
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        spec:
          plan:
            - name: base
              kind: apt-packages
              spec:
                install: []
        """
      )

      assert(errors.exists(_.message == "spec.plan[0].spec.install: must contain at least one package"))

    test("rejects unsupported checksum algorithms"):
      val errors = validateYaml(
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        spec:
          plan:
            - name: direct-binaries
              kind: binary-downloads
              spec:
                items:
                  - name: tool
                    checksum:
                      algorithm: md5
                      value: abc
        """
      )

      assert(
        errors.exists(
          _.message == "spec.plan[0].spec.items[0].checksum.algorithm: unsupported checksum algorithm 'md5'"
        )
      )

    test("rejects invalid interrupt state shape and manifest state path reuse"):
      val tmp = os.temp.dir()
      try
        val config = tmp / "config.yaml"
        os.write(
          config,
          stripYaml(s"""
          apiVersion: initkit.io/v1alpha1
          kind: WorkstationProfile
          spec:
            plan:
              - name: pause
                kind: interrupt
                spec:
                  state:
                    path: "${config.toNIO.toAbsolutePath.normalize}"
                    format: yaml
          """)
        )

        val errors = loadValidated(config).left.toOption.get

        assert(errors.exists(_.message == "spec.plan[0].spec.state.format: unsupported state format 'yaml'"))
        assert(errors.exists(_.message == "spec.plan[0].spec.state.path: must not point to the manifest file"))
      finally os.remove.all(tmp)

    test("reports multiple obvious validation errors in one pass"):
      val errors = validateYaml(
        """
        apiVersion: wrong
        kind: Wrong
        spec:
          plan:
            - name: duplicate
              kind: unknown-kind
              execution:
                mode: sideways
              spec: {}
            - name: duplicate
              kind: apt-packages
              spec:
                install: []
        """
      )

      assert(errors.size >= 5)
      assert(errors.exists(_.message.contains("apiVersion: unsupported apiVersion 'wrong'")))
      assert(errors.exists(_.message.contains("kind: unsupported kind 'Wrong'")))
      assert(errors.exists(_.message.contains("spec.plan[0].kind: unsupported plan kind 'unknown-kind'")))
      assert(errors.exists(_.message.contains("spec.plan[1].name: duplicate plan name 'duplicate'")))
      assert(errors.exists(_.message.contains("spec.plan[1].spec.install: must contain at least one package")))

  private def validateYaml(source: String): Vector[ManifestValidationError] =
    loadValidatedYaml(source).left.toOption.get

  private def loadValidatedYaml(source: String): Either[Vector[ManifestValidationError], Manifest] =
    val tmp = os.temp.dir()
    try
      val config = tmp / "config.yaml"
      os.write(config, stripYaml(source))
      loadValidated(config)
    finally os.remove.all(tmp)

  private def loadValidated(config: os.Path): Either[Vector[ManifestValidationError], Manifest] =
    ManifestLoader.loadValidated(config.toNIO) match
      case Right(manifest) => Right(manifest)
      case Left(error: ManifestLoadError.ValidationFailure) => Left(error.errors)
      case Left(error) => fail(s"expected validation result, found ${error.message}")

  private def stripYaml(source: String): String =
    val lines = source.replace("\r\n", "\n").split("\n").toVector
    val contentLines = lines.filter(_.trim.nonEmpty)
    val indent = contentLines.map(_.takeWhile(_ == ' ').length).minOption.getOrElse(0)

    lines.map(line => if line.length >= indent then line.drop(indent) else line).mkString("\n").trim

  private def fail(message: String): Nothing =
    throw new java.lang.AssertionError(message)
