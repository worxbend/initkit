# Testing Guide

Date: 2026-06-30

Use the checked-in Mill launcher. The project is a Scala 3/Mill build; do not
add sbt, Maven, Gradle, npm, or Make for normal validation.

## Project-Native Targets

Focused tests:

```bash
./mill config.test
./mill core.test
./mill cli.test
./mill tui.test
```

Broad checks:

```bash
./mill __.compile
./mill __.test
./mill mill.scalalib.scalafmt/checkFormatAll
git diff --check
```

App smokes:

```bash
./mill app.run --help
./mill app.run tui --help
./mill app.run plan --config config.example.yaml
./mill app.run apply --config config.example.yaml --dry-run
./mill app.run versions --config config.example.yaml
./mill app.run lock --help
./mill app.run tui --config config.example.yaml
```

Use `./mill mill.scalalib.scalafmt/reformatAll` to repair formatting.

## Mill Output

For long Mill commands, stream and save output with `tee`:

```bash
LOGFILE=".agent-loop/validations/check-$(date +%s).log"
./mill __.test 2>&1 | tee "$LOGFILE"
```

Do not pipe Mill output through `grep`, `head`, `tail`, or `/dev/null`; that
can hide diagnostics needed for follow-up fixes.

## Fake Download Patterns

Core and CLI tests avoid live downloads by injecting `BinaryDownloadClient` and
`HttpTextClient` implementations:

- A fake text client returns a pinned resolver value for `http-text` versions.
- A fake binary client returns bytes or a typed `BinaryDownloadError`.
- Progress tests override `download(url, observer)` and emit started,
  advanced, and finished events before returning bytes.
- Routing clients map URLs to bytes so multi-tool apply tests can prove
  continue-on-error and state behavior.

Prefer these injected boundaries over local HTTP servers unless redirect or
timeout behavior is the behavior under test.

## Fake Archive Patterns

Archive tests build small in-memory artifacts:

- ZIP tests use `ZipOutputStream` and hand-built duplicate local headers for
  duplicate-member regressions.
- `tar.gz` tests write minimal ustar headers through `GZIPOutputStream` and vary
  entry type flags to cover files, directories, links, and unsupported metadata.
- `tar.xz` tests inject a fake archive command executor instead of depending on
  system `tar`.

Keep archive tests small, deterministic, and focused on path safety, metadata
rejection, mapping behavior, and previous-install preservation.

## Terminal Output Assertions

CLI/TUI output includes ANSI styling. Tests normally compare behavior after
stripping ANSI SGR color sequences:

```scala
private def stripAnsi(output: String): String =
  output.replaceAll("\u001b\\[[;\\d]*m", "")
```

Use plain-output assertions for text, table content, and progress summaries.
Keep at least one assertion proving colored output is present when color itself
is part of the behavior.

For TUI, prefer deterministic state, renderer, input, and terminal-boundary
tests:

- build `TuiAppState` from a resolved snapshot and assert header, entries,
  selection, filter, focus, modal, logs, and execution state
- drive `TuiAppController` or `PlanningTuiSession.run` with explicit
  `TuiInput` values for keybindings, selection, filtering, modals, and action
  shortcuts
- assert selected-entry plan/dry-run/apply actions convert TUI-local selection
  to core `ToolSelection` only at service boundaries
- render static browsing and execution models, including normal, narrow, and
  tiny viewport sizes
- assert execution rows are pre-seeded in selected candidate order, the active
  row owns progress/status, failed-row focus controls which root-cause modal
  opens, and the narrow fallback preserves ordered candidates plus readable
  lower-info progress
- assert known-size progress shows bar, percentage, and byte counts; unknown
  sizes use deterministic indeterminate frames; and advanced progress ticks do
  not append noisy log lines
- assert lower info output keeps the table visible for selected details, plan
  preview, dry-run output, logs, and errors
- assert log focus and root-cause modal scrolling respond to keyboard and mouse
  wheel input
- assert password modal rendering names the operation/tool/destination/target,
  masks typed input, survives resize, submits through the credential provider,
  and treats Escape, Ctrl+C, `q`, end-of-input, and `/cancel` plus Enter as
  cancellation
- assert password sentinels do not appear in rendered prompt frames, TUI logs,
  error/root-cause details, command argv/spec diagnostics, installer events, or
  state files
- strip ANSI and compare stable substrings
- use `FakeTuiTerminal` for open/close, render-failure cleanup, resize, Ctrl+C,
  quit, modal close, and non-interactive fallback assertions

The static TUI smoke command is:

```bash
./mill app.run tui --config config.example.yaml
```

In non-interactive shells it should render a TUI-shaped frame plus a clear
`non-interactive terminal detected` message without entering raw mode or the
alternate screen.

Live raw-terminal behavior remains manual and is documented in
`docs/tui-smoke.md`.

Manual live-terminal checks are still required for behavior that depends on a
real emulator and `/dev/tty`:

- startup into alternate screen and raw mode
- resize while browsing, execution, logs, root-cause modal, and password modal
  are visible
- password entry masking, cancellation, cached-sudo skip, and terminal echo
  after the prompt closes
- mouse-wheel log/root-cause scrolling
- `q`, Ctrl+C, and handled failure cleanup restoring cursor, echo, and the
  previous shell screen

## No-Write And State Assertions

Plan and dry-run tests should use temporary `appsDir` and state paths, then
assert those paths were not created.

Stateful apply tests should use current-directory state filenames under a
temporary working directory. Assert completed tools are skipped, failed tools
are retried, incompatible fingerprints require `--reset-state`, and temporary
state files are cleaned up.

## Native Image Checks

Native image builds require GraalVM with `native-image` installed. Locally:

```bash
command -v native-image
java -version
GRAALVM_HOME=/path/to/graalvm ./mill app.nativeImage
```

If `native-image` is missing, record the blocker and the `java -version`
details. The release workflow uses GraalVM 21 and runs `./mill app.nativeImage`
on Ubuntu 24.04.
