# 12. Scope of the first milestone

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel |

## 1. Context

A vertical slice that runs end to end was needed before the shared resource dialog.

## 2. Decision

M1: one session per panel, start and resume, the sessions sidebar, the prompt bar and dispatch, the stopgap transcript,
approvals, session restore, `ui_show_table` and `ui_plot`. `ResourceDialog<S>` in M2.

Miguel decided, on the default proposed in the review of the design plan.

## 3. Consequences

M1 to M4 were then implemented in one pass. The stopgap transcript was replaced in M2
([record 21](0021-painted-transcript.md)); the prompt bar was removed later
([record 25](0025-no-prompt-bar.md)).
