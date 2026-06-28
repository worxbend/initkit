package initkit.config

import scala.collection.immutable.VectorMap

enum RawYaml:
  case NullValue
  case StringValue(value: String)
  case BooleanValue(value: Boolean)
  case IntegerValue(value: BigInt)
  case DecimalValue(value: BigDecimal)
  case SequenceValue(items: Vector[RawYaml])
  case MappingValue(fields: VectorMap[String, RawYaml])

  def asMapping: Option[VectorMap[String, RawYaml]] =
    this match
      case RawYaml.MappingValue(fields) => Some(fields)
      case _                            => None

  def asSequence: Option[Vector[RawYaml]] =
    this match
      case RawYaml.SequenceValue(items) => Some(items)
      case _                            => None

  def asString: Option[String] =
    this match
      case RawYaml.StringValue(value) => Some(value)
      case _                          => None
