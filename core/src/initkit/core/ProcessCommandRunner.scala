package initkit.core

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.concurrent.TimeUnit
import scala.collection.immutable.VectorMap
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import ox.*

enum CommandRunMode:
  case Apply, DryRun

/**
 * Limits in-memory stdout/stderr capture.
 *
 * The runner continues draining the child process even after the buffer is full, retaining only the
 * most recent bytes from each stream.
 */
final case class CommandStreamCaptureConfig(maxBytesPerStream: Int)

object CommandStreamCaptureConfig:

  val Default: CommandStreamCaptureConfig =
    CommandStreamCaptureConfig(maxBytesPerStream = 1024 * 1024)

/**
 * JVM `ProcessBuilder` command executor.
 *
 * Dry-run mode bypasses sudo preparation and process startup. Apply mode owns the child process,
 * closes stdin unless a file is configured, drains stdout/stderr concurrently, and terminates the
 * process tree on timeout or cancellation where the JVM exposes descendants.
 */
final class ProcessCommandRunner(
    sudoStrategy: SudoStrategy,
    mode: CommandRunMode = CommandRunMode.Apply,
    streamCapture: CommandStreamCaptureConfig = CommandStreamCaptureConfig.Default,
    clock: Clock = Clock.systemUTC()
) extends CommandExecutor:

  override def run(spec: CommandSpec): CommandResult =
    val startedAt = clock.millis()
    mode match
      case CommandRunMode.DryRun =>
        CommandResult(spec, CommandTermination.Exited(0), "", "", elapsedSince(startedAt))
      case CommandRunMode.Apply => runPrepared(spec, startedAt)

  private def runPrepared(spec: CommandSpec, startedAt: Long): CommandResult =
    sudoStrategy.prepare(spec) match
      case Left(error) => CommandResult(
          spec,
          CommandTermination.FailedToStart(error.message),
          "",
          "",
          elapsedSince(startedAt)
        )
      case Right(prepared) => startAndCapture(prepared, startedAt)

  private def startAndCapture(spec: CommandSpec, startedAt: Long): CommandResult =
    commandVector(spec) match
      case Left(message) => CommandResult(
          spec,
          CommandTermination.FailedToStart(message),
          "",
          "",
          elapsedSince(startedAt)
        )
      case Right(command) =>
        val builder = processBuilder(command, spec)
        try
          val process = builder.start()
          closeChildStdin(process, spec)
          try captureProcessResult(spec, process, startedAt)
          catch
            case error: InterruptedException =>
              terminateProcessTree(process)
              CommandResult(
                spec,
                CommandTermination.Cancelled(Option(error.getMessage).getOrElse("interrupted")),
                "",
                "",
                elapsedSince(startedAt)
              )
        catch
          case error: IOException => CommandResult(
              spec,
              CommandTermination.FailedToStart(error.getMessage),
              "",
              "",
              elapsedSince(startedAt)
            )
          case error: SecurityException => CommandResult(
              spec,
              CommandTermination.FailedToStart(error.getMessage),
              "",
              "",
              elapsedSince(startedAt)
            )

  private def captureProcessResult(
      spec: CommandSpec,
      process: Process,
      startedAt: Long
  ): CommandResult = supervised:
    val stdout     = new BoundedStreamCapture(streamCapture.maxBytesPerStream)
    val stderr     = new BoundedStreamCapture(streamCapture.maxBytesPerStream)
    val stdoutPump = fork(pump(process.getInputStream, stdout))
    val stderrPump = fork(pump(process.getErrorStream, stderr))

    val termination = waitForProcess(process, spec.timeout)
    if !termination.isInstanceOf[CommandTermination.Exited] then terminateProcessTree(process)

    stdoutPump.join()
    stderrPump.join()

    CommandResult(
      spec = spec,
      termination = termination,
      stdout = stdout.text,
      stderr = stderr.text,
      duration = elapsedSince(startedAt)
    )

  private def waitForProcess(
      process: Process,
      timeout: Option[FiniteDuration]
  ): CommandTermination =
    try
      timeout match
        case Some(duration) =>
          if process.waitFor(atLeastOneMillisecond(duration), TimeUnit.MILLISECONDS) then
            CommandTermination.Exited(process.exitValue())
          else CommandTermination.TimedOut(duration)
        case None => CommandTermination.Exited(process.waitFor())
    catch
      case error: InterruptedException =>
        terminateProcessTree(process)
        CommandTermination.Cancelled(Option(error.getMessage).getOrElse("interrupted"))

  private def processBuilder(command: Vector[String], spec: CommandSpec): ProcessBuilder =
    val builder = ProcessBuilder(command*)
    spec.cwd.foreach(path => builder.directory(path.toFile))
    val environment = builder.environment()
    spec.env.foreach: (name, value) =>
      environment.put(name, value.value)
    spec.stdinFile.foreach(path => builder.redirectInput(path.toFile))
    builder

  private def commandVector(spec: CommandSpec): Either[String, Vector[String]] =
    spec.invocation match
      case CommandInvocation.Direct(argv) if argv.isEmpty     => Left("command argv is empty")
      case CommandInvocation.Direct(argv)                     => Right(argv.map(_.value))
      case CommandInvocation.Shell(_, shell) if shell.isEmpty =>
        Left("shell command prefix is empty")
      case CommandInvocation.Shell(command, shell) => Right(shell :+ command.value)

  private def pump(input: InputStream, capture: BoundedStreamCapture): Unit =
    val buffer = Array.ofDim[Byte](8192)
    try
      var bytesRead = input.read(buffer)
      while bytesRead != -1 do
        capture.append(buffer, bytesRead)
        bytesRead = input.read(buffer)
    catch
      case _: IOException => ()
      case NonFatal(_)    => ()
    finally input.close()

  private def closeChildStdin(process: Process, spec: CommandSpec): Unit =
    if spec.stdinFile.isEmpty then
      try process.getOutputStream.close()
      catch case _: IOException => ()

  private def terminateProcessTree(process: Process): Unit =
    val handles = processTree(process)
    handles.reverse.foreach(_.destroy())
    waitBriefly(process)
    handles.reverse.filter(_.isAlive).foreach(_.destroyForcibly())
    waitBriefly(process)

  private def processTree(process: Process): Vector[ProcessHandle] =
    val descendants = process.toHandle.descendants()
    try descendants.iterator().asScala.toVector :+ process.toHandle
    finally descendants.close()

  private def waitBriefly(process: Process): Unit =
    try process.waitFor(250, TimeUnit.MILLISECONDS)
    catch case _: InterruptedException => ()

  private def elapsedSince(startedAt: Long): FiniteDuration = (clock.millis() - startedAt).millis

  private def atLeastOneMillisecond(duration: FiniteDuration): Long =
    math.max(1L, duration.toMillis)

