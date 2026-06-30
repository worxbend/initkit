package binstaller.core

import binstaller.config.SymlinkPrivilege

private[core] object PlanRenderer:

  def render(
      plan: ResolvedPlan,
      lockedProvenance: Option[LockedApplyProvenance] = None
  ): InstallerResult = InstallerResult(
    RenderSafety.displayLines(
      header(plan, lockedProvenance) ++
        plan.tools.zipWithIndex.flatMap(renderTool(_, lockedProvenance)),
      plan.redactions
    ),
    0
  )

  private def header(
      plan: ResolvedPlan,
      lockedProvenance: Option[LockedApplyProvenance]
  ): Vector[String] =
    val sudoSymlinkCount =
      plan.tools.flatMap(_.symlinks).count(_.privilege == SymlinkPrivilege.Sudo)
    val stateLine = plan.policy.stateFile match
      case Some(path) => s"state file: $path (not created)"
      case None       => "state file: not configured"
    val lockLine = lockedProvenance match
      case Some(value) => s"lock file: ${value.path} (validated)"
      case None        => "lock file: not required"
    val sudoLine =
      if sudoSymlinkCount == 0 then "sudo risk: none"
      else
        s"sudo risk: YES - $sudoSymlinkCount sudo symlink command(s) require elevated privileges"
    Vector(
      "binstaller plan",
      s"tools: ${plan.tools.size}",
      s"apps dir: ${plan.policy.appsDir} (not created)",
      s"policy mode: ${plan.policy.mode.value}",
      stateLine,
      lockLine,
      "filesystem: no changes will be made",
      sudoLine
    )

  private def renderTool(
      indexedTool: (ResolvedTool, Int),
      lockedProvenance: Option[LockedApplyProvenance]
  ): Vector[String] =
    val (tool, index) = indexedTool
    Vector(
      "",
      s"${index + 1}. ${tool.name}",
      s"   destination: ${tool.installDir}",
      s"   version: ${renderVersion(tool.version)}",
      s"   download: ${tool.download.url}",
      s"   download file: ${joinPath(tool.installDir, tool.download.filename)}",
      s"   checksum: ${renderChecksum(tool.download.checksum)}"
    ) ++ renderLockedProvenance(tool, lockedProvenance) ++
      renderCreateDirectories(tool) ++ renderStrategy(tool) ++ renderExecutables(tool) ++
      renderSymlinks(tool)

  private def renderLockedProvenance(
      tool: ResolvedTool,
      lockedProvenance: Option[LockedApplyProvenance]
  ): Vector[String] = lockedProvenance.flatMap(_.tools.get(tool.name)) match
    case Some(lockedTool) =>
      val size = lockedTool.sizeBytes.map(value => s"$value bytes").getOrElse("unknown")
      Vector(
        s"   locked download final url: ${lockedTool.downloadProvenance.finalUrl}",
        s"   locked download size: $size"
      ) ++ lockedTool.versionProvenance.map(provenance =>
        s"   locked version final url: ${provenance.finalUrl}"
      ).toVector
    case None => Vector.empty

  private def renderVersion(version: ResolvedVersion): String = version match
    case ResolvedVersion.Concrete(value, provenance) =>
      s"concrete $value${UrlProvenance.redirectSuffix(provenance)}"
    case ResolvedVersion.DynamicLatestUrl(_) => "dynamic latest-url"

  private def renderChecksum(checksum: Option[ResolvedChecksum]): String = checksum match
    case Some(value) => s"${value.algorithm.value} ${value.value} (${checksumStatus(value)})"
    case None        => "missing (not configured)"

  private def checksumStatus(checksum: ResolvedChecksum): String = checksum.source match
    case ResolvedChecksumSource.Configured                        => "configured"
    case ResolvedChecksumSource.Discovered(url, file, provenance) =>
      s"discovered from $url for $file" + UrlProvenance.redirectSuffix(Some(provenance))

  private def renderCreateDirectories(tool: ResolvedTool): Vector[String] =
    if tool.createDirectories.isEmpty then Vector.empty
    else
      Vector("   create directories:") ++
        tool.createDirectories.map(path => s"     ${joinPath(tool.installDir, path)}")

  private def renderStrategy(tool: ResolvedTool): Vector[String] =
    val archiveLines = tool.download.archive match
      case Some(archive) => renderArchive(archive)
      case None          => Vector("   archive: none")
    val directLine =
      if tool.download.archive.isEmpty then
        Vector("   strategy: direct binary download")
      else Vector.empty

    directLine ++ archiveLines

  private def renderArchive(archive: ResolvedArchive): Vector[String] =
    Vector(s"   archive: ${archive.original.archiveType.value}") ++
      archive.files.map(mapping => s"     file ${mapping.from} -> ${mapping.to}") ++
      archive.directories.map(mapping => s"     directory ${mapping.from} -> ${mapping.to}")

  private def renderExecutables(tool: ResolvedTool): Vector[String] =
    if tool.executables.isEmpty then Vector("   executables: none")
    else
      Vector("   executables:") ++ tool.executables.map: executable =>
        val mode = executable.mode.map(value => s" mode ${value.value}").getOrElse("")
        s"     ${joinPath(tool.installDir, executable.path)}$mode"

  private def renderSymlinks(tool: ResolvedTool): Vector[String] =
    if tool.symlinks.isEmpty then Vector("   symlinks: none")
    else Vector("   symlinks:") ++ tool.symlinks.map(renderSymlinkCommand(tool, _))

  private def renderSymlinkCommand(tool: ResolvedTool, symlink: ResolvedSymlink): String =
    val destination = absoluteOrInstallPath(tool.installDir, symlink.path)
    val target      = absoluteOrInstallPath(tool.installDir, symlink.target)
    val command     = symlink.privilege match
      case SymlinkPrivilege.User => s"ln -sfn ${shellQuote(target)} ${shellQuote(destination)}"
      case SymlinkPrivilege.Sudo => s"sudo ln -sfn ${shellQuote(target)} ${shellQuote(destination)}"
    val risk = symlink.privilege match
      case SymlinkPrivilege.User => "local"
      case SymlinkPrivilege.Sudo => "sudo risk"
    s"     [$risk] $command"

  private def absoluteOrInstallPath(installDir: String, path: String): String =
    if path.startsWith("/") then path else joinPath(installDir, path)

  private def joinPath(parent: String, child: String): String =
    if parent.endsWith("/") then s"$parent$child" else s"$parent/$child"

  private def shellQuote(value: String): String = s"'${value.replace("'", "'\"'\"'")}'"
