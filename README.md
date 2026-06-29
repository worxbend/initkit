# binstaller

[![Native Release](https://github.com/worxbend/initkit/actions/workflows/native-release.yml/badge.svg)](https://github.com/worxbend/initkit/actions/workflows/native-release.yml)
![Scala 3](https://img.shields.io/badge/Scala-3.8.2-dc322f?logo=scala&logoColor=white)
![Mill](https://img.shields.io/badge/Mill-1.1.7-5b5bd6)
![GraalVM](https://img.shields.io/badge/GraalVM-native%20ready-f2a900?logo=graalvm&logoColor=111111)
![Linux](https://img.shields.io/badge/Linux-binary%20installer-2ea44f?logo=linux&logoColor=white)

`binstaller` is a config-driven installer for Linux amd64 binary tool
distributions. It reads one YAML profile, resolves versions, previews the plan,
and installs selected tools under a user-controlled apps directory such as
`${HOME}/.apps`.

The first milestone is intentionally small: binary downloads, archive unpacking,
installer scripts that are explicit in the manifest, local executable exposure,
optional sudo symlinks, dry-run output, apply state, and version reporting.

## Manifest Shape

Profiles use the `binstaller.io/v1alpha1` manifest identity:

```yaml
apiVersion: binstaller.io/v1alpha1
kind: BinaryDistributionProfile

metadata:
  name: developer-binaries

spec:
  policy:
    appsDir: "${HOME}/.apps"
    requireConfirmation: true
    allowSudoSymlinks: true
    stateFile: developer-binaries.state.json

  versions:
    kubectl:
      resolver:
        type: http-text
        url: https://dl.k8s.io/release/stable.txt

  plan:
    - name: kubectl
      kind: binary-tool
      spec:
        versionRef: kubectl
        installDir: "${appsDir}/kubectl"
        download:
          url: https://dl.k8s.io/release/${version}/bin/linux/amd64/kubectl
          filename: kubectl
        executables:
          - path: bin/kubectl
```

The checked-in [config.example.yaml](config.example.yaml) is the primary example
profile and exercises the supported binary distribution cases.

## Install

Native Linux amd64 builds are published from `v*` tags by GitHub Actions.

```bash
curl -L -o binstaller \
  https://github.com/worxbend/initkit/releases/latest/download/binstaller-linux-amd64
chmod +x binstaller
sudo mv binstaller /usr/local/bin/binstaller
```

The release also publishes `binstaller-linux-amd64.tar.gz` and `SHA256SUMS`.

## CLI Usage

Preview the resolved install plan:

```bash
binstaller plan --config config.example.yaml
```

Render the apply plan without changing files:

```bash
binstaller apply --config config.example.yaml --dry-run
```

Apply the profile. `--yes` is required when the profile requires confirmation:

```bash
binstaller apply --config config.example.yaml --yes
```

Apply only selected tools:

```bash
binstaller apply --config config.example.yaml --only yazi --yes
```

Inspect pinned, resolved, and dynamic version sources:

```bash
binstaller versions --config config.example.yaml
```

Source-development equivalents use the checked-in Mill launcher:

```bash
./mill app.run plan --config config.example.yaml
./mill app.run apply --config config.example.yaml --dry-run
./mill app.run apply --config config.example.yaml --yes
./mill app.run apply --config config.example.yaml --only yazi --yes
./mill app.run versions --config config.example.yaml
```

Useful global options:

- `--config FILE`: required for `plan`, `apply`, and `versions`.
- `--state FILE`: override the profile state file with a current-directory
  filename.
- `--reset-state`: ignore existing apply state and start fresh.
- `--verbose`: print additional apply diagnostics.

## Exit Codes

- `0`: command completed successfully, including help and dry-run commands.
- `1`: the manifest could not be loaded or resolved, selection was invalid,
  confirmation was missing, apply failed, or state persistence failed.
- `2`: command-line usage error, including a missing required `--config`.

## Native Binary Workflow

From a release download:

```bash
binstaller --help
binstaller plan --config config.example.yaml
binstaller apply --config config.example.yaml --dry-run
```

Build a native image locally when GraalVM is installed:

```bash
GRAALVM_HOME=/path/to/graalvm ./mill app.nativeImage
```

The release workflow builds the Linux amd64 native image, packages it as
`binstaller-linux-amd64`, creates `binstaller-linux-amd64.tar.gz`, and smoke
tests native `--help`, `plan`, and `apply --dry-run`.

## Build From Source

Requirements:

- JDK 21+
- GraalVM 21 only when building native images locally

Common development commands:

```bash
./mill __.compile
./mill __.test
./mill app.run --help
./mill app.run plan --config config.example.yaml
./mill app.run apply --config config.example.yaml --dry-run
./mill app.run versions --config config.example.yaml
```

Formatting:

```bash
./mill mill.scalalib.scalafmt/checkFormatAll
./mill mill.scalalib.scalafmt/reformatAll
```

## Project Status

`binstaller` is a young, focused binary installer. The manifest API is currently
`binstaller.io/v1alpha1`, so the shape is explicit but still allowed to evolve.

Use `plan` first, run `apply --dry-run`, then use `apply --yes` when the preview
matches what you expect.
