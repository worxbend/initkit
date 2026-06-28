# Initkit Implementation Plan

This plan describes how to implement the runner for the manifest shape in
`config.example.yaml`. The goal is a safe workstation bootstrap tool that reads
a Kubernetes-style YAML profile, resolves variables and host facts, previews the
work, then executes matching plan entries in order.

## Target Behavior

`initkit` should support a manifest with:

- `apiVersion`, `kind`, `metadata`, and `spec`
- informational `spec.target.os`
- global `spec.policy`
- variable interpolation through `spec.vars`
- package source setup through `spec.sources`
- ordered `spec.plan` entries
- one installer `kind` per plan entry
- sequential or parallel execution within each plan entry
- conditional execution through `when`
- explicit interrupt points that write a separate state file and stop cleanly
- dry-run previews before destructive or filesystem-changing work

The first usable command should be:

```bash
./mill app.run apply --config config.example.yaml --dry-run
./mill app.run apply --config config.example.yaml
./mill app.run apply --config config.example.yaml --state ~/.local/state/initkit/developer-workstation.state.json
./mill app.run tui --config config.example.yaml
```

The application has two user-facing modes:

- Plain CLI mode: reads the config file and runs the selected plan entries without an interactive UI.
- TUI mode: reads the same config file, renders the plan with TamboUI widgets, lets the user select plan entries with checkboxes, then runs the selected entries through the same execution engine.

## Tech Stack

Use the existing JVM/Scala stack in this repository.

- Scala 3, currently `3.8.2` in `build.mill`
- Mill, using the checked-in `./mill` launcher
- JDK 21 or newer, required by Ox
- uTest for unit tests
- os-lib for filesystem and process helpers where it fits
- upickle for JSON output where already used
- SnakeYAML Engine, or another maintained JVM YAML parser, for manifest loading
- picocli for command-line parsing
- TamboUI for terminal UI flows
- Ox for structured concurrency, bounded parallelism, cancellation, retries, and timeouts
- sttp client4 for HTTP downloads

Build-file expectations:

- keep dependencies centralized in `build.mill`
- remove `mainargs` after picocli command parsing is in place
- add `info.picocli:picocli:4.7.7` unless a newer compatible version is selected deliberately
- add `org.snakeyaml:snakeyaml-engine:3.0.1`, latest stable checked on 2026-06-28
- add `com.softwaremill.ox::core:1.0.5`, latest stable checked on 2026-06-28
- add `com.softwaremill.sttp.client4::core:4.0.25`, latest stable checked on 2026-06-28
- before implementation, re-check Maven Central and use newer stable releases if available
- keep the TamboUI snapshot repository configured because TamboUI is currently published as snapshots
- keep existing TamboUI modules already in use
- consider adding `dev.tamboui:tamboui-picocli:<same snapshot version>` when the TUI command is wired through picocli

Progress note, 2026-06-28: T001 updated `build.mill` with picocli, SnakeYAML
Engine, Ox core, and sttp client4 versions from canonical Maven metadata and
removed the direct `mainargs` dependency. The existing `info` and `tui` starter
commands were minimally moved to picocli so the build no longer needs
`mainargs`. Required Mill checks are still pending in a network-enabled JVM
environment because this sandbox blocks Mill/coursier downloads with
`java.net.SocketException: Operation not permitted`.

Progress note, 2026-06-28: T002 replaced the temporary CLI with a picocli
`initkit` root command exposing `apply`, `info`, and `tui` subcommands.
`apply` and `tui` now share `--config`, `--state`, and `--reset-state` options;
`apply` parses `--dry-run`, `--yes`, `--only`, and `--skip`; `tui` parses
`--dry-run`, `--select`, and `--skip`. Both `apply` and `tui` return clear
non-stacktrace errors for missing config paths. Focused CLI tests were added,
but Mill validation still cannot reach compilation in this sandbox because
coursier downloads fail with `java.net.SocketException: Operation not permitted`.

