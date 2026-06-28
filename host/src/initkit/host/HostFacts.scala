package initkit.host

final case class HostFacts(
    os: HostOs,
    architecture: String,
    commandAvailability: CommandAvailability
):
  def commandExists(command: String): Boolean =
    commandAvailability.exists(command)

final case class HostOs(
    family: String,
    distribution: Option[String],
    version: Option[String],
    codename: Option[String]
)

trait CommandAvailability:
  def exists(command: String): Boolean

object HostFacts:
  def fake(
      family: String = "linux",
      distribution: Option[String] = Some("ubuntu"),
      version: Option[String] = Some("24.04"),
      codename: Option[String] = Some("noble"),
      architecture: String = "amd64",
      commands: Set[String] = Set.empty
  ): HostFacts =
    HostFacts(
      os = HostOs(
        family = family,
        distribution = distribution,
        version = version,
        codename = codename
      ),
      architecture = architecture,
      commandAvailability = CommandAvailability.fromSet(commands)
    )

object CommandAvailability:
  def fromSet(commands: Set[String]): CommandAvailability =
    new CommandAvailability:
      override def exists(command: String): Boolean =
        commands.contains(command)
