package initkit.core

import java.nio.file.{Files, Path}
import scala.util.Try

import initkit.config.*

final class DotfilesExecutor(
    commandExecutor: CommandExecutor,
    files: DotfilesFiles = DotfilesFiles.Jvm
):
  def install(
      operation: InstallerPlanOperation[InstallerSpec.DotfilesApply],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    policy.mode match
      case ExecutionRunMode.DryRun =>
        PlanOperationOutcome.DryRun(DotfilesExecutor.dryRunData(operation))
      case ExecutionRunMode.Apply =>
        applyDotfiles(operation)

  private def applyDotfiles(
      operation: InstallerPlanOperation[InstallerSpec.DotfilesApply]
  ): PlanOperationOutcome =
    runSetupCommands(operation) match
      case Left(failure) =>
        failed(operation.summary, failure)
      case Right(setupResults) =>
        verifyConfig(operation.spec.config) match
          case Left(failure) =>
            failed(operation.summary, failure)
          case Right(()) =>
            runToolCommands(operation) match
              case Left(failure) =>
                failed(operation.summary, failure)
              case Right(toolResults) =>
                PlanOperationOutcome.Completed(Vector(completionDetail(setupResults.size, toolResults.size)))

  private def runSetupCommands(
      operation: InstallerPlanOperation[InstallerSpec.DotfilesApply]
  ): Either[DotfilesFailure, Vector[CommandResult]] =
    runCommands(DotfilesExecutor.setupCommandSpecs(operation.spec, includeClone = !repositoryExists(operation.spec.repository)))

  private def repositoryExists(repository: GitRepository): Boolean =
    files.repositoryExists(Path.of(repository.destination))

  private def verifyConfig(config: DotfilesConfig): Either[DotfilesFailure, Unit] =
    val path = Path.of(config.path)
    if files.configExists(path) then Right(())
    else Left(DotfilesFailure.ConfigMissing(path, config.sourceUrl))

  private def runToolCommands(
      operation: InstallerPlanOperation[InstallerSpec.DotfilesApply]
  ): Either[DotfilesFailure, Vector[CommandResult]] =
    runCommands(DotfilesExecutor.toolCommandSpecs(operation.spec))

  private def runCommands(commands: Vector[CommandSpec]): Either[DotfilesFailure, Vector[CommandResult]] =
    var results = Vector.empty[CommandResult]
    var failure = Option.empty[DotfilesFailure]

    commands.foreach: command =>
      if failure.isEmpty then
        val result = commandExecutor.run(command)
        results = results :+ result
        if !result.succeeded then failure = Some(DotfilesFailure.Command(result))

    failure match
      case Some(value) => Left(value)
      case None        => Right(results)

  private def failed(summary: PlanOperationSummary, failure: DotfilesFailure): PlanOperationOutcome =
    PlanOperationOutcome.Failed(
      PlanFailure(
        operation = summary,
        message = failure.message,
        exitCode = failure.exitCode
      )
    )

  private def completionDetail(setupCommandCount: Int, toolCommandCount: Int): String =
    val setupDetail = setupCommandCount match
      case 0 => Vector.empty
      case 1 => Vector("ran 1 dotfiles repository command")
      case _ => Vector(s"ran $setupCommandCount dotfiles repository commands")

    val toolDetail = toolCommandCount match
      case 0 => Vector("ran no dotfiles apply commands")
      case 1 => Vector("ran 1 dotfiles apply command")
      case _ => Vector(s"ran $toolCommandCount dotfiles apply commands")

    (setupDetail ++ toolDetail).mkString("; ")

private enum DotfilesFailure:
  case Command(result: CommandResult)
  case ConfigMissing(path: Path, sourceUrl: Option[String])

  def message: String =
    this match
      case Command(result) =>
        s"dotfiles command failed: ${DotfilesExecutor.describe(result.spec)} " +
          s"(${CommandsExecutor.describeTermination(result.termination)})"
      case ConfigMissing(path, sourceUrl) =>
        val sourceDetail = sourceUrl.fold("")(url => s"; expected source: $url")
        s"dotfiles config missing after checkout: $path$sourceDetail"

  def exitCode: Option[Int] =
    this match
      case Command(result)     => result.exitCode
      case ConfigMissing(_, _) => None

trait DotfilesFiles:
  def repositoryExists(path: Path): Boolean
  def configExists(path: Path): Boolean

object DotfilesFiles:
  val Jvm: DotfilesFiles =
    new DotfilesFiles:
      override def repositoryExists(path: Path): Boolean =
        Try(Files.exists(path.toAbsolutePath.normalize())).getOrElse(false)

      override def configExists(path: Path): Boolean =
        Try(Files.isRegularFile(path.toAbsolutePath.normalize())).getOrElse(false)

object DotfilesExecutor:
  def commandSpecs(operation: InstallerPlanOperation[InstallerSpec.DotfilesApply]): Vector[CommandSpec] =
    setupCommandSpecs(operation.spec, includeClone = true) ++ toolCommandSpecs(operation.spec)

  def setupCommandSpecs(spec: InstallerSpec.DotfilesApply, includeClone: Boolean): Vector[CommandSpec] =
    Option.when(includeClone)(cloneCommandSpec(spec.repository)).toVector ++
      updateCommandSpec(spec.repository).toVector ++
      checkoutCommandSpec(spec.repository).toVector

  def toolCommandSpecs(spec: InstallerSpec.DotfilesApply): Vector[CommandSpec] =
    previewCommandSpec(spec).toVector :+ applyCommandSpec(spec)

  def applyCommandSpec(spec: InstallerSpec.DotfilesApply): CommandSpec =
    toolCommand(spec.tool, extraArgs = Vector.empty)

  def previewCommandSpec(spec: InstallerSpec.DotfilesApply): Option[CommandSpec] =
    spec.preview.filter(_.enabled.contains(true)).map: preview =>
      toolCommand(spec.tool, extraArgs = preview.args)

  def dryRunData(operation: InstallerPlanOperation[InstallerSpec.DotfilesApply]): DryRunOperationData =
    DryRunOperationData(
      operation = operation.summary,
      actions = commandSpecs(operation).map(dryRunCommand) :+
        DryRunAction.Message(s"verify dotfiles config exists at ${operation.spec.config.path} after checkout")
    )

  def describe(spec: CommandSpec): String =
    spec.redacted.invocation match
      case RedactedCommandInvocation.Direct(argv) =>
        argv.mkString(" ")
      case RedactedCommandInvocation.Shell(command, shell) =>
        (shell :+ command).mkString(" ")

  private def cloneCommandSpec(repository: GitRepository): CommandSpec =
    direct(Vector("git", "clone", repository.url, repository.destination))

  private def updateCommandSpec(repository: GitRepository): Option[CommandSpec] =
    Option.when(repository.update.contains(true)):
      direct(Vector("git", "-C", repository.destination, "pull", "--ff-only"))

  private def checkoutCommandSpec(repository: GitRepository): Option[CommandSpec] =
    repository.ref.map: ref =>
      direct(Vector("git", "-C", repository.destination, "checkout", ref))

  private def toolCommand(tool: ToolInvocation, extraArgs: Vector[String]): CommandSpec =
    direct(Vector(tool.path) ++ tool.args ++ extraArgs)

  private def direct(values: Vector[String]): CommandSpec =
    CommandSpec.direct(
      argv = values.map(CommandArgument(_)),
      sudo = SudoMode.Disabled
    )

  private def dryRunCommand(command: CommandSpec): DryRunAction =
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
