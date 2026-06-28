package initkit.config

final case class ManifestValidationError(path: String, detail: String):
  def message: String =
    s"$path: $detail"
