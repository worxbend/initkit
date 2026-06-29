# Binstaller TUI Refactor Plan

Date: 2026-06-30

This plan supersedes the older `plan --tui` / `apply --tui` approach.
The TUI must become a separate interactive application command:

```bash
binstaller tui --config config.example.yaml
./mill app.run tui --config config.example.yaml
```

Default CLI commands remain script-friendly:

```bash
binstaller plan --config config.example.yaml
binstaller apply --config config.example.yaml --dry-run
binstaller apply --config config.example.yaml --yes
```

The TUI command owns interactive selection, dry-run preview, apply execution,
logs, details, and error display inside the interface. `plan --tui` and
`apply --tui` are transitional code paths and should be removed or deprecated
as part of this refactor.

## Current Code State

- The active module graph is `app -> cli -> {core, tui}`, `tui -> core`,
  `core -> config`.
- The current TUI exists in a dedicated `tui` module and is reachable through
  the first-class `tui` command.
- The current TUI already has deterministic rendering, focusable panes,
  scrollable details/logs, static fallback for non-interactive shells, terminal
  cleanup, resize handling, and tests.
- Core exposes renderer-agnostic plan/apply events and should remain UI-agnostic.
- The TUI now owns one unified app state for browsing, checkbox selection,
  filtering, plan preview, dry-run, confirmed apply, logs, and modals. Remaining
  work is documentation/review/README closure and final validation.

## Implementation Progress

- 2026-06-30: T001 added first-class `binstaller tui` routing, removed the
  transitional `--tui` flags from `plan` and `apply`, and kept `plan`, `apply`,
  `versions`, and `lock` on non-interactive service-backed paths. The new
  command currently starts the existing planning TUI frame; later tasks move
  selection and plan/dry-run/apply actions inside a unified TUI app state.
- 2026-06-30: T002 introduced a unified pure `TuiAppState` for browsing mode,
  header metadata, resolved entries, TUI-local selected tool names, focus,
  filter, modal, logs, and optional execution state. The planning renderer and
  session now derive from that state, and TUI selection converts to core
  `ToolSelection` only through a boundary helper.
- 2026-06-30: T003 rendered checkbox state in plan rows and added persistent
  TUI-local selection transitions for Space, select-all-visible, clear-visible,
  and invert-visible. Selection counts refresh in the header after each change,
  and hidden selections survive filtering.
- 2026-06-30: Validation iteration 26 passed the configured checkpoint after
  T001-T003: focused tests, recursive compile/test, scalafmt, Mill resolution,
  first-class `tui` help/config smokes, non-interactive plan/apply/version/lock
  smokes, and `git diff --check`. No code fixes were needed. Remaining risks
  are environment-bound: live raw-terminal TUI behavior still needs a real TTY,
  and local native-image validation is blocked because `native-image` is not on
  `PATH`.
- 2026-06-30: T004 completed browsing controls by wiring `Enter` to selected
  entry details and `l` to log focus through `TuiAppController`, keeping the
  existing focus cycling, row movement, scrolling, filtering, help modal, and
  quit/cleanup paths on the unified state model. Deterministic TUI tests and
  the non-interactive `tui --config config.example.yaml` smoke passed.
- 2026-06-30: Validation iteration 28 passed the T005 checkpoint after
  T001-T004: focused tests, recursive compile/test, scalafmt, Mill resolution,
  first-class `tui` help/config smokes, non-interactive app smokes, and
  `git diff --check`. The static `tui --config config.example.yaml` smoke
  rendered a frame and clear non-interactive message. Local blockers remain
  environment-bound: `native-image` is not on `PATH`, and stdin is not a live
  TTY in this agent shell.
- 2026-06-30: T006 wired the internal `p` action for selected-entry plan
  preview. The TUI now converts its local selection to core `ToolSelection` at
  the action boundary, appends selected plan output to logs, opens a visible
  no-selection modal, and preserves filter/focus/details/selection state. The
  focused checks `core.test`, `tui.test`, recursive compile, scalafmt check,
  JSON validation, and `git diff --check` passed.
