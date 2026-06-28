package initkit.core

import java.time.Instant

import initkit.config.*

final case class ExecutionPolicy(
    mode: ExecutionRunMode,
    continueOnError: Boolean,
    requireSudo: Boolean,
    reboot: RebootExecutionPolicy
)

object ExecutionPolicy:
  def fromManifest(policy: Option[Policy], dryRunOverride: Option[ExecutionRunMode]): ExecutionPolicy =
    val manifestPolicy = policy.getOrElse(Policy(None, None, None, None))

    ExecutionPolicy(
      mode = dryRunOverride.getOrElse(runModeFrom(manifestPolicy.dryRun)),
      continueOnError = manifestPolicy.continueOnError.getOrElse(false),
      requireSudo = manifestPolicy.requireSudo.getOrElse(false),
      reboot = RebootExecutionPolicy.fromManifest(manifestPolicy.reboot)
    )

  private def runModeFrom(value: Option[Boolean]): ExecutionRunMode =
    if value.contains(true) then ExecutionRunMode.DryRun
    else ExecutionRunMode.Apply

enum ExecutionRunMode:
  case DryRun, Apply

final case class RebootExecutionPolicy(
    allowed: Boolean,
    prompt: Boolean
)

object RebootExecutionPolicy:
  def fromManifest(policy: Option[RebootPolicy]): RebootExecutionPolicy =
    val manifestPolicy = policy.getOrElse(RebootPolicy(None, None))

    RebootExecutionPolicy(
      allowed = manifestPolicy.allowed.getOrElse(false),
      prompt = manifestPolicy.prompt.getOrElse(true)
    )

final case class PlanEntryExecutionPolicy(
    mode: PlanEntryExecutionMode,
    maxConcurrency: Int,
    failFast: Boolean,
    locks: Vector[String]
)

object PlanEntryExecutionPolicy:
  def fromManifest(execution: Option[Execution]): PlanEntryExecutionPolicy =
    val manifestExecution = execution.getOrElse(Execution(None, None, None, Vector.empty))
    val mode = manifestExecution.mode match
      case Some("parallel") => PlanEntryExecutionMode.Parallel
      case _                => PlanEntryExecutionMode.Sequential

    PlanEntryExecutionPolicy(
      mode = mode,
      maxConcurrency = manifestExecution.maxConcurrency.getOrElse(1),
      failFast = manifestExecution.failFast.getOrElse(true),
      locks = manifestExecution.locks
    )

enum PlanEntryExecutionMode:
  case Sequential, Parallel

final case class PlanOperationSummary(
    index: Int,
    name: String,
    kind: String,
    description: Option[String]
)

object PlanOperationSummary:
  def fromPlanEntry(index: Int, entry: PlanEntry): Either[Vector[ManifestValidationError], PlanOperationSummary] =
    (required(entry.name, s"spec.plan[$index].name"), required(entry.kind, s"spec.plan[$index].kind")) match
      case (Right(name), Right(kind)) =>
        Right(PlanOperationSummary(index, name, kind, entry.description))
      case (nameResult, kindResult) =>
        Left(leftErrors(nameResult) ++ leftErrors(kindResult))

  private def required(value: Option[String], path: String): Either[Vector[ManifestValidationError], String] =
    value.map(_.trim).filter(_.nonEmpty) match
      case Some(trimmed) => Right(trimmed)
      case None          => Left(Vector(ManifestValidationError(path, "is required")))

  private def leftErrors[A](value: Either[Vector[ManifestValidationError], A]): Vector[ManifestValidationError] =
    value.left.toOption.getOrElse(Vector.empty)

final case class PackagePlanOperation[+S <: PackageSpec](
    summary: PlanOperationSummary,
    execution: PlanEntryExecutionPolicy,
    spec: S
)

final case class InstallerPlanOperation[+S <: InstallerSpec](
    summary: PlanOperationSummary,
    execution: PlanEntryExecutionPolicy,
    spec: S
)

