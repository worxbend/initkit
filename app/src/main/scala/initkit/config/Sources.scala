package initkit.config

final case class Sources(
    apt: Option[RawYaml],
    dnf: Option[RawYaml],
    zypper: Option[RawYaml],
    flatpak: Option[RawYaml],
    raw: RawYaml
)
