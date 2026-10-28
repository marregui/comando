# 116. A panel's connection is one toggle and its name, first of the tools

| Date | Status | Decided by |
|---|---|---|
| 2026-10-28 | accepted | Miguel and Claude |

## 1. Context

A panel's header showed at its left a chip (a database icon, the connection's name and a status dot: ○ ● ◐ ✕) and a
plug that connected or disconnected; its tools sat at the right. Miguel: the dot's meaning is not evident, and the
connection should be one toggle, as the embedded server's ([record 114](0114-help-menu-and-corner-buttons.md)), for
one connection, with the tools at the right and tooltips that say how to proceed.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the toggle | one icon, the database in its state's colour: `database` open, `database-starting` opening or closing, `database-failed` failed, `database-off` closed; a click connects or disconnects; with no connection bound, a click opens the menu that binds one | the state and the action in one place, as the sidebar's icons |
| binding | the connection's name beside the icon, with ▾; a click opens the bind menu (another connection, Unbind); hidden while none is bound | one click away, and the name says which connection the toggle is for |
| where | first of the tools at the right; the header's left is empty | Miguel's choice |
| tooltips | say the name, the state and what a click does ("local disconnected; click to connect; its name binds another"), the error after a ·; at most 80 characters, a name at most 24 | one short line (`UiKeysTest`) |
| the dot | removed | its meaning was not evident |

## 3. Consequences

`EditorPanelHeaderTest`: unbound, the toggle says how to bind and no chip shows; bound, the icon follows closed, open and
failed, the name sits beside it, both first in the tools' row, and clicks send `OpenConnection` then `CloseConnection`.
`UiKeysTest` keeps every tooltip to one line of at most 80 characters.
