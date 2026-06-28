# Initkit Implementation Plan

This plan describes how to implement the runner for the manifest shape in
`config.example.yaml`. The goal is a safe workstation bootstrap tool that reads
a Kubernetes-style YAML profile, resolves variables and host facts, previews the
work, then executes matching plan entries in order.

## Target Behavior

`initkit` should support a manifest with:

- `apiVersion`, `kind`, `metadata`, and `spec`
- informational `spec.target.os`
- global `spec.policy`
- variable interpolation through `spec.vars`
- package source setup through `spec.sources`
- ordered `spec.plan` entries
- one installer `kind` per plan entry
- sequential or parallel execution within each plan entry
- conditional execution through `when`
- explicit interrupt points that write a separate state file and stop cleanly
- dry-run previews before destructive or filesystem-changing work

The first usable command should be:

```bash
./mill app.run apply --config config.example.yaml --dry-run
./mill app.run apply --config config.example.yaml
./mill app.run apply --config config.example.yaml --state ~/.local/state/initkit/developer-workstation.state.json
./mill app.run tui --config config.example.yaml
```

The application has two user-facing modes:

- Plain CLI mode: reads the config file and runs the selected plan entries without an interactive UI.
- TUI mode: reads the same config file, renders the plan with TamboUI widgets, lets the user select plan entries with checkboxes, then runs the selected entries through the same execution engine.

## Tech Stack

Use the existing JVM/Scala stack in this repository.

- Scala 3, currently `3.8.2` in the existing build metadata
- Mill, using the checked-in `./mill` launcher
- JDK 21 or newer, required by Ox
- uTest for unit tests
- os-lib for filesystem and process helpers where it fits
- upickle for JSON output where already used
- SnakeYAML Engine, or another maintained JVM YAML parser, for manifest loading
- picocli for command-line parsing
- fansi for colorful plain CLI output
- TamboUI for terminal UI flows
- Ox for structured concurrency, bounded parallelism, cancellation, retries, and timeouts
- sttp client4 for HTTP downloads

Build-file expectations:

- prefer declarative Mill YAML files: `build.mill.yaml` and module-local `package.mill.yaml` for simple module configuration
- use module-local `package.mill` files when a subfolder needs programmable build logic or custom reusable targets
- use a multi-module build; keep module dependencies explicit, acyclic, and meaningful
- use Mill `ScalaModule`, not `SbtModule`
- define each Scala module with `extends: ScalaModule`
- define tests as module-local `<module>/test/package.mill.yaml` files extending `[build.<module>.ScalaTests, TestModule.Utest]`
- migrate source layout to Mill's ScalaModule defaults: `<module>/src` and `<module>/test/src`
- split build logic by subfolder so modules can be compiled independently when their `package.mill` or `package.mill.yaml` changes
- use programmable `build.mill` or `package.mill` only if YAML cannot express a required customization
- promote reusable utilities, fixtures, code generators, packaging logic, or other extractable pieces to dedicated Mill modules/targets instead of hiding them in `app` or a generic `util` package
- remove `mainargs` after picocli command parsing is in place
- add `info.picocli:picocli:4.7.7` unless a newer compatible version is selected deliberately
- add `com.lihaoyi::fansi:0.5.1`, latest stable checked on 2026-06-28, to the CLI module for terminal styling
- add `org.snakeyaml:snakeyaml-engine:3.0.1`, latest stable checked on 2026-06-28
- add `com.softwaremill.ox::core:1.0.5`, latest stable checked on 2026-06-28
- add `com.softwaremill.sttp.client4::core:4.0.25`, latest stable checked on 2026-06-28
- before implementation, re-check Maven Central and use newer stable releases if available
- add `.scalafmt.conf` before substantial implementation work; use Scala 3 dialect and document the Mill command that checks formatting
- keep the TamboUI snapshot repository configured because TamboUI is currently published as snapshots
- keep existing TamboUI modules already in use
- consider adding `dev.tamboui:tamboui-picocli:<same snapshot version>` when the TUI command is wired through picocli

Progress note, 2026-06-28: T001 migrated the current workspace from the
sbt-compatible Mill shape to `ScalaModule` defaults. Production sources now
live under `app/src`, tests under `app/test/src`, and `build.mill` declares
`object app extends ScalaModule` with `object test extends ScalaTests`. README
commands remain accurate because the module name and `app.run`/`app.test`
targets did not change. `./mill __.compile` and `./mill __.test` both pass.

Progress note, 2026-06-28: T002 split the starter shell into explicit Mill
modules with an acyclic `app -> cli -> tui` dependency direction.
`app/src/initkit/Main.scala` now only delegates to `initkit.cli.InitkitCli` and
exits with its return code. Picocli command classes and CLI tests live under
`cli/src/initkit/cli` and `cli/test/src/initkit/cli`; the existing TamboUI
starter lives under `tui/src/initkit/tui`. `./mill __.compile`,
`./mill __.test`, and `./mill app.run --help` all pass.

Progress note, 2026-06-28: T001 updated `build.mill` with picocli, SnakeYAML
Engine, Ox core, and sttp client4 versions from canonical Maven metadata and
removed the direct `mainargs` dependency. The existing `info` and `tui` starter
commands were minimally moved to picocli so the build no longer needs
`mainargs`. Required Mill checks are still pending in a network-enabled JVM
environment because this sandbox blocks Mill/coursier downloads with
`java.net.SocketException: Operation not permitted`.

Progress note, 2026-06-28: T002 replaced the temporary CLI with a picocli
`initkit` root command exposing `apply`, `info`, and `tui` subcommands.
`apply` and `tui` now share `--config`, `--state`, and `--reset-state` options;
`apply` parses `--dry-run`, `--yes`, `--only`, and `--skip`; `tui` parses
`--dry-run`, `--select`, and `--skip`. Both `apply` and `tui` return clear
non-stacktrace errors for missing config paths. Focused CLI tests were added,
but Mill validation still cannot reach compilation in this sandbox because
coursier downloads fail with `java.net.SocketException: Operation not permitted`.

Progress note, 2026-06-28: T003 added a dedicated Mill `config` module owning
the public `initkit.config` manifest models and SnakeYAML Engine loader for
top-level manifest metadata, policy, target, sources, plan entries, execution
settings, conditions, and raw kind-specific plan specs. `config.example.yaml`
coverage lives in `config/test/src`, including a path-aware invalid-YAML parse
error check that asserts the loader returns a parse failure without
stacktrace-shaped output. `./mill __.compile` and `./mill __.test` both pass
for the current `config`, `app`, `cli`, and `tui` module graph.

Progress note, 2026-06-28: T004 added semantic manifest validation in the
`config` module. `ManifestLoader.loadValidated` now returns aggregated
`ValidationFailure` errors for unsupported `apiVersion`/top-level `kind`,
missing and duplicate plan names, unknown plan kinds, invalid execution modes
and `maxConcurrency`, package entries with empty `install` lists, unsupported
binary checksum algorithms, and invalid interrupt state settings including
state-file reuse of the manifest path. Focused validator tests cover both
single-rule failures and multi-error aggregation. `./mill __.compile` and
`./mill __.test` pass.

Progress note, 2026-06-28: T005 added typed package and source specs in the
`config` module. `spec.sources.apt`, `dnf`, `zypper`, and `flatpak` now decode
into typed repository/remote models while preserving raw source YAML. Package
plan entries for apt, pacman, dnf, zypper, flatpak, and snap now decode through
`PackageSpecDecoder`, and manifest validation uses that decoder so empty
`install` lists report the related plan entry name. Focused tests cover one
valid and one invalid package entry for every package manager kind, and source
typing is covered against `config.example.yaml`. `./mill __.compile`,
`./mill __.test`, and `git diff --check` pass.

Progress note, 2026-06-28: T006 added typed installer specs in the `config`
module for the non-package plan kinds represented in `config.example.yaml`:
`binary-downloads`, `shell-scripts`, `nerd-fonts`, `dotfiles-apply`,
`interrupt`, and `commands`. `ManifestValidator` now delegates those kinds to
`InstallerSpecDecoder`, so binary download URL/destination/mode, checksum and
archive fields, interrupt JSON state settings, and command/script/tool config
shape are validated through the same typed boundary future executors will use.
Focused tests cover one valid and one invalid entry for every installer kind.
`./mill __.compile`, `./mill __.test`, and `git diff --check` pass.

Validation checkpoint VALIDATION-9, 2026-06-28: loop state and build metadata
were rechecked for the completed T004-T006 manifest validation and typed
decoder chunk. `./mill --no-daemon resolve _` confirms the current module graph
still exposes `app`, `cli`, `config`, and `tui`. The configured recursive
checks passed: `./mill __.compile` and `./mill __.test`. Full recursive tests
ran the CLI suites plus `ManifestLoaderTests`, `ManifestValidatorTests`,
`PackageSpecDecoderTests`, and `InstallerSpecDecoderTests` successfully.
`git diff --check` also passed. `.scalafmt.conf` exists, but no local
`scalafmt` executable or Mill formatting target is configured, so formatter
validation remains unavailable in this workspace.

Progress note, 2026-06-28: T008 added a standalone `host` module for host fact
detection. `HostDetector` reads Linux distribution fields from `/etc/os-release`
when available, normalizes common JVM architecture names to values such as
`amd64` and `arm64`, and checks command availability by probing executable files
under `PATH` without invoking package managers or other commands. Tests cover
representative os-release data, architecture aliases, PATH lookup, and fake host
facts for later condition evaluation. `./mill __.compile`, `./mill __.test`,
and `git diff --check` pass.

