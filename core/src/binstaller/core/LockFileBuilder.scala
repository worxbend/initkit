package binstaller.core

private[core] object LockFileBuilder:

  def build(
      prepared: PreparedPlan,
      metadataClient: BinaryMetadataClient
  ): LockFile = LockFile(
    LockFile.schemaVersion,
    prepared.profileName,
    prepared.manifestFingerprint,
    prepared.plan.tools.map(tool => toolEntry(tool, metadataClient))
  )

  private def toolEntry(
      tool: ResolvedTool,
      metadataClient: BinaryMetadataClient
  ): LockFileTool =
    val metadata = metadataClient
      .metadata(tool.download.url)
      .getOrElse(BinaryMetadata(None, UrlProvenance.direct(tool.download.url)))
    val (resolvedVersion, versionProvenance, dynamicSource) = versionFields(tool.version)
    LockFileTool(
      name = tool.name,
      resolvedVersion = resolvedVersion,
      versionProvenance = versionProvenance,
      downloadProvenance = metadata.provenance,
      sizeBytes = metadata.sizeBytes,
      checksum = tool.download.checksum.map(LockFileChecksum.fromResolved),
      dynamicSource = dynamicSource
    )

  private def versionFields(
      version: ResolvedVersion
  ): (Option[String], Option[UrlProvenance], Boolean) = version match
    case ResolvedVersion.Concrete(value, provenance) => (Some(value), provenance, false)
    case ResolvedVersion.DynamicLatestUrl(_)         => (None, None, true)
