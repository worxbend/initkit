package initkit.core

import java.nio.file.{Files, Path}
import java.time.{Clock, Instant, ZoneOffset}
import scala.collection.immutable.VectorMap

import initkit.config.*
import utest.*

object ExecutionStateTests extends TestSuite:
  val tests: Tests = Tests:
    test("writes and reads state JSON with manifest identity and entry statuses"):
      withTempDir: tmp =>
        val statePath = tmp.resolve("nested").resolve("developer.state.json")
        val state = ExecutionState.initial(manifest("developer-workstation"), fixedClock)

        val writeResult = ExecutionStateStore.write(statePath, state)
        val readResult = ExecutionStateStore.load(statePath)
        val json = Files.readString(statePath)

        assert(writeResult == Right(()))
        assert(readResult == Right(state))
        assert(Files.isRegularFile(statePath))
        assert(json.contains("\"manifest\""))
        assert(json.contains("\"fingerprint\""))
        assert(json.contains("\"createdAt\""))
        assert(json.contains("\"updatedAt\""))
        assert(json.contains("\"lastCompleted\""))
        assert(json.contains("\"nextPlanEntry\""))
        assert(json.contains("\"status\""))
        assert(json.contains("\"Pending\""))

    test("resume point returns completed names and next plan entry"):
      val initial = ExecutionState.initial(manifest("developer-workstation"), fixedClock)
      val completed = ExecutionState.markCompleted(initial, "first", laterInstant)
      val resumePoint = ExecutionState.resumePoint(completed)

      assert(resumePoint.lastCompleted.contains("first"))
      assert(resumePoint.nextPlanEntry.contains("second"))
      assert(resumePoint.completedEntryNames == Set("first"))
      assert(resumePoint.nextIndex.contains(1))

    test("stale state rejects mismatched manifest name and fingerprint without reset"):
      withTempDir: tmp =>
        val statePath = tmp.resolve("state.json")
        val baseManifest = manifest("developer-workstation")
        val baseState = ExecutionState.initial(baseManifest, fixedClock)
        val changedName = manifest("other-workstation")
        val changedFingerprint = manifest("developer-workstation", entries = Vector("first", "second", "third"))

        ExecutionStateStore.write(statePath, baseState)

        val nameMismatch = ExecutionStateStore.loadOrInitialize(statePath, changedName, resetState = false, fixedClock)
        val fingerprintMismatch =
          ExecutionStateStore.loadOrInitialize(statePath, changedFingerprint, resetState = false, fixedClock)

        assert(nameMismatch.isLeft)
        assert(fingerprintMismatch.isLeft)
        assert(nameMismatch.left.toOption.exists(_.isInstanceOf[ExecutionStateError.StaleManifestName]))
        assert(fingerprintMismatch.left.toOption.exists(_.isInstanceOf[ExecutionStateError.StaleManifestFingerprint]))

    test("reset state ignores stale file and returns a fresh state for the current manifest"):
      withTempDir: tmp =>
        val statePath = tmp.resolve("state.json")
        val oldManifest = manifest("old-workstation")
        val currentManifest = manifest("current-workstation")
        val oldState = ExecutionState.markCompleted(
          ExecutionState.initial(oldManifest, fixedClock),
          "first",
          laterInstant
        )

        ExecutionStateStore.write(statePath, oldState)

        val resetState = ExecutionStateStore.loadOrInitialize(statePath, currentManifest, resetState = true, fixedClock)

        assert(resetState.map(_.manifest.name) == Right(Some("current-workstation")))
        assert(resetState.map(_.lastCompleted) == Right(None))
        assert(resetState.map(_.nextPlanEntry) == Right(Some("first")))

  private val fixedInstant: Instant =
    Instant.parse("2026-06-28T10:15:30Z")

  private val laterInstant: Instant =
    Instant.parse("2026-06-28T10:20:30Z")

  private val fixedClock: Clock =
    Clock.fixed(fixedInstant, ZoneOffset.UTC)

  private def manifest(
      name: String,
      entries: Vector[String] = Vector("first", "second")
  ): Manifest =
    Manifest(
      apiVersion = Some("initkit.io/v1alpha1"),
      kind = Some("WorkstationProfile"),
      metadata = Metadata(
        name = Some(name),
        labels = VectorMap.empty,
        annotations = VectorMap.empty
      ),
      spec = ManifestSpec(
        target = None,
        policy = None,
        vars = VectorMap.empty,
        sources = None,
        plan = entries.map(planEntry)
      )
    )

  private def planEntry(name: String): PlanEntry =
    PlanEntry(
      name = Some(name),
      kind = Some("commands"),
      description = None,
      execution = Some(Execution(mode = Some("sequential"), maxConcurrency = None, failFast = None, locks = Vector.empty)),
      when = None,
      spec = Some(RawYaml.MappingValue(VectorMap.empty))
    )

  private def withTempDir(run: Path => Unit): Unit =
    val tmp = Files.createTempDirectory("initkit-state-tests-")
    try run(tmp)
    finally deleteRecursively(tmp)

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        val children = Files.list(path)
        try children.forEach(deleteRecursively)
        finally children.close()
      Files.deleteIfExists(path)
