package binstaller.config

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

/** YAML loader used by [[ConfigModule]] and tests. */
object ConfigLoader:

  /** Read, parse, and validate a YAML profile from disk. */
  def load(path: Path): Either[ConfigLoadError, BinaryDistributionProfile] =
    Try(Files.readString(path)) match
      case Success(yaml)  => loadString(yaml)
      case Failure(error) => Left(ConfigLoadError.ReadFailed(path, error.getMessage))

  /** Parse and validate raw YAML profile text. */
  def loadString(yaml: String): Either[ConfigLoadError, BinaryDistributionProfile] =
    parseYaml(yaml).flatMap(loadParsedYaml)

  private def parseYaml(yaml: String): Either[ConfigLoadError, Any] =
    val settings = LoadSettings.builder().setLabel("binstaller-profile").build()
    Try(Load(settings).loadFromString(yaml)) match
      case Success(value)                      => Right(convertYaml(value))
      case Failure(error: YamlEngineException) =>
        Left(ConfigLoadError.ParseFailed(error.getMessage))
      case Failure(error) => Left(ConfigLoadError.ParseFailed(error.getMessage))

  private def loadParsedYaml(value: Any): Either[ConfigLoadError, BinaryDistributionProfile] =
    val decoded          = ManifestDecoder.decode(value)
    val validationErrors = ProfileValidator.validate(decoded.value)
    val errors           = decoded.errors ++ validationErrors
    if errors.isEmpty then Right(decoded.value)
    else Left(ConfigLoadError.ValidationFailed(errors))

  private def convertYaml(value: Any): Any = value match
    case map: java.util.Map[?, ?] => map.asScala.collect:
        case (key: String, child) => key -> convertYaml(child)
      .toMap
    case list: java.util.List[?] => list.asScala.map(convertYaml).toVector
    case scalar                  => scalar
