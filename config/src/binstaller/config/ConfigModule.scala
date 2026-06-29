package binstaller.config

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

object ConfigModule:
  val moduleName: String = "config"

  def load(path: String): Either[ConfigLoadError, BinaryDistributionProfile] =
    ConfigLoader.load(Path.of(path))

  def load(path: Path): Either[ConfigLoadError, BinaryDistributionProfile] = ConfigLoader.load(path)

  def loadString(yaml: String): Either[ConfigLoadError, BinaryDistributionProfile] =
    ConfigLoader.loadString(yaml)

enum ConfigLoadError:
  case ReadFailed(path: Path, message: String)
  case ParseFailed(message: String)
  case ValidationFailed(errors: Vector[ValidationError])

final case class ValidationError(path: String, message: String)

final case class BinaryDistributionProfile(
    apiVersion: ApiVersion,
    kind: ManifestKind,
    metadata: ManifestMetadata,
    spec: ProfileSpec
)

enum ApiVersion(val value: String):
  case V1Alpha1 extends ApiVersion("binstaller.io/v1alpha1")

enum ManifestKind(val value: String):
  case BinaryDistributionProfile extends ManifestKind("BinaryDistributionProfile")

final case class ManifestMetadata(
    name: String,
    labels: Map[String, String],
    annotations: Map[String, String]
)

final case class ProfileSpec(
    policy: InstallPolicy,
    vars: Map[String, String],
    versions: Map[String, VersionSource],
    plan: Vector[PlanEntry]
)

final case class InstallPolicy(
    dryRun: Boolean,
    continueOnError: Boolean,
    appsDir: String,
    cleanInstall: Boolean,
    requireConfirmation: Boolean,
    allowSudoSymlinks: AllowSudoSymlinks,
    stateFile: Option[String]
)

enum AllowSudoSymlinks:
  case Enabled, Disabled

object AllowSudoSymlinks:
  def fromBoolean(value: Boolean): AllowSudoSymlinks = if value then Enabled else Disabled

enum VersionSource:
  case Pinned(value: String)
  case Dynamic(kind: DynamicVersionKind, note: Option[String])
  case Resolver(kind: VersionResolverKind, url: String)

enum DynamicVersionKind(val value: String):
  case LatestUrl extends DynamicVersionKind("latest-url")

enum VersionResolverKind(val value: String):
  case HttpText extends VersionResolverKind("http-text")

final case class PlanEntry(
    name: String,
    kind: PlanKind,
    description: Option[String],
    when: Option[WhenClause],
    spec: BinaryToolSpec
)

enum PlanKind(val value: String):
  case BinaryTool extends PlanKind("binary-tool")

final case class WhenClause(os: Option[OsClause], architecture: Option[String])

final case class OsClause(family: Option[String])

final case class BinaryToolSpec(
    versionRef: String,
    installDir: String,
    createDirectories: Vector[String],
    download: DownloadSpec,
    installer: Option[InstallerSpec],
    executables: Vector[ExecutableSpec],
    symlinks: Vector[SymlinkSpec]
)

final case class DownloadSpec(
    url: String,
    filename: String,
    checksum: Option[ChecksumSpec],
    archive: Option[ArchiveSpec]
)

final case class ChecksumSpec(algorithm: ChecksumAlgorithm, value: String)

enum ChecksumAlgorithm(val value: String):
  case Sha256 extends ChecksumAlgorithm("sha256")

final case class ArchiveSpec(archiveType: ArchiveType, extract: ArchiveExtract)

enum ArchiveType(val value: String):
  case Zip   extends ArchiveType("zip")
  case TarGz extends ArchiveType("tar.gz")
  case TarXz extends ArchiveType("tar.xz")

final case class ArchiveExtract(
    files: Vector[ExtractMapping],
    directories: Vector[ExtractMapping]
)

final case class ExtractMapping(from: String, to: String)

final case class InstallerSpec(
    shell: InstallerShell,
    args: Vector[String],
    env: Vector[InstallerEnv],
    cleanup: Boolean
)

