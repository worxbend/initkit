package initkit.config

final case class Sources(
    apt: Option[AptSources],
    dnf: Option[DnfSources],
    zypper: Option[ZypperSources],
    flatpak: Option[FlatpakSources],
    raw: RawYaml
)

final case class AptSources(
    repositories: Vector[AptRepository],
    updateBeforeInstall: Option[Boolean]
)

final case class AptRepository(
    name: String,
    keyUrl: Option[String],
    source: String
)

final case class DnfSources(
    repositories: Vector[DnfRepository]
)

final case class DnfRepository(
    name: String,
    description: Option[String],
    baseUrl: String,
    gpgKey: Option[String]
)

final case class ZypperSources(
    repositories: Vector[ZypperRepository]
)

final case class ZypperRepository(
    name: String,
    url: String,
    autoRefresh: Option[Boolean]
)

final case class FlatpakSources(
    remotes: Vector[FlatpakRemote]
)

final case class FlatpakRemote(
    name: String,
    url: String,
    ifMissing: Option[Boolean]
)
