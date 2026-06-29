package binstaller.core

import binstaller.config.ArchiveSpec
import binstaller.config.ArchiveType
import binstaller.config.AllowSudoSymlinks
import binstaller.config.BinaryDistributionProfile
import binstaller.config.BinaryToolSpec
import binstaller.config.ChecksumSpec
import binstaller.config.ConfigLoadError
import binstaller.config.ConfigModule
import binstaller.config.DownloadSpec
import binstaller.config.DynamicVersionKind
import binstaller.config.ExecutableMode
import binstaller.config.ExtractMapping
import binstaller.config.InstallerEnv
import binstaller.config.InstallerShell
import binstaller.config.InstallerSpec
import binstaller.config.PlanEntry
import binstaller.config.SymlinkPrivilege
import binstaller.config.ValidationError
import binstaller.config.VersionResolverKind
import binstaller.config.VersionSource

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using
import scala.util.matching.Regex
import upickle.default.*

object CoreModule:
  def modulePath: Vector[String] = Vector(ConfigModule.moduleName, "core")

enum ResetState:
  case Enabled, Disabled

object ResetState:
  def fromFlag(value: Boolean): ResetState = if value then Enabled else Disabled

enum VerboseOutput:
  case Enabled, Disabled

object VerboseOutput:
  def fromFlag(value: Boolean): VerboseOutput = if value then Enabled else Disabled

final case class InstallerOptions(
    configPath: String,
    statePath: Option[String],
    resetState: ResetState,
    verboseOutput: VerboseOutput,
    selection: ToolSelection = ToolSelection.all,
    dryRun: DryRunMode = DryRunMode.Disabled,
    applyConfirmation: ApplyConfirmation = ApplyConfirmation.Disabled
)

final case class InstallerResult(lines: Vector[String], exitCode: Int)

final case class ToolSelection(only: Vector[String], skip: Vector[String])

object ToolSelection:
  def all: ToolSelection = ToolSelection(Vector.empty, Vector.empty)

enum DryRunMode:
  case Enabled, Disabled

object DryRunMode:
  def fromFlag(value: Boolean): DryRunMode = if value then Enabled else Disabled

enum ApplyConfirmation:
  case Enabled, Disabled

object ApplyConfirmation:
  def fromFlag(value: Boolean): ApplyConfirmation = if value then Enabled else Disabled

trait BinaryInstallerService:
  def plan(options: InstallerOptions): InstallerResult
  def apply(options: InstallerOptions): InstallerResult
  def versions(options: InstallerOptions): InstallerResult

object BinaryInstallerService:
  def placeholder: BinaryInstallerService = PlaceholderBinaryInstallerService

  def resolving(httpTextClient: HttpTextClient): BinaryInstallerService =
    resolving(httpTextClient, DirectBinaryInstaller.default)

  def resolving(
      httpTextClient: HttpTextClient,
      installer: DirectBinaryInstaller
  ): BinaryInstallerService = ResolvingBinaryInstallerService(
    httpTextClient,
    ResolutionOptions.fromEnvironment(),
    installer,
    ApplyStateStore.cwd
  )

  def resolving(
      httpTextClient: HttpTextClient,
      installer: DirectBinaryInstaller,
      stateStore: ApplyStateStore
  ): BinaryInstallerService = ResolvingBinaryInstallerService(
    httpTextClient,
    ResolutionOptions.fromEnvironment(),
    installer,
    stateStore
  )

final case class ResolutionOptions(runtimeVariables: Map[String, String])

object ResolutionOptions:
  def fromEnvironment(): ResolutionOptions = ResolutionOptions(sys.env.toMap)

final case class HttpTextError(url: String, message: String)

trait HttpTextClient:
  def getText(url: String): Either[HttpTextError, String]

object HttpTextClient:
  def jdk: HttpTextClient = JdkHttpTextClient(RuntimeHttpClient.create())

final case class BinaryDownloadError(url: String, message: String)

trait BinaryDownloadClient:
  def download(url: String): Either[BinaryDownloadError, Array[Byte]]

object BinaryDownloadClient:
  def jdk: BinaryDownloadClient = JdkBinaryDownloadClient(RuntimeHttpClient.create())

private object RuntimeHttpClient:

  def create(): HttpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

final case class CommandSpec(argv: Vector[String], cwd: Path, env: Map[String, String])

final case class CommandExecutionError(spec: CommandSpec, message: String, exitCode: Option[Int])

trait CommandExecutor:
  def run(spec: CommandSpec): Either[CommandExecutionError, Unit]

object CommandExecutor:
  def process: CommandExecutor = ProcessCommandExecutor

final case class ResolvedPlan(
    policy: ResolvedPolicy,
    tools: Vector[ResolvedTool]
)

final case class ResolvedPolicy(
    appsDir: String,
    stateFile: Option[String],
    allowSudoSymlinks: AllowSudoSymlinks,
    requireConfirmation: RequireConfirmation,
    continueOnError: ContinueOnError
)

enum RequireConfirmation:
  case Enabled, Disabled

object RequireConfirmation:
  def fromBoolean(value: Boolean): RequireConfirmation = if value then Enabled else Disabled

enum ContinueOnError:
  case Enabled, Disabled

object ContinueOnError:
  def fromBoolean(value: Boolean): ContinueOnError = if value then Enabled else Disabled

final case class ResolvedTool(
    name: String,
    description: Option[String],
    version: ResolvedVersion,
    installDir: String,
    createDirectories: Vector[String],
    download: ResolvedDownload,
    installer: Option[ResolvedInstaller],
    executables: Vector[ResolvedExecutable],
    symlinks: Vector[ResolvedSymlink]
)

enum ResolvedVersion:
  case Concrete(value: String)
  case DynamicLatestUrl(note: Option[String])

object ResolvedVersion:

  def render(version: ResolvedVersion): String = version match
    case ResolvedVersion.Concrete(value)     => value
    case ResolvedVersion.DynamicLatestUrl(_) => "dynamic latest-url"

final case class ResolvedDownload(
    url: String,
    filename: String,
    checksum: Option[ChecksumSpec],
    archive: Option[ResolvedArchive]
)

final case class ResolvedArchive(
    original: ArchiveSpec,
    files: Vector[ResolvedExtractMapping],
    directories: Vector[ResolvedExtractMapping]
)

final case class ResolvedExtractMapping(from: String, to: String)

final case class ResolvedInstaller(
    shell: InstallerShell,
    args: Vector[String],
    env: Vector[ResolvedInstallerEnv],
    cleanup: Boolean
)

final case class ResolvedInstallerEnv(name: String, value: String)

final case class ResolvedExecutable(path: String, mode: Option[ExecutableMode])

final case class ResolvedSymlink(path: String, target: String, privilege: SymlinkPrivilege)

enum ResolvePlanError:
  case ConfigLoadFailed(error: ConfigLoadError)
  case ValidationFailed(errors: Vector[ValidationError])
  case SelectionFailed(messages: Vector[String])

enum ApplyStateError:
  case InvalidPath(path: String, message: String)
  case ReadFailed(path: Path, message: String)
  case WriteFailed(path: Path, message: String)
  case DecodeFailed(path: Path, message: String)

  case IncompatibleState(
      path: Path,
      expectedProfileName: String,
      actualProfileName: String,
      expectedFingerprint: String,
      actualFingerprint: String
  )

final case class ApplyState(
    schemaVersion: Int,
    profileName: String,
    manifestFingerprint: String,
    tools: Vector[ApplyStateTool]
)

final case class ApplyStateTool(
    name: String,
    status: String,
    installDir: Option[String],
    message: Option[String]
)

object ApplyState:
  val schemaVersion: Int = 1

  given ReadWriter[ApplyStateTool] = macroRW
  given ReadWriter[ApplyState]     = macroRW

  def empty(profileName: String, manifestFingerprint: String): ApplyState = ApplyState(
    schemaVersion,
    profileName,
    manifestFingerprint,
    Vector.empty
  )

trait ApplyStateStore:
  def cwd: Path
  def load(path: Path): Either[ApplyStateError, Option[ApplyState]]
  def save(path: Path, state: ApplyState): Either[ApplyStateError, Unit]

object ApplyStateStore:
  def cwd: ApplyStateStore = nio(Path.of("").toAbsolutePath.normalize())

  def nio(directory: Path): ApplyStateStore =
    NioApplyStateStore(directory.toAbsolutePath.normalize())

final case class ToolInstallSuccess(toolName: String, installDir: String)

enum TerminalToolResult:
  case Completed(toolName: String, installDir: String)
  case Failed(toolName: String, message: String)

object TerminalToolResult:

  def line(result: TerminalToolResult): String = result match
    case TerminalToolResult.Completed(toolName, installDir) => s"installed $toolName to $installDir"
    case TerminalToolResult.Failed(toolName, message)       => s"failed $toolName: $message"

private final case class ObservedInstallResults(
    lines: Vector[String],
    results: Vector[TerminalToolResult],
    persistenceError: Option[String]
)

enum ApplyPreflightError:
  case ConfirmationRequired
  case SudoSymlinkNotAllowed(toolName: String)
  case SudoSymlinkConfirmationRequired(toolName: String)

enum ToolInstallError:
  case DownloadFailed(toolName: String, url: String, message: String)
  case ChecksumMismatch(toolName: String, expected: String, actual: String)
  case StagingFailed(toolName: String, message: String)
  case ModeApplicationFailed(toolName: String, path: String, mode: String, message: String)
  case ReplacementFailed(toolName: String, message: String)
  case ArchiveExtractionFailed(toolName: String, message: String)
  case InstallerExecutionFailed(toolName: String, argv: Vector[String], message: String)
  case InstallerCleanupFailed(toolName: String, path: String, message: String)
  case MissingExecutable(toolName: String, path: String)
  case SymlinkFailed(toolName: String, path: String, target: String, message: String)
  case SudoSymlinkNotAllowed(toolName: String)
  case SudoSymlinkConfirmationRequired(toolName: String)

