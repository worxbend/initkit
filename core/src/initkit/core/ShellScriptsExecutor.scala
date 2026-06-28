package initkit.core

import java.io.IOException
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import scala.collection.immutable.VectorMap
import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal

import initkit.config.*

final class ShellScriptsExecutor(
    commandExecutor: CommandExecutor,
    downloader: ShellScriptDownloader = ShellScriptDownloader.Jdk,
    files: ShellScriptFiles = ShellScriptFiles.Jvm
):

  def install(
      operation: InstallerPlanOperation[InstallerSpec.ShellScripts],
      policy: ExecutionPolicy
  ): PlanOperationOutcome = policy.mode match
    case ExecutionRunMode.DryRun =>
      PlanOperationOutcome.DryRun(ShellScriptsExecutor.dryRunData(operation, files))
    case ExecutionRunMode.Apply => applyScripts(operation)

  private def applyScripts(
      operation: InstallerPlanOperation[InstallerSpec.ShellScripts]
  ): PlanOperationOutcome =
    var ran      = 0
    var skipped  = 0
    var failures = Vector.empty[ShellScriptFailure]
    var stopped  = false

    operation.spec.items.foreach: item =>
      if stopped then ()
      else if createsExists(item) then skipped += 1
      else
        runItem(item) match
          case Left(failure) =>
            failures = failures :+ failure
            if operation.execution.failFast then stopped = true
          case Right(()) => ran += 1

    failures.headOption match
      case Some(firstFailure) => PlanOperationOutcome.Failed(
          PlanFailure(
            operation = operation.summary,
            message =
              s"${failures.size} shell script item(s) failed; first failure: ${firstFailure.message}",
            exitCode = firstFailure.exitCode
          )
        )
      case None => PlanOperationOutcome.Completed(Vector(completionDetail(ran, skipped)))

  private def runItem(item: ShellScriptItem): Either[ShellScriptFailure, Unit] =
    files.createTempScript(item.name) match
      case Left(error)     => Left(ShellScriptFailure.TempFile(item.name, error.message))
      case Right(tempPath) =>
        try downloadAndRun(item, tempPath)
        finally if item.cleanup.getOrElse(true) then files.deleteIfExists(tempPath)

  private def downloadAndRun(
      item: ShellScriptItem,
      tempPath: Path
  ): Either[ShellScriptFailure, Unit] = downloader.download(item.url, tempPath) match
    case Left(error) => Left(ShellScriptFailure.Download(item.name, error.message))
    case Right(())   =>
      val command = ShellScriptsExecutor.commandSpec(item, tempPath)
      val result  = commandExecutor.run(command)
      if result.succeeded then Right(())
      else Left(ShellScriptFailure.Command(result))

  private def createsExists(item: ShellScriptItem): Boolean =
    item.creates.exists(path => files.exists(Path.of(path)))

  private def completionDetail(ran: Int, skipped: Int): String = (ran, skipped) match
    case (0, 0) => "no shell scripts generated"
    case (_, 0) => s"ran $ran shell script(s)"
    case (0, _) => s"skipped $skipped shell script(s)"
    case _      => s"ran $ran shell script(s), skipped $skipped shell script(s)"

private enum ShellScriptFailure:
  case TempFile(itemName: String, detail: String)
  case Download(itemName: String, detail: String)
  case Command(result: CommandResult)

  def message: String = this match
    case TempFile(itemName, detail) => s"$itemName temp file failed: $detail"
    case Download(itemName, detail) => s"$itemName download failed: $detail"
    case Command(result)            =>
      s"${ShellScriptsExecutor.describe(result.spec)} (${CommandsExecutor.describeTermination(result.termination)})"

  def exitCode: Option[Int] = this match
    case Command(result) => result.exitCode
    case _               => None

trait ShellScriptDownloader:
  def download(url: String, destination: Path): Either[ShellScriptDownloadError, Unit]

final case class ShellScriptDownloadError(message: String)

object ShellScriptDownloader:

  val Jdk: ShellScriptDownloader = new ShellScriptDownloader:
    private val client = HttpClient.newHttpClient()

    override def download(url: String, destination: Path): Either[ShellScriptDownloadError, Unit] =
      try
        val request  = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(destination))
        if response.statusCode() >= 200 && response.statusCode() < 300 then Right(())
        else
          Files.deleteIfExists(destination)
          Left(ShellScriptDownloadError(s"HTTP ${response.statusCode()} from $url"))
      catch
        case error: IllegalArgumentException =>
          Left(ShellScriptDownloadError(s"invalid URL '$url': ${error.getMessage}"))
        case error: IOException =>
          Left(ShellScriptDownloadError(s"failed to download $url: ${error.getMessage}"))
        case error: InterruptedException =>
          Thread.currentThread().interrupt()
          Left(ShellScriptDownloadError(s"interrupted while downloading $url"))
        case NonFatal(error) =>
          Left(ShellScriptDownloadError(s"failed to download $url: ${error.getMessage}"))

