package initkit.config

import scala.collection.immutable.VectorMap

object PackageSpecDecoder:

  val PackageKinds: Set[String] = Set(
    "apt-packages",
    "pacman-packages",
    "dnf-packages",
    "zypper-packages",
    "flatpak-packages",
    "snap-packages",
    "aur-packages",
    "cargo-packages",
    "sdkman-packages"
  )

  def isPackageKind(kind: String): Boolean = PackageKinds.contains(kind)

  def isSourceSetupPackageKind(kind: String): Boolean = Set(
    "apt-packages",
    "pacman-packages",
    "dnf-packages",
    "zypper-packages",
    "flatpak-packages",
    "snap-packages"
  ).contains(kind)

  def decode(entry: PlanEntry, index: Int): Either[Vector[ManifestValidationError], PackageSpec] =
    entry.kind.flatMap(nonEmptyTrimmed) match
      case Some(kind) => decode(kind, entry.spec, planPath(index, "spec"), entry.name)
      case None       => Left(Vector(error(planPath(index, "kind"), "is required")))

  def decode(
      kind: String,
      spec: Option[RawYaml],
      specAt: String,
      entryName: Option[String]
  ): Either[Vector[ManifestValidationError], PackageSpec] = spec match
    case None                               => Left(Vector(error(specAt, "is required")))
    case Some(RawYaml.MappingValue(fields)) => decodePackageFields(kind, fields, specAt, entryName)
    case Some(other) => Left(Vector(error(specAt, s"must be a mapping, found ${kindOf(other)}")))

  private def decodePackageFields(
      kind: String,
      fields: VectorMap[String, RawYaml],
      specAt: String,
      entryName: Option[String]
  ): Either[Vector[ManifestValidationError], PackageSpec] = kind match
    case "apt-packages" => buildPackageSpec(
        decodeOptionalBoolean(fields, "update", s"$specAt.update"),
        decodeOptionalInstallList(fields, specAt),
        decodeActions(fields, specAt),
        specAt,
        entryName,
        allowActionOnly = fields.get("update").contains(RawYaml.BooleanValue(true)),
        installFieldPresent = fields.contains("install")
      )(PackageSpec.Apt.apply)
    case "pacman-packages" => buildPackageSpec(
        decodeOptionalBoolean(fields, "sync", s"$specAt.sync"),
        decodeOptionalInstallList(fields, specAt),
        decodeActions(fields, specAt),
        specAt,
        entryName,
        allowActionOnly = fields.get("sync").contains(RawYaml.BooleanValue(true)),
        installFieldPresent = fields.contains("install")
      )(PackageSpec.Pacman.apply)
    case "dnf-packages" => buildPackageSpec(
        Right(()),
        decodeOptionalInstallList(fields, specAt),
        decodeActions(fields, specAt),
        specAt,
        entryName,
        allowActionOnly = false,
        installFieldPresent = fields.contains("install")
      )((_, install, actions) => PackageSpec.Dnf(install, actions))
    case "zypper-packages" => buildPackageSpec(
        decodeOptionalBoolean(fields, "refresh", s"$specAt.refresh"),
        decodeOptionalInstallList(fields, specAt),
        decodeActions(fields, specAt),
        specAt,
        entryName,
        allowActionOnly = fields.get("refresh").contains(RawYaml.BooleanValue(true)),
        installFieldPresent = fields.contains("install")
      )(PackageSpec.Zypper.apply)
    case "flatpak-packages" => buildFlatpakSpec(fields, specAt, entryName)
    case "snap-packages"    =>
      decodeSnapInstallList(fields, specAt, entryName).map(PackageSpec.Snap.apply)
    case "aur-packages" => buildPackageSpec(
        decodeOptionalString(fields, "helper", s"$specAt.helper"),
        decodeInstallList(fields, specAt, entryName)
      )(PackageSpec.Aur.apply)
    case "cargo-packages" => buildPackageSpec(
        decodeOptionalString(fields, "installer", s"$specAt.installer"),
        decodeInstallList(fields, specAt, entryName)
      )(PackageSpec.Cargo.apply)
    case "sdkman-packages" => decodeSdkmanInstallList(fields, specAt, entryName)
        .map(PackageSpec.Sdkman.apply)
    case other => Left(Vector(error(specAt, s"unsupported package kind '$other'")))

  private def buildFlatpakSpec(
      fields: VectorMap[String, RawYaml],
      specAt: String,
      entryName: Option[String]
  ): Either[Vector[ManifestValidationError], PackageSpec] =
    val remote  = decodeOptionalString(fields, "remote", s"$specAt.remote")
    val system  = decodeOptionalBoolean(fields, "system", s"$specAt.system")
    val install = decodeInstallList(fields, specAt, entryName)
    combine(remote, system, install).map { case (remote, system, install) =>
      PackageSpec.Flatpak(remote, system, install)
    }

  private def buildPackageSpec[A, B, C](
      first: Either[Vector[ManifestValidationError], A],
      second: Either[Vector[ManifestValidationError], B]
  )(build: (A, B) => C): Either[Vector[ManifestValidationError], C] =
    combine(first, second).map(build.tupled)

  private def buildPackageSpec[A, B, C, D](
      first: Either[Vector[ManifestValidationError], A],
      second: Either[Vector[ManifestValidationError], Vector[String]],
      third: Either[Vector[ManifestValidationError], Vector[PackageAction]],
      specAt: String,
      entryName: Option[String],
      allowActionOnly: Boolean,
      installFieldPresent: Boolean
  )(build: (A, Vector[String], Vector[PackageAction]) => D)
      : Either[Vector[ManifestValidationError], D] = combine(first, second, third).flatMap:
    case (_, install, actions) if install.isEmpty && actions.isEmpty && !allowActionOnly =>
      if installFieldPresent then
        Left(Vector(error(
          s"$specAt.install",
          withEntryName(entryName, "must contain at least one package")
        )))
      else
        Left(Vector(error(
          specAt,
          withEntryName(entryName, "must define at least one install item or action")
        )))
    case (a, b, c) => Right(build(a, b, c))

  private def combine[A, B](
      first: Either[Vector[ManifestValidationError], A],
      second: Either[Vector[ManifestValidationError], B]
  ): Either[Vector[ManifestValidationError], (A, B)] = (first, second) match
    case (Right(a), Right(b)) => Right(a -> b)
    case _                    => Left(leftErrors(first) ++ leftErrors(second))

  private def combine[A, B, C](
      first: Either[Vector[ManifestValidationError], A],
      second: Either[Vector[ManifestValidationError], B],
      third: Either[Vector[ManifestValidationError], C]
  ): Either[Vector[ManifestValidationError], (A, B, C)] = (first, second, third) match
    case (Right(a), Right(b), Right(c)) => Right((a, b, c))
    case _ => Left(leftErrors(first) ++ leftErrors(second) ++ leftErrors(third))

  private def decodeInstallList(
      fields: VectorMap[String, RawYaml],
      specAt: String,
      entryName: Option[String]
  ): Either[Vector[ManifestValidationError], Vector[String]] = fields.get("install") match
    case Some(RawYaml.SequenceValue(items)) if items.nonEmpty =>
      sequence(items.zipWithIndex.map((item, index) =>
        decodeString(item, s"$specAt.install[$index]")
      ))
    case Some(RawYaml.SequenceValue(_)) => Left(Vector(error(
        s"$specAt.install",
        withEntryName(entryName, "must contain at least one package")
      )))
    case Some(other) => Left(Vector(error(
        s"$specAt.install",
        s"must be a non-empty sequence, found ${kindOf(other)}"
      )))
    case None => Left(Vector(error(
        s"$specAt.install",
        withEntryName(entryName, "is required and must contain at least one package")
      )))

  private def decodeOptionalInstallList(
      fields: VectorMap[String, RawYaml],
      specAt: String
  ): Either[Vector[ManifestValidationError], Vector[String]] = fields.get("install") match
    case Some(RawYaml.SequenceValue(items)) => sequence(items.zipWithIndex.map((item, index) =>
        decodeString(item, s"$specAt.install[$index]")
      ))
    case Some(other) => Left(Vector(error(
        s"$specAt.install",
        s"must be a sequence, found ${kindOf(other)}"
      )))
    case None => Right(Vector.empty)

  private def decodeActions(
      fields: VectorMap[String, RawYaml],
      specAt: String
  ): Either[Vector[ManifestValidationError], Vector[PackageAction]] = fields.get("actions") match
    case Some(RawYaml.SequenceValue(items)) => sequence(items.zipWithIndex.map((item, index) =>
        decodeAction(item, s"$specAt.actions[$index]")
      ))
    case Some(other) => Left(Vector(error(
        s"$specAt.actions",
        s"must be a sequence, found ${kindOf(other)}"
      )))
    case None => Right(Vector.empty)

  private def decodeAction(
      raw: RawYaml,
      at: String
  ): Either[Vector[ManifestValidationError], PackageAction] = raw match
    case RawYaml.StringValue(value) if value.trim.nonEmpty =>
      Right(PackageAction(value, Vector.empty))
    case RawYaml.StringValue(_)       => Left(Vector(error(at, "must not be empty")))
    case RawYaml.MappingValue(fields) => combine(
        decodeRequiredString(fields, "action", s"$at.action"),
        decodeStringSequence(fields, "args", s"$at.args")
      ).map { case (action, args) => PackageAction(action, args) }
    case other =>
      Left(Vector(error(at, s"must be an action name or mapping, found ${kindOf(other)}")))

  private def decodeSnapInstallList(
      fields: VectorMap[String, RawYaml],
      specAt: String,
      entryName: Option[String]
  ): Either[Vector[ManifestValidationError], Vector[SnapPackage]] = fields.get("install") match
    case Some(RawYaml.SequenceValue(items)) if items.nonEmpty =>
      sequence(items.zipWithIndex.map((item, index) =>
        decodeSnapPackage(item, s"$specAt.install[$index]")
      ))
    case Some(RawYaml.SequenceValue(_)) => Left(Vector(error(
        s"$specAt.install",
        withEntryName(entryName, "must contain at least one package")
      )))
    case Some(other) => Left(Vector(error(
        s"$specAt.install",
        s"must be a non-empty sequence, found ${kindOf(other)}"
      )))
    case None => Left(Vector(error(
        s"$specAt.install",
        withEntryName(entryName, "is required and must contain at least one package")
      )))

  private def decodeSnapPackage(
      raw: RawYaml,
      at: String
  ): Either[Vector[ManifestValidationError], SnapPackage] = raw match
    case RawYaml.StringValue(value)   => Right(SnapPackage(value, classic = None))
    case RawYaml.MappingValue(fields) => combine(
        decodeRequiredString(fields, "name", s"$at.name"),
        decodeOptionalBoolean(fields, "classic", s"$at.classic")
      ).map { case (name, classic) => SnapPackage(name, classic) }
    case other =>
      Left(Vector(error(at, s"must be a package name or mapping, found ${kindOf(other)}")))

  private def decodeSdkmanInstallList(
      fields: VectorMap[String, RawYaml],
      specAt: String,
      entryName: Option[String]
  ): Either[Vector[ManifestValidationError], Vector[SdkmanPackage]] = fields.get("install") match
    case Some(RawYaml.SequenceValue(items)) if items.nonEmpty =>
      sequence(items.zipWithIndex.map((item, index) =>
        decodeSdkmanPackage(item, s"$specAt.install[$index]")
      ))
    case Some(RawYaml.SequenceValue(_)) => Left(Vector(error(
        s"$specAt.install",
        withEntryName(entryName, "must contain at least one package")
      )))
    case Some(other) => Left(Vector(error(
        s"$specAt.install",
        s"must be a non-empty sequence, found ${kindOf(other)}"
      )))
    case None => Left(Vector(error(
        s"$specAt.install",
        withEntryName(entryName, "is required and must contain at least one package")
      )))

  private def decodeSdkmanPackage(
      raw: RawYaml,
      at: String
  ): Either[Vector[ManifestValidationError], SdkmanPackage] = raw match
    case RawYaml.StringValue(value)   => decodeString(raw, at).map(SdkmanPackage(_, None))
    case RawYaml.MappingValue(fields) => combine(
        decodeRequiredString(fields, "candidate", s"$at.candidate"),
        decodeOptionalString(fields, "version", s"$at.version")
      ).map { case (candidate, version) => SdkmanPackage(candidate, version) }
    case other =>
      Left(Vector(error(at, s"must be a candidate name or mapping, found ${kindOf(other)}")))

  private def decodeRequiredString(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): Either[Vector[ManifestValidationError], String] = fields.get(key) match
    case Some(value) => decodeString(value, at)
    case None        => Left(Vector(error(at, "is required")))

  private def decodeOptionalString(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): Either[Vector[ManifestValidationError], Option[String]] = fields.get(key) match
    case None        => Right(None)
    case Some(value) => decodeString(value, at).map(Some(_))

  private def decodeString(
      raw: RawYaml,
      at: String
  ): Either[Vector[ManifestValidationError], String] = raw match
    case RawYaml.StringValue(value) if value.trim.nonEmpty => Right(value)
    case RawYaml.StringValue(_) => Left(Vector(error(at, "must not be empty")))
    case other => Left(Vector(error(at, s"must be a string, found ${kindOf(other)}")))

  private def decodeOptionalBoolean(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): Either[Vector[ManifestValidationError], Option[Boolean]] = fields.get(key) match
    case None                              => Right(None)
    case Some(RawYaml.BooleanValue(value)) => Right(Some(value))
    case Some(other) => Left(Vector(error(at, s"must be a boolean, found ${kindOf(other)}")))

  private def decodeStringSequence(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): Either[Vector[ManifestValidationError], Vector[String]] = fields.get(key) match
    case None                               => Right(Vector.empty)
    case Some(RawYaml.SequenceValue(items)) =>
      sequence(items.zipWithIndex.map((item, index) => decodeString(item, s"$at[$index]")))
    case Some(other) => Left(Vector(error(at, s"must be a sequence, found ${kindOf(other)}")))

  private def sequence[A](
      values: Vector[Either[Vector[ManifestValidationError], A]]
  ): Either[Vector[ManifestValidationError], Vector[A]] =
    val errors = values.flatMap(_.left.toOption.getOrElse(Vector.empty))
    if errors.nonEmpty then Left(errors)
    else Right(values.flatMap(_.toOption))

  private def leftErrors[A](value: Either[Vector[ManifestValidationError], A])
      : Vector[ManifestValidationError] = value.left.toOption.getOrElse(Vector.empty)

  private def withEntryName(entryName: Option[String], detail: String): String =
    entryName.flatMap(nonEmptyTrimmed) match
      case Some(name) => s"plan entry '$name' $detail"
      case None       => detail

  private def nonEmptyTrimmed(value: String): Option[String] =
    val trimmed = value.trim
    Option.when(trimmed.nonEmpty)(trimmed)

  private def planPath(index: Int, field: String): String = s"spec.plan[$index].$field"

  private def error(path: String, detail: String): ManifestValidationError =
    ManifestValidationError(path, detail)

  private def kindOf(value: RawYaml): String = value match
    case RawYaml.NullValue        => "null"
    case RawYaml.StringValue(_)   => "string"
    case RawYaml.BooleanValue(_)  => "boolean"
    case RawYaml.IntegerValue(_)  => "integer"
    case RawYaml.DecimalValue(_)  => "decimal"
    case RawYaml.SequenceValue(_) => "sequence"
    case RawYaml.MappingValue(_)  => "mapping"
