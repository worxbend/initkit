package initkit.cli

import java.io.PrintWriter
import java.util.concurrent.Callable
import scala.compiletime.uninitialized

import initkit.tui.TambouiApp
import picocli.CommandLine
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.{Command, Mixin, Option as CliOption, Spec}

@Command(
  name = "tui",
  description = Array("Open the starter terminal UI."),
  mixinStandardHelpOptions = true,
  version = Array("initkit 0.1.0")
)
final class TuiCommand extends Callable[Int]:
  @Spec private var spec: CommandSpec = uninitialized

  @Mixin
  private var shared: SharedOptions = uninitialized

  @CliOption(names = Array("-n", "--name"), description = Array("Name displayed in the TUI"))
  private var name: String = "initkit"

  @CliOption(names = Array("-t", "--title"), description = Array("Panel title"))
  private var title: String = "Initkit"

  @CliOption(names = Array("--dry-run"), description = Array("Start the TUI in preview mode."))
  private var dryRun: Boolean = false

  @CliOption(
    names = Array("--select"),
    paramLabel = "NAME_OR_KIND",
    description = Array("Preselect matching plan entries. May be repeated.")
  )
  private var selectedValues: Array[String] = Array.empty

  @CliOption(
    names = Array("--skip"),
    paramLabel = "NAME_OR_KIND",
    description = Array("Start with matching plan entries unselected. May be repeated.")
  )
  private var skipValues: Array[String] = Array.empty

  override def call(): Int =
    shared.configFile match
      case Left(message) =>
        commandErr.println(message)
        CommandLine.ExitCode.USAGE
      case Right(configPath) =>
        shared.stateFile match
          case Left(message) =>
            commandErr.println(message)
            CommandLine.ExitCode.USAGE
          case Right(_) =>
            commandOut.println(s"config: $configPath")
            commandOut.println(s"reset-state: ${shared.resetState}")
            commandOut.println(s"dry-run: $dryRun")
            writeSelections("select", selectedValues)
            writeSelections("skip", skipValues)
            TambouiApp.run(name, title)
            CommandLine.ExitCode.OK

  private def writeSelections(label: String, values: Array[String]): Unit =
    if values.nonEmpty then commandOut.println(s"$label: ${values.mkString(",")}")

  private def commandOut: PrintWriter =
    spec.commandLine().getOut()

  private def commandErr: PrintWriter =
    spec.commandLine().getErr()