Progress note, 2026-06-28: T003 added `initkit.config` manifest models and a
SnakeYAML Engine loader for top-level manifest metadata, policy, target,
sources, plan entries, execution settings, conditions, and raw kind-specific
plan specs. `config.example.yaml` coverage was added through focused loader
tests, including path-aware YAML parse errors. Mill validation still cannot
reach source compilation in this sandbox because coursier downloads fail with
`java.net.SocketException: Operation not permitted`.

Before implementing TUI-related work, scan the current TamboUI repository and
docs, not only the existing local wrapper:

- repository: <https://github.com/tamboui/tamboui>
- read `AGENTS.md` for repository-specific coding-agent instructions
- read `README.md`
- inspect the `docs/` directory
- inspect `demos/` and `tamboui-demos/`
- inspect `tamboui-toolkit`, `tamboui-tui`, `tamboui-jline3-backend`, and `tamboui-picocli`
- confirm the current API names because TamboUI is experimental and APIs may change

Use picocli's Scala-compatible annotation style:

- command classes implement `Callable[Int]` or `Runnable`
- use `@Command`, `@Option`, and `@Parameters`
- use `mixinStandardHelpOptions = true`
- start the CLI through `new CommandLine(new RootCommand()).execute(args: _*)`
- call `System.exit(exitCode)` from the application entrypoint

## Supported Plan Kinds

Implement these kinds first because they are represented in the example config:

- `apt-packages`
- `pacman-packages`
- `dnf-packages`
- `zypper-packages`
- `flatpak-packages`
- `snap-packages`
- `binary-downloads`
- `shell-scripts`
- `nerd-fonts`
- `dotfiles-apply`
- `interrupt`
- `commands`

Each plan entry has this common shape:

```yaml
- name: example
  kind: binary-downloads
  description: Optional human-readable text.
  execution:
    mode: sequential | parallel
    maxConcurrency: 4
    failFast: true
    locks:
      - system-package-manager
  when:
    os:
      family: linux
      distribution:
        oneOf: [ubuntu, debian]
  spec:
    ...
```

## Phase 1: CLI And Config Loading

Replace the existing `mainargs` command parsing with picocli.

Create a root command:

```scala
@Command(
  name = "initkit",
  mixinStandardHelpOptions = true,
  version = Array("initkit 0.1.0"),
  subcommands = Array(classOf[ApplyCommand], classOf[InfoCommand], classOf[TuiCommand])
)
final class InitkitCommand extends Runnable {
  override def run(): Unit = CommandLine.usage(this, System.out)
}
```

Keep `info` and `tui` available as subcommands while adding `apply`.

Plain CLI command contract:

```bash
initkit apply --config config.example.yaml --dry-run
initkit apply --config config.example.yaml --state ~/.local/state/initkit/developer-workstation.state.json
```

TUI command contract:

```bash
initkit tui --config config.example.yaml
initkit tui --config config.example.yaml --state ~/.local/state/initkit/developer-workstation.state.json
```

Shared flags:

- `--config <path>`: YAML manifest path, default `config.yaml`
- `--state <path>`: read and write execution state in a separate JSON file
- `--reset-state`: ignore and overwrite any existing state file

Plain CLI flags:

- `apply --dry-run`: override `spec.policy.dryRun`
- `apply --yes`: skip interactive confirmations where supported
- `apply --only <name-or-kind>`: run matching plan entries only
- `apply --skip <name-or-kind>`: skip matching plan entries

TUI flags:

- `tui --dry-run`: start the TUI in preview mode
- `tui --select <name-or-kind>`: preselect matching plan entries
- `tui --skip <name-or-kind>`: start with matching plan entries unselected

Add a YAML parser dependency. Prefer SnakeYAML Engine or another maintained JVM
YAML parser that supports ordinary YAML maps and lists without requiring custom
tags.

Acceptance criteria:

