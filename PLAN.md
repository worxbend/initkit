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
- 2026-06-29: T004 validation checkpoint passed. Focused config/core/cli tests,
  recursive compile and tests, scalafmt, git whitespace checks, and read-only
  `app.run --help` / `app.run plan --config config.example.yaml` smoke checks
  completed successfully with no fixes required. Remaining risk is in the
  planned T005+ implementation work: CLI plan/apply output still uses
  placeholder core services until version resolution and planning are added.
- 2026-06-29: T005 completed core variable and version resolution. Runtime,
  manifest, policy, and tool-local `${...}` variables resolve into a
  `ResolvedPlan`; pinned and `http-text` versions produce concrete values;
  `dynamic.latest-url` remains explicitly dynamic; and resolution failures
  aggregate `ValidationError`s with YAML-like paths. CLI plan/apply rendering
  is still intentionally deferred to T006.
- 2026-06-29: T006 completed read-only plan rendering and selection. `plan`
  now resolves and prints ordered tools from the manifest with destinations,
  version status, downloads, archive or installer strategy, executables, and
  local/sudo symlink commands; `--only` and `--skip` are shared by `plan` and
  `apply --dry-run`; sudo symlink risk is highlighted; and tests cover that
  plan/dry-run paths do not create install or state paths.
- 2026-06-29: T007 validation checkpoint passed. Config, core, CLI, recursive
  compile, recursive tests, scalafmt, git whitespace, live
  `app.run plan --config config.example.yaml`, and isolated temporary-apps
  no-write smoke checks completed successfully. No source fixes were required
  before filesystem-changing executor work begins.
- 2026-06-29: T008 completed direct binary apply execution. Core now has typed
  download and filesystem staging boundaries, sha256 verification before
  staging replacement, direct binary placement at the first executable path,
  executable mode application from four-digit octal strings with a 0755
  default, and typed apply failures that render without stack traces. Existing
  installs are preserved for download, checksum, staging, and mode failures;
  archive extraction and symlink application were still deferred at that point.
- 2026-06-29: T009 completed archive-backed installs for `zip`, `tar.gz`, and
  structured-command `tar.xz`, including archive path safety checks and mapped
  file/directory extraction before install replacement.
- 2026-06-29: T010 originally completed constrained installer-script execution
  and symlink application. This was later superseded: installer scripts were
  removed from the supported manifest and Helm/Kustomize moved to archive
  downloads. The symlink policy and rendering work remains relevant.
- 2026-06-29: T011 validation checkpoint passed after the direct binary,
  archive, and symlink executor work. Config, core, CLI,
  recursive compile, recursive tests, scalafmt, git whitespace checks, and live
  `app.run plan` / `app.run apply --dry-run` smoke checks against
  `config.example.yaml` all completed successfully with no source fixes
  required. Remaining risk is in future state/resume and reporting work rather
  than the validated executor checkpoint.
- 2026-06-29: T012 locked `config.example.yaml` as the executable replacement
  for `binary-dist.sh`. Version keys now match the 15 binary tool names, and
  regression tests assert the exact tool order, pinned/dynamic/resolver version
  sources, `${appsDir}` install directories, configured sha256 checksums, live
  core resolution of example install paths, and Neovim sudo symlink policy
  gating.
- 2026-06-29: T013 completed state persistence and resume for apply. State is
  tied to profile name plus a manifest fingerprint, CWD-local filenames only,
  atomically written after each terminal tool result, and resume skips completed
  tools by name while retrying failed tools unless selection excludes them.
- 2026-06-29: T014 completed CLI reporting polish. Expected validation,
  resolution, confirmation, and apply failures now render concise lines without
  stack traces; invalid config validation errors are printed individually;
  failed tools include the tool name, action, and reason; `versions` reports
  pinned, resolved, and dynamic version sources with referencing tools; non
  dry-run apply enforces `policy.requireConfirmation` via `--yes`; and
  `policy.continueOnError` controls whether apply stops after the first failed
  tool.
- 2026-06-29: VALIDATION-17 checkpoint passed after state/resume and reporting
  work. Config, core, CLI, recursive compile, recursive tests, scalafmt, git
  whitespace, and app-level `--help`, `plan`, `apply --dry-run`, and `versions`
  smokes against `config.example.yaml` all completed successfully with no source
  fixes required. Remaining risk is in later release/documentation wiring and
  hardening review findings.
- 2026-06-29: T015 validation checkpoint passed after one runtime apply fix.
  The focused yazi non-dry-run smoke initially exposed that GitHub release
  downloads return redirects; the default JDK HTTP clients now follow normal
  redirects. Config, core, CLI, recursive compile, recursive tests, plan,
  apply `--dry-run`, yazi apply in an isolated temporary apps directory,
  scalafmt, and git whitespace checks all completed successfully afterward.
  Remaining risk is in release/documentation wiring and checksum coverage for
  unpinned upstream assets.
- 2026-06-29: T016 completed the documentation and release workflow pivot.
  README now describes `binstaller`, its focused binary distribution scope,
  release artifact names, CLI usage, and exit codes. The native release workflow
  now packages `binstaller-linux-amd64` and smoke-tests native `--help`, `plan`,
  and `apply --dry-run`. Recursive tests, app help smoke, and git whitespace
  checks passed.
- 2026-06-29: T017 completed the post-implementation hardening review. Findings
  and P010 question answers are documented in `docs/hardening-review.md`. The
  implemented hardening changes enforce HTTPS version/download URLs, add runtime
  HTTP and process timeouts, constrain install directories under `appsDir`, and
  improved command diagnostics. Remaining risks for native `tar.xz` inspection
  and richer provenance are explicitly deferred with rationale.
- 2026-06-29: T018 final validation passed. Config, core, CLI, recursive
  compile, recursive tests, scalafmt, git whitespace, and app-level `plan`,
  `apply --dry-run`, and `versions` smokes against `config.example.yaml` all
  completed successfully. Native image build was skipped because `native-image`
  was not available on `PATH`; the active JVM was OpenJDK rather than GraalVM.
  No pending must-fix hardening findings remain without documented deferral.
- 2026-06-29: VALIDATION-22 checkpoint passed. The configured config/core/CLI
  tests, recursive compile, recursive tests, scalafmt, git whitespace check,
  Mill module resolution, task JSON validation, and app-level `--help`, `plan`,
  `apply --dry-run`, and `versions` smokes against `config.example.yaml` all
  completed successfully with no source fixes required. Native image execution
  remains blocked locally because `native-image` is not available on `PATH`;
  `java -version` reports OpenJDK 25.0.3 rather than GraalVM.
