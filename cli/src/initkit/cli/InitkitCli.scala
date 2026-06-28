package initkit.cli

import picocli.CommandLine

object InitkitCli:
  def commandLine(): CommandLine =
    new CommandLine(new RootCommand())

  def execute(args: Array[String]): Int =
    commandLine().execute(args*)
