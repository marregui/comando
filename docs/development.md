# Development

![From a clone to a release in six stages: your clone with JDK 25 and the Gradle wrapper; ./gradlew build, where warnings and Checkstyle reports fail the build and the unit and adapter tests run; :app:uiTest, the tests on the real window; the gates for frame times, threads, file reads and layers; CI on macOS and Ubuntu for every push and pull request; a tag that builds the dmg, tar.gz and zip packages](images/dev-pipeline.svg)

## In one paragraph

This page is for you if you change comando. It shows how to build it from a clone, run it, test it and release it.
It lists the settings comando reads at start, the rules its code is held to, and the gates that measure it. It ends
with the rules for these documents and the steps to add a feature. Every stage checks more than the one before, so
a mistake is caught at the cheapest place. Installing a release is in [INSTALL.md](../INSTALL.md); how the code is
organised is in [Architecture](architecture.md).

## 1. Prerequisites

| Need | For | Note |
|---|---|---|
| JDK 25 | building, and running from Gradle | The build names a JDK 25 toolchain and provisions none: Gradle finds an installed JDK 25 or stops. |
| Claude Code CLI | sessions | Looked for at `~/.local/bin/claude`, `~/.claude/local/claude`, `/opt/homebrew/bin/claude`, `/usr/local/bin/claude`, else `claude` on the `PATH`. `COMANDO_CLAUDE` overrides. Tested from 2.1.316 up to, not including, 2.2.0; another version is named in a warning at start. |
| Codex CLI | Codex's usage in the status bar | Optional. Looked for at `~/.local/bin/codex`, `/opt/homebrew/bin/codex`, `/usr/local/bin/codex`, else `codex` on the `PATH`. `COMANDO_CODEX` overrides. |
| Docker | the PostgreSQL adapter test (Testcontainers) | Optional. Without it that test is skipped. |
| macOS | the login keychain, `:app:dmg` | Everything else runs on Linux too, with Ctrl for ⌘ ([record 65](adr/0065-keys-on-desktops-other-than-macos.md)). CI builds and tests on macOS and Linux. Windows has a package, built by the release workflow, and no test run. |
| IntelliJ IDEA's `idea` launcher | "Open in IntelliJ" | Optional. Without it a file opens in the system's default application. |
| `mmdc` (Mermaid CLI) | diagrams in Markdown previews | Optional. Looked for on the `PATH`, under `~/.nvm`, in `/opt/homebrew/bin` and `/usr/local/bin`. Without it a Mermaid block is not drawn. |
| folders of documents | the sidebar's Kit, Brain and Scratchpad tabs | Optional. At first the kit's folders when there is a kit, else Claude Code's folder, until you choose your own ([The kit](kit.md)). A configured folder that is missing says so in its row. |
| `gradle-serial` on the `PATH` | Run Gradle tasks (⌥⌘G), queued per checkout | Optional; comando ships none. Found on comando's `PATH`, a run goes through it. Else a run is the checkout's `./gradlew`. |

## 2. Gradle tasks