- 2026-06-29: The follow-up CLI and executor cleanup moved the implementation
  beyond the original first milestone. Installer-script support was removed
  from the manifest and core executor path; Helm and Kustomize are modeled as
  archive-backed tools; download progress renders as an in-place colored CLI
  progress bar with completed rows and a colored summary; and archive path
  normalization accepts safe `./name` members while still rejecting traversal.
  The next development phase should focus on a first-class TUI and then a
  deliberate production-readiness pass.
- 2026-06-29: T001 of the TUI phase chose the initial TUI stack and entrypoint.
  Tamboui 0.4.0 is not being added directly for the first implementation pass;
  it remains the visual and interaction reference. The implementation will use
  a small deterministic renderer in a dedicated `tui` module, reusing `fansi`
  for ANSI styling and adding JLine only if raw-mode input, resize, alternate
  screen, or mouse support cannot be kept correct with local terminal
  primitives. The explicit user entrypoints are `plan --tui` and
  `apply --tui`; default `plan` and `apply` remain script-friendly.
- 2026-06-29: T002 of the TUI phase added the dedicated `tui` module and wired
  explicit `plan --tui` / `apply --tui` CLI flags. The module graph is acyclic:
  `app -> cli -> {core, tui}`, `tui -> core`, and `core -> config`; core still
  imports no UI code. The initial TUI shell returns a concise not-yet-implemented
  message behind the explicit flags only, while default `plan`, `apply`,
  `apply --dry-run`, and `versions` output remains non-interactive.
- 2026-06-29: T003 of the TUI phase introduced renderer-agnostic core events
  for plan/apply workflows. Core now exposes `planWithEvents` and
  `applyWithEvents`, emits resolving, plan-ready, tool-start, phase,
  download-progress, log, terminal-result, skipped-tool, and summary events, and
  keeps CLI/TUI code out of core. The CLI apply progress bar and colored summary
  now consume the structured event path while preserving script-friendly default
  commands.
- 2026-06-29: T004 of the TUI phase validation checkpoint passed. Config, core,
  CLI, recursive compile, recursive tests including `tui`, app-level `--help`,
  `plan`, `apply --dry-run`, and `versions` smokes against
  `config.example.yaml`, scalafmt, and git whitespace checks all passed with no
  source fixes required. The explicit `plan --tui` and `apply --tui`
  entrypoints remain isolated from default CLI output.
- 2026-06-29: T005 of the TUI phase completed the deterministic planning TUI
  renderer. `plan --tui` and `apply --tui --dry-run` now render a dense
  pre-execution frame with header metadata, selected plan table, highlighted
  details, logs, footer status, and keybar. The renderer uses `fansi` styling,
  truncates long table values with an ellipsis while keeping full values in
  details, and has focused model/rendering tests.
- 2026-06-29: T006 of the TUI phase added keyboard-first navigation. The
  planning TUI now has focus state for plan, details, and logs; Tab cycles
  forward and Shift+Tab or `b` cycles backward; arrows, PageUp/PageDown,
  Home/End, and mouse-wheel events move selection or scroll the focused pane;
  `/` edits the filter; `?` renders in-frame help; and `q`/Ctrl+C exits through
  the terminal cleanup path. Overflowing details and logs render visible
  scrollbars, and non-interactive `--tui` invocations fall back to a static
  frame instead of hanging.
- 2026-06-29: T007 of the TUI phase completed the apply execution renderer.
  `apply --tui` now consumes core apply events through an execution model,
  replacing the planning table during apply with current tool, phase, spinner,
  elapsed time, byte progress, recent logs, compact completed/failed/skipped
  rows, and final summary. `apply --dry-run --tui` renders the exact dry-run
  operation lines from the non-interactive apply renderer in a static execution
  frame without creating install or state paths.
- 2026-06-29: T008 / VALIDATION-31 checkpoint passed for automated
  project-native validation. Config, core, CLI, recursive compile, recursive
  tests including TUI, app-level `--help`, `plan`, `apply --dry-run`,
  `versions`, static `plan --tui`, static `apply --dry-run --tui`, scalafmt,
  Mill module resolution, task JSON validation, and git whitespace checks all
  passed. Live raw-terminal smoke coverage for Ctrl+C, `q`, resize, mouse, and
  narrow-terminal behavior remains blocked in this agent shell because stdin is
  not an interactive TTY.
- 2026-06-29: T009 documented the manual TUI smoke workflow in
  `docs/tui-smoke.md`. The guide includes a disposable no-network profile,
  static fallback checks, normal and narrow terminal steps, focus movement,
  filter/help behavior, detail/log scrolling expectations, dry-run execution
  view checks, terminal cleanup checks, and known limitations for live resize,
  mouse support, non-interactive shells, and synchronous apply cancellation.
- 2026-06-29: T010 completed the mandatory post-TUI readiness review in
  `docs/post-tui-readiness-review.md`. The review covers module
  responsibilities, shell/paths/archive/symlink/sudo/state/checksum/redirect/
  size/timeout/redaction/terminal-control risks, and assigns must-fix follow-up
  work to T011 with explicit deferral rationale for native `tar.xz`
  pre-inspection.
- 2026-06-29: T011 implemented the post-TUI must-fix readiness pass. Runtime
  downloads now have explicit size/body-time limits; archive extraction rejects
  duplicate members and has stronger malformed metadata tests; render surfaces
  use centralized terminal-control scrubbing and env-derived secret redaction;
  SHA-256 checksum values are validated; install replacement attempts rollback
  restore on staged-move failure; and the live apply TUI no longer advertises
  `q`/Ctrl+C cancellation while apply runs synchronously. The review document
  marks remaining deferrals for ZIP external attributes, native `tar.xz`
  pre-inspection, and strict rejection of missing checksums.
- 2026-06-29: VALIDATION-35 checkpoint passed after the post-TUI must-fix
  readiness work. Config, core, CLI, recursive compile, recursive tests
  including TUI, app-level `--help`, `plan`, `apply --dry-run`, and `versions`
  smokes, static non-interactive `plan --tui` and `apply --dry-run --tui`
  smokes, scalafmt, Mill module resolution, task JSON validation, and git
  whitespace checks all passed with no source fixes required. Live
  raw-terminal TUI smoke remains manual because this agent shell is not an
  interactive TTY.
- 2026-06-29: T012 documented public contracts across `config`, `core`, `cli`,
  `tui`, and `app`. Public boundary classes, traits, enums, objects, methods,
  and values now have intentional ScalaDoc where externally observable, and
  security-sensitive implementation points have concise invariant/risk comments
  for redaction, bounded downloads, checksums, archive extraction, process and
  sudo boundaries, state persistence, terminal control, path normalization, and
  replacement rollback.
