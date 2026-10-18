# Workbench

What the window does: panels and their bindings, running text, the database, the development tools, the Files tab, and
what is kept on disk.

1. [Panels and resources](#1-panels-and-resources)
2. [Running text](#2-running-text)
3. [The database](#3-the-database)
4. [Development tools](#4-development-tools)
5. [The Files tab](#5-the-files-tab)
6. [Persistence](#6-persistence)

## 1. Panels and resources

### 1.1 One model for connections, sessions and folders

A connection, a session and a folder of the Files tab are resources: configured, stored and listed in the sidebar.
Each is written once as a resource kind. Connections and sessions are edited in one dialog; folders in `folders.json`
([§5](#5-the-files-tab)).

| Layer | Connection | Session | Folder |
|---|---|---|---|
| Spec (`core.model`) | `ConnectionSpec`: name, host, port, database, user, password, production, TLS mode | `SessionSpec`: name, directory, model, effort, permission mode, extra directories, budget, checkpoint command, conversation id, next start (new, resume, fork) | `FolderRoot`: name, path, index file, names left out, icon |
| Kind (`core.resource`) | `ConnectionKind` | `SessionKind` (a clone forks the conversation) | `FolderKind` |
| Live state (`core.state`) | `ConnectionState`: closed, opening, open, closing, failed, last error | `SessionState`: the same, plus turns, context, activity, branch, background tasks | — |
| Store file | `connections.json` | `sessions.json` | `folders.json` |

`ResourceDialog` edits one resource: stored, new, or an unsaved copy ([record 40](adr/0040-one-resource-per-dialog-and-two-tab-rows.md)).
The form is generated from the kind's attributes, with a hint under each field; rarely set fields sit under "▸
Advanced". ⏎ saves; ⌘⏎ saves and assigns to the focused panel; Esc or the close button asks before dropping unsaved
changes. Connect or Start, and Assign save a changed form first; Test tries the form as typed, without saving it.
Their outcome shows on the line above the buttons.

### 1.2 Panels

A panel is a tab in the workspace: an id, a title, its text, and two bindings by name, a connection and a session,
either empty. Several panels may share a connection; a session has at most one panel, and one with none is headless
([record 38](adr/0038-a-session-has-one-panel.md)).

- **Opening a session** (double-click in the sidebar, Open in a panel, the dialog's Assign, the header chip) brings its
  panel forward; else the focused panel takes it when it has no session; else a new panel opens, titled after the
  session, with the focused panel's connection. Detach (header chip) leaves the session running headless.
- **A new panel** (⌘T) gets the focused panel's connection and no session.
- **Moving** a tab: drag it along the row; a line marks where it lands. The order is saved and kept across restarts.
- **HTML files** open as text, to edit; the Browser button opens the page in the desktop's browser
  ([record 56](adr/0056-html-opens-in-the-browser-not-in-comando.md)).
- **Links** in rendered Markdown: a web link opens in the browser at a click; a link to another Markdown or HTML
  file, beside the document or by its full path, opens that file in a panel at a double click, or brings forward the
  panel that has it. A link to a heading (`#` and the heading's id, as GitHub makes it: the text in lower case,
  punctuation dropped, each space a hyphen, `-1`, `-2` on a repeated heading) scrolls that heading to the top at a
  double click: in the same document, or in the other one, as it opens or in its open panel (one showing its text
  stays as it is); `#` alone goes to the top. Until the reader scrolls, the heading stays at the top as pictures and
  diagrams arrive above it.
- **Running** SQL opens the panel's connection when closed; a prompt starts its session when stopped.
- **The header** shows the session, then the connection, as chips with a status dot and a plug (green when running or
  connected) that starts or stops, connects or disconnects. Next to the session's plug: a fresh start (while stopped
  with a conversation: a new conversation; the old one stays importable) and a checkpoint (sends the session's
  checkpoint command, `/reflect` by default; purple once a turn ended with the context at 60% of the window or more and
  the command was not sent since) ([records 45 and 46](adr/0046-clear-buttons-text-coloured-by-kind-checkpoint-due.md)),
  then, while the session runs, compact and clear (`/compact`, `/clear` sent from the panel, as on the session's row).
  On the right, icons without a button's shape, close together: Browser (HTML: the page in the browser), Preview or
  Edit (Markdown), the editor theme, Run, Stop, Clear (the panel's own text, one ⌘Z step; not on documents). A
  production connection frames the panel in red ([record 57](adr/0057-a-light-editor-and-a-header-of-icons.md)).
- **The editor theme** is dark (IntelliJ's Dark) or light (IntelliJ's Light) for every panel at once: the text, its
  gutter, its rendered Markdown (code blocks in the theme's colours, Mermaid diagrams drawn in Mermaid's default
  theme). The rest of the window stays dark. The choice is kept across restarts.
- **Pictures in Markdown**: an image link to a PNG, JPEG, GIF or SVG on this machine, named from the document's folder
  (`images/chart.png`, `../img/flow.svg`) or by its full path, shows the picture, no wider than the panel; it is read on
  a thread of its own and shown when ready, and read again when the file changes. A web address shows the alt text;
  so does a missing file, with the reason ([record 58](adr/0058-markdown-shows-the-pictures-beside-it.md)).
- **The tab icon** shows the first that applies ([records 42 and 43](adr/0043-a-session-waiting-on-background-work-shows-it.md)):
  a question mark while a request waits; a red mark when the last turn or query failed, or the session died, out of view; a green
  check when a turn or query finished out of view; a spinner while one runs; an hourglass while background work the
  session started has not ended; the Claude mark for an idle session; the file icon without one. The first three put
  "● " before the title: the question mark's until the request is answered, the others' until the tab is looked at. A
  waiting request or a failure bounces the Dock while the window is not active.
- **The lower panel** follows the focused panel: its last SQL run, its session's transcript, its terminal. Approvals
  lists every session's requests.
- **Closing** (⌘W when no view has the keyboard; the last open panel cannot be closed) keeps a panel of comando's own, closed, with its file, title and bindings, until removed; a document
  is dropped ([record 39](adr/0039-panels-are-kept-until-removed.md)). ⌘⇧T reopens the last of ten recently closed;
  the Panels tab reopens any. Closing asks first when the panel's shell runs a job in the foreground.
- **Renaming** a connection or session renames every binding (a running session must stop first); removing one closes
  it and unbinds its panels.

### 1.3 The sidebar

Four tabs in two fixed rows: Panels and Files, then Sessions and Connections; the tab in front is kept across restarts.
Panels, Sessions and Connections have a bar of buttons above their list (`ResourceActions`): add and import on the
left, edit, clone and remove of the selected row on the right, off while nothing is selected
([record 37](adr/0037-session-actions-act-on-the-selected-row.md)); the Files tab has none ([§5](#5-the-files-tab)). Removing something in use asks
**Stop and remove**, **Disconnect and remove** or **Close and remove**, or **Leave it**.

- **Sessions:** name, where it is bound ("headless" when nowhere), status, current activity or what it waits on,
  pending approvals; directory and branch; `ctx` and the context % (purple from 60%), how long it has run, a line
  only when either is known, so a disconnected row has its controls under its folder. Over the
  row's text the tooltip shows the details Copy details copies, the full session id among them. At the left a plug, fresh start, checkpoint, and while running compact and clear (`/compact`, `/clear`
  sent from the session's panel); at the right the model and effort as menus (a change saves and, on a running session,
  sends `/model` or `/effort`). A copy button at the row's top right under the mouse, Copy details in its menu and ⌘C
  copy name, path, branch, full id, status, uptime and start, context with tokens, model and effort. Listed running
  first, then by name, until a row is dragged; the order then stands (saved in `sessions.json`) until the sort button
  ([record 53](adr/0053-session-rows-compact-clear-copy-and-an-order-of-your-own.md)). Import lists stored
  conversations.
- **Panels:** comando's own panels, open and closed, with session, connection and file name. Clone copies text and
  connection, not the session. Removing deletes the panel's file; removing the last open panel leaves an empty one.
- **Connections:** name, user, host, port, database, status; the context menu connects, tests, explores the schema.
- ⌘B or the `⋯` button hides the sidebar to a rail on the left edge.

## 2. Running text

One editor holds SQL and prompts. SQL goes to the panel's connection, anything else to the panel's session.

### 2.1 What runs

The selection, when there is one; else the block under the caret: a run of non-blank lines between blank lines. On a
blank line, the nearest block above. A block of several SQL statements runs in one call; Results shows the first
result, and a failure says the statements before it may have run. The block that ran is tinted until the next edit,
not selected, so the next keystroke cannot replace it.

### 2.2 SQL or prompt

`Classifier.classify` decides, in order:

1. A first line `-- @sql` or `-- @claude` decides, and is removed.
2. A leading `/` and a letter is a Claude command (`/compact`, a skill).
3. A block of comments only has nothing to run: no gutter icon; ⌘⏎ says "Nothing to run".
4. A leading SQL keyword followed by the shape that keyword takes is SQL, unless an English function word appears
   outside quotes or the text ends with `?` or `.`. Keywords: `SELECT`, `WITH`, `INSERT`, `UPDATE`, `DELETE`,
   `CREATE`, `ALTER`, `DROP`, `TRUNCATE`, `EXPLAIN`, `SHOW`, `COPY`, `VACUUM`, `REINDEX`, `SET`, `CANCEL`, `BACKUP`,
   `CHECKPOINT`, `SNAPSHOT`, `DECLARE`, `BEGIN`, `COMMIT`, `ROLLBACK`.
5. Otherwise a prompt.

"update the README" and "show me the log" are prompts: no `SET`, and function words. Identifiers count as whole words
(`my_table`, `us_east`), quoted strings and comments are skipped, and the gutter shows each block's target (database or
Claude icon) 250 ms after the last keystroke, before anything runs.

### 2.3 Keys

| Key | Runs | As |
|---|---|---|
| ⌘⏎ | selection or block | classified |
| ⌘⇧⏎ | selection or block | prompt |
| ⌥⏎ | selection or block | SQL |
| ⌃⏎ | selection or line | typed into the panel's terminal |
| ⌘. | — | stops the panel's SQL run, else interrupts the session's turn |
| ⌘⇧. | — | interrupts the session's turn |

The run keys act while the keyboard is in the focused panel's editor.

### 2.4 Where things land

SQL results fill the Results tab; an error is selectable text, and a position the database names is underlined in a
single-statement block until the next edit. A prompt's turn streams into the Session tab. ⌘⇧I appends what the panel's
last run produced as a block of its own: the selected rows (up to 500), else the first 50, else the error, as
tab-separated text in a fence with no blank line inside; a question typed on the next line belongs to the same block,
so ⌘⏎ sends rows and question together.

## 3. The database

PostgreSQL and QuestDB over the PostgreSQL wire protocol, through the PostgreSQL JDBC driver.

### 3.1 Lanes

Each connection name has a lane: one connection and one thread running its opens, closes, statements and health checks
in order. The user's runs and Claude's `db_run_sql` queue on the same lane. A lane's thread stops once its last task
ends with no connection; renaming, repointing or removing a connection also forgets its schema.

| Property | Value |
|---|---|
| URL | `jdbc:postgresql://<host>:<port>/<database>` |
| Driver properties | `ApplicationName=comando`, `loginTimeout=20`, `tcpKeepAlive=true`, `cancelSignalTimeout=2`, `sslmode` (`prefer` by default, or `disable`, `allow`, `require`, `verify-ca`, `verify-full`) |
| Autocommit | on |
| Fetch | 5 000 rows per round trip; at most 5 000 000 rows kept, then the run is marked truncated |
| Health check | `isValid(5)` every 60 s on the lane; a failure closes the connection and reports it lost |

**Cancel.** ⌘. marks the request cancelled, then reads the statement the lane runs; the lane publishes its statement,
then reads the mark, so either the lane sees the mark first or the caller sees the statement and cancels it. A
statement still running 2 s after its cancel has its connection aborted; the lane reconnects on the next run.

### 3.2 Results

`ColumnarResult` stores rows by column in primitive chunks of 16 384 values: integers, booleans and times (epoch
microseconds UTC) as `long`, floats as `double`, everything else (`numeric` included) as text. The lane publishes the
row count with a volatile write, so the grid and plots read while the fetch continues. The grid paints visible rows
only, formatting cells into one reused buffer.

| Key | Does |
|---|---|
| ⌘J | plots the last result: x is the first time column, else the first numeric one; up to eight more numeric columns as series; at most 2 000 000 rows |
| ⌘⇧J | the same, re-running the query each period (default 5 s) while it is not running; another run in the panel or a failed query or connection stops it with a notice; closing its tab stops it quietly |
| ⌘⇧R | keeps the result in a tab of its own (at most 100 000 rows) |
| ⌘⇧I | attaches the result to the editor ([§2.4](#24-where-things-land)) |
| ⌘⇧S | exports the grid as CSV in the order shown, written off the EDT |

A one-column `EXPLAIN` result shows as a tree by indentation, with a toggle back to rows.

### 3.3 Schema

When a connection opens, its schema loads in the background: from QuestDB's catalogue (`tables()` and each table's
description, up to 50 tables), else from `information_schema.columns`; at most 50 000 rows. A `CREATE`, `ALTER`,
`DROP`, `TRUNCATE` or `RENAME` from the editor or from Claude reloads it. It feeds completion (keywords, then tables and
columns of the bound connection) and the explorer (⌘⇧E): a tree in the side pane, whose table menu inserts
`SELECT * FROM <table> LIMIT 100;`.

### 3.4 Embedded QuestDB

A toggle starts a QuestDB server in `questdb/` of the data root, on QuestDB's default ports (PG wire 8812, console 9000,
ILP 9009). Every listener binds `127.0.0.1` and ILP over UDP is off, through `QDB_` environment overrides, because
QuestDB binds all interfaces by default and its default login is public. It stops at exit, after the lanes.

## 4. Development tools

Runs go through `ToolPort` (`tools` module); the terminal lives in the UI because JediTerm is a Swing component.

### 4.1 Terminal

JediTerm on a pseudo-terminal from pty4j, one per panel, started the first time it is shown, as `$SHELL -l` (else
`/bin/zsh`, else `/bin/sh`) in the session's directory, else home; `TERM=xterm-256color`, `TERM_PROGRAM=comando`. ⌃3
shows it; ⌃⏎ in the editor types the selection or line into it and presses Enter, waiting until a starting shell has
written and then been quiet for 200 ms (at most 3 s). An exited shell offers a restart bar.

### 4.2 Gradle runs

⌥⌘G asks for tasks (default `build`, then the last used) and runs them in the build of the focused panel's session:
the nearest directory at or above its working directory with an executable `gradlew`.

- **Command:** `<wrapper> <root> <tasks>`, the wrapper being `tools.json`'s `gradleWrapper`, by default the kit's
  `gradle-serial` (one run per checkout at a time); `<root>/gradlew --console=plain <tasks>` when it is `none` or
  the wrapper is not found. Arguments split like shell words, without expansion.
- **Output:** a console view filled as output arrives; past 4 MB the rest is held and its last 64 kB shown at the end.
  The same command again replaces an unpinned view and stops the old run.
- **Stop:** SIGTERM to the process tree, SIGKILL 2 s later; output is read at most 2 s after the process exits.
- **Tests:** JUnit XML written since the run started becomes a tree, failures first; a failure links to its source
  line, and "Send failures to Claude" sends messages and stacks (60 lines each) to the session of the panel the run
  came from.
- A run of 10 s or more ends with a desktop notification while the window is not active.

`tools.json` in the data root holds the Gradle wrapper's path (`gradleWrapper`); Tools ▸ Tool settings opens it
(written with an empty key when missing). Each run reads it; what is wrong with it is the run's first console line, and
the default applies.

## 5. The Files tab

The folders the user chose, one top-level row each, as a navigator of their folders and files
([records 49, 51, 52, 55](adr/0055-the-files-tab-only-shows-folders-json-configures-it.md)). The tab only shows and
opens: it has no buttons and writes nothing. The files it opens are edited and saved like any other; a `.log` stays
read-only.

- **Folders** are resources ([§1.1](#11-one-model-for-connections-sessions-and-folders)): a unique name, a full path,
  an optional index file inside it, names to leave out, and the row's icon (folder, scratchpad, memory, knowledge,
  skills, plugin). Without `folders.json` there is one folder, `~/.claude`, opening `CLAUDE.md` and leaving out Claude
  Code's transcripts, caches and per-session state; an emptied list stays empty.
- **Configuring.** Files ▸ Files tab folders opens `folders.json` in a panel (written with the tab's folders
  when missing). The store checks it every second: a change it did not write is read again and the tab follows; an
  entry that breaks the rules is left out with a notice; a file that does not parse keeps the tab's folders, says why
  and stays where it is. The kit adds its folders there too. A `readOnly` key from older versions is ignored.
- **Order.** At a folder's top level its index file comes first. Every level then lists folders not named after a date,
  date-named folders (`yyyy-MM-dd`) oldest first, then files, by name ignoring case. Hidden entries, `node_modules`,
  `build`, `target`, `__pycache__`, `out` and the folder's own exclusions are left out; a link to a folder is listed,
  not followed.
- **Bounds.** 12 levels; at most 20 000 names listed and 5 000 entries kept per folder tree; past a bound the folder ends
  with `…`. An unreadable subfolder says why and the rest stands.
- **Clicks.** A click selects and opens nothing; a double click opens a folder, or a file in a panel (Enter opens a
  file). A text file of at most 2 MB opens in a panel bound to it; a larger one (to 4 MB) or a binary one shows in the
  side pane. Opening a folder opens no file; the first time, its newest date-named folder opens too.
- **Menus.** A file's: Open in a panel, and Edit in a panel (its text rather than rendered). A folder row's: its index
  file.
- **Sync.** Only folders whose rows are open are read: at once when opened, then every 5 s on `folder-poll`, sent only
  when something changed. A tree carries the settings it was read with and
  is dropped if the folder was edited since; reads are ticketed so an older one never overwrites a newer one. The tree
  keeps what the user opened and selected.
- **Filter.** A field above the tree finds entries by name across every folder, reading never-read folders once; a
  second tree shows the matches with the folders leading to them; Esc returns to the tree as left. The palette's Files
  command puts the keyboard there.

| Entry | Icon |
|---|---|
| a folder row | its icon setting: folder; a pencil, chip, book, bolt or puzzle piece on it |
| a folder; `work` | folder; folder with a gear |
| `*plan*.md`; `READ-*`; an index file name; Markdown | plan; book; index; Markdown |
| code; data (`.json`, `.yaml`, …); shell; `.sql`; `.csv`; images; diffs; `.txt`, `.log` | code; braces; terminal; database; table; image; diff; text |

## 6. Persistence

### 6.1 The data root

`-Dcomando.home`, else `COMANDO_HOME`, else `~/.comando`.

| Path | Holds |
|---|---|
| `connections.json`, `sessions.json`, `folders.json`, `panels.json` | the specs and panel metadata (bindings, file name, closed) |
| `panels/<title>.md` | each panel's text ([record 26](adr/0026-a-file-per-panel.md)) |
| `tools.json` | the Gradle wrapper's path |
| `comando.lock` | held while comando runs; a second start on the same root refuses |
| `run/` | MCP configurations of running sessions (`0600`) |
| `audit/audit-<UTC date>.jsonl` | every MCP tool call and approval decision |
| `logs/comando-<n>.log` | the log, 10 files of 1 MiB; Tools ▸ Help ▸ Show the log opens it read-only |
| `logs/intents-<millis>.jsonl` | the intent log, when recording |
| `help/tutorial.md` | the tutorial, rewritten from the build's copy when it differs |
| `cache/mermaid/` | diagrams drawn by `mmdc`; safe to delete |
| `cache/images/` | SVG pictures named in Markdown, drawn to PNG; safe to delete |
| `kit/` | comando's copy of its Claude Code kit, made equal to the build's at start ([record 50](adr/0050-comando-ships-a-claude-code-kit.md)) |
| `questdb/` | the embedded QuestDB's data |

Outside it: connection passwords in the macOS keychain ([Claude §3.3](claude.md#33-secrets)); window layout and recent
inputs in Java preferences (node `dev/marregui/comando`; tests use `comando.prefsNode`); Claude Code's conversations in
`~/.claude/projects/`, read, never written; the kit's skeleton files in Claude Code's folder, written only when missing.

### 6.2 Store files

`JsonFile` writes a temporary file and moves it over the old one with `ATOMIC_MOVE`, so a crash leaves old or new, never
torn. A file that cannot be parsed at start is renamed `<name>.corrupt-<millis>`. `Store` writes from immutable
snapshots: sessions and panels on one thread, connections on another, because a keychain call may wait on an unlock
prompt. Specs are written at once; panel text 800 ms after the first unsaved change. `Store.close()` writes what is
pending.

On a fresh data root comando writes a connection named local QuestDB and one panel, scratch, bound to it, with a
welcome text.

### 6.3 After a restart

Connections and sessions come back closed (sessions with their stored transcripts); folders as stored, none read until
opened; panels with their bindings and text, a binding to a name that no longer exists dropped; the window's size,
position and dividers, the sidebar's tab and whether it shows, and whether the sessions keep a dragged order. Views, results and terminals belong to one run and are not restored.

### 6.4 Panel files

Each panel's text is a Markdown file of its own in `panels/`, so a panel can be a spec other tools read, diff or keep
in Git.

| Event | Effect on the file |
|---|---|
| new panel | written at once, named after the title (unsafe characters become `-`, at most 80 characters, ` 2`, ` 3` on a clash, compared ignoring case) |
| typing | written 800 ms after the first unsaved change, through a temporary file |
| rename | moves to a free name for the new title |
| close | the text is written; a panel of comando's own stays in `panels.json` as closed; a document is let go |
| remove | the panel's own file is deleted; a document's file never is |
| a write that fails | the text waits in `panels.json` and is retried every 5 s and on every save |
| ⌘O, or a double click in the Files tab | opens the file in a panel titled after it; a file of `panels/` bound by name, any other by absolute path; a file already open brings its panel forward |
| edited outside comando | checked once a second; reloads into its panel when the panel has no unsaved edits (the caret stays, ⌘Z restores) ([record 27](adr/0027-panels-reload-files-changed-outside.md)); otherwise the next save moves the outside version aside as `<name> (outside comando <date time>).md` |
| deleted outside comando | the panel is removed, unless it has unsaved edits, which its save writes back ([record 34](adr/0034-a-deleted-document-closes-its-panel.md)) |
| a file of `panels/` no panel names, at start | becomes a closed panel, with a notice |
