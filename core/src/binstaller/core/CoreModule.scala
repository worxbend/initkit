package binstaller.core

import binstaller.config.AllowSudoSymlinks
import binstaller.config.BinaryDistributionProfile
import binstaller.config.BinaryToolSpec
import binstaller.config.ConfigModule
import binstaller.config.DownloadSpec
import binstaller.config.ExtractMapping
import binstaller.config.PlanEntry
import binstaller.config.PolicyOverride
import binstaller.config.SymlinkPrivilege
import binstaller.config.VersionSource

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/** Public module metadata for core planning and apply behavior. */
object CoreModule:
  /** Module path used by downstream modules to report dependency lineage. */
  def modulePath: Vector[String] = Vector(ConfigModule.moduleName, "core")

/** Whether apply should ignore a saved execution state file. */
enum ResetState:
  case Enabled, Disabled

/** Helpers for converting CLI flags into reset-state policy. */
object ResetState:
  /** Convert a boolean CLI flag into [[ResetState]]. */
  def fromFlag(value: Boolean): ResetState = if value then Enabled else Disabled

/** Whether command diagnostics and detailed operation lines should be emitted. */
enum VerboseOutput:
  case Enabled, Disabled

/** Helpers for converting CLI flags into verbose-output policy. */
object VerboseOutput:
  /** Convert a boolean CLI flag into [[VerboseOutput]]. */
  def fromFlag(value: Boolean): VerboseOutput = if value then Enabled else Disabled

/** Runtime options shared by plan, apply, versions, and lock entrypoints. */
final case class InstallerOptions(
    configPath: String,
    statePath: Option[String],
    resetState: ResetState,
    verboseOutput: VerboseOutput,
    selection: ToolSelection = ToolSelection.all,
    applyConfirmation: ApplyConfirmation = ApplyConfirmation.Disabled,
    lockPath: String = LockOptions.defaultOutputPath,
    lockedApply: LockedApplyMode = LockedApplyMode.Disabled
)

/** Rendered command result and process exit code. */
final case class InstallerResult(lines: Vector[String], exitCode: Int)

/** Tool selection requested by `--only` and `--skip`. */
final case class ToolSelection(only: Vector[String], skip: Vector[String])

/** Tool-selection constructors. */
object ToolSelection:
  /** Select every resolved tool. */
  def all: ToolSelection = ToolSelection(Vector.empty, Vector.empty)

/** Whether the user confirmed apply side effects. */
enum ApplyConfirmation:
  case Enabled, Disabled

/** Helpers for converting CLI flags into apply confirmation. */
object ApplyConfirmation:
  /** Convert a boolean CLI flag into [[ApplyConfirmation]]. */
  def fromFlag(value: Boolean): ApplyConfirmation = if value then Enabled else Disabled

/** Boundary service consumed by CLI commands and tests. */
trait BinaryInstallerService:

  /** Render a script-friendly install plan without events. */
  def plan(options: InstallerOptions): InstallerResult =
    planWithEvents(options, InstallerEventObserver.none)

  /** Render a plan while emitting renderer-agnostic lifecycle events. */
  def planWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult

  /** Apply a plan without progress or lifecycle observers. */
  def apply(options: InstallerOptions): InstallerResult =
    applyWithEvents(options, InstallerEventObserver.none)

  /** Apply a plan while adapting download-only progress observers. */
  def applyWithProgress(
      options: InstallerOptions,
      progressObserver: BinaryDownloadProgressObserver
  ): InstallerResult = applyWithEvents(
    options,
    InstallerEventObserver.fromDownloadProgress(progressObserver)
  )

  /** Apply a plan while emitting renderer-agnostic lifecycle events. */
  def applyWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult

  /** Resolve and render the configured version sources. */
  def versions(options: InstallerOptions): InstallerResult

  /** Resolve and write a reproducible lock file without applying tools. */
  def lock(options: InstallerOptions, lockOptions: LockOptions): InstallerResult