- 2026-06-29: T016 final validation passed with no source fixes required.
  Config, core, CLI, TUI, recursive compile, recursive tests, scalafmt, git
  whitespace, task JSON, module resolution, app-level `--help`, `plan`,
  `apply --dry-run`, `versions`, and static non-interactive TUI smokes against
  `config.example.yaml` all passed. Native image build remains locally blocked
  because `native-image` is not on `PATH`; `java -version` reports OpenJDK
  25.0.3. Iteration 41 re-ran this final gate and marked T016 complete in the
  task ledger.
- 2026-06-29: VALIDATION-42 checkpoint revalidated the completed TUI/readiness
  chunk. The configured config/core/CLI tests, recursive compile and tests,
  app-level `--help`, `plan`, `apply --dry-run`, `versions`, scalafmt, and git
  whitespace checks all passed with no source fixes. Additional project-native
  checks also passed: `tui.test`, static non-interactive `plan --tui` and
  `apply --dry-run --tui` smokes, Mill module resolution, task JSON validation,
  and must-fix review status scan. Native-image remains locally blocked because
  `native-image` is not on `PATH`; this shell is not an interactive TTY for live
  raw-terminal TUI smoke.
- 2026-06-29: T001 of the follow-up hardening queue recorded redirect
  provenance for `http-text` version resolution and binary downloads. JDK HTTP
  clients now expose initial URL, final URL, and followed redirect hops while
  preserving HTTPS validation, normal redirect policy, request timeout, download
  size limit, and body-timeout behavior. Plan/versions/apply output and TUI
  details render final URL provenance only when redirects occur, with terminal
  scrubbing and sensitive-value redaction applied. Apply state now stores
  successful download provenance for future lock-ready data use.
- 2026-06-29: T002 of the follow-up hardening queue replaced the TUI `stty`
  shell wrapper with an injectable terminal backend that invokes `stty` through
  direct argv using `ProcessBuilder` and redirects process input from
  `/dev/tty`. Interactive plan/apply runners now place `open()` inside
  cleanup `finally` paths, and the system terminal restores saved raw-mode
  state when normal close, quit, service failure, or `/dev/tty` input-open
  failure occurs. Focused TUI tests cover argv boundaries, tty targeting,
  idempotent close, and open-failure cleanup.
- 2026-06-29: T003 of the follow-up hardening queue added live TUI resize
  support. Raw terminal mode now uses timed nonblocking reads so periodic size
  checks can emit `TuiInput.Resize` after startup; planning sessions consume
  those events through the existing state machine, and execution rendering
  refreshes the terminal viewport before each frame. Planning and execution
  renderers now clip headers, state/config paths, status lines, keybars,
  scrollbars, and narrow terminal frames to the current viewport width.
- 2026-06-29: VALIDATION-6 checkpoint passed for the completed follow-up
  hardening chunk covering redirect provenance, direct `stty` process
  boundaries, and TUI resize handling. Config, core, CLI, and TUI focused
  tests passed, along with recursive compile/test, scalafmt, Mill module
  resolution, task JSON validation, app-level `--help`, `plan`, `apply
  --dry-run`, `versions`, static non-interactive `plan --tui` and `apply
  --dry-run --tui` smokes against `config.example.yaml`, and git whitespace
  checks. No source fixes were required. Native-image remains locally blocked
  because `native-image` is not on `PATH`; live raw-terminal resize/TUI smoke
  still requires a real interactive terminal.
- 2026-06-29: T004 of the follow-up hardening queue revalidated interpolated
  path values. Tool names now reject empty, traversal, control-character, and
  path-separator values with YAML-like paths. Core resolution now rechecks
  interpolated state files, install directories, download filenames, archive
  mappings, executable paths, create-directory paths, and symlink paths/targets
  according to each field's safety class before execution. Focused config and
  core tests cover malicious variables resolving into absolute, traversal, and
  control-character path values.
- 2026-06-29: T005 validation checkpoint passed after updating stale CLI/TUI
  test fixtures to use CWD-local state filenames under the stricter state-file
  contract. Config, core, CLI, and TUI focused tests passed, along with
  recursive compile/test, scalafmt, Mill module resolution, task JSON
  validation, app-level `--help`, `plan`, `apply --dry-run`, `versions`,
  static non-interactive `plan --tui` and `apply --dry-run --tui` smokes
  against `config.example.yaml`, and git whitespace checks. Native-image
  remains locally blocked because `native-image` is not on `PATH`;
  `java -version` reports OpenJDK 25.0.3.
- 2026-06-29: T006 added static TUI native smokes to the native release
  workflow. After the native executable is copied to `dist/binstaller-linux-amd64`,
  the release job now preserves the existing native `--help`, `plan`, and
  `apply --dry-run` smokes and also runs native `plan --tui` and
  `apply --dry-run --tui` against `config.example.yaml`. The release guide
  documents these static TUI native smoke checks for workflow, local native,
  and post-publish release smoke paths.
- 2026-06-29: T007 extracted resolution and download responsibilities out of
  `core/src/binstaller/core/CoreModule.scala` into focused core source files
  for resolved plan types, plan resolution/interpolation/path validation, URL
  provenance, HTTP text resolution, runtime HTTP URL/client helpers, and binary
  download/body limits. The module graph was not changed, and `./mill core.test`,
  `./mill __.compile`, scalafmt check, and git whitespace checks passed.
- 2026-06-29: T008 extracted archive extraction, structured command execution,
  install filesystem staging/replacement, and symlink apply boundaries out of
  `CoreModule.scala` into focused core source files. Security-sensitive comments
  remain beside traversal normalization, archive metadata rejection, command
  environment isolation, sudo argv construction, checksum-before-replace, and
  replacement rollback. `./mill core.test`, `./mill cli.test`, `./mill
  __.compile`, scalafmt check, task JSON validation, and git whitespace checks
  passed.
- 2026-06-29: VALIDATION-12 checkpoint passed for the completed maintainability
  split chunk. Config, core, CLI, and TUI focused tests passed, along with
  recursive compile/test, scalafmt, Mill module resolution, task JSON
  validation, app-level `--help`, `plan`, `apply --dry-run`, `versions`,
  static non-interactive `plan --tui` and `apply --dry-run --tui` smokes
  against `config.example.yaml`, and git whitespace checks. No source fixes
  were required. Native-image remains locally blocked because `native-image`
  is not on `PATH`; `java -version` reports OpenJDK 25.0.3. Live raw-terminal
  TUI behavior remains covered by deterministic tests and still needs a real
  interactive terminal for manual smoke.
