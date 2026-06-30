# Architecture

Date: 2026-06-30

`binstaller` is a Scala 3/Mill application for one manifest shape:
`binstaller.io/v1alpha1` / `BinaryDistributionProfile`. The runtime graph is
acyclic:

```text
app -> cli -> core
core -> config
```

`core` does not import CLI code. Business rules live below the command layer,
and command output consumes resolved plans or renderer-agnostic events.

## Module Responsibilities

- `config`: reads YAML with SnakeYAML Engine, decodes typed manifest models,
  validates supported enum values, rejects unsupported installer scripts, checks
  duplicate tool names, checks unknown `versionRef` values, validates SHA-256
  value shape, and gates sudo symlink declarations through
  `policy.allowSudoSymlinks`.
- `core`: resolves variables and versions, validates HTTPS URLs, applies
  `--only`/`--skip` selection, creates resolved plans, downloads bounded binary
  bodies, verifies checksums, extracts archives, stages and replaces installs,
  creates symlinks, persists apply state, emits typed installer events, and
  stays independent from command parsing.
- `cli`: owns Picocli command parsing, exit codes, script-friendly default
  output, colored apply progress, global flags, and routing for `plan`,
  `apply`, `versions`, and `lock`.
- `app`: owns process entry and exit-code propagation only.
- `build/release`: `build.mill` defines modules and native-image settings;
  `.github/workflows/native-release.yml` builds, smokes, packages, checksums,
  and publishes Linux amd64 artifacts.

## Data Flow

1. CLI parses command flags into `InstallerOptions`.
2. `ConfigModule.load` reads YAML into `BinaryDistributionProfile`.
3. `PlanResolver` resolves runtime variables, manifest vars, policy paths,
   version sources, download URLs, archive mappings, executable paths, and
   symlinks into `ResolvedPlan`.
4. `ToolSelection` applies `--only` first and `--skip` second while preserving
   manifest order.
5. `plan` renders the selected `ResolvedPlan` directly as script-friendly text.
6. `apply` checks confirmation and state compatibility, executes
   each selected tool, writes apply state after terminal tool results, and emits
   `InstallerEvent` values.
7. CLI apply progress consumes the event contract to keep a compact progress
   line and summary without changing core execution behavior.

## Command Surface

The supported executable surface is intentionally small:

- `plan`: resolve and render the selected plan without writing.
- `apply --yes`: perform a confirmed apply when policy requires confirmation.
- `versions`: print a package/version summary table and show newer GitHub
  release versions when available.
- `lock`: resolve and write reproducible lock metadata.

## Event Contract

Core emits the following renderer-agnostic events:

- `ResolvingStarted(configPath, elapsedTime)`
- `PlanReady(toolNames, stateFilePath, elapsedTime)`
- `ToolStarted(toolName, phase, elapsedTime)`
- `ToolPhaseChanged(toolName, phase, elapsedTime)`
- `DownloadProgress(toolName, url, downloadedBytes, totalBytes, status, elapsedTime)`
- `LogLine(toolName, line, elapsedTime)`
- `ToolResult(toolName, status, installDir, failureSummary, elapsedTime)`
- `ToolSkipped(toolName, reason, stateFilePath, elapsedTime)`
- `Summary(status, installed, failed, skipped, exitCode, stateFilePath, elapsedTime)`

Current phases are `Resolving`, `Planning`, `LoadingState`, `Downloading`,
`VerifyingChecksum`, `Staging`, `ApplyingModes`, `ReplacingInstall`,
`VerifyingExecutables`, `CreatingSymlinks`, and `SavingState`.

## Invariants

- `plan`, `apply`, and `versions` output remains script-friendly.
- GitHub latest-release lookup failures do not turn version reporting into a
  failed command.
- `plan` does not touch install directories or state files.
- Apply state is filename-only in the current working directory.
- Manifest installer scripts are unsupported and rejected during config loading.
- Display surfaces use render safety and redaction at renderer boundaries while
  preserving raw values for filesystem and network operations.
