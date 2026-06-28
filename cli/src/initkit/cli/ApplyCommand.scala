package initkit.cli

import java.io.PrintWriter
import java.nio.file.{Path, Paths}
import java.time.Clock
import java.util.concurrent.Callable
import scala.compiletime.uninitialized
import scala.util.Try

import initkit.core.*
import initkit.host.HostDetector
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

  @CliOption(
    names = Array("--dry-run"),
    description = Array("Preview the selected work without applying changes.")
  )
  private var dryRun: Boolean = false

  @CliOption(
    names = Array("--yes"),
    description = Array("Skip interactive confirmations where supported.")
  )
  private var yes: Boolean = false

  @CliOption(
    names = Array("--color"),
    paramLabel = "auto|always|never",
    description = Array("Control ANSI color in plain CLI output. Defaults to auto.")
  )
  private var color: String = "auto"

  @CliOption(
    names = Array("--no-color"),
    description = Array("Disable ANSI color in plain CLI output.")
  )
  private var noColor: Boolean = false

  @CliOption(
    names = Array("--debug"),
    description = Array("Emit redacted diagnostic logs in addition to normal output.")
  )
  private var debug: Boolean = false

  @CliOption(
    names = Array("--debug-log"),
    paramLabel = "PATH",
    description = Array("Write redacted diagnostic logs to a file instead of stderr.")
  )
  private var debugLog: String = ""

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

  override def call(): Int = buildOptions() match
    case Left(message) =>
      commandErr.println(message)
      CommandLine.ExitCode.USAGE
    case Right(options) =>
      val debugLogger = options.debugLogger
      try runApply(options, debugLogger)
      finally debugLogger.close()

  private def runApply(options: ApplyCommandOptions, debugLogger: CliDebugLogger): Int =
    debugLogger.line("apply command started")
    debugLogger.line(s"yes=$yes")
    execute(options, debugLogger) match
      case Left(error) =>
        commandErr.println(error)
        debugLogger.line(s"apply command failed: $error")
        CommandLine.ExitCode.SOFTWARE
      case Right(report) =>
        ApplyReporter.print(report, commandOut, options.renderer)
        debugLogger.lines(ApplyReporter.debugLines(report))
        report.engineResult.exitCode

  private def execute(
      options: ApplyCommandOptions,
      debugLogger: CliDebugLogger
  ): Either[String, ApplyReport] =
    val clock     = Clock.systemUTC()
    val hostFacts = HostDetector.detect()
    debugLogger.line(
      s"detected host family=${hostFacts.os.family} architecture=${hostFacts.architecture}"
    )

    for
      context <- CliLaunchContextLoader.load(
        CliLaunchContextOptions(
          configPath = options.configPath,
          statePath = options.statePath,
          resetState = options.resetState,
          dryRun = options.dryRun,
          onlyValues = options.onlyValues,
          skipValues = options.skipValues,
          hostFacts = hostFacts,
          clock = clock
        )
      )
      result <- runEngine(context, clock)
    yield ApplyReport(
      manifest = context.manifest,
      hostFacts = context.hostFacts,
      statePath = context.statePath,
      state = context.state,
      sourceSetup = context.sourceSetup,
      selection = context.selection,
      engineResult = result,
      dryRun = options.dryRun,
      resumed = context.resumed,
      configPath = options.configPath
    )

  private def runEngine(
      context: CliLaunchContext,
      clock: Clock
  ): Either[String, ExecutionEngineResult] =
    val commandMode =
      if context.policy.mode == ExecutionRunMode.DryRun then CommandRunMode.DryRun
      else CommandRunMode.Apply
    val commandRunner =
      new ProcessCommandRunner(SudoStrategy.Passthrough, mode = commandMode, clock = clock)
    val installer = new PackageManagerInstallers(
      commandExecutor = commandRunner,
      aptUpdateBeforeInstall = context.sourceSetup.aptUpdateBeforeInstall,
      hostFacts = context.hostFacts
    )
    val sourceSetupExecutor = SourceSetupExecutor(commandRunner)
    val request             = ExecutionEngineRequest(
      manifest = context.manifest,
      selection = context.selectionRequest,
      hostFacts = context.hostFacts,
      state = context.state,
      statePath = context.statePath,
      policy = context.policy
    )

    ExecutionWithSourceSetup
      .run(
        request,
        installer,
        context.sourceSetup,
        sourceSetupExecutor,
        ExecutionStateWriter.live,
        clock
      )
      .left
      .map(_.message)

  private def buildOptions(): Either[String, ApplyCommandOptions] =
    for
      configPath   <- shared.configFile
      statePath    <- shared.stateFile
      colorMode    <- CliColorMode.parse(color)
      debugLogPath <- normalizeOptionalPath(debugLog)
      debugLogger  <- CliDebugLogger.fromOptions(debug, debugLogPath, commandErr)
    yield ApplyCommandOptions(
      configPath = configPath,
      statePath = statePath,
      resetState = shared.resetState,
      dryRun = dryRun,
      onlyValues = onlyValues.toVector,
      skipValues = skipValues.toVector,
      renderer = CliRenderer(
        CliColorSettings.resolve(
          mode = colorMode,
          noColor = noColor,
          noColorEnvironment = sys.env.contains("NO_COLOR"),
          stdoutIsTerminal = System.console() != null
        )
      ),
      debugLogger = debugLogger
    )

  private def normalizeOptionalPath(value: String): Either[String, Option[Path]] =
    if value.trim.isEmpty then Right(None)
    else
      Try(Paths.get(value).toAbsolutePath.normalize()).toEither
        .map(Some(_))
        .left
        .map(error => s"Invalid debug log path '$value': ${error.getMessage}")

  private def commandOut: PrintWriter = spec.commandLine().getOut()

  private def commandErr: PrintWriter = spec.commandLine().getErr()

private final case class ApplyCommandOptions(
    configPath: Path,
    statePath: Option[Path],
    resetState: Boolean,
    dryRun: Boolean,
    onlyValues: Vector[String],
    skipValues: Vector[String],
    renderer: CliRenderer,
    debugLogger: CliDebugLogger
)
