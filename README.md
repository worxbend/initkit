# binstaller

[![Native Release](https://github.com/worxbend/initkit/actions/workflows/native-release.yml/badge.svg)](https://github.com/worxbend/initkit/actions/workflows/native-release.yml)
![Scala 3](https://img.shields.io/badge/Scala-3.8.2-dc322f?logo=scala&logoColor=white)
![Mill](https://img.shields.io/badge/Mill-1.1.7-5b5bd6)
![GraalVM](https://img.shields.io/badge/GraalVM-native%20ready-f2a900?logo=graalvm&logoColor=111111)
![Linux](https://img.shields.io/badge/Linux-amd64%20binary%20installer-2ea44f?logo=linux&logoColor=white)

`binstaller` installs Linux amd64 binary tool distributions from one YAML
profile. It resolves versions, previews what will be downloaded and unpacked,
then installs selected tools under a user-controlled apps directory such as
`${HOME}/.apps`.

The supported scope is deliberately narrow: direct binary downloads, `zip`,
`tar.gz`, and `tar.xz` archives, executable checks, local symlinks, optional
sudo symlinks, dry-run previews, apply state/resume, CLI progress, explicit TUI
entrypoints, and version reporting. It is not a package manager, dotfiles
runner, installer-script host, broad shell-command runner, or multi-OS
workstation provisioner.

## Install

Native Linux amd64 builds are published from `v*` tags by GitHub Actions.

```bash
curl -L -o binstaller \
  https://github.com/worxbend/initkit/releases/latest/download/binstaller-linux-amd64
chmod +x binstaller
sudo mv binstaller /usr/local/bin/binstaller
```

Release artifacts:

- `binstaller-linux-amd64`
- `binstaller-linux-amd64.tar.gz`
- `SHA256SUMS`

## Quick Start

Use the checked-in [config.example.yaml](config.example.yaml) as the reference
profile. It uses:

```yaml
apiVersion: binstaller.io/v1alpha1
kind: BinaryDistributionProfile
```

Preview the resolved plan:

```bash
binstaller plan --config config.example.yaml
```

Render the apply operations without changing files:

```bash
binstaller apply --config config.example.yaml --dry-run
```

Apply the profile. `--yes` is required when the profile has
`requireConfirmation: true`.

```bash
binstaller apply --config config.example.yaml --yes
```

Select or omit tools by name. These flags may be repeated.

```bash
binstaller plan --config config.example.yaml --only yazi
binstaller apply --config config.example.yaml --skip neovim --dry-run
```

Inspect pinned, resolved, and dynamic version sources:

```bash
binstaller versions --config config.example.yaml
```

Write a reproducible lock file without installing tools:

```bash
binstaller lock --config config.example.yaml --output binstaller.lock.json
```

Open the interactive installer workspace:

```bash
binstaller tui --config config.example.yaml
```

The manifest policy defaults to `mode: developer`, which preserves local
tooling convenience. Production-oriented profiles can set `policy.mode: strict`
to reject dynamic latest URLs, missing checksums, sudo symlinks, and `tar.xz`
fallback extraction unless those risks are explicitly allowed in the manifest.

Source-development equivalents use the checked-in Mill launcher:

```bash
./mill app.run --help
./mill app.run plan --config config.example.yaml
./mill app.run apply --config config.example.yaml --dry-run
./mill app.run versions --config config.example.yaml
./mill app.run lock --config config.example.yaml --output binstaller.lock.json
./mill app.run tui --config config.example.yaml
```

## CLI Surface

Top-level commands:

- `plan`: render the binary installer plan without changing files.
- `tui`: open the interactive terminal UI workspace.
- `apply`: run the installer, or render operations with `--dry-run`.
- `versions`: resolve and print manifest version sources.
- `lock`: resolve and write a JSON lock file without installing tools.

Useful shared options:

- `--config FILE`: path to the YAML profile; required for `plan`, `tui`,
  `apply`, `versions`, and `lock`.
- `--state FILE`: override the profile state file for apply and TUI actions.
- `--reset-state`: ignore saved execution state and start fresh for apply and
  TUI actions.
- `--verbose`: show additional command diagnostics.
- `--only TOOL`: include only a named tool for `plan`, `apply`, or `lock`;
  repeatable.
- `--skip TOOL`: omit a named tool for `plan`, `apply`, or `lock`; repeatable.

`apply` also accepts:

- `--dry-run`: render concrete apply operations without downloads, install
  writes, symlink writes, or state writes.
- `--yes`: confirm non-dry-run apply actions, including sudo symlinks.
- `--locked`: require a compatible JSON lock file before rendering or applying.
- `--lock-file FILE`: path to the JSON lock file used by `--locked`.

`lock` also accepts:

- `--output FILE`: path to the JSON lock file to write. The default is
  `binstaller.lock.json`.

Exit codes:

- `0`: command completed successfully, including help and dry-run commands.
- `1`: manifest loading or resolution failed, selection was invalid,
  confirmation was missing, apply failed, or state persistence failed.
- `2`: command-line usage error, including a missing required `--config`.

## State And Resume

Non-dry-run `apply` writes state after each terminal tool result. State is tied
to the profile name and manifest fingerprint, so a later apply can skip tools
already completed for the same profile.

The state path comes from `--state` or `spec.policy.stateFile`. Current apply
state paths are current-directory filenames only; absolute, nested, and empty
paths are rejected. `plan` and `apply --dry-run` do not touch state.

Use `--reset-state` when you intentionally want to ignore compatible saved
state and retry from the beginning.

## TUI

The TUI is explicit and optional. Default command output remains
script-friendly.

```bash
binstaller tui --config config.example.yaml
```

The workspace is table-first: a compact header, a selectable tool table, a lower
info bar, and one command legend. The table owns checkbox selection, row focus,
status, and execution progress. The lower info bar shows selected-tool details
by default, then plan preview, dry-run/apply output, logs, or error output when
those views are active.

Core shortcuts are `Space` for the current row, `a`/`c`/`i` for visible-row
bulk selection, `/` for filtering, `p` for plan preview, `d` for dry-run, `r`
for confirmed apply, `l` for logs, `Enter` for details or root-cause modals,
`?` for help, and `q`/`Ctrl+C` for terminal cleanup and exit. During dry-run or
apply, selected candidates remain in stable order; the active row shows spinner,
progress, byte counts when known, and final completed, failed, skipped, or
pending status. Very narrow terminals keep the ordered candidate list and move
the active progress bar to the lower info bar.

Privileged sudo symlinks are still limited by manifest policy and confirmation.
When cached sudo credentials are unavailable, TUI apply opens a focused masked
password modal that names the operation, tool, destination, and target. Password
text is sent through the core secret-stdin boundary, never argv, logs, errors,
previews, or state. `Escape`, `Ctrl+C`, `q`, end-of-input, or `/cancel` plus
`Enter` cancels only the current privileged operation and reports a clear apply
failure.

In non-interactive shells, the TUI renders a static fallback frame instead of
entering raw mode or the alternate screen.

## Build From Source

Requirements:

- JDK 21+
- GraalVM 21 only when building native images locally

Common checks:

```bash
./mill config.test
./mill core.test
./mill cli.test
./mill tui.test
./mill __.compile
./mill __.test
./mill mill.scalalib.scalafmt/checkFormatAll
git diff --check
```

Build a native image locally when GraalVM is installed:

```bash
GRAALVM_HOME=/path/to/graalvm ./mill app.nativeImage
```

## Documentation

- [Architecture](docs/architecture.md): module graph, data flow, and event
  contract.
- [Manifest reference](docs/manifest-reference.md): supported profile shape,
  policy fields, versions, downloads, archives, symlinks, and selection.
- [Security model](docs/security.md): trust boundaries, checksums, archive
  safety, sudo policy, state rules, redaction, and known risks.
- [TUI guide](docs/tui-guide.md): planning/execution views, keybindings, and
  terminal troubleshooting.
- [TUI smoke workflow](docs/tui-smoke.md): manual terminal smoke steps.
- [Testing guide](docs/testing.md): project-native checks and test patterns.
- [Release guide](docs/release.md): native artifacts, release workflow, and
  smoke checks.
- [First-class TUI review](docs/first-class-tui-review.md): final command,
  control-flow, modal, selection, security, and deferral review.
- [Post-TUI readiness review](docs/post-tui-readiness-review.md): hardening
  findings, implemented fixes, and documented deferrals.
