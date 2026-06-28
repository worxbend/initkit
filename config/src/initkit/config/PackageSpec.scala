package initkit.config

enum PackageSpec:
  case Apt(update: Option[Boolean], install: Vector[String])
  case Pacman(sync: Option[Boolean], install: Vector[String])
  case Dnf(install: Vector[String])
  case Zypper(refresh: Option[Boolean], install: Vector[String])
  case Flatpak(remote: Option[String], system: Option[Boolean], install: Vector[String])
  case Snap(install: Vector[SnapPackage])

final case class SnapPackage(
    name: String,
    classic: Option[Boolean]
)
