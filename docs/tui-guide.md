# TUI Guide

Date: 2026-06-30

The TUI is explicit. Default commands remain script-friendly.

```bash
binstaller tui --config config.example.yaml
binstaller tui --config config.example.yaml --state binstaller-state.json
binstaller tui --config config.example.yaml --reset-state --verbose
```

`plan`, `apply`, `versions`, and `lock` stay non-interactive. The TUI command
loads one manifest, owns checkbox selection internally, and lets the user run
plan preview, dry-run, and confirmed apply without leaving the workspace.

In non-interactive shells, the TUI renders a static frame and does not enter raw
mode or the alternate screen.

## Browsing Workspace

`binstaller tui` starts in browsing mode with a table-first workspace:

- Header: app name/version, mode, current action, manifest/profile, selected
  count, config path, state file, host summary, and active filter.
- Top table: every resolved tool in stable plan order with checkbox, name,
  version, checksum state, and status.
- Lower info bar: selected-tool details by default; plan preview, dry-run/apply
  output, logs, or error output when those views are active.
- Footer/keybar: one compact shortcut legend.

The TUI selection is independent of CLI `--only`/`--skip`. It is converted to
core selection only when `p`, `d`, or confirmed `r` calls the underlying service.
Hidden filtered entries keep their checkbox state.

The active row is highlighted independently of its checkbox. Full URL, archive,
symlink, sudo, and operation details remain available in the lower info bar for
the focused row even when table cells are clipped.

## Execution View

Pressing `d` or confirmed `r` switches the primary view to execution mode. It
shows:

- The same selected candidate order as browsing mode.
- One execution row per selected candidate with name, status, and progress.
- The active installing row with spinner, phase, elapsed time, progress bar,
  percentage, and byte counts when total bytes are known.
- Deterministic indeterminate progress for unknown-size downloads without
  claiming a false total.
- Completed, failed, skipped, pending, interrupted, and remaining rows with
  distinct status styling.
- A final summary with completed, failed, skipped, interrupted, remaining, exit
  code, and elapsed time.

Dry-run (`d`) renders concrete operations without downloads, install writes,
symlink writes, or state writes. Real apply (`r`, then `Enter` in the
confirmation modal) runs the same core apply path as CLI apply with confirmation
enabled.

Execution progress updates replace the active row status instead of appending a
log line for every download tick. Download started/finished transitions, phase
changes, tool results, and action errors remain visible in logs. On extremely
narrow terminals, the view preserves the ordered candidate list and renders the
active row's progress in the lower info bar where it remains readable.

## Actions

- `p`: append a selected-entry plan preview to Logs. It does not install or
  write state.
- `d`: run dry-run apply for selected entries and show the execution view.
- `r`: open the real-apply confirmation modal for selected entries.
- `Enter` in the confirmation modal: run real apply for those selected entries.
- `Escape` or `n` in the confirmation modal: close it without writes.

If no entries are checked, `p`, `d`, and `r` open a message modal and do not call
the plan or apply service.

## Keybindings

- `Tab`: focus next area.
- `Shift+Tab` or `b`: focus previous area.
- `Left` / `Right`: focus previous or next area.
- `Up` / `Down`: move the highlighted row when the table is focused; scroll
  when details, logs, or root-cause text is focused.
- `PageUp` / `PageDown`: jump selection or scroll by a visible page.
- `Home` / `End`: move to first/last row or scroll edge.
- `Space`: toggle the current visible row checkbox.
- `a`: select all visible rows.
- `c`: clear all visible rows.
- `i`: invert visible row selection.
- `/`: edit the filter.
- `Enter`: apply filter while editing, focus lower info from browsing mode, or
  confirm a modal action. In execution mode it opens root-cause details for the
  focused failed row, or for the current failure output.
- `l`: focus Logs.
- `Escape`: cancel filter editing or close a modal.
- `?`: toggle in-frame help.
- `q` or `Ctrl+C`: exit the TUI and restore terminal state.
- Mouse wheel: scrolls the focused details, logs, or root-cause area when the
  terminal sends SGR mouse-wheel sequences.