Progress note, 2026-06-28: T009 added a `core` module for plan-condition
evaluation that depends on `config` and `host`. `ConditionEvaluator` evaluates
optional plan `when` conditions against injected `HostFacts`, including exact
and `oneOf` OS selectors and `commandExists` checks, and returns structured
skip reasons with user-facing messages for future CLI/TUI display. Focused
tests cover matched distribution conditions, skipped distribution mismatches,
available commands, and missing-command skips. `./mill core.compile`,
`./mill core.test`, `./mill __.compile`, `./mill __.test`, and
`git diff --check` pass.

Progress note, 2026-06-28: T010 added manifest variable resolution in the
`core` module. `ManifestVariableResolver` resolves `${name}` placeholders from
runtime variables, `spec.vars`, and host facts, resolving spec variables before
walking typed manifest fields and raw YAML sources/plan specs. Unresolved
variables return validation errors with field paths and plan-entry context, and
the resolver treats shell syntax such as `$(...)` and backticks as literal text.
Focused tests cover `config.example.yaml`, nested spec variables, repeated
variables, host facts, unresolved variables, and shell-literal command text.
`./mill core.test`, `./mill __.compile`, `./mill __.test`, and
`git diff --check` pass.

Validation checkpoint VALIDATION-13, 2026-06-28: loop state and build metadata
were rechecked for the current completed chunk. Mill discovery confirms the
recursive compile targets include `app`, `cli`, `config`, `core`, `host`, and
`tui`, and recursive test targets include `cli`, `config`, `core`, and `host`.
The configured checks passed: `./mill __.compile` and `./mill __.test`.
Additional validation passed: `git diff --check`, `jq empty
.agent-loop/tasks.json`, and `./mill app.run --help`. `.scalafmt.conf` exists,
but no local `scalafmt` executable or Mill formatting target is configured, so
formatter validation remains unavailable. No source fix was needed; remaining
risk is limited to future tasks wiring the resolved manifest into selection,
state, and execution behavior.

Progress note, 2026-06-28: T011 added shared plan selection in the `core`
module. `PlanSelector` applies normalized `--only` and `--skip` selectors
against plan entry names or kinds, evaluates `when` conditions through
`ConditionEvaluator`, treats completed entry names as state placeholders, and
returns manifest-order runnable entries plus skipped entries with structured
user-facing reasons. Focused tests cover name/kind selection, skip filtering,
condition skips, completed placeholders, and order preservation. Checks passed:
`./mill core.test`, `./mill __.compile`, `./mill __.test`, and
`git diff --check`.

Validation checkpoint T012, 2026-06-28: added a focused core regression test
that loads `config.example.yaml`, resolves runtime and host variables, evaluates
conditions against fake Ubuntu host facts, and selects runnable/skipped plan
entries in manifest order. Recursive validation passed with `./mill __.compile`
and `./mill __.test`; `git diff --check` and `jq empty .agent-loop/tasks.json`
also pass.

Progress note, 2026-06-28: T015 added the first execution engine skeleton in
the `core` module. `ExecutionEngine` processes selected entries in manifest
order, emits scheduled/started/skipped/completed/failed/interrupted/dry-run
events, writes state after terminal apply-mode transitions, honors
`continueOnError`, and handles manifest `interrupt` entries directly so they
write their configured state file, expose resume instructions, and return the
configured exit code. State helpers now cover running, skipped, failed, and
interrupted transitions in addition to completed entries. Focused engine tests
cover order, condition and completed skips, fail-fast, continue-on-error,
dry-run emission, and interrupt resume behavior. `./mill core.test`,
`./mill __.compile`, `./mill __.test`, `git diff --check`, and
`jq empty .agent-loop/tasks.json` pass.

Validation checkpoint, 2026-06-28: `./mill app.compile` and `./mill app.test`
were rerun for the completed T001-T003 chunk. Daemon-mode Mill cannot start its
localhost server in this sandbox (`java.net.SocketException: Operation not
permitted`, followed by missing `out/mill-daemon/socketPort`). The documented
fallback `COURSIER_CACHE=/tmp/initkit-coursier-cache ./mill --no-daemon
app.compile` and `... app.test` reaches dependency resolution, but Maven
artifact downloads are also blocked with `java.net.SocketException: Operation
not permitted`. No source-level compile or test failure was reached; rerun the
same checks in an environment that permits local server sockets and Maven
downloads.

Validation checkpoint 6, 2026-06-28: project metadata was rechecked
(`build.mill`, `README.md`, and absence of scalafmt/scalafix/package-manager
metadata); the only project-native checks remain `./mill app.compile` and
`./mill app.test`. Both configured checks still fail before build compilation in
daemon mode because the Mill server cannot create the localhost server socket.
Fallback no-daemon runs with `COURSIER_CACHE=/tmp/initkit-coursier-cache` reach
dependency resolution, but Maven artifact downloads from `repo1.maven.org` are
blocked with `java.net.SocketException: Operation not permitted`. No source or
test regression was reached, so no code fix was made; rerun the same checks in
an environment that permits local server sockets and Maven downloads.

Validation checkpoint 7, 2026-06-28: metadata was checked again (`build.mill`,
`README.md`, and absence of scalafmt/scalafix/package-manager metadata). The
configured `./mill app.compile` and `./mill app.test` commands still fail before
build compilation because the daemon cannot bind its localhost server socket in
this sandbox (`java.net.SocketException: Operation not permitted`, followed by
missing `out/mill-daemon/socketPort`). `./mill --no-daemon resolve _` succeeds
and confirms the `app` module is discoverable. Fallback validation with
`COURSIER_CACHE=/tmp/initkit-coursier-cache ./mill --no-daemon app.compile` and
`... app.test` reaches dependency resolution but cannot download Scala/Mill
artifacts from Maven Central because network sockets are blocked. No source or
test failure was reached; rerun compile and tests in an environment that permits
local server sockets and Maven/coursier downloads.

Validation checkpoint 8, 2026-06-28: metadata was rechecked and still exposes
only the Mill `app` module with uTest; no scalafmt, scalafix, Makefile, justfile,
GitHub workflow, sbt, Maven, npm, or other project-native validation metadata
was found. The configured `./mill app.compile` and `./mill app.test` commands
again fail before build compilation because daemon startup cannot create/bind
its local server socket in this sandbox, leaving `out/mill-daemon/socketPort`
missing. `./mill --no-daemon resolve _` succeeds and confirms build discovery.
Fallback `COURSIER_CACHE=/tmp/initkit-coursier-cache ./mill --no-daemon
app.compile` and `... app.test` reach dependency resolution but fail before
source compilation because Maven Central downloads are blocked with
`java.net.SocketException: Operation not permitted`. No source or test failure
was reached, so no code fix was made; rerun the same compile and test checks in
an environment that permits local server sockets and Maven/coursier downloads.

Validation checkpoint 9, 2026-06-28: metadata was rechecked (`build.mill`,
`README.md`, and absence of scalafmt/scalafix/Makefile/justfile/sbt/Maven/npm
metadata). The configured `./mill app.compile` and `./mill app.test` commands
again fail before build compilation because daemon startup cannot create/bind
its local server socket in this sandbox (`java.net.SocketException: Operation
not permitted`, followed by missing `out/mill-daemon/socketPort`). The
no-daemon discovery command `./mill --no-daemon resolve _` succeeds and confirms
the `app` module is present. Fallback validation with
`COURSIER_CACHE=/tmp/initkit-coursier-cache ./mill --no-daemon app.compile` and
`... app.test` reaches dependency resolution but cannot download Scala/Mill
artifacts from Maven Central because network sockets are blocked. No source or
test regression was reached, so no code fix was made; rerun the same compile
and test checks in an environment that permits local server sockets and
Maven/coursier downloads.

Validation checkpoint 11, 2026-06-28: metadata was rechecked (`build.mill`,
`README.md`, source/test layout, and absence of scalafmt/scalafix/Makefile/
justfile/sbt/Maven/npm/workflow validation metadata). The project-native checks
remain `./mill app.compile` and `./mill app.test`. Both configured daemon-mode
commands still fail before build compilation because Mill cannot create its
local server socket in this sandbox; `out/mill-daemon/server.log` reports
`java.net.SocketException: Operation not permitted`, and the launcher reports
missing `out/mill-daemon/socketPort`. `./mill --no-daemon resolve _` succeeds
and confirms the `app` module is discoverable. Fallback checks with
`COURSIER_CACHE=/tmp/initkit-coursier-cache ./mill --no-daemon app.compile` and
`... app.test` reach dependency resolution but cannot download Scala/Mill
artifacts from Maven Central because network sockets are blocked. No source or
test failure was reached, so no code fix was made; rerun the same checks in an
environment that permits local server sockets and Maven/coursier downloads.

Validation checkpoint 13, 2026-06-28: metadata was rechecked (`build.mill`,
`README.md`, Mill module discovery, and absence of scalafmt/scalafix/Makefile/
justfile/sbt/Maven/npm/workflow validation metadata). `./mill app.compile`
initially reached source compilation and failed because `picocli.CommandLine.Option`
shadowed Scala `Option` in `Main.scala`; the picocli annotation import is now
aliased as `CliOption`. `./mill app.test` then exposed test-scope issues:
`ManifestLoaderTests` used an unavailable bare `fail` helper and looked for
`config.example.yaml` under Mill's forked sandbox working directory. The tests
now use a local assertion helper and locate the fixture by walking up from the
forked working directory. Final `./mill app.compile`, `./mill app.test`, and
`./mill --no-daemon resolve _` pass. Remaining risk is limited to future
implementation tasks; this checkpoint reached source and test execution.

Validation checkpoint VALIDATION-5, 2026-06-28: loop state and build metadata
were rechecked for the completed T001-T003 chunk. The configured recursive
checks passed: `./mill __.compile` and `./mill __.test`. Full recursive tests
ran `ManifestLoaderTests`, `AppSnapshotTests`, and `InitkitCliTests`
successfully. `./mill --no-daemon resolve _` also confirms the `app`, `cli`,
`config`, and `tui` modules remain discoverable. A CLI smoke check
`./mill app.run --help` produced one transient Mill subprocess failure while
printing correct usage, then passed on two immediate reruns, so no code fix was
made. `.scalafmt.conf` is present, but no local `scalafmt` executable or Mill
formatter target is configured in this workspace.