private final class BoundedStreamCapture(maxBytes: Int):
  private val buffer = Array.ofDim[Byte](math.max(0, maxBytes))
  private var start  = 0
  private var size   = 0

  def append(bytes: Array[Byte], count: Int): Unit = if buffer.nonEmpty then
    var index = 0
    while index < count do
      appendByte(bytes(index))
      index += 1

  def text: String =
    if size == 0 then ""
    else String(toByteArray, StandardCharsets.UTF_8)

  private def appendByte(byte: Byte): Unit =
    val writeIndex = (start + size) % buffer.length
    buffer(writeIndex) = byte
    if size < buffer.length then size += 1
    else start = (start + 1) % buffer.length

  private def toByteArray: Array[Byte] =
    val result = Array.ofDim[Byte](size)
    var index  = 0
    while index < size do
      result(index) = buffer((start + index) % buffer.length)
      index += 1
    result

enum SudoPreflightMode:
  case Interactive, AskPass

final case class SudoPreflightRequest(mode: SudoPreflightMode)

trait SudoPreflight:
  def validate(request: SudoPreflightRequest): Either[SudoPreparationError, Unit]

object SudoPreflight:

  val Jvm: SudoPreflight = new SudoPreflight:
    override def validate(request: SudoPreflightRequest): Either[SudoPreparationError, Unit] =
      val command = request.mode match
        case SudoPreflightMode.Interactive => Vector("sudo", "-v")
        case SudoPreflightMode.AskPass     => Vector("sudo", "-A", "-v")

      try
        val builder = ProcessBuilder(command*)
        builder.redirectInput(ProcessBuilder.Redirect.INHERIT)
        builder.redirectError(ProcessBuilder.Redirect.INHERIT)
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        val process = builder.start()
        try
          val exitCode = process.waitFor()
          if exitCode == 0 then Right(())
          else Left(SudoPreparationError(s"sudo preflight failed with exit code $exitCode"))
        catch
          case error: InterruptedException =>
            process.destroy()
            try process.waitFor(250, TimeUnit.MILLISECONDS)
            catch case _: InterruptedException => ()
            if process.isAlive then process.destroyForcibly()
            Thread.currentThread().interrupt()
            Left(SudoPreparationError(
              s"sudo preflight was interrupted: ${Option(error.getMessage).getOrElse("interrupted")}"
            ))
      catch
        case error: IOException =>
          Left(SudoPreparationError(s"sudo preflight failed to start: ${error.getMessage}"))