final case class ExecutableInstallMode(octal: String, numeric: Int):

  def permissions: Set[PosixFilePermission] =
    val ownerRead    = permission(PosixFilePermission.OWNER_READ, 0x100)
    val ownerWrite   = permission(PosixFilePermission.OWNER_WRITE, 0x080)
    val ownerExecute = permission(PosixFilePermission.OWNER_EXECUTE, 0x040)
    val groupRead    = permission(PosixFilePermission.GROUP_READ, 0x020)
    val groupWrite   = permission(PosixFilePermission.GROUP_WRITE, 0x010)
    val groupExecute = permission(PosixFilePermission.GROUP_EXECUTE, 0x008)
    val otherRead    = permission(PosixFilePermission.OTHERS_READ, 0x004)
    val otherWrite   = permission(PosixFilePermission.OTHERS_WRITE, 0x002)
    val otherExecute = permission(PosixFilePermission.OTHERS_EXECUTE, 0x001)

    Vector(
      ownerRead,
      ownerWrite,
      ownerExecute,
      groupRead,
      groupWrite,
      groupExecute,
      otherRead,
      otherWrite,
      otherExecute
    ).flatten.toSet

  private def permission(
      permission: PosixFilePermission,
      bit: Int
  ): Option[PosixFilePermission] = if (numeric & bit) == bit then Some(permission) else None

object ExecutableInstallMode:
  val default: ExecutableInstallMode = fromOctal("0755")

  def fromConfig(mode: Option[ExecutableMode]): ExecutableInstallMode = mode match
    case Some(value) => fromOctal(value.value)
    case None        => default

  def fromOctal(value: String): ExecutableInstallMode =
    ExecutableInstallMode(value, Integer.parseInt(value, 8))

final case class ExecutableModeRequest(path: String, mode: ExecutableInstallMode)

final case class StagedInstall(stagingDir: Path, installDir: Path)

enum InstallFileSystemError:
  case StagingFailed(message: String)
  case ModeApplicationFailed(path: String, mode: String, message: String)
  case ReplacementFailed(message: String)

trait InstallFileSystem:

  def stageDirectBinary(
      installDir: Path,
      createDirectories: Vector[String],
      executablePath: String,
      bytes: Array[Byte]
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall]

  def stageArchive(
      installDir: Path,
      createDirectories: Vector[String],
      archive: ResolvedArchive,
      bytes: Array[Byte],
      commandExecutor: CommandExecutor
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall]

  def applyExecutableModes(
      stagedInstall: StagedInstall,
      executables: Vector[ExecutableModeRequest]
  ): Either[InstallFileSystemError.ModeApplicationFailed, Unit]

  def replaceInstall(
      stagedInstall: StagedInstall
  ): Either[InstallFileSystemError.ReplacementFailed, Unit]

  def discardStaged(stagedInstall: StagedInstall): Unit

object InstallFileSystem:
  def nio: InstallFileSystem = NioInstallFileSystem