/** Constructors for production and test service implementations. */
object BinaryInstallerService:
  /** Minimal placeholder used by early wiring tests. */
  def placeholder: BinaryInstallerService = PlaceholderBinaryInstallerService

  /** Create the production resolving service with the default installer and cwd state store. */
  def resolving(httpTextClient: HttpTextClient): BinaryInstallerService =
    resolving(httpTextClient, DirectBinaryInstaller.default)

  /** Create the production resolving service with explicit sudo credential handling. */
  def resolving(
      httpTextClient: HttpTextClient,
      sudoCredentials: SudoCredentialProvider
  ): BinaryInstallerService =
    resolving(httpTextClient, DirectBinaryInstaller.default(sudoCredentials))

  /** Create a resolving service with an injected installer and the default cwd state store. */
  def resolving(
      httpTextClient: HttpTextClient,
      installer: DirectBinaryInstaller
  ): BinaryInstallerService = ResolvingBinaryInstallerService(
    httpTextClient,
    ResolutionOptions.fromEnvironment(),
    installer,
    ApplyStateStore.cwd,
    BinaryMetadataClient.jdk,
    LockFileStore.nio
  )

  /** Create a resolving service with injectable installer and state storage boundaries. */
  def resolving(
      httpTextClient: HttpTextClient,
      installer: DirectBinaryInstaller,
      stateStore: ApplyStateStore
  ): BinaryInstallerService = ResolvingBinaryInstallerService(
    httpTextClient,
    ResolutionOptions.fromEnvironment(),
    installer,
    stateStore,
    BinaryMetadataClient.jdk,
    LockFileStore.nio
  )

  /** Create a resolving service with injectable installer, state, and lock metadata boundaries. */
  def resolving(
      httpTextClient: HttpTextClient,
      installer: DirectBinaryInstaller,
      stateStore: ApplyStateStore,
      metadataClient: BinaryMetadataClient,
      lockFileStore: LockFileStore
  ): BinaryInstallerService = ResolvingBinaryInstallerService(
    httpTextClient,
    ResolutionOptions.fromEnvironment(),
    installer,
    stateStore,
    metadataClient,
    lockFileStore
  )

private final case class ObservedInstallResults(
    lines: Vector[String],
    results: Vector[TerminalToolResult],
    persistenceError: Option[String]
)

