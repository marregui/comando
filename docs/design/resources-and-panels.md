# Resources and panels

## 1. One model for connections and sessions

A database connection and a Claude session have the same life: configured, stored, tested or started, opened, bound to panels, checked, closed. comando writes that life once as a resource kind and has two instances of it.

| Layer | Written once | Connection | Session |
|---|---|---|---|
| Spec (`core.model`) | a record validated in its constructor | `ConnectionSpec`: name, host, port, database, user, password, production flag, TLS mode | `SessionSpec`: name, working directory, model, effort, permission mode, extra directories, budget, conversation id, next start mode (new, resume, fork) |
| Kind (`core.resource`) | `ResourceKind<S>`: attributes, spec to and from a form, template, clone | `ConnectionKind` | `SessionKind`: cloning a session that has a conversation makes the clone fork it |
| Live state (`core.state`) | status closed, opening, open, closing, failed, with the last error | `ConnectionState` | `SessionState`, plus pending turns, cost, context, activity, branch |
| Dialog (`ui.dialogs.resource`) | `ResourceDialog<S>`: list with status, Add, Clone, Remove, a form generated from the attributes, Test, Connect or Start, Assign to panel, Save | Connections… (⌘⇧O) | Sessions… (⌘⇧K), plus Import… |
| Store file | atomic JSON | `connections.json` | `sessions.json` |

The form has one field type per attribute kind: text, secret, integer, decimal, boolean, choice, directory, directory list. A muted hint sits under each field; a session's conversation id and next start mode sit under a collapsed "▸ Advanced" that opens when an id is set. Choice fields are pick-only, except the model; choosing a new start for a session with a stored conversation says that conversation will not be continued. Folder pickers are the native dialog on macOS; a new session's directory starts at the focused panel's session directory, else the last one used in the dialog.

The dialog works from the keyboard and never drops an edit without asking:

| Action | Behaviour |
|---|---|
| ⏎ | saves (Save is the default button) |
| ⌘⏎ | saves when needed, then assigns to the focused panel |
| Esc | closes; with unsaved changes it asks "Discard changes to X?" inside the dialog |
| another row, Add, Clone, or the sidebar opening another resource | with unsaved changes, the same question first; Keep editing returns to the edited row; opening the dialog on the resource being edited keeps the edits |
| Assign, Connect, Start | save a new or changed form first and say so ("Saved and connecting"); an invalid form shows the reason in red and does nothing |
| Add, or opening the dialog with no resource yet | a new form named "new connection" or "new session"; the keyboard is in Name, its default selected to type over |
| Test | tests the form as typed, saved or not |
| Remove | asks first and names what applies: stops it, denies its waiting approvals, unbinds its panels, deletes its saved password, and that an imported conversation can be imported again; the form then shows the next resource or a new one |

The outcome of Test, Connect, Start and Stop shows on the line under the buttons, red on failure, from the notices about that resource that arrived after the click.

## 2. Panels

An editor panel is a tab in the workspace with an id, a title, its text, and two bindings: one connection and one session, each by name; either may be empty. Several panels may bind one session or one connection.

- The panel header shows the connection and the session as chips with their status dot, the session's git branch and whether a turn is running, with a button each to connect or disconnect, start or stop. A production connection frames the panel in red.
- Running SQL in a panel uses its connection, opening it when closed. Sending a prompt uses its session, starting it when stopped.
- Renaming a connection or a session renames every binding; a running session must be stopped first, and the dialog refuses the rename until it is. Removing one closes it first and unbinds every panel.
- The lower panel follows the focused panel: its last SQL run, its session's transcript, its terminal. The Approvals tab lists every session's requests. Results and Session get a dot when something arrived that the user has not seen; a panel whose query finished while another panel was in front gets a "● " before its title and a notice.
- ⌘W, or a tab's close button, closes the panel; ⌘⇧T reopens the last of the ten most recently closed, after the focused panel, on its file when that is still there, else with its text, and with its title and the bindings that still exist. Panels ▸ Open spec… (⌘O) opens any panel file as a panel ([Persistence](persistence.md#5-panel-files)). Closing asks first when the panel's shell runs a job in the foreground, when the shell was replaced by a program (`exec ssh …`), or when comando cannot check ([Development tools](dev-tools.md#1-terminal)); a background helper of the shell, such as a prompt's git daemon, does not count. Tab titles are clipped to 32 characters; the tooltip has the full title and bindings.
- Importing exactly one stored conversation binds it to the focused panel when that panel has no session.

## 3. The sidebar

The sidebar is the left column: three tabs at the top, **Sessions**, **Connections** and **Scratchpad**, on one row that never wraps; the tab in front is kept across restarts ([record 28](../adr/0028-the-sidebar-has-three-tabs.md)). Sessions and Connections are the switch surface; each has its buttons at the top right of the tab. Scratchpad is the scratchpad's documents as a tree ([Development tools](dev-tools.md#4-the-scratchpad)).

- **Sessions:** name, where it is bound ("· this panel" in the accent colour for the focused panel's session, else the titles of its panels), working directory, branch, status, cost, time since the last activity, pending approvals. Double-click binds the session to the focused panel; the context menu binds, starts or stops, interrupts the turn, edits or removes it. The import button lists stored conversations from `~/.claude/projects`; a title equal to an existing session's name gets a number.
- **Connections:** name, user, host, port and database, status. Double-click binds; the context menu binds, connects or disconnects, tests, explores the schema, edits or removes.
- Remove in either context menu asks first, with the same question as the dialog. An empty list says how to add the first item.
- From the keyboard, the palette's "Bind a session to the panel…" and "Bind a connection to the panel…" do what a double-click does.
- ⌘B, or the `⋯` button at the end of the tab strip, hides the sidebar; it collapses to a rail on the window's left edge whose `⋯` button shows it again at the width it had. While hidden there is no divider to drag. The split keeps both sides visible and sets the divider to the rail's width, because Swing's split pane leaves a hidden side's width unclaimed when the split's own size changes in the same layout.

## 4. Persistence of bindings

`panels.json` holds each panel's id, title, bindings and file name; the text is in the panel's own file under `panels/` ([Persistence](persistence.md#5-panel-files)). At start, a binding to a name that no longer exists is dropped. Sessions come back closed, with their transcripts loaded from Claude Code's files ([Persistence](persistence.md)).
