package initkit.core

import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}
import java.security.MessageDigest
import java.time.{Clock, Instant}
import scala.collection.immutable.VectorMap
import scala.util.Try

import initkit.config.*
import upickle.default.{ReadWriter, macroRW, readwriter, read as readJson, write as writeJson}

final case class ExecutionState(
    schemaVersion: Int,
    manifest: StateManifestIdentity,
    createdAt: Instant,
    updatedAt: Instant,
    lastCompleted: Option[String],
    nextPlanEntry: Option[String],
    entries: Vector[PlanEntryState]
)

object ExecutionState:
  val CurrentSchemaVersion: Int = 1

  given instantReadWriter: ReadWriter[Instant] =
    readwriter[String].bimap[Instant](_.toString, Instant.parse)

  given ReadWriter[ExecutionState] = macroRW

  def initial(manifest: Manifest, clock: Clock): ExecutionState =
    val now = clock.instant()
    val entries = manifest.spec.plan.zipWithIndex.map { case (entry, index) =>
      PlanEntryState.initial(index, entry)
    }

    ExecutionState(
      schemaVersion = CurrentSchemaVersion,
      manifest = StateManifestIdentity.fromManifest(manifest),
      createdAt = now,
      updatedAt = now,
      lastCompleted = None,
      nextPlanEntry = entries.flatMap(_.name).headOption,
      entries = entries
    )

  def completedNames(state: ExecutionState): Set[String] =
    state.entries.collect {
      case entry if entry.status == PlanEntryStatus.Completed => entry.name
    }.flatten.toSet

  def resumePoint(state: ExecutionState): ExecutionResumePoint =
    val completed = completedNames(state)
    val nextIndex = state.nextPlanEntry.flatMap: name =>
      state.entries.find(entry => entry.name.contains(name)).map(_.index)

    ExecutionResumePoint(
      lastCompleted = state.lastCompleted,
      nextPlanEntry = state.nextPlanEntry,
      completedEntryNames = completed,
      nextIndex = nextIndex
    )

  def markCompleted(
      state: ExecutionState,
      entryName: String,
      completedAt: Instant
  ): ExecutionState =
    val entryIndex = state.entries.indexWhere(entry => entry.name.contains(entryName))
    val entries = state.entries.map: entry =>
      if entry.index == entryIndex then
        entry.copy(
          status = PlanEntryStatus.Completed,
          startedAt = entry.startedAt.orElse(Some(completedAt)),
          completedAt = Some(completedAt),
          message = None
        )
      else entry

    state.copy(
      updatedAt = completedAt,
      lastCompleted = Some(entryName),
      nextPlanEntry = nextOpenEntryAfter(entries, entryIndex),
      entries = entries
    )

  def markStarted(
      state: ExecutionState,
      entryName: String,
      startedAt: Instant
  ): ExecutionState =
    val entries = state.entries.map: entry =>
      if entry.name.contains(entryName) then
        entry.copy(
          status = PlanEntryStatus.Running,
          startedAt = entry.startedAt.orElse(Some(startedAt)),
          message = None
        )
      else entry

    state.copy(updatedAt = startedAt, nextPlanEntry = Some(entryName), entries = entries)

  def markSkipped(
      state: ExecutionState,
      entryName: String,
      reasons: Vector[String],
      skippedAt: Instant
  ): ExecutionState =
    val entryIndex = state.entries.indexWhere(entry => entry.name.contains(entryName))
    val entries = state.entries.map: entry =>
      if entry.index == entryIndex then
        entry.copy(
          status = PlanEntryStatus.Skipped,
          completedAt = Some(skippedAt),
          message = Some(reasons.mkString("; "))
        )
      else entry

    state.copy(
      updatedAt = skippedAt,
      nextPlanEntry = nextOpenEntryAfter(entries, entryIndex),
      entries = entries
    )

  def markFailed(
      state: ExecutionState,
      entryName: String,
      message: String,
      failedAt: Instant,
      continueAfterFailure: Boolean
  ): ExecutionState =
    val entryIndex = state.entries.indexWhere(entry => entry.name.contains(entryName))
    val entries = state.entries.map: entry =>
      if entry.index == entryIndex then
        entry.copy(
          status = PlanEntryStatus.Failed,
          startedAt = entry.startedAt.orElse(Some(failedAt)),
          completedAt = Some(failedAt),
          message = Some(message)
        )
      else entry

    state.copy(
      updatedAt = failedAt,
      nextPlanEntry =
        if continueAfterFailure then nextOpenEntryAfter(entries, entryIndex)
        else Some(entryName),
      entries = entries
    )

  def markInterrupted(
      state: ExecutionState,
      entryName: String,
      reason: String,
      resumeFrom: Option[InterruptResumeFrom],
      interruptedAt: Instant
  ): ExecutionState =
    resumeFrom.getOrElse(InterruptResumeFrom.Current) match
      case InterruptResumeFrom.Next =>
        markCompleted(state, entryName, interruptedAt).copy(updatedAt = interruptedAt)
      case InterruptResumeFrom.Current =>
        val entries = state.entries.map: entry =>
          if entry.name.contains(entryName) then
            entry.copy(
              status = PlanEntryStatus.Interrupted,
              startedAt = entry.startedAt.orElse(Some(interruptedAt)),
              completedAt = Some(interruptedAt),
              message = Some(reason)
            )
          else entry

        state.copy(updatedAt = interruptedAt, nextPlanEntry = Some(entryName), entries = entries)

  private def nextOpenEntryAfter(
      entries: Vector[PlanEntryState],
      entryIndex: Int
  ): Option[String] =
    entries
      .drop(entryIndex + 1)
      .find(entry => !isTerminal(entry.status))
      .flatMap(_.name)

  private def isTerminal(status: PlanEntryStatus): Boolean =
    status match
      case PlanEntryStatus.Pending => false
      case PlanEntryStatus.Running => false
      case _                       => true

