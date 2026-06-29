package binstaller.core

import binstaller.config.AllowSudoSymlinks
import binstaller.config.ArchiveSpec
import binstaller.config.ChecksumSpec
import binstaller.config.ConfigLoadError
import binstaller.config.ConfigModule
import binstaller.config.ExecutableMode
import binstaller.config.SymlinkPrivilege
import binstaller.config.ValidationError

/** Variable-resolution inputs and display redaction policy for manifest resolution. */
final case class ResolutionOptions(
    runtimeVariables: Map[String, String],
    redactions: SensitiveValueRedactions
)

/** Resolution option constructors. */
object ResolutionOptions:

  /** Build options from explicit runtime variables and derive sensitive-value redactions. */
  def apply(runtimeVariables: Map[String, String]): ResolutionOptions = ResolutionOptions(
    runtimeVariables,
    SensitiveValueRedactions.fromRuntimeVariables(runtimeVariables)
  )

  /** Build options from the current process environment. */
  def fromEnvironment(): ResolutionOptions = ResolutionOptions(sys.env.toMap)

/** Resolved install plan after interpolation, version lookup, and validation. */
final case class ResolvedPlan(
    policy: ResolvedPolicy,
    tools: Vector[ResolvedTool],
    redactions: SensitiveValueRedactions = SensitiveValueRedactions.empty
)

/** Resolved profile-wide policy used by apply execution. */
final case class ResolvedPolicy(
    appsDir: String,
    stateFile: Option[String],
    allowSudoSymlinks: AllowSudoSymlinks,
    requireConfirmation: RequireConfirmation,
    continueOnError: ContinueOnError
)

/** Whether non-dry-run apply requires explicit confirmation. */
enum RequireConfirmation:
  case Enabled, Disabled

/** Helpers for converting manifest booleans into confirmation policy. */
object RequireConfirmation:
  /** Convert a manifest boolean into [[RequireConfirmation]]. */
  def fromBoolean(value: Boolean): RequireConfirmation = if value then Enabled else Disabled

/** Whether apply should continue after a failed tool. */
enum ContinueOnError:
  case Enabled, Disabled

/** Helpers for converting manifest booleans into continue-on-error policy. */
object ContinueOnError:
  /** Convert a manifest boolean into [[ContinueOnError]]. */
  def fromBoolean(value: Boolean): ContinueOnError = if value then Enabled else Disabled

/** One resolved binary tool ready for rendering or execution. */
final case class ResolvedTool(
    name: String,
    description: Option[String],
    version: ResolvedVersion,
    installDir: String,
    createDirectories: Vector[String],
    download: ResolvedDownload,
    executables: Vector[ResolvedExecutable],
    symlinks: Vector[ResolvedSymlink]
)

/** Tool version after resolution. */
enum ResolvedVersion:
  case Concrete(value: String, provenance: Option[UrlProvenance] = None)
  case DynamicLatestUrl(note: Option[String])

/** Rendering helpers for resolved versions. */
object ResolvedVersion:

  /** Render a resolved version for CLI/TUI display. */
  def render(version: ResolvedVersion): String = version match
    case ResolvedVersion.Concrete(value, _)  => value
    case ResolvedVersion.DynamicLatestUrl(_) => "dynamic latest-url"

/** Resolved download fields after interpolation and URL validation. */
final case class ResolvedDownload(
    url: String,
    filename: String,
    checksum: Option[ChecksumSpec],
    archive: Option[ResolvedArchive]
)

/** Resolved archive mappings paired with the original archive declaration. */
final case class ResolvedArchive(
    original: ArchiveSpec,
    files: Vector[ResolvedExtractMapping],
    directories: Vector[ResolvedExtractMapping]
)

/** Resolved archive source to install-target mapping. */
final case class ResolvedExtractMapping(from: String, to: String)

/** Resolved executable path and optional manifest mode. */
final case class ResolvedExecutable(path: String, mode: Option[ExecutableMode])

/** Resolved symlink path, target, and privilege boundary. */
final case class ResolvedSymlink(path: String, target: String, privilege: SymlinkPrivilege)

/** Expected failure while loading, resolving, or selecting a plan. */
enum ResolvePlanError:
  case ConfigLoadFailed(error: ConfigLoadError)
  case ValidationFailed(errors: Vector[ValidationError])
  case SelectionFailed(messages: Vector[String])

/** Rendering helpers for plan-resolution failures. */
object ResolvePlanError:

  /** Render resolution failures into scrubbed user-facing lines. */
  def renderLines(error: ResolvePlanError): Vector[String] = error match
    case ResolvePlanError.ConfigLoadFailed(loadError) => renderConfigLoadError(loadError)
    case ResolvePlanError.ValidationFailed(errors)    =>
      errors.map(error => RenderSafety.display(s"${error.path}: ${error.message}"))
    case ResolvePlanError.SelectionFailed(messages) =>
      messages.map(message => RenderSafety.display(s"selection: $message"))

  private def renderConfigLoadError(error: ConfigLoadError): Vector[String] = error match
    case ConfigLoadError.ValidationFailed(errors) =>
      errors.map(error => RenderSafety.display(s"${error.path}: ${error.message}"))
    case ConfigLoadError.ReadFailed(path, message) =>
      Vector(RenderSafety.display(s"config read failed for $path: $message"))
    case ConfigLoadError.ParseFailed(message) =>
      Vector(RenderSafety.display(s"config parse failed: $message"))

/** Snapshot consumed by the TUI without importing config internals. */
final case class ResolvedPlanSnapshot(
    profileName: String,
    manifestKind: String,
    configPath: String,
    stateFilePath: Option[String],
    plan: ResolvedPlan
)

/** Snapshot resolution helpers for renderers. */
object ResolvedPlanSnapshot:

  /** Resolve a snapshot using environment-derived resolution options. */
  def resolve(
      options: InstallerOptions,
      httpTextClient: HttpTextClient
  ): Either[ResolvePlanError, ResolvedPlanSnapshot] = resolve(
    options,
    httpTextClient,
    ResolutionOptions.fromEnvironment()
  )

  /** Resolve a snapshot with explicit resolution options for tests or alternate runtimes. */
  def resolve(
      options: InstallerOptions,
      httpTextClient: HttpTextClient,
      resolutionOptions: ResolutionOptions
  ): Either[ResolvePlanError, ResolvedPlanSnapshot] = ConfigModule.load(options.configPath) match
    case Left(error)    => Left(ResolvePlanError.ConfigLoadFailed(error))
    case Right(profile) => PlanResolver
        .resolve(profile, resolutionOptions, httpTextClient)
        .flatMap(plan => ToolSelector.select(plan, options.selection))
        .map: selectedPlan =>
          ResolvedPlanSnapshot(
            profileName = profile.metadata.name,
            manifestKind = profile.kind.value,
            configPath = options.configPath,
            stateFilePath = options.statePath.orElse(selectedPlan.policy.stateFile),
            plan = selectedPlan
          )
