# 90. A session row is its icon and its name; the card says each fact once

| Date | Status | Decided by |
|---|---|---|
| 2026-10-25 | proposed | Miguel ("the sessions item is not usable. I would much rather have the icon and everything else in the context menu"; the icon still connects; "I hate the default icon, it looks bigger"; the chosen gold for "a more premium look", then Claude's orange back: "when it was claude's icon it looked nice, now it looks like we are in a bazar"; "in the tooltip there is redundant information and it is rendered in a dull non premium way"), Claude (where the checkpoint signal goes, the glyph, the card's facts, holding the order under the pointer) |

## 1. Context

A session row (record 89) held, on one line, the state icon, the name, fresh start, checkpoint, compact, model ▾,
effort ▾ and a copy button under the mouse; in a 260 px sidebar they crowded the name. The stopped icon was the running
one's outline: its stroke drew outside the filled shape, so it read larger. The card (record 87) repeated itself: the
status line named the panel, its connection and its file; the details named the session again, its path, its status,
and "Context: unknown until a turn ends" before any turn.

Starting a session under the pointer moved it: the list sorts running first, then by name. On 2026-10-25 "comando
improvement", second in the list, was started from its icon at 17:54:44; it went to the top, "comando developer" took
its place under the pointer, and a click there at 17:54:55 started it too (comando's log; `sessions.manualOrder` false).

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the row | the state icon, which a click connects or disconnects, and the name with its marks; nothing else | the name gets the row |
| the marks | an amber ↑ for an older Claude Code; the context in purple ("72%") once the checkpoint is due; ⚑n for approvals waiting | the purple checkpoint button was the only sign of a due checkpoint |
| the menu | Open in a panel; Start or Stop, Interrupt; Start a new conversation (when there is one to leave); Checkpoint ("Checkpoint, due" with the purple icon); Compact (running); Rewrite; Model: ‹family› ▸ and Effort: ‹level› ▸, each naming what the session runs at; Copy details; Edit, Clone, Remove. Each item keeps the tooltip its button had | everything the row held, one place |
| the icon | Claude's mark, `claude.svg` as it was, the glyph for every state, filled, so every state has the same extent: Claude's orange while it runs, grey at 75% while stopped, the orange at 40% while it starts or stops, red when it failed. A gold mark and a twelve-ray glyph were tried the same day: gold beside the orange marks of the panel header and the menus made the window look like "a bazar" | one size; one Claude colour wherever Claude is named |
| the order | while the pointer is on the session list a session that starts or stops keeps its row, its state shown; a new session goes last, a removed one goes; the order applies when the pointer leaves | the row under the pointer stays the one it was on |
| the card | its title, the panel's or the headless session's name, in the headings' serif along the gold's ramp; then facts, a quiet label and the value in the transcript's ivory, each once and only when known: State (running, starting, stopping, stopped or failed with its error; what it does; uptime; "no panel" for a headless session), Model (the id reported, else the family) · effort, Context (once a turn reported it; "checkpoint due"), Folder · branch, Conversation (the id's first 8 characters), Claude Code (while it runs, with the older-CLI note), Session (when the title does not name it), Connection, File (not for a session's own panel); then Summary as before | the title says the name; no fact repeats another or the icon |
| Copy details | unchanged: every fact, the full id, for a clipboard | the card is read, the copy is pasted |

## 3. Consequences

Amends record 89 (the row's controls, the stopped icon's outline) and record 87 (the card's status line and details).
`SessionRow.Control` is `POWER` alone; the row's hover state and copy button are gone; `CardFacts` builds the card's
title and facts; `TabCard` shows them in a grid. Tested in `SessionsSidebarTest` (the menu's items and what they send,
the order held under the pointer and applied when it leaves, the purple context mark), `WorkspaceTest` (the facts of a
session panel, a headless session and a plain panel), and off screen in `SessionCardUiTest` (`:app:uiTest`), which paints
the card to `build/snapshots/session-card.png` and the row to `session-rows.png`.
