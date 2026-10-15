# Resources and panels

## 1. One model for connections and sessions

A database connection and a Claude session have the same life: configured, stored, tested or started, opened, bound to panels, checked, closed. comando writes that life once as a resource kind and has two instances of it.

| Layer | Written once | Connection | Session |
|---|---|---|---|
| Spec (`core.model`) | a record validated in its constructor | `ConnectionSpec`: name, host, port, database, user, password, production flag, TLS mode | `SessionSpec`: name, working directory, model, effort, permission mode, extra directories, budget, conversation id, next start mode (new, resume, fork) |
| Kind (`core.resource`) | `ResourceKind<S>`: attributes, spec to and from a form, template, clone | `ConnectionKind` | `SessionKind`: cloning a session that has a conversation makes the clone fork it |
| Live state (`core.state`) | status closed, opening, open, closing, failed, with the last error | `ConnectionState` | `SessionState`, plus pending turns, cost, context, activity, branch |
| Dialog (`ui.dialogs.resource`) | `ResourceDialog<S>`: the form of one resource (stored, new, or an unsaved copy, as the caller asks), generated from the attributes, with Test, Connect or Start, Assign to panel, Save; the sidebar adds, clones and removes ([record 40](../adr/0040-one-resource-per-dialog-and-two-tab-rows.md)) | Connections… (⌘⇧O) | Sessions… (⌘⇧K), plus Import… |
| Store file | atomic JSON | `connections.json` | `sessions.json` |

The form has one field type per attribute kind: text, secret, integer, decimal, boolean, choice, directory, directory list. A muted hint sits under each field; a session's conversation id and next start mode sit under a collapsed "▸ Advanced" that opens when an id is set. Choice fields are pick-only, except the model; choosing a new start for a session with a stored conversation says that conversation will not be continued. Folder pickers are the native dialog on macOS; a new session's directory starts at the focused panel's session directory, else the last one used in the dialog.

The dialog works from the keyboard and never drops an edit without asking:

| Action | Behaviour |
|---|---|
| ⏎ | saves (Save is the default button) |
| ⌘⏎ | saves when needed, then assigns to the focused panel |
| Esc, or the window's close button | closes; with unsaved changes it asks "Discard changes to X?" inside the dialog, so a reopened dialog never shows an edit that was not saved |
| the sidebar opening the dialog on another resource, a new one or a copy | with unsaved changes, the same question first; Keep editing cancels the opening; opening it on the resource being edited keeps the edits |
| Assign, Connect, Start | save a new or changed form first and say so ("Saved and connecting"); an invalid form shows the reason in red and does nothing |
| **+** in the sidebar, or ⌘⇧K / ⌘⇧O on a panel without a session / connection | a new form named "new connection" or "new session"; the keyboard is in Name, its default selected to type over; the title says "New session" until Save |
| Test | tests the form as typed, saved or not |

The outcome of Test, Connect, Start and Stop shows on the line under the buttons, red on failure, from the notices about that resource that arrived after the click.

## 2. Panels

An editor panel is a tab in the workspace with an id, a title, its text, and two bindings: one connection and one session, each by name; either may be empty. Several panels may bind one connection; a session has at most one panel, and a session with none is headless ([record 38](../adr/0038-a-session-has-one-panel.md)):

| Action | Result |
|---|---|
| open a session: double-click in the sidebar, Open in a panel, Panel ▸ Session ▸ Open a session…, the dialog's Assign to panel, the header chip of a panel without a session | the panel that has it comes forward; else the focused panel (or the chip's) takes it when it has no session; else a new panel opens, titled after the session (`edge 2` when `edge` is taken), with the focused panel's connection |
| Detach, in the header chip of a panel with a session | the session keeps running headless; the panel keeps its text and can open another |
| a new panel (⌘T, a file opened as a panel) | the focused panel's connection and no session |
| a closed panel | keeps its session: opening the session reopens it; binding the session elsewhere takes it from the closed panel |
| the start | a session bound to several panels by an older comando keeps its first; a notice names the panels detached |