Before implementing TUI-related work, scan the current TamboUI repository and
docs, not only the existing local wrapper:

- repository: <https://github.com/tamboui/tamboui>
- read `AGENTS.md` for repository-specific coding-agent instructions
- read `README.md`
- inspect the `docs/` directory
- inspect `demos/` and `tamboui-demos/`
- inspect `tamboui-toolkit`, `tamboui-tui`, `tamboui-jline3-backend`, and `tamboui-picocli`
- confirm the current API names because TamboUI is experimental and APIs may change

Use picocli's Scala-compatible annotation style:

- command classes implement `Callable[Int]` or `Runnable`
- use `@Command`, `@Option`, and `@Parameters`
- use `mixinStandardHelpOptions = true`
- start the CLI through `new CommandLine(new RootCommand()).execute(args: _*)`
- call `System.exit(exitCode)` from the application entrypoint

## Project Organization

Use a multi-module Mill build with `ScalaModule`. Prefer declarative YAML for
ordinary module configuration, and use co-located `package.mill` files for
subfolders that need custom build tasks or reusable build targets.

```text
build.mill.yaml
buildtools/
  package.mill
  src/initkit/build/...
common/
  package.mill.yaml
  src/initkit/common/...
  test/package.mill.yaml
  test/src/initkit/common/...
config/
  package.mill.yaml
  src/initkit/config/...
  test/package.mill.yaml
  test/src/initkit/config/...
core/
  package.mill.yaml
  src/initkit/{platform,resolve,state,logging}/...
  test/package.mill.yaml
  test/src/initkit/{platform,resolve,state,logging}/...
command/
  package.mill.yaml
  src/initkit/command/...
  test/package.mill.yaml
  test/src/initkit/command/...
download/
  package.mill.yaml
  src/initkit/download/...
  test/package.mill.yaml
  test/src/initkit/download/...
installers/
  package.mill.yaml
  src/initkit/installers/...
  test/package.mill.yaml
  test/src/initkit/installers/...
cli/
  package.mill.yaml
  src/initkit/cli/...
  test/package.mill.yaml
  test/src/initkit/cli/...
tui/
  package.mill.yaml
  src/initkit/tui/...
  test/package.mill.yaml
  test/src/initkit/tui/...
app/
  package.mill.yaml
  src/initkit/Main.scala
  test/package.mill.yaml
  test/src/initkit/...
dist/
  package.mill
```

The current repository now uses a single root `build.mill` `ScalaModule` with
Mill's default source layout. Future module-splitting work can still move to
Mill's declarative YAML `ScalaModule` configuration where it fits the planned
multi-module structure.

Use `build.mill.yaml` for shared defaults and workspace-level configuration
where possible, and `package.mill.yaml` for concrete modules that only need
simple settings. Use `package.mill` inside a subfolder when that subfolder owns
custom tasks, generated sources, reusable build traits, packaging commands, or
other build logic that should compile independently from the rest of the build.

Keep the Mill version pinned by the checked-in Mill launcher/version metadata;
do not invent unsupported version keys inside module YAML files.

Dedicated reusable modules and targets:

- `common`: small shared domain primitives, ADTs, error helpers, and pure utility code used by at least two production modules
- `buildtools`: build-only helper code, custom Mill traits, generated source helpers, or target definitions; keep it out of runtime dependencies
- `testkit`: add only if multiple modules need shared fakes, fixtures, temp-file helpers, or assertion helpers
- `dist`: packaging and distribution targets such as assembly, launcher scripts, checksums, release archives, or installable bundles

Do not create a shared module just because a helper exists. Extract only when
there is a clear second consumer, a clean standalone responsibility, or a build
target that should be callable directly from Mill. When extracted, expose it as
a named module/target with a stable command path such as `./mill common.test`,
`./mill buildtools.checkBuildMetadata`, or `./mill dist.archive`.

Target module examples:

```yaml
# common/package.mill.yaml
extends: ScalaModule
scalaVersion: 3.8.2
mvnDeps:
- com.lihaoyi::upickle:4.4.3
```

```yaml
# config/package.mill.yaml
extends: ScalaModule
scalaVersion: 3.8.2
moduleDeps: [common]
mvnDeps:
- org.snakeyaml:snakeyaml-engine:3.0.1
```

```yaml
# core/package.mill.yaml
extends: ScalaModule
scalaVersion: 3.8.2
moduleDeps: [common, config]
mvnDeps:
- com.lihaoyi::os-lib:0.11.8
- com.softwaremill.ox::core:1.0.5
```

```yaml
# command/package.mill.yaml
extends: ScalaModule
scalaVersion: 3.8.2
moduleDeps: [core]
```

```yaml
# download/package.mill.yaml
extends: ScalaModule
scalaVersion: 3.8.2
moduleDeps: [core]
mvnDeps:
- com.softwaremill.sttp.client4::core:4.0.25
```

```yaml
# installers/package.mill.yaml
extends: ScalaModule
scalaVersion: 3.8.2
moduleDeps: [config, core, command, download]
```

```yaml
# cli/package.mill.yaml
extends: ScalaModule
scalaVersion: 3.8.2
moduleDeps: [config, core, installers]
mvnDeps:
- info.picocli:picocli:4.7.7
- com.lihaoyi::fansi:0.5.1
```

```yaml
# tui/package.mill.yaml
extends: ScalaModule
scalaVersion: 3.8.2
moduleDeps: [config, core, installers]
mvnDeps:
- dev.tamboui:tamboui-toolkit:0.5.0-SNAPSHOT
- dev.tamboui:tamboui-jline3-backend:0.5.0-SNAPSHOT
```

```yaml
# app/package.mill.yaml
extends: ScalaModule
scalaVersion: 3.8.2
moduleDeps: [cli, tui]
mainClass: initkit.Main
```

Use a separate test module per production module:

```yaml
# config/test/package.mill.yaml
extends: [build.config.ScalaTests, TestModule.Utest]
mvnDeps: [com.lihaoyi::utest:0.9.5]
```

Repeat the same test-module pattern for `common`, `core`, `command`,
`download`, `installers`, `cli`, `tui`, and `app`, replacing
`build.config.ScalaTests` with the matching parent module, for example
`build.core.ScalaTests` or `build.app.ScalaTests`.

Programmable subfolder examples:

```scala
// buildtools/package.mill
package build.buildtools

import mill.*

object `package` extends RootModule {
  def checkBuildMetadata = Task.Command {
    // Build-only validation target. Keep runtime modules independent from this.
  }
}
```

```scala
// dist/package.mill
package build.dist

import mill.*

object `package` extends RootModule {
  def archive = Task.Command {
    // Distribution target that can depend on app assembly outputs when wired.
  }
}
```

Use Mill's module commands after migration:

```bash
./mill common.compile
./mill config.compile
./mill core.compile
./mill installers.compile
./mill dist.archive
./mill app.run --help
./mill __.test
```

Organize production code by responsibility, not by technical file type:

```text
app/src/initkit/
  Main.scala                         # tiny application entrypoint
cli/src/initkit/cli/
  InitkitCommand.scala               # picocli root command
  ApplyCommand.scala
  TuiCommand.scala
  InfoCommand.scala
  CliOptions.scala                   # shared option parsing/value mapping
config/src/initkit/config/
  Manifest.scala
  ManifestSpec.scala
  PlanEntry.scala
  Sources.scala
  Target.scala
  Policy.scala
  RawYaml.scala
  ManifestLoader.scala
  ManifestValidator.scala
  ManifestLoadError.scala
core/src/initkit/
  platform/
    HostFacts.scala                  # OS, distro, arch, command availability
    HostDetector.scala
    ConditionEvaluator.scala
  resolve/
    VariableResolver.scala
    ResolvedManifest.scala
  engine/
    ExecutionEngine.scala
    PlanSelection.scala
    PlanEvent.scala
    PlanResult.scala
    ExecutionPolicy.scala
  state/
    ExecutionState.scala
    StateStore.scala
    StateFingerprint.scala
  logging/
    InitkitLogger.scala
    DebugLogger.scala
    LogEvent.scala
command/src/initkit/command/
  CommandSpec.scala
  CommandRunner.scala
  ProcessCommandRunner.scala
  SudoStrategy.scala
  Redactor.scala
download/src/initkit/download/
  DownloadClient.scala               # sttp wrapper
  BinaryInstaller.scala
  ArchiveExtractor.scala
  Checksum.scala
installers/src/initkit/installers/
  Installer.scala                    # common executor interface
  PackageManagerInstallers.scala
  AptInstaller.scala
  PacmanInstaller.scala
  DnfInstaller.scala
  ZypperInstaller.scala
  FlatpakInstaller.scala
  SnapInstaller.scala
  ShellScriptInstaller.scala
  NerdFontInstaller.scala
  DotfilesInstaller.scala
  InterruptInstaller.scala
  CommandsInstaller.scala
tui/src/initkit/tui/
  TuiApp.scala
  TuiModel.scala
  TuiUpdate.scala
  TuiView.scala
  PlanChecklist.scala
  Theme.scala
```

Mirror this structure in module-local tests:

```text
common/test/src/initkit/common/
config/test/src/initkit/config/
core/test/src/initkit/{platform,resolve,engine,state,logging}/
command/test/src/initkit/command/
download/test/src/initkit/download/
installers/test/src/initkit/installers/
cli/test/src/initkit/cli/
tui/test/src/initkit/tui/
app/test/src/initkit/
```

Guidelines:

- keep `app` as the final assembly/runtime module; it should contain only the app entrypoint and wiring
- keep reusable code in a dedicated module when it has multiple consumers; do not bury reusable utilities in `app`
- keep `Main.scala` small; it should only delegate to the picocli root command
- keep CLI classes thin; they translate flags into service calls and exit codes
- keep TUI classes free of installer logic; TUI calls the same engine as CLI
- keep `common` small and boring; if it grows domain-specific behavior, move that behavior to the owning module
- keep `config` focused on loading, raw YAML, schema, and validation
- put host detection, state, logging, engine, and variable interpolation in `core`
- put process execution, sudo, and redaction in `command`
- put HTTP and archive work in `download`
- put plan-kind-specific behavior in `installers`
- keep module dependencies explicit through `moduleDeps` in YAML
- avoid dependency cycles; the intended module graph is:
  `app -> cli/tui -> installers -> command/download/core -> config -> common`
- keep build-only targets such as `buildtools` and `dist` outside runtime module dependencies
- if a module needs custom task logic unsupported by YAML, isolate that one module in programmable Mill and leave the rest declarative
- avoid one giant `util` package; create narrow utilities beside the domain that uses them

## Scala Coding Standards

Follow the official Scala style guide unless this plan says otherwise.

Naming:

- packages: lowercase ASCII, no underscores
- classes, traits, objects, enums, and type aliases: `UpperCamelCase`
- methods, vals, vars, parameters, and fields: `lowerCamelCase`
- acronyms are normal words: `httpClient`, `maxId`, `YamlLoader`, not `HTTPClient`, `maxID`, `YAMLLoader`
- constants: prefer descriptive `UpperCamelCase` vals inside companions, for example `DefaultStatePath`
- test names: readable sentence-style strings in uTest

Formatting:

- use Scala 3 significant indentation consistently
- avoid mixing braces and significant indentation in the same file unless Java interop or generated-style code makes braces clearer
- keep constructors on one line when short; split each argument onto its own line when long
- keep line length around 100-120 characters
- add `.scalafmt.conf` before the codebase grows; use Scala 3 dialect and make formatting a normal validation step
- group imports as Java/JDK, Scala, third-party, then local packages
- avoid wildcard imports except for test DSLs such as `utest.*` and established local DSL-style imports

Types and domain modeling:

- prefer immutable `final case class` data models with `Vector`, `Map`, or `VectorMap`
- use `enum` or sealed traits for closed ADTs such as plan kinds, execution modes, statuses, and checksum algorithms
- use opaque types for safety around stringly identifiers when useful, for example `PlanEntryName`, `StateFingerprint`, or `ChecksumValue`
- avoid `null`; model absence with `Option`
- avoid exceptions for expected validation and execution errors; use typed `Either` or domain error ADTs
- keep throwing exceptions for truly unexpected programmer errors or boundary failures that are immediately converted to typed errors
- prefer total functions; when parsing untrusted config, do not use unsafe `.get`
- keep side effects at the edge: CLI, TUI, file IO, process execution, HTTP, and state store

Scala 3 language use:

- use `given`/`using` only for true contextual dependencies or typeclass-style behavior, not hidden service locators
- use extension methods sparingly; keep them in companions or clearly named syntax files
- prefer ordinary methods over symbolic operators for this codebase
- prefer pattern matching on ADTs over string matching after validation
- use `export` only when it materially improves a public facade
- avoid implicit conversions

Concurrency and resource safety:

- use Ox for structured concurrency and cancellation boundaries
- do not create raw global thread pools from feature code
- make every resource owner explicit: HTTP backends, temp directories, process handles, and log files
- close files, streams, and HTTP resources deterministically
- keep long-running process and download work outside the TUI render/update loop

Error handling and logging:

- define small error ADTs per boundary, then convert to user-facing messages at CLI/TUI edges
- include plan entry name and item name in all execution errors
- redact secrets before logging
- debug logs may include internal details; normal stdout should remain concise and user-oriented

Testing standards:

- mirror source packages in test packages
- test pure logic before process or network edges
- use fake command runners, fake sudo strategies, fake clocks, and temp directories
- do not run real package managers, `sudo`, shell installers, or live network downloads in tests
- keep fixtures small and local; prefer generated temp files for archive/checksum tests
- every new package boundary should have at least one focused test suite before it becomes depended on widely

## Supported Plan Kinds

Implement these kinds first because they are represented in the example config:

- `apt-packages`
- `pacman-packages`
- `dnf-packages`
- `zypper-packages`
- `flatpak-packages`
- `snap-packages`
- `binary-downloads`
- `shell-scripts`
- `nerd-fonts`
- `dotfiles-apply`
- `interrupt`
- `commands`

Each plan entry has this common shape:

```yaml
- name: example
  kind: binary-downloads
  description: Optional human-readable text.
  execution:
    mode: sequential | parallel
    maxConcurrency: 4
    failFast: true
    locks:
      - system-package-manager
  when:
    os:
      family: linux
      distribution:
        oneOf: [ubuntu, debian]
  spec:
    ...
```

## Phase 1: CLI And Config Loading

Replace the existing `mainargs` command parsing with picocli.

Create a root command:

```scala
@Command(
  name = "initkit",
  mixinStandardHelpOptions = true,
  version = Array("initkit 0.1.0"),
  subcommands = Array(classOf[ApplyCommand], classOf[InfoCommand], classOf[TuiCommand])
)
final class InitkitCommand extends Runnable {
  override def run(): Unit = CommandLine.usage(this, System.out)
}
```

Keep `info` and `tui` available as subcommands while adding `apply`.

Plain CLI command contract:

```bash
initkit apply --config config.example.yaml --dry-run
initkit apply --config config.example.yaml --state ~/.local/state/initkit/developer-workstation.state.json
```

TUI command contract:

```bash
initkit tui --config config.example.yaml
initkit tui --config config.example.yaml --state ~/.local/state/initkit/developer-workstation.state.json
```

Shared flags:

- `--config <path>`: YAML manifest path, default `config.yaml`
- `--state <path>`: read and write execution state in a separate JSON file
- `--reset-state`: ignore and overwrite any existing state file
- `--debug`: keep normal stdout output and also emit verbose diagnostic logs
- `--debug-log <path>`: optional file path for debug logs; when omitted, debug logs go to stderr
- `--color <auto|always|never>`: control ANSI color in plain CLI output; default `auto`
- `--no-color`: alias for `--color never`

Plain CLI flags:

- `apply --dry-run`: override `spec.policy.dryRun`
- `apply --yes`: skip interactive confirmations where supported
- `apply --only <name-or-kind>`: run matching plan entries only
- `apply --skip <name-or-kind>`: skip matching plan entries

TUI flags:

- `tui --dry-run`: start the TUI in preview mode
- `tui --select <name-or-kind>`: preselect matching plan entries
- `tui --skip <name-or-kind>`: start with matching plan entries unselected

Add a YAML parser dependency. Prefer SnakeYAML Engine or another maintained JVM
YAML parser that supports ordinary YAML maps and lists without requiring custom
tags.

Acceptance criteria:

- `mainargs` is removed from the build metadata
- picocli powers `info`, `tui`, and `apply`
- `--help` and `--version` work through picocli standard help options
- `apply` and `tui` both load the same manifest parser, validator, variable resolver, condition evaluator, state loader, and execution engine
- missing config path returns a clear error
- invalid YAML returns a clear parse error
- unsupported `apiVersion` or `kind` returns a clear validation error
- `config.example.yaml` parses successfully in tests

## Phase 2: Manifest Model And Validation

Create typed models under `config/src/initkit/config`.

Suggested model groups:

- `Manifest`
- `Metadata`
- `Spec`
- `Policy`
- `Target`
- `Sources`
- `PlanEntry`
- `Execution`
- `Condition`
- kind-specific spec models

Keep common plan fields typed, and parse `spec` based on `kind`.

Validation rules:

- `apiVersion` must be `initkit.io/v1alpha1`
- top-level `kind` must be `WorkstationProfile`
- each plan entry must have `name`, `kind`, and `spec`
- plan entry names must be unique
- `execution.mode` defaults to `sequential`
- `execution.maxConcurrency` is valid only for `parallel`
- unknown plan kinds fail validation
- unsupported checksum algorithms fail validation
- package lists must not be empty
- binary downloads require `url`, `destination`, and executable `mode` when they install commands
- `interrupt` entries require `spec.state.path` and may only use `spec.state.format: json` initially
- state paths must resolve outside the config file; do not write execution state into the manifest

Acceptance criteria:

- validation reports all obvious manifest errors with plan entry names
- kind-specific specs are available as typed values to executors
- tests cover one valid entry and one invalid entry per kind

## Phase 3: Host Detection And Conditions

Implement host detection in a small service.

Detect:

- OS family
- Linux distribution from `/etc/os-release`
- version and codename where available
- architecture normalized to values like `amd64` and `arm64`
- command availability through `PATH`

Implement `when` evaluation:

- exact scalar match, for example `distribution: arch`
- `oneOf` match, for example `distribution.oneOf`
- command existence, for example `commandExists: systemctl`

Acceptance criteria:

- skipped entries are printed with a reason
- matching entries are printed before execution
- tests can inject fake host facts

## Phase 4: Variable Resolution

Implement interpolation for strings containing `${name}`.

Variable sources, in precedence order:

1. runtime variables such as `USER` and `HOME`
2. `spec.vars`
3. host facts such as architecture, if exposed

Resolve variables in:

- source definitions
- plan entry `spec`
- command args
- destinations
- config file paths

Rules:

- unresolved variables fail validation unless explicitly allowed later
- interpolation should happen before executor dispatch
- do not run shell expansion for config values

Acceptance criteria:

- `${HOME}` and `${binDir}` resolve in nested plan specs
- unresolved variables produce a useful error with the config path

## Phase 5: Execution Engine

