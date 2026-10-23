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
Each is written once as a resource kind. Connections and sessions are edited in one dialog; folders in two windows of
their own, Configure ~/.claude folders and Configure repositories, which keep them in `folders.json`
([§5](#5-the-files-tab)).

| Layer | Connection | Session | Folder |
|---|---|---|---|
| Spec (`core.model`) | `ConnectionSpec`: name, host, port, database, user, password, production, TLS mode | `SessionSpec`: name, directory, model, effort, permission mode, extra directories, budget, checkpoint command, conversation id, next start (new, resume, fork) | `FolderRoot`: name, path, index file, names left out, icon, whether entries go to the Trash, the listing file, whether it is a repository |
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
- **Renaming** a panel: double-click its tab, the Panels tab's Rename…, or "Rename panel…" in the palette.
- **Restore layout** (palette) puts the sidebar, side pane and lower divider back as they were before a session
  arranged them; it is greyed out while nothing was arranged.
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
  then, while the session runs, compact and clear (`/compact`, `/clear` sent from the panel, as on the session's row),
  and rewrite as a document: the session rewrites the panel's whole text as a professional Markdown document and puts
  it in place of the text, ⌘Z bringing the original back ([record 85](adr/0085-a-panel-rewritten-as-a-document.md)).
  On the right, icons without a button's shape, close together: Browser (HTML: the page in the browser), Preview or
  Edit (Markdown), the editor theme, Run, Stop, Clear (the panel's own text, one ⌘Z step; not on documents). A
  production connection frames the panel in red ([record 57](adr/0057-a-light-editor-and-a-header-of-icons.md)).
- **The editor theme** is dark (IntelliJ's Dark) or light (IntelliJ's Light) for every panel at once: the text, its
  gutter, its rendered Markdown (code blocks in the theme's colours, Mermaid diagrams drawn in Mermaid's dark
  theme, or its default theme with light editors). The rest of the window stays dark. The choice is kept across restarts.
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
  lists every session's requests. At its right end, the gold ingot chooses the window's gold (Treasure, 18k yellow,
  Champagne; kept across restarts) and Clear empties the Session tab
  ([record 83](adr/0083-one-gold-chosen-beside-clear.md)).
- **Closing** (⌘W when no view has the keyboard; the last open panel cannot be closed) keeps a panel of comando's own, closed, with its file, title and bindings, until removed; a document
  is dropped ([record 39](adr/0039-panels-are-kept-until-removed.md)). ⌘⇧T reopens the last of ten recently closed;
  the Panels tab reopens any. Closing asks first when the panel's shell runs a job in the foreground.
- **A tab's menu** (right click on the tab, not the panel) closes it, the others, the tabs to its right or those to its
  left, in the order shown after any drag. Such a close puts the clicked tab in front and asks nothing: a tab waiting
  for the user and a tab whose shell runs a program stay open, and one notice says how many closed and stayed; a tab
  mid-turn closes and its session runs on ([record 63](adr/0063-a-tabs-menu-closes-many-and-a-scratchpads-entries-go-to-the-trash.md)).
- **Renaming** a connection or session renames every binding (a running session must stop first); removing one closes
  it and unbinds its panels.

### 1.3 The sidebar

Four tabs in one row, each an icon named by its tooltip: Sessions, Connections, Panels, Files; the tab in front is
kept across restarts. A right click on a tab selects it and opens its menu: the tab's own actions, the window's
commands for it with their keys ([record 70](adr/0070-the-sidebars-tabs-in-one-row-with-a-menu-each.md)): New session…, Import stored
conversations… and Sort (on only after a drag ordered them by hand); New connection…, and Start or Stop embedded QuestDB; the new and reopened panel; the
Files tab's two windows, Configure ~/.claude folders and Configure repositories ([record 73](adr/0073-the-claude-folders-window-explains-and-creates-the-index.md)).
No list has a bar of buttons ([record 74](adr/0074-the-sidebars-lists-have-no-bar-of-buttons.md)): a row's menu edits (renames a panel),
clones and removes it (`ResourceActions`). Removing something in use asks **Stop and remove**, **Disconnect and remove**
or **Close and remove**, or **Leave it** ([record 37](adr/0037-session-actions-act-on-the-selected-row.md)).

- **Sessions:** name, where it is bound ("headless" when nowhere), status, current activity or what it waits on,
  pending approvals; directory and branch; `ctx` and the context % (purple from 60%), the Claude Code version the
  session runs (from its `system/init`; in gold with ↑ when another running session runs a newer one, as after an update
  of the CLI: each start runs the CLI installed then), how long it has run, a line only when one of them is known, so a
  disconnected row has its controls under its folder. Over the
  row's text the tooltip shows the details Copy details copies, the full session id among them. At the left a plug, fresh start, checkpoint, and while running compact and clear (`/compact`, `/clear`
  sent from the session's panel); at the right the model and effort as menus (a change saves and, on a running session,
  sends `/model` or `/effort`). A copy button at the row's top right under the mouse, Copy details in its menu and ⌘C
  copy name, path, branch, full id, status, uptime and start, context with tokens, model and effort, and the Claude Code version. Listed running
  first, then by name, until a row is dragged; the order then stands (saved in `sessions.json`) until **Sort: running first, then by name** in the Sessions tab's
  menu ([record 53](adr/0053-session-rows-compact-clear-copy-and-an-order-of-your-own.md), [record 74](adr/0074-the-sidebars-lists-have-no-bar-of-buttons.md)). Import lists stored
  conversations.
- **Panels:** comando's own panels, open and closed, with session, connection and file name. Clone copies text and
  connection, not the session. Removing deletes the panel's file; removing the last open panel leaves an empty one.
- **Connections:** name, user, host, port, database, status; the context menu binds it to the focused panel, connects
  or disconnects, tests, explores the schema, edits, clones and removes.
- A session row's context menu: Open in a panel, Start or Stop, Interrupt the turn while one runs, Compact and Clear
  the conversation while it runs, Model, Effort, Copy details, Edit…, Clone…, Remove….
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

Keys are written as macOS writes them. On other desktops each is the same with Ctrl for ⌘ (⌘⇧P is Ctrl+Shift+P), but
for two that would be another command's there: the lower tabs ⌃1–⌃4 are Alt+1–4, and ⌃⏎ is Ctrl+Alt+⏎. In the
terminal Ctrl with a letter, `[`, `\`, `]` or space goes to the shell, as ⌃ does on macOS; comando's other keys work there.
The palette, the shortcuts table and every text in the window name the desktop's own keys
([record 65](adr/0065-keys-on-desktops-other-than-macos.md)).

### 2.4 Where things land

SQL results fill the Results tab; an error is selectable text, and a position the database names is underlined in a
single-statement block until the next edit. A prompt's turn streams into the Session tab. ⌘⇧I appends what the panel's
last run produced as a block of its own: the selected rows (up to 500), else the first 50, else the error, as
tab-separated text in a fence with no blank line inside; a question typed on the next line belongs to the same block,
so ⌘⏎ sends rows and question together. A query of 10 s or more ends with a desktop notification naming its panel
while the window is not active; a live plot's refresh gives none.

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
| ⌘⇧J | the same, re-running the query each period (default 5 s) while it is not running; another run in the panel or a failed query or connection stops it with a notice; closing its tab stops it quietly; Actions ▸ Stop refreshing plots stops them all, the plots staying as they are |
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

**Actions ▸ Start embedded QuestDB** (also in the Connections tab's menu) starts a QuestDB server in `questdb/` of the data root, on QuestDB's default ports (PG wire 8812, console 9000,
ILP 9009). Every listener binds `127.0.0.1` and ILP over UDP is off, through `QDB_` environment overrides, because
QuestDB binds all interfaces by default and its default login is public. It stops at exit, after the lanes.

## 4. Development tools

Runs go through `ToolPort` (`tools` module); the terminal lives in the UI because JediTerm is a Swing component.

### 4.1 Terminal

JediTerm on a pseudo-terminal from pty4j, one per panel, started the first time it is shown, as `$SHELL -l` (else
`/bin/zsh`, else `/bin/sh`) in the session's directory, else home; `TERM=xterm-256color`, `TERM_PROGRAM=comando`. ⌃3
shows it; ⌃⏎ in the editor types the selection or line into it and presses Enter, waiting until a starting shell has
written and then been quiet for 200 ms (at most 3 s). An exited shell offers a restart bar; Actions ▸ Restart the shell
ends the focused panel's shell and starts a new one in the session's directory.

### 4.2 Gradle runs

⌥⌘G asks for tasks (default `build`, then the last used) and runs them in the build of the focused panel's session:
the nearest directory at or above its working directory with an executable `gradlew`.

- **Command:** `<kit>/bin/gradle-serial <root> <tasks>` (one run per checkout at a time, queued on the same lock as
  the kit's own runs), or `<root>/gradlew --console=plain <tasks>` when the kit's `gradle-serial` is not there or not
  executable; each run looks again. Arguments split like shell words, without expansion. Nothing configures it
  ([record 62](adr/0062-gradle-runs-by-convention.md)).
- **Output:** a console view filled as output arrives; past 4 MB the rest is held and its last 64 kB shown at the end.
  The same command again replaces an unpinned view and stops the old run.
- **Stop:** SIGTERM to the process tree, SIGKILL 2 s later; output is read at most 2 s after the process exits.
- **Tests:** JUnit XML written since the run started becomes a tree, failures first; a failure links to its source
  line, and "Send failures to Claude" sends messages and stacks (60 lines each) to the session of the panel the run
  came from.
- A run of 10 s or more ends with a desktop notification while the window is not active.

## 5. The Files tab

The folders the user chose, one top-level row each, as a navigator of their folders and files
([records 49, 51, 52, 55](adr/0055-the-files-tab-only-shows-folders-json-configures-it.md)). The tab only shows and
opens: it has no buttons, and writes nothing but the Trash of a deletable folder
([record 63](adr/0063-a-tabs-menu-closes-many-and-a-scratchpads-entries-go-to-the-trash.md)). The files it opens are
edited and saved like any other; a `.log` stays read-only.

- **Folders** are resources ([§1.1](#11-one-model-for-connections-sessions-and-folders)): a unique name, a full path,
  an optional index file inside it, names to leave out, the row's icon (folder, scratchpad, memory, knowledge,
  skills, plugin), whether its entries go to the Trash (`deletable`), the file name that lists them (`listedIn`), and
  whether it is a repository (`repository`).
  The kit's Scratchpad is deletable and listed in `INDEX.md`, its knowledge base indexed by `00_START_HERE.md`; the kit adds only folders that are missing, so a
  Scratchpad entry written before gains the two keys by editing `folders.json`. Without `folders.json` there is one folder, `~/.claude`, opening `CLAUDE.md` and leaving out Claude
  Code's transcripts, caches and per-session state; an emptied list stays empty.
- **Repositories.** The folders configured as repositories are rows of one node, Repositories (its own icon), after the
  other folders, which lie inside `~/.claude` ([record 71](adr/0071-repositories-in-the-files-tab.md)).
- **Configuring.** The Files tab's menu (a right click on its header) opens the `~/.claude` folders
  window (its title names Claude Code's folder wherever it is) or the repositories window (Configure repositories) ([records 69,
  71, 73](adr/0073-the-claude-folders-window-explains-and-creates-the-index.md)): the entries in the tab's order on the
  left (+ chooses a folder first and names it after it, − takes one out, ↑ ↓ or ⌥↑ ⌥↓ move it), the selected one's
  fields on the right, each field's name explaining it under the mouse. Each is checked as it is typed (a name used once
  in `folders.json`, a folder that exists and can be read, set only by choosing it: inside `~/.claude` for a folder, a
  git repository's top folder for a repository; an index inside it whose own folder exists, a listing file name); a
  field that fails is outlined in red and one line under the form says why, or notes what Save will do. A pick outside
  `~/.claude`, or a file outside the entry's folder, is not taken, and that line says so. The index file and the listing
  file are typed or picked (…); a picked listing file gives its name. Under that line, the git repository holding the
  folder: `repo: ~/.claude · master · 1 uncommitted`, or `repo: none`. A repository has a name, its folder, an index
  file and the names left out; a folder has every field. Save, on only when every entry passes, writes `folders.json`
  once, keeping the entries of the other window, and creates the index file a folder inside `~/.claude` names but does
  not have, with a title and one line on how it is kept; `/reflect` and `index-check` then keep one line per entry in
  it, and one per entry of a subfolder in that subfolder's listing file. Cancel or Esc drop the edits, asking first. The
  store checks `folders.json` every second: a change it did not write is read again and the tab follows; an entry that
  breaks the rules is left out with a notice; a file that does not parse keeps the tab's folders, says why and stays
  where it is. The kit adds its folders there too. A `readOnly` key from older versions is ignored.
- **Order.** At a folder's top level its index file comes first. Every level then lists folders not named after a date,
  date-named folders (`yyyy-MM-dd`) oldest first, then files, by name ignoring case. Hidden entries, `node_modules`,
  `build`, `target`, `__pycache__`, `out` and the folder's own exclusions are left out, and so is what git ignores in
  a repository (a folder git ignores as a whole shows all it holds); a link to a folder is listed, not followed.
- **Bounds.** 12 levels; at most 80 000 names listed and 20 000 entries kept per folder tree; past a bound the folder ends
  with `…`. An unreadable subfolder says why and the rest stands.
- **Clicks.** A click on a folder opens or closes it; on a file it selects. A double click opens a file in a panel (Enter
  opens a file); on a folder's row (a folder of `~/.claude` or a repository) it shows the row's changes instead and
  leaves the row open or closed as it was; on a subfolder it does nothing more than its first click
  ([record 78](adr/0078-a-rows-marks-and-double-click-show-its-changes.md)). A text file of at most 2 MB opens in a panel bound to it; a larger one (to 4 MB) or a binary one shows in the
  side pane. Opening a folder opens no file; the first time, its newest date-named folder opens too.
- **Menus.** A file's: Open in a panel, Edit in a panel (its text rather than rendered), and Compare with HEAD when it
  changed. A folder row's: its index file. A folder row's and a subfolder's: Show changes in it (off, as No changes in
  it, when it holds none): the Changes view of the repository holding it, `~/.claude` for a folder, inside it alone,
  its row named after the folder's row and the subfolders leading to it.
- **Trash.** In a deletable folder, a file's or subfolder's menu moves the selection (⌘- or ⇧-click for several) to the
  desktop's Trash after one question naming it; the folder's row never goes, and a selection reaching another folder
  offers nothing. Without a Trash nothing moves; nothing is deleted for good. With `listedIn` set, each entry moved
  leaves the nearest such file in its folder or above: its own lines go (a list item or table row whose first element
  is its path relative to that file, `READ-01-X.md`, `work/a.py`, a folder with its slash `work/`, with the lines
  indented under an item; never inside a code block), and the notice gives the lines that still name it. A panel on a moved file is removed as for any file deleted outside.
- **Sync.** Only folders whose rows are open are read: at once when opened, then every 5 s on `folder-poll`, sent only
  when something changed. A tree carries the settings it was read with and
  is dropped if the folder was edited since; reads are ticketed so an older one never overwrites a newer one. The tree
  keeps what the user opened and selected.
- **Git.** In a git repository a file that differs from `HEAD` takes git's colour for how (changed blue, added green,
  untracked in the gold chosen beside the Session tab's Clear, in conflict red), and a folder that holds one is blue
  ([record 83](adr/0083-one-gold-chosen-beside-clear.md)). A new (untracked) file and every folder on the
  way down to it carry a gold dot after the name, so the closed folders to open are marked from the folder's row down.
  Once the new file is opened (in a panel or the side pane, from any place) it reads as any other file, without gold
  or dot, and counts for its folders no more; comando keeps the opened files in `opened-files`
  ([record 76](adr/0076-new-files-are-gold-and-marked-from-the-top.md)). A repository's top folder (a folder row or a subfolder) has the
  git icon (under Repositories, where every row is one, a plain folder), and a rising arrow after its name while the
  repository has changes; hovering it says its branch (or a detached `HEAD`) and what it holds uncommitted, by kind
  ("On main · 3 uncommitted: 2 modified, 1 untracked"). A folder's row inside a repository (KB, Memory, any folder
  of `~/.claude`) has the same arrow while what it holds differs from `HEAD`, and says so for what it holds ("On master
  · 2 uncommitted here: 1 modified, 1 untracked"); a row holding both a change and a new file not opened yet shows the
  arrow, then the dot. A double click on a row opens the Changes view of what it holds: a repository's row all of the
  repository, a folder's row its folder only (KB's shows `KB/`, not the whole of `~/.claude`); with nothing to show,
  or no repository holding the folder, a balloon says so ([record 78](adr/0078-a-rows-marks-and-double-click-show-its-changes.md)).
  The open folders' repositories are read with
  them every 5 s, the others every 30 s ([record 68](adr/0068-git-changes-and-a-side-by-side-diff.md)).
- **Filter.** A field under the tree finds entries by name across every folder, reading never-read folders once; a
  second tree shows the matches with the folders leading to them; Esc returns to the tree as left. The palette's Files
  command puts the keyboard there.

| Entry | Icon |
|---|---|
| a folder row | its icon setting: folder; a pencil, chip, book, bolt or puzzle piece on it |
| Repositories; a repository's row | two folders with git's mark; a folder |
| a folder; `work` | folder; folder with a gear |
| `*plan*.md`; `READ-*`; an index file name; Markdown | plan; book; index; Markdown |
| code; data (`.json`, `.yaml`, …); shell; `.sql`; `.csv`; images; diffs; `.txt`, `.log` | code; braces; terminal; database; table; image; diff; text |

### 5.1 Changes

The Changes view, always titled Uncommitted changes, in the side pane, lists what the repositories of the Files tab's
folders hold that their last commits do not, read only, as IntelliJ's commit view does
([record 68](adr/0068-git-changes-and-a-side-by-side-diff.md)). ⌘K opens it on every repository, and closes it when it
shows them all; a repository's double click in the Files tab shows that repository alone, a folder's menu the changes
inside that folder alone ([record 71](adr/0071-repositories-in-the-files-tab.md)), each replacing what the view showed:

- **Repositories.** The one holding a folder, else those directly inside it; one row each, with its branch and its
  count, however many folders share it. Read every 5 s while the view is open, with the `git` command line
  (`status --porcelain=v2`, no optional locks, 10 s per run); a repository that cannot be read says why.
- **Rows.** Up to three groups, each with its icon (a folder with a pencil, a plus or a minus): Changed (the working
  tree and the index together against `HEAD`: modified, added, renamed, copied, in conflict), Untracked (new files
  git does not track) and Removed (deleted), each by folder, a chain of single folders in one row; a file is named in its colour, with how it changed and the path it had when renamed.
  At most 2 000 changes per repository. New rows come open; what the user closed and selected stays.
- **Comparing.** A double click or Enter on a file, or Compare with HEAD in its menu, opens it side by side with its
  `HEAD` version in one window floating over comando's, reused for every file; moving the selection moves an open
  window along without taking the keyboard. Lost lines are red, gained lines green, the words that changed inside a
  changed stretch marked; a band joins each difference across; scrolling one side scrolls the other to the matching
  line. F7 and ⇧F7 go from difference to difference, Esc closes. While it shows, the file is read again every 3 s, so
  a session's edits show where the user scrolled to. The line match stops after 2 s and the texts are then compared as
  a whole; line ends are compared as git commits them (`core.autocrlf`), a link as its target. A binary file, a version
  over 4 MB, a line over 10 000 characters, a Git LFS file or a submodule is not compared, and the window says why. A
  terminal with the keyboard keeps ⌘K, its Clear Buffer. The comparison takes the editors' theme; its ◑ button is the
  panels' one, and switches every editor.
  The file's menu also opens it in a panel.
- **Editing** ([record 72](adr/0072-the-comparison-window-edits-rolls-back-and-removes.md)). The working tree's side is
  an editor, the only one of the two: the differences are worked out again 250 ms after typing pauses, and ⌘Z and ⌘⇧Z
  (⌘Y) go back and forth through every edit made there since the file was shown; the window has no buttons for them. The
  text is written every second while it has edits, on ⌘S, and on leaving the file: another file, the window closing,
  comando quitting (waiting up to 2 s). Beside the working tree's title, "● edited" (gold) shows while edits wait, "✓
  saved" (green) once they are written, "✗ not saved" (red) when a write fails, which a notice explains and the next
  second tries again; on macOS the window's close button carries a dot meanwhile. A read of the file while it has
  unsaved edits is not shown; a panel open on the same file takes the saved text as any change made outside it. A
  removed file stays removed while its text is empty. Links, binary files and files not compared are not edited.
- **Markdown.** A `.md` file's window offers both sides rendered (its eye button, ⌘⇧M), with the renderer an editor
  panel's preview uses, scrolled together by the share of their length; editing happens on the texts. What changed is
  shaded as in the texts: a block (paragraph, heading, list item, table cell, code block, diagram) whose source lines
  differ is red on `HEAD`'s side and green on the working tree's, and the words that changed inside it more strongly.
- **Rolling back and removing.** The working tree's right-click menu offers both. Roll back to HEAD gives a changed or
  removed file the window edits `HEAD`'s text as one edit that undo takes back, saves it and unstages the file. Any
  other file goes back through git after a question, its version to the Trash first: an untracked file goes no further;
  an added or copied one leaves the index (`git rm --cached`); a renamed one leaves the index and its old path comes
  back; the rest take `git restore --source=HEAD --staged --worktree`. Move to the Trash… moves the file to the Trash after a question, and out of the index
  when `HEAD` does not have it. In the view, the menu of the selection (⌘- or ⇧-click for several) rolls back or removes
  every file in it, a folder's, a group's or a repository's row standing for every file under it, after one question.
  Without a Trash nothing changes. Edit ▸ Undo, Redo, Cut, Copy and Paste act on the window while it has the keyboard.

## 6. Persistence

### 6.1 The data root

`-Dcomando.home`, else `COMANDO_HOME`, else `~/.comando`.

| Path | Holds |
|---|---|
| `connections.json`, `sessions.json`, `folders.json`, `panels.json` | the specs and panel metadata (bindings, file name, closed) |
| `panels/<title>.md` | each panel's text ([record 26](adr/0026-a-file-per-panel.md)) |
| `comando.lock` | held while comando runs; a second start on the same root refuses |
| `run/` | MCP configurations of running sessions (`0600`) |
| `audit/audit-<UTC date>.jsonl` | every MCP tool call and approval decision |
| `logs/comando-<n>.log` | the log, 10 files of 1 MiB; Actions ▸ Show the log opens it read-only, a click on a notice that names an entry opens it at that entry |
| `logs/intents-<millis>.jsonl` | the intent log, when recording |
| `help/tutorial.md` | the tutorial, rewritten from the build's copy when it differs |
| `opened-files` | the files opened in comando, one path per line, at most 20 000: a new (untracked) file listed here is no longer marked in the Files tab ([record 76](adr/0076-new-files-are-gold-and-marked-from-the-top.md)) |
| `cache/mermaid/` | diagrams drawn by `mmdc`; safe to delete |
| `cache/images/` | SVG pictures named in Markdown, drawn to PNG; safe to delete |
| `kit/` | comando's copy of its Claude Code kit, made equal to the build's at start ([record 50](adr/0050-comando-ships-a-claude-code-kit.md)) |
| `questdb/` | the embedded QuestDB's data |

Outside it: connection passwords in the macOS keychain for the default data root (elsewhere, or off macOS, they stay
in `connections.json`; [Claude §3.3](claude.md#33-secrets)); window layout and recent
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
| ⌘O, or a double click in the Files tab | opens the file in a panel titled after it; a file of `panels/` bound by name and titled without `.md`, any other bound by absolute path and titled with its extension (`reviewer.md`), so it never takes a session's or a panel's name; when that title is taken, the folder follows it (`README.md · docs`), then a number; a file already open brings its panel forward ([record 77](adr/0077-a-files-panel-keeps-its-extension.md)) |
| edited outside comando | checked once a second; reloads into its panel when the panel has no unsaved edits (the caret stays, ⌘Z restores) ([record 27](adr/0027-panels-reload-files-changed-outside.md)); otherwise the next save moves the outside version aside as `<name> (outside comando <date time>).md` |
| deleted outside comando | the panel is removed, unless it has unsaved edits, which its save writes back ([record 34](adr/0034-a-deleted-document-closes-its-panel.md)) |
| a file of `panels/` no panel names, at start | becomes a closed panel, with a notice |