- 2026-06-29: T009 extracted apply state persistence, installer event ADTs,
  structured apply/install errors, terminal result rendering, and render
  safety/redaction helpers from `CoreModule.scala` into focused same-package
  core files. CLI and TUI continue importing the same `binstaller.core` API
  names. `./mill core.test`, `./mill cli.test`, `./mill tui.test`, `./mill
  __.compile`, scalafmt check, task JSON validation, and git whitespace checks
  passed.

## Current Agent Loop State

As of 2026-06-29, the active product is no longer the original broad
bootstrapper or the earlier script-capable binary installer. The working shape
is:

- `app -> cli -> {core, tui}`, `tui -> core`, and `core -> config` remains the
  active runtime graph; core does not depend on CLI or TUI code.
- `config.example.yaml` is the executable profile for the current binary-tool
  surface.
- Direct downloads, archive extraction, checksums, symlinks, state/resume,
  root-cause error rendering, colored CLI progress, and colored CLI summaries
  are implemented.
- Installer scripts are intentionally unsupported. Any `installer:` block should
  fail manifest validation and suggest direct binary or archive download.
- The remaining command execution boundaries are structured and narrow: sudo
  symlink operations, the current `tar.xz` fallback, and test/fake executors.
- The TUI terminal backend no longer constructs shell command strings for
  `stty`; terminal sizing and raw-mode save/restore use direct process argv
  with `/dev/tty` as redirected input, and live interactive sessions can emit
  `TuiInput.Resize` from periodic terminal-size checks.
- The planning TUI now renders deterministically behind explicit `plan --tui`
  and `apply --tui` entrypoints and includes keyboard navigation, filtering,
  focusable scrollable details/logs, help, resize-aware layout sizing, and
  terminal cleanup on quit.
- Durable manual TUI smoke instructions are stored in `docs/tui-smoke.md`.
  The no-network smoke uses a temporary profile and dry-run apply; any real
  apply smoke must use an isolated temporary `appsDir` and disposable
  current-directory state filename.
- The TUI, post-TUI readiness fixes, maintainer docs, README, and final
  validation are complete for this loop.

## Agent Loop Tasks

The resumable implementation queue is stored in `.agent-loop/tasks.json`.
The next loop continues after the completed TUI/readiness milestone and
prioritizes remaining production hardening before new lock/provenance features:

| Task | Type | Complexity | Title |
| --- | --- | --- | --- |
| T001 | improvement | complex | Record redirect provenance |
| T002 | fix | moderate | Replace TUI stty shell boundary |
| T003 | improvement | moderate | Emit TUI resize events |
| T004 | fix | complex | Revalidate interpolated paths |
| T005 | validation | simple | Checkpoint hardening fixes |
| T006 | improvement | moderate | Smoke TUI in release workflow |
| T007 | improvement | complex | Extract resolution and download code |
| T008 | improvement | complex | Extract archive and filesystem code |
| T009 | improvement | complex | Extract state events and render safety |
| T010 | validation | simple | Checkpoint maintainability split |
| T011 | feature | complex | Add lock command |
| T012 | feature | complex | Enforce locked apply |
| T013 | feature | complex | Add strict policy profiles |
| T014 | validation | simple | Checkpoint lock and policy |
| T015 | improvement | complex | Discover published checksums |
| T016 | validation | simple | Run final validation |

All tasks start as `pending`. Validation checkpoints are inserted after the
network/terminal/path hardening cluster, after the core maintainability split,
after lock/policy work, and at final completion.

## Next Phase Development Plan

### P011 - Tamboui-Inspired TUI Experience

Build a real TUI experience, not a marketing screen. Use Tamboui references only
for visual and interaction direction, especially the demos for `Canvas`,
`Scrollbar`, `Spinner`, `Table`, and `Wavetext`. The implementation may use
Tamboui directly if its JVM/Scala integration is practical and version-checked
before coding; otherwise, reproduce the same interaction model in the existing
TUI stack with a clear note in the implementation summary.

T001 stack decision: for the initial implementation, Tamboui is visual and
interaction reference only, not a direct runtime dependency. The practical path
for the current Scala 3/Mill/JVM/native-image build is a dedicated `tui` module
with a deterministic layout/rendering model, `fansi` for color/styling, and
small terminal primitives for borders, tables, scrollbars, spinner/wavetext-like
indicators, truncation, and snapshot-friendly output. JLine is the only planned
new dependency candidate for terminal raw mode, key decoding, resize handling,
alternate-screen lifecycle, and mouse wheel support if local primitives become
too brittle. TUI entry is explicit: `plan --tui` opens the read-only planning
TUI, and `apply --tui` opens the apply TUI; default `plan` and `apply` output
must remain non-interactive.

Dependency impact and risks: adding Tamboui directly would likely require
`dev.tamboui:tamboui-tui:0.4.0`,
`dev.tamboui:tamboui-toolkit:0.4.0`, and
`dev.tamboui:tamboui-jline3-backend:0.4.0`. Those artifacts are current on
Maven Central as of 2026-06-29, and Tamboui documents GraalVM native-image
support, but the project still describes itself as experimental and under
active API development. Direct use would add Java-first widget abstractions,
backend lifecycle behavior, and native-image reachability/terminal cleanup
surface that this repo has not validated. Testability risk is also higher
because the TUI would depend on third-party widget internals rather than this
repo's own deterministic layout model. JLine, if added, has a narrower risk:
native-image behavior and terminal state cleanup still need validation, but it
can be isolated behind an input/backend boundary while rendering remains pure
and snapshot-testable.

Style direction:

- Dark, dense, developer-tool UI with crisp borders, clear focus rings, muted
  background panels, and one high-contrast accent color for the active pane.
- Reference feel: terminal-native dashboards like PR/review tools and compact
  agent workbench layouts, with readable tables, narrow status bars, and a
  large log/detail area.
- Avoid ornamental screens. The first viewport must be the working installer
  interface.
- Prefer sharp or lightly rounded panels, 1px borders, compact spacing, and
  strong contrast between active and inactive panes.
- Use color intentionally: green for completed, red for failed, yellow for
  warnings/confirmation, cyan/blue for active download/progress, gray for
  skipped or inactive details.
- Add a Posting-inspired variant to the visual language: deep navy/black
  background, purple/magenta borders and tabs, cyan success/activity accents,
  pale monospace foreground, and a persistent bottom shortcut bar. Use the
  screenshot's request/response split as inspiration for installer plan/detail
  and execution/log splits, not as a literal HTTP-client clone.
- Active selection should be obvious through a filled highlight band plus bright
  foreground text. Inactive rows should remain readable but muted.
