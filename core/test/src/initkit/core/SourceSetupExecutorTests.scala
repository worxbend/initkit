package initkit.core

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Files, Path}
import java.util.Comparator
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*

import utest.*

object SourceSetupExecutorTests extends TestSuite:

  val tests: Tests = Tests:
    test("apply mode runs source setup commands through the command executor"):
      val command  = commandSpec("setup-source")
      val executor = FakeCommandExecutor(
        Vector(FakeCommandResponse(command, CommandResultData.exited(0, duration = Duration.Zero)))
      )
      val files   = RecordingSourceSetupFiles()
      val outcome = SourceSetupExecutor(executor, files).execute(
        SourceSetupPlan(
          Vector(SourceSetupOperation.RunCommand("Add source", command)),
          Vector.empty,
          aptUpdateBeforeInstall = false
        ),
        applyPolicy,
        sourceSetupSummary
      )

      assert(outcome ==
        PlanOperationOutcome.Completed(Vector("ran source setup command 'Add source'")))
      assert(executor.calls == Vector(command))
      assert(files.writes.isEmpty)

    test("apply mode reports source setup command failures"):
      val command  = commandSpec("fail-source")
      val executor = FakeCommandExecutor(
        Vector(FakeCommandResponse(command, CommandResultData.exited(7, duration = Duration.Zero)))
      )
      val outcome = SourceSetupExecutor(executor, RecordingSourceSetupFiles()).execute(
        SourceSetupPlan(
          Vector(SourceSetupOperation.RunCommand("Add failing source", command)),
          Vector.empty,
          aptUpdateBeforeInstall = false
        ),
        applyPolicy,
        sourceSetupSummary
      )

      val failure = outcome match
        case PlanOperationOutcome.Failed(value) => value
        case other                              => fail(s"expected failed outcome, got $other")

      assert(executor.calls == Vector(command))
      assert(failure.exitCode == Some(7))
      assert(failure.message.contains("source setup command 'Add failing source' failed"))
      assert(failure.message.contains("exit code 7"))

    test("apply mode stops source setup after the first failed operation"):
      val command     = commandSpec("fail-source")
      val destination = Path.of("/tmp/initkit/source-after-failure.list")
      val executor    = FakeCommandExecutor(
        Vector(FakeCommandResponse(command, CommandResultData.exited(7, duration = Duration.Zero)))
      )
      val files   = RecordingSourceSetupFiles()
      val outcome = SourceSetupExecutor(executor, files).execute(
        SourceSetupPlan(
          Vector(
            SourceSetupOperation.RunCommand("Add failing source", command),
            SourceSetupOperation.WriteFile(
              "Write later source",
              destination,
              "content",
              Some("0644"),
              sudo = false
            )
          ),
          Vector.empty,
          aptUpdateBeforeInstall = false
        ),
        applyPolicy,
        sourceSetupSummary
      )

      assert(outcome.isInstanceOf[PlanOperationOutcome.Failed])
      assert(executor.calls == Vector(command))
      assert(files.writes.isEmpty)

    test("apply mode writes source files with parent directory creation and configured mode"):
      val tempDir = Files.createTempDirectory("initkit-source-setup-test-")
      try
        val destination = tempDir.resolve("nested/docker.list")
        val operation   = SourceSetupOperation.WriteFile(
          label = "Write docker source",
          path = destination,
          content = "deb https://example.test stable main\n",
          mode = Some("0600"),
          sudo = false
        )
        val outcome = SourceSetupExecutor(FakeCommandExecutor(Vector.empty)).execute(
          SourceSetupPlan(Vector(operation), Vector.empty, aptUpdateBeforeInstall = false),
          applyPolicy,
          sourceSetupSummary
        )

        assert(Files.isDirectory(destination.getParent))
        assert(Files.readString(destination, StandardCharsets.UTF_8) ==
          "deb https://example.test stable main\n")
        assert(hasMode(destination, "0600"))
        assert(outcome == PlanOperationOutcome.Completed(
          Vector(s"wrote source setup file 'Write docker source' to $destination mode=0600")
        ))
      finally deleteRecursively(tempDir)

    test("apply mode reports source file write failures"):
      val destination = Path.of("/etc/initkit/failing-source.list")
      val files   = RecordingSourceSetupFiles(failures = Map(destination -> "permission denied"))
      val outcome = SourceSetupExecutor(FakeCommandExecutor(Vector.empty), files).execute(
        SourceSetupPlan(
          Vector(SourceSetupOperation.WriteFile(
            "Write failing source",
            destination,
            "content",
            Some("0644"),
            sudo = false
          )),
          Vector.empty,
          aptUpdateBeforeInstall = false
        ),
        applyPolicy,
        sourceSetupSummary
      )

      val failure = outcome match
        case PlanOperationOutcome.Failed(value) => value
        case other                              => fail(s"expected failed outcome, got $other")

      assert(files.writes ==
        Vector(SourceSetupFileWrite(destination, "content", Some("0644"), sudo = false)))
      assert(failure.exitCode.isEmpty)
      assert(
        failure.message ==
          "source setup file write 'Write failing source' failed for /etc/initkit/failing-source.list: permission denied"
      )

    test("sudo-marked source file writes are passed to the file boundary and reported"):
      val destination = Path.of("/etc/apt/sources.list.d/docker.list")
      val files       = RecordingSourceSetupFiles()
      val outcome     = SourceSetupExecutor(FakeCommandExecutor(Vector.empty), files).execute(
        SourceSetupPlan(
          Vector(SourceSetupOperation.WriteFile(
            "Write sudo source",
            destination,
            "content",
            Some("0644"),
            sudo = true
          )),
          Vector.empty,
          aptUpdateBeforeInstall = false
        ),
        applyPolicy,
        sourceSetupSummary
      )

      assert(files.writes ==
        Vector(SourceSetupFileWrite(destination, "content", Some("0644"), sudo = true)))
      assert(outcome == PlanOperationOutcome.Completed(
        Vector(
          "wrote source setup file 'Write sudo source' to /etc/apt/sources.list.d/docker.list mode=0644 (sudo requested)"
        )
      ))

    test("dry-run returns source setup previews without running commands or writing files"):
      val command     = commandSpec("preview-source")
      val destination = Path.of("/etc/apt/sources.list.d/preview.list")
      val plan        = SourceSetupPlan(
        Vector(
          SourceSetupOperation.RunCommand("Preview command", command),
          SourceSetupOperation.WriteFile(
            "Preview file",
            destination,
            "content",
            Some("0644"),
            sudo = true
          )
        ),
        Vector.empty,
        aptUpdateBeforeInstall = true
      )
      val executor = FakeCommandExecutor(Vector.empty)
      val files    = RecordingSourceSetupFiles()
      val outcome  =
        SourceSetupExecutor(executor, files).execute(plan, dryRunPolicy, sourceSetupSummary)

      val dryRun = outcome match
        case PlanOperationOutcome.DryRun(data) => data
        case other                             => fail(s"expected dry-run outcome, got $other")

      assert(executor.calls.isEmpty)
      assert(files.writes.isEmpty)
      assert(dryRun == plan.dryRunData(sourceSetupSummary))

  private val sourceSetupSummary: PlanOperationSummary = PlanOperationSummary(
    index = -1,
    name = "source-setup",
    kind = "sources",
    description = Some("Configure package sources")
  )

  private val dryRunPolicy: ExecutionPolicy = ExecutionPolicy(
    mode = ExecutionRunMode.DryRun,
    continueOnError = false,
    requireSudo = true,
    reboot = RebootExecutionPolicy(allowed = false, prompt = true)
  )

  private val applyPolicy: ExecutionPolicy = dryRunPolicy.copy(mode = ExecutionRunMode.Apply)

  private def commandSpec(name: String): CommandSpec =
    CommandSpec.direct(Vector(CommandArgument(name)), sudo = SudoMode.Required)

  private def hasMode(path: Path, mode: String): Boolean =
    BinaryDownloadsExecutor.permissionsFromMode(mode) match
      case Left(_)         => false
      case Right(expected) =>
        try Files.getPosixFilePermissions(path).asScala.toSet == expected
        catch case _: UnsupportedOperationException => true

  private def deleteRecursively(path: Path): Unit = if Files.exists(path) then
    val stream = Files.walk(path)
    try stream.sorted(Comparator.reverseOrder()).forEach(Files.deleteIfExists)
    finally stream.close()

  private def fail(message: String): Nothing = throw new java.lang.AssertionError(message)

private final case class SourceSetupFileWrite(
    path: Path,
    content: String,
    mode: Option[String],
    sudo: Boolean
)

private final class RecordingSourceSetupFiles(
    failures: Map[Path, String] = Map.empty
) extends SourceSetupFiles:
  private val writesRef = AtomicReference(Vector.empty[SourceSetupFileWrite])

  def writes: Vector[SourceSetupFileWrite] = writesRef.get()

  override def writeFile(
      path: Path,
      content: String,
      mode: Option[String],
      sudo: Boolean
  ): Either[SourceSetupFileError, Unit] =
    writesRef.set(writesRef.get() :+ SourceSetupFileWrite(path, content, mode, sudo))
    failures.get(path) match
      case Some(message) => Left(SourceSetupFileError(message))
      case None          => Right(())
