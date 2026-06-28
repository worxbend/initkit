package initkit.core

import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.VectorMap
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

final case class CommandSpec(
    invocation: CommandInvocation,
    cwd: Option[Path],
    env: VectorMap[String, CommandEnvironmentValue],
    sudo: SudoMode,
    timeout: Option[FiniteDuration]
):
  def redacted: RedactedCommandSpec =
    CommandRedactor.redact(this)

object CommandSpec:
  def direct(
      argv: Vector[CommandArgument],
      cwd: Option[Path] = None,
      env: VectorMap[String, CommandEnvironmentValue] = VectorMap.empty,
      sudo: SudoMode = SudoMode.Disabled,
      timeout: Option[FiniteDuration] = None
  ): CommandSpec =
    CommandSpec(
      invocation = CommandInvocation.Direct(argv),
      cwd = cwd,
      env = env,
      sudo = sudo,
      timeout = timeout
    )

  def shell(
      command: CommandArgument,
      shell: Vector[String] = Vector("/bin/sh", "-c"),
      cwd: Option[Path] = None,
      env: VectorMap[String, CommandEnvironmentValue] = VectorMap.empty,
      sudo: SudoMode = SudoMode.Disabled,
      timeout: Option[FiniteDuration] = None
  ): CommandSpec =
    CommandSpec(
      invocation = CommandInvocation.Shell(command, shell),
      cwd = cwd,
      env = env,
      sudo = sudo,
      timeout = timeout
    )

enum CommandInvocation:
  case Direct(argv: Vector[CommandArgument])
  case Shell(command: CommandArgument, shell: Vector[String])

final case class CommandArgument(
    value: String,
    sensitivity: Sensitivity = Sensitivity.Public
)

final case class CommandEnvironmentValue(
    value: String,
    sensitivity: Sensitivity = Sensitivity.Public
)

enum Sensitivity:
  case Public
  case Sensitive(label: Option[String])

object Sensitivity:
  val Secret: Sensitivity =
    Sensitive(None)

enum SudoMode:
  case Disabled, Required

final case class RedactedCommandSpec(
    invocation: RedactedCommandInvocation,
    cwd: Option[Path],
    env: VectorMap[String, String],
    sudo: SudoMode,
    timeout: Option[FiniteDuration]
)

enum RedactedCommandInvocation:
  case Direct(argv: Vector[String])
  case Shell(command: String, shell: Vector[String])

object CommandRedactor:
  val Redaction: String =
    "[redacted]"

  def redact(spec: CommandSpec): RedactedCommandSpec =
    RedactedCommandSpec(
      invocation = redactInvocation(spec.invocation),
      cwd = spec.cwd,
      env = redactEnv(spec.env),
      sudo = spec.sudo,
      timeout = spec.timeout
    )

  def redactInvocation(invocation: CommandInvocation): RedactedCommandInvocation =
    invocation match
      case CommandInvocation.Direct(argv) =>
        RedactedCommandInvocation.Direct(redactArgv(argv))
      case CommandInvocation.Shell(command, shell) =>
        RedactedCommandInvocation.Shell(redactValue(command), shell)

  def redactArgv(argv: Vector[CommandArgument]): Vector[String] =
    var redactNext = false

    argv.map: argument =>
      val redacted =
        if argument.sensitivity != Sensitivity.Public || redactNext then Redaction
        else redactText(argument.value)

      redactNext = argument.sensitivity == Sensitivity.Public &&
        isSensitiveFlag(argument.value) &&
        !argument.value.contains("=") &&
        !argument.value.contains(":")

      redacted

  def redactEnv(env: VectorMap[String, CommandEnvironmentValue]): VectorMap[String, String] =
    env.map: (name, value) =>
      val redacted =
        if value.sensitivity != Sensitivity.Public || isSensitiveKey(name) then Redaction
        else redactText(value.value)

      name -> redacted

  def redactValue(value: CommandArgument): String =
    if value.sensitivity == Sensitivity.Public then redactText(value.value)
    else Redaction

  def redactText(value: String): String =
    redactPasswordLikeTokens(redactUrls(value))

  private val UrlPattern =
    """https?://[^\s'"<>]+""".r

  private val SensitiveAssignmentPattern =
    """(?i)\b([A-Za-z0-9_.-]*(?:password|passwd|passphrase|token|secret|api[_-]?key|access[_-]?token|credential)[A-Za-z0-9_.-]*)(=|:)([^\s&;]+)""".r

  private val SensitiveKeyFragments: Vector[String] =
    Vector("password", "passwd", "passphrase", "token", "secret", "api_key", "api-key", "access_token", "access-token", "credential")

  private def redactUrls(value: String): String =
    UrlPattern.replaceAllIn(
      value,
      matched => java.util.regex.Matcher.quoteReplacement(redactUrl(matched.matched))
    )

  private def redactUrl(value: String): String =
    Try(URI(value)).toOption match
      case Some(uri) if uri.getScheme != null && uri.getHost != null =>
        val userInfo = Option(uri.getUserInfo).map(_ => Redaction).orNull
        val query = Option(uri.getRawQuery).map(redactQuery).orNull
        Try(URI(uri.getScheme, userInfo, uri.getHost, uri.getPort, uri.getRawPath, query, uri.getRawFragment).toString)
          .getOrElse(value)
      case _ => value

  private def redactQuery(query: String): String =
    query
      .split("&", -1)
      .toVector
      .map(redactQueryPart)
      .mkString("&")

  private def redactQueryPart(part: String): String =
    part.split("=", 2).toVector match
      case Vector(key, _) if isSensitiveKey(key) => s"$key=$Redaction"
      case _                                    => part

  private def redactPasswordLikeTokens(value: String): String =
    SensitiveAssignmentPattern.replaceAllIn(
      value,
      matched => s"${matched.group(1)}${matched.group(2)}$Redaction"
    )

  private def isSensitiveFlag(value: String): Boolean =
    value.startsWith("-") &&
      isSensitiveKey(value.dropWhile(_ == '-'))

  private def isSensitiveKey(value: String): Boolean =
    val normalized = value.toLowerCase
    SensitiveKeyFragments.exists(normalized.contains)

