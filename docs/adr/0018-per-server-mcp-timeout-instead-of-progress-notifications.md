# 18. Per-server MCP timeout instead of progress notifications

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Claude |

## 1. Context

The plan expected Claude Code to abort an MCP tool call after 5 minutes without progress, so an approval would send
progress notifications every 60 s. The probes showed that the per-server `timeout` in the MCP configuration also
raises the idle window.

## 2. Decision

The MCP configuration sets `"timeout": 3600000`. An approval waits without progress notifications; comando withdraws
it after 55 minutes.

Claude decided during implementation; Miguel accepted the record on 2026-10-13.

## 3. Consequences

The server sends no notifications and keeps no per-call streams. A change in Claude Code's timeout handling would show
as calls failing at 5 minutes; the version warning names the tested line. The 55 minutes are `App.APPROVAL_TIMEOUT`.

## 4. Alternatives

| Option | Why rejected |
|---|---|
| progress notifications every 60 s during an approval | the per-server timeout already raises the idle window, without a stream per call |