Implement an execution engine that runs plan entries top-to-bottom.

Use Ox for concurrency. Do not build custom thread pools, raw `Future` graphs,
or ad-hoc cancellation. Keep concurrency structured so failures, interrupts,
and cancellation cannot leave background work running after a plan entry exits.

Behavior:

- top-level `spec.plan` order is always respected
- entries with failing `when` conditions are skipped
- completed and skipped entries are persisted when a state file is configured
- when `--state` points to an existing state file, resume from `nextPlanEntry` if present, otherwise after the last completed plan entry
- each entry runs its internal work according to `execution.mode`
- `sequential` runs items one at a time
- `parallel` runs items concurrently with `maxConcurrency`
- `failFast` stops remaining parallel work when feasible
- `locks` prevent conflicting entries or tasks from running at the same time
- `continueOnError` comes from global policy unless an entry override is added later
- timeouts, retries, and cancellation should use Ox primitives where applicable
- package-manager plan entries are a special case: even in `sequential` mode,
  each package in `spec.install` is its own operation and sibling packages must
  continue after an individual package failure

For the first version, top-level entries can remain sequential even if locks are
implemented as no-ops. Keep the lock field in the model so parallel top-level
execution can be added later without changing the manifest.

Acceptance criteria:

- binary downloads can run in parallel
- parallel entries use Ox structured concurrency and bounded concurrency
- package manager entries run sequentially
- non-package failures stop execution unless `continueOnError` is true
- package-manager item failures do not prevent later packages in the same
  `install` list from being attempted
- dry-run prints commands and file changes without applying them

## Phase 6: State And Interrupts

Implement resumable execution through a separate state file.

State file requirements:

- JSON format
- separate from the YAML config
- path comes from `--state` or from an `interrupt.spec.state.path`
- parent directory is created when needed
- written atomically through a temporary file plus rename
- includes enough manifest identity to detect mismatches

Suggested state shape:

```json
{
  "apiVersion": "initkit.io/v1alpha1",
  "kind": "ExecutionState",
  "manifest": {
    "name": "developer-workstation",
    "configPath": "/abs/path/config.example.yaml",
    "fingerprint": "sha256-of-normalized-manifest"
  },
  "createdAt": "2026-06-28T12:00:00Z",
  "updatedAt": "2026-06-28T12:05:00Z",
  "lastCompleted": "relogin-after-shell-install",
  "nextPlanEntry": "apt-containers",
  "entries": [
    {
      "name": "apt-base-cli",
      "kind": "apt-packages",
      "status": "completed",
      "startedAt": "2026-06-28T12:00:10Z",
      "finishedAt": "2026-06-28T12:04:20Z"
    },
    {
      "name": "relogin-after-shell-install",
      "kind": "interrupt",
      "status": "interrupted",
      "finishedAt": "2026-06-28T12:05:00Z"
    }
  ]
}
```

Implement `kind: interrupt`.

Behavior:

- write state before exiting
- mark all previous successful plan entries as completed
- mark the interrupt entry as interrupted
- store `nextPlanEntry` when `spec.state.resumeFrom` is `next`
- print `spec.reason`, `spec.instructions`, and the resume command
- exit with `spec.exit.code`, defaulting to a non-zero pause code such as `75`
- in dry-run, print the state file that would be written and keep running only if explicitly requested later

Resume behavior:

- `--state <path>` loads existing state if present
- completed entries from the state file are skipped
- an interrupted entry is skipped when its `nextPlanEntry` is available
- without `--reset-state`, reject state files whose manifest name or fingerprint does not match
- if the next plan entry no longer exists, fail with a clear error
- update the same state file as additional entries complete
- mark the run complete when the final selected plan entry succeeds

Acceptance criteria:

- the zsh/logout use case is represented by `relogin-after-shell-install`
- the runner stops at `kind: interrupt` and writes a state file
- a subsequent run with `--state` resumes from the next flat plan entry
- tests cover interrupt, resume, stale state mismatch, and reset-state behavior

## Phase 7: Command Runner And Privilege Handling

Create a command runner abstraction.

Use proper JVM process execution. Prefer a small wrapper around
`java.lang.ProcessBuilder` over ad-hoc shell strings. `os-lib` can be used for
filesystem helpers, but command execution should preserve argv boundaries,
working directory, environment, stream handling, exit codes, cancellation, and
timeouts explicitly.

Responsibilities:

- render commands as arrays where possible
- support `sudo` when a task requires it
- stream stdout and stderr
- capture exit code
- support dry-run without spawning the command
- provide clear logs with plan entry name and item name
- support working directory and environment overrides
- support command timeouts and cancellation through Ox
- expose structured execution events for both CLI output and TUI log panels
- redact sensitive values before printing commands or debug logs

Suggested model:

```scala
final case class CommandSpec(
    argv: Vector[String],
    cwd: Option[os.Path] = None,
    env: Map[String, String] = Map.empty,
    sudo: Boolean = false,
    shell: Boolean = false,
    timeout: Option[FiniteDuration] = None,
    sensitiveEnv: Set[String] = Set.empty,
    sensitiveArgs: Set[Int] = Set.empty
)
```

Execution rules:

- default to argv execution with `ProcessBuilder(argv*)`
- use shell execution only for `commands.run` and explicit shell-script items
- when `shell = true`, execute through `/bin/sh -lc` unless the spec provides a shell
- inherit stdin only when an interactive prompt is expected
- stream stdout and stderr concurrently so commands cannot deadlock on full buffers
- preserve stdout/stderr ordering where practical, but do not block execution to perfectly merge streams
- return a typed result containing exit code, duration, stdout/stderr tail, and failure reason
- terminate the process tree on cancellation or timeout where the JVM/platform allows it
- never pass user-provided command text through a shell unless the manifest kind explicitly requires it

Privilege and password prompting:

- do not collect, echo, log, store, or cache passwords inside initkit
- for sudo commands in interactive CLI mode, run a `sudo -v` preflight with inherited stdin/stderr before the first sudo command
- if sudo credentials are already cached, continue without prompting
- if no interactive terminal is available, fail with a clear message unless `SUDO_ASKPASS` is configured
- when `SUDO_ASKPASS` is present and the session is noninteractive, use `sudo -A` for the preflight
- for TUI live execution, temporarily suspend or tear down the alternate-screen UI before any sudo password prompt, run `sudo -v`, then restore the UI
- do not run package-manager commands that may prompt for confirmation unless the executor passes noninteractive flags such as `-y`
- respect `--yes` only for initkit confirmations; it must not answer arbitrary subprocess password prompts
- tests must use a fake sudo strategy and must never invoke real sudo

Rules:

- do not use shell unless a spec explicitly requires shell behavior
- for `commands.run`, use shell because it is intentionally user-provided command text
- never prompt for sudo in tests
- detect missing required commands before executing a plan entry when practical
- debug logs may show argv, cwd, env keys, duration, exit code, and stream tails, but must redact configured sensitive values

Acceptance criteria:

- command runner has unit tests with a fake backend
- dry-run output includes the exact command that would run
- commands with large stdout/stderr do not deadlock
- timeout and cancellation terminate child processes
- sudo preflight is exercised through a fake strategy in tests
- TUI mode can request sudo without corrupting the terminal UI

## Phase 8: TUI Mode

Implement a TamboUI-based interactive mode backed by the same manifest and
execution services as plain CLI mode.

The TUI should feel like a polished terminal product, not a debug form. Use
the visual language shown in the user's references: framed panes, colorful
section labels, highlighted selections, compact status text, visible key hints,
and dense but readable information. Aim for an experience closer to tools like
binsider or rich OpenAPI terminal explorers than a plain checklist.

TUI goals:

- present the selected profile name, target OS info, detected host facts, and state-file status
- render `spec.plan` as an ordered checklist
- show each entry's `name`, `kind`, `description`, selection state, condition status, and execution mode
- disable entries whose `when` condition does not match, while still showing why they are skipped
- mark completed entries from `--state` as completed and unselected
- make `interrupt` entries visually distinct as pause/checkpoint steps
- let the user select or unselect runnable plan entries with checkboxes
- provide actions for dry-run, run selected, run all matching, resume from state, view details, and quit
- stream execution progress and command output in a log panel
- show a final summary with completed, skipped, interrupted, failed, and remaining entries

Suggested layout:

- top status bar: profile name, current host, dry-run/live mode, state path
- left or main panel: plan checklist in manifest order
- right/details panel: selected entry details, including kind-specific summary
- bottom log panel: recent commands, previews, failures, and resume instructions
- footer: key hints for select, details, dry-run, run, resume, and quit

Interaction model:

- arrow keys move focus through plan entries
- space toggles the focused checkbox when the entry is runnable
- `a` toggles all runnable entries
- `d` opens or focuses the details panel
- `p` runs a dry-run preview for selected entries
- `r` runs selected entries
- `R` resumes from the loaded state file
- `q` quits after confirmation if work is running

Implementation notes:

- scan the current TamboUI docs and demos before building widgets
- prefer existing TamboUI widgets and layout primitives over custom rendering
- keep execution logic outside UI classes
- represent UI state separately from execution state
- do not block the render loop while commands run
- route execution events into the UI through a small event model
- use the same dry-run and state behavior as `apply`
- if live execution requires sudo, surface that clearly before starting

Acceptance criteria:

- `initkit tui --config config.example.yaml` renders the plan from the YAML file
- plan entries are displayed in the same order as `spec.plan`
- matching entries can be selected with checkboxes
- skipped entries are visible but disabled with reasons
- completed state from `--state` is reflected in the checklist
- dry-run selected uses the same command generation as CLI mode
- run selected uses the same execution engine as CLI mode
- interrupt entries write state and show resume instructions in the TUI
- tests cover view-model generation without requiring a real terminal

Visual design requirements:

