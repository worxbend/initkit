package initkit.config

enum PackageSpec:

  case Apt(
      update: Option[Boolean],
      install: Vector[String],
      actions: Vector[PackageAction] = Vector.empty
  )

  case Pacman(
      sync: Option[Boolean],
      install: Vector[String],
      actions: Vector[PackageAction] = Vector.empty
  )

  case Dnf(install: Vector[String], actions: Vector[PackageAction] = Vector.empty)

  case Zypper(
      refresh: Option[Boolean],
      install: Vector[String],
      actions: Vector[PackageAction] = Vector.empty
  )

  case Flatpak(remote: Option[String], system: Option[Boolean], install: Vector[String])
  case Snap(install: Vector[SnapPackage])
  case Aur(helper: Option[String], install: Vector[String])
  case Cargo(installer: Option[String], install: Vector[String])
  case Sdkman(install: Vector[SdkmanPackage])

final case class PackageAction(
    action: String,
    args: Vector[String]
)

final case class SnapPackage(
    name: String,
    classic: Option[Boolean]
)

final case class SdkmanPackage(
    candidate: String,
    version: Option[String]
)
