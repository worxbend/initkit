package initkit.host

import java.nio.file.{Path, Paths}

import utest.*

object HostDetectorTests extends TestSuite:
  val tests: Tests = Tests:
    test("reads linux distribution fields from os release"):
      val system = FakeHostSystem(
        osName = "Linux",
        osArch = "x86_64",
        files = Map(
          Paths.get("/etc/os-release") ->
            """
            NAME="Ubuntu"
            ID=ubuntu
            VERSION_ID="24.04"
            VERSION_CODENAME=noble
            """
        )
      )

      val facts = HostDetector.detect(system)

      assert(facts.os.family == "linux")
      assert(facts.os.distribution.contains("ubuntu"))
      assert(facts.os.version.contains("24.04"))
      assert(facts.os.codename.contains("noble"))
      assert(facts.architecture == "amd64")

    test("uses ubuntu codename fallback from representative os release data"):
      val system = FakeHostSystem(
        osName = "Linux",
        osArch = "aarch64",
        files = Map(
          Paths.get("/etc/os-release") ->
            """
            ID=ubuntu
            VERSION_ID="22.04"
            UBUNTU_CODENAME=jammy
            """
        )
      )

      val facts = HostDetector.detect(system)

      assert(facts.os.distribution.contains("ubuntu"))
      assert(facts.os.version.contains("22.04"))
      assert(facts.os.codename.contains("jammy"))
      assert(facts.architecture == "arm64")

    test("normalizes representative architecture names"):
      assert(HostDetector.normalizeArchitecture("x86_64") == "amd64")
      assert(HostDetector.normalizeArchitecture("amd64") == "amd64")
      assert(HostDetector.normalizeArchitecture("aarch64") == "arm64")
      assert(HostDetector.normalizeArchitecture("arm64") == "arm64")
      assert(HostDetector.normalizeArchitecture("i686") == "386")

    test("checks command availability through PATH entries"):
      val system = FakeHostSystem(
        osName = "Linux",
        osArch = "x86_64",
        envVars = Map("PATH" -> "/usr/bin:/opt/bin"),
        executableFiles = Set(Paths.get("/opt/bin/git"))
      )

      val facts = HostDetector.detect(system)

      assert(facts.commandExists("git"))
      assert(!facts.commandExists("curl"))
      assert(!facts.commandExists("/opt/bin/git"))

    test("supports fake host facts for condition tests"):
      val facts = HostFacts.fake(
        distribution = Some("fedora"),
        version = Some("41"),
        codename = None,
        architecture = "arm64",
        commands = Set("dnf", "systemctl")
      )

      assert(facts.os.family == "linux")
      assert(facts.os.distribution.contains("fedora"))
      assert(facts.os.version.contains("41"))
      assert(facts.os.codename.isEmpty)
      assert(facts.architecture == "arm64")
      assert(facts.commandExists("dnf"))
      assert(!facts.commandExists("apt"))

  private final case class FakeHostSystem(
      osName: String,
      osArch: String,
      envVars: Map[String, String] = Map.empty,
      files: Map[Path, String] = Map.empty,
      executableFiles: Set[Path] = Set.empty,
      pathSeparator: String = ":"
  ) extends HostSystem:
    override def env(name: String): Option[String] =
      envVars.get(name)

    override def readFile(path: Path): Option[String] =
      files.get(path)

    override def isExecutableRegularFile(path: Path): Boolean =
      executableFiles.contains(path)
