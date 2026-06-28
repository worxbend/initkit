package initkit.cli

import java.io.PrintWriter
import java.util.concurrent.Callable
import scala.compiletime.uninitialized

import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.{Command, Option as CliOption, Spec}
import upickle.default.write

@Command(
  name = "info",
  description = Array("Print a workspace snapshot."),
  mixinStandardHelpOptions = true,
  version = Array("initkit 0.1.0")
)
final class InfoCommand extends Callable[Int]:
  @Spec private var spec: CommandSpec = uninitialized

  @CliOption(
    names = Array("-n", "--name"),
    description = Array("Application name to include in the snapshot")
  )
  private var name: String = "initkit"

  @CliOption(names = Array("--json"), description = Array("Print the snapshot as pretty JSON"))
  private var json: Boolean = false

  override def call(): Int =
    val snapshot = AppSnapshot.collect(name, os.pwd)

    if json then commandOut.println(write(snapshot, indent = 2))
    else
      commandOut.println(s"name:  ${snapshot.name}")
      commandOut.println(s"cwd:   ${snapshot.cwd}")
      commandOut.println(s"files: ${snapshot.files}")

    0

  private def commandOut: PrintWriter =
    spec.commandLine().getOut()
