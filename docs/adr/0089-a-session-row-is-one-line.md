# 89. A session row is one line, its state an icon

| Date | Status | Decided by |
|---|---|---|
| 2026-10-25 | proposed | Miguel ("each item in a single line, all the controls grouped together"; Claude's icon in its orange while connected, another icon in another colour while not, on the tab too, so the status dot goes; the model and effort controls reflecting what the session runs at), Claude (the four states, the icon as the toggle, where the activity and approvals go, what the effort can show) |

## 1. Context

A session row had two lines (record 87): the status dot, name and what it did; then a plug, the controls, the model and
the effort. The dot and the plug both said whether the session runs. The model chip said "default" for a session
started with Claude Code's default, though the session's `system/init` names the model it runs; the effort chip said
"default" too. Measured 2026-10-25: the stream reports the model at start and on every answer, and no effort level, only
`per_turn_effort_active`; Claude Code's `settings.json` holds `effortLevel` and `modelSettings.<model id>.effortLevel`.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| layout | one line: the state icon, the name, then fresh, checkpoint, compact, model ▾, effort ▾, and copy under the mouse; 25 px a row (measured; 51 before, 17 a text line) | twice the sessions in the same height |
| state | one icon in four states (`SessionIcons`): `claude` orange while it runs, `claude-off` a grey outline while stopped, `claude-starting` faint while it starts or stops, `claude-failed` red when it failed; the spinner while it works, the hourglass while it waits on background work; a click connects or disconnects; its tooltip says which, and what it does | the dot and the plug said the same; the activity text needed a line |
| the name | dimmed while its own panel is not open (closed, or never opened); the accent colour while one of its panels has the focus; an amber ↑ for an older Claude Code; ⚑n for approvals waiting | what the "· closed", "· no panel" text said, without the text |
| the tab | a session's tab at rest shows the same state icon; working, waiting, done, failed and needing the user keep theirs | the same philosophy; what is under way still shows first |
| model | while it runs, the model its `system/init` reported, by family (`opus`); stopped, the one set for the next start, else "default"; the tooltip names the full id | the chip says what the session runs |
| effort | the one set for the session; else, while it runs, the one Claude Code's settings give its model, read when the session starts (`SessionInfo.effort`); else "default"; the tooltip says it is the base each turn starts at, which a turn may raise | the stream does not report the effort a turn runs at |

## 3. Consequences

Amends record 53 (the row) and record 87 (its two lines). The documents attached to a session no longer show on its
row; they stay visible as their own tabs. comando reads Claude Code's `settings.json`, a file of the CLI's own, for the
effort, beside the projects folder it already reads. Tested in `SessionsSidebarTest` (one line, the icons, the tooltip,
the dimmed name, the model and effort chips), and off screen in `SessionCardUiTest`, which paints the row to
`build/snapshots/session-rows.png`.