- Controls should feel terminal-native: tab labels across pane headers,
  compact input/status strips, inline badges, and mode chips such as
  `dry-run`, `apply`, `read-only`, `sudo`, `verified`, or `dynamic`.

Required TUI layout:

- Header: app name/version, mode (`plan`, `apply`, `dry-run`), manifest name,
  config path, state-file path, host summary, and current selection/filter.
- Main planning view before execution:
  - left or top table for selected plan entries with status, kind, version,
    install dir, checksum state, and risk markers;
  - detail pane for the highlighted entry, including URL, archive mappings,
    symlinks, and dry-run operation preview;
  - logs pane visible and scrollable, with enough height to be useful.
- Navigation tree/list variant:
  - left pane may render a grouped tree of plan entries by status, source type,
    or phase, similar to collection/request trees;
  - expanded groups should show concise status/method-like badges such as
    `BIN`, `ZIP`, `TGZ`, `TXZ`, `DIR`, `LINK`, and `SUDO`;
  - selected row should drive the right-side details pane.
- Apply execution view:
  - when a tool is actively running, hide the full plan table and render a
    focused execution screen instead;
  - show a `Spinner` or `Wavetext` loading indicator, current tool name, phase,
    active download/progress bar, bytes, elapsed time, and recent log lines;
  - after the tool completes, append a compact completed/failed row and move to
    the next active execution screen.
- Logs view:
  - logs pane must be larger than the initial implementation;
  - logs pane must be focusable;
  - users must be able to switch focus between plan/table, details, and logs;
  - focused logs must support scrolling with arrow keys, PageUp/PageDown,
    Home/End, and mouse wheel where available;
  - include a visible scrollbar when content exceeds the viewport.
- Footer/status bar: key hints, current focus, progress count, last result,
  and transient messages.
- Bottom keybar should use concise shortcut labels inspired by Posting:
  `^c Quit`, `Tab Focus`, `/ Filter`, `Enter Details`, `PgUp/PgDn Logs`,
  `r Retry`, `? Help`. Keep it visible unless terminal height is too small.

Tamboui widget guidance:

- `Table`: plan entries, skipped entries, and completed results.
- `Scrollbar`: logs pane and long details pane.
- `Spinner`: active operation when the exact percent is unknown.
- `Wavetext`: short loading banner for resolving, source setup, or waiting
  phases.
- `Canvas`: lightweight branded header/progress accents only if it improves
  clarity; do not let decorative canvas work reduce readability.

Interaction requirements:

- Keyboard-first: `Tab`/`Shift+Tab` or equivalent to cycle panes, arrow keys to
  move selection, `/` for filtering if simple to add, `Enter` for details, `q`
  to quit, and `?` for help.
- Preserve non-interactive CLI behavior. The TUI must be an explicit command or
  flag and must not replace script-friendly output.
- Terminal resizing must keep text inside panes and avoid overlap.
- Long paths and URLs should truncate with visible ellipsis in tables, while
  details/log panes provide full values.
- State-file path must be visible in the header or a persistent metadata area.

Implementation requirements:

- Add or restore a dedicated `tui` module only after choosing the dependency
  and confirming native-image compatibility.
- Keep `core` UI-agnostic. Core emits structured state/progress/events; CLI and
  TUI render those events differently.
- Introduce a small event model for apply progress if the current callback
  surface is too download-specific.
- Keep TUI rendering deterministic enough for snapshot or model-level tests.
- Add focused tests for pane focus, log scrolling, state-file display, active
  execution view, and summary rendering.
- Add manual smoke instructions for normal terminal, narrow terminal, and
  resized terminal.

Acceptance checks:

- `./mill __.compile` and `./mill __.test` pass.
- TUI opens without requiring network access for a dry-run profile.
- State-file path is visible.
- Logs pane is visibly larger, focusable, and scrollable.
- During active execution the plan table is not the main view; the active tool
  progress/spinner view is.
- Completed tools render as green completed rows; failed tools render with red
  root-cause summaries.
- The TUI can exit cleanly without corrupting terminal state.

### P012 - Post-TUI Code Review And Production Readiness

After P011 is implemented and validated, pause feature work and perform a full
codebase review. This phase should be treated as mandatory before declaring the
tool production-grade.

Review areas:

- Architecture boundaries: `config`, `core`, `cli`, `tui`, and `app` must have
  clear responsibilities and no accidental UI logic in core.
- Error model: expected failures should remain typed, structured, redacted, and
  rendered separately from construction.
- Security: repeat shell-injection, path traversal, archive metadata, symlink,
  sudo, state-file, checksum, redirect, max-size, timeout, and redaction audits.
- Maintainability: identify long functions, duplicated render logic, stringly
  contracts, awkward ADTs, and places where small abstractions would clarify
  behavior.
- Test quality: strengthen tests around real failure modes, not only happy-path
  rendering.
- Native-image readiness: verify dependencies, reflection/resource needs,
  terminal cleanup, and release workflow compatibility.

Code standards for this phase:

- Add ScalaDoc to every public class, trait, enum, object, method, and value
  that is part of the module boundary or externally observable behavior.
- Add short comments around non-obvious security-sensitive logic, including
  path normalization, archive extraction, checksum verification, process
  execution, sudo boundaries, state persistence, redaction, and terminal control
  sequences.
- Do not add comments that restate the code. Comments must explain intent,
  invariants, risk, or tradeoffs.
- Keep functions small and named by intent. Extract validation, transformation,
  rendering, and side effects when they are mixed together.
- Keep public method return types explicit.
- Keep shell/process boundaries structured as argv, with tests proving manifest
  metacharacters remain text.
- Preserve deterministic rendering tests for CLI/TUI output after stripping
  ANSI where needed.

Deliverables:

- A written code review document under `docs/` covering findings and decisions.
- A prioritized refactoring backlog with `must fix`, `should fix`, and `later`.
- Implement all `must fix` items or document explicit deferrals with rationale.
- Updated security regression tests.
- Updated developer documentation describing module boundaries and common
  development workflows.

### P013 - Developer Documentation

After the post-TUI review, improve project documentation for maintainers and
future agents.

Required docs:

- Architecture overview: module responsibilities, data flow from YAML to
  resolved plan to apply events to CLI/TUI rendering.
- Manifest schema reference with examples for direct binaries, archives,
  symlinks, checksums, state, and selection.
- Security model: trust boundaries, unsupported installer scripts, command
  boundaries, archive safety, checksum policy, sudo policy, state-file policy,
  and redaction.
- TUI guide: keybindings, pane focus, log scrolling, progress states, and
  troubleshooting terminal rendering.
