# Architecture

How comando is built: the modules, the one state and its reducer, the threads, who owns what, the UI's package rules, the
code rules and the tests.

1. [What comando is](#1-what-comando-is)
2. [Modules](#2-modules)
3. [The flow of one command](#3-the-flow-of-one-command)
4. [State, intents, effects](#4-state-intents-effects)
5. [Ports and test fakes](#5-ports-and-test-fakes)
6. [Threads](#6-threads)
7. [Ownership and exit](#7-ownership-and-exit)
8. [UI packages](#8-ui-packages)
9. [Code rules](#9-code-rules)
10. [Quality](#10-quality)

## 1. What comando is

A Java Swing desktop application for working with Claude Code and SQL databases in one window. It runs Claude Code
headless (`claude -p`, stream-JSON on standard input and output) and shows each session as a painted transcript with
tool cards, diffs and approval cards. One editor holds SQL and prompts: a block runs on the panel's database connection
when it is SQL and goes to the panel's Claude session otherwise. Claude Code reaches back into the window through an
MCP server comando runs on `127.0.0.1`: tables, plots, diffs and trees, text in the editor, SQL under a policy, and
permission prompts answered on cards. The rest of the window replaces the terminal: a shell per panel, Gradle runs with
test trees, and a browser over folders of documents.

## 2. Modules

| Module | Holds | Depends on |
|---|---|---|
| `core` | state, intents, reducer, effects, ports, the stores outside the state, the SQL-or-prompt classifier | JDK only |
| `testkit` | fakes for every effect port, a fake clock, recorded Claude streams, a synthetic session, a recording intent sink | `core` |
| `db` | JDBC lanes (one thread per connection), cancel, columnar results, schema reading, queries for Claude | `core`, pgjdbc |
| `claude` | the `claude -p` process per session, the stream-JSON mapper, stored conversations, the Claude Code kit (`Kit`) | `core`, Jackson |
| `mcp` | the MCP server on loopback, its tools, the SQL policy, the audit log | `core`, Jackson |
| `tools` | Gradle runs, the Files tab's folder reads and moves to the Trash, the folders' git repositories (the `git` command line) | `core` |
| `ui` | the Swing shell, panels, dialogs, the kit, the widgets | `core`, FlatLaf (and Extras), jsvg, RSyntaxTextArea and AutoComplete, CommonMark (with tables), java-diff-utils, Jackson, JediTerm, pty4j |
| `app` | wiring, the store files, the keychain, the intent log, the embedded QuestDB, `main` | all, QuestDB |

`ui` depends on `core` only; `app` is the one module that sees every other.

Each module has a README on its classes, its threads and its interface with the others:
[`core`](../core/README.md), [`testkit`](../testkit/README.md), [`db`](../db/README.md),
[`claude`](../claude/README.md), [`mcp`](../mcp/README.md), [`tools`](../tools/README.md), [`ui`](../ui/README.md),
[`app`](../app/README.md).

## 3. The flow of one command

Every change to what the window shows is an intent, applied by one reducer on one thread. Keys, database rows, Claude
Code events and MCP calls enter the same queue. The reducer returns the next state and a list of effects; adapters do
the effects on their own threads and report back with more intents. The UI renders the state.

```mermaid
flowchart LR
  subgraph UI["ui (EDT)"]
    ED["editor, sidebar, cards"]
    VW["results, transcript, views"]
  end
  subgraph CORE["core (reducer thread)"]
    Q["intent queue"] --> R["reducer"] --> S[("AppState")]
  end
  subgraph ADP["adapters (own threads)"]
    DBA["db lanes"]
    CL["claude processes"]
    MC["MCP server"]
    TL["tools runs"]
  end
  ED -->|intents| Q
  S -->|render| VW
  R -->|effects| DBA
  R -->|effects| CL
  R -->|effects| TL
  R -->|"effects (ResolveApproval)"| MC
  R -->|"effects (EditPanelText, Arrange, …)"| ED
  DBA -->|"SqlRows, SqlCompleted"| Q
  CL -->|"SessionInit, SessionTurnEnded"| Q
  CC["Claude Code"] -->|"MCP calls"| MC
  CL --- CC
  MC -->|"ShowView, ApprovalRequested"| Q
  TL -->|"CommandFinished, FolderLoaded, ReposLoaded"| Q
```

Principles:

1. **Ports and adapters.** `core` names every outside system as a port; adapters implement them; tests replace each
   effect port with a fake from `testkit`; `McpGateway.NONE` and a test's own `QueryPort` stand in for the other two.
2. **One state, one direction.** `AppState` is immutable; only the reducer replaces it, one intent at a time.
3. **Request identity.** Every SQL run and command run has an id the reducer assigns; every approval has an id the MCP
   server assigns. A late event for a superseded id is dropped.
4. **Nothing blocks the EDT or the reducer.** Port methods queue work and return; every wait has a deadline.
5. **Ownership is a tree.** `App` creates every part and closes them in reverse order, each once.
6. **Replay.** Every intent can be logged, after the state the log started from; a log replayed against the real Swing shell
   with fake adapters is a UI test.

## 4. State, intents, effects

### 4.1 `AppState`

One immutable record of metadata and ids. Lists are tens of entries, so lookups scan.

| Field | Holds |
|---|---|
| `panels` | each open editor panel: id, title, connection and session bindings, whether it is a document (a file from outside comando's folder) |
| `closed` | comando's own panels the user closed, kept with their bindings until removed |
| `focused` | the panel the user works in; the lower panel follows it |
| `connections` | each connection's spec, status (closed, opening, open, closing, failed) and last error |
| `sessions` | each session's spec, status, `system/init` facts, pending turns, cost, context size and window, activity, branch, process and turn start, last turn error, background tasks, whether a checkpoint is due |
| `folders` | the Files tab's folders and repositories in order: name, path, index file, names left out, icon, whether entries go to the Trash, the listing file, whether it is a repository (shown under Repositories) |
| `runs` | at most one SQL run per panel: request id, SQL, status, counts, timings, error, whether it is a live plot's background refresh |
| `approvals` | permission prompts waiting for an answer, from every session |
| `views` | views placed by Claude or the user: id, title, spec, placement (lower or side), pinned, owning session, version |
| `lowerTab` | the selected lower tab: Session, Approvals, Terminal, Results, or a view |
| `notice`, `notices` | the standing status-bar message (level, text, time, subject) and the last 50 |
| `nextId` | the next request, view or run id |

Invariants: at least one panel; `focused` names a panel (both checked by the record's constructor); connection and
session names are unique; one run per panel; a session is bound to at most one panel, open or closed.

### 4.2 Intents

A record of the sealed interface `Intent`; anything that changes the state is one.

| Group | Intents |
|---|---|
| Panels | `AddPanel`, `ClosePanel`, `ReopenPanel`, `RemovePanel`, `RenamePanel`, `MovePanel`, `FocusPanel`, `BindConnection`, `BindSession`, `OpenSession` |
| Running text | `RunText` (target auto, SQL or Claude), `RefreshSql` (a live plot's re-run), `CancelRun` |
| SQL events | `SqlStarted`, `SqlRows`, `SqlCompleted`, `SqlFailed`, `SqlCancelled`, `SqlWarning` |
| Connections | `SaveConnection`, `RemoveConnection`, `OpenConnection`, `CloseConnection`, `TestConnection`, `ExploreSchema`, `SchemaChanged`, `ConnectionOpened`, `ConnectionClosed`, `ConnectionFailed`, `ConnectionLost`, `ConnectionTested` |
| Sessions | `SaveSession`, `RemoveSession`, `ImportSessions`, `StartSession`, `StartFresh`, `StopSession`, `InterruptSession`, `Checkpoint`, `SessionCommand`, `OrderSessions`, `ClearTranscript`, `SetSessionModel`, `SetSessionEffort`, `SessionStarted`, `SessionInit`, `SessionActivity`, `SessionTurnRunning`, `SessionTurnEnded`, `SessionTasksChanged`, `SessionExited`, `SessionBranch`, `PlanUsageRead` |
| Approvals | `ApprovalRequested`, `ApprovalAnswered`, `ApprovalWithdrawn` |
| Views | `ShowView`, `RefreshView`, `CloseView`, `PinView`, `SelectLowerTab`, `Arrange`, `SessionLayout`, `SessionFocus` |
| Editor and files | `EditPanel`, `OpenFile`, `OpenSubagent` |
| Tools and folders | `RunGradle`, `CommandFinished`, `CancelCommand`, `AddFolder`, `SaveFolders`, `FoldersRead`, `BrowseFolders`, `WatchFolders`, `FolderLoaded`, `DeleteFiles` (the Files tab's move to the Trash), `ReposLoaded`, `OpenChange`, `ChangeRead`, `RollBack`, `RemoveFiles`, `Unstage` (the git writes of the Changes view and the comparison window) |
| Notices | `Notify`, `DismissNotice` |

A null panel in `RunText`, `RunGradle` or `EditPanel` means the focused panel.

### 4.3 The reducer

`Reducer.apply(state, intent)` returns a `Transition`: the next state and a list of effects. It is pure in its
arguments, a `Clock` and the home folder, so tests drive it with a fake clock. Nothing a user or an adapter sends makes
it throw: an intent naming a missing panel, connection or session changes nothing or sets a notice.

- **Ids.** Request, run and view ids come from `nextId`; an event for a run no longer active is dropped.
- **Start on demand.** SQL on a closed connection opens it first; a prompt to a stopped session starts it first.
- **Same state for no change.** Focusing the focused panel returns the same object, so nothing re-renders.
- **User wins.** `ShowView` replaces an unpinned view with the same title, session and placement; a pinned view is
  never replaced.
- **Notices.** Every notice joins the history. A new notice replaces the standing one, except that a standing ERROR
  stays until dismissed, replaced by another error, or settled by its own subject. An event that settles a subject
  clears its notice (a run completing, a connection opening, a turn succeeding, an approval answered). A new ERROR also
  asks for a desktop notification while the window is behind. The subject is also where a click on the notice goes,
  where the window has a target: `log:<millis>` opens the log at that entry, found by its time, and `panel:<id>` focuses
  the panel ([record 79](adr/0079-a-notice-takes-you-to-what-it-is-about.md)); a notice of another subject opens the recent
  notices;
  `Intent.Notify.logged` writes a failure to the log and names that entry.
- **Repositories.** After any intent, folders that changed or a Changes view that appeared or went add `WatchRepos`
  with every folder and whether the view shows; `App` sends the first at start.
- **The folders window.** `SaveFolders` replaces the folders and saves them; its notice says when it replaced a change
  to `folders.json` read while the window was open; a list naming one folder twice saves nothing.
- **The user's file.** `FoldersRead`, `folders.json` read again after the user changed it, replaces the folders and
  saves nothing; `AddFolder`, the kit's, saves.

### 4.4 Effects

Work the reducer asks of an adapter. `EffectRouter` hands each to its port on the reducer thread; every port method
queues and returns.

| Port | Effects |
|---|---|
| `DatabasePort` | `OpenDb`, `CloseDb`, `ForgetSchema`, `TestDb`, `ExecuteSql`, `CancelSql`, `ReleaseResult`, `LoadSchema` |
| `ClaudePort` | `StartClaude`, `StopClaude`, `SendToClaude`, `InterruptClaude`, `LoadHistory`, `ClearTranscript`, `ForgetSession`, `ShowSubagent` |
| `ApprovalPort` | `ResolveApproval` |
| `UiPort` | `EditPanelText`, `DesktopNotify`, `ForgetPanel`, `DeletePanel`, `ShowFile`, `Arrange`, `ShowFolder`, `ShowRepos`, `ShowChange`, `ShowPlanUsage` |
| `PersistencePort` | `SaveConnections`, `SaveSessions`, `SaveFolders`, `SavePanels` |
| `ToolPort` | `RunGradle`, `CancelCommand`, `ForgetCommand`, `ReadFolders`, `WatchFolders`, `DeleteFiles`, `WatchRepos`, `ReadChange`, `RollBack`, `RemoveFiles`, `Unstage` |

`StateLoop` runs one intent at a time: observers (the intent log), the reducer, the effects in order, then the state
listeners (the UI's coalesced render). The reducer, each effect and each listener are guarded, so a failure is logged and
the loop goes on; an observer must not throw (the intent log catches its own failures).

### 4.5 Stores outside the state

Large data would make every state copy expensive, so it lives in stores the state refers to by id. Each has one writer
thread.

| Store | Keyed by | Written by | Read by |
|---|---|---|---|
| `ResultRegistry` → `ColumnarResult` | SQL request id | the connection's lane | Results tab, plots, attachments |
| `TranscriptRegistry` → `Transcript` | session name | the session's strand | Session tab |
| `ConsoleRegistry` → `Console` | command run id | the run's thread | console views |
| `SchemaRegistry` → `Schema` | connection name | the database adapter | completion, schema explorer |
| `PanelTexts` | panel id | the EDT | the store thread, on save |

`ColumnarResult` and `Transcript` publish their size through a `volatile` field written after the data, so a reader
that reads the size first sees complete rows or blocks. `Console` guards its buffer with its own lock.

### 4.6 The intent log

With `-Dcomando.recordIntents=true`, `IntentLog` writes one JSON line per intent to `logs/intents-<millis>.jsonl`; the
first line is the starting state; every `password` field is blanked. `UiReplayTest` feeds a log to a real `StateLoop`
and the real Swing shell, with recording fakes for every other port, and checks the window against the final state.
The stores outside the state are not recorded, so a replay reproduces layout, panels, views and run states, not rows or
transcript text.

## 5. Ports and test fakes

| Port | Methods | Adapter |
|---|---|---|
| `IntentSink` | `dispatch` | `StateLoop` |
| `Clock` | `millis` | `Clock.SYSTEM` |
| `DatabasePort` | `open`, `close`, `forgetSchema`, `test`, `execute`, `cancel`, `release`, `loadSchema` | `DbAdapter` |
| `QueryPort` | `query`, `describe` (futures of text rows, for Claude) | `DbAdapter` |
| `ClaudePort` | `start`, `stop`, `send`, `interrupt`, `clearTranscript`, `loadHistory`, `forget`, `showSubagent` | `ClaudeAdapter` |
| `McpGateway` | `register(session)` gives the URL and bearer token a session's CLI uses; `unregister` | `McpServer` |
| `ApprovalPort` | `resolve` | `McpServer` |
| `ToolPort` | `runGradle`, `cancelRun`, `forgetRun`, `readFolders`, `watchFolders`, `deleteFiles`, `watchRepos`, `readChange`, `rollBack`, `removeFiles`, `unstage` | `ToolsAdapter` |
| `UiPort` | `editPanel`, `notifyDesktop`, `forgetPanel`, `deletePanel`, `showFile`, `arrange`, `showFolder`, `showRepos`, `showChange`, `showPlanUsage` | `ComandoUi` |
| `PersistencePort` | `saveConnections`, `saveSessions`, `saveFolders`, `savePanels` | `Store` |

`testkit` holds what every module's tests share: `RecordingPorts` (every effect port, each call recorded as a line),
`RecordingSink` (keeps intents, awaits one with a deadline), `FakeClock`, `States` (a two-panel state), `Fixtures`
(sanitized stream-JSON recorded from Claude Code), `SyntheticSession` (a deterministic eight-turn session replayed delta
by delta), `FakeResult`.

## 6. Threads

comando's threads are of six kinds ([record 82](adr/0082-threads-by-kind-with-a-budget.md)). Only the first three use
operating-system threads, and their number is fixed: **two, plus one per running Claude session**, whatever the number
of connections, folders, panels, diagrams or runs. `ThreadBudgetTest` (in `:app:uiTest`) holds the budget with five
sessions and checks nothing is left after the close; `ThreadRulesTest` fails the build when code outside the kinds below
starts a thread or an executor.

| # | Kind | Is | Threads |
|---|---|---|---|
| K1 | Event loop | owns state or the toolkit; never blocks | the EDT (AWT's); `comando-reducer` (platform) |
| K2 | Watchdog | must run while what it watches is held | one `watchdog` (platform) for the EDT and the reducer, from `Watchdog.shared()`; started with the first look, ended after the last |
| K3 | Session reader | reads a pipe live: a pipe read holds its operating-system thread even on a virtual thread (measured on JDK 25: 100 blocked readers, 101 carriers) | `claude-out-<session>` (platform), one per running session |
| K4 | Strand | sequential work on one resource; its waits hold no operating-system thread | one virtual thread each, from `Threads.strand` or, with timers, `Threads.timedStrand` (the JDK's one delay thread keeps the timers) |
| K5 | Errand | one blocking call started by a gesture or an event, then gone | a virtual thread from its owner's `Errands` (`App`, `ToolsAdapter`, `EmbeddedQuestDb`, the window's `UiErrands`), interrupted and waited for at the owner's close |
| K6 | Fork | a thread its caller collects in the same call, with a bound | a virtual thread from `Threads.fork` |

The rest of a running process is not comando's: the JVM's (GC, compiler, VM; about 30), AWT's and Java2D's (6), the
virtual threads' scheduler (its carriers: one per core while busy, plus one for each virtual thread held in file or
pipe I/O, which is why a session's reader is a platform thread), the JDK's `process reaper` (one per child
process), the MCP server's dispatcher and the libraries' (JNA, a `java.util.Timer`). The GC threads were measured too:
halving them to two lengthened the G1 pause p99 from 6 to 30–48 ms on `:ui:transcriptGate`, so the JVM keeps its
default.

| Thread | Kind | Does |
|---|---|---|
| EDT | K1 | renders the state, turns input into intents; owns every Swing component |
| `comando-reducer` | K1 | applies intents in order; the only writer of `AppState` |
| `watchdog` | K2 | every 100 ms: a probe to the EDT, a look at the reducer; an EDT held 200 ms or an intent applying 1 s is a stall, logged |
| `claude-out-<session>` | K3 | stdout lines of the session's process; stderr goes to a file, read once at the exit |
| `claude-strand-<session>` | K4 | every change to a session: start, send, interrupt, stop, history; the transcript's one writer |
| `claude-timer` | K4, timed | the stop sequence's deadlines |
| `subagent-tail` | K4, timed | finds and renders a sub-agent's stored conversation; follows up to 4 of them every 2 s while they run, reading only what their files gained |
| `plan-usage` | K4, timed | `claude --safe-mode -p /usage` at start and every 5 minutes, stopped after 30 s |
| `topic-reader` | K4 | the tabs' topics with Haiku, one `claude --safe-mode -p --model haiku` at a time; at most one ask waiting per tab, the card shown first |
| `db-<connection>` | K4 | opens the connection, runs statements one at a time, fetches rows |
| `db-timer` | K4, timed | health checks every 60 s; the abort after an unanswered cancel |
| `folder-poll` | K4, timed | every 5 s reads the watched git repositories, then the open folders; sends each only when it changed |
| `change-read`, `repo-write` | K4 | a changed file's two versions, one at a time; rolling back, removing and unstaging files with git and the Trash, one at a time |
| `store` | K4, timed | atomic writes of the store files; panel text 800 ms after the first unsaved change; a check of open panels' files and `folders.json` every second |
| `store-secrets` | K4 | keychain calls apart, so an unlock prompt never holds back panel text |
| `mermaid`, `local-images` | K4 | `mmdc`, one diagram at a time, 30 s bound; the pictures a Markdown view names, SVGs drawn into `cache/images/` |
| `claude-in-`, `claude-exit-<session>` | K6 | the stdin queue; the wait for the process's exit, then the stderr tail |
| `git-out`, `git-err`, `tool-output-<run>`, `keychain-output`, `claude-output`, `claude-version` | K6 | one process's output while its caller waits for it, bounded |
| `gradle-<run>`, `tool-kill-<run>`, `folder-read`, `repo-read`, `file-trash` | K5 (`ToolsAdapter`) | a run's process; SIGKILL what a stop left alive after 2 s; folder and repository reads; moving files to the Trash |
| `claude-version`, `kit`, `questdb-toggle` | K5 (`App`, `EmbeddedQuestDb`) | the start-up checks; one start or stop of the embedded QuestDB |
| `open-file`, `show-file`, `log-entry`, `open-link`, `external-editor`, `notify`, `export`, `plot-prepare`, `diff-compute`, `folder-facts`, `read-conversations`, `opened-files`, the tutorial's and the kit's | K5 (`UiErrands`) | one blocking call each off the EDT |
| MCP requests; a connection test, a cancel | owned per-task executors | JSON-RPC (an `approve` call waits for its card); `DbAdapter`'s testers and cancellers |
| `exit`, `shutdown` | platform, at quit only | close the application after Quit, or from the shutdown hook |

How work crosses threads:

- **Into the reducer.** `IntentSink.dispatch` puts the intent on an unbounded queue; intents from one thread keep their
  order.
- **Out of the reducer.** Ports queue work on their strands and return.
- **To the EDT.** One state listener requests a render through a `Coalescer`: many state changes become at most one
  pending `invokeLater`. Growing stores (transcripts, consoles) ask for repaints the same way.
- **From the EDT.** Input becomes an intent; blocking work moves to an errand.

Bounded waits:

| Wait | Bound | On expiry |
|---|---|---|
| An approval card | 55 min (the MCP configuration comando writes gives its server a 60 min timeout) | `deny`, card withdrawn |
| `db_run_sql` for Claude | 90 s | a queued statement is withdrawn; the tool says so |
| `db_describe` | 60 s | tool error |
| `session_read` with `wait_seconds` | at most 600 s | the blocks so far, status still working |
| Statement cancel | `cancelSignalTimeout=2` | the connection is aborted after 2 s |
| Claude process stop | SIGTERM after 3 s, SIGKILL 2 s later | the exit is reported |
| Tool run stop | SIGKILL 2 s after SIGTERM | the run ends as stopped |
| Output after a process exits (tools, Claude) | 2 s | a child holding the pipe no longer holds the run or session |
| `claude --version` | 5 s | a warning |
| A tab's topic read (Haiku) | 30 s | killed; the card says Haiku did not answer |
| Desktop notification (`osascript`) | 10 s | killed, not shown |
| A `git` command | 10 s | killed and its whole tree with it; the read or write says git took too long |
| Keychain calls | 10 s | killed; the connection loads without its password, the item is kept |
| Closing the window at quit | 5 s | quit anyway |
| The EDT | 200 ms | a stall, one INFO line when it ends; past 1 s (2 s in the JDK's drawing) a hang, logged with the EDT's stack; a stall of 1 s or more a notice whose click opens the log at the stack, or at the stall's line |
| One intent in the reducer | 200 ms | a stall, one INFO line when it ends naming its slowest part; past 1 s a hang, logged with the reducer's stack while it holds, a WARNING and the user's notice when it ends, whose click opens the log at the stack |

## 7. Ownership and exit

`App` creates every long-lived part in dependency order on a stack; `close()` pops it, so parts close in reverse order,
each once. Two are moved to the end: the embedded QuestDB, which the lanes may still use, and the window, titled "comando —
closing…" and greyed under a cover until everything behind it has closed. Every `close()` is idempotent, never throws
(failures are logged with the part's name), and is safe on a half-built part; a failed constructor step closes what was
built and rethrows.

| Created | Part | Closing it |
|---|---|---|
| 1 | `Store` | writes pending panel text and `panels.json`, finishes queued writes; waits up to 5 s (11 s for connections, one keychain call) |
| 2 | `IntentLog` | closes the log after the loop, so the shutdown's intents are recorded |
| 3 | `StateLoop` | stops the reducer after the queued intents, then its watchdog |
| 4 | `DbAdapter` | cancels running statements, closes connections |
| 5 | `MermaidDiagrams` | stops `mmdc` and its children |
| 6 | `LocalImages` | stops reading pictures and drawing SVGs |
| 7 | `McpServer` | denies every waiting approval, stops the server |
| 8 | `ClaudeAdapter` | SIGTERM, SIGKILL 2 s later; deletes every MCP configuration file and revokes its token |
| 9 | `ToolsAdapter` | SIGTERM to every run's process tree, SIGKILL 2 s later; stops the folder poll |
| 10 | `EmbeddedQuestDb` | closes after the lanes |
| 11 | `ComandoUi` | closes last: terminals, live plots, the EDT watchdog, the window |

Quit (window, Dock, menu) first asks when work would stop: a running query, a turn, a session's background work, a shell job in the foreground, a
Gradle run; Cancel is the default. It then saves the layout, ends terminals and live plots, greys the window, and
runs `onQuit`, which starts the `exit` thread: `App.close()`, then `System.exit(0)`. A shutdown hook calls `App.close()`
too; the second call does nothing.

## 8. UI packages

```
dev.marregui.comando.ui
├── kit/            theme, icons, commands, overlays, EDT helpers, formatting, text, trees, tabs, layout, resource
│                   actions, desktop, vcs
├── widget/         the contract: Widget, WidgetContext, WidgetRegistry; capability/ (Searchable, Copyable, …)
├── widgets/        one package per widget: approval, changes, console, diff, editor, markdown, plot, table, terminal, text,
│                   transcript (with doc/ and render/), tree
├── panels/         the window's regions: Workspace, EditorPanel, LowerPanel, SessionsSidebar, PanelsTab, FilesTab,
│                   SidePane, StatusBar, ResultsView, TerminalTabs, ViewTabs, …
├── dialogs/        folders/ (the Files tab's folders: FoldersEditor, FoldersDialog), resource/ (ResourceDialog, one dialog for connections and sessions), sessions/ (import)
├── shell/          ComandoUi (the frame and the UI port), WindowLayout, HelpActions, Menus, Keys, LivePlots, Notifier, HelpFiles, OpenFiles, DiffWindow, …
└── PanelTexts      editor text between saves
```

A layer uses only those above it in this list: `kit` uses `core` and the UI libraries; `widget` adds `kit`; a widget
package adds `widget` but no other widget package; `panels`, `dialogs` and `shell` use everything before them.
`UiArchitectureTest` (ArchUnit) fails the build when a widget imports another widget or `panels`, `shell` or
`dialogs`; when the kit imports a widget, `panels` or `shell`; or when any UI class refers to `db`, `claude`, `mcp`,
`tools` or `java.sql`. The Gradle module graph enforces it a second time for `db`, `claude`, `mcp` and `tools`.

New code goes: a view Claude or the user can place → `widgets/<name>`, a `ViewSpec` record in `core`, a registration
in `ComandoUi`, and for Claude a case in `mcp`'s `ViewSpecs` and its schema in the tool list; behaviour two widgets share → `kit` or a capability; a region of the window → `panels`; a user action →
a `Command`.

## 9. Code rules

1. A change to state is an intent; the reducer is the only writer of `AppState`.
2. A thread is of one of the six kinds of §6 and comes from `Threads`, an owner's `Errands` or `Watchdog`; a new
   platform thread needs a new kind. Every thread a part starts ends when the part closes. Repeated work goes to a
   part's strand, at most one ask waiting per key, never to a thread of its own per occurrence.
3. Every wait has a bound and an action on expiry.
4. Nothing in `ui` imports an adapter module; no widget imports another widget.
5. Nothing blocks the EDT: process runs, network, reads of unknown size and keychain calls go to other threads; Swing
   only on the EDT.
6. Text from Claude, a database or a file reaches Swing as text, never as HTML (`Plain`, `MarkdownHtml`).
7. Tests never touch the user's data root, keychain or window preferences unless a flag says so.
8. Code and comments name no tool, agent or review finding.
9. Compiler warnings are errors (`-Xlint:all -Werror`, except `serial`, `processing`, `this-escape`).
10. Types are imported, never fully qualified, except to tell apart two types with one simple name. Imports: project,
    `javax`, `java`, each sorted; no wildcards.
11. Logging is `System.Logger`; messages read `verb phrase [key=value]`; a failure logs its exception, a handled
    condition its message.

Hot paths follow the author's high-throughput Java guidelines: results stored by column in primitive chunks, grid
cells formatted into one reused buffer, plots reduced to min and max per pixel column, transcript layouts kept and only
the clip painted, release-acquire publication in the stores. Object pools, ring queues, padding and worker pools are not
used: comando's event rates are UI rates.

## 10. Quality

`./gradlew build` compiles every module with warnings as errors and runs the unit and adapter tests. The database tests run against an embedded QuestDB, and against PostgreSQL through Testcontainers
when Docker is there; the Claude adapter runs against fake CLI scripts and recorded streams; the MCP server is tested
over HTTP.

| Opt-in test | Enabled by | Why |
|---|---|---|
| keychain round trip | `./gradlew test -PkeychainTest=true` | writes and deletes a login keychain item |
| window replay | `./gradlew :app:uiTest` | needs a display; its windows open on no screen and take no focus |
| the kit with the real Claude Code | `COMANDO_KIT_LIVE=true ./gradlew :claude:test --tests '*KitLiveTest'` | runs the installed `claude` in a throwaway `CLAUDE_CONFIG_DIR` |

Gates:

| Gate | Command | Budget |
|---|---|---|
| transcript frame time | `./gradlew :ui:transcriptGate` | p99 ≤ 16 ms ([UI](ui.md#43-the-frame-time-gate)) |
| EDT responsiveness | always on: `EdtWatchdog` | a probe answered within 200 ms |
| reducer responsiveness | always on: `StateLoop` and its watchdog | each intent applied within 200 ms |

comando has no JFR code. It is profiled from outside like any JVM, with the JDK's own events, and its EDT stalls are
in its log ([UI §1](ui.md#1-the-kit)); the packaged runtime keeps `jdk.jfr`, `jdk.management.jfr` and
`jdk.management.agent` so the installed app can be recorded, and watched over JMX, too
([record 54](adr/0054-comando-knows-nothing-of-jfr.md)).

CI (`.github/workflows/build.yml`, Ubuntu and macOS, Temurin 25, actions pinned by SHA) runs `build`, `:app:uiTest`
(under `xvfb-run` on Ubuntu), a test summary (`ci/TestReport.java`), the transcript gate (reported, not enforced), and on
macOS `:app:dmg` as a packaging smoke test. `dependabot.yml` groups weekly updates of the actions and the Gradle
dependencies. `./gradlew :app:dmg` builds an unsigned disk image with `jpackage` and a runtime of the modules listed in `app/build.gradle.kts`
([decision 16](adr/0016-unsigned-macos-build.md)).
