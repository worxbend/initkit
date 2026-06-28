package initkit

import initkit.cli.InitkitCli

object Main:
  def main(args: Array[String]): Unit =
    val exitCode = InitkitCli.execute(args)
    sys.exit(exitCode)