- Testing guide: which Mill targets to run, how to add fake download/archive
  tests, how to snapshot or assert terminal output, and how to run smoke tests.
- Release guide: native-image requirements, workflow artifacts, smoke checks,
  and rollback notes.

README must be updated only as the final-final step after code review,
refactoring, security checks, and developer docs are complete. At that point,
README should become the concise user-facing entry point and link to the deeper
developer docs rather than carrying all internal details itself.

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

### Security And Customization Principles

Hardening is a product requirement, not only a late cleanup pass. The app should
remain flexible enough to model real upstream binary distribution quirks, but
each flexible escape hatch must be explicit, typed, previewable, and testable.

Security defaults:

- Treat manifests as untrusted input, even when they come from a user's own
  dotfiles repository.
- Preserve argv boundaries for commands. Do not concatenate manifest values into
  shell strings.
- Never evaluate manifest variables as shell syntax. Values such as `$()`,
  backticks, glob characters, semicolons, pipes, and redirects are plain text
  unless a field is explicitly documented as shell-owned.
- Require explicit opt-in for risky behavior: dynamic URLs, sudo symlinks,
  overwrite/clean-install behavior, and any future environment passthrough.
- Prefer least privilege. Normal installs are user-local; sudo is allowed only
  for declared system symlink operations or another future typed capability with
  equivalent review.
- Verify before replace: downloads, archive contents, executable presence, and
  checksums where configured must be validated before replacing an existing
  installation.
- Render the same concrete operations in dry-run that apply mode will execute,
  including URL, destination, checksum status, archive extraction mapping,
  symlinks, and sudo usage.
- Redact secrets and sensitive environment values in logs, state, errors,
  debug output, and dry-run previews.

Customization rules:

- Prefer typed fields over free-form commands for common operations.
- Do not support installer scripts in the binary-tool manifest. Upstreams that
  publish shell installers should be represented as direct binary or archive
  downloads only when a real binary artifact is available.
- Do not add a generic shell-command plan kind to the smaller app. If a user
  needs one later, it should be a deliberately gated extension with clear risk
  labeling, confirmations, redaction, and tests.
- Allow project-specific policy knobs, but make the unsafe value noisy in plan
  output and require apply confirmation where appropriate.

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
- Symlink creation, including optional sudo symlinks for Neovim compatibility.
- Checksum fields and checksum enforcement where configured. Missing checksums
  are allowed only when the plan clearly marks the source as unverified or a
  later policy explicitly requires checksums for all non-dynamic downloads.
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
- Installer-script execution for binary tools.
- Broad shell-command execution.
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

Candidate additions, to be version-checked before implementation:

- `org.apache.commons:commons-compress` for JVM-native archive extraction,
  including tar metadata handling and safer archive entry inspection.
- `org.tukaani:xz` if `commons-compress` needs explicit XZ support for
  `.tar.xz` on the selected runtime path.
- `org.jline:jline` for terminal capability detection, width-aware rendering,
  cursor-safe progress updates, and non-garbled output when stdout is a TTY.
- A small JVM progress-rendering library may be considered only if JLine plus a
  local renderer becomes too much maintenance. Prefer a dependency with no
  background threads, good native-image behavior, and testable output.

Before implementation begins, re-check dependency versions if the goal is to
use latest stable releases. If versions are upgraded, record the reason in the
plan or a dedicated change note.

### Formatting And Quality

- Keep `.scalafmt.conf`.
- Use the Scala 3 dialect.
- Run Mill scalafmt checks during validation.
- Prefer typed ADTs and small case classes over stringly typed execution logic.
- Preserve argv boundaries for external commands whenever possible.
- Do not use manifest-derived shell text. External process boundaries must use
  structured argv and explicit typed inputs.
- Model expected failures as typed errors instead of throwing through the CLI.
- Keep public contracts documented where behavior is security-sensitive,
  stateful, or externally observable.
- Add Scala docs or short comments for security-sensitive public contracts:
  command execution, archive extraction, checksum verification, path
  normalization, sudo symlinks, state writes, and redaction.
- Add focused tests alongside each implementation phase.

### Scala Error And Reporting Quality

The Scala implementation should make expected failures first-class values, not
stringly exceptions.

Required shape:

- Define small sealed error ADTs per boundary: config loading, validation,
  variable/version resolution, planning, download, checksum, archive extraction,
  filesystem replacement, symlink creation, sudo execution, state load/write,
  CLI rendering, and TUI rendering.
- Each user-facing error should carry:
  - stable error code or category,
  - affected profile/tool/action/item,
  - root cause message,
  - optional nested causes,
  - command/download/path/checksum context where safe,
  - redacted diagnostics,
  - concrete suggestions when the next action is knowable.
- Aggregated errors should preserve every root cause rather than only the first
  failure. CLI output can summarize at the top, but verbose/root-cause sections
  must allow the user to see all validation and apply failures.
- Suggestions should be typed data, not ad hoc strings spread through the code.
  Examples: update checksum from actual hash, remove conflicting package,
  rerun with `--reset-state`, enable sudo symlinks, check network/TLS, inspect
  archive path, or use `--verbose`.
- Keep rendering separate from error construction. Core returns structured
  errors; CLI decides plain, colored, compact, verbose, or future JSON output.
- Redaction must happen before rendering and before debug-log/state persistence.
- Avoid stack traces for expected failures. Stack traces are debug-only for
  defects and should still be redacted.
- Public methods that return domain errors should have explicit return types,
  and tests should assert both machine-readable error fields and user-facing
  rendering.

Root-cause output expectations:

- Invalid config prints every validation error with YAML-like path, message, and
  suggestion when available.
- Apply failures print every failed tool and every failed item when multiple
  downloads, extractions, mode changes, symlinks, or state writes fail, with a
  concise summary and an expanded root-cause section.
- Command failures include argv, exit status, allowed exit codes, cwd, timeout,
  selected env names, duration, and bounded stdout/stderr tails.
- Download failures include URL, redirect target when relevant, HTTP status,
  size/progress if known, retry count, checksum expectation, and destination.
- Archive failures include archive type, member path, target path, and whether
  the rejection was traversal, unsupported entry type, missing selected member,
  duplicate output, fallback no-match, fallback ambiguity, or extraction I/O.
- State failures include state path, stale fingerprint/profile mismatch, and
  safe recovery options.

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
| `helm` | pinned release archive | `tar.gz`, extract `linux-amd64/helm` to `bin/helm` |
| `kubectl` | `stable.txt` resolver | direct binary download using resolved version |
| `kustomize` | pinned release archive | `tar.gz`, extract `kustomize` to `bin/kustomize` |
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
    versionRef: String,
    installDir: String,
    createDirectories: Vector[String],
    download: DownloadSpec,
    executables: Vector[ExecutableSpec],
    symlinks: Vector[SymlinkSpec]
)

