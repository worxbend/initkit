package binstaller.core

import java.net.http.HttpResponse
import upickle.default.*

/** One followed HTTP redirect from a response URL to the next request URL. */
final case class UrlRedirectHop(from: String, to: String, statusCode: Int)

/** Initial and final effective URLs observed at an HTTP boundary. */
final case class UrlProvenance(
    initialUrl: String,
    finalUrl: String,
    redirects: Vector[UrlRedirectHop]
):
  /** Whether the final URL differs from the request URL through a followed redirect. */
  def redirected: Boolean = redirects.nonEmpty || initialUrl != finalUrl

/** URL provenance constructors and rendering helpers. */
object UrlProvenance:
  /** Provenance for a response that did not follow redirects. */
  def direct(url: String): UrlProvenance = UrlProvenance(url, url, Vector.empty)

  /** Derive provenance from a JDK HTTP response and its previous-response chain. */
  def fromResponse(initialUrl: String, response: HttpResponse[?]): UrlProvenance =
    val previous  = previousResponses(response)
    val nextUrls  = previous.drop(1).map(_.uri().toString) :+ response.uri().toString
    val redirects = previous.zip(nextUrls).map:
      case (redirectResponse, nextUrl) =>
        UrlRedirectHop(redirectResponse.uri().toString, nextUrl, redirectResponse.statusCode())
    UrlProvenance(initialUrl, response.uri().toString, redirects)

  /** Render a compact parenthetical suffix only when redirects occurred. */
  def redirectSuffix(
      provenance: Option[UrlProvenance],
      redactions: SensitiveValueRedactions = SensitiveValueRedactions.empty
  ): String = provenance.filter(_.redirected) match
    case Some(value) => s" (${RenderSafety.display(redirectSummary(value), redactions)})"
    case None        => ""

  /** Render detailed provenance lines only when redirects occurred. */
  def redirectDetailLines(
      label: String,
      provenance: Option[UrlProvenance],
      redactions: SensitiveValueRedactions = SensitiveValueRedactions.empty
  ): Vector[String] = provenance.filter(_.redirected) match
    case Some(value) => RenderSafety.displayLines(
        Vector(
          s"$label initial url: ${value.initialUrl}",
          s"$label final url: ${value.finalUrl}",
          s"$label redirects: ${redirectChain(value)}"
        ),
        redactions
      )
    case None => Vector.empty

  /** Render the redirect chain without applying terminal safety; callers scrub at the boundary. */
  def redirectChainForDisplay(provenance: UrlProvenance): String = redirectChain(provenance)

  given ReadWriter[UrlRedirectHop] = macroRW
  given ReadWriter[UrlProvenance]  = macroRW

  private def previousResponses(response: HttpResponse[?]): Vector[HttpResponse[?]] =
    val previous = response.previousResponse()
    if previous.isPresent then previousResponses(previous.get()) :+ previous.get()
    else Vector.empty

  private def redirectSummary(provenance: UrlProvenance): String =
    s"final url: ${provenance.finalUrl}; redirects: ${redirectChain(provenance)}"

  private def redirectChain(provenance: UrlProvenance): String =
    if provenance.redirects.isEmpty then s"${provenance.initialUrl} -> ${provenance.finalUrl}"
    else
      provenance.redirects
        .map(hop => s"${hop.statusCode} ${hop.from} -> ${hop.to}")
        .mkString(", ")
