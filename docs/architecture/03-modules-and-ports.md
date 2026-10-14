# Modules and ports

## 1. Ports

A port is an interface in `core` that names an outside system. Every port method returns at once; outcomes come back as intents.

| Port | Methods | Adapter | Module |
|---|---|---|---|
| `IntentSink` | `dispatch(intent)` | `StateLoop` | `core` |
| `Clock` | `millis()` | `Clock.SYSTEM` | `core` |
| `DatabasePort` | `open`, `close`, `test`, `execute`, `cancel`, `release`, `loadSchema` | `DbAdapter` | `db` |
| `QueryPort` | `query`, `describe` (futures of text rows, for Claude) | `DbAdapter` | `db` |
| `ClaudePort` | `start`, `stop`, `send`, `interrupt`, `loadHistory`, `forget` | `ClaudeAdapter` | `claude` |
| `McpGateway` | `register(session)` returns the URL and bearer token a session's CLI uses; `unregister` | `McpServer` | `mcp` |
| `ApprovalPort` | `resolve(approval, decision)` | `McpServer` | `mcp` |
| `ToolPort` | `runGradle`, `runJfrq`, `cancelRun`, `forgetRun`, `openRecording`, `browseScratchpad` | `ToolsAdapter` | `tools` |
| `UiPort` | `editPanel`, `notifyDesktop`, `forgetPanel`, `showFile`, `arrange`, `showScratchpad` | `ComandoUi` | `ui` |
| `PersistencePort` | `saveConnections`, `saveSessions`, `savePanels` | `Store` | `app` |

`ClaudeAdapter` receives the `McpGateway` at construction: starting a session registers it with the MCP server and writes the ticket into the session's MCP configuration. `McpGateway.NONE` starts sessions without comando's tools; the adapter tests use a gateway that records each registration instead.

## 2. Test fakes

`testkit` holds what every module's tests share, so no module builds its own fakes.

| Fake or fixture | Replaces or provides |
|---|---|
| `RecordingPorts` | every effect port; records each call as a line of text for assertions |
| `RecordingSink` | an `IntentSink` that keeps intents and waits for one of a type with a deadline |
| `FakeClock` | a settable clock |
| `States` | a two-panel state bound to one connection and one session |
| `Fixtures` | recorded stream-JSON sessions from the Claude Code CLI, sanitized |
| `SyntheticSession` | a deterministic eight-turn session with 41 tool calls, one of them a subagent, replayed delta by delta |
| `FakeResult` | a small `ResultView` of rows of objects |

## 3. Import rules

ArchUnit tests in the `ui` module fail the build when:

1. a widget package imports another widget package;
2. a widget imports `panels`, `shell` or `dialogs`;
3. the kit imports a widget, `panels` or `shell`;
4. any UI class refers to `db`, `claude`, `mcp`, `tools` or `java.sql`.

Gradle enforces the module graph itself: `core` has no project dependency, and only `app` sees every module.
