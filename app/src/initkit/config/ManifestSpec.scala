package initkit.config

import scala.collection.immutable.VectorMap

final case class ManifestSpec(
    target: Option[Target],
    policy: Option[Policy],
    vars: VectorMap[String, String],
    sources: Option[Sources],
    plan: Vector[PlanEntry]
)

object ManifestSpec:
  val empty: ManifestSpec =
    ManifestSpec(
      target = None,
      policy = None,
      vars = VectorMap.empty,
      sources = None,
      plan = Vector.empty
    )
