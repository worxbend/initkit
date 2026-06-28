# initkit

`initkit` is a Scala workstation bootstrap runner. It reads a Kubernetes-style
YAML profile, resolves variables and host facts, previews the selected work, and
then runs matching plan entries through either a plain CLI or an interactive TUI.

The runnable example profile is [config.example.yaml](config.example.yaml).

## Requirements

- JDK 21 or newer
- A Linux-like host for the package-manager workflows in `config.example.yaml`
- A terminal that supports alternate-screen apps for `tui`

The repository includes the Mill 1.1.7 bootstrap script, so a global `mill`
install is not required.

## Quick Start

Preview the example profile without changing the machine:

```bash
./mill app.run apply --config config.example.yaml --dry-run
```

Run the same profile:

```bash
./mill app.run apply --config config.example.yaml
```

Open the interactive TUI:

```bash
./mill app.run tui --config config.example.yaml
```

Show command help without opening the full-screen TUI:

```bash
./mill app.run --help
./mill app.run apply --help
./mill app.run tui --help
```

## Manifest Shape

Profiles use this top-level structure:

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

`spec.target.os` is informational. It documents the system the profile was
written for and is shown to users, but it does not select plan entries by
itself. Selection is controlled by each plan entry's `when` block evaluated
against the current host facts detected from the JVM, `/etc/os-release`, CPU
architecture, and executable lookup on `PATH`.

Variables use `${name}` interpolation. Runtime variables include `HOME` and
`USER`; host-derived variables include names such as `host.os.family`,
`host.os.distribution`, `host.os.codename`, `host.architecture`, `osFamily`, and
`arch`. `spec.vars` can reference runtime, host, and earlier/later manifest
variables. Shell syntax such as `$(...)` and backticks is treated as literal
text, not executed.

## Supported Plan Kinds

Package kinds:

- `apt-packages`
- `pacman-packages`
- `dnf-packages`
- `zypper-packages`
- `flatpak-packages`
- `snap-packages`

Installer kinds:

- `binary-downloads`
- `shell-scripts`
- `nerd-fonts`
- `dotfiles-apply`
- `interrupt`
- `commands`

Each `spec.plan` entry needs a unique `name`, one supported `kind`, and a
kind-specific `spec`. Optional `execution` settings include `mode: sequential`
or `mode: parallel`, `maxConcurrency`, `failFast`, and `locks`. Optional `when`
selectors support host OS matches and `commandExists`.

`spec.sources` can describe package-manager source setup for apt, dnf, zypper,
and flatpak. The CLI and TUI show generated source setup operations in previews;
the apt `updateBeforeInstall` marker also causes apt package entries to run
`apt-get update` before package installation.

## CLI Workflow

`apply` loads the manifest, resolves variables, detects the host, loads or
creates state, filters plan entries, and runs the shared execution engine.

Useful options:

- `--config PATH`: YAML profile path. Defaults to `config.yaml`.
- `--dry-run`: preview operations without applying changes or writing engine state.
- `--only NAME_OR_KIND`: run matching entries by name or kind. Repeatable.
- `--skip NAME_OR_KIND`: skip matching entries by name or kind. Repeatable.
- `--state PATH`: read and write execution state in a separate JSON file.
- `--reset-state`: ignore an existing state file and start fresh.
- `--color auto|always|never` and `--no-color`: control ANSI output.
- `--debug` and `--debug-log PATH`: emit redacted diagnostic logs.

Examples:

```bash
./mill app.run apply --config config.example.yaml --dry-run
./mill app.run apply --config config.example.yaml --only apt-packages
./mill app.run apply --config config.example.yaml --skip snap-packages
./mill app.run apply --config config.example.yaml --state ~/.local/state/initkit/developer-workstation.state.json
```

## TUI Workflow

`tui` loads the same manifest and state model as `apply`, then opens a checklist
view. Runnable rows are selectable checkboxes; skipped, completed, failed,
interrupted, and running rows remain visible but disabled.

Start the TUI with the example profile:

```bash
./mill app.run tui --config config.example.yaml
```

Preselect or unselect entries before the UI opens:

```bash
./mill app.run tui --config config.example.yaml --select apt-packages --skip snap-packages
./mill app.run tui --config config.example.yaml --dry-run
```

Keyboard controls:

- Up/Down, Home/End: move focus
- Space/Enter: toggle the focused runnable row
- `a`: select all runnable rows
- `p`: preview selected rows
- `r`: run selected rows
- `R`: run all currently runnable rows
- `e`: resume from state
- `d`: append focused-row details to the log
- `q`: quit

## State, Interrupts, And Resume

Execution state is stored separately from the manifest as JSON. The state path
is chosen in this order:

1. `--state PATH`
2. resolved `spec.vars.stateFile`
3. a sibling file named `.<metadata.name>.state.json`

State is tied to the manifest name and a fingerprint of the resolved manifest.
If a state file belongs to a different profile or profile contents, initkit
stops and asks for `--reset-state`.

The `interrupt` plan kind cleanly stops a run, writes the configured state file,
prints instructions, and exits with the configured code. The example manifest
uses this after shell packages are installed:

```bash
./mill app.run apply \
  --config config.example.yaml \
  --state ~/.local/state/initkit/developer-workstation.state.json

# after logging out/back in or otherwise satisfying the instructions:
./mill app.run apply \
  --config config.example.yaml \
  --state ~/.local/state/initkit/developer-workstation.state.json
```

The TUI uses the same state file:

```bash
./mill app.run tui \
  --config config.example.yaml \
  --state ~/.local/state/initkit/developer-workstation.state.json
```

## Safety Model

- Use `--dry-run` first. Dry-run mode prints command, file-write, download, and
  state-write previews without running installers or writing engine state.
- Manifest validation rejects unsupported API versions, unknown plan kinds,
  duplicate or missing plan names, invalid execution settings, invalid checksum
  algorithms, malformed installer specs, and interrupt state paths that reuse the
  manifest path.
- Host selection is explicit through `when`; non-matching entries are skipped
  with reasons instead of being run opportunistically.
- Commands preserve direct argv boundaries. Shell execution is used only for
  plan kinds or manifest fields that explicitly model shell text.
- Logs and debug output redact sensitive arguments, environment values, and
  token/password-shaped text.
- Downloads install through temporary files, verify configured SHA-256/SHA-512
  checksums, set requested file modes, and then move into place.
- State writes create parent directories and use an atomic move when the
  filesystem supports it.

## Development Checks

```bash
./mill __.compile
./mill __.test
```

Tamboui is currently consumed from Sonatype snapshot builds, configured in
[build.mill](build.mill).
