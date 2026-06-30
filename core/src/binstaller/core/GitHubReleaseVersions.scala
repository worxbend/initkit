package binstaller.core

import java.net.URI
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** GitHub release metadata used to annotate `versions` output. */
private[core] object GitHubReleaseVersions:

  def newerVersionsByTool(
      plan: ResolvedPlan,
      httpTextClient: HttpTextClient
  ): Map[String, String] = candidates(plan)
    .view
    .flatMap(candidate => newerVersion(candidate, httpTextClient).map(candidate.toolName -> _))
    .toMap

  private def candidates(
      plan: ResolvedPlan
  ): Vector[GitHubReleaseCandidate] = plan.tools.flatMap: tool =>
    for
      current <- concreteVersion(tool.version)
      repo    <- GitHubRepo.fromReleaseDownloadUrl(tool.download.url)
    yield GitHubReleaseCandidate(tool.name, repo, current)

  private def concreteVersion(version: ResolvedVersion): Option[String] = version match
    case ResolvedVersion.Concrete(value, _) if value.nonEmpty => Some(value)
    case ResolvedVersion.Concrete(_, _)                       => None
    case ResolvedVersion.DynamicLatestUrl(_)                  => None

  private def newerVersion(
      candidate: GitHubReleaseCandidate,
      httpTextClient: HttpTextClient
  ): Option[String] = latestTag(candidate.repo, httpTextClient).toOption.flatMap: latest =>
    Option.when(VersionOrdering.compare(latest, candidate.current) == VersionOrder.Greater)(latest)

  private def latestTag(repo: GitHubRepo, httpTextClient: HttpTextClient): Either[String, String] =
    httpTextClient.getText(repo.latestReleaseApiUrl)
      .left.map(_.message)
      .flatMap(parseLatestTag)

  private def parseLatestTag(json: String): Either[String, String] =
    Try(ujson.read(json)("tag_name").str.trim) match
      case Success(value) if value.nonEmpty => Right(value)
      case Success(_)                       => Left("empty tag_name")
      case Failure(error) => Left(s"invalid GitHub release JSON: ${error.getMessage}")

private[core] final case class GitHubReleaseCandidate(
    toolName: String,
    repo: GitHubRepo,
    current: String
)

private[core] final case class GitHubRepo(owner: String, name: String):
  def latestReleaseApiUrl: String = s"https://api.github.com/repos/$owner/$name/releases/latest"

private[core] object GitHubRepo:

  def fromReleaseDownloadUrl(url: String): Option[GitHubRepo] = Try(URI.create(url)) match
    case Success(uri) if Option(uri.getHost).contains("github.com") =>
      releaseDownloadPathSegments(uri).collect:
        case owner +: repo +: "releases" +: "download" +: _ if owner.nonEmpty && repo.nonEmpty =>
          GitHubRepo(owner, repo)
    case _ => None

  private def releaseDownloadPathSegments(uri: URI): Option[Vector[String]] =
    Option(uri.getPath).map:
      _.split('/').toVector.filter(_.nonEmpty)

private[core] enum VersionOrder:
  case Greater, Equal, Less, Unknown

private[core] object VersionOrdering:
  private val VersionToken = """(\d+(?:\.\d+)*)""".r

  def compare(left: String, right: String): VersionOrder =
    (versionNumbers(left), versionNumbers(right)) match
      case (Some(leftNumbers), Some(rightNumbers)) => compareNumbers(leftNumbers, rightNumbers)
      case _                                       => VersionOrder.Unknown

  private def versionNumbers(value: String): Option[Vector[Int]] =
    VersionToken.findFirstMatchIn(value).flatMap: matched =>
      Try(matched.group(1).split("\\.").toVector.map(_.toInt)).toOption

  private def compareNumbers(left: Vector[Int], right: Vector[Int]): VersionOrder =
    val size   = left.size.max(right.size)
    val padded = left.padTo(size, 0).zip(right.padTo(size, 0))
    padded.collectFirst:
      case (leftValue, rightValue) if leftValue > rightValue => VersionOrder.Greater
      case (leftValue, rightValue) if leftValue < rightValue => VersionOrder.Less
    .getOrElse(VersionOrder.Equal)
