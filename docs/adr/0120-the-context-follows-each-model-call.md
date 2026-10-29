# 120. The session bar's context follows each model call, and a restart keeps it

| Date | Status | Decided by |
|---|---|---|
| 2026-10-29 | accepted | Miguel and Claude |

## 1. Context

Miguel, 2026-10-29: the session bar's context was neither set nor refreshed, and read "–".
The context reached the session bar only in `SessionTurnEnded`, at a turn's end. After a restart a resumed session's
state starts at zero, so the bar said "ctx –" until its next turn ended; during a long turn (18 minutes on 2026-10-28)
it showed the previous turn's figure. The stream mapper already read each model call's input tokens, and the metrics
log (record 110) already kept every turn's context and window.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| during a turn | each top-level model call's input tokens (input, cache creation, cache read) go out at once as `SessionContext`, with the window last known | the bar follows the turn |
| after a restart | once a stored conversation is loaded: its last model call's tokens, else the context its last turn left in the metrics log; the window from the metrics log, newest of the last 14 days' files | the bar starts where it was |
| unknowns | a value at 0 or below keeps the one known; "ctx –" only while nothing is known | never a regression to unknown |
| the turn's end | unchanged: `SessionTurnEnded` still sets the context, the window and the checkpoint's reflection | the checkpoint is a turn's affair |

## 3. Consequences

`Intent.SessionContext(name, tokens, window)`, `SessionState.withContext`, `MetricsLog.lastContext(conversation)`,
`StreamMapper.reportContext` and the mapper's remembered window; `ClaudeAdapter` reports the context after loading a
stored conversation. Tested in `StreamMapperTest` (one report a model call, none while replaying, one on load with the
metrics' window or tokens), `MetricsTest` (the newest turn across days) and `ReducerTest` (a report keeps what it does
not know).
