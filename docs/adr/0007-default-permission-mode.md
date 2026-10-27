# 7. Default permission mode

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel |

## 1. Context

Claude Code has a configured default permission mode per user and project. comando routes permission prompts to its
own cards.

## 2. Decision

A session uses the CLI's configured default unless its spec names a mode. Approvals go through comando's `approve`
tool.

Miguel decided, on the default proposed in the review of the design plan.

## 3. Consequences

comando neither weakens nor strengthens the user's Claude Code settings. `--permission-mode` is passed only when the
session names a mode; `--permission-prompt-tool mcp__comando__approve` is passed with the MCP configuration
([The process](../claude.md#11-the-process)).
