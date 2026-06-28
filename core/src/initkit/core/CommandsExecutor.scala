package initkit.core

import java.nio.file.{Files, Path}
import scala.collection.immutable.VectorMap
import scala.concurrent.duration.DurationInt

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
      case ExecutionRunMode.Apply => applySteps(operation, steps)

  private def applySteps(
      operation: InstallerPlanOperation[InstallerSpec.Commands],
      steps: Vector[CommandExecutionStep]
  ): PlanOperationOutcome =
    var results = Vector.empty[CommandResult]
    var skipped = Vector.empty[CommandExecutionStep.Skipped]
    var stopped = false

    steps.foreach:
      case step: CommandExecutionStep.Skipped                  => skipped = skipped :+ step
      case CommandExecutionStep.Run(item, command) if !stopped =>
        if shouldSkipByUnless(item) then
          skipped = skipped :+ CommandExecutionStep.Skipped(
            item,
            Vector(s"unless command succeeded: ${item.unless.get}")
          )
        else
          val result = commandExecutor.run(command)
          results = results :+ result
          if !result.succeeded && operation.execution.failFast then
            stopped = true
      case CommandExecutionStep.Run(_, _) => ()

    val failures = results.filter(!_.succeeded)
    failures.headOption match
      case Some(firstFailure) => PlanOperationOutcome.Failed(
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

  private def completionDetail(ran: Int, skipped: Int): String = (ran, skipped) match
    case (0, 0) => "no commands generated"
    case (_, 0) => s"ran $ran command(s)"
    case (0, _) => s"skipped $skipped command(s)"
    case _      => s"ran $ran command(s), skipped $skipped command(s)"

  private def shouldSkipByUnless(item: CommandItem): Boolean =
    CommandsExecutor.unlessCommand(item).exists(command => commandExecutor.run(command).succeeded)

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

  def commandSpec(item: CommandItem, policy: ExecutionPolicy): CommandSpec = CommandSpec.shell(
    command = CommandArgument(item.run),
    cwd = item.cwd.map(Path.of(_)),
    env = env(item.env),
    sudo = sudoMode(item, policy),
    timeout = item.timeout.map(_.seconds),
    allowedExitCodes = item.allowedExitCodes.toSet
  )

  def dryRunData(
      summary: PlanOperationSummary,
      steps: Vector[CommandExecutionStep]
  ): DryRunOperationData = DryRunOperationData(summary, steps.flatMap(dryRunActions))

  def describe(spec: CommandSpec): String = spec.redacted.invocation match
    case RedactedCommandInvocation.Direct(argv)          => argv.mkString(" ")
    case RedactedCommandInvocation.Shell(command, shell) => (shell :+ command).mkString(" ")

  def describeTermination(termination: CommandTermination): String = termination match
    case CommandTermination.Exited(code)           => s"exit code $code"
    case CommandTermination.TimedOut(after)        => s"timed out after $after"
    case CommandTermination.Cancelled(message)     => s"cancelled: $message"
    case CommandTermination.FailedToStart(message) => s"failed to start: $message"

  private def executionStep(
      item: CommandItem,
      policy: ExecutionPolicy,
      hostFacts: HostFacts
  ): CommandExecutionStep =
    val condition = ConditionEvaluator.evaluate(item.when, hostFacts)

    val createsReason = item.creates
      .filter(path => Files.exists(Path.of(path)))
      .map(path => s"creates path already exists: $path")

    if !condition.matched then
      CommandExecutionStep.Skipped(item, condition.userFacingSkipReasons)
    else if createsReason.nonEmpty then
      CommandExecutionStep.Skipped(item, createsReason.toVector)
    else
      CommandExecutionStep.Run(item, commandSpec(item, policy))

  private def sudoMode(item: CommandItem, policy: ExecutionPolicy): SudoMode =
    if item.sudo.getOrElse(policy.requireSudo) then SudoMode.Required
    else SudoMode.Disabled

  private def env(entries: Vector[EnvironmentEntry]): VectorMap[String, CommandEnvironmentValue] =
    VectorMap.from(entries.map(entry =>
      entry.name -> CommandEnvironmentValue(
        entry.value,
        if entry.sensitive.contains(true) then Sensitivity.Secret else Sensitivity.Public
      )
    ))

  def unlessCommand(item: CommandItem): Option[CommandSpec] = item.unless.map(command =>
    CommandSpec.shell(
      CommandArgument(command),
      cwd = item.cwd.map(Path.of(_)),
      env = env(item.env),
      sudo = SudoMode.Disabled,
      timeout = item.timeout.map(_.seconds),
      allowedExitCodes = item.allowedExitCodes.toSet
    )
  )

  private def dryRunActions(step: CommandExecutionStep): Vector[DryRunAction] = step match
    case CommandExecutionStep.Run(item, command) =>
      val guards = item.creates.toVector.map(path =>
        DryRunAction.Message(s"guard command '${item.name}': creates path $path")
      ) ++
        item.unless.toVector.map(command =>
          DryRunAction.Message(s"guard command '${item.name}': unless '$command' succeeds")
        ) ++
        Option
          .when(item.allowedExitCodes != Vector(0))(
            DryRunAction.Message(
              s"guard command '${item.name}': allowed exit codes ${item.allowedExitCodes.mkString(",")}"
            )
          )
          .toVector
      val confirmation = item.confirm.toVector.map(message =>
        DryRunAction.Message(s"confirm command '${item.name}': $message")
      )
      guards ++ confirmation :+ dryRunCommand(command)
    case CommandExecutionStep.Skipped(item, reasons) =>
      Vector(DryRunAction.Message(s"skip command '${item.name}': ${reasons.mkString("; ")}"))

  private def dryRunCommand(command: CommandSpec): DryRunAction = command.redacted.invocation match
    case RedactedCommandInvocation.Direct(argv) => DryRunAction.Command(
        argv = argv,
        shell = None,
        sudo = command.sudo == SudoMode.Required,
        workingDirectory = command.cwd.map(_.toString)
      )
    case RedactedCommandInvocation.Shell(commandText, shell) => DryRunAction.Command(
        argv = Vector(commandText),
        shell = Some(shell.mkString(" ")),
        sudo = command.sudo == SudoMode.Required,
        workingDirectory = command.cwd.map(_.toString)
      )
