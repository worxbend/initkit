package initkit.config

import java.nio.file.Path
import scala.collection.immutable.VectorMap

object ManifestValidator:
  private val SupportedApiVersion = "initkit.io/v1alpha1"
  private val SupportedKind = "WorkstationProfile"
  private val DefaultExecutionMode = "sequential"
  private val SupportedExecutionModes = Set("sequential", "parallel")
  private val SupportedPlanKinds = PackageSpecDecoder.PackageKinds ++ Set(
    "binary-downloads",
    "shell-scripts",
    "nerd-fonts",
    "dotfiles-apply",
    "interrupt",
    "commands"
  )
  private val SupportedChecksumAlgorithms = Set("sha256", "sha512")

  def validate(manifest: Manifest, manifestPath: Option[Path] = None): Either[Vector[ManifestValidationError], Manifest] =
    val errors =
      validateTopLevel(manifest) ++
        validatePlanEntries(manifest.spec.plan, manifestPath)

    if errors.isEmpty then Right(manifest)
    else Left(errors)

  private def validateTopLevel(manifest: Manifest): Vector[ManifestValidationError] =
    validateRequiredFixedValue(manifest.apiVersion, "apiVersion", SupportedApiVersion, "apiVersion") ++
      validateRequiredFixedValue(manifest.kind, "kind", SupportedKind, "kind")

  private def validatePlanEntries(
      plan: Vector[PlanEntry],
      manifestPath: Option[Path]
  ): Vector[ManifestValidationError] =
    plan.zipWithIndex.flatMap((entry, index) => validatePlanEntry(entry, index, manifestPath)) ++
      validateDuplicatePlanNames(plan)

  private def validatePlanEntry(
      entry: PlanEntry,
      index: Int,
      manifestPath: Option[Path]
  ): Vector[ManifestValidationError] =
    val kind = entry.kind.flatMap(nonEmptyTrimmed)

    validateRequiredString(entry.name, planPath(index, "name")) ++
      validatePlanKind(entry.kind, planPath(index, "kind")) ++
      validateExecution(entry.execution, planPath(index, "execution")) ++
      kind.toVector.flatMap(validateKindSpecificSpec(entry, index, _, manifestPath))

  private def validatePlanKind(value: Option[String], at: String): Vector[ManifestValidationError] =
    value.flatMap(nonEmptyTrimmed) match
      case None => Vector(error(at, "is required"))
      case Some(kind) if SupportedPlanKinds.contains(kind) => Vector.empty
      case Some(kind) => Vector(error(at, s"unsupported plan kind '$kind'"))

  private def validateKindSpecificSpec(
      entry: PlanEntry,
      index: Int,
      kind: String,
      manifestPath: Option[Path]
  ): Vector[ManifestValidationError] =
    val specAt = planPath(index, "spec")

    entry.spec match
      case None => Vector(error(specAt, "is required"))
      case Some(RawYaml.MappingValue(fields)) =>
        kind match
          case packageKind if PackageSpecDecoder.isPackageKind(packageKind) =>
            PackageSpecDecoder.decode(packageKind, entry.spec, specAt, entry.name).left.toOption.getOrElse(Vector.empty)
          case "binary-downloads"                               => validateBinaryDownloadSpec(fields, specAt)
          case "interrupt"                                      => validateInterruptSpec(fields, specAt, manifestPath)
          case _                                                => Vector.empty
      case Some(other) => Vector(error(specAt, s"must be a mapping, found ${kindOf(other)}"))

  private def validateBinaryDownloadSpec(
      fields: VectorMap[String, RawYaml],
      specAt: String
  ): Vector[ManifestValidationError] =
    fields.get("items") match
      case Some(RawYaml.SequenceValue(items)) =>
        items.zipWithIndex.flatMap((item, index) => validateBinaryDownloadItem(item, s"$specAt.items[$index]"))
      case Some(other) => Vector(error(s"$specAt.items", s"must be a sequence, found ${kindOf(other)}"))
      case None        => Vector.empty

  private def validateBinaryDownloadItem(item: RawYaml, at: String): Vector[ManifestValidationError] =
    item match
      case RawYaml.MappingValue(fields) =>
        fields.get("checksum").toVector.flatMap(validateChecksum(_, s"$at.checksum"))
      case other => Vector(error(at, s"must be a mapping, found ${kindOf(other)}"))

  private def validateChecksum(checksum: RawYaml, at: String): Vector[ManifestValidationError] =
    checksum match
      case RawYaml.MappingValue(fields) =>
        fields.get("algorithm") match
          case Some(RawYaml.StringValue(value)) if SupportedChecksumAlgorithms.contains(value.trim.toLowerCase) =>
            Vector.empty
          case Some(RawYaml.StringValue(value)) =>
            Vector(error(s"$at.algorithm", s"unsupported checksum algorithm '$value'"))
          case Some(other) => Vector(error(s"$at.algorithm", s"must be a string, found ${kindOf(other)}"))
          case None        => Vector(error(s"$at.algorithm", "is required"))
      case other => Vector(error(at, s"must be a mapping, found ${kindOf(other)}"))

  private def validateInterruptSpec(
      fields: VectorMap[String, RawYaml],
      specAt: String,
      manifestPath: Option[Path]
  ): Vector[ManifestValidationError] =
    fields.get("state") match
      case Some(RawYaml.MappingValue(stateFields)) =>
        validateInterruptState(stateFields, s"$specAt.state", manifestPath)
      case Some(other) => Vector(error(s"$specAt.state", s"must be a mapping, found ${kindOf(other)}"))
      case None        => Vector(error(s"$specAt.state", "is required"))

  private def validateInterruptState(
      fields: VectorMap[String, RawYaml],
      stateAt: String,
      manifestPath: Option[Path]
  ): Vector[ManifestValidationError] =
    validateInterruptStatePath(fields, stateAt, manifestPath) ++
      validateInterruptStateFormat(fields, stateAt)

  private def validateInterruptStatePath(
      fields: VectorMap[String, RawYaml],
      stateAt: String,
      manifestPath: Option[Path]
  ): Vector[ManifestValidationError] =
    fields.get("path") match
      case Some(RawYaml.StringValue(value)) =>
        val requiredErrors = validateRequiredString(Some(value), s"$stateAt.path")
        requiredErrors ++ validateStatePathSeparation(value, s"$stateAt.path", manifestPath)
      case Some(other) => Vector(error(s"$stateAt.path", s"must be a string, found ${kindOf(other)}"))
      case None        => Vector(error(s"$stateAt.path", "is required"))

  private def validateInterruptStateFormat(
      fields: VectorMap[String, RawYaml],
      stateAt: String
  ): Vector[ManifestValidationError] =
    fields.get("format") match
      case Some(RawYaml.StringValue(value)) if value.trim == "json" => Vector.empty
      case Some(RawYaml.StringValue(value)) => Vector(error(s"$stateAt.format", s"unsupported state format '$value'"))
      case Some(other) => Vector(error(s"$stateAt.format", s"must be a string, found ${kindOf(other)}"))
      case None        => Vector(error(s"$stateAt.format", "is required"))

  private def validateStatePathSeparation(
      statePath: String,
      at: String,
      manifestPath: Option[Path]
  ): Vector[ManifestValidationError] =
    if statePath.contains("${") then Vector.empty
    else
      manifestPath match
        case Some(path) if Path.of(statePath).toAbsolutePath.normalize() == path.toAbsolutePath.normalize() =>
          Vector(error(at, "must not point to the manifest file"))
        case _ => Vector.empty

  private def validateExecution(
      execution: Option[Execution],
      at: String
  ): Vector[ManifestValidationError] =
    execution match
      case None => Vector.empty
      case Some(value) =>
        val explicitMode = value.mode.map(_.trim)
        val modeForRules = explicitMode.filter(_.nonEmpty).getOrElse(DefaultExecutionMode)

        validateExecutionMode(explicitMode, s"$at.mode") ++
          validateMaxConcurrency(value.maxConcurrency, modeForRules, s"$at.maxConcurrency")

  private def validateExecutionMode(
      mode: Option[String],
      at: String
  ): Vector[ManifestValidationError] =
    mode match
      case None => Vector.empty
      case Some(value) if value.isEmpty => Vector(error(at, "must not be empty"))
      case Some(value) if SupportedExecutionModes.contains(value) => Vector.empty
      case Some(value) => Vector(error(at, s"unsupported execution mode '$value'"))

  private def validateMaxConcurrency(
      value: Option[Int],
      mode: String,
      at: String
  ): Vector[ManifestValidationError] =
    value match
      case Some(maxConcurrency) if maxConcurrency < 1 => Vector(error(at, "must be at least 1"))
      case Some(maxConcurrency) if mode == DefaultExecutionMode && maxConcurrency > 1 =>
        Vector(error(at, "can only be greater than 1 when execution.mode is parallel"))
      case _ => Vector.empty

  private def validateDuplicatePlanNames(plan: Vector[PlanEntry]): Vector[ManifestValidationError] =
    val namedEntries = plan.zipWithIndex.flatMap { case (entry, index) =>
      entry.name.flatMap(nonEmptyTrimmed).map(name => name -> index)
    }

    namedEntries
      .foldLeft((Map.empty[String, Int], Vector.empty[ManifestValidationError])) {
        case ((seen, errors), (name, index)) =>
          seen.get(name) match
            case Some(firstIndex) =>
              val detail = s"duplicate plan name '$name' also used at ${planPath(firstIndex, "name")}"
              (seen, errors :+ error(planPath(index, "name"), detail))
            case None => (seen.updated(name, index), errors)
      }
      ._2

  private def validateRequiredFixedValue(
      value: Option[String],
      fieldName: String,
      expected: String,
      at: String
  ): Vector[ManifestValidationError] =
    value.flatMap(nonEmptyTrimmed) match
      case None => Vector(error(at, s"$fieldName is required"))
      case Some(actual) if actual == expected => Vector.empty
      case Some(actual) => Vector(error(at, s"unsupported $fieldName '$actual'; expected '$expected'"))

  private def validateRequiredString(
      value: Option[String],
      at: String
  ): Vector[ManifestValidationError] =
    value match
      case Some(text) if text.trim.nonEmpty => Vector.empty
      case Some(_)                         => Vector(error(at, "must not be empty"))
      case None                            => Vector(error(at, "is required"))

  private def nonEmptyTrimmed(value: String): Option[String] =
    val trimmed = value.trim
    Option.when(trimmed.nonEmpty)(trimmed)

  private def planPath(index: Int, field: String): String =
    s"spec.plan[$index].$field"

  private def error(path: String, detail: String): ManifestValidationError =
    ManifestValidationError(path, detail)

  private def kindOf(value: RawYaml): String =
    value match
      case RawYaml.NullValue         => "null"
      case RawYaml.StringValue(_)    => "string"
      case RawYaml.BooleanValue(_)   => "boolean"
      case RawYaml.IntegerValue(_)   => "integer"
      case RawYaml.DecimalValue(_)   => "decimal"
      case RawYaml.SequenceValue(_)  => "sequence"
      case RawYaml.MappingValue(_)   => "mapping"
