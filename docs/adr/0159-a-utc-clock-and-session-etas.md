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

4. Amended 2026-11-15 (task 148): `session_eta` also takes the timebox the user gave, `box_minutes`, and when,
   `box_given_utc` (`HH:mm` in UTC, the last such time at or before now, or an ISO-8601 instant; empty is now; any
   other text is refused). `SessionEta` keeps them and when the session set the estimate. The activity and the ETA on
   the session bar open the card a session's row shows, in place of a tooltip, after the same rest: every fact in UTC,
   moved on each tick while it shows. The kit's `measure phase <task> go --quoted N` prints a line asking the
   session to call `session_eta` with the box and the go's time.

5. Amended 2026-11-15 (task 153, Miguel: "the activity is useless ... I want the eta always visible ... like a HUD in
   a fighter jet"): the activity label goes and the ETA shows at all times, as one run of work. `SessionEta.run` is the
   run: when it started, its deadline and where that came from (`BOX`, the timebox; `ESTIMATE`, the session's goal;
   `DEFAULT`, comando's), the percent the session reports and when, and when it ended. A turn that starts with no task
   running opens a `DEFAULT` run due at the median of the session's last 20 turns (`SessionEta.turnMillis`), 5 minutes
   before any, and its end ends the run. A `session_eta` with a goal or a timebox opens a task run, started at the
   timebox's time, else at the turn's start; it spans turns, and ends when the session reports `percent` 100 or
   clears the goal and the timebox with no percent. A task is its timebox's time, else its goal: the same again goes
   on with its start and last percent, another starts fresh; a timebox repeated with no time keeps the go's; a call
   with only a percent reports progress and keeps the goal and the timebox. `session_eta` takes `percent`, 0 to 100, the session's own reading; nothing
   interpolates it. The bar reads "16:02 UTC ▸ 12 min 05 s ▸ due 16:30 UTC ▸ 60% (4 min ago)": the start, the time running,
   the deadline in the start's format (`~` before a default one, "(+6 min)" and attention colour once late), the
   percent with its age ("–%" before a report). Idle, the last run, muted: "… ▸ done, 1 min early"; before any, "no
   run yet". The activity's facts (what the turn does, queued prompts, work waited on, the checkpoint's hint) move into
   the ETA's card; while a checkpoint runs the line takes the checkpoint's colour. A session estimates a deadline the
   user gave no timebox for with the kit's `measure eta "<task>"`, which ranks past tasks of `kit_eval_plan_eta` by
   likeness and suggests the median minutes they took.

## 3. Consequences

- A turn always has an ETA; a task has one only if the session calls `session_eta`. A task the session never ends
  keeps running, late, until it ends it or the next task replaces it.
- The estimate, the run and the turn lengths are state, not persisted: a restart of comando drops them.
