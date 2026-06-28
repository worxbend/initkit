package initkit.cli

import java.nio.file.{Files, Path, Paths}
import java.time.Clock
import scala.collection.immutable.VectorMap

import initkit.config.{Manifest, ManifestLoadError}
import initkit.core.*
import initkit.host.HostFacts

private[cli] final case class CliLaunchContextOptions(
    configPath: Path,
    statePath: Option[Path],
    resetState: Boolean,
    dryRun: Boolean,
    onlyValues: Vector[String],
    skipValues: Vector[String],
    hostFacts: HostFacts,
    clock: Clock
)

private[cli] final case class CliStateFileContext(
    path: Path,
    existedBeforeLoad: Boolean,
    resetRequested: Boolean
)

private[cli] final case class CliLaunchContext(
    manifest: Manifest,
    hostFacts: HostFacts,
    configPath: Path,
    statePath: Path,
    stateFile: CliStateFileContext,
    state: ExecutionState,
    policy: ExecutionPolicy,
    sourceSetup: SourceSetupPlan,
    selectionRequest: PlanSelectionRequest,
    selection: PlanSelection
):
  def resumed: Boolean = stateFile.existedBeforeLoad

private[cli] object CliLaunchContextLoader:

  def load(options: CliLaunchContextOptions): Either[String, CliLaunchContext] =
    for
      manifest <- loadManifest(options.configPath, options.hostFacts)
      statePath         = resolveStatePath(options.statePath, manifest, options.configPath)
      existedBeforeLoad = !options.resetState && Files.exists(statePath)
      state <- ExecutionStateStore
        .loadOrInitialize(statePath, manifest, options.resetState, options.clock)
        .left
        .map(_.message)
      policy = ExecutionPolicy.fromManifest(
        manifest.spec.policy,
        Some(if options.dryRun then ExecutionRunMode.DryRun else ExecutionRunMode.Apply)
      )
      sourceSetup = SourceSetupGenerator.generate(manifest.spec.sources, options.hostFacts, policy)
      selectionRequest = PlanSelectionRequest.fromFilters(
        options.onlyValues,
        options.skipValues,
        ExecutionState.completedNames(state)
      )
      selection = PlanSelector.select(manifest, selectionRequest, options.hostFacts)
    yield CliLaunchContext(
      manifest = manifest,
      hostFacts = options.hostFacts,
      configPath = options.configPath,
      statePath = statePath,
      stateFile = CliStateFileContext(
        path = statePath,
        existedBeforeLoad = existedBeforeLoad,
        resetRequested = options.resetState
      ),
      state = state,
      policy = policy,
      sourceSetup = sourceSetup,
      selectionRequest = selectionRequest,
      selection = selection
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
  ): Path = explicitStatePath
    .orElse(manifest.spec.vars.get("stateFile").map(Paths.get(_)))
    .getOrElse(configPath.resolveSibling(
      s".${manifest.metadata.name.getOrElse("initkit")}.state.json"
    ))
    .toAbsolutePath
    .normalize()

  private def runtimeVariables: RuntimeVariables = RuntimeVariables(
    VectorMap.from(
      Vector(
        "HOME" -> sys.env.getOrElse("HOME", System.getProperty("user.home", "")),
        "USER" -> sys.env.getOrElse("USER", System.getProperty("user.name", ""))
      ).filter(_._2.nonEmpty)
    )
  )

  private def describeManifestError(error: ManifestLoadError): String = error.message
