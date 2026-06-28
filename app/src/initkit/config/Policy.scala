package initkit.config

final case class Policy(
    dryRun: Option[Boolean],
    continueOnError: Option[Boolean],
    requireSudo: Option[Boolean],
    reboot: Option[RebootPolicy]
)

final case class RebootPolicy(
    allowed: Option[Boolean],
    prompt: Option[Boolean]
)