final class DirectBinaryInstaller(
    downloadClient: BinaryDownloadClient,
    fileSystem: InstallFileSystem,
    commandExecutor: CommandExecutor = CommandExecutor.process
):

  def installPlan(
      plan: ResolvedPlan,
      applyConfirmation: ApplyConfirmation = ApplyConfirmation.Disabled,
      verboseOutput: VerboseOutput = VerboseOutput.Disabled
  ): InstallerResult =
    installPlanWithObserver(plan, applyConfirmation, verboseOutput, _ => Right(()))

  def installPlanWithObserver(
      plan: ResolvedPlan,
      applyConfirmation: ApplyConfirmation,
      verboseOutput: VerboseOutput,
      terminalObserver: TerminalToolResult => Either[String, Unit]
  ): InstallerResult = preflight(plan, applyConfirmation) match
    case Some(error) => InstallerResult(Vector(renderPreflightError(error)), 1)
    case None        =>
      val observed = installTools(plan.policy, plan.tools, verboseOutput, terminalObserver)
      val lines    = observed.lines ++
        observed.persistenceError.map(message => s"state write failed: $message").toVector
      val exitCode =
        if observed.results.exists(_.isInstanceOf[TerminalToolResult.Failed]) ||
          observed.persistenceError.nonEmpty
        then 1
        else 0

      InstallerResult(lines, exitCode)

  private def preflight(
      plan: ResolvedPlan,
      applyConfirmation: ApplyConfirmation
  ): Option[ApplyPreflightError] = applyConfirmation match
    case ApplyConfirmation.Disabled
        if plan.policy.requireConfirmation == RequireConfirmation.Enabled =>
      Some(ApplyPreflightError.ConfirmationRequired)
    case _ => sudoPreflight(plan, applyConfirmation)

  private def sudoPreflight(
      plan: ResolvedPlan,
      applyConfirmation: ApplyConfirmation
  ): Option[ApplyPreflightError] = plan.tools
    .find(_.symlinks.exists(_.privilege == SymlinkPrivilege.Sudo))
    .flatMap: tool =>
      plan.policy.allowSudoSymlinks match
        case AllowSudoSymlinks.Disabled =>
          Some(ApplyPreflightError.SudoSymlinkNotAllowed(tool.name))
        case AllowSudoSymlinks.Enabled => applyConfirmation match
            case ApplyConfirmation.Enabled  => None
            case ApplyConfirmation.Disabled =>
              Some(ApplyPreflightError.SudoSymlinkConfirmationRequired(tool.name))

  private def installTools(
      policy: ResolvedPolicy,
      tools: Vector[ResolvedTool],
      verboseOutput: VerboseOutput,
      terminalObserver: TerminalToolResult => Either[String, Unit]
  ): ObservedInstallResults = tools.headOption match
    case None       => ObservedInstallResults(Vector.empty, Vector.empty, None)
    case Some(tool) =>
      val result   = installTool(policy, tool)
      val terminal = terminalResult(result)
      terminalObserver(terminal) match
        case Left(message) => ObservedInstallResults(
            verboseLines(tool, verboseOutput) :+ TerminalToolResult.line(terminal),
            Vector(terminal),
            Some(message)
          )
        case Right(()) => result match
            case Left(_) if policy.continueOnError == ContinueOnError.Disabled =>
              ObservedInstallResults(
                verboseLines(tool, verboseOutput) :+ TerminalToolResult.line(terminal),
                Vector(terminal),
                None
              )
            case Left(_) =>
              val rest = installTools(policy, tools.tail, verboseOutput, terminalObserver)
              rest.copy(
                lines = verboseLines(tool, verboseOutput) ++
                  (TerminalToolResult.line(terminal) +: rest.lines),
                results = terminal +: rest.results
              )
            case Right(_) =>
              val rest = installTools(policy, tools.tail, verboseOutput, terminalObserver)
              rest.copy(
                lines = verboseLines(tool, verboseOutput) ++
                  (TerminalToolResult.line(terminal) +: rest.lines),
                results = terminal +: rest.results
              )

  def installTool(tool: ResolvedTool): Either[ToolInstallError, ToolInstallSuccess] =
    val policy = ResolvedPolicy(
      tool.installDir,
      None,
      AllowSudoSymlinks.Disabled,
      RequireConfirmation.Disabled,
      ContinueOnError.Disabled
    )
    if tool.symlinks.exists(_.privilege == SymlinkPrivilege.Sudo) then
      Left(ToolInstallError.SudoSymlinkNotAllowed(tool.name))
    else installTool(policy, tool)

  private def installTool(
      policy: ResolvedPolicy,
      tool: ResolvedTool
  ): Either[ToolInstallError, ToolInstallSuccess] = installWithoutPreflight(policy, tool)

  private def terminalResult(
      result: Either[ToolInstallError, ToolInstallSuccess]
  ): TerminalToolResult = result match
    case Right(success) => TerminalToolResult.Completed(success.toolName, success.installDir)
    case Left(error)    => TerminalToolResult.Failed(toolName(error), renderInstallError(error))

  private def verboseLines(tool: ResolvedTool, verboseOutput: VerboseOutput): Vector[String] =
    verboseOutput match
      case VerboseOutput.Disabled => Vector.empty
      case VerboseOutput.Enabled  =>
        val downloadLine   = s"verbose ${tool.name}: download ${tool.download.url}"
        val extractionLine = tool.download.archive match
          case Some(archive) => Some(
              s"verbose ${tool.name}: extract ${archive.original.archiveType.value} ${tool.download.filename}"
            )
          case None => None
        Vector(downloadLine) ++ extractionLine.toVector

  private def installWithoutPreflight(
      policy: ResolvedPolicy,
      tool: ResolvedTool
  ): Either[ToolInstallError, ToolInstallSuccess] = tool.installer match
    case Some(installer) => installWithScript(policy, tool, installer)
    case None            => installDownloadedBinaryOrArchive(policy, tool)

  private def installDownloadedBinaryOrArchive(
      policy: ResolvedPolicy,
      tool: ResolvedTool
  ): Either[ToolInstallError, ToolInstallSuccess] =
    for
      bytes  <- download(tool)
      _      <- verifyChecksum(tool, bytes)
      staged <- stage(tool, bytes)
      _      <- applyModes(tool, staged)
      _      <- replace(tool, staged)
      _      <- verifyExecutables(tool)
      _      <- createSymlinks(policy, tool)
    yield ToolInstallSuccess(tool.name, tool.installDir)

  private def installWithScript(
      policy: ResolvedPolicy,
      tool: ResolvedTool,
      installer: ResolvedInstaller
  ): Either[ToolInstallError, ToolInstallSuccess] =
    for
      bytes      <- download(tool)
      _          <- verifyChecksum(tool, bytes)
      scriptPath <- writeInstallerScript(tool, bytes)
      _          <- runInstallerWithCleanup(tool, installer, scriptPath)
      _          <- verifyExecutables(tool)
      _          <- applyInstalledModes(tool)
      _          <- createSymlinks(policy, tool)
    yield ToolInstallSuccess(tool.name, tool.installDir)

  private def runInstallerWithCleanup(
      tool: ResolvedTool,
      installer: ResolvedInstaller,
      scriptPath: Path
  ): Either[ToolInstallError, Unit] =
    val result  = runInstaller(tool, installer)
    val cleanup = cleanupInstallerScript(tool, installer, scriptPath)
    cleanup match
      case Left(error) => Left(error)
      case Right(())   => result

  private def runInstaller(
      tool: ResolvedTool,
      installer: ResolvedInstaller
  ): Either[ToolInstallError, Unit] =
    val spec = CommandSpec(
      Vector(installer.shell.value) ++ installer.args,
      Path.of(tool.installDir).toAbsolutePath.normalize(),
      CommandEnvironment.baseline ++ installer.env.map(env => env.name -> env.value).toMap
    )
    commandExecutor.run(spec).left.map: error =>
      ToolInstallError.InstallerExecutionFailed(tool.name, error.spec.argv, error.message)

  private def writeInstallerScript(
      tool: ResolvedTool,
      bytes: Array[Byte]
  ): Either[ToolInstallError, Path] =
    for
      scriptPath <- resolveInsideInstall(tool, tool.download.filename)
      _          <- createInstallDirectories(tool)
      _          <- Try:
        Option(scriptPath.getParent).foreach(parent => Files.createDirectories(parent))
        val _ = Files.write(
          scriptPath,
          bytes,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE
        )
        Files.setPosixFilePermissions(
          scriptPath,
          ExecutableInstallMode.fromOctal("0700").permissions.asJava
        )
      match
        case Success(_)     => Right(())
        case Failure(error) => Left(ToolInstallError.StagingFailed(tool.name, error.getMessage))
    yield scriptPath

  private def createInstallDirectories(tool: ResolvedTool): Either[ToolInstallError, Unit] =
    val writes = tool.createDirectories.map: directory =>
      resolveInsideInstall(tool, directory).flatMap: path =>
        Try(Files.createDirectories(path)) match
          case Success(_)     => Right(())
          case Failure(error) => Left(ToolInstallError.StagingFailed(tool.name, error.getMessage))
    writes.collectFirst:
      case Left(error) => error
    match
      case Some(error) => Left(error)
      case None        => Right(())

  private def cleanupInstallerScript(
      tool: ResolvedTool,
      installer: ResolvedInstaller,
      scriptPath: Path
  ): Either[ToolInstallError, Unit] =
    if !installer.cleanup then Right(())
    else
      Try(Files.deleteIfExists(scriptPath)) match
        case Success(_)     => Right(())
        case Failure(error) => Left(ToolInstallError.InstallerCleanupFailed(
            tool.name,
            scriptPath.toString,
            error.getMessage
          ))

  private def download(tool: ResolvedTool): Either[ToolInstallError, Array[Byte]] =
    downloadClient.download(tool.download.url).left.map: error =>
      ToolInstallError.DownloadFailed(tool.name, error.url, error.message)

  private def verifyChecksum(
      tool: ResolvedTool,
      bytes: Array[Byte]
  ): Either[ToolInstallError, Unit] = tool.download.checksum match
    case None           => Right(())
    case Some(checksum) =>
      val actual = Sha256.digest(bytes)
      if actual.equalsIgnoreCase(checksum.value) then Right(())
      else Left(ToolInstallError.ChecksumMismatch(tool.name, checksum.value, actual))

  private def stage(
      tool: ResolvedTool,
      bytes: Array[Byte]
  ): Either[ToolInstallError, StagedInstall] = tool.download.archive match
    case Some(archive) => fileSystem
        .stageArchive(
          Path.of(tool.installDir),
          tool.createDirectories,
          archive,
          bytes,
          commandExecutor
        )
        .left
        .map(error => ToolInstallError.ArchiveExtractionFailed(tool.name, error.message))
    case None => tool.executables.headOption match
        case None                  => Left(ToolInstallError.MissingExecutable(tool.name, "<none>"))
        case Some(firstExecutable) => fileSystem
            .stageDirectBinary(
              Path.of(tool.installDir),
              tool.createDirectories,
              firstExecutable.path,
              bytes
            )
            .left
            .map(error => ToolInstallError.StagingFailed(tool.name, error.message))

  private def applyModes(
      tool: ResolvedTool,
      stagedInstall: StagedInstall
  ): Either[ToolInstallError, Unit] =
    val modes = tool.executables.map: executable =>
      ExecutableModeRequest(executable.path, ExecutableInstallMode.fromConfig(executable.mode))

    fileSystem.applyExecutableModes(stagedInstall, modes).left.map: error =>
      ToolInstallError.ModeApplicationFailed(tool.name, error.path, error.mode, error.message)

  private def replace(
      tool: ResolvedTool,
      stagedInstall: StagedInstall
  ): Either[ToolInstallError, Unit] = fileSystem.replaceInstall(stagedInstall).left.map: error =>
    ToolInstallError.ReplacementFailed(tool.name, error.message)

  private def applyInstalledModes(tool: ResolvedTool): Either[ToolInstallError, Unit] =
    val writes = tool.executables.map: executable =>
      val mode = ExecutableInstallMode.fromConfig(executable.mode)
      resolveInsideInstall(tool, executable.path).flatMap: path =>
        Try(Files.setPosixFilePermissions(path, mode.permissions.asJava)) match
          case Success(_)     => Right(())
          case Failure(error) => Left(ToolInstallError.ModeApplicationFailed(
              tool.name,
              executable.path,
              mode.octal,
              error.getMessage
            ))
    writes.collectFirst:
      case Left(error) => error
    match
      case Some(error) => Left(error)
      case None        => Right(())

  private def verifyExecutables(tool: ResolvedTool): Either[ToolInstallError, Unit] =
    tool.executables
      .map: executable =>
        resolveInsideInstall(tool, executable.path).flatMap: path =>
          if Files.isRegularFile(path) then Right(())
          else Left(ToolInstallError.MissingExecutable(tool.name, executable.path))
      .collectFirst:
        case Left(error) => error
    match
      case Some(error) => Left(error)
      case None        => Right(())

  private def createSymlinks(
      policy: ResolvedPolicy,
      tool: ResolvedTool
  ): Either[ToolInstallError, Unit] =
    val writes = tool.symlinks.map: symlink =>
      symlink.privilege match
        case SymlinkPrivilege.User => createLocalSymlink(tool, symlink)
        case SymlinkPrivilege.Sudo => createSudoSymlink(policy, tool, symlink)
    writes.collectFirst:
      case Left(error) => error
    match
      case Some(error) => Left(error)
      case None        => Right(())

  private def createLocalSymlink(
      tool: ResolvedTool,
      symlink: ResolvedSymlink
  ): Either[ToolInstallError, Unit] =
    for
      path   <- resolveInsideInstall(tool, symlink.path)
      target <- resolveSymlinkTarget(tool, symlink)
      _      <- Try:
        Option(path.getParent).foreach(parent => Files.createDirectories(parent))
        val _ = Files.deleteIfExists(path)
        Files.createSymbolicLink(path, target)
      match
        case Success(_)     => Right(())
        case Failure(error) => Left(ToolInstallError.SymlinkFailed(
            tool.name,
            path.toString,
            target.toString,
            error.getMessage
          ))
    yield ()

  private def createSudoSymlink(
      policy: ResolvedPolicy,
      tool: ResolvedTool,
      symlink: ResolvedSymlink
  ): Either[ToolInstallError, Unit] = policy.allowSudoSymlinks match
    case AllowSudoSymlinks.Disabled => Left(ToolInstallError.SudoSymlinkNotAllowed(tool.name))
    case AllowSudoSymlinks.Enabled  =>
      val path = Path.of(symlink.path)
      if !path.isAbsolute then
        Left(
          ToolInstallError.SymlinkFailed(
            tool.name,
            symlink.path,
            symlink.target,
            "sudo symlink path must be absolute"
          )
        )
      else
        resolveSymlinkTarget(tool, symlink).flatMap: target =>
          val spec = CommandSpec(
            Vector("sudo", "ln", "-sfn", target.toString, path.toString),
            Path.of(tool.installDir).toAbsolutePath.normalize(),
            CommandEnvironment.baseline
          )
          commandExecutor.run(spec).left.map: error =>
            ToolInstallError.SymlinkFailed(tool.name, path.toString, target.toString, error.message)

  private def resolveSymlinkTarget(
      tool: ResolvedTool,
      symlink: ResolvedSymlink
  ): Either[ToolInstallError, Path] =
    val installDir = Path.of(tool.installDir).toAbsolutePath.normalize()
    val rawTarget  = Path.of(symlink.target)
    val target     =
      if rawTarget.isAbsolute then rawTarget.toAbsolutePath.normalize()
      else installDir.resolve(rawTarget).normalize()
    if target.startsWith(installDir) then Right(target)
    else
      Left(
        ToolInstallError.SymlinkFailed(
          tool.name,
          symlink.path,
          symlink.target,
          "symlink target must resolve inside installDir"
        )
      )

  private def resolveInsideInstall(
      tool: ResolvedTool,
      relative: String
  ): Either[ToolInstallError, Path] =
    val input      = Path.of(relative)
    val installDir = Path.of(tool.installDir).toAbsolutePath.normalize()
    if input.isAbsolute then
      Left(ToolInstallError.StagingFailed(tool.name, s"path must be relative: $relative"))
    else
      val resolved = installDir.resolve(input).normalize()
      if resolved.startsWith(installDir) then Right(resolved)
      else Left(ToolInstallError.StagingFailed(tool.name, s"path escapes installDir: $relative"))

  private def toolName(error: ToolInstallError): String = error match
    case ToolInstallError.DownloadFailed(toolName, _, _)            => toolName
    case ToolInstallError.ChecksumMismatch(toolName, _, _)          => toolName
    case ToolInstallError.StagingFailed(toolName, _)                => toolName
    case ToolInstallError.ModeApplicationFailed(toolName, _, _, _)  => toolName
    case ToolInstallError.ReplacementFailed(toolName, _)            => toolName
    case ToolInstallError.ArchiveExtractionFailed(toolName, _)      => toolName
    case ToolInstallError.InstallerExecutionFailed(toolName, _, _)  => toolName
    case ToolInstallError.InstallerCleanupFailed(toolName, _, _)    => toolName
    case ToolInstallError.MissingExecutable(toolName, _)            => toolName
    case ToolInstallError.SymlinkFailed(toolName, _, _, _)          => toolName
    case ToolInstallError.SudoSymlinkNotAllowed(toolName)           => toolName
    case ToolInstallError.SudoSymlinkConfirmationRequired(toolName) => toolName

  private def renderInstallError(error: ToolInstallError): String = error match
    case ToolInstallError.DownloadFailed(_, url, message)       => s"download: $url: $message"
    case ToolInstallError.ChecksumMismatch(_, expected, actual) =>
      s"checksum: sha256 expected $expected, got $actual"
    case ToolInstallError.StagingFailed(_, message)                     => s"staging: $message"
    case ToolInstallError.ModeApplicationFailed(_, path, mode, message) =>
      s"mode: $mode for $path: $message"
    case ToolInstallError.ReplacementFailed(_, message)       => s"replacement: $message"
    case ToolInstallError.ArchiveExtractionFailed(_, message) => s"archive extraction: $message"
    case ToolInstallError.InstallerExecutionFailed(_, argv, message) =>
      s"installer command: ${argv.mkString(" ")}: $message"
    case ToolInstallError.InstallerCleanupFailed(_, path, message) =>
      s"installer cleanup: $path: $message"
    case ToolInstallError.MissingExecutable(_, path) => s"verify executable: missing $path"
    case ToolInstallError.SymlinkFailed(_, path, target, message) =>
      s"symlink: $target -> $path: $message"
    case ToolInstallError.SudoSymlinkNotAllowed(_) =>
      "sudo symlinks are not allowed by policy.allowSudoSymlinks"
    case ToolInstallError.SudoSymlinkConfirmationRequired(_) =>
      "sudo symlinks require apply confirmation; rerun apply with --yes"

  private def renderPreflightError(error: ApplyPreflightError): String = error match
    case ApplyPreflightError.ConfirmationRequired =>
      "apply requires confirmation by policy.requireConfirmation; rerun apply with --yes"
    case ApplyPreflightError.SudoSymlinkNotAllowed(toolName) =>
      s"failed $toolName: sudo symlinks are not allowed by policy.allowSudoSymlinks"
    case ApplyPreflightError.SudoSymlinkConfirmationRequired(toolName) =>
      s"failed $toolName: sudo symlinks require apply confirmation; rerun apply with --yes"

