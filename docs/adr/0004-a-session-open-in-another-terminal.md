# 4. A session open in another terminal

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel, on the defaults proposed in the plan review |

## 1. Context

Two processes writing one Claude Code conversation corrupt its order. comando cannot tell whether a terminal holds a conversation open.

## 2. Decision

A conversation imported from Claude Code's files starts the first time with `--resume <id> --fork-session`, because comando cannot tell whether a terminal still has it open. From then on the session resumes the fork's own id. A session started by comando resumes its own conversation.

## 3. Consequences

Cloning a session with a conversation forks it too. The user can set "Next start" to resume in the session dialog after closing the terminal; that choice is theirs. The terminal's conversation and comando's continue as two conversations with a shared history. After the first start the session holds the fork's id, so the import dialog no longer marks the original conversation as imported and it can be imported again, as another fork.
