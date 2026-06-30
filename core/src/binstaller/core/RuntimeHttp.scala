package binstaller.core

import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

private[core] object RuntimeHttpClient:
  val requestTimeout: Duration = Duration.ofSeconds(30)

  def create(): HttpClient = HttpClient.newBuilder()
    .connectTimeout(requestTimeout)
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

private[core] object RuntimeUrl:

  def httpsUri(url: String): Either[String, URI] = Try(URI.create(url)) match
    case Failure(error)                           => Left(s"invalid URL: ${error.getMessage}")
    case Success(uri) if uri.getScheme != "https" => Left("URL must use https")
    case Success(uri) if Option(uri.getHost).forall(_.isEmpty) => Left("URL must include a host")
    case Success(uri)                                          => Right(uri)
