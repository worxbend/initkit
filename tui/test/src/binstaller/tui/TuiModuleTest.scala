package binstaller.tui

import binstaller.config.ChecksumAlgorithm
import binstaller.core.ApplyConfirmation
import binstaller.core.BinaryDownloadClient
import binstaller.core.BinaryDownloadError
import binstaller.core.BinaryDownloadProgressObserver
import binstaller.core.BinaryDownloadResult
import binstaller.core.BinaryInstallerService
import binstaller.core.CommandExecutionError
import binstaller.core.CommandExecutor
import binstaller.core.CommandSpec
import binstaller.core.CommandOutput
import binstaller.core.DirectBinaryInstaller
import binstaller.core.DownloadProgressStatus
import binstaller.core.DryRunMode
import binstaller.core.HttpTextClient
import binstaller.core.HttpTextError
import binstaller.core.InstallFileSystem
import binstaller.core.InstallerEvent
import binstaller.core.InstallerEventObserver
import binstaller.core.InstallerPhase
import binstaller.core.InstallerRunStatus
import binstaller.core.InstallerOptions
import binstaller.core.InstallerResult
import binstaller.core.LockOptions
import binstaller.core.LockedApplyMode
import binstaller.core.ResetState
import binstaller.core.ResolutionOptions
import binstaller.core.ResolvedPlanSnapshot
import binstaller.core.ResolvedVersion
import binstaller.core.SensitiveValueRedactions
import binstaller.core.SudoCredentialError
import binstaller.core.SudoCredentialProvider
import binstaller.core.SudoCredentialRequest
import binstaller.core.ToolResultStatus
import binstaller.core.ToolSelection
import binstaller.core.UrlProvenance
import binstaller.core.UrlRedirectHop
import binstaller.core.VerboseOutput
import utest.*

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

