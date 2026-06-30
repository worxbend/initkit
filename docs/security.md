# Security Model

Date: 2026-06-30

`binstaller` installs binary tool distributions from a user-provided manifest.
The manifest is trusted configuration, but downloads, archive metadata, terminal
text, and process output are treated as untrusted input at display and install
boundaries.

## Trust Boundaries

- YAML parsing and validation are the first trust boundary. Unsupported fields
  that would execute scripts are rejected before planning.
- Version resolver and download URLs must be HTTPS and have a host.
- Downloaded bytes are untrusted until bounded, checksum-verified when a
  checksum is configured, extracted or staged, and executable paths are verified.
- Archive member names and mapped targets are untrusted and must remain inside
  staging or install roots.
- CLI and TUI rendering scrub terminal control characters and redact sensitive
  environment-derived values at display boundaries.
- TUI modal bodies and log lines are display surfaces. They are sanitized before
  rendering even when the underlying failure came from process output, config
  diagnostics, or selected-entry action results.
- State files are local resume metadata, not a security authority.

## Unsupported Installer Scripts

Manifest installer scripts are deliberately unsupported. A profile containing an
`installer` block fails during config loading with:

```text
installer scripts are not supported; use direct binary or archive download
```

This keeps shell syntax, inherited environment, cleanup, timeout, and sudo
behavior out of the manifest contract.

## Command Boundaries

The remaining external process boundaries are intentionally narrow:

- `sudo -n true`, `sudo -n ln -sfn <target> <path>`, and when a UI supplies a
  password, `sudo -S -p "" ln -sfn <target> <path>` for privileged symlinks.
- `tar -xJf <archive> -C <extractDir>` for the `tar.xz` fallback.
- `stty` calls in the TUI terminal backend for raw-mode setup and restore.

Core command execution uses argv, not manifest-provided shell strings. Process
execution has a default 15 minute timeout. Failure messages quote arguments so
diagnostics preserve argument boundaries.

Password-backed sudo uses modeled secret stdin. The password is not included in
argv, environment variables, command previews, command diagnostics, installer
events, apply state, logs, or TUI modals. Command failures redact the secret
from command messages and captured stdout/stderr before they reach renderers or
state.

## Archive Safety

Native `zip` and `tar.gz` extraction rejects unsafe paths before writing mapped
files. The native `tar.gz` path rejects symlink, hardlink, and unsupported tar
entry types. Duplicate archive member sources and duplicate output targets are
rejected.

`tar.xz` remains a structured system-`tar` fallback. It extracts into private
staging and then copies only declared mapped members after path validation, but
it does not yet provide native pre-extraction metadata inspection. Do not treat
untrusted `tar.xz` archives as equivalent to native `zip` and `tar.gz` inputs.

Known deferred archive gaps are documented in
`docs/post-tui-readiness-review.md`.

## Checksum Policy

Configured checksums use SHA-256 and must be 64 hex characters. Apply verifies
the checksum before staging replacement.

Missing checksums are currently allowed to support dynamic upstream assets and
developer convenience. They are surfaced as `not configured`, `missing`, or
`no-checksum` risk markers in CLI/TUI plan views. Production profiles should add
checksums and use `policy.mode: strict`. Strict mode rejects missing checksums
unless `policy.allowMissingChecksums: true` is explicitly set.

## Strict Policy Mode

`policy.mode` defaults to `developer` for compatibility with local tool
profiles. Developer mode allows dynamic latest URLs, missing checksums, and the
system `tar.xz` fallback by default. Sudo symlinks still require
`policy.allowSudoSymlinks: true`.

`policy.mode: strict` rejects production-sensitive risks unless the manifest
explicitly opts in:

- Dynamic latest sources: `dynamic.latest-url` versions and download URLs that
  contain `/latest`.
- Missing SHA-256 checksums.
- Sudo symlinks, unless `policy.allowSudoSymlinks: true`.
- `tar.xz` archives, unless `policy.allowTarXzFallback: true`.
- Archive candidate fallback, if candidate discovery is added later, unless
  `policy.allowArchiveCandidateFallback: true`.

Strict-policy failures render as validation-style messages with stable
`strict-policy[...]` codes and matching `suggestion[...]` hints.

## Sudo Policy

Sudo is available only for symlink creation. It requires all of the following:

- The manifest declares `policy.allowSudoSymlinks: true`.
- The symlink entry sets `sudo: true`.
- Non-dry-run apply is confirmed, either with CLI `--yes` or the TUI
  confirmation modal.
