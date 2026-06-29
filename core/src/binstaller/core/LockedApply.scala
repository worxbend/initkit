package binstaller.core

import java.nio.file.Path

/** Whether apply should require a compatible lock file before rendering or installing. */
enum LockedApplyMode:
  case Enabled, Disabled

/** Helpers for converting CLI flags into locked-apply mode. */
object LockedApplyMode:
  /** Convert a boolean CLI flag into [[LockedApplyMode]]. */
  def fromFlag(value: Boolean): LockedApplyMode = if value then Enabled else Disabled

/** Validated lock metadata visible to dry-run renderers. */
final case class LockedApplyProvenance(path: Path, tools: Map[String, LockFileTool])

/** Expected locked-apply gate failure. */
enum LockedApplyError:
  case LockFile(error: LockFileError)
  case Incompatible(path: Path, message: String)

/** Rendering helpers for locked-apply failures. */
object LockedApplyError:

  /** Render a locked-apply failure into concise user-facing lines. */
  def renderLines(error: LockedApplyError): Vector[String] = error match
    case LockedApplyError.LockFile(lockError)         => Vector(LockFileError.render(lockError))
    case LockedApplyError.Incompatible(path, message) =>
      Vector(s"locked apply refused by $path: $message")

private[core] object LockedApplyValidator:

  def validate(
      prepared: PreparedPlan,
      lockPath: Path,
      lockFileStore: LockFileStore,
      metadataClient: BinaryMetadataClient
  ): Either[LockedApplyError, LockedApplyProvenance] =
    val normalized = lockPath.toAbsolutePath.normalize()
    lockFileStore.load(lockPath) match
      case Left(error)     => Left(LockedApplyError.LockFile(error))
      case Right(lockFile) => validateLoaded(prepared, normalized, lockFile, metadataClient)

  private def validateLoaded(
      prepared: PreparedPlan,
      path: Path,
      lockFile: LockFile,
      metadataClient: BinaryMetadataClient
  ): Either[LockedApplyError, LockedApplyProvenance] =
    firstProblem(prepared, lockFile, metadataClient) match
      case Some(message) => Left(LockedApplyError.Incompatible(path, message))
      case None          => Right(LockedApplyProvenance(
          path,
          lockFile.tools.map(tool =>
            tool.name -> tool
          ).toMap
        ))

  private def firstProblem(
      prepared: PreparedPlan,
      lockFile: LockFile,
      metadataClient: BinaryMetadataClient
  ): Option[String] = schemaProblem(lockFile)
    .orElse(profileProblem(prepared, lockFile))
    .orElse(fingerprintProblem(prepared, lockFile))
    .orElse(duplicateToolProblem(lockFile))
    .orElse(toolProblem(prepared.plan.tools, lockFile, metadataClient))

  private def schemaProblem(lockFile: LockFile): Option[String] =
    Option.when(lockFile.schemaVersion != LockFile.schemaVersion)(
      s"expected schema version ${LockFile.schemaVersion}, found ${lockFile.schemaVersion}"
    )

  private def profileProblem(prepared: PreparedPlan, lockFile: LockFile): Option[String] =
    Option.when(lockFile.profileName != prepared.profileName)(
      s"expected profile '${prepared.profileName}', found '${lockFile.profileName}'"
    )

  private def fingerprintProblem(prepared: PreparedPlan, lockFile: LockFile): Option[String] =
    Option.when(lockFile.manifestFingerprint != prepared.manifestFingerprint)(
      s"manifest fingerprint changed: expected ${prepared.manifestFingerprint}, " +
        s"found ${lockFile.manifestFingerprint}; rerun `binstaller lock --config <file>`"
    )

  private def duplicateToolProblem(lockFile: LockFile): Option[String] =
    val duplicates = lockFile.tools.groupBy(_.name).collect:
      case (name, values) if values.size > 1 => name
    duplicates.toVector.sorted.headOption.map(name => s"duplicate lock entry for tool '$name'")

  private def toolProblem(
      tools: Vector[ResolvedTool],
      lockFile: LockFile,
      metadataClient: BinaryMetadataClient
  ): Option[String] =
    val lockedTools = lockFile.tools.map(tool => tool.name -> tool).toMap
    tools.view.flatMap(tool =>
      lockedTools.get(tool.name) match
        case None             => Some(s"missing lock entry for tool '${tool.name}'")
        case Some(lockedTool) => validateTool(tool, lockedTool, metadataClient)
    ).headOption

  private def validateTool(
      tool: ResolvedTool,
      lockedTool: LockFileTool,
      metadataClient: BinaryMetadataClient
  ): Option[String] = incompleteProvenance(tool, lockedTool)
    .orElse(versionProblem(tool, lockedTool))
    .orElse(checksumProblem(tool, lockedTool))
    .orElse(downloadUrlProblem(tool, lockedTool))
    .orElse(downloadMetadataProblem(tool, lockedTool, metadataClient))

  private def incompleteProvenance(
      tool: ResolvedTool,
      lockedTool: LockFileTool
  ): Option[String] = Option.when(
    lockedTool.downloadProvenance.initialUrl.trim.isEmpty ||
      lockedTool.downloadProvenance.finalUrl.trim.isEmpty
  )(s"tool '${tool.name}' has incomplete download provenance")
    .orElse:
      Option.when(
        lockedTool.dynamicSource && lockedTool.sizeBytes.isEmpty && lockedTool.checksum.isEmpty
      )(
        s"tool '${tool.name}' has incomplete dynamic lock data; " +
          "dynamic sources require size or checksum metadata"
      )

  private def versionProblem(tool: ResolvedTool, lockedTool: LockFileTool): Option[String] =
    tool.version match
      case ResolvedVersion.Concrete(value, provenance) => lockedTool.resolvedVersion match
          case None => Some(s"tool '${tool.name}' is missing locked resolved version '$value'")
          case Some(lockedValue) if lockedValue != value =>
            Some(
              s"tool '${tool.name}' version changed: lock has '$lockedValue', resolved '$value'"
            )
          case Some(_) => provenanceProblem(tool, lockedTool, provenance)
      case ResolvedVersion.DynamicLatestUrl(_) => Option.when(!lockedTool.dynamicSource)(
          s"tool '${tool.name}' lock is not marked as a dynamic source"
        )

  private def provenanceProblem(
      tool: ResolvedTool,
      lockedTool: LockFileTool,
      provenance: Option[UrlProvenance]
  ): Option[String] = (provenance, lockedTool.versionProvenance) match
    case (Some(current), Some(locked)) if current != locked =>
      Some(s"tool '${tool.name}' version provenance changed")
    case (Some(_), None) => Some(s"tool '${tool.name}' is missing locked version provenance")
    case (None, Some(_)) => Some(s"tool '${tool.name}' lock has unexpected version provenance")
    case _               => None

  private def checksumProblem(tool: ResolvedTool, lockedTool: LockFileTool): Option[String] =
    val current = tool.download.checksum.map(lockChecksum)
    (current, lockedTool.checksum) match
      case (Some(expected), Some(actual)) if expected != actual =>
        Some(
          s"tool '${tool.name}' checksum changed: lock has ${render(actual)}, " +
            s"manifest has ${render(expected)}"
        )
      case (Some(expected), None) =>
        Some(s"tool '${tool.name}' is missing locked checksum ${render(expected)}")
      case (None, Some(actual)) =>
        Some(s"tool '${tool.name}' lock has checksum ${render(actual)} but manifest has none")
      case _ => None

  private def downloadUrlProblem(tool: ResolvedTool, lockedTool: LockFileTool): Option[String] =
    Option.when(lockedTool.downloadProvenance.initialUrl != tool.download.url)(
      s"tool '${tool.name}' download URL changed: lock has " +
        s"'${lockedTool.downloadProvenance.initialUrl}', resolved '${tool.download.url}'"
    )

  private def downloadMetadataProblem(
      tool: ResolvedTool,
      lockedTool: LockFileTool,
      metadataClient: BinaryMetadataClient
  ): Option[String] = metadataClient.metadata(tool.download.url) match
    case Left(error) => Some(
        s"tool '${tool.name}' download metadata could not be verified for " +
          s"${tool.download.url}: ${error.message}"
      )
    case Right(metadata) => provenanceDriftProblem(tool, lockedTool, metadata)
        .orElse(sizeDriftProblem(tool, lockedTool, metadata))

  private def provenanceDriftProblem(
      tool: ResolvedTool,
      lockedTool: LockFileTool,
      metadata: BinaryMetadata
  ): Option[String] = Option.when(metadata.provenance != lockedTool.downloadProvenance)(
    s"tool '${tool.name}' download provenance changed: lock final URL " +
      s"'${lockedTool.downloadProvenance.finalUrl}', current final URL " +
      s"'${metadata.provenance.finalUrl}'"
  )

  private def sizeDriftProblem(
      tool: ResolvedTool,
      lockedTool: LockFileTool,
      metadata: BinaryMetadata
  ): Option[String] = (lockedTool.sizeBytes, metadata.sizeBytes) match
    case (Some(expected), Some(actual)) if expected != actual =>
      Some(s"tool '${tool.name}' size changed: lock has $expected bytes, current is $actual bytes")
    case _ => None

  private def lockChecksum(checksum: ResolvedChecksum): LockFileChecksum =
    LockFileChecksum.fromResolved(checksum)

  private def render(checksum: LockFileChecksum): String =
    s"${checksum.algorithm} ${checksum.value}"