object TuiModuleTest extends TestSuite:

  val tests: Tests = Tests:
    test("module path includes upstream modules"):
      assert(TuiModule.modulePath == Vector("config", "core", "tui"))

    test("plan tui renders a deterministic planning frame"):
      val fixture = writeFixture()

      val result = TuiModule.start(
        TuiRequest(TuiMode.Plan, fixture.options),
        FakeHttpTextClient(""),
        testSettings()
      )

      val plain = stripAnsi(result.lines.mkString("\n"))
      assert(result.exitCode == 0)
      assert(plain.contains("binstaller 1.2.3 | mode browse | action [browse] | tui-profile"))
      assert(plain.contains(s"config ${fixture.config}"))
      assert(plain.contains(s"state ${fixture.stateFile}"))
      assert(plain.contains("host linux/amd64"))
      assert(plain.contains("selected 2 / total 2"))
      assert(plain.contains("Plan"))
      assert(plain.contains("Details: alpha"))
      assert(plain.contains("l logs"))
      assert(plain.contains("q quit"))

    test("apply dry-run tui renders execution view with concrete dry-run operations"):
      val fixture = writeFixture()

      val result = TuiModule.start(
        TuiRequest(TuiMode.Apply, fixture.options.copy(dryRun = DryRunMode.Enabled)),
        FakeHttpTextClient(""),
        testSettings(height = 60).copy(detailScroll = 9)
      )

      val plain = stripAnsi(result.lines.mkString("\n"))
      assert(result.exitCode == 0)
      assert(plain.contains("binstaller 1.2.3 | apply | elapsed"))
      assert(plain.contains("Dry-run operations"))
      assert(plain.contains("binstaller apply --dry-run"))
      assert(plain.contains("download: https://example.invalid/releases/alpha.tar.gz"))
      assert(!plain.contains("#   status     name"))
      assert(!Files.exists(fixture.appsDir))

    test("invalid config renders a visible validation error modal"):
      val config = writeInvalidConfig()

      val result = TuiModule.start(
        TuiRequest(TuiMode.Plan, installerOptions(config), Some("tui")),
        FakeHttpTextClient(""),
        testSettings()
      )
      val plain = stripAnsi(result.lines.mkString("\n"))

      assert(result.exitCode == 1)
      assert(plain.contains("ERROR: TUI startup failed"))
      assert(plain.contains("category: validation"))
      assert(plain.contains("action: binstaller tui"))
      assert(plain.contains("root cause:"))
      assert(plain.contains("suggestion: Fix the manifest field"))

    test("execution view shows active tool progress bytes elapsed and logs"):
      val fixture = writeFixture()
      val pending = ExecutionTuiRenderer
        .render(
          ExecutionTuiState
            .initial(
              TuiRequest(TuiMode.Apply, fixture.options),
              ExecutionTuiSettings.fromPlanning(testSettings(height = 42))
            )
            .toModel
        )
        .mkString("\n")
      val state = ExecutionTuiState
        .initial(
          TuiRequest(TuiMode.Apply, fixture.options),
          ExecutionTuiSettings.fromPlanning(testSettings(height = 42))
        )
        .onEvent(InstallerEvent.PlanReady(
          Vector("alpha", "beta"),
          Some(fixture.stateFile.toString),
          elapsed(10)
        ))
        .onEvent(InstallerEvent.ToolStarted("alpha", InstallerPhase.Downloading, elapsed(20)))
        .onEvent(InstallerEvent.DownloadProgress(
          "alpha",
          fixture.longUrl,
          512L,
          Some(1024L),
          DownloadProgressStatus.Advanced,
          elapsed(300)
        ))
        .onEvent(InstallerEvent.LogLine(
          Some("alpha"),
          "extracting selected archive paths",
          elapsed(350)
        ))

      val plain        = stripAnsi(ExecutionTuiRenderer.render(state.toModel).mkString("\n"))
      val pendingPlain = stripAnsi(pending)

      assert(pendingPlain.contains("activity |"))
      assert(pendingPlain.contains("progress"))
      assert(pendingPlain.contains("bytes pending"))
      assert(plain.contains("Execution [active]"))
      assert(plain.contains("activity"))
      assert(plain.indexOf("alpha") < plain.indexOf("beta"))
      assert(plain.contains("beta"))
      assert(plain.contains("pending"))
      assert(plain.contains("█████████░░░░░░░░░ 50%"))
      assert(plain.contains("current tool alpha"))
      assert(plain.contains("phase Downloading"))
      assert(plain.contains("elapsed 300ms"))
      assert(plain.contains("512 B/1.0 KiB"))
      assert(!plain.contains("download advanced"))
      assert(plain.contains("alpha: extracting selected archive paths"))
      assert(!plain.contains("q/Ctrl+C quit"))
      assert(!plain.contains("Plan ["))

    test("execution logs focus scrolls with keyboard and mouse wheel"):
      val fixture = writeFixture()
      val logs    = Vector.tabulate(28)(index => s"execution log line ${index + 1}")
      val state   = ExecutionTuiState
        .initial(
          TuiRequest(TuiMode.Apply, fixture.options),
          ExecutionTuiSettings.fromPlanning(testSettings(height = 24))
        )
        .copy(logs = logs)
        .handle(TuiInput.PageDown, TuiPane.Logs)
        .handle(TuiInput.MouseWheelDown, TuiPane.Logs)
      val plain = stripAnsi(
        ExecutionTuiRenderer.render(state.toModel.copy(focusedPane = TuiPane.Logs)).mkString("\n")
      )

      assert(state.logScroll > 0)
      assert(plain.contains("Logs"))
      assert(plain.contains("execution log line 15"))
      assert(!plain.contains("execution log line 4"))

    test("unknown-size download progress uses deterministic indeterminate frames"):
      val frameOne = ExecutionTuiRenderer.progressText(Some(512L), None, frame = 3)
      val frameTwo = ExecutionTuiRenderer.progressText(Some(512L), None, frame = 4)

      assert(frameOne != frameTwo)
      assert(frameOne.contains("512 B / unknown"))
      assert(frameTwo.contains("512 B / unknown"))
      assert(!frameOne.contains("/0 B"))
      assert(!frameOne.contains("100%"))

    test("execution state preserves seeded candidate order and focuses failed rows"):
      val fixture = writeFixture()
      val state   = ExecutionTuiState
        .initial(
          TuiRequest(TuiMode.Apply, fixture.options),
          ExecutionTuiSettings
            .fromPlanning(testSettings(height = 42))
            .copy(candidateNames = Vector("alpha", "beta", "gamma"))
        )
        .onEvent(InstallerEvent.ToolStarted("beta", InstallerPhase.Downloading, elapsed(20)))
        .onEvent(InstallerEvent.DownloadProgress(
          "beta",
          fixture.longUrl,
          512L,
          Some(1024L),
          DownloadProgressStatus.Advanced,
          elapsed(300)
        ))
        .onEvent(InstallerEvent.ToolResult(
          "alpha",
          ToolResultStatus.Completed,
          Some(fixture.longInstallDir.toString),
          None,
          elapsed(400)
        ))
        .onEvent(InstallerEvent.ToolResult(
          "gamma",
          ToolResultStatus.Failed,
          None,
          Some("checksum mismatch: expected abc got def"),
          elapsed(500)
        ))
        .handle(TuiInput.End)

      val rendered = stripAnsi(ExecutionTuiRenderer.render(state.toModel).mkString("\n"))

      assert(state.rows.map(_.name) == Vector("alpha", "beta", "gamma"))
      assert(state.rows.map(_.status) ==
        Vector(PlanningTuiStatus.Completed, PlanningTuiStatus.Active, PlanningTuiStatus.Failed))
      assert(state.focusedFailure.exists(_.toolName.contains("gamma")))
      assert(rendered.indexOf("alpha") < rendered.indexOf("beta"))
      assert(rendered.indexOf("beta") < rendered.indexOf("gamma"))
      assert(rendered.contains("beta"))
      assert(rendered.contains("512 B/1.0 KiB"))
      assert(rendered.contains("> [x] gamma"))

    test("extreme narrow execution view keeps ordered candidates and lower progress"):
      val fixture = writeFixture()
      val state   = ExecutionTuiState
        .initial(
          TuiRequest(TuiMode.Apply, fixture.options),
          ExecutionTuiSettings
            .fromPlanning(testSettings(width = 30, height = 24))
            .copy(candidateNames = Vector("alpha", "beta"))
        )
        .onEvent(InstallerEvent.ToolStarted("alpha", InstallerPhase.Downloading, elapsed(20)))
        .onEvent(InstallerEvent.DownloadProgress(
          "alpha",
          fixture.longUrl,
          512L,
          Some(1024L),
          DownloadProgressStatus.Advanced,
          elapsed(300)
        ))
      val rendered = ExecutionTuiRenderer.render(state.toModel)
      val plain    = stripAnsi(rendered.mkString("\n"))

      assertRenderedWithin(rendered, width = 30)
      assert(plain.indexOf("alpha active") < plain.indexOf("beta pending"))
      assert(plain.contains("activity | current tool alp"))
      assert(plain.contains("50%"))

    test("download progress ticks update status without noisy advanced logs"):
      val fixture = writeFixture()
      val state   = ExecutionTuiState
        .initial(
          TuiRequest(TuiMode.Apply, fixture.options),
          ExecutionTuiSettings.fromPlanning(testSettings(height = 42))
        )
        .onEvent(InstallerEvent.ToolStarted("alpha", InstallerPhase.Downloading, elapsed(20)))
        .onEvent(InstallerEvent.DownloadProgress(
          "alpha",
          fixture.longUrl,
          0L,
          None,
          DownloadProgressStatus.Started,
          elapsed(30)
        ))
        .onEvent(InstallerEvent.DownloadProgress(
          "alpha",
          fixture.longUrl,
          256L,
          None,
          DownloadProgressStatus.Advanced,
          elapsed(40)
        ))
        .onEvent(InstallerEvent.DownloadProgress(
          "alpha",
          fixture.longUrl,
          512L,
          None,
          DownloadProgressStatus.Advanced,
          elapsed(50)
        ))
        .onEvent(InstallerEvent.DownloadProgress(
          "alpha",
          fixture.longUrl,
          512L,
          None,
          DownloadProgressStatus.Finished,
          elapsed(60)
        ))
      val plain = stripAnsi(ExecutionTuiRenderer.render(state.toModel).mkString("\n"))

      assert(plain.contains("512 B / unknown"))
      assert(plain.contains("alpha: download started 0 B"))
      assert(plain.contains("alpha: download finished 512 B"))
      assert(!plain.contains("download advanced"))

    test("execution state accepts resize inputs and renders within narrow bounds"):
      val fixture = writeFixture(longValues = true)
      val state   = ExecutionTuiState
        .initial(
          TuiRequest(TuiMode.Apply, fixture.options),
          ExecutionTuiSettings.fromPlanning(testSettings(width = 100, height = 42))
        )
        .onEvent(InstallerEvent.ToolStarted("alpha", InstallerPhase.Downloading, elapsed(20)))
        .onEvent(InstallerEvent.LogLine(
          Some("alpha"),
          "extracting selected archive paths from a very long archive member name",
          elapsed(350)
        ))
        .handle(TuiInput.Resize(TuiViewport(32, 24)))

      val rendered = ExecutionTuiRenderer.render(state.toModel)

      assert(state.viewport == TuiViewport(32, 24))
      assertRenderedWithin(rendered, width = 32)

    test("execution rows show completed and failed tools with root cause styling"):
      val fixture = writeFixture()
      val state   = ExecutionTuiState
        .initial(
          TuiRequest(TuiMode.Apply, fixture.options),
          ExecutionTuiSettings.fromPlanning(testSettings(height = 42))
        )
        .onEvent(InstallerEvent.ToolResult(
          "alpha",
          ToolResultStatus.Completed,
          Some(fixture.longInstallDir.toString),
          None,
          elapsed(1200)
        ))
        .onEvent(InstallerEvent.ToolResult(
          "beta",
          ToolResultStatus.Failed,
          None,
          Some("checksum mismatch: expected abc got def"),
          elapsed(1500)
        ))
        .onEvent(InstallerEvent.ToolSkipped(
          "gamma",
          "already completed in state",
          Some(fixture.stateFile.toString),
          elapsed(1550)
        ))
        .onEvent(InstallerEvent.Summary(
          InstallerRunStatus.Failed,
          installed = 1,
          failed = 1,
          skipped = 1,
          exitCode = 1,
          stateFilePath = Some(fixture.stateFile.toString),
          elapsedTime = elapsed(1600)
        ))
      val rendered = ExecutionTuiRenderer.render(state.toModel).mkString("\n")
      val plain    = stripAnsi(rendered)

      assert(plain.contains("ok alpha"))
      assert(plain.contains("fail beta"))
      assert(plain.contains("skipped"))
      assert(plain.contains("checksum mismatch"))
      assert(plain.contains(
        "failed | completed 1 | failed 1 | skipped 1 | remaining 0 | interrupted 0 | exit 1 | elapsed 1.6s"
      ))
      assert(rendered.contains("✅ ok"))
      assert(rendered.contains("❌ fail"))
      assert(rendered.contains("⏭ skipped"))

    test("apply execution TUI closes terminal after dry-run success"):
      val fixture  = writeFixture()
      val terminal = FakeTuiTerminal(interactive = true, TuiViewport(100, 38))
      val result   = TuiModule.startInteractive(
        TuiRequest(TuiMode.Apply, fixture.options.copy(dryRun = DryRunMode.Enabled)),
        FakeHttpTextClient(""),
        testSettings(height = 38),
        terminal
      )
      val rendered = stripAnsi(terminal.rendered.mkString("\n"))

      assert(result.exitCode == 0)
      assert(result.lines.isEmpty)
      assert(terminal.opened)
      assert(terminal.closed)
      assert(rendered.contains("binstaller 1.2.3 | apply | elapsed"))
      assert(rendered.contains("binstaller apply --dry-run"))

    test("apply execution TUI closes terminal after failure"):
      val terminal = FakeTuiTerminal(interactive = true, TuiViewport(100, 38))
      val result   = TuiModule.startInteractive(
        TuiRequest(TuiMode.Apply, fixtureOptionsForMissingConfig()),
        FakeHttpTextClient(""),
        testSettings(height = 38),
        terminal
      )
      val rendered = stripAnsi(terminal.rendered.mkString("\n"))

      assert(result.exitCode == 1)
      assert(terminal.opened)
      assert(terminal.closed)
      assert(rendered.contains("failed"))

    test("planning TUI closes terminal when open fails"):
      val fixture  = writeFixture()
      val terminal = FakeTuiTerminal(
        interactive = true,
        TuiViewport(100, 38),
        openFailure = Some(IOException("cannot open terminal"))
      )

      val result = TuiModule.startInteractive(
        TuiRequest(TuiMode.Plan, fixture.options),
        FakeHttpTextClient(""),
        testSettings(height = 38),
        terminal
      )
      val plain = stripAnsi(result.lines.mkString("\n"))

      assert(result.exitCode == 1)
      assert(terminal.opened)
      assert(terminal.closed)
      assert(plain.contains("ERROR: Terminal failure"))
      assert(plain.contains("category: terminal"))

    test("non-interactive execution frame avoids alternate-screen terminal sequences"):
      val fixture  = writeFixture()
      val terminal = FakeTuiTerminal(interactive = false, TuiViewport(100, 38))
      val result   = TuiModule.startInteractive(
        TuiRequest(TuiMode.Apply, fixture.options.copy(dryRun = DryRunMode.Enabled)),
        FakeHttpTextClient(""),
        testSettings(height = 38),
        terminal
      )
      val output = result.lines.mkString("\n")

      assert(result.exitCode == 0)
      assert(!terminal.opened)
      assert(!terminal.closed)
      assert(!output.contains("\u001b[?1049h"))
      assert(output.contains("non-interactive terminal detected"))

    test("non-interactive browsing frame does not open or close terminal raw mode"):
      val fixture  = writeFixture()
      val terminal = FakeTuiTerminal(interactive = false, TuiViewport(100, 38))
      val result   = TuiModule.startInteractive(
        TuiRequest(TuiMode.Plan, fixture.options, Some("tui")),
        FakeHttpTextClient(""),
        testSettings(height = 38),
        terminal
      )
      val output = result.lines.mkString("\n")

      assert(result.exitCode == 0)
      assert(!terminal.opened)
      assert(!terminal.closed)
      assert(!output.contains("\u001b[?1049h"))
      assert(output.contains("non-interactive terminal detected; rendered a static TUI frame"))
      assert(stripAnsi(output).contains(
        "binstaller 1.2.3 | mode browse | action [browse] | tui-profile"
      ))

    test("layout model carries header metadata and plan rows"):
      val fixture = writeFixture()
      val model   = modelFor(fixture, selectedIndex = 0)

      assert(model.header.appName == "binstaller")
      assert(model.header.appVersion == "1.2.3")
      assert(model.header.mode == "browse")
      assert(model.header.manifestName == "tui-profile")
      assert(model.header.configPath == fixture.config.toString)
      assert(model.header.stateFilePath.contains(fixture.stateFile.toString))
      assert(model.header.selectionText == "selected 2 / total 2")
      assert(model.rows.map(_.name) == Vector("alpha", "beta"))
      assert(model.rows.map(_.kind).distinct == Vector("binary-tool"))
      assert(model.rows.head.checksumState == ChecksumAlgorithm.Sha256.value)

    test("polished planning model carries dense header and entry details"):
      val fixture   = writeFixture()
      val alpha     = modelFor(fixture, selectedIndex = 0, width = 100, height = 44)
      val beta      = modelFor(fixture, selectedIndex = 1, width = 100, height = 44)
      val sudoModel = modelFor(writeRiskFixture(), selectedIndex = 0, width = 100, height = 44)
      val rendered  = PlanningTuiRenderer.render(alpha)
      val plain     = stripAnsi(rendered.mkString("\n"))
      val alphaText = alpha.detail.map(_.lines.mkString("\n")).getOrElse("")
      val betaText  = beta.detail.map(_.lines.mkString("\n")).getOrElse("")
      val sudoText  = sudoModel.detail.map(_.lines.mkString("\n")).getOrElse("")

      assert(plain.contains("binstaller 1.2.3 | mode browse | action [browse] | tui-profile"))
      assert(plain.contains(s"config ${fixture.config}"))
      assert(plain.contains(s"state ${fixture.stateFile}"))
      assert(plain.contains("host linux/amd64"))
      assert(plain.contains("selected 2 / total 2"))
      assert(alphaText.contains("version resolver provenance: static manifest value"))
      assert(alphaText.contains("download final url: observed during dry-run/apply"))
      assert(alphaText.contains("download provenance: initial manifest URL"))
      assert(alphaText.contains("checksum status: configured sha256"))
      assert(alphaText.contains("archive mappings:"))
      assert(alphaText.contains("archive file: alpha -> bin/alpha"))
      assert(betaText.contains("symlinks:"))
      assert(betaText.contains("sudo risk: no"))
      assert(sudoText.contains("sudo risk: yes"))
      assert(alphaText.contains("dry-run operation preview:"))
      assertRenderedWithin(rendered, width = 100)

    test("polished planning renderer clips normal and narrow snapshots without overlap"):
      val fixture = writeFixture(longValues = true)
      val normal  =
        PlanningTuiRenderer.render(modelFor(fixture, selectedIndex = 0, width = 96, height = 44))
      val narrow =
        PlanningTuiRenderer.render(modelFor(fixture, selectedIndex = 0, width = 28, height = 24))
      val plain = stripAnsi(narrow.mkString("\n"))

      assertRenderedWithin(normal, width = 96)
      assertRenderedWithin(narrow, width = 28)
      assert(plain.contains("…"))
      assert(plain.linesIterator.forall(line => !line.contains("  \u001b")))

    test("app state owns header entries selection focus filter modal logs and execution state"):
      val fixture = writeFixture()
      val state   = sessionState(fixture).appState

      assert(state.mode == TuiBrowsingMode.Browsing)
      assert(state.header.configPath == fixture.config.toString)
      assert(state.header.stateFilePath.contains(fixture.stateFile.toString))
      assert(state.header.profileName == "tui-profile")
      assert(state.header.hostSummary == "linux/amd64")
      assert(state.header.selectionText == "selected 2 / total 2")
      assert(state.entries.map(_.name) == Vector("alpha", "beta"))
      assert(state.selectedToolNames == Set("alpha", "beta"))
      assert(state.focus == TuiPane.Plan)
      assert(state.filter == TuiAppFilter.committed(None))
      assert(state.modal.isEmpty)
      assert(state.logs.exists(_.contains("resolved manifest tui-profile")))
      assert(state.executionState.isEmpty)

    test("tui selection converts to core selection only at service boundary"):
      val fixture       = writeFixture()
      val state         = sessionState(fixture).appState
      val tuiSelection  = TuiSelection(Set("beta"))
      val coreSelection = TuiCoreSelection.toToolSelection(tuiSelection, state.entries)

      assert(tuiSelection.selectedToolNames == Set("beta"))
      assert(coreSelection == ToolSelection(only = Vector("beta"), skip = Vector.empty))

    test("p previews only selected entries and preserves browsing state without writes"):
      val fixture          = writeFixture()
      val previewStateFile = fixture.root.resolve("preview.state.json")
      val options          = fixture.options.copy(statePath = Some(previewStateFile.toString))
      val service          = BinaryInstallerService.resolving(FakeHttpTextClient(""))
      val actions          = TuiAppActions.fromService(options, service)
      val browsedState     = PlanningTuiSession.run(
        sessionState(fixture.copy(options = options)),
        Vector(
          TuiInput.Character('c'),
          TuiInput.Slash,
          TuiInput.Character('b'),
          TuiInput.Character('e'),
          TuiInput.Enter,
          TuiInput.Character(' '),
          TuiInput.Enter
        )
      )
      val previewState =
        PlanningTuiSession.run(browsedState, Vector(TuiInput.Character('p')), actions)
      val model = previewState.toModel
      val logs  = stripAnsi(model.logs.mkString("\n"))
      val plain = stripAnsi(PlanningTuiRenderer.render(model).mkString("\n"))

      assert(previewState.appState.mode == TuiBrowsingMode.PlanPreview)
      assert(previewState.focusedPane == TuiPane.Details)
      assert(model.detail.exists(_.name == "beta"))
      assert(previewState.appState.infoOutput.exists(_.lines.exists(_.contains("1. beta"))))
      assert(model.header.filterText == "be")
      assert(previewState.appState.selectedToolNames == Set("beta"))
      assert(logs.contains("plan preview selected 1 / 2: beta"))
      assert(logs.contains("binstaller plan (dry-run)"))
      assert(logs.contains("tools: 1"))
      assert(logs.contains("1. beta"))
      assert(!logs.contains("1. alpha"))
      assert(plain.contains("Plan table"))
      assert(plain.contains("1. beta"))
      assert(!Files.exists(fixture.appsDir))
      assert(!Files.exists(previewStateFile))

    test("p with no selected entries opens a visible modal and does not call plan"):
      val fixture = writeFixture()
      val service = RecordingPlanService(InstallerResult(Vector("should not render"), 0))
      val actions = TuiAppActions.fromService(fixture.options, service)
      val cleared = PlanningTuiSession.run(
        sessionState(fixture),
        Vector(TuiInput.Character('c'))
      )
      val finalState = PlanningTuiSession.run(cleared, Vector(TuiInput.Character('p')), actions)
      val plain      = stripAnsi(PlanningTuiRenderer.render(finalState.toModel).mkString("\n"))

      assert(service.planOptions.isEmpty)
      assert(service.applyOptions.isEmpty)
      assert(finalState.appState.modal.exists:
        case TuiModal.Message("Selection required", lines) =>
          lines.exists(_.contains("at least one plan entry"))
        case _ => false)
      assert(plain.contains("Selection required"))
      assert(plain.contains("Select at least one plan entry"))

    test("p converts selected TUI entries to core ToolSelection at the plan boundary"):
      val fixture  = writeFixture()
      val service  = RecordingPlanService(InstallerResult(Vector("preview ok"), 0))
      val actions  = TuiAppActions.fromService(fixture.options, service)
      val selected = PlanningTuiSession.run(
        sessionState(fixture),
        Vector(TuiInput.Character('c'), TuiInput.Down, TuiInput.Character(' '))
      )
      val finalState = PlanningTuiSession.run(selected, Vector(TuiInput.Character('p')), actions)

      assert(service.planOptions.map(_.selection) ==
        Vector(ToolSelection(only = Vector("beta"), skip = Vector.empty)))
      assert(service.applyOptions.isEmpty)
      assert(finalState.appState.logs.contains("preview ok"))
      assert(finalState.appState.mode == TuiBrowsingMode.PlanPreview)

    test("d runs dry-run apply only for selected entries in the execution view without writes"):
      val fixture         = writeFixture()
      val dryRunStateFile = fixture.root.resolve("dry-run.state.json")
      val options         = fixture.options.copy(statePath = Some(dryRunStateFile.toString))
      val service         = BinaryInstallerService.resolving(FakeHttpTextClient(""))
      val actions         = TuiAppActions.fromService(options, service)
      val selected        = PlanningTuiSession.run(
        sessionState(fixture.copy(options = options)),
        Vector(TuiInput.Character('c'), TuiInput.Down, TuiInput.Character(' '))
      )
      val finalState = PlanningTuiSession.run(selected, Vector(TuiInput.Character('d')), actions)
      val rendered   = stripAnsi(TuiAppRenderer.render(finalState.appState).mkString("\n"))

      assert(finalState.appState.mode == TuiBrowsingMode.DryRun)
      assert(finalState.appState.executionState.exists(_.summary.nonEmpty))
      assert(rendered.contains("binstaller 1.2.3 | apply | elapsed"))
      assert(rendered.contains("Dry-run operations"))
      assert(rendered.contains("Recent Logs"))
      assert(rendered.contains("binstaller apply --dry-run"))
      assert(rendered.contains("tools: 1"))
      assert(rendered.contains("1. beta"))
      assert(!rendered.contains("1. alpha"))
      assert(rendered.contains("dry-run selected 1 / 2: beta"))
      assert(rendered.contains(
        "succeeded | completed 0 | failed 0 | skipped 0 | remaining 1 | interrupted 0 | exit 0"
      ))
      assert(!rendered.contains("Plan ["))
      assert(!Files.exists(fixture.appsDir))
      assert(!Files.exists(dryRunStateFile))
      assert(!Files.exists(fixture.appsDir.resolve("beta")))
      assert(!Files.exists(fixture.appsDir.resolve("beta").resolve("beta")))
      assert(!Files.exists(fixture.appsDir.resolve("beta").resolve("bin").resolve("beta")))

    test("d with no selected entries opens a visible modal and does not call apply"):
      val fixture = writeFixture()
      val service = RecordingPlanService(InstallerResult(Vector("should not render"), 0))
      val actions = TuiAppActions.fromService(fixture.options, service)
      val cleared = PlanningTuiSession.run(
        sessionState(fixture),
        Vector(TuiInput.Character('c'))
      )
      val finalState = PlanningTuiSession.run(cleared, Vector(TuiInput.Character('d')), actions)
      val plain      = stripAnsi(PlanningTuiRenderer.render(finalState.toModel).mkString("\n"))

      assert(service.planOptions.isEmpty)
      assert(service.applyOptions.isEmpty)
      assert(finalState.appState.executionState.isEmpty)
      assert(finalState.appState.modal.exists:
        case TuiModal.Message("Selection required", lines) =>
          lines.exists(_.contains("at least one plan entry"))
        case _ => false)
      assert(plain.contains("Selection required"))
      assert(plain.contains("Select at least one plan entry"))

    test("d converts selected TUI entries to core ToolSelection at the apply boundary"):
      val fixture  = writeFixture()
      val service  = RecordingDryRunService(InstallerResult(Vector("dry-run ok"), 0))
      val actions  = TuiAppActions.fromService(fixture.options, service)
      val selected = PlanningTuiSession.run(
        sessionState(fixture),
        Vector(TuiInput.Character('c'), TuiInput.Down, TuiInput.Character(' '))
      )
      val finalState = PlanningTuiSession.run(selected, Vector(TuiInput.Character('d')), actions)

      assert(service.planOptions.isEmpty)
      assert(service.applyOptions.map(options => options.selection -> options.dryRun) ==
        Vector(ToolSelection(only = Vector("beta"), skip = Vector.empty) -> DryRunMode.Enabled))
      assert(finalState.appState.mode == TuiBrowsingMode.DryRun)
      assert(finalState.appState.executionState.exists(_.dryRunLines.contains("dry-run ok")))

    test("failed dry-run renders error output first and opens redacted root cause"):
      val fixture = writeFixture()
      val secret  = "secret-token-value"
      val lines   = Vector(
        "failed beta: command: 'tar': command exited with status 2",
        "  exit code: 2",
        "  stdout: line-1",
        "  stdout: line-2",
        "  stdout: line-3",
        "  stdout: line-4",
        "  stdout: line-5",
        "  stdout: line-6",
        "  stdout: line-7",
        s"  stderr: bad token $secret \u001b[31m"
      )
      val service  = RecordingDryRunService(InstallerResult(lines, 1))
      val actions  = TuiAppActions.fromService(fixture.options, service)
      val state    = withRedactions(sessionState(fixture), SensitiveValueRedactions(Vector(secret)))
      val selected = PlanningTuiSession.run(
        state,
        Vector(TuiInput.Character('c'), TuiInput.Down, TuiInput.Character(' '))
      )
      val finalState = PlanningTuiSession.run(selected, Vector(TuiInput.Character('d')), actions)
      val rendered   = stripAnsi(TuiAppRenderer.render(finalState.appState).mkString("\n"))
      val logs       = finalState.appState.logs.mkString("\n")
      val failure    = finalState.appState.executionState.flatMap(_.failureOutput)

      assert(finalState.appState.modal.isEmpty)
      assert(failure.exists: failure =>
        failure.category == TuiFailureCategory.DryRun &&
          failure.action.contains("dry-run") &&
          failure.toolName.contains("beta") &&
          failure.exitCode.contains(2) &&
          failure.rootCause.contains("failed beta") &&
          failure.stdoutSnippet == Vector(
            "line-2",
            "line-3",
            "line-4",
            "line-5",
            "line-6",
            "line-7"
          ) &&
          failure.stderrSnippet.exists(_.contains("<redacted>")) &&
          failure.environmentHints.exists(_.contains("modeled environment")))
      assert(rendered.contains("Dry run failed"))
      assert(rendered.contains("category: dry-run"))
      assert(rendered.contains("action: dry-run"))
      assert(rendered.contains("tool: beta"))
      assert(rendered.contains("exit code: 2"))
      assert(rendered.contains("suggestion: Review the dry-run root cause"))
      assert(!rendered.contains(secret))
      assert(!rendered.contains("\u001b[31m"))
      assert(!logs.contains(secret))
      assert(!logs.contains("\u001b[31m"))

      val rootCause = PlanningTuiSession.run(finalState, Vector(TuiInput.Enter), actions)
      assert(rootCause.appState.modal.exists:
        case TuiModal.RootCause(value) => value.exitCode.contains(2)
        case _                         => false)

    test("password modal explains privileged operation and masks typed input"):
      val request = SudoCredentialRequest(
        "alpha",
        "/usr/local/bin/alpha",
        "/tmp/apps/alpha/bin/alpha",
        "create sudo symlink /tmp/apps/alpha/bin/alpha -> /usr/local/bin/alpha"
      )
      val prompt = TuiPasswordPromptView.fromRequest(
        request,
        maskedLength = 6,
        SensitiveValueRedactions.empty
      )
      val plain = stripAnsi(
        TuiModalRenderer.render(Some(TuiModal.PasswordPrompt(prompt)), 90).mkString("\n")
      )

      assert(plain.contains("Sudo password required"))
      assert(plain.contains("operation: create sudo symlink"))
      assert(plain.contains("tool: alpha"))
      assert(plain.contains("destination: /usr/local/bin/alpha"))
      assert(plain.contains("target: /tmp/apps/alpha/bin/alpha"))
      assert(plain.contains("password: ******"))
      assert(!plain.contains("secret"))

    test("terminal password provider submits a masked password without rendering it"):
      val password = "tui-secret"
      val terminal = FakeTuiTerminal(
        interactive = true,
        TuiViewport(90, 20),
        inputs = password.map(TuiInput.Character.apply).toVector :+ TuiInput.Enter
      )
      val provider = new TerminalSudoCredentialProvider(
        terminal,
        SensitiveValueRedactions.empty
      )

      val result = provider.requestSudoPassword(sudoRequest())
      val output = stripAnsi(terminal.rendered.flatten.mkString("\n"))

      assert(result.isRight)
      assert(output.contains("password: **********"))
      assert(!output.contains(password))
      assert(!result.toString.contains(password))

    test("terminal password provider cancels on escape ctrl-c and explicit cancel input"):
      val escape = new TerminalSudoCredentialProvider(
        FakeTuiTerminal(true, TuiViewport(90, 20), inputs = Vector(TuiInput.Escape)),
        SensitiveValueRedactions.empty
      )
      val ctrlC = new TerminalSudoCredentialProvider(
        FakeTuiTerminal(true, TuiViewport(90, 20), inputs = Vector(TuiInput.CtrlC)),
        SensitiveValueRedactions.empty
      )
      val explicit = new TerminalSudoCredentialProvider(
        FakeTuiTerminal(
          true,
          TuiViewport(90, 20),
          inputs = Vector(
            TuiInput.Slash,
            TuiInput.Character('c'),
            TuiInput.Character('a'),
            TuiInput.Character('n'),
            TuiInput.Character('c'),
            TuiInput.Character('e'),
            TuiInput.Character('l'),
            TuiInput.Enter
          )
        ),
        SensitiveValueRedactions.empty
      )

      assert(escape.requestSudoPassword(sudoRequest()) == Left(SudoCredentialError.Canceled))
      assert(ctrlC.requestSudoPassword(sudoRequest()) == Left(SudoCredentialError.Canceled))
      assert(explicit.requestSudoPassword(sudoRequest()) == Left(SudoCredentialError.Canceled))

    test("password modal survives resize while open and keeps input masked"):
      val password = "ab"
      val terminal = FakeTuiTerminal(
        interactive = true,
        TuiViewport(90, 20),
        inputs = Vector(
          TuiInput.Character('a'),
          TuiInput.Resize(TuiViewport(36, 14)),
          TuiInput.Character('b'),
          TuiInput.Enter
        )
      )
      val provider = new TerminalSudoCredentialProvider(
        terminal,
        SensitiveValueRedactions.empty
      )

      val result        = provider.requestSudoPassword(sudoRequest())
      val resizedFrames = terminal.rendered.drop(2)

      assert(result.isRight)
      assert(resizedFrames.nonEmpty)
      assert(resizedFrames.forall(lines => lines.forall(line => stripAnsi(line).length <= 36)))
      assert(!terminal.rendered.flatten.mkString("\n").contains(password))

    test("tui apply skips password modal when sudo credentials are cached"):
      val fixture         = writeSudoApplyFixture("cached")
      val commandExecutor = RecordingSudoCommandExecutor(cacheAvailable = true)
      val terminal        = FakeTuiTerminal(
        interactive = true,
        TuiViewport(90, 20),
        inputs = Vector.empty
      )
      val provider = new TerminalSudoCredentialProvider(
        terminal,
        SensitiveValueRedactions.empty
      )
      val service    = sudoApplyService(commandExecutor, provider)
      val actions    = TuiAppActions.fromService(fixture.options, service)
      val finalState = PlanningTuiSession.run(
        sessionState(fixture),
        Vector(TuiInput.Character('r'), TuiInput.Enter),
        actions
      )

      assert(finalState.appState.mode == TuiBrowsingMode.Apply)
      assert(finalState.appState.executionState.exists(_.summary.nonEmpty))
      assert(terminal.rendered.isEmpty)
      assert(commandExecutor.commands.map(_.argv).contains(Vector("sudo", "-n", "true")))
      assert(commandExecutor.commands.exists(_.argv.startsWith(Vector("sudo", "-n", "ln"))))
      assert(Files.exists(fixture.stateFile))
      assert(!Files.readString(fixture.stateFile).contains("password"))
      val _ = Files.deleteIfExists(fixture.stateFile)

    test("tui password cancellation reports a clear current-operation failure"):
      val fixture         = writeSudoApplyFixture("cancel")
      val commandExecutor = RecordingSudoCommandExecutor(cacheAvailable = false)
      val terminal        = FakeTuiTerminal(
        interactive = true,
        TuiViewport(100, 28),
        inputs = Vector(TuiInput.Escape)
      )
      val provider = new TerminalSudoCredentialProvider(
        terminal,
        SensitiveValueRedactions.empty
      )
      val service    = sudoApplyService(commandExecutor, provider)
      val actions    = TuiAppActions.fromService(fixture.options, service)
      val finalState = PlanningTuiSession.run(
        sessionState(fixture),
        Vector(TuiInput.Character('r'), TuiInput.Enter),
        actions
      )
      val rendered = stripAnsi(TuiAppRenderer.render(finalState.appState).mkString("\n"))

      val failure = finalState.appState.executionState.flatMap(_.failureOutput)
      assert(finalState.appState.modal.isEmpty)
      assert(failure.exists(failure =>
        failure.rootCause.contains("sudo credentials canceled") &&
          failure.toolName.contains("alpha")
      ))
      assert(rendered.contains("sudo credentials canceled"))
      assert(terminal.rendered.nonEmpty)
      assert(!commandExecutor.commands.exists(_.argv.startsWith(Vector("sudo", "-S"))))
      val _ = Files.deleteIfExists(fixture.stateFile)

    test("tui password value does not leak into apply logs errors diagnostics or state"):
      val password        = "tui-apply-secret"
      val fixture         = writeSudoApplyFixture("leak")
      val commandExecutor = RecordingSudoCommandExecutor(
        cacheAvailable = false,
        passwordToLeak = Some(password),
        passwordCommandFails = true
      )
      val terminal = FakeTuiTerminal(
        interactive = true,
        TuiViewport(100, 28),
        inputs = password.map(TuiInput.Character.apply).toVector :+ TuiInput.Enter
      )
      val provider = new TerminalSudoCredentialProvider(
        terminal,
        SensitiveValueRedactions.empty
      )
      val service    = sudoApplyService(commandExecutor, provider)
      val actions    = TuiAppActions.fromService(fixture.options, service)
      val finalState = PlanningTuiSession.run(
        sessionState(fixture),
        Vector(TuiInput.Character('r'), TuiInput.Enter),
        actions
      )
      val rendered  = stripAnsi(TuiAppRenderer.render(finalState.appState).mkString("\n"))
      val logs      = finalState.appState.logs.mkString("\n")
      val prompt    = stripAnsi(terminal.rendered.flatten.mkString("\n"))
      val stateText =
        if Files.exists(fixture.stateFile) then Files.readString(fixture.stateFile) else ""

      val failure = finalState.appState.executionState.flatMap(_.failureOutput)
      assert(finalState.appState.modal.isEmpty)
      assert(failure.exists(failure =>
        !failure.renderLines.mkString("\n").contains(password) &&
          failure.renderLines.mkString("\n").contains("<redacted>")
      ))
      assert(!rendered.contains(password))
      assert(!logs.contains(password))
      assert(!prompt.contains(password))
      assert(prompt.contains("password: ********"))
      assert(!stateText.contains(password))
      assert(!commandExecutor.commands.exists(_.argv.contains(password)))
      assert(!commandExecutor.commands.map(_.toString).exists(_.contains(password)))
      assert(rendered.contains("<redacted>"))
      val _ = Files.deleteIfExists(fixture.stateFile)

    test("r opens a confirmation modal before real apply starts"):
      val fixture  = writeFixture()
      val service  = RecordingDryRunService(InstallerResult(Vector("apply ok"), 0))
      val actions  = TuiAppActions.fromService(fixture.options, service)
      val selected = PlanningTuiSession.run(
        sessionState(fixture),
        Vector(TuiInput.Character('c'), TuiInput.Down, TuiInput.Character(' '))
      )
      val prompted = PlanningTuiSession.run(selected, Vector(TuiInput.Character('r')), actions)
      val plain    = stripAnsi(PlanningTuiRenderer.render(prompted.toModel).mkString("\n"))

      assert(service.applyOptions.isEmpty)
      assert(prompted.appState.executionState.isEmpty)
      assert(prompted.appState.modal.contains(TuiModal.ConfirmApply(Vector("beta"))))
      assert(plain.contains("Confirm real apply"))
      assert(plain.contains("Apply will install 1 selected entry: beta"))

    test("closing or cancelling real-apply confirmation performs no writes"):
      val fixture = writeFixture()
      val service = RecordingDryRunService(InstallerResult(Vector("apply ok"), 0))
      val actions = TuiAppActions.fromService(fixture.options, service)
      val prompt  = PlanningTuiSession.run(
        sessionState(fixture),
        Vector(TuiInput.Character('r')),
        actions
      )
      val closed = PlanningTuiSession.run(prompt, Vector(TuiInput.Escape, TuiInput.Enter), actions)
      val cancelPrompt = PlanningTuiSession.run(
        sessionState(fixture),
        Vector(TuiInput.Character('r')),
        actions
      )
      val cancelled = PlanningTuiSession.run(cancelPrompt, Vector(TuiInput.Character('n')), actions)

      assert(service.planOptions.isEmpty)
      assert(service.applyOptions.isEmpty)
      assert(closed.appState.executionState.isEmpty)
      assert(cancelled.appState.executionState.isEmpty)
      assert(closed.appState.modal.isEmpty)
      assert(cancelled.appState.modal.isEmpty)
      assert(!Files.exists(fixture.appsDir))
      assert(!Files.exists(fixture.stateFile))

    test("confirming r runs real apply only for selected entries through existing gates"):
      val fixture = writeFixture()
      val options = fixture.options.copy(
        statePath = Some(fixture.stateFile.toString),
        resetState = ResetState.Enabled,
        lockPath = "custom.lock.json",
        lockedApply = LockedApplyMode.Enabled
      )
      val service  = RecordingDryRunService(InstallerResult(Vector("apply ok"), 0))
      val actions  = TuiAppActions.fromService(options, service)
      val selected = PlanningTuiSession.run(
        sessionState(fixture.copy(options = options)),
        Vector(TuiInput.Character('c'), TuiInput.Down, TuiInput.Character(' '))
      )
      val finalState =
        PlanningTuiSession.run(selected, Vector(TuiInput.Character('r'), TuiInput.Enter), actions)
      val rendered = stripAnsi(TuiAppRenderer.render(finalState.appState).mkString("\n"))

      assert(service.planOptions.isEmpty)
      assert(service.applyOptions.size == 1)
      val observed = service.applyOptions.head
      assert(observed.selection == ToolSelection(only = Vector("beta"), skip = Vector.empty))
      assert(observed.dryRun == DryRunMode.Disabled)
      assert(observed.applyConfirmation == ApplyConfirmation.Enabled)
      assert(observed.statePath == Some(fixture.stateFile.toString))
      assert(observed.resetState == ResetState.Enabled)
      assert(observed.lockPath == "custom.lock.json")
      assert(observed.lockedApply == LockedApplyMode.Enabled)
      assert(finalState.appState.mode == TuiBrowsingMode.Apply)
      assert(finalState.appState.modal.isEmpty)
      assert(finalState.appState.executionState.exists(_.summary.nonEmpty))
      assert(rendered.contains("binstaller 1.2.3 | apply | elapsed"))
      assert(rendered.contains("apply selected 1 / 2: beta"))

    test("failed apply row can open root-cause detail modal"):
      val fixture = writeFixture()
      val service = RecordingFailedApplyService(
        InstallerResult(
          Vector(
            "failed beta: symlink: target -> path: permission denied",
            "  stderr: ln failed"
          ),
          1
        )
      )
      val actions  = TuiAppActions.fromService(fixture.options, service)
      val selected = PlanningTuiSession.run(
        sessionState(fixture),
        Vector(TuiInput.Character('c'), TuiInput.Down, TuiInput.Character(' '))
      )
      val failed = PlanningTuiSession.run(
        selected,
        Vector(TuiInput.Character('r'), TuiInput.Enter),
        actions
      )
      val rootCause = PlanningTuiSession.run(failed, Vector(TuiInput.Enter), actions)
      val rendered  = stripAnsi(TuiAppRenderer.render(rootCause.appState).mkString("\n"))

      assert(failed.appState.modal.isEmpty)
      assert(failed.appState.executionState.exists(_.failureOutput.nonEmpty))
      assert(rootCause.appState.modal.exists:
        case TuiModal.RootCause(failure) => failure.toolName.contains("beta") &&
          failure.rootCause.contains("failed beta") &&
          failure.stderrSnippet.contains("ln failed") &&
          failure.exitCode.contains(1) &&
          failure.duration.nonEmpty
        case _ => false)
      assert(rendered.contains("Root cause: beta"))
      assert(rendered.contains("category: apply"))
      assert(rendered.contains("exit code: 1"))
      assert(rendered.contains("duration:"))
      assert(rendered.contains("stderr:"))

    test("interactive d action replaces the planning frame with execution output"):
      val fixture  = writeFixture()
      val terminal = FakeTuiTerminal(
        interactive = true,
        TuiViewport(100, 38),
        inputs = Vector(TuiInput.Character('d'), TuiInput.Quit)
      )
      val result = TuiModule.startInteractive(
        TuiRequest(TuiMode.Plan, fixture.options),
        FakeHttpTextClient(""),
        testSettings(height = 38),
        terminal
      )
      val first  = stripAnsi(terminal.rendered.head.mkString("\n"))
      val second = stripAnsi(terminal.rendered(1).mkString("\n"))

      assert(result.exitCode == 0)
      assert(first.contains("Plan [focus]"))
      assert(second.contains("binstaller 1.2.3 | apply | elapsed"))
      assert(second.contains("Dry-run operations"))
      assert(!second.contains("Plan [focus]"))
      assert(!Files.exists(fixture.appsDir))

    test("row selection highlights the active entry and updates details"):
      val fixture = writeFixture()
      val model   = modelFor(fixture, selectedIndex = 1)

      assert(!model.rows.head.selected)
      assert(model.rows(1).selected)
      assert(model.rows.forall(_.checked))
      assert(model.rows(1).status == PlanningTuiStatus.Active)
      assert(model.detail.exists(_.name == "beta"))
      assert(
        model.detail.exists(_.lines.exists(_.contains("https://example.invalid/releases/beta")))
      )

    test("plan rows render checked and unchecked states deterministically"):
      val finalState = PlanningTuiSession.run(
        sessionState(writeFixture()),
        Vector(TuiInput.Character(' '))
      )
      val first  = PlanningTuiRenderer.render(finalState.toModel).mkString("\n")
      val second = PlanningTuiRenderer.render(finalState.toModel).mkString("\n")
      val plain  = stripAnsi(first)

      assert(finalState.appState.selectedToolNames == Set("beta"))
      assert(finalState.toModel.rows.map(_.checked) == Vector(false, true))
      assert(stripAnsi(first) == stripAnsi(second))
      assert(plain.contains("[ ]"))
      assert(plain.contains("[x]"))
      assert(plain.contains("selected 1 / total 2"))

    test("space toggles the current visible entry"):
      val finalState = PlanningTuiSession.run(
        sessionState(writeFixture()),
        Vector(TuiInput.Down, TuiInput.Character(' '))
      )
      val model = finalState.toModel

      assert(finalState.selectedIndex == 1)
      assert(finalState.appState.selectedToolNames == Set("alpha"))
      assert(model.rows.map(row => row.name -> row.checked) ==
        Vector("alpha" -> true, "beta" -> false))
      assert(model.header.selectionText == "selected 1 / total 2")

    test("visible selection shortcuts preserve hidden selections"):
      val filtered = PlanningTuiSession.run(
        sessionState(writeFixture()),
        Vector(
          TuiInput.Slash,
          TuiInput.Character('b'),
          TuiInput.Character('e'),
          TuiInput.Enter
        )
      )
      val cleared  = PlanningTuiSession.run(filtered, Vector(TuiInput.Character('c')))
      val selected = PlanningTuiSession.run(cleared, Vector(TuiInput.Character('a')))
      val inverted = PlanningTuiSession.run(selected, Vector(TuiInput.Character('i')))

      assert(filtered.toModel.rows.map(_.name) == Vector("beta"))
      assert(cleared.appState.selectedToolNames == Set("alpha"))
      assert(cleared.toModel.rows.map(_.checked) == Vector(false))
      assert(cleared.toModel.header.selectionText == "selected 1 / total 2")
      assert(selected.appState.selectedToolNames == Set("alpha", "beta"))
      assert(selected.toModel.header.selectionText == "selected 2 / total 2")
      assert(inverted.appState.selectedToolNames == Set("alpha"))
      assert(inverted.toModel.header.selectionText == "selected 1 / total 2")

    test("a toggles all visible selections off when they are already selected"):
      val toggled = PlanningTuiSession.run(
        sessionState(writeFixture()),
        Vector(TuiInput.Character('a'))
      )

      assert(toggled.appState.selectedToolNames.isEmpty)
      assert(toggled.toModel.rows.map(_.checked) == Vector(false, false))
      assert(toggled.toModel.header.selectionText == "selected 0 / total 2")

    test("state override is shown instead of manifest state file"):
      val fixture       = writeFixture()
      val overrideState = "override.state.json"
      val snapshot      = snapshotFor(fixture.options.copy(statePath = Some(overrideState)))
      val model         = PlanningTuiModel.fromSnapshot(
        snapshot,
        testSettings()
      )

      val plain = stripAnsi(PlanningTuiRenderer.render(model).mkString("\n"))
      assert(model.header.stateFilePath.contains(overrideState))
      assert(plain.contains(s"state $overrideState"))

    test("risk markers include missing checksums dynamic versions and sudo symlinks"):
      val fixture = writeRiskFixture()
      val model   = modelFor(fixture, selectedIndex = 0)
      val row     = model.rows.head

      assert(row.riskMarkers.contains("no-checksum"))
      assert(row.riskMarkers.contains("dynamic-version"))
      assert(row.riskMarkers.contains("sudo"))
      assert(row.status == PlanningTuiStatus.Active)
      assert(model.footer.contains("sudo symlinks 1"))
      assert(model.footer.contains("missing checksums 1"))

    test("ANSI-stripped rendering is stable and keeps full details"):
      val fixture = writeFixture(longValues = true)
      val model   = modelFor(fixture, selectedIndex = 0, width = 88, height = 60)

      val first  = PlanningTuiRenderer.render(model).mkString("\n")
      val second = PlanningTuiRenderer.render(model).mkString("\n")
      val plain  = stripAnsi(first)

      assert(stripAnsi(first) == stripAnsi(second))
      assert(!plain.contains("\u001b["))
      assert(plain.contains("…"))
      assert(model.detail.exists(_.lines.contains(s"download url: ${fixture.longUrl}")))
      assert(model.detail.exists(_.lines.contains(s"install dir: ${fixture.longInstallDir}")))
      assert(first.contains("\u001b["))

    test("planning TUI redacts redirected resolver final URLs"):
      val fixture    = writeFixture()
      val snapshot   = snapshotFor(fixture.options)
      val secret     = "secret-token-value"
      val provenance = UrlProvenance(
        "https://example.invalid/stable.txt",
        s"https://cdn.example.invalid/$secret/stable.txt",
        Vector(UrlRedirectHop(
          "https://example.invalid/stable.txt",
          s"https://cdn.example.invalid/$secret/stable.txt",
          302
        ))
      )
      val plan = snapshot.plan.copy(
        tools = snapshot.plan.tools.map(tool =>
          tool.copy(version = ResolvedVersion.Concrete("1.2.3", Some(provenance)))
        ),
        redactions = SensitiveValueRedactions(Vector(secret))
      )
      val model = PlanningTuiModel.fromSnapshot(
        snapshot.copy(plan = plan),
        testSettings(width = 100, height = 50).copy(detailScroll = 8)
      )

      val plain = stripAnsi(PlanningTuiRenderer.render(model).mkString("\n"))

      assert(plain.contains(
        "version resolver final url: https://cdn.example.invalid/<redacted>/stable.txt"
      ))
      assert(model.detail.exists(_.lines.contains(
        "version resolver redirects: 302 https://example.invalid/stable.txt -> " +
          "https://cdn.example.invalid/<redacted>/stable.txt"
      )))
      assert(plain.contains("version resolver redirects: 302 https://example.invalid/stable.txt"))
      assert(!plain.contains(secret))

    test("tab and documented backward equivalent cycle pane focus"):
      val state        = sessionState(writeFixture())
      val afterForward = PlanningTuiSession.run(
        state,
        Vector(TuiInput.Tab, TuiInput.Tab, TuiInput.Tab)
      )
      val afterBackward = PlanningTuiSession.run(
        state,
        Vector(TuiInput.Tab, TuiInput.Character('b'))
      )

      assert(afterForward.focusedPane == TuiPane.Plan)
      assert(afterBackward.focusedPane == TuiPane.Plan)

    test("enter focuses selected details and l focuses logs without resetting browsing state"):
      val browsedState = PlanningTuiSession.run(
        sessionState(writeFixture()),
        Vector(
          TuiInput.Down,
          TuiInput.Slash,
          TuiInput.Character('b'),
          TuiInput.Enter
        )
      )
      val detailsState = PlanningTuiSession.run(browsedState, Vector(TuiInput.Enter))
      val logsState    = PlanningTuiSession.run(detailsState, Vector(TuiInput.Character('l')))

      assert(detailsState.focusedPane == TuiPane.Details)
      assert(detailsState.toModel.detail.exists(_.name == "beta"))
      assert(detailsState.toModel.header.filterText == "b")
      assert(detailsState.appState.selectedToolNames == Set("alpha", "beta"))
      assert(logsState.focusedPane == TuiPane.Logs)
      assert(logsState.toModel.detail.exists(_.name == "beta"))
      assert(logsState.toModel.header.filterText == "b")

    test("plan focused arrows move selection and update details"):
      val finalState = PlanningTuiSession.run(
        sessionState(writeFixture()),
        Vector(TuiInput.Down)
      )
      val model = finalState.toModel

      assert(finalState.selectedIndex == 1)
      assert(model.header.selectionText == "selected 2 / total 2")
      assert(model.detail.exists(_.name == "beta"))

    test("focused details and logs scroll with keyboard and mouse wheel"):
      val logs  = Vector.tabulate(24)(index => s"log line ${index + 1}")
      val state = sessionState(
        writeFixture(longValues = true),
        settings = testSettings(width = 88).copy(logs = logs)
      )
      val detailsScrolled = PlanningTuiSession.run(
        state.withFocus(TuiPane.Details),
        Vector(TuiInput.PageDown, TuiInput.Down)
      )
      val logsScrolled = PlanningTuiSession.run(
        state.withFocus(TuiPane.Logs),
        Vector(TuiInput.Down, TuiInput.PageDown, TuiInput.MouseWheelDown, TuiInput.Home)
      )
      val logsAtEnd = PlanningTuiSession.run(
        state.withFocus(TuiPane.Logs),
        Vector(TuiInput.End)
      )

      assert(detailsScrolled.detailScroll > 0)
      assert(logsScrolled.logScroll == 0)
      assert(logsAtEnd.logScroll > 0)

    test("overflowing details and logs render in the focused info bar"):
      val fixture = writeFixture(longValues = true)
      val logs    = Vector.tabulate(30)(index => s"overflow log line ${index + 1}")
      val model   = sessionState(
        fixture,
        settings = testSettings(width = 88).copy(logs = logs)
      ).withFocus(TuiPane.Logs).withLogScroll(4).toModel
      val plain = stripAnsi(PlanningTuiRenderer.render(model).mkString("\n"))

      assert(plain.contains("Logs"))
      assert(plain.contains("overflow log line 5"))
      assert(plain.contains("overflow log line 20"))
      assert(plain.contains("Plan table"))

    test("slash filtering updates visible rows and header filter text"):
      val finalState = PlanningTuiSession.run(
        sessionState(writeFixture()),
        Vector(
          TuiInput.Slash,
          TuiInput.Character('b'),
          TuiInput.Character('e'),
          TuiInput.Enter
        )
      )
      val model = finalState.toModel

      assert(model.rows.map(_.name) == Vector("beta"))
      assert(model.header.selectionText == "selected 2 / total 2")
      assert(model.header.filterText == "be")

    test("help renders in-frame and closing it preserves browsing state"):
      val state      = sessionState(writeFixture())
      val helpState  = PlanningTuiSession.run(state, Vector(TuiInput.Question))
      val closeState = PlanningTuiSession.run(helpState, Vector(TuiInput.Escape))
      val plain      = stripAnsi(PlanningTuiRenderer.render(helpState.toModel).mkString("\n"))

      assert(helpState.helpOpen)
      assert(plain.contains("Help"))
      assert(plain.contains("Enter focuses selected entry details"))
      assert(plain.contains("q or Ctrl+C exits"))
      assert(!closeState.helpOpen)
      assert(closeState.appState == state.appState.copy(modal = None))

    test("interactive modal close returns to browsing frame and cleans up terminal"):
      val fixture  = writeFixture()
      val terminal = FakeTuiTerminal(
        interactive = true,
        TuiViewport(100, 38),
        inputs = Vector(TuiInput.Question, TuiInput.Escape, TuiInput.Quit)
      )
      val result = TuiModule.startInteractive(
        TuiRequest(TuiMode.Plan, fixture.options, Some("tui")),
        FakeHttpTextClient(""),
        testSettings(height = 38),
        terminal
      )
      val helpFrame   = stripAnsi(terminal.rendered(1).mkString("\n"))
      val closedFrame = stripAnsi(terminal.rendered(2).mkString("\n"))

      assert(result.exitCode == 0)
      assert(terminal.opened)
      assert(terminal.closed)
      assert(helpFrame.contains("Help"))
      assert(closedFrame.contains("Plan [focus]"))
      assert(!closedFrame.contains("Help"))

    test("quit inputs exit through terminal cleanup path"):
      val fixture       = writeFixture()
      val quitTerminal  = FakeTuiTerminal(true, TuiViewport(100, 38), Vector(TuiInput.Quit))
      val ctrlCTerminal = FakeTuiTerminal(true, TuiViewport(100, 38), Vector(TuiInput.CtrlC))

      val quitResult = TuiModule.startInteractive(
        TuiRequest(TuiMode.Plan, fixture.options),
        FakeHttpTextClient(""),
        testSettings(height = 38),
        quitTerminal
      )
      val ctrlCResult = TuiModule.startInteractive(
        TuiRequest(TuiMode.Plan, fixture.options),
        FakeHttpTextClient(""),
        testSettings(height = 38),
        ctrlCTerminal
      )

      assert(quitResult.exitCode == 0)
      assert(ctrlCResult.exitCode == 0)
      assert(quitTerminal.opened)
      assert(quitTerminal.closed)
      assert(ctrlCTerminal.opened)
      assert(ctrlCTerminal.closed)

    test("terminal render failure exits through cleanup path"):
      val fixture  = writeFixture()
      val terminal = FakeTuiTerminal(
        interactive = true,
        TuiViewport(100, 38),
        renderFailureAt = Some(0)
      )
      val result = TuiModule.startInteractive(
        TuiRequest(TuiMode.Plan, fixture.options, Some("tui")),
        FakeHttpTextClient(""),
        testSettings(height = 38),
        terminal
      )
      val plain = stripAnsi(result.lines.mkString("\n"))

      assert(result.exitCode == 1)
      assert(terminal.opened)
      assert(terminal.closed)
      assert(plain.contains("ERROR: Terminal failure"))
      assert(plain.contains("category: terminal"))
      assert(plain.contains("render failed"))

    test("planning session accepts resize inputs and renders within narrow bounds"):
      val state = sessionState(
        writeFixture(longValues = true),
        settings = testSettings(width = 100, height = 42)
      )
      val finalState = PlanningTuiSession.run(
        state,
        Vector(TuiInput.Resize(TuiViewport(32, 24)))
      )
      val rendered = PlanningTuiRenderer.render(finalState.toModel)

      assert(finalState.viewport == TuiViewport(32, 24))
      assertRenderedWithin(rendered, width = 32)

    test("resize events keep browsing and execution views within updated terminal width"):
      val fixture       = writeFixture(longValues = true)
      val browsingState = PlanningTuiSession.run(
        sessionState(fixture, settings = testSettings(width = 100, height = 42)),
        Vector(TuiInput.Resize(TuiViewport(30, 22)))
      )
      val executionState = TuiAppController.handle(
        browsingState.appState.copy(executionState =
          Some(ExecutionTuiState
            .initial(
              TuiRequest(TuiMode.Apply, fixture.options),
              ExecutionTuiSettings.fromPlanning(testSettings(width = 100, height = 42))
            )
            .onEvent(InstallerEvent.ToolStarted(
              "alpha-with-a-long-name",
              InstallerPhase.Downloading,
              elapsed(5)
            ))
            .onEvent(InstallerEvent.LogLine(
              Some("alpha-with-a-long-name"),
              "long log line that should be clipped after resize",
              elapsed(6)
            )))
        ),
        TuiInput.Resize(TuiViewport(30, 22))
      )

      assertRenderedWithin(TuiAppRenderer.render(browsingState.appState), width = 30)
      assertRenderedWithin(TuiAppRenderer.render(executionState), width = 30)
      assert(executionState.executionState.exists(_.viewport == TuiViewport(30, 22)))

    test("interactive planning rerenders after resize input"):
      val fixture  = writeFixture(longValues = true)
      val terminal = FakeTuiTerminal(
        interactive = true,
        TuiViewport(100, 38),
        inputs = Vector(TuiInput.Resize(TuiViewport(30, 22)), TuiInput.Quit)
      )
      val result = TuiModule.startInteractive(
        TuiRequest(TuiMode.Plan, fixture.options),
        FakeHttpTextClient(""),
        testSettings(height = 38),
        terminal
      )

      assert(result.exitCode == 0)
      assert(terminal.rendered.size == 2)
      assertRenderedWithin(terminal.rendered.last, width = 30)

    test("stty backend uses direct argv and redirects input to tty"):
      val tty     = File("/dev/tty")
      val runner  = RecordingProcessRunner(Vector(Some("saved-state\n"), Some(""), Some("")))
      val backend = SystemTuiTerminalBackend(runner, tty)

      val saved    = backend.readTerminalState()
      val raw      = backend.enterRawMode()
      val restored = backend.restoreTerminalState("saved; touch /tmp/owned")

      assert(saved.contains("saved-state"))
      assert(raw)
      assert(restored)
      assert(runner.specs.map(_.argv) == Vector(
        Vector("stty", "-g"),
        Vector("stty", "raw", "-echo", "min", "0", "time", "1"),
        Vector("stty", "saved; touch /tmp/owned")
      ))
      assert(runner.specs.flatMap(_.argv.headOption).forall(_ == "stty"))
      assert(runner.specs.forall(spec => !spec.argv.exists(arg => arg == "sh" || arg == "bash")))
      assert(runner.specs.forall(spec => !spec.argv.contains("-c")))
      assert(runner.specs.forall(_.inputFile == tty))

    test("system terminal emits resize input when size changes"):
      val backend = RecordingTerminalBackend(
        state = Some("saved-state"),
        rawModeResult = true,
        sizes = Vector(TuiViewport(100, 38), TuiViewport(32, 20))
      )
      val terminal = SystemTuiTerminal(backend, silentOutput())

      assert(terminal.viewport == TuiViewport(100, 38))
      terminal.open()
      val input = terminal.readInput()
      terminal.close()

      assert(input.contains(TuiInput.Resize(TuiViewport(32, 20))))

    test("system terminal restores raw mode and closes tty input after normal close"):
      val backend = RecordingTerminalBackend(
        state = Some("saved-state"),
        rawModeResult = true,
        openedInput = RecordingInputStream()
      )
      val terminal = SystemTuiTerminal(backend, silentOutput())

      terminal.open()
      terminal.close()
      terminal.close()

      assert(backend.actions == Vector("read-state", "raw", "open-input", "restore:saved-state"))
      assert(backend.openedInput.closed)

    test("system terminal restores raw mode when tty input open fails"):
      val backend = RecordingTerminalBackend(
        state = Some("saved-state"),
        rawModeResult = true,
        inputFailure = Some(IOException("missing tty"))
      )
      val terminal = SystemTuiTerminal(backend, silentOutput())

      var threw = false
      try
        terminal.open()
      catch case _: IOException => threw = true

      terminal.close()

      assert(threw)
      assert(backend.actions == Vector("read-state", "raw", "open-input", "restore:saved-state"))

  private def modelFor(
      fixture: TuiFixture,
      selectedIndex: Int,
      width: Int = 120,
      height: Int = 34
  ): PlanningTuiModel = PlanningTuiModel.fromSnapshot(
    snapshotFor(fixture.options),
    testSettings(selectedIndex = selectedIndex, width = width, height = height)
  )

  private def snapshotFor(options: InstallerOptions): ResolvedPlanSnapshot = ResolvedPlanSnapshot
    .resolve(
      options,
      FakeHttpTextClient(""),
      ResolutionOptions(Map("HOME" -> options.configPath))
    )
    .fold(
      error => throw new java.lang.AssertionError(error.toString),
      identity
    )

  private def testSettings(
      selectedIndex: Int = 0,
      width: Int = 120,
      height: Int = 34
  ): PlanningTuiSettings = PlanningTuiSettings(
    viewport = TuiViewport(width, height),
    appVersion = "1.2.3",
    hostSummary = "linux/amd64",
    selectedIndex = selectedIndex,
    filter = None,
    filterEditing = false,
    focusedPane = TuiPane.Plan,
    detailScroll = 0,
    logScroll = 0,
    helpOpen = false,
    logs = Vector("test log line")
  )

  private def sessionState(
      fixture: TuiFixture,
      settings: PlanningTuiSettings = testSettings()
  ): PlanningTuiState = PlanningTuiState.initial(
    snapshotFor(fixture.options),
    settings
  )

  private def withRedactions(
      state: PlanningTuiState,
      redactions: SensitiveValueRedactions
  ): PlanningTuiState =
    val snapshot = state.appState.snapshot
    PlanningTuiState(state.appState.copy(snapshot =
      snapshot.copy(plan = snapshot.plan.copy(redactions = redactions))
    ))

  private def sudoRequest(): SudoCredentialRequest = SudoCredentialRequest(
    "alpha",
    "/usr/local/bin/alpha",
    "/tmp/apps/alpha/bin/alpha",
    "create sudo symlink /tmp/apps/alpha/bin/alpha -> /usr/local/bin/alpha"
  )

  private def writeSudoApplyFixture(suffix: String): TuiFixture =
    val root      = Files.createTempDirectory(s"binstaller-tui-sudo-$suffix")
    val appsDir   = root.resolve("apps")
    val stateFile = Path.of(s"tui-password-$suffix-${System.nanoTime()}.state.json")
    val _         = Files.deleteIfExists(stateFile)
    val config    = root.resolve("profile.yaml")
    val install   = appsDir.resolve("alpha")
    val bytes     = sudoBinaryBytes
    val checksum  = sha256Hex(bytes)
    val url       = "https://example.invalid/releases/alpha"
    val linkPath  = root.resolve("sudo-bin").resolve("alpha")
    Files.writeString(
      config,
      s"""
         |apiVersion: binstaller.io/v1alpha1
         |kind: BinaryDistributionProfile
         |metadata:
         |  name: sudo-profile
         |spec:
         |  policy:
         |    appsDir: "$appsDir"
         |    stateFile: "$stateFile"
         |    allowSudoSymlinks: true
         |    continueOnError: false
         |  vars: {}
         |  versions:
         |    alpha: "1.0.0"
         |  plan:
         |    - name: alpha
         |      kind: binary-tool
         |      spec:
         |        versionRef: alpha
         |        installDir: "$install"
         |        download:
         |          url: "$url"
         |          filename: alpha
         |          checksum:
         |            algorithm: sha256
         |            value: "$checksum"
         |        executables:
         |          - path: bin/alpha
         |        symlinks:
         |          - path: "$linkPath"
         |            target: bin/alpha
         |            sudo: true
         |""".stripMargin
    )
    TuiFixture(root, config, appsDir, stateFile, url, install, installerOptions(config))

  private def sudoApplyService(
      commandExecutor: RecordingSudoCommandExecutor,
      credentials: SudoCredentialProvider
  ): BinaryInstallerService =
    val installer = DirectBinaryInstaller(
      StaticBinaryDownloadClient(sudoBinaryBytes),
      InstallFileSystem.nio,
      commandExecutor,
      credentials
    )
    BinaryInstallerService.resolving(FakeHttpTextClient(""), installer)

  private def sudoBinaryBytes: Array[Byte] =
    "binary\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)

  private def sha256Hex(bytes: Array[Byte]): String = MessageDigest
    .getInstance("SHA-256")
    .digest(bytes)
    .map(byte => f"${byte & 0xff}%02x")
    .mkString

  private def writeFixture(longValues: Boolean = false): TuiFixture =
    val root         = Files.createTempDirectory("binstaller-tui")
    val appsDir      = root.resolve("apps")
    val stateFile    = Path.of("tui.state.json")
    val config       = root.resolve("profile.yaml")
    val longSuffix   = "very-long-directory-name-for-truncation-checks"
    val alphaInstall =
      if longValues then appsDir.resolve(s"alpha-$longSuffix") else appsDir.resolve("alpha")
    val betaInstall = appsDir.resolve("beta")
    val alphaUrl    =
      if longValues then
        "https://example.invalid/releases/alpha/" +
          "very-long-download-url-that-must-remain-visible-in-details/alpha.tar.gz"
      else "https://example.invalid/releases/alpha.tar.gz"
    Files.writeString(
      config,
      s"""
         |apiVersion: binstaller.io/v1alpha1
         |kind: BinaryDistributionProfile
         |metadata:
         |  name: tui-profile
         |spec:
         |  policy:
         |    appsDir: "$appsDir"
         |    stateFile: "$stateFile"
         |    allowSudoSymlinks: true
         |  vars: {}
         |  versions:
         |    alpha: "1.0.0"
         |    beta: "2.0.0"
         |  plan:
         |    - name: alpha
         |      kind: binary-tool
         |      description: Alpha test tool.
         |      spec:
         |        versionRef: alpha
         |        installDir: "$alphaInstall"
         |        download:
         |          url: "$alphaUrl"
         |          filename: alpha.tar.gz
         |          checksum:
         |            algorithm: sha256
         |            value: "1111111111111111111111111111111111111111111111111111111111111111"
         |          archive:
         |            type: tar.gz
         |            extract:
         |              files:
         |                - from: alpha
         |                  to: bin/alpha
         |        executables:
         |          - path: bin/alpha
         |    - name: beta
         |      kind: binary-tool
         |      description: Beta test tool.
         |      spec:
         |        versionRef: beta
         |        installDir: "$betaInstall"
         |        download:
         |          url: https://example.invalid/releases/beta/beta-linux-amd64
         |          filename: beta
         |          checksum:
         |            algorithm: sha256
         |            value: "2222222222222222222222222222222222222222222222222222222222222222"
         |        executables:
         |          - path: bin/beta
         |        symlinks:
         |          - path: bin/beta
         |            target: bin/beta
         |""".stripMargin
    )
    TuiFixture(
      root,
      config,
      appsDir,
      stateFile,
      alphaUrl,
      alphaInstall,
      installerOptions(config)
    )

  private def writeRiskFixture(): TuiFixture =
    val root      = Files.createTempDirectory("binstaller-tui-risk")
    val appsDir   = root.resolve("apps")
    val stateFile = Path.of("risk.state.json")
    val config    = root.resolve("profile.yaml")
    val install   = appsDir.resolve("gamma")
    val url       = "https://example.invalid/releases/latest/gamma"
    Files.writeString(
      config,
      s"""
         |apiVersion: binstaller.io/v1alpha1
         |kind: BinaryDistributionProfile
         |metadata:
         |  name: risk-profile
         |spec:
         |  policy:
         |    appsDir: "$appsDir"
         |    stateFile: "$stateFile"
         |    allowSudoSymlinks: true
         |  vars: {}
         |  versions:
         |    gamma:
         |      dynamic:
         |        type: latest-url
         |        note: latest endpoint
         |  plan:
         |    - name: gamma
         |      kind: binary-tool
         |      spec:
         |        versionRef: gamma
         |        installDir: "$install"
         |        download:
         |          url: "$url"
         |          filename: gamma
         |        executables:
         |          - path: bin/gamma
         |        symlinks:
         |          - path: /usr/local/bin/gamma
         |            target: "$install/bin/gamma"
         |            sudo: true
         |""".stripMargin
    )
    TuiFixture(root, config, appsDir, stateFile, url, install, installerOptions(config))

  private def writeInvalidConfig(): Path =
    val root   = Files.createTempDirectory("binstaller-tui-invalid")
    val config = root.resolve("profile.yaml")
    Files.writeString(
      config,
      """
        |apiVersion: binstaller.io/v1alpha1
        |kind: BinaryDistributionProfile
        |metadata:
        |  name: invalid-profile
        |spec:
        |  policy: {}
        |  versions: {}
        |  plan:
        |    - name: ""
        |      kind: binary-tool
        |      spec: {}
        |""".stripMargin
    )
    config

  private def installerOptions(config: Path): InstallerOptions = InstallerOptions(
    configPath = config.toString,
    statePath = None,
    resetState = ResetState.Disabled,
    verboseOutput = VerboseOutput.Disabled,
    selection = ToolSelection.all
  )

  private def fixtureOptionsForMissingConfig(): InstallerOptions = InstallerOptions(
    configPath = "/tmp/binstaller-tui-missing-profile.yaml",
    statePath = None,
    resetState = ResetState.Disabled,
    verboseOutput = VerboseOutput.Disabled,
    selection = ToolSelection.all
  )

  private def elapsed(millis: Long): Duration = Duration.ofMillis(millis)

  private def stripAnsi(output: String): String = output.replaceAll("\u001b\\[[;\\d]*m", "")

  private def assertRenderedWithin(lines: Vector[String], width: Int): Unit =
    assert(lines.forall(line => stripAnsi(line).length <= width))

  private def silentOutput(): PrintWriter = PrintWriter(ByteArrayOutputStream(), true)

