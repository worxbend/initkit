# Binary Distribution Installer Plan

This plan pivots the repository to a smaller application: a config-driven
installer for the tools currently installed by
`system-bootstrap/scripts/binary-dist.sh`.

The goal is not a general workstation bootstrapper. The first useful product is
a safe, repeatable binary distribution installer that reads one YAML profile,
resolves tool versions, previews the install plan, then installs tools under a
user-controlled apps directory such as `${HOME}/.apps`.

## Product Direction

The app should answer one focused question:

> Given a manifest of binary tools, where should each tool be downloaded from,
> how should it be unpacked, which executable paths should be exposed, and which
> version is installed?

Everything else is secondary. The earlier broad bootstrapper behavior is useful
as implementation material, but the active product surface should become much
smaller.

### Product Name

Use `binstaller` for the app and command name unless a later naming decision
overrides it.

Recommended manifest identity:

```yaml
apiVersion: binstaller.io/v1alpha1
kind: BinaryDistributionProfile
```

## Implementation Progress

- 2026-06-29: T001 completed the Scala 3/Mill module skeleton with
  `app -> cli -> core -> config`, native-image settings, placeholder module
  sources, and initial tests.
- 2026-06-29: T002 completed the picocli CLI shell. `binstaller --help`
  advertises the focused binary installer, global `--config`, `--state`,
  `--reset-state`, and `--verbose` options, and only the `plan`, `apply`, and
  `versions` commands. Those commands currently call placeholder core services
  and fail concisely when `--config` is missing.
- 2026-06-29: T003 completed the config manifest model and validation layer.
  `config.example.yaml` loads into typed `BinaryDistributionProfile` models,
  and invalid manifest identity, plan kinds, archive types, executable modes,
  installer shells, duplicate names, unknown version refs, and sudo symlink
  policy violations report aggregated validation errors with YAML-like paths.

### User Experience Goals

- A user can inspect a concrete install plan before anything changes.
- Updating pinned versions is a small edit in one `spec.versions` block.
- Latest/dynamic URLs are visible as dynamic sources, not hidden shell logic.
- Failed tools report useful per-tool errors without losing the rest of the
  plan context.
- Installs are user-local by default.
- Sudo is limited to explicit system symlinks, and those symlinks are visible in
  dry-run output.
- The converted `config.example.yaml` is the executable replacement for
  `binary-dist.sh`.

## Scope

### In Scope

- Kubernetes-style YAML manifest with `apiVersion`, `kind`, `metadata`, and
  `spec`.
- Central version management for pinned, dynamic, and externally resolved
  versions.
- A typed `binary-tool` plan kind that can model every install in
  `binary-dist.sh`.
- Dry-run plan rendering before filesystem or network changes.
- Apply mode with structured per-tool results.
- User-local install roots such as `${HOME}/.apps`.
- Archive extraction for `zip`, `tar.gz`, and `tar.xz`.
- Direct binary downloads.
- Install-script based binary tools when the upstream project only ships an
  installer script, as with Helm and Kustomize.
- Symlink creation, including optional sudo symlinks for Neovim compatibility.
- Optional checksum fields for future hardening.
- State/resume support at the plan-entry level.
- Native Linux amd64 release through the existing Mill/GraalVM flow.

### Out Of Scope For The Smaller App

- Package-manager installers: `apt`, `dnf`, `pacman`, `zypper`, `flatpak`,
  `snap`, `aur`, `cargo`, and `sdkman`.
- Package source setup.
- Nerd Fonts as a custom installer kind.
- Dotfiles as a custom installer kind. Worxbend `dotbot-go` is in scope only as
  a downloaded binary tool.
- Generic shell script workflows unrelated to binary distribution installs.
- TUI as a required first milestone.
- Broad distro provisioning.
- Multi-OS package naming. The first target is Linux amd64, matching the
  existing script.

## Technical Foundation

Even if the smaller app is implemented from scratch, keep the existing JVM and
Scala foundation. The product scope is smaller; the engineering stack should
remain explicit and production-oriented.

### Language And Runtime

- Scala 3, currently `3.8.2` in `build.mill`.
- JDK 21 or newer.
- Linux as the primary runtime target.
- GraalVM 21+ only when building native images locally.
- Native release target: Linux amd64.

### Build Tooling

- Use the checked-in `./mill` launcher.
- Keep Mill as the only build tool; do not introduce sbt, Maven, Gradle, npm,
  or Make unless a later plan explicitly adds them.
- Keep a multi-module Mill build with explicit, acyclic dependencies.
- Use Mill `ScalaModule`.
- Keep source layout on Mill ScalaModule defaults:
  - production: `<module>/src`
  - tests: `<module>/test/src`
