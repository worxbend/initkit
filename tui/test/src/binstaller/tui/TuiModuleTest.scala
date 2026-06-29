package binstaller.tui

import binstaller.core.InstallerOptions
import binstaller.core.ResetState
import binstaller.core.VerboseOutput
import utest.*

object TuiModuleTest extends TestSuite:

  val tests: Tests = Tests:
    test("module path includes upstream modules"):
      assert(TuiModule.modulePath == Vector("config", "core", "tui"))

    test("plan tui shell returns explicit not implemented result"):
      val result = TuiModule.start(TuiRequest(TuiMode.Plan, installerOptions))

      assert(result.exitCode == 1)
      assert(result.lines.head == "binstaller plan --tui is not implemented yet.")
      assert(result.lines.exists(_.contains("non-interactive")))

    test("apply tui shell returns explicit not implemented result"):
      val result = TuiModule.start(TuiRequest(TuiMode.Apply, installerOptions))

      assert(result.exitCode == 1)
      assert(result.lines.head == "binstaller apply --tui is not implemented yet.")
      assert(result.lines.exists(_.contains("apply --dry-run")))

  private val installerOptions: InstallerOptions = InstallerOptions(
    configPath = "profile.yaml",
    statePath = None,
    resetState = ResetState.Disabled,
    verboseOutput = VerboseOutput.Disabled
  )
