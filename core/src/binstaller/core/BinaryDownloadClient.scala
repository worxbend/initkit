package binstaller.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.Using

/** Expected failure from a binary download. */
final case class BinaryDownloadError(
    url: String,
    message: String,
    provenance: Option[UrlProvenance] = None
)

/** Downloaded artifact bytes paired with effective URL metadata. */
final case class BinaryDownloadResult(bytes: Array[Byte], provenance: UrlProvenance)

/** Download progress events emitted by binary download clients. */
enum BinaryDownloadProgress:
  case Started(url: String, totalBytes: Option[Long])
  case Advanced(url: String, downloadedBytes: Long, totalBytes: Option[Long])
  case Finished(url: String, downloadedBytes: Long, totalBytes: Option[Long])

/** Observer for raw download progress events. */
trait BinaryDownloadProgressObserver:
  /** Receive one download progress event. */
  def onProgress(progress: BinaryDownloadProgress): Unit

/** Download progress observer constructors. */
object BinaryDownloadProgressObserver:
  /** Observer that ignores all progress events. */
  val none: BinaryDownloadProgressObserver = _ => ()

/** Boundary for fetching binary artifact bytes. */
trait BinaryDownloadClient:
  /** Download bytes without progress callbacks. */
  def download(url: String): Either[BinaryDownloadError, Array[Byte]]

  /** Download bytes and optionally emit progress callbacks. */
  def download(
      url: String,
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, Array[Byte]] = progressObserver match
    case _ => download(url)

  /** Download bytes and report the initial URL, final URL, and redirect chain. */
  def downloadWithProvenance(url: String): Either[BinaryDownloadError, BinaryDownloadResult] =
    downloadWithProvenance(url, BinaryDownloadProgressObserver.none)

  /** Download bytes with progress callbacks and effective URL metadata. */
  def downloadWithProvenance(
      url: String,
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, BinaryDownloadResult] = download(url, progressObserver).map(
    bytes => BinaryDownloadResult(bytes, UrlProvenance.direct(url))
  )

/** Binary download client constructors. */
object BinaryDownloadClient:
  /** JDK HTTP implementation with HTTPS, redirects, timeout, size, and body-time limits. */
  def jdk: BinaryDownloadClient = JdkBinaryDownloadClient(RuntimeHttpClient.create())

/** Runtime limits applied while reading downloaded artifact bodies. */
final case class BinaryDownloadLimits(maxBytes: Long, bodyTimeout: Duration)

/** Default binary-download limit values. */
object BinaryDownloadLimits:

  /** Conservative default sized for developer tools while bounding memory and stalled bodies. */
  val default: BinaryDownloadLimits = BinaryDownloadLimits(
    maxBytes = 512L * 1024L * 1024L,
    bodyTimeout = Duration.ofMinutes(30)
  )