object DirectBinaryInstaller:

  def default: DirectBinaryInstaller =
    DirectBinaryInstaller(BinaryDownloadClient.jdk, NioInstallFileSystem, CommandExecutor.process)

object PlanResolver:

  def resolve(
      profile: BinaryDistributionProfile,
      options: ResolutionOptions,
      httpTextClient: HttpTextClient
  ): Either[ResolvePlanError.ValidationFailed, ResolvedPlan] =
    val resolved = ResolutionBuilder(profile, options, httpTextClient).resolve()
    if resolved.errors.isEmpty then Right(resolved.value)
    else Left(ResolvePlanError.ValidationFailed(resolved.errors))

private final case class PreparedPlan(
    profile: BinaryDistributionProfile,
    profileName: String,
    manifestFingerprint: String,
    plan: ResolvedPlan
)

private object StatefulApplyRunner:

  def run(
      options: InstallerOptions,
      prepared: PreparedPlan,
      installer: DirectBinaryInstaller,
      stateStore: ApplyStateStore
  ): InstallerResult = confirmationPreflight(options, prepared.plan) match
    case Some(result) => result
    case None         => runAfterConfirmation(options, prepared, installer, stateStore)

  private def confirmationPreflight(
      options: InstallerOptions,
      plan: ResolvedPlan
  ): Option[InstallerResult] = options.applyConfirmation match
    case ApplyConfirmation.Disabled
        if plan.policy.requireConfirmation == RequireConfirmation.Enabled =>
      Some(InstallerResult(
        Vector(
          "apply requires confirmation by policy.requireConfirmation; rerun apply with --yes"
        ),
        1
      ))
    case _ => None

  private def runAfterConfirmation(
      options: InstallerOptions,
      prepared: PreparedPlan,
      installer: DirectBinaryInstaller,
      stateStore: ApplyStateStore
  ): InstallerResult = statePath(options, prepared.plan) match
    case None =>
      installer.installPlan(prepared.plan, options.applyConfirmation, options.verboseOutput)
    case Some(path) => loadInitialState(path, options.resetState, prepared, stateStore) match
        case Left(error)               => InstallerResult(Vector(renderStateError(error)), 1)
        case Right((statePath, state)) =>
          runWithState(statePath, state, prepared, options, installer, stateStore)

  private def statePath(options: InstallerOptions, plan: ResolvedPlan): Option[String] =
    options.statePath.orElse(plan.policy.stateFile)

  private def loadInitialState(
      rawPath: String,
      resetState: ResetState,
      prepared: PreparedPlan,
      stateStore: ApplyStateStore
  ): Either[ApplyStateError, (Path, ApplyState)] =
    for
      path  <- StatePathResolver.resolve(rawPath, stateStore.cwd)
      state <- resetState match
        case ResetState.Enabled => Right(
            ApplyState.empty(prepared.profileName, prepared.manifestFingerprint)
          )
        case ResetState.Disabled => stateStore.load(path).flatMap:
            case None => Right(ApplyState.empty(prepared.profileName, prepared.manifestFingerprint))
            case Some(state) => validateState(path, state, prepared)
    yield path -> state

  private def validateState(
      path: Path,
      state: ApplyState,
      prepared: PreparedPlan
  ): Either[ApplyStateError, ApplyState] =
    if state.profileName == prepared.profileName &&
      state.manifestFingerprint == prepared.manifestFingerprint
    then Right(state)
    else
      Left(
        ApplyStateError.IncompatibleState(
          path,
          prepared.profileName,
          state.profileName,
          prepared.manifestFingerprint,
          state.manifestFingerprint
        )
      )

  private def runWithState(
      path: Path,
      state: ApplyState,
      prepared: PreparedPlan,
      options: InstallerOptions,
      installer: DirectBinaryInstaller,
      stateStore: ApplyStateStore
  ): InstallerResult =
    val completed    = completedToolNames(state)
    val pendingTools = prepared.plan.tools.filterNot(tool => completed(tool.name))
    val skippedLines = prepared.plan.tools
      .filter(tool => completed(tool.name))
      .map(tool => s"skipped ${tool.name}: already completed in state")
    val pendingPlan  = prepared.plan.copy(tools = pendingTools)
    var currentState = state
    val result       = installer.installPlanWithObserver(
      pendingPlan,
      options.applyConfirmation,
      options.verboseOutput,
      terminal =>
        currentState = updateState(currentState, terminal)
        stateStore.save(path, currentState).left.map(renderStateError)
    )

    result.copy(lines = skippedLines ++ result.lines)

  private def completedToolNames(state: ApplyState): Set[String] = state.tools.collect:
    case tool if tool.status == "completed" => tool.name
  .toSet

  private def updateState(state: ApplyState, result: TerminalToolResult): ApplyState =
    val updatedTool = result match
      case TerminalToolResult.Completed(toolName, installDir) =>
        ApplyStateTool(toolName, "completed", Some(installDir), None)
      case TerminalToolResult.Failed(toolName, message) =>
        ApplyStateTool(toolName, "failed", None, Some(message))
    state.copy(tools = replaceTool(state.tools, updatedTool))

  private def replaceTool(
      tools: Vector[ApplyStateTool],
      updated: ApplyStateTool
  ): Vector[ApplyStateTool] =
    val withoutCurrent = tools.filterNot(_.name == updated.name)
    withoutCurrent :+ updated

  private def renderStateError(error: ApplyStateError): String = error match
    case ApplyStateError.InvalidPath(path, message)  => s"state path '$path' is invalid: $message"
    case ApplyStateError.ReadFailed(path, message)   => s"state read failed for $path: $message"
    case ApplyStateError.WriteFailed(path, message)  => s"state write failed for $path: $message"
    case ApplyStateError.DecodeFailed(path, message) => s"state decode failed for $path: $message"
    case ApplyStateError.IncompatibleState(
          path,
          expectedProfileName,
          actualProfileName,
          expectedFingerprint,
          actualFingerprint
        ) =>
      s"state file $path does not match this manifest: expected profile '$expectedProfileName' " +
        s"with fingerprint $expectedFingerprint, found profile '$actualProfileName' with " +
        s"fingerprint $actualFingerprint; rerun with --reset-state to ignore saved state"

private object StatePathResolver:

  def resolve(rawPath: String, cwd: Path): Either[ApplyStateError.InvalidPath, Path] =
    val path = Path.of(rawPath)
    if rawPath.trim.isEmpty then invalid(rawPath, "state filename must not be empty")
    else if path.isAbsolute then invalid(rawPath, "absolute state paths are not allowed")
    else if path.getNameCount != 1 then
      invalid(rawPath, "state path must be a filename in the current working directory")
    else
      val resolved = cwd.toAbsolutePath.normalize().resolve(path).normalize()
      if resolved.getParent == cwd.toAbsolutePath.normalize() then Right(resolved)
      else invalid(rawPath, "state path must stay in the current working directory")

  private def invalid(
      path: String,
      message: String
  ): Either[ApplyStateError.InvalidPath, Path] = Left(ApplyStateError.InvalidPath(path, message))

private final class NioApplyStateStore(val cwd: Path) extends ApplyStateStore:

  def load(path: Path): Either[ApplyStateError, Option[ApplyState]] =
    if !Files.exists(path) then Right(None)
    else
      Try(read[ApplyState](Files.readString(path))) match
        case Success(state)                     => Right(Some(state))
        case Failure(error: upickle.core.Abort) =>
          Left(ApplyStateError.DecodeFailed(path, error.getMessage))
        case Failure(error) => Left(ApplyStateError.ReadFailed(path, error.getMessage))

  def save(path: Path, state: ApplyState): Either[ApplyStateError, Unit] =
    val tmp = cwd.resolve(s".${path.getFileName}.tmp-${UUID.randomUUID()}")
    Try:
      Files.createDirectories(cwd)
      val _ = Files.writeString(
        tmp,
        write(state, indent = 2),
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE
      )
      val _ = Files.move(
        tmp,
        path,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING
      )
    match
      case Success(_)     => Right(())
      case Failure(error) =>
        val _ = Files.deleteIfExists(tmp)
        Left(ApplyStateError.WriteFailed(path, error.getMessage))

