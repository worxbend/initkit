# Developer Guide

This guide is for people who want to hack on Initkit itself.

## Requirements

- JDK 21+
- Linux or a Linux-like environment for realistic package-manager flows
- GraalVM 21 if you want to build native images locally
- No global Mill install required; use `./mill`

## Repository Layout

```text
app/       tiny executable entry point
cli/       picocli commands, colorful plain CLI rendering, debug logs
config/    YAML loading, typed manifest models, validation
core/      execution engine, state, installers, command runner, downloads
host/      host OS and command detection
tui/       TamboUI view model, app, and execution bridge
docs/      user docs, examples, architecture notes
```

## Daily Commands

Compile everything:

```bash
./mill __.compile
```

Run all tests:

```bash
./mill __.test
```

Run focused tests:

```bash
./mill core.test.testOnly initkit.core.PackageManagerInstallersTests
./mill cli.test.testOnly initkit.cli.InitkitCliTests
./mill tui.test.testOnly initkit.tui.TuiViewModelTests
```

Run the app from source:

```bash
./mill app.run --help
./mill app.run apply --config config.example.yaml --dry-run
./mill app.run tui --config config.example.yaml
```

Format:

```bash
./mill mill.scalalib.scalafmt/checkFormatAll
./mill mill.scalalib.scalafmt/reformatAll
```

## Native Image

The `app` module extends Mill's `NativeImageModule`.

Local build:

```bash
GRAALVM_HOME=/path/to/graalvm ./mill app.nativeImage
```

If you see:

```text
JvmWorkerModule.javaHome/GRAALVM_HOME not defined
```

you are running on a normal JDK. Install GraalVM or set `GRAALVM_HOME`.

The GitHub Actions workflow in `.github/workflows/native-release.yml` handles this automatically for releases.

## Release Flow

Create and push a `v*` tag:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The workflow:

1. checks out the repo
2. installs GraalVM 21 with `native-image`
3. runs `./mill __.test`
4. builds `./mill app.nativeImage`
5. packages `initkit-linux-amd64`
6. uploads the binary, tarball, and checksums to the GitHub Release

You can also run the workflow manually from GitHub Actions with a tag input.

## Coding Style

Keep code boring and obvious.

- Prefer small modules with clear boundaries.
- Model config as typed data as early as possible.
- Return typed errors for expected failures.
- Keep command execution behind `CommandExecutor`.
- Keep host facts injectable in tests.
- Do not call real package managers, `sudo`, or live shell installers in tests.
- Preserve argv boundaries. Use shell mode only when the manifest explicitly asks for shell text.

## Where To Put Things

### Config shape or validation

Use `config/src/initkit/config`.

Files to check:

- `Manifest.scala`
- `ManifestSpec.scala`
- `PlanEntry.scala`
- `PackageSpec.scala`
- `InstallerSpec.scala`
- `ManifestLoader.scala`
- `ManifestValidator.scala`

### Execution behavior

Use `core/src/initkit/core`.

Files to check:

- `ExecutionEngine.scala`
- `ExecutionContracts.scala`
- `ExecutionWithSourceSetup.scala`
- `ExecutionState.scala`
- `PlanSelector.scala`

### New installer kind

Add or update:

1. config model
2. decoder
3. validator rules
4. `PlanOperation`
5. `PlanOperationInstaller`
6. executor implementation
7. CLI/TUI display if needed
8. tests
9. docs

### CLI output

Use `cli/src/initkit/cli`.

Plain CLI output uses fansi through a renderer. Respect:

- `--color auto`
- `--color always`
- `--color never`
- `--no-color`
- `NO_COLOR`

### TUI

Use `tui/src/initkit/tui`.

The TUI should not duplicate execution logic. It builds a `TuiViewModel`, lets the user select rows, and calls the same execution engine as the CLI.

## Testing Rules

Good tests here use fakes.

Use:

- fake command executors
- fake sudo strategies
- fake host facts
- temp directories
- small YAML fixtures

Avoid:

- real `sudo`
- real package-manager installs
- live network downloads unless a test is explicitly an integration test
- tests that depend on your workstation state

## Documentation Rules

When behavior changes, update:

- `README.md` for user-facing workflows
- `docs/config-structure.md` for manifest shape
- `docs/examples/*.yaml` when examples need the new field
- `docs/architecture.md` when module boundaries change

Docs are part of the product. Keep them warm, direct, and copy-pasteable.
