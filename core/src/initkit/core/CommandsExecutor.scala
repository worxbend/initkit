package initkit.core

import initkit.config.*
import initkit.host.HostFacts

final class CommandsExecutor(
    commandExecutor: CommandExecutor,
    hostFacts: HostFacts
):
  def install(
      operation: InstallerPlanOperation[InstallerSpec.Commands],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    val steps = CommandsExecutor.executionSteps(operation, policy, hostFacts)

    policy.mode match
      case ExecutionRunMode.DryRun =>
        PlanOperationOutcome.DryRun(CommandsExecutor.dryRunData(operation.summary, steps))
      case ExecutionRunMode.Apply =>
        applySteps(operation, steps)

  private def applySteps(
      operation: InstallerPlanOperation[InstallerSpec.Commands],
      steps: Vector[CommandExecutionStep]
  ): PlanOperationOutcome =
    var results = Vector.empty[CommandResult]
    var skipped = Vector.empty[CommandExecutionStep.Skipped]
    var stopped = false

    steps.foreach:
      case step: CommandExecutionStep.Skipped =>
        skipped = skipped :+ step
      case CommandExecutionStep.Run(_, command) if !stopped =>
        val result = commandExecutor.run(command)
        results = results :+ result
        if !result.succeeded && operation.execution.failFast then
          stopped = true
      case CommandExecutionStep.Run(_, _) =>
        ()

    val failures = results.filter(!_.succeeded)
    failures.headOption match
      case Some(firstFailure) =>
        PlanOperationOutcome.Failed(
          PlanFailure(
            operation = operation.summary,
            message = s"${failures.size} of ${results.size} command(s) failed; first failure: " +
              s"${CommandsExecutor.describe(firstFailure.spec)} " +
              s"(${CommandsExecutor.describeTermination(firstFailure.termination)})",
            exitCode = firstFailure.exitCode
          )
        )
      case None =>
        PlanOperationOutcome.Completed(Vector(completionDetail(results.size, skipped.size)))

  private def completionDetail(ran: Int, skipped: Int): String =
    (ran, skipped) match
      case (0, 0) => "no commands generated"
      case (_, 0) => s"ran $ran command(s)"
      case (0, _) => s"skipped $skipped command(s)"
      case _      => s"ran $ran command(s), skipped $skipped command(s)"

enum CommandExecutionStep:
  case Run(item: CommandItem, command: CommandSpec)
  case Skipped(item: CommandItem, reasons: Vector[String])

object CommandsExecutor:
  def executionSteps(
      operation: InstallerPlanOperation[InstallerSpec.Commands],
      policy: ExecutionPolicy,
      hostFacts: HostFacts
  ): Vector[CommandExecutionStep] =
    operation.spec.items.map(item => executionStep(item, policy, hostFacts))

  def commandSpec(item: CommandItem, policy: ExecutionPolicy): CommandSpec =
    CommandSpec.shell(
      command = CommandArgument(item.run),
      sudo = sudoMode(item, policy)
    )

  def dryRunData(
      summary: PlanOperationSummary,
      steps: Vector[CommandExecutionStep]
  ): DryRunOperationData =
    DryRunOperationData(summary, steps.map(dryRunAction))

  def describe(spec: CommandSpec): String =
    spec.redacted.invocation match
      case RedactedCommandInvocation.Direct(argv) =>
        argv.mkString(" ")
      case RedactedCommandInvocation.Shell(command, shell) =>
        (shell :+ command).mkString(" ")

  def describeTermination(termination: CommandTermination): String =
    termination match
      case CommandTermination.Exited(code) =>
        s"exit code $code"
      case CommandTermination.TimedOut(after) =>
        s"timed out after $after"
      case CommandTermination.Cancelled(message) =>
        s"cancelled: $message"
      case CommandTermination.FailedToStart(message) =>
        s"failed to start: $message"

  private def executionStep(
      item: CommandItem,
      policy: ExecutionPolicy,
      hostFacts: HostFacts
  ): CommandExecutionStep =
    val condition = ConditionEvaluator.evaluate(item.when, hostFacts)

    if condition.matched then
      CommandExecutionStep.Run(item, commandSpec(item, policy))
    else
      CommandExecutionStep.Skipped(item, condition.userFacingSkipReasons)

  private def sudoMode(item: CommandItem, policy: ExecutionPolicy): SudoMode =
    if item.sudo.getOrElse(policy.requireSudo) then SudoMode.Required
    else SudoMode.Disabled

  private def dryRunAction(step: CommandExecutionStep): DryRunAction =
    step match
      case CommandExecutionStep.Run(_, command) =>
        command.redacted.invocation match
          case RedactedCommandInvocation.Direct(argv) =>
            DryRunAction.Command(
              argv = argv,
              shell = None,
              sudo = command.sudo == SudoMode.Required,
              workingDirectory = command.cwd.map(_.toString)
            )
          case RedactedCommandInvocation.Shell(commandText, shell) =>
            DryRunAction.Command(
              argv = Vector(commandText),
              shell = Some(shell.mkString(" ")),
              sudo = command.sudo == SudoMode.Required,
              workingDirectory = command.cwd.map(_.toString)
            )
      case CommandExecutionStep.Skipped(item, reasons) =>
        DryRunAction.Message(s"skip command '${item.name}': ${reasons.mkString("; ")}")