- use a dark terminal-first theme with strong contrast
- use color deliberately for status: ready, selected, skipped, completed, running, interrupted, failed
- give each plan kind a stable accent color or compact badge
- render active focus with a clear border/accent, not only text color
- use framed panels with compact titles, for example `[ Plan ]`, `[ Details ]`, `[ Output ]`
- keep the layout information-dense, with no marketing copy or empty decoration
- use symbols only when they improve scanability, for example checkbox states and status marks
- keep labels short enough to fit narrow terminals
- provide a clean fallback for terminals without full color support
- avoid relying on Unicode glyphs when ASCII fallback is needed

Suggested color semantics:

- selected/runnable: cyan or blue accent
- completed: green
- skipped/disabled: muted gray
- interrupted/checkpoint: amber/yellow
- failed: red
- running: magenta or bright blue
- dangerous/live mode: red or amber status indicator
- dry-run mode: cyan status indicator

Suggested entry rendering:

```text
[x] 01 apt-base-cli              apt-packages      ready      sequential
[ ] 02 relogin-after-shell       interrupt         checkpoint sequential
[-] 03 pacman-containers         pacman-packages   skipped    condition mismatch
[✓] 04 direct-binaries           binary-downloads  completed  parallel x4
```

Suggested pane structure:

```text
┌─ initkit ─ developer-workstation ─────────────────── dry-run ─ state: loaded ─┐
│ [ Plan ]                         │ [ Details: direct-binaries ]              │
│ > [x] apt-base-cli      ready     │ kind: binary-downloads                    │
│   [x] direct-binaries   ready     │ mode: parallel, maxConcurrency: 4         │
│   [ ] apply-dotfiles    ready     │ items: kubectl, helm, dotbot-go, fonts    │
│   [-] dnf-base-cli      skipped   │ checksum: required                        │
│                                  │                                            │
│ [ Output ]                                                                    │
│ dry-run $ curl ... kubectl                                                     │
│ dry-run $ install -m 0755 ...                                                  │
└─ [space] select  [a] all  [p] preview  [r] run  [d] details  [q] quit ────────┘
```

## Phase 9: Source Setup

Implement `spec.sources` before package installation.

Adapters:

- apt repositories and GPG keys
- dnf repositories
- zypper repositories
- flatpak remotes

Initial implementation can limit source setup to the active host package manager.
For unsupported host package managers, skip unrelated source sections.

Acceptance criteria:

- source setup is visible in dry-run output
- apt `updateBeforeInstall` causes an update before apt install
- flatpak remote setup is idempotent where possible

## Phase 10: Package Manager Executors

Implement one executor per package manager kind.

Critical package-list behavior:

- never generate one bulk install command for a package list
- every item in `spec.install` must become a separate command execution
- an invalid package name, unavailable group, or failed package install must
  mark that item as failed but must not prevent later `spec.install` items from
  being attempted
- after all package items are attempted, the plan entry should report partial
  failure if any required package item failed
- global `continueOnError` controls whether execution continues to the next
  top-level plan entry after that aggregate package-entry result
- state and reporting should include package-level results, including package
  name, generated command, exit code, and failure summary
- dry-run should show one command per package item in the same order that apply
  mode would execute them

For example, this DNF list:

```yaml
spec:
  install:
    - "@development-tools"
    - ca-certificates
    - curl
    - gnupg2
    - jq
```

must execute as separate commands:

```bash
sudo dnf install -y "@development-tools"
sudo dnf install -y ca-certificates
sudo dnf install -y curl
sudo dnf install -y gnupg2
sudo dnf install -y jq
```

If `gnupg2` is the wrong package name on that system, `jq` must still be
attempted. This rule applies to all package-manager kinds, not only DNF.

Command templates:

- `apt-packages`: optional `apt-get update` once, then `apt-get install -y <package>` once per package item
- `pacman-packages`: `pacman -S --needed --noconfirm <package>` once per package item
- `dnf-packages`: `dnf install -y <package-or-group>` once per package item
- `zypper-packages`: optional `zypper refresh` once, then `zypper install --non-interactive <package>` once per package item
- `flatpak-packages`: `flatpak install -y <remote> <package>` once per package item
- `snap-packages`: `snap install <package>`, with `--classic` where configured, once per package item

Do not put package-manager synchronization flags that refresh repositories on
every package item unless that package manager requires it. For pacman, prefer
separate source/sync setup or a single sync step before item installs over
`pacman -Sy` repeated for every package.

Acceptance criteria:

- generated commands match the manifest and contain exactly one install package/group/app per command
- package managers use sudo by default when `requireSudo` is true
- dry-run never mutates package state
- tests assert command generation for each package manager
- tests cover an invalid middle package item and assert later items are still attempted
- tests assert the final package entry result reports partial failure when one or more item installs fail

## Phase 11: Binary Downloads

Implement `binary-downloads`.

Use sttp client4 for HTTP. The core dependency is enough for JVM synchronous
downloads because it includes Java `HttpClient`-based backends. Prefer a
synchronous sttp backend inside Ox-supervised work units so parallel downloads
stay direct-style and cancellable by the enclosing Ox scope.

Responsibilities:

- create destination parent directories
- download files to a temporary path with sttp
- stream response bodies to disk rather than loading full archives into memory
- support HTTP status validation and useful error messages for failed downloads
- configure sane connect/read timeouts
- verify SHA256 checksums when configured
- extract archives when `archive` is present
- support `tar.gz`
- support `archive.path`
- support `archive.stripComponents`
- install the selected file to `destination`
- set file mode
- replace destination atomically where possible

Acceptance criteria:

- plain binary download works
- tar.gz extraction with selected path works
- checksum mismatch fails before installation
- direct binaries run in parallel when configured
- sttp calls are wrapped in Ox concurrency for parallel downloads
- tests use local fixture files, not live network calls

## Phase 12: Shell Script Installers

Implement `shell-scripts`.

Responsibilities:

- skip an item when `creates` already exists
- download installer scripts to a temporary path
- execute with configured shell and args
- honor dry-run

Acceptance criteria:

- rustup and miniforge examples render correct commands
- `creates` prevents duplicate execution
- script execution is sequential in the example

## Phase 13: Nerd Font Executor

Implement `nerd-fonts`.

Behavior:

- ensure config parent directory exists
- write `spec.config.content` to `spec.config.path` when `create` is true
- run preview first when `preview.enabled` is true and not globally disabled
- run `${tool.path} ${tool.args...}`
- append preview args only to the preview command

For the example, the command is:

```bash
${binDir}/nerdfont-install -config ${nerdFontConfig}
${binDir}/nerdfont-install -config ${nerdFontConfig} -dry-run
```

Acceptance criteria:

- generated YAML config contains selected font families
- dry-run shows config creation and install command
- normal run executes preview, then apply

## Phase 14: Dotfiles Executor

Implement `dotfiles-apply`.

Behavior:

- clone repository if `destination` does not exist
- update repository when `repository.update` is true
- checkout configured `ref`
- verify `config.path` exists after checkout
- run preview first when `preview.enabled` is true and not globally disabled
- run `${tool.path} ${tool.args...}`

For the example, the command is:

```bash
${binDir}/dotbot-go -d ${dotfilesDir} -c ${dotfilesConfig}
${binDir}/dotbot-go -d ${dotfilesDir} -c ${dotfilesConfig} --dry-run
```

Acceptance criteria:

- clone, update, and checkout commands are visible in dry-run
- missing dotbot config fails with a clear error
- preview runs before apply

## Phase 15: Generic Commands Executor

Implement `commands`.

Behavior:

- each item has `name`, `run`, optional `sudo`, and optional `when`
- evaluate item-level `when`
- execute sequentially unless the entry later declares parallel execution
- use shell execution for `run`

Acceptance criteria:

- `systemctl enable --now docker` is skipped when `systemctl` is missing
- sudo is applied only to items that request it or require it through policy
- dry-run prints command text without executing

## Phase 16: Logging And Reporting

Implement clear console output.

Report:

- manifest name
- detected host facts
- selected plan entries
- skipped entries with reasons
- each command or file operation
- dry-run status
- state file path, current resume point, and whether the run was resumed
- final summary with success, skipped, failed counts

For TUI mode, report the same information through widgets and a log panel rather
than only plain stdout.

Keep plain CLI output useful in CI and terminals. Use color conservatively in
plain CLI mode only if it does not make logs harder to parse.

Plain CLI styling:

- use fansi to render ANSI-styled labels, badges, statuses, section titles, and summaries
- default `--color auto` should enable color only for interactive terminals that appear to support ANSI
- disable color when `--color never`, `--no-color`, `NO_COLOR` is set, or stdout is not a terminal
- force color only when `--color always`
- never write raw escape codes by hand; centralize all color decisions behind a small CLI renderer
- keep the unstyled text readable and complete; color must add scanability, not carry the only meaning
- use compact status badges inspired by the reference style, for example gray package-manager names, cyan running entries, green completed entries, yellow skipped/interrupted entries, and red failed entries
- keep stdout parseable in CI by avoiding full-screen redraws, cursor movement, or animated output in plain CLI mode
- do not use fansi for TUI rendering; TamboUI owns full-screen terminal styling

Debug logging:

- default mode writes concise user-facing progress to stdout
- `--debug` keeps the default stdout output and additionally emits verbose diagnostics
- when `--debug-log <path>` is set, write verbose diagnostics to that file
- when `--debug` is set without `--debug-log`, write verbose diagnostics to stderr
- create debug log parent directories when needed
- include timestamps, run id, manifest name, plan entry, item name, command argv, cwd, duration, exit code, and state-file decisions
- include HTTP download request method, URL, status, content length when known, target path, checksum result, and retry attempts
- include Ox task lifecycle events for parallel entries: scheduled, started, completed, failed, cancelled
- redact secrets and password-like values from argv, environment, URLs, headers, and command output tails
- avoid logging full stdout/stderr by default; keep bounded tails unless a later explicit flag asks for full traces
- TUI mode should expose recent debug lines in the log panel when debug is enabled, while still writing the full debug log to stderr or file

