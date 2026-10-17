# Build and run

1. [Prerequisites](#1-prerequisites)
2. [Gradle tasks](#2-gradle-tasks)
3. [Configuration](#3-configuration)
4. [The data root](#4-the-data-root)

## 1. Prerequisites

| Need | For | Note |
|---|---|---|
| JDK 25 | building and running from Gradle | the toolchain is not provisioned automatically; Gradle finds an installed JDK 25 |
| Claude Code CLI | sessions | found at `~/.local/bin/claude`, `~/.claude/local/claude`, `/opt/homebrew/bin/claude`, `/usr/local/bin/claude`, else on `PATH`; `COMANDO_CLAUDE` overrides. Tested from 2.1.296 up to, not including, 2.2.0; another version gets a warning at start |
| Docker | the PostgreSQL adapter tests | optional; those tests are skipped without it |
| macOS | the keychain, the DMG | everything else runs on Linux (IntelliJ links too, with `idea` on the `PATH`) |
| IntelliJ IDEA's `idea` launcher | "Open in IntelliJ" | optional; files open in the default application without it |
| folders of documents | the sidebar's Files tab | optional; Claude Code's folder (`CLAUDE_CONFIG_DIR`, else `~/.claude`) until you configure your own; a folder that is missing says so in its row |
| `lockf` (macOS) or `flock` (Linux) | "Run Gradle tasks" through the kit's `gradle-serial` | the kit's wrapper takes a per-checkout lock with one of them |

## 2. Gradle tasks

| Command | Does |
|---|---|
| `./gradlew build` | compiles every module with warnings as errors, runs the unit and adapter tests (the opt-in ones are skipped) |
| `./gradlew :app:run` | starts comando on the default data root, from a copy of the build output made at launch (in `.gradle/run-stage`, one folder per launch, pruned when no process uses it), so a build while it runs leaves it alone |
| `./gradlew :app:installDist` | a runnable distribution in `app/build/install/comando`; start it with `bin/comando` |
| `./gradlew :app:dmg` | an unsigned macOS disk image with its own runtime in `app/build/dmg`, with comando's icon (`app/src/main/icon/comando.icns`, drawn from `comando.svg` next to it; the commands that redraw it after an edit are in `app/build.gradle.kts`) |
| `./gradlew test -PkeychainTest=true` | also runs the test that writes and deletes one item in the login keychain |
| `./gradlew :app:uiTest` | replays the recorded intent log against the window; needs a display (`xvfb-run` on Linux) |
| `./gradlew :ui:transcriptGate` | measures transcript frame times; fails when p99 exceeds 16 ms |
| `./gradlew :app:snapshot -Pout=<dir>` | drives comando on a scratch data root and paints the window to PNG files; `-Plive=true` adds a real Claude session (haiku) whose approvals are allowed, `-Phistory=<conversation id>` loads a stored conversation, `-Ptools=true` adds a Gradle run in a build of its own |
| `./gradlew :app:hero -Pout=<dir>` | paints the README's picture to `hero.png`: starts the embedded QuestDB from the menu, connects, and runs a real Claude turn (haiku) that queries and plots through comando's tools; approvals are allowed; the turn is live, so when it shows a failed tool call, run the task again |

Tests run headless, with the data root in each module's `build/test-home` and window preferences under a separate node, so they never read or change the user's data.

## 3. Configuration

| Setting | Kind | Default | Effect |
|---|---|---|---|
| `comando.home` | system property | — | the data root; wins over `COMANDO_HOME` |
| `COMANDO_HOME` | environment | `~/.comando` | the data root |
| `COMANDO_CLAUDE` | environment | search above | the Claude Code executable |
| `comando.offerKit` | system property | on for the default data root | offer the Claude Code kit at start |
| `COMANDO_KIT_LIVE` | environment | unset | `true` runs `KitLiveTest`, which installs, disables and re-enables the kit with the real Claude Code in a throwaway `CLAUDE_CONFIG_DIR` |
| `comando.keychain` | system property | `true` for `~/.comando`, else `false` | keep connection passwords in the login keychain |
| `comando.recordIntents` | system property | `false` | write the intent log to `logs/` in the data root |
| `comando.prefsNode` | system property | `dev/marregui/comando` | the Java preferences node for window layout |

System properties go to the JVM through `COMANDO_OPTS` (or `JAVA_OPTS`) for the installed launcher:

```
COMANDO_OPTS="-Dcomando.recordIntents=true" app/build/install/comando/bin/comando
```

## 4. The data root

`~/.comando` unless `comando.home` or `COMANDO_HOME` says otherwise; [Workbench §6](workbench.md#6-persistence) describes
each file. On a fresh root comando creates one panel, scratch, bound to a connection named local QuestDB; the
[tutorial](tutorial.md) takes it from there.
