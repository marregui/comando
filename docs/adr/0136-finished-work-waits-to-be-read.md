# 136. Finished work waits to be read, and the lists say what each session asks of you

| Date | Status | Decided by |
|---|---|---|
| 2026-11-07 | accepted | Miguel and Claude |

## 1. Context

46% of finished turns waited over 10 minutes for the user. The mark that says so lived in `Workspace.unseen`: in memory,
one per open tab, lost at a restart and absent for a session with no panel. The work list ([record 134](0134-the-window-says-who-works-and-gives-room-to-what-is-read.md))
listed who needs you, who works and who waits, and had no line for what had finished, so the session waiting longest on
the user was the one the window did not name.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| where the mark lives | `SessionSpec.unreadMillis`, when the work finished, 0 for none, optional in `sessions.json`, read as 0 when absent, no version bump (record 133); every edit keeps it, as `family`; `restarted()` keeps it; a new session has none | the session owns the outcome, so a rename, a remove and a restart need no rule of their own; the mark outlives the window |
| what sets it | the reducer, when a turn ends and the focused panel is another session's: an outcome is a turn that ran and left no work handed off, a failed or interrupted turn, or a session that exits at work; the return of background work sets it too, whichever turn left that work | the tab's rule: a hand-off turn is no outcome, a failed hand-off is |
| what clears it | one step after every intent: a session busy, or the focused panel's, has no mark; if the set changed, one `SaveSessions` | `OpenSession` and `FocusPanel` both count, and any later intent that leaves focus there, with no list of intents to keep; a new turn supersedes the outcome |
| what a session asks of you | `Attention.of(state, session, now)`, the first that applies: `NEEDS_YOU` (requests pending), `WORKING` (busy, waiting, or returned within `REPORT_GRACE_MILLIS`), `TO_READ` (marked), `IDLE` | one answer for the tab, the Sessions tab and the work list |
| a return | the mark is set at the return and held back for 60 s, as the reading turn that follows clears it and sets it again at its end; a hand-off that failed is not held back | with no reading turn the mark surfaces by itself; the once-a-second tick re-reads it, as no state change carries it |
| the Sessions tab | grouped in that order, the heading painted in the first row of each group, empty groups showing nothing, the list's own order inside a group; each row tells assistive technology its group | keeps `JList<SessionState>` and its index maths; a row in the model per heading would have shifted every index |
| a drag | orders inside a group; a drop among another group's rows is ignored | the group is computed, so a drop across it could not hold |
| under the pointer | rows hold their place and their group until the pointer leaves, a new session placed last under its own group | a row moving under the pointer takes the click meant for another |
| the work list | To read lines rank between who needs you and who works, "name · to read · since", and the count mentions them | what waits on the user comes before what does not |
| a click on a To read line | opens the session, `OpenSession` when it has no panel, and lands on the report: a double click in the Sessions tab likewise; other lines with no panel still do nothing | the line exists to be read, so the click must end at what it announces |
| the tab | a session panel's tab reads the session's mark; `Workspace.unseen` stays for queries | a query's outcome belongs to its panel, not a session |

## 3. Consequences

`SessionSpec` gains `unreadMillis`, `Attention` is added with `REPORT_GRACE_MILLIS` moved from `TabStatus`, `Reducer.apply` gains
a post-step, `StatusBar.WorkRow` gains the session to read, and `StatusBar` and `SessionsSidebar` gain a `tick` the shell
calls once a second. The status bar's work rows now take the time. `Workspace`'s topic source uses the session's subject
when known. Tested in `AttentionTest`, `ReducerAttentionTest`, `TabStatusTest`, `WorkspaceTest`, `StatusBarWorkTest`,
`SessionsSidebarTest`, `StoreTest` and `AttentionUiTest`.

While a later turn's mark stands and older background work runs on, the Sessions tab and the work list say Working and
the tab says done. During a return's grace the tab is idle and the lists say Working. Each state of the mark costs one
write of `sessions.json`. A transcript whose history has not loaded has no report to land on, so it stays at its end.
With the pointer on the Sessions tab a new session may repeat a heading until the pointer leaves.

## 4. Alternatives

Keeping the mark per tab leaves a session with no panel unmarked. A separate file for marks adds a second store for one
flag, and an edit could then lose it. A model row per heading, or a `JList` of a sealed row type, changes every index the
drag, the hit tests and the held rows compute. Clearing the mark in `OpenSession` and `FocusPanel` only misses the intents
that move focus by another route.