private final case class TuiFixture(
    root: Path,
    config: Path,
    appsDir: Path,
    stateFile: Path,
    longUrl: String,
    longInstallDir: Path,
    options: InstallerOptions
)

private final class FakeHttpTextClient(text: String) extends HttpTextClient:

  def getText(url: String): Either[HttpTextError, String] = Right(text)

private final case class StaticBinaryDownloadClient(bytes: Array[Byte])
    extends BinaryDownloadClient:

  def download(url: String): Either[BinaryDownloadError, Array[Byte]] = Right(bytes)

  override def download(
      url: String,
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, Array[Byte]] =
    progressObserver.onProgress(binstaller.core.BinaryDownloadProgress.Started(
      url,
      Some(bytes.length.toLong)
    ))
    progressObserver.onProgress(binstaller.core.BinaryDownloadProgress.Finished(
      url,
      bytes.length.toLong,
      Some(bytes.length.toLong)
    ))
    Right(bytes)

  override def downloadWithProvenance(
      url: String,
      progressObserver: BinaryDownloadProgressObserver
  ): Either[BinaryDownloadError, BinaryDownloadResult] = download(url, progressObserver).map(
    value => BinaryDownloadResult(value, UrlProvenance.direct(url))
  )

private final class RecordingSudoCommandExecutor(
    cacheAvailable: Boolean,
    passwordToLeak: Option[String] = None,
    passwordCommandFails: Boolean = false
) extends CommandExecutor:
  var commands: Vector[CommandSpec] = Vector.empty

  def run(spec: CommandSpec): Either[CommandExecutionError, Unit] =
    commands = commands :+ spec
    spec.argv match
      case Vector("sudo", "-n", "true") if cacheAvailable => Right(())
      case Vector("sudo", "-n", "true")                   =>
        Left(CommandExecutionError(spec, "sudo password required", Some(1)))
      case argv if argv.startsWith(Vector("sudo", "-n", "ln"))           => Right(())
      case argv if argv.startsWith(Vector("sudo", "-S", "-p", "", "ln")) =>
        if passwordCommandFails then Left(leakingFailure(spec))
        else Right(())
      case _ => Right(())

  private def leakingFailure(spec: CommandSpec): CommandExecutionError =
    val leaked = passwordToLeak.getOrElse("password")
    CommandExecutionError(
      spec,
      s"authentication failed with $leaked",
      Some(1),
      CommandOutput(s"stdout $leaked", s"stderr $leaked")
    )

