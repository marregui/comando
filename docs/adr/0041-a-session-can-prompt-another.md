# 41. A session can prompt another

| Date | Status | Decided by |
|---|---|---|
| 2026-10-15 | accepted | Miguel (sessions may hand work to each other; the user tracks it and chooses each time), Claude (how) |

## 1. Context

Two sessions, each in its own panel, could not work together: a session could write into another session's panel
(`ui_insert` with `force`), but not run that text, and could not see what the other session did with it. Miguel
wants a session able to prompt another and read back its work, with the user always able to follow it and the
choice to let it happen. Sending one prompt to several sessions at once stays out ([record 38](0038-a-session-has-one-panel.md)).

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the tools | `session_send` (session, text) and `session_read` (session, after, wait_seconds) | one to hand work over, one to read the result |
| consent | every `session_send` raises a session-prompt card; Send or Deny, no scope, so no prompt goes out without the user's word; a card still waiting when the asking turn ends is withdrawn | the user's choice, each time |
| where it runs | in the receiving session's panel: the prompt is appended as a block of its own and run there, as if the user ran it; a session with no open panel is refused | the user sees what was sent, and the receiving session's work shows in its panel's Session tab |
| who sent it | the prompt starts with `[from session <sender>]`, in the panel and in what the receiving session reads; the notice says "<sender> added N lines to <panel>"; the audit log records the card's answer and the call | the receiving session knows the prompt came from another agent, not from the user |
| a busy receiver | the prompt queues behind its current turn, as a prompt the user sends does | one order of turns per session |
| reading | from comando's own transcript of the session, by block index; without a cursor the last 40 blocks; a block still being written or run stops the cursor so the next read shows it finished; `wait_seconds` (at most 600) waits for the receiving session's turns to end | the same blocks the user sees; a reader never misses a tool's result that arrived later |
| what is refused | prompting itself; an unknown session; a panel closed or rebound while the card waited | nothing runs where the user did not see it |

## 3. Consequences

`McpTools` takes the `TranscriptRegistry`; `ApprovalKind` gains `SESSION_PROMPT`; the inbox draws its card. A
prompt run in a panel the user is not looking at leaves the lower panel on its tab ([record 24](0024-the-window-belongs-to-the-user.md));
the user's own runs, always from the focused panel, still bring the Session tab forward. The
session instructions name both tools, for use when the user asks a session to hand work to another.
