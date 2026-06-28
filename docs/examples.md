# Example Profiles

These are small, practical profiles you can copy into `my-workstation.yaml` and edit.

They are intentionally not mega-configs. The best Initkit profile starts boring and grows only when you actually need more.

## Run Any Example

Preview first:

```bash
initkit apply --config docs/examples/ubuntu.yaml --dry-run
```

Or from source:

```bash
./mill app.run apply --config docs/examples/ubuntu.yaml --dry-run
```

Open the TUI:

```bash
initkit tui --config docs/examples/ubuntu.yaml
```

Use a state file:

```bash
initkit apply \
  --config docs/examples/fedora.yaml \
  --state ~/.local/state/initkit/fedora-dev.state.json
```

## Profiles

| Distro | Profile | Package manager | Notes |
| --- | --- | --- | --- |
| Ubuntu | [ubuntu.yaml](examples/ubuntu.yaml) | apt | Docker apt source setup, apt actions, direct binary symlink, guarded commands, file write |
| Fedora | [fedora.yaml](examples/fedora.yaml) | dnf | DNF repo/key/release setup, DNF actions, cargo packages |
| EndeavourOS / Arch | [endeavouros.yaml](examples/endeavouros.yaml) | pacman | Pacman sync/upgrade actions, AUR packages, guarded commands |
| openSUSE Tumbleweed | [opensuse-tumbleweed.yaml](examples/opensuse-tumbleweed.yaml) | zypper | Packman source setup, zypper dup action, SDKMAN packages, SDDM file write |

## Legacy-Parity Features In The Examples

The examples use currently implemented manifest kinds only:

- Package manager actions through `actions` on `apt-packages`, `dnf-packages`, `pacman-packages`, and `zypper-packages`.
- Additional package ecosystems through `aur-packages`, `cargo-packages`, and `sdkman-packages`.
- Repository and remote setup through `spec.sources.apt`, `dnf`, `zypper`, and `flatpak`.
- File creation through `file-writes`.
- Command guards through `creates`, `unless`, `allowedExitCodes`, and `confirm` on `commands` items.
- Binary archive install plus symlink preview through `binary-downloads.items[].symlinks`.

Use `--dry-run` to inspect the generated source setup, package commands, file writes, command guard notes, and symlink commands before applying a profile.

## Distro ID Notes

Initkit matches against detected host facts. Linux distribution names come from `/etc/os-release`, usually the `ID` field.

If your machine reports a different ID than the examples, adjust the `when.os.distribution` block:

```bash
cat /etc/os-release
```

Then update:

```yaml
when:
  os:
    distribution:
      oneOf:
        - your-distro-id-here
```

## Package Failure Behavior

Package lists are not installed as one huge command. Initkit runs one package item at a time.

That means this:

```yaml
install:
  - git
  - wrong-package-name
  - jq
```

still attempts `jq` after `wrong-package-name` fails. The entry reports partial failure afterward.
