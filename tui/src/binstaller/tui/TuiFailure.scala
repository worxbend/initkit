package binstaller.tui

import binstaller.config.ConfigLoadError
import binstaller.core.InstallerResult
import binstaller.core.RenderSafety
import binstaller.core.ResolvePlanError
import binstaller.core.SensitiveValueRedactions

/** High-level category for user-visible TUI failures. */
enum TuiFailureCategory(val label: String):
  case Validation extends TuiFailureCategory("validation")
  case Config     extends TuiFailureCategory("config")
  case Resolution extends TuiFailureCategory("resolution")
  case DryRun     extends TuiFailureCategory("dry-run")
  case Apply      extends TuiFailureCategory("apply")
  case Lock       extends TuiFailureCategory("lock")
  case State      extends TuiFailureCategory("state")
  case Terminal   extends TuiFailureCategory("terminal")

/** Structured failure payload shown in TUI error and root-cause modals. */
final case class TuiFailure(
    title: String,
    category: TuiFailureCategory,
    action: Option[String],
    toolName: Option[String],
    path: Option[String],
    rootCause: String,
    suggestion: String,
    stdoutSnippet: Vector[String],
    stderrSnippet: Vector[String],
    detailLines: Vector[String]
):

  /** Sanitized modal body lines with bounded process-output snippets. */
  def renderLines: Vector[String] = Vector(s"category: ${category.label}") ++
    action.map(value => s"action: $value").toVector ++
    toolName.map(value => s"tool: $value").toVector ++
    path.map(value => s"path: $value").toVector ++
    Vector(
      s"root cause: $rootCause",
      s"suggestion: $suggestion"
    ) ++ snippetLines("stdout", stdoutSnippet) ++
    snippetLines("stderr", stderrSnippet) ++
    detailSection

  private def snippetLines(label: String, lines: Vector[String]): Vector[String] =
    if lines.isEmpty then Vector.empty
    else Vector(s"$label:") ++ lines.map(line => s"  $line")

  private def detailSection: Vector[String] =
    val compact = detailLines.filterNot(_.isBlank).distinct.take(8)
    if compact.isEmpty then Vector.empty
    else Vector("details:") ++ compact.map(line => s"  $line")

