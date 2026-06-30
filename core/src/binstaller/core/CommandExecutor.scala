package binstaller.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import ox.fork
import ox.supervised
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using

/** Secret command input that can be written to stdin but never rendered directly. */
final class SecretText private (private val value: String):

  private[core] def writeLineTo(output: OutputStream): Unit =
    output.write((value + "\n").getBytes(StandardCharsets.UTF_8))

  private[core] def redact(text: String): String =
    if value.isEmpty then text
    else text.replace(value, "<redacted>")

  override def toString: String = "<redacted>"

/** Secret command-input constructors. */
object SecretText:
  /** Wrap a runtime secret while keeping it out of command diagnostics. */
  def fromString(value: String): SecretText = new SecretText(value)

/** Modeled stdin for structured process invocation. */
enum CommandInput:
  case Empty
  case SecretLine(secret: SecretText)

  private[core] def writeTo(output: OutputStream): Unit = this match
    case CommandInput.Empty              => ()
    case CommandInput.SecretLine(secret) => secret.writeLineTo(output)

  private[core] def redact(text: String): String = this match
    case CommandInput.Empty              => text
    case CommandInput.SecretLine(secret) => secret.redact(text)

  private[core] def redact(output: CommandOutput): CommandOutput = CommandOutput(
    redact(output.stdout),
    redact(output.stderr)
  )

/** Structured process invocation. `argv` is passed directly, never through shell text. */
final case class CommandSpec(
    argv: Vector[String],
    cwd: Path,
    env: Map[String, String],
    input: CommandInput = CommandInput.Empty
)

/** Captured stdout and stderr from a bounded process execution. */
final case class CommandOutput(stdout: String, stderr: String):
  /** Whether either stream contained captured output. */
  def hasOutput: Boolean = stdout.nonEmpty || stderr.nonEmpty

/** Command-output constructors. */
object CommandOutput:
  /** Empty command output. */
  val empty: CommandOutput = CommandOutput("", "")

/** Expected process execution failure with the structured command that produced it. */
final case class CommandExecutionError(
    spec: CommandSpec,
    message: String,
    exitCode: Option[Int],
    output: CommandOutput = CommandOutput.empty
)

private[core] object CommandFailureDetails:

  def render(error: CommandExecutionError): String =
    render("command", error.spec, error.message, error.exitCode, error.output)

  def render(
      context: String,
      spec: CommandSpec,
      message: String,
      exitCode: Option[Int],
      output: CommandOutput
  ): String =
    val command     = renderArgv(spec.argv)
    val safeMessage = spec.input.redact(message)
    val details     = Vector(s"  command: $command") ++
      Vector(s"  cwd: ${spec.cwd}") ++
      renderEnv(spec.env) ++
      exitCode.map(code => s"  exit code: $code").toVector ++
      renderOutputTail("stdout", spec.input.redact(output.stdout)) ++
      renderOutputTail("stderr", spec.input.redact(output.stderr))
    (s"$context: $command: $safeMessage" +: details).mkString("\n")

  private def renderArgv(argv: Vector[String]): String = argv.map(shellQuote).mkString(" ")

  private def shellQuote(value: String): String = s"'${value.replace("'", "'\"'\"'")}'"

  private def renderEnv(env: Map[String, String]): Vector[String] =
    if env.isEmpty then Vector("  env: <empty>")
    else
      val rendered = env.toVector
        .sortBy((name, _) => name)
        .map((name, value) => s"$name=${renderEnvValue(name, value)}")
        .mkString(", ")
      Vector(s"  env: $rendered")

  private def renderEnvValue(name: String, value: String): String =
    RenderSafety.envValue(name, value)

  private def renderOutputTail(label: String, text: String): Vector[String] =
    val maxRenderedLines = 40
    val lines            = text.linesIterator.toVector.filterNot(_.isBlank)
    val omitted          =
      if lines.length > maxRenderedLines then
        Vector(s"  $label: ... omitted ${lines.length - maxRenderedLines} earlier line(s)")
      else Vector.empty
    omitted ++
      lines.takeRight(maxRenderedLines).map(line => s"  $label: ${RenderSafety.display(line)}")

/** Boundary for the few remaining process executions: sudo symlinks and tar.xz fallback. */
trait CommandExecutor:
  /** Run a structured command, returning expected process failures as data. */
  def run(spec: CommandSpec): Either[CommandExecutionError, Unit]

/** Process command executor constructors. */
object CommandExecutor:
  /** Process executor with the production timeout. */
  def process: CommandExecutor = processWithTimeout(Duration.ofMinutes(15))

  /** Process executor with an explicit timeout for tests and specialized runtimes. */
  def processWithTimeout(timeout: Duration): CommandExecutor = ProcessCommandExecutor(timeout)

private[core] object CommandEnvironment:
  val baseline: Map[String, String] = Map("PATH" -> sys.env.getOrElse("PATH", "/usr/bin:/bin"))

private[core] final class ProcessCommandExecutor(timeout: Duration) extends CommandExecutor:
  private val capturedOutputLimitBytes = 64 * 1024

  def run(spec: CommandSpec): Either[CommandExecutionError, Unit] = Try:
    val builder = ProcessBuilder(spec.argv.asJava)
    val _       = builder.directory(spec.cwd.toFile)
    val env     = builder.environment()
    // Commands receive only the modeled environment. Parent secrets must not leak into sudo/tar
    // process boundaries or later diagnostics.
    env.clear()
    spec.env.foreach:
      case (name, value) => val _ = env.put(name, value)
    val process = builder.start()
    Using.resource(process.getOutputStream)(spec.input.writeTo)
    supervised:
      val stdout = fork(readBounded(process.getInputStream))
      val stderr = fork(readBounded(process.getErrorStream))
      if process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS) then
        val exit   = process.exitValue()
        val output = spec.input.redact(CommandOutput(stdout.join(), stderr.join()))
        if exit == 0 then Right(())
        else
          Left(
            CommandExecutionError(
              spec,
              spec.input.redact(s"command exited with status $exit"),
              Some(exit),
              output
            )
          )
      else
        val _      = process.destroyForcibly()
        val _      = process.waitFor(5, TimeUnit.SECONDS)
        val output = spec.input.redact(CommandOutput(stdout.join(), stderr.join()))
        Left(
          CommandExecutionError(
            spec,
            spec.input.redact(s"command timed out after ${timeout.toSeconds}s"),
            None,
            output
          )
        )
  match
    case Success(result) => result
    case Failure(error)  =>
      Left(CommandExecutionError(spec, spec.input.redact(error.getMessage), None))

  private def readBounded(input: InputStream): String = Using.resource(input): stream =>
    val output  = ByteArrayOutputStream()
    val buffer  = Array.ofDim[Byte](8 * 1024)
    var read    = stream.read(buffer)
    var stored  = 0
    var clipped = false
    while read != -1 do
      val remaining = capturedOutputLimitBytes - stored
      if remaining > 0 then
        val writable = read.min(remaining)
        output.write(buffer, 0, writable)
        stored += writable
        clipped = clipped || writable < read
      else clipped = true
      read = stream.read(buffer)
    val suffix =
      if clipped then "\n... output truncated after 65536 bytes ..."
      else ""
    output.toString(StandardCharsets.UTF_8) + suffix
