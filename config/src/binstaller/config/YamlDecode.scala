package binstaller.config

private[config] object YamlDecode:
  type YamlMap = Map[String, Any]

  def requiredMap(map: YamlMap, path: String): DecodeResult[YamlMap] =
    val key = path.split("\\.").last
    map.get(key) match
      case Some(value) => asMap(value, path)
      case None        => DecodeResult.invalid(Map.empty, path, "required map is missing")

  def requiredList(map: YamlMap, path: String): DecodeResult[Vector[Any]] =
    val key = path.split("\\.").last
    map.get(key) match
      case Some(value) => asList(value, path)
      case None        => DecodeResult.invalid(Vector.empty, path, "required list is missing")

  def optionalList(map: YamlMap, key: String, path: String): DecodeResult[Vector[Any]] =
    map.get(key) match
      case Some(value) => asList(value, path)
      case None        => DecodeResult.valid(Vector.empty)

  def optionalStringList(
      map: YamlMap,
      key: String,
      path: String
  ): DecodeResult[Vector[String]] =
    val list    = optionalList(map, key, path)
    val decoded = list.value.zipWithIndex.map:
      case (value: String, _) => DecodeResult.valid(value)
      case (_, index) => DecodeResult.invalid("", s"$path[$index]", "value must be a string")
    DecodeResult(decoded.map(_.value), list.errors ++ decoded.flatMap(_.errors))

  def optionalStringMap(
      map: YamlMap,
      key: String,
      path: String
  ): DecodeResult[Map[String, String]] = map.get(key) match
    case None        => DecodeResult.valid(Map.empty)
    case Some(value) =>
      val child   = asMap(value, path)
      val decoded = child.value.toVector.map:
        case (childKey, scalar: String) => childKey -> DecodeResult.valid(scalar)
        case (childKey, _)              => childKey ->
            DecodeResult.invalid("", s"$path.$childKey", "value must be a string")
      DecodeResult(
        decoded.map((childKey, result) => childKey -> result.value).toMap,
        child.errors ++ decoded.flatMap((_, result) => result.errors)
      )

  def requiredString(map: YamlMap, path: String): DecodeResult[String] =
    val key = path.split("\\.").last
    requiredString(map, key, path)

  def requiredString(map: YamlMap, key: String, path: String): DecodeResult[String] =
    map.get(key) match
      case Some(value: String) => DecodeResult.valid(value)
      case Some(_)             => DecodeResult.invalid("", path, "required string must be a string")
      case None                => DecodeResult.invalid("", path, "required string is missing")

  def optionalString(
      map: YamlMap,
      key: String,
      path: String
  ): DecodeResult[Option[String]] = map.get(key) match
    case None                => DecodeResult.valid(None)
    case Some(value: String) => DecodeResult.valid(Some(value))
    case Some(_)             => DecodeResult.invalid(None, path, "value must be a string")

  def optionalBoolean(
      map: YamlMap,
      key: String,
      path: String,
      default: Boolean
  ): DecodeResult[Boolean] = map.get(key) match
    case None                 => DecodeResult.valid(default)
    case Some(value: Boolean) => DecodeResult.valid(value)
    case Some(_)              => DecodeResult.invalid(default, path, "value must be a boolean")

  def asMap(value: Any, path: String): DecodeResult[YamlMap] = value match
    case map: YamlMap @unchecked => DecodeResult.valid(map)
    case _                       => DecodeResult.invalid(Map.empty, path, "value must be a map")

  def asList(value: Any, path: String): DecodeResult[Vector[Any]] = value match
    case list: Vector[?] => DecodeResult.valid(list.asInstanceOf[Vector[Any]])
    case _               => DecodeResult.invalid(Vector.empty, path, "value must be a list")

  def enumValue[A](
      input: DecodeResult[String],
      path: String,
      values: Vector[A],
      fallback: A,
      render: A => String
  ): DecodeResult[A] =
    if input.errors.nonEmpty then DecodeResult(fallback, input.errors)
    else
      values.find(value => render(value) == input.value) match
        case Some(value) => DecodeResult(value, input.errors)
        case None        => DecodeResult(
            fallback,
            input.errors :+ ValidationError(
              path,
              s"unsupported value '${input.value}', expected one of ${values.map(render).mkString(", ")}"
            )
          )

  def optionalEnumValue[A](
      input: DecodeResult[Option[String]],
      path: String,
      values: Vector[A],
      fallback: A,
      render: A => String
  ): DecodeResult[A] =
    if input.errors.nonEmpty then DecodeResult(fallback, input.errors)
    else
      input.value match
        case None        => DecodeResult.valid(fallback)
        case Some(value) => values.find(candidate => render(candidate) == value) match
            case Some(candidate) => DecodeResult.valid(candidate)
            case None            => DecodeResult(
                fallback,
                Vector(ValidationError(
                  path,
                  s"unsupported value '$value', expected one of ${values.map(render).mkString(", ")}"
                ))
              )