- Keep module responsibilities narrow so each module can compile independently.
- Use programmable `build.mill` only where needed for current repo simplicity,
  native-image settings, reusable test traits, or dependency centralization.

### Required Modules

```text
app -> cli -> core -> config
```

Required module roles:

- `app`: tiny entry point, native-image target, no business logic.
- `cli`: picocli commands, terminal rendering, exit codes, user-facing errors.
- `core`: variable resolution, version resolution, planning, state, execution,
  filesystem/download/command boundaries.
- `config`: YAML loading, raw YAML preservation if needed, typed models,
  validation, validation error aggregation.

Optional module:

- `host`: only if simple `when` matching needs host facts such as OS family or
  architecture.

Not required for the first smaller app:

- `tui`: keep out of the required app dependency graph until the CLI path is
  stable.
- broad package/source/dotfiles/font modules and executors.

### Libraries

Use the existing dependency choices unless an implementation checkpoint
deliberately upgrades them:

- `info.picocli:picocli:4.7.7` for CLI parsing.
- `org.snakeyaml:snakeyaml-engine:3.0.1` for YAML loading.
- `com.softwaremill.sttp.client4::core:4.0.25` for HTTP downloads and version
  resolver requests.
- `com.softwaremill.ox::core:1.0.5` for structured concurrency if bounded
  parallelism or cancellation is added.
- `com.lihaoyi::upickle:4.4.3` for state JSON.
- `com.lihaoyi::os-lib:0.11.8` where filesystem/process helpers are useful.
- `com.lihaoyi::fansi:0.5.1` for colored terminal rendering.
- `com.lihaoyi::utest:0.9.5` for tests.

Before implementation begins, re-check dependency versions if the goal is to
use latest stable releases. If versions are upgraded, record the reason in the
plan or a dedicated change note.

### Formatting And Quality

- Keep `.scalafmt.conf`.
- Use the Scala 3 dialect.
- Run Mill scalafmt checks during validation.
- Prefer typed ADTs and small case classes over stringly typed execution logic.
- Preserve argv boundaries for external commands whenever possible.
- Use shell text only for explicitly modeled installer-script behavior.
- Model expected failures as typed errors instead of throwing through the CLI.
- Keep public contracts documented where behavior is security-sensitive,
  stateful, or externally observable.
- Add focused tests alongside each implementation phase.

### Native Image

- Keep `app extends NativeImageModule`.
- Keep `mainClass` on the app entry point.
- Keep native options conservative, currently:

```text
--no-fallback
-Os
```

- Native-image packaging is a release concern; normal development should work
  with JVM `./mill app.run ...` commands.

### Validation Commands

The implementation should keep these project-native checks working:

```bash
./mill config.test
./mill core.test
./mill cli.test
./mill __.compile
./mill __.test
./mill mill.scalalib.scalafmt/checkFormatAll
git diff --check
```

Use focused module tests while developing, then recursive compile/test before
closing a task.

## Existing Script Inventory

The initial config should faithfully replace these script entries:

| Tool | Version source | Install shape |
| --- | --- | --- |
| `yazi` | pinned `v26.5.6` | GitHub zip, move extracted `yazi` and `ya` into `bin` |
| `zig` | pinned `0.15.2` | `tar.xz`, expose `zig` through a symlink |
| `minikube` | latest URL | direct binary download |
| `xplr` | latest GitHub asset | `tar.gz`, expose `xplr` |
| `kind` | pinned `0.31.0` | direct binary download |
| `zellij` | pinned `0.44.1` | `tar.gz`, expose `zellij` |
| `helm` | upstream installer script | run script with `USE_SUDO=false` and `HELM_INSTALL_DIR` |
| `kubectl` | `stable.txt` resolver | direct binary download using resolved version |
| `kustomize` | upstream installer script | run installer script with install directory arg |
| `neovide` | latest GitHub AppImage | direct binary download |
| `neovim` | latest GitHub archive | `tar.gz`, move tree, create local and sudo symlinks |
| `lazygit` | pinned `0.61.0` | `tar.gz`, expose `lazygit`, symlink `lzg` |
| `jujutsu` | pinned `0.40.0` | `tar.gz`, expose `jj`, symlink aliases |
| `dotbot` | pinned `v0.3.0` | Worxbend `dotbot-go` `tar.gz`, expose `dotbot` |
| `nerd-font-installer` | pinned `v1.0.6` | Worxbend `tar.gz`, expose `nerdfont-install` and alias |

## Manifest Contract

### Top-Level Shape

```yaml
apiVersion: binstaller.io/v1alpha1
kind: BinaryDistributionProfile

metadata:
  name: developer-binaries
  labels: {}
  annotations: {}

spec:
  policy: {}
  vars: {}
  versions: {}
  plan: []
```

### `metadata`

