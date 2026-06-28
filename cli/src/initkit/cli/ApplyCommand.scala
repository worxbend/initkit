package initkit.cli

import java.io.PrintWriter
import java.nio.file.Path
import java.util.concurrent.Callable
import scala.compiletime.uninitialized

import picocli.CommandLine
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.{Command, Mixin, Option as CliOption, Spec}

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
