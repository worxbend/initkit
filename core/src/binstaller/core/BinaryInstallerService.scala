package binstaller.core

/** Boundary service consumed by CLI commands and tests. */
trait BinaryInstallerService:

  /** Render a script-friendly install plan without events. */
  def plan(options: InstallerOptions): InstallerResult =
    planWithEvents(options, InstallerEventObserver.none)

  /** Render a plan while emitting renderer-agnostic lifecycle events. */
  def planWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult

  /** Apply a plan without progress or lifecycle observers. */
  def apply(options: InstallerOptions): InstallerResult =
    applyWithEvents(options, InstallerEventObserver.none)

  /** Apply a plan while adapting download-only progress observers. */
  def applyWithProgress(
      options: InstallerOptions,
      progressObserver: BinaryDownloadProgressObserver
  ): InstallerResult = applyWithEvents(
    options,
    InstallerEventObserver.fromDownloadProgress(progressObserver)
  )

  /** Apply a plan while emitting renderer-agnostic lifecycle events. */
  def applyWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult

  /** Resolve and render the configured version sources. */
  def versions(options: InstallerOptions): InstallerResult

  /** Resolve and write a reproducible lock file without applying tools. */
  def lock(options: InstallerOptions, lockOptions: LockOptions): InstallerResult

/** Constructors for production and test service implementations. */
object BinaryInstallerService:
  /** Minimal placeholder used by early wiring tests. */
  def placeholder: BinaryInstallerService = PlaceholderBinaryInstallerService

  /** Create the production resolving service with the default installer and cwd state store. */
  def resolving(httpTextClient: HttpTextClient): BinaryInstallerService =
    resolving(httpTextClient, DirectBinaryInstaller.default)

  /** Create the production resolving service with explicit sudo credential handling. */
  def resolving(
      httpTextClient: HttpTextClient,
      sudoCredentials: SudoCredentialProvider
  ): BinaryInstallerService =
    resolving(httpTextClient, DirectBinaryInstaller.default(sudoCredentials))

  /** Create a resolving service with an injected installer and the default cwd state store. */
  def resolving(
      httpTextClient: HttpTextClient,
      installer: DirectBinaryInstaller
  ): BinaryInstallerService = ResolvingBinaryInstallerService(
    httpTextClient,
    ResolutionOptions.fromEnvironment(),
    installer,
    ApplyStateStore.cwd,
    BinaryMetadataClient.jdk,
    LockFileStore.nio
  )

  /** Create a resolving service with injectable installer and state storage boundaries. */
  def resolving(
      httpTextClient: HttpTextClient,
      installer: DirectBinaryInstaller,
      stateStore: ApplyStateStore
  ): BinaryInstallerService = ResolvingBinaryInstallerService(
    httpTextClient,
    ResolutionOptions.fromEnvironment(),
    installer,
    stateStore,
    BinaryMetadataClient.jdk,
    LockFileStore.nio
  )

  /** Create a resolving service with injectable installer, state, and lock metadata boundaries. */
  def resolving(
      httpTextClient: HttpTextClient,
      installer: DirectBinaryInstaller,
      stateStore: ApplyStateStore,
      metadataClient: BinaryMetadataClient,
      lockFileStore: LockFileStore
  ): BinaryInstallerService = ResolvingBinaryInstallerService(
    httpTextClient,
    ResolutionOptions.fromEnvironment(),
    installer,
    stateStore,
    metadataClient,
    lockFileStore
  )

private[core] object PlaceholderBinaryInstallerService extends BinaryInstallerService:

  def planWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult = placeholderResult("plan", options)

  def applyWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult = placeholderResult("apply", options)

  def versions(options: InstallerOptions): InstallerResult = placeholderResult("versions", options)

  def lock(options: InstallerOptions, lockOptions: LockOptions): InstallerResult =
    InstallerResult(Vector(s"binstaller lock placeholder for ${options.configPath}"), 0)

  private def placeholderResult(command: String, options: InstallerOptions): InstallerResult =
    InstallerResult(
      Vector(s"binstaller $command placeholder for ${options.configPath}"),
      0
    )