`metadata.name` is required and becomes part of state-file validation. Labels
and annotations are preserved but do not affect execution.

### `spec.policy`

```yaml
policy:
  dryRun: false
  continueOnError: false
  appsDir: "${HOME}/.apps"
  cleanInstall: true
  requireConfirmation: true
  allowSudoSymlinks: false
  stateFile: developer-binaries.state.json
```

Fields:

- `dryRun`: default run mode when the CLI does not override it.
- `continueOnError`: whether to continue to later tools after one tool fails.
- `appsDir`: default root for all `installDir` values.
- `cleanInstall`: default value for tool-level clean installs.
- `requireConfirmation`: require an explicit `--yes` before apply mode.
- `allowSudoSymlinks`: permits `symlinks[].sudo: true`; false by default.
- `stateFile`: optional state file name. The file always lives in the current
  working directory. Directory components are invalid.

CLI flags should override policy values when provided.

### `spec.vars`

Variables are string values available to interpolation. Runtime variables should
include:

- `${HOME}`
- `${USER}`
- `${appsDir}`
- `${profileName}`
- `${cwd}`
- `${toolName}`
- `${version}`

Variables are not shell. The resolver must not execute `$(...)`, backticks, or
other shell syntax.

### `spec.versions`

Versions can be direct strings or typed sources.

```yaml
versions:
  lazygit: 0.61.0

  kubectl:
    resolver:
      type: http-text
      url: https://dl.k8s.io/release/stable.txt

  neovide:
    dynamic:
      type: latest-url
      note: GitHub latest AppImage endpoint
```

Version source types:

- Direct string: pinned version.
- `resolver.type: http-text`: fetch URL and use trimmed response as version.
- `dynamic.type: latest-url`: no version is resolved; the download URL remains
  an explicitly dynamic upstream URL.

Resolved versions should be displayed before apply. Dynamic entries should be
displayed as `dynamic latest-url`.

### `spec.plan`

Every entry has one installer kind. For this smaller app, the only required
kind is `binary-tool`.

```yaml
plan:
  - name: yazi
    kind: binary-tool
    description: Terminal file manager.
    enabled: true
    when:
      os:
        family: linux
      architecture: amd64
    spec: {}
```

Required fields:

- `name`: unique tool name.
- `kind`: must be `binary-tool`.
- `spec`: tool-specific config.

Optional fields:

- `description`: shown in plan output.
- `enabled`: defaults to true.
- `when`: reserved for simple OS/architecture checks.

## Binary Tool Spec

### YAML Shape

```yaml
spec:
  versionRef: lazygit
  installDir: "${appsDir}/lazygit"
  cleanInstall: true

  download:
    url: "https://github.com/jesseduffield/lazygit/releases/download/v${version}/lazygit_${version}_Linux_x86_64.tar.gz"
    filename: lazygit.tar.gz
    checksum:
      algorithm: sha256
      value: ""
    archive:
      type: tar.gz
      extract:
        files:
          - from: lazygit
            to: bin/lazygit

  executables:
    - path: bin/lazygit
      mode: "0755"

  symlinks:
    - path: bin/lzg
      target: bin/lazygit
```

### Typed Model Target

```scala
final case class BinaryToolSpec(
    versionRef: Option[String],
    version: Option[VersionValue],
    installDir: Option[String],
    cleanInstall: Option[Boolean],
    download: BinaryToolDownload,
    executables: Vector[ExecutablePath],
    symlinks: Vector[SymlinkSpec],
    installer: Option[InstallerScriptSpec]
)

final case class BinaryToolDownload(
    url: String,
    filename: Option[String],
    checksum: Option[Checksum],
    archive: Option[ArchiveSpec]
)

final case class ArchiveSpec(
    archiveType: ArchiveType,
    extract: ExtractSpec
)

final case class ExtractSpec(
    files: Vector[ExtractFile],
    directories: Vector[ExtractDirectory],
    stripComponents: Option[Int]
)

final case class InstallerScriptSpec(
    shell: String,
    args: Vector[String],
    env: Vector[EnvironmentEntry],
    cleanup: Boolean
)
```

### Defaults

- `installDir`: `${appsDir}/${toolName}`
- `cleanInstall`: `spec.policy.cleanInstall`, default true
- `download.filename`: derive from URL path when possible
- `executables[].mode`: `0755`
- `installer.cleanup`: true

### Validation Rules

- Manifest `apiVersion` must be `binstaller.io/v1alpha1`.
- Manifest `kind` must be `BinaryDistributionProfile`.
- Plan names must be unique, non-empty, and path-safe.
- Only `binary-tool` is supported in the first app version.
- `versionRef` must reference an existing entry in `spec.versions`.
- A tool can use either `versionRef` or inline `version`, but not both.
- `download.url` is required.
- `archive.type` must be `zip`, `tar.gz`, or `tar.xz`.
- Extract targets must stay inside the tool install directory.
- Executable paths must be relative to `installDir`.
- Local symlink paths must be relative to `installDir` unless `sudo: true`.
- Sudo symlink paths must be absolute.
- Sudo symlinks require `policy.allowSudoSymlinks: true` and apply
  confirmation.
