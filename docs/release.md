# Release Guide

Date: 2026-06-30

Releases are Linux amd64 native binaries produced by
`.github/workflows/native-release.yml`.

## Artifacts

The release workflow publishes:

- `binstaller-linux-amd64`
- `binstaller-linux-amd64.tar.gz`
- `SHA256SUMS`

The workflow is triggered by `v*` tags or manual `workflow_dispatch` with a
tag input.

## Workflow Summary

The GitHub Actions job:

1. Checks out the repository.
2. Sets up GraalVM 21 with `native-image`.
3. Installs native build dependencies.
4. Runs `java -version` and `native-image --version`.
5. Runs `./mill __.test`.
6. Builds `./mill app.nativeImage`.
7. Copies the native executable to `dist/binstaller-linux-amd64`.
8. Smokes native `--help`, `plan`, and `versions`.
9. Creates `binstaller-linux-amd64.tar.gz`.
10. Writes `SHA256SUMS`.
11. Publishes the GitHub Release assets.

## Pre-Release Checks

Before tagging, run:

```bash
./mill config.test
./mill core.test
./mill cli.test
./mill __.compile
./mill __.test
./mill app.run --help
./mill app.run plan --config config.example.yaml
./mill app.run versions --config config.example.yaml
./mill app.run lock --config config.example.yaml --output /tmp/binstaller.lock.json
./mill mill.scalalib.scalafmt/checkFormatAll
git diff --check
jq empty .agent-loop/tasks.json
```

## Native Smoke Checks

When `native-image` is available locally:

```bash
GRAALVM_HOME=/path/to/graalvm ./mill app.nativeImage
native_path="$(find out/app/nativeImage.dest -maxdepth 1 -type f -name native-executable -print -quit)"
"$native_path" --help
"$native_path" plan --config config.example.yaml
"$native_path" versions --config config.example.yaml
"$native_path" lock --config config.example.yaml --output /tmp/binstaller.lock.json
```

If local native image is blocked, record `command -v native-image` and
`java -version` output, then rely on the GitHub workflow for the native build.

## Release Smoke After Publish

After a release is published:

```bash
mkdir -p dist
curl -L -o dist/binstaller-linux-amd64 \
  https://github.com/worxbend/initkit/releases/latest/download/binstaller-linux-amd64
curl -L -o dist/SHA256SUMS \
  https://github.com/worxbend/initkit/releases/latest/download/SHA256SUMS
sha256sum --check --ignore-missing dist/SHA256SUMS
chmod +x dist/binstaller-linux-amd64
./dist/binstaller-linux-amd64 --help
./dist/binstaller-linux-amd64 plan --config config.example.yaml
./dist/binstaller-linux-amd64 versions --config config.example.yaml
./dist/binstaller-linux-amd64 lock --config config.example.yaml --output /tmp/binstaller.lock.json
```

Do not run apply against a real profile during release smoke unless the profile
uses an isolated temporary `appsDir`, a disposable current-directory state
filename, and `--yes` is intentional.

## Rollback Notes

If a release is bad:

- Stop any rollout automation that downloads `releases/latest`.
- Delete or mark the GitHub Release as prerelease if the tag should no longer
  be promoted.
- Publish a fixed patch tag rather than mutating a release that users may have
  checksummed.
- Keep the bad artifact checksum in incident notes for user verification.
- If the issue is manifest compatibility rather than binary behavior, document
  the required manifest change and link to the fixed docs.

Runtime install rollback is handled per tool by the staged replacement layer:
if replacing an existing install fails after the old install was moved aside,
the filesystem layer attempts to restore the previous install and reports any
rollback failure. Apply state may still record terminal failures, so retry with
`--reset-state` only after confirming the manifest and install directories are
safe.
