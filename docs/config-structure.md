# Config Structure

Initkit profiles are YAML documents inspired by Kubernetes manifests.

The shape is intentionally plain:

```yaml
apiVersion: initkit.io/v1alpha1
kind: WorkstationProfile

metadata:
  name: developer-workstation

spec:
  target: {}
  policy: {}
  vars: {}
  sources: {}
  plan: []
```

## Top-Level Fields

### `apiVersion`

Currently:

```yaml
apiVersion: initkit.io/v1alpha1
```

### `kind`

Currently:

```yaml
kind: WorkstationProfile
```

### `metadata`

Human and tooling metadata.

```yaml
metadata:
  name: developer-workstation
  labels:
    role: development
  annotations:
    initkit.io/description: My laptop setup
```

`metadata.name` is important because state files are tied to the profile name.

## `spec.target`

Target OS data is informational. Initkit can show it to the user, but it does not decide which steps run.

```yaml
target:
  os:
    family: linux
    distribution: ubuntu
    version: "24.04"
    codename: noble
    architecture: amd64
    desktop: gnome
```

Actual selection happens through each plan entry's `when` block.

## `spec.policy`

Global behavior for the run.

```yaml
policy:
  dryRun: false
  continueOnError: false
  requireSudo: true
  reboot:
    allowed: false
    prompt: true
```

Fields:

- `dryRun`: preview by default.
- `continueOnError`: continue to later top-level entries after a failed entry.
- `requireSudo`: package/source commands use sudo by default.
- `reboot.allowed`: reserved policy signal for workflows that may require reboot.
- `reboot.prompt`: reserved policy signal for interactive confirmation.

## `spec.vars`

Variables are simple string substitutions.

```yaml
vars:
  user: "${USER}"
  home: "${HOME}"
  binDir: "${HOME}/.local/bin"
  stateFile: "${HOME}/.local/state/initkit/workstation.state.json"
```

Runtime variables include:

- `${HOME}`
- `${USER}`

Host-derived variables include:

- `${host.os.family}`
- `${host.os.distribution}`
- `${host.os.version}`
- `${host.os.codename}`
- `${host.architecture}`
- `${osFamily}`
- `${arch}`

Variables are not shell. Initkit does not execute `$(...)`, backticks, or shell expansions.

## `spec.sources`

Source setup is separate from package installation. That keeps repository/remotes setup visible and reusable.

Supported sections:

- `apt`
- `dnf`
- `zypper`
- `flatpak`

Example:

```yaml
sources:
  apt:
    repositories:
      - name: docker
        keyUrl: https://download.docker.com/linux/ubuntu/gpg
        source: "deb [arch=amd64] https://download.docker.com/linux/ubuntu noble stable"
    updateBeforeInstall: true

  flatpak:
    remotes:
      - name: flathub
        url: https://flathub.org/repo/flathub.flatpakrepo
        ifMissing: true

  dnf:
    keyImports:
      - name: microsoft
        url: https://packages.microsoft.com/keys/microsoft.asc
    releasePackages:
      - name: rpmfusion-free
        url: https://download1.rpmfusion.org/free/fedora/rpmfusion-free-release-%fedora.noarch.rpm
    repositories:
      - name: vscode
        description: Visual Studio Code
        baseUrl: https://packages.microsoft.com/yumrepos/vscode
        gpgKey: https://packages.microsoft.com/keys/microsoft.asc

  zypper:
    repositories:
      - name: packman
        url: https://ftp.gwdg.de/pub/linux/misc/packman/suse/openSUSE_Tumbleweed/
        autoRefresh: true
    commands:
      - name: opi codecs
        run: opi codecs
        sudo: false
```

Source setup only runs when selected package entries need it.

## `spec.plan`

The plan is a top-to-bottom list of work.

```yaml
plan:
  - name: apt-base-cli
    kind: apt-packages
    description: Core terminal tools for Ubuntu.
    execution:
      mode: sequential
      locks:
        - system-package-manager
    when:
      os:
        family: linux
        distribution: ubuntu
    spec:
      install:
        - git
        - curl
        - jq
```

Every entry needs:

- `name`: unique inside the plan.
- `kind`: one supported installer kind.
- `spec`: the fields for that kind.

Optional fields:

- `description`
- `execution`
- `when`

## Execution

```yaml
execution:
  mode: sequential
  maxConcurrency: 4
  failFast: true
  locks:
    - system-package-manager
```

Modes:

- `sequential`: run internal items one at a time.
- `parallel`: run internal items concurrently with `maxConcurrency`.

Package-manager entries are special: package items are always attempted one by one. One bad package name does not stop the rest of the package list.

## Conditions

```yaml
when:
  os:
    family: linux
    distribution:
      oneOf:
        - ubuntu
        - debian
```

Supported examples:

```yaml
when:
  os:
    distribution: fedora
```

```yaml
when:
  commandExists: systemctl
```

Conditions are evaluated against detected host facts, not `spec.target`.

## Package Kinds

### `apt-packages`

```yaml
kind: apt-packages
spec:
  update: true
  actions:
    - upgrade
    - dist-upgrade
  install:
    - build-essential
    - git
```

### `dnf-packages`

```yaml
kind: dnf-packages
spec:
  actions:
    - check-update
    - action: swap
      args: [ffmpeg-free, ffmpeg]
    - action: groupupdate
      args: ["multimedia"]
  install:
    - "@development-tools"
    - git
```

### `pacman-packages`

```yaml
kind: pacman-packages
spec:
  sync: true
  actions:
    - sync-upgrade
  install:
    - base-devel
    - git
```

