# Build and run

How to build comando from a clone, run it, test it and package it, and the settings it reads at start. For a reader
who builds from source; installing a release is in `INSTALL.md` at the top of the repository.

1. [Prerequisites](#1-prerequisites)
2. [Gradle tasks](#2-gradle-tasks)
3. [Continuous integration](#3-continuous-integration)
4. [Configuration](#4-configuration)
5. [The data root](#5-the-data-root)

## 1. Prerequisites

| Need | For | Note |
|---|---|---|
| JDK 25 | building, and running from Gradle | the build names a JDK 25 toolchain and provisions none: Gradle finds an installed JDK 25 or stops |
| Claude Code CLI | sessions | looked for at `~/.local/bin/claude`, `~/.claude/local/claude`, `/opt/homebrew/bin/claude`, `/usr/local/bin/claude`, else `claude` on the `PATH`; `COMANDO_CLAUDE` overrides. Tested from 2.1.296 up to, not including, 2.2.0; another version is named in a warning at start |
| Codex CLI | Codex's usage in the status bar | optional; looked for at `~/.local/bin/codex`, `/opt/homebrew/bin/codex`, `/usr/local/bin/codex`, else `codex` on the `PATH`; `COMANDO_CODEX` overrides |
| Docker | the PostgreSQL adapter test (Testcontainers) | optional; without it that test is skipped |
| macOS | the login keychain, `:app:dmg` | everything else runs on Linux too, with Ctrl for ⌘ ([record 65](adr/0065-keys-on-desktops-other-than-macos.md)). CI builds and tests on macOS and Linux; Windows has a package, built by the release workflow, and no test run |
| IntelliJ IDEA's `idea` launcher | "Open in IntelliJ" | optional; without it a file opens in the system's default application |
| `mmdc` (Mermaid CLI) | diagrams in Markdown previews | optional; looked for on the `PATH`, under `~/.nvm`, in `/opt/homebrew/bin` and `/usr/local/bin`; without it a Mermaid block is not drawn |
| folders of documents | the sidebar's Files tab | optional; at first the kit's folders when there is a kit, else Claude Code's folder, until you choose your own ([Workbench §7](workbench.md#7-the-kit)); a configured folder that is missing says so in its row |
| `gradle-serial` on the `PATH` | Run Gradle tasks (⌥⌘G), queued per checkout | optional; comando ships none. Found on comando's `PATH`, a run goes through it; else a run is the checkout's `./gradlew` |

## 2. Gradle tasks

| Command | Does |
|---|---|
| `./gradlew build` | compiles every module with javac's lint warnings as errors (`-Xlint:all` less three, `-Werror`), and runs the unit and adapter tests; the opt-in tests are skipped |
| `./gradlew :app:run` | starts comando on the default data root from a copy of the build output made at launch, so a build while it runs leaves it alone (below) |
| `./gradlew :app:installDist` | a runnable distribution in `app/build/install/comando`; start it with `bin/comando` |
| `./gradlew :app:package` | this system's release file, with its own Java runtime, unsigned, in `app/build/package`: `comando-<version>-macos-<arch>.dmg`, `comando-<version>-linux-<arch>.tar.gz` or `comando-<version>-windows-<arch>.zip`. `<arch>` is `arm64` or `x64`; each archive holds one folder, `comando/`. `-PreleaseVersion=<version>` names the file and sets the version comando shows, as the release workflow does from the tag |
| `./gradlew :app:dmg` | an unsigned macOS disk image with its own runtime, in `app/build/dmg` |
| `./gradlew :app:appImage` | the application as a folder with its own runtime, in `app/build/app-image`: `comando.app` on macOS, `comando/` elsewhere |
| `./gradlew test -PkeychainTest=true` | the tests, plus the one that writes and deletes one item in the login keychain |
| `./gradlew :app:uiTest` | the tests that need a real window ([§2.1](#21-the-window-tests)); needs a display (`xvfb-run` on Linux) |
| `./gradlew :ui:transcriptGate` | measures the transcript's frame times; fails when a 99th percentile exceeds 16 ms (exit 1) or when the JVM does not end by itself within 10 s of the measurement (exit 2) ([UI §4.3](ui.md#43-the-frame-time-gate)) |
| `./gradlew :app:snapshot -Pout=<dir>` | drives comando on a scratch data root and paints the window to PNG files. `-Plive=true` adds a real Claude session (haiku) whose approvals are allowed; `-Phistory=<conversation id>` loads a stored conversation; `-Ptools=true` adds a Gradle run in a build of its own. A relative `<dir>` is under `app/` |
| `./gradlew :app:docImages -Pout=<dir>` | paints the pictures of the README and `docs/` at twice their size (`hero`, `approvals`, `sql`, `changes`, `diff`): a scratch data root and home, the embedded QuestDB started from the menu, one real Claude turn (haiku) that queries and plots, fixed plan usage in the status bar; `-Pout=$PWD/docs/images` replaces them |

`:app:snapshot`, `:app:docImages` and `:app:uiTest` open their windows off screen (`comando.offScreen`), without a Dock
icon (`apple.awt.UIElement`), and never take the desktop's focus, so they run while the desktop is in use.

A build rewrites the module jars and class files in place, and a running JVM loads classes from them lazily, so a
build under a running comando breaks it at the next class it loads. `:app:run` therefore copies this checkout's build
output to a folder of its own under `.gradle/run-stage` at each launch and runs from the copy. A later launch deletes a
copy once it is two minutes old and no live process names it on its command line. Jars from Gradle's cache are used in
place, as no build rewrites them; `clean` leaves `.gradle/` alone.

Tests run headless, with the data root in each module's `build/test-home`, so they never read or change the user's
data; a window test without a data root keeps its settings in memory, and the earlier preferences it might read are
under a node of its own. The modules test side by side
(`gradle.properties`), each in up to three JVMs; `:app:uiTest` runs one JVM per test class, one at a time, as its
windows share one desktop.

### 2.1 The window tests

`:app:uiTest` runs the test classes that open the real window. Each is skipped outside that task
(`comando.uiTests`).

| Class | Checks |
|---|---|
| `UiReplayTest` | recorded intent logs replayed against the window, every adapter a recording fake; the window shows what the final state says |
| `UiKeysTest` | the keyboard with macOS's keys and with the other desktops' (`comando.keys`): no two commands share a key, Ctrl with a letter reaches the terminal's shell, the run keys run, every text names this desktop's keys ([record 65](adr/0065-keys-on-desktops-other-than-macos.md)) |
| `TreeClicksUiTest` | a tree that opens a folder on one click, as the Files tab's; a window started on a kit's root opens on the kit's folders |
| `DarkPageTintUiTest` | a rendered Markdown page takes a newly chosen tint at once, as the transcript does |
| `ThreadBudgetTest` | comando's own platform threads stay within their budget with the processor count plus four fake sessions running ([record 82](adr/0082-threads-by-kind-with-a-budget.md)) |
| `CheckpointClearUiTest` | the Checkpoint button against a stand-in for `claude -p`: the conversation cleared, the summary shown and carried into the next prompt, the panel's text kept ([record 86](adr/0086-a-checkpoint-clears-and-carries-its-summary.md)) |
| `SessionCardUiTest` | a session's row shows the tab's card, a connection's row a card of its facts; both painted to `app/build/snapshots/` |
| `LinksUiTest` | a memory file's front matter shown as facts; a double click on a `[[name]]` link opens the named file, on a web address goes to the browser ([record 94](adr/0094-links-in-both-styles-everywhere.md)) |

## 3. Continuous integration

| Workflow | Runs on | Does |
|---|---|---|
| `build` | every push to `main` and every pull request, Ubuntu and macOS; a weekly run on Mondays; by hand | `./gradlew build`, then `:app:uiTest` (under `xvfb-run` on Ubuntu), a per-module test report with every skipped test named (`ci/TestReport.java`), `:ui:transcriptGate` reported but not enforced, as runner speed varies, and on macOS `:app:dmg` |
| `release` | a tag `v*`; by hand | `:app:package` on macOS, Ubuntu and Windows without the tests, then a GitHub release of the three files for a tag; by hand, the packages are kept as the run's artifacts |

A push or pull request that changes only documentation builds nothing, except a change to `docs/tutorial.md`, which
the ui jar carries. The PostgreSQL adapter test runs on Ubuntu only: macOS runners have no Docker.

## 4. Configuration

| Setting | Kind | Default | Effect |
|---|---|---|---|
| `comando.home` | system property | — | the data root; wins over `COMANDO_HOME`; a leading `~` is the home folder |
| `COMANDO_HOME` | environment | `~/.comando` | the data root; a leading `~` is the home folder |
| `COMANDO_CLAUDE` | environment | the search in [§1](#1-prerequisites) | the Claude Code executable |
| `COMANDO_CODEX` | environment | the search in [§1](#1-prerequisites) | the Codex executable, read for the status bar's usage |
| `CLAUDE_CONFIG_DIR` | environment | unset | the root comando's sessions run on, in place of `~/.claude` or `~/.claude/comando-kit` ([Workbench §7.1](workbench.md#71-the-root)) |
| `comando.keychain` | system property | `true` for `~/.comando`, else `false` | keep connection passwords in the login keychain (macOS); elsewhere they stay in `connections.json` |
| `comando.recordIntents` | system property | `false` | write the intent log to `logs/` in the data root |
| `comando.prefsNode` | system property | `dev/marregui/comando` | the Java preferences node read once into `settings.json` when that file does not exist yet |
| `comando.offScreen` | system property | `false` | `true` opens the window undecorated, left of every screen, never taking the focus; set by `:app:uiTest`, `:app:snapshot` and `:app:docImages` |
| `comando.keys` | system property | the desktop's | `mac` or `linux` chooses macOS's keys or the other desktops' on any desktop; for tests |

System properties reach the JVM through `COMANDO_OPTS` (or `JAVA_OPTS`) for the launcher `./gradlew :app:installDist`
writes:

```
COMANDO_OPTS="-Dcomando.recordIntents=true" app/build/install/comando/bin/comando
```

## 5. The data root

The data root is `~/.comando` unless `comando.home` or `COMANDO_HOME` names another folder;
[Workbench §6](workbench.md#6-persistence) describes each file in it. On a fresh data root comando creates one
connection, local QuestDB, with QuestDB's defaults, and one panel, scratch, bound to it and holding a short welcome
text. The [tutorial](tutorial.md) starts from there.
