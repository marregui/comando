# 46. Clear buttons, text coloured by its kind, a checkpoint that says when it is due

| Date | Status | Decided by |
|---|---|---|
| 2026-10-16 | accepted; its last paragraph replaced by [record 50](0050-comando-ships-a-claude-code-kit.md); the session rows' start time moved to the copied details by [record 53](0053-session-rows-compact-clear-copy-and-an-order-of-your-own.md) | Miguel (60% as the checkpoint threshold, the value his own setup uses; due-ness not saved; no SQL completion without an open database; a Clear button after Stop on the editor panel and the Session tab; Stop with a body; colouring that follows what the text is; the checkpoint grey until due; start time and uptime on the session rows; no money on the turn end), Claude (how) |

## 1. Context

Emptying a panel's text took ⌘A and Delete; emptying the Session tab was not possible: it keeps the last 2000 blocks of
a conversation. Stop was a bare icon among labelled buttons. A panel's own text was coloured as SQL throughout, also
without a database and in prompts and notes. The checkpoint button ([record 45](0045-fresh-start-and-checkpoint-buttons.md))
looked the same whether a checkpoint was due or not; record 45 left the counting out, to be added if missed. The
turn-end line showed the conversation's spend in dollars, a figure that does not apply to a subscription, whose limits
are the plan usage the status bar shows ([record 31](0031-the-status-bar-shows-plan-usage.md)).

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| Stop | a bodied button with its label, like Run | it read as a different kind of control |
| Clear, editor | an icon button with a body after Stop; empties the panel's own text in one step that ⌘Z undoes; hidden on a document (a file opened in the panel), disabled while previewing or with no text | a document is a real file, saved at once; the panel's own text is a scratch area |
| Clear, Session tab | the same button at the end of the lower tab strip, shown while Session is in front; drops the transcript's blocks; the conversation goes on, whole in Claude Code's files; refused while a turn runs | a turn being written holds indexes into the blocks |
| header on a narrow panel | the session and connection chips give up width and end in "…"; the plugs and buttons keep their size | the extra buttons pushed the chips under the buttons |
| colouring | block by block, with the blocks and the classifier that decide where a run goes: a SQL block is coloured as SQL only while the panel's database is open; any other block is Markdown, a leading `/command` marked, a `-- @claude` line a comment; SQL completion opens by itself only with the database open | text without a database is prompts and notes; a colour must agree with where the block runs |
| checkpoint due | due once a turn ends with the context at 60% of the window or more; stays due through a compaction; ends when the checkpoint command is sent, by the button, typed, or from another session (the command alone or with arguments); after a checkpoint the context must drop below 60% and cross it again; a fresh start clears it | the point at which the user's own sessions are asked to reflect; a compaction does not reflect anything |
| checkpoint colour | grey, purple while due, on the panel chip and the sidebar row; the tooltip says why | the colour is the state |
| uptime | a third line on a running session's row: "since 09:12 · 3 h 05 min" (the date before the time from the second day); none while stopped; repainted when the text changes | the controls line has no room in a narrow sidebar |
| turn end | its duration, and the error when it failed; no spend, on the Session tab, in a copy as Markdown, or in `session_read` | the plan's percentages are the budget, shown in the status bar |

## 3. Consequences

`SessionState` gains `startedMillis` and `reflection` (`Reflection`: due, armed); `Intent.ClearTranscript`,
`Effect.ClearTranscript` and `ClaudePort.clearTranscript` empty a session's transcript on its strand, and the mapper
forgets its block indexes then and on a fresh start. The command editor's document is a `PanelDocument` under the
style `text/comando-panel`; its `PanelTokenMaker` keeps each block's line states itself, because RSyntaxTextArea asks
for a line's end state without saying which line it is. Due-ness is not saved: after a restart a session resumed past
60% is due at its first turn end. The `Block.TurnEnd` and the JFR turn event keep the cost Claude Code reports; nothing
shows it.

Due-ness stays in memory on purpose. Saving it would mean reading when the last checkpoint ran from the user's own
Claude Code setup (memory files, a skill's record), which differs from one user to the next; comando reads nothing from
such a setup, so that it works for any user. What a session exposes to comando is to be defined as a convention when
comando is made public.