- `mainargs` is removed from `build.mill`
- picocli powers `info`, `tui`, and `apply`
- `--help` and `--version` work through picocli standard help options
- `apply` and `tui` both load the same manifest parser, validator, variable resolver, condition evaluator, state loader, and execution engine
- missing config path returns a clear error
- invalid YAML returns a clear parse error
- unsupported `apiVersion` or `kind` returns a clear validation error
- `config.example.yaml` parses successfully in tests

## Phase 2: Manifest Model And Validation

Create typed models under `app/src/main/scala/initkit/config`.

Suggested model groups:

- `Manifest`
- `Metadata`
- `Spec`
- `Policy`
- `Target`
- `Sources`
- `PlanEntry`
- `Execution`
- `Condition`
- kind-specific spec models

Keep common plan fields typed, and parse `spec` based on `kind`.

Validation rules:

- `apiVersion` must be `initkit.io/v1alpha1`
- top-level `kind` must be `WorkstationProfile`
- each plan entry must have `name`, `kind`, and `spec`
- plan entry names must be unique
- `execution.mode` defaults to `sequential`
- `execution.maxConcurrency` is valid only for `parallel`
- unknown plan kinds fail validation
- unsupported checksum algorithms fail validation
- package lists must not be empty
- binary downloads require `url`, `destination`, and executable `mode` when they install commands
- `interrupt` entries require `spec.state.path` and may only use `spec.state.format: json` initially
- state paths must resolve outside the config file; do not write execution state into the manifest

Acceptance criteria:

- validation reports all obvious manifest errors with plan entry names
- kind-specific specs are available as typed values to executors
- tests cover one valid entry and one invalid entry per kind

## Phase 3: Host Detection And Conditions

Implement host detection in a small service.

Detect:

- OS family
- Linux distribution from `/etc/os-release`
- version and codename where available
- architecture normalized to values like `amd64` and `arm64`
- command availability through `PATH`

Implement `when` evaluation:

- exact scalar match, for example `distribution: arch`
- `oneOf` match, for example `distribution.oneOf`
- command existence, for example `commandExists: systemctl`

Acceptance criteria:

- skipped entries are printed with a reason
- matching entries are printed before execution
- tests can inject fake host facts

## Phase 4: Variable Resolution

Implement interpolation for strings containing `${name}`.

Variable sources, in precedence order:

1. runtime variables such as `USER` and `HOME`
2. `spec.vars`
3. host facts such as architecture, if exposed

Resolve variables in:

- source definitions
- plan entry `spec`
- command args
- destinations
- config file paths

Rules:

- unresolved variables fail validation unless explicitly allowed later
- interpolation should happen before executor dispatch
- do not run shell expansion for config values

Acceptance criteria:

- `${HOME}` and `${binDir}` resolve in nested plan specs
- unresolved variables produce a useful error with the config path

## Phase 5: Execution Engine

Implement an execution engine that runs plan entries top-to-bottom.

Use Ox for concurrency. Do not build custom thread pools, raw `Future` graphs,
or ad-hoc cancellation. Keep concurrency structured so failures, interrupts,
and cancellation cannot leave background work running after a plan entry exits.

Behavior:

- top-level `spec.plan` order is always respected
- entries with failing `when` conditions are skipped
- completed and skipped entries are persisted when a state file is configured
- when `--state` points to an existing state file, resume from `nextPlanEntry` if present, otherwise after the last completed plan entry
- each entry runs its internal work according to `execution.mode`
- `sequential` runs items one at a time
- `parallel` runs items concurrently with `maxConcurrency`
- `failFast` stops remaining parallel work when feasible
- `locks` prevent conflicting entries or tasks from running at the same time
- `continueOnError` comes from global policy unless an entry override is added later
- timeouts, retries, and cancellation should use Ox primitives where applicable

For the first version, top-level entries can remain sequential even if locks are
implemented as no-ops. Keep the lock field in the model so parallel top-level
execution can be added later without changing the manifest.

Acceptance criteria:

