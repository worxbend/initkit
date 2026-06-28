package initkit.cli

import upickle.default.{ReadWriter, macroRW}

final case class AppSnapshot(name: String, cwd: String, files: Int)

object AppSnapshot:
  given ReadWriter[AppSnapshot] = macroRW

  private val skippedSegments = Set(".git", "out", ".bsp", ".metals", ".scala-build", "target")

  def collect(name: String, cwd: os.Path): AppSnapshot =
    val files =
      if os.exists(cwd) then
        os.walk(cwd).count { path =>
          os.isFile(path) && !path.relativeTo(cwd).segments.exists(skippedSegments.contains)
        }
      else 0

    AppSnapshot(name = name, cwd = cwd.toString, files = files)
