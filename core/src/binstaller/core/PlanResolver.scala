package binstaller.core

import binstaller.config.ArchiveSpec
import binstaller.config.BinaryDistributionProfile
import binstaller.config.BinaryToolSpec
import binstaller.config.ChecksumDiscoveryKind
import binstaller.config.ChecksumDiscoverySpec
import binstaller.config.ChecksumSpec
import binstaller.config.DownloadSpec
import binstaller.config.DynamicVersionKind
import binstaller.config.ExtractMapping
import binstaller.config.PlanEntry
import binstaller.config.SymlinkPrivilege
import binstaller.config.ValidationError
import binstaller.config.VersionResolverKind
import binstaller.config.VersionSource

import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.matching.Regex

/** Resolver for converting typed manifests into executable plans. */
object PlanResolver:

  /** Resolve variables, versions, URLs, install directories, and selected archive mappings. */
  def resolve(
      profile: BinaryDistributionProfile,
      options: ResolutionOptions,
      httpTextClient: HttpTextClient
  ): Either[ResolvePlanError.ValidationFailed, ResolvedPlan] =
    val resolved = ResolutionBuilder(profile, options, httpTextClient).resolve()
    if resolved.errors.isEmpty then Right(resolved.value)
    else Left(ResolvePlanError.ValidationFailed(resolved.errors))

private final case class ResolvedValue[+A](value: A, errors: Vector[ValidationError]):
  def map[B](f: A => B): ResolvedValue[B] = ResolvedValue(f(value), errors)

private object ResolvedValue:
  def valid[A](value: A): ResolvedValue[A] = ResolvedValue(value, Vector.empty)

  def invalid[A](value: A, path: String, message: String): ResolvedValue[A] =
    ResolvedValue(value, Vector(ValidationError(path, message)))

