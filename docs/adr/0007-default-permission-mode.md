# 7. Default permission mode

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel, on the defaults proposed in the plan review |

## 1. Context

Claude Code has a configured default permission mode per user and project. comando routes permission prompts to its own cards.

## 2. Decision

A session uses the CLI's configured default unless its spec names a mode. Approvals go through comando's `approve` tool.

## 3. Consequences

comando does not weaken or strengthen the user's Claude Code settings.