final case class StateManifestIdentity(
    name: Option[String],
    apiVersion: Option[String],
    kind: Option[String],
    fingerprint: String
)

object StateManifestIdentity:
  given ReadWriter[StateManifestIdentity] = macroRW

  def fromManifest(manifest: Manifest): StateManifestIdentity =
    StateManifestIdentity(
      name = manifest.metadata.name,
      apiVersion = manifest.apiVersion,
      kind = manifest.kind,
      fingerprint = ManifestFingerprint.sha256(manifest)
    )

final case class PlanEntryState(
    index: Int,
    name: Option[String],
    kind: Option[String],
    status: PlanEntryStatus,
    startedAt: Option[Instant],
    completedAt: Option[Instant],
    message: Option[String]
)

object PlanEntryState:
  import ExecutionState.instantReadWriter

  given ReadWriter[PlanEntryState] = macroRW

  def initial(index: Int, entry: PlanEntry): PlanEntryState =
    PlanEntryState(
      index = index,
      name = entry.name,
      kind = entry.kind,
      status = PlanEntryStatus.Pending,
      startedAt = None,
      completedAt = None,
      message = None
    )

enum PlanEntryStatus:
  case Pending, Running, Completed, Skipped, Failed, Interrupted

object PlanEntryStatus:
  given readWriter: ReadWriter[PlanEntryStatus] =
    readwriter[String].bimap[PlanEntryStatus](_.toString, PlanEntryStatus.valueOf)

final case class ExecutionResumePoint(
    lastCompleted: Option[String],
    nextPlanEntry: Option[String],
    completedEntryNames: Set[String],
    nextIndex: Option[Int]
)

