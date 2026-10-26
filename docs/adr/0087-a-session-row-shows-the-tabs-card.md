# 87. A session row shows the tab's card, with the session's details

| Date | Status | Decided by |
|---|---|---|
| 2026-10-25 | proposed | Miguel ("the tooltip in the sessions tab, for the session, contains a lot of information that I do not have available in the tooltip I love … I would like the tooltip in the sessions panel to have the functionality of the one I love … they both are in fact the same … same code; when the haiku is not present, it gets produced"), Claude (which card a row shows, where the details go) |

## 1. Context

A panel tab shows a card (record 81): its status line, a summary Haiku writes, the Linear tickets the panel names, ↻
and ✕. A session row in the sidebar showed a plain tooltip with the session's details, the lines Copy details copies:
path, branch, session id, status, uptime, context, model and effort, the Claude Code version. Each had what the other
lacked.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| one card | the tab card is the only card; a session row shows it in place of its tooltip, from the same code: the same wait, the same reading of the summary (read when missing or when what it is read from changed, ↻ to read again), the same closing (off the row without going into the card, ✕, a click) | "the same code" |
| what it shows | the status line; for a session, its details as Copy details copies them (`SessionRow.details`), one fact a line, in grey; then Summary, the tickets, ↻ ✕ | the row's information, which the tab card lacked, on both |
| a row's card | the card of the session's panel: the focused panel when it is the session's, else its first open one (the rule of the Rewrite item, record 86); its summary is that panel's, read once for both | one summary per panel, the same wherever it is shown |
| a headless session | a card of its own, keyed by the session, its summary read from the end of its conversation headed by where it works (`TopicSource.ofSession`, no panel named); its status line "‹name› · headless" and what it does | a session without a panel still has a topic |
| a panel without a session | the card as before, without details | nothing to detail |
| a row's controls | keep their one-line tooltips | the card is about the session, a control's tip about the control |
| the row | two lines: status, name, where it is bound, pending approvals and what it does; then the controls. The folder and branch line and the context · Claude Code · uptime line are gone, being on the card; their two signals stay on the row: a context past 60% as the purple checkpoint button, an older Claude Code as an amber ↑ after the name. Measured: 51 px a row, running or stopped (17 px a line) | the card made those lines redundant; the list holds more sessions on screen |
| placement | under the row it describes; a tab's card under its tab; the window's tick refreshes the details (uptime, working time) and a removed session's card closes | as the tab's card |

## 3. Consequences

Amends record 81 (the card's content and where it shows) and record 53 (the row's tooltip and lines). `SessionRow.detailsTip`,
`bodyHtml`, `uptimeText` and the sidebar's one-second tick, which repainted the uptimes, are gone. Tested in `WorkspaceTest` (the details on a session panel's card; a row showing its panel's card with no second
read; a headless session's card, its read, ↻, the grace, a removed session; a panel without details),
`SessionsSidebarTest` (the card asked for over a row's text, told when the pointer reaches a control or leaves),
`TopicSourceTest`, and off screen in `SessionCardUiTest` (`:app:uiTest`: the pointer resting on a row in the real window;
the card painted to `build/snapshots/session-card.png`).
