package binstaller.core

import binstaller.config.BinaryDistributionProfile
import binstaller.config.BinaryToolSpec
import binstaller.config.DownloadSpec
import binstaller.config.ExtractMapping
import binstaller.config.PlanEntry
import binstaller.config.PolicyOverride
import binstaller.config.VersionSource

import java.nio.charset.StandardCharsets

private[core] object ManifestFingerprint:

  def profile(profile: BinaryDistributionProfile): String =
    Sha256.digest(canonicalProfile(profile).getBytes(StandardCharsets.UTF_8))

  private def canonicalProfile(profile: BinaryDistributionProfile): String =
    val builder = StringBuilder()
    append(builder, "apiVersion", profile.apiVersion.value)
    append(builder, "kind", profile.kind.value)
    append(builder, "metadata.name", profile.metadata.name)
    appendMap(builder, "metadata.labels", profile.metadata.labels)
    appendMap(builder, "metadata.annotations", profile.metadata.annotations)
    appendPolicy(builder, profile.spec.policy)
    appendMap(builder, "spec.vars", profile.spec.vars)
    appendVersions(builder, profile.spec.versions)
    appendPlan(builder, profile.spec.plan)
    builder.result()

  private def appendPolicy(builder: StringBuilder, policy: binstaller.config.InstallPolicy): Unit =
    append(builder, "spec.policy.mode", policy.mode.value)
    append(builder, "spec.policy.continueOnError", policy.continueOnError.toString)
    append(builder, "spec.policy.appsDir", policy.appsDir)
    append(builder, "spec.policy.cleanInstall", policy.cleanInstall.toString)
    append(builder, "spec.policy.requireConfirmation", policy.requireConfirmation.toString)
    append(builder, "spec.policy.allowSudoSymlinks", policy.allowSudoSymlinks.toString)
    appendOverride(builder, "spec.policy.allowDynamicLatestUrls", policy.allowDynamicLatestUrls)
    appendOverride(builder, "spec.policy.allowMissingChecksums", policy.allowMissingChecksums)
    appendOverride(builder, "spec.policy.allowTarXzFallback", policy.allowTarXzFallback)
    appendOverride(
      builder,
      "spec.policy.allowArchiveCandidateFallback",
      policy.allowArchiveCandidateFallback
    )
    append(builder, "spec.policy.stateFile", policy.stateFile.getOrElse(""))

  private def appendOverride(
      builder: StringBuilder,
      key: String,
      value: Option[PolicyOverride]
  ): Unit =
    val rendered = value match
      case Some(PolicyOverride.Enabled)  => "true"
      case Some(PolicyOverride.Disabled) => "false"
      case None                          => ""
    append(builder, key, rendered)

  private def appendVersions(
      builder: StringBuilder,
      versions: Map[String, VersionSource]
  ): Unit = versions.toVector.sortBy(_._1).foreach:
    case (name, source) => source match
        case VersionSource.Pinned(value)       => append(builder, s"spec.versions.$name", value)
        case VersionSource.Dynamic(kind, note) =>
          append(builder, s"spec.versions.$name.dynamic.type", kind.value)
          append(builder, s"spec.versions.$name.dynamic.note", note.getOrElse(""))
        case VersionSource.Resolver(kind, url) =>
          append(builder, s"spec.versions.$name.resolver.type", kind.value)
          append(builder, s"spec.versions.$name.resolver.url", url)

  private def appendPlan(builder: StringBuilder, plan: Vector[PlanEntry]): Unit =
    plan.zipWithIndex.foreach:
      case (entry, index) =>
        val base = s"spec.plan[$index]"
        append(builder, s"$base.name", entry.name)
        append(builder, s"$base.kind", entry.kind.value)
        append(builder, s"$base.description", entry.description.getOrElse(""))
        append(
          builder,
          s"$base.when.os.family",
          entry.when.flatMap(_.os).flatMap(_.family).getOrElse("")
        )
        append(
          builder,
          s"$base.when.architecture",
          entry.when.flatMap(_.architecture).getOrElse("")
        )
        appendToolSpec(builder, s"$base.spec", entry.spec)

  private def appendToolSpec(
      builder: StringBuilder,
      base: String,
      spec: BinaryToolSpec
  ): Unit =
    append(builder, s"$base.versionRef", spec.versionRef)
    append(builder, s"$base.installDir", spec.installDir)
    appendVector(builder, s"$base.createDirectories", spec.createDirectories)
    appendDownload(builder, s"$base.download", spec.download)
    appendExecutables(builder, s"$base.executables", spec.executables)
    appendSymlinks(builder, s"$base.symlinks", spec.symlinks)

  private def appendDownload(builder: StringBuilder, base: String, download: DownloadSpec): Unit =
    append(builder, s"$base.url", download.url)
    append(builder, s"$base.filename", download.filename)
    download.checksum.foreach: checksum =>
      append(builder, s"$base.checksum.algorithm", checksum.algorithm.value)
      checksum.value.foreach(value => append(builder, s"$base.checksum.value", value))
      checksum.discover.foreach: discovery =>
        append(builder, s"$base.checksum.discover.type", discovery.kind.value)
        append(builder, s"$base.checksum.discover.url", discovery.url)
        append(builder, s"$base.checksum.discover.file", discovery.file.getOrElse(""))
    download.archive.foreach: archive =>
      append(builder, s"$base.archive.type", archive.archiveType.value)
      appendMappings(builder, s"$base.archive.extract.files", archive.extract.files)
      appendMappings(builder, s"$base.archive.extract.directories", archive.extract.directories)

  private def appendExecutables(
      builder: StringBuilder,
      base: String,
      executables: Vector[binstaller.config.ExecutableSpec]
  ): Unit = executables.zipWithIndex.foreach:
    case (executable, index) =>
      append(builder, s"$base[$index].path", executable.path)
      append(builder, s"$base[$index].mode", executable.mode.map(_.value).getOrElse(""))

  private def appendSymlinks(
      builder: StringBuilder,
      base: String,
      symlinks: Vector[binstaller.config.SymlinkSpec]
  ): Unit = symlinks.zipWithIndex.foreach:
    case (symlink, index) =>
      append(builder, s"$base[$index].path", symlink.path)
      append(builder, s"$base[$index].target", symlink.target)
      append(builder, s"$base[$index].privilege", symlink.privilege.toString)

  private def appendMappings(
      builder: StringBuilder,
      base: String,
      mappings: Vector[ExtractMapping]
  ): Unit = mappings.zipWithIndex.foreach:
    case (mapping, index) =>
      append(builder, s"$base[$index].from", mapping.from)
      append(builder, s"$base[$index].to", mapping.to)

  private def appendMap(builder: StringBuilder, base: String, values: Map[String, String]): Unit =
    values.toVector.sortBy(_._1).foreach:
      case (name, value) => append(builder, s"$base.$name", value)

  private def appendVector(builder: StringBuilder, base: String, values: Vector[String]): Unit =
    values.zipWithIndex.foreach:
      case (value, index) => append(builder, s"$base[$index]", value)

  private def append(builder: StringBuilder, key: String, value: String): Unit =
    val _ = builder.append(key.length).append(':').append(key).append('=')
    val _ = builder.append(value.length).append(':').append(value).append('\n')
