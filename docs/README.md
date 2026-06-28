# Initkit Docs

Welcome to the slightly more detailed side of Initkit.

If the main README is the friendly tour, this folder is the toolbox.

## Start Here

- [Config structure](config-structure.md): every top-level manifest section, explained.
- [Examples](examples.md): distro-specific profiles you can copy and edit.
- [Developer guide](developer-guide.md): how to build, test, release, and contribute.
- [Architecture overview](architecture.md): how the CLI, TUI, config loader, and engine fit together.

## Example Profiles

- [Ubuntu](examples/ubuntu.yaml)
- [Fedora](examples/fedora.yaml)
- [EndeavourOS / Arch](examples/endeavouros.yaml)
- [openSUSE Tumbleweed](examples/opensuse-tumbleweed.yaml)

## Important Safety Notes

- Run with `--dry-run` before applying a real profile.
- Keep execution state outside the manifest file.
- Package-manager entries install one package at a time, so one bad package name does not block the rest of the list.
- Shell commands are powerful. Keep them explicit and boring.
- Checksums are strongly recommended for downloaded binaries.

## Current Manifest API

```yaml
apiVersion: initkit.io/v1alpha1
kind: WorkstationProfile
```

`v1alpha1` means the format is usable, but still allowed to evolve while the project grows.
