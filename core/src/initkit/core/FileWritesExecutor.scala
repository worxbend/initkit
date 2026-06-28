package initkit.core

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt

import initkit.config.*
import initkit.host.HostFacts

final class FileWritesExecutor(
    commandExecutor: CommandExecutor,
    hostFacts: HostFacts,
    files: FileWriteFiles = FileWriteFiles.Jvm
):

  def install(
      operation: InstallerPlanOperation[InstallerSpec.FileWrites],
      policy: ExecutionPolicy
  ): PlanOperationOutcome =
    val steps = FileWritesExecutor.executionSteps(operation, policy, hostFacts, files)

    policy.mode match
      case ExecutionRunMode.DryRun =>
        PlanOperationOutcome.DryRun(FileWritesExecutor.dryRunData(operation.summary, steps))
      case ExecutionRunMode.Apply => applySteps(operation, steps)

  private def applySteps(
      operation: InstallerPlanOperation[InstallerSpec.FileWrites],
      steps: Vector[FileWriteExecutionStep]
  ): PlanOperationOutcome =
    var written  = 0
    var skipped  = 0
    var failures = Vector.empty[FileWriteFailure]
    var stopped  = false

    steps.foreach:
      case FileWriteExecutionStep.Skipped(_, _)           => skipped += 1
      case FileWriteExecutionStep.Write(item) if !stopped =>
        writeItem(item) match
          case Right(())     => written += 1
          case Left(failure) =>
            failures = failures :+ failure
            if operation.execution.failFast then stopped = true
      case FileWriteExecutionStep.Write(_) => ()

    failures.headOption match
      case Some(firstFailure) => PlanOperationOutcome.Failed(
          PlanFailure(
            operation.summary,
            s"${failures.size} file write(s) failed; first failure: ${firstFailure.message}",
            firstFailure.exitCode
          )
        )
      case None => PlanOperationOutcome.Completed(Vector(completionDetail(written, skipped)))

  private def writeItem(
      item: FileWriteItem
  ): Either[FileWriteFailure, Unit] = files.createTempFile(item.name) match
    case Left(error)     => Left(FileWriteFailure.File(item.name, error.message))
    case Right(tempPath) =>
      try files.writeTempFile(tempPath, item.content) match
          case Left(error) => Left(FileWriteFailure.File(item.name, error.message))
          case Right(())   =>
            val result = commandExecutor.run(FileWritesExecutor.installCommand(item, tempPath))
            if result.succeeded then Right(())
            else Left(FileWriteFailure.Command(result))
      finally files.deleteIfExists(tempPath)

  private def completionDetail(written: Int, skipped: Int): String = (written, skipped) match
    case (0, 0) => "no file writes generated"
    case (_, 0) => s"wrote $written file(s)"
    case (0, _) => s"skipped $skipped file(s)"
    case _      => s"wrote $written file(s), skipped $skipped file(s)"

enum FileWriteExecutionStep:
  case Write(item: FileWriteItem)
  case Skipped(item: FileWriteItem, reasons: Vector[String])

private enum FileWriteFailure:
  case File(itemName: String, detail: String)
  case Command(result: CommandResult)

  def message: String = this match
    case File(itemName, detail) => s"$itemName file operation failed: $detail"
    case Command(result)        =>
      s"${CommandsExecutor.describe(result.spec)} (${CommandsExecutor.describeTermination(result.termination)})"

  def exitCode: Option[Int] = this match
    case File(_, _)      => None
    case Command(result) => result.exitCode

trait FileWriteFiles:
  def createTempFile(itemName: String): Either[FileWriteFileError, Path]
  def previewTempFile(itemName: String): Path
  def writeTempFile(path: Path, content: String): Either[FileWriteFileError, Unit]
  def deleteIfExists(path: Path): Unit

final case class FileWriteFileError(message: String)

object FileWriteFiles:

  val Jvm: FileWriteFiles = new FileWriteFiles:
    override def createTempFile(itemName: String): Either[FileWriteFileError, Path] =
      safely(Files.createTempFile("initkit-" + safeName(itemName) + "-", ".file"))

    override def previewTempFile(itemName: String): Path =
      Path.of("<temp>", s"initkit-${safeName(itemName)}.file")

    override def writeTempFile(
        path: Path,
        content: String
    ): Either[FileWriteFileError, Unit] = safely:
      Files.writeString(path, content, StandardCharsets.UTF_8)
      ()

    override def deleteIfExists(path: Path): Unit =
      try Files.deleteIfExists(path)
      catch case _: IOException | _: SecurityException => ()

    private def safely[A](body: => A): Either[FileWriteFileError, A] =
      try Right(body)
      catch
        case error: IOException       => Left(FileWriteFileError(safeMessage(error)))
        case error: SecurityException => Left(FileWriteFileError(safeMessage(error)))

    private def safeName(value: String): String = value.map:
      case char if char.isLetterOrDigit || char == '-' || char == '_' => char
      case _                                                          => '-'
    .mkString

    private def safeMessage(error: Throwable): String =
      Option(error.getMessage).getOrElse(error.getClass.getName)

object FileWritesExecutor:

  def executionSteps(
      operation: InstallerPlanOperation[InstallerSpec.FileWrites],
      policy: ExecutionPolicy,
      hostFacts: HostFacts,
      files: FileWriteFiles
  ): Vector[FileWriteExecutionStep] = operation.spec.items.map: item =>
    val condition = ConditionEvaluator.evaluate(item.when, hostFacts)
    if condition.matched then FileWriteExecutionStep.Write(item)
    else FileWriteExecutionStep.Skipped(item, condition.userFacingSkipReasons)

  def installCommand(item: FileWriteItem, tempPath: Path): CommandSpec = CommandSpec.direct(
    argv = installArgv(item, tempPath).map(CommandArgument(_)),
    sudo = if item.sudo.contains(true) then SudoMode.Required else SudoMode.Disabled,
    timeout = Some(5.minutes)
  )

  def dryRunData(
      summary: PlanOperationSummary,
      steps: Vector[FileWriteExecutionStep]
  ): DryRunOperationData = DryRunOperationData(summary, steps.map(dryRunAction))

  private def installArgv(
      item: FileWriteItem,
      tempPath: Path
  ): Vector[String] = Vector("install", "-D") ++
    item.mode.toVector.flatMap(mode => Vector("-m", mode)) ++
    item.owner.toVector.flatMap(owner => Vector("-o", owner)) ++
    item.group.toVector.flatMap(group => Vector("-g", group)) ++
    Vector(tempPath.toString, item.path)

  private def dryRunAction(step: FileWriteExecutionStep): DryRunAction = step match
    case FileWriteExecutionStep.Write(item) =>
      DryRunAction.FileWrite(item.path, item.mode, s"write file '${item.name}'")
    case FileWriteExecutionStep.Skipped(item, reasons) =>
      DryRunAction.Message(s"skip file write '${item.name}': ${reasons.mkString("; ")}")