Acceptance criteria:

- a user can understand what happened without opening debug logs
- failures include plan entry and item name
- `--debug` adds diagnostics without removing or replacing normal stdout output
- debug logs are redacted and include process execution details
- `--color never`, `--no-color`, and non-TTY stdout produce no ANSI escape sequences
- `--color always` produces fansi-styled output in plain CLI mode

## Phase 17: Tests

Add tests around pure logic first.

Required test areas:

- YAML loading
- validation
- variable interpolation
- condition matching
- command generation
- execution ordering
- Ox-backed bounded parallel execution
- JVM process execution with stdout/stderr pumping, timeouts, cancellation, and exit code capture
- sudo preflight behavior through fake interactive and noninteractive strategies
- TUI view-model generation from a manifest
- TUI selection rules for matched, skipped, completed, and interrupted entries
- dry-run behavior
- fansi CLI rendering, including `auto`, `always`, `never`, `NO_COLOR`, and non-TTY behavior
- state file writing and resume behavior
- binary archive extraction with fixtures
- sttp download handling through fake or local HTTP fixtures

Integration tests should use fake command runners and temporary directories.
Do not install real packages during tests.

Target command:

```bash
./mill app.test
```

## Phase 18: Documentation

Update `README.md` after the first working version.

Document:

- manifest structure
- CLI mode and TUI mode
- supported `kind` values
- dry-run workflow
- interactive plan selection with checkboxes
- interrupt and resume workflow with `--state`
- OS detection
- variable interpolation
- safety model
- examples for package managers, binaries, scripts, fonts, and dotfiles

Also document that `spec.target.os` is informational and that actual selection
comes from host detection plus `when`.

## First Milestone

The first milestone is complete when CLI dry-run works safely:

```bash
./mill app.run apply --config config.example.yaml --dry-run
```

It should parse the config, validate it, resolve variables, detect the current
host, select matching plan entries, and print the commands and file operations
that would run without mutating the machine.

## Second Milestone

The second milestone is complete when these entries can run for real on a
supported Linux host:

- matching native package manager entries
- `interrupt` with state-file resume
- `binary-downloads`
- `shell-scripts`
- `nerd-fonts`
- `dotfiles-apply`
- `commands`

Real execution should require a clear non-dry-run command and should stop on the
first failure unless `spec.policy.continueOnError` is true.

## Third Milestone

The third milestone is complete when this interactive flow works:

```bash
./mill app.run tui --config config.example.yaml
```

It should render the plan with TamboUI widgets, show checkboxes for runnable
entries, disable skipped entries, reflect state-file progress, run dry-run
previews for selected entries, and execute selected entries through the same
engine used by plain CLI mode.

## Agent Loop Tasks

The strict queue for subsequent implementation and validation iterations is in
`.agent-loop/tasks.json`. All tasks are pending at the time of this analysis
iteration. The current workspace still has a single starter `app` module, so the
queue begins with build/source-layout and module-boundary cleanup before the
manifest pipeline.

- T001 Migrate starter build layout (chore, complex)
- T002 Split CLI and TUI shells (improvement, complex)
- T003 Load raw manifests (feature, complex)
- T004 Validate manifest semantics (feature, complex)
- T005 Decode package and source specs (feature, complex)
- T006 Decode installer specs (feature, complex)
- T007 Run manifest checkpoint (validation, simple)
- T008 Detect host facts (feature, moderate)
- T009 Evaluate plan conditions (feature, moderate)
- T010 Resolve manifest variables (feature, complex)
- T011 Select runnable entries (feature, moderate)
- T012 Run resolution checkpoint (validation, simple)
- T013 Persist execution state (feature, complex)
- T014 Define execution contracts (feature, moderate)
- T015 Implement engine skeleton (feature, complex)
- T016 Run engine checkpoint (validation, simple)
- T017 Build command contracts (feature, moderate)
- T018 Run JVM processes safely (feature, complex)
- T019 Generate source setup operations (feature, moderate)
- T020 Implement package executors (feature, complex)
- T021 Implement commands executor (feature, moderate)
- T022 Run command checkpoint (validation, simple)
- T023 Install shell scripts (feature, moderate)
- T024 Download binaries with checksums (feature, complex)
- T025 Extract binary archives (feature, complex)
- T026 Run download checkpoint (validation, simple)
- T027 Install Nerd Fonts (feature, moderate)
- T028 Apply dotfiles (feature, moderate)
- T029 Report CLI outcomes (improvement, complex)
- T030 Run CLI milestone (validation, simple)
- T031 Research TamboUI APIs (chore, moderate)
- T032 Build TUI view model (feature, complex)
- T033 Render TUI checklist (feature, complex)
- T034 Wire TUI execution (feature, complex)
- T035 Run TUI checkpoint (validation, simple)
- T036 Document initkit usage (chore, moderate)
- T037 Run final validation (validation, simple)

Progress note, 2026-06-28: T013 added JSON execution state persistence in the
`core` module. State now records manifest identity (`metadata.name`,
`apiVersion`, `kind`, and a SHA-256 fingerprint), `createdAt`/`updatedAt`,
`lastCompleted`, `nextPlanEntry`, and per-entry status records. State writes
create parent directories and write through a sibling temp file before rename;
loading rejects stale manifest names or fingerprints unless reset-state behavior
is requested. Focused tests cover write/read, resume lookup, stale mismatch, and
reset initialization. `./mill __.compile`, `./mill __.test`,
`git diff --check`, and `jq empty .agent-loop/tasks.json` pass.

Progress note, 2026-06-28: T014 added shared execution contracts in the `core`
module. `ExecutionPolicy` and per-plan-entry execution policy now normalize
manifest policy defaults and CLI dry-run override intent. `PlanOperation`
decodes selected entries into typed package or installer operation variants
using the existing `PackageSpecDecoder` and `InstallerSpecDecoder`, and
`PlanOperationInstaller` dispatches to typed methods for every supported plan
kind instead of exposing raw YAML to executors. `PlanEvent` now represents
scheduled, started, skipped, completed, failed, interrupted, and dry-run
operation events; `PlanResult` reports completed, skipped, failed,
interrupted, and remaining operation counts. Focused tests cover typed
dispatch, representative event construction, dry-run operation data, and result
counts. `./mill core.test`, `./mill __.compile`, `./mill __.test`,
`git diff --check`, and `jq empty .agent-loop/tasks.json` pass.

Validation checkpoint T016 / VALIDATION-19, 2026-06-28: state and execution
engine behavior were revalidated before command/process executor work begins.
Mill discovery passed for recursive compile and test targets. The configured
checks passed: `./mill __.compile` and `./mill __.test`, including engine tests
for success, skips, failure, continueOnError, dry-run, interrupt, and resume
behavior. Additional validation passed: `git diff --check`, `jq empty
.agent-loop/tasks.json`, and `./mill app.run --help`. No source fix was needed.
`.scalafmt.conf` exists, but no local scalafmt executable or Mill formatting
target is configured, so formatter validation remains unavailable.

Progress note, 2026-06-28: T017 added command execution contracts in the
`core` module. `CommandSpec` now models direct argv and shell-mode invocations
without splitting argv, plus cwd, env values, sudo mode, timeout, and
sensitivity metadata. `CommandRedactor` covers metadata-marked values,
sensitive env keys, URL credentials/query parameters, option-following secrets,
and password/token/api-key style assignments. Command result and termination
types, a sudo strategy interface, and deterministic fake command/sudo backends
were added for executor tests; no test invokes real sudo. Checks passed:
`./mill core.test`, `./mill __.compile`, and `./mill __.test`.

Progress note, 2026-06-28: T018 added the JVM process runner in the `core`
module. `ProcessCommandRunner` executes direct argv specs with
`ProcessBuilder` by default, only uses shell prefixes for explicit shell specs,
applies cwd/env overrides, captures exit codes, supports dry-run bypass, and
pumps stdout/stderr concurrently through Ox forks into bounded stream tails so
large output cannot deadlock the child. Timeout and Ox cancellation paths
terminate the process tree where the JVM exposes it. Sudo preflight is modeled
through `PreflightSudoStrategy`, with interactive and noninteractive askpass
paths covered by fakes; tests still avoid real sudo. Checks passed:
`./mill core.test.testOnly initkit.core.ProcessCommandRunnerTests`,
`./mill __.compile`, `./mill __.test`, `git diff --check`, and
`jq empty .agent-loop/tasks.json`.

Progress note, 2026-06-28: T019 added source setup operation generation in the
`core` module. `SourceSetupGenerator` turns typed `spec.sources` into execution
operations and dry-run actions for apt GPG keys/repository files, dnf repo
files, zypper repository commands, and flatpak remotes. Apt/dnf/zypper source
sections are filtered to the active host package manager, while flatpak remotes
are generated only when the `flatpak` command is available. Apt
`updateBeforeInstall` is carried as an `aptUpdateBeforeInstall` marker for
later package install executors, and flatpak remotes use `--if-not-exists` by
default for idempotence where practical. Checks passed: `./mill core.test`,
`./mill __.compile`, `./mill __.test`, `git diff --check`, and
`jq empty .agent-loop/tasks.json`.