private object ManifestFingerprint:

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
    append(builder, "spec.policy.dryRun", policy.dryRun.toString)
    append(builder, "spec.policy.continueOnError", policy.continueOnError.toString)
    append(builder, "spec.policy.appsDir", policy.appsDir)
    append(builder, "spec.policy.cleanInstall", policy.cleanInstall.toString)
    append(builder, "spec.policy.requireConfirmation", policy.requireConfirmation.toString)
    append(builder, "spec.policy.allowSudoSymlinks", policy.allowSudoSymlinks.toString)
    append(builder, "spec.policy.stateFile", policy.stateFile.getOrElse(""))

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
    spec.installer.foreach(appendInstaller(builder, s"$base.installer", _))
    appendExecutables(builder, s"$base.executables", spec.executables)
    appendSymlinks(builder, s"$base.symlinks", spec.symlinks)

  private def appendDownload(builder: StringBuilder, base: String, download: DownloadSpec): Unit =
    append(builder, s"$base.url", download.url)
    append(builder, s"$base.filename", download.filename)
    download.checksum.foreach: checksum =>
      append(builder, s"$base.checksum.algorithm", checksum.algorithm.value)
      append(builder, s"$base.checksum.value", checksum.value)
    download.archive.foreach: archive =>
      append(builder, s"$base.archive.type", archive.archiveType.value)
      appendMappings(builder, s"$base.archive.extract.files", archive.extract.files)
      appendMappings(builder, s"$base.archive.extract.directories", archive.extract.directories)

  private def appendInstaller(
      builder: StringBuilder,
      base: String,
      installer: InstallerSpec
  ): Unit =
    append(builder, s"$base.shell", installer.shell.value)
    appendVector(builder, s"$base.args", installer.args)
    installer.env.zipWithIndex.foreach:
      case (env, index) =>
        append(builder, s"$base.env[$index].name", env.name)
        append(builder, s"$base.env[$index].value", env.value)
    append(builder, s"$base.cleanup", installer.cleanup.toString)

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

private object PlaceholderBinaryInstallerService extends BinaryInstallerService:
  def plan(options: InstallerOptions): InstallerResult = placeholderResult("plan", options)

  def apply(options: InstallerOptions): InstallerResult = placeholderResult("apply", options)

  def versions(options: InstallerOptions): InstallerResult = placeholderResult("versions", options)

  private def placeholderResult(command: String, options: InstallerOptions): InstallerResult =
    InstallerResult(
      Vector(s"binstaller $command placeholder for ${options.configPath}"),
      0
    )

private final class ResolvingBinaryInstallerService(
    httpTextClient: HttpTextClient,
    resolutionOptions: ResolutionOptions,
    installer: DirectBinaryInstaller,
    stateStore: ApplyStateStore
) extends BinaryInstallerService:

  def plan(options: InstallerOptions): InstallerResult =
    renderSelectedPlan(options, PlanRenderCommand.Plan)

  def apply(options: InstallerOptions): InstallerResult =
    if options.dryRun == DryRunMode.Enabled then
      renderSelectedPlan(options, PlanRenderCommand.ApplyDryRun)
    else
      resolveSelectedPreparedPlan(options).fold(
        renderError,
        prepared => StatefulApplyRunner.run(options, prepared, installer, stateStore)
      )

  def versions(options: InstallerOptions): InstallerResult =
    resolveFromOptions(options).fold(renderError, renderVersions)

  private def renderSelectedPlan(
      options: InstallerOptions,
      command: PlanRenderCommand
  ): InstallerResult =
    resolveSelectedPlan(options).fold(renderError, plan => PlanRenderer.render(plan, command))

  private def resolveSelectedPlan(
      options: InstallerOptions
  ): Either[ResolvePlanError, ResolvedPlan] = resolveSelectedPreparedPlan(options).map(_.plan)

  private def resolveSelectedPreparedPlan(
      options: InstallerOptions
  ): Either[ResolvePlanError, PreparedPlan] = resolveFromOptions(options).flatMap: prepared =>
    ToolSelector.select(prepared.plan, options.selection).map: selected =>
      prepared.copy(plan = selected)

  private def resolveFromOptions(
      options: InstallerOptions
  ): Either[ResolvePlanError, PreparedPlan] = ConfigModule.load(options.configPath) match
    case Left(error)    => Left(ResolvePlanError.ConfigLoadFailed(error))
    case Right(profile) => PlanResolver.resolve(profile, resolutionOptions, httpTextClient).map:
        plan =>
          PreparedPlan(
            profile,
            profile.metadata.name,
            ManifestFingerprint.profile(profile),
            plan
          )

  private def renderVersions(prepared: PreparedPlan): InstallerResult =
    val references = versionReferences(prepared.profile)
    val resolved   = prepared.plan.tools.map(tool => tool.name -> tool.version).toMap
    val lines      = prepared.profile.spec.versions.toVector.sortBy(_._1).map:
      case (name, source) =>
        val tools = references.getOrElse(name, Vector.empty)
        renderVersionSource(name, source, resolved.get(name), tools)
    InstallerResult("binstaller versions" +: lines, 0)

  private def versionReferences(
      profile: BinaryDistributionProfile
  ): Map[String, Vector[String]] = profile.spec.plan
    .groupBy(_.spec.versionRef)
    .view
    .mapValues(_.map(_.name))
    .toMap

  private def renderVersionSource(
      name: String,
      source: VersionSource,
      resolved: Option[ResolvedVersion],
      tools: Vector[String]
  ): String =
    val toolList = if tools.isEmpty then "none" else tools.mkString(", ")
    source match
      case VersionSource.Pinned(value) => s"pinned $name: $value (tools: $toolList)"
      case VersionSource.Dynamic(DynamicVersionKind.LatestUrl, note) =>
        val suffix = note.map(value => s" - $value").getOrElse("")
        s"dynamic $name: dynamic latest-url (tools: $toolList)$suffix"
      case VersionSource.Resolver(VersionResolverKind.HttpText, url) =>
        val value = resolved.map(ResolvedVersion.render).getOrElse("<unresolved>")
        s"resolved $name: $value from $url (tools: $toolList)"

  private def renderError(error: ResolvePlanError): InstallerResult = error match
    case ResolvePlanError.ConfigLoadFailed(loadError) => renderConfigLoadError(loadError)
    case ResolvePlanError.ValidationFailed(errors)    =>
      InstallerResult(errors.map(error => s"${error.path}: ${error.message}"), 1)
    case ResolvePlanError.SelectionFailed(messages) =>
      InstallerResult(messages.map(message => s"selection: $message"), 1)

  private def renderConfigLoadError(error: ConfigLoadError): InstallerResult = error match
    case ConfigLoadError.ValidationFailed(errors) =>
      InstallerResult(errors.map(error => s"${error.path}: ${error.message}"), 1)
    case ConfigLoadError.ReadFailed(path, message) =>
      InstallerResult(Vector(s"config read failed for $path: $message"), 1)
    case ConfigLoadError.ParseFailed(message) =>
      InstallerResult(Vector(s"config parse failed: $message"), 1)

private object ToolSelector:

  def select(
      plan: ResolvedPlan,
      selection: ToolSelection
  ): Either[ResolvePlanError.SelectionFailed, ResolvedPlan] =
    val toolNames = plan.tools.map(_.name).toSet
    val unknown   = (selection.only ++ selection.skip)
      .distinct
      .filterNot(toolNames.contains)
      .map(name => s"unknown tool '$name'")

    if unknown.nonEmpty then Left(ResolvePlanError.SelectionFailed(unknown))
    else Right(plan.copy(tools = selectedTools(plan.tools, selection)))

  private def selectedTools(
      tools: Vector[ResolvedTool],
      selection: ToolSelection
  ): Vector[ResolvedTool] =
    val onlyNames = selection.only.toSet
    val skipNames = selection.skip.toSet
    val included  =
      if onlyNames.isEmpty then tools
      else tools.filter(tool => onlyNames(tool.name))

    included.filterNot(tool => skipNames(tool.name))

private enum PlanRenderCommand:
  case Plan, ApplyDryRun

