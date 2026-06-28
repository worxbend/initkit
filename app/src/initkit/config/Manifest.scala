package initkit.config

final case class Manifest(
    apiVersion: Option[String],
    kind: Option[String],
    metadata: Metadata,
    spec: ManifestSpec
)
