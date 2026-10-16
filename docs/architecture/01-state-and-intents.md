# State and intents

## 1. The state

`AppState` is one immutable record. It holds metadata and ids; lists are short (tens of entries), so lookups scan.

| Field | Holds |
|---|---|
| `panels` | each open editor panel: id, title, the connection and the session it is bound to, whether it is a document (a file opened from outside comando's folder) |
| `closed` | comando's own panels the user closed, kept with their bindings until removed, for the Panels tab and ⌘⇧T |
| `focused` | the panel the user works in; the lower panel follows its bindings |
| `connections` | each connection's spec and status (closed, opening, open, closing, failed) with its last error |
| `sessions` | each session's spec, status, the `system/init` facts (conversation id, model, CLI version, capabilities), pending turns, cost, context tokens and window, activity, git branch |
| `folders` | the Files tab's folders, in order: name, path, index file, names left out |
| `runs` | at most one SQL run per panel: request id, SQL, status, row count, timings, error, start time, a note on the other statements of a multi-statement block, and whether it runs in the background (a live plot's refresh, which posts no "Query finished" and no ERROR) |
| `approvals` | permission prompts waiting for an answer, from every session |
| `views` | views placed by Claude or the user: id, title, spec, placement (lower or side), pinned, owning session, version |
| `lowerTab` | the selected lower tab: Session (the initial one), Approvals, Terminal, Results, or a view by id |
| `notice` | the standing status-bar message: level, text, time, and its subject (a panel, connection, session, folder or approval) |
| `notices` | the last 50 notices, oldest first, for the status bar's history |
| `nextId` | the next request, view or run id |

Invariants the reducer keeps: at least one panel; `focused` names a panel; connection names and session names are unique; one run per panel. The record's constructor checks the first two.

Large data stays out of the state ([section 5](#5-stores-outside-the-state)).

## 2. Intents

An intent is a record of the sealed interface `Intent`. Anything that changes the state is an intent: a key the user pressed, a row batch from the database, an event from Claude Code, a call to an MCP tool.

| Group | Intents |
|---|---|
| Panels | `AddPanel`, `ClosePanel`, `ReopenPanel`, `RemovePanel`, `RenamePanel`, `FocusPanel`, `BindConnection`, `BindSession` |
| Running text | `RunText` (panel, text, target: auto, SQL or Claude), `RefreshSql` (a live plot's re-run, which keeps the lower tab), `CancelRun` |
| SQL run events | `SqlStarted`, `SqlRows`, `SqlCompleted`, `SqlFailed`, `SqlCancelled`, `SqlWarning` (the later statements of a block) |
| Connections | `SchemaChanged` (a DDL from Claude's `db_run_sql`), `SaveConnection`, `RemoveConnection`, `OpenConnection`, `CloseConnection`, `TestConnection`, `ExploreSchema`, `ConnectionOpened`, `ConnectionClosed`, `ConnectionFailed`, `ConnectionLost`, `ConnectionTested` |
| Sessions | `SaveSession`, `RemoveSession`, `ImportSessions`, `OpenSession`, `StartSession`, `StartFresh`, `StopSession`, `InterruptSession`, `Checkpoint`, `ClearTranscript`, `SetSessionModel`, `SetSessionEffort`, `SessionStarted`, `SessionInit`, `SessionActivity`, `SessionTurnRunning`, `SessionTurnEnded`, `SessionTasksChanged`, `SessionExited`, `SessionBranch`, `PlanUsageRead` |
| Folders | `SaveFolder`, `RemoveFolder` |
| Approvals | `ApprovalRequested`, `ApprovalAnswered`, `ApprovalWithdrawn` |
| Views | `ShowView`, `RefreshView` (new data for a view the user placed, without selecting it), `CloseView`, `PinView`, `SelectLowerTab`, `Arrange`, `SessionLayout` (a session's layout request, which the reducer limits to that session's panels), `SessionFocus` (that request's focus and tab, applied after the typing pause only if the panel is still the session's) |
| Editor and files | `EditPanel`, `OpenFile` |
| Development tools | `RunGradle`, `RunJfrq`, `CommandFinished`, `CancelCommand`, `OpenRecording`, `BrowseFolders`, `WatchFolders`, `FolderLoaded` |
| Notices | `Notify` (level, text, desktop, subject), `DismissNotice` |

A panel argument may be null in `RunText`, `RunGradle` and `EditPanel`; it means the focused panel. A console view records the panel its run came from, so a failed test goes to that panel's session, not to whichever panel has the focus.

## 3. The reducer

`Reducer.apply(state, intent)` returns a `Transition`: the next state and a list of effects. It is a pure function of its arguments, a `Clock` and the home folder (where a file link or a Gradle run without a session starts), so tests drive it with a fake clock and assert on both outputs. It throws for nothing a user or an adapter can send: an intent naming a missing panel, connection or session changes nothing or sets a notice.

Notices follow four rules. Every notice joins the history. A new notice replaces the standing one unless the standing one is an ERROR, the new one is not, and the two are about different subjects; so an error stays until it is dismissed, replaced by another error, or replaced or settled by its own subject. An event that settles a subject clears its notice: a statement that runs or completes clears its panel's, an opened connection its connection's, a started session or a successful turn its session's, an answered approval its approval's (a withdrawn one replaces it with a warning that names the reason; approvals withdrawn because their session ended or was removed clear it), and removing a connection or session clears its own. A new ERROR notice also asks for a desktop notification, which shows while the window is behind.

Rules that recur:

- **Ids.** The reducer assigns request, run and view ids from `nextId`. An adapter event carries the id; `onRun` drops an event whose run is no longer active, so a cancelled or superseded run never changes the state again.
- **Start on demand.** Running SQL on a closed connection opens it first; sending a prompt to a stopped session starts it first. The effects come out in that order.
- **Same state for no change.** Focusing the focused panel or selecting the selected tab returns the same object, so listeners and the UI do not re-render.
- **User wins.** `ShowView` replaces an unpinned view with the same title, session and placement; a pinned view is never replaced, so a view the user pinned survives Claude's next call.

## 4. Effects

An effect is work the reducer asks an adapter to do. `EffectRouter` hands each to its port on the reducer thread; every port method only queues work and returns.

| Port | Effects |
|---|---|
| `DatabasePort` | `OpenDb`, `CloseDb`, `ForgetSchema`, `TestDb`, `ExecuteSql`, `CancelSql`, `ReleaseResult`, `LoadSchema` |
| `ClaudePort` | `StartClaude`, `StopClaude`, `SendToClaude`, `InterruptClaude`, `LoadHistory`, `ClearTranscript`, `ForgetSession` |
| `ApprovalPort` | `ResolveApproval` |
| `UiPort` | `EditPanelText`, `DesktopNotify`, `ForgetPanel`, `DeletePanel`, `ShowFile`, `Arrange`, `ShowFolder`, `ShowPlanUsage` |
| `PersistencePort` | `SaveConnections`, `SaveSessions`, `SaveFolders`, `SavePanels` |
| `ToolPort` | `RunGradle`, `RunJfrq`, `CancelCommand`, `ForgetCommand`, `OpenRecording`, `ReadFolders`, `WatchFolders` |

`StateLoop` runs one intent at a time: observers first (the intent log), then the reducer, then the effects in order, then the state listeners (the UI's coalesced render). The reducer, each effect and each listener are guarded, so a failing one is logged and the loop goes on; an observer must not throw (the intent log catches its own failures). Each applied intent is a JFR event `comando.Intent` with its type, effect count and whether the state changed.

## 5. Stores outside the state

A result of five million rows, a transcript of thousands of blocks or a build's output would make every state copy expensive. They live in stores that the state refers to by id. Each store has one writer thread; the EDT reads.

| Store | Keyed by | Written by | Read by |
|---|---|---|---|
| `ResultRegistry` → `ColumnarResult` | SQL request id | the connection's lane thread | Results tab, plots, MCP |
| `TranscriptRegistry` → `Transcript` | session name | the session's strand | Session tab |
| `ConsoleRegistry` → `Console` | command run id | the run's thread | console views |
| `SchemaRegistry` → `Schema` | connection name | the database adapter | editor completion |
| `PanelTexts` (the store) | panel id | the EDT | the store thread, on save |

Publication: `ColumnarResult` and `Transcript` publish their size through a `volatile` field after the data it covers is written, so a reader that reads the size first sees complete rows or blocks. `Console` guards its buffer with its own lock and readers copy from the length they already have. A writer that replaced a transcript block tells listeners; the UI coalesces those calls into one repaint.

## 6. The intent log and replay

With `-Dcomando.recordIntents=true`, `IntentLog` writes one JSON line per intent to `logs/intents-<millis>.jsonl` under the data root. The first line holds the state the intents start from. Interface-typed fields (view specs, approval decisions) carry their record's class name. Every `password` field is blanked before the line is written.

`IntentLog.read` returns the starting state and the intents. The replay test feeds them to a real `StateLoop` and the real Swing shell, with every other port replaced by a recording fake, and compares the window with the final state ([Quality](../design/quality.md)).

The log holds intents only. The stores outside the state (result rows, transcripts, console output) are not recorded, so a replay reproduces the window's layout, panels, views and run states, but not the rows, transcript text or console text they showed. The plan asked for the stream fixtures and result snapshots to be recorded too; that is not built.
