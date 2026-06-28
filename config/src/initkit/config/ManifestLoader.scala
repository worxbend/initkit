package initkit.config

import java.math.{BigDecimal as JavaBigDecimal, BigInteger}
import java.nio.file.{Files, Path}
import scala.collection.immutable.VectorMap
import scala.jdk.CollectionConverters.*
import scala.util.Try

import org.snakeyaml.engine.v2.api.{Load, LoadSettings}

object ManifestLoader:

  import ManifestLoadError.*
  import RawYaml.*

  private type DecodeResult[A] = Either[String, A]

  /**
   * Loads YAML into the raw manifest model without semantic validation.
   *
   * Callers that execute or preview plans should use `loadValidated`, or a higher-level resolver
   * that validates after variable interpolation, so path-sensitive and kind-specific invariants are
   * enforced before execution.
   */
  def load(path: Path): Either[ManifestLoadError, Manifest] =
    val normalizedPath = path.toAbsolutePath.normalize()

    readYaml(normalizedPath)
      .flatMap(parseYaml(normalizedPath, _))
      .flatMap(decodeManifest(normalizedPath, _))

  /**
   * Loads and validates a manifest against the typed execution contracts.
   *
   * The returned manifest still preserves raw plan specs; package and installer specs are decoded
   * on demand by their decoders so future executors share the same validation boundary.
   */
  def loadValidated(path: Path): Either[ManifestLoadError, Manifest] =
    val normalizedPath = path.toAbsolutePath.normalize()

    load(normalizedPath).flatMap { manifest =>
      ManifestValidator
        .validate(manifest, Some(normalizedPath))
        .left
        .map(errors => ValidationFailure(normalizedPath, errors))
    }

  private def readYaml(path: Path): Either[ManifestLoadError, String] =
    Try(Files.readString(path)).toEither.left.map(error => ReadFailure(path, safeMessage(error)))

  private def parseYaml(path: Path, source: String): Either[ManifestLoadError, RawYaml] = Try {
    val settings = LoadSettings.builder().build()
    val loaded   = new Load(settings).loadFromString(source)
    toRawYaml(loaded)
  }.toEither.left
    .map(error => ParseFailure(path, safeMessage(error)))
    .flatMap(_.left.map(error => ShapeFailure(path, error)))

  private def decodeManifest(path: Path, raw: RawYaml): Either[ManifestLoadError, Manifest] =
    decodeManifest(raw).left.map(error => ShapeFailure(path, error))

  private def decodeManifest(raw: RawYaml): DecodeResult[Manifest] =
    for
      fields     <- mapping(raw, "manifest")
      apiVersion <- optionalScalarString(fields, "apiVersion", "apiVersion")
      kind       <- optionalScalarString(fields, "kind", "kind")
      metadata   <- optionalMapping(fields, "metadata", "metadata").flatMap:
        case Some(value) => decodeMetadata(value).map(Some(_))
        case None        => Right(None)
      spec <- optionalMapping(fields, "spec", "spec").flatMap:
        case Some(value) => decodeSpec(value).map(Some(_))
        case None        => Right(None)
    yield Manifest(
      apiVersion = apiVersion,
      kind = kind,
      metadata = metadata.getOrElse(Metadata.empty),
      spec = spec.getOrElse(ManifestSpec.empty)
    )

  private def decodeMetadata(raw: RawYaml): DecodeResult[Metadata] =
    for
      fields      <- mapping(raw, "metadata")
      name        <- optionalScalarString(fields, "name", "metadata.name")
      labels      <- optionalStringMap(fields, "labels", "metadata.labels")
      annotations <- optionalStringMap(fields, "annotations", "metadata.annotations")
    yield Metadata(
      name = name,
      labels = labels.getOrElse(VectorMap.empty),
      annotations = annotations.getOrElse(VectorMap.empty)
    )

  private def decodeSpec(raw: RawYaml): DecodeResult[ManifestSpec] =
    for
      fields <- mapping(raw, "spec")
      target <- optionalMapping(fields, "target", "spec.target").flatMap:
        case Some(value) => decodeTarget(value).map(Some(_))
        case None        => Right(None)
      policy <- optionalMapping(fields, "policy", "spec.policy").flatMap:
        case Some(value) => decodePolicy(value).map(Some(_))
        case None        => Right(None)
      vars    <- optionalStringMap(fields, "vars", "spec.vars")
      sources <- optionalMapping(fields, "sources", "spec.sources").flatMap:
        case Some(value) => decodeSources(value).map(Some(_))
        case None        => Right(None)
      plan <- optionalSequence(fields, "plan", "spec.plan").flatMap:
        case Some(items) => decodePlan(items)
        case None        => Right(Vector.empty)
    yield ManifestSpec(
      target = target,
      policy = policy,
      vars = vars.getOrElse(VectorMap.empty),
      sources = sources,
      plan = plan
    )

  private def decodeTarget(raw: RawYaml): DecodeResult[Target] =
    for
      fields <- mapping(raw, "spec.target")
      os     <- optionalMapping(fields, "os", "spec.target.os").flatMap:
        case Some(value) => decodeTargetOs(value).map(Some(_))
        case None        => Right(None)
    yield Target(os = os)

  private def decodeTargetOs(raw: RawYaml): DecodeResult[TargetOs] =
    for
      fields       <- mapping(raw, "spec.target.os")
      family       <- optionalScalarString(fields, "family", "spec.target.os.family")
      distribution <- optionalScalarString(fields, "distribution", "spec.target.os.distribution")
      version      <- optionalScalarString(fields, "version", "spec.target.os.version")
      codename     <- optionalScalarString(fields, "codename", "spec.target.os.codename")
      architecture <- optionalScalarString(fields, "architecture", "spec.target.os.architecture")
      desktop      <- optionalScalarString(fields, "desktop", "spec.target.os.desktop")
    yield TargetOs(
      family = family,
      distribution = distribution,
      version = version,
      codename = codename,
      architecture = architecture,
      desktop = desktop
    )

  private def decodePolicy(raw: RawYaml): DecodeResult[Policy] =
    for
      fields          <- mapping(raw, "spec.policy")
      dryRun          <- optionalBoolean(fields, "dryRun", "spec.policy.dryRun")
      continueOnError <- optionalBoolean(fields, "continueOnError", "spec.policy.continueOnError")
      requireSudo     <- optionalBoolean(fields, "requireSudo", "spec.policy.requireSudo")
      reboot          <- optionalMapping(fields, "reboot", "spec.policy.reboot").flatMap:
        case Some(value) => decodeRebootPolicy(value).map(Some(_))
        case None        => Right(None)
    yield Policy(
      dryRun = dryRun,
      continueOnError = continueOnError,
      requireSudo = requireSudo,
      reboot = reboot
    )

  private def decodeRebootPolicy(raw: RawYaml): DecodeResult[RebootPolicy] =
    for
      fields  <- mapping(raw, "spec.policy.reboot")
      allowed <- optionalBoolean(fields, "allowed", "spec.policy.reboot.allowed")
      prompt  <- optionalBoolean(fields, "prompt", "spec.policy.reboot.prompt")
    yield RebootPolicy(
      allowed = allowed,
      prompt = prompt
    )

  private def decodeSources(raw: RawYaml): DecodeResult[Sources] =
    for
      fields <- mapping(raw, "spec.sources")
      apt    <- optionalMapping(fields, "apt", "spec.sources.apt").flatMap:
        case Some(value) => decodeAptSources(value).map(Some(_))
        case None        => Right(None)
      dnf <- optionalMapping(fields, "dnf", "spec.sources.dnf").flatMap:
        case Some(value) => decodeDnfSources(value).map(Some(_))
        case None        => Right(None)
      zypper <- optionalMapping(fields, "zypper", "spec.sources.zypper").flatMap:
        case Some(value) => decodeZypperSources(value).map(Some(_))
        case None        => Right(None)
      flatpak <- optionalMapping(fields, "flatpak", "spec.sources.flatpak").flatMap:
        case Some(value) => decodeFlatpakSources(value).map(Some(_))
        case None        => Right(None)
    yield Sources(
      apt = apt,
      dnf = dnf,
      zypper = zypper,
      flatpak = flatpak,
      raw = raw
    )

  private def decodeAptSources(raw: RawYaml): DecodeResult[AptSources] =
    for
      fields       <- mapping(raw, "spec.sources.apt")
      repositories <-
        optionalSequence(fields, "repositories", "spec.sources.apt.repositories").flatMap:
          case Some(items) => decodeAptRepositories(items)
          case None        => Right(Vector.empty)
      updateBeforeInstall <-
        optionalBoolean(fields, "updateBeforeInstall", "spec.sources.apt.updateBeforeInstall")
    yield AptSources(
      repositories = repositories,
      updateBeforeInstall = updateBeforeInstall
    )

  private def decodeAptRepositories(items: Vector[RawYaml]): DecodeResult[Vector[AptRepository]] =
    sequence(items.zipWithIndex.map((item, index) =>
      decodeAptRepository(item, s"spec.sources.apt.repositories[$index]")
    ))

  private def decodeAptRepository(raw: RawYaml, at: String): DecodeResult[AptRepository] =
    for
      fields <- mapping(raw, at)
      name   <- requiredScalarString(fields, "name", s"$at.name")
      keyUrl <- optionalScalarString(fields, "keyUrl", s"$at.keyUrl")
      source <- requiredScalarString(fields, "source", s"$at.source")
    yield AptRepository(
      name = name,
      keyUrl = keyUrl,
      source = source
    )

  private def decodeDnfSources(raw: RawYaml): DecodeResult[DnfSources] =
    for
      fields       <- mapping(raw, "spec.sources.dnf")
      repositories <-
        optionalSequence(fields, "repositories", "spec.sources.dnf.repositories").flatMap:
          case Some(items) => decodeDnfRepositories(items)
          case None        => Right(Vector.empty)
      releasePackages <-
        optionalSequence(fields, "releasePackages", "spec.sources.dnf.releasePackages").flatMap:
          case Some(items) => decodeReleasePackages(items, "spec.sources.dnf.releasePackages")
          case None        => Right(Vector.empty)
      keyImports <- optionalSequence(fields, "keyImports", "spec.sources.dnf.keyImports").flatMap:
        case Some(items) => decodeGpgKeyImports(items, "spec.sources.dnf.keyImports")
        case None        => Right(Vector.empty)
      commands <- optionalSequence(fields, "commands", "spec.sources.dnf.commands").flatMap:
        case Some(items) => decodeSourceCommands(items, "spec.sources.dnf.commands")
        case None        => Right(Vector.empty)
    yield DnfSources(
      repositories = repositories,
      releasePackages = releasePackages,
      keyImports = keyImports,
      commands = commands
    )

  private def decodeDnfRepositories(items: Vector[RawYaml]): DecodeResult[Vector[DnfRepository]] =
    sequence(items.zipWithIndex.map((item, index) =>
      decodeDnfRepository(item, s"spec.sources.dnf.repositories[$index]")
    ))

  private def decodeDnfRepository(raw: RawYaml, at: String): DecodeResult[DnfRepository] =
    for
      fields      <- mapping(raw, at)
      name        <- requiredScalarString(fields, "name", s"$at.name")
      description <- optionalScalarString(fields, "description", s"$at.description")
      baseUrl     <- requiredScalarString(fields, "baseUrl", s"$at.baseUrl")
      gpgKey      <- optionalScalarString(fields, "gpgKey", s"$at.gpgKey")
    yield DnfRepository(
      name = name,
      description = description,
      baseUrl = baseUrl,
      gpgKey = gpgKey
    )

  private def decodeZypperSources(raw: RawYaml): DecodeResult[ZypperSources] =
    for
      fields       <- mapping(raw, "spec.sources.zypper")
      repositories <-
        optionalSequence(fields, "repositories", "spec.sources.zypper.repositories").flatMap:
          case Some(items) => decodeZypperRepositories(items)
          case None        => Right(Vector.empty)
      keyImports <-
        optionalSequence(fields, "keyImports", "spec.sources.zypper.keyImports").flatMap:
          case Some(items) => decodeGpgKeyImports(items, "spec.sources.zypper.keyImports")
          case None        => Right(Vector.empty)
      commands <- optionalSequence(fields, "commands", "spec.sources.zypper.commands").flatMap:
        case Some(items) => decodeSourceCommands(items, "spec.sources.zypper.commands")
        case None        => Right(Vector.empty)
    yield ZypperSources(repositories = repositories, keyImports = keyImports, commands = commands)

  private def decodeZypperRepositories(items: Vector[RawYaml])
      : DecodeResult[Vector[ZypperRepository]] = sequence(
    items.zipWithIndex.map((item, index) =>
      decodeZypperRepository(item, s"spec.sources.zypper.repositories[$index]")
    )
  )

  private def decodeZypperRepository(raw: RawYaml, at: String): DecodeResult[ZypperRepository] =
    for
      fields      <- mapping(raw, at)
      name        <- requiredScalarString(fields, "name", s"$at.name")
      url         <- requiredScalarString(fields, "url", s"$at.url")
      autoRefresh <- optionalBoolean(fields, "autoRefresh", s"$at.autoRefresh")
    yield ZypperRepository(
      name = name,
      url = url,
      autoRefresh = autoRefresh
    )

  private def decodeFlatpakSources(raw: RawYaml): DecodeResult[FlatpakSources] =
    for
      fields  <- mapping(raw, "spec.sources.flatpak")
      remotes <- optionalSequence(fields, "remotes", "spec.sources.flatpak.remotes").flatMap:
        case Some(items) => decodeFlatpakRemotes(items)
        case None        => Right(Vector.empty)
    yield FlatpakSources(remotes = remotes)

  private def decodeFlatpakRemotes(items: Vector[RawYaml]): DecodeResult[Vector[FlatpakRemote]] =
    sequence(items.zipWithIndex.map((item, index) =>
      decodeFlatpakRemote(item, s"spec.sources.flatpak.remotes[$index]")
    ))

  private def decodeFlatpakRemote(raw: RawYaml, at: String): DecodeResult[FlatpakRemote] =
    for
      fields    <- mapping(raw, at)
      name      <- requiredScalarString(fields, "name", s"$at.name")
      url       <- requiredScalarString(fields, "url", s"$at.url")
      ifMissing <- optionalBoolean(fields, "ifMissing", s"$at.ifMissing")
    yield FlatpakRemote(
      name = name,
      url = url,
      ifMissing = ifMissing
    )

  private def decodeReleasePackages(
      items: Vector[RawYaml],
      at: String
  ): DecodeResult[Vector[ReleasePackage]] = sequence(
    items.zipWithIndex.map((item, index) => decodeReleasePackage(item, s"$at[$index]"))
  )

  private def decodeReleasePackage(raw: RawYaml, at: String): DecodeResult[ReleasePackage] =
    for
      fields <- mapping(raw, at)
      name   <- requiredScalarString(fields, "name", s"$at.name")
      url    <- requiredScalarString(fields, "url", s"$at.url")
    yield ReleasePackage(name, url)

  private def decodeGpgKeyImports(
      items: Vector[RawYaml],
      at: String
  ): DecodeResult[Vector[GpgKeyImport]] = sequence(
    items.zipWithIndex.map((item, index) => decodeGpgKeyImport(item, s"$at[$index]"))
  )

  private def decodeGpgKeyImport(raw: RawYaml, at: String): DecodeResult[GpgKeyImport] =
    for
      fields <- mapping(raw, at)
      name   <- requiredScalarString(fields, "name", s"$at.name")
      url    <- requiredScalarString(fields, "url", s"$at.url")
    yield GpgKeyImport(name, url)

  private def decodeSourceCommands(
      items: Vector[RawYaml],
      at: String
  ): DecodeResult[Vector[SourceCommand]] = sequence(
    items.zipWithIndex.map((item, index) => decodeSourceCommand(item, s"$at[$index]"))
  )

  private def decodeSourceCommand(raw: RawYaml, at: String): DecodeResult[SourceCommand] =
    for
      fields <- mapping(raw, at)
      name   <- requiredScalarString(fields, "name", s"$at.name")
      run    <- requiredScalarString(fields, "run", s"$at.run")
      sudo   <- optionalBoolean(fields, "sudo", s"$at.sudo")
    yield SourceCommand(name, run, sudo)

  private def decodePlan(items: Vector[RawYaml]): DecodeResult[Vector[PlanEntry]] =
    sequence(items.zipWithIndex.map((item, index) => decodePlanEntry(item, s"spec.plan[$index]")))

  private def decodePlanEntry(raw: RawYaml, at: String): DecodeResult[PlanEntry] =
    for
      fields      <- mapping(raw, at)
      name        <- optionalScalarString(fields, "name", s"$at.name")
      kind        <- optionalScalarString(fields, "kind", s"$at.kind")
      description <- optionalScalarString(fields, "description", s"$at.description")
      execution   <- optionalMapping(fields, "execution", s"$at.execution").flatMap:
        case Some(value) => decodeExecution(value, s"$at.execution").map(Some(_))
        case None        => Right(None)
      condition <- optionalMapping(fields, "when", s"$at.when").flatMap:
        case Some(value) => decodeCondition(value, s"$at.when").map(Some(_))
        case None        => Right(None)
    yield PlanEntry(
      name = name,
      kind = kind,
      description = description,
      execution = execution,
      when = condition,
      spec = fields.get("spec")
    )

  private def decodeExecution(raw: RawYaml, at: String): DecodeResult[Execution] =
    for
      fields         <- mapping(raw, at)
      mode           <- optionalScalarString(fields, "mode", s"$at.mode")
      maxConcurrency <- optionalInt(fields, "maxConcurrency", s"$at.maxConcurrency")
      failFast       <- optionalBoolean(fields, "failFast", s"$at.failFast")
      locks          <- optionalStringSequence(fields, "locks", s"$at.locks")
    yield Execution(
      mode = mode,
      maxConcurrency = maxConcurrency,
      failFast = failFast,
      locks = locks.getOrElse(Vector.empty)
    )

  private def decodeCondition(raw: RawYaml, at: String): DecodeResult[Condition] =
    for
      fields        <- mapping(raw, at)
      commandExists <- optionalScalarString(fields, "commandExists", s"$at.commandExists")
      os            <- optionalMapping(fields, "os", s"$at.os").flatMap:
        case Some(value) => decodeOsCondition(value, s"$at.os").map(Some(_))
        case None        => Right(None)
    yield Condition(
      os = os,
      commandExists = commandExists,
      raw = raw
    )

  private def decodeOsCondition(raw: RawYaml, at: String): DecodeResult[OsCondition] =
    for
      fields       <- mapping(raw, at)
      family       <- optionalMatch(fields, "family", s"$at.family")
      distribution <- optionalMatch(fields, "distribution", s"$at.distribution")
      version      <- optionalMatch(fields, "version", s"$at.version")
      codename     <- optionalMatch(fields, "codename", s"$at.codename")
      architecture <- optionalMatch(fields, "architecture", s"$at.architecture")
      desktop      <- optionalMatch(fields, "desktop", s"$at.desktop")
    yield OsCondition(
      family = family,
      distribution = distribution,
      version = version,
      codename = codename,
      architecture = architecture,
      desktop = desktop,
      raw = raw
    )

  private def optionalMatch(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): DecodeResult[Option[MatchExpression]] = fields.get(key) match
    case None        => Right(None)
    case Some(value) => decodeMatchExpression(value, at).map(Some(_))

  private def decodeMatchExpression(raw: RawYaml, at: String): DecodeResult[MatchExpression] =
    raw match
      case StringValue(value)   => Right(MatchExpression.Exact(value))
      case MappingValue(fields) => fields.get("oneOf") match
          case Some(SequenceValue(items)) => sequence(items.zipWithIndex.map((item, index) =>
              scalarString(item, s"$at.oneOf[$index]")
            ))
              .map(MatchExpression.OneOf(_))
          case Some(other) => Left(s"$at.oneOf must be a sequence, found ${kindOf(other)}")
          case None        => Left(s"$at must be a scalar string or an object with oneOf")
      case other =>
        Left(s"$at must be a scalar string or an object with oneOf, found ${kindOf(other)}")

  private def optionalStringMap(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): DecodeResult[Option[VectorMap[String, String]]] = fields.get(key) match
    case None        => Right(None)
    case Some(value) =>
      for
        rawMap <- mapping(value, at)
        fields <- sequence(rawMap.toVector.map { case (fieldKey, fieldValue) =>
          scalarString(fieldValue, s"$at.$fieldKey").map(fieldKey -> _)
        })
      yield Some(VectorMap.from(fields))

  private def optionalMapping(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): DecodeResult[Option[RawYaml]] = fields.get(key) match
    case None        => Right(None)
    case Some(value) => mapping(value, at).map(_ => Some(value))

  private def optionalSequence(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): DecodeResult[Option[Vector[RawYaml]]] = fields.get(key) match
    case None                       => Right(None)
    case Some(SequenceValue(items)) => Right(Some(items))
    case Some(other)                => Left(s"$at must be a sequence, found ${kindOf(other)}")

  private def optionalStringSequence(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): DecodeResult[Option[Vector[String]]] = optionalSequence(fields, key, at).flatMap:
    case None        => Right(None)
    case Some(items) => sequence(items.zipWithIndex.map((item, index) =>
        scalarString(item, s"$at[$index]")
      )).map(Some(_))

  private def optionalScalarString(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): DecodeResult[Option[String]] = fields.get(key) match
    case None        => Right(None)
    case Some(value) => scalarString(value, at).map(Some(_))

  private def requiredScalarString(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): DecodeResult[String] = fields.get(key) match
    case None        => Left(s"$at is required")
    case Some(value) => scalarString(value, at)

  private def optionalBoolean(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): DecodeResult[Option[Boolean]] = fields.get(key) match
    case None                      => Right(None)
    case Some(BooleanValue(value)) => Right(Some(value))
    case Some(other)               => Left(s"$at must be a boolean, found ${kindOf(other)}")

  private def optionalInt(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): DecodeResult[Option[Int]] = fields.get(key) match
    case None                                          => Right(None)
    case Some(IntegerValue(value)) if value.isValidInt => Right(Some(value.toInt))
    case Some(IntegerValue(_))                         => Left(s"$at must fit in a 32-bit integer")
    case Some(other) => Left(s"$at must be an integer, found ${kindOf(other)}")

  private def mapping(raw: RawYaml, at: String): DecodeResult[VectorMap[String, RawYaml]] =
    raw match
      case MappingValue(fields) => Right(fields)
      case other                => Left(s"$at must be a mapping, found ${kindOf(other)}")

  private def scalarString(raw: RawYaml, at: String): DecodeResult[String] = raw match
    case StringValue(value)  => Right(value)
    case BooleanValue(value) => Right(value.toString)
    case IntegerValue(value) => Right(value.toString)
    case DecimalValue(value) => Right(value.toString)
    case other               => Left(s"$at must be a scalar, found ${kindOf(other)}")

  private def toRawYaml(value: Any): DecodeResult[RawYaml] = value match
    case null                       => Right(NullValue)
    case text: String               => Right(StringValue(text))
    case boolean: java.lang.Boolean => Right(BooleanValue(boolean.booleanValue()))
    case integer: java.lang.Byte    => Right(IntegerValue(BigInt(integer.longValue())))
    case integer: java.lang.Short   => Right(IntegerValue(BigInt(integer.longValue())))
    case integer: java.lang.Integer => Right(IntegerValue(BigInt(integer.longValue())))
    case integer: java.lang.Long    => Right(IntegerValue(BigInt(integer.longValue())))
    case integer: BigInteger        => Right(IntegerValue(BigInt(integer)))
    case decimal: JavaBigDecimal    => Right(DecimalValue(BigDecimal(decimal)))
    case decimal: java.lang.Float  => Right(DecimalValue(BigDecimal.decimal(decimal.doubleValue())))
    case decimal: java.lang.Double => Right(DecimalValue(BigDecimal.decimal(decimal.doubleValue())))
    case map: java.util.Map[?, ?]  => toRawMapping(map)
    case list: java.util.List[?]   =>
      sequence(list.asScala.toVector.map(toRawYaml)).map(SequenceValue(_))
    case other => Left(s"unsupported YAML value ${other.getClass.getName}")

  private def toRawMapping(map: java.util.Map[?, ?]): DecodeResult[RawYaml] = sequence(
    map.asScala.toVector.map { case (key, value) =>
      key match
        case text: String => toRawYaml(value).map(text -> _)
        case other => Left(s"YAML mapping keys must be strings, found ${other.getClass.getName}")
    }
  ).map(fields => MappingValue(VectorMap.from(fields)))

  private def sequence[A](values: Vector[DecodeResult[A]]): DecodeResult[Vector[A]] =
    values.foldLeft(Right(Vector.empty): DecodeResult[Vector[A]]) { (acc, value) =>
      acc.flatMap(items => value.map(items :+ _))
    }

  private def kindOf(value: RawYaml): String = value match
    case NullValue        => "null"
    case StringValue(_)   => "string"
    case BooleanValue(_)  => "boolean"
    case IntegerValue(_)  => "integer"
    case DecimalValue(_)  => "decimal"
    case SequenceValue(_) => "sequence"
    case MappingValue(_)  => "mapping"

  private def safeMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
