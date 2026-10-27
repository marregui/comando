# 14. MCP over HTTP on localhost

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel |

## 1. Context

comando is Claude Code's parent process. A stdio MCP server would be a child that Claude Code spawns, outside
comando's JVM.

## 2. Decision

An HTTP MCP server on `127.0.0.1` and an ephemeral port, with a bearer token for each session's process in the
`--mcp-config` headers.

Miguel decided, on the default proposed in the review of the design plan.

## 3. Consequences

The token names the calling session, so tools act on that session's panels. An Origin check keeps browsers out
([The server](../claude.md#21-the-server)).

## 4. Alternatives

| Option | Why rejected |
|---|---|
| a stdio MCP server | Claude Code would spawn it as its own child, outside comando's JVM |
