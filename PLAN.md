# Initkit TUI Excellence Plan

Date: 2026-06-30

This file is intentionally reset. It contains only the current product goal:
make `initkit tui` / `binstaller tui` a best-in-class terminal UI, inspired by
Ratatui showcase apps, opencode-style developer tools, and polished modern TUI
workflows.

## Goal

Build a beautiful, fast, colorful, ergonomic TUI that feels like a serious
developer workstation installer, not a plain text report.

The TUI should be:

- Table-first and easy to scan.
- Colorful, with tasteful use of `fansi` colors and emoji.
- Animated where it helps: spinners, progress bars, loading/wave text, active
  row indicators, and status transitions.
- Keyboard-first, predictable, and responsive.
- Safe for real install operations: confirmation, clear errors, root-cause
  details, and visible state/config paths.
- Production-grade Scala: readable structure, good naming, tests, comments,
  Scaladoc where useful, and strict formatting.

## Visual Direction

Use these references for style and interaction quality:

- Ratatui showcase apps: polished panes, rich table states, strong focus
  styling, tasteful color accents, good keyboard command bars.
- opencode-style TUI: developer-focused layout, dense information, active
  status, useful side/details panels, and strong terminal ergonomics.
- Tamboui widget demos for design ideas only:
  - Canvas
  - Scrollbar
  - Spinner
  - Table
  - Wavetext for loading

Do not copy web/docs code blindly. Use the references to guide the terminal
look and UX expectations.

## Primary UX Contract

The latest hand-drawn sketch is the primary UX contract. Ratatui/opencode/
Tamboui references define polish and quality, but the screen structure must
follow the sketch first.

Default screen structure:

```text
┌──────────────────────────────────────────────────────────────┐
│ #   name        version      checksum        status          │
│ [x] yazi        0.01         no-checksum     installed       │
│ [x] minikube    2.4.4        aasdasdasd      not installed   │
│ [x] xplr        2.4.4        asdasdasdasd    not installed   │
│ [x] kind        2.4.4        asdasdasdasd    not installed   │
│ [x] nvim        0.01         no-checksum     installing ███░ │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│ info bar                                                     │
│ description, logs, plan preview, or error output             │
└──────────────────────────────────────────────────────────────┘

p plan | d dry-run | r apply | tab focus | l logs | space select | a select all
```

The default screen must be a simple two-panel workspace:

- Top panel: selectable table.
- Bottom panel: info bar. It shows selected-entry details by default, logs
  when log focus is active, plan/dry-run output when requested, and error
  output when a failure occurs.
- Bottom-most line: compact legend/command bar.

Avoid returning to a crowded dashboard layout. Do not show multiple competing
sidebars, duplicate footers, or verbose status blocks on the default screen.

## Target Table

Required table columns:

- Checkbox selected state.
- Name.
- Version.
- Checksum state.
- Status.

Column behavior:

- Checkbox is the first column and is the main selection affordance.
- The active row should be visibly highlighted.
- The name column must stay compact.
- The status column owns install state, warnings, and the per-entry progress
  bar.
- During execution, update the current entry in place; when it completes, leave
  that row finalized and move progress to the next row.
- Every installing entry must display its progress bar in its own table row
  whenever the table is visible.
- Only if the terminal is too narrow to render any readable row progress, keep
  the candidate list in order and render the active candidate's progress in the
  lower info bar, clearly tied to the active row.

The table behavior must support:

- `Space`: toggle current row.
- `a`: select/deselect all visible rows.
- Arrow keys: move active row.
- Page/Home/End navigation.
- `/`: filter rows.
- `Tab`: switch focus between main table, details/logs/info pane, and command
  areas.
- `p`: show plan preview for selected entries.
- `d`: run dry-run for selected entries.
- `r`: confirm and apply selected entries.
- `l`: focus logs.
- `?`: help.
- `q` / Ctrl+C: quit with terminal cleanup.

Header metadata should be visible but must not dominate the sketch layout.
Use a compact header strip or short metadata lines above the table for:

- App name/version.
- Current mode/action.
- Manifest/profile name.
- Selected count and total count.
- Config path.
- State file path.
- Host summary.
- Filter state.

The footer must be one compact command legend, for example:

```text
p plan | d dry-run | r apply | tab focus | l logs | space select | a toggle all | q quit
```

Do not repeat the same shortcuts in multiple footer lines.