final case class DownloadSpec(
    url: String,
    filename: String,
    checksum: Option[ChecksumSpec],
    archive: Option[ArchiveSpec]
)

final case class ArchiveSpec(
    archiveType: ArchiveType,
    extract: ExtractSpec
)

final case class ExtractSpec(
    files: Vector[ExtractFile],
    directories: Vector[ExtractDirectory]
)
```

### Defaults

- `installDir`: `${appsDir}/${toolName}`
- `cleanInstall`: `spec.policy.cleanInstall`, default true
- `executables[].mode`: `0755`

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
- `installer:` blocks are unsupported and must fail validation with a direct
  binary/archive suggestion.
- URLs must use `https` by default. Any future `http` or local-file source
  support must require an explicit unsafe policy flag and loud dry-run warning.
- Download filenames, install directories, extracted paths, executable paths,
  and local symlink paths must reject absolute paths, `..` traversal, empty path
  segments, control characters, and NUL bytes unless the field is explicitly
  modeled as an absolute system path.
- Variable interpolation must not change a field's safety class. A path that is
  required to be relative must remain relative after interpolation.
- Checksum values must be valid hex for the selected algorithm. Placeholder
  values such as `replace-with-real-*` are invalid in executable examples.
- Dynamic latest URLs must be marked dynamic in output and should be rejected by
  a future strict/reproducible policy unless locked.

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

### Unsupported Installer Script

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

Installer scripts are no longer supported. A manifest containing this shape
must fail validation and should point the user toward a direct binary or archive
download instead.

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

- Version: pinned release, currently represented in `spec.versions`.
- URL:
  `https://get.helm.sh/helm-${version}-linux-amd64.tar.gz`.
- Extract `linux-amd64/helm` into `bin/helm`.
- Verify `bin/helm` exists after archive extraction.

### `kubectl`

- Version resolver: `https://dl.k8s.io/release/stable.txt`.
- URL:
  `https://dl.k8s.io/release/${version}/bin/linux/amd64/kubectl`.
- Direct download to `bin/kubectl`.
- Mark executable.

### `kustomize`

- Version: pinned release, currently represented in `spec.versions`.
- URL:
  `https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize/${version}/kustomize_${version}_linux_amd64.tar.gz`.
- Extract `kustomize` into `bin/kustomize`.
- Verify `bin/kustomize` exists after archive extraction.

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
- Extraction must reject absolute archive member paths, drive-prefixed paths,
  `..` traversal, NUL/control characters, unsafe symlink/hardlink entries, and
  archive members that would overwrite the same target ambiguously.
- Zip-slip and tar path traversal regressions must be covered by tests.
- Directory extraction should support moving an extracted root directory into
  the install root.
- File extraction should support mapping archive members to final relative
  paths.
- If an explicitly configured archive member path does not exist, the extractor
  should scan the safe archive entry index for a binary candidate matching the
  tool name before failing. Candidate discovery must be deterministic:
  - consider only regular file entries that passed path-safety checks,
  - prefer basename equality with the plan tool name or requested executable
    name,
  - prefer common executable locations such as `bin/<name>`,
    `<root>/bin/<name>`, and `<root>/<name>`,
  - optionally use executable mode bits when the archive format exposes them,
  - reject ambiguous matches with a root-cause error listing the candidate
    archive paths,
  - reject no-match cases with a suggestion to update `archive.extract.files`
    or inspect the archive with `--verbose`.
- Fallback archive scanning must be visible in verbose output so the user can
  see the missing requested path, chosen candidate, or ambiguity.

### Symlink Behavior

- Relative symlink paths are resolved under `installDir`.
- Relative symlink targets are resolved relative to the symlink parent or
  `installDir`; pick one rule and document it before implementation.
- Absolute symlink paths require `sudo: true`.
- Sudo symlinks use structured argv:
  `sudo ln -sfn <target> <path>`.
- Dry-run prints every symlink command.
- Symlink creation must reject targets outside allowed roots unless the symlink
  is explicitly marked as a system symlink and the policy allows it.
- Sudo symlink commands must not run through shell text. Use argv and redacted
  command rendering.

### Installer Script Behavior

Installer scripts are allowed only inside `binary-tool` and only with explicit
configuration.

Allowed first-milestone behavior:

- Download script to temp file.
- Set mode `0700`.
- Run configured shell with explicit args.
- Pass explicit env vars.
- Pass only a small baseline environment plus declared env vars. Do not inherit
  the full parent process environment by default.
- Apply configured timeout and cancellation.
- Verify expected executable paths afterward.
- Delete script when `cleanup: true`.

Disallowed behavior:

- Arbitrary inline shell snippets as standalone plan entries.
- Unbounded environment passthrough.
- Silent sudo usage from the app. If the upstream script runs sudo despite env
  config, the failed command should be reported.
- Implicit shell interpolation of manifest values into a command string.

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
- Add a dedicated `tui` module for the next phase. Keep it UI-only and keep
  `core` independent from terminal rendering.
- Delete, quarantine, or leave unused the broad package/source/dotfiles/font
  executors, but do not keep them on the active user-facing path.

## Implementation Tasks

### P001 - Repository Pivot

Deliverables:

- README and docs describe `binstaller`, not broad workstation bootstrapping.
- CLI root command name becomes `binstaller`.
- Build graph keeps TUI separate until the explicit TUI command or flag is
  introduced.
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
- No command help mentions package managers, dotfiles, or Nerd Fonts as
  first-class features.

### P002 - Manifest Model And Validation

Deliverables:

- New manifest model for `BinaryDistributionProfile`.
- Typed `Policy`, `VersionSource`, `BinaryToolSpec`, `ArchiveSpec`, and
  symlink/executable models.
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
- JVM-native archive extraction for `zip`, `tar.gz`, and `tar.xz`; use
  `commons-compress` plus XZ support if the standard library path is not enough.
  Shelling out to `tar` should be a temporary fallback only when explicitly
  documented and covered by tests.
- Direct binary install.
- Local symlinks.
- Sudo symlink command path.
- Structured progress events for downloads, checksum verification, extraction,
  install replacement, and symlink creation. Core should emit progress data
  without knowing whether the CLI is rendering a progress bar, verbose logs, or
  test assertions.

Acceptance checks:

- Fake executor can install direct binary, zip archive, tar.gz archive, and
  tar.xz archive through the selected extraction path.