private final class RecordingPlanService(result: InstallerResult) extends BinaryInstallerService:
  var planOptions: Vector[InstallerOptions]  = Vector.empty
  var applyOptions: Vector[InstallerOptions] = Vector.empty

  def planWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult =
    planOptions = planOptions :+ options
    result

  def applyWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult =
    applyOptions = applyOptions :+ options
    InstallerResult(Vector("unexpected apply"), 99)

  def versions(options: InstallerOptions): InstallerResult = InstallerResult(Vector.empty, 0)

  def lock(options: InstallerOptions, lockOptions: LockOptions): InstallerResult =
    InstallerResult(Vector.empty, 0)

private final class RecordingDryRunService(result: InstallerResult) extends BinaryInstallerService:
  var planOptions: Vector[InstallerOptions]  = Vector.empty
  var applyOptions: Vector[InstallerOptions] = Vector.empty

  def planWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult =
    planOptions = planOptions :+ options
    InstallerResult(Vector("unexpected plan"), 98)

  def applyWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult =
    applyOptions = applyOptions :+ options
    eventObserver.onEvent(InstallerEvent.ResolvingStarted(options.configPath, Duration.ZERO))
    eventObserver.onEvent(InstallerEvent.PlanReady(
      options.selection.only,
      options.statePath,
      Duration.ofMillis(1)
    ))
    eventObserver.onEvent(InstallerEvent.Summary(
      InstallerRunStatus.Succeeded,
      installed = 0,
      failed = 0,
      skipped = 0,
      exitCode = result.exitCode,
      stateFilePath = options.statePath,
      elapsedTime = Duration.ofMillis(2)
    ))
    result

  def versions(options: InstallerOptions): InstallerResult = InstallerResult(Vector.empty, 0)

  def lock(options: InstallerOptions, lockOptions: LockOptions): InstallerResult =
    InstallerResult(Vector.empty, 0)