trait CommandExecutor:
  def run(spec: CommandSpec): CommandResult

final case class CommandResult(
    spec: CommandSpec,
    termination: CommandTermination,
    stdout: String,
    stderr: String,
    duration: FiniteDuration
):
  def exitCode: Option[Int] =
    termination match
      case CommandTermination.Exited(code) => Some(code)
      case _                               => None

  def succeeded: Boolean =
    exitCode.contains(0)

enum CommandTermination:
  case Exited(code: Int)
  case TimedOut(after: FiniteDuration)
  case FailedToStart(message: String)

final case class CommandResultData(
    termination: CommandTermination,
    stdout: String,
    stderr: String,
    duration: FiniteDuration
):
  def toResult(spec: CommandSpec): CommandResult =
    CommandResult(spec, termination, stdout, stderr, duration)

object CommandResultData:
  def exited(
      code: Int,
      stdout: String = "",
      stderr: String = "",
      duration: FiniteDuration
  ): CommandResultData =
    CommandResultData(CommandTermination.Exited(code), stdout, stderr, duration)

final case class FakeCommandResponse(
    expected: CommandSpec,
    result: CommandResultData
)

final class FakeCommandExecutor private (
    initialState: FakeCommandExecutorState
) extends CommandExecutor:
  private val stateRef = AtomicReference(initialState)

  def calls: Vector[CommandSpec] =
    stateRef.get().calls

  def remainingResponses: Vector[FakeCommandResponse] =
    stateRef.get().pending

  override def run(spec: CommandSpec): CommandResult =
    val state = stateRef.get()
    val (result, nextPending) =
      state.pending.headOption match
        case Some(response) if response.expected == spec =>
          response.result.toResult(spec) -> state.pending.tail
        case Some(response) =>
          unexpectedCommand(spec, s"expected ${response.expected.redacted}") -> state.pending
        case None =>
          unexpectedCommand(spec, "no fake command response configured") -> Vector.empty

    stateRef.set(state.copy(pending = nextPending, calls = state.calls :+ spec))
    result

  private def unexpectedCommand(spec: CommandSpec, detail: String): CommandResult =
    CommandResult(
      spec = spec,
      termination = CommandTermination.FailedToStart(s"unexpected command: $detail"),
      stdout = "",
      stderr = "",
      duration = scala.concurrent.duration.Duration.Zero
    )

object FakeCommandExecutor:
  def apply(responses: Vector[FakeCommandResponse]): FakeCommandExecutor =
    new FakeCommandExecutor(FakeCommandExecutorState(pending = responses, calls = Vector.empty))

private final case class FakeCommandExecutorState(
    pending: Vector[FakeCommandResponse],
    calls: Vector[CommandSpec]
)

trait SudoStrategy:
  def prepare(spec: CommandSpec): Either[SudoPreparationError, CommandSpec]

final case class SudoPreparationError(message: String)

object SudoStrategy:
  val Passthrough: SudoStrategy =
    new SudoStrategy:
      override def prepare(spec: CommandSpec): Either[SudoPreparationError, CommandSpec] =
        Right(spec)

final case class FakeSudoResponse(
    expected: CommandSpec,
    result: Either[SudoPreparationError, CommandSpec]
)

final class FakeSudoStrategy private (
    initialState: FakeSudoStrategyState
) extends SudoStrategy:
  private val stateRef = AtomicReference(initialState)

  def calls: Vector[CommandSpec] =
    stateRef.get().calls

  def remainingResponses: Vector[FakeSudoResponse] =
    stateRef.get().pending

  override def prepare(spec: CommandSpec): Either[SudoPreparationError, CommandSpec] =
    val state = stateRef.get()
    val (result, nextPending) =
      state.pending.headOption match
        case Some(response) if response.expected == spec =>
          response.result -> state.pending.tail
        case Some(response) =>
          Left(SudoPreparationError(s"unexpected sudo request: expected ${response.expected.redacted}")) -> state.pending
        case None =>
          Left(SudoPreparationError("unexpected sudo request: no fake sudo response configured")) -> Vector.empty

    stateRef.set(state.copy(pending = nextPending, calls = state.calls :+ spec))
    result

object FakeSudoStrategy:
  def apply(responses: Vector[FakeSudoResponse]): FakeSudoStrategy =
    new FakeSudoStrategy(FakeSudoStrategyState(pending = responses, calls = Vector.empty))

private final case class FakeSudoStrategyState(
    pending: Vector[FakeSudoResponse],
    calls: Vector[CommandSpec]
)