- Failed checksum prevents install replacement.
- Failed extraction preserves previous install directory.
- Malicious archive entries are rejected before touching the final install
  directory.
- Missing archive member path falls back to scanning for a matching binary name
  when exactly one safe candidate exists.
- Missing archive member path fails with suggestions when no candidate exists.
- Missing archive member path fails with an ambiguity report when multiple safe
  candidates match.
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
- Styled terminal output with a quiet default and a readable verbose mode.
- Download progress bars when stdout is a TTY and content length or streamed
  byte counts are available.
- Spinner or indeterminate progress when total size is unknown.
- Width-aware rendering that degrades cleanly when output is redirected or
  `--no-color` is set.
- Root-cause section that prints all failures, nested causes, safe diagnostics,
  and typed suggestions.
- Verbose mode for detailed download/checksum/extraction/install operations.
- Exit codes documented in README.

Acceptance checks:

- Missing config path exits nonzero with a concise message.
- Invalid config prints all validation errors.
- Failed apply prints tool name, action, reason, root cause details, and
  suggestions.
- Multiple failures remain visible; the first failure may be highlighted but
  must not hide the rest.
- Download progress rendering is tested with a fake progress stream and is
  disabled automatically for non-TTY output.
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
  resolution, download handling, archive extraction, symlink creation, state
  writes, CLI/TUI reporting, tests, and release workflow.
- Analyze security, reliability, maintainability, and user-experience risks.
- Perform a shell-injection and command-boundary audit. Every external process
  should be classified as argv-safe, a narrow structured fallback, or rejected
  as too broad for the smaller app.
- Perform a path traversal audit for all manifest-derived paths, archive member
  names, symlink targets, temp files, state files, and install directories.
- Perform a supply-chain trust audit for every download and version source:
  pinned version, dynamic source, checksum availability, checksum enforcement,
  TLS requirement, redirect behavior, and provenance in plan output.
- Perform a privilege audit for sudo symlinks and any future privileged
  boundary. Confirm sudo cannot be reached by ordinary install fields.
- Perform a logging/redaction audit for CLI output, debug logs, errors, state,
  and test failures.
- Prepare a written improvement report before beginning broad follow-up work.
- Convert accepted suggestions into a prioritized hardening backlog.

Required review questions:

- Can any manifest value become executable shell syntax through interpolation,
  quoting mistakes, generated commands, or terminal rendering?
- Are command args preserved as argv through dry-run and apply, and are the few
  shell boundaries intentionally modeled?
- Can a malicious or malformed archive write outside the staging directory?
- Can a malicious archive create symlinks, hardlinks, special files, duplicate
  paths, permission bits, or ownership metadata that escape the intended install
  root?
- Can a manifest path escape the apps directory, overwrite the state file, write
  into another tool's install directory, or clobber user files unexpectedly?
- Are downloads written atomically and verified before replacing an existing
  install?
- Are checksum fields supported consistently, and where should checksums become
  required?
- Are examples free of placeholder checksums and unsafe dynamic sources that
  look reproducible?
- Are redirects, content length, content type, max download size, and timeout
  handled predictably enough for a binary installer?
- Are unsupported `installer:` blocks rejected early with a clear migration
  suggestion?
- Are the remaining process boundaries limited to structured argv and covered
  by tests?
- Are sudo symlinks impossible unless clearly configured and confirmed?
- Are sudo operations rendered with exact argv and highlighted as privileged in
  dry-run and apply?
- Is state written atomically, and does stale state fail safely?
- Are error messages actionable without exposing sensitive environment values?
- Are dry-run and apply paths close enough that dry-run is a trustworthy preview?
- Are tests covering real failure modes, or only happy-path conversions?
- Is the manifest schema still understandable after representing all tools?
- Which old broad-bootstrap modules should be deleted, retained, or split out?

Hardening backlog categories:

- `must fix`: shell injection risks, path traversal, archive escape, unsafe sudo
  reachability, silent checksum bypass, non-atomic replacement, state corruption,
  secret leakage, and dry-run/apply divergence.
- `should fix`: checksum auto-discovery, lock-file generation, max download
  size, stricter redirect policy, network timeouts/retries, improved provenance
  rendering, richer security docs, and additional fuzz/property tests.
- `later`: optional policy profiles such as `strict`, `developer`, and
  `legacy-compatible`; signed provenance/SLSA verification; SBOM export; and
  sandboxed installer execution only if a future product decision explicitly
  reintroduces a script contract.

Acceptance checks:

- A hardening report exists in docs or the plan with concrete findings and
  recommendations.
- Each recommendation is classified as `must fix`, `should fix`, or `later`.
- Must-fix findings are implemented or explicitly deferred with rationale.
- Tests include malicious manifest, archive traversal, shell metacharacter,
  unsafe symlink, bad checksum, secret redaction, timeout, and stale-state
  regressions.
- The final implementation still passes the full verification suite after the
  hardening changes.

## Agent Loop Tasks

The authoritative resumable implementation queue is stored in
`.agent-loop/tasks.json`. The prior bootstrap, executor, TUI, readiness,
documentation, and README phases are complete and recorded in the progress
history above. The active pending queue is:

| Task | Type | Complexity | Title |
| --- | --- | --- | --- |
| T001 | improvement | complex | Record redirect provenance |
| T002 | fix | moderate | Replace TUI stty shell boundary |
| T003 | improvement | moderate | Emit TUI resize events |
| T004 | fix | complex | Revalidate interpolated paths |
| T005 | validation | simple | Checkpoint hardening fixes |
| T006 | improvement | moderate | Smoke TUI in release workflow |
| T007 | improvement | complex | Extract resolution and download code |
| T008 | improvement | complex | Extract archive and filesystem code |
| T009 | improvement | complex | Extract state events and render safety |
| T010 | validation | simple | Checkpoint maintainability split |
| T011 | feature | complex | Add lock command |
| T012 | feature | complex | Enforce locked apply |
| T013 | feature | complex | Add strict policy profiles |
| T014 | validation | simple | Checkpoint lock and policy |
| T015 | improvement | complex | Discover published checksums |
| T016 | validation | simple | Run final validation |

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
- Strict policy mode that rejects dynamic URLs, missing checksums, HTTP URLs,
  parent env inheritance, and sudo symlinks unless explicitly overridden.
- ARM64 platform support.
- `binstaller update --write-config` for bumping pinned versions.
- Rich TUI is the next active phase and is tracked by T024-T027.
- Parallel downloads with bounded concurrency.
- Per-tool uninstall command.
- Path export helper that prints shell snippets for adding tool bins to `PATH`.
