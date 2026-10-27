# 48. Stalls are a line, hangs a stack; the heap starts small

| Date | Status | Decided by |
|---|---|---|
| 2026-10-16 | accepted; its JFR row removed by [record 54](0054-comando-knows-nothing-of-jfr.md) | Miguel and Claude |

## 1. Context

The EDT watchdog logged a WARNING with a 40-frame stack for every stall of 200 ms or more that was not the JDK's
drawing, and a second WARNING when it ended. From 2026-10-14 to 2026-10-16 the log held 31 of them, the longest
530 ms; 16 were the session switch's layout, fixed on 2026-10-15, and the user noticed none. One stack per stall is a
single look at the EDT 200 ms in: it names where the EDT happened to be, not what held it. Three of the warnings were
the EDT waiting on Java2D's render queue while comando's transcript painted a card, the same wait logged as INFO when
no comando frame was on the stack.

Activity Monitor showed comando at 815 MB of physical footprint: about 360 MB graphics (one 1920 × 1050 point window
at 2×, 32 MB a full-window buffer), 256 MB of committed heap held by `-Xms256m` with 36 to 54 MB live after each young
collection, 121 MB of native allocation.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| a stall below its limit | one INFO line when it ends: `the EDT was busy for <ms> ms [<kind>] in=<frame> comando=<frame:line>` | the length, the kind and two frames group stalls by cause with a search; no stack for what nobody noticed |
| kind | `startup` (no probe answered yet), `drawing`, `comando` (a comando frame on the stack), `jdk` (neither) | names whose stall it is |
| drawing | the EDT waits on Java2D's render queue (its lock, or a flush to finish) before any comando frame, or is in Java2D with no comando frame | the wait is the GPU pipeline's, whoever called the paint |
| a hang | 1 s, or 2 s for `drawing`: a WARNING with the EDT's stack taken at that moment, then a WARNING when it answers; the user's notice from 1 s, as before | the stack of what holds the EDT now, only when it was noticed |
| JFR | `comando.EdtBlocked` carries kind and where; `drawing` is gone from it | the same grouping in a recording |
| heap | `-Xms64m` and `-XX:G1PeriodicGCInterval=60000`, for `:app:run` and the DMG; `-Xmx4g` unchanged | a small start, and memory an idle minute leaves unused goes back to the system; `-Xms` left out would start at 1/64 of RAM, 512 MB on 32 GB |

Miguel asked for a log that alarms less and still serves an agent, and for a smaller heap; Claude decided how.

## 3. Consequences

`EdtWatchdog.Stall` carries `kind`, `where` and `reported`; `EdtWatchdog` takes a hang limit as well as the drawing
limit. The graphics share of the footprint follows the window's pixel count and is not comando's to shrink.