enum InstallerShell(val value: String):
  case Sh   extends InstallerShell("sh")
  case Bash extends InstallerShell("bash")

final case class InstallerEnv(name: String, value: String)

final case class ExecutableSpec(path: String, mode: Option[ExecutableMode])

final case class ExecutableMode(value: String)

enum SymlinkPrivilege:
  case User, Sudo

object SymlinkPrivilege:
  def fromBoolean(value: Boolean): SymlinkPrivilege = if value then Sudo else User

final case class SymlinkSpec(path: String, target: String, privilege: SymlinkPrivilege)

object ConfigLoader:

  def load(path: Path): Either[ConfigLoadError, BinaryDistributionProfile] =
    Try(Files.readString(path)) match
      case Success(yaml)  => loadString(yaml)
      case Failure(error) => Left(ConfigLoadError.ReadFailed(path, error.getMessage))

  def loadString(yaml: String): Either[ConfigLoadError, BinaryDistributionProfile] =
    parseYaml(yaml).flatMap(loadParsedYaml)

  private def parseYaml(yaml: String): Either[ConfigLoadError, Any] =
    val settings = LoadSettings.builder().setLabel("binstaller-profile").build()
    Try(Load(settings).loadFromString(yaml)) match
      case Success(value)                      => Right(convertYaml(value))
      case Failure(error: YamlEngineException) =>
        Left(ConfigLoadError.ParseFailed(error.getMessage))
      case Failure(error) => Left(ConfigLoadError.ParseFailed(error.getMessage))

  private def loadParsedYaml(value: Any): Either[ConfigLoadError, BinaryDistributionProfile] =
    val decoded          = ManifestDecoder.decode(value)
    val validationErrors = ProfileValidator.validate(decoded.value)
    val errors           = decoded.errors ++ validationErrors
    if errors.isEmpty then Right(decoded.value)
    else Left(ConfigLoadError.ValidationFailed(errors))

  private def convertYaml(value: Any): Any = value match
    case map: java.util.Map[?, ?] => map.asScala.collect:
        case (key: String, child) => key -> convertYaml(child)
      .toMap
    case list: java.util.List[?] => list.asScala.map(convertYaml).toVector
    case scalar                  => scalar

private final case class DecodeResult[+A](value: A, errors: Vector[ValidationError]):
  def map[B](f: A => B): DecodeResult[B] = DecodeResult(f(value), errors)

private object DecodeResult:
  def valid[A](value: A): DecodeResult[A] = DecodeResult(value, Vector.empty)

  def invalid[A](value: A, path: String, message: String): DecodeResult[A] =
    DecodeResult(value, Vector(ValidationError(path, message)))