## Execution UX

When running plan, dry-run, or apply:

- Do not replace the user experience with a noisy static list.
- Keep the main table visible when useful, and update the active entry status
  inline.
- The active installing row must show a spinner and progress bar in the status
  column.
- Row-level progress is the required default. If a row-level progress bar is
  not readable because of extreme terminal width constraints, use a fallback
  execution layout that still shows the ordered candidate list and renders one
  in-place progress bar for the active candidate in the lower info bar.
- Completed rows should turn green and remain readable.
- Failed rows should turn red and be selectable for root-cause details.
- Skipped rows should be subdued.
- Download progress should update in place, not print a new line per tick.
- Progress bars should be visually rich: filled/empty segments, percentage,
  byte counts when known, and indeterminate animation when total size is
  unknown.
- At completion, show a concise colored summary with totals for completed,
  skipped, failed, interrupted, remaining, and elapsed time.

## Password And Privilege UX

The TUI must handle privileged operations inside the interface.

- When a command needs a password, show a focused password modal.
- The password input must be masked and must never be printed, logged,
  persisted, included in errors, or exposed in command previews.
- The modal should explain which operation is requesting elevated privileges.
- The user must be able to cancel the password prompt safely.
- Cancellation should fail or interrupt only the current privileged operation
  with a clear message.
- Prefer an explicit privilege boundary such as a controlled askpass flow or
  equivalent TUI-mediated input path instead of letting `sudo` take over the
  terminal unexpectedly.
- Preserve compatibility with systems where existing sudo credentials are
  already cached and no prompt is needed.
- Tests must cover password modal rendering, cancel behavior, redaction, and
  no leakage into logs/errors.

## Logs, Details, And Errors

The TUI must provide a strong logs/details experience:

- The lower info bar is the default home for details, logs, plan preview,
  dry-run output, and error output.
- Logs view inside the info bar must be larger than before and focusable.
- When logs are focused, scrolling must work with keyboard and mouse wheel.
- Details view must show selected entry metadata without overwhelming the
  default screen.
- Error output should appear in the lower info bar first, with an optional
  modal for full root-cause detail.
- Error details must include root cause, command/action, exit code, stderr,
  stdout excerpt, duration, environment hints, and practical suggestions when
  possible.
- Failed table rows should reopen the root-cause modal with `Enter`.
- Errors and logs must be redacted using existing redaction rules.

## Style Requirements

Use terminal styling deliberately:

- Use colors for semantic state:
  - Green: completed/success.
  - Red: failed/error.
  - Yellow: warning/risk/missing checksum.
  - Cyan/blue: active/focus/current action.
  - Gray: skipped/inactive/secondary text.
- Use emoji/icons where they improve scanning:
  - Package/app marker in header.
  - Check/warning/error/status markers in rows.
  - Spinner/progress/activity markers during execution.
- Avoid broken ANSI rendering. Never measure, pad, or truncate strings after
  embedding ANSI escape sequences.
- Keep columns compact; avoid excessively wide name columns.
- Keep text inside panels clipped safely with ellipsis.
- Preserve a good layout at narrow and wide terminal sizes.

## Responsive Resize Requirements

Use the Tamboui core concepts as design guidance for resize behavior:
<https://tamboui.dev/docs/main/core-concepts.html>

- Treat rendering as immediate-mode: each frame is derived from current app
  state and the current terminal area.
- Recompute layout on every render and every resize event.
- Split the available area into explicit rectangles for header, table,
  info/error bar, and footer.
- Use constraints instead of fixed coordinates: minimum sizes for essential
  areas, fill/percentage sizing for flexible areas, and safe clipping when
  space is limited.
- Keep widget state outside rendering functions: selected row, focus, scroll
  offsets, filter text, active execution row, modal state, and progress state
  must survive resize.
- Use deterministic tick/animation frames for spinners, progress animation, and
  loading/wave text.
- Support keyboard and mouse input after resize without resetting focus.
- On very small terminals, degrade gracefully: compact header, compact table,
  one-line legend, and lower info bar with clipped/scrollable content.

## Architecture Requirements

Keep the TUI maintainable and testable:

- Core installer logic remains UI-agnostic.
- The TUI owns interaction state, selection state, modal state, focus, scroll,
  and execution rendering.
