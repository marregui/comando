# 66. The reducer is watched as the EDT is

| Date | Status | Decided by |
|---|---|---|
| 2026-10-18 | accepted | Miguel (watch the reducer), Claude (how: per-intent timing, the slowest part, a hang's stack) |

## 1. Context

Every intent is applied on one thread, `comando-reducer`: the observers, the reducer, each effect handed to its port,
then the state listeners. The rule that a port method queues its work and returns was kept by convention only. A port
method that blocked (a keychain call, a process wait, a JDBC call made in place) would have held every intent of every
session, so nothing in the window changed state, with nothing in the log: the EDT had a watchdog
([record 48](0048-stalls-are-a-line-hangs-a-stack-the-heap-starts-small.md)), the reducer none.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| a stall | an intent that takes 200 ms or more: one INFO line when it ends, `the reducer was busy for <ms> ms [part=<part>]` | the length and the part group stalls by cause with a search |
| part | `observe`, `reduce <Intent>`, `effect <Effect>` or `listeners`; the line names the part that took longest | timed on the reducer thread itself, so the part is exact, not a sample |
| a hang | an intent still applying at 1 s: a WARNING with the reducer's stack taken then by `reducer-watchdog` (every 100 ms), once per intent; a WARNING when it ends, and the user's notice | the stack of what holds the reducer while it holds, even if it never ends |
| no probe intent | the watchdog reads what the loop applies now; it puts nothing on the queue | a probe would sit behind a backlog and would enter the intent log |

## 3. Consequences

`StateLoop` owns the watchdog: started by `start`, stopped by `close` after the queued intents, so the shutdown is
watched too. A queue backlog with every intent fast is not a stall; it is not measured.
