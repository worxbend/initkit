package initkit.core

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.atomic.AtomicReference

import initkit.config.*
import utest.*

object BinaryDownloadsExecutorTests extends TestSuite:
  val tests: Tests = Tests:
    test("plain binary download writes to a temp file before replacing destination"):
      withTempDir: tempDir =>
        val destination = tempDir.resolve("bin").resolve("tool")
        Files.createDirectories(destination.getParent)
        Files.writeString(destination, "old", StandardCharsets.UTF_8)

        val bytes = "new binary\n".getBytes(StandardCharsets.UTF_8)
        val item = binaryItem(destination, bytes)
        val httpClient = RecordingBinaryDownloadHttpClient(Vector(
          RecordingBinaryDownloadResponse.bytes(item.url, bytes)
        ))
        val installer = new PackageManagerInstallers(
          FakeCommandExecutor(Vector.empty),
          binaryDownloadHttpClient = httpClient
        )

        val outcome = installer.install(PlanOperation.BinaryDownloads(operation(Vector(item))), applyPolicy)

        assert(outcome == PlanOperationOutcome.Completed(Vector("installed 1 binary download")))
        assert(Files.readAllBytes(destination).toVector == bytes.toVector)
        assert(httpClient.calls.size == 1)
        assert(httpClient.calls.head.config == BinaryDownloadHttpConfig.default)
        assert(httpClient.calls.head.destination != destination)
        assert(httpClient.calls.head.destination.getParent == destination.getParent)
        assert(!Files.exists(httpClient.calls.head.destination))
        assert(isExecutable(destination))

    test("HTTP failures include plan entry and item-level context"):
      withTempDir: tempDir =>
        val destination = tempDir.resolve("tool")
        val item = binaryItem(destination, "payload".getBytes(StandardCharsets.UTF_8))
        val httpClient = RecordingBinaryDownloadHttpClient(Vector(
          RecordingBinaryDownloadResponse.failure(
            BinaryDownloadHttpError.HttpStatus(item.url, 503, "service unavailable")
          )
        ))
        val installer = new PackageManagerInstallers(
          FakeCommandExecutor(Vector.empty),
          binaryDownloadHttpClient = httpClient
        )

        val outcome = installer.install(PlanOperation.BinaryDownloads(operation(Vector(item))), applyPolicy)
        val failure = outcome match
          case PlanOperationOutcome.Failed(value) => value
          case other                              => fail(s"expected failed outcome, got $other")

        assert(failure.operation.name == "direct-binaries")
        assert(failure.message.contains("plan entry 'direct-binaries'"))
        assert(failure.message.contains("tool download failed"))
        assert(failure.message.contains("HTTP 503"))
        assert(failure.message.contains(item.url))
        assert(failure.message.contains("service unavailable"))
        assert(!Files.exists(destination))

    test("SHA256 mismatch fails before installation"):
      withTempDir: tempDir =>
        val destination = tempDir.resolve("tool")
        val bytes = "unexpected content".getBytes(StandardCharsets.UTF_8)
        val item = binaryItem(destination, bytes).copy(
          checksum = Some(Checksum(ChecksumAlgorithm.Sha256, "0" * 64))
        )
        val httpClient = RecordingBinaryDownloadHttpClient(Vector(
          RecordingBinaryDownloadResponse.bytes(item.url, bytes)
        ))
        val installer = new PackageManagerInstallers(
          FakeCommandExecutor(Vector.empty),
          binaryDownloadHttpClient = httpClient
        )

        val outcome = installer.install(PlanOperation.BinaryDownloads(operation(Vector(item))), applyPolicy)
        val failure = outcome match
          case PlanOperationOutcome.Failed(value) => value
          case other                              => fail(s"expected failed outcome, got $other")

        assert(failure.message.contains("tool checksum mismatch"))
        assert(failure.message.contains("expected " + ("0" * 64)))
        assert(!Files.exists(destination))
        assert(!Files.exists(httpClient.calls.head.destination))

    test("dry-run previews direct downloads without calling HTTP"):
      withTempDir: tempDir =>
        val destination = tempDir.resolve("tool")
        val item = binaryItem(destination, "payload".getBytes(StandardCharsets.UTF_8))
        val httpClient = RecordingBinaryDownloadHttpClient(Vector.empty)
        val installer = new PackageManagerInstallers(
          FakeCommandExecutor(Vector.empty),
          binaryDownloadHttpClient = httpClient
        )

        val outcome = installer.install(PlanOperation.BinaryDownloads(operation(Vector(item))), dryRunPolicy)
        val dryRun = outcome match
          case PlanOperationOutcome.DryRun(data) => data
          case other                            => fail(s"expected dry-run outcome, got $other")

        assert(httpClient.calls.isEmpty)
        assert(!Files.exists(destination))
        assert(dryRun.actions == Vector(
          DryRunAction.Message(
            s"download binary 'tool' from ${item.url} to ${destination.getParent.resolve(".initkit-tool.download")} with sha256 verification"
          ),
          DryRunAction.FileWrite(destination.toString, Some("0755"), "install binary download 'tool'")
        ))

    test("archive items fail clearly until archive extraction is implemented"):
      withTempDir: tempDir =>
        val destination = tempDir.resolve("tool")
        val item = binaryItem(destination, "payload".getBytes(StandardCharsets.UTF_8)).copy(
          archive = Some(Archive(ArchiveType.TarGz, "tool", None))
        )
        val httpClient = RecordingBinaryDownloadHttpClient(Vector.empty)
        val installer = new PackageManagerInstallers(
          FakeCommandExecutor(Vector.empty),
          binaryDownloadHttpClient = httpClient
        )

        val outcome = installer.install(PlanOperation.BinaryDownloads(operation(Vector(item))), applyPolicy)
        val failure = outcome match
          case PlanOperationOutcome.Failed(value) => value
          case other                              => fail(s"expected failed outcome, got $other")

        assert(httpClient.calls.isEmpty)
        assert(failure.message.contains("tool archive extraction is not supported yet"))

  private val dryRunPolicy: ExecutionPolicy =
    ExecutionPolicy(
      mode = ExecutionRunMode.DryRun,
      continueOnError = false,
      requireSudo = true,
      reboot = RebootExecutionPolicy(allowed = false, prompt = true)
    )

  private val applyPolicy: ExecutionPolicy =
    dryRunPolicy.copy(mode = ExecutionRunMode.Apply)

  private def operation(items: Vector[BinaryDownloadItem]): InstallerPlanOperation[InstallerSpec.BinaryDownloads] =
    InstallerPlanOperation(
      summary = PlanOperationSummary(
        index = 3,
        name = "direct-binaries",
        kind = "binary-downloads",
        description = Some("Download standalone binaries and place them on PATH.")
      ),
      execution = PlanEntryExecutionPolicy(
        mode = PlanEntryExecutionMode.Sequential,
        maxConcurrency = 1,
        failFast = true,
        locks = Vector.empty
      ),
      spec = InstallerSpec.BinaryDownloads(items)
    )

  private def binaryItem(destination: Path, bytes: Array[Byte]): BinaryDownloadItem =
    BinaryDownloadItem(
      name = "tool",
      url = "https://example.test/tool",
      destination = destination.toString,
      mode = "0755",
      checksum = Some(Checksum(ChecksumAlgorithm.Sha256, sha256Hex(bytes))),
      archive = None
    )

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def isExecutable(path: Path): Boolean =
    try Files.getPosixFilePermissions(path).contains(PosixFilePermission.OWNER_EXECUTE)
    catch case _: UnsupportedOperationException => Files.isExecutable(path)

  private def withTempDir(test: Path => Unit): Unit =
    val path = Files.createTempDirectory("initkit-binary-downloads-test-")
    try test(path)
    finally deleteRecursively(path)

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      Files
        .walk(path)
        .sorted(Comparator.reverseOrder())
        .forEach(Files.deleteIfExists)

  private def fail(message: String): Nothing =
    throw new java.lang.AssertionError(message)