- Convert TUI selections to core selections only at service/action boundaries.
- Keep rendering deterministic for tests.
- Separate state transitions from rendering.
- Keep terminal side effects isolated behind terminal/backend traits.
- Model resize as a normal input event that updates viewport and causes a full
  redraw from preserved state.
- Avoid shell injection and unsafe command string handling in terminal control
  paths.
- Add comments or Scaladoc where behavior is non-obvious, especially around
  terminal lifecycle, rendering safety, ANSI handling, selection semantics, and
  event-driven execution state.

## Implementation Phases

### 1. UX Audit

- Capture the current TUI output in normal, narrow, and execution states.
- Identify visual noise, duplicated text, poor spacing, weak focus cues, and
  non-working navigation.
- List all keyboard interactions and verify whether each actually works.
- Review current renderer boundaries for ANSI measurement bugs.

### 2. Visual System

- Define shared color/status helpers.
- Define reusable panel, table, progress bar, spinner, scrollbar, and footer
  rendering helpers.
- Make table widths responsive and compact.
- Add safe text measurement/truncation before applying color.
- Add deterministic animation frames for tests.

### 3. Responsive Layout And Resize

- Build layout from the current terminal area on every frame.
- Define header, table, info/error bar, and footer rectangles using constraints.
- Preserve focus, selection, scroll offsets, modals, active row, and progress
  state across resize.
- Add normal, narrow, and tiny terminal snapshot tests.
- Verify mouse wheel and keyboard input still target the correct focused
  widget after resize.

### 4. Table-First Browsing

- Refine the default browsing screen to match the sketch: top table, lower
  info bar, bottom legend.
- Make selected/current/focused rows visually distinct.
- Keep details/logs/preview/errors in the lower info bar.
- Ensure config and state paths stay visible in the header.
- Make footer shortcuts accurate and non-duplicated.

### 5. Execution Experience

- Render active execution progress inline in the installing entry's table row.
- Treat row-level progress as mandatory for normal terminals.
- Add the narrow-terminal fallback only for extreme widths: ordered candidate
  list plus one in-place active progress bar in the lower info bar.
- Add animated spinner and progress bar states.
- Render completed/failed/skipped status transitions with color.
- Keep logs accessible during execution.
- Render final summary with color and concise totals.

### 6. Password And Privilege Flow

- Design a password modal for privileged operations.
- Ensure password input is masked, redacted, and never stored.
- Route privilege prompts through TUI-controlled input instead of raw `sudo`
  terminal takeover where possible.
- Handle cached sudo credentials without showing a prompt.
- Add cancellation and failure states with clear messages.

### 7. Error Experience

- Improve CLI and TUI root-cause rendering.
- Add practical suggestions where possible.
- Make TUI error modals readable, scrollable, and redacted.
- Ensure failed rows can reopen detailed diagnostics.

### 8. Keyboard And Focus

- Verify `Tab`, `Enter`, `l`, `/`, arrows, PageUp/PageDown, Home/End, mouse
  wheel, `Space`, `a`, `p`, `d`, `r`, `?`, `q`, and Ctrl+C.
- Make logs and details focus obvious.
- Keep focus stable after filtering, modal close, resize, and execution
  completion.

### 9. Code Quality Pass

- Refactor large renderer/state methods into clear helpers where it improves
  readability.
- Add Scaladoc/comments for public and complex internal APIs.
- Review naming, data flow, and test coverage.
- Remove dead transitional code and stale comments.
- Keep formatting clean with scalafmt.

### 10. Validation

Required checks:

```bash
./mill tui.test
./mill cli.test
./mill __.compile
./mill mill.scalalib.scalafmt/checkFormatAll
./mill app.run tui --config config.example.yaml
git diff --check
```

Manual checks in a real terminal:

- Open TUI.
- Resize terminal.
- Resize during active install/progress.
- Resize while password modal, help modal, logs, and error output are visible.
- Move through rows.
- Toggle one row.
- Toggle all.
- Filter.
- Focus details.
- Focus logs.
- Scroll logs.
- Open help.
- Run plan preview.
- Run dry-run.
- Confirm/cancel apply modal.
- Trigger or inspect an error modal.
- Quit with `q`.
- Quit with Ctrl+C.

## Acceptance Criteria

The work is complete when:

- The default UX follows the sketch: top selectable table, lower info/error
  bar, and bottom command legend.
