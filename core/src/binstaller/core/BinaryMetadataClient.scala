package binstaller.core

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** Expected failure from a binary metadata lookup. */
final case class BinaryMetadataError(
    url: String,
    message: String,
    provenance: Option[UrlProvenance] = None
)

/** Metadata observed for a downloadable artifact without materializing the body. */
final case class BinaryMetadata(sizeBytes: Option[Long], provenance: UrlProvenance)

/** Boundary for resolving download URL provenance and content length for lock files. */
trait BinaryMetadataClient:
  /** Fetch metadata for a download URL. */
  def metadata(url: String): Either[BinaryMetadataError, BinaryMetadata]

/** Binary metadata client constructors. */
object BinaryMetadataClient:
  /** JDK HTTP implementation using HEAD with HTTPS, redirects, and timeout. */
  def jdk: BinaryMetadataClient = JdkBinaryMetadataClient(RuntimeHttpClient.create())

private[core] final class JdkBinaryMetadataClient(client: HttpClient) extends BinaryMetadataClient:

  def metadata(url: String): Either[BinaryMetadataError, BinaryMetadata] =
    RuntimeUrl.httpsUri(url) match
      case Left(message) => Left(BinaryMetadataError(url, message))
      case Right(uri)    =>
        val request = HttpRequest
          .newBuilder(uri)
          .timeout(RuntimeHttpClient.requestTimeout)
          .method("HEAD", HttpRequest.BodyPublishers.noBody())
          .build()
        Try(client.send(request, HttpResponse.BodyHandlers.discarding())) match
          case Success(response) if response.statusCode() >= 200 && response.statusCode() < 300 =>
            val provenance = UrlProvenance.fromResponse(url, response)
            RuntimeUrl.httpsUri(provenance.finalUrl) match
              case Right(_)      => Right(BinaryMetadata(contentLength(response), provenance))
              case Left(message) => Left(BinaryMetadataError(url, message, Some(provenance)))
          case Success(response) =>
            val provenance = UrlProvenance.fromResponse(url, response)
            Left(BinaryMetadataError(url, s"HTTP ${response.statusCode()}", Some(provenance)))
          case Failure(error) => Left(BinaryMetadataError(url, error.getMessage))

  private def contentLength(response: HttpResponse[?]): Option[Long] =
    response.headers().firstValueAsLong("Content-Length") match
      case value if value.isPresent && value.getAsLong >= 0L => Some(value.getAsLong)
      case _                                                 => None
