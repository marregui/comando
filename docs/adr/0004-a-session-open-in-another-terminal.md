# 4. A session open in another terminal

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel |

## 1. Context

Two processes writing one Claude Code conversation corrupt its order. comando cannot tell whether a terminal holds a
conversation open.

## 2. Decision

A conversation imported from Claude Code's files starts the first time with `--resume <id> --fork-session`. From then
on the session resumes the fork's own id. A session started by comando resumes its own conversation.

Miguel decided, on the default proposed in the review of the design plan.

## 3. Consequences

Cloning a session with a conversation forks it too. After closing the terminal, the user can set "Next start" to
`RESUME` in the session dialog; that choice is the user's. The terminal's conversation and comando's continue as two
conversations with a shared history. After the first start the session holds the fork's id, so the import dialog no
longer marks the original conversation as imported, and it can be imported again, as another fork. In the code the
start modes are `StartMode` `NEW`, `RESUME` and `FORK`; `ClaudeCommand` adds `--fork-session` for `FORK`.
