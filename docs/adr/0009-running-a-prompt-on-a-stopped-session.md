# 9. Running a prompt on a stopped session

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel |

## 1. Context

Running SQL on a closed connection opens it. A prompt on a stopped session needs the same rule.

## 2. Decision

Sending a prompt starts the panel's session when it is stopped, resuming its conversation when it has one.

Miguel decided, on the default proposed in the review of the design plan.

## 3. Consequences

The first prompt after a restart resumes the conversation. Nothing starts a session before the user sends something.