object ExecutionStateStore:
  def load(path: Path): Either[ExecutionStateError, ExecutionState] =
    val normalizedPath = path.toAbsolutePath.normalize()

    Try(readJson[ExecutionState](Files.readString(normalizedPath))).toEither.left.map: error =>
      ExecutionStateError.InvalidStateFile(normalizedPath, error.getMessage)

  def write(path: Path, state: ExecutionState): Either[ExecutionStateError, Unit] =
    val normalizedPath = path.toAbsolutePath.normalize()

    Try(writeAtomic(normalizedPath, writeJson(state, indent = 2))).toEither.left.map: error =>
      ExecutionStateError.IoFailure(normalizedPath, error.getMessage)

  def loadOrInitialize(
      path: Path,
      manifest: Manifest,
      resetState: Boolean,
      clock: Clock
  ): Either[ExecutionStateError, ExecutionState] =
    val normalizedPath = path.toAbsolutePath.normalize()

    if resetState || !Files.exists(normalizedPath) then
      Right(ExecutionState.initial(manifest, clock))
    else
      load(normalizedPath).flatMap: state =>
        validateFresh(normalizedPath, state.manifest, StateManifestIdentity.fromManifest(manifest)).map(_ => state)

  private def validateFresh(
      path: Path,
      actual: StateManifestIdentity,
      expected: StateManifestIdentity
  ): Either[ExecutionStateError, Unit] =
    if actual.name != expected.name then
      Left(ExecutionStateError.StaleManifestName(path, expected.name, actual.name))
    else if actual.fingerprint != expected.fingerprint then
      Left(ExecutionStateError.StaleManifestFingerprint(path, expected.fingerprint, actual.fingerprint))
    else Right(())

  private def writeAtomic(path: Path, json: String): Unit =
    val parent = path.getParent
    if parent != null then Files.createDirectories(parent)

    val temp = Files.createTempFile(parent, s".${path.getFileName}.", ".tmp")
    try
      Files.writeString(temp, json, StandardCharsets.UTF_8)
      moveIntoPlace(temp, path)
    finally
      Files.deleteIfExists(temp)

  private def moveIntoPlace(temp: Path, target: Path): Unit =
    try Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    catch
      case _: AtomicMoveNotSupportedException =>
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)

enum ExecutionStateError:
  case InvalidStateFile(path: Path, detail: String)
  case IoFailure(path: Path, detail: String)
  case StaleManifestName(path: Path, expected: Option[String], actual: Option[String])
  case StaleManifestFingerprint(path: Path, expected: String, actual: String)

  def message: String =
    this match
      case InvalidStateFile(path, detail) =>
        s"Invalid execution state file '$path': $detail"
      case IoFailure(path, detail) =>
        s"Could not write execution state file '$path': $detail"
      case StaleManifestName(path, expected, actual) =>
        s"Execution state '$path' belongs to manifest '${describe(actual)}', expected '${describe(expected)}'. Use --reset-state to replace it."
      case StaleManifestFingerprint(path, _, _) =>
        s"Execution state '$path' was created for different manifest contents. Use --reset-state to replace it."

  private def describe(value: Option[String]): String =
    value.getOrElse("<unnamed>")