/** Installer that applies resolved direct-binary and archive-backed tools. */
final class DirectBinaryInstaller(
    downloadClient: BinaryDownloadClient,
    fileSystem: InstallFileSystem,
    commandExecutor: CommandExecutor = CommandExecutor.process,
    sudoCredentials: SudoCredentialProvider = SudoCredentialProvider.unavailable
):

  /** Install every tool in a plan and render terminal result lines. */
  def installPlan(
      plan: ResolvedPlan,
      applyConfirmation: ApplyConfirmation = ApplyConfirmation.Disabled,
      verboseOutput: VerboseOutput = VerboseOutput.Disabled,
      progressObserver: BinaryDownloadProgressObserver = BinaryDownloadProgressObserver.none
  ): InstallerResult = installPlanWithObserver(
    plan,
    applyConfirmation,
    verboseOutput,
    _ => Right(()),
    InstallerEventContext.start(InstallerEventObserver.fromDownloadProgress(progressObserver))
  )

  private[core] def installPlanWithObserver(
      plan: ResolvedPlan,
      applyConfirmation: ApplyConfirmation,
      verboseOutput: VerboseOutput,
      terminalObserver: TerminalToolResult => Either[String, Unit],
      eventContext: InstallerEventContext
  ): InstallerResult = preflight(plan, applyConfirmation) match
    case Some(error) => InstallerResult(Vector(ApplyPreflightError.render(error)), 1)
    case None        =>
      val observed = installTools(
        plan.policy,
        plan.tools,
        plan.redactions,
        verboseOutput,
        terminalObserver,
        eventContext
      )
      val lines = observed.lines ++
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
      redactions: SensitiveValueRedactions,
      verboseOutput: VerboseOutput,
      terminalObserver: TerminalToolResult => Either[String, Unit],
      eventContext: InstallerEventContext
  ): ObservedInstallResults = tools.headOption match
    case None       => ObservedInstallResults(Vector.empty, Vector.empty, None)
    case Some(tool) =>
      val verbose = verboseLines(tool, verboseOutput, redactions)
      verbose.foreach(line =>
        eventContext.emit(InstallerEvent.LogLine(Some(tool.name), line, _))
      )
      eventContext.emit(InstallerEvent.ToolStarted(tool.name, InstallerPhase.Downloading, _))
      val result   = installTool(policy, tool, eventContext, redactions)
      val terminal = terminalResult(result, redactions)
      eventContext.emit(toolResultEvent(terminal))
      val terminalLines = renderedTerminalLines(terminal, redactions)
      terminalObserver(terminal) match
        case Left(message) => ObservedInstallResults(
            verbose ++ terminalLines,
            Vector(terminal),
            Some(RenderSafety.display(message, redactions))
          )
        case Right(()) => result match
            case Left(_) if policy.continueOnError == ContinueOnError.Disabled =>
              ObservedInstallResults(
                verbose ++ terminalLines,
                Vector(terminal),
                None
              )
            case Left(_) =>
              val rest = installTools(
                policy,
                tools.tail,
                redactions,
                verboseOutput,
                terminalObserver,
                eventContext
              )
              rest.copy(
                lines = verbose ++
                  (terminalLines ++ rest.lines),
                results = terminal +: rest.results
              )
            case Right(_) =>
              val rest = installTools(
                policy,
                tools.tail,
                redactions,
                verboseOutput,
                terminalObserver,
                eventContext
              )
              rest.copy(
                lines = verbose ++
                  (terminalLines ++ rest.lines),
                results = terminal +: rest.results
              )

  private def renderedTerminalLines(
      terminal: TerminalToolResult,
      redactions: SensitiveValueRedactions
  ): Vector[String] = terminal match
    case TerminalToolResult.Completed(_, _, provenance) =>
      UrlProvenance.redirectDetailLines("download", provenance, redactions) :+
        TerminalToolResult.line(terminal, redactions)
    case TerminalToolResult.Failed(_, _) => Vector(TerminalToolResult.line(terminal, redactions))

  /** Install a single tool without sudo symlink support. Intended for focused tests and helpers. */
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
    else
      installTool(
        policy,
        tool,
        InstallerEventContext.start(InstallerEventObserver.none),
        SensitiveValueRedactions.empty
      )

  private def installTool(
      policy: ResolvedPolicy,
      tool: ResolvedTool,
      eventContext: InstallerEventContext,
      redactions: SensitiveValueRedactions
  ): Either[ToolInstallError, ToolInstallSuccess] =
    installWithoutPreflight(policy, tool, eventContext, redactions)

  private def terminalResult(
      result: Either[ToolInstallError, ToolInstallSuccess],
      redactions: SensitiveValueRedactions
  ): TerminalToolResult = result match
    case Right(success) =>
      TerminalToolResult.Completed(success.toolName, success.installDir, success.download)
    case Left(error) => TerminalToolResult.Failed(
        ToolInstallError.toolName(error),
        ToolInstallError.render(error, redactions)
      )

  private def toolResultEvent(
      result: TerminalToolResult
  )(elapsedTime: Duration): InstallerEvent = result match
    case TerminalToolResult.Completed(toolName, installDir, _) => InstallerEvent.ToolResult(
        toolName,
        ToolResultStatus.Completed,
        Some(installDir),
        None,
        elapsedTime
      )
    case TerminalToolResult.Failed(toolName, message) => InstallerEvent.ToolResult(
        toolName,
        ToolResultStatus.Failed,
        None,
        Some(rootCauseSummary(message)),
        elapsedTime
      )

  private def rootCauseSummary(message: String): String =
    message.linesIterator.nextOption.getOrElse(message)

  private def verboseLines(
      tool: ResolvedTool,
      verboseOutput: VerboseOutput,
      redactions: SensitiveValueRedactions
  ): Vector[String] = verboseOutput match
    case VerboseOutput.Disabled => Vector.empty
    case VerboseOutput.Enabled  =>
      val downloadLine   = s"verbose ${tool.name}: download ${tool.download.url}"
      val extractionLine = tool.download.archive match
        case Some(archive) => Some(
            s"verbose ${tool.name}: extract ${archive.original.archiveType.value} ${tool.download.filename}"
          )
        case None => None
      RenderSafety.displayLines(Vector(downloadLine) ++ extractionLine.toVector, redactions)

  private def installWithoutPreflight(
      policy: ResolvedPolicy,
      tool: ResolvedTool,
      eventContext: InstallerEventContext,
      redactions: SensitiveValueRedactions
  ): Either[ToolInstallError, ToolInstallSuccess] =
    installDownloadedBinaryOrArchive(policy, tool, eventContext, redactions)

  private def installDownloadedBinaryOrArchive(
      policy: ResolvedPolicy,
      tool: ResolvedTool,
      eventContext: InstallerEventContext,
      redactions: SensitiveValueRedactions
  ): Either[ToolInstallError, ToolInstallSuccess] =
    for
      downloadResult <- download(tool, eventContext, redactions)
      bytes = downloadResult.bytes
      // Integrity is checked before staging/replacement so a bad artifact cannot overwrite a
      // previously working install.
      _ <-
        withPhase(tool, InstallerPhase.VerifyingChecksum, eventContext)(verifyChecksum(tool, bytes))
      staged <- withPhase(tool, InstallerPhase.Staging, eventContext)(stage(tool, bytes))
      _      <- withPhase(tool, InstallerPhase.VerifyingExecutables, eventContext)(
        verifyStagedExecutables(tool, staged)
      )
      _ <- withPhase(tool, InstallerPhase.ApplyingModes, eventContext)(applyModes(tool, staged))
      _ <- withPhase(tool, InstallerPhase.ReplacingInstall, eventContext)(replace(tool, staged))
      _ <- withPhase(tool, InstallerPhase.VerifyingExecutables, eventContext)(
        verifyExecutables(tool)
      )
      _ <- withPhase(tool, InstallerPhase.CreatingSymlinks, eventContext)(
        SymlinkInstaller.create(policy, tool, commandExecutor, sudoCredentials)
      )
    yield ToolInstallSuccess(tool.name, tool.installDir, Some(downloadResult.provenance))

  private def download(
      tool: ResolvedTool,
      eventContext: InstallerEventContext,
      redactions: SensitiveValueRedactions
  ): Either[ToolInstallError, BinaryDownloadResult] = downloadClient.downloadWithProvenance(
    tool.download.url,
    downloadProgressObserver(tool, eventContext, redactions)
  ).left.map: error =>
    ToolInstallError.DownloadFailed(
      tool.name,
      RenderSafety.display(error.url, redactions),
      RenderSafety.display(error.message, redactions),
      error.provenance
    )

  private def downloadProgressObserver(
      tool: ResolvedTool,
      eventContext: InstallerEventContext,
      redactions: SensitiveValueRedactions
  ): BinaryDownloadProgressObserver = new BinaryDownloadProgressObserver:
    def onProgress(progress: BinaryDownloadProgress): Unit = progress match
      case BinaryDownloadProgress.Started(url, totalBytes) =>
        eventContext.emit(InstallerEvent.DownloadProgress(
          tool.name,
          RenderSafety.display(url, redactions),
          0L,
          totalBytes,
          DownloadProgressStatus.Started,
          _
        ))
      case BinaryDownloadProgress.Advanced(url, downloadedBytes, totalBytes) =>
        eventContext.emit(InstallerEvent.DownloadProgress(
          tool.name,
          RenderSafety.display(url, redactions),
          downloadedBytes,
          totalBytes,
          DownloadProgressStatus.Advanced,
          _
        ))
      case BinaryDownloadProgress.Finished(url, downloadedBytes, totalBytes) =>
        eventContext.emit(InstallerEvent.DownloadProgress(
          tool.name,
          RenderSafety.display(url, redactions),
          downloadedBytes,
          totalBytes,
          DownloadProgressStatus.Finished,
          _
        ))

  private def withPhase[A](
      tool: ResolvedTool,
      phase: InstallerPhase,
      eventContext: InstallerEventContext
  )(result: => Either[ToolInstallError, A]): Either[ToolInstallError, A] =
    eventContext.emit(InstallerEvent.ToolPhaseChanged(tool.name, phase, _))
    result

  private def verifyChecksum(
      tool: ResolvedTool,
      bytes: Array[Byte]
  ): Either[ToolInstallError, Unit] = tool.download.checksum match
    case None           => Right(())
    case Some(checksum) =>
      val actual = Sha256.digest(bytes)
      if actual.equalsIgnoreCase(checksum.value) then Right(())
      else
        Left(ToolInstallError.ChecksumMismatch(
          tool.name,
          checksum.value,
          actual,
          ResolvedChecksum.sourceDescription(checksum)
        ))

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

  private def verifyStagedExecutables(
      tool: ResolvedTool,
      stagedInstall: StagedInstall
  ): Either[ToolInstallError, Unit] = tool.executables
    .map: executable =>
      resolveInsideStaging(tool, stagedInstall, executable.path).flatMap: path =>
        if Files.isRegularFile(path) then Right(())
        else Left(ToolInstallError.MissingExecutable(tool.name, executable.path))
    .collectFirst:
      case Left(error) => error
  match
    case Some(error) => Left(error)
    case None        => Right(())

  private def resolveInsideStaging(
      tool: ResolvedTool,
      stagedInstall: StagedInstall,
      relative: String
  ): Either[ToolInstallError, Path] =
    val input      = Path.of(relative)
    val stagingDir = stagedInstall.stagingDir.toAbsolutePath.normalize()
    if input.isAbsolute then
      Left(ToolInstallError.StagingFailed(tool.name, s"path must be relative: $relative"))
    else
      val resolved = stagingDir.resolve(input).normalize()
      if resolved.startsWith(stagingDir) then Right(resolved)
      else Left(ToolInstallError.StagingFailed(tool.name, s"path escapes installDir: $relative"))

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

