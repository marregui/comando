# Persistence

## 1. The data root

The data root is the directory in `-Dcomando.home`, else the environment variable `COMANDO_HOME`, else `~/.comando`. Tests and the snapshot tool point it at a temporary directory.

| Path | Holds | Written by |
|---|---|---|
| `connections.json` | connection specs; passwords empty when the keychain holds them | `Store` |
| `sessions.json` | session specs, including the conversation id that makes the next start a resume | `Store` |
| `panels.json` | each panel's id, title, connection and session binding, and editor text | `Store` |
| `run/` | one MCP configuration per running session, mode `0600`, deleted when the process exits | `ClaudeAdapter` |
| `audit/audit-<UTC date>.jsonl` | every MCP tool call and approval decision | `AuditLog` |
| `logs/comando-<n>.log` | the application log, 5 files of 5 MB, rotated | `Logging` |
| `logs/intents-<epoch ms>.jsonl` | the intent log, when recording is on ([Quality](quality.md)) | `IntentLog` |
| `questdb/` | the embedded QuestDB's data, when the user starts it | `EmbeddedQuestDb` |

Outside the data root:

| Location | Holds |
|---|---|
| macOS login keychain, service `comando` | connection passwords, one item per connection name ([Approvals and safety](approvals-and-safety.md#5-credentials)) |
| Java preferences, node `dev/marregui/comando` | window size and position, divider positions, the side pane's state; tests use another node through `comando.prefsNode` |
| `~/.claude/projects/` | Claude Code's conversations; comando reads them and never writes them |

## 2. Atomic store files

`JsonFile` writes a file to a temporary file in the same directory, then moves it over the old one with `ATOMIC_MOVE`. A crash leaves the old content or the new, never a torn file. A file that cannot be parsed at start is renamed to `<name>.corrupt-<epoch ms>` and comando starts without it, so the next save does not destroy what the user may want to recover.

`Store` runs every write from an immutable snapshot of the state, so a save never races an edit. Sessions and panels are written on one thread; connections on another, because a connection save talks to the keychain, which may wait on an unlock prompt, and panel text must not queue behind it. Connection and session changes are written at once. Panel text is written 800 ms after the last change. `Store.close()` writes what is pending.

## 3. What comes back after a restart

| Thing | Restored as |
|---|---|
| connections | closed; a password is read from the keychain when the connection is loaded; when the keychain cannot be asked, the connection loads without it and the stored item is kept |
| sessions | closed; a session with a conversation id shows its stored transcript, read from Claude Code's file without starting a process |
| panels | their text and bindings; a binding to a name that no longer exists is dropped; with no panel left, one panel named `scratch` with the welcome text |
| window | size, position, dividers; a side pane divider outside the window is ignored |
| views, results, terminals | not restored; they belong to one run |

Nothing is sent to a session until the user sends something; the first prompt resumes the conversation.

## 4. Seeding from the earlier comando

On a data root without store files, comando reads `connections.json` and `default-notebook.json` from the earlier comando's folder (`~/QUESTS`, or `-Dcomando.legacy=<dir>`; `none` turns it off) once, and writes its own files from them; without that folder it writes defaults. The old files are only read.