- 2026-06-30: T007 wired the internal `d` action for selected-entry dry-run
  apply. The TUI now converts selected entries to core `ToolSelection`, runs
  `applyWithEvents` with `DryRunMode.Enabled`, stores the completed
  `ExecutionTuiState`, renders execution as the primary view after `d`, keeps
  recent logs and the final summary visible, and opens the shared
  no-selection modal without calling apply when nothing is selected. The
  focused checks `core.test`, `tui.test`, `app.run tui --config
  config.example.yaml`, recursive compile, scalafmt check, JSON validation,
  and `git diff --check` passed.
- 2026-06-30: T008 wired the internal `r` action for confirmed real apply.
  Pressing `r` now opens an in-TUI confirmation modal before any non-dry-run
  apply call is made; only `Enter` in that modal calls `applyWithEvents`.
  Escape or `n` closes the modal without writes. Confirmed apply converts only
  selected TUI entries to core `ToolSelection`, sets `DryRunMode.Disabled` and
  `ApplyConfirmation.Enabled`, and preserves the existing option fields for
  state, reset-state, lock validation, and other core gates. Focused core,
  CLI, TUI, compile, scalafmt, JSON, and whitespace checks passed.
- 2026-06-30: Validation iteration 32 passed the T009 broad checkpoint after
  T006-T008 internal TUI actions: focused tests, recursive compile/test,
  scalafmt, Mill resolution, first-class `tui` help/config smokes, static
  plan/apply/version/lock smokes, and `git diff --check`. No code
  fixes were needed. The static `tui --config config.example.yaml` smoke
  rendered a frame and clear non-interactive message. Local blockers remain
  environment-bound: `native-image` is not on `PATH`, and stdin is not a live
  TTY in this agent shell.
- 2026-06-30: T010 implemented structured TUI failure modals and root-cause
  details. Invalid config/resolution failures now render a visible startup
  error screen, failed plan/dry-run/apply results open sanitized error modals
  and append the same bounded details to logs, failed execution rows can reopen
  a root-cause modal with `Enter`, and terminal open/render failures return a
  terminal failure screen while still closing the terminal boundary. Focused
  core, CLI, TUI, recursive compile, scalafmt, JSON, whitespace, and static
  `tui --config config.example.yaml` checks passed.
- 2026-06-30: T011 polished the TUI workspace layout. The header now exposes
  profile/manifest, config/state paths, host, mode chip, action mode, and
  selected/total count; plan rows render compact risk badges; details include
  URL/final-URL/provenance, checksum status, archive mappings, symlinks, sudo
  risk, and dry-run preview; execution frames show activity/progress,
  indeterminate progress, bytes, elapsed time, logs, result rows, and summary.
  Snapshot/model tests now cover dense normal-width output and narrow clipping.
  Focused TUI tests, static `tui --config config.example.yaml`, recursive
  compile, scalafmt check, JSON validation, and whitespace checks passed.
- 2026-06-30: T012 hardened and revalidated terminal lifecycle behavior for
  the first-class `tui` command. Tests now cover non-interactive browsing
  fallback without raw mode, terminal open failure, normal close, cleanup after
  render failure, modal close, `q`, Ctrl+C, resize-driven layout bounds in
  browsing and execution views, and direct `stty` argv usage without shell
  strings. The manual smoke guide now uses `./mill app.run tui --config ...`
  and includes real-terminal startup, resize, quit, Ctrl+C, modal close, and
  cleanup checks. Focused TUI tests, static `tui --config config.example.yaml`,
  recursive compile, scalafmt check, JSON validation, and whitespace checks
  passed.
- 2026-06-30: Validation iteration 36 passed the T013 broad checkpoint after
  T010-T012 modal/root-cause UI, layout polish, and terminal lifecycle
  hardening. Focused config/core/CLI/TUI tests, recursive compile/test,
  scalafmt, Mill resolution, first-class `tui` help/config smokes,
  non-interactive plan/apply/version/lock smokes, and `git diff --check`
  passed without production or test source fixes. Local blockers remain
  environment-bound: `native-image` is not on `PATH`, and stdin is not a live
  TTY for the manual raw-terminal smoke.