private final class ResolutionBuilder(
    profile: BinaryDistributionProfile,
    options: ResolutionOptions,
    httpTextClient: HttpTextClient
):

  def resolve(): ResolvedValue[ResolvedPlan] =
    val manifestVars = resolveManifestVars()
    val policy       = resolvePolicy(options.runtimeVariables ++ manifestVars.value)
    val baseVars     = options.runtimeVariables ++ manifestVars.value +
      ("appsDir" -> policy.value.appsDir)
    val versions               = resolveVersions(baseVars)
    val tools                  = resolveTools(baseVars, versions.value)
    val installDirectoryErrors = validateInstallDirectories(policy.value, tools.value)
    val strictPolicyErrors     = StrictPolicyValidator.validate(profile, policy.value, tools.value)

    ResolvedValue(
      ResolvedPlan(policy.value, tools.value, options.redactions),
      manifestVars.errors ++ policy.errors ++ versions.errors ++ tools.errors
        ++ installDirectoryErrors ++ strictPolicyErrors
    )

  private def resolveManifestVars(): ResolvedValue[Map[String, String]] =
    val rawVars  = profile.spec.vars
    val resolved = rawVars.toVector.map:
      case (name, value) =>
        val path = s"spec.vars.$name"
        name -> interpolate(value, path, options.runtimeVariables ++ rawVars)
    ResolvedValue(
      resolved.map((name, value) => name -> value.value).toMap,
      resolved.flatMap((_, value) => value.errors)
    )

  private def resolvePolicy(vars: Map[String, String]): ResolvedValue[ResolvedPolicy] =
    val appsDir   = interpolate(profile.spec.policy.appsDir, "spec.policy.appsDir", vars)
    val stateFile = profile.spec.policy.stateFile match
      case Some(value) => interpolate(value, "spec.policy.stateFile", vars).map(Some(_))
      case None        => ResolvedValue.valid(None)
    val stateFilePathErrors = stateFile.value.toVector.flatMap: value =>
      ResolvedPathValidator.stateFile(value, "spec.policy.stateFile")

    ResolvedValue(
      ResolvedPolicy(
        appsDir.value,
        stateFile.value,
        profile.spec.policy.allowSudoSymlinks,
        RequireConfirmation.fromBoolean(profile.spec.policy.requireConfirmation),
        ContinueOnError.fromBoolean(profile.spec.policy.continueOnError),
        profile.spec.policy.mode,
        ManifestPolicy.allowance(
          profile.spec.policy.mode,
          profile.spec.policy.allowDynamicLatestUrls
        ),
        ManifestPolicy.allowance(
          profile.spec.policy.mode,
          profile.spec.policy.allowMissingChecksums
        ),
        ManifestPolicy.allowance(
          profile.spec.policy.mode,
          profile.spec.policy.allowTarXzFallback
        ),
        ManifestPolicy.allowance(
          profile.spec.policy.mode,
          profile.spec.policy.allowArchiveCandidateFallback
        )
      ),
      appsDir.errors ++ stateFile.errors ++ stateFilePathErrors
    )

  private def resolveVersions(
      vars: Map[String, String]
  ): ResolvedValue[Map[String, ResolvedVersion]] =
    val resolved = profile.spec.versions.toVector.map:
      case (name, source) => name -> resolveVersionSource(name, source, vars)
    ResolvedValue(
      resolved.map((name, value) => name -> value.value).toMap,
      resolved.flatMap((_, value) => value.errors)
    )

  private def resolveVersionSource(
      name: String,
      source: VersionSource,
      vars: Map[String, String]
  ): ResolvedValue[ResolvedVersion] = source match
    case VersionSource.Pinned(value) =>
      val path     = s"spec.versions.$name"
      val resolved = interpolate(value, path, vars)
      val errors   = resolved.errors ++ missingConcreteVersionErrors(resolved.value, path, name)
      ResolvedValue(ResolvedVersion.Concrete(resolved.value, None), errors)
    case VersionSource.Dynamic(DynamicVersionKind.LatestUrl, note) =>
      ResolvedValue.valid(ResolvedVersion.DynamicLatestUrl(note))
    case VersionSource.Resolver(VersionResolverKind.HttpText, url) =>
      resolveHttpTextVersion(name, url, vars)

  private def resolveHttpTextVersion(
      name: String,
      url: String,
      vars: Map[String, String]
  ): ResolvedValue[ResolvedVersion] =
    val path        = s"spec.versions.$name.resolver.url"
    val resolvedUrl = interpolate(url, path, vars)
    val fetched     =
      if resolvedUrl.errors.nonEmpty then
        ResolvedValue.valid(HttpTextResponse("", UrlProvenance.direct(resolvedUrl.value)))
      else if httpsUrlErrors(resolvedUrl.value, path).nonEmpty then
        ResolvedValue.valid(HttpTextResponse("", UrlProvenance.direct(resolvedUrl.value)))
      else
        httpTextClient.getTextWithProvenance(resolvedUrl.value) match
          case Right(response) => ResolvedValue.valid(response.copy(text = response.text.trim))
          case Left(error)     => ResolvedValue.invalid(
              HttpTextResponse(
                "",
                error.provenance.getOrElse(UrlProvenance.direct(resolvedUrl.value))
              ),
              path,
              s"http-text resolver failed: ${error.message}"
            )

    ResolvedValue(
      ResolvedVersion.Concrete(fetched.value.text, Some(fetched.value.provenance)),
      resolvedUrl.errors ++ httpsUrlErrors(resolvedUrl.value, path) ++ fetched.errors ++
        missingConcreteVersionErrors(fetched.value.text, path, name)
    )

  private def missingConcreteVersionErrors(
      value: String,
      path: String,
      name: String
  ): Vector[ValidationError] =
    if value.nonEmpty then Vector.empty
    else Vector(ValidationError(path, s"version '$name' did not resolve to a concrete value"))

  private def resolveTools(
      baseVars: Map[String, String],
      versions: Map[String, ResolvedVersion]
  ): ResolvedValue[Vector[ResolvedTool]] =
    val resolved = profile.spec.plan.zipWithIndex.map:
      case (entry, index) => resolveTool(entry, index, baseVars, versions)
    ResolvedValue(
      resolved.map(_.value),
      resolved.flatMap(_.errors)
    )

  private def resolveTool(
      entry: PlanEntry,
      index: Int,
      baseVars: Map[String, String],
      versions: Map[String, ResolvedVersion]
  ): ResolvedValue[ResolvedTool] =
    val spec        = entry.spec
    val version     = versions.getOrElse(spec.versionRef, ResolvedVersion.Concrete(""))
    val versionVars = concreteVersionVars(version)
    val vars        = baseVars ++ versionVars + ("tool" -> entry.name)
    val specPath    = s"spec.plan[$index].spec"
    val installDir  = interpolate(spec.installDir, s"$specPath.installDir", vars)
    val filename    = interpolate(spec.download.filename, s"$specPath.download.filename", vars)
    val localVars   = vars + ("installDir"              -> installDir.value)
    val download    = resolveDownload(spec.download, specPath, localVars, version)
    val createDirectories = resolveStringVector(
      spec.createDirectories,
      s"$specPath.createDirectories",
      localVars,
      version
    )
    val executables = resolveExecutables(spec, specPath, localVars, version)
    val symlinks    = resolveSymlinks(spec, specPath, localVars, version, installDir.value)

    ResolvedValue(
      ResolvedTool(
        name = entry.name,
        description = entry.description,
        version = version,
        installDir = installDir.value,
        createDirectories = createDirectories.value,
        download = download.value,
        executables = executables.value,
        symlinks = symlinks.value
      ),
      installDir.errors ++
        versionTemplateErrors(spec.installDir, s"$specPath.installDir", version) ++
        filename.errors ++
        versionTemplateErrors(spec.download.filename, s"$specPath.download.filename", version) ++
        createDirectories.errors ++ download.errors ++ executables.errors ++
        symlinks.errors
    )

  private def concreteVersionVars(version: ResolvedVersion): Map[String, String] = version match
    case ResolvedVersion.Concrete(value, _) if value.nonEmpty => Map("version" -> value)
    case _                                                    => Map.empty

  private def resolveDownload(
      download: DownloadSpec,
      specPath: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[ResolvedDownload] =
    val path     = s"$specPath.download"
    val url      = interpolate(download.url, s"$path.url", vars)
    val filename = interpolate(download.filename, s"$path.filename", vars)
    val archive  = resolveArchive(download.archive, path, vars, version)
    val checksum = resolveChecksum(download.checksum, path, vars, version, filename.value)

    ResolvedValue(
      ResolvedDownload(url.value, filename.value, checksum.value, archive.value),
      url.errors ++ versionTemplateErrors(download.url, s"$path.url", version) ++
        httpsUrlErrors(url.value, s"$path.url") ++
        filename.errors ++
        versionTemplateErrors(download.filename, s"$path.filename", version) ++
        ResolvedPathValidator.downloadFilename(filename.value, s"$path.filename") ++
        checksum.errors ++
        archive.errors
    )

  private def resolveChecksum(
      checksum: Option[ChecksumSpec],
      downloadPath: String,
      vars: Map[String, String],
      version: ResolvedVersion,
      resolvedFilename: String
  ): ResolvedValue[Option[ResolvedChecksum]] = checksum match
    case None        => ResolvedValue.valid(None)
    case Some(value) => value.value match
        case Some(literal) => ResolvedValue.valid(Some(ResolvedChecksum(
            value.algorithm,
            literal,
            ResolvedChecksumSource.Configured
          )))
        case None => value.discover match
            case Some(discovery) => resolveDiscoveredChecksum(
                value,
                discovery,
                downloadPath,
                vars,
                version,
                resolvedFilename
              )
            case None => ResolvedValue.valid(None)

  private def resolveDiscoveredChecksum(
      checksum: ChecksumSpec,
      discovery: ChecksumDiscoverySpec,
      downloadPath: String,
      vars: Map[String, String],
      version: ResolvedVersion,
      resolvedFilename: String
  ): ResolvedValue[Option[ResolvedChecksum]] =
    val sourcePath = s"$downloadPath.checksum.discover"
    val url        = interpolate(discovery.url, s"$sourcePath.url", vars)
    val file       = discovery.file match
      case Some(value) => interpolate(value, s"$sourcePath.file", vars)
      case None        => ResolvedValue.valid(resolvedFilename)
    val fileErrors =
      if file.value.trim.nonEmpty then Vector.empty
      else Vector(ValidationError(s"$sourcePath.file", "checksum discovery file must not be empty"))
    val requestErrors = url.errors ++
      versionTemplateErrors(discovery.url, s"$sourcePath.url", version) ++
      httpsUrlErrors(url.value, s"$sourcePath.url") ++
      file.errors ++
      discovery.file.toVector.flatMap(value =>
        versionTemplateErrors(value, s"$sourcePath.file", version)
      ) ++ fileErrors

    if requestErrors.nonEmpty then ResolvedValue(None, requestErrors)
    else
      val fetched = httpTextClient.getTextWithProvenance(url.value) match
        case Right(response) => parseChecksumFile(
            response,
            checksum,
            discovery,
            file.value,
            sourcePath
          )
        case Left(error) => ResolvedValue.invalid(
            None,
            s"$sourcePath.url",
            s"checksum discovery failed: ${error.message}"
          )
      ResolvedValue(fetched.value, requestErrors ++ fetched.errors)

  private def parseChecksumFile(
      response: HttpTextResponse,
      checksum: ChecksumSpec,
      discovery: ChecksumDiscoverySpec,
      file: String,
      sourcePath: String
  ): ResolvedValue[Option[ResolvedChecksum]] = discovery.kind match
    case ChecksumDiscoveryKind.Sha256Sum => Sha256SumChecksumFile.find(response.text, file) match
        case Some(value) => ResolvedValue.valid(Some(ResolvedChecksum(
            checksum.algorithm,
            value,
            ResolvedChecksumSource.Discovered(
              response.provenance.initialUrl,
              file,
              response.provenance
            )
          )))
        case None => ResolvedValue.invalid(
            None,
            sourcePath,
            s"checksum discovery missing sha256sum entry for '$file'"
          )

  private def resolveArchive(
      archive: Option[ArchiveSpec],
      downloadPath: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[Option[ResolvedArchive]] = archive match
    case None        => ResolvedValue.valid(None)
    case Some(value) =>
      val files = resolveExtractMappings(
        value.extract.files,
        s"$downloadPath.archive.extract.files",
        vars,
        version
      )
      val directories = resolveExtractMappings(
        value.extract.directories,
        s"$downloadPath.archive.extract.directories",
        vars,
        version
      )
      ResolvedValue(
        Some(ResolvedArchive(value, files.value, directories.value)),
        files.errors ++ directories.errors
      )

  private def resolveExtractMappings(
      mappings: Vector[ExtractMapping],
      path: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[Vector[ResolvedExtractMapping]] =
    val resolved = mappings.zipWithIndex.map:
      case (mapping, index) =>
        val fromPath = s"$path[$index].from"
        val toPath   = s"$path[$index].to"
        val from     = interpolate(mapping.from, fromPath, vars)
        val to       = interpolate(mapping.to, toPath, vars)
        ResolvedValue(
          ResolvedExtractMapping(from.value, to.value),
          from.errors ++ versionTemplateErrors(mapping.from, fromPath, version) ++
            ResolvedPathValidator.archivePath(from.value, fromPath, "archive source") ++
            to.errors ++ versionTemplateErrors(mapping.to, toPath, version) ++
            ResolvedPathValidator.archivePath(to.value, toPath, "archive target")
        )
    ResolvedValue(resolved.map(_.value), resolved.flatMap(_.errors))

  private def resolveExecutables(
      spec: BinaryToolSpec,
      specPath: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[Vector[ResolvedExecutable]] =
    val resolved = spec.executables.zipWithIndex.map:
      case (executable, index) =>
        val path  = s"$specPath.executables[$index].path"
        val value = interpolate(executable.path, path, vars)
        ResolvedValue(
          ResolvedExecutable(value.value, executable.mode),
          value.errors ++ versionTemplateErrors(executable.path, path, version) ++
            ResolvedPathValidator.installRelativePath(value.value, path, "executable path")
        )
    ResolvedValue(resolved.map(_.value), resolved.flatMap(_.errors))

  private def resolveSymlinks(
      spec: BinaryToolSpec,
      specPath: String,
      vars: Map[String, String],
      version: ResolvedVersion,
      installDir: String
  ): ResolvedValue[Vector[ResolvedSymlink]] =
    val resolved = spec.symlinks.zipWithIndex.map:
      case (symlink, index) =>
        val pathPath   = s"$specPath.symlinks[$index].path"
        val targetPath = s"$specPath.symlinks[$index].target"
        val path       = interpolate(symlink.path, pathPath, vars)
        val target     = interpolate(symlink.target, targetPath, vars)
        val pathErrors = symlink.privilege match
          case SymlinkPrivilege.User =>
            ResolvedPathValidator.installRelativePath(path.value, pathPath, "local symlink path")
          case SymlinkPrivilege.Sudo =>
            ResolvedPathValidator.externalPath(path.value, pathPath, "sudo symlink path")
        ResolvedValue(
          ResolvedSymlink(path.value, target.value, symlink.privilege),
          path.errors ++ versionTemplateErrors(symlink.path, pathPath, version) ++
            pathErrors ++
            target.errors ++ versionTemplateErrors(symlink.target, targetPath, version) ++
            ResolvedPathValidator.symlinkTarget(target.value, targetPath, installDir)
        )
    ResolvedValue(resolved.map(_.value), resolved.flatMap(_.errors))

  private def resolveStringVector(
      values: Vector[String],
      path: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[Vector[String]] =
    val resolved = values.zipWithIndex.map:
      case (value, index) =>
        val itemPath = s"$path[$index]"
        val item     = interpolate(value, itemPath, vars)
        ResolvedValue(
          item.value,
          item.errors ++ versionTemplateErrors(value, itemPath, version) ++
            ResolvedPathValidator.installRelativePath(
              item.value,
              itemPath,
              "create directory path"
            )
        )
    ResolvedValue(resolved.map(_.value), resolved.flatMap(_.errors))

  private def versionTemplateErrors(
      value: String,
      path: String,
      version: ResolvedVersion
  ): Vector[ValidationError] = version match
    case ResolvedVersion.Concrete(value, _) if value.nonEmpty => Vector.empty
    case _ => TemplateInterpolator.variableNames(value).collect:
        case "version" => ValidationError(
            path,
            "template references ${version}, but no concrete version is available"
          )

  private def interpolate(
      value: String,
      path: String,
      vars: Map[String, String]
  ): ResolvedValue[String] = TemplateInterpolator.interpolate(value, path, vars)

  private def validateInstallDirectories(
      policy: ResolvedPolicy,
      tools: Vector[ResolvedTool]
  ): Vector[ValidationError] =
    val appsDirPath         = "spec.policy.appsDir"
    val appsDirSyntaxErrors =
      ResolvedPathValidator.pathSyntax(policy.appsDir, appsDirPath, "appsDir")
    if appsDirSyntaxErrors.nonEmpty then appsDirSyntaxErrors
    else
      Try(Path.of(policy.appsDir).toAbsolutePath.normalize()) match
        case Failure(error) =>
          Vector(ValidationError(appsDirPath, s"invalid appsDir: ${error.getMessage}"))
        case Success(appsDir) =>
          // Install roots must stay under appsDir and must not nest inside another tool. This keeps a
          // bad manifest from replacing the apps root or another tool's install directory.
          val containmentErrors = tools.zipWithIndex.flatMap:
            case (tool, index) =>
              val path         = s"spec.plan[$index].spec.installDir"
              val syntaxErrors =
                ResolvedPathValidator.pathSyntax(tool.installDir, path, "installDir")
              if syntaxErrors.nonEmpty then syntaxErrors
              else
                Try(Path.of(tool.installDir).toAbsolutePath.normalize()) match
                  case Failure(error) =>
                    Vector(ValidationError(path, s"invalid installDir: ${error.getMessage}"))
                  case Success(installDir) if installDir == appsDir =>
                    Vector(ValidationError(
                      path,
                      "installDir must be a child of appsDir, not appsDir itself"
                    ))
                  case Success(installDir) if !installDir.startsWith(appsDir) =>
                    Vector(ValidationError(
                      path,
                      "installDir must resolve inside spec.policy.appsDir"
                    ))
                  case Success(_) => Vector.empty

          containmentErrors ++ nestedInstallDirectoryErrors(tools)

  private def nestedInstallDirectoryErrors(tools: Vector[ResolvedTool]): Vector[ValidationError] =
    val indexed = tools.zipWithIndex.flatMap:
      case (tool, index) => Try(Path.of(tool.installDir).toAbsolutePath.normalize()).toOption.map:
          path => (tool, index, path)

    indexed.flatMap:
      case (tool, index, installDir) => indexed.collect:
          case (otherTool, _, otherInstallDir)
              if tool.name != otherTool.name && installDir.startsWith(otherInstallDir) =>
            ValidationError(
              s"spec.plan[$index].spec.installDir",
              s"installDir must not be nested inside tool '${otherTool.name}' installDir"
            )

  private def httpsUrlErrors(value: String, path: String): Vector[ValidationError] =
    RuntimeUrl.httpsUri(value) match
      case Right(_)      => Vector.empty
      case Left(message) => Vector(ValidationError(path, message))

private[core] object Sha256SumChecksumFile:

  private val HashPattern: Regex = "(?i)^[0-9a-f]{64}$".r

  def find(content: String, file: String): Option[String] = content.linesIterator
    .flatMap(parseLine)
    .find((_, candidateFile) => candidateFile == file || fileName(candidateFile) == file)
    .map((checksum, _) => checksum.toLowerCase(java.util.Locale.ROOT))

  private def parseLine(line: String): Option[(String, String)] =
    val trimmed = line.trim
    if trimmed.isEmpty || trimmed.startsWith("#") then None
    else
      val parts = trimmed.split("\\s+", 2).toVector
      parts match
        case Vector(hash, path) if HashPattern.pattern.matcher(hash).matches() =>
          Some(hash -> path.stripPrefix("*").trim)
        case _ => None

  private def fileName(path: String): String =
    path.split('/').toVector.filter(_.nonEmpty).lastOption.getOrElse(path)

private object ResolvedPathValidator:

  def stateFile(value: String, path: String): Vector[ValidationError] =
    filename(value, path, "state filename")

  def downloadFilename(value: String, path: String): Vector[ValidationError] =
    filename(value, path, "download filename")

  def archivePath(value: String, path: String, label: String): Vector[ValidationError] =
    relativePath(value, path, label, allowCurrentDirectory = true)

  def installRelativePath(value: String, path: String, label: String): Vector[ValidationError] =
    relativePath(value, path, label, allowCurrentDirectory = false)

  def externalPath(value: String, path: String, label: String): Vector[ValidationError] =
    pathSyntax(value, path, label)

  def symlinkTarget(
      value: String,
      path: String,
      installDir: String
  ): Vector[ValidationError] =
    val syntaxErrors = pathSyntax(value, path, "symlink target")
    if syntaxErrors.nonEmpty then syntaxErrors
    else
      Try:
        val installRoot = Path.of(installDir).toAbsolutePath.normalize()
        val rawTarget   = Path.of(value)
        val target      =
          if rawTarget.isAbsolute then rawTarget.toAbsolutePath.normalize()
          else installRoot.resolve(rawTarget).normalize()
        installRoot -> target
      match
        case Failure(error) =>
          Vector(ValidationError(path, s"invalid symlink target: ${error.getMessage}"))
        case Success((installRoot, target)) if !target.startsWith(installRoot) =>
          Vector(ValidationError(path, "symlink target must resolve inside installDir"))
        case Success(_) => Vector.empty

  def pathSyntax(value: String, path: String, label: String): Vector[ValidationError] =
    if value.trim.isEmpty then Vector(ValidationError(path, s"$label must not be empty"))
    else if value.exists(Character.isISOControl) then
      Vector(ValidationError(path, s"$label must not contain control characters"))
    else if value.contains('\\') then
      Vector(ValidationError(path, s"$label must not contain backslashes"))
    else if value.matches("^[A-Za-z]:.*") then
      Vector(ValidationError(path, s"$label must not be drive-prefixed"))
    else if hasTraversalSegment(value) then
      Vector(ValidationError(path, s"$label must not contain traversal segments"))
    else Vector.empty

  private def filename(value: String, path: String, label: String): Vector[ValidationError] =
    val syntaxErrors = pathSyntax(value, path, label)
    if syntaxErrors.nonEmpty then syntaxErrors
    else if value.contains('/') then
      Vector(ValidationError(path, s"$label must be a filename, not a path"))
    else if value == "." || value == ".." then
      Vector(ValidationError(path, s"$label must not be a traversal segment"))
    else
      Try(Path.of(value)) match
        case Failure(error) => Vector(ValidationError(path, s"invalid $label: ${error.getMessage}"))
        case Success(file) if file.isAbsolute || file.getNameCount != 1 =>
          Vector(ValidationError(path, s"$label must be a filename in the current directory"))
        case Success(_) => Vector.empty

  private def relativePath(
      value: String,
      path: String,
      label: String,
      allowCurrentDirectory: Boolean
  ): Vector[ValidationError] =
    val syntaxErrors = pathSyntax(value, path, label)
    if syntaxErrors.nonEmpty then syntaxErrors
    else if value == "." && allowCurrentDirectory then Vector.empty
    else if value == "." then Vector(ValidationError(path, s"$label must not be current directory"))
    else
      Try(Path.of(value)) match
        case Failure(error) => Vector(ValidationError(path, s"invalid $label: ${error.getMessage}"))
        case Success(relative) if relative.isAbsolute =>
          Vector(ValidationError(path, s"$label must be relative"))
        case Success(_) => Vector.empty

  private def hasTraversalSegment(value: String): Boolean =
    Try(Path.of(value).iterator().asScala.exists(_.toString == "..")).getOrElse(false)

private object TemplateInterpolator:
  private val Variable: Regex = "\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}".r

  def interpolate(
      value: String,
      path: String,
      vars: Map[String, String]
  ): ResolvedValue[String] =
    // Only ${name} placeholders are recognized. Shell forms such as $(...) remain literal data.
    val errors = variableNames(value).distinct.flatMap: name =>
      if vars.contains(name) then Vector.empty
      else Vector(ValidationError(path, s"unresolved variable '$name'"))

    val rendered = Variable.replaceAllIn(
      value,
      matched => Regex.quoteReplacement(vars.getOrElse(matched.group(1), matched.matched))
    )
    ResolvedValue(rendered, errors)

  def variableNames(value: String): Vector[String] =
    Variable.findAllMatchIn(value).map(_.group(1)).toVector