- binary downloads can run in parallel
- parallel entries use Ox structured concurrency and bounded concurrency
- package manager entries run sequentially
- failures stop execution unless `continueOnError` is true
- dry-run prints commands and file changes without applying them

## Phase 6: State And Interrupts

Implement resumable execution through a separate state file.

State file requirements:

- JSON format
- separate from the YAML config
- path comes from `--state` or from an `interrupt.spec.state.path`
- parent directory is created when needed
- written atomically through a temporary file plus rename
- includes enough manifest identity to detect mismatches

Suggested state shape:

```json
{
  "apiVersion": "initkit.io/v1alpha1",
  "kind": "ExecutionState",
  "manifest": {
    "name": "developer-workstation",
    "configPath": "/abs/path/config.example.yaml",
    "fingerprint": "sha256-of-normalized-manifest"
  },
  "createdAt": "2026-06-28T12:00:00Z",
  "updatedAt": "2026-06-28T12:05:00Z",
  "lastCompleted": "relogin-after-shell-install",
  "nextPlanEntry": "apt-containers",
  "entries": [
    {
      "name": "apt-base-cli",
      "kind": "apt-packages",
      "status": "completed",
      "startedAt": "2026-06-28T12:00:10Z",
      "finishedAt": "2026-06-28T12:04:20Z"
    },
    {
      "name": "relogin-after-shell-install",
      "kind": "interrupt",
      "status": "interrupted",
      "finishedAt": "2026-06-28T12:05:00Z"
    }
  ]
}
```

Implement `kind: interrupt`.

Behavior:

- write state before exiting
- mark all previous successful plan entries as completed
- mark the interrupt entry as interrupted
- store `nextPlanEntry` when `spec.state.resumeFrom` is `next`
- print `spec.reason`, `spec.instructions`, and the resume command
- exit with `spec.exit.code`, defaulting to a non-zero pause code such as `75`
- in dry-run, print the state file that would be written and keep running only if explicitly requested later

Resume behavior:

- `--state <path>` loads existing state if present
- completed entries from the state file are skipped
- an interrupted entry is skipped when its `nextPlanEntry` is available
- without `--reset-state`, reject state files whose manifest name or fingerprint does not match
- if the next plan entry no longer exists, fail with a clear error
- update the same state file as additional entries complete
- mark the run complete when the final selected plan entry succeeds

Acceptance criteria:

- the zsh/logout use case is represented by `relogin-after-shell-install`
- the runner stops at `kind: interrupt` and writes a state file
- a subsequent run with `--state` resumes from the next flat plan entry
- tests cover interrupt, resume, stale state mismatch, and reset-state behavior

## Phase 7: Command Runner And Privilege Handling

Create a command runner abstraction.

Responsibilities:

- render commands as arrays where possible
- support `sudo` when a task requires it
- stream stdout and stderr
- capture exit code
- support dry-run without spawning the command
- provide clear logs with plan entry name and item name

Rules:

- do not use shell unless a spec explicitly requires shell behavior
- for `commands.run`, use shell because it is intentionally user-provided command text
- never prompt for sudo in tests
- detect missing required commands before executing a plan entry when practical

Acceptance criteria:

- command runner has unit tests with a fake backend
- dry-run output includes the exact command that would run

## Phase 8: TUI Mode

Implement a TamboUI-based interactive mode backed by the same manifest and
execution services as plain CLI mode.

The TUI should feel like a polished terminal product, not a debug form. Use
the visual language shown in the user's references: framed panes, colorful
section labels, highlighted selections, compact status text, visible key hints,
and dense but readable information. Aim for an experience closer to tools like
binsider or rich OpenAPI terminal explorers than a plain checklist.

TUI goals:

- present the selected profile name, target OS info, detected host facts, and state-file status
- render `spec.plan` as an ordered checklist
- show each entry's `name`, `kind`, `description`, selection state, condition status, and execution mode
- disable entries whose `when` condition does not match, while still showing why they are skipped
- mark completed entries from `--state` as completed and unselected
- make `interrupt` entries visually distinct as pause/checkpoint steps
- let the user select or unselect runnable plan entries with checkboxes
- provide actions for dry-run, run selected, run all matching, resume from state, view details, and quit
- stream execution progress and command output in a log panel
- show a final summary with completed, skipped, interrupted, failed, and remaining entries

