package initkit.host

import java.nio.file.{Files, Path}

trait HostSystem:
  def osName: String
  def osArch: String
  def env(name: String): Option[String]
  def readFile(path: Path): Option[String]
  def isExecutableRegularFile(path: Path): Boolean
  def pathSeparator: String

object HostSystem:
  val live: HostSystem =
    new HostSystem:
      override def osName: String =
        System.getProperty("os.name", "")

      override def osArch: String =
        System.getProperty("os.arch", "")

      override def env(name: String): Option[String] =
        sys.env.get(name)

      override def readFile(path: Path): Option[String] =
        if Files.isRegularFile(path) then
          scala.util.Try(Files.readString(path)).toOption
        else None

      override def isExecutableRegularFile(path: Path): Boolean =
        Files.isRegularFile(path) && Files.isExecutable(path)

      override def pathSeparator: String =
        java.io.File.pathSeparator
