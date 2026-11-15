# 159. A UTC clock, session ETAs, and session buttons that start a stopped session

| Date | Status | Decided by |
|---|---|---|
| 2026-11-15 | accepted; built | Miguel and Claude |

## 1. Context

Sessions give their reports, estimates and ETAs in UTC; the window showed no UTC time, and Miguel, in Madrid (UTC+1),
converted each one. A session's estimate lived only in its transcript, read once and then lost. On the session bar,
Compact and Clear were greyed while the session was stopped, though a prompt to a stopped session starts it (record 44)
and Reflect already did so.

## 2. Decision

1. The status bar ends with the time in UTC, "11:24:05 UTC", ticked by the shell's one-second tick, in a slot of its
   own at the bar's right edge whose width is its widest text in its font: the usage and work counts beside it grow
   and shrink without moving it. The tooltip gives the local time and zone.
2. A session sets its ETA with the `session_eta` action: the step under way and the task's goal (its end state), each
   in minutes from now. The reducer stores each as an absolute time on `SessionState.eta` (`SessionEta`); 0 minutes or
   an empty text clears a part, and the next call replaces the whole estimate. The session bar shows it after the
   activity, "step build · ETA 11:40 UTC (8 min) · goal shipped · ETA 11:52 UTC (20 min)", muted, in attention colour
   once a time has passed ("(3 min late)"), recounted on every tick.
3. Compact, Clear and Rewrite are enabled for a stopped session wherever offered (session bar, session menu,
   palette); the reducer starts the session and sends the command, as for a prompt. They stay off while the session
   stops or runs its checkpoint (record 130).

## 3. Consequences

- The ETA exists only if the session calls `session_eta`; nothing infers one.
- The estimate is state, not persisted: a restart of comando drops it.
