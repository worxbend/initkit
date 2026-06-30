package binstaller.config

import binstaller.config.YamlDecode.*

private[config] object ManifestDecoder:

  def decode(value: Any): DecodeResult[BinaryDistributionProfile] =
    val root       = asMap(value, "$")
    val apiVersion = enumValue(
      requiredString(root.value, "apiVersion"),
      "apiVersion",
      ApiVersion.values.toVector,
      ApiVersion.V1Alpha1,
      _.value
    )
    val kind = enumValue(
      requiredString(root.value, "kind"),
      "kind",
      ManifestKind.values.toVector,
      ManifestKind.BinaryDistributionProfile,
      _.value
    )
    val metadata = decodeMetadata(requiredMap(root.value, "metadata"))
    val spec     = decodeSpec(requiredMap(root.value, "spec"))

    DecodeResult(
      BinaryDistributionProfile(
        apiVersion = apiVersion.value,
        kind = kind.value,
        metadata = metadata.value,
        spec = spec.value
      ),
      root.errors ++ apiVersion.errors ++ kind.errors ++ metadata.errors ++ spec.errors
    )

  private def decodeMetadata(input: DecodeResult[YamlMap]): DecodeResult[ManifestMetadata] =
    val map         = input.value
    val name        = requiredString(map, "metadata.name")
    val labels      = optionalStringMap(map, "labels", "metadata.labels")
    val annotations = optionalStringMap(map, "annotations", "metadata.annotations")
    DecodeResult(
      ManifestMetadata(name.value, labels.value, annotations.value),
      input.errors ++ name.errors ++ labels.errors ++ annotations.errors
    )

  private def decodeSpec(input: DecodeResult[YamlMap]): DecodeResult[ProfileSpec] =
    val map      = input.value
    val policy   = decodePolicy(requiredMap(map, "spec.policy"))
    val vars     = optionalStringMap(map, "vars", "spec.vars")
    val versions = decodeVersions(requiredMap(map, "spec.versions"))
    val plan     = decodePlan(requiredList(map, "spec.plan"))

    DecodeResult(
      ProfileSpec(policy.value, vars.value, versions.value, plan.value),
      input.errors ++ policy.errors ++ vars.errors ++ versions.errors ++ plan.errors
    )

  private def decodePolicy(input: DecodeResult[YamlMap]): DecodeResult[InstallPolicy] =
    val map  = input.value
    val mode = optionalEnumValue(
      optionalString(map, "mode", "spec.policy.mode"),
      "spec.policy.mode",
      PolicyMode.values.toVector,
      PolicyMode.Developer,
      _.value
    )
    val continueOnError =
      optionalBoolean(map, "continueOnError", "spec.policy.continueOnError", default = false)
    val appsDir      = requiredString(map, "spec.policy.appsDir")
    val cleanInstall =
      optionalBoolean(map, "cleanInstall", "spec.policy.cleanInstall", default = true)
    val requireConfirmation = optionalBoolean(
      map,
      "requireConfirmation",
      "spec.policy.requireConfirmation",
      default = true
    )
    val allowSudoSymlinks = optionalBoolean(
      map,
      "allowSudoSymlinks",
      "spec.policy.allowSudoSymlinks",
      default = false
    ).map(AllowSudoSymlinks.fromBoolean)
    val allowDynamicLatestUrls = optionalPolicyOverride(
      map,
      "allowDynamicLatestUrls",
      "spec.policy.allowDynamicLatestUrls"
    )
    val allowMissingChecksums = optionalPolicyOverride(
      map,
      "allowMissingChecksums",
      "spec.policy.allowMissingChecksums"
    )
    val allowTarXzFallback = optionalPolicyOverride(
      map,
      "allowTarXzFallback",
      "spec.policy.allowTarXzFallback"
    )
    val allowArchiveCandidateFallback = optionalPolicyOverride(
      map,
      "allowArchiveCandidateFallback",
      "spec.policy.allowArchiveCandidateFallback"
    )
    val stateFile = optionalString(map, "stateFile", "spec.policy.stateFile")

    DecodeResult(
      InstallPolicy(
        mode = mode.value,
        continueOnError = continueOnError.value,
        appsDir = appsDir.value,
        cleanInstall = cleanInstall.value,
        requireConfirmation = requireConfirmation.value,
        allowSudoSymlinks = allowSudoSymlinks.value,
        allowDynamicLatestUrls = allowDynamicLatestUrls.value,
        allowMissingChecksums = allowMissingChecksums.value,
        allowTarXzFallback = allowTarXzFallback.value,
        allowArchiveCandidateFallback = allowArchiveCandidateFallback.value,
        stateFile = stateFile.value
      ),
      input.errors ++ mode.errors ++ continueOnError.errors ++ appsDir.errors ++
        cleanInstall.errors ++ requireConfirmation.errors ++ allowSudoSymlinks.errors ++
        allowDynamicLatestUrls.errors ++ allowMissingChecksums.errors ++
        allowTarXzFallback.errors ++ allowArchiveCandidateFallback.errors ++ stateFile.errors
    )

  private def decodeVersions(input: DecodeResult[YamlMap])
      : DecodeResult[Map[String, VersionSource]] =
    val decoded = input.value.toVector.map:
      case (name, source) => name -> decodeVersionSource(source, s"spec.versions.$name")

    DecodeResult(
      decoded.map((name, result) => name -> result.value).toMap,
      input.errors ++ decoded.flatMap((_, result) => result.errors)
    )

  private def decodeVersionSource(value: Any, path: String): DecodeResult[VersionSource] =
    value match
      case scalar: String => DecodeResult.valid(VersionSource.Pinned(scalar))
      case map: YamlMap @unchecked if map.contains("dynamic") =>
        decodeDynamicVersion(requiredMap(map, s"$path.dynamic"), s"$path.dynamic")
      case map: YamlMap @unchecked if map.contains("resolver") =>
        decodeVersionResolver(requiredMap(map, s"$path.resolver"), s"$path.resolver")
      case _ => DecodeResult.invalid(
          VersionSource.Pinned(""),
          path,
          "version source must be a pinned string, dynamic block, or resolver block"
        )

  private def decodeDynamicVersion(
      input: DecodeResult[YamlMap],
      path: String
  ): DecodeResult[VersionSource] =
    val map  = input.value
    val kind = enumValue(
      requiredString(map, "type", s"$path.type"),
      s"$path.type",
      DynamicVersionKind.values.toVector,
      DynamicVersionKind.LatestUrl,
      _.value
    )
    val note = optionalString(map, "note", s"$path.note")
    DecodeResult(
      VersionSource.Dynamic(kind.value, note.value),
      input.errors ++ kind.errors ++ note.errors
    )

  private def decodeVersionResolver(
      input: DecodeResult[YamlMap],
      path: String
  ): DecodeResult[VersionSource] =
    val map  = input.value
    val kind = enumValue(
      requiredString(map, "type", s"$path.type"),
      s"$path.type",
      VersionResolverKind.values.toVector,
      VersionResolverKind.HttpText,
      _.value
    )
    val url = requiredString(map, s"$path.url")
    DecodeResult(
      VersionSource.Resolver(kind.value, url.value),
      input.errors ++ kind.errors ++ url.errors
    )

  private def decodePlan(input: DecodeResult[Vector[Any]]): DecodeResult[Vector[PlanEntry]] =
    val decoded = input.value.zipWithIndex.map:
      case (value, index) => decodePlanEntry(asMap(value, s"spec.plan[$index]"), index)

    DecodeResult(
      decoded.map(_.value),
      input.errors ++ decoded.flatMap(_.errors)
    )

  private def decodePlanEntry(input: DecodeResult[YamlMap], index: Int): DecodeResult[PlanEntry] =
    val map  = input.value
    val path = s"spec.plan[$index]"
    val name = requiredString(map, s"$path.name")
    val kind = enumValue(
      requiredString(map, s"$path.kind"),
      s"$path.kind",
      PlanKind.values.toVector,
      PlanKind.BinaryTool,
      _.value
    )
    val description = optionalString(map, "description", s"$path.description")
    val when        = optionalWhen(map, s"$path.when")
    val spec        = decodeBinaryToolSpec(requiredMap(map, s"$path.spec"), path)

    DecodeResult(
      PlanEntry(
        name = name.value,
        kind = kind.value,
        description = description.value,
        when = when.value,
        spec = spec.value
      ),
      input.errors ++ name.errors ++ kind.errors ++ description.errors ++ when.errors ++ spec.errors
    )

  private def optionalWhen(map: YamlMap, path: String): DecodeResult[Option[WhenClause]] =
    map.get("when") match
      case None        => DecodeResult.valid(None)
      case Some(value) =>
        val whenMap      = asMap(value, path)
        val os           = optionalOs(whenMap.value, s"$path.os")
        val architecture = optionalString(whenMap.value, "architecture", s"$path.architecture")
        DecodeResult(
          Some(WhenClause(os.value, architecture.value)),
          whenMap.errors ++ os.errors ++ architecture.errors
        )

  private def optionalOs(map: YamlMap, path: String): DecodeResult[Option[OsClause]] =
    map.get("os") match
      case None        => DecodeResult.valid(None)
      case Some(value) =>
        val osMap  = asMap(value, path)
        val family = optionalString(osMap.value, "family", s"$path.family")
        DecodeResult(Some(OsClause(family.value)), osMap.errors ++ family.errors)

  private def decodeBinaryToolSpec(
      input: DecodeResult[YamlMap],
      entryPath: String
  ): DecodeResult[BinaryToolSpec] =
    val map               = input.value
    val path              = s"$entryPath.spec"
    val versionRef        = requiredString(map, s"$path.versionRef")
    val installDir        = requiredString(map, s"$path.installDir")
    val createDirectories = optionalStringList(map, "createDirectories", s"$path.createDirectories")
    val download          = decodeDownload(requiredMap(map, s"$path.download"), path)
    val unsupportedScript = unsupportedInstaller(map, s"$path.installer")
    val executables       = decodeExecutables(requiredList(map, s"$path.executables"), path)
    val symlinks          = decodeSymlinks(optionalList(map, "symlinks", s"$path.symlinks"), path)

    DecodeResult(
      BinaryToolSpec(
        versionRef = versionRef.value,
        installDir = installDir.value,
        createDirectories = createDirectories.value,
        download = download.value,
        executables = executables.value,
        symlinks = symlinks.value
      ),
      input.errors ++ versionRef.errors ++ installDir.errors ++ createDirectories.errors ++
        download.errors ++ unsupportedScript.errors ++ executables.errors ++ symlinks.errors
    )

  private def decodeDownload(
      input: DecodeResult[YamlMap],
      specPath: String
  ): DecodeResult[DownloadSpec] =
    val map      = input.value
    val path     = s"$specPath.download"
    val url      = requiredString(map, s"$path.url")
    val filename = requiredString(map, s"$path.filename")
    val checksum = optionalChecksum(map, s"$path.checksum")
    val archive  = optionalArchive(map, s"$path.archive")
    DecodeResult(
      DownloadSpec(url.value, filename.value, checksum.value, archive.value),
      input.errors ++ url.errors ++ filename.errors ++ checksum.errors ++ archive.errors
    )

  private def optionalChecksum(map: YamlMap, path: String): DecodeResult[Option[ChecksumSpec]] =
    map.get("checksum") match
      case None        => DecodeResult.valid(None)
      case Some(value) =>
        val checksumMap = asMap(value, path)
        val algorithm   = enumValue(
          requiredString(checksumMap.value, s"$path.algorithm"),
          s"$path.algorithm",
          ChecksumAlgorithm.values.toVector,
          ChecksumAlgorithm.Sha256,
          _.value
        )
        val checksum = optionalString(checksumMap.value, "value", s"$path.value")
        val discover = optionalChecksumDiscovery(
          checksumMap.value,
          s"$path.discover"
        )
        val shapeErrors = checksumShapeErrors(path, checksum.value, discover.value)
        val valueErrors = checksum.value.toVector.flatMap(value =>
          checksumValueErrors(algorithm.value, value, s"$path.value")
        )
        DecodeResult(
          Some(ChecksumSpec(algorithm.value, checksum.value, discover.value)),
          checksumMap.errors ++ algorithm.errors ++ checksum.errors ++ discover.errors ++
            shapeErrors ++ valueErrors
        )

  private def optionalChecksumDiscovery(
      map: YamlMap,
      path: String
  ): DecodeResult[Option[ChecksumDiscoverySpec]] = map.get("discover") match
    case None        => DecodeResult.valid(None)
    case Some(value) =>
      val sourceMap = asMap(value, path)
      val kind      = enumValue(
        requiredString(sourceMap.value, s"$path.type"),
        s"$path.type",
        ChecksumDiscoveryKind.values.toVector,
        ChecksumDiscoveryKind.Sha256Sum,
        _.value
      )
      val url  = requiredString(sourceMap.value, s"$path.url")
      val file = optionalString(sourceMap.value, "file", s"$path.file")
      DecodeResult(
        Some(ChecksumDiscoverySpec(kind.value, url.value, file.value)),
        sourceMap.errors ++ kind.errors ++ url.errors ++ file.errors
      )

  private def checksumShapeErrors(
      path: String,
      value: Option[String],
      discover: Option[ChecksumDiscoverySpec]
  ): Vector[ValidationError] = (value, discover) match
    case (Some(_), Some(_)) =>
      Vector(ValidationError(path, "checksum must declare either value or discover, not both"))
    case (None, None) => Vector(ValidationError(path, "checksum must declare value or discover"))
    case _            => Vector.empty

  private def checksumValueErrors(
      algorithm: ChecksumAlgorithm,
      value: String,
      path: String
  ): Vector[ValidationError] = algorithm match
    case ChecksumAlgorithm.Sha256 =>
      // The value is format-checked here so checksum mismatches later mean artifact integrity,
      // not a malformed manifest value being treated as a runtime comparison target.
      if value.matches("(?i)^[0-9a-f]{64}$") then Vector.empty
      else Vector(ValidationError(path, "sha256 checksum must be 64 hexadecimal characters"))

  private def optionalArchive(map: YamlMap, path: String): DecodeResult[Option[ArchiveSpec]] =
    map.get("archive") match
      case None        => DecodeResult.valid(None)
      case Some(value) =>
        val archiveMap  = asMap(value, path)
        val archiveType = enumValue(
          requiredString(archiveMap.value, s"$path.type"),
          s"$path.type",
          ArchiveType.values.toVector,
          ArchiveType.Zip,
          _.value
        )
        val extract = decodeArchiveExtract(requiredMap(archiveMap.value, s"$path.extract"), path)
        DecodeResult(
          Some(ArchiveSpec(archiveType.value, extract.value)),
          archiveMap.errors ++ archiveType.errors ++ extract.errors
        )

  private def decodeArchiveExtract(
      input: DecodeResult[YamlMap],
      archivePath: String
  ): DecodeResult[ArchiveExtract] =
    val map   = input.value
    val files = decodeExtractMappings(
      optionalList(map, "files", s"$archivePath.extract.files"),
      s"$archivePath.extract.files"
    )
    val directories = decodeExtractMappings(
      optionalList(map, "directories", s"$archivePath.extract.directories"),
      s"$archivePath.extract.directories"
    )
    DecodeResult(
      ArchiveExtract(files.value, directories.value),
      input.errors ++ files.errors ++ directories.errors
    )

  private def decodeExtractMappings(
      input: DecodeResult[Vector[Any]],
      path: String
  ): DecodeResult[Vector[ExtractMapping]] =
    val decoded = input.value.zipWithIndex.map:
      case (value, index) =>
        val item = asMap(value, s"$path[$index]")
        val from = requiredString(item.value, s"$path[$index].from")
        val to   = requiredString(item.value, s"$path[$index].to")
        DecodeResult(
          ExtractMapping(from.value, to.value),
          item.errors ++ from.errors ++ to.errors
        )
    DecodeResult(decoded.map(_.value), input.errors ++ decoded.flatMap(_.errors))

  private def unsupportedInstaller(map: YamlMap, path: String): DecodeResult[Unit] =
    map.get("installer") match
      case None    => DecodeResult.valid(())
      case Some(_) => DecodeResult.invalid(
          (),
          path,
          // Installer scripts are deliberately rejected at config load so no later boundary has
          // to decide whether arbitrary manifest text is executable.
          "installer scripts are not supported; use direct binary or archive download"
        )

  private def decodeExecutables(
      input: DecodeResult[Vector[Any]],
      specPath: String
  ): DecodeResult[Vector[ExecutableSpec]] =
    val path    = s"$specPath.executables"
    val decoded = input.value.zipWithIndex.map:
      case (value, index) =>
        val item = asMap(value, s"$path[$index]")
        val file = requiredString(item.value, s"$path[$index].path")
        val mode = optionalMode(item.value, s"$path[$index].mode")
        DecodeResult(
          ExecutableSpec(file.value, mode.value),
          item.errors ++ file.errors ++ mode.errors
        )
    DecodeResult(decoded.map(_.value), input.errors ++ decoded.flatMap(_.errors))

  private def decodeSymlinks(
      input: DecodeResult[Vector[Any]],
      specPath: String
  ): DecodeResult[Vector[SymlinkSpec]] =
    val path    = s"$specPath.symlinks"
    val decoded = input.value.zipWithIndex.map:
      case (value, index) =>
        val item   = asMap(value, s"$path[$index]")
        val file   = requiredString(item.value, s"$path[$index].path")
        val target = requiredString(item.value, s"$path[$index].target")
        val sudo   = optionalBoolean(item.value, "sudo", s"$path[$index].sudo", default = false)
          .map(SymlinkPrivilege.fromBoolean)
        DecodeResult(
          SymlinkSpec(file.value, target.value, sudo.value),
          item.errors ++ file.errors ++ target.errors ++ sudo.errors
        )
    DecodeResult(decoded.map(_.value), input.errors ++ decoded.flatMap(_.errors))

  private def optionalMode(map: YamlMap, path: String): DecodeResult[Option[ExecutableMode]] =
    map.get("mode") match
      case None                                              => DecodeResult.valid(None)
      case Some(value: String) if value.matches("0[0-7]{3}") =>
        DecodeResult.valid(Some(ExecutableMode(value)))
      case Some(_: String) =>
        DecodeResult.invalid(None, path, "mode must be a four-digit octal string")
      case Some(_) => DecodeResult.invalid(None, path, "mode must be a string")

  private def optionalPolicyOverride(
      map: YamlMap,
      key: String,
      path: String
  ): DecodeResult[Option[PolicyOverride]] = map.get(key) match
    case None                 => DecodeResult.valid(None)
    case Some(value: Boolean) => DecodeResult.valid(Some(PolicyOverride.fromBoolean(value)))
    case Some(_)              => DecodeResult.invalid(None, path, "value must be a boolean")