Suggested layout:

- top status bar: profile name, current host, dry-run/live mode, state path
- left or main panel: plan checklist in manifest order
- right/details panel: selected entry details, including kind-specific summary
- bottom log panel: recent commands, previews, failures, and resume instructions
- footer: key hints for select, details, dry-run, run, resume, and quit

Interaction model:

- arrow keys move focus through plan entries
- space toggles the focused checkbox when the entry is runnable
- `a` toggles all runnable entries
- `d` opens or focuses the details panel
- `p` runs a dry-run preview for selected entries
- `r` runs selected entries
- `R` resumes from the loaded state file
- `q` quits after confirmation if work is running

Implementation notes:

- scan the current TamboUI docs and demos before building widgets
- prefer existing TamboUI widgets and layout primitives over custom rendering
- keep execution logic outside UI classes
- represent UI state separately from execution state
- do not block the render loop while commands run
- route execution events into the UI through a small event model
- use the same dry-run and state behavior as `apply`
- if live execution requires sudo, surface that clearly before starting

Acceptance criteria:

- `initkit tui --config config.example.yaml` renders the plan from the YAML file
- plan entries are displayed in the same order as `spec.plan`
- matching entries can be selected with checkboxes
- skipped entries are visible but disabled with reasons
- completed state from `--state` is reflected in the checklist
- dry-run selected uses the same command generation as CLI mode
- run selected uses the same execution engine as CLI mode
- interrupt entries write state and show resume instructions in the TUI
- tests cover view-model generation without requiring a real terminal

Visual design requirements:

- use a dark terminal-first theme with strong contrast
- use color deliberately for status: ready, selected, skipped, completed, running, interrupted, failed
- give each plan kind a stable accent color or compact badge
- render active focus with a clear border/accent, not only text color
- use framed panels with compact titles, for example `[ Plan ]`, `[ Details ]`, `[ Output ]`
- keep the layout information-dense, with no marketing copy or empty decoration
- use symbols only when they improve scanability, for example checkbox states and status marks
- keep labels short enough to fit narrow terminals
- provide a clean fallback for terminals without full color support
- avoid relying on Unicode glyphs when ASCII fallback is needed

Suggested color semantics:

- selected/runnable: cyan or blue accent
- completed: green
- skipped/disabled: muted gray
- interrupted/checkpoint: amber/yellow
- failed: red
- running: magenta or bright blue
- dangerous/live mode: red or amber status indicator
- dry-run mode: cyan status indicator

Suggested entry rendering:

```text
[x] 01 apt-base-cli              apt-packages      ready      sequential
[ ] 02 relogin-after-shell       interrupt         checkpoint sequential
[-] 03 pacman-containers         pacman-packages   skipped    condition mismatch
[✓] 04 direct-binaries           binary-downloads  completed  parallel x4
```

Suggested pane structure:

```text
┌─ initkit ─ developer-workstation ─────────────────── dry-run ─ state: loaded ─┐
│ [ Plan ]                         │ [ Details: direct-binaries ]              │
│ > [x] apt-base-cli      ready     │ kind: binary-downloads                    │
│   [x] direct-binaries   ready     │ mode: parallel, maxConcurrency: 4         │
│   [ ] apply-dotfiles    ready     │ items: kubectl, helm, dotbot-go, fonts    │
│   [-] dnf-base-cli      skipped   │ checksum: required                        │
│                                  │                                            │
│ [ Output ]                                                                    │
│ dry-run $ curl ... kubectl                                                     │
│ dry-run $ install -m 0755 ...                                                  │
└─ [space] select  [a] all  [p] preview  [r] run  [d] details  [q] quit ────────┘
```

## Phase 9: Source Setup

Implement `spec.sources` before package installation.