- Modes must be four-digit octal strings such as `"0755"`.
- Installer scripts must declare `shell`; allowed first version values are
  `sh`, `bash`, and `zsh`.

## Example Tool Entries

### Direct Binary

```yaml
- name: kind
  kind: binary-tool
  spec:
    versionRef: kind
    download:
      url: "https://kind.sigs.k8s.io/dl/v${version}/kind-linux-amd64"
    executables:
      - path: bin/kind
```

Implementation note: direct downloads should land at the first executable path
when no archive is configured.

### Archive With File Extraction

```yaml
- name: lazygit
  kind: binary-tool
  spec:
    versionRef: lazygit
    download:
      url: "https://github.com/jesseduffield/lazygit/releases/download/v${version}/lazygit_${version}_Linux_x86_64.tar.gz"
      archive:
        type: tar.gz
        extract:
          files:
            - from: lazygit
              to: bin/lazygit
    executables:
      - path: bin/lazygit
    symlinks:
      - path: bin/lzg
        target: bin/lazygit
```

### Archive With Directory Extraction

```yaml
- name: neovim
  kind: binary-tool
  spec:
    dynamic:
      type: latest-url
    download:
      url: https://github.com/neovim/neovim/releases/latest/download/nvim-linux-x86_64.tar.gz
      archive:
        type: tar.gz
        extract:
          directories:
            - from: nvim-linux-x86_64
              to: .
    executables:
      - path: bin/nvim
    symlinks:
      - path: bin/neovim
        target: bin/nvim
      - path: bin/vim
        target: bin/nvim
      - path: /usr/local/bin/nvim
        target: "${appsDir}/neovim/bin/nvim"
        sudo: true
```

### Installer Script

```yaml
- name: helm
  kind: binary-tool
  spec:
    download:
      url: https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3
      filename: get_helm.sh
    installer:
      shell: sh
      env:
        - name: USE_SUDO
          value: "false"
        - name: HELM_INSTALL_DIR
          value: "${appsDir}/helm/bin"
      args:
        - "${downloadPath}"
      cleanup: true
    executables:
      - path: bin/helm
```

Installer scripts are constrained binary-tool helpers. They should not become a
general shell execution feature.

## Tool-By-Tool Conversion Notes

### `yazi`

- Version: `v26.5.6`.
- URL: GitHub release zip.
- Install directory: `${appsDir}/yazi`.
- Extract directory `yazi-x86_64-unknown-linux-gnu` into `bin`.
- Mark `bin/yazi` and `bin/ya` executable.

### `zig`

- Version: `0.15.2`.
- URL: `https://ziglang.org/download/${version}/zig-x86_64-linux-${version}.tar.xz`.
- Extract archive directory into `bin`.
- Create local symlink `bin/zig` to the extracted `zig` executable.
- This requires either glob resolution after extraction or an explicit
  directory pattern in the extract spec.

### `minikube`

- Dynamic latest URL:
  `https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64`.
- Direct download to `bin/minikube`.
- Mark executable.

### `xplr`

- Dynamic latest URL:
  `https://github.com/sayanarijit/xplr/releases/latest/download/xplr-linux.tar.gz`.
- Extract `xplr` into `bin/xplr`.
- Mark executable.

### `kind`

- Version: `0.31.0`.
- URL: `https://kind.sigs.k8s.io/dl/v${version}/kind-linux-amd64`.
- Direct download to `bin/kind`.
- Mark executable.

### `zellij`

- Version: `0.44.1`.
- URL:
  `https://github.com/zellij-org/zellij/releases/download/v${version}/zellij-x86_64-unknown-linux-musl.tar.gz`.
- Extract `zellij` into `bin/zellij`.
- Mark executable.

### `helm`

- Dynamic installer script URL:
  `https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3`.
- Download script to the tool temp path.
- Run with `USE_SUDO=false` and `HELM_INSTALL_DIR=${appsDir}/helm/bin`.
- Verify `bin/helm` exists after installer success.

### `kubectl`

- Version resolver: `https://dl.k8s.io/release/stable.txt`.
- URL:
  `https://dl.k8s.io/release/${version}/bin/linux/amd64/kubectl`.
- Direct download to `bin/kubectl`.
- Mark executable.

### `kustomize`

- Dynamic installer script URL:
  `https://raw.githubusercontent.com/kubernetes-sigs/kustomize/master/hack/install_kustomize.sh`.
