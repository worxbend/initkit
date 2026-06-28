package initkit.core

import java.nio.file.Paths
import scala.collection.immutable.VectorMap
import scala.concurrent.duration.*

import utest.*

object CommandContractsTests extends TestSuite:

  val tests: Tests = Tests:
    test("preserves direct argv boundaries and execution metadata"):
      val spec = CommandSpec.direct(
        argv = Vector(
          CommandArgument("git"),
          CommandArgument("clone"),
          CommandArgument("https://example.test/repo.git")
        ),
        cwd = Some(Paths.get("/work/tree")),
        env = VectorMap(
          "GIT_ASKPASS" -> CommandEnvironmentValue("/bin/false"),
          "API_TOKEN"   -> CommandEnvironmentValue("secret", Sensitivity.Secret)
        ),
        sudo = SudoMode.Required,
        timeout = Some(30.seconds)
      )

      assert(
        spec.invocation == CommandInvocation.Direct(
          Vector(
            CommandArgument("git"),
            CommandArgument("clone"),
            CommandArgument("https://example.test/repo.git")
          )
        )
      )
      assert(spec.cwd == Some(Paths.get("/work/tree")))
      assert(spec.env("API_TOKEN").sensitivity == Sensitivity.Secret)
      assert(spec.sudo == SudoMode.Required)
      assert(spec.timeout == Some(30.seconds))

    test("preserves shell mode without splitting command text"):
      val spec = CommandSpec.shell(
        command = CommandArgument("printf '%s\n' \"$TOKEN\""),
        shell = Vector("/bin/bash", "-lc"),
        cwd = Some(Paths.get("/tmp/initkit")),
        env = VectorMap("TOKEN" -> CommandEnvironmentValue("secret", Sensitivity.Secret)),
        sudo = SudoMode.Disabled,
        timeout = Some(5.seconds)
      )

      assert(spec.invocation == CommandInvocation.Shell(
        CommandArgument("printf '%s\n' \"$TOKEN\""),
        Vector("/bin/bash", "-lc")
      ))
      assert(spec.cwd == Some(Paths.get("/tmp/initkit")))
      assert(spec.env("TOKEN").value == "secret")
      assert(spec.timeout == Some(5.seconds))

    test("redacts sensitive args env urls and password-like tokens"):
      val spec = CommandSpec.direct(
        argv = Vector(
          CommandArgument("curl"),
          CommandArgument("https://user:pass@example.test/download?token=abc123&plain=yes"),
          CommandArgument("--password"),
          CommandArgument("super-secret"),
          CommandArgument("api_key=abc123"),
          CommandArgument("literal-secret", Sensitivity.Sensitive(Some("fixture")))
        ),
        env = VectorMap(
          "API_TOKEN"  -> CommandEnvironmentValue("abc123"),
          "CONFIG_URL" ->
            CommandEnvironmentValue("https://example.test/path?password=abc123&plain=yes"),
          "PLAIN" -> CommandEnvironmentValue("hello")
        )
      )

      val redacted = spec.redacted

      redacted.invocation match
        case RedactedCommandInvocation.Direct(argv) =>
          assert(argv(0) == "curl")
          assert(argv(
            1
          ).contains("https://%5Bredacted%5D@example.test/download?token=[redacted]&plain=yes"))
          assert(argv(2) == "--password")
          assert(argv(3) == CommandRedactor.Redaction)
          assert(argv(4) == s"api_key=${CommandRedactor.Redaction}")
          assert(argv(5) == CommandRedactor.Redaction)
        case other => fail(s"expected direct invocation, found $other")

      assert(redacted.env("API_TOKEN") == CommandRedactor.Redaction)
      assert(redacted.env("CONFIG_URL").contains(s"password=${CommandRedactor.Redaction}"))
      assert(redacted.env("PLAIN") == "hello")

    test("redacts shell command values marked sensitive"):
      val redacted = CommandSpec
        .shell(
          command = CommandArgument("export PASSWORD=abc123", Sensitivity.Secret),
          shell = Vector("/bin/sh", "-c")
        )
        .redacted

      assert(redacted.invocation ==
        RedactedCommandInvocation.Shell(CommandRedactor.Redaction, Vector("/bin/sh", "-c")))

    test("fake command executor returns queued results and records calls"):
      val first    = CommandSpec.direct(Vector(CommandArgument("echo"), CommandArgument("one")))
      val second   = CommandSpec.direct(Vector(CommandArgument("echo"), CommandArgument("two")))
      val executor = FakeCommandExecutor(
        Vector(
          FakeCommandResponse(
            first,
            CommandResultData.exited(0, stdout = "one\n", duration = 10.millis)
          ),
          FakeCommandResponse(
            second,
            CommandResultData.exited(2, stderr = "bad\n", duration = 20.millis)
          )
        )
      )

      val firstResult  = executor.run(first)
      val secondResult = executor.run(second)

      assert(firstResult.succeeded)
      assert(firstResult.stdout == "one\n")
      assert(secondResult.exitCode == Some(2))
      assert(secondResult.stderr == "bad\n")
      assert(executor.calls == Vector(first, second))
      assert(executor.remainingResponses.isEmpty)

    test("command success honors configured allowed exit codes"):
      val spec = CommandSpec.direct(
        Vector(CommandArgument("test-command")),
        allowedExitCodes = Set(0, 2)
      )
      val result = CommandResultData.exited(2, duration = 1.millis).toResult(spec)

      assert(result.exitCode == Some(2))
      assert(result.succeeded)

    test("fake command executor reports unexpected commands without running the host"):
      val expected = CommandSpec.direct(Vector(CommandArgument("expected")))
      val actual   = CommandSpec.direct(Vector(CommandArgument("actual")))
      val executor = FakeCommandExecutor(
        Vector(FakeCommandResponse(expected, CommandResultData.exited(0, duration = 1.millis)))
      )

      val result = executor.run(actual)

      assert(result.termination.isInstanceOf[CommandTermination.FailedToStart])
      assert(result.stderr == "")
      assert(executor.calls == Vector(actual))
      assert(executor.remainingResponses.map(_.expected) == Vector(expected))

    test("fake sudo strategy returns queued preparations and records requests"):
      val original = CommandSpec.direct(
        argv = Vector(CommandArgument("apt-get"), CommandArgument("update")),
        sudo = SudoMode.Required
      )
      val prepared = CommandSpec.direct(
        argv =
          Vector(CommandArgument("sudo"), CommandArgument("apt-get"), CommandArgument("update")),
        sudo = SudoMode.Disabled
      )
      val strategy = FakeSudoStrategy(
        Vector(FakeSudoResponse(original, Right(prepared)))
      )

      val result = strategy.prepare(original)

      assert(result == Right(prepared))
      assert(strategy.calls == Vector(original))
      assert(strategy.remainingResponses.isEmpty)

    test("passthrough sudo strategy never shells out"):
      val spec = CommandSpec.direct(Vector(CommandArgument("whoami")), sudo = SudoMode.Required)

      assert(SudoStrategy.Passthrough.prepare(spec) == Right(spec))

  private def fail(message: String): Nothing = throw new java.lang.AssertionError(message)