### `zypper-packages`

```yaml
kind: zypper-packages
spec:
  refresh: true
  actions:
    - dup
    - action: dup-from
      args: [packman, --allow-vendor-change]
  install:
    - patterns-devel-base-devel_basis
    - git
```

### `flatpak-packages`

```yaml
kind: flatpak-packages
spec:
  remote: flathub
  system: true
  install:
    - org.mozilla.firefox
```

### `snap-packages`

```yaml
kind: snap-packages
spec:
  install:
    - name: code
      classic: true
    - name: postman
```

### `aur-packages`

Installs AUR packages one package per command with `paru` by default, or a configured helper.

```yaml
kind: aur-packages
spec:
  helper: yay
  install:
    - visual-studio-code-bin
```

### `cargo-packages`

Installs Rust CLI tools one tool per command with `cargo binstall` by default.

```yaml
kind: cargo-packages
spec:
  installer: cargo-binstall
  install:
    - ripgrep
    - fd-find
```

Use `installer: cargo` to run `cargo install <tool>`.

### `sdkman-packages`

Installs SDKMAN candidates through `sdk install`.

```yaml
kind: sdkman-packages
spec:
  install:
    - candidate: java
      version: 21.0.4-tem
    - gradle
    - maven
```

## Installer Kinds

### `binary-downloads`

Downloads binaries or archives and installs selected files.

```yaml
kind: binary-downloads
execution:
  mode: parallel
  maxConcurrency: 4
spec:
  items:
    - name: kubectl
      url: https://dl.k8s.io/release/v1.30.2/bin/linux/amd64/kubectl
      destination: "${binDir}/kubectl"
      mode: "0755"
      checksum:
        algorithm: sha256
        value: replace-with-real-sha256
```

Archive example:

```yaml
archive:
  type: tar.gz # also: zip, tar.xz
  stripComponents: 1
  path: linux-amd64/helm
symlinks:
  - path: /usr/local/bin/helm
    target: "${binDir}/helm-v3.15.2"
    sudo: true
```

Dry-run output shows the download/install preview and any generated symlink
command.

### `shell-scripts`

Downloads and runs shell installers.

```yaml
kind: shell-scripts
spec:
  items:
    - name: rustup
      url: https://sh.rustup.rs
      shell: sh
      args: [-s, --, -y]
      download: stdin
      mode: unattended
      env:
        RUSTUP_INIT_SKIP_PATH_CHECK: "yes"
      creates: "${HOME}/.cargo/bin/rustc"
      cleanup: true
```

Downloaded installer files use `download: file`. Set `sudo`, `cwd`, `timeout`,
and `allowedExitCodes` on an item when the installer needs them.

### `file-writes`

Writes arbitrary files through a temporary file and an `install` command.

```yaml
kind: file-writes
spec:
  items:
    - name: sddm-sway
      path: /etc/sddm.conf.d/sway.conf
      content: |
        [General]
        DisplayServer=wayland
      sudo: true
      owner: root
      group: root
      mode: "0644"
```

### `nerd-fonts`

Runs `worxbend/nerd-font-installer` with generated config.

```yaml
kind: nerd-fonts
spec:
  tool:
    path: "${binDir}/nerdfont-install"
    args: [-config, "${nerdFontConfig}"]
  config:
    path: "${nerdFontConfig}"
    create: true
    content:
      release: latest
      destination: "${HOME}/.local/share/fonts/NerdFonts"
      families: [JetBrainsMono, Hack, FiraCode]
  preview:
    enabled: true
    args: [-dry-run]
```

### `dotfiles-apply`

Clones/updates dotfiles and runs `worxbend/dotbot-go`.

```yaml
kind: dotfiles-apply
spec:
  tool:
    path: "${binDir}/dotbot-go"
    args: [-d, "${dotfilesDir}", -c, "${dotfilesConfig}"]
  repository:
    url: https://github.com/w0rxbend/system-bootstrap.git
    ref: main
    destination: "${dotfilesDir}"
    update: true
  config:
    path: "${dotfilesConfig}"
  preview:
    enabled: true
    args: [--dry-run]
```

### `interrupt`

Stops the run intentionally and writes state.

```yaml
kind: interrupt
spec:
  reason: Log out and back in before continuing.
  state:
    path: "${stateFile}"
    format: json
    resumeFrom: next
  instructions:
    - Log out.
    - Log back in.
    - "Run: initkit apply --config profile.yaml --state ${stateFile}"
  exit:
    code: 75
    message: Bootstrap paused.
```

### `commands`

Runs explicit shell commands.

```yaml
kind: commands
spec:
  items:
    - name: enable-docker
      run: systemctl enable --now docker
      sudo: true
      cwd: /tmp
      env:
        FOO: bar
      creates: /tmp/docker-enabled
      unless: systemctl is-enabled docker
      allowedExitCodes: [0, 3]
      confirm: Enable Docker at boot?
      timeout: 30
      when:
        commandExists: systemctl
```

Use `commands` for small host operations such as user group changes, login shell
changes, system service commands, simple Git clones, Git config, and time setup.
Dry-run output includes guard notes for `creates`, `unless`, non-default
`allowedExitCodes`, and `confirm`.

## State Files

Use `--state` when you want a stable resume path:

```bash
initkit apply --config profile.yaml --state ~/.local/state/initkit/profile.state.json
```

State stores completed, failed, interrupted, skipped, and resume metadata. If the manifest changes, Initkit rejects stale state unless `--reset-state` is used.