Adapters:

- apt repositories and GPG keys
- dnf repositories
- zypper repositories
- flatpak remotes

Initial implementation can limit source setup to the active host package manager.
For unsupported host package managers, skip unrelated source sections.

Acceptance criteria:

- source setup is visible in dry-run output
- apt `updateBeforeInstall` causes an update before apt install
- flatpak remote setup is idempotent where possible

## Phase 10: Package Manager Executors

Implement one executor per package manager kind.

Commands:

- `apt-packages`: `apt-get update`, `apt-get install -y ...`
- `pacman-packages`: `pacman -Sy --needed ...`
- `dnf-packages`: `dnf install -y ...`
- `zypper-packages`: `zypper refresh`, `zypper install -y ...`
- `flatpak-packages`: `flatpak install -y <remote> ...`
- `snap-packages`: `snap install ...`, with `--classic` where configured

Acceptance criteria:

- generated commands match the manifest
- package managers use sudo by default when `requireSudo` is true
- dry-run never mutates package state
- tests assert command generation for each package manager

## Phase 11: Binary Downloads

Implement `binary-downloads`.

Use sttp client4 for HTTP. The core dependency is enough for JVM synchronous
downloads because it includes Java `HttpClient`-based backends. Prefer a
synchronous sttp backend inside Ox-supervised work units so parallel downloads
stay direct-style and cancellable by the enclosing Ox scope.

Responsibilities:

- create destination parent directories
- download files to a temporary path with sttp
- stream response bodies to disk rather than loading full archives into memory
- support HTTP status validation and useful error messages for failed downloads
- configure sane connect/read timeouts
- verify SHA256 checksums when configured
- extract archives when `archive` is present
- support `tar.gz`
- support `archive.path`
- support `archive.stripComponents`
- install the selected file to `destination`
- set file mode
- replace destination atomically where possible

Acceptance criteria:

- plain binary download works
- tar.gz extraction with selected path works
- checksum mismatch fails before installation
- direct binaries run in parallel when configured
- sttp calls are wrapped in Ox concurrency for parallel downloads
- tests use local fixture files, not live network calls

## Phase 12: Shell Script Installers

Implement `shell-scripts`.

Responsibilities:

- skip an item when `creates` already exists
- download installer scripts to a temporary path
- execute with configured shell and args
- honor dry-run

Acceptance criteria:

- rustup and miniforge examples render correct commands
- `creates` prevents duplicate execution
- script execution is sequential in the example

## Phase 13: Nerd Font Executor

Implement `nerd-fonts`.

Behavior:

- ensure config parent directory exists
- write `spec.config.content` to `spec.config.path` when `create` is true
- run preview first when `preview.enabled` is true and not globally disabled
- run `${tool.path} ${tool.args...}`
- append preview args only to the preview command

For the example, the command is:

```bash
${binDir}/nerdfont-install -config ${nerdFontConfig}
${binDir}/nerdfont-install -config ${nerdFontConfig} -dry-run
```

Acceptance criteria:

- generated YAML config contains selected font families
- dry-run shows config creation and install command
- normal run executes preview, then apply

## Phase 14: Dotfiles Executor

Implement `dotfiles-apply`.

Behavior:

- clone repository if `destination` does not exist
- update repository when `repository.update` is true
- checkout configured `ref`
- verify `config.path` exists after checkout
- run preview first when `preview.enabled` is true and not globally disabled
- run `${tool.path} ${tool.args...}`

For the example, the command is:

```bash
${binDir}/dotbot-go -d ${dotfilesDir} -c ${dotfilesConfig}
${binDir}/dotbot-go -d ${dotfilesDir} -c ${dotfilesConfig} --dry-run
```

Acceptance criteria:

- clone, update, and checkout commands are visible in dry-run
- missing dotbot config fails with a clear error
- preview runs before apply

## Phase 15: Generic Commands Executor

Implement `commands`.

Behavior:

- each item has `name`, `run`, optional `sudo`, and optional `when`
- evaluate item-level `when`
- execute sequentially unless the entry later declares parallel execution
- use shell execution for `run`

Acceptance criteria:

- `systemctl enable --now docker` is skipped when `systemctl` is missing
- sudo is applied only to items that request it or require it through policy
- dry-run prints command text without executing

## Phase 16: Logging And Reporting

Implement clear console output.

Report:

- manifest name
- detected host facts
- selected plan entries
- skipped entries with reasons
- each command or file operation
- dry-run status
- state file path, current resume point, and whether the run was resumed
- final summary with success, skipped, failed counts

For TUI mode, report the same information through widgets and a log panel rather
than only plain stdout.

Keep plain CLI output useful in CI and terminals. Use color conservatively in
plain CLI mode only if it does not make logs harder to parse.

Acceptance criteria:

- a user can understand what happened without opening debug logs
- failures include plan entry and item name

## Phase 17: Tests

Add tests around pure logic first.

Required test areas:

- YAML loading
- validation
- variable interpolation
- condition matching
- command generation
- execution ordering
- Ox-backed bounded parallel execution
- TUI view-model generation from a manifest
- TUI selection rules for matched, skipped, completed, and interrupted entries
- dry-run behavior
- state file writing and resume behavior
- binary archive extraction with fixtures
- sttp download handling through fake or local HTTP fixtures

Integration tests should use fake command runners and temporary directories.
Do not install real packages during tests.

Target command:

```bash
./mill app.test
```

## Phase 18: Documentation

Update `README.md` after the first working version.

Document:

- manifest structure
- CLI mode and TUI mode
- supported `kind` values
- dry-run workflow
- interactive plan selection with checkboxes
- interrupt and resume workflow with `--state`
- OS detection
- variable interpolation
- safety model
- examples for package managers, binaries, scripts, fonts, and dotfiles

Also document that `spec.target.os` is informational and that actual selection
comes from host detection plus `when`.

## First Milestone

The first milestone is complete when CLI dry-run works safely:

```bash
./mill app.run apply --config config.example.yaml --dry-run
```

It should parse the config, validate it, resolve variables, detect the current
host, select matching plan entries, and print the commands and file operations
that would run without mutating the machine.

## Second Milestone

The second milestone is complete when these entries can run for real on a
supported Linux host:

- matching native package manager entries
- `interrupt` with state-file resume
- `binary-downloads`
- `shell-scripts`
- `nerd-fonts`
- `dotfiles-apply`
- `commands`

Real execution should require a clear non-dry-run command and should stop on the
first failure unless `spec.policy.continueOnError` is true.

## Third Milestone

The third milestone is complete when this interactive flow works:

```bash
./mill app.run tui --config config.example.yaml
```

It should render the plan with TamboUI widgets, show checkboxes for runnable
entries, disable skipped entries, reflect state-file progress, run dry-run
previews for selected entries, and execute selected entries through the same
engine used by plain CLI mode.

## Agent Loop Tasks

- T001 Refresh build dependencies
- T002 Replace command parsing
- T003 Model and parse manifests
- T004 Validate manifest semantics
- T005 Run parser checkpoint
- T006 Resolve manifest variables
- T007 Detect host conditions
- T008 Select preview entries
- T009 Persist execution state
- T010 Abstract command execution
- T011 Run execution engine
- T012 Run engine checkpoint
- T013 Generate source setup
- T014 Implement package managers
- T015 Implement generic commands
- T016 Run package checkpoint
- T017 Implement shell scripts
- T018 Download binaries safely
- T019 Extract binary archives
- T020 Run download checkpoint
- T021 Implement font installer
- T022 Implement dotfiles apply
- T023 Report CLI outcomes
- T024 Run CLI milestone
- T025 Research TamboUI APIs
- T026 Build TUI view model
- T027 Render TUI checklist
- T028 Wire TUI execution
- T029 Run TUI checkpoint
- T030 Document initkit usage
- T031 Run final validation
