# Quality

## 1. Test suites

`./gradlew build` compiles every module with `-Xlint:all -Werror` (except `serial`, `processing`, `this-escape`) and runs the unit and adapter tests. Counts from the build of 2026-10-16:

| Module | Classes | Tests | What they cover |
|---|---|---|---|
| `core` | 15 | 185 | the reducer (one test per behaviour; a session's one panel: open, take, detach, reopen; closed panels kept, documents dropped, removal; notice subjects, history and clearing, session layout limits, reopened panels), the state loop and its drain on close, the classifier and block finder, resource kinds and specs, the transcript's publication, console output past its cap, formatting, the JFR intent event |
| `db` | 2 | 24 | lanes against an embedded QuestDB: runs stored by column, nulls, the row cap, DDL, server errors, cancel of a running and of a queued run, connect failures, Claude's capped rows, schema loading, tests, close; against PostgreSQL in Docker through Testcontainers (skipped without Docker): type mapping, cancel of `pg_sleep`, schema from `information_schema`, the describe fallback, Test and Connect agreeing on the TLS mode, a closed or failed connection's lane ending, a schema load overtaken by a close neither reopening the connection nor keeping its tables |
| `claude` | 10 | 68 | the Claude Code kit (the copy kept equal to the build's, the install and its consent list, a setup of the user's own never duplicated, a marketplace pointed back, enabling, Claude Code's failure answers, the bounded process runner; with `COMANDO_KIT_LIVE=true`, an install with the real CLI), the stream mapper on recorded CLI streams, the command line, the adapter against a fake CLI script, the plan usage read (its flags, its parse, a hang stopped, a missing CLI), the session catalogue, git branch reading, version checks |
| `mcp` | 3 | 74 | the server over HTTP: authentication, Origin, protocol versions, tokens per process, every tool, tool scoping to the caller's panels and views, approvals, cancelled calls, the permission rules derived per tool, the plan's `setMode`, the SQL policy |
| `tools` | 2 | 25 | the tool settings (the kit's wrapper by default, none, a path; jfrq from the settings, the variable or the `PATH`; read at each run, with what is wrong said first), Gradle runs and test reports, folders read only while open, sent only when they changed, ordered at every level and bounded, cancel and SIGKILL escalation, output held by an orphan, a console released after its view closed, an unreadable directory, shell-like argument splitting, jfrq, JFR recordings |
| `ui` | 61 | 311 | the kit's question (its changes and folders, Skip for a setup of the user's own, the buttons by position, the folders the Files tab lacks), import rules (ArchUnit), the approval cards (keys, focus, the two-press Always, questions), the transcript's folding, thinking giving way to the next item (in subagent cards too), the gold conclusion (at the turn end or the next prompt, none after a tool call), following and find, the resource dialogs at their default size (one resource, no list), their close button asking like Esc, new and clone forms and their titles, the terminal's input gate and foreground job on a real pty, the editor's tint, inserts, error underline and reload from its file (caret, one undo, no save), a panel taking an outside change only without unsaved edits, the status bar's session item without cost, its plan usage (a failed read keeps the last, colours near a limit), the sidebar's four tabs in two fixed rows, a session row's controls (the plug showing the state, model, effort; a double click on a control binds nothing), where a row is bound (its panels' titles, else headless), the Panels tab (open and closed by title, no documents, double-click reopens, the removal question, clone with text and connection), the buttons above the sessions and the connections (two groups; add, then edit, clone and remove on the selected row; removing one in use asks to stop or disconnect it, or leave it; import on the sessions only), its collapse to a rail, the Files tab (a row per folder, its index opened by hand only, only open folders watched, the filter across folders, edit and remove of a folder row from one group at the left, a read-only folder neither edited nor removed, icons by kind and per folder, one click opens), a tree refresh keeping what was opened and selected, the Markdown preview (documents rendered first, the Preview / Edit button naming the other view), documents coloured as their language with no run targets, the editor theme's colours, coloured code fences, Mermaid diagrams (drawn once, rejected once, no mmdc, sized to the pane), text against binary files, palette ranking (label, then menu path) and key glyphs, the menu bar (submenus, separators, paths, unknown or doubled commands, the icon rule), key forwarding, Markdown to paragraphs and to HTML (images as alt text), the HTML guard against Swing, attachments without blank lines, the transcript canvas, diffs, completion and its disposal, plan trees, result views and live plots, plot time ticks, the dialog's form merge, sorted selection and CSV export, source links, the pseudo-terminal, the external editor, the EDT watchdog (a drawing stall logged when it ends, one past its limit reported like a hang) |
| `app` | 10 | 67 | `tools.json` (template, edits, every malformed form), the kit at start (offered only without the plugin and without a setup of the user's own, on the user's own data root, normalised), the store (atomic files, closed panels kept with their files, blank or not, and their text, a new panel's file written at once, a removed panel's deleted and a document never, loose files of `panels/` adopted as closed panels, a closed panel taking an outside edit, a file deleted outside reported for its panel to be removed unless edited after the report, an outside change not taken over an edit made after it, a document unreadable or unwritable keeping its own path, loose files matched by the same file on the volume, a session bound to several panels keeping its first at the start, a deleted document reported for its panel to close and dropped at the start unless it has unsaved text, a close that drops a pending retry and leaves no write behind, corrupt files kept aside, keychain migration, an unreadable keychain item kept, both keychain answer forms, panel saves not queued behind a keychain prompt), the panel files (names from titles, renames, numbered names, a closed panel's file never taken, edits outside comando reported once, taken or kept aside, files deleted outside, a file that cannot be written kept in `panels.json` across a restart, closing (before the first save too, and a closed panel whose file cannot be named or written coming back, its name kept from new panels), a `panels.json` that cannot be written told and tried again, a reopen reading after the close's write, a panel on a file elsewhere (never renamed or deleted, restored after a restart), one file never bound to two panels, opening a spec, text moved out of an old `panels.json`), the intent log, uncaught exceptions in the log; 2 skipped by default: the real keychain and the window replay |
| total | 103 | 754 | 0 failures |

Three tests touch the user's machine and are opt-in:

| Test | Enabled by | Why opt-in |
|---|---|---|
| keychain round trip | `./gradlew test -PkeychainTest=true` (sets `comando.keychainTest` in the test JVM) | writes and deletes an item in the login keychain |
| window replay | `./gradlew :app:uiTest` | needs a display and opens a window |
| the kit with the real Claude Code | `COMANDO_KIT_LIVE=true ./gradlew :claude:test --tests '*KitLiveTest'` | runs the installed `claude` (in a throwaway `CLAUDE_CONFIG_DIR`) |

## 2. Fixtures

| Fixture | Holds |
|---|---|
| `testkit/.../fixtures/stream/*.jsonl` | stream-JSON recorded from Claude Code 2.1.296: approvals and a question; a queued prompt, `/compact` and an interrupt |
| `fake-claude.sh`, `fake-claude-crash.sh` | shell stand-ins for `claude -p` that answer each prompt, or die with stderr |
| `SyntheticSession` | a deterministic session of 8 turns and 41 tool calls (one a subagent), replayed as stream deltas |
| `RecordingPorts`, `RecordingSink`, `FakeClock`, `FakeResult`, `States` | port fakes that record effects, an intent sink with `await`, a settable clock, results and states built in one line |
| `app/src/test/resources/replay/snapshot.jsonl` | a recorded intent log of a snapshot run, with no real connection data |

## 3. Replay

With `-Dcomando.recordIntents=true` comando writes every intent it applies to `logs/intents-<epoch ms>.jsonl` in the data root. The first line holds the initial state; each further line one intent, with its type. Every `password` field is blanked before it is written, so a log can be attached to a defect report.

`IntentLog.read` returns a log's starting state and its intents. `UiReplayTest` feeds the fixture to a `StateLoop` driving the real Swing shell, with every adapter replaced by a recording fake, and checks that the window shows what the final state says: the window title, the panel tabs and the focused one, the lower tabs and the selected one, the side pane's views, and that the editor and a non-empty side pane are each wider than a fifth of the window.

## 4. Performance gates

| Gate | Command | Budget | Latest |
|---|---|---|---|
| transcript frame time | `./gradlew :ui:transcriptGate` | p99 ≤ 16 ms | 2026-10-13: frame p50 1.15 ms, p99 3.39 ms, max 9.43 ms, 0 frames over 16 ms; open thinking streamed p99 1.40 ms ([Transcript renderer](transcript-renderer.md#5-the-frame-time-gate)) |
| EDT responsiveness | always on: `EdtWatchdog` | probe answered within 200 ms | when exceeded, one INFO line as the stall ends (its length, kind and where) and a `comando.EdtBlocked` event; a stack in the log only for a hang, 1 s (2 s in the JDK's drawing) |

## 5. JFR events

comando records its own events in any JFR recording of the process (`-XX:StartFlightRecording`):

| Event | Fields | Emitted by |
|---|---|---|
| `comando.Intent` | intent type, effect count, whether the state changed; duration = reducer time | the reducer thread |
| `comando.SqlRun` | connection, request id, SQL, outcome; duration = dequeue to last row | the connection's lane |
| `comando.ClaudeTurn` | session, turn time, cost, context tokens, error | the session strand at each turn end |
| `comando.McpCall` | session, tool, error; duration = the call | the MCP server |
| `comando.EdtBlocked` | blocked time, kind (`startup`, `drawing`, `comando`, `jdk`), where, the EDT's stack | the EDT watchdog |

`jfrq` reads them like any other event.

## 6. Continuous integration

`.github/workflows/build.yml` runs on pushes to `main` and on pull requests, on Ubuntu and macOS with Temurin 25; a newer push to a pull request cancels its run in progress. Actions are pinned by commit SHA.

1. `./gradlew build`: every test, PostgreSQL through Testcontainers where the runner has Docker (Ubuntu; macOS runners have none, so that test is skipped there).
2. `./gradlew :app:uiTest`: the window replay, under `xvfb-run` on Ubuntu, in the runner's own window session on macOS.
3. `java ci/TestReport.java`: tests, skips, failures and errors per module and task, with every skipped test named, in the job summary.
4. `./gradlew :ui:transcriptGate`: reported, not enforced, because runner speed varies.
5. `./gradlew :app:dmg`, on macOS: the disk image, as a smoke test of the packaging.
6. The test reports and results are uploaded per runner.

`assign.yml` assigns every pull request to the owner; `dependabot.yml` opens one grouped pull request a week for the pinned actions and one for the Gradle dependencies. There is no CodeQL workflow: code scanning on a private repository needs GitHub Advanced Security.

## 7. The macOS build

`./gradlew :app:dmg` builds `app/build/dmg/comando-<version>.dmg` with `jpackage`: the application jars and a runtime trimmed to the modules `jdeps` reports plus locale data, charsets and `zipfs`. The image is about 83 MB and unsigned ([decision 16](../adr/0016-unsigned-macos-build.md)).