private[core] final class JdkBinaryDownloadClient(
    client: HttpClient,
    limits: BinaryDownloadLimits = BinaryDownloadLimits.default
) extends BinaryDownloadClient:

  def download(url: String): Either[BinaryDownloadError, Array[Byte]] =
    download(url, BinaryDownloadProgressObserver.none)

  override def download(
      url: String,
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, Array[Byte]] =
    downloadWithProvenance(url, progressObserver).map(_.bytes)

  override def downloadWithProvenance(
      url: String,
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, BinaryDownloadResult] = RuntimeUrl.httpsUri(url) match
    case Left(message) => Left(BinaryDownloadError(url, message))
    case Right(uri)    =>
      val request = HttpRequest.newBuilder(uri).timeout(RuntimeHttpClient.requestTimeout).GET()
        .build()
      Try(client.send(request, HttpResponse.BodyHandlers.ofInputStream())) match
        case Success(response) if response.statusCode() >= 200 && response.statusCode() < 300 =>
          val provenance = UrlProvenance.fromResponse(url, response)
          RuntimeUrl.httpsUri(provenance.finalUrl) match
            case Left(message) => Left(BinaryDownloadError(url, message, Some(provenance)))
            case Right(_)      => readBody(provenance, response, progressObserver)
        case Success(response) =>
          val provenance = UrlProvenance.fromResponse(url, response)
          Left(BinaryDownloadError(url, s"HTTP ${response.statusCode()}", Some(provenance)))
        case Failure(error) => Left(BinaryDownloadError(url, error.getMessage))

  private def readBody(
      provenance: UrlProvenance,
      response: HttpResponse[InputStream],
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, BinaryDownloadResult] =
    val totalBytes = response.headers().firstValueAsLong("Content-Length") match
      case value if value.isPresent && value.getAsLong >= 0L => Some(value.getAsLong)
      case _                                                 => None

    Try:
      Using.resource(response.body()): input =>
        // The whole artifact is currently materialized because checksum and archive extraction
        // operate on bytes; size and body-time limits bound that risk until streaming exists.
        BoundedBinaryBodyReader.read(
          provenance.finalUrl,
          input,
          totalBytes,
          limits,
          progressObserver
        )
    match
      case Success(result) => result
          .map(bytes => BinaryDownloadResult(bytes, provenance))
          .left
          .map(error => error.copy(url = provenance.initialUrl, provenance = Some(provenance)))
      case Failure(error) =>
        Left(BinaryDownloadError(provenance.initialUrl, error.getMessage, Some(provenance)))

private[core] object BoundedBinaryBodyReader:

  def read(
      url: String,
      input: InputStream,
      totalBytes: Option[Long],
      limits: BinaryDownloadLimits,
      progressObserver: BinaryDownloadProgressObserver,
      nowNanos: () => Long = () => System.nanoTime()
  ): Either[BinaryDownloadError, Array[Byte]] = totalBytes match
    // Reject oversized declared bodies before reading, then enforce the same limit while reading
    // because Content-Length can be absent or wrong.
    case Some(length) if length > limits.maxBytes =>
      Left(BinaryDownloadError(url, maxSizeMessage(length, limits.maxBytes)))
    case _ => readBounded(url, input, totalBytes, limits, progressObserver, nowNanos)

  private def readBounded(
      url: String,
      input: InputStream,
      totalBytes: Option[Long],
      limits: BinaryDownloadLimits,
      progressObserver: BinaryDownloadProgressObserver,
      nowNanos: () => Long
  ): Either[BinaryDownloadError, Array[Byte]] = Try:
    val deadline = nowNanos() + limits.bodyTimeout.toNanos
    progressObserver.onProgress(BinaryDownloadProgress.Started(url, totalBytes))
    val output = ByteArrayOutputStream()
    val buffer = Array.ofDim[Byte](64 * 1024)
    var read   = input.read(buffer)
    var total  = 0L

    while read != -1 do
      rejectAfterDeadline(nowNanos(), deadline, limits.bodyTimeout)
      total += read.toLong
      if total > limits.maxBytes then
        throw IllegalArgumentException(
          maxSizeMessage(total, limits.maxBytes)
        )
      output.write(buffer, 0, read)
      progressObserver.onProgress(BinaryDownloadProgress.Advanced(url, total, totalBytes))
      read = input.read(buffer)

    rejectAfterDeadline(nowNanos(), deadline, limits.bodyTimeout)
    progressObserver.onProgress(BinaryDownloadProgress.Finished(url, total, totalBytes))
    output.toByteArray
  match
    case Success(bytes) => Right(bytes)
    case Failure(error) => Left(BinaryDownloadError(url, error.getMessage))

  private def rejectAfterDeadline(now: Long, deadline: Long, timeout: Duration): Unit =
    if now > deadline then
      throw IllegalArgumentException(s"download body timed out after ${timeout.toSeconds}s")

  private def maxSizeMessage(actual: Long, maxBytes: Long): String =
    s"download size $actual exceeds max allowed $maxBytes bytes"
