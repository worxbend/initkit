package binstaller.core

/** Values that must be redacted when raw runtime data reaches output surfaces. */
final case class SensitiveValueRedactions(values: Vector[String]):

  /** Replace every configured sensitive value with `<redacted>`. */
  def redact(value: String): String = values.foldLeft(value): (current, secret) =>
    current.replace(secret, "<redacted>")

/** Redaction policy constructors. */
object SensitiveValueRedactions:
  /** Redaction policy that does not hide any values. */
  val empty: SensitiveValueRedactions = SensitiveValueRedactions(Vector.empty)

  /** Derive sensitive values from environment-like variables by inspecting variable names. */
  def fromRuntimeVariables(values: Map[String, String]): SensitiveValueRedactions =
    val redactedValues = values.toVector.collect:
      case (name, value) if isSensitiveName(name) && value.length >= 4 => value
    SensitiveValueRedactions(redactedValues.distinct.sortBy(value => -value.length))

  private def isSensitiveName(name: String): Boolean =
    val upper = name.toUpperCase(java.util.Locale.ROOT)
    Vector(
      "TOKEN",
      "SECRET",
      "PASSWORD",
      "PASS",
      "API_KEY",
      "ACCESS_KEY",
      "PRIVATE_KEY",
      "CREDENTIAL",
      "AUTHORIZATION",
      "BEARER",
      "SESSION",
      "COOKIE"
    ).exists(upper.contains)

/** Display-safety helpers for terminal text, diagnostics, and env rendering. */
object RenderSafety:

  /** Redact sensitive values and replace terminal-control characters in a display string. */
  def display(
      value: String,
      redactions: SensitiveValueRedactions = SensitiveValueRedactions.empty
  ): String = scrubControls(redactions.redact(value))

  /** Apply [[display]] to each line. */
  def displayLines(
      lines: Vector[String],
      redactions: SensitiveValueRedactions = SensitiveValueRedactions.empty
  ): Vector[String] = lines.map(display(_, redactions))

  /** Render a single terminal row by removing embedded line breaks and tabs. */
  def terminalLine(
      value: String,
      redactions: SensitiveValueRedactions = SensitiveValueRedactions.empty
  ): String = display(value, redactions)
    .replace('\n', ' ')
    .replace('\t', ' ')

  /** Render an environment value for diagnostics without exposing likely secrets. */
  def envValue(name: String, value: String): String =
    val safeNames = Set("PATH", "HOME", "LANG", "LC_ALL", "SHELL", "TERM", "TMPDIR", "USER")
    if safeNames(name) || name.startsWith("LC_") then display(value)
    else "<redacted>"

  private def scrubControls(value: String): String = value.map:
    case '\n'                                => '\n'
    case '\t'                                => ' '
    case ch if ch < ' ' || ch == 0x7f.toChar => '?'
    case ch                                  => ch