- Run installer script with `${appsDir}/kustomize/bin` as the install directory
  argument.
- Verify `bin/kustomize` exists after installer success.

### `neovide`

- Dynamic latest URL:
  `https://github.com/neovide/neovide/releases/latest/download/neovide.AppImage`.
- Direct download to `bin/neovide`.
- Mark executable.

### `neovim`

- Dynamic latest URL:
  `https://github.com/neovim/neovim/releases/latest/download/nvim-linux-x86_64.tar.gz`.
- Extract `nvim-linux-x86_64` directory into `${appsDir}/neovim`.
- Mark `bin/nvim` executable.
- Create local symlinks `bin/neovim` and `bin/vim`.
- Create optional sudo symlinks:
  - `/usr/local/bin/neovim`
  - `/usr/local/bin/vim`
  - `/usr/local/bin/nvim`
  - `/usr/bin/nvim`
  - `/usr/bin/neovim`

### `lazygit`

- Version: `0.61.0`.
- URL:
  `https://github.com/jesseduffield/lazygit/releases/download/v${version}/lazygit_${version}_Linux_x86_64.tar.gz`.
- Extract `lazygit` into `bin/lazygit`.
- Create local symlink `bin/lzg`.

### `jujutsu`

- Version: `0.40.0`.
- URL:
  `https://github.com/jj-vcs/jj/releases/download/v${version}/jj-v${version}-x86_64-unknown-linux-musl.tar.gz`.
- Extract `jj` into `bin/jj`.
- Create local symlinks `bin/jj-scm` and `bin/jujutsu`.

### `dotbot`

- Source: `worxbend/dotbot-go`.
- Version: `v0.3.0`.
- URL:
  `https://github.com/worxbend/dotbot-go/releases/download/${version}/dotbot-linux-amd64.tar.gz`.
- Extract `dotbot` into `bin/dotbot`.
- Mark executable.
- Use the published Linux amd64 release asset checksum when available.

### `nerd-font-installer`

- Source: `worxbend/nerd-font-installer`.
- Version: `v1.0.6`.
- URL:
  `https://github.com/worxbend/nerd-font-installer/releases/download/${version}/nerdfont-install_${version}_linux_amd64.tar.gz`.
- Extract `nerdfont-install_${version}_linux_amd64/nerdfont-install` into
  `bin/nerdfont-install`.
- Create local symlink `bin/nerd-font-installer`.
- Mark executable.
- Use the published Linux amd64 release asset checksum when available.

## Version Management

### Resolution Pipeline

1. Load and validate manifest.
2. Build runtime variable map from environment and policy.
3. Resolve `spec.versions`.
4. For each tool, attach a `ResolvedVersion`:
   - `Pinned(value)`
   - `Resolved(value, sourceUrl)`
   - `Dynamic(reason)`
5. Interpolate tool specs using the resolved version value where available.
6. Render plan or execute apply.

### Lock File Future

The lock command is not first-milestone required, but the data model should not
block it.

Potential lock shape:

```json
{
  "profileName": "developer-binaries",
  "manifestFingerprint": "...",
  "lockedAt": "2026-06-29T00:00:00Z",
  "tools": [
    {
      "name": "kubectl",
      "version": "v1.33.2",
      "url": "https://dl.k8s.io/release/v1.33.2/bin/linux/amd64/kubectl",
      "checksum": null
    }
  ]
}
```

Later behavior:

- `lock`: writes resolved tool metadata.
- `apply --locked`: refuses dynamic version resolution and requires lock data.
- `versions`: prints pinned, resolved, and dynamic versions.

## CLI Contract

### Commands

```bash
binstaller plan --config config.example.yaml
binstaller apply --config config.example.yaml --dry-run
binstaller apply --config config.example.yaml --yes
binstaller apply --config config.example.yaml --only yazi --yes
binstaller versions --config config.example.yaml
```

Source-development equivalents:

```bash
./mill app.run plan --config config.example.yaml
./mill app.run apply --config config.example.yaml --dry-run
./mill app.run apply --config config.example.yaml --yes
./mill app.run versions --config config.example.yaml
```

Future:

```bash
binstaller lock --config config.example.yaml
binstaller apply --config config.example.yaml --locked --yes
```

### Global Options

- `--config <path>`: required for `plan`, `apply`, `versions`, and later
  `lock`.
- `--state <name>`: overrides `spec.policy.stateFile` with a file name in the
  current working directory. Paths with directory components are rejected.
- `--reset-state`: ignore existing state and start fresh.
- `--verbose`: include lower-level download and extraction details.

### `plan`

Responsibilities:

- Load, validate, resolve, and interpolate config.
- Print ordered tools.
- Print destination directories.
- Print whether each version is pinned, resolved, or dynamic.
- Print download URL and archive/install strategy.
- Print local and sudo symlinks.
- Do not write install directories, temp files, or state.