- The TUI looks polished, colorful, and modern in a real terminal.
- The default screen is a clean table-first installer workspace.
- The TUI is fully resizable: layout recomputes from current terminal area,
  state is preserved, and no panels overlap or disappear incoherently.
- Logs are focusable, larger, scrollable, and rendered through the info bar.
- Each installing entry displays an in-row progress bar while it is running.
- Execution progress updates in place with animation.
- Narrow terminals still show an ordered candidate list and one clearly tied
  active progress bar.
- Password prompts are handled through a masked TUI modal and never leak into
  logs, errors, previews, state, or tests.
- Errors are actionable and visible in both CLI and TUI.
- Header always shows config and state paths.
- The renderer has no visible ANSI escape artifacts.
- Tests cover deterministic render output, selection behavior, focus behavior,
  progress states, error modals, and terminal cleanup.
- Code is maintainable Scala with clear structure and useful comments.

## Agent Loop Tasks

The agent loop should continue from the current first-class `binstaller tui`
baseline and close the remaining gaps in this order:

1. T001 Audit current TUI gaps.
2. T002 Seed execution rows.
3. T003 Enrich progress rendering.
4. T004 Checkpoint execution rendering.
5. T005 Introduce privilege boundary.
6. T006 Add password modal.
7. T007 Checkpoint privilege flow.
8. T008 Polish logs and errors.
9. T009 Harden responsive resize.
10. T010 Checkpoint responsive UI.
11. T011 Update TUI documentation.
12. T012 Run final validation.

## Progress Updates

- 2026-06-30: Validation iteration 43 completed T001, the current TUI gap
  audit. The current static browsing frame renders the target two-panel shape:
  compact header, selectable checkbox table, lower focused info/details panel,
  and one command legend. Narrow browsing/execution behavior is covered by
  deterministic width-bound tests at 30-32 columns, and execution currently
  renders completed/failed/skipped rows plus the active row as events arrive.
  No production behavior was changed.
- T001 mapped the remaining implementation gaps to the active task queue:
  T002 for ordered pre-seeded execution rows and failed-row focus, T003 for
  richer quieter progress/status rendering, T005-T006 for privileged password
  mediation, T008 for logs/error info-bar polish, T009 for broader responsive
  and future password-modal resize coverage, T011 for documentation updates,
  and T012 for final/manual validation closure.
- T001 validation passed: `./mill tui.test`,
  `./mill app.run tui --config config.example.yaml`, `git diff --check`, and
  `jq empty .agent-loop/tasks.json`. Local environment blockers were recorded:
  `test -t 0` exits 1 with `stdin_is_tty=false`, and
  `command -v native-image` exits 1 while `java -version` reports OpenJDK
  25.0.3.
- 2026-06-30: Implementation iteration 44 completed T002. Execution rendering
  now seeds ordered selected candidate rows from `PlanReady`/TUI-selected
  entries, updates active progress in the candidate row instead of appending a
  separate active row, preserves completed/failed/skipped/pending row order,
  and supports execution-row focus so `Enter` opens the focused failed row's
  root-cause modal. Extreme narrow execution rendering falls back to an ordered
  candidate list with the active progress shown in the lower info bar.
- T002 validation passed: `./mill tui.test`, `./mill core.test`,
  `./mill __.compile`, `./mill mill.scalalib.scalafmt/checkFormatAll`,
  `./mill app.run tui --config config.example.yaml`,
  `jq empty .agent-loop/tasks.json`, and `git diff --check`. The first
  scalafmt check found formatting drift after implementation; running
  `./mill mill.scalalib.scalafmt/reformatAll` fixed it before the final
  passing checks.
- 2026-06-30: Implementation iteration 45 completed T003. Execution progress
  now keeps known-size download bars in the active row with filled/empty
  segments, percentage, and byte counts; unknown-size downloads use a
  deterministic frame-based indeterminate bar without inventing a total.
  Advanced download ticks update the active row in place without appending log
  lines for every tick, while start/finish transitions remain visible.
- T003 final summaries now include completed, failed, skipped, remaining and
  interrupted counts when row state is available, plus exit code and elapsed
  time. Completed/failed/skipped row styling remains semantic and is covered by
  deterministic TUI tests. Validation passed after formatting: `./mill
  tui.test`, `./mill __.compile`, and `./mill
  mill.scalalib.scalafmt/checkFormatAll`.
