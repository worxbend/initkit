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
./mill app.run plan --config config.example.yaml
./mill app.run versions --config config.example.yaml
./mill app.run lock --config config.example.yaml --output /tmp/binstaller.lock.json
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
- Routing text clients also cover GitHub latest-release metadata used by
  `versions` for release-download URLs.
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

CLI output includes ANSI styling. Tests normally compare behavior after stripping
ANSI SGR color sequences:

```scala
private def stripAnsi(output: String): String =
  output.replaceAll("\u001b\\[[;\\d]*m", "")
```

Use plain-output assertions for text, command content, and progress summaries.
Keep at least one assertion proving colored output is present when color itself
is part of the behavior.

Strip ANSI and compare stable substrings for command output. Keep direct
assertions around in-place progress rendering where carriage returns are part
of the behavior.

## No-Write And State Assertions

Plan tests should use temporary `appsDir` and state paths, then assert those
paths were not created.

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