private final case class BinaryDownloadCall(
    url: String,
    destination: Path,
    config: BinaryDownloadHttpConfig
)

private enum RecordingBinaryDownloadResponse:
  case Bytes(url: String, bytes: Array[Byte])
  case Failure(error: BinaryDownloadHttpError)

private object RecordingBinaryDownloadResponse:
  def bytes(url: String, bytes: Array[Byte]): RecordingBinaryDownloadResponse =
    RecordingBinaryDownloadResponse.Bytes(url, bytes)

  def failure(error: BinaryDownloadHttpError): RecordingBinaryDownloadResponse =
    RecordingBinaryDownloadResponse.Failure(error)

private final class RecordingBinaryDownloadHttpClient(
    responses: Vector[RecordingBinaryDownloadResponse]
) extends BinaryDownloadHttpClient:
  private val stateRef = AtomicReference(RecordingBinaryDownloadHttpClientState(responses, Vector.empty))

  def calls: Vector[BinaryDownloadCall] =
    stateRef.get().calls

  override def download(
      url: String,
      destination: Path,
      config: BinaryDownloadHttpConfig
  ): Either[BinaryDownloadHttpError, BinaryDownloadHttpResponse] =
    val state = stateRef.get()
    val response = state.pending.headOption.getOrElse:
      RecordingBinaryDownloadResponse.Failure(BinaryDownloadHttpError.Transport(url, "no fake response configured"))

    stateRef.set(state.copy(pending = state.pending.drop(1), calls = state.calls :+ BinaryDownloadCall(url, destination, config)))

    response match
      case RecordingBinaryDownloadResponse.Bytes(expectedUrl, bytes) if expectedUrl == url =>
        Files.write(destination, bytes)
        Right(BinaryDownloadHttpResponse(200, Some(bytes.length.toLong)))
      case RecordingBinaryDownloadResponse.Bytes(expectedUrl, _) =>
        Left(BinaryDownloadHttpError.Transport(url, s"unexpected URL: expected $expectedUrl"))
      case RecordingBinaryDownloadResponse.Failure(error) =>
        Left(error)

private final case class RecordingBinaryDownloadHttpClientState(
    pending: Vector[RecordingBinaryDownloadResponse],
    calls: Vector[BinaryDownloadCall]
)