### `apply`

Responsibilities:

- Default to dry-run when either config policy or CLI says dry-run.
- Require `--yes` for non-dry-run apply when `requireConfirmation` is true.
- Honor `--only` and `--skip`.
- Load state and skip completed matching tools.
- Execute tools in manifest order.
- Persist completed/failed state after each terminal tool result.
- Exit nonzero on validation failure, resolution failure, or failed install
  when `continueOnError` is false.

### `versions`

Responsibilities:

- Show all entries in `spec.versions`.
- Resolve external resolvers unless `--no-resolve` is later added.
- Show which tools reference each version.

## Execution Model

### Apply Steps For One Tool

1. Resolve final install directory.
2. Create a temporary work directory outside the final install directory.
3. Download to a temporary file.
4. Verify checksum when configured.
5. Prepare a staged install directory.
6. Extract archive, copy direct binary, or run constrained installer.
7. Apply executable modes.
8. Verify expected executables exist.
9. Replace existing install directory if `cleanInstall` is true.
10. Create local symlinks.
11. Create sudo symlinks through `CommandExecutor`.
12. Record success in state.

Important rule: avoid deleting the previous working install until the staged
install is ready. This improves on the original script's direct `rm -rf`
behavior while preserving the user-visible clean-install result.

### Extraction Behavior

- Native JVM extraction should cover `zip` and `tar.gz` where existing code
  already supports it.
- `tar.xz` can use the system `tar` command boundary for the first milestone.
- Extraction must reject paths that escape the staging directory.
- Directory extraction should support moving an extracted root directory into
  the install root.
- File extraction should support mapping archive members to final relative
  paths.

### Symlink Behavior

- Relative symlink paths are resolved under `installDir`.
- Relative symlink targets are resolved relative to the symlink parent or
  `installDir`; pick one rule and document it before implementation.
- Absolute symlink paths require `sudo: true`.
- Sudo symlinks use structured argv:
  `sudo ln -sfn <target> <path>`.
- Dry-run prints every symlink command.

### Installer Script Behavior

Installer scripts are allowed only inside `binary-tool` and only with explicit
configuration.

Allowed first-milestone behavior:

- Download script to temp file.
- Set mode `0700`.
- Run configured shell with explicit args.
- Pass explicit env vars.
- Verify expected executable paths afterward.
- Delete script when `cleanup: true`.

Disallowed behavior:

- Arbitrary inline shell snippets as standalone plan entries.
- Unbounded environment passthrough.
- Silent sudo usage from the app. If the upstream script runs sudo despite env
  config, the failed command should be reported.

## State And Resume

### State File Shape

```json
{
  "profileName": "developer-binaries",
  "manifestFingerprint": "...",
  "startedAt": "2026-06-29T00:00:00Z",
  "updatedAt": "2026-06-29T00:00:00Z",
  "tools": {
    "yazi": {
      "status": "completed",
      "version": "v26.5.6",
      "installDir": "/home/user/.apps/yazi",
      "completedAt": "2026-06-29T00:00:00Z"
    }
  }
}
```

Statuses:

- `running`
- `completed`
- `failed`
- `skipped`

State rules:

- Reject state if `profileName` differs.
- Reject state if manifest fingerprint differs unless `--reset-state`.
- State is always read from and written to the current working directory.
- `spec.policy.stateFile` and `--state` accept only a file name, not an
  absolute path or a relative path containing directories.
- Dynamic/latest tools should store the final URL used.
- Resuming skips completed tools by name.
- Failed tools are attempted again unless the user skips them.

## Module Plan

The authoritative build and dependency guidance is in the Technical Foundation
section. During the pivot, apply that guidance this way:

- Keep `app`, `cli`, `core`, and `config` as the required active modules.
- Keep `host` only if the first implementation supports `when` conditions.
- Remove `tui` from the app dependency graph until the CLI path is stable.
- Delete, quarantine, or leave unused the broad package/source/dotfiles/font
  executors, but do not keep them on the active user-facing path.

## Implementation Tasks

### P001 - Repository Pivot

Deliverables:

- README and docs describe `binstaller`, not broad workstation bootstrapping.
- CLI root command name becomes `binstaller`.
- Build graph no longer requires TUI for app execution.
- Old broad examples are removed or moved under a legacy note.

Implementation notes:

- Prefer deleting unused broad code only when tests are ready to protect the
  new path. A temporary quarantine package is acceptable for a short pivot.
- Keep native-image settings.
- Keep picocli and sttp dependencies.
- Keep SnakeYAML for manifest parsing.

Acceptance checks:

- `./mill app.run --help` shows binary installer language.
- `./mill __.compile` passes.
- No command help mentions package managers, dotfiles, Nerd Fonts, or TUI as
  first-class features.

