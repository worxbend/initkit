package initkit.core

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream

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

    test("tar.gz archive extraction installs the selected member"):
      withTempDir: tempDir =>
        val destination = tempDir.resolve("tool")
        val selectedBytes = "selected tool\n".getBytes(StandardCharsets.UTF_8)
        val archiveBytes = tarGz(Vector(
          "docs/readme.txt" -> "readme".getBytes(StandardCharsets.UTF_8),
          "bin/tool" -> selectedBytes
        ))
        val item = binaryItem(destination, archiveBytes).copy(
          archive = Some(Archive(ArchiveType.TarGz, "bin/tool", None))
        )
        val httpClient = RecordingBinaryDownloadHttpClient(Vector(
          RecordingBinaryDownloadResponse.bytes(item.url, archiveBytes)
        ))
        val installer = new PackageManagerInstallers(
          FakeCommandExecutor(Vector.empty),
          binaryDownloadHttpClient = httpClient
        )

        val outcome = installer.install(PlanOperation.BinaryDownloads(operation(Vector(item))), applyPolicy)

        assert(outcome == PlanOperationOutcome.Completed(Vector("installed 1 binary download")))
        assert(Files.readAllBytes(destination).toVector == selectedBytes.toVector)
        assert(isExecutable(destination))
        assert(!Files.exists(httpClient.calls.head.destination))

    test("stripComponents is applied before selecting archive members"):
      withTempDir: tempDir =>
        val destination = tempDir.resolve("tool")
        val wrongBytes = "wrong\n".getBytes(StandardCharsets.UTF_8)
        val selectedBytes = "selected\n".getBytes(StandardCharsets.UTF_8)
        val archiveBytes = tarGz(Vector(
          "nested/tool" -> wrongBytes,
          "root/nested/tool" -> selectedBytes
        ))
        val item = binaryItem(destination, archiveBytes).copy(
          archive = Some(Archive(ArchiveType.TarGz, "nested/tool", Some(1)))
        )
        val httpClient = RecordingBinaryDownloadHttpClient(Vector(
          RecordingBinaryDownloadResponse.bytes(item.url, archiveBytes)
        ))
        val installer = new PackageManagerInstallers(
          FakeCommandExecutor(Vector.empty),
          binaryDownloadHttpClient = httpClient
        )

        val outcome = installer.install(PlanOperation.BinaryDownloads(operation(Vector(item))), applyPolicy)

        assert(outcome == PlanOperationOutcome.Completed(Vector("installed 1 binary download")))
        assert(Files.readAllBytes(destination).toVector == selectedBytes.toVector)

    test("parallel binary downloads honor maxConcurrency"):
      withTempDir: tempDir =>
        val items = (1 to 6).toVector.map: index =>
          binaryItem(tempDir.resolve(s"tool-$index"), s"payload-$index".getBytes(StandardCharsets.UTF_8)).copy(
            name = s"tool-$index",
            url = s"https://example.test/tool-$index"
          )
        val httpClient = ConcurrentBinaryDownloadHttpClient.successful(delayMillis = 75)
        val installer = new PackageManagerInstallers(
          FakeCommandExecutor(Vector.empty),
          binaryDownloadHttpClient = httpClient
        )

        val outcome = installer.install(
          PlanOperation.BinaryDownloads(
            operation(items, mode = PlanEntryExecutionMode.Parallel, maxConcurrency = 2, failFast = false)
          ),
          applyPolicy
        )

        assert(outcome == PlanOperationOutcome.Completed(Vector("installed 6 binary downloads")))
        assert(httpClient.calls.size == 6)
        assert(httpClient.maxObserved <= 2)

    test("parallel failFast stops remaining downloads when feasible"):
      withTempDir: tempDir =>
        val items = (1 to 12).toVector.map: index =>
          binaryItem(tempDir.resolve(s"tool-$index"), s"payload-$index".getBytes(StandardCharsets.UTF_8)).copy(
            name = s"tool-$index",
            url = s"https://example.test/tool-$index"
          )
        val httpClient = ConcurrentBinaryDownloadHttpClient.failFirst(delayMillis = 250)
        val installer = new PackageManagerInstallers(
          FakeCommandExecutor(Vector.empty),
          binaryDownloadHttpClient = httpClient
        )

        val outcome = installer.install(
          PlanOperation.BinaryDownloads(
            operation(items, mode = PlanEntryExecutionMode.Parallel, maxConcurrency = 2, failFast = true)
          ),
          applyPolicy
        )
        val failure = outcome match
          case PlanOperationOutcome.Failed(value) => value
          case other                              => fail(s"expected failed outcome, got $other")

        assert(failure.message.contains("tool-1 download failed"))
        assert(httpClient.calls.size < items.size)

  private val dryRunPolicy: ExecutionPolicy =
    ExecutionPolicy(
      mode = ExecutionRunMode.DryRun,
      continueOnError = false,
      requireSudo = true,
      reboot = RebootExecutionPolicy(allowed = false, prompt = true)
    )

  private val applyPolicy: ExecutionPolicy =
    dryRunPolicy.copy(mode = ExecutionRunMode.Apply)

  private def operation(
      items: Vector[BinaryDownloadItem],
      mode: PlanEntryExecutionMode = PlanEntryExecutionMode.Sequential,
      maxConcurrency: Int = 1,
      failFast: Boolean = true
  ): InstallerPlanOperation[InstallerSpec.BinaryDownloads] =
    InstallerPlanOperation(
      summary = PlanOperationSummary(
        index = 3,
        name = "direct-binaries",
        kind = "binary-downloads",
        description = Some("Download standalone binaries and place them on PATH.")
      ),
      execution = PlanEntryExecutionPolicy(
        mode = mode,
        maxConcurrency = maxConcurrency,
        failFast = failFast,
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

  private def tarGz(entries: Vector[(String, Array[Byte])]): Array[Byte] =
    val bytes = ByteArrayOutputStream()
    val gzip = GZIPOutputStream(bytes)
    try
      entries.foreach { case (name, content) => writeTarEntry(gzip, name, content) }
      gzip.write(Array.fill[Byte](1024)(0))
    finally gzip.close()
    bytes.toByteArray

  private def writeTarEntry(output: GZIPOutputStream, name: String, content: Array[Byte]): Unit =
    val header = Array.fill[Byte](512)(0)
    writeAscii(header, 0, 100, name)
    writeAscii(header, 100, 8, "0000755")
    writeAscii(header, 108, 8, "0000000")
    writeAscii(header, 116, 8, "0000000")
    writeAscii(header, 124, 12, Integer.toOctalString(content.length))
    writeAscii(header, 136, 12, "0000000")
    java.util.Arrays.fill(header, 148, 156, ' '.toByte)
    header(156) = '0'.toByte
    writeAscii(header, 257, 6, "ustar")
    writeAscii(header, 263, 2, "00")
    val checksum = header.map(byte => byte & 0xff).sum
    writeAscii(header, 148, 8, f"$checksum%06o")
    output.write(header)
    output.write(content)
    output.write(Array.fill[Byte](tarPadding(content.length))(0))

  private def writeAscii(bytes: Array[Byte], offset: Int, length: Int, value: String): Unit =
    val encoded = value.getBytes(StandardCharsets.US_ASCII)
    System.arraycopy(encoded, 0, bytes, offset, math.min(encoded.length, length))

  private def tarPadding(size: Int): Int =
    val remainder = size % 512
    if remainder == 0 then 0
    else 512 - remainder

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

private final class ConcurrentBinaryDownloadHttpClient private (
    delayMillis: Long,
    failFirstCall: Boolean
) extends BinaryDownloadHttpClient:
  private val callsQueue = ConcurrentLinkedQueue[BinaryDownloadCall]()
  private val activeCount = AtomicInteger(0)
  private val maxObservedCount = AtomicInteger(0)

  def calls: Vector[BinaryDownloadCall] =
    callsQueue.toArray.toVector.map(_.asInstanceOf[BinaryDownloadCall])

  def maxObserved: Int =
    maxObservedCount.get()

  override def download(
      url: String,
      destination: Path,
      config: BinaryDownloadHttpConfig
  ): Either[BinaryDownloadHttpError, BinaryDownloadHttpResponse] =
    callsQueue.add(BinaryDownloadCall(url, destination, config))
    val active = activeCount.incrementAndGet()
    updateMaxObserved(active)
    try
      if failFirstCall && url.endsWith("tool-1") then
        Left(BinaryDownloadHttpError.Transport(url, "planned failure"))
      else
        sleep(url) match
          case Left(error) =>
            Left(error)
          case Right(()) =>
            val bytes = payloadFor(url)
            Files.write(destination, bytes)
            Right(BinaryDownloadHttpResponse(200, Some(bytes.length.toLong)))
    finally activeCount.decrementAndGet()

  private def sleep(url: String): Either[BinaryDownloadHttpError, Unit] =
    try
      Thread.sleep(delayMillis)
      Right(())
    catch
      case _: InterruptedException =>
        Thread.currentThread().interrupt()
        Left(BinaryDownloadHttpError.Interrupted(url))

  private def updateMaxObserved(active: Int): Unit =
    var current = maxObservedCount.get()
    while active > current && !maxObservedCount.compareAndSet(current, active) do
      current = maxObservedCount.get()

  private def payloadFor(url: String): Array[Byte] =
    val name = url.drop(url.lastIndexOf('/') + 1)
    name.replace("tool-", "payload-").getBytes(StandardCharsets.UTF_8)

private object ConcurrentBinaryDownloadHttpClient:
  def successful(delayMillis: Long): ConcurrentBinaryDownloadHttpClient =
    new ConcurrentBinaryDownloadHttpClient(delayMillis, failFirstCall = false)

  def failFirst(delayMillis: Long): ConcurrentBinaryDownloadHttpClient =
    new ConcurrentBinaryDownloadHttpClient(delayMillis, failFirstCall = true)