enum PlanOperation:
  case AptPackages(operation: PackagePlanOperation[PackageSpec.Apt])
  case PacmanPackages(operation: PackagePlanOperation[PackageSpec.Pacman])
  case DnfPackages(operation: PackagePlanOperation[PackageSpec.Dnf])
  case ZypperPackages(operation: PackagePlanOperation[PackageSpec.Zypper])
  case FlatpakPackages(operation: PackagePlanOperation[PackageSpec.Flatpak])
  case SnapPackages(operation: PackagePlanOperation[PackageSpec.Snap])
  case BinaryDownloads(operation: InstallerPlanOperation[InstallerSpec.BinaryDownloads])
  case ShellScripts(operation: InstallerPlanOperation[InstallerSpec.ShellScripts])
  case NerdFonts(operation: InstallerPlanOperation[InstallerSpec.NerdFonts])
  case DotfilesApply(operation: InstallerPlanOperation[InstallerSpec.DotfilesApply])
  case Interrupt(operation: InstallerPlanOperation[InstallerSpec.Interrupt])
  case Commands(operation: InstallerPlanOperation[InstallerSpec.Commands])

  def summary: PlanOperationSummary =
    this match
      case AptPackages(operation)     => operation.summary
      case PacmanPackages(operation)  => operation.summary
      case DnfPackages(operation)     => operation.summary
      case ZypperPackages(operation)  => operation.summary
      case FlatpakPackages(operation) => operation.summary
      case SnapPackages(operation)    => operation.summary
      case BinaryDownloads(operation) => operation.summary
      case ShellScripts(operation)    => operation.summary
      case NerdFonts(operation)       => operation.summary
      case DotfilesApply(operation)   => operation.summary
      case Interrupt(operation)       => operation.summary
      case Commands(operation)        => operation.summary

  def execution: PlanEntryExecutionPolicy =
    this match
      case AptPackages(operation)     => operation.execution
      case PacmanPackages(operation)  => operation.execution
      case DnfPackages(operation)     => operation.execution
      case ZypperPackages(operation)  => operation.execution
      case FlatpakPackages(operation) => operation.execution
      case SnapPackages(operation)    => operation.execution
      case BinaryDownloads(operation) => operation.execution
      case ShellScripts(operation)    => operation.execution
      case NerdFonts(operation)       => operation.execution
      case DotfilesApply(operation)   => operation.execution
      case Interrupt(operation)       => operation.execution
      case Commands(operation)        => operation.execution

