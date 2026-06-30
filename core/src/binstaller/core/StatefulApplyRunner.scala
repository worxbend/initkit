package binstaller.core

import java.nio.file.Path

private[core] object StatefulApplyRunner:

  def run(
      options: InstallerOptions,
      prepared: PreparedPlan,
      installer: DirectBinaryInstaller,
      stateStore: ApplyStateStore,
      eventContext: InstallerEventContext
  ): InstallerResult = statePath(options, prepared.plan) match
    case None => installer.installPlanWithObserver(
        prepared.plan,
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
