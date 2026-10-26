# 92. A connection row is its icon and its name, as a session's

| Date | Status | Decided by |
|---|---|---|
| 2026-10-26 | proposed | Miguel ("the connections tab items should follow a similar pattern as the session's minus the summary in the tooltip"), Claude (the icon's states, the card's facts, Copy details, the shared row) |

## 1. Context

A session row ([record 90](0090-a-session-row-is-its-icon-and-name.md)) is its state icon, which a click connects or
disconnects, and its name; its card shows over the name in place of a tooltip, and its menu does the rest. A connection
row was two lines: a coloured dot and the name, the host and database under it, the same database icon in every state,
and a tooltip with the address or the error. It could be connected only from its menu.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the row | one line: the state icon, which a click connects or disconnects, and the name; a red "prod" for a production database; the name in the accent colour while the focused panel uses it | as a session's |
| the icon | the database glyph in every state, the same stroke so the same extent: blue while connected, grey at 75% while disconnected, the blue at 40% while it connects or disconnects, red when it failed; its tooltip says the state and what a click does ("Disconnected; click to connect", "Failed: ‹error›; click to connect") | as a session's: one glyph at one size |
| the card | over the name, after the tooltip's delay, in place of a tooltip: the name as its title, then State, Database ("qdb on localhost:8812"), User, TLS, Production ("yes; Claude's writes are refused"), Panels (the open panels that use it), each only when known; no summary, no tickets, no buttons; as wide as its facts; it follows the connection's state and closes when the connection is removed | Miguel: the session's card without the summary; a connection has no conversation to summarise |
| a double click on the name | binds the connection to the focused panel, as before | unchanged |
| the menu | as before, plus Copy details; ⌘C copies the selected connection's details too | as a session's; never the password |
| the code | `IconRow` holds what the two rows share (the icon, the name, finding the icon under a click); `SessionRow` and `ConnectionRow` extend it; `TabCard.showFacts` shows a card without its summary | one way of doing it |

## 3. Consequences

`CardFacts.of(ConnectionState, panels)` builds the card's facts; the Workspace's `rowCards()` (was `sessionCards()`)
shows both rows' cards under the key `connection:‹name›` for a connection. Tested in `SessionsSidebarTest` (the row,
the icon's states and tips, a click connecting and disconnecting, a double click binding, the card resting and leaving,
Copy details without the password), `WorkspaceTest` (the card's facts, no summary, following the state, closing on
removal, a session's card after it with its summary), and off screen in `SessionCardUiTest` (`:app:uiTest`), which
paints `build/snapshots/connection-card.png` and `connection-rows.png`.
