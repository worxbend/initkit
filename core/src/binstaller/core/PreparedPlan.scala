package binstaller.core

import binstaller.config.BinaryDistributionProfile

private[core] final case class PreparedPlan(
    profile: BinaryDistributionProfile,
    profileName: String,
    manifestFingerprint: String,
    plan: ResolvedPlan
)
