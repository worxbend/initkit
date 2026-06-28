package initkit.host

import java.nio.file.{Path, Paths}

object HostDetector:
  private val OsReleasePath = Paths.get("/etc/os-release")

  def detect(system: HostSystem = HostSystem.live): HostFacts =
    val osRelease = readOsRelease(system)

    HostFacts(
      os = HostOs(
        family = detectFamily(system.osName),
        distribution = osRelease.flatMap(_.distribution),
        version = osRelease.flatMap(_.version),
        codename = osRelease.flatMap(_.codename)
      ),
      architecture = normalizeArchitecture(system.osArch),
      commandAvailability = pathCommandAvailability(system)
    )

  def normalizeArchitecture(value: String): String =
    value.trim.toLowerCase match
      case "x86_64" | "amd64"                  => "amd64"
      case "aarch64" | "arm64"                 => "arm64"
      case "armv7" | "armv7l" | "armhf"        => "armv7"
      case "i386" | "i486" | "i586" | "i686"   => "386"
      case "ppc64le" | "powerpc64le"           => "ppc64le"
      case "s390x"                             => "s390x"
      case ""                                  => "unknown"
      case other                               => other

  private def readOsRelease(system: HostSystem): Option[OsRelease] =
    system.readFile(OsReleasePath).map(parseOsRelease)

  private def detectFamily(osName: String): String =
    val normalized = osName.trim.toLowerCase
    if normalized.contains("linux") then "linux"
    else if normalized.contains("mac") || normalized.contains("darwin") then "macos"
    else if normalized.contains("windows") then "windows"
    else if normalized.isEmpty then "unknown"
    else normalized

  private def parseOsRelease(source: String): OsRelease =
    val values = source
      .replace("\r\n", "\n")
      .split("\n")
      .toVector
      .flatMap(parseOsReleaseLine)
      .toMap

    OsRelease(
      distribution = normalizedValue(values.get("ID")),
      version = nonEmpty(values.get("VERSION_ID")),
      codename = normalizedValue(values.get("VERSION_CODENAME").orElse(values.get("UBUNTU_CODENAME")))
    )

  private def parseOsReleaseLine(line: String): Option[(String, String)] =
    val trimmed = line.trim
    if trimmed.isEmpty || trimmed.startsWith("#") then None
    else
      trimmed.indexOf('=') match
        case -1 => None
        case index =>
          val key = trimmed.take(index).trim
          val value = trimmed.drop(index + 1).trim
          Option.when(key.nonEmpty)(key -> unquote(value))

  private def unquote(value: String): String =
    if isQuoted(value, '"') || isQuoted(value, '\'') then
      unescapeQuoted(value.drop(1).dropRight(1))
    else value

  private def isQuoted(value: String, quote: Char): Boolean =
    value.length >= 2 && value.head == quote && value.last == quote

  private def unescapeQuoted(value: String): String =
    value
      .replace("\\\"", "\"")
      .replace("\\'", "'")
      .replace("\\\\", "\\")
      .replace("\\$", "$")
      .replace("\\`", "`")

  private def pathCommandAvailability(system: HostSystem): CommandAvailability =
    new CommandAvailability:
      override def exists(command: String): Boolean =
        command.trim.nonEmpty &&
          !command.contains("/") &&
          !command.contains("\\") &&
          pathDirectories(system).exists(directory =>
            system.isExecutableRegularFile(directory.resolve(command))
          )

  private def pathDirectories(system: HostSystem): Vector[Path] =
    system
      .env("PATH")
      .toVector
      .flatMap(_.split(java.util.regex.Pattern.quote(system.pathSeparator)).toVector)
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(Paths.get(_))

  private def normalizedValue(value: Option[String]): Option[String] =
    nonEmpty(value).map(_.toLowerCase)

  private def nonEmpty(value: Option[String]): Option[String] =
    value.map(_.trim).filter(_.nonEmpty)

  private final case class OsRelease(
      distribution: Option[String],
      version: Option[String],
      codename: Option[String]
  )