object PlanOperation:
  def decode(runnable: RunnablePlanEntry): Either[Vector[ManifestValidationError], PlanOperation] =
    decode(runnable.index, runnable.entry)

  def decode(index: Int, entry: PlanEntry): Either[Vector[ManifestValidationError], PlanOperation] =
    PlanOperationSummary.fromPlanEntry(index, entry).flatMap: summary =>
      val execution = PlanEntryExecutionPolicy.fromManifest(entry.execution)

      if PackageSpecDecoder.isPackageKind(summary.kind) then
        PackageSpecDecoder.decode(entry, index).flatMap(packageOperation(summary, execution))
      else if InstallerSpecDecoder.isInstallerKind(summary.kind) then
        InstallerSpecDecoder.decode(entry, index).flatMap(installerOperation(summary, execution))
      else
        Left(Vector(ManifestValidationError(s"spec.plan[$index].kind", s"unsupported plan kind '${summary.kind}'")))

  private def packageOperation(
      summary: PlanOperationSummary,
      execution: PlanEntryExecutionPolicy
  )(spec: PackageSpec): Either[Vector[ManifestValidationError], PlanOperation] =
    spec match
      case typed: PackageSpec.Apt =>
        Right(PlanOperation.AptPackages(PackagePlanOperation(summary, execution, typed)))
      case typed: PackageSpec.Pacman =>
        Right(PlanOperation.PacmanPackages(PackagePlanOperation(summary, execution, typed)))
      case typed: PackageSpec.Dnf =>
        Right(PlanOperation.DnfPackages(PackagePlanOperation(summary, execution, typed)))
      case typed: PackageSpec.Zypper =>
        Right(PlanOperation.ZypperPackages(PackagePlanOperation(summary, execution, typed)))
      case typed: PackageSpec.Flatpak =>
        Right(PlanOperation.FlatpakPackages(PackagePlanOperation(summary, execution, typed)))
      case typed: PackageSpec.Snap =>
        Right(PlanOperation.SnapPackages(PackagePlanOperation(summary, execution, typed)))

  private def installerOperation(
      summary: PlanOperationSummary,
      execution: PlanEntryExecutionPolicy
  )(spec: InstallerSpec): Either[Vector[ManifestValidationError], PlanOperation] =
    spec match
      case typed: InstallerSpec.BinaryDownloads =>
        Right(PlanOperation.BinaryDownloads(InstallerPlanOperation(summary, execution, typed)))
      case typed: InstallerSpec.ShellScripts =>
        Right(PlanOperation.ShellScripts(InstallerPlanOperation(summary, execution, typed)))
      case typed: InstallerSpec.NerdFonts =>
        Right(PlanOperation.NerdFonts(InstallerPlanOperation(summary, execution, typed)))
      case typed: InstallerSpec.DotfilesApply =>
        Right(PlanOperation.DotfilesApply(InstallerPlanOperation(summary, execution, typed)))
      case typed: InstallerSpec.Interrupt =>
        Right(PlanOperation.Interrupt(InstallerPlanOperation(summary, execution, typed)))
      case typed: InstallerSpec.Commands =>
        Right(PlanOperation.Commands(InstallerPlanOperation(summary, execution, typed)))

trait PlanOperationInstaller:
  def install(operation: PlanOperation, policy: ExecutionPolicy): PlanOperationOutcome =
    operation match
      case PlanOperation.AptPackages(typed)     => installApt(typed, policy)
      case PlanOperation.PacmanPackages(typed)  => installPacman(typed, policy)
      case PlanOperation.DnfPackages(typed)     => installDnf(typed, policy)
      case PlanOperation.ZypperPackages(typed)  => installZypper(typed, policy)
      case PlanOperation.FlatpakPackages(typed) => installFlatpak(typed, policy)
      case PlanOperation.SnapPackages(typed)    => installSnap(typed, policy)
      case PlanOperation.BinaryDownloads(typed) => installBinaryDownloads(typed, policy)
      case PlanOperation.ShellScripts(typed)    => installShellScripts(typed, policy)
      case PlanOperation.NerdFonts(typed)       => installNerdFonts(typed, policy)
      case PlanOperation.DotfilesApply(typed)   => installDotfilesApply(typed, policy)
      case PlanOperation.Interrupt(typed)       => installInterrupt(typed, policy)
      case PlanOperation.Commands(typed)        => installCommands(typed, policy)

  def installApt(
      operation: PackagePlanOperation[PackageSpec.Apt],
      policy: ExecutionPolicy
  ): PlanOperationOutcome

  def installPacman(
      operation: PackagePlanOperation[PackageSpec.Pacman],
      policy: ExecutionPolicy
  ): PlanOperationOutcome

  def installDnf(
      operation: PackagePlanOperation[PackageSpec.Dnf],
      policy: ExecutionPolicy
  ): PlanOperationOutcome

  def installZypper(
      operation: PackagePlanOperation[PackageSpec.Zypper],
      policy: ExecutionPolicy
  ): PlanOperationOutcome

  def installFlatpak(
      operation: PackagePlanOperation[PackageSpec.Flatpak],
      policy: ExecutionPolicy
  ): PlanOperationOutcome

  def installSnap(
      operation: PackagePlanOperation[PackageSpec.Snap],
      policy: ExecutionPolicy
  ): PlanOperationOutcome

  def installBinaryDownloads(
      operation: InstallerPlanOperation[InstallerSpec.BinaryDownloads],
      policy: ExecutionPolicy
  ): PlanOperationOutcome

  def installShellScripts(
      operation: InstallerPlanOperation[InstallerSpec.ShellScripts],
      policy: ExecutionPolicy
  ): PlanOperationOutcome

  def installNerdFonts(
      operation: InstallerPlanOperation[InstallerSpec.NerdFonts],
      policy: ExecutionPolicy
  ): PlanOperationOutcome

  def installDotfilesApply(
      operation: InstallerPlanOperation[InstallerSpec.DotfilesApply],
      policy: ExecutionPolicy
  ): PlanOperationOutcome

  def installInterrupt(
      operation: InstallerPlanOperation[InstallerSpec.Interrupt],
      policy: ExecutionPolicy
  ): PlanOperationOutcome

  def installCommands(
      operation: InstallerPlanOperation[InstallerSpec.Commands],
      policy: ExecutionPolicy
  ): PlanOperationOutcome