- 2026-06-30: T014 updated the architecture, TUI guide, manual TUI smoke,
  security, and testing docs for the first-class `binstaller tui` command. The
  docs now describe TUI-local selection, filtering, internal `p`/`d`/`r`
  actions, confirmation and failure modals, modal/log redaction, terminal
  control scrubbing, selected-entry execution guarantees, terminal lifecycle
  expectations, deterministic TUI tests, and the static non-interactive
  `./mill app.run tui --config config.example.yaml` smoke. Targeted docs checks
  passed: `git diff --check`, `./mill app.run tui --help`, and
  `./mill app.run tui --config config.example.yaml`.
- 2026-06-30: T015 added the mandatory first-class TUI review document covering
  command boundaries, TUI control flow, modal rendering, selection guarantees,
  security risks, test evidence, and documented deferrals. README now documents
  `binstaller tui --config config.example.yaml`, keeps `plan`,
  `apply --dry-run`, `apply --yes`, `versions`, and `lock` as non-interactive
  command paths, and no longer presents `plan --tui` or `apply --tui` as the
  TUI path. Release docs and native release smokes were also migrated to the
  first-class `tui --config` smoke. Targeted CLI/TUI tests, app help smokes,
  JSON validation, stale-doc scan, and `git diff --check` passed.
- 2026-06-30: Validation iteration 39 completed T016 final validation for the
  first-class TUI refactor. Focused config/core/CLI/TUI tests, recursive
  compile/test, scalafmt check, Mill resolution, app help/config smokes,
  first-class static `tui --config config.example.yaml`, `git diff --check`,
  and JSON validation all passed. `plan --help` and `apply --help` logs were
  scanned and contain no `--tui`. Local blockers remain environment-bound:
  `native-image` is not on `PATH` (`command -v native-image` exits 1), and
  stdin is not a live TTY (`test -t 0` exits 1), so live raw-terminal smoke
  remains a documented manual/interactive-environment check.
- 2026-06-30: Validation iteration 40 re-ran the checkpoint validation after
  T016. JSON validation, focused config/core/CLI/TUI tests, recursive
  compile/test, scalafmt check, Mill resolution, app help/config smokes,
  first-class static `tui --config config.example.yaml`, and `git diff --check`
  all passed with no source fixes. `plan --help` and `apply --help` logs still
  contain no `--tui`. The only remaining blockers are environment-bound:
  `native-image` is absent from `PATH`, and stdin is not a live TTY for the
  manual raw-terminal smoke.

## Product Target

`binstaller tui` is an interactive installer workspace.

The user should be able to:

- Load one manifest and inspect all resolved entries.
- Toggle selected entries with checkboxes.
- Select all, clear all, invert selection, filter, and inspect details.
- Choose `Plan`, `Dry Run`, or `Apply` from inside the TUI.
- Run dry-run preview without leaving the TUI.
- Run real apply from inside the TUI after confirmation.
- See active execution as a focused progress screen.
- See failures in modal dialogs with root-cause details and suggestions.
- Return from modals to the same TUI state.
- Keep logs visible, focusable, larger, and scrollable.
- See the config path and state-file path in the header at all times.

## UX Requirements

### Command Model

Add a first-class subcommand:

```bash
binstaller tui --config FILE [--state FILE] [--reset-state] [--verbose]
```

Do not require these flags for normal interactive use:

- `--dry-run`
- `--yes`
- `--only`
- `--skip`
- `--tui`

Inside the TUI, users choose selected entries and action mode.

Keep `--only` and `--skip` for non-interactive `plan` and `apply`; do not
make them the primary TUI selection mechanism. If supported on `tui`, they
should only initialize the checkbox state.

### Layout

Use a dense, developer-tool terminal UI inspired by Tamboui widget demos and
the provided Posting-style screenshot.

Required layout:

- Header:
  - app name/version;
  - profile/manifest name;
  - config path;
  - state-file path;
  - host summary;
  - current action mode;
  - selected count and total count.
- Left pane:
  - tree/table/list of plan entries;
  - checkbox per entry;
  - status/risk badges;
  - current row highlight;
  - grouping by status, source type, or install phase when useful.
- Right/top detail pane:
  - selected entry details;
  - version, URL, final URL/provenance, checksum status;
  - archive mappings;
  - symlinks and sudo risk;
  - dry-run operation preview for that entry.
- Logs pane:
  - larger than previous versions;
  - focusable;
  - scrollable with visible scrollbar;
  - shows resolver logs, dry-run lines, apply events, and root-cause snippets.