trait ShellScriptFiles:
  def exists(path: Path): Boolean
  def createTempScript(itemName: String): Either[ShellScriptFileError, Path]
  def previewTempScriptPath(itemName: String): Path
  def deleteIfExists(path: Path): Unit

final case class ShellScriptFileError(message: String)

object ShellScriptFiles:

  val Jvm: ShellScriptFiles = new ShellScriptFiles:
    override def exists(path: Path): Boolean = Files.exists(path)

    override def createTempScript(itemName: String): Either[ShellScriptFileError, Path] =
      try Right(Files.createTempFile("initkit-" + safeName(itemName) + "-", ".sh"))
      catch
        case error: IOException       => Left(ShellScriptFileError(error.getMessage))
        case error: SecurityException => Left(ShellScriptFileError(error.getMessage))

    override def previewTempScriptPath(itemName: String): Path =
      Path.of("<temp>", s"initkit-${safeName(itemName)}.sh")

    override def deleteIfExists(path: Path): Unit =
      try Files.deleteIfExists(path)
      catch case _: IOException | _: SecurityException => ()

    private def safeName(value: String): String = value.map:
      case char if char.isLetterOrDigit || char == '-' || char == '_' => char
      case _                                                          => '-'
    .mkString

object ShellScriptsExecutor:

  def commandSpec(item: ShellScriptItem, tempPath: Path): CommandSpec =
    if readsScriptFromStdin(item) then
      CommandSpec.direct(
        argv = (Vector(item.shell) ++ item.args).map(CommandArgument(_)),
        cwd = item.cwd.map(Path.of(_)),
        env = env(item.env),
        sudo = sudoMode(item),
        timeout = item.timeout.map(_.seconds),
        stdinFile = Some(tempPath),
        allowedExitCodes = item.allowedExitCodes.toSet
      )
    else
      CommandSpec.direct(
        argv = (Vector(item.shell, tempPath.toString) ++ item.args).map(CommandArgument(_)),
        cwd = item.cwd.map(Path.of(_)),
        env = env(item.env),
        sudo = sudoMode(item),
        timeout = item.timeout.map(_.seconds),
        allowedExitCodes = item.allowedExitCodes.toSet
      )

  def dryRunData(
      operation: InstallerPlanOperation[InstallerSpec.ShellScripts],
      files: ShellScriptFiles
  ): DryRunOperationData = DryRunOperationData(
    operation.summary,
    operation.spec.items.flatMap(item => dryRunActions(item, files))
  )

  def describe(spec: CommandSpec): String = spec.redacted.invocation match
    case RedactedCommandInvocation.Direct(argv) =>
      val input = spec.redacted.stdinFile.map(path => s" < $path").getOrElse("")
      argv.mkString(" ") + input
    case RedactedCommandInvocation.Shell(command, shell) =>
      val input = spec.redacted.stdinFile.map(path => s" < $path").getOrElse("")
      (shell :+ command).mkString(" ") + input

  private def dryRunActions(item: ShellScriptItem, files: ShellScriptFiles): Vector[DryRunAction] =
    item.creates match
      case Some(path) if files.exists(Path.of(path)) =>
        Vector(DryRunAction.Message(
          s"skip shell script '${item.name}': creates path already exists: $path"
        ))
      case _ =>
        val tempPath = files.previewTempScriptPath(item.name)
        Vector(
          DryRunAction.Message(
            s"download shell script '${item.name}' from ${item.url} to $tempPath"
          ),
          dryRunCommand(commandSpec(item, tempPath))
        )

  private def dryRunCommand(command: CommandSpec): DryRunAction = command.redacted.invocation match
    case RedactedCommandInvocation.Direct(argv) => DryRunAction.Command(
        argv = argv,
        shell = None,
        sudo = command.sudo == SudoMode.Required,
        workingDirectory = command.cwd.map(_.toString),
        stdinFile = command.redacted.stdinFile.map(_.toString)
      )
    case RedactedCommandInvocation.Shell(commandText, shell) => DryRunAction.Command(
        argv = Vector(commandText),
        shell = Some(shell.mkString(" ")),
        sudo = command.sudo == SudoMode.Required,
        workingDirectory = command.cwd.map(_.toString)
      )

  private def readsScriptFromStdin(item: ShellScriptItem): Boolean =
    item.download == ShellScriptDownloadMode.Stdin || item.args.headOption.contains("-s")

  private def sudoMode(item: ShellScriptItem): SudoMode =
    if item.sudo.contains(true) then SudoMode.Required else SudoMode.Disabled

  private def env(entries: Vector[EnvironmentEntry]): VectorMap[String, CommandEnvironmentValue] =
    VectorMap.from(entries.map(entry =>
      entry.name -> CommandEnvironmentValue(
        entry.value,
        if entry.sensitive.contains(true) then Sensitivity.Secret else Sensitivity.Public
      )
    ))
