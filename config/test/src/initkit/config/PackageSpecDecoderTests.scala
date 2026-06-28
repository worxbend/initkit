package initkit.config

import utest.*

object PackageSpecDecoderTests extends TestSuite:
  val tests: Tests = Tests:
    test("decodes valid apt package specs"):
      val spec = decodeValidPackageSpec(
        "apt-base",
        "apt-packages",
        """
        update: true
        install:
          - curl
        """
      )

      assert(spec == PackageSpec.Apt(update = Some(true), install = Vector("curl")))

    test("rejects invalid apt package specs"):
      val errors = validatePackageSpec("apt-empty", "apt-packages", emptyInstallSpec)

      assertEmptyInstallError(errors, "apt-empty")

    test("decodes valid pacman package specs"):
      val spec = decodeValidPackageSpec(
        "pacman-base",
        "pacman-packages",
        """
        sync: true
        install:
          - git
        """
      )

      assert(spec == PackageSpec.Pacman(sync = Some(true), install = Vector("git")))

    test("rejects invalid pacman package specs"):
      val errors = validatePackageSpec("pacman-empty", "pacman-packages", emptyInstallSpec)

      assertEmptyInstallError(errors, "pacman-empty")

    test("decodes valid dnf package specs"):
      val spec = decodeValidPackageSpec(
        "dnf-base",
        "dnf-packages",
        """
        install:
          - "@development-tools"
        """
      )

      assert(spec == PackageSpec.Dnf(install = Vector("@development-tools")))

    test("rejects invalid dnf package specs"):
      val errors = validatePackageSpec("dnf-empty", "dnf-packages", emptyInstallSpec)

      assertEmptyInstallError(errors, "dnf-empty")

    test("decodes valid zypper package specs"):
      val spec = decodeValidPackageSpec(
        "zypper-base",
        "zypper-packages",
        """
        refresh: true
        install:
          - patterns-devel-base-devel_basis
        """
      )

      assert(
        spec == PackageSpec.Zypper(
          refresh = Some(true),
          install = Vector("patterns-devel-base-devel_basis")
        )
      )

    test("rejects invalid zypper package specs"):
      val errors = validatePackageSpec("zypper-empty", "zypper-packages", emptyInstallSpec)

      assertEmptyInstallError(errors, "zypper-empty")

    test("decodes valid flatpak package specs"):
      val spec = decodeValidPackageSpec(
        "flatpak-apps",
        "flatpak-packages",
        """
        remote: flathub
        system: true
        install:
          - org.mozilla.firefox
        """
      )

      assert(
        spec == PackageSpec.Flatpak(
          remote = Some("flathub"),
          system = Some(true),
          install = Vector("org.mozilla.firefox")
        )
      )

    test("rejects invalid flatpak package specs"):
      val errors = validatePackageSpec("flatpak-empty", "flatpak-packages", emptyInstallSpec)

      assertEmptyInstallError(errors, "flatpak-empty")

    test("decodes valid snap package specs"):
      val spec = decodeValidPackageSpec(
        "snap-apps",
        "snap-packages",
        """
        install:
          - name: code
            classic: true
          - postman
        """
      )

      assert(
        spec == PackageSpec.Snap(
          install = Vector(
            SnapPackage(name = "code", classic = Some(true)),
            SnapPackage(name = "postman", classic = None)
          )
        )
      )

    test("rejects invalid snap package specs"):
      val errors = validatePackageSpec("snap-empty", "snap-packages", emptyInstallSpec)

      assertEmptyInstallError(errors, "snap-empty")

  private val emptyInstallSpec: String =
    """
    install: []
    """

  private def decodeValidPackageSpec(
      name: String,
      kind: String,
      spec: String
  ): PackageSpec =
    loadValidatedPackage(name, kind, spec) match
      case Right(entry) =>
        PackageSpecDecoder.decode(entry, index = 0) match
          case Right(spec)  => spec
          case Left(errors) => fail(s"expected package spec, found ${errors.map(_.message).mkString("; ")}")
      case Left(errors) => fail(s"expected valid manifest, found ${errors.map(_.message).mkString("; ")}")

  private def validatePackageSpec(
      name: String,
      kind: String,
      spec: String
  ): Vector[ManifestValidationError] =
    loadValidatedPackage(name, kind, spec).left.toOption.get

  private def loadValidatedPackage(
      name: String,
      kind: String,
      spec: String
  ): Either[Vector[ManifestValidationError], PlanEntry] =
    val tmp = os.temp.dir()
    try
      val config = tmp / "config.yaml"
      os.write(config, packageManifest(name, kind, spec))
      ManifestLoader.loadValidated(config.toNIO) match
        case Right(manifest) => Right(manifest.spec.plan.head)
        case Left(error: ManifestLoadError.ValidationFailure) => Left(error.errors)
        case Left(error) => fail(s"expected validation result, found ${error.message}")
    finally os.remove.all(tmp)

  private def packageManifest(
      name: String,
      kind: String,
      spec: String
  ): String =
    val header = stripYaml(s"""
    apiVersion: initkit.io/v1alpha1
    kind: WorkstationProfile
    spec:
      plan:
        - name: $name
          kind: $kind
          spec:
    """)
    s"$header\n${indent(spec, 8)}"

  private def assertEmptyInstallError(
      errors: Vector[ManifestValidationError],
      name: String
  ): Unit =
    assert(
      errors.exists(
        _.message == s"spec.plan[0].spec.install: plan entry '$name' must contain at least one package"
      )
    )

  private def indent(source: String, spaces: Int): String =
    val padding = " " * spaces
    stripYaml(source).split("\n").map(line => s"$padding$line").mkString("\n")

  private def stripYaml(source: String): String =
    val lines = source.replace("\r\n", "\n").split("\n").toVector
    val contentLines = lines.filter(_.trim.nonEmpty)
    val indent = contentLines.map(_.takeWhile(_ == ' ').length).minOption.getOrElse(0)

    lines.map(line => if line.length >= indent then line.drop(indent) else line).mkString("\n").trim

  private def fail(message: String): Nothing =
    throw new java.lang.AssertionError(message)