- 2026-06-30: Validation iteration 46 completed T004, the execution-rendering
  checkpoint after T002/T003. No source or test fixes were required. Focused
  config/core/CLI/TUI tests, recursive compile/test, scalafmt check, app help
  and command smokes, static first-class TUI smoke, JSON validation, and
  whitespace checks all passed. Full logs are in
  `.agent-loop/validations/validation-46-20260630-120535/`.
- The T004 static TUI smoke rendered the first-class `tui --config` frame and
  printed `non-interactive terminal detected; rendered a static TUI frame`,
  so it did not enter raw mode in this shell. Environment blockers remain
  explicit: `command -v native-image` exits 1, `java -version` reports OpenJDK
  25.0.3, and `test -t 0` exits 1.
- 2026-06-30: Implementation iteration 47 completed T005. Sudo symlink
  creation now probes cached credentials with fixed `sudo -n true` argv, runs
  cached privileged links with fixed `sudo -n ln -sfn ...` argv, and requests
  credentials through an injected core `SudoCredentialProvider` only when the
  cache probe fails. Password-backed sudo execution uses modeled secret stdin
  with fixed `sudo -S -p "" ln -sfn ...` argv; passwords are not stored in argv,
  env, command diagnostics, installer events, or apply state.
- T005 added typed credential cancellation/unavailable install errors and
  command-input redaction for process output/errors. Validation passed:
  `./mill core.test`, `./mill tui.test`, `./mill __.compile`,
  `./mill mill.scalalib.scalafmt/checkFormatAll`, and `git diff --check`.
- 2026-06-30: Implementation iteration 48 completed T006. Interactive TUI
  apply now wires sudo credential requests to a focused terminal-backed
  password modal. The modal shows the privileged operation, tool, destination,
  and target path when available; typed input is represented only by a masked
  character count in render state.
- T006 cancellation through Escape, Ctrl+C, or `/cancel` followed by Enter
  returns the typed core cancellation outcome, so only the current privileged
  operation fails and the TUI reports a clear apply error. Cached sudo
  credentials still skip the modal because core completes the `sudo -n true`
  path before requesting credentials. Validation passed: `./mill tui.test`,
  `./mill core.test`, `./mill __.compile`, `./mill
  mill.scalalib.scalafmt/checkFormatAll`, `jq empty .agent-loop/tasks.json`,
  and `git diff --check`.
- 2026-06-30: Validation iteration 49 completed T007, the privilege/password
  checkpoint after T005/T006. Focused config/core/CLI/TUI tests, recursive
  compile/test, scalafmt check, command/help smokes, static first-class TUI
  smoke, JSON validation, and whitespace checks all passed. Full logs are in
  `.agent-loop/validations/validation-49-20260630-122412/`.
- The T007 static TUI smoke rendered the first-class `tui --config` frame and
  printed `non-interactive terminal detected; rendered a static TUI frame`.
  The validation log scan found no known password sentinel values in current
  test/smoke output. Local live-TTY password smoke remains manual because
  `test -t 0` exits 1; `command -v native-image` also exits 1 while
  `java -version` reports OpenJDK 25.0.3.
- 2026-06-30: Implementation iteration 50 completed T008. Browsing still
  defaults the lower info bar to selected-row details, while plan preview,
  dry-run output, and errors now render in the lower info bar without replacing
  the table-first workspace. Logs are focusable and scrollable in both
  browsing and execution contexts.
- T008 error handling now shows failures in the lower info bar first and opens
  root-cause details on demand. Root-cause details include action, affected
  tool/path where known, exit code, stdout/stderr excerpts, duration when
  available, environment hints, suggestions, and existing redaction. Validation
  passed: `./mill tui.test`, `./mill cli.test`, `./mill __.compile`,
  `./mill mill.scalalib.scalafmt/checkFormatAll`,
  `jq empty .agent-loop/tasks.json`, and `git diff --check`.
- 2026-06-30: Implementation iteration 51 completed T009. Planning and
  execution renderers now derive compact header/footer and table/info body
  rectangles from the current viewport, including tiny terminal heights, and
  panel widths no longer exceed very narrow viewport widths.
