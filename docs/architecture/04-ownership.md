# Ownership

## 1. The tree

`App` creates every long-lived part in dependency order and keeps it on a stack; `close()` pops the stack, so parts close in reverse order of creation, each once, with two parts moved to the end: the embedded QuestDB, which the lanes may still use, and the window, which stays greyed and takes no clicks or keys, titled "comando — closing…", until everything behind it has closed. Every `close()` in the tree is idempotent and safe on a part that failed half-way through construction.

| Order created | Part | Closing it |
|---|---|---|
| 1 | `Store` | writes the panel files with pending text and `panels.json`, then lets the queued writes of `sessions.json` and `connections.json` finish; a delayed write or retry still waiting is dropped, as that write covers it; waits up to 5 s for panels and sessions and up to 11 s for connections (one keychain call's bound) |
| 2 | `StateLoop` | stops the reducer thread after the intents already queued |
| 3 | `IntentLog` (when recording) | flushes and closes the log file |
| 4 | `DbAdapter` | closes every lane: cancels the running statement, closes the connection |
| 5 | `McpServer` | denies every waiting approval, stops the HTTP server |
| 6 | `ClaudeAdapter` | SIGTERM to every process, SIGKILL to those alive 2 s later; deletes every MCP configuration file and revokes its token; stops the strands |
| 7 | `ToolsAdapter` | SIGTERM to every Gradle and jfrq run and its child processes, SIGKILL to what is alive 2 s later |
| 8 | `EmbeddedQuestDb` (closes after 1) | stops the embedded QuestDB server when it runs; it closes after the lanes that may use it |
| 9 | `ComandoUi` (closes last) | ends the terminals' shells and the live plots, stops the EDT watchdog, disposes the window |

If a constructor step fails, `App` closes what it has built so far and rethrows.

## 2. Exit

Quitting from the window, the Dock or the application menu first asks when work would stop (a running query, a session in a turn, a shell running a job in the foreground, a Gradle or jfrq run; Cancel is the default; a Cancel also cancels a pending logout), then saves the window layout, ends the terminals' shells and the live plots, stops the EDT watchdog, greys the window out as "comando — closing…" under a cover that takes every click and key and disables the menus, then runs the `onQuit` callback that `Main` passed in. The callback starts a platform thread named `exit` that calls `App.close()` and then `System.exit(0)`, so the EDT is free while the parts close. A shutdown hook calls `App.close()` too; the second call does nothing.

## 3. Rules

1. The part that creates a resource closes it; nothing closes a resource it received.
2. `close()` never throws to its caller; a failure is logged with the part's name.
3. Threads a part starts are daemon threads or end when the part closes; a forgotten thread cannot keep the JVM alive.