final class PreflightSudoStrategy(
    preflight: SudoPreflight,
    preflightMode: SudoPreflightMode
) extends SudoStrategy:

  override def prepare(spec: CommandSpec): Either[SudoPreparationError, CommandSpec] =
    spec.sudo match
      case SudoMode.Disabled => Right(spec)
      case SudoMode.Required => preflight.validate(SudoPreflightRequest(preflightMode)).map(_ =>
          withSudo(spec, preflightMode)
        )

  private def withSudo(spec: CommandSpec, mode: SudoPreflightMode): CommandSpec =
    spec.invocation match
      case CommandInvocation.Direct(argv) => spec.copy(
          invocation =
            CommandInvocation.Direct(sudoArguments(mode).map(value => CommandArgument(value)) ++
              argv),
          sudo = SudoMode.Disabled
        )
      case CommandInvocation.Shell(command, shell) => spec.copy(
          invocation = CommandInvocation.Shell(command, sudoArguments(mode) ++ shell),
          sudo = SudoMode.Disabled
        )

  private def sudoArguments(mode: SudoPreflightMode): Vector[String] = mode match
    case SudoPreflightMode.Interactive => Vector("sudo")
    case SudoPreflightMode.AskPass     => Vector("sudo", "-A")

object PreflightSudoStrategy:

  def fromEnvironment(
      interactive: SudoInteraction,
      environment: VectorMap[String, String] = VectorMap.from(System.getenv().asScala.toVector),
      preflight: SudoPreflight = SudoPreflight.Jvm
  ): Either[SudoPreparationError, PreflightSudoStrategy] = interactive match
    case SudoInteraction.InteractiveTerminal =>
      Right(new PreflightSudoStrategy(preflight, SudoPreflightMode.Interactive))
    case SudoInteraction.NonInteractive if environment.contains("SUDO_ASKPASS") =>
      Right(new PreflightSudoStrategy(preflight, SudoPreflightMode.AskPass))
    case SudoInteraction.NonInteractive =>
      Left(SudoPreparationError("sudo requires an interactive terminal or SUDO_ASKPASS"))

enum SudoInteraction:
  case InteractiveTerminal, NonInteractive

final case class FakeSudoPreflightResponse(
    expected: SudoPreflightRequest,
    result: Either[SudoPreparationError, Unit]
)

final class FakeSudoPreflight private (
    initialState: FakeSudoPreflightState
) extends SudoPreflight:
  private val stateRef = java.util.concurrent.atomic.AtomicReference(initialState)

  def calls: Vector[SudoPreflightRequest] = stateRef.get().calls

  override def validate(request: SudoPreflightRequest): Either[SudoPreparationError, Unit] =
    val state             = stateRef.get()
    val (result, pending) = state.pending.headOption match
      case Some(response) if response.expected == request => response.result -> state.pending.tail
      case Some(response)                                 =>
        Left(SudoPreparationError(s"unexpected sudo preflight: expected ${response.expected}")) ->
          state.pending
      case None =>
        Left(SudoPreparationError("unexpected sudo preflight: no fake response configured")) ->
          Vector.empty

    stateRef.set(state.copy(pending = pending, calls = state.calls :+ request))
    result

object FakeSudoPreflight:

  def apply(responses: Vector[FakeSudoPreflightResponse]): FakeSudoPreflight =
    new FakeSudoPreflight(FakeSudoPreflightState(responses, Vector.empty))

private final case class FakeSudoPreflightState(
    pending: Vector[FakeSudoPreflightResponse],
    calls: Vector[SudoPreflightRequest]
)
