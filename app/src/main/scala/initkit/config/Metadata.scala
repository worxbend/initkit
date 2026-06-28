package initkit.config

import scala.collection.immutable.VectorMap

final case class Metadata(
    name: Option[String],
    labels: VectorMap[String, String],
    annotations: VectorMap[String, String]
)

object Metadata:
  val empty: Metadata =
    Metadata(
      name = None,
      labels = VectorMap.empty,
      annotations = VectorMap.empty
    )
