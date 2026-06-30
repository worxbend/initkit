package binstaller.core

import scala.util.matching.Regex

private[core] object Sha256SumChecksumFile:

  private val HashPattern: Regex = "(?i)^[0-9a-f]{64}$".r

  def find(content: String, file: String): Option[String] = content.linesIterator
    .flatMap(parseLine)
    .find((_, candidateFile) => candidateFile == file || fileName(candidateFile) == file)
    .map((checksum, _) => checksum.toLowerCase(java.util.Locale.ROOT))

  private def parseLine(line: String): Option[(String, String)] =
    val trimmed = line.trim
    if trimmed.isEmpty || trimmed.startsWith("#") then None
    else
      val parts = trimmed.split("\\s+", 2).toVector
      parts match
        case Vector(hash, path) if HashPattern.pattern.matcher(hash).matches() =>
          Some(hash -> path.stripPrefix("*").trim)
        case _ => None

  private def fileName(path: String): String =
    path.split('/').toVector.filter(_.nonEmpty).lastOption.getOrElse(path)
