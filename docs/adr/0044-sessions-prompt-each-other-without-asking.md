# 44. Sessions prompt each other without asking

| Date | Status | Decided by |
|---|---|---|
| 2026-10-15 | accepted | Miguel and Claude |

## 1. Context

[Record 41](0041-a-session-can-prompt-another.md) let a session prompt another through `session_send`, with a
session-prompt card for every prompt: Send or Deny, no scope. In use every card was answered Send; the card delayed
the work and asked for an answer that was never withheld. This record replaces that consent rule.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| consent | `session_send` asks nothing: the prompt is added to the receiving session's panel and run at once | the sessions are trusted with each other |
| what stays | the prompt is headed `[from session <sender>]` in the panel and in what the receiving session reads; it runs in the receiving panel, where the user sees it; the audit log records every call; prompting itself, an unknown session and a session with no open panel are still refused; a busy receiver takes it after its current turn | the user follows every prompt without being asked |
| the tool's guidance | the tool still says to hand work to another session only when the user asked | no session starts a conversation the user did not want |
| the card kind | `ApprovalKind.SESSION_PROMPT` and its card are removed | no code for a question nobody is asked |
| a stopped session | `session_send` refuses a session that is not running; only the user starts it | stopping a session is the circuit breaker for sessions that keep prompting each other: a prompt run in a panel starts a stopped session, so without this refusal the other session would start it again |

Miguel decided that the sessions are a trusted community and that approval between them is always granted; Claude
decided how.

## 3. Consequences

The consent row of record 41 is replaced; the rest of record 41 stands. Nothing limits two sessions prompting each
other back and forth (each prompt a turn, each turn tokens). Miguel accepted that risk on 2026-10-15; the breaker is to
stop either session, which ends the loop because the other cannot start it again. The only approval a session can
still leave behind when a turn ends is a statement card
([record 43](0043-a-session-waiting-on-background-work-shows-it.md)).
