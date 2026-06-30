# Manifest Reference

Date: 2026-06-30

Supported identity:

```yaml
apiVersion: binstaller.io/v1alpha1
kind: BinaryDistributionProfile
```

The manifest is a single profile with metadata, policy, variables, version
sources, and an ordered `spec.plan`.

## Top-Level Shape

```yaml
metadata:
  name: developer-binaries
  labels:
    role: development
  annotations:
    binstaller.io/description: Example profile.

spec:
  policy:
    mode: developer
    appsDir: "${HOME}/.apps"
    continueOnError: false
    requireConfirmation: true
    allowSudoSymlinks: true
    stateFile: developer-binaries.state.json
  vars:
    arch: amd64
  versions:
    kubectl:
      resolver:
        type: http-text
        url: https://dl.k8s.io/release/stable.txt
  plan: []
```

`metadata.name` participates in state compatibility. The ordered `plan` order is
the default render and apply order.

## Policy

- `mode`: optional policy profile. Defaults to `developer`. Supported values
  are `developer` and `strict`.
- `appsDir`: root directory that resolved install directories must stay under.
- `continueOnError`: when `true`, apply continues after a failed tool and still
  exits nonzero if any tool failed.
- `requireConfirmation`: when `true`, apply requires `--yes`.
- `allowSudoSymlinks`: must be `true` before any plan entry may declare
  `sudo: true` symlinks.
- `stateFile`: optional current-directory filename used by apply resume.
- `cleanInstall` is decoded for compatibility with the profile shape and is not
  a command control.

Developer mode preserves the historical behavior for local tooling profiles:
dynamic latest URLs, missing checksums, and the system `tar.xz` fallback are
allowed by default. Sudo symlinks still require `allowSudoSymlinks: true`.

Strict mode rejects production-sensitive risks by default:

- `dynamic.latest-url` version sources and download URLs containing `/latest`.
- Missing `download.checksum` values.
- `sudo: true` symlinks unless `allowSudoSymlinks: true`.
- `tar.xz` archives, because they currently use the system `tar` fallback.
- Archive candidate fallback, if a later extractor adds candidate discovery.

Strict profiles can opt into reviewed exceptions with explicit booleans:

```yaml
policy:
  mode: strict
  allowDynamicLatestUrls: true
  allowMissingChecksums: true
  allowSudoSymlinks: true
  allowTarXzFallback: true
  allowArchiveCandidateFallback: true
```

State files are not written by `plan`. Apply rejects absolute, nested, or empty
state paths.

## Variables And Versions

Interpolation supports literal `${name}` references. Shell forms such as
`$(cmd)` are not executed.

Available variables include runtime variables such as `HOME`, manifest
`spec.vars`, policy-derived `appsDir`, tool-local `version`, `installDir`, and
`downloadPath`.

Version sources:

```yaml
versions:
  yazi: v26.5.6

  kubectl:
    resolver:
      type: http-text
      url: https://dl.k8s.io/release/stable.txt

  neovim:
    dynamic:
      type: latest-url
      note: GitHub latest Neovim Linux archive endpoint.
```

- A string value is a pinned version.
- `resolver.type: http-text` fetches HTTPS text and uses it as the concrete
  version.
- `dynamic.type: latest-url` intentionally keeps the version displayed as
  `dynamic latest-url`.
- `versions` detects GitHub release download URLs such as
  `https://github.com/jj-vcs/jj/releases/download/v${version}/...`, queries the
  repository's latest release tag, and prints the newer version in the package
  summary table when an update is available.

## Direct Binary

A direct binary has no `archive` block. The downloaded bytes are written to the
first executable path under the install directory, mode is applied, executables
are verified, then the staged install replaces the previous install.

```yaml
plan:
  - name: kubectl
    kind: binary-tool
    description: Kubernetes command-line client.
    spec:
      versionRef: kubectl
      installDir: "${appsDir}/kubectl"
      download:
        url: "https://dl.k8s.io/release/${version}/bin/linux/amd64/kubectl"
        filename: kubectl
      executables:
        - path: bin/kubectl
          mode: "0755"
```

## Archives

Supported archive types are `zip`, `tar.gz`, and `tar.xz`.

`zip` and `tar.gz` use native extraction paths. `tar.xz` uses a structured
system `tar` fallback into private staging, then copies only declared mapped
members.

File mapping example:

```yaml
download:
  url: "https://get.helm.sh/helm-${version}-linux-amd64.tar.gz"
  filename: helm-linux-amd64.tar.gz
  checksum:
    algorithm: sha256
    value: 0a745198de24545d0055cd8414bc8d2ba10363ef5f5d38369ea1b399671cc083
  archive:
    type: tar.gz
    extract:
      files:
        - from: linux-amd64/helm
          to: bin/helm
executables:
  - path: bin/helm
```

Directory mapping example:

```yaml
download:
  url: https://github.com/neovim/neovim/releases/latest/download/nvim-linux-x86_64.tar.gz
  filename: neovim.tar.gz
  archive:
    type: tar.gz
    extract:
      directories:
        - from: nvim-linux-x86_64
          to: "."
executables:
  - path: bin/nvim
```

Archive `from` paths identify archive members. `to` paths are relative install
targets. Absolute paths, traversal, backslashes, control characters, duplicate
sources, and duplicate targets are rejected.

## Checksums

Only SHA-256 is supported:

```yaml
checksum:
  algorithm: sha256
  value: 45d49e064d8684926fed97ad051c6ecebbf796a3c709edaa7a4a166b2978633d
```

Configured SHA-256 values must be exactly 64 hexadecimal characters. Apply
verifies checksums before staging replacement. Missing checksums remain allowed
in developer mode, but plan surfaces mark them as risk. Strict mode rejects
missing checksums unless `policy.allowMissingChecksums: true` is set.

Profiles can also opt into checksum discovery from an upstream-published
`sha256sum` file:

```yaml
checksum:
  algorithm: sha256
  discover:
    type: sha256sum
    url: "https://example.invalid/releases/${version}/SHA256SUMS"
    file: "tool-${version}-linux-amd64.tar.gz"
```

`discover.type: sha256sum` fetches the HTTPS text file through the built-in text
client and parses standard `<sha256> <file>` lines. `file` is optional; when it
is omitted, the resolved `download.filename` is matched. Discovery is data-only:
it does not run shell commands. Plan, `versions`, lock output, and checksum
mismatch diagnostics label checksums as configured, discovered, or missing.

## Symlinks

Local symlinks stay within the install directory:

```yaml
symlinks:
  - path: bin/lzg
    target: bin/lazygit
```

Sudo symlinks require `policy.allowSudoSymlinks: true` and `apply --yes`:

```yaml
symlinks:
  - path: /usr/local/bin/nvim
    target: "${appsDir}/neovim/bin/nvim"
    sudo: true
```

Apply executes privileged symlinks as structured argv equivalent to
`sudo ln -sfn <target> <path>`.

## Selection

Selection is a command option, not a manifest field.

```bash
binstaller plan --config config.example.yaml --only yazi
binstaller apply --config config.example.yaml --skip neovim --yes
```

`--only` and `--skip` may be repeated. `--only` is applied first, `--skip` is
applied second, and manifest order is preserved. Unknown names are selection
errors.

## Unsupported Fields

Installer script blocks are not supported:

```yaml
installer:
  shell: bash
```

The loader rejects installer blocks with a validation error. Use a direct binary
or archive-backed download instead.
