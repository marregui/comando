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

The form has one field type per attribute kind: text, secret, integer, decimal, boolean, choice, directory, directory list.

## 2. Panels

An editor panel is a tab in the workspace with an id, a title, its text, and two bindings: one connection and one session, each by name; either may be empty. Several panels may bind one session or one connection.

- The panel header shows the connection and the session as chips with their status dot, the session's git branch and whether a turn is running, with a button each to connect or disconnect, start or stop. A production connection frames the panel in red.
- Running SQL in a panel uses its connection, opening it when closed. Sending a prompt uses its session, starting it when stopped.
- Renaming a connection or a session renames every binding; a running session is stopped before it can be renamed. Removing one closes it first and unbinds every panel.
- The lower panel follows the focused panel: its last SQL run, its session's transcript, its terminal. The Approvals tab lists every session's requests.

## 3. The sidebar

The sidebar is the switch surface, because sessions change many times a day while connections are configured rarely.

- **Sessions:** name, working directory, branch, status, cost, time since the last activity, pending approvals. Double-click binds the session to the focused panel; the context menu binds, starts or stops, interrupts the turn, edits or removes it. The import button lists stored conversations from `~/.claude/projects`; a title equal to an existing session's name gets a number.
- **Connections:** name, user, host, port and database, status. Double-click binds; the context menu binds, connects or disconnects, tests, explores the schema, edits or removes.
- From the keyboard, the palette's "Bind a session to the panel…" and "Bind a connection to the panel…" do what a double-click does.
- ⌘B hides and shows the sidebar.

## 4. Persistence of bindings

`panels.json` holds each panel's id, title, bindings and text. At start, a binding to a name that no longer exists is dropped. Sessions come back closed, with their transcripts loaded from Claude Code's files ([Persistence](persistence.md)).