object ManifestFingerprint:
  def sha256(manifest: Manifest): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = canonicalManifest(manifest).getBytes(StandardCharsets.UTF_8)
    digest.digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString

  private def canonicalManifest(manifest: Manifest): String =
    obj(
      "apiVersion" -> opt(manifest.apiVersion.map(str)),
      "kind" -> opt(manifest.kind.map(str)),
      "metadata" -> metadata(manifest.metadata),
      "spec" -> spec(manifest.spec)
    )

  private def metadata(value: Metadata): String =
    obj(
      "name" -> opt(value.name.map(str)),
      "labels" -> stringMap(value.labels),
      "annotations" -> stringMap(value.annotations)
    )

  private def spec(value: ManifestSpec): String =
    obj(
      "target" -> opt(value.target.map(target)),
      "policy" -> opt(value.policy.map(policy)),
      "vars" -> stringMap(value.vars),
      "sources" -> opt(value.sources.map(sources)),
      "plan" -> seq(value.plan.map(planEntry))
    )

  private def target(value: Target): String =
    obj("os" -> opt(value.os.map(targetOs)))

  private def targetOs(value: TargetOs): String =
    obj(
      "family" -> opt(value.family.map(str)),
      "distribution" -> opt(value.distribution.map(str)),
      "version" -> opt(value.version.map(str)),
      "codename" -> opt(value.codename.map(str)),
      "architecture" -> opt(value.architecture.map(str)),
      "desktop" -> opt(value.desktop.map(str))
    )

  private def policy(value: Policy): String =
    obj(
      "dryRun" -> opt(value.dryRun.map(bool)),
      "continueOnError" -> opt(value.continueOnError.map(bool)),
      "requireSudo" -> opt(value.requireSudo.map(bool)),
      "reboot" -> opt(value.reboot.map(rebootPolicy))
    )

  private def rebootPolicy(value: RebootPolicy): String =
    obj(
      "allowed" -> opt(value.allowed.map(bool)),
      "prompt" -> opt(value.prompt.map(bool))
    )

  private def sources(value: Sources): String =
    obj(
      "raw" -> raw(value.raw)
    )

  private def planEntry(value: PlanEntry): String =
    obj(
      "name" -> opt(value.name.map(str)),
      "kind" -> opt(value.kind.map(str)),
      "description" -> opt(value.description.map(str)),
      "execution" -> opt(value.execution.map(execution)),
      "when" -> opt(value.when.map(condition)),
      "spec" -> opt(value.spec.map(raw))
    )

  private def execution(value: Execution): String =
    obj(
      "mode" -> opt(value.mode.map(str)),
      "maxConcurrency" -> opt(value.maxConcurrency.map(number)),
      "failFast" -> opt(value.failFast.map(bool)),
      "locks" -> seq(value.locks.map(str))
    )

  private def condition(value: Condition): String =
    obj(
      "os" -> opt(value.os.map(osCondition)),
      "commandExists" -> opt(value.commandExists.map(str)),
      "raw" -> raw(value.raw)
    )

  private def osCondition(value: OsCondition): String =
    obj(
      "family" -> opt(value.family.map(matchExpression)),
      "distribution" -> opt(value.distribution.map(matchExpression)),
      "version" -> opt(value.version.map(matchExpression)),
      "codename" -> opt(value.codename.map(matchExpression)),
      "architecture" -> opt(value.architecture.map(matchExpression)),
      "desktop" -> opt(value.desktop.map(matchExpression)),
      "raw" -> raw(value.raw)
    )

  private def matchExpression(value: MatchExpression): String =
    value match
      case MatchExpression.Exact(exact)  => obj("exact" -> str(exact))
      case MatchExpression.OneOf(values) => obj("oneOf" -> seq(values.map(str)))

  private def raw(value: RawYaml): String =
    value match
      case RawYaml.NullValue            => "null"
      case RawYaml.StringValue(value)   => str(value)
      case RawYaml.BooleanValue(value)  => bool(value)
      case RawYaml.IntegerValue(value)  => s"int(${value.toString})"
      case RawYaml.DecimalValue(value)  => s"dec(${value.bigDecimal.toPlainString})"
      case RawYaml.SequenceValue(items) => seq(items.map(raw))
      case RawYaml.MappingValue(fields) => map(fields.view.mapValues(raw).to(VectorMap))

  private def stringMap(values: VectorMap[String, String]): String =
    map(values.view.mapValues(str).to(VectorMap))

  private def map(values: VectorMap[String, String]): String =
    val fields = values.toVector.sortBy(_._1)
    obj(fields*)

  private def obj(fields: (String, String)*): String =
    fields.map { case (name, value) => s"${str(name)}=$value" }.mkString("{", "|", "}")

  private def seq(values: Iterable[String]): String =
    values.mkString("[", ",", "]")

  private def opt(value: Option[String]): String =
    value.map(inner => s"some($inner)").getOrElse("none")

  private def str(value: String): String =
    s"s${value.length}:$value"

  private def bool(value: Boolean): String =
    s"bool($value)"

  private def number(value: Int): String =
    s"int($value)"