| Command | Does |
|---|---|
| `./gradlew build` | Compiles every module with javac's lint warnings as errors (`-Xlint:all` less `serial`, `-Werror`). Checks main and test code with the Java Coding Standard's Checkstyle configuration (`config/checkstyle/checkstyle.xml`; any report fails it). Runs the unit and adapter tests; the opt-in tests are skipped. |
| `./gradlew :app:run` | Starts comando on the default data root, from a copy of the build output made at launch. A build while it runs leaves it alone (below). |
| `./gradlew :app:installDist` | A runnable distribution in `app/build/install/comando`; start it with `bin/comando`. |
| `./gradlew :app:package` | This system's release file, with its own Java runtime, unsigned, in `app/build/package`: `comando-<version>-macos-<arch>.dmg`, `comando-<version>-linux-<arch>.tar.gz` or `comando-<version>-windows-<arch>.zip`. `<arch>` is `arm64` or `x64`; each archive holds one folder, `comando/`. `-PreleaseVersion=<version>` names the file and sets the version comando shows, as the release workflow does from the tag. |
| `./gradlew :app:dmg` | An unsigned macOS disk image with its own runtime, in `app/build/dmg`. |
| `./gradlew :app:appImage` | The application as a folder with its own runtime, in `app/build/app-image`: `comando.app` on macOS, `comando/` elsewhere. |
| `./gradlew test -PkeychainTest=true` | The tests, plus the one that writes and deletes one item in the login keychain. |
| `./gradlew test jacocoTestReport` | The coverage of a module's unit tests, on demand, in `build/reports/jacoco/test`; `build` does not run it. |
| `./gradlew test testCodeCoverageReport` | The unit tests' coverage of every module in one report, on demand, in `app/build/reports/jacoco/testCodeCoverageReport`; `build` does not run it, and `:app:uiTest` is not in it. |
| `./gradlew :ui:testDocs` | The test-doc rule over every module's tests alone (`TestDocsTest`, G-11.7). `check` and `build` run it and `:ui:test` does not, so a test edit in another module reruns only it. |
| `./gradlew :app:uiTest` | The tests that need a real window ([§2.1](#21-the-window-tests)); needs a display (`xvfb-run` on Linux). |
| `./gradlew :ui:gridGate`, `./gradlew :ui:plotGate` | Measure the grid's and the plot's frame times; print, never fail ([§6](#6-quality)). |
| `./gradlew :ui:transcriptGate` | Measures the transcript's frame times. Fails when a 99th percentile exceeds 16 ms (exit 1), or when the JVM does not end by itself within 10 s of the measurement (exit 2). |
| `./gradlew :app:snapshot -Pout=<dir>` | Drives comando on a scratch data root and paints the window to PNG files. `-Plive=true` adds a real Claude session (haiku) whose approvals are allowed. `-Phistory=<conversation id>` loads a stored conversation. `-Ptools=true` adds a Gradle run in a build of its own. A relative `<dir>` is under `app/`. |

**A build under a running comando.** A build rewrites the module jars and class files in place, and a running JVM
loads classes from them lazily. So a build would break a running comando at the next class it loads. `:app:run`
therefore copies this checkout's build output to a folder of its own under `.gradle/run-stage` at each launch, and
runs from the copy. A later launch deletes a copy once it is two minutes old and no live process names it on its
command line. Jars from Gradle's cache are used in place, as no build rewrites them; `clean` leaves `.gradle/`
alone.

**Tests never touch your data.** Tests run headless, with the data root in each module's `build/test-home`. A
window test without a data root keeps its settings in memory, and reads earlier preferences from a node of its own.
`:app:snapshot` and `:app:uiTest` open their windows off screen (`comando.offScreen`), without a Dock icon
(`apple.awt.UIElement`), and never take the desktop's focus. You can run them while you use the desktop.

**How many at once.** The modules test side by side (`gradle.properties`), each in up to three JVMs. `:app:uiTest`
runs one JVM per test class, one at a time, as its windows share one desktop.

**One window test on the machine.** One `:app:uiTest` JVM runs at a time on the machine, across checkouts, users
and direct `./gradlew` runs (`app/uilock/UiTestLock`, proved by `UiTestLockTest`).

| Question | Answer |
|---|---|
| The lock | An exclusive lock on `/tmp/comando/uiTest.lock`, taken when JUnit opens its launcher session. On Windows the file is `comando\uiTest.lock` under `java.io.tmpdir`, which may be per user: unchecked. |
| Who can open it | Everyone: the folder and file are writable by all users, and neither is ever reached through a symbolic link. |
| A JVM that waits | Writes `waiting for another uiTest JVM to end [lock=…]` to its standard error every 30 s, which the HTML report shows. It fails after 30 min with the lock's path in the message. |
| Release | No code releases the lock. The kernel frees it when the JVM ends, however it ends, so a killed run never leaves it held. |
| Plain `test` | Takes no lock. The `comando.uiTests` property that `uiTest` sets switches the lock on. |

> **Learn: a build cache.** Compiling the same source with the same compiler gives the same result. So a build tool
> can skip the work: it fingerprints a task's inputs, and when it has seen that fingerprint before, it copies the
> stored output. This is the trade of memory for time, the same idea as memoization. It is only correct when every
> input is in the fingerprint. comando's cache is on (`org.gradle.caching=true`) and keeps outputs in
> `~/.gradle/caches/build-cache-1`, so a second clone at the same commit compiles from the cache. `uiTest` is never
> cached: what it checks is the desktop, and the desktop is no input.

The wrapper pins Gradle 9.9.1 and the distribution's SHA-256.

### 2.1 The window tests

`:app:uiTest` runs the 37 test classes that open the real window. Each is skipped outside that task
(`comando.uiTests`).

**The harness and the budget**

| Class | Checks |
|---|---|
| `UiReplayTest` | recorded intent logs replayed against the window, every adapter a recording fake; the window shows what the final state says |
| `ThreadBudgetTest` | comando's own platform threads stay within their budget with the processor count plus four fake sessions running ([record 82](adr/0082-threads-by-kind-with-a-budget.md)) |
| `QuitTeardownUiTest` | a step of the window's teardown or close that throws stops neither the quit nor the closing of the window's errands |
| `OwnLogUiTest` | comando's log opened from Help shows the lines written after it opened, at its end, also after a restart |

**Keys and focus**

| Class | Checks |
|---|---|
| `UiKeysTest` | the keyboard with macOS's keys and with the other desktops' (`comando.keys`): no two commands share a key, Ctrl with a letter reaches the terminal's shell, the run keys run, every text names this desktop's keys ([record 65](adr/0065-keys-on-desktops-other-than-macos.md)) |
| `FocusBackUiTest` | closing what a double click opened gives the keyboard back to what opened it ([record 112](adr/0112-closing-gives-the-keyboard-back.md)) |
| `FocusRouterUiTest` | a view opened in the lower area while the keyboard is in a panel's text takes nothing from it, and closing it in the background moves nothing ([record 112](adr/0112-closing-gives-the-keyboard-back.md)) |
| `CompareKeysUiTest` | F7 and ⇧F7 walk the differences of the comparison window a changed file opens in |

**Sessions, cards and the checkpoint**

| Class | Checks |
|---|---|
| `CheckpointCompactUiTest` | the checkpoint (`Intent.Checkpoint`, as the automatic checkpoint and `session_checkpoint` send it) against a stand-in for `claude -p`: the checkpoint command, then `/compact` once its turn has finished, the conversation and the Session tab kept, nothing carried, the panel's text kept ([record 129](adr/0129-a-checkpoint-reflects-then-compacts.md)) |
| `CheckpointLockUiTest` | the session locked while its checkpoint runs ([record 130](adr/0130-a-session-locks-while-its-checkpoint-runs.md)) |
| `SessionBarButtonsUiTest` | the session bar's Reflect, Compact and Clear buttons under a dark and a light theme, each enabled by its predicate ([record 156](adr/0156-one-icon-set-and-a-split-checkpoint.md)) |
| `SessionCardUiTest` | a session's row shows the tab's card, a connection's row a card of its facts; both painted to `app/build/snapshots/` |
| `SessionsMultiSelectUiTest` | the Sessions tab's multiple selection: ⇧ and ⌘ pick rows without a drag, a right-click in the selection opens the group menu, under a dark and a light theme |
| `AttentionUiTest` | a turn that ends behind another panel puts its session under To read in the Sessions tab and the work list; a click on the line focuses its panel and the mark goes ([record 136](adr/0136-finished-work-waits-to-be-read.md)) |
| `WorkListUiTest` | the status bar's work list: a click on the count opens one line per session at work, and a click on a line goes to that session's panel |
| `SubjectUiTest` | a session in Claude Code's folder shows no branch; a turn's writes put branch and repository on the session bar and the ticket on the card, and the row stays one line ([record 138](adr/0138-a-session-shows-what-it-writes-to.md)) |
| `QuestionUnansweredUiTest` | a question card nobody answers before the approval timeout: while it waits its card is in the Approvals tab and the title counts it; withdrawn at the deadline, the card goes and nothing of it is kept |
| `TerminalToolsUiTest` | an agent drives the Terminal of a panel you are not looking at, through the window's terminal port and real shells: start, type, read the answer, interrupt a job, stop ([record 148](adr/0148-agents-drive-a-panel-terminal.md)) |

**The transcript**

| Class | Checks |
|---|---|
| `ReportFirstUiTest` | a turn of tools and a report folds to one row; a click opens it and a second closes it; a find opens a closed row and closes it when it moves on; asking for the report puts it at the view's top ([record 137](adr/0137-a-turn-reads-as-its-report.md)) |
| `TranscriptAboveUiTest` | a session's panel in front puts the lower area above it and a file puts it back below, at the same height; turned off in `settings.json`, or by an arrangement as `ui_set_layout` sends, it stays below ([record 144](adr/0144-the-transcript-sits-above-the-prompt.md)) |
| `TranscriptFollowUiTest` | a prompt run from a session's panel while the Session tab is scrolled up lands in view, though the session's next block follows it at once, and the answer streaming under it stays in view; sent while the tab is hidden, the end shows when the tab is shown again |
| `DeliverablesUiTest` | the Files written view lists a session's two written files in order, the first gold; Enter opens it in the preview tab and the row turns plain ([record 143](adr/0143-a-session-lists-the-files-it-wrote.md)) |

**Themes and icons**

| Class | Checks |
|---|---|
| `ThemeFadeUiTest` | a theme chosen fades in over the one before: ground and tint applied at once, the old picture over the new |
| `DarkPageTintUiTest` | a rendered Markdown page takes a newly chosen tint at once, as the transcript does |
| `DiffThemeUiTest` | the comparison window opens on the theme chosen, its texts and rendered sides, and follows the next ([record 135](adr/0135-the-comparison-follows-the-theme-and-lies-level.md)) |
| `ConsoleStyleUiTest` | a console theme frames the four regions and shows the plan usage and the context as gauges; a plain theme's bounds are the same after the console was chosen and left ([record 151](adr/0151-a-theme-has-a-style-the-console.md)) |
| `OneIconSetUiTest` | one icon set: the theme chooser offers none to choose and the palette no `icons.*` command; the palette's Reflect, Compact the conversation and Clear the conversation send their commands to a stand-in for `claude -p`; the panel tab menu has an icon on each item, under a dark and a light theme ([record 156](adr/0156-one-icon-set-and-a-split-checkpoint.md)) |
| `PanelMenusIconsUiTest` | every item of the panels' context menus (connection, instances, notices) has an icon, and each popup paints from a dark and a light theme |
| `WidgetMenusIconsUiTest` | the tree, console, Changes, Search and jobs editor right-click menus: their items, each with an icon, under a dark and a light theme |

**Layout, files and search**

| Class | Checks |
|---|---|
| `TreeClicksUiTest` | a tree that opens a folder on one click, as the folder tabs'; a window started on a kit's root opens on the kit's folders |
| `LayoutKeeperUiTest` | the sidebar's width kept in `settings.json` comes back at the start (a value that is no number gives the default); a session's arrangement hides the sidebar to its rail and shows it again at its kept width; the width left is saved a second after the window last changed size and the next window opens with it; a close without the quit flow, as on the shutdown hook's path, keeps a width moved just before |
| `LowerDividerUiTest` | the divider above the lower panel is each panel's own ([record 115](adr/0115-the-lower-divider-per-panel.md)) |
| `PartitionUiTest` | a panel and the Changes view go to the right side by their tab's menu and back; a file moved there leaves the lower panel shown, at a side view's width; ⌘1 to ⌘3 go to the main tabs' panels, then the right side's; ⌘W and ⌘⇧T close and bring back a side panel; the partition follows a theme chosen while it was hidden; a side panel and the side views come back after a restart, the layout saved by the close ([record 141](adr/0141-the-right-side-is-a-partition-of-the-workspace.md)) |
| `LocateUiTest` | a panel's Locate button selects its file in the tab whose folder holds it or its session in the Sessions tab ([record 119](adr/0119-the-ui-simplified-kit-row-locate-no-thinking-mark.md)) |
| `OutlineUiTest` | a Markdown document's outline at the panel's right; a double click on a heading scrolls the page to it ([record 113](adr/0113-a-documents-outline-at-the-panels-right.md)) |
| `LinksUiTest` | a memory file's front matter shown as facts; a double click on a `[[name]]` link opens the named file, on a web address goes to the browser ([record 94](adr/0094-links-in-both-styles-everywhere.md)); an HTML page a session names opens in the browser, not a panel ([record 154](adr/0154-a-link-to-a-page-opens-the-browser.md)) |
| `SearchUiTest` | ⌘⇧F opens the Search view in the side pane with the keyboard in its field, and typing lists a hit under its folder, file and heading; ↓ shows the hit in the preview tab, every hit marked, the keyboard kept in the rows, and F7 walks the hits; ⏎ keeps the preview tab; the selection fills the field; the toggles decide what is searched; the Find menu and the palette hold the command; a conversation a session holds opens there with the transcript's Find on the query, another offers Import ([record 139](adr/0139-one-window-finds-in-files-and-conversations.md), [record 141](adr/0141-the-right-side-is-a-partition-of-the-workspace.md)) |

## 3. Continuous integration

![The three workflows: build starts on a push to main, a pull request, Mondays and by hand, and runs three jobs side by side: build on Ubuntu and macOS, window with :app:uiTest in shards, and disk-image with :app:dmg in the weekly run only; release starts on a tag and runs package on macOS, Ubuntu and Windows, then the tag's GitHub release of the three files; assign gives each new pull request to the owner](images/dev-ci.svg)

> **Learn: continuous integration.** "It works on my machine" proves little: your machine has your files, your
> tools and your luck. Continuous integration (CI) runs the whole build on a clean machine for every change, before
> the change joins the main branch. A change that breaks something is caught within minutes, while its author still
> remembers it. comando's CI runs on two operating systems, because a desktop application meets both.

| Workflow | Starts on | Does |
|---|---|---|
| `build` | every push to `main` and every pull request; Mondays; by hand | Three jobs side by side. `build`: `./gradlew build` on Ubuntu and macOS. `window`: `:app:uiTest` in shards, three runners on macOS and two on Ubuntu (under `xvfb-run`). `disk-image`: `:app:dmg` on macOS, in the weekly run only, as a tag builds the packages in `release`. |
| `release` | a tag `v*`; by hand | `:app:package` with `-PreleaseVersion=<tag without v>` on macOS, Ubuntu and Windows, without the tests. Then the tag's GitHub release of the three files, its notes the commits since the previous tag. |
| `assign` | every pull request opened, reopened or ready for review | Assigns it to the owner; checks out nothing. |

| Detail | How |
|---|---|
| Shards | Each runner gets `-PuiShard=<k>/<n>` and keeps the k-th of n groups of the window test classes. The groups are balanced on the times in `ci/ui-test-seconds.tsv`, written from a run's `uiTest` results. A class not in the file counts the median of those that are. |
| Reports | The `build` and `window` jobs write a test report per module, with every skipped test named (`ci/TestReport.java`). |
| The frame-time gate | `:ui:transcriptGate` runs on each system's last shard. It is reported but not enforced, as runner speed varies. |
| A release by hand | The packages are the run's artifacts, kept 7 days. A release is made only on a tag with dry run off. |
| Toolchain | `build` and `release` use Temurin 25 and actions pinned by SHA. |
| Dependencies | `dependabot.yml` opens one grouped pull request a month for the actions and one for the Gradle dependencies. |
| Documentation | A push or pull request that changes only documentation builds nothing. The exception is `docs/tutorial.md`, which the `ui` jar carries. |
| PostgreSQL | The PostgreSQL adapter test runs on Ubuntu only: macOS runners have no Docker. |

## 4. Configuration

| Setting | Kind | Default | Effect |
|---|---|---|---|
| `comando.home` | system property | — | the data root; wins over `COMANDO_HOME`; a leading `~` is the home folder |
| `COMANDO_HOME` | environment | `~/.comando` | the data root; a leading `~` is the home folder |
| `comando.name`, `COMANDO_NAME` | system property, environment | the data root's `instance.json` `name` (written by whoever creates the instance), else its folder: none for `~/.comando`, `b` for `~/.comando_b` | the instance's name: the Dock's, the menu bar's and the window's `comando · b` (a name that says comando, `Comando Expert`, as it is), and the Dock badge, its first letter ([record 122](adr/0122-instances-name-kit-brain-data-root-edge.md)) |
| `COMANDO_CLAUDE` | environment | the search in [§1](#1-prerequisites) | the Claude Code executable |
| `COMANDO_CODEX` | environment | the search in [§1](#1-prerequisites) | the Codex executable, read for the status bar's usage |
| `CLAUDE_CONFIG_DIR` | environment | unset | the root comando's sessions run on, in place of the folders it looks in (`~/.claude`, `~/comando-kit`, `~/.claude/comando-kit`) ([The kit](kit.md)) |
| `COMANDO_BRAIN` | environment | unset | the brain's folder, over the manifest's `brain`; used only with a kit ([The kit](kit.md)) |
| `comando.machineRoot` | system property | the home folder | the folder of the files one machine's instances share (the frontear registry, `.comando-machine/`, `.comando-remotes.json`); another one makes a second comando on this host another machine, paired over loopback |
| `comando.keychain` | system property | `true` for `~/.comando`, else `false` | keep connection passwords in the login keychain (macOS); elsewhere they stay in `connections.json` |
| `comando.metrics` | system property | `true` | `false` keeps no metrics, as the tests do ([Claude Code](claude-code.md)) |
| `comando.recordIntents` | system property | `false` | write the intent log to `logs/` in the data root |
| `comando.prefsNode` | system property | `dev/marregui/comando`; `dev/marregui/comando-<folder>` for another data root, named by its folder | the Java preferences node read once into the data root's `settings.json` when that file does not exist yet ([record 106](adr/0106-settings-in-the-data-root.md)) |
| `comando.offScreen` | system property | `false` | `true` opens the window undecorated, left of every screen, never taking the focus; set by `:app:uiTest` and `:app:snapshot` |
| `comando.keys` | system property | the desktop's | `mac` or `linux` chooses macOS's keys or the other desktops' on any desktop; for tests |
| `comando.peers` | system property | `true` | `false` keeps the instance off frontear, neither starting nor joining a node; set by every test task of `app` and by `:app:snapshot`, so no test joins a developer's running frontear or binds its port |

System properties reach the JVM through `COMANDO_OPTS` (or `JAVA_OPTS`), which the launcher from
`./gradlew :app:installDist` reads:

```
COMANDO_OPTS="-Dcomando.recordIntents=true" app/build/install/comando/bin/comando
```

## 5. Code rules

![The code rules in three layers and what holds them: the Java Coding Standard with its G rules, comando's Apply section saying which of them apply and which departures it keeps, and comando's own rules 1 to 16; five checks hold them, from the earliest to the latest: the compiler with warnings as errors, Checkstyle with no report allowed, the rule tests, a review, and watchdogs while comando runs](images/dev-rules.svg)

> **Learn: rules a machine checks.** A rule that lives only in a document is forgotten on a busy day. A rule a
> machine checks is not. The cheapest check is the compiler: treat each warning as an error and a whole class of
> mistakes never runs. A linter reads the code's shape, such as naming and import order. An architecture test goes
> further: it is an ordinary test that reads the compiled classes and fails when one layer calls a layer it must
> not. comando uses all three, and leaves to a human review only what no checker can see.

comando follows the [Java Coding Standard v2.0.3](https://github.com/marregui/java-standard/blob/v2.0.3/STANDARD.md),
on JDK 25 (`release = 25`). The standard's rules live there and are cited by ID (`G-<section>.<n>`). This section
holds three things: comando's own rules, with the numbers other documents cite; comando's Apply section (§12 of the
standard), as the table below; and in [§5.1](#51-not-applied), the rules not applied yet. Rules 12 to 16 cover what
a desktop application meets and the standard leaves open.

1. A change to state is an intent; the reducer is the only writer of `AppState`.
2. Threads follow G-7.10. A thread is of one of the six kinds of [Architecture](architecture.md) §6 and comes from `Threads`, an owner's `Errands` or
   `Watchdog`; a new platform thread needs a new kind.
3. Waits follow G-7.6, and every wait has an action on expiry.
4. Nothing in `ui` imports an adapter module; no widget imports another widget.
5. The EDT owns latency (G-7.11). Swing components and their models are created, read and written on the EDT only;
   work done on another thread hands its result back with `invokeLater`. A test does the same through the `OnEdt`
   extension or `invokeAndWait`.
6. Text from Claude, a database or a file reaches Swing as text, never as HTML (`Plain`, `MarkdownHtml`).
7. Tests follow G-11.8: the user's data root, keychain and Java preferences.
8. G-10.9.
9. G-10.10; the one excluded category is `serial`.
10. G-10.11; static imports last.
11. G-6.5.
12. A child process has one owner. A short command runs through `Processes` (output to temporary files, a time limit,
    the program and every process it started ended forcibly past it); a long-lived one (the `claude -p` process, the
    terminal's shell) is started, drained and ended with its descendants by the adapter that owns
    it, and its close waits a bounded time. The one exception is the machine's cdb
    (record 146): its launcher is a short command under `Processes`, and the server it leaves belongs to the machine's
    registry, not to the instance, so no instance's close ends it. Descendants are read for a signal only while the parent lives: the JDK
    finds them by pid, and an ended process's pid may already be another's. What a child did is read from its exit code, never from its text, which
    a locale changes (G-5.7).
13. A file comando owns is written whole through `AtomicFiles`: a temporary file beside it, then a move over it.
14. The MCP server binds `127.0.0.1` only, answers only a request that carries a session's bearer token, refuses a
    browser `Origin` that is not loopback and bounds a request body.
15. A password lives in the keychain (`Secrets`); a token comando issues lives in memory and in files created
    owner-only (`rw-------`). Neither reaches a log line, a notice, the intent log or the audit log. Every owner-only
    file or folder is made through `core`'s `OwnerOnly`, the one helper every module uses.
16. A file is read through `core`'s `Mapped`: mapped read-only, scanned in place, and only the slice needed decoded.
    Bytes leave a mapping one by one, never by a bulk copy: a bulk copy off a file cut in place kills the JVM. Outside it
    stay the libraries that take only a path, process pipes, the instance lock, the machine's cdb's start lock,
    writes, and cdb's store (`cdb.store.StoreFiles`): cdb depends on the JDK alone, owns its column files and maps them
    in shared arenas its readers close ([record 158](adr/0158-cdb-comandos-own-database.md)); its readers
    (`cdb.store.Version`, `cdb.store.TextCache`) copy those mappings in bulk, as a read slices exactly the committed
    bytes and a partition's committed bytes never shrink, so no cut meets a slice. `FileReadRulesTest`
    fails the build otherwise ([record 140](adr/0140-every-file-is-read-mapped.md)).

> **Learn: an atomic write.** A program can die halfway through writing a file: a crash, a full disk, a pulled
> plug. The file is then half old and half new, and nothing can read it. The cure is to write the new content to a
> second file beside the first, and then rename it over the first. A rename is one step for the file system: a
> reader sees the whole old file or the whole new one, never a mix. That is "atomic": it cannot be seen half done.
> Rule 13 makes every file comando owns change this way.

**The Apply section.** The standard asks each project to say how it applies it. Part 4 is a contract: each
departure is kept on purpose, with its reason and, where there is one, the condition that ends it.

| Part | comando |
|---|---|
| 1. Version | v2.0.3, JDK 25. No `AGENTS.md` or `CLAUDE.md` in the repository yet to point an agent here (§12) |
| 2. Hot paths | A path is hot when the p99 of its frame-time gate exceeds 4 ms. Hot: the transcript renderer (`ui.widgets.transcript.render`: `TranscriptCanvas`, `TextBox`; layouts kept within a window around the view and let go beyond it, only the clip painted), `:ui:transcriptGate`, p99 3.09 ms on the gate's document with each turn's row opened and 4.75 ms on a 1,500-line code fence ([ui](modules/ui.md)); the plot (`ui.widgets.plot`: `PlotCanvas`), `:ui:plotGate`, p99 28–32 ms over three runs on an M2 Max, over its 16 ms budget: a crosshair move repaints and re-reduces every series (2026-10-31). Not hot: the grid (`GridTable`), `:ui:gridGate`, p99 1.4–1.5 ms. `Transcript`, which the transcript renderer reads, publishes with release-acquire ordering (a volatile write after the data); so does `ColumnarResult`, which the grid reads. cdb's per-row code is hot by its throughput, not a frame gate: the store's ingest, apply and replay loops (`cdb.store`), the executor's kernels and cursors (`cdb.exec`), and the server's row encoders and line-protocol parser (`cdb.serve`). G-1 to G-3 and G-10.7 apply on the hot paths only; what they leave open there is in [§5.1](#51-not-applied) |
| 3. Applied and enforced | Enforced by the build or a test: G-10.10 (`-Xlint:all -Werror`, `serial` excluded with its reason beside the flag in `build.gradle.kts`; doclint not yet); G-9.5 (the `this-escape` lint); G-10.2, G-10.8's braces and G-10.11 (the standard's Checkstyle configuration, copied to `config/checkstyle/checkstyle.xml`, on main and test code; any report fails `build`); G-7.10 (`ThreadBudgetTest`: no platform thread and none of the threads `Threads` made alive after close, read from `Threads.alive()`); G-11.7 (`TestDocsTest`: a `///` comment, not `/** */`, on the top-level type and every test method of each `*Test.java` in every module's `src/test/java`); G-5.3 and G-6.5 for log calls (`LoggingRulesTest`, from the source of every module's `src/main/java`: a value outside the `[...]` tail, a value inside it without its own `key=`, a tail before the text, a non-ASCII message, or a warning or error in a `catch` that does not pass the caught exception unless its text names the handling; the log calls that predate it are listed in `logging-baseline.txt`, and a call not listed fails); G-10.1 by band (`MemberOrderRulesTest`, over every module's `src/main/java` and `src/test/java`: no member in an earlier band than one above it, not the order within a band; a field is not compared with the members above it that it depends on (the fields its initialiser reads, transitively, and the initialiser blocks that name them); the first other later-band member above it is reported, as the place to move it up to, or, when that member sits above what the field depends on, as the member to move down below it, named with the members between them that read it; the files out of order when it came are listed in `member-order-baseline.txt`, by file, so a new file out of order fails and a listed file is not checked further). Both baselines only shrink: a line nothing matches any more fails too. Applied and checked by review only, no checker in the repository: G-10.5 (test methods named for the behaviour they prove), G-10.2 on what Checkstyle does not report (fields, try-with-resources variables, multi-catch parameters, basic-for initialisers), G-10.11's fully qualified names. Measured at run time, not enforced: G-7.11 (`EdtWatchdog` logs a stall of the EDT over 200 ms with its length when it ends, and the EDT's stack at 1 s, 2 s for a stall in the JDK's drawing; the `StateLoop` watchdog logs an intent still applying at 1 s with the loop's stack) |
| 4. Departures kept on purpose | G-14.6 and G-14.11: no JFR code ([§6](#6-quality)). G-16.4: `FolderSearch` reads files on its forks, virtual threads, capped at half the cores, and `ClaudeAdapter` reads transcripts on virtual threads (the sub-agent tail's timed strand, a session's strand loading its history): comando has no bounded platform kind; until it has one ([record 139](adr/0139-one-window-finds-in-files-and-conversations.md)). Both read regular files only: `Mapped.open` refuses a FIFO, a device or a folder before opening it, so no read waits on a writer; what remains is a disk read's time. The result grid sorts on a virtual thread too (`grid-sort`, one per header click), CPU-bound work kept off the event thread: a newer click or new rows drop an older sort's result, and the older thread stops at its next check (every 65,536 keys read, each merge pass), so two sorts can overlap briefly, each with its own keys. G-4.7: `Mapped`'s confined arenas carry no tag or byte count: each lives for one read on one thread and is closed by try-with-resources; until a leak report needs one. G-10.1 in test classes already in the repository: their private helpers and fixtures may stay before or between the tests, where many of them have been since the first tests (2026-11-08); a test class a change adds or reorders keeps the standard's order, nested types last; those already there are in `member-order-baseline.txt`. cdb depends on the JDK alone ([record 158](adr/0158-cdb-comandos-own-database.md)), so comando's own rules 2 and 16 give way there: its store opens its column files (`cdb.store.StoreFiles`) and its readers copy them in bulk (`cdb.store.Version`, `cdb.store.TextCache`), its server's admission makes its request threads, its launcher registers a shutdown hook, and its store runs one platform thread, the applier, whose batches write and force files ([record 162](adr/0162-cdb-applies-commits-from-its-log.md)), and with the timed force a second, `cdb-log-force-<root>`, whose forces block in the file system ([record 166](adr/0166-cdb-timed-log-force.md)); its query pool runs platform daemon threads `cdb-query-<n>` for CPU-bound frames, one pool per process whose threads end with the process (G-7.10: the shared pool's close does nothing, as no store owns it; [record 165](adr/0165-cdb-parallel-queries.md)); `FileReadRulesTest` and `ThreadRulesTest` name those. frontear ([record 184](adr/0184-frontear-comandos-own-mqtt-octopus-network.md)) gives way to comando's own rules 2 and 13 where cdb does: its launcher registers a shutdown hook; each link log runs one platform thread, `frontear-log-force-<log folder>` (`frontear.store.LogForcer`), whose forces block in the file system, as cdb's log force does; its one class that opens files, `frontear.store.DiskFiles`, writes and forces a log's segments and its cursor, and writes the cursor as a temporary file forced, moved and its folder forced, since `AtomicFiles` does not force, and maps each sealed segment once in a shared arena (tagged with its file and size) that lives until the segment is deleted or the log closes, so a link catching up reads without opening a file per record; and its control plane's strand writes and forces a link's spec through `frontear.store.WholeFile` (G-16.4), so a control request's reply means the change survives a restart, at the rate control requests come; `FileReadRulesTest` and `ThreadRulesTest` name those. G-7.11 and G-7.10 for HTML pages ([record 190](adr/0190-html-pages-render-in-a-panel-through-javafx-webview.md)): the first page view starts JavaFX on the EDT, once per process, and JavaFX's threads are the toolkit's, not the thread kit's, alive until the process exits; until a page renders off the EDT's start. G-11.4 in cdb's tests: most draw from one printed seed `cdb.<name>.seed`, replayed through the `CDB_<NAME>_SEED` variable that `cdb/build.gradle.kts` forwards for every such name, where the standard asks for two; the newer ones (`CDB_PARALLEL_SEED0/1`, `CDB_BACKUP_SEED0/1`, `CDB_DESTRUCTIVE_SEED0/1`) take two; a test a change adds follows the package it joins (G-13.2) until one shared two-seed helper replaces both. G-15.9: cdb's store format is one-way since its write-ahead log ([record 161](adr/0161-cdb-write-ahead-log.md)): a checkpointed `_txn` carries lines an older engine refuses, loudly, rather than open a table whose last commits only the log holds; so is the log's format 2 (record 166), which an older engine refuses; frontear's link-log segment format 2 ([record 191](adr/0191-frontear-compact-link-log-records.md)) is one-way too: an older comando reads a version 2 segment as one that does not follow and cuts the log there |
| 5. Not applied | [§5.1](#51-not-applied): each rule with its sites and why, and either the condition under which it does not fit or the change that must apply it |
| 6. Project packages | `^dev\.marregui\.comando\.` |
| 7. Preview features | None |
| 8. Doc comments in main code | `/** */` |
| 9. Nullability annotations | None; from `org.jetbrains:annotations`, declared `compileOnly`, only `@TestOnly` is used (G-13.5) |
| 10. Licence header | None; G-10.8's header clause is not checked |
| 11. Logging | `System.Logger` (G-6.5), JUL behind it; no hot-path logger |
| 12. Test framework | JUnit Jupiter 6.1.3, AssertJ, Awaitility. Lifecycle: `@BeforeEach`/`@AfterEach`; `@BeforeAll`/`@AfterAll` for a fixture shared by a class (the embedded QuestDB, a Postgres container), closed in `@AfterAll`; `@TempDir` for scratch folders; `@ExtendWith(OnEdt.class)` runs a test on the EDT; `@ParameterizedTest` for tables of cases |
| 13. Entry points, configuration, latency budgets | The `core.port` interfaces; configuration as records, no interface; budgets: the EDT and one intent 200 ms ([Architecture](architecture.md) §6); a frame of the transcript, the plot and the grid p99 16 ms ([§6](#6-quality)) |
| 14. Line limit | 120 characters (the default) |

### 5.1 Not applied

A rule here is not waived: a review still reports it. "Does not fit" names the condition under which the rule has
nothing to act on; when the condition stops holding, the rule applies. Otherwise the last column names the change
that must apply it: "a change to the file" means the next change to that file applies the rule to the whole file,
not only to its changed lines. Every rule applies to every new or changed line. Counts are of 2026-10-31, after the
tool commit that added `final` and ordered the imports.

| Rule | Class | Sites | Why not yet | Applied by |
|---|---|---|---|---|
| G-7.1 to G-7.5 | rule | cdb's query pool (`exec.QueryPool`), a worker pool whose idle workers park on a condition at most 1 s ([record 165](adr/0165-cdb-parallel-queries.md)) | Does not fit elsewhere: no object pools, ring queues or padding; comando's event rates are UI rates. The query pool's frames are CPU-bound work of 65,536 rows, so a park per idle spell costs nothing measurable beside a frame; not measured | when a path takes events at a rate a frame cannot absorb, or a frame shrinks to where the wake-up shows |
| G-8.4 | rule | `SubagentFiles` | Does not fit: a JSONL tail read at UI rate whose consumer, `core.json`'s `Json.readTree`, takes a `String` | when a tail is parsed below line level |
| G-10.11 | rule | 9 fully qualified names in main code (order and unused imports are enforced) | Predates the standard; Checkstyle does not report them | a change to the file |
| G-10.8 | rule | 1,644 main lines over 120, 171 of them pushed past it by the tool that added `final` (2026-10-31); 66 signatures not wrapped | Predates the standard; a tool rewrap would touch every file for no defect | a change to the line by hand |
| G-10.10 | rule | doclint off | Doc comments predate the standard; doclint's count is not taken | its own change: count the reports of `-Xdoclint:all/private,-missing`, fix them, turn it on |
| G-1.1, G-1.3, G-3.7 | rule, hot path | transcript: boxed-key maps (`TextBox.singles`, `TextBox.paragraphs`) and null tests on the paint path (`TextBox`, `Finder`) | The transcript turned hot on its 1,500-line fence (4.75 ms) | a change to the file |
| G-2.3, G-3.1, G-10.7 | rule, hot path | plot: tick text formatted per frame, a `Path2D` and lambdas per series per repaint (`PlotCanvas`); `TextBox` lambdas | The plot is over its budget; its fix is one change | the plot's frame-time fix |
| G-15.2, G-15.12 | defect | frontear MQTT reconnect: no jitter, no attempt limit | Disputed: the standard's v3 backlog asks whether "reconnects while the gateway runs" is a stop condition | the standard's answer |
| G-15.6 | rule | intent queue, strands (a session's among them, timed strands included), `ClaudeProcess` stdin: unbounded | Measured, bound later per use pattern: each reports its depth and peak to the metrics table `comando_queue` | the bound each queue's use calls for |
| G-15.7 | defect | registry removal not fenced | Found 2026-10-30 | a change to `FrontearOctopus`'s registry |
| G-5.3, G-6.5 | rule | log messages not shaped `[key=value]`, the sites in `logging-baseline.txt`; `Pty` has no logger of its own | Predates the standard | a change to the file |
| G-10.1, G-10.5 | rule | member order in the files of `member-order-baseline.txt`; `tl` prefix and `length()` names | Predates the standard | a change to the file |
| G-11.5 | rule | 34 `Thread.sleep` calls in `*Test.java` files (16 inside a loop, 14 fixed waits, among them waits that check nothing happens, 4 in helpers) and 5 unbounded joins (3 `Thread.join()`, 2 `CompletableFuture.join()`), counted 2026-11-01 with `grep`; the hand-run mains `Snapshot` and the three gates hold 18 more and are not tests | Predates the standard | a change to the test |
| G-13.5 | rule | `StateLoop.failure()`: surface for tests only, not marked | Found 2026-10-31 | a change to the file |
| G-13.8 | rule | `Watchdog`'s `finally` untested; `Transcript`'s growth limit untested (2^31 blocks cannot be built in a test) | The first needs a fault-injecting look | a change to `Watchdog` |
| G-14.1, G-14.4 | rule | peer answers and kit backups log failures only; `context` and `window` metrics without unit | Found 2026-10-31 | a change to the file |
| G-15.14 | rule | `subscribe` states no delivery semantics | Found 2026-10-31 | a change to `Peers` |
| G-16.1, G-16.2 | rule | `default` arms over sealed `Block` (outside `TranscriptCanvas`) and `Intent`; casts after type tests | Predates the standard | a change to the file |
| own rule 5 | rule | 12 test files build Swing components off the EDT (`ResourceDialogTest`, `MenuBarTest`, `TableSourceTest`, ...) | Predates rule 5's test clause | a change to the test |

## 6. Quality

`./gradlew build` compiles every module with warnings as errors and runs the unit and adapter tests.

| What is tested | Against |
|---|---|
| the database adapters | an embedded QuestDB, and PostgreSQL through Testcontainers when Docker is there |
| the Claude adapter | fake CLI scripts and recorded streams |
| the MCP server | real requests over HTTP |
| the reducer and the loop | the recording fakes of [testkit](modules/testkit.md) |
| the window | the real window, off screen ([§2.1](#21-the-window-tests)) |

> **Learn: the 99th percentile.** An average hides the bad moments. If 99 frames take 2 ms and one takes 200 ms,
> the average is about 4 ms and looks fine, yet you saw the window freeze. So measure the slow end. Sort all the
> frame times: the 99th percentile, p99, is the time that 99 frames in 100 stay under. A screen at 60 frames a
> second gives each frame 16 ms. comando's gates hold the p99 of a frame under that budget.

| Gate | Command | Budget |
|---|---|---|
| transcript frame time | `./gradlew :ui:transcriptGate` | p99 ≤ 16 ms; enforced ([ui](modules/ui.md)) |
| grid and plot frame time | `./gradlew :ui:gridGate`, `:ui:plotGate` | measured, not enforced: p99 printed, `HOT` above 4 ms, `OVER BUDGET` above 16 ms |
| EDT responsiveness | always on: `EdtWatchdog` | a probe answered within 200 ms |
| reducer responsiveness | always on: `StateLoop` and its watchdog look | each intent applied within 200 ms |
| threads | `ThreadRulesTest`, `ThreadBudgetTest` | every thread of a known kind, within its budget, none alive after close ([Architecture](architecture.md)) |
| file reads | `FileReadRulesTest` | every read through `Mapped`, but for the sites the test names (rule 16) |
| layers | `UiArchitectureTest`, `CdbArchitectureTest` | `ui` calls no adapter and no widget imports another (rule 4); cdb is reached through its facade only, by the `Cdb*` classes of `db` only |
| tools and gestures | `ActionParityTest` | every intent the window sends has an action an agent can call ([Claude Code](claude-code.md)) |

**Profiling.** comando has no JFR code and imports no `jdk.jfr`. You profile it from outside like any JVM, with the
JDK's own events, and its EDT stalls are in its log. The packaged runtime keeps `jdk.jfr`, `jdk.management.jfr` and
`jdk.management.agent`, so the installed application can be recorded and watched over JMX
([record 54](adr/0054-comando-knows-nothing-of-jfr.md)).

**Packages.** The packages are built with `jpackage` and a runtime of the modules listed in `app/build.gradle.kts`.
Nothing is signed ([record 16](adr/0016-unsigned-macos-build.md)). [INSTALL.md](../INSTALL.md) is the install
recipe.

## 7. Documentation

The documents are comando's specification as much as its manual. They are a network: every page stands alone,
links to its neighbours, and teaches the idea behind what it describes.

| Rule | How |
|---|---|
| One shape | A page has a title, one picture within its first five lines, "In one paragraph" (what, for whom, why), numbered sections, and "Where next" with two to five links. |
| Learn boxes | A general idea is taught where comando uses it: a blockquote that starts `> **Learn: <idea>.**`, three to six sentences a sharp 13-year-old can follow, then how comando applies it. |
| Plain words | Short sentences, active voice, present tense, the reader as "you". Tables for comparisons and reference lists. Exact names stay exact. |
| No history | A page says what is, not what was. It names no internal task or ticket. Why a thing is the way it is belongs to a decision record. |
| One canonical place | Each concept is described on one page; [§7.2](#72-where-each-concept-lives) says which. The others link to it in at most a line. The tutorial alone repeats: it teaches by walking the window, and Help ▸ Tutorial shows its links as plain text, so it must stand on its own. |
| True to the code | The code is the source of truth. A claim about behaviour is checked against the code when it is written, and a change to the code changes the page in the same commit. What cannot be verified is left out. |
| Links resolve | Every relative link, image and anchor in `README.md`, `INSTALL.md` and `docs/` lands on a file and a heading that exist. A link never points at `#` alone. |
| Where files sit | Every page but the root `README.md` and `INSTALL.md` sits in `docs/`, with one entry point, [docs/README.md](README.md). The folders are `modules/` (a page per module), `images/` and `adr/`. |
| Decisions | One record per decision in `adr/`, dated, with who decided. [adr/README.md](adr/README.md) is the index, checked by `AdrIndexTest`. |
| The tutorial ships | `docs/tutorial.md` is copied into the `ui` jar for Help ▸ Tutorial (`ui/build.gradle.kts`), so the app and the docs show one text. It is the one documentation change CI builds. |
| A kit and a brain, in general | The pages describe what any kit and any brain are. They quote no person's own kit or memory. |

### 7.1 Pictures

Every picture is a hand-drawn SVG in `docs/images/`, embedded with a sentence that says what it shows as its
alternative text. There are no screenshots and no Mermaid diagrams. A picture earns its place when it shows a structure or a sequence that
text cannot. All pictures share one look, comando's graphite and gold:

| Element | Value |
|---|---|
| ground | graphite, `#121212` to `#1B1B1B` |
| title | spaced capitals in gold `#E7C55A`, a light headline under them |
| text | `#E8E8E8`; secondary `#9AA0A6`; at least 13 px, readable at a width of 800 to 1200 px |
| fonts | `-apple-system, Segoe UI, Helvetica, Arial, sans-serif`; code labels in `Menlo, Consolas, monospace` |
| lines and boxes | hairlines; a box is outlined in its owner's colour on a fill of `#1E1E1E` |
| owners | comando `#6FAEE8` blue, Claude Code `#D98E5F` copper, the kit `#6CC69A` jade, instances `#AEB6C1` silver; gold `#E7C55A` for the centre of the picture |
| legend | real swatches, wherever a colour means something |
| footer | `comando · docs/<page>` at the right |
| file | self-contained, with a `viewBox` and a `<title>` element that says what the picture shows |

Check each picture before it lands: `xmllint --noout <file>` exits 0; then render it to a PNG and look at it.
Fix every overlap and every clipped text.

A picture's prefix names the page that owns it:

| Prefix | Page |
|---|---|
| `arch-` | [Architecture](architecture.md), and the hub [docs/README.md](README.md) |
| `wb-` | [Workbench](workbench.md) |
| `tut-` | [Tutorial](tutorial.md) |
| `cc-` | [Claude Code](claude-code.md) |
| `kb-`, `kit-`, `brain-` | [Kit and brain](kit-and-brain.md), [The kit](kit.md), [The brain](brain.md) |
| `cdb-`, `sql-`, `ft-` | [cdb](cdb.md), [cdb SQL](cdb-sql.md), [frontear](frontear.md) |
| `mod-<module>-` | the module's page in `modules/` |
| `dev-` | this page |
| none | shared: `comando.svg` (the icon, a copy of `app/src/main/icon/comando.svg`) and `glance.svg` in the [README](../README.md); `command-flow.svg`, `instance-ask.svg`, `approval.svg` |

### 7.2 Where each concept lives

When a feature lands, its description goes to the one page below that owns the concept, and every other mention
becomes a link. A page that is not the owner keeps only what its reader needs to follow its own text.

| Concept | Owner |
|---|---|
| why comando exists, what it does at a glance | [README](../README.md) |
| installing, upgrading and uninstalling comando; offering a kit | [Install](../INSTALL.md) |
| the map of the pages, the reading paths, how to read a page | [docs/README.md](README.md) |
| a first walk through the window | [Tutorial](tutorial.md), which repeats on purpose |
| the window, panels, themes and their file format, the sidebar and its tabs, the lower panel and views, the session bar and status bar, the palette, Find | [Workbench](workbench.md) §1 |
| how a block is classified and where it goes, keys on every desktop, where results land | [Workbench](workbench.md) §2 |
| what you do with a database: connections, lanes, cancel, results, plots, the schema explorer | [Workbench](workbench.md) §3 |
| instances, frontear, what travels between instances | [Workbench](workbench.md) §4 |
| the terminal and Gradle runs | [Workbench](workbench.md) §5 |
| the Kit, Brain and Scratchpad tabs and git changes | [Workbench](workbench.md) §6 |
| what is kept on disk: the data root and each file in it | [Workbench](workbench.md) §7 |
| the `claude -p` process: arguments, MCP configuration, the stream, turns, stop; stored conversations and their search; what a session works on; subagents; models and effort | [Claude Code §1](claude-code.md#1-the-claude-bridge) |
| the checkpoint | [Claude Code §2](claude-code.md#2-the-checkpoint) |
| the MCP server and its tools, the action registry's rules, the audit log, the metrics sessions write | [Claude Code §3](claude-code.md#3-mcp-tools) |
| approvals, permission rules, read-only SQL, production connections, secrets | [Claude Code §4](claude-code.md#4-approvals-and-safety) |
| how kit, brain, sessions, agents and gates fit together | [Kit and brain](kit-and-brain.md) |
| a kit's parts, the root and how comando finds it, the launch environment, the manifest, upgrades, scheduled jobs, a minimal kit | [The kit](kit.md) |
| the brain: memory, the knowledge base, the scratchpad, recall, the reflect cycle, budgets, the learning loop, a new brain | [The brain](brain.md) |
| cdb for its user: running it, its three doors, access, storage, durability, retention, backup, limits | [cdb](cdb.md) |
| cdb's SQL: statements, grammar, types and functions | [cdb SQL](cdb-sql.md) |
| frontear for its user: nodes, links, signed control messages, monitoring, operations | [frontear](frontear.md) |
| the design as a whole: processes, modules, one command's path, state, intents and effects, ports, threads and the thread rules, sessions, instances, data on disk, ownership and exit | [Architecture](architecture.md) |
| a module's classes, interface, own threads and tests | its page: [core](modules/core.md), [testkit](modules/testkit.md), [cdb](modules/cdb.md), [frontear](modules/frontear.md), [db](modules/db.md), [claude](modules/claude.md), [mcp](modules/mcp.md), [tools](modules/tools.md), [ui](modules/ui.md), [app](modules/app.md) |
| prerequisites, Gradle tasks, CI, configuration, code rules, quality gates, these rules, the pictures, adding a feature | [Development](development.md) |
| why a thing is the way it is | [Decisions](adr/README.md) |

## 8. Adding a feature or a module

![The path of a new feature in eight steps: an Intent names what happens; a rule in the Reducer turns state and intent into new state and effects; an Effect asks for work and EffectRouter sends it to a port; the port is an interface in core.port with a recording fake in testkit; an adapter module implements the port; an entry in Actions makes the feature an MCP tool; a command or widget in ui makes it a gesture; tests, the owning page and a decision record close it](images/dev-feature.svg)

A feature grows from the core outwards. The first three steps need no window, no process and no network, so you
can test the behaviour before any of them exists. [Architecture](architecture.md) explains each part.

| Step | Where | What you add |
|---|---|---|
| 1 | `core.intent.Intent` | A record that names what happened or what is wanted. `Intent` is sealed, so the compiler shows every place that must handle it. |
| 2 | `core.reduce` | A rule: state and intent in, new state and effects out. The rules sit by area (`SessionRules`, `PanelRules`, `QueryRules`, `ApprovalRules`, `TurnRules`, `ViewRules`, `FolderRules`). A rule does no I/O and reads no clock. When the intent can be refused, `Reducer.refusal` says why. |
| 3 | `core.effect.Effect` | A request for work outside the core, when the feature needs any. `EffectRouter` hands it to a port. |
| 4 | `core.port`, `testkit` | A method on a port, or a new port, and its fake in `RecordingPorts`. The fake records each call, so a reducer test reads what was asked. |
| 5 | an adapter module | The real work: `claude`, `db`, `tools`, `mcp` or `frontear`. It runs on the adapter's own threads and answers with intents, never by touching state. |
| 6 | `core.action.Actions` | One entry makes the intent a tool named `<area>_<verb>` that an agent can call. `ActionParityTest` fails while the window sends an intent that has no action. |
| 7 | `ui` | A command with its key in `ui.shell.Commands`, and a widget that draws the new state. A widget reads state and sends intents; it calls no adapter (rule 4). |
| 8 | tests and documents | A test per behaviour, each with its `///` comment (G-11.7); a window test when the behaviour is the window's ([§2.1](#21-the-window-tests)). The owning page of [§7.2](#72-where-each-concept-lives) in the same commit. A record in `docs/adr/` when you decided something, with its line in the index. |

**A recorded run becomes a test.** Start comando with `-Dcomando.recordIntents=true` and use the feature. The
intent log in `logs/` holds every intent in order. `UiReplayTest` replays such a log against the window with every
adapter a recording fake.

**A new module** needs four things:

| What | Where |
|---|---|
| its name | `include(…)` in `settings.gradle.kts`; the root `build.gradle.kts` then gives it the compiler flags, Checkstyle and the test setup |
| its dependencies | `core` alone. Two modules differ on purpose: `cdb` depends on the JDK alone, and `db` uses `cdb` as a library. Only `app` depends on every module. |
| its wiring | `app`, the composition root, builds it and hands it its ports ([app](modules/app.md)) |
| its page | `docs/modules/<module>.md`, a row in [§7.2](#72-where-each-concept-lives), and a link from [docs/README.md](README.md) |

A new platform thread needs a new kind first (rule 2), and a new file read goes through `Mapped` (rule 16). The
rule tests fail the build otherwise.

## Where next

- [Architecture](architecture.md): the design these rules protect.
- [core](modules/core.md) and [testkit](modules/testkit.md): where a feature starts and how it is tested.
- [ui](modules/ui.md): the render loop and the frame-time gates.
- [Decisions](adr/README.md): why each thing is the way it is.
- [INSTALL.md](../INSTALL.md): installing a release instead of building one.