Validation checkpoint VALIDATION-23, 2026-06-28: the completed command/process
and source setup chunk was revalidated before package executor work begins.
Project metadata still points to the checked-in `./mill` launcher as the native
build entrypoint. Mill discovery passed for recursive compile and test targets:
compile covers `app`, `cli`, `config`, `core`, `host`, and `tui`, and tests
cover `cli`, `config`, `core`, and `host`. The configured checks passed:
`./mill __.compile` (log `/tmp/initkit-validation-23-compile-1782666219.log`)
and `./mill __.test` (log `/tmp/initkit-validation-23-test-1782666223.log`).
Additional validation passed: `git diff --check`, `jq empty
.agent-loop/tasks.json`, and `./mill app.run --help` (log
`/tmp/initkit-validation-23-help-1782666238.log`). No source fix was needed.
Formatter validation remains unavailable because `.scalafmt.conf` exists but no
local `scalafmt` executable or Mill formatting target is configured.

Progress note, 2026-06-28: T020 added package-manager executors in the `core`
module. `PackageManagerInstallers` now generates direct, noninteractive
`CommandSpec`s for apt, pacman, dnf, zypper, flatpak, and snap package plan
entries, applies `requireSudo` through `SudoMode.Required`, executes package
commands sequentially in apply mode, and returns dry-run command previews
without calling the command executor. Apt commands set
`DEBIAN_FRONTEND=noninteractive`; pacman includes `--noconfirm`; zypper uses
`--non-interactive`; dnf and flatpak use `-y`. Package installs are itemized:
setup/sync/refresh commands run once where configured, then each
`spec.install` package/group/app is attempted with its own command so a failed
middle item does not prevent later items from running. Snap emits one command
per package so `--classic` applies only where configured. Focused tests load
`config.example.yaml` and assert generated commands for every package manager
kind, sudo behavior, dry-run no-mutation behavior, apply-mode command execution
through the fake executor, and aggregate failure reporting when an item command
fails. Checks passed: `./mill core.test.testOnly
initkit.core.PackageManagerInstallersTests`, `./mill __.compile`, and
`./mill __.test`.

Correction resolved, 2026-06-28: package-manager executors were audited against
the Phase 10 itemized-install rule. The implementation now generates one install
command per package/group/app item and reports partial package-entry failure
after attempting all item commands.

Progress note, 2026-06-28: T021 added the generic commands executor in the
`core` module. `CommandsExecutor` evaluates each command item's `when`
condition against injected `HostFacts`, skips only unmatched items, and turns
`run` text into explicit shell-mode `CommandSpec.shell` invocations without
splitting the command string. Command items execute sequentially through the
injected `CommandExecutor`; failed commands honor the plan-entry `failFast`
setting and report an aggregate failure. Sudo mode is item-aware:
`sudo: true` requests sudo, `sudo: false` disables it, and omitted `sudo`
inherits `ExecutionPolicy.requireSudo`. Dry-run returns command-text previews
and skip messages without calling the executor. `PackageManagerInstallers` now
delegates `commands` operations to this executor. Checks passed:
`./mill core.test.testOnly initkit.core.CommandsExecutorTests`,
`./mill core.test`, `./mill __.compile`, `./mill __.test`,
`git diff --check`, and `jq empty .agent-loop/tasks.json`.

Validation checkpoint T022 / VALIDATION-26, 2026-06-28: command contracts,
process execution, source setup generation, package executors, and generic
commands were revalidated before download and installer work continues. The
configured recursive checks passed: `./mill __.compile` (log
`/tmp/initkit-validation-26-compile-1782667255.log`) and `./mill __.test`
(log `/tmp/initkit-validation-26-test-1782667258.log`). The test audit
confirmed package-manager, sudo, and URL-shaped paths use fake executors,
dry-run assertions, pure command-generation assertions, or harmless local
process-runner fixtures; no test invokes real package managers, real sudo, or
live network downloads. No source fix was needed.

Progress note, 2026-06-28: T023 added shell-script installer support in the
`core` module. `ShellScriptsExecutor` skips items whose `creates` path already
exists, downloads scripts to per-item temp files through an injectable
downloader, executes items sequentially through the shared `CommandExecutor`,
and deletes temp files after each attempt. `CommandSpec` and dry-run command
actions now expose optional stdin-file metadata so the rustup example renders
as direct argv `sh -s -- -y --default-toolchain stable` with the downloaded
script attached as stdin, while Miniforge renders as
`bash <temp> -b -p ${home}/miniforge3`. Dry-run previews download and execution
actions without creating temp files, downloading, or invoking the command
executor. Checks passed: `./mill core.test`, `./mill __.compile`,
`./mill __.test`, `git diff --check`, and `jq empty .agent-loop/tasks.json`.

Progress note, 2026-06-28: T024 added direct binary-download support in the
`core` module. `BinaryDownloadsExecutor` downloads direct files through an
injectable HTTP client, with the production client using sttp client4
`DefaultSyncBackend`, configured connection/read timeouts, and `asPath` so
response bodies are written to disk rather than loaded into memory. Downloads
target a temporary file in the destination directory, verify configured
checksums before installation, set POSIX file permissions from manifest modes,
and replace the destination with an atomic move where the filesystem supports
it. `PackageManagerInstallers` now delegates `binary-downloads` operations to
this executor, while archive items fail with a clear item-level unsupported
message until T025 implements archive extraction. Tests use a fake HTTP client
and temp filesystem fixtures; no test performs live network calls. Checks
passed: `./mill core.compile`, `./mill core.test.testOnly
initkit.core.BinaryDownloadsExecutorTests`, `./mill core.test`,
`./mill __.compile`, `./mill __.test`, `git diff --check`, and
`jq empty .agent-loop/tasks.json`.

Progress note, 2026-06-28: T025 extended `binary-downloads` in the `core`
module to install selected members from downloaded `tar.gz` archives. Archive
downloads still use the same temp-file, checksum, mode, and atomic-replace
path as direct binaries; archive members are extracted to a second temp file
before installation. `archive.path` selection supports stripped member names
and falls back to the raw member path for compatibility with existing manifest
examples; tests pin that `stripComponents` is applied before selecting the
installed member. Parallel binary-download entries now run through Ox
`Flow.mapParUnordered` with the plan-entry `maxConcurrency`, and fail-fast
parallel execution propagates the first item failure through Ox cancellation so
remaining work stops when feasible. Checks passed:
`./mill core.test.testOnly initkit.core.BinaryDownloadsExecutorTests`,
`./mill core.test`, `./mill __.compile`, `./mill __.test`,
`git diff --check`, and `jq empty .agent-loop/tasks.json`.

Validation checkpoint T026 / VALIDATION-30, 2026-06-28: shell-script and
binary-download behavior was revalidated before user-home installers continue.
Mill discovery confirms the current graph includes `app`, `cli`, `config`,
`core`, `host`, `selective`, and `tui`; recursive compile targets include test
modules for `cli`, `config`, `core`, and `host`, and recursive test targets
remain `cli.test`, `config.test`, `core.test`, and `host.test`. The configured
checks passed: `./mill __.compile` (log
`/tmp/initkit-validation-30-compile-1782668809.log`) and `./mill __.test`
(log `/tmp/initkit-validation-30-test-1782668812.log`). Additional checks
passed: `git diff --check`, `jq empty .agent-loop/tasks.json`, a test-source
audit confirming no production HTTP client calls in tests, and
`./mill app.run --help` (log
`/tmp/initkit-validation-30-help-1782668827.log`). No source fix was needed.
Residual risks: formatter validation remains unavailable because `scalafmt` is
not installed or exposed as a Mill target, and the archive tar reader still
intentionally supports regular-file selection from standard `tar.gz` archives
rather than every tar extension.

Progress note, 2026-06-28: T027 added Nerd Fonts installer support in the
`core` module. `NerdFontsExecutor` renders `spec.config.content` from the typed
raw YAML tree, creates the config parent directory, and writes the generated
config when `spec.config.create` is true. Command generation now produces the
preview command first when `preview.enabled` is true, appending preview args
only to that command, followed by the normal apply command. `PackageManagerInstallers`
delegates `nerd-fonts` operations to this executor; these user-home font
commands run without inheriting global sudo. Dry-run reports the config file
write plus both preview and apply commands without mutating files or invoking
the command executor. Focused tests cover YAML rendering with selected families
and destination, dry-run output, parent-directory creation, YAML writing, and
preview-before-apply execution. Checks passed: `./mill core.test`,
`./mill __.compile`, and `./mill __.test`.

Progress note, 2026-06-28: T028 added dotfiles apply support in the `core`
module. `DotfilesExecutor` generates repository setup commands for clone,
optional update, and checkout, verifies the dotbot config path after checkout,
then runs the preview command before the apply command when `preview.enabled`
is true. `PackageManagerInstallers` now delegates `dotfiles-apply` operations
to this executor; these user-home dotfiles commands run without inheriting
global sudo. Dry-run reports clone, update, checkout, preview, and apply
commands plus the config-verification step without mutating files or invoking
the command executor. Focused tests cover command generation, optional update
omission, dry-run visibility, clone-if-missing behavior with temp directories,
preview-before-apply ordering, and clear missing-config failure after checkout.
Checks passed: `./mill core.test`, `./mill __.compile`, and `./mill __.test`.

Progress note, 2026-06-28: T029 wired plain CLI `apply` through the manifest
pipeline and execution engine. The command now loads, validates, resolves,
selects, and dry-runs `config.example.yaml`, constructing live host facts,
state loading, source setup previews, `PackageManagerInstallers`, and
`ExecutionEngine` requests from CLI options. Plain output reports manifest
name, config, mode, host facts, state path/resume point, selected entries,
skipped reasons, source setup operations, per-plan dry-run actions, and a final
summary. The CLI module now depends on `core` and uses `fansi` for centralized
plain-output styling with `--color auto|always|never`, `--no-color`, and
`NO_COLOR` handling. `--debug` writes redacted diagnostics to stderr while
preserving stdout, and `--debug-log` writes those diagnostics to a created file
path. Checks passed: `./mill cli.test`, `./mill __.compile`, `./mill __.test`,
and `./mill app.run apply --config config.example.yaml --dry-run`.
