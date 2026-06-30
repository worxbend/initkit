# binstaller

[![Native Release](https://github.com/worxbend/initkit/actions/workflows/native-release.yml/badge.svg)](https://github.com/worxbend/initkit/actions/workflows/native-release.yml)
![Scala 3](https://img.shields.io/badge/Scala-3.8.2-dc322f?logo=scala&logoColor=white)
![Mill](https://img.shields.io/badge/Mill-1.1.7-5b5bd6)
![GraalVM](https://img.shields.io/badge/GraalVM-native%20ready-f2a900?logo=graalvm&logoColor=111111)
![Linux](https://img.shields.io/badge/Linux-amd64%20binary%20installer-2ea44f?logo=linux&logoColor=white)

`binstaller` is a command-line installer for Linux amd64 binary tool
distributions described by one YAML profile. It resolves versions, previews
what will be downloaded and unpacked, then installs selected tools under a
user-controlled apps directory such as `${HOME}/.apps`.

The supported scope is deliberately narrow: command-line plan/apply workflows,
direct binary downloads, `zip`, `tar.gz`, and `tar.xz` archives, executable
checks, local symlinks, optional sudo symlinks, plan previews, apply
state/resume, CLI progress, and version reporting. It is not a package manager,
dotfiles runner, installer-script host, broad shell-command runner, or multi-OS
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

Apply the profile. `--yes` is required when the profile has
`requireConfirmation: true`.

```bash
binstaller apply --config config.example.yaml --yes
```

Select or omit tools by name. These flags may be repeated.

```bash
binstaller plan --config config.example.yaml --only yazi
binstaller apply --config config.example.yaml --skip neovim --yes
```

Print a package/version summary table. For tools downloaded from GitHub
Releases, this also checks the repository's latest release tag and prints the
newer version when an update is available.

```bash
binstaller versions --config config.example.yaml
```

Write a reproducible lock file without installing tools:

```bash
binstaller lock --config config.example.yaml --output /tmp/binstaller.lock.json
```

The manifest policy defaults to `mode: developer`, which preserves local
tooling convenience. Production-oriented profiles can set `policy.mode: strict`
to reject dynamic latest URLs, missing checksums, sudo symlinks, and `tar.xz`
fallback extraction unless those risks are explicitly allowed in the manifest.

Source-development equivalents use the checked-in Mill launcher:

```bash
./mill app.run --help
./mill app.run plan --config config.example.yaml
./mill app.run apply --config config.example.yaml --yes
./mill app.run versions --config config.example.yaml
./mill app.run lock --config config.example.yaml --output /tmp/binstaller.lock.json
```

## CLI Surface

Top-level commands:

| Command | Purpose | Writes files |
|---|---|---|
| `plan` | Render the resolved install plan. | No |
| `apply --yes` | Download, stage, install, symlink, and save state. | Yes |
| `versions` | Print package versions and available GitHub release updates. | No |
| `lock` | Resolve and write a JSON lock file. | Lock file only |

Useful shared options:

- `--config FILE`: path to the YAML profile; required for `plan`, `apply`,
  `versions`, and `lock`.
- `--state FILE`: override the profile state file for apply.
- `--reset-state`: ignore saved execution state and start fresh for apply.
- `--verbose`: show additional command diagnostics.
- `--only TOOL`: include only a named tool for `plan`, `apply`, or `lock`;
  repeatable.
- `--skip TOOL`: omit a named tool for `plan`, `apply`, or `lock`; repeatable.

`apply` also accepts:

- `--yes`: confirm apply actions, including sudo symlinks.
- `--locked`: require a compatible JSON lock file before applying.
- `--lock-file FILE`: path to the JSON lock file used by `--locked`.

`lock` also accepts:

- `--output FILE`: path to the JSON lock file to write. The default is
  `binstaller.lock.json`.

Exit codes:

- `0`: command completed successfully, including help and plan commands.
- `1`: manifest loading or resolution failed, selection was invalid,
  confirmation was missing, apply failed, or state persistence failed.
- `2`: command-line usage error, including a missing required `--config`.

## State And Resume

`apply` writes state after each per-tool result. State is tied to the profile
name and manifest fingerprint, so a later apply can skip tools already completed
for the same profile.

The state path comes from `--state` or `spec.policy.stateFile`. Current apply
state paths are current-directory filenames only; absolute, nested, and empty
paths are rejected. `plan` does not touch state.

Use `--reset-state` when you intentionally want to ignore compatible saved
state and retry from the beginning.

## Build From Source

Requirements:

- JDK 21+
- GraalVM 21 only when building native images locally

Common checks:

```bash
./mill config.test
./mill core.test
./mill cli.test
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
- [Testing guide](docs/testing.md): project-native checks and test patterns.
- [Release guide](docs/release.md): native artifacts, release workflow, and
  smoke checks.