private final class RecordingFailedApplyService(result: InstallerResult)
    extends BinaryInstallerService:
  var planOptions: Vector[InstallerOptions]  = Vector.empty
  var applyOptions: Vector[InstallerOptions] = Vector.empty

  def planWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult =
    planOptions = planOptions :+ options
    InstallerResult(Vector("unexpected plan"), 98)

  def applyWithEvents(
      options: InstallerOptions,
      eventObserver: InstallerEventObserver
  ): InstallerResult =
    applyOptions = applyOptions :+ options
    eventObserver.onEvent(InstallerEvent.ResolvingStarted(options.configPath, Duration.ZERO))
    eventObserver.onEvent(InstallerEvent.PlanReady(
      options.selection.only,
      options.statePath,
      Duration.ofMillis(1)
    ))
    eventObserver.onEvent(InstallerEvent.ToolStarted(
      "beta",
      InstallerPhase.CreatingSymlinks,
      Duration.ofMillis(2)
    ))
    eventObserver.onEvent(InstallerEvent.ToolResult(
      "beta",
      ToolResultStatus.Failed,
      None,
      Some("symlink: target -> path: permission denied"),
      Duration.ofMillis(3)
    ))
    eventObserver.onEvent(InstallerEvent.Summary(
      InstallerRunStatus.Failed,
      installed = 0,
      failed = 1,
      skipped = 0,
      exitCode = 1,
      stateFilePath = options.statePath,
      elapsedTime = Duration.ofMillis(4)
    ))
    result

  def versions(options: InstallerOptions): InstallerResult = InstallerResult(Vector.empty, 0)

  def lock(options: InstallerOptions, lockOptions: LockOptions): InstallerResult =
    InstallerResult(Vector.empty, 0)

