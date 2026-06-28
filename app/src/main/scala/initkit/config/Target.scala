package initkit.config

final case class Target(os: Option[TargetOs])

final case class TargetOs(
    family: Option[String],
    distribution: Option[String],
    version: Option[String],
    codename: Option[String],
    architecture: Option[String],
    desktop: Option[String]
)