- The panel header shows the session, then the connection, as chips with their status dot, the session's git branch and whether a turn is running, with a plug each, green while running or connected and grey while not, whose click starts or stops, connects or disconnects. The status bar's right side keeps the same order: the session, then the connection. A production connection frames the panel in red.
- Running SQL in a panel uses its connection, opening it when closed. Sending a prompt uses its session, starting it when stopped.
- Renaming a connection or a session renames every binding; a running session must be stopped first, and the dialog refuses the rename until it is. Removing one closes it first and unbinds every panel.
- The lower panel follows the focused panel: its last SQL run, its session's transcript, its terminal. The Approvals tab lists every session's requests. Results and Session get a dot when something arrived that the user has not seen.
- Each tab's icon shows its panel's status, the first that applies ([record 42](../adr/0042-tabs-show-their-sessions-state.md)): a question mark while the session has a request waiting (until answered; looking does not clear it); the red error mark when the last turn failed or the session died while another tab was in front; the green check when a turn or query finished while another tab was in front; a spinner while a turn or query runs; an hourglass while no turn runs but background work the session started (an agent, a shell command) has not ended ([record 43](../adr/0043-a-session-waiting-on-background-work-shows-it.md)); the Claude mark for a bound session with nothing going on; the file icon with no session. The first three put a "● " before the title; the mark clears when the user looks at the tab or new work starts. The tooltip says why: the number of requests, the failure, what the session does and for how long, or what it waits on and since when. A turn that hands work to the background marks nothing finished, nor does the work's return until its reading turn starts (at most 60 s); the reading turn does. A later turn that ends while older background work runs on is finished as usual. A hand-off turn that fails shows its error mark. A tab that comes to need the user bounces the Dock icon while the window is not active; a finished turn gets the desktop notice instead. The spinner's timer runs only while a tab spins and repaints the spinning tabs only.
- ⌘W, or a tab's close button, closes the panel. A panel of comando's own is kept, closed, with its file, title and bindings, until it is removed or its file is deleted outside comando; a document (a file opened from elsewhere) is dropped ([record 39](../adr/0039-panels-are-kept-until-removed.md)). ⌘⇧T reopens the last of the ten most recently closed tabs after the focused panel; the Panels tab reopens any closed panel. File ▸ Open file… (⌘O) opens any file: one of `panels/` that a closed panel has reopens that panel ([Persistence](persistence.md#5-panel-files)). Closing asks first when the panel's shell runs a job in the foreground, when the shell was replaced by a program (`exec ssh …`), or when comando cannot check ([Development tools](dev-tools.md#1-terminal)); a background helper of the shell, such as a prompt's git daemon, does not count. Tab titles are clipped to 32 characters; the tooltip has the full title and bindings.
- Importing exactly one stored conversation binds it to the focused panel when that panel has no session.

## 3. The sidebar

The sidebar is the left column: four tabs at the top in two fixed rows, **Panels** and **Scratchpad**, then **Sessions** and **Connections**; a click selects a tab and moves nothing ([record 40](../adr/0040-one-resource-per-dialog-and-two-tab-rows.md)); the tab in front is kept across restarts ([record 28](../adr/0028-the-sidebar-has-three-tabs.md), [record 39](../adr/0039-panels-are-kept-until-removed.md)). Sessions, Panels and Connections are the switch surface; each has the same bar of buttons at its top. Scratchpad navigates the scratchpad's folders and files; one click on a file opens it in a panel ([Development tools](dev-tools.md#4-the-scratchpad)).

- **Sessions:** name, where it is bound (the titles of its panels, the focused one first and in the accent colour when the focused panel is one of them; "headless" when no panel binds it), working directory, branch, status, what it is doing while a turn runs or what it waits on while background work runs, pending approvals; under them a row of controls: the plug, green while the session runs and grey while it does not, whose click stops or starts it, and the model and the effort as menus (a choice saves the session and, when it runs, sends `/model` or `/effort` to it). Above the list, on the left: **+** opens the dialog on a new session, and import lists stored conversations from `~/.claude/projects` (a title equal to an existing session's name gets a number); on the right: edit, clone and remove act on the selected session and are off while none is selected. Remove on a running session asks **Stop and remove** or **Leave it** ([record 37](../adr/0037-session-actions-act-on-the-selected-row.md)). Double-click opens the session in a panel (the table above); the context menu opens it, starts or stops it, interrupts the turn, sets the model or the effort, edits, clones or removes it.
- **Panels:** comando's own panels, open and closed, by title, without documents: open (● in the accent colour, "· focused" for the focused one) or closed, then the session, the connection and the file name. Above the list: **+** a new panel; edit (rename), clone (a new panel with the text and connection, not the session) and remove of the selected panel. Remove asks, naming the file it deletes and that the session keeps running; an open panel asks **Close and remove** or **Leave it**; removing the last open panel leaves an empty one in its place. Double-click reopens a closed panel or brings an open one forward; the context menu opens, renames, clones or removes.
- **Connections:** name, user, host, port and database, status. Above the list, the same buttons as for sessions, without import: **+** on the left; edit, clone and remove of the selected connection on the right; Remove on an open connection asks **Disconnect and remove** or **Leave it**. Double-click binds; the context menu binds, connects or disconnects, tests, explores the schema, edits, clones or removes.
- Remove in either context menu asks first, with the same question as the dialog. An empty list says how to add the first item.
- From the keyboard, the palette's "Open a session…" and "Bind a connection…" do what a double-click does.
- ⌘B, or the `⋯` button at the end of the tab strip, hides the sidebar; it collapses to a rail on the window's left edge whose `⋯` button shows it again at the width it had. While hidden there is no divider to drag. The split keeps both sides visible and sets the divider to the rail's width, because Swing's split pane leaves a hidden side's width unclaimed when the split's own size changes in the same layout.

## 4. Persistence of bindings

`panels.json` holds each panel's id, title, bindings and file name; the text is in the panel's own file under `panels/` ([Persistence](persistence.md#5-panel-files)). At start, a binding to a name that no longer exists is dropped. Sessions come back closed, with their transcripts loaded from Claude Code's files ([Persistence](persistence.md)).
