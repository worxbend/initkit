package binstaller.tui

import binstaller.core.CoreModule
import binstaller.core.InstallerOptions
import binstaller.core.InstallerResult

object TuiModule:
  def modulePath: Vector[String] = CoreModule.modulePath :+ "tui"

  def start(request: TuiRequest): InstallerResult = InstallerResult(
    Vector(
      s"binstaller ${request.mode.commandName} --tui is not implemented yet.",
      "Default plan, apply, apply --dry-run, and versions output remains non-interactive."
    ),
    1
  )

enum TuiMode:
  case Plan, Apply

  def commandName: String = this match
    case Plan  => "plan"
    case Apply => "apply"

final case class TuiRequest(mode: TuiMode, options: InstallerOptions)
