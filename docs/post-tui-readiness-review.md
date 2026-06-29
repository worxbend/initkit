# Post-TUI Production Readiness Review

Date: 2026-06-29

Scope: post-TUI review of `config`, `core`, `cli`, `tui`, `app`, `docs`,
`build.mill`, and `.github/workflows/native-release.yml` after the explicit
`plan --tui` and `apply --tui` flows landed.

This review is a backlog gate. Items classified as must fix either reference the
implementation task that must address them or carry an explicit deferral
rationale.

## Reviewed Responsibilities

- `config`: owns YAML loading, typed manifest decoding, enum validation,
  installer-script rejection, duplicate tool and version-reference checks, and
  sudo symlink policy validation. It should not perform network, filesystem, or
  terminal side effects.
- `core`: owns environment interpolation, version resolution, HTTPS URL checks,
  selection, planning, downloads, checksum verification, archive extraction,
  staged filesystem replacement, executable modes, symlink creation, state
  persistence, command execution boundaries, typed expected errors, and
  renderer-agnostic apply events.
- `cli`: owns Picocli command shape, global flags, exit codes, script-friendly
  default rendering, colored progress, and routing explicit `--tui` requests
  into the TUI module. It should not duplicate install business rules.
- `tui`: owns deterministic planning/execution models, ANSI rendering, input
  parsing, terminal lifecycle, static non-interactive fallback, and event-driven
  execution display. It should consume core snapshots/events and avoid direct
  install logic.
- `app`: owns process entry and exit-code propagation only.
- `build/release`: Mill defines the acyclic graph `app -> cli -> {core, tui}`,
  `tui -> core`, and `core -> config`. The native release workflow builds and
  smoke-tests the native binary, but currently only covers non-TUI app smokes.

## Required Audit

- Shell injection: core process boundaries use argv for sudo symlinks and the
  `tar.xz` fallback. Manifest installer scripts are rejected. The TUI terminal
  backend still invokes `stty` through `sh -c`, but with internally generated
  arguments only; this is a should-fix boundary simplification, not a current
  manifest injection path.
- Path traversal: install directories are constrained under `appsDir`; state
  files are current-directory filenames; executable paths, archive targets, and
  local symlink paths are resolved under the install or staging root. Continue
  testing interpolated path fields because interpolation can change safety
  classes.
- Archive metadata: native `tar.gz` rejects links and unsupported tar entry
  types. Zip path traversal is checked, but symlink/external-attribute metadata
  is not explicitly rejected or tested. `tar.xz` extracts with system `tar`
  before indexing copied members, so it is not equivalent to the native paths
  for pre-extraction metadata control.
- Symlinks: local symlink paths resolve inside `installDir`, and targets must
  resolve inside `installDir`. This prevents ordinary manifest fields from
  linking arbitrary external targets.
- Sudo: sudo symlinks require `policy.allowSudoSymlinks`, non-dry-run
  confirmation through `--yes`, absolute sudo destinations, and argv execution
  of `sudo ln -sfn`. Ordinary path, archive, and download fields do not reach
  sudo.
- State files: state paths are filename-only, loaded from the current working
  directory, checked against profile name and manifest fingerprint, and written
  through a same-directory temp file plus `ATOMIC_MOVE`.
- Checksums: SHA-256 is enforced when configured and happens before install
  replacement. Missing checksums are visible in CLI/TUI output but are still
  accepted. Config does not yet validate checksum value shape beyond string
  type.
- Redirects: runtime clients require initial HTTPS URLs and follow normal JDK
  redirects. The final effective URL and redirect chain are not exposed in
  plan, versions, state, or apply diagnostics.
- Max size: binary downloads are buffered into memory without a configured max
  size or enforced `Content-Length` ceiling.
- Timeouts: HTTP requests have 30 second request/connect timeouts and external
  commands have a 15 minute timeout. Streaming body reads still need an overall
  body deadline or bounded read policy.
- Redaction: command environment rendering redacts non-safe env values. Other
  rendered surfaces can still expose env-derived secrets interpolated into URLs,
  paths, failure strings, TUI logs, or state messages.
- Terminal control: TUI raw mode is restored in `finally` for normal failures
  and exits, and non-interactive mode renders static frames. Manifest, path, and
  log strings are not centrally scrubbed for terminal control characters before
  CLI/TUI rendering. Interactive `apply --tui` does not read input while the
  synchronous apply loop runs, so the advertised `q`/Ctrl+C cancellation path is
  incomplete for long-running non-dry-run apply.

## Must Fix