/** Constructors for the production binary installer. */
object DirectBinaryInstaller:

  /** Production installer wired to JDK downloads, NIO staging, and bounded process execution. */
  def default: DirectBinaryInstaller =
    DirectBinaryInstaller(BinaryDownloadClient.jdk, NioInstallFileSystem, CommandExecutor.process)

  /** Production installer wired with an explicit sudo credential boundary. */
  def default(sudoCredentials: SudoCredentialProvider): DirectBinaryInstaller =
    DirectBinaryInstaller(
      BinaryDownloadClient.jdk,
      NioInstallFileSystem,
      CommandExecutor.process,
      sudoCredentials
    )

private final case class PreparedPlan(
    profile: BinaryDistributionProfile,
    profileName: String,
    manifestFingerprint: String,
    plan: ResolvedPlan
)

private final case class VersionSummaryRow(
    packageName: String,
    version: String,
    newerVersion: String
)

private object StatefulApplyRunner:

  def run(
      options: InstallerOptions,
      prepared: PreparedPlan,
      installer: DirectBinaryInstaller,
      stateStore: ApplyStateStore,
      eventContext: InstallerEventContext
  ): InstallerResult = confirmationPreflight(options, prepared.plan) match
    case Some(result) => result
    case None => runAfterConfirmation(options, prepared, installer, stateStore, eventContext)

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
      stateStore: ApplyStateStore,
      eventContext: InstallerEventContext
  ): InstallerResult = statePath(options, prepared.plan) match
    case None => installer.installPlanWithObserver(
        prepared.plan,
        options.applyConfirmation,
        options.verboseOutput,
        _ => Right(()),
        eventContext
      )
    case Some(path) =>
      eventContext.emit(InstallerEvent.LogLine(
        None,
        RenderSafety.display(s"state file: $path", prepared.plan.redactions),
        _
      ))
      eventContext.emit(InstallerEvent.ToolPhaseChanged("state", InstallerPhase.LoadingState, _))
      loadInitialState(path, options.resetState, prepared, stateStore) match
        case Left(error) => InstallerResult(
            Vector(RenderSafety.display(
              ApplyStateError.render(error),
              prepared.plan.redactions
            )),
            1
          )
        case Right((statePath, state)) =>
          runWithState(statePath, state, prepared, options, installer, stateStore, eventContext)

  private def statePath(options: InstallerOptions, plan: ResolvedPlan): Option[String] =
    options.statePath.orElse(plan.policy.stateFile)

  private def loadInitialState(
      rawPath: String,
      resetState: ResetState,
      prepared: PreparedPlan,
      stateStore: ApplyStateStore
  ): Either[ApplyStateError, (Path, ApplyState)] =
    for
      // State files are intentionally CWD-local filenames only; this prevents a profile or CLI
      // option from writing outside the working directory or targeting an install path.
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
      stateStore: ApplyStateStore,
      eventContext: InstallerEventContext
  ): InstallerResult =
    val completed    = completedToolNames(state)
    val pendingTools = prepared.plan.tools.filterNot(tool => completed(tool.name))
    val skippedLines = prepared.plan.tools
      .filter(tool => completed(tool.name))
      .map: tool =>
        eventContext.emit(InstallerEvent.ToolSkipped(
          tool.name,
          "already completed in state",
          Some(path.toString),
          _
        ))
        s"skipped ${tool.name}: already completed in state"
    val pendingPlan  = prepared.plan.copy(tools = pendingTools)
    var currentState = state
    val terminalObserver: TerminalToolResult => Either[String, Unit] = terminal =>
      eventContext.emit(InstallerEvent.ToolPhaseChanged(
        toolName(terminal),
        InstallerPhase.SavingState,
        _
      ))
      currentState = updateState(currentState, terminal)
      stateStore.save(path, currentState).left.map(error =>
        RenderSafety.display(ApplyStateError.render(error), prepared.plan.redactions)
      )
    val result = installer.installPlanWithObserver(
      pendingPlan,
      options.applyConfirmation,
      options.verboseOutput,
      terminalObserver,
      eventContext
    )

    result.copy(lines = skippedLines ++ result.lines)

  private def completedToolNames(state: ApplyState): Set[String] = state.tools.collect:
    case tool if tool.status == "completed" => tool.name
  .toSet

  private def updateState(state: ApplyState, result: TerminalToolResult): ApplyState =
    val updatedTool = result match
      case TerminalToolResult.Completed(toolName, installDir, download) =>
        ApplyStateTool(toolName, "completed", Some(installDir), None, download)
      case TerminalToolResult.Failed(toolName, message) =>
        ApplyStateTool(toolName, "failed", None, Some(message))
    state.copy(tools = replaceTool(state.tools, updatedTool))

  private def toolName(result: TerminalToolResult): String = result match
    case TerminalToolResult.Completed(toolName, _, _) => toolName
    case TerminalToolResult.Failed(toolName, _)       => toolName

  private def replaceTool(
      tools: Vector[ApplyStateTool],
      updated: ApplyStateTool
  ): Vector[ApplyStateTool] =
    val withoutCurrent = tools.filterNot(_.name == updated.name)
    withoutCurrent :+ updated

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

