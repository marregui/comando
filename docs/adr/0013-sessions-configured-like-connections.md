# 13. Sessions configured like connections

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel, on the defaults proposed in the plan review |

## 1. Context

Requirement R13 asks for sessions configured through the UI. Connections already had a dialog.

## 2. Decision

Sessions are configured with the same dialog and interaction as connections, and switched from a persistent sidebar.

## 3. Consequences

One `ResourceKind<S>` model and one `ResourceDialog<S>` serve both ([Resources and panels](../design/resources-and-panels.md)).
