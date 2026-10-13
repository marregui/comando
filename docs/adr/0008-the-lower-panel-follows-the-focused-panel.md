# 8. The lower panel follows the focused panel

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel, on the defaults proposed in the plan review |

## 1. Context

Several editor panels can be open, each bound to a connection and a session. The lower panel has room for one set of results, one transcript, one terminal.

## 2. Decision

Results, Session and Terminal show the focused panel's. Approvals list every session's requests.

## 3. Consequences

Switching tabs in the workspace switches the lower panel. An approval from a session in an unfocused panel is never hidden.
