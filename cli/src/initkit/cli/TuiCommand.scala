package initkit.cli

import java.io.PrintWriter
import java.nio.file.{Files, Path, Paths}
import java.time.Clock
import java.util.concurrent.Callable
import scala.collection.immutable.VectorMap
import scala.compiletime.uninitialized
import scala.util.control.NonFatal

import initkit.config.{Manifest, ManifestLoadError}
import initkit.core.{ExecutionStateStore, ManifestVariableResolver, RuntimeVariables}
import initkit.host.{HostDetector, HostFacts}
import initkit.tui.TambouiApp
import initkit.tui.{TuiSelectionInputs, TuiStateFileInput, TuiViewModel, TuiViewModelRequest}
import picocli.CommandLine
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.{Command, Mixin, Option as CliOption, Spec}

@Command(
  name = "tui",
  description = Array("Open the interactive workstation plan UI."),
  mixinStandardHelpOptions = true,
  version = Array("initkit 0.1.0")
)
final class TuiCommand extends Callable[Int]:
  @Spec private var spec: CommandSpec = uninitialized

  @Mixin
  private var shared: SharedOptions = uninitialized

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
    buildViewModel() match
      case Left(message) =>
        commandErr.println(message)
        CommandLine.ExitCode.USAGE
      case Right(viewModel) =>
        try
          TambouiApp.run(viewModel)
          CommandLine.ExitCode.OK
        catch
          case NonFatal(error) =>
            commandErr.println(s"Unable to start terminal UI: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}")
            CommandLine.ExitCode.SOFTWARE

  private def buildViewModel(): Either[String, TuiViewModel] =
    shared.configFile match
      case Left(message) =>
        Left(message)
      case Right(configPath) =>
        shared.stateFile match
          case Left(message) =>
            Left(message)
          case Right(explicitStatePath) =>
            TuiCommandModelLoader.load(
              TuiCommandModelOptions(
                configPath = configPath,
                statePath = explicitStatePath,
                resetState = shared.resetState,
                dryRun = dryRun,
                selectedValues = selectedValues.toVector,
                skipValues = skipValues.toVector,
                hostFacts = HostDetector.detect(),
                clock = Clock.systemUTC()
              )
            )

  private def commandErr: PrintWriter =
    spec.commandLine().getErr()

private[cli] final case class TuiCommandModelOptions(
    configPath: Path,
    statePath: Option[Path],
    resetState: Boolean,
    dryRun: Boolean,
    selectedValues: Vector[String],
    skipValues: Vector[String],
    hostFacts: HostFacts,
    clock: Clock
)

private[cli] object TuiCommandModelLoader:
  def load(options: TuiCommandModelOptions): Either[String, TuiViewModel] =
    for
      manifest <- loadManifest(options.configPath, options.hostFacts)
      statePath = resolveStatePath(options.statePath, manifest, options.configPath)
      existedBeforeLoad = !options.resetState && Files.exists(statePath)
      state <- ExecutionStateStore
        .loadOrInitialize(statePath, manifest, options.resetState, options.clock)
        .left
        .map(_.message)
    yield
      TuiViewModel.from(
        TuiViewModelRequest(
          manifest = manifest,
          hostFacts = options.hostFacts,
          state = state,
          stateFile = TuiStateFileInput(
            path = statePath,
            existedBeforeLoad = existedBeforeLoad,
            resetRequested = options.resetState
          ),
          selection = TuiSelectionInputs.fromOptions(options.selectedValues, options.skipValues),
          dryRun = options.dryRun
        )
      )

  private def loadManifest(configPath: Path, hostFacts: HostFacts): Either[String, Manifest] =
    ManifestVariableResolver
      .loadValidatedResolved(configPath, runtimeVariables, hostFacts)
      .left
      .map(describeManifestError)

  private def resolveStatePath(
      explicitStatePath: Option[Path],
      manifest: Manifest,
      configPath: Path
  ): Path =
    explicitStatePath
      .orElse(manifest.spec.vars.get("stateFile").map(Paths.get(_)))
      .getOrElse(configPath.resolveSibling(s".${manifest.metadata.name.getOrElse("initkit")}.state.json"))
      .toAbsolutePath
      .normalize()

  private def runtimeVariables: RuntimeVariables =
    RuntimeVariables(
      VectorMap.from(
        Vector(
          "HOME" -> sys.env.getOrElse("HOME", System.getProperty("user.home", "")),
          "USER" -> sys.env.getOrElse("USER", System.getProperty("user.name", ""))
        ).filter(_._2.nonEmpty)
      )
    )

  private def describeManifestError(error: ManifestLoadError): String =
    error.message