During synchronous apply work, input is processed between rendered frames rather
than as a preemptive cancellation mechanism. Password prompts are an exception:
Escape, Ctrl+C, `q`, end-of-input, and `/cancel` plus `Enter` cancel the
credential request. Terminal cleanup still runs on normal completion and handled
failure.

## Selection And Filtering

The table has both a highlighted row and checkbox state. `Up`/`Down` moves
the highlighted row. `Space`, `a`, `c`, and `i` change checkbox state. The
header shows `selected N / total M` after each change.

Filtering matches visible entry text such as tool names and descriptions. If
the filter hides the highlighted row, the row index clamps to the visible result
set. Hidden selections are preserved, so filtering does not accidentally clear
or add entries.

## Focus And Scrolling

The focused area title includes `[focus]`. In browsing mode, table focus changes
the highlighted row while details/log focus scrolls the lower info content. In
execution mode, `Tab` switches between execution rows and logs; row focus can
move through completed, failed, skipped, pending, and active rows.

When Details, Logs, or root-cause modal text overflow, the title includes a
range like `scroll 2-7/12` and the right edge renders scrollbar markers. Full
long values that are truncated in the table remain visible in the lower info
bar.

Filtering currently matches tool names and descriptions. Full URL, archive,
symlink, and operation details remain visible in the lower info bar for the
highlighted row.

## Logs, Errors, And Modals

The TUI uses in-frame modals for help, no-selection messages, real-apply
confirmation, startup failures, password prompts, and root-cause details for
failed execution rows. Plan preview output, dry-run/apply output, logs, and
action errors appear in the lower info bar first so the table stays visible.
`l` focuses logs. When logs or root-cause details overflow, keyboard paging and
mouse wheel events scroll the focused content.

Error and root-cause details include category, action, root cause, suggestion,
exit code, duration, environment hints, and bounded stdout/stderr snippets when
available. Modal and log text uses the same display safety path as other TUI
rendering: terminal control characters are scrubbed and sensitive
environment-derived values are redacted before display.

`Enter` or `Escape` closes informational/error modals. In execution mode,
`Enter` opens the focused failed row's root-cause modal. If no failed row is
focused but the current action has failure output, `Enter` opens that failure's
root-cause modal.

## Password Modal

Privileged operations are limited to sudo symlink creation. Core first tries
the cached sudo path with fixed argv. If credentials are not cached, TUI apply
uses a terminal-backed credential provider and opens a focused password modal.

The modal shows the privileged operation, affected tool, destination path, and
target path when available. Typed characters are rendered only as `*` count;
the password buffer is local to the prompt and cleared after submit or cancel.
Submitted passwords travel to sudo through modeled secret stdin, not argv,
environment, logs, previews, errors, root-cause details, or state files.

`Enter` submits the masked input. `Escape`, `Ctrl+C`, `q`, end-of-input, or
typing `/cancel` and pressing `Enter` cancels the credential request. Core
reports `sudo credentials canceled` for the current privileged operation; normal
continue-on-error policy can still continue later tools.

## Progress States

Planning rows can show active, warning, inactive, completed, failed, and skipped
styles. Current planning risk markers include missing checksums, dynamic
versions, and sudo symlinks.

Execution consumes core events:

- resolving and plan ready
- tool started
- phase changed
- download started, advanced, and finished
- log line
- tool completed or failed
- tool skipped from state
- final summary

## Terminal Troubleshooting

- Run interactive TUI checks from a real terminal emulator, not a pipe, IDE task
  runner, or CI shell.
- If you see `non-interactive terminal detected`, the process used a static
  fallback frame.
- The system backend uses `/dev/tty` and `stty` to enter raw mode.
- Live resize is polled through the terminal boundary and rerenders on the next
  input/read cycle when the terminal reports a changed size.
- Mouse wheel behavior depends on terminal emulator and multiplexer mouse
  settings.
- If a local terminal is left in raw mode after an external kill, recover with:

```bash
stty sane
printf '\033[?25h\033[?1049l\033[?1000l\033[?1006l'
```

See `docs/tui-smoke.md` for the full manual smoke workflow.
