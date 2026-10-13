# 14. MCP over HTTP on localhost

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel, on the defaults proposed in the plan review |

## 1. Context

comando is Claude Code's parent process. A stdio MCP server would be a child that Claude Code spawns, outside comando's JVM.

## 2. Decision

An HTTP MCP server on `127.0.0.1`, an ephemeral port, a bearer token per session in the `--mcp-config` headers.

## 3. Consequences

The token names the calling session, so tools act on that session's panels. Origin checks keep browsers out ([Approvals and safety](../design/approvals-and-safety.md#4-the-mcp-server)).