- The sudo symlink destination path is absolute.

Ordinary downloads, archive extraction, executable checks, local symlinks, and
state writes do not cross the sudo boundary.

The privileged-command flow is:

1. Core probes cached credentials with fixed `sudo -n true` argv.
2. If cached credentials are valid, core creates the symlink with fixed
   `sudo -n ln -sfn <target> <path>` argv and does not request a password.
3. If the cache probe fails, core asks an injected `SudoCredentialProvider` for
   one password for that privileged operation.
4. TUI apply supplies that provider with a focused masked password modal. CLI
   and other non-interactive callers use the unavailable provider unless they
   inject their own credential boundary.
5. The password-backed command uses fixed `sudo -S -p "" ln -sfn <target>
   <path>` argv plus secret stdin.

Credential cancellation is typed as `SudoCredentialError.Canceled` and becomes a
tool failure for the current privileged symlink. It does not cancel the whole
process by itself; existing `continueOnError` behavior determines whether later
tools continue.

## TUI Selection And Confirmation

`binstaller tui` owns checkbox selection inside TUI-local state. The selected
tool names are converted to core `ToolSelection` only at the action boundary for
plan preview, dry-run apply, or confirmed real apply. Hidden entries preserved by
filtering are not executed unless their checkbox remains selected, and visible
bulk operations affect only the currently visible set.

Plan preview (`p`) and dry-run (`d`) operate only on selected entries. Dry-run
uses the core dry-run path, so it must not download, replace installs, create
symlinks, or write state. If no entries are selected, the TUI opens a
no-selection modal and does not call the plan/apply service.

Real apply (`r`) first opens an in-frame confirmation modal. Core apply is not
called with non-dry-run options until the user presses `Enter` in that modal.
`Escape`, `n`, `q`, or `Ctrl+C` leave the confirmation path without starting
real apply.

If real apply later needs sudo credentials, the TUI password modal opens inside
the same terminal workspace. It renders only a masked character count and
redacted operation context. `Escape`, `Ctrl+C`, `q`, end-of-input, or `/cancel`
plus `Enter` cancels the credential request and fails only the current
privileged operation.

## State-File Policy

Apply state records schema version, profile name, manifest fingerprint, and
terminal per-tool status. It is used for resume only.

Current rules:

- The state path comes from `--state` or `spec.policy.stateFile`.
- Non-dry-run apply accepts only a current-directory filename.
- Absolute paths, nested relative paths, and empty names are rejected.
- Plan and dry-run apply do not validate or touch the state file.
- State is written after terminal tool results through a same-directory temp
  file and atomic move.
- Incompatible profile names or manifest fingerprints fail unless
  `--reset-state` is used.

## Redaction And Display Safety

Resolution derives redactions from sensitive runtime variable names. Renderers
scrub display lines, command-output tails, plan output, versions output, apply
errors, progress labels, state messages, TUI cells, modal bodies, root-cause
details, and TUI log lines.

Redaction is display-only: raw values are preserved for filesystem and network
behavior. This prevents corrupting legitimate paths or URLs while reducing
secret exposure in terminal output.

Passwords have a stronger guarantee than ordinary display redactions: password
values are wrapped as secret command input and are never part of the stable
renderable data model. Tests cover no leakage into command argv/spec strings,
command diagnostics, installer events, TUI logs, error/root-cause modals, prompt
frames, or apply state.

Terminal-control scrubbing is also display-only. Untrusted text is collapsed
into safe terminal lines before rendering so config values, resolver output,
download diagnostics, command stdout/stderr snippets, and modal details cannot
inject cursor movement, alternate-screen toggles, color resets, or mouse-mode
escape sequences into CLI/TUI output.

## Remaining Risks

- Downloads are bounded by default limits, but body deadlines are checked at
  chunk boundaries.
- Missing checksums are still accepted by developer-mode profiles.
- ZIP external attributes and native `tar.xz` pre-inspection remain deferred.
- Dry-run and real apply actions inside `binstaller tui` currently run
  synchronously while rendering execution updates; they do not provide
  preemptive cancellation for long-running work.
- Password-prompt cancellation is scoped to the credential request/current
  privileged operation. It is not a general cancellation mechanism for an
  already-running download, extraction, or command.
- Live raw-terminal password entry, resize during password/root-cause modals,
  and terminal cleanup require a real interactive terminal for manual
  validation; deterministic tests cover state, rendering, redaction, and fake
  terminal cleanup.
- Native image validation is environment-bound when `native-image` is not
  installed locally; the release workflow remains the native build boundary.
