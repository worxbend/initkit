# Hardening Review

Date: 2026-06-29

Scope: post-implementation review of config parsing, version resolution, downloads,
archive extraction, symlink creation, state writes, plan fidelity, CLI
reporting, tests, and release workflow.

## Findings And Recommendations

### Must Fix

- Implemented: Runtime version resolver and download URLs now must be HTTPS and
  include a host. Non-HTTPS manifests fail during resolution before downloads or
  writes.
- Implemented: Runtime HTTP clients now use a 30 second connect/request timeout
  and still follow normal HTTPS redirects.
- Implemented: External process execution now has a bounded timeout. The default
  process timeout is 15 minutes, and tests cover timeout behavior.
- Implemented: Resolved tool install directories must be children of
  `spec.policy.appsDir` and must not be nested under another tool's install
  directory. This reduces accidental clobbering of unrelated user files, state
  files, and sibling tools.
- Implemented: Manifest installer scripts are no longer supported. Installer
  blocks are rejected during config decoding, keeping the binary installer
  focused on direct downloads and archive extraction.
- Implemented: Process failure rendering now quotes argv elements so diagnostics
  preserve argument boundaries and do not imply shell evaluation.
- Deferred with rationale: `tar.xz` extraction still depends on the system `tar`
  fallback. It extracts into a private staging directory and only copies mapped
  members after path validation, but it does not perform native pre-extraction
  member-type inspection. Full native `tar.xz` inspection should be implemented
  before treating untrusted `.tar.xz` archives as equivalent to the native zip
  and tar.gz paths.

### Should Fix

- Require checksums, lock-file entries, or signed provenance for dynamic sources
  before encouraging production use. Current output calls missing checksums out
  as `not configured`, but absence is still accepted.
- Add max download size enforcement and optional content-length checks before
  buffering downloads into memory.
- Add redirect provenance to plan/apply/lock output, including the final
  effective URL where the JDK client exposes it.
- Add retry policy for transient HTTP failures, keeping retries off for scripts
  unless idempotence is modeled.
- Add native `tar.xz` handling or a stronger extraction sandbox; the current tar
  fallback is the largest remaining archive boundary.
- Add broader malformed archive tests for symlink, hardlink, device, duplicate,
  and permission metadata cases across all supported archive types.
- Add richer structured reporting for apply, including per-tool phases and
  redacted command/env detail in verbose mode.
- Exercise the GitHub release workflow in CI with a real native-image run and
  record native smoke logs.

### Later

- Add policy profiles such as `strict`, `developer`, and `legacy-compatible`.
- Add signature/SLSA verification and SBOM export.
- Revisit sandboxed installer execution only if a future manifest contract makes
  scripts necessary again.
- Add a manifest schema document or generated reference once the v1alpha1 shape
  stabilizes.

## Required Review Questions

- Can manifest values become executable shell syntax? Interpolation treats shell
  syntax as text. External process boundaries are `ProcessBuilder` argv calls.
  Manifest installer scripts are rejected; the remaining intentional external
  process boundaries are sudo symlinks and the temporary `tar` fallback for
  `tar.xz`.
- Are command args preserved as argv? Yes for sudo symlinks and `tar.xz`;
  plan now quotes args for display and failure output quotes argv elements.
- Can archives write outside staging? Native zip and tar.gz paths validate
  member names and mapped targets before writing. The `tar.xz` fallback extracts
  to private staging and copies only validated mapped files, but native
  pre-extraction inspection remains deferred.
- Can archives create unsafe symlinks, hardlinks, special files, duplicate
  paths, permissions, or ownership escapes? Native tar.gz rejects link and
  unsupported tar entry types, and extraction ignores archive ownership and mode
  metadata. Duplicate mapped targets are rejected. Native zip currently treats
  non-directory entries as files and should gain explicit symlink metadata tests.
  `tar.xz` needs stronger native inspection.
- Can manifest paths escape appsDir or clobber user files? Resolved install dirs
  now must be under `appsDir` and cannot nest inside another tool. Executable,
  create-directory, local symlink, and archive target writes are constrained
  under the install/staging directory at apply time. State paths remain limited
  to current-working-directory filenames.
- Are downloads atomic and verified before replacement? Direct binary and native
  archive installs download, verify checksum when configured, stage, apply modes,
  then replace.
- Are checksums supported consistently, and where should they become required?
  SHA-256 is enforced when configured for all download strategies. Missing
  checksums are visible in plan output but accepted; checksums should become
  required for pinned production profiles or represented in a lock file.
- Are examples free of placeholder checksums and unsafe dynamic sources? Example
  checksums are concrete where present. Several dynamic latest-url entries have
  no checksum and remain acceptable only as developer convenience until lock-file
  or provenance work lands.
- Are redirects, content length, content type, max size, and timeout predictable?
  Redirects follow normal HTTPS redirects and requests now time out. Content
  length, content type, and max download size are not yet enforced.
- Can installer scripts unexpectedly use sudo or inherit unsafe env? No through
  the manifest model: installer script blocks are rejected during config
  decoding.
- Can installer scripts read sensitive parent env vars? No through binstaller,
  because installer script execution is not supported.
- Do installer scripts have timeout/cancellation and cleanup behavior? Installer
  scripts are not executed. External process execution still has a timeout for
  the remaining structured process boundaries.
- Are sudo symlinks impossible unless configured and confirmed? Yes. Config
  validation gates sudo symlinks with `policy.allowSudoSymlinks`; apply preflight
  also requires policy allowance and `--yes` before writes.
- Are sudo operations rendered exactly and highlighted? Dry-run renders exact
  `sudo ln -sfn` commands with quoted args and `sudo risk` markers. Apply uses
  structured argv.
- Is state written atomically and stale state safe? State is written through a
  temp file in the same directory and `ATOMIC_MOVE`; incompatible profile names
  or fingerprints fail unless `--reset-state` is used.
- Are error messages actionable without exposing secrets? Validation and apply
  failures include paths/actions. Some filesystem and process errors still
  include paths by design.
- Are plan and apply close enough? Plan and apply share resolution and
  selection. Apply has extra preflight and executor phases.
  Native `tar.xz` inspection is the main remaining divergence.
- Do tests cover real failure modes? Tests cover invalid manifests, URL scheme,
  archive traversal, shell metacharacters as text, unsafe symlink policy, checksum
  mismatch, timeout, stale state, continue-on-error, and plan
  no-write behavior. More archive metadata and native-release tests are still
  recommended.
- Is the manifest understandable after all tools? The manifest remains one
  `BinaryDistributionProfile` with policy, vars, versions, and ordered plan
  entries. A schema reference should be added before broad external use.
- Which old broad-bootstrap modules should be deleted, retained, or split out?
  No old broad-bootstrap source modules remain in this implementation. README and
  release workflow were already pivoted to `binstaller`.
