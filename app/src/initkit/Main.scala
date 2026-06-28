package initkit

import java.io.PrintWriter
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.Callable
import scala.compiletime.uninitialized
import scala.util.Try

import picocli.CommandLine
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.{Command, Mixin, Option as CliOption, Spec}
import upickle.default.write

object Main:
  @Command(
    name = "initkit",
    mixinStandardHelpOptions = true,
    version = Array("initkit 0.1.0"),
    subcommands = Array(classOf[ApplyCommand], classOf[InfoCommand], classOf[TuiCommand])
  )
  final class RootCommand extends Runnable:
    @Spec private var spec: CommandSpec = uninitialized

    override def run(): Unit =
      spec.commandLine().usage(spec.commandLine().getOut())

  final class SharedOptions:
    @CliOption(
      names = Array("--config"),
      paramLabel = "PATH",
      description = Array("YAML manifest path. Defaults to config.yaml.")
    )
    private var config: String = "config.yaml"

    @CliOption(
      names = Array("--state"),
      paramLabel = "PATH",
      description = Array("Read and write execution state in a separate JSON file.")
    )
    private var state: String = ""

    @CliOption(
      names = Array("--reset-state"),
      description = Array("Ignore and overwrite any existing execution state file.")
    )
    private var resetStateValue: Boolean = false

    def configFile: Either[String, Path] =
      normalizePath(config).flatMap { path =>
        if Files.isRegularFile(path) then Right(path)
        else Left(s"Config file not found: $path")
      }

    def stateFile: Either[String, Option[Path]] =
      if state.trim.isEmpty then Right(None)
      else normalizePath(state).map(Some(_))

    def resetState: Boolean =
      resetStateValue

    private def normalizePath(value: String): Either[String, Path] =
      Try(Paths.get(value).toAbsolutePath.normalize()).toEither.left.map { error =>
        s"Invalid path '$value': ${error.getMessage}"
      }

  @Command(
    name = "apply",
    description = Array("Preview or apply the workstation profile."),
    mixinStandardHelpOptions = true,
    version = Array("initkit 0.1.0")
  )
  final class ApplyCommand extends Callable[Int]:
    @Spec private var spec: CommandSpec = uninitialized

    @Mixin
    private var shared: SharedOptions = uninitialized

    @CliOption(names = Array("--dry-run"), description = Array("Preview the selected work without applying changes."))
    private var dryRun: Boolean = false

    @CliOption(names = Array("--yes"), description = Array("Skip interactive confirmations where supported."))
    private var yes: Boolean = false

    @CliOption(
      names = Array("--only"),
      paramLabel = "NAME_OR_KIND",
      description = Array("Run only matching plan entries. May be repeated.")
    )
    private var onlyValues: Array[String] = Array.empty

    @CliOption(
      names = Array("--skip"),
      paramLabel = "NAME_OR_KIND",
      description = Array("Skip matching plan entries. May be repeated.")
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
            case Right(statePath) =>
              writeApplySummary(configPath, statePath)
              CommandLine.ExitCode.OK

    private def writeApplySummary(configPath: Path, statePath: Option[Path]): Unit =
      commandOut.println("Apply command parsed successfully.")
      commandOut.println(s"config: $configPath")
      statePath.foreach(path => commandOut.println(s"state: $path"))
      commandOut.println(s"reset-state: ${shared.resetState}")
      commandOut.println(s"dry-run: $dryRun")
      commandOut.println(s"yes: $yes")
      writeSelections("only", onlyValues)
      writeSelections("skip", skipValues)
      commandOut.println("Plan loading and execution will be implemented in later tasks.")

    private def writeSelections(label: String, values: Array[String]): Unit =
      if values.nonEmpty then commandOut.println(s"$label: ${values.mkString(",")}")

    private def commandOut: PrintWriter =
      spec.commandLine().getOut()

    private def commandErr: PrintWriter =
      spec.commandLine().getErr()

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

  def commandLine(): CommandLine =
    new CommandLine(new RootCommand())

  def main(args: Array[String]): Unit =
    val exitCode = commandLine().execute(args*)
    sys.exit(exitCode)