private object PlanRenderer:

  def render(plan: ResolvedPlan, command: PlanRenderCommand): InstallerResult =
    InstallerResult(header(plan, command) ++ plan.tools.zipWithIndex.flatMap(renderTool), 0)

  private def header(plan: ResolvedPlan, command: PlanRenderCommand): Vector[String] =
    val sudoSymlinkCount =
      plan.tools.flatMap(_.symlinks).count(_.privilege == SymlinkPrivilege.Sudo)
    val stateLine = plan.policy.stateFile match
      case Some(path) => s"state file: $path (not created)"
      case None       => "state file: not configured"
    val sudoLine =
      if sudoSymlinkCount == 0 then "sudo risk: none"
      else
        s"sudo risk: YES - $sudoSymlinkCount sudo symlink command(s) require elevated privileges"
    val title = command match
      case PlanRenderCommand.Plan        => "binstaller plan (dry-run)"
      case PlanRenderCommand.ApplyDryRun => "binstaller apply --dry-run"

    Vector(
      title,
      s"tools: ${plan.tools.size}",
      s"apps dir: ${plan.policy.appsDir} (not created)",
      stateLine,
      "filesystem: no changes will be made",
      sudoLine
    )

  private def renderTool(indexedTool: (ResolvedTool, Int)): Vector[String] =
    val (tool, index) = indexedTool
    Vector(
      "",
      s"${index + 1}. ${tool.name}",
      s"   destination: ${tool.installDir}",
      s"   version: ${renderVersion(tool.version)}",
      s"   download: ${tool.download.url}",
      s"   download file: ${joinPath(tool.installDir, tool.download.filename)}",
      s"   checksum: ${renderChecksum(tool.download.checksum)}"
    ) ++ renderCreateDirectories(tool) ++ renderStrategy(tool) ++ renderExecutables(tool) ++
      renderSymlinks(tool)

  private def renderVersion(version: ResolvedVersion): String = version match
    case ResolvedVersion.Concrete(value)     => s"concrete $value"
    case ResolvedVersion.DynamicLatestUrl(_) => "dynamic latest-url"

  private def renderChecksum(checksum: Option[ChecksumSpec]): String = checksum match
    case Some(value) => s"${value.algorithm.value} ${value.value}"
    case None        => "not configured"

  private def renderCreateDirectories(tool: ResolvedTool): Vector[String] =
    if tool.createDirectories.isEmpty then Vector.empty
    else
      Vector("   create directories:") ++
        tool.createDirectories.map(path => s"     ${joinPath(tool.installDir, path)}")

  private def renderStrategy(tool: ResolvedTool): Vector[String] =
    val archiveLines = tool.download.archive match
      case Some(archive) => renderArchive(archive)
      case None          => Vector("   archive: none")
    val installerLines = tool.installer match
      case Some(installer) => renderInstaller(installer)
      case None            => Vector("   installer: none")
    val directLine =
      if tool.download.archive.isEmpty && tool.installer.isEmpty then
        Vector("   strategy: direct binary download")
      else Vector.empty

    directLine ++ archiveLines ++ installerLines

  private def renderArchive(archive: ResolvedArchive): Vector[String] =
    Vector(s"   archive: ${archive.original.archiveType.value}") ++
      archive.files.map(mapping => s"     file ${mapping.from} -> ${mapping.to}") ++
      archive.directories.map(mapping => s"     directory ${mapping.from} -> ${mapping.to}")

  private def renderInstaller(installer: ResolvedInstaller): Vector[String] = Vector(
    s"   installer: ${installer.shell.value} ${installer.args.map(shellQuote).mkString(" ")}"
  ) ++
    installer.env.map(env => s"     env ${env.name}=${shellQuote(env.value)}") :+
    s"     cleanup: ${installer.cleanup}"

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
    val versions = resolveVersions(baseVars)
    val tools    = resolveTools(baseVars, versions.value)

    ResolvedValue(
      ResolvedPlan(policy.value, tools.value),
      manifestVars.errors ++ policy.errors ++ versions.errors ++ tools.errors
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

    ResolvedValue(
      ResolvedPolicy(
        appsDir.value,
        stateFile.value,
        profile.spec.policy.allowSudoSymlinks,
        RequireConfirmation.fromBoolean(profile.spec.policy.requireConfirmation),
        ContinueOnError.fromBoolean(profile.spec.policy.continueOnError)
      ),
      appsDir.errors ++ stateFile.errors
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
      ResolvedValue(ResolvedVersion.Concrete(resolved.value), errors)
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
      if resolvedUrl.errors.nonEmpty then ResolvedValue.valid("")
      else
        httpTextClient.getText(resolvedUrl.value) match
          case Right(text) => ResolvedValue.valid(text.trim)
          case Left(error) =>
            ResolvedValue.invalid("", path, s"http-text resolver failed: ${error.message}")

    ResolvedValue(
      ResolvedVersion.Concrete(fetched.value),
      resolvedUrl.errors ++ fetched.errors ++
        missingConcreteVersionErrors(fetched.value, path, name)
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
    val localVars   = vars ++ Map(
      "installDir"   -> installDir.value,
      "downloadPath" -> joinPath(installDir.value, filename.value)
    )
    val download          = resolveDownload(spec.download, specPath, localVars, version)
    val createDirectories = resolveStringVector(
      spec.createDirectories,
      s"$specPath.createDirectories",
      localVars,
      version
    )
    val installer   = resolveInstaller(spec.installer, specPath, localVars, version)
    val executables = resolveExecutables(spec, specPath, localVars, version)
    val symlinks    = resolveSymlinks(spec, specPath, localVars, version)

    ResolvedValue(
      ResolvedTool(
        name = entry.name,
        description = entry.description,
        version = version,
        installDir = installDir.value,
        createDirectories = createDirectories.value,
        download = download.value,
        installer = installer.value,
        executables = executables.value,
        symlinks = symlinks.value
      ),
      installDir.errors ++
        versionTemplateErrors(spec.installDir, s"$specPath.installDir", version) ++
        filename.errors ++
        versionTemplateErrors(spec.download.filename, s"$specPath.download.filename", version) ++
        createDirectories.errors ++ download.errors ++ installer.errors ++ executables.errors ++
        symlinks.errors
    )

  private def concreteVersionVars(version: ResolvedVersion): Map[String, String] = version match
    case ResolvedVersion.Concrete(value) if value.nonEmpty => Map("version" -> value)
    case _                                                 => Map.empty

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

    ResolvedValue(
      ResolvedDownload(url.value, filename.value, download.checksum, archive.value),
      url.errors ++ versionTemplateErrors(download.url, s"$path.url", version) ++
        filename.errors ++ versionTemplateErrors(download.filename, s"$path.filename", version) ++
        archive.errors
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
            to.errors ++ versionTemplateErrors(mapping.to, toPath, version)
        )
    ResolvedValue(resolved.map(_.value), resolved.flatMap(_.errors))

  private def resolveInstaller(
      installer: Option[InstallerSpec],
      specPath: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[Option[ResolvedInstaller]] = installer match
    case None        => ResolvedValue.valid(None)
    case Some(value) =>
      val path = s"$specPath.installer"
      val args = resolveStringVector(value.args, s"$path.args", vars, version)
      val env  = resolveInstallerEnv(value.env, path, vars, version)
      ResolvedValue(
        Some(ResolvedInstaller(value.shell, args.value, env.value, value.cleanup)),
        args.errors ++ env.errors
      )

  private def resolveInstallerEnv(
      env: Vector[InstallerEnv],
      installerPath: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[Vector[ResolvedInstallerEnv]] =
    val resolved = env.zipWithIndex.map:
      case (entry, index) =>
        val path  = s"$installerPath.env[$index].value"
        val value = interpolate(entry.value, path, vars)
        ResolvedValue(
          ResolvedInstallerEnv(entry.name, value.value),
          value.errors ++ versionTemplateErrors(entry.value, path, version)
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
          value.errors ++ versionTemplateErrors(executable.path, path, version)
        )
    ResolvedValue(resolved.map(_.value), resolved.flatMap(_.errors))

  private def resolveSymlinks(
      spec: BinaryToolSpec,
      specPath: String,
      vars: Map[String, String],
      version: ResolvedVersion
  ): ResolvedValue[Vector[ResolvedSymlink]] =
    val resolved = spec.symlinks.zipWithIndex.map:
      case (symlink, index) =>
        val pathPath   = s"$specPath.symlinks[$index].path"
        val targetPath = s"$specPath.symlinks[$index].target"
        val path       = interpolate(symlink.path, pathPath, vars)
        val target     = interpolate(symlink.target, targetPath, vars)
        ResolvedValue(
          ResolvedSymlink(path.value, target.value, symlink.privilege),
          path.errors ++ versionTemplateErrors(symlink.path, pathPath, version) ++
            target.errors ++ versionTemplateErrors(symlink.target, targetPath, version)
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
        ResolvedValue(item.value, item.errors ++ versionTemplateErrors(value, itemPath, version))
    ResolvedValue(resolved.map(_.value), resolved.flatMap(_.errors))

  private def versionTemplateErrors(
      value: String,
      path: String,
      version: ResolvedVersion
  ): Vector[ValidationError] = version match
    case ResolvedVersion.Concrete(value) if value.nonEmpty => Vector.empty
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

  private def joinPath(parent: String, child: String): String =
    if parent.endsWith("/") then s"$parent$child" else s"$parent/$child"

private object TemplateInterpolator:
  private val Variable: Regex = "\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}".r

  def interpolate(
      value: String,
      path: String,
      vars: Map[String, String]
  ): ResolvedValue[String] =
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

private final class JdkHttpTextClient(client: HttpClient) extends HttpTextClient:

  def getText(url: String): Either[HttpTextError, String] =
    val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
    Try(client.send(request, HttpResponse.BodyHandlers.ofString())) match
      case Success(response) if response.statusCode() >= 200 && response.statusCode() < 300 =>
        Right(response.body())
      case Success(response) => Left(HttpTextError(url, s"HTTP ${response.statusCode()}"))
      case Failure(error)    => Left(HttpTextError(url, error.getMessage))

private final class JdkBinaryDownloadClient(client: HttpClient) extends BinaryDownloadClient:

  def download(url: String): Either[BinaryDownloadError, Array[Byte]] =
    val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
    Try(client.send(request, HttpResponse.BodyHandlers.ofByteArray())) match
      case Success(response) if response.statusCode() >= 200 && response.statusCode() < 300 =>
        Right(response.body())
      case Success(response) => Left(BinaryDownloadError(url, s"HTTP ${response.statusCode()}"))
      case Failure(error)    => Left(BinaryDownloadError(url, error.getMessage))

private object CommandEnvironment:
  val baseline: Map[String, String] = Map("PATH" -> sys.env.getOrElse("PATH", "/usr/bin:/bin"))

private object ProcessCommandExecutor extends CommandExecutor:

  def run(spec: CommandSpec): Either[CommandExecutionError, Unit] = Try:
    val builder = ProcessBuilder(spec.argv.asJava)
    val _       = builder.directory(spec.cwd.toFile)
    val env     = builder.environment()
    env.clear()
    spec.env.foreach:
      case (name, value) => val _ = env.put(name, value)
    val process = builder.start()
    val exit    = process.waitFor()
    if exit == 0 then Right(())
    else Left(CommandExecutionError(spec, s"command exited with status $exit", Some(exit)))
  match
    case Success(result) => result
    case Failure(error)  => Left(CommandExecutionError(spec, error.getMessage, None))

private enum ArchiveEntryKind:
  case File, Directory

private final case class ArchiveEntry(name: String, kind: ArchiveEntryKind)

private final case class PlannedArchiveFile(source: String, target: Path)

private object ArchiveExtractor:

  def extract(
      archive: ResolvedArchive,
      bytes: Array[Byte],
      stagingDir: Path,
      commandExecutor: CommandExecutor
  ): Either[String, Unit] = archive.original.archiveType match
    case ArchiveType.Zip   => extractZip(archive, bytes, stagingDir)
    case ArchiveType.TarGz => extractTarGz(archive, bytes, stagingDir)
    case ArchiveType.TarXz => extractTarXz(archive, bytes, stagingDir, commandExecutor)

  private def extractZip(
      archive: ResolvedArchive,
      bytes: Array[Byte],
      stagingDir: Path
  ): Either[String, Unit] = Try(indexZip(bytes)).toEither.left.map(_.getMessage).flatMap: entries =>
    planExtraction(entries, archive, stagingDir).flatMap: plannedFiles =>
      Try:
        Using.resource(ZipInputStream(ByteArrayInputStream(bytes))): zip =>
          var entry = zip.getNextEntry
          while entry != null do
            normalizedArchivePath(entry.getName).foreach: source =>
              plannedFiles.find(_.source == source).foreach: planned =>
                copyCurrentEntry(zip, planned.target)
            zip.closeEntry()
            entry = zip.getNextEntry
      match
        case Success(_)     => Right(())
        case Failure(error) => Left(error.getMessage)

  private def indexZip(bytes: Array[Byte]): Vector[ArchiveEntry] =
    Using.resource(ZipInputStream(ByteArrayInputStream(bytes))): zip =>
      Iterator
        .continually(zip.getNextEntry)
        .takeWhile(_ != null)
        .map: entry =>
          val source = normalizedArchivePath(entry.getName).fold(
            message => throw IllegalArgumentException(message),
            identity
          )
          val kind = if entry.isDirectory then ArchiveEntryKind.Directory else ArchiveEntryKind.File
          ArchiveEntry(source, kind)
        .toVector

  private def extractTarGz(
      archive: ResolvedArchive,
      bytes: Array[Byte],
      stagingDir: Path
  ): Either[String, Unit] = Try(indexTarGz(bytes)).toEither.left.map(_.getMessage).flatMap:
    entries =>
      planExtraction(entries, archive, stagingDir).flatMap: plannedFiles =>
        val plannedBySource = plannedFiles.map(file => file.source -> file.target).toMap
        Try:
          Using.resource(GZIPInputStream(ByteArrayInputStream(bytes))): input =>
            readTarEntries(input): (entry, content) =>
              plannedBySource.get(entry.name).foreach: target =>
                copyBounded(content, target, entry.size)
              if !plannedBySource.contains(entry.name) then
                val _ = skipFully(content, entry.size)
        match
          case Success(_)     => Right(())
          case Failure(error) => Left(error.getMessage)

  private def indexTarGz(bytes: Array[Byte]): Vector[ArchiveEntry] =
    Using.resource(GZIPInputStream(ByteArrayInputStream(bytes))): input =>
      val entries = Vector.newBuilder[ArchiveEntry]
      readTarEntries(input): (entry, content) =>
        val _ = skipFully(content, entry.size)
        entries += ArchiveEntry(entry.name, entry.kind)
      entries.result()

  private def extractTarXz(
      archive: ResolvedArchive,
      bytes: Array[Byte],
      stagingDir: Path,
      commandExecutor: CommandExecutor
  ): Either[String, Unit] =
    val archiveFile = Files.createTempFile(stagingDir, ".archive-", ".tar.xz")
    val extractDir  = Files.createTempDirectory(stagingDir, ".archive-extract-")
    Files.write(archiveFile, bytes)
    val spec = CommandSpec(
      Vector("tar", "-xJf", archiveFile.toString, "-C", extractDir.toString),
      stagingDir,
      CommandEnvironment.baseline
    )
    commandExecutor.run(spec) match
      case Left(error) =>
        deleteRecursively(extractDir)
        val _ = Files.deleteIfExists(archiveFile)
        Left(s"${error.spec.argv.mkString(" ")}: ${error.message}")
      case Right(()) =>
        val result = Try(indexExtractedDirectory(extractDir)).toEither.left.map(_.getMessage)
          .flatMap: entries =>
            planExtraction(entries, archive, stagingDir).flatMap: plannedFiles =>
              Try:
                plannedFiles.foreach: planned =>
                  val source = extractDir.resolve(planned.source).normalize()
                  copyFile(source, planned.target)
              match
                case Success(_)     => Right(())
                case Failure(error) => Left(error.getMessage)
        deleteRecursively(extractDir)
        val _ = Files.deleteIfExists(archiveFile)
        result

  private def indexExtractedDirectory(root: Path): Vector[ArchiveEntry] =
    Using.resource(Files.walk(root)): stream =>
      stream
        .iterator()
        .asScala
        .toVector
        .filterNot(_ == root)
        .map: path =>
          val relative = root.relativize(path).toString.replace('\\', '/')
          val source   = normalizedArchivePath(relative).fold(
            message => throw IllegalArgumentException(message),
            identity
          )
          val kind =
            if Files.isDirectory(path) then ArchiveEntryKind.Directory else ArchiveEntryKind.File
          ArchiveEntry(source, kind)

  private def planExtraction(
      entries: Vector[ArchiveEntry],
      archive: ResolvedArchive,
      stagingDir: Path
  ): Either[String, Vector[PlannedArchiveFile]] =
    val fileMappings      = archive.files.map(planFileMapping(entries, stagingDir, _))
    val directoryMappings = archive.directories.map(planDirectoryMapping(entries, stagingDir, _))
    val planned           = (fileMappings ++ directoryMappings).foldLeft(
      Right(Vector.empty): Either[String, Vector[PlannedArchiveFile]]
    ): (acc, next) =>
      for
        current <- acc
        files   <- next
      yield current ++ files

    planned.flatMap(rejectDuplicateTargets)

  private def planFileMapping(
      entries: Vector[ArchiveEntry],
      stagingDir: Path,
      mapping: ResolvedExtractMapping
  ): Either[String, Vector[PlannedArchiveFile]] =
    for
      source <- normalizedArchivePath(mapping.from)
      target <- resolveInside(stagingDir, mapping.to)
      _      <- entries.find(entry => entry.name == source && entry.kind == ArchiveEntryKind.File)
        .toRight(s"archive member not found: ${mapping.from}")
    yield Vector(PlannedArchiveFile(source, target))

  private def planDirectoryMapping(
      entries: Vector[ArchiveEntry],
      stagingDir: Path,
      mapping: ResolvedExtractMapping
  ): Either[String, Vector[PlannedArchiveFile]] =
    normalizedArchivePath(mapping.from).flatMap: source =>
      val prefix = s"$source/"
      val files  = entries.filter: entry =>
        entry.kind == ArchiveEntryKind.File && entry.name.startsWith(prefix)
      if files.isEmpty then Left(s"archive directory not found: ${mapping.from}")
      else
        val planned = files.map: entry =>
          val relative = entry.name.stripPrefix(prefix)
          val target   = joinArchivePath(mapping.to, relative)
          resolveInside(stagingDir, target).map: targetPath =>
            PlannedArchiveFile(entry.name, targetPath)
        collectEither(planned)

  private def rejectDuplicateTargets(
      plannedFiles: Vector[PlannedArchiveFile]
  ): Either[String, Vector[PlannedArchiveFile]] =
    val duplicate = plannedFiles
      .groupBy(_.target)
      .collectFirst:
        case (target, files) if files.size > 1 => target
    duplicate match
      case Some(target) => Left(s"multiple archive members map to $target")
      case None         => Right(plannedFiles)

  private final case class TarEntry(name: String, kind: ArchiveEntryKind, size: Long)

  private def readTarEntries(input: InputStream)(handle: (TarEntry, InputStream) => Unit): Unit =
    var header = readTarBlock(input)
    while header.exists(!_.forall(_ == 0.toByte)) do
      val current = header.get
      val entry   = tarEntry(current)
      handle(entry, input)
      val padding = tarPadding(entry.size)
      val _       = skipFully(input, padding)
      header = readTarBlock(input)

  private def tarEntry(header: Array[Byte]): TarEntry =
    val name     = tarString(header, 0, 100)
    val prefix   = tarString(header, 345, 155)
    val fullName = if prefix.isEmpty then name else s"$prefix/$name"
    val source   = normalizedArchivePath(fullName).fold(
      message => throw IllegalArgumentException(message),
      identity
    )
    val size = tarOctal(header, 124, 12)
    val kind = header(156).toChar match
      case 0 | '0'   => ArchiveEntryKind.File
      case '5'       => ArchiveEntryKind.Directory
      case '1' | '2' => throw IllegalArgumentException(s"unsafe archive link entry: $source")
      case other => throw IllegalArgumentException(s"unsupported tar entry type '$other': $source")
    TarEntry(source, kind, size)

  private def readTarBlock(input: InputStream): Option[Array[Byte]] =
    val buffer = Array.ofDim[Byte](512)
    var offset = 0
    while offset < buffer.length do
      val count = input.read(buffer, offset, buffer.length - offset)
      if count == -1 then return if offset == 0 then None else Some(buffer)
      offset = offset + count
    Some(buffer)

  private def tarString(header: Array[Byte], offset: Int, length: Int): String =
    val bytes = header.slice(offset, offset + length).takeWhile(_ != 0.toByte)
    new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim

  private def tarOctal(header: Array[Byte], offset: Int, length: Int): Long =
    val value = tarString(header, offset, length).trim
    if value.isEmpty then 0L else java.lang.Long.parseLong(value, 8)

  private def tarPadding(size: Long): Long =
    val remainder = size % 512L
    if remainder == 0L then 0L else 512L - remainder

  private def normalizedArchivePath(value: String): Either[String, String] =
    val path = value.stripSuffix("/")
    if path.isEmpty then Left("archive path must not be empty")
    else if path.exists(_ < ' ') then Left(s"archive path contains control character: $value")
    else if path.contains('\\') then Left(s"archive path contains backslash: $value")
    else if path.matches("^[A-Za-z]:.*") then Left(s"archive path is drive-prefixed: $value")
    else
      val nioPath = Path.of(path)
      if nioPath.isAbsolute then Left(s"archive path is absolute: $value")
      else
        val segments = path.split('/').toVector
        val unsafe   = segments.exists(segment => segment == "." || segment == "..")
        if unsafe then Left(s"archive path escapes staging directory: $value")
        else Right(path)

  private def resolveInside(root: Path, relative: String): Either[String, Path] =
    val clean = relative match
      case "" | "." => "."
      case other    => other
    validateRelativeTarget(clean).flatMap: _ =>
      val input = Path.of(clean)
      if input.isAbsolute then Left(s"path must be relative: $relative")
      else
        val normalizedRoot = root.toAbsolutePath.normalize()
        val resolved       = normalizedRoot.resolve(input).normalize()
        if resolved.startsWith(normalizedRoot) then Right(resolved)
        else Left(s"path escapes staging directory: $relative")

  private def validateRelativeTarget(value: String): Either[String, Unit] =
    if value == "." then Right(())
    else normalizedArchivePath(value).map(_ => ())

  private def collectEither[A](values: Vector[Either[String, A]]): Either[String, Vector[A]] =
    values.foldLeft(Right(Vector.empty): Either[String, Vector[A]]): (acc, next) =>
      for
        current <- acc
        value   <- next
      yield current :+ value

  private def joinArchivePath(parent: String, child: String): String = parent match
    case "" | "."                     => child
    case value if value.endsWith("/") => s"$value$child"
    case value                        => s"$value/$child"

  private def copyCurrentEntry(input: InputStream, target: Path): Unit =
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))
    val _ = Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)

  private def copyBounded(input: InputStream, target: Path, bytes: Long): Unit =
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))
    Using.resource(Files.newOutputStream(
      target,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    )): output =>
      val buffer    = Array.ofDim[Byte](8192)
      var remaining = bytes
      while remaining > 0 do
        val count = input.read(buffer, 0, math.min(buffer.length.toLong, remaining).toInt)
        if count == -1 then throw IllegalArgumentException("unexpected end of tar entry")
        output.write(buffer, 0, count)
        remaining = remaining - count

  private def copyFile(source: Path, target: Path): Unit =
    Option(target.getParent).foreach(parent => Files.createDirectories(parent))
    val _ = Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)

  private def skipFully(input: InputStream, bytes: Long): Long =
    var remaining = bytes
    while remaining > 0 do
      val skipped = input.skip(remaining)
      if skipped <= 0 then
        if input.read() == -1 then return remaining
        else remaining = remaining - 1
      else remaining = remaining - skipped
    0L

  private def deleteRecursively(path: Path): Unit = if Files.exists(path) then
    Using.resource(Files.walk(path)): stream =>
      stream
        .iterator()
        .asScala
        .toVector
        .sortBy(_.getNameCount)
        .reverse
        .foreach(child => Try(Files.deleteIfExists(child)))