private final class FakeTuiTerminal(
    interactive: Boolean,
    initialViewport: TuiViewport,
    inputs: Vector[TuiInput] = Vector.empty,
    openFailure: Option[Throwable] = None,
    renderFailureAt: Option[Int] = None
) extends TuiTerminal:
  var opened: Boolean                  = false
  var closed: Boolean                  = false
  var rendered: Vector[Vector[String]] = Vector.empty
  private var inputIndex: Int          = 0
  private var currentViewport          = initialViewport

  def isInteractive: Boolean = interactive

  def viewport: TuiViewport = currentViewport

  def open(): Unit =
    opened = true
    openFailure.foreach(error => throw error)

  def render(lines: Vector[String]): Unit =
    renderFailureAt.foreach: index =>
      if rendered.size == index then throw IOException("render failed")
    rendered = rendered :+ lines

  def readInput(): Option[TuiInput] =
    val input = inputs.lift(inputIndex)
    inputIndex = inputIndex + 1
    input.foreach:
      case TuiInput.Resize(value) => currentViewport = value
      case _                      => ()
    input

  def close(): Unit = closed = true

private final class RecordingProcessRunner(responses: Vector[Option[String]])
    extends TuiProcessRunner:
  var specs: Vector[TuiProcessSpec] = Vector.empty

  def run(spec: TuiProcessSpec): Option[String] =
    val response = responses.lift(specs.size).flatten
    specs = specs :+ spec
    response

private final class RecordingTerminalBackend(
    state: Option[String],
    rawModeResult: Boolean,
    val openedInput: RecordingInputStream = RecordingInputStream(),
    inputFailure: Option[Throwable] = None,
    sizes: Vector[TuiViewport] = Vector(TuiViewport(100, 38))
) extends TuiTerminalBackend:
  var actions: Vector[String] = Vector.empty
  private var sizeReads: Int  = 0

  def readSize(): Option[TuiViewport] =
    val index = sizeReads.min((sizes.size - 1).max(0))
    sizeReads = sizeReads + 1
    sizes.lift(index)

  def readTerminalState(): Option[String] =
    actions = actions :+ "read-state"
    state

  def enterRawMode(): Boolean =
    actions = actions :+ "raw"
    rawModeResult

  def restoreTerminalState(state: String): Boolean =
    actions = actions :+ s"restore:$state"
    true

  def openInput(): InputStream =
    actions = actions :+ "open-input"
    inputFailure.foreach(error => throw error)
    openedInput

private final class RecordingInputStream() extends ByteArrayInputStream(Array.empty[Byte]):
  var closed: Boolean = false

  override def close(): Unit =
    closed = true
    super.close()
