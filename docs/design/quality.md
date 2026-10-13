# Quality

## 1. Test suites

`./gradlew build` compiles every module with `-Xlint:all -Werror` (except `serial`, `processing`, `this-escape`) and runs the unit and adapter tests. Counts from the build of 2026-10-12:

| Module | Classes | Tests | What they cover |
|---|---|---|---|
| `core` | 9 | 102 | the reducer (one test per behaviour), the state loop and its drain on close, the classifier and block finder, resource kinds and specs, the transcript's publication, console output past its cap, formatting, the JFR intent event |
| `db` | 2 | 17 | lanes against an embedded QuestDB: runs stored by column, nulls, the row cap, DDL, server errors, cancel of a running and of a queued run, connect failures, Claude's capped rows, schema loading, tests, close; against PostgreSQL in Docker through Testcontainers (skipped without Docker): type mapping, cancel of `pg_sleep`, schema from `information_schema`, the describe fallback |
| `claude` | 6 | 22 | the stream mapper on recorded CLI streams, the command line, the adapter against a fake CLI script, the session catalogue, git branch reading, version checks |
| `mcp` | 2 | 51 | the server over HTTP: authentication, Origin, protocol versions, tokens per process, every tool, tool scoping to the caller's panels and views, approvals, cancelled calls, host-scoped WebFetch rules, the SQL policy |
| `tools` | 1 | 11 | Gradle runs and test reports, cancel and SIGKILL escalation, output held by an orphan, a console released after its view closed, an unreadable directory, shell-like argument splitting, jfrq, JFR recordings, the scratchpad tree |
| `ui` | 17 | 50 | import rules (ArchUnit), Markdown to paragraphs and to HTML (images as alt text), the HTML guard against Swing, attachments without blank lines, the transcript canvas, diffs, completion and its disposal, plan trees, result views and live plots, plot time ticks, the dialog's form merge, sorted selection and CSV export, source links, the pseudo-terminal, the external editor, the EDT watchdog |
| `app` | 3 | 14 | the store (atomic files, corrupt files kept aside, keychain migration, an unreadable keychain item kept, both keychain answer forms, panel saves not queued behind a keychain prompt), the intent log; 2 skipped by default: the real keychain and the window replay |
| total | 40 | 267 | 0 failures |

Two tests touch the user's machine and are opt-in:

| Test | Enabled by | Why opt-in |
|---|---|---|
| keychain round trip | `-Dcomando.keychainTest=true` | writes and deletes an item in the login keychain |
| window replay | `./gradlew :app:uiTest` | needs a display and opens a window |

## 2. Fixtures

| Fixture | Holds |
|---|---|
| `testkit/.../fixtures/stream/*.jsonl` | stream-JSON recorded from Claude Code 2.1.296: approvals and a question; a queued prompt, `/compact` and an interrupt |
| `fake-claude.sh`, `fake-claude-crash.sh` | shell stand-ins for `claude -p` that answer each prompt, or die with stderr |
| `SyntheticSession` | a deterministic session of 8 turns and 40 tool calls, replayed as stream deltas |
| `RecordingPorts`, `RecordingSink`, `FakeClock`, `FakeResult`, `States` | port fakes that record effects, an intent sink with `await`, a settable clock, results and states built in one line |
| `app/src/test/resources/replay/snapshot.jsonl` | a recorded intent log of a snapshot run, with no real connection data |

## 3. Replay

With `-Dcomando.recordIntents=true` comando writes every intent it applies to `logs/intents-<epoch ms>.jsonl` in the data root. The first line holds the initial state; each further line one intent, with its type. Every `password` field is blanked before it is written, so a log can be attached to a defect report.

`IntentLog.Replay` reads a log back and applies it to the reducer. `UiReplayTest` replays the fixture against the real Swing shell, with every adapter replaced by a recording fake, and checks that the window shows what the final state says: the window title, the panel tabs and the focused one, the lower tabs and the selected one, the side pane's views, and that the editor and a non-empty side pane are each wider than a fifth of the window.

## 4. Performance gates

| Gate | Command | Budget | Latest |
|---|---|---|---|
| transcript frame time | `./gradlew :ui:transcriptGate` | p99 ≤ 16 ms | p99 3.26 ms ([Transcript renderer](transcript-renderer.md#5-the-frame-time-gate)) |
| EDT responsiveness | always on: `EdtWatchdog` | probe answered within 200 ms | a stack in the log and a `comando.EdtBlocked` event when exceeded |

## 5. JFR events

comando records its own events in any JFR recording of the process (`-XX:StartFlightRecording`):

| Event | Fields | Emitted by |
|---|---|---|
| `comando.Intent` | intent type, effect count, whether the state changed; duration = reducer time | the reducer thread |
| `comando.SqlRun` | connection, request id, SQL, outcome; duration = dequeue to last row | the connection's lane |
| `comando.ClaudeTurn` | session, turn time, cost, context tokens, error | the session strand at each turn end |
| `comando.McpCall` | session, tool, error; duration = the call | the MCP server |
| `comando.EdtBlocked` | blocked time, the EDT's stack | the EDT watchdog |

`jfrq` reads them like any other event.

## 6. Continuous integration

`.github/workflows/gradle.yml` runs on pushes and pull requests to `main`, on Ubuntu with Temurin 25:

1. `./gradlew build`: every test, PostgreSQL through Testcontainers on the runner's Docker.
2. `./gradlew :ui:transcriptGate || true`: reported, not enforced, because runner speed varies.
3. `xvfb-run --auto-servernum ./gradlew :app:uiTest`: the window replay on a virtual display.
4. On failure, the test reports are uploaded as an artifact.

## 7. The macOS build

`./gradlew :app:dmg` builds `app/build/dmg/comando-<version>.dmg` with `jpackage`: the application jars and a runtime trimmed to the modules `jdeps` reports plus locale data, charsets and `zipfs`. The image is about 83 MB and unsigned ([decision 16](../adr/0016-unsigned-macos-build.md)).