- Execution view:
  - when running dry-run/apply, do not keep the plan table as the primary view;
  - render current tool, phase, spinner/wavetext, progress bar, bytes, elapsed
    time, recent logs, completed rows, failed rows, skipped rows, and summary.
- Footer/keybar:
  - concise shortcuts;
  - current focus;
  - current mode;
  - transient status messages.

### Styling

Style direction:

- Deep navy/black background.
- Purple/magenta borders, active tabs, and selection accents.
- Cyan activity/progress accents.
- Green completed states.
- Red failed states.
- Yellow warning/confirmation/sudo/strict-policy states.
- Muted gray inactive rows.
- Pale monospace foreground.
- Active selection uses a filled highlight band plus bright foreground.
- Use compact tabs, badges, mode chips, and status strips.

Tamboui references are style and interaction references only unless a later
implementation explicitly validates Tamboui as a runtime dependency. Borrow the
gist of:

- `Table`: plan entries, results, skipped rows.
- `Scrollbar`: logs and details panes.
- `Spinner`: active operation with unknown percent.
- `Wavetext`: resolving/loading/working banners.
- `Canvas`: subtle header/progress accents only if they improve clarity.

Do not turn the first screen into a landing page. It must be the usable
installer workspace.

### Interaction

Required keybindings:

- `Tab`: focus next pane.
- `Shift+Tab` or `b`: focus previous pane.
- `Up` / `Down`: move current list row or scroll focused text pane.
- `PageUp` / `PageDown`: page selection or logs/details.
- `Home` / `End`: jump to start/end.
- `Space`: toggle selected entry checkbox.
- `a`: select all visible entries.
- `c`: clear all visible entries.
- `i`: invert visible selection.
- `/`: filter entries.
- `Enter`: open details or confirm modal action.
- `p`: run plan preview for selected entries.
- `d`: run dry-run apply for selected entries.
- `r`: run real apply for selected entries, after confirmation modal.
- `l`: focus logs.
- `?`: help modal.
- `q` or `Ctrl+C`: quit, restoring terminal state.

Mouse support is desirable but secondary. Keyboard behavior must be complete.

### Selection Model

The TUI owns a persistent selection state:

- Each plan entry has a checkbox.
- Selection state survives focus changes, filtering, detail view, modals, and
  dry-run preview.
- Filtering does not lose hidden selections.
- Header shows `selected N / total M`.
- Apply/dry-run operates on selected entries only.
- If no entries are selected and the user runs dry-run/apply, show a modal
  explaining that at least one entry must be selected.

Selection state should be independent from CLI `ToolSelection`, but converted
to `ToolSelection` before invoking core.

### Modal Requirements

The TUI must support modal dialogs:

- Help modal.
- Confirmation modal before real apply.
- Error modal for validation, resolution, dry-run, apply, lock, state, or
  terminal errors.
- Root-cause modal for failed tool details.

Error modal contents:

- top-level failure category;
- affected tool/action/path when available;
- root cause;
- safe command/download/path/checksum context;
- bounded stdout/stderr snippets when available;
- redacted sensitive values;
- suggested next action when known;
- key hints: close, copy/save later if implemented, jump to logs.

Errors must not be rendered only in logs. A failed action should visibly open a
modal and also append log lines.

## Refactor Architecture

### CLI Layer

Change `cli` module:

- Add `tui` as a first-class picocli subcommand.
- Remove `--tui` flags from `plan` and `apply`, or keep temporary deprecated
  aliases only during migration with tests marking them for deletion.
- `plan` and `apply` remain non-interactive and script-friendly.
- `tui` command builds `InstallerOptions` and calls `TuiModule.start(...)`.
- TUI command should accept global config/state/reset/verbose options and
  optional initial selection flags only if useful.

Target command surface:

```text
binstaller plan      # non-interactive plan
binstaller apply     # non-interactive apply
binstaller versions  # non-interactive versions
binstaller lock      # non-interactive lock
binstaller tui       # interactive TUI application
```

### TUI Module

Refactor `tui` module around an application state machine.

Suggested model:

```scala
final case class TuiAppState(
    header: TuiHeader,
    entries: Vector[TuiEntry],
    selection: TuiSelectionState,
    focus: TuiFocus,
    filter: TuiFilter,
    mode: TuiMode,
    modal: Option[TuiModal],
    logs: TuiLogBuffer,
    execution: Option[TuiExecutionState]
)
```

