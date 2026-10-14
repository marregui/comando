# 32. Session rows carry their controls

| Date | Status | Decided by |
|---|---|---|
| 2026-10-14 | accepted | Miguel (no cost or time, the controls), Claude (how) |

## 1. Context

A row in the Sessions tab showed the conversation's cost (Claude Code's estimate at API prices, which a subscription
does not bill) and the time since its last activity. Miguel found neither useful. Starting or stopping a session,
and changing its model or effort, took the context menu, the command palette or the session dialog.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| cost, time since last activity | removed from the row | not used; a turn in progress still shows what it is doing |
| cost in the status bar | removed from the focused session's item; the model, effort, context share and activity stay | the same figure, as little used there |
| the embedded server in the status bar | removed, with its click to start or stop; Start and Stop stay in the palette and the Connections menu, and the start still reports in a notice | Miguel does not want the server one click away in the bar |
| order in the panel header and the status bar | the session first, then the connection | Claude is what a panel is for; the database is the least used feature |
| order of the lower tabs | Session, Approvals, Terminal, Results; ⌃1–⌃4 (and ⌘⌥1–4) follow it, so Approvals moves to ⌃2, Terminal to ⌃3, Results to ⌃4; Session is the initial tab and the one a closed view returns to | Results, leftmost, took the eye; the keys follow the positions, as tab keys do |
| controls | a row under the folder line: connect or disconnect (plug, unplug), model ▾, effort ▾, edit (pencil) | one click from the list, without opening the dialog |
| model and effort menus | the palette's choices (fable, opus, sonnet, haiku; low to max), the current one checked; a choice dispatches `SetSessionModel` or `SetSessionEffort`, which saves the session and, when it runs, sends `/model` or `/effort` to it | the path the palette already used |
| what the row names | the model the session asks for, else the one Claude Code reported, else "default"; a full id by its family | a session on the CLI's default still says what runs |
| the list | stays a `JList`: the row is its renderer, and a click is matched to the control under it | selection, double-click to bind, the context menu and keyboard navigation stay as they were |
| keyboard | the context menu gains Model and Effort submenus | a painted button takes no focus |

## 3. Consequences

The edit dialog remains the place to clear a model or effort back to the CLI's default. The sidebar no longer
repaints every 30 s: nothing in it depends on the time.
