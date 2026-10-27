# 11. The documented CLI route to Claude Code

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel |

## 1. Context

Claude Code can be driven through its documented `-p` stream-JSON interface, with a permission-prompt tool and MCP, or
through the Agent SDK's control protocol on the same stdio stream.

## 2. Decision

The documented route: `claude -p --input-format stream-json --output-format stream-json`, `--permission-prompt-tool`,
and an HTTP MCP server on localhost. The control protocol is used only for `interrupt`.

Miguel decided, on the default proposed in the review of the design plan.

## 3. Consequences

The bridge is behind `ClaudePort`. The facts it relies on are recorded probes against CLI 2.1.296
([The Claude bridge](../claude.md#1-the-claude-bridge)). `ClaudeVersion` names the tested line, 2.1.296 up to 2.2.0;
a version outside it gets a warning, not a refusal.

## 4. Alternatives

| Option | Why rejected |
|---|---|
| the Agent SDK's control protocol for the whole exchange | not the documented interface; kept for `interrupt` only |
