# 11. The documented CLI route to Claude Code

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel, on the defaults proposed in the plan review |

## 1. Context

Claude Code can be driven through its documented `-p` stream-JSON interface with a permission-prompt tool and MCP, or through the Agent SDK's control protocol on the same stdio stream.

## 2. Decision

The documented route: `claude -p --input-format stream-json --output-format stream-json`, `--permission-prompt-tool`, an HTTP MCP server on localhost. The control protocol is used only for `interrupt`.

## 3. Consequences

The bridge is behind `ClaudePort`. The facts it relies on are recorded probes against CLI 2.1.296 ([Claude bridge](../claude.md#1-the-claude-bridge)); a version outside the tested line is warned about.
