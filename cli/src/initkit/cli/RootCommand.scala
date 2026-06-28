package initkit.cli

import scala.compiletime.uninitialized

import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.{Command, Spec}

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