Core concepts:

- `TuiMode`: browsing, planning, dryRunReady, applying, completed.
- `TuiAction`: runPlan, runDryRun, runApply, toggleSelection, focusPane,
  scroll, filter, openModal, closeModal.
- `TuiModal`: help, confirmApply, error, rootCause.
- `TuiSelectionState`: selected entry names plus visible-filter helpers.
- `TuiRenderer`: pure deterministic render from model to frame.
- `TuiController`: consumes input and updates state.
- `TuiRunner`: owns terminal lifecycle and invokes core.

Keep rendering pure where possible. Side effects belong in command/service
boundaries, not in render functions.

### Core Integration

Core should stay independent from the TUI.

If current events are not enough, add renderer-agnostic events rather than
TUI-specific callbacks.

Required core capabilities:

- Resolve plan snapshot without writes.
- Render/apply dry-run for selected entries without writes.
- Apply selected entries with structured events.
- Return typed errors with root-cause details and suggestions.
- Preserve state/resume semantics for selected entries.

Do not add TUI concepts to core data types. Convert TUI selection to existing
selection/options at the boundary.

## Implementation Phases

### TUI-R001 - Command Refactor

Deliverables:

- Add `binstaller tui`.
- Remove or deprecate `plan --tui` and `apply --tui`.
- Update help output.
- Update docs references to the new command.
- Add CLI tests for command routing.

Acceptance checks:

- `./mill app.run tui --help` shows TUI command help.
- `./mill app.run plan --help` no longer advertises `--tui` after migration.
- `./mill app.run apply --help` no longer advertises `--tui` after migration.
- Existing non-interactive commands still work.

### TUI-R002 - Application State And Selection

Deliverables:

- Add TUI state model with checkbox selection.
- Render checkboxes in the plan list.
- Implement selection toggles: single, all, clear, invert.
- Preserve selection across filters and focus changes.

Acceptance checks:

- Unit tests verify checkbox rendering.
- Unit tests verify selection survives filtering.
- Header shows selected/total count.

### TUI-R003 - Internal Actions: Plan, Dry Run, Apply

Deliverables:

- Add internal action dispatch from TUI keybindings.
- `p` renders plan/details/logs for selected entries.
- `d` runs dry-run apply for selected entries.
- `r` opens confirmation modal and then runs real apply for selected entries.
- No-selection action opens an error modal.

Acceptance checks:

- Dry-run action performs no filesystem writes.
- Apply action requires explicit confirmation modal.
- Selected entries map correctly to core selection.
- Result summary remains visible after action completes.

### TUI-R004 - Error Modals And Root Cause UI

Deliverables:

- Implement error modal model and renderer.
- Convert validation/resolution/apply errors into modal content.
- Add root-cause detail modal for failed tools.
- Append errors to logs as well as showing modal.

Acceptance checks:

- Invalid config opens an error modal.
- Failed dry-run/apply opens an error modal with root cause and suggestion.
- Sensitive values are redacted.
- Terminal-control characters are scrubbed.

### TUI-R005 - Styling And Layout Polish

Deliverables:

- Apply Posting/Tamboui-inspired styling.
- Make logs pane larger and clearly focusable.
- Add scrollbars to logs/details.
- Add mode chips and status badges.
- Improve execution view with spinner/wavetext-like loading and progress bars.
- Ensure narrow terminal layout does not overlap text.

Acceptance checks:

- Snapshot/model tests cover normal and narrow terminal widths.
- Logs/details scrollbars render when content overflows.
- Active pane and active selection are obvious.
- Completed rows are green; failed rows are red.

### TUI-R006 - Terminal Lifecycle And Input Hardening

Deliverables:

- Revalidate alternate-screen/raw-mode cleanup.
- Revalidate `q`, Ctrl+C, modal close, and failure cleanup paths.
- Revalidate resize handling.
- Revalidate non-interactive fallback behavior.

Acceptance checks:

- Tests cover terminal open failure.
- Tests cover normal close and failure close.
- Non-interactive `binstaller tui` renders static fallback or clear message.
- Manual smoke guide updated.

