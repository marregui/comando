# 19. Own MCP server, no library

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Claude |

## 1. Context

Question U5 of the design plan: the official MCP Java SDK or a small JSON-RPC implementation. comando needs four
methods over HTTP (`initialize`, `ping`, `tools/list`, `tools/call`), bearer tokens and an Origin check.

## 2. Decision

A JSON-RPC server on the JDK's `HttpServer` and Jackson, about 250 lines.

Claude decided during implementation; Miguel accepted the record on 2026-10-13.

## 3. Consequences

No dependency on the SDK's transport stack. Protocol versions `2025-03-26`, `2025-06-18` and `2025-11-25` are
accepted; a new version needs one line (`McpServer.PROTOCOLS`).

## 4. Alternatives

| Option | Why rejected |
|---|---|
| the official MCP Java SDK | a dependency on its transport stack for four methods |