enum PlanOperationOutcome:
  case Completed(details: Vector[String])
  case Failed(failure: PlanFailure)
  case Interrupted(interrupt: PlanInterrupt)
  case DryRun(data: DryRunOperationData)

enum PlanEvent:
  case Scheduled(operation: PlanOperationSummary, at: Instant)
  case Started(operation: PlanOperationSummary, at: Instant)
  case Skipped(operation: PlanOperationSummary, reasons: Vector[String], at: Instant)
  case Completed(operation: PlanOperationSummary, details: Vector[String], at: Instant)
  case Failed(operation: PlanOperationSummary, failure: PlanFailure, at: Instant)
  case Interrupted(operation: PlanOperationSummary, interrupt: PlanInterrupt, at: Instant)
  case DryRunOperation(operation: PlanOperationSummary, data: DryRunOperationData, at: Instant)

final case class PlanSkip(
    operation: PlanOperationSummary,
    reasons: Vector[String]
)

final case class PlanFailure(
    operation: PlanOperationSummary,
    message: String,
    exitCode: Option[Int]
)

final case class PlanInterrupt(
    operation: PlanOperationSummary,
    reason: String,
    statePath: Option[String],
    resumeFrom: Option[InterruptResumeFrom],
    instructions: Vector[String],
    exitCode: Int
)

final case class DryRunOperationData(
    operation: PlanOperationSummary,
    actions: Vector[DryRunAction]
)

enum DryRunAction:
  case Command(
      argv: Vector[String],
      shell: Option[String],
      sudo: Boolean,
      workingDirectory: Option[String]
  )
  case FileWrite(path: String, mode: Option[String], description: String)
  case StateWrite(path: String, resumeFrom: Option[InterruptResumeFrom])
  case Message(text: String)

final case class PlanResult(
    completed: Vector[PlanOperationSummary],
    skipped: Vector[PlanSkip],
    failed: Vector[PlanFailure],
    interrupted: Vector[PlanInterrupt],
    remaining: Vector[PlanOperationSummary]
):
  def counts: PlanResultCounts =
    PlanResultCounts(
      completed = completed.size,
      skipped = skipped.size,
      failed = failed.size,
      interrupted = interrupted.size,
      remaining = remaining.size
    )

object PlanResult:
  def fromEvents(events: Vector[PlanEvent], remaining: Vector[PlanOperationSummary]): PlanResult =
    PlanResult(
      completed = events.collect { case PlanEvent.Completed(operation, _, _) => operation },
      skipped = events.collect { case PlanEvent.Skipped(operation, reasons, _) => PlanSkip(operation, reasons) },
      failed = events.collect { case PlanEvent.Failed(_, failure, _) => failure },
      interrupted = events.collect { case PlanEvent.Interrupted(_, interrupt, _) => interrupt },
      remaining = remaining
    )

final case class PlanResultCounts(
    completed: Int,
    skipped: Int,
    failed: Int,
    interrupted: Int,
    remaining: Int
)