- T009 centralized TUI resize handling so nested execution state receives
  resize events even while a filter draft or modal owns input. Deterministic
  tests now cover normal, narrow, and tiny snapshots for browsing, execution,
  help, password, logs, and error output; state preservation across resize; and
  keyboard/mouse-wheel routing to the focused widget after resize. Validation
  passed: `./mill tui.test`, `./mill __.compile`,
  `./mill app.run tui --config config.example.yaml`,
  `./mill mill.scalalib.scalafmt/checkFormatAll`,
  `jq empty .agent-loop/tasks.json`, and `git diff --check`.
- 2026-06-30: Validation iteration 52 completed T010, the responsive UI
  checkpoint after T008/T009. No source or test fixes were required. Focused
  config/core/CLI/TUI tests, recursive compile/test, scalafmt check, Mill
  resolution, app help and command smokes, static first-class TUI smoke, JSON
  validation, and whitespace checks all passed. Full logs are in
  `.agent-loop/validations/validation-52-20260630-124145-final/`.
- The T010 static TUI smoke rendered the current footer shortcuts
  `p plan | d dry-run | r apply | tab focus | enter details | l logs | space |
  a/c/i | / filter | ? help | q quit` and printed
  `non-interactive terminal detected; rendered a static TUI frame`. Live TTY
  resize/password/error smoke remains environment-blocked here because
  `test -t 0` exits 1 and reports `stdin_is_tty=false`; `command -v
  native-image` also exits 1 while `java -version` reports OpenJDK 25.0.3.
- 2026-06-30: Implementation iteration 53 completed T011 documentation
  updates. README, `docs/tui-guide.md`, `docs/security.md`,
  `docs/testing.md`, `docs/tui-smoke.md`, and
  `docs/first-class-tui-review.md` now describe the current table-first
  workspace, execution rows/progress, focusable logs/errors, row-specific
  root-cause behavior, password modal, privilege boundary, redaction and
  cancellation guarantees, deterministic test coverage, manual smoke workflow,
  and deliberate deferrals.
- T011 kept environment-bound validations explicit: live raw-terminal startup,
  resize, password modal, root-cause modal, mouse-wheel scrolling, `q`,
  Ctrl+C, and cleanup remain manual in `docs/tui-smoke.md`; native-image
  validation remains tied to an environment with `native-image` installed.
  Documentation validation passed: `./mill app.run tui --help`,
  `./mill app.run tui --config config.example.yaml`, `git diff --check`, and
  `jq empty .agent-loop/tasks.json`.
- 2026-06-30: Validation iteration 54 completed T012, the final validation
  gate for the TUI excellence queue. Focused config/core/CLI/TUI tests,
  recursive compile/test, scalafmt check, Mill resolution, app help and command
  smokes, static first-class TUI smoke, JSON validation, stale transitional-doc
  scans, and whitespace checks all passed. Full logs are in
  `.agent-loop/validations/validation-54-20260630-124858/`.
- The T012 static TUI smoke rendered the table-first `tui --config` frame and
  printed `non-interactive terminal detected; rendered a static TUI frame`.
  Current user-facing docs, release docs, and release workflow examples no
  longer contain transitional `plan/apply --tui` examples; the remaining
  `--tui` mentions are either historical review context or negative assertions
  that `plan`/`apply` no longer own the flag.
- Manual live-terminal smoke status for startup, resize, password modal, logs,
  root-cause modal, `q`, Ctrl+C, and terminal cleanup remains
  environment-blocked in this agent shell because `test -t 0` reports
  `stdin_is_tty=false`. Local native-image validation is also blocked because
  `native-image` is not on `PATH`; `java -version` reports OpenJDK 25.0.3.
- 2026-06-30: Validation iteration 55 re-ran the configured checkpoint after
  the completed TUI excellence queue. No in-scope source or test fixes were
  required. Focused config/core/CLI/TUI tests, recursive compile/test,
  scalafmt check, Mill resolution, app help and command smokes, static
  first-class TUI smoke, JSON validation, and whitespace checks all passed.
  Full logs are in
  `.agent-loop/validations/validation-55-20260630-125238/`.
- The TUI static smoke again rendered the table-first `tui --config` frame and
  printed `non-interactive terminal detected; rendered a static TUI frame`.
  Remaining validation risks are environment-bound: `native-image` is not on
  `PATH`, while `java -version` reports OpenJDK 25.0.3; `test -t 0` reports
  `stdin_is_tty=false`, so live raw-terminal startup, resize, password modal,
  logs/root-cause scrolling, quit/Ctrl+C, and cleanup still require an
  interactive terminal.
