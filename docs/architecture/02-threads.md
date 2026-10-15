# Threads

## 1. Roles

| Thread | Kind | Does | Owns |
|---|---|---|---|
| EDT | AWT | renders the state, turns input into intents | Swing components, editor documents |
| `comando-reducer` | platform | applies intents in order, hands effects to ports | `AppState` (the only writer) |
| `db-<connection>` | platform, one per connection | opens the JDBC connection, runs statements one at a time, fetches rows | the connection, the running statement, its `ColumnarResult` |
| `db-timer` | platform | health checks every 60 s, the abort that follows an unanswered cancel | — |
| testers, cancellers | virtual | connection tests; `Statement.cancel()` (pgjdbc waits for the server to close the cancel socket) | — |
| `claude-strand-<session>` | virtual, single-threaded executor | every change to a session: start, send, interrupt, stop, history load | the session's `Transcript` and stream mapper |
| `claude-out-`, `claude-in-`, `claude-err-`, `claude-exit-<session>` | platform, per process | read stdout lines, write the stdin queue, keep the last 40 stderr lines, wait for the exit | the process streams |
| `claude-timer` | platform | the stop sequence's deadlines | — |
| `plan-usage` | platform, daemon | runs `claude --safe-mode -p /usage` at start and every 5 minutes for the status bar; a read is stopped after 30 s | — |
| MCP server | virtual per request | MCP JSON-RPC over HTTP; an `approve` call waits for its card | pending approvals |
| `gradle-<run>`, `jfrq-<run>` | virtual | run the child process, copy its output to the console, collect test reports | the run's `Console` |
| `tool-output-<run>`, `tool-kill-<run>` | virtual | copy a run's output while it is open; SIGKILL what a stop left alive after 2 s | — |
| `recording`, `scratchpad` | virtual | read a JFR file, then send a view; read the scratchpad folder at once (refresh button, View ▸ Scratchpad), then send its tree when it changed | — |
| `scratchpad-watch` | platform, daemon | reads the whole scratchpad every 2 s and sends its tree when it changed; one read at a time with the virtual reads | — |
| `open-file` | virtual | checks whether a file chosen for a panel is text, before the store binds it | — |
| `store` | platform | atomic writes of `sessions.json`, `panels.json` and the panel files in `panels/`; panel text 800 ms after the first unsaved change; a check of the open panels' files for outside changes every second; the read of a file that Open spec or ⌘⇧T binds to a panel | those files |
| `store-secrets` | platform | keychain calls and `connections.json`, apart so an unanswered unlock prompt never holds back panel text | `connections.json` |
| `mermaid` | platform, daemon | runs `mmdc` for a Mermaid diagram a preview shows, one at a time; stopped after 30 s and at exit | `cache/mermaid/` |
| `edt-watchdog` | platform, daemon | posts a probe to the EDT every 100 ms and reports one that waits 200 ms | — |
| `show-file`, `show-log`, `reveal`, `notify`, `open-link`, `external-editor`, `terminal-exit`, `claude-version`, `questdb-toggle`, `pty-preload`, `export` | virtual | one blocking call each, off the EDT | — |
| `exit`, `shutdown` | platform | close the application after Quit, or from the JVM's shutdown hook ([Ownership](04-ownership.md#2-exit)) | — |
| `keychain-output` | virtual | reads the `security` tool's output, so the call's 10 s bound holds even while an unlock prompt is open | — |

JediTerm and pty4j start their own threads for each terminal; comando closes the terminal, and with it those threads.

## 2. How work crosses threads

- **Into the reducer:** `IntentSink.dispatch` puts the intent on an unbounded `LinkedBlockingQueue`; any thread may call it. Intents from one thread keep their order.
- **Out of the reducer:** ports queue work on their own executors (a lane's single thread, a session's strand, a new virtual thread) and return. No port method blocks.
- **To the EDT:** the UI registers one state listener that requests a render through a `Coalescer`: many state changes become at most one pending `invokeLater`. A store that grew (a transcript, a console) asks for a repaint the same way.
- **From the EDT:** user input becomes an intent; work that would block (reading a file, the IntelliJ launcher, writing an export) moves to a virtual thread. A terminal's shell is spawned on the EDT, which takes a few milliseconds; its output is read by pty4j's threads.

## 3. Bounded waits

Every wait has a deadline. A wait that can be cut short by shutdown also checks a halt flag.

| Wait | Bound | On expiry |
|---|---|---|
| An approval card | 55 min, under Claude Code's 60 min MCP timeout set per server | the tool answers `deny` and the card is withdrawn |
| `db_run_sql` for Claude | 90 s | a statement still queued is withdrawn and never runs; the tool says so |
| `db_describe` | 60 s | tool error |
| `session_read` with `wait_seconds` | the given seconds, at most 600; polled every 200 ms | the blocks so far, the status saying the session is still working |
| Statement cancel | caller returns at once; the cancel request itself is bounded by `cancelSignalTimeout=2` | after 2 s the statement's connection is aborted |
| Claude process stop | interrupt and close stdin; SIGTERM after 3 s; SIGKILL 2 s later | the exit thread reports the exit |
| Tool run stop | SIGTERM to the process tree; SIGKILL 2 s later | the run ends as stopped |
| Tool output after the process exits | 2 s | a note in the console; a child holding the pipe no longer keeps the run open |
| `claude --version` | 5 s | a warning in the status bar |
| Keychain calls | 10 s | the tool is killed; a password not stored stays in the file, a password not read is kept in the keychain and the connection loads without it |
| The EDT | probe answered within 200 ms | a stack in the log and a `comando.EdtBlocked` JFR event; a stall inside the JDK's drawing logs one INFO line, with a stack only past 2 s |

## 4. Publication

`StateLoop.state` is `volatile`: the reducer writes it once per intent and any thread reads a complete state. The stores outside the state publish through a `volatile` size or a lock ([State and intents](01-state-and-intents.md#5-stores-outside-the-state)). Pending approvals are `CompletableFuture`s in a concurrent map; the answer completes the future the MCP request thread waits on.