private object ManifestDecoder:
  private type YamlMap = Map[String, Any]

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
    val map             = input.value
    val dryRun          = optionalBoolean(map, "dryRun", "spec.policy.dryRun", default = false)
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
    val stateFile = optionalString(map, "stateFile", "spec.policy.stateFile")

    DecodeResult(
      InstallPolicy(
        dryRun = dryRun.value,
        continueOnError = continueOnError.value,
        appsDir = appsDir.value,
        cleanInstall = cleanInstall.value,
        requireConfirmation = requireConfirmation.value,
        allowSudoSymlinks = allowSudoSymlinks.value,
        stateFile = stateFile.value
      ),
      input.errors ++ dryRun.errors ++ continueOnError.errors ++ appsDir.errors ++
        cleanInstall.errors ++ requireConfirmation.errors ++ allowSudoSymlinks.errors ++
        stateFile.errors
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
    val installer         = optionalInstaller(map, s"$path.installer")
    val executables       = decodeExecutables(requiredList(map, s"$path.executables"), path)
    val symlinks          = decodeSymlinks(optionalList(map, "symlinks", s"$path.symlinks"), path)

    DecodeResult(
      BinaryToolSpec(
        versionRef = versionRef.value,
        installDir = installDir.value,
        createDirectories = createDirectories.value,
        download = download.value,
        installer = installer.value,
        executables = executables.value,
        symlinks = symlinks.value
      ),
      input.errors ++ versionRef.errors ++ installDir.errors ++ createDirectories.errors ++
        download.errors ++ installer.errors ++ executables.errors ++ symlinks.errors
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
        val checksum = requiredString(checksumMap.value, s"$path.value")
        DecodeResult(
          Some(ChecksumSpec(algorithm.value, checksum.value)),
          checksumMap.errors ++ algorithm.errors ++ checksum.errors
        )

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

  private def optionalInstaller(map: YamlMap, path: String): DecodeResult[Option[InstallerSpec]] =
    map.get("installer") match
      case None        => DecodeResult.valid(None)
      case Some(value) =>
        val installerMap = asMap(value, path)
        val shell        = enumValue(
          requiredString(installerMap.value, s"$path.shell"),
          s"$path.shell",
          InstallerShell.values.toVector,
          InstallerShell.Sh,
          _.value
        )
        val args = optionalStringList(installerMap.value, "args", s"$path.args")
        val env  = decodeInstallerEnv(optionalList(installerMap.value, "env", s"$path.env"), path)
        val cleanup =
          optionalBoolean(installerMap.value, "cleanup", s"$path.cleanup", default = false)
        DecodeResult(
          Some(InstallerSpec(shell.value, args.value, env.value, cleanup.value)),
          installerMap.errors ++ shell.errors ++ args.errors ++ env.errors ++ cleanup.errors
        )

  private def decodeInstallerEnv(
      input: DecodeResult[Vector[Any]],
      installerPath: String
  ): DecodeResult[Vector[InstallerEnv]] =
    val path    = s"$installerPath.env"
    val decoded = input.value.zipWithIndex.map:
      case (rawValue, index) =>
        val item     = asMap(rawValue, s"$path[$index]")
        val name     = requiredString(item.value, s"$path[$index].name")
        val envValue = requiredString(item.value, s"$path[$index].value")
        DecodeResult(
          InstallerEnv(name.value, envValue.value),
          item.errors ++ name.errors ++ envValue.errors
        )
    DecodeResult(decoded.map(_.value), input.errors ++ decoded.flatMap(_.errors))

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

  private def requiredMap(map: YamlMap, path: String): DecodeResult[YamlMap] =
    val key = path.split("\\.").last
    map.get(key) match
      case Some(value) => asMap(value, path)
      case None        => DecodeResult.invalid(Map.empty, path, "required map is missing")

  private def requiredList(map: YamlMap, path: String): DecodeResult[Vector[Any]] =
    val key = path.split("\\.").last
    map.get(key) match
      case Some(value) => asList(value, path)
      case None        => DecodeResult.invalid(Vector.empty, path, "required list is missing")

  private def optionalList(map: YamlMap, key: String, path: String): DecodeResult[Vector[Any]] =
    map.get(key) match
      case Some(value) => asList(value, path)
      case None        => DecodeResult.valid(Vector.empty)

  private def optionalStringList(
      map: YamlMap,
      key: String,
      path: String
  ): DecodeResult[Vector[String]] =
    val list    = optionalList(map, key, path)
    val decoded = list.value.zipWithIndex.map:
      case (value: String, _) => DecodeResult.valid(value)
      case (_, index) => DecodeResult.invalid("", s"$path[$index]", "value must be a string")
    DecodeResult(decoded.map(_.value), list.errors ++ decoded.flatMap(_.errors))

  private def optionalStringMap(
      map: YamlMap,
      key: String,
      path: String
  ): DecodeResult[Map[String, String]] = map.get(key) match
    case None        => DecodeResult.valid(Map.empty)
    case Some(value) =>
      val child   = asMap(value, path)
      val decoded = child.value.toVector.map:
        case (childKey, scalar: String) => childKey -> DecodeResult.valid(scalar)
        case (childKey, _)              => childKey ->
            DecodeResult.invalid("", s"$path.$childKey", "value must be a string")
      DecodeResult(
        decoded.map((childKey, result) => childKey -> result.value).toMap,
        child.errors ++ decoded.flatMap((_, result) => result.errors)
      )

  private def requiredString(map: YamlMap, path: String): DecodeResult[String] =
    val key = path.split("\\.").last
    requiredString(map, key, path)

  private def requiredString(map: YamlMap, key: String, path: String): DecodeResult[String] =
    map.get(key) match
      case Some(value: String) => DecodeResult.valid(value)
      case Some(_)             => DecodeResult.invalid("", path, "required string must be a string")
      case None                => DecodeResult.invalid("", path, "required string is missing")

  private def optionalString(
      map: YamlMap,
      key: String,
      path: String
  ): DecodeResult[Option[String]] = map.get(key) match
    case None                => DecodeResult.valid(None)
    case Some(value: String) => DecodeResult.valid(Some(value))
    case Some(_)             => DecodeResult.invalid(None, path, "value must be a string")

  private def optionalBoolean(
      map: YamlMap,
      key: String,
      path: String,
      default: Boolean
  ): DecodeResult[Boolean] = map.get(key) match
    case None                 => DecodeResult.valid(default)
    case Some(value: Boolean) => DecodeResult.valid(value)
    case Some(_)              => DecodeResult.invalid(default, path, "value must be a boolean")

  private def asMap(value: Any, path: String): DecodeResult[YamlMap] = value match
    case map: YamlMap @unchecked => DecodeResult.valid(map)
    case _                       => DecodeResult.invalid(Map.empty, path, "value must be a map")

  private def asList(value: Any, path: String): DecodeResult[Vector[Any]] = value match
    case list: Vector[?] => DecodeResult.valid(list.asInstanceOf[Vector[Any]])
    case _               => DecodeResult.invalid(Vector.empty, path, "value must be a list")

  private def enumValue[A](
      input: DecodeResult[String],
      path: String,
      values: Vector[A],
      fallback: A,
      render: A => String
  ): DecodeResult[A] =
    if input.errors.nonEmpty then DecodeResult(fallback, input.errors)
    else
      values.find(value => render(value) == input.value) match
        case Some(value) => DecodeResult(value, input.errors)
        case None        => DecodeResult(
            fallback,
            input.errors :+ ValidationError(
              path,
              s"unsupported value '${input.value}', expected one of ${values.map(render).mkString(", ")}"
            )
          )

private object ProfileValidator:

  def validate(profile: BinaryDistributionProfile): Vector[ValidationError] =
    duplicateToolNameErrors(profile) ++ unknownVersionRefErrors(profile) ++
      sudoSymlinkErrors(profile)

  private def duplicateToolNameErrors(
      profile: BinaryDistributionProfile
  ): Vector[ValidationError] = profile.spec.plan
    .groupBy(_.name)
    .toVector
    .collect:
      case (name, entries) if name.nonEmpty && entries.size > 1 =>
        ValidationError("spec.plan", s"duplicate tool name '$name'")

  private def unknownVersionRefErrors(
      profile: BinaryDistributionProfile
  ): Vector[ValidationError] =
    val versionNames = profile.spec.versions.keySet
    profile.spec.plan.zipWithIndex.collect:
      case (entry, index)
          if entry.spec.versionRef.nonEmpty && !versionNames(entry.spec.versionRef) =>
        ValidationError(
          s"spec.plan[$index].spec.versionRef",
          s"tool '${entry.name}' references unknown version '${entry.spec.versionRef}'"
        )

  private def sudoSymlinkErrors(profile: BinaryDistributionProfile): Vector[ValidationError] =
    profile.spec.policy.allowSudoSymlinks match
      case AllowSudoSymlinks.Enabled  => Vector.empty
      case AllowSudoSymlinks.Disabled => profile.spec.plan.zipWithIndex.flatMap:
          case (entry, entryIndex) => entry.spec.symlinks.zipWithIndex.collect:
              case (symlink, symlinkIndex) if symlink.privilege == SymlinkPrivilege.Sudo =>
                ValidationError(
                  s"spec.plan[$entryIndex].spec.symlinks[$symlinkIndex].sudo",
                  s"tool '${entry.name}' uses a sudo symlink but policy.allowSudoSymlinks is false"
                )
