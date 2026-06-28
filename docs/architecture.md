# Architecture Overview

Initkit is split around one idea:

> Load a manifest, resolve it, select matching plan entries, preview or execute them, and report clearly.

The CLI and TUI are two faces over the same core engine.

## High-Level Flow

```text
YAML profile
  |
  v
ManifestLoader
  |
  v
ManifestValidator
  |
  v
ManifestVariableResolver + HostDetector
  |
  v
PlanSelector
  |
  v
ExecutionWithSourceSetup
  |
  +--> SourceSetupExecutor
  |
  v
ExecutionEngine
  |
  v
PlanOperationInstaller implementations
  |
  v
CommandExecutor / file / HTTP boundaries
```

## Modules

### `app`

Tiny entry point.

It should stay boring:

- call the CLI entry point
- expose `mainClass`
- support native-image packaging

### `cli`

Plain terminal interface.

Responsibilities:

- picocli command parsing
- shared flags such as `--config`, `--state`, `--reset-state`
- `apply`, `tui`, and `info` commands
- color rendering through fansi
- debug logs
- reporting dry-run/apply results

The CLI should translate user input into core requests. It should not implement installer behavior.

### `tui`

Interactive TamboUI interface.

Responsibilities:

- create view models from manifest, host facts, state, and selection
- render plan rows
- handle keyboard actions
- run selected entries through the shared execution engine
- show logs and resume instructions

The TUI should not become a separate runner.

### `config`

Manifest parsing and validation.

Responsibilities:

- YAML loading
- raw YAML preservation for kind-specific specs
- typed models
- package spec decoding
- installer spec decoding
- validation errors with path and context

### `host`

Host detection.

Responsibilities:

- OS family
- Linux distribution from `/etc/os-release`
- version/codename
- architecture normalization
- command existence on `PATH`

Tests inject fake host facts so behavior does not depend on the developer's machine.

### `core`

Execution brain.

Responsibilities:

- variable resolution
- plan selection
- source setup
- execution state
- command contracts and process runner
- installer executors
- binary downloads
- shell scripts
- package managers
- Nerd Fonts
- dotfiles
- generic commands

## Important Boundaries

### Manifest vs State

The manifest says what should happen.

The state file says what already happened.

They are separate on purpose. Never write execution state into the profile YAML.

### CLI/TUI vs Engine

CLI and TUI are presentation layers.

The engine owns:

- selected entry order
- dry-run behavior
- failed/completed/interrupted state transitions
- `continueOnError`
- resume metadata

### Command Text vs Argv

Initkit preserves argv boundaries wherever possible.

Direct package installs become structured argv commands:

```text
dnf install -y jq
```

Shell mode is used only when the manifest explicitly models shell text, such as:

```yaml
kind: commands
spec:
  items:
    - run: "mkdir -p ${binDir}"
```

## Source Setup

`spec.sources` is handled before package entries, but only when selected entries need package-manager work.

Examples:

- apt repository files and GPG keys
- dnf repo files
- zypper addrepo commands
- flatpak remotes

The generated source setup is visible in dry-run mode.

## Package Manager Execution

Package managers are intentionally item-scoped.

This list:

```yaml
install:
  - git
  - wrong-name
  - jq
```

does not become:

```bash
dnf install -y git wrong-name jq
```

It becomes separate operations:

```bash
dnf install -y git
dnf install -y wrong-name
dnf install -y jq
```

That way `jq` is still attempted if `wrong-name` fails. The plan entry reports partial failure afterward.

## Error Model

Expected failures should become typed outcomes:

- validation errors
- manifest load errors
- command failures
- download failures
- checksum failures
- state load/write failures

User-facing messages should include:

- plan entry name
- item name when available
- command or file path when useful
- redacted sensitive values

## State And Resume

State stores:

- manifest name
- manifest fingerprint
- entry statuses
- failure/interruption details
- resume point

If the manifest changes, the old state is rejected unless the user passes `--reset-state`.

## Native Release Architecture

Mill builds a native image from the `app` module:

```bash
GRAALVM_HOME=/path/to/graalvm ./mill app.nativeImage
```

GitHub Actions builds on `ubuntu-24.04` for Linux amd64, smoke-tests `--help`, archives the binary, and uploads release assets.

## Design Principles

- Make the safe thing obvious.
- Prefer dry-run visibility over hidden magic.
- Keep distro differences in YAML, not scattered through code.
- Keep command execution structured.
- Make failure messages useful enough to fix the profile.
- Let the TUI be nice, but never let it fork the logic.
