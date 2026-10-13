# Ownership

## 1. The tree

`App` creates every long-lived part in dependency order and keeps it on a stack; `close()` pops the stack, so parts close in reverse order of creation, each once. Every `close()` in the tree is idempotent and safe on a part that failed half-way through construction.

| Order created | Part | Closing it |
|---|---|---|
| 1 | `Store` | flushes pending panel text and writes the three files; waits up to 5 s for panels and sessions and up to 11 s for connections (one keychain call's bound) |
| 2 | `StateLoop` | stops the reducer thread after the intents already queued |
| 3 | `IntentLog` (when recording) | flushes and closes the log file |
| 4 | `DbAdapter` | closes every lane: cancels the running statement, closes the connection |
| 5 | `McpServer` | denies every waiting approval, stops the HTTP server |
| 6 | `ClaudeAdapter` | SIGTERM to every process, SIGKILL to those alive 2 s later; deletes every MCP configuration file and revokes its token; stops the strands |
| 7 | `ToolsAdapter` | SIGTERM to every Gradle and jfrq run and its child processes, SIGKILL to what is alive 2 s later |
| 8 | `ComandoUi` | ends the terminals' shells and the live plots, stops the EDT watchdog, disposes the window |
| last | `EmbeddedQuestDb` | stops the embedded QuestDB server when it runs; it closes after the lanes that may use it |

If a constructor step fails, `App` closes what it has built so far and rethrows.

## 2. Exit

Quitting from the window saves the window layout, then runs the `onQuit` callback that `Main` passed in. The callback starts a platform thread named `exit` that calls `App.close()` and then `System.exit(0)`, so the EDT is free while the parts close. A shutdown hook calls `App.close()` too; the second call does nothing.

## 3. Rules

1. The part that creates a resource closes it; nothing closes a resource it received.
2. `close()` never throws to its caller; a failure is logged with the part's name.
3. Threads a part starts are daemon threads or end when the part closes; a forgotten thread cannot keep the JVM alive.
