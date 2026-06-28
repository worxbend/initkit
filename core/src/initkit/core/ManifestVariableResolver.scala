package initkit.core

import java.nio.file.Path
import scala.collection.immutable.VectorMap

import initkit.config.*
import initkit.host.{HostFacts, HostSystem}

object ManifestVariableResolver:
  def resolve(
      manifest: Manifest,
      runtimeVariables: RuntimeVariables,
      hostFacts: HostFacts
  ): Either[Vector[ManifestValidationError], Manifest] =
    val baseVariables = runtimeVariables.values ++ hostVariables(hostFacts)
    val specVariables = resolveSpecVariables(manifest.spec.vars, baseVariables)
    val variables = baseVariables ++ specVariables.values
    val resolvedManifest = resolveManifest(manifest, variables)
    val errors = (specVariables.errors ++ resolvedManifest.errors).distinct

    if errors.isEmpty then Right(resolvedManifest.value)
    else Left(errors)

  def loadValidatedResolved(
      path: Path,
      runtimeVariables: RuntimeVariables,
      hostFacts: HostFacts
  ): Either[ManifestLoadError, Manifest] =
    val normalizedPath = path.toAbsolutePath.normalize()

    ManifestLoader.loadValidated(normalizedPath).flatMap: manifest =>
      resolve(manifest, runtimeVariables, hostFacts)
        .left
        .map(errors => ManifestLoadError.ValidationFailure(normalizedPath, errors))
        .flatMap: resolved =>
          ManifestValidator
            .validate(resolved, Some(normalizedPath))
            .left
            .map(errors => ManifestLoadError.ValidationFailure(normalizedPath, errors))
            .map(_ => resolved)

  private def resolveManifest(
      manifest: Manifest,
      variables: VectorMap[String, String]
  ): Resolved[Manifest] =
    val apiVersion = resolveOptionalString(manifest.apiVersion, "apiVersion", variables, None)
    val kind = resolveOptionalString(manifest.kind, "kind", variables, None)
    val metadata = resolveMetadata(manifest.metadata, variables)
    val spec = resolveSpec(manifest.spec, variables)

    Resolved(
      manifest.copy(
        apiVersion = apiVersion.value,
        kind = kind.value,
        metadata = metadata.value,
        spec = spec.value
      ),
      apiVersion.errors ++ kind.errors ++ metadata.errors ++ spec.errors
    )

  private def resolveMetadata(
      metadata: Metadata,
      variables: VectorMap[String, String]
  ): Resolved[Metadata] =
    val name = resolveOptionalString(metadata.name, "metadata.name", variables, None)
    val labels = resolveStringMap(metadata.labels, "metadata.labels", variables, None)
    val annotations = resolveStringMap(metadata.annotations, "metadata.annotations", variables, None)

    Resolved(
      metadata.copy(
        name = name.value,
        labels = labels.value,
        annotations = annotations.value
      ),
      name.errors ++ labels.errors ++ annotations.errors
    )

  private def resolveSpec(
      spec: ManifestSpec,
      variables: VectorMap[String, String]
  ): Resolved[ManifestSpec] =
    val target = resolveOptionalTarget(spec.target, variables)
    val sources = resolveOptionalSources(spec.sources, variables)
    val plan = resolvePlan(spec.plan, variables)

    Resolved(
      spec.copy(
        target = target.value,
        vars = spec.vars.map { case (name, value) => name -> variables.getOrElse(name, value) },
        sources = sources.value,
        plan = plan.value
      ),
      target.errors ++ sources.errors ++ plan.errors
    )

  private def resolveOptionalTarget(
      target: Option[Target],
      variables: VectorMap[String, String]
  ): Resolved[Option[Target]] =
    target match
      case None => Resolved(None, Vector.empty)
      case Some(value) =>
        val os = value.os match
          case None => Resolved(None, Vector.empty)
          case Some(targetOs) =>
            val family = resolveOptionalString(targetOs.family, "spec.target.os.family", variables, None)
            val distribution =
              resolveOptionalString(targetOs.distribution, "spec.target.os.distribution", variables, None)
            val version = resolveOptionalString(targetOs.version, "spec.target.os.version", variables, None)
            val codename = resolveOptionalString(targetOs.codename, "spec.target.os.codename", variables, None)
            val architecture =
              resolveOptionalString(targetOs.architecture, "spec.target.os.architecture", variables, None)
            val desktop = resolveOptionalString(targetOs.desktop, "spec.target.os.desktop", variables, None)

            Resolved(
              Some(
                targetOs.copy(
                  family = family.value,
                  distribution = distribution.value,
                  version = version.value,
                  codename = codename.value,
                  architecture = architecture.value,
                  desktop = desktop.value
                )
              ),
              family.errors ++ distribution.errors ++ version.errors ++ codename.errors ++ architecture.errors ++ desktop.errors
            )

        Resolved(Some(value.copy(os = os.value)), os.errors)

  private def resolveOptionalSources(
      sources: Option[Sources],
      variables: VectorMap[String, String]
  ): Resolved[Option[Sources]] =
    sources match
      case None => Resolved(None, Vector.empty)
      case Some(value) =>
        val apt = value.apt match
          case None => Resolved(None, Vector.empty)
          case Some(aptSources) =>
            val repositories = collect(
              aptSources.repositories.zipWithIndex.map { case (repository, index) =>
                resolveAptRepository(repository, s"spec.sources.apt.repositories[$index]", variables)
              }
            )
            Resolved(Some(aptSources.copy(repositories = repositories.value)), repositories.errors)

        val dnf = value.dnf match
          case None => Resolved(None, Vector.empty)
          case Some(dnfSources) =>
            val repositories = collect(
              dnfSources.repositories.zipWithIndex.map { case (repository, index) =>
                resolveDnfRepository(repository, s"spec.sources.dnf.repositories[$index]", variables)
              }
            )
            Resolved(Some(dnfSources.copy(repositories = repositories.value)), repositories.errors)

        val zypper = value.zypper match
          case None => Resolved(None, Vector.empty)
          case Some(zypperSources) =>
            val repositories = collect(
              zypperSources.repositories.zipWithIndex.map { case (repository, index) =>
                resolveZypperRepository(repository, s"spec.sources.zypper.repositories[$index]", variables)
              }
            )
            Resolved(Some(zypperSources.copy(repositories = repositories.value)), repositories.errors)

        val flatpak = value.flatpak match
          case None => Resolved(None, Vector.empty)
          case Some(flatpakSources) =>
            val remotes = collect(
              flatpakSources.remotes.zipWithIndex.map { case (remote, index) =>
                resolveFlatpakRemote(remote, s"spec.sources.flatpak.remotes[$index]", variables)
              }
            )
            Resolved(Some(flatpakSources.copy(remotes = remotes.value)), remotes.errors)

        val raw = resolveRaw(value.raw, "spec.sources", variables, None)

        Resolved(
          Some(
            value.copy(
              apt = apt.value,
              dnf = dnf.value,
              zypper = zypper.value,
              flatpak = flatpak.value,
              raw = raw.value
            )
          ),
          apt.errors ++ dnf.errors ++ zypper.errors ++ flatpak.errors ++ raw.errors
        )

  private def resolveAptRepository(
      repository: AptRepository,
      at: String,
      variables: VectorMap[String, String]
  ): Resolved[AptRepository] =
    val name = resolveString(repository.name, s"$at.name", variables, None)
    val keyUrl = resolveOptionalString(repository.keyUrl, s"$at.keyUrl", variables, None)
    val source = resolveString(repository.source, s"$at.source", variables, None)

    Resolved(
      repository.copy(name = name.value, keyUrl = keyUrl.value, source = source.value),
      name.errors ++ keyUrl.errors ++ source.errors
    )

  private def resolveDnfRepository(
      repository: DnfRepository,
      at: String,
      variables: VectorMap[String, String]
  ): Resolved[DnfRepository] =
    val name = resolveString(repository.name, s"$at.name", variables, None)
    val description = resolveOptionalString(repository.description, s"$at.description", variables, None)
    val baseUrl = resolveString(repository.baseUrl, s"$at.baseUrl", variables, None)
    val gpgKey = resolveOptionalString(repository.gpgKey, s"$at.gpgKey", variables, None)

    Resolved(
      repository.copy(name = name.value, description = description.value, baseUrl = baseUrl.value, gpgKey = gpgKey.value),
      name.errors ++ description.errors ++ baseUrl.errors ++ gpgKey.errors
    )

  private def resolveZypperRepository(
      repository: ZypperRepository,
      at: String,
      variables: VectorMap[String, String]
  ): Resolved[ZypperRepository] =
    val name = resolveString(repository.name, s"$at.name", variables, None)
    val url = resolveString(repository.url, s"$at.url", variables, None)

    Resolved(
      repository.copy(name = name.value, url = url.value),
      name.errors ++ url.errors
    )

  private def resolveFlatpakRemote(
      remote: FlatpakRemote,
      at: String,
      variables: VectorMap[String, String]
  ): Resolved[FlatpakRemote] =
    val name = resolveString(remote.name, s"$at.name", variables, None)
    val url = resolveString(remote.url, s"$at.url", variables, None)

    Resolved(
      remote.copy(name = name.value, url = url.value),
      name.errors ++ url.errors
    )

  private def resolvePlan(
      plan: Vector[PlanEntry],
      variables: VectorMap[String, String]
  ): Resolved[Vector[PlanEntry]] =
    collect(
      plan.zipWithIndex.map { case (entry, index) =>
        resolvePlanEntry(entry, index, variables)
      }
    )

  private def resolvePlanEntry(
      entry: PlanEntry,
      index: Int,
      variables: VectorMap[String, String]
  ): Resolved[PlanEntry] =
    val at = s"spec.plan[$index]"
    val context = planContext(entry)
    val name = resolveOptionalString(entry.name, s"$at.name", variables, context)
    val kind = resolveOptionalString(entry.kind, s"$at.kind", variables, context)
    val description = resolveOptionalString(entry.description, s"$at.description", variables, context)
    val execution = resolveOptionalExecution(entry.execution, s"$at.execution", variables, context)
    val condition = resolveOptionalCondition(entry.when, s"$at.when", variables, context)
    val spec = entry.spec match
      case None        => Resolved(None, Vector.empty)
      case Some(value) => resolveRaw(value, s"$at.spec", variables, context).map(Some(_))

    Resolved(
      entry.copy(
        name = name.value,
        kind = kind.value,
        description = description.value,
        execution = execution.value,
        when = condition.value,
        spec = spec.value
      ),
      name.errors ++ kind.errors ++ description.errors ++ execution.errors ++ condition.errors ++ spec.errors
    )

  private def resolveOptionalExecution(
      execution: Option[Execution],
      at: String,
      variables: VectorMap[String, String],
      context: Option[String]
  ): Resolved[Option[Execution]] =
    execution match
      case None => Resolved(None, Vector.empty)
      case Some(value) =>
        val mode = resolveOptionalString(value.mode, s"$at.mode", variables, context)
        val locks = resolveStringVector(value.locks, s"$at.locks", variables, context)

        Resolved(
          Some(value.copy(mode = mode.value, locks = locks.value)),
          mode.errors ++ locks.errors
        )

  private def resolveOptionalCondition(
      condition: Option[Condition],
      at: String,
      variables: VectorMap[String, String],
      context: Option[String]
  ): Resolved[Option[Condition]] =
    condition match
      case None => Resolved(None, Vector.empty)
      case Some(value) =>
        val commandExists = resolveOptionalString(value.commandExists, s"$at.commandExists", variables, context)
        val os = resolveOptionalOsCondition(value.os, s"$at.os", variables, context)
        val raw = resolveRaw(value.raw, at, variables, context)

        Resolved(
          Some(value.copy(commandExists = commandExists.value, os = os.value, raw = raw.value)),
          commandExists.errors ++ os.errors ++ raw.errors
        )

  private def resolveOptionalOsCondition(
      os: Option[OsCondition],
      at: String,
      variables: VectorMap[String, String],
      context: Option[String]
  ): Resolved[Option[OsCondition]] =
    os match
      case None => Resolved(None, Vector.empty)
      case Some(value) =>
        val family = resolveOptionalMatchExpression(value.family, s"$at.family", variables, context)
        val distribution = resolveOptionalMatchExpression(value.distribution, s"$at.distribution", variables, context)
        val version = resolveOptionalMatchExpression(value.version, s"$at.version", variables, context)
        val codename = resolveOptionalMatchExpression(value.codename, s"$at.codename", variables, context)
        val architecture = resolveOptionalMatchExpression(value.architecture, s"$at.architecture", variables, context)
        val desktop = resolveOptionalMatchExpression(value.desktop, s"$at.desktop", variables, context)
        val raw = resolveRaw(value.raw, at, variables, context)

        Resolved(
          Some(
            value.copy(
              family = family.value,
              distribution = distribution.value,
              version = version.value,
              codename = codename.value,
              architecture = architecture.value,
              desktop = desktop.value,
              raw = raw.value
            )
          ),
          family.errors ++ distribution.errors ++ version.errors ++ codename.errors ++ architecture.errors ++ desktop.errors ++ raw.errors
        )

  private def resolveOptionalMatchExpression(
      expression: Option[MatchExpression],
      at: String,
      variables: VectorMap[String, String],
      context: Option[String]
  ): Resolved[Option[MatchExpression]] =
    expression match
      case None => Resolved(None, Vector.empty)
      case Some(MatchExpression.Exact(value)) =>
        resolveString(value, at, variables, context).map(value => Some(MatchExpression.Exact(value)))
      case Some(MatchExpression.OneOf(values)) =>
        resolveStringVector(values, s"$at.oneOf", variables, context).map(value => Some(MatchExpression.OneOf(value)))

  private def resolveRaw(
      raw: RawYaml,
      at: String,
      variables: VectorMap[String, String],
      context: Option[String]
  ): Resolved[RawYaml] =
    raw match
      case RawYaml.StringValue(value) =>
        resolveString(value, at, variables, context).map(RawYaml.StringValue(_))
      case RawYaml.SequenceValue(items) =>
        collect(items.zipWithIndex.map((item, index) => resolveRaw(item, s"$at[$index]", variables, context)))
          .map(RawYaml.SequenceValue(_))
      case RawYaml.MappingValue(fields) =>
        val resolvedFields = fields.toVector.map { case (key, value) =>
          resolveRaw(value, s"$at.$key", variables, context).map(key -> _)
        }
        collect(resolvedFields).map(fields => RawYaml.MappingValue(VectorMap.from(fields)))
      case other => Resolved(other, Vector.empty)

  private def resolveStringMap(
      values: VectorMap[String, String],
      at: String,
      variables: VectorMap[String, String],
      context: Option[String]
  ): Resolved[VectorMap[String, String]] =
    collect(
      values.toVector.map { case (key, value) =>
        resolveString(value, s"$at.$key", variables, context).map(key -> _)
      }
    ).map(values => VectorMap.from(values))

  private def resolveStringVector(
      values: Vector[String],
      at: String,
      variables: VectorMap[String, String],
      context: Option[String]
  ): Resolved[Vector[String]] =
    collect(values.zipWithIndex.map((value, index) => resolveString(value, s"$at[$index]", variables, context)))

  private def resolveOptionalString(
      value: Option[String],
      at: String,
      variables: VectorMap[String, String],
      context: Option[String]
  ): Resolved[Option[String]] =
    value match
      case None        => Resolved(None, Vector.empty)
      case Some(value) => resolveString(value, at, variables, context).map(Some(_))

  private def resolveString(
      value: String,
      at: String,
      variables: VectorMap[String, String],
      context: Option[String]
  ): Resolved[String] =
    interpolate(value, at, context): name =>
      variables.get(name) match
        case Some(value) => Right(value)
        case None        => Left(Vector(unresolvedVariable(at, name, context)))

  private def resolveSpecVariables(
      vars: VectorMap[String, String],
      baseVariables: VectorMap[String, String]
  ): SpecVariableResolution =
    var resolved = VectorMap.empty[String, String]
    var failed = Set.empty[String]

    def resolveSpecVariable(name: String, stack: Vector[String]): Either[Vector[ManifestValidationError], String] =
      resolved.get(name) match
        case Some(value) => Right(value)
        case None if failed.contains(name) =>
          Left(Vector(ManifestValidationError(s"spec.vars.$name", s"could not resolve variable '$name'")))
        case None =>
          vars.get(name) match
            case None =>
              baseVariables.get(name) match
                case Some(value) => Right(value)
                case None        => Left(Vector(unresolvedVariable(s"spec.vars.${stack.lastOption.getOrElse(name)}", name, None)))
            case Some(_) if stack.contains(name) =>
              failed = failed + name
              val cycle = (stack.drop(stack.indexOf(name)) :+ name).mkString(" -> ")
              Left(Vector(ManifestValidationError(s"spec.vars.$name", s"cyclic variable reference '$cycle'")))
            case Some(rawValue) =>
              val value = interpolate(rawValue, s"spec.vars.$name", None): referencedName =>
                resolveSpecVariable(referencedName, stack :+ name)

              if value.errors.isEmpty then
                resolved = resolved.updated(name, value.value)
                Right(value.value)
              else
                failed = failed + name
                Left(value.errors)

    val errors =
      vars.keys.toVector.flatMap: name =>
        resolveSpecVariable(name, Vector.empty) match
          case Right(_)     => Vector.empty
          case Left(errors) => errors

    SpecVariableResolution(VectorMap.from(vars.keys.toVector.flatMap(name => resolved.get(name).map(name -> _))), errors)

  private def interpolate(
      value: String,
      at: String,
      context: Option[String]
  )(
      lookup: String => Either[Vector[ManifestValidationError], String]
  ): Resolved[String] =
    val builder = new StringBuilder
    var errors = Vector.empty[ManifestValidationError]
    var cursor = 0

    while cursor < value.length do
      val start = value.indexOf("${", cursor)
      if start < 0 then
        builder.append(value.substring(cursor))
        cursor = value.length
      else
        val end = value.indexOf("}", start + 2)
        if end < 0 then
          builder.append(value.substring(cursor))
          cursor = value.length
        else
          builder.append(value.substring(cursor, start))
          val name = value.substring(start + 2, end)
          lookup(name) match
            case Right(resolved) => builder.append(resolved)
            case Left(newErrors) =>
              errors = errors ++ newErrors
              builder.append(value.substring(start, end + 1))
          cursor = end + 1

    Resolved(builder.result(), errors)

  private def hostVariables(hostFacts: HostFacts): VectorMap[String, String] =
    val optionalValues = Vector(
      "host.os.distribution" -> hostFacts.os.distribution,
      "host.os.version" -> hostFacts.os.version,
      "host.os.codename" -> hostFacts.os.codename,
      "osDistribution" -> hostFacts.os.distribution,
      "osVersion" -> hostFacts.os.version,
      "osCodename" -> hostFacts.os.codename
    ).collect { case (name, Some(value)) => name -> value }

    VectorMap.from(
      Vector(
        "host.os.family" -> hostFacts.os.family,
        "host.architecture" -> hostFacts.architecture,
        "osFamily" -> hostFacts.os.family,
        "architecture" -> hostFacts.architecture,
        "arch" -> hostFacts.architecture
      ) ++ optionalValues
    )

  private def unresolvedVariable(
      at: String,
      name: String,
      context: Option[String]
  ): ManifestValidationError =
    val contextDetail = context.map(value => s" in $value").getOrElse("")
    ManifestValidationError(at, "unresolved variable '${" + name + "}'" + contextDetail)

  private def planContext(entry: PlanEntry): Option[String] =
    entry.name.map(name => s"plan entry '$name'")

  private def collect[A](values: Vector[Resolved[A]]): Resolved[Vector[A]] =
    Resolved(
      values.map(_.value),
      values.flatMap(_.errors)
    )

  private final case class Resolved[A](value: A, errors: Vector[ManifestValidationError]):
    def map[B](transform: A => B): Resolved[B] =
      Resolved(transform(value), errors)

  private final case class SpecVariableResolution(
      values: VectorMap[String, String],
      errors: Vector[ManifestValidationError]
  )

final case class RuntimeVariables(values: VectorMap[String, String])

object RuntimeVariables:
  val empty: RuntimeVariables =
    RuntimeVariables(VectorMap.empty)

  def from(values: (String, String)*): RuntimeVariables =
    RuntimeVariables(VectorMap.from(values))

  def fromMap(values: Map[String, String]): RuntimeVariables =
    RuntimeVariables(VectorMap.from(values.toVector))

  def fromHostSystem(system: HostSystem = HostSystem.live): RuntimeVariables =
    RuntimeVariables(
      VectorMap.from(
        Vector("HOME", "USER").flatMap(name => system.env(name).map(name -> _))
      )
    )