/** Constructors for structured TUI failures. */
object TuiFailure:
  private val MaxSnippetLines = 6

  /** Build a TUI failure from a plan-resolution failure. */
  def fromResolvePlanError(entrypoint: String, error: ResolvePlanError): TuiFailure =
    val lines    = ResolvePlanError.renderLines(error)
    val category = error match
      case ResolvePlanError.ConfigLoadFailed(ConfigLoadError.ValidationFailed(_)) =>
        TuiFailureCategory.Validation
      case ResolvePlanError.ConfigLoadFailed(_) => TuiFailureCategory.Config
      case ResolvePlanError.ValidationFailed(_) => TuiFailureCategory.Validation
      case ResolvePlanError.SelectionFailed(_)  => TuiFailureCategory.Resolution
    fromLines(
      title = "TUI startup failed",
      category = category,
      action = Some(entrypoint),
      affectedTool = None,
      affectedPath = extractPath(lines),
      lines = lines,
      redactions = SensitiveValueRedactions.empty
    )

  /** Build a TUI failure from a failed installer result. */
  def fromResult(
      title: String,
      defaultCategory: TuiFailureCategory,
      action: String,
      result: InstallerResult,
      affectedTool: Option[String],
      affectedPath: Option[String],
      redactions: SensitiveValueRedactions
  ): TuiFailure =
    val rawLines  = splitLines(result.lines)
    val safeLines = RenderSafety.displayLines(rawLines, redactions)
    val category  = classify(defaultCategory, safeLines)
    fromLines(
      title = title,
      category = category,
      action = Some(action),
      affectedTool = affectedTool.orElse(extractTool(safeLines)),
      affectedPath = affectedPath.orElse(extractPath(safeLines)),
      lines = safeLines,
      redactions = SensitiveValueRedactions.empty
    )

  /** Build a terminal-boundary failure from an exception. */
  def terminal(action: String, error: Throwable): TuiFailure = fromLines(
    title = "Terminal failure",
    category = TuiFailureCategory.Terminal,
    action = Some(action),
    affectedTool = None,
    affectedPath = None,
    lines = Vector(Option(error.getMessage).getOrElse(error.getClass.getSimpleName)),
    redactions = SensitiveValueRedactions.empty
  )

  private def fromLines(
      title: String,
      category: TuiFailureCategory,
      action: Option[String],
      affectedTool: Option[String],
      affectedPath: Option[String],
      lines: Vector[String],
      redactions: SensitiveValueRedactions
  ): TuiFailure =
    val safeLines = RenderSafety.displayLines(lines, redactions)
    val stdout    = snippet("stdout", safeLines)
    val stderr    = snippet("stderr", safeLines)
    TuiFailure(
      title = RenderSafety.display(title, redactions),
      category = category,
      action = action.map(RenderSafety.display(_, redactions)),
      toolName = affectedTool.map(RenderSafety.display(_, redactions)),
      path = affectedPath.map(RenderSafety.display(_, redactions)),
      rootCause = rootCause(safeLines),
      suggestion = suggestion(category),
      stdoutSnippet = stdout,
      stderrSnippet = stderr,
      detailLines = detailLines(safeLines)
    )

  private def splitLines(lines: Vector[String]): Vector[String] =
    lines.flatMap(_.linesIterator.toVector).map(_.trim).filterNot(_.isBlank)

  private def classify(
      defaultCategory: TuiFailureCategory,
      lines: Vector[String]
  ): TuiFailureCategory =
    val text = lines.mkString("\n").toLowerCase(java.util.Locale.ROOT)
    if text.contains("lock ") || text.contains("locked apply") then TuiFailureCategory.Lock
    else if text.contains("state ") || text.contains("state-") then TuiFailureCategory.State
    else if text.contains("config ") || text.contains("spec.") then TuiFailureCategory.Validation
    else defaultCategory

  private def extractTool(lines: Vector[String]): Option[String] = lines.collectFirst:
    case line if line.startsWith("failed ") && line.contains(":") =>
      line.stripPrefix("failed ").takeWhile(_ != ':').trim
  .filter(_.nonEmpty)

  private def extractPath(lines: Vector[String]): Option[String] =
    val pathPatterns = Vector(" for ", " at ")
    lines.collectFirst:
      case line if pathPatterns.exists(line.contains) && line.contains(":") =>
        val marker = pathPatterns.find(line.contains).getOrElse(" for ")
        line.drop(line.indexOf(marker) + marker.length).takeWhile(_ != ':').trim
    .filter(_.nonEmpty)

  private def rootCause(lines: Vector[String]): String = lines
    .find(line => line.nonEmpty && !isOutputLine(line))
    .getOrElse("the action failed without a detailed message")

  private def snippet(label: String, lines: Vector[String]): Vector[String] =
    val prefix = s"$label:"
    lines
      .collect:
        case line if line.stripLeading().startsWith(prefix) =>
          line.stripLeading().stripPrefix(prefix).trim
      .filterNot(_.isBlank)
      .takeRight(MaxSnippetLines)

  private def detailLines(lines: Vector[String]): Vector[String] =
    lines.filterNot(isOutputLine).take(12)

  private def isOutputLine(line: String): Boolean =
    val trimmed = line.stripLeading()
    trimmed.startsWith("stdout:") || trimmed.startsWith("stderr:")

  private def suggestion(category: TuiFailureCategory): String = category match
    case TuiFailureCategory.Validation =>
      "Fix the manifest field named in the error and reload the TUI."
    case TuiFailureCategory.Config => "Check the config path and YAML syntax, then reload the TUI."
    case TuiFailureCategory.Resolution =>
      "Check resolver URLs, selected tool names, and policy settings before retrying."
    case TuiFailureCategory.DryRun =>
      "Review the dry-run root cause, adjust the manifest or lock, then rerun dry-run."
    case TuiFailureCategory.Apply =>
      "Review the failed tool, fix the underlying filesystem/download issue, then rerun apply."
    case TuiFailureCategory.Lock =>
      "Regenerate the lock file or pass the compatible lock path before retrying."
    case TuiFailureCategory.State =>
      "Inspect the state file or retry with --reset-state if the previous state is stale."
    case TuiFailureCategory.Terminal =>
      "Restore the terminal or run the non-interactive command variant for diagnostics."

/** Standalone error screen used when the TUI cannot build its normal app state. */
object TuiFailureScreen:

  /** Render a visible failure modal without requiring a resolved plan snapshot. */
  def render(failure: TuiFailure, viewport: TuiViewport): Vector[String] =
    val width = viewport.width.max(1)
    Vector(
      fit("binstaller tui", width),
      separator(width),
      fit(s"ERROR: ${failure.title}", width)
    ) ++
      failure.renderLines.map(fit(_, width)) ++
      Vector(
        separator(width),
        fit("Press q to exit when running inside an interactive shell.", width)
      )

  private def separator(width: Int): String = "-" * width

  private def fit(value: String, width: Int): String =
    val safe = RenderSafety.terminalLine(value)
    if safe.length <= width then safe + (" " * (width - safe.length).max(0))
    else if width == 1 then "."
    else safe.take(width - 1) + "."
