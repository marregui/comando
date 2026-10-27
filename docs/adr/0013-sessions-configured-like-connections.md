# 13. Sessions configured like connections

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted; amended by [record 88](0088-a-session-and-its-panel-share-a-name.md) | Miguel |

## 1. Context

Requirement R13 of the design plan asks for sessions configured through the UI. Connections already had a dialog.

## 2. Decision

Sessions are configured with the same dialog and interaction as connections, and switched from a persistent sidebar.

Miguel decided, on the default proposed in the review of the design plan.

## 3. Consequences

One `ResourceKind<S>` model and one `ResourceDialog<S>` serve both
([One model for connections, sessions and folders](../workbench.md#11-one-model-for-connections-sessions-and-folders)).
Record 88, proposed, makes a session and its own panel share a name.
