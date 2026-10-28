# 43. A session waiting on background work shows it

| Date | Status | Decided by |
|---|---|---|
| 2026-10-15 | accepted; the header's text moved to the session bar by [record 105](0105-a-session-bar-under-the-lower-panel.md) | Miguel and Claude |

## 1. Context

A turn can start work that outlives it: a background agent, a shell command run in the background (a Codex run), a
workflow. The turn ends and the session waits for that work; when it ends, Claude Code starts a turn of its own to
read the result. comando counted a session as working only while a turn it had sent was pending. Claude Code's
`task_started` and `task_notification` events only set the activity label, which the turn's end cleared. So a
waiting session looked idle in its tab, its header, its sidebar row and the status bar, and the turn Claude Code
started by itself did not count as running either. The tab of [record 42](0042-tabs-show-their-sessions-state.md)
marked the hand-off as "finished", and the desktop said "Turn finished" while the work had only begun. Observed case:
a session waiting on a background agent or a Codex run looked idle, so the user asked it for updates. This record adds
a status to those of record 42.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what is tracked | each session's open background tasks, the whole set from `background_tasks_changed`, which Claude Code sends at every change: an agent or a shell command left running past its turn (task id, type, description), including work a background agent itself leaves running; a foreground agent and the commands a background agent runs and waits for are not in it, and a task Claude Code runs by itself (`ambient`) is left out; a task keeps the time comando first heard of it; a session's tasks end with its process | one level snapshot, so a missed event cannot leave a session waiting; the event is undocumented as of Claude Code 2.1.300, and a CLI that stops sending it shows sessions idle, never stuck waiting |
| waiting | no turn runs and at least one task is open | a running turn says more than waiting does |
| a turn comando did not send | the main conversation's call to the model (`stream_event` `message_start`) while no turn is pending counts as a turn until its `result`. Claude Code 2.1.300 forwards no subagent's stream events and sends a null parent on these; one with a parent call would be a subagent's and starts none | the turn that reads the result shows as working |
| the tab | a seventh status, waiting, between working and idle: an hourglass, plain title, the tooltip "waiting on review agent 1 min 30 s" | its own shape, so it reads without colour |
| an outcome | waiting on the work the last turn started is work going on, and so is the return of background work until the turn that reads it makes its first model call (at most 60 s; a return that starts no turn is finished then, on the shell's tick): the turn that hands work off (the open set grew during it) marks nothing done and sends no desktop notice; the reading turn does. A later turn that ends while older work runs on is finished, with its mark and notice. A hand-off turn that fails shows its error mark while the work runs on, and always notifies. A `result` while no turn runs made no model call (Claude Code sends one after a background agent's report) and notifies nothing | "finished" means the work finished, and a failure is never hidden |
| where else | the panel header ("waiting on review agent · 3 min", counted on the shell's tick), the sidebar row, the status bar ("1 session waiting on background work"; the focused session's item says on what), `ui_get_state` (`waitingOn`), `session_read`'s status, the quit question ("Quitting stops <session>'s background work.") | wherever a working session shows, a waiting one shows |
| on what | the task's description when one task is open, else "N background tasks"; the time since the oldest started | one line that fits a tab's tooltip |

Miguel named the gap and accepted the proposal, and decided on 2026-10-15 that a turn ending while background work runs
withdraws no card; Claude decided how.

## 3. Consequences

`SessionState` keeps the open tasks (`BackgroundTask`), when they returned and whether the last turn handed work off,
with `isWaiting()`, `isAwaitingHandOff()`, `isReturning()`, `waitingFor()` and `waitingSinceMillis()`; `Intent` gains
`SessionTasksChanged` and `SessionTurnRunning`; `TabStatus` gains `WAITING`, and `Workspace` re-checks every tab on
the shell's tick.

`session_read`'s wait is unchanged: it ends when the receiving session's turns are over and no block is still running.
Behind a background agent it waits for the report turn (the agent's blocks are running); behind a background shell
command it ends with the hand-off turn. Its status says when the session waits on background work.

A turn that ends while background work runs withdraws no card: a statement card, withdrawn at a turn end with nothing
queued, may be the background work's own, and an approval must not be missed.
