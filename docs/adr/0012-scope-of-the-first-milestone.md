# 12. Scope of the first milestone

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel, on the defaults proposed in the plan review |

## 1. Context

A usable vertical slice was needed before the shared resource dialog.

## 2. Decision

M1: one session per panel, start and resume, the sessions sidebar, prompt bar and dispatch, the stopgap transcript, approvals, session restore, `ui_show_table` and `ui_plot`. `ResourceDialog<S>` in M2.

## 3. Consequences

M1 to M4 were then implemented in one pass; the stopgap transcript was replaced in M2.
