# Development

How I build comando from a clone, run it, test it and release it; the settings it reads at start; the code rules it is
held to; the quality gates; and how these documents are kept. Installing a release is in [INSTALL.md](../INSTALL.md);
how the code is organised is in [Architecture](architecture.md).

1. [Prerequisites](#1-prerequisites)
2. [Gradle tasks](#2-gradle-tasks)
3. [Continuous integration](#3-continuous-integration)
4. [Configuration](#4-configuration)
5. [Code rules](#5-code-rules)
6. [Quality](#6-quality)
7. [Documentation](#7-documentation)

## 1. Prerequisites

| Need | For | Note |
|---|---|---|
| JDK 25 | building, and running from Gradle | the build names a JDK 25 toolchain and provisions none: Gradle finds an installed JDK 25 or stops |
| Claude Code CLI | sessions | looked for at `~/.local/bin/claude`, `~/.claude/local/claude`, `/opt/homebrew/bin/claude`, `/usr/local/bin/claude`, else `claude` on the `PATH`; `COMANDO_CLAUDE` overrides. Tested from 2.1.316, the newest on the release's day and the release's minimum, up to, not including, 2.2.0; another version is named in a warning at start |
| Codex CLI | Codex's usage in the status bar | optional; looked for at `~/.local/bin/codex`, `/opt/homebrew/bin/codex`, `/usr/local/bin/codex`, else `codex` on the `PATH`; `COMANDO_CODEX` overrides |
| Docker | the PostgreSQL adapter test (Testcontainers) | optional; without it that test is skipped |
| macOS | the login keychain, `:app:dmg` | everything else runs on Linux too, with Ctrl for ⌘ ([record 65](adr/0065-keys-on-desktops-other-than-macos.md)). CI builds and tests on macOS and Linux; Windows has a package, built by the release workflow, and no test run |
| IntelliJ IDEA's `idea` launcher | "Open in IntelliJ" | optional; without it a file opens in the system's default application |
| `mmdc` (Mermaid CLI) | diagrams in Markdown previews | optional; looked for on the `PATH`, under `~/.nvm`, in `/opt/homebrew/bin` and `/usr/local/bin`; without it a Mermaid block is not drawn |
| folders of documents | the sidebar's Files tab | optional; at first the kit's folders when there is a kit, else Claude Code's folder, until you choose your own ([Kit and brain §3](kit-and-brain.md#3-comando-and-a-kit)); a configured folder that is missing says so in its row |
| `gradle-serial` on the `PATH` | Run Gradle tasks (⌥⌘G), queued per checkout | optional; comando ships none. Found on comando's `PATH`, a run goes through it; else a run is the checkout's `./gradlew` |

## 2. Gradle tasks

| Command | Does |
|---|---|
| `./gradlew build` | compiles every module with javac's lint warnings as errors (`-Xlint:all` less `serial`, `-Werror`), checks main and test code with the Java Coding Standard's Checkstyle configuration (`config/checkstyle/checkstyle.xml`; any report fails it), and runs the unit and adapter tests; the opt-in tests are skipped |
| `./gradlew :app:run` | starts comando on the default data root from a copy of the build output made at launch, so a build while it runs leaves it alone (below) |
| `./gradlew :app:installDist` | a runnable distribution in `app/build/install/comando`; start it with `bin/comando` |
| `./gradlew :app:package` | this system's release file, with its own Java runtime, unsigned, in `app/build/package`: `comando-<version>-macos-<arch>.dmg`, `comando-<version>-linux-<arch>.tar.gz` or `comando-<version>-windows-<arch>.zip`. `<arch>` is `arm64` or `x64`; each archive holds one folder, `comando/`. `-PreleaseVersion=<version>` names the file and sets the version comando shows, as the release workflow does from the tag |
| `./gradlew :app:dmg` | an unsigned macOS disk image with its own runtime, in `app/build/dmg` |
| `./gradlew :app:appImage` | the application as a folder with its own runtime, in `app/build/app-image`: `comando.app` on macOS, `comando/` elsewhere |
| `./gradlew test -PkeychainTest=true` | the tests, plus the one that writes and deletes one item in the login keychain |
| `./gradlew :app:test -PedgeLiveTest=true` | the tests, plus the one that downloads the pinned open-source Edge release (359 MB) and runs it on free loopback ports; `-PedgeLiveCache=<folder>` keeps the zip between runs |
| `./gradlew :app:uiTest` | the tests that need a real window ([§2.1](#21-the-window-tests)); needs a display (`xvfb-run` on Linux) |
| `./gradlew :ui:gridGate`, `./gradlew :ui:plotGate` | measure the grid's and the plot's frame times; print, never fail ([Architecture §14.5.3](architecture.md#1453-the-frame-time-gate)) |
| `./gradlew :ui:transcriptGate` | measures the transcript's frame times; fails when a 99th percentile exceeds 16 ms (exit 1) or when the JVM does not end by itself within 10 s of the measurement (exit 2) ([Architecture §14.5.3](architecture.md#1453-the-frame-time-gate)) |
| `./gradlew :app:snapshot -Pout=<dir>` | drives comando on a scratch data root and paints the window to PNG files. `-Plive=true` adds a real Claude session (haiku) whose approvals are allowed; `-Phistory=<conversation id>` loads a stored conversation; `-Ptools=true` adds a Gradle run in a build of its own. A relative `<dir>` is under `app/` |

`:app:snapshot` and `:app:uiTest` open their windows off screen (`comando.offScreen`), without a Dock
icon (`apple.awt.UIElement`), and never take the desktop's focus, so they run while the desktop is in use.

A build rewrites the module jars and class files in place, and a running JVM loads classes from them lazily, so a
build under a running comando breaks it at the next class it loads. `:app:run` therefore copies this checkout's build
output to a folder of its own under `.gradle/run-stage` at each launch and runs from the copy. A later launch deletes a
copy once it is two minutes old and no live process names it on its command line. Jars from Gradle's cache are used in
place, as no build rewrites them; `clean` leaves `.gradle/` alone.

Tests run headless, with the data root in each module's `build/test-home`, so they never read or change the user's
data; a window test without a data root keeps its settings in memory, and the earlier preferences it might read are
under a node of its own. The modules test side by side (`gradle.properties`), each in up to three JVMs; `:app:uiTest`
runs one JVM per test class, one at a time, as its windows share one desktop.

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
| `CheckpointCompactUiTest` | the Checkpoint button against a stand-in for `claude -p`: the checkpoint, then `/compact` once its turn has finished, the conversation and the Session tab kept, nothing carried, the panel's text kept ([record 129](adr/0129-a-checkpoint-reflects-then-compacts.md)) |
| `CheckpointLockUiTest` | the session locked while its checkpoint runs ([record 130](adr/0130-a-session-locks-while-its-checkpoint-runs.md)) |
| `ThemeFadeUiTest` | a theme chosen fades in over the one before: ground and tint applied at once, the old picture over the new |
| `LaterDecisionsUiTest` | a decision held back joins the Approvals tab's Later group and title without coming forward, is read back from `decisions.json` at the next start, and Dismiss removes it ([record 131](adr/0131-an-automatic-checkpoint-asks-nothing.md)); decisions of two sessions, one chosen and one typed, go out in one Send answers, in order ([record 142](adr/0142-one-decision-queue.md)) |
| `QuestionLaterUiTest` | a decision held with options shows one toggle per option and no typed field ([record 142](adr/0142-one-decision-queue.md)) |
| `SessionCardUiTest` | a session's row shows the tab's card, a connection's row a card of its facts; both painted to `app/build/snapshots/` |
| `LinksUiTest` | a memory file's front matter shown as facts; a double click on a `[[name]]` link opens the named file, on a web address goes to the browser ([record 94](adr/0094-links-in-both-styles-everywhere.md)) |
| `FocusBackUiTest` | closing what a double click opened gives the keyboard back to what opened it ([record 112](adr/0112-closing-gives-the-keyboard-back.md)) |
| `OutlineUiTest` | a Markdown document's outline at the panel's right; a double click on a heading scrolls the page to it ([record 113](adr/0113-a-documents-outline-at-the-panels-right.md)) |
| `DiffThemeUiTest` | the comparison window opens on the theme chosen, its texts and rendered sides, and follows the next ([record 135](adr/0135-the-comparison-follows-the-theme-and-lies-level.md)) |
| `LowerDividerUiTest` | the divider above the lower panel is each panel's own ([record 115](adr/0115-the-lower-divider-per-panel.md)) |
| `LocateUiTest` | a panel's Locate button selects its file in the Files tab or its session in the Sessions tab ([record 119](adr/0119-the-ui-simplified-kit-row-locate-no-thinking-mark.md)) |
| `OwnLogUiTest` | comando's log opened from Help shows the lines written after it opened, at its end, also after a restart |
| `LayoutKeeperUiTest` | the sidebar's width kept in `settings.json` comes back at the start (a value that is no number gives the default); a session's arrangement hides the sidebar to its rail and shows it again at its kept width; the width left is saved a second after the window last changed size and the next window opens with it |
| `QuitTeardownUiTest` | a step of the window's teardown or close that throws stops neither the quit nor the closing of the window's errands |
| `AttentionUiTest` | a turn that ends behind another panel puts its session under To read in the Sessions tab and the work list; a click on the line focuses its panel and the mark goes ([record 136](adr/0136-finished-work-waits-to-be-read.md)) |
| `ReportFirstUiTest` | a turn of tools and a report folds to one row; a click opens it and a second closes it; a find opens a closed row and closes it when it moves on; asking for the report puts it at the view's top ([record 137](adr/0137-a-turn-reads-as-its-report.md)) |
| `SubjectUiTest` | a session in Claude Code's folder shows no branch; a turn's writes put branch and repository on the session bar and the ticket on the card, and the row stays one line ([record 138](adr/0138-a-session-shows-what-it-writes-to.md)) |
| `SearchUiTest` | ⌘⇧F opens the Search view in the side pane with the keyboard in its field, and typing lists a hit under its folder, file and heading; ↓ shows the hit in the preview tab, every hit marked, the keyboard kept in the rows, and F7 walks the hits; ⏎ keeps the preview tab; the selection fills the field; the toggles decide what is searched; the Find menu and the palette hold the command; a conversation a session holds opens there with the transcript's Find on the query, another offers Import ([record 139](adr/0139-one-window-finds-in-files-and-conversations.md), [record 141](adr/0141-the-right-side-is-a-partition-of-the-workspace.md)) |
| `DeliverablesUiTest` | the Files written view lists a session's two written files in order, the first gold; Enter opens it in the preview tab and the row turns plain ([record 143](adr/0143-a-session-lists-the-files-it-wrote.md)) |
| `TranscriptAboveUiTest` | a session's panel in front puts the lower area above it and a file puts it back below, at the same height; turned off in `settings.json`, or by an arrangement as `ui_set_layout` sends, it stays below ([record 144](adr/0144-the-transcript-sits-above-the-prompt.md)) |
| `PartitionUiTest` | a panel and the Changes view go to the right side by their tab's menu and back; a file moved there leaves the lower panel shown, at a side view's width; ⌘1 to ⌘3 go to the main tabs' panels, then the right side's; ⌘W and ⌘⇧T close and bring back a side panel; the partition follows a theme chosen while it was hidden; a side panel and the side views come back after a restart, the layout saved by the close ([record 141](adr/0141-the-right-side-is-a-partition-of-the-workspace.md)) |
| `CompareKeysUiTest` | F7 and ⇧F7 walk the differences of the comparison window a changed file opens in |
| `FocusRouterUiTest` | a view opened in the lower area while the keyboard is in a panel's text takes nothing from it, and closing it in the background moves nothing ([record 112](adr/0112-closing-gives-the-keyboard-back.md)) |

## 3. Continuous integration

| Workflow | Runs on | Does |
|---|---|---|
| `build` | every push to `main` and every pull request, Ubuntu and macOS; a weekly run on Mondays; by hand | `./gradlew build`, then `:app:uiTest` (under `xvfb-run` on Ubuntu), a per-module test report with every skipped test named (`ci/TestReport.java`), `:ui:transcriptGate` reported but not enforced, as runner speed varies, and on macOS `:app:dmg` |
| `release` | a tag `v*`; by hand | `:app:package` with `-PreleaseVersion=<tag without v>` on macOS, Ubuntu and Windows without the tests, then the tag's GitHub release of the three files, its notes the commits since the previous tag; by hand, the packages are the run's artifacts, kept 7 days, and a release is made only on a tag with dry run off |
| `assign` | every pull request opened, reopened or ready for review | assigns it to the owner; checks out nothing |

`build` and `release` use Temurin 25 and actions pinned by SHA. `dependabot.yml` opens one grouped pull request a
month for the actions and one for the Gradle dependencies.

A push or pull request that changes only documentation builds nothing, except a change to `docs/tutorial.md`, which
the ui jar carries. The PostgreSQL adapter test runs on Ubuntu only: macOS runners have no Docker.

## 4. Configuration

| Setting | Kind | Default | Effect |
|---|---|---|---|
| `comando.home` | system property | — | the data root; wins over `COMANDO_HOME`; a leading `~` is the home folder |
| `COMANDO_HOME` | environment | `~/.comando` | the data root; a leading `~` is the home folder |
| `comando.name`, `COMANDO_NAME` | system property, environment | the data root's `instance.json` `name` (written by whoever creates the instance), else its folder: none for `~/.comando`, `b` for `~/.comando_b` | the instance's name: the Dock's, the menu bar's and the window's `comando · b` (a name that says comando, `Comando Expert`, as it is), and the Dock badge, its first letter ([record 122](adr/0122-instances-name-kit-brain-data-root-edge.md)) |
| `COMANDO_CLAUDE` | environment | the search in [§1](#1-prerequisites) | the Claude Code executable |
| `COMANDO_CODEX` | environment | the search in [§1](#1-prerequisites) | the Codex executable, read for the status bar's usage |
| `comando.edge.zip`, `COMANDO_EDGE_ZIP` | system property, environment | the pinned open-source HiveMQ Edge 2026.15 release, downloaded once into `edge/dist/` of the data root and checked against its published SHA-256 | the Edge distribution the comando Edge is installed from; the property wins ([Workbench §4.1](workbench.md#41-the-comando-edge)) |
| `CLAUDE_CONFIG_DIR` | environment | unset | the root comando's sessions run on, in place of the folders it looks in (`~/.claude`, `~/comando-kit`, `~/.claude/comando-kit`) ([Kit and brain §3.1](kit-and-brain.md#31-the-root)) |
| `COMANDO_BRAIN` | environment | unset | the brain's folder, over the manifest's `brain`; used only with a kit ([Kit and brain §3.1](kit-and-brain.md#31-the-root)) |
| `comando.keychain` | system property | `true` for `~/.comando`, else `false` | keep connection passwords in the login keychain (macOS); elsewhere they stay in `connections.json` |
| `comando.recordIntents` | system property | `false` | write the intent log to `logs/` in the data root |
| `comando.prefsNode` | system property | `dev/marregui/comando`; `dev/marregui/comando-<folder>` for another data root, named by its folder | the Java preferences node read once into the data root's `settings.json` when that file does not exist yet ([record 106](adr/0106-settings-in-the-data-root.md)) |
| `comando.offScreen` | system property | `false` | `true` opens the window undecorated, left of every screen, never taking the focus; set by `:app:uiTest` and `:app:snapshot` |
| `comando.keys` | system property | the desktop's | `mac` or `linux` chooses macOS's keys or the other desktops' on any desktop; for tests |
| `comando.peers` | system property | `true` | `false` keeps the instance off the comando Edge; set by every test task of `app` and by `:app:snapshot`, so no test joins a developer's running Edge |

System properties reach the JVM through `COMANDO_OPTS` (or `JAVA_OPTS`) for the launcher `./gradlew :app:installDist`
writes:

```
COMANDO_OPTS="-Dcomando.recordIntents=true" app/build/install/comando/bin/comando
```

## 5. Code rules

comando follows the [Java Coding Standard v2.0.3](https://github.com/marregui/java-standard/blob/v2.0.3/STANDARD.md),
on JDK 25 (`release = 25`). Its rules live there and are cited by ID (`G-<section>.<n>`); this section holds comando's
own rules, keeps the numbers other documents cite, and is comando's Apply section (§12 of the standard), its parts in
the table below. Rules 12 to 16 cover what a desktop application meets and the standard leaves open.

1. A change to state is an intent; the reducer is the only writer of `AppState`.
2. Threads follow G-7.10. A thread is of one of the six kinds of [Architecture §6](architecture.md#6-threads) and comes from `Threads`, an owner's `Errands` or
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
    terminal's shell, the embedded Edge) is started, drained and ended with its descendants by the adapter that owns
    it, and its close waits a bounded time. The one exception is the machine's QuestDB
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
    stay the libraries that take only a path, process pipes, the instance lock, the machine's QuestDB's start lock
    and writes. `FileReadRulesTest`
    fails the build otherwise ([record 140](adr/0140-every-file-is-read-mapped.md)).

| Part | comando |
|---|---|
| 1. Version | v2.0.3, JDK 25. No `AGENTS.md` or `CLAUDE.md` in the repository yet to point an agent here (§12) |
| 2. Hot paths | A path is hot when the p99 of its frame-time gate exceeds 4 ms. Hot: the transcript renderer (`ui.widgets.transcript.render`: `TranscriptCanvas`, `TextBox`; layouts kept within a window around the view and let go beyond it, only the clip painted), `:ui:transcriptGate`, p99 3.09 ms on the gate's document with each turn's row opened and 4.75 ms on a 1,500-line code fence ([Architecture §14.5.3](architecture.md#1453-the-frame-time-gate)); the plot (`ui.widgets.plot`: `PlotCanvas`), `:ui:plotGate`, p99 28–32 ms over three runs on an M2 Max, over its 16 ms budget: a crosshair move repaints and re-reduces every series (2026-10-31). Not hot: the grid (`GridTable`), `:ui:gridGate`, p99 1.4–1.5 ms. `Transcript`, which the transcript renderer reads, publishes with release-acquire ordering (a volatile write after the data); so does `ColumnarResult`, which the grid reads. G-1 to G-3 and G-10.7 apply on the hot paths only; what they leave open there is in [§5.1](#51-not-applied) |
| 3. Applied and enforced | Enforced by the build or a test: G-10.10 (`-Xlint:all -Werror`, `serial` excluded with its reason beside the flag in `build.gradle.kts`; doclint not yet); G-9.5 (the `this-escape` lint); G-10.2, G-10.8's braces and G-10.11 (the standard's Checkstyle configuration, copied to `config/checkstyle/checkstyle.xml`, on main and test code; any report fails `build`); G-7.10 (`ThreadBudgetTest`: no platform thread and none of the threads `Threads` made alive after close, read from `Threads.alive()`). Applied and checked by review only, no checker in the repository: G-11.7 (every test documented with `///`), G-10.5 (test methods named for the behaviour they prove), G-10.2 on what Checkstyle does not report (fields, try-with-resources variables, multi-catch parameters, basic-for initialisers), G-10.11's fully qualified names. Measured at run time, not enforced: G-7.11 (`EdtWatchdog` logs a stall of the EDT over 200 ms with its length when it ends, and the EDT's stack at 1 s, 2 s for a stall in the JDK's drawing; the `StateLoop` watchdog logs an intent still applying at 1 s with the loop's stack) |
| 4. Departures kept on purpose | G-14.6 and G-14.11: no JFR code ([§6](#6-quality)). G-16.4: `FolderSearch` reads files on its forks, virtual threads, capped at half the cores: comando has no bounded platform kind; until it has one ([record 139](adr/0139-one-window-finds-in-files-and-conversations.md)). G-4.7: `Mapped`'s confined arenas carry no tag or byte count: each lives for one read on one thread and is closed by try-with-resources; until a leak report needs one. G-10.1 in test classes already in the repository: their private helpers and fixtures may stay before or between the tests, where many of them have been since the first tests (2026-11-08); a test class a change adds or reorders keeps the standard's order, nested types last |
| 5. Not applied | [§5.1](#51-not-applied): each rule with its sites and why, and either the condition under which it does not fit or the change that must apply it |
| 6. Project packages | `^dev\.marregui\.comando\.` |
| 7. Preview features | None |
| 8. Doc comments in main code | `/** */` |
| 9. Nullability annotations | None; from `org.jetbrains:annotations`, declared `compileOnly`, only `@TestOnly` is used (G-13.5) |
| 10. Licence header | None; G-10.8's header clause is not checked |
| 11. Logging | `System.Logger` (G-6.5), JUL behind it; no hot-path logger |
| 12. Test framework | JUnit Jupiter 6.1.3, AssertJ, Awaitility. Lifecycle: `@BeforeEach`/`@AfterEach`; `@BeforeAll`/`@AfterAll` for a fixture shared by a class (the embedded QuestDB, a Postgres container), closed in `@AfterAll`; `@TempDir` for scratch folders; `@ExtendWith(OnEdt.class)` runs a test on the EDT; `@ParameterizedTest` for tables of cases |
| 13. Entry points, configuration, latency budgets | The `core.port` interfaces; configuration as records, no interface; budgets: the EDT and one intent 200 ms ([Architecture §6](architecture.md#6-threads)); a frame of the transcript, the plot and the grid p99 16 ms ([§6](#6-quality)) |
| 14. Line limit | 120 characters (the default) |

### 5.1 Not applied

A rule here is not waived: a review still reports it. "Does not fit" names the condition under which the rule has
nothing to act on; when the condition stops holding, the rule applies. Otherwise the last column names the change that
must apply it: "a change to the file" means the next change to that file applies
the rule to the whole file, not only to its changed lines. Every rule applies to every new or changed line from now
on. Counts are of 2026-10-31, after the tool commit that added `final` and ordered the imports.

| Rule | Class | Sites | Why not yet | Applied by |
|---|---|---|---|---|
| G-7.1 to G-7.5 | rule | none | Does not fit: no object pools, ring queues, padding or worker pools; comando's event rates are UI rates | when a path takes events at a rate a frame cannot absorb |
| G-8.4 | rule | `SubagentFiles` | Does not fit: a JSONL tail read at UI rate whose consumer, Jackson, takes a `String` | when a tail is parsed below line level |
| G-10.11 | rule | 9 fully qualified names in main code (order and unused imports: enforced since 2026-10-31) | Predates the standard; Checkstyle does not report them | a change to the file |
| G-10.8 | rule | 1,644 main lines over 120, 171 of them pushed past it by the tool that added `final` (2026-10-31); 66 signatures not wrapped | Predates the standard; a tool rewrap would touch every file for no defect | a change to the line by hand |
| G-10.10 | rule | doclint off | Doc comments predate the standard; doclint's count is not taken | its own change: count the reports of `-Xdoclint:all/private,-missing`, fix them, turn it on |
| G-1.1, G-1.3, G-3.7 | rule, hot path | transcript: boxed-key maps (`TextBox.singles`, `TextBox.paragraphs`) and null tests on the paint path (`TextBox`, `Finder`); `TranscriptCanvas`'s item map by boxed index became a list on 2026-11-03, and its boxed-value map (`boxIndex`) and paint-path null tests went on 2026-11-08 | The transcript turned hot on its 1,500-line fence (4.75 ms) | a change to the file |
| G-2.3, G-3.1, G-10.7 | rule, hot path | plot: tick text formatted per frame, a `Path2D` and lambdas per series per repaint (`PlotCanvas`); `TextBox` lambdas | The plot is over its budget; its fix is one change | the plot's frame-time fix |
| G-15.2, G-15.12 | defect | Edge MQTT reconnect: no jitter, no attempt limit | Disputed: the standard's v3 backlog asks whether "reconnects while the Edge runs" is a stop condition | the standard's answer |
| G-15.6 | rule | intent queue, strands (a session's among them, timed strands included), `ClaudeProcess` stdin: unbounded | Measured, bound later per use pattern (Miguel's decision D6, 2026-11-01): each reports its depth and peak to the metrics log's `queues` event | the bound each queue's use calls for |
| G-15.7 | defect | registry removal not fenced | Found 2026-10-30 | a change to `EmbeddedEdge`'s registry |
| G-5.3, G-6.5 | rule | log messages not shaped `[key=value]`; `Pty` has no logger of its own | Predates the standard | a change to the file |
| G-10.1, G-10.5 | rule | member order in `PlotGate`; `tl` prefix and `length()` names | Predates the standard | a change to the file |
| G-11.5 | rule | 34 `Thread.sleep` calls in `*Test.java` files (16 inside a loop, 14 fixed waits, among them waits that check nothing happens, 4 in helpers) and 5 unbounded joins (3 `Thread.join()`, 2 `CompletableFuture.join()`), counted 2026-11-01 with `grep`; the hand-run mains `ProbeNewSession`, `Snapshot` and the three gates hold 24 more and are not tests | Predates the standard | a change to the test |
| G-13.5 | rule | `StateLoop.failure()`: surface for tests only, not marked (`McpTools.pending()` is marked `@TestOnly` since 2026-11-09) | Found 2026-10-31 | a change to the file |
| G-13.8 | rule | `Watchdog`'s `finally` untested; `Transcript`'s growth limit untested (2^31 blocks cannot be built in a test) | The first needs a fault-injecting look | a change to `Watchdog` |
| G-14.1, G-14.4 | rule | peer answers and kit backups log failures only; `context` and `window` metrics without unit | Found 2026-10-31 | a change to the file |
| G-15.14 | rule | `subscribe` states no delivery semantics | Found 2026-10-31 | a change to `Peers` |
| G-16.1, G-16.2 | rule | `default` arms over sealed `Block` (outside `TranscriptCanvas`, cleared 2026-11-08) and `Intent`; casts after type tests | Predates the standard | a change to the file |
| own rule 5 | rule | 12 test files build Swing components off the EDT (`ResourceDialogTest`, `MenuBarTest`, `TableSourceTest`, ...) | Predates rule 5's test clause | a change to the test |

## 6. Quality

`./gradlew build` compiles every module with warnings as errors and runs the unit and adapter tests. The database tests
run against an embedded QuestDB, and against PostgreSQL through Testcontainers when Docker is there; the Claude adapter
runs against fake CLI scripts and recorded streams; the MCP server is tested over HTTP. The opt-in tests, the window
tests, CI and the release workflow are in [§2](#2-gradle-tasks) and [§3](#3-continuous-integration).

Gates:

| Gate | Command | Budget |
|---|---|---|
| transcript frame time | `./gradlew :ui:transcriptGate` | p99 ≤ 16 ms ([Architecture §14.5.3](architecture.md#1453-the-frame-time-gate)) |
| grid and plot frame time | `./gradlew :ui:gridGate`, `:ui:plotGate` | measured, not enforced: p99 printed, `HOT` above 4 ms, `OVER BUDGET` above 16 ms ([Architecture §14.5.3](architecture.md#1453-the-frame-time-gate)) |
| EDT responsiveness | always on: `EdtWatchdog` | a probe answered within 200 ms |
| reducer responsiveness | always on: `StateLoop` and its watchdog look | each intent applied within 200 ms |

comando has no JFR code and imports no `jdk.jfr`. It is profiled from outside like any JVM, with the JDK's own events,
and its EDT stalls are in its log ([Architecture §14.2](architecture.md#142-the-uikit-package)). The packaged runtime
keeps `jdk.jfr`, `jdk.management.jfr` and `jdk.management.agent`, so the installed application can be recorded and
watched over JMX ([record 54](adr/0054-comando-knows-nothing-of-jfr.md)).

The packages are built with `jpackage` and a runtime of the modules listed in `app/build.gradle.kts`, and nothing is
signed ([record 16](adr/0016-unsigned-macos-build.md)); [INSTALL.md](../INSTALL.md) is the install recipe.

## 7. Documentation

The documents are comando's specification as much as its manual, so I keep them to a few rules.

| Rule | How |
|---|---|
| a flat set | every document but the root `README.md` and `INSTALL.md` sits in `docs/`, with one entry point, [docs/README.md](README.md); the only folders are `images/` and `adr/` |
| one canonical place | each concept is described in one document, [§7.2](#72-where-each-concept-lives) says which, and the others link to it in at most a line; the tutorial alone repeats, because it teaches by walking the window and Help ▸ Tutorial shows its links as plain text, so it must stand on its own |
| true to the code | a claim about behaviour is checked against the code when it is written, and a change to the code changes the document in the same commit |
| links resolve | every relative link, image and anchor in `README.md`, `INSTALL.md`, `docs/` and `adr/` lands on a file and a heading that exist; a link never points at `#` alone |
| my voice | first person where I explain a choice; exact and factual where a rule is stated |
| decisions | one record per decision in `adr/`, dated, with who decided; [adr/README.md](adr/README.md) is the index, checked by `AdrIndexTest` |
| the tutorial ships | `docs/tutorial.md` is copied into the `ui` jar for Help ▸ Tutorial (`ui/build.gradle.kts`), so the app and the docs show one text; it is the one documentation change CI builds |

### 7.1 Pictures

Every picture is a hand-drawn SVG in `docs/images/`, referred to as `images/<name>.svg`. There are no screenshots and no
Mermaid diagrams. A picture earns its place when it shows what the text cannot, a structure or a sequence; a drawn
mock-up of the window stands in where words leave a layout unclear. All of them share one look, comando's graphite and
gold:

| Element | Value |
|---|---|
| ground | a radial graphite gradient, `#1B1B1B` to `#101010`, 1600 px wide |
| title | spaced capitals in gold `#E7C55A` (13 px, letter spacing 3), a light headline under them (24 px, weight 300, `#F2F2F2`) |
| text | Helvetica Neue, light (weight 300), `#BDBDBD`; identifiers in Menlo, `#9A9A9A` |
| lines and boxes | hairlines of 1 to 1.2 px; a box is outlined in its owner's colour on a fill one step above the ground (`#1E1E1E`); solid for a call or a write, dashed for what comes back or for a file on disk |
| owners | one colour each: comando `#6FAEE8` blue, Claude Code `#D98E5F` copper, the kit `#6CC69A` jade, the brain and the centre `#E7C55A` gold, instances and what they share `#AEB6C1` silver |
| footer | what the marks mean, and `comando · docs/<document>` at the right |

| Picture | Shows | In |
|---|---|---|
| `comando.svg` | the icon, a copy of `app/src/main/icon/comando.svg` | [README](../README.md) |
| `glance.svg` | where a block goes and what comes back | [README](../README.md) |
| `window.svg` | the window's regions, drawn | [Workbench](workbench.md) |
| `instance-ask.svg` | a question from one instance to another | [Workbench](workbench.md) |
| `session.svg` | a session's process from start to stop | [Claude Code](claude-code.md) |
| `approval.svg` | an approval, from the CLI's call to the answer | [Claude Code](claude-code.md) |
| `kit.svg` | a kit's parts and which part acts when | [Kit and brain](kit-and-brain.md) |
| `brain-overview.svg`, `brain-anatomy.svg`, `brain-flows.svg`, `brain-reflect.svg`, `brain-loop.svg` | a session and its five parts; the brain's parts and who writes them; every flow; `/reflect`; the learning loop | [Kit and brain](kit-and-brain.md) |
| `modules.svg` | the modules and what each depends on | [Architecture](architecture.md) |
| `command-flow.svg` | one command's path through intents, the reducer and the ports | [Architecture](architecture.md) |

### 7.2 Where each concept lives

When a feature lands, its description goes to the one document below that owns the concept, and every other mention
becomes a link. A document that is not the owner keeps only what its reader needs to follow its own text.

| Concept | Owner |
|---|---|
| why comando exists, what it does at a glance | [README](../README.md) |
| installing, upgrading and uninstalling comando; offering a kit | [Install](../INSTALL.md) |
| a first walk through the window | [Tutorial](tutorial.md), which repeats on purpose |
| the window, panels, themes and their file format, the sidebar and its tabs, keys on every desktop | [Workbench](workbench.md) §1, §2 |
| how a block is classified and where it goes, where results land | [Workbench](workbench.md) §2 |
| what the user does with a database: connections, cancel, results, plots, the schema explorer | [Workbench](workbench.md) §3 |
| instances, the comando Edge, what travels between instances and the question protocol | [Workbench](workbench.md) §4 |
| the terminal and Gradle runs (command, output bound, stop) | [Workbench](workbench.md) §5 |
| the Files tab and git changes | [Workbench](workbench.md) §6 |
| what is kept on disk: the data root and each file in it | [Workbench](workbench.md) §7 |
| Find in files: the Search view, its viewer, its keys, what a hit opens | [Workbench](workbench.md) §1.2 |
| the `claude -p` process contract: arguments, MCP configuration, stderr, threads; the stored conversations and their search; what a session works on, read from its writes | [Claude Code](claude-code.md) §1 |
| the checkpoint | [Claude Code](claude-code.md) §2 |
| the MCP tools; the metrics the sessions write and what comando reads back | [Claude Code](claude-code.md) §3 |
| approvals, permission rules, read-only SQL, production connections, secrets | [Claude Code](claude-code.md) §4 |
| a kit's parts and what each does in a session | [Kit and brain](kit-and-brain.md) §2 |
| the root, its launch environment, a kit's upgrade, backups and jobs | [Kit and brain](kit-and-brain.md) §3 |
| the brain, its rules, its flows, `/reflect`, the learning loop | [Kit and brain](kit-and-brain.md) §4 to §7 |
| creating a default kit and brain, checking one the user brings, a kit after a comando upgrade; signing a root in | [Kit and brain](kit-and-brain.md) §8, §3.1 for signing in |
| modules, state and intents, effects, ports, threads, ownership and exit | [Architecture](architecture.md) §2 to §7 |
| each module's classes, interface, own threads and tests; the database mechanism (cancel handshake, storage by column, catalogue choice) | [Architecture](architecture.md) §8 to §15 |
| prerequisites, Gradle tasks, CI, configuration properties, code rules, quality gates, these rules and the pictures | [Development](development.md) |
| why a thing is the way it is | [Decisions](adr/README.md) |