### P002 - Manifest Model And Validation

Deliverables:

- New manifest model for `BinaryDistributionProfile`.
- Typed `Policy`, `VersionSource`, `BinaryToolSpec`, `ArchiveSpec`,
  `InstallerScriptSpec`, and symlink/executable models.
- Aggregated validation errors with YAML-ish paths.
- Tests for valid minimal manifest and invalid manifest aggregation.

Acceptance checks:

- `config.example.yaml` loads and validates.
- Duplicate tool names report all duplicate names.
- Unknown version refs report the tool name and field path.
- Unsafe sudo symlinks fail unless policy allows them.

### P003 - Variable And Version Resolution

Deliverables:

- Resolver for env/runtime vars, policy vars, and tool-local vars.
- Resolver for direct strings, `http-text`, and `latest-url`.
- Resolved plan model with concrete URLs and destinations.
- Tests with fake HTTP client.

Acceptance checks:

- Kubectl stable version lookup resolves through fake HTTP.
- Pinned versions interpolate correctly.
- Dynamic latest URL entries render without pretending to know a version.
- Unresolved variables produce validation-style errors.

### P004 - Plan Rendering And Selection

Deliverables:

- `plan` command.
- Shared selection for `--only` and `--skip`.
- Dry-run renderer used by both `plan` and `apply --dry-run`.
- Clear rendering of sudo symlink risk.

Acceptance checks:

- `plan` prints all tools from `config.example.yaml` in order.
- `--only yazi` prints only `yazi`.
- `--skip neovim` omits `neovim` and keeps all other tools ordered.
- Dry-run creates no install directories or state files.

### P005 - Binary Tool Executor

Deliverables:

- Download boundary with fake test implementation.
- Filesystem boundary for temp/staging/install operations.
- Archive extraction for `zip`, `tar.gz`, and `tar.xz`.
- Direct binary install.
- Local symlinks.
- Sudo symlink command path.
- Installer script execution path.

Acceptance checks:

- Fake executor can install direct binary, zip archive, tar.gz archive, tar.xz
  archive through command boundary, and installer-script tool.
- Failed checksum prevents install replacement.
- Failed extraction preserves previous install directory.
- Sudo symlink dry-run and apply command specs are tested.

### P006 - Convert `config.example.yaml`

Deliverables:

- Example config fully replaces `binary-dist.sh`.
- Every tool from the script has one plan entry.
- All pinned versions live in `spec.versions`.
- Dynamic latest URLs are marked explicitly.
- Kubectl uses `http-text` resolver.

Acceptance checks:

- Regression test asserts expected tool names:
  `yazi`, `zig`, `minikube`, `xplr`, `kind`, `zellij`, `helm`, `kubectl`,
  `kustomize`, `neovide`, `neovim`, `lazygit`, `jujutsu`, `dotbot`,
  `nerd-font-installer`.
- Regression test asserts expected install dirs under `${appsDir}`.
- Regression test asserts Neovim sudo symlinks are present and gated by policy.

### P007 - State And Resume

Deliverables:

- State load/write model.
- Manifest fingerprinting.
- Completed-tool skip logic.
- CWD-local `--state` and `--reset-state` behavior.

Acceptance checks:

- Completed tools are skipped on resume.
- Failed tools are retried.
- Stale state fails with a clear message.
- State files are created only in the current working directory.
- Absolute state paths and nested relative state paths are rejected.
- `--reset-state` starts fresh.

### P008 - CLI Polish And Reporting

Deliverables:

- User-facing error messages without stack traces for expected failures.
- Summary table for plan/apply.
- Verbose mode for detailed download/extraction operations.
- Exit codes documented in README.

Acceptance checks:

- Missing config path exits nonzero with a concise message.
- Invalid config prints all validation errors.
- Failed apply prints tool name, action, and reason.
- `versions` prints pinned/resolved/dynamic versions.

### P009 - Native Release

Deliverables:

- Native image target still builds from `app`.
- Release workflow smoke-tests command help and dry-run.
- README install snippet uses `binstaller`.

Acceptance checks:

- `GRAALVM_HOME=/path/to/graalvm ./mill app.nativeImage` works where GraalVM is
  available.
- Release workflow uploads Linux amd64 binary.
- Native binary can run `--help`, `plan`, and `apply --dry-run`.

### P010 - Post-Implementation Review And Hardening

This phase starts only after the first complete implementation is working:
manifest loading, planning, dry-run, apply, config conversion, state/resume, CLI
reporting, tests, and native release wiring.

Deliverables:

- Review the finished codebase end to end, including config parsing, version
  resolution, download handling, archive extraction, installer scripts, symlink
  creation, state writes, CLI reporting, tests, and release workflow.