- MUST-001 - Bound download size and body time. Implement in T011. Add a runtime
  max download size policy, reject oversized `Content-Length` before buffering,
  stop reads that exceed the limit when no length is present, and cover this
  with core tests. Also add a body-read deadline or equivalent cancellation so a
  stalled response body cannot hang after headers arrive.
- MUST-002 - Close archive metadata gaps. Implement in T011 for zip metadata
  tests/rejection where the JVM API exposes enough information, and strengthen
  malformed archive coverage for symlink, hardlink, device/special, duplicate,
  and permission metadata. Explicit deferral rationale for `tar.xz`: native
  pre-extraction inspection remains deferred until the project selects either a
  native xz/tar dependency or a stronger sandbox. Until then, `tar.xz` must stay
  documented as a structured fallback that is not production-equivalent for
  untrusted archives.
- MUST-003 - Sanitize terminal control output. Implement in T011. Add a shared
  renderer-safe text scrubber for CLI/TUI display of manifest names, paths,
  URLs, command output, failure strings, and logs. Preserve tabs/newlines only
  where the renderer intentionally handles them, and test ANSI/OSC/control
  sequence injection attempts.
- MUST-004 - Make interactive apply TUI cancellation honest. Implement in T011.
  Either add a nonblocking input/cancellation boundary for long-running
  `apply --tui`, including terminal cleanup tests, or remove the misleading
  `q/Ctrl+C quit` keybar from the live execution view and document the current
  limitation as explicit deferral.
- MUST-005 - Centralize redaction for env-derived values. Implement in T011.
  Identify sensitive runtime variable names and redact their values before plan,
  versions, apply errors, TUI logs, and state messages are rendered. Command env
  redaction is already present but too narrow for interpolation-based leaks.
- MUST-006 - Tighten checksum policy. Implement in T011. Validate configured
  SHA-256 values as 64 hex characters and add a production-safe policy for
  concrete downloads without checksums. If missing checksums remain allowed for
  developer convenience, the review document must mark that as an explicit
  deferral with rationale and a visible warning path.
- MUST-007 - Restore or prove install replacement atomicity. Implement in T011.
  `replaceInstallDirectory` moves an existing install to a backup, then moves
  staging into place; if the second move fails, the previous install may remain
  displaced. Add rollback/restore behavior or a documented filesystem-level
  atomic replacement constraint with tests for replacement failure.

## Should Fix

- Record redirect provenance: capture and render the final effective URL for
  downloads and `http-text` resolvers, and fail or warn if provenance changes
  expected host boundaries.
- Add optional content-type checks for downloads where upstreams provide stable
  values.
- Add retry policy for transient HTTP failures, bounded and off by default until
  idempotence is modeled.
- Replace the TUI `stty` shell wrapper with a direct process boundary or a small
  terminal backend abstraction that redirects `/dev/tty` without `sh -c`.
- Add live resize support by turning `SIGWINCH` or periodic size checks into
  `TuiInput.Resize` events.
- Expand config validation for non-empty, path-safe tool names and normalized
  path fields after interpolation.
- Split the large `core/src/binstaller/core/CoreModule.scala` into focused
  files after the must-fix pass: resolution, state, download, archive,
  filesystem, command, rendering, and events.
- Add native release static TUI smokes to the workflow, at least
  `plan --tui` and `apply --dry-run --tui` in non-interactive fallback mode.
- Add ScalaDoc and concise invariant comments for public boundaries and
  security-sensitive logic in T012.

## Later

- Add a lock file containing resolved versions, final URLs, sizes, checksums,
  and provenance.
- Add checksum auto-discovery for upstreams that publish checksum files.
- Add strict/developer policy profiles so production users can reject dynamic
  versions, missing checksums, sudo symlinks, and fallback archive paths without
  hand-editing every manifest.
- Add signature, SLSA, or SBOM verification for release assets.
- Revisit sandboxed installer-script support only if a future product decision
  explicitly reintroduces scripts.
- Consider JLine or another terminal backend only if local primitives remain too
  brittle after cancellation, resize, and cleanup hardening.

## Validation Notes

Existing automated coverage is strong for typed config failures, HTTPS checks,
checksum mismatch, archive path traversal, state fingerprint mismatch, sudo
gating, dry-run no-write behavior, deterministic TUI rendering, input model
navigation, and non-interactive TUI fallbacks.

Remaining validation gaps are mostly production-edge cases: oversized downloads,
body stalls, archive metadata beyond traversal, terminal control injection,
env-derived secret redaction, failed replacement rollback, live raw-terminal
long-running cancellation, native-image TUI smoke, and release workflow evidence.