### TUI-R007 - Review, Hardening, And Documentation

This is mandatory because the refactor changes command shape and TUI control
flow.

Deliverables:

- Code review document under `docs/`.
- Security review of new TUI command boundaries and modal rendering.
- Tests for command migration and error modal redaction.
- Update architecture docs.
- Update TUI guide and smoke guide.
- Update README last.

Acceptance checks:

- `./mill config.test`
- `./mill core.test`
- `./mill cli.test`
- `./mill tui.test`
- `./mill __.compile`
- `./mill __.test`
- `./mill mill.scalalib.scalafmt/checkFormatAll`
- `git diff --check`
- App smokes:

```bash
./mill app.run --help
./mill app.run tui --help
./mill app.run plan --config config.example.yaml
./mill app.run apply --config config.example.yaml --dry-run
./mill app.run tui --config config.example.yaml
```

Live raw-terminal TUI smoke must be run manually from a real terminal emulator.

## Security And Hardening Requirements

- TUI must not execute shell strings.
- TUI selection must not bypass existing policy, state, checksum, strict mode,
  lock, sudo, or confirmation gates.
- TUI modal rendering must scrub terminal control characters.
- TUI modal rendering must redact sensitive env-derived values.
- TUI logs must not leak secrets.
- Real apply must require an in-TUI confirmation modal.
- Sudo symlink operations must remain explicit and highlighted.
- State-file path must remain visible before execution.
- Dry-run/apply must operate only on selected entries.
- Hidden filtered entries must not accidentally run unless selected.
- On any failure, terminal state must be restored.

## Code Quality Requirements

- Keep `core` UI-agnostic.
- Keep TUI state transitions testable without a real terminal.
- Keep render functions deterministic and side-effect free.
- Add ScalaDoc to public TUI state/controller/runner APIs.
- Add comments for non-obvious terminal lifecycle, modal, selection, and
  security invariants.
- Avoid long render/controller functions; split layout, input handling, modal
  rendering, and action dispatch into focused units.
- Keep explicit public return types.
- Preserve existing CLI behavior unless intentionally changed by this plan.

## Documentation Order

Update docs in this order:

1. Architecture docs for new `tui` command and module responsibilities.
2. TUI guide with command, keybindings, selection, dry-run/apply actions, and
   modals.
3. TUI smoke workflow with real-terminal steps.
4. Security docs for TUI modal/log rendering and selection guarantees.
5. Testing docs for TUI state/render/input tests.
6. README last, after implementation, review, and validation are complete.

README must not be treated as complete until the command refactor, hardening,
tests, and developer docs are done.

## Agent Loop Tasks

The authoritative resumable implementation queue is stored in
`.agent-loop/tasks.json`. The active pending queue for this TUI refactor is:

| Task | Type | Complexity | Title |
| --- | --- | --- | --- |
| T001 | feature | complex | Add tui subcommand (completed 2026-06-30) |
| T002 | feature | complex | Introduce TUI app state |
| T003 | feature | moderate | Render checkbox selection |
| T004 | feature | moderate | Complete browsing controls |
| T005 | validation | simple | Checkpoint command browsing |
| T006 | feature | moderate | Wire selected plan action |
| T007 | feature | complex | Wire selected dry run (completed 2026-06-30) |
| T008 | feature | complex | Wire confirmed apply (completed 2026-06-30) |
| T009 | validation | simple | Checkpoint TUI actions |
| T010 | feature | complex | Implement error modals |
| T011 | improvement | complex | Polish TUI layout |
| T012 | fix | moderate | Harden terminal lifecycle |
| T013 | validation | simple | Checkpoint modal layout hardening |
| T014 | chore | moderate | Update TUI docs |
| T015 | chore | moderate | Complete review and README |
| T016 | validation | simple | Run final validation |

## Verification Strategy

Use the checked-in Mill launcher. The default validation command set is written
to `.agent-loop/config.json` and includes focused module tests, recursive
compile/test, scalafmt checks, Mill task resolution, app-level non-interactive
smokes, the first-class `tui` static smoke, and `git diff --check`.

Live raw-terminal TUI smoke and native-image builds are not automatic in the
agent shell. Validation iterations should record whether stdin is a TTY and
whether `native-image` is available before treating those checks as blocked.