private object PlaceholderBinaryInstallerService extends BinaryInstallerService:

  def planWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult = placeholderResult("plan", options)

  def applyWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult = placeholderResult("apply", options)

  def versions(options: InstallerOptions): InstallerResult = placeholderResult("versions", options)

  def lock(options: InstallerOptions, lockOptions: LockOptions): InstallerResult =
    InstallerResult(Vector(s"binstaller lock placeholder for ${options.configPath}"), 0)

  private def placeholderResult(command: String, options: InstallerOptions): InstallerResult =
    InstallerResult(
      Vector(s"binstaller $command placeholder for ${options.configPath}"),
      0
    )

private final class ResolvingBinaryInstallerService(
    httpTextClient: HttpTextClient,
    resolutionOptions: ResolutionOptions,
    installer: DirectBinaryInstaller,
    stateStore: ApplyStateStore,
    metadataClient: BinaryMetadataClient,
    lockFileStore: LockFileStore
) extends BinaryInstallerService:

  def planWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult =
    val eventContext = InstallerEventContext.start(eventObserver)
    renderSelectedPlanWithEvents(options, eventContext)

  override def applyWithProgress(
      options: InstallerOptions,
      progressObserver: BinaryDownloadProgressObserver
  ): InstallerResult =
    applyWithEvents(options, InstallerEventObserver.fromDownloadProgress(progressObserver))

  def applyWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult =
    val eventContext = InstallerEventContext.start(eventObserver)
    eventContext.emit(InstallerEvent.ResolvingStarted(options.configPath, _))
    resolveSelectedPreparedPlan(options) match
      case Left(error) =>
        val result = renderError(error)
        emitSummary(result, stateFilePath = None, eventContext)
        result
      case Right(prepared) => validateLockIfRequested(options, prepared) match
          case Left(error) =>
            val result = renderLockedApplyError(error)
            emitSummary(result, stateFilePath = None, eventContext)
            result
          case Right(_) =>
            val statePath = configuredStatePath(options, prepared.plan)
            eventContext.emit(InstallerEvent.PlanReady(
              prepared.plan.tools.map(_.name),
              statePath,
              _
            ))
            val result =
              StatefulApplyRunner.run(options, prepared, installer, stateStore, eventContext)
            emitSummary(result, statePath, eventContext)
            result

  def versions(options: InstallerOptions): InstallerResult =
    resolveFromOptions(options).fold(renderError, renderVersions)

  def lock(options: InstallerOptions, lockOptions: LockOptions): InstallerResult =
    resolveSelectedPreparedPlan(options) match
      case Left(error)     => renderError(error)
      case Right(prepared) =>
        val lockFile = LockFileBuilder.build(prepared, metadataClient)
        val path     = Path.of(lockOptions.outputPath)
        lockFileStore.save(path, lockFile) match
          case Right(()) => InstallerResult(
              Vector(
                s"wrote lock file: ${path.toAbsolutePath.normalize()}",
                s"profile: ${prepared.profileName}",
                s"manifest fingerprint: ${prepared.manifestFingerprint}",
                s"tools: ${lockFile.tools.size}",
                s"checksums: ${LockFileChecksum.summary(lockFile.tools)}"
              ),
              0
            )
          case Left(error) => InstallerResult(Vector(LockFileError.render(error)), 1)

  private def renderSelectedPlanWithEvents(
      options: InstallerOptions,
      eventContext: InstallerEventContext
  ): InstallerResult =
    eventContext.emit(InstallerEvent.ResolvingStarted(options.configPath, _))
    resolveSelectedPreparedPlan(options) match
      case Left(error) =>
        val result = renderError(error)
        emitSummary(result, stateFilePath = None, eventContext)
        result
      case Right(prepared) =>
        val plan      = prepared.plan
        val statePath = configuredStatePath(options, plan)
        validateLockIfRequested(options, prepared) match
          case Left(error) =>
            val result = renderLockedApplyError(error)
            emitSummary(result, stateFilePath = None, eventContext)
            result
          case Right(lockedProvenance) =>
            eventContext.emit(InstallerEvent.PlanReady(plan.tools.map(_.name), statePath, _))
            val result = PlanRenderer.render(plan, lockedProvenance)
            emitSummary(result, statePath, eventContext)
            result

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

  private def configuredStatePath(
      options: InstallerOptions,
      plan: ResolvedPlan
  ): Option[String] = options.statePath.orElse(plan.policy.stateFile)

  private def emitSummary(
      result: InstallerResult,
      stateFilePath: Option[String],
      eventContext: InstallerEventContext
  ): Unit =
    val status =
      if result.exitCode == 0 then InstallerRunStatus.Succeeded
      else InstallerRunStatus.Failed
    eventContext.emit(InstallerEvent.Summary(
      status,
      installed = result.lines.count(_.startsWith("installed ")),
      failed = result.lines.count(_.startsWith("failed ")),
      skipped = result.lines.count(_.startsWith("skipped ")),
      exitCode = result.exitCode,
      stateFilePath = stateFilePath,
      _
    ))

  private def renderVersions(prepared: PreparedPlan): InstallerResult =
    val newerVersions = GitHubReleaseVersions.newerVersionsByTool(prepared.plan, httpTextClient)
    val rows          = prepared.plan.tools.map: tool =>
      VersionSummaryRow(
        packageName = tool.name,
        version = ResolvedVersion.render(tool.version),
        newerVersion = newerVersions.get(tool.name).getOrElse("-")
      )
    val lines = renderVersionSummaryTable(rows)
    InstallerResult(
      RenderSafety.displayLines("binstaller versions" +: lines, prepared.plan.redactions),
      0
    )

  private def renderVersionSummaryTable(rows: Vector[VersionSummaryRow]): Vector[String] =
    val headers      = VersionSummaryRow("package", "version", "newer version")
    val displayRows  = headers +: rows
    val packageWidth = displayRows.map(_.packageName.length).max
    val versionWidth = displayRows.map(_.version.length).max
    displayRows.map: row =>
      s"${row.packageName.padTo(packageWidth, ' ')}  " +
        s"${row.version.padTo(versionWidth, ' ')}  " +
        row.newerVersion

  private def renderError(error: ResolvePlanError): InstallerResult =
    InstallerResult(ResolvePlanError.renderLines(error), 1)

  private def validateLockIfRequested(
      options: InstallerOptions,
      prepared: PreparedPlan
  ): Either[LockedApplyError, Option[LockedApplyProvenance]] = options.lockedApply match
    case LockedApplyMode.Disabled => Right(None)
    case LockedApplyMode.Enabled  => LockedApplyValidator
        .validate(prepared, Path.of(options.lockPath), lockFileStore, metadataClient)
        .map(Some(_))

  private def renderLockedApplyError(error: LockedApplyError): InstallerResult =
    InstallerResult(LockedApplyError.renderLines(error), 1)

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

private object LockFileBuilder:

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

private object PlanRenderer:

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

private object Sha256:

  def digest(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    digest.map(byte => f"${byte & 0xff}%02x").mkString