private object Sha256:

  def digest(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    digest.map(byte => f"${byte & 0xff}%02x").mkString

private object NioInstallFileSystem extends InstallFileSystem:

  def stageDirectBinary(
      installDir: Path,
      createDirectories: Vector[String],
      executablePath: String,
      bytes: Array[Byte]
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall] =
    val normalizedInstallDir = installDir.toAbsolutePath.normalize()
    createStagingDirectory(normalizedInstallDir).flatMap: stagedInstall =>
      writeStagedDirectBinary(stagedInstall, createDirectories, executablePath, bytes) match
        case Right(())   => Right(stagedInstall)
        case Left(error) =>
          discardStaged(stagedInstall)
          Left(error)

  def stageArchive(
      installDir: Path,
      createDirectories: Vector[String],
      archive: ResolvedArchive,
      bytes: Array[Byte],
      commandExecutor: CommandExecutor
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall] =
    val normalizedInstallDir = installDir.toAbsolutePath.normalize()
    createStagingDirectory(normalizedInstallDir).flatMap: stagedInstall =>
      val result: Either[InstallFileSystemError.StagingFailed, Unit] =
        stageCreateDirectories(stagedInstall, createDirectories) match
          case Left(error) => Left(error)
          case Right(())   =>
            ArchiveExtractor.extract(archive, bytes, stagedInstall.stagingDir, commandExecutor)
              .left
              .map(message => InstallFileSystemError.StagingFailed(message))
      result match
        case Right(())   => Right(stagedInstall)
        case Left(error) =>
          discardStaged(stagedInstall)
          Left(error)

  def applyExecutableModes(
      stagedInstall: StagedInstall,
      executables: Vector[ExecutableModeRequest]
  ): Either[InstallFileSystemError.ModeApplicationFailed, Unit] =
    val errors: Vector[Either[InstallFileSystemError.ModeApplicationFailed, Unit]] =
      executables.map(applyExecutableMode(stagedInstall, _))

    errors.collectFirst:
      case Left(error) => error
    match
      case Some(error) =>
        discardStaged(stagedInstall)
        Left(error)
      case None => Right(())

  def replaceInstall(
      stagedInstall: StagedInstall
  ): Either[InstallFileSystemError.ReplacementFailed, Unit] =
    replaceInstallDirectory(stagedInstall) match
      case Right(())   => Right(())
      case Left(error) =>
        discardStaged(stagedInstall)
        Left(error)

  def discardStaged(stagedInstall: StagedInstall): Unit =
    deleteRecursively(stagedInstall.stagingDir)

  private def createStagingDirectory(
      installDir: Path
  ): Either[InstallFileSystemError.StagingFailed, StagedInstall] = Try:
    val parent = Option(installDir.getParent).getOrElse(Path.of("").toAbsolutePath.normalize())
    val _      = Files.createDirectories(parent)
    val prefix = s".${Option(installDir.getFileName).map(_.toString).getOrElse("install")}.stage-"
    StagedInstall(Files.createTempDirectory(parent, prefix), installDir)
  match
    case Success(stagedInstall) => Right(stagedInstall)
    case Failure(error)         => Left(InstallFileSystemError.StagingFailed(error.getMessage))

  private def writeStagedDirectBinary(
      stagedInstall: StagedInstall,
      createDirectories: Vector[String],
      executablePath: String,
      bytes: Array[Byte]
  ): Either[InstallFileSystemError.StagingFailed, Unit] =
    stageCreateDirectories(stagedInstall, createDirectories).flatMap: _ =>
      val binaryWrite: Either[String, Unit] =
        resolveInside(stagedInstall.stagingDir, executablePath).flatMap: path =>
          writeBinary(path, bytes)

      binaryWrite.left.map(InstallFileSystemError.StagingFailed.apply)

  private def stageCreateDirectories(
      stagedInstall: StagedInstall,
      createDirectories: Vector[String]
  ): Either[InstallFileSystemError.StagingFailed, Unit] =
    val directoryWrites: Vector[Either[String, Unit]] = createDirectories.map: directory =>
      resolveInside(stagedInstall.stagingDir, directory).flatMap: path =>
        Try(Files.createDirectories(path)) match
          case Success(_)     => Right(())
          case Failure(error) => Left(error.getMessage)

    val failures = directoryWrites.flatMap(stagingFailure)

    failures.headOption match
      case Some(error) => Left(error)
      case None        => Right(())

  private def stagingFailure(
      result: Either[String, Unit]
  ): Option[InstallFileSystemError.StagingFailed] = result match
    case Left(message) => Some(InstallFileSystemError.StagingFailed(message))
    case Right(())     => None

  private def applyExecutableMode(
      stagedInstall: StagedInstall,
      executable: ExecutableModeRequest
  ): Either[InstallFileSystemError.ModeApplicationFailed, Unit] =
    val executablePath = stagedInstall.stagingDir.resolve(executable.path).normalize()
    if !executablePath.startsWith(stagedInstall.stagingDir) then
      Left(
        InstallFileSystemError.ModeApplicationFailed(
          executable.path,
          executable.mode.octal,
          "path escapes staging directory"
        )
      )
    else
      Try(
        Files.setPosixFilePermissions(executablePath, executable.mode.permissions.asJava)
      ) match
        case Success(_)     => Right(())
        case Failure(error) => Left(
            InstallFileSystemError.ModeApplicationFailed(
              executable.path,
              executable.mode.octal,
              error.getMessage
            )
          )

  private def writeBinary(path: Path, bytes: Array[Byte]): Either[String, Unit] = Try:
    Option(path.getParent).foreach: parent =>
      Files.createDirectories(parent)
    val _ = Files.write(
      path,
      bytes,
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.WRITE
    )
  match
    case Success(_)     => Right(())
    case Failure(error) => Left(error.getMessage)

  private def resolveInside(root: Path, relative: String): Either[String, Path] =
    val input = Path.of(relative)
    if input.isAbsolute then Left(s"path must be relative: $relative")
    else
      val resolved = root.resolve(input).normalize()
      if resolved.startsWith(root) then Right(resolved)
      else Left(s"path escapes staging directory: $relative")

  private def replaceInstallDirectory(
      stagedInstall: StagedInstall
  ): Either[InstallFileSystemError.ReplacementFailed, Unit] =
    val installDir = stagedInstall.installDir
    val parent     = Option(installDir.getParent).getOrElse(Path.of("").toAbsolutePath.normalize())
    val backupPrefix =
      s".${Option(installDir.getFileName).map(_.toString).getOrElse("install")}.backup-"

    Try:
      val backupDir = Files.createTempDirectory(parent, backupPrefix)
      Files.delete(backupDir)
      if Files.exists(installDir) then
        val _ = Files.move(installDir, backupDir, StandardCopyOption.REPLACE_EXISTING)
      val _ = Files.move(stagedInstall.stagingDir, installDir, StandardCopyOption.REPLACE_EXISTING)
      deleteRecursively(backupDir)
    match
      case Success(_)     => Right(())
      case Failure(error) => Left(InstallFileSystemError.ReplacementFailed(error.getMessage))

  private def deleteRecursively(path: Path): Unit = if Files.exists(path) then
    Using.resource(Files.walk(path)): stream =>
      stream
        .iterator()
        .asScala
        .toVector
        .sortBy(_.getNameCount)
        .reverse
        .foreach(child => Try(Files.deleteIfExists(child)))
