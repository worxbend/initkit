package binstaller.core

import binstaller.config.AllowSudoSymlinks
import binstaller.config.SymlinkPrivilege

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import ox.channels.BufferCapacity
import ox.flow.Flow
import ox.supervised

private[core] final case class ObservedInstallResults(
    lines: Vector[String],
    results: Vector[TerminalToolResult],
    persistenceError: Option[String]
)

private[core] enum PreparedToolResult:

  case Ready(
      tool: ResolvedTool,
      stagedInstall: StagedInstall,
      download: UrlProvenance,
      verboseLines: Vector[String]
  )

  case Failed(
      toolName: String,
      error: ToolInstallError,
      verboseLines: Vector[String]
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
      verboseOutput: VerboseOutput = VerboseOutput.Disabled,
      progressObserver: BinaryDownloadProgressObserver = BinaryDownloadProgressObserver.none
  ): InstallerResult = installPlanWithObserver(
    plan,
    verboseOutput,
    _ => Right(()),
    InstallerEventContext.start(InstallerEventObserver.fromDownloadProgress(progressObserver)),
    ApplyParallelism.default
  )

  private[core] def installPlanWithObserver(
      plan: ResolvedPlan,
      verboseOutput: VerboseOutput,
      terminalObserver: TerminalToolResult => Either[String, Unit],
      eventContext: InstallerEventContext,
      applyParallelism: ApplyParallelism = ApplyParallelism.default
  ): InstallerResult = preflight(plan) match
    case Some(error) => InstallerResult(Vector(ApplyPreflightError.render(error)), 1)
    case None        =>
      val observed = installTools(
        plan.policy,
        plan.tools,
        plan.redactions,
        verboseOutput,
        terminalObserver,
        eventContext,
        applyParallelism
      )
      val lines = observed.lines ++
        observed.persistenceError.map(message => s"state write failed: $message").toVector
      val exitCode =
        if observed.results.exists(_.isInstanceOf[TerminalToolResult.Failed]) ||
          observed.persistenceError.nonEmpty
        then 1
        else 0

      InstallerResult(lines, exitCode)

  private def preflight(plan: ResolvedPlan): Option[ApplyPreflightError] = plan.tools
    .find(_.symlinks.exists(_.privilege == SymlinkPrivilege.Sudo))
    .flatMap: tool =>
      plan.policy.allowSudoSymlinks match
        case AllowSudoSymlinks.Disabled =>
          Some(ApplyPreflightError.SudoSymlinkNotAllowed(tool.name))
        case AllowSudoSymlinks.Enabled => None

  private def installTools(
      policy: ResolvedPolicy,
      tools: Vector[ResolvedTool],
      redactions: SensitiveValueRedactions,
      verboseOutput: VerboseOutput,
      terminalObserver: TerminalToolResult => Either[String, Unit],
      eventContext: InstallerEventContext,
      applyParallelism: ApplyParallelism
  ): ObservedInstallResults =
    if tools.isEmpty then ObservedInstallResults(Vector.empty, Vector.empty, None)
    else
      supervised:
        val effectiveParallelism = math.max(1, applyParallelism.value)
        given BufferCapacity     = BufferCapacity(effectiveParallelism)
        val serializedEvents     = eventContext.serialized
        val preparedResults      = Flow
          .fromIterable(tools)
          .mapPar(effectiveParallelism): tool =>
            prepareTool(tool, redactions, verboseOutput, serializedEvents)
          .runToList()
          .toVector
        finalizePreparedResults(
          policy,
          preparedResults,
          redactions,
          terminalObserver,
          serializedEvents
        )

  private def renderedTerminalLines(
      terminal: TerminalToolResult,
      redactions: SensitiveValueRedactions
  ): Vector[String] = terminal match
    case TerminalToolResult.Completed(_, _, _) =>
      Vector(TerminalToolResult.line(terminal, redactions))
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
    installDownloadedBinaryOrArchive(policy, tool, eventContext, redactions)

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

  private def prepareTool(
      tool: ResolvedTool,
      redactions: SensitiveValueRedactions,
      verboseOutput: VerboseOutput,
      eventContext: InstallerEventContext
  ): PreparedToolResult =
    val verbose = verboseLines(tool, verboseOutput, redactions)
    verbose.foreach(line =>
      eventContext.emit(InstallerEvent.LogLine(Some(tool.name), line, _))
    )
    eventContext.emit(InstallerEvent.ToolStarted(tool.name, InstallerPhase.Downloading, _))
    prepareDownloadedBinaryOrArchive(tool, eventContext, redactions) match
      case Right((stagedInstall, provenance)) =>
        PreparedToolResult.Ready(tool, stagedInstall, provenance, verbose)
      case Left(error) => PreparedToolResult.Failed(tool.name, error, verbose)

  private def prepareDownloadedBinaryOrArchive(
      tool: ResolvedTool,
      eventContext: InstallerEventContext,
      redactions: SensitiveValueRedactions
  ): Either[ToolInstallError, (StagedInstall, UrlProvenance)] =
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
    yield staged -> downloadResult.provenance

  private def finalizePreparedResults(
      policy: ResolvedPolicy,
      preparedResults: Vector[PreparedToolResult],
      redactions: SensitiveValueRedactions,
      terminalObserver: TerminalToolResult => Either[String, Unit],
      eventContext: InstallerEventContext
  ): ObservedInstallResults =
    val initial = ObservedInstallResults(Vector.empty, Vector.empty, None)
    preparedResults.foldLeft(initial): (observed, prepared) =>
      if observed.persistenceError.nonEmpty ||
        stoppedAfterFailure(policy, observed.results)
      then
        discardPrepared(prepared)
        observed
      else
        appendFinalizedResult(
          observed,
          finalizePrepared(policy, prepared, eventContext),
          redactions,
          terminalObserver,
          eventContext
        )

  private def stoppedAfterFailure(
      policy: ResolvedPolicy,
      results: Vector[TerminalToolResult]
  ): Boolean = policy.continueOnError == ContinueOnError.Disabled &&
    results.exists(_.isInstanceOf[TerminalToolResult.Failed])

  private def discardPrepared(prepared: PreparedToolResult): Unit = prepared match
    case PreparedToolResult.Ready(_, stagedInstall, _, _) => fileSystem.discardStaged(stagedInstall)
    case PreparedToolResult.Failed(_, _, _)               => ()

  private def finalizePrepared(
      policy: ResolvedPolicy,
      prepared: PreparedToolResult,
      eventContext: InstallerEventContext
  ): (Vector[String], Either[ToolInstallError, ToolInstallSuccess]) = prepared match
    case PreparedToolResult.Failed(_, error, verbose)                     => verbose -> Left(error)
    case PreparedToolResult.Ready(tool, stagedInstall, download, verbose) => verbose ->
        completePreparedTool(policy, tool, stagedInstall, download, eventContext)

  private def completePreparedTool(
      policy: ResolvedPolicy,
      tool: ResolvedTool,
      stagedInstall: StagedInstall,
      download: UrlProvenance,
      eventContext: InstallerEventContext
  ): Either[ToolInstallError, ToolInstallSuccess] =
    for
      _ <-
        withPhase(tool, InstallerPhase.ReplacingInstall, eventContext)(replace(tool, stagedInstall))
      _ <- withPhase(tool, InstallerPhase.VerifyingExecutables, eventContext)(
        verifyExecutables(tool)
      )
      _ <- withPhase(tool, InstallerPhase.CreatingSymlinks, eventContext)(
        SymlinkInstaller.create(policy, tool, commandExecutor, sudoCredentials)
      )
    yield ToolInstallSuccess(tool.name, tool.installDir, Some(download))

  private def appendFinalizedResult(
      observed: ObservedInstallResults,
      finalized: (Vector[String], Either[ToolInstallError, ToolInstallSuccess]),
      redactions: SensitiveValueRedactions,
      terminalObserver: TerminalToolResult => Either[String, Unit],
      eventContext: InstallerEventContext
  ): ObservedInstallResults =
    val (verbose, result) = finalized
    val terminal          = terminalResult(result, redactions)
    eventContext.emit(toolResultEvent(terminal))
    val terminalLines = renderedTerminalLines(terminal, redactions)
    terminalObserver(terminal) match
      case Left(message) => observed.copy(
          lines = observed.lines ++ verbose ++ terminalLines,
          results = observed.results :+ terminal,
          persistenceError = Some(RenderSafety.display(message, redactions))
        )
      case Right(()) => observed.copy(
          lines = observed.lines ++ verbose ++ terminalLines,
          results = observed.results :+ terminal
        )

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
