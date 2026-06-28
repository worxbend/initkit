package initkit.config

import java.nio.file.Path
import scala.collection.immutable.VectorMap

object InstallerSpecDecoder:

  val InstallerKinds: Set[String] = Set(
    "binary-downloads",
    "shell-scripts",
    "nerd-fonts",
    "dotfiles-apply",
    "file-writes",
    "interrupt",
    "commands"
  )

  def isInstallerKind(kind: String): Boolean = InstallerKinds.contains(kind)

  def decode(entry: PlanEntry, index: Int): Either[Vector[ManifestValidationError], InstallerSpec] =
    entry.kind.flatMap(nonEmptyTrimmed) match
      case Some(kind) =>
        decode(kind, entry.spec, planPath(index, "spec"), entry.name, manifestPath = None)
      case None => Left(Vector(error(planPath(index, "kind"), "is required")))

  def decode(
      kind: String,
      spec: Option[RawYaml],
      specAt: String,
      entryName: Option[String],
      manifestPath: Option[Path]
  ): Either[Vector[ManifestValidationError], InstallerSpec] = spec match
    case None                               => Left(Vector(error(specAt, "is required")))
    case Some(RawYaml.MappingValue(fields)) =>
      decodeInstallerFields(kind, fields, specAt, entryName, manifestPath)
    case Some(other) => Left(Vector(error(specAt, s"must be a mapping, found ${kindOf(other)}")))

  private def decodeInstallerFields(
      kind: String,
      fields: VectorMap[String, RawYaml],
      specAt: String,
      entryName: Option[String],
      manifestPath: Option[Path]
  ): Either[Vector[ManifestValidationError], InstallerSpec] = kind match
    case "binary-downloads" => decodeBinaryDownloads(fields, specAt, entryName)
    case "shell-scripts"    => decodeShellScripts(fields, specAt, entryName)
    case "nerd-fonts"       => decodeNerdFonts(fields, specAt)
    case "dotfiles-apply"   => decodeDotfilesApply(fields, specAt)
    case "file-writes"      => decodeFileWrites(fields, specAt, entryName)
    case "interrupt"        => decodeInterrupt(fields, specAt, manifestPath)
    case "commands"         => decodeCommands(fields, specAt, entryName)
    case other              => Left(Vector(error(specAt, s"unsupported installer kind '$other'")))

  private def decodeBinaryDownloads(
      fields: VectorMap[String, RawYaml],
      specAt: String,
      entryName: Option[String]
  ): Either[Vector[ManifestValidationError], InstallerSpec] =
    decodeRequiredItems(fields, specAt, entryName, "download item", decodeBinaryDownloadItem)
      .map(InstallerSpec.BinaryDownloads.apply)

  private def decodeBinaryDownloadItem(
      raw: RawYaml,
      at: String
  ): Either[Vector[ManifestValidationError], BinaryDownloadItem] = raw match
    case RawYaml.MappingValue(fields) => combine(
        decodeRequiredString(fields, "name", s"$at.name"),
        decodeRequiredString(fields, "url", s"$at.url"),
        decodeRequiredString(fields, "destination", s"$at.destination"),
        decodeRequiredString(fields, "mode", s"$at.mode"),
        decodeOptionalChecksum(fields, s"$at.checksum"),
        decodeOptionalArchive(fields, s"$at.archive"),
        decodeBinarySymlinks(fields, s"$at.symlinks")
      ).map { case (name, url, destination, mode, checksum, archive, symlinks) =>
        BinaryDownloadItem(name, url, destination, mode, checksum, archive, symlinks)
      }
    case other => Left(Vector(error(at, s"must be a mapping, found ${kindOf(other)}")))

  private def decodeOptionalChecksum(
      fields: VectorMap[String, RawYaml],
      at: String
  ): Either[Vector[ManifestValidationError], Option[Checksum]] = fields.get("checksum") match
    case None                                       => Right(None)
    case Some(RawYaml.MappingValue(checksumFields)) => combine(
        decodeChecksumAlgorithm(checksumFields, s"$at.algorithm"),
        decodeRequiredString(checksumFields, "value", s"$at.value")
      ).map { case (algorithm, value) => Some(Checksum(algorithm, value)) }
    case Some(other) => Left(Vector(error(at, s"must be a mapping, found ${kindOf(other)}")))

  private def decodeChecksumAlgorithm(
      fields: VectorMap[String, RawYaml],
      at: String
  ): Either[Vector[ManifestValidationError], ChecksumAlgorithm] = fields.get("algorithm") match
    case Some(RawYaml.StringValue(value)) => value.trim.toLowerCase match
        case "sha256" => Right(ChecksumAlgorithm.Sha256)
        case "sha512" => Right(ChecksumAlgorithm.Sha512)
        case other    => Left(Vector(error(at, s"unsupported checksum algorithm '$other'")))
    case Some(other) => Left(Vector(error(at, s"must be a string, found ${kindOf(other)}")))
    case None        => Left(Vector(error(at, "is required")))

  private def decodeOptionalArchive(
      fields: VectorMap[String, RawYaml],
      at: String
  ): Either[Vector[ManifestValidationError], Option[Archive]] = fields.get("archive") match
    case None                                      => Right(None)
    case Some(RawYaml.MappingValue(archiveFields)) => combine(
        decodeArchiveType(archiveFields, s"$at.type"),
        decodeRequiredString(archiveFields, "path", s"$at.path"),
        decodeOptionalNonNegativeInt(archiveFields, "stripComponents", s"$at.stripComponents")
      ).map { case (archiveType, path, stripComponents) =>
        Some(Archive(archiveType, path, stripComponents))
      }
    case Some(other) => Left(Vector(error(at, s"must be a mapping, found ${kindOf(other)}")))

  private def decodeArchiveType(
      fields: VectorMap[String, RawYaml],
      at: String
  ): Either[Vector[ManifestValidationError], ArchiveType] = fields.get("type") match
    case Some(RawYaml.StringValue(value)) => value.trim.toLowerCase match
        case "tar.gz" => Right(ArchiveType.TarGz)
        case "tar.xz" => Right(ArchiveType.TarXz)
        case "zip"    => Right(ArchiveType.Zip)
        case other    => Left(Vector(error(at, s"unsupported archive type '$other'")))
    case Some(other) => Left(Vector(error(at, s"must be a string, found ${kindOf(other)}")))
    case None        => Left(Vector(error(at, "is required")))

  private def decodeShellScripts(
      fields: VectorMap[String, RawYaml],
      specAt: String,
      entryName: Option[String]
  ): Either[Vector[ManifestValidationError], InstallerSpec] =
    decodeRequiredItems(fields, specAt, entryName, "shell script item", decodeShellScriptItem)
      .map(InstallerSpec.ShellScripts.apply)

  private def decodeShellScriptItem(
      raw: RawYaml,
      at: String
  ): Either[Vector[ManifestValidationError], ShellScriptItem] = raw match
    case RawYaml.MappingValue(fields) =>
      for
        name             <- decodeRequiredString(fields, "name", s"$at.name")
        url              <- decodeRequiredString(fields, "url", s"$at.url")
        shell            <- decodeRequiredString(fields, "shell", s"$at.shell")
        args             <- decodeStringSequence(fields, "args", s"$at.args")
        creates          <- decodeOptionalString(fields, "creates", s"$at.creates")
        env              <- decodeEnvironment(fields, s"$at.env")
        mode             <- decodeShellScriptMode(fields, s"$at.mode")
        download         <- decodeShellScriptDownloadMode(fields, s"$at.download")
        cleanup          <- decodeOptionalBoolean(fields, "cleanup", s"$at.cleanup")
        sudo             <- decodeOptionalBoolean(fields, "sudo", s"$at.sudo")
        cwd              <- decodeOptionalString(fields, "cwd", s"$at.cwd")
        timeout          <- decodeOptionalPositiveInt(fields, "timeout", s"$at.timeout")
        allowedExitCodes <- decodeAllowedExitCodes(fields, s"$at.allowedExitCodes")
      yield ShellScriptItem(
        name,
        url,
        shell,
        args,
        creates,
        env,
        mode,
        download,
        cleanup,
        sudo,
        cwd,
        timeout,
        allowedExitCodes
      )
    case other => Left(Vector(error(at, s"must be a mapping, found ${kindOf(other)}")))

  private def decodeNerdFonts(
      fields: VectorMap[String, RawYaml],
      specAt: String
  ): Either[Vector[ManifestValidationError], InstallerSpec] = combine(
    decodeRequiredTool(fields, s"$specAt.tool"),
    decodeRequiredGeneratedConfig(fields, s"$specAt.config"),
    decodeOptionalPreview(fields, s"$specAt.preview")
  ).map { case (tool, config, preview) => InstallerSpec.NerdFonts(tool, config, preview) }

  private def decodeDotfilesApply(
      fields: VectorMap[String, RawYaml],
      specAt: String
  ): Either[Vector[ManifestValidationError], InstallerSpec] = combine(
    decodeRequiredTool(fields, s"$specAt.tool"),
    decodeRequiredRepository(fields, s"$specAt.repository"),
    decodeRequiredDotfilesConfig(fields, s"$specAt.config"),
    decodeOptionalPreview(fields, s"$specAt.preview")
  ).map { case (tool, repository, config, preview) =>
    InstallerSpec.DotfilesApply(tool, repository, config, preview)
  }

  private def decodeRequiredTool(
      fields: VectorMap[String, RawYaml],
      at: String
  ): Either[Vector[ManifestValidationError], ToolInvocation] = fields.get("tool") match
    case Some(RawYaml.MappingValue(toolFields)) => combine(
        decodeRequiredString(toolFields, "path", s"$at.path"),
        decodeStringSequence(toolFields, "args", s"$at.args")
      ).map { case (path, args) => ToolInvocation(path, args) }
    case Some(other) => Left(Vector(error(at, s"must be a mapping, found ${kindOf(other)}")))
    case None        => Left(Vector(error(at, "is required")))

  private def decodeRequiredGeneratedConfig(
      fields: VectorMap[String, RawYaml],
      at: String
  ): Either[Vector[ManifestValidationError], GeneratedConfig] = fields.get("config") match
    case Some(RawYaml.MappingValue(configFields)) => combine(
        decodeRequiredString(configFields, "path", s"$at.path"),
        decodeOptionalBoolean(configFields, "create", s"$at.create"),
        Right(configFields.get("content"))
      ).map { case (path, create, content) => GeneratedConfig(path, create, content) }
    case Some(other) => Left(Vector(error(at, s"must be a mapping, found ${kindOf(other)}")))
    case None        => Left(Vector(error(at, "is required")))

  private def decodeOptionalPreview(
      fields: VectorMap[String, RawYaml],
      at: String
  ): Either[Vector[ManifestValidationError], Option[PreviewInvocation]] =
    fields.get("preview") match
      case None                                      => Right(None)
      case Some(RawYaml.MappingValue(previewFields)) => combine(
          decodeOptionalBoolean(previewFields, "enabled", s"$at.enabled"),
          decodeStringSequence(previewFields, "args", s"$at.args")
        ).map { case (enabled, args) => Some(PreviewInvocation(enabled, args)) }
      case Some(other) => Left(Vector(error(at, s"must be a mapping, found ${kindOf(other)}")))

  private def decodeRequiredRepository(
      fields: VectorMap[String, RawYaml],
      at: String
  ): Either[Vector[ManifestValidationError], GitRepository] = fields.get("repository") match
    case Some(RawYaml.MappingValue(repositoryFields)) => combine(
        decodeRequiredString(repositoryFields, "url", s"$at.url"),
        decodeOptionalString(repositoryFields, "ref", s"$at.ref"),
        decodeRequiredString(repositoryFields, "destination", s"$at.destination"),
        decodeOptionalBoolean(repositoryFields, "update", s"$at.update")
      ).map { case (url, ref, destination, update) => GitRepository(url, ref, destination, update) }
    case Some(other) => Left(Vector(error(at, s"must be a mapping, found ${kindOf(other)}")))
    case None        => Left(Vector(error(at, "is required")))

  private def decodeRequiredDotfilesConfig(
      fields: VectorMap[String, RawYaml],
      at: String
  ): Either[Vector[ManifestValidationError], DotfilesConfig] = fields.get("config") match
    case Some(RawYaml.MappingValue(configFields)) => combine(
        decodeRequiredString(configFields, "path", s"$at.path"),
        decodeOptionalString(configFields, "sourceUrl", s"$at.sourceUrl")
      ).map { case (path, sourceUrl) => DotfilesConfig(path, sourceUrl) }
    case Some(other) => Left(Vector(error(at, s"must be a mapping, found ${kindOf(other)}")))
    case None        => Left(Vector(error(at, "is required")))

  private def decodeFileWrites(
      fields: VectorMap[String, RawYaml],
      specAt: String,
      entryName: Option[String]
  ): Either[Vector[ManifestValidationError], InstallerSpec] =
    decodeRequiredItems(fields, specAt, entryName, "file write item", decodeFileWriteItem)
      .map(InstallerSpec.FileWrites.apply)

  private def decodeFileWriteItem(
      raw: RawYaml,
      at: String
  ): Either[Vector[ManifestValidationError], FileWriteItem] = raw match
    case RawYaml.MappingValue(fields) => combine(
        decodeRequiredString(fields, "name", s"$at.name"),
        decodeRequiredString(fields, "path", s"$at.path"),
        decodeRequiredString(fields, "content", s"$at.content"),
        decodeOptionalBoolean(fields, "sudo", s"$at.sudo"),
        decodeOptionalString(fields, "owner", s"$at.owner"),
        decodeOptionalString(fields, "group", s"$at.group"),
        decodeOptionalString(fields, "mode", s"$at.mode"),
        decodeOptionalCondition(fields, "when", s"$at.when")
      ).map { case (name, path, content, sudo, owner, group, mode, when) =>
        FileWriteItem(name, path, content, sudo, owner, group, mode, when)
      }
    case other => Left(Vector(error(at, s"must be a mapping, found ${kindOf(other)}")))

  private def decodeInterrupt(
      fields: VectorMap[String, RawYaml],
      specAt: String,
      manifestPath: Option[java.nio.file.Path]
  ): Either[Vector[ManifestValidationError], InstallerSpec] = combine(
    decodeRequiredString(fields, "reason", s"$specAt.reason"),
    decodeRequiredInterruptState(fields, s"$specAt.state", manifestPath),
    decodeStringSequence(fields, "instructions", s"$specAt.instructions"),
    decodeInterruptExit(fields, s"$specAt.exit")
  ).map { case (reason, state, instructions, exit) =>
    InstallerSpec.Interrupt(reason, state, instructions, exit)
  }

  private def decodeRequiredInterruptState(
      fields: VectorMap[String, RawYaml],
      at: String,
      manifestPath: Option[Path]
  ): Either[Vector[ManifestValidationError], InterruptState] = fields.get("state") match
    case Some(RawYaml.MappingValue(stateFields)) =>
      val statePath         = decodeRequiredString(stateFields, "path", s"$at.path")
      val format            = decodeJsonStateFormat(stateFields, s"$at.format")
      val resumeFrom        = decodeOptionalResumeFrom(stateFields, s"$at.resumeFrom")
      val separateStatePath =
        statePath.flatMap(path => validateStatePathSeparation(path, s"$at.path", manifestPath))
      combine(statePath, format, resumeFrom, separateStatePath).map {
        case (path, _, resumeFrom, _) => InterruptState(path, resumeFrom)
      }
    case Some(other) => Left(Vector(error(at, s"must be a mapping, found ${kindOf(other)}")))
    case None        => Left(Vector(error(at, "is required")))

  private def decodeJsonStateFormat(
      fields: VectorMap[String, RawYaml],
      at: String
  ): Either[Vector[ManifestValidationError], Unit] = fields.get("format") match
    case Some(RawYaml.StringValue(value)) if value.trim == "json" => Right(())
    case Some(RawYaml.StringValue(value))                         =>
      Left(Vector(error(at, s"unsupported state format '$value'")))
    case Some(other) => Left(Vector(error(at, s"must be a string, found ${kindOf(other)}")))
    case None        => Left(Vector(error(at, "is required")))

  private def decodeOptionalResumeFrom(
      fields: VectorMap[String, RawYaml],
      at: String
  ): Either[Vector[ManifestValidationError], Option[InterruptResumeFrom]] =
    fields.get("resumeFrom") match
      case None                             => Right(None)
      case Some(RawYaml.StringValue(value)) => value.trim match
          case "current" => Right(Some(InterruptResumeFrom.Current))
          case "next"    => Right(Some(InterruptResumeFrom.Next))
          case other     => Left(Vector(error(at, s"unsupported resume target '$other'")))
      case Some(other) => Left(Vector(error(at, s"must be a string, found ${kindOf(other)}")))

  private def decodeInterruptExit(
      fields: VectorMap[String, RawYaml],
      at: String
  ): Either[Vector[ManifestValidationError], InterruptExit] = fields.get("exit") match
    case None                                   => Right(InterruptExit(code = None, message = None))
    case Some(RawYaml.MappingValue(exitFields)) => combine(
        decodeOptionalInt(exitFields, "code", s"$at.code"),
        decodeOptionalString(exitFields, "message", s"$at.message")
      ).map { case (code, message) => InterruptExit(code, message) }
    case Some(other) => Left(Vector(error(at, s"must be a mapping, found ${kindOf(other)}")))

  private def validateStatePathSeparation(
      statePath: String,
      at: String,
      manifestPath: Option[Path]
  ): Either[Vector[ManifestValidationError], Unit] =
    if statePath.contains("${") then Right(())
    else
      manifestPath match
        case Some(path)
            if Path.of(statePath).toAbsolutePath.normalize() == path.toAbsolutePath.normalize() =>
          Left(Vector(error(at, "must not point to the manifest file")))
        case _ => Right(())

  private def decodeCommands(
      fields: VectorMap[String, RawYaml],
      specAt: String,
      entryName: Option[String]
  ): Either[Vector[ManifestValidationError], InstallerSpec] =
    decodeRequiredItems(fields, specAt, entryName, "command item", decodeCommandItem)
      .map(InstallerSpec.Commands.apply)

  private def decodeCommandItem(
      raw: RawYaml,
      at: String
  ): Either[Vector[ManifestValidationError], CommandItem] = raw match
    case RawYaml.MappingValue(fields) => combine(
        decodeRequiredString(fields, "name", s"$at.name"),
        decodeRequiredString(fields, "run", s"$at.run"),
        decodeOptionalBoolean(fields, "sudo", s"$at.sudo"),
        decodeOptionalCondition(fields, "when", s"$at.when"),
        decodeOptionalString(fields, "cwd", s"$at.cwd"),
        decodeEnvironment(fields, s"$at.env"),
        decodeOptionalString(fields, "creates", s"$at.creates"),
        decodeOptionalString(fields, "unless", s"$at.unless"),
        decodeAllowedExitCodes(fields, s"$at.allowedExitCodes"),
        decodeOptionalString(fields, "confirm", s"$at.confirm"),
        decodeOptionalPositiveInt(fields, "timeout", s"$at.timeout")
      ).map {
        case (
              name,
              run,
              sudo,
              when,
              cwd,
              env,
              creates,
              unless,
              allowedExitCodes,
              confirm,
              timeout
            ) => CommandItem(
            name,
            run,
            sudo,
            when,
            cwd,
            env,
            creates,
            unless,
            allowedExitCodes,
            confirm,
            timeout
          )
      }
    case other => Left(Vector(error(at, s"must be a mapping, found ${kindOf(other)}")))

  private def decodeOptionalCondition(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): Either[Vector[ManifestValidationError], Option[Condition]] = fields.get(key) match
    case None                                        => Right(None)
    case Some(RawYaml.MappingValue(conditionFields)) => combine(
        decodeOptionalOsCondition(conditionFields, "os", s"$at.os"),
        decodeOptionalString(conditionFields, "commandExists", s"$at.commandExists"),
        Right(RawYaml.MappingValue(conditionFields))
      ).map { case (os, commandExists, raw) => Some(Condition(os, commandExists, raw)) }
    case Some(other) => Left(Vector(error(at, s"must be a mapping, found ${kindOf(other)}")))

  private def decodeOptionalOsCondition(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): Either[Vector[ManifestValidationError], Option[OsCondition]] = fields.get(key) match
    case None                                 => Right(None)
    case Some(RawYaml.MappingValue(osFields)) => combine(
        decodeOptionalMatch(osFields, "family", s"$at.family"),
        decodeOptionalMatch(osFields, "distribution", s"$at.distribution"),
        decodeOptionalMatch(osFields, "version", s"$at.version"),
        decodeOptionalMatch(osFields, "codename", s"$at.codename"),
        decodeOptionalMatch(osFields, "architecture", s"$at.architecture"),
        decodeOptionalMatch(osFields, "desktop", s"$at.desktop")
      ).map { case (family, distribution, version, codename, architecture, desktop) =>
        Some(
          OsCondition(
            family = family,
            distribution = distribution,
            version = version,
            codename = codename,
            architecture = architecture,
            desktop = desktop,
            raw = RawYaml.MappingValue(osFields)
          )
        )
      }
    case Some(other) => Left(Vector(error(at, s"must be a mapping, found ${kindOf(other)}")))

  private def decodeOptionalMatch(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): Either[Vector[ManifestValidationError], Option[MatchExpression]] = fields.get(key) match
    case None        => Right(None)
    case Some(value) => decodeMatchExpression(value, at).map(Some(_))

  private def decodeMatchExpression(
      raw: RawYaml,
      at: String
  ): Either[Vector[ManifestValidationError], MatchExpression] = raw match
    case RawYaml.StringValue(value)   => Right(MatchExpression.Exact(value))
    case RawYaml.MappingValue(fields) => fields.get("oneOf") match
        case Some(RawYaml.SequenceValue(items)) => sequence(items.zipWithIndex.map((item, index) =>
            decodeString(item, s"$at.oneOf[$index]")
          ))
            .map(MatchExpression.OneOf.apply)
        case Some(other) =>
          Left(Vector(error(s"$at.oneOf", s"must be a sequence, found ${kindOf(other)}")))
        case None => Left(Vector(error(at, "must be a scalar string or an object with oneOf")))
    case other => Left(Vector(error(
        at,
        s"must be a scalar string or an object with oneOf, found ${kindOf(other)}"
      )))

  private def decodeRequiredItems[A](
      fields: VectorMap[String, RawYaml],
      specAt: String,
      entryName: Option[String],
      itemLabel: String,
      decodeItem: (RawYaml, String) => Either[Vector[ManifestValidationError], A]
  ): Either[Vector[ManifestValidationError], Vector[A]] = fields.get("items") match
    case Some(RawYaml.SequenceValue(items)) if items.nonEmpty =>
      sequence(items.zipWithIndex.map((item, index) => decodeItem(item, s"$specAt.items[$index]")))
    case Some(RawYaml.SequenceValue(_)) => Left(Vector(error(
        s"$specAt.items",
        withEntryName(entryName, s"must contain at least one $itemLabel")
      )))
    case Some(other) =>
      Left(Vector(error(s"$specAt.items", s"must be a non-empty sequence, found ${kindOf(other)}")))
    case None => Left(Vector(error(
        s"$specAt.items",
        withEntryName(entryName, s"is required and must contain at least one $itemLabel")
      )))

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

  private def decodeStringSequence(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): Either[Vector[ManifestValidationError], Vector[String]] = fields.get(key) match
    case None                               => Right(Vector.empty)
    case Some(RawYaml.SequenceValue(items)) =>
      sequence(items.zipWithIndex.map((item, index) => decodeString(item, s"$at[$index]")))
    case Some(other) => Left(Vector(error(at, s"must be a sequence, found ${kindOf(other)}")))

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

  private def decodeOptionalInt(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): Either[Vector[ManifestValidationError], Option[Int]] = fields.get(key) match
    case None                                                  => Right(None)
    case Some(RawYaml.IntegerValue(value)) if value.isValidInt => Right(Some(value.toInt))
    case Some(RawYaml.IntegerValue(_)) => Left(Vector(error(at, "must fit in a 32-bit integer")))
    case Some(other) => Left(Vector(error(at, s"must be an integer, found ${kindOf(other)}")))

  private def decodeOptionalNonNegativeInt(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): Either[Vector[ManifestValidationError], Option[Int]] =
    decodeOptionalInt(fields, key, at).flatMap:
      case Some(value) if value < 0 => Left(Vector(error(at, "must be at least 0")))
      case value                    => Right(value)

  private def decodeOptionalPositiveInt(
      fields: VectorMap[String, RawYaml],
      key: String,
      at: String
  ): Either[Vector[ManifestValidationError], Option[Int]] =
    decodeOptionalInt(fields, key, at).flatMap:
      case Some(value) if value <= 0 => Left(Vector(error(at, "must be greater than 0")))
      case value                     => Right(value)

  private def decodeAllowedExitCodes(
      fields: VectorMap[String, RawYaml],
      at: String
  ): Either[Vector[ManifestValidationError], Vector[Int]] = fields.get("allowedExitCodes") match
    case None                               => Right(Vector(0))
    case Some(RawYaml.SequenceValue(items)) =>
      sequence(items.zipWithIndex.map((item, index) => decodeExitCode(item, s"$at[$index]")))
    case Some(other) => Left(Vector(error(at, s"must be a sequence, found ${kindOf(other)}")))

  private def decodeExitCode(
      raw: RawYaml,
      at: String
  ): Either[Vector[ManifestValidationError], Int] = raw match
    case RawYaml.IntegerValue(value) if value.isValidInt && value >= 0 => Right(value.toInt)
    case RawYaml.IntegerValue(_) => Left(Vector(error(at, "must be a non-negative 32-bit integer")))
    case other => Left(Vector(error(at, s"must be an integer, found ${kindOf(other)}")))

  private def decodeEnvironment(
      fields: VectorMap[String, RawYaml],
      at: String
  ): Either[Vector[ManifestValidationError], Vector[EnvironmentEntry]] = fields.get("env") match
    case None                                  => Right(Vector.empty)
    case Some(RawYaml.MappingValue(envFields)) => sequence(envFields.toVector.map {
        case (name, value) =>
          decodeString(value, s"$at.$name").map(EnvironmentEntry(name, _, sensitive = None))
      })
    case Some(RawYaml.SequenceValue(items)) => sequence(items.zipWithIndex.map((item, index) =>
        decodeEnvironmentEntry(item, s"$at[$index]")
      ))
    case Some(other) =>
      Left(Vector(error(at, s"must be a mapping or sequence, found ${kindOf(other)}")))

  private def decodeEnvironmentEntry(
      raw: RawYaml,
      at: String
  ): Either[Vector[ManifestValidationError], EnvironmentEntry] = raw match
    case RawYaml.MappingValue(fields) => combine(
        decodeRequiredString(fields, "name", s"$at.name"),
        decodeRequiredString(fields, "value", s"$at.value"),
        decodeOptionalBoolean(fields, "sensitive", s"$at.sensitive")
      ).map { case (name, value, sensitive) => EnvironmentEntry(name, value, sensitive) }
    case other => Left(Vector(error(at, s"must be a mapping, found ${kindOf(other)}")))

  private def decodeShellScriptMode(
      fields: VectorMap[String, RawYaml],
      at: String
  ): Either[Vector[ManifestValidationError], ShellScriptMode] = fields.get("mode") match
    case None                             => Right(ShellScriptMode.Unattended)
    case Some(RawYaml.StringValue(value)) => value.trim match
        case "interactive" => Right(ShellScriptMode.Interactive)
        case "unattended"  => Right(ShellScriptMode.Unattended)
        case other         => Left(Vector(error(at, s"unsupported shell script mode '$other'")))
    case Some(other) => Left(Vector(error(at, s"must be a string, found ${kindOf(other)}")))

  private def decodeShellScriptDownloadMode(
      fields: VectorMap[String, RawYaml],
      at: String
  ): Either[Vector[ManifestValidationError], ShellScriptDownloadMode] = fields.get("download") match
    case None                             => Right(ShellScriptDownloadMode.File)
    case Some(RawYaml.StringValue(value)) => value.trim match
        case "stdin" => Right(ShellScriptDownloadMode.Stdin)
        case "file"  => Right(ShellScriptDownloadMode.File)
        case other   => Left(Vector(error(at, s"unsupported shell script download mode '$other'")))
    case Some(other) => Left(Vector(error(at, s"must be a string, found ${kindOf(other)}")))

  private def decodeBinarySymlinks(
      fields: VectorMap[String, RawYaml],
      at: String
  ): Either[Vector[ManifestValidationError], Vector[BinarySymlink]] = fields.get("symlinks") match
    case None                               => Right(Vector.empty)
    case Some(RawYaml.SequenceValue(items)) =>
      sequence(items.zipWithIndex.map((item, index) => decodeBinarySymlink(item, s"$at[$index]")))
    case Some(other) => Left(Vector(error(at, s"must be a sequence, found ${kindOf(other)}")))

  private def decodeBinarySymlink(
      raw: RawYaml,
      at: String
  ): Either[Vector[ManifestValidationError], BinarySymlink] = raw match
    case RawYaml.StringValue(value) if value.trim.nonEmpty =>
      Right(BinarySymlink(value, None, None))
    case RawYaml.StringValue(_)       => Left(Vector(error(at, "must not be empty")))
    case RawYaml.MappingValue(fields) => combine(
        decodeRequiredString(fields, "path", s"$at.path"),
        decodeOptionalString(fields, "target", s"$at.target"),
        decodeOptionalBoolean(fields, "sudo", s"$at.sudo")
      ).map { case (path, target, sudo) => BinarySymlink(path, target, sudo) }
    case other => Left(Vector(error(at, s"must be a path or mapping, found ${kindOf(other)}")))

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

  private def combine[A, B, C, D](
      first: Either[Vector[ManifestValidationError], A],
      second: Either[Vector[ManifestValidationError], B],
      third: Either[Vector[ManifestValidationError], C],
      fourth: Either[Vector[ManifestValidationError], D]
  ): Either[Vector[ManifestValidationError], (A, B, C, D)] = (first, second, third, fourth) match
    case (Right(a), Right(b), Right(c), Right(d)) => Right((a, b, c, d))
    case _                                        =>
      Left(leftErrors(first) ++ leftErrors(second) ++ leftErrors(third) ++ leftErrors(fourth))

  private def combine[A, B, C, D, E](
      first: Either[Vector[ManifestValidationError], A],
      second: Either[Vector[ManifestValidationError], B],
      third: Either[Vector[ManifestValidationError], C],
      fourth: Either[Vector[ManifestValidationError], D],
      fifth: Either[Vector[ManifestValidationError], E]
  ): Either[Vector[ManifestValidationError], (A, B, C, D, E)] =
    (first, second, third, fourth, fifth) match
      case (Right(a), Right(b), Right(c), Right(d), Right(e)) => Right((a, b, c, d, e))
      case _ => Left(leftErrors(first) ++ leftErrors(second) ++ leftErrors(third) ++
          leftErrors(fourth) ++ leftErrors(fifth))

  private def combine[A, B, C, D, E, F](
      first: Either[Vector[ManifestValidationError], A],
      second: Either[Vector[ManifestValidationError], B],
      third: Either[Vector[ManifestValidationError], C],
      fourth: Either[Vector[ManifestValidationError], D],
      fifth: Either[Vector[ManifestValidationError], E],
      sixth: Either[Vector[ManifestValidationError], F]
  ): Either[Vector[ManifestValidationError], (A, B, C, D, E, F)] =
    (first, second, third, fourth, fifth, sixth) match
      case (Right(a), Right(b), Right(c), Right(d), Right(e), Right(f)) => Right((a, b, c, d, e, f))
      case _                                                            => Left(
          leftErrors(first) ++ leftErrors(second) ++ leftErrors(third) ++
            leftErrors(fourth) ++ leftErrors(fifth) ++ leftErrors(sixth)
        )

  private def combine[A, B, C, D, E, F, G](
      first: Either[Vector[ManifestValidationError], A],
      second: Either[Vector[ManifestValidationError], B],
      third: Either[Vector[ManifestValidationError], C],
      fourth: Either[Vector[ManifestValidationError], D],
      fifth: Either[Vector[ManifestValidationError], E],
      sixth: Either[Vector[ManifestValidationError], F],
      seventh: Either[Vector[ManifestValidationError], G]
  ): Either[Vector[ManifestValidationError], (A, B, C, D, E, F, G)] =
    (first, second, third, fourth, fifth, sixth, seventh) match
      case (Right(a), Right(b), Right(c), Right(d), Right(e), Right(f), Right(g)) =>
        Right((a, b, c, d, e, f, g))
      case _ => Left(leftErrors(first) ++ leftErrors(second) ++ leftErrors(third) ++
          leftErrors(fourth) ++ leftErrors(fifth) ++ leftErrors(sixth) ++ leftErrors(seventh))

  private def combine[A, B, C, D, E, F, G, H](
      first: Either[Vector[ManifestValidationError], A],
      second: Either[Vector[ManifestValidationError], B],
      third: Either[Vector[ManifestValidationError], C],
      fourth: Either[Vector[ManifestValidationError], D],
      fifth: Either[Vector[ManifestValidationError], E],
      sixth: Either[Vector[ManifestValidationError], F],
      seventh: Either[Vector[ManifestValidationError], G],
      eighth: Either[Vector[ManifestValidationError], H]
  ): Either[Vector[ManifestValidationError], (A, B, C, D, E, F, G, H)] =
    combine(first, second, third, fourth, fifth, sixth, seventh).flatMap:
      case (a, b, c, d, e, f, g) => eighth match
          case Right(h) => Right((a, b, c, d, e, f, g, h))
          case Left(_)  => Left(leftErrors(eighth))

  private def combine[A, B, C, D, E, F, G, H, I, J, K](
      first: Either[Vector[ManifestValidationError], A],
      second: Either[Vector[ManifestValidationError], B],
      third: Either[Vector[ManifestValidationError], C],
      fourth: Either[Vector[ManifestValidationError], D],
      fifth: Either[Vector[ManifestValidationError], E],
      sixth: Either[Vector[ManifestValidationError], F],
      seventh: Either[Vector[ManifestValidationError], G],
      eighth: Either[Vector[ManifestValidationError], H],
      ninth: Either[Vector[ManifestValidationError], I],
      tenth: Either[Vector[ManifestValidationError], J],
      eleventh: Either[Vector[ManifestValidationError], K]
  ): Either[Vector[ManifestValidationError], (A, B, C, D, E, F, G, H, I, J, K)] =
    (first, second, third, fourth, fifth, sixth, seventh, eighth, ninth, tenth, eleventh) match
      case (
            Right(a),
            Right(b),
            Right(c),
            Right(d),
            Right(e),
            Right(f),
            Right(g),
            Right(h),
            Right(i),
            Right(j),
            Right(k)
          ) => Right((a, b, c, d, e, f, g, h, i, j, k))
      case _ => Left(leftErrors(first) ++ leftErrors(second) ++ leftErrors(third) ++
          leftErrors(fourth) ++ leftErrors(fifth) ++ leftErrors(sixth) ++ leftErrors(seventh) ++
          leftErrors(eighth) ++ leftErrors(ninth) ++ leftErrors(tenth) ++ leftErrors(eleventh))

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
