# 53. Session rows: compact, clear, copy, and an order of the user's own

| Date | Status | Decided by |
|---|---|---|
| 2026-10-17 | accepted; amends the session rows' start time of [record 46](0046-clear-buttons-text-coloured-by-kind-checkpoint-due.md) | Miguel (compact and clear buttons on a session row; the row's buttons packed at the left, model and effort at the right; a row that shows and copies the folder, name, session id, uptime and context; drag to order, running sessions first and then by name otherwise), Claude (how: when compact and clear show, what the row shows and what it copies, where the start time went, when the automatic order gives way and how it comes back, where each is kept) |

## 1. Context

A session row showed its name, binding, folder, branch and, while running, its start and uptime, then a row of plug,
fresh start, checkpoint, model and effort, all at the left with gaps between them. Compacting or clearing a
conversation took typing `/compact` or `/clear` in the session's panel. The context's size was in the status bar only
for the focused panel's session, the conversation id only in the tooltip. The list kept the order in which sessions were
added.

`/clear` under `claude -p` with stream-JSON input was checked on Claude Code 2.1.302: the process stays up, writes a
`conversation_reset` event (`trigger: "clear"`), ends the clear's turn with a `result` and no model call, and
reports the new conversation in a new `system/init`.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| buttons | plug, fresh start, checkpoint, compact, clear packed at the left with no gap; model and effort at the right | the session's actions together, its settings apart |
| compact, clear | shown while the session runs; each sends its command as a prompt from the session's panel, as the checkpoint does (`Intent.SessionCommand`); no question asked | a stopped session has nothing to compact, and a start just to clear is the fresh start's job; the old conversation stays importable |
| a cleared conversation | the stream mapper zeroes the context on `conversation_reset` and notes "Conversation cleared"; the reducer follows the new id, saves it, leaves nothing due, and says the previous one can be imported again | the next start resumes the new conversation; the old context does not linger until the next turn |
| the row | line 1: state, name, binding, approvals, activity; line 2: folder and branch; line 3: context % (purple from the checkpoint's 60%), "up 3 h 05 min", "id" and the id's first 8 characters; lines clipped at the sidebar's edge | what a decision on the session needs, readable at a glance; the full id does not fit a sidebar |
| copy | a copy button at the row's top right while the mouse is over it; the menu's Copy details; ⌘C on the selected row. It copies name, path, branch, full session id, status, uptime with the start time, context with tokens, model and effort, one per line | the row shows what fits; the clipboard carries all of it |
| start time | in the copied details only; the row keeps the uptime | the third line holds the context and the id instead |
| order | running first, then by name, case ignored, until the user drags a row; the list as shown, with that move, becomes the stored order in `sessions.json` (`Intent.OrderSessions`) and stands; the bar's sort button returns to the automatic order | the order the user asked for by default, and a hand-made one kept when wanted |
| order mode | kept with the window's settings (`sessions.manualOrder`), not in the state | it is how the list is shown, not a fact about sessions |

## 3. Consequences

`SessionRow.Control` gains `COMPACT`, `CLEAR` and `COPY`; a hidden control is never hit and has no bounds. The session
list tracks the viewport's width, so the right-hand buttons stay in view and lines clip rather than widen the list. A
drag starts on a row's text after 5 pixels, draws a line where the row will land, and leaves the click that may follow it
unanswered. `Reducer.checkpoint` became `sessionCommand`, which the checkpoint, compact and clear share.
`:app:snapshot` paints the Sessions tab with a running session as `02a-sidebar-sessions.png`; the stream fixture
`clear.jsonl` holds the recorded clear.
