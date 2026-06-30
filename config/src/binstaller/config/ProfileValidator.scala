package binstaller.config

private[config] object ProfileValidator:

  def validate(profile: BinaryDistributionProfile): Vector[ValidationError] =
    toolNameErrors(profile) ++ duplicateToolNameErrors(profile) ++
      unknownVersionRefErrors(profile) ++
      sudoSymlinkErrors(profile)

  private def toolNameErrors(profile: BinaryDistributionProfile): Vector[ValidationError] =
    profile.spec.plan.zipWithIndex.flatMap:
      case (entry, index) => unsafeToolNameMessage(entry.name).map: message =>
          ValidationError(s"spec.plan[$index].name", message)

  private def unsafeToolNameMessage(value: String): Option[String] =
    if value.trim.isEmpty then Some("tool name must not be empty")
    else if value.exists(Character.isISOControl) then
      Some("tool name must not contain control characters")
    else if value.contains('/') || value.contains('\\') then
      Some("tool name must not contain path separators")
    else if value == "." || value == ".." then Some("tool name must not be a traversal segment")
    else None

  private def duplicateToolNameErrors(
      profile: BinaryDistributionProfile
  ): Vector[ValidationError] = profile.spec.plan
    .groupBy(_.name)
    .toVector
    .collect:
      case (name, entries) if name.nonEmpty && entries.size > 1 =>
        ValidationError("spec.plan", s"duplicate tool name '$name'")

  private def unknownVersionRefErrors(
      profile: BinaryDistributionProfile
  ): Vector[ValidationError] =
    val versionNames = profile.spec.versions.keySet
    profile.spec.plan.zipWithIndex.collect:
      case (entry, index)
          if entry.spec.versionRef.nonEmpty && !versionNames(entry.spec.versionRef) =>
        ValidationError(
          s"spec.plan[$index].spec.versionRef",
          s"tool '${entry.name}' references unknown version '${entry.spec.versionRef}'"
        )

  private def sudoSymlinkErrors(profile: BinaryDistributionProfile): Vector[ValidationError] =
    profile.spec.policy.allowSudoSymlinks match
      case AllowSudoSymlinks.Enabled  => Vector.empty
      case AllowSudoSymlinks.Disabled => profile.spec.plan.zipWithIndex.flatMap:
          case (entry, entryIndex) => entry.spec.symlinks.zipWithIndex.collect:
              case (symlink, symlinkIndex) if symlink.privilege == SymlinkPrivilege.Sudo =>
                ValidationError(
                  s"spec.plan[$entryIndex].spec.symlinks[$symlinkIndex].sudo",
                  sudoSymlinkPolicyMessage(profile, entry.name)
                )

  private def sudoSymlinkPolicyMessage(
      profile: BinaryDistributionProfile,
      toolName: String
  ): String = profile.spec.policy.mode match
    case PolicyMode.Strict =>
      s"strict-policy[sudo-symlink]: tool '$toolName' uses a sudo symlink; " +
        "suggestion[allow-sudo-symlinks]: set spec.policy.allowSudoSymlinks: true " +
        "only for reviewed system symlinks"
    case PolicyMode.Developer =>
      s"tool '$toolName' uses a sudo symlink but policy.allowSudoSymlinks is false"
