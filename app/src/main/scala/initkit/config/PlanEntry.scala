package initkit.config

final case class PlanEntry(
    name: Option[String],
    kind: Option[String],
    description: Option[String],
    execution: Option[Execution],
    when: Option[Condition],
    spec: Option[RawYaml]
)

final case class Execution(
    mode: Option[String],
    maxConcurrency: Option[Int],
    failFast: Option[Boolean],
    locks: Vector[String]
)

final case class Condition(
    os: Option[OsCondition],
    commandExists: Option[String],
    raw: RawYaml
)

final case class OsCondition(
    family: Option[MatchExpression],
    distribution: Option[MatchExpression],
    version: Option[MatchExpression],
    codename: Option[MatchExpression],
    architecture: Option[MatchExpression],
    desktop: Option[MatchExpression],
    raw: RawYaml
)

enum MatchExpression:
  case Exact(value: String)
  case OneOf(values: Vector[String])