- Analyze security, reliability, maintainability, and user-experience risks.
- Prepare a written improvement report before beginning broad follow-up work.
- Convert accepted suggestions into a prioritized hardening backlog.

Required review questions:

- Can a malicious or malformed archive write outside the staging directory?
- Are downloads written atomically and verified before replacing an existing
  install?
- Are checksum fields supported consistently, and where should checksums become
  required?
- Can installer scripts unexpectedly use sudo or inherit unsafe environment
  values?
- Are sudo symlinks impossible unless clearly configured and confirmed?
- Is state written atomically, and does stale state fail safely?
- Are error messages actionable without exposing sensitive environment values?
- Are dry-run and apply paths close enough that dry-run is a trustworthy preview?
- Are tests covering real failure modes, or only happy-path conversions?
- Is the manifest schema still understandable after representing all tools?
- Which old broad-bootstrap modules should be deleted, retained, or split out?

Acceptance checks:

- A hardening report exists in docs or the plan with concrete findings and
  recommendations.
- Each recommendation is classified as `must fix`, `should fix`, or `later`.
- Must-fix findings are implemented or explicitly deferred with rationale.
- The final implementation still passes the full verification suite after the
  hardening changes.

## Agent Loop Tasks

The resumable implementation queue is stored in `.agent-loop/tasks.json`. All
tasks start as `pending`; validation checkpoints are inserted before riskier
phases continue.

| Task | Type | Complexity | Title |
| --- | --- | --- | --- |
| T001 | chore | moderate | Scaffold Mill modules |
| T002 | improvement | moderate | Add binstaller CLI shell |
| T003 | feature | complex | Model and validate manifests |
| T004 | validation | simple | Checkpoint config and CLI |
| T005 | feature | complex | Resolve variables and versions |
| T006 | feature | moderate | Render plans and selection |
| T007 | validation | simple | Checkpoint resolution and planning |
| T008 | feature | complex | Execute direct binary installs |
| T009 | feature | complex | Extract archives safely |
| T010 | feature | complex | Run installers and symlinks |
| T011 | validation | simple | Checkpoint executor safety |
| T012 | improvement | moderate | Lock config example coverage |
| T013 | feature | complex | Persist state and resume |
| T014 | improvement | moderate | Polish CLI reporting |
| T015 | validation | simple | Checkpoint apply workflow |
| T016 | improvement | moderate | Update docs and release workflow |
| T017 | improvement | complex | Review and harden implementation |
| T018 | validation | simple | Run final validation |

Current progress:

- 2026-06-29: T001 is complete. The repository now has a Scala 3 Mill
  skeleton with `app -> cli -> core -> config`, centralized dependency
  coordinates, uTest module test layouts, and an `app` NativeImageModule
  configured with `--no-fallback` and `-Os`.
- Native-image packaging was configured but not built during T001; normal JVM
  compile/test validation passed through the checked-in `./mill` launcher.

## Verification Strategy

Run these during implementation:

- `./mill config.test`
- `./mill core.test`
- `./mill cli.test`
- `./mill __.compile`
- `./mill __.test`
- `./mill mill.scalalib.scalafmt/checkFormatAll`
- `git diff --check`

Focused coverage:

- YAML decoding and validation.
- Version resolution and interpolation.
- Plan selection.
- Dry-run rendering.
- Executor behavior with fake HTTP/files/commands.
- State and resume.
- `config.example.yaml` regression.

Manual smoke after first working executor:

```bash
./mill app.run plan --config config.example.yaml
./mill app.run apply --config config.example.yaml --dry-run
./mill app.run apply --config config.example.yaml --only yazi --yes
```

## First Milestone Definition Of Done

- `config.example.yaml` fully represents the linked `binary-dist.sh`.
- `./mill app.run plan --config config.example.yaml` prints all planned tools
  with concrete destinations.
- `./mill app.run apply --config config.example.yaml --dry-run` performs no
  filesystem-changing install actions.
- `./mill app.run apply --config config.example.yaml --only yazi --yes` can
  install a single archive-backed tool through the same engine used for the full
  plan.
- Tests pass for config decoding, version resolution, plan selection, and the
  first binary-tool executor path.
- README points users to the new binary-distribution workflow.
- The post-implementation review and hardening pass is complete, with
  improvement suggestions documented and must-fix items handled or explicitly
  deferred.

## Later Enhancements

- Lock file with resolved versions, URLs, sizes, and checksums.
- Checksum auto-discovery for upstreams that publish checksum files.
- ARM64 platform support.
- `binstaller update --write-config` for bumping pinned versions.
- Optional TUI after the CLI flow is stable.
- Parallel downloads with bounded concurrency.
- Per-tool uninstall command.
- Path export helper that prints shell snippets for adding tool bins to `PATH`.
