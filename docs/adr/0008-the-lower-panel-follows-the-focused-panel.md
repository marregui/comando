# 8. The lower panel follows the focused panel

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel |

## 1. Context

Several editor panels can be open, each bound to a connection and a session. The lower panel has room for one set of
results, one transcript and one terminal.

## 2. Decision

Results, Session and Terminal show the focused panel's. Approvals lists every session's requests.

Miguel decided, on the default proposed in the review of the design plan.

## 3. Consequences

Switching tabs in the workspace switches the lower panel. An approval from a session in an unfocused panel is never
hidden.
