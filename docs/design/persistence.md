# Persistence

## 1. The data root

The data root is the directory in `-Dcomando.home`, else the environment variable `COMANDO_HOME`, else `~/.comando`. Tests and the snapshot tool point it at a temporary directory.

| Path | Holds | Written by |
|---|---|---|
| `connections.json` | connection specs; passwords empty when the keychain holds them | `Store` |
| `sessions.json` | session specs, including the conversation id that makes the next start a resume | `Store` |
| `panels.json` | each panel's id, title, connection and session binding, and the name of its file | `Store` |
| `panels/<title>.md` | each panel's editor text, one Markdown file per panel ([A file per panel](../adr/0026-a-file-per-panel.md)) | `Store` (`PanelFiles`) |
| `comando.lock` | a file lock held while comando runs, so a second comando on the same data root says so and exits; the operating system releases it, also after a crash | `Main` |
| `run/` | one MCP configuration per running session, mode `0600`, deleted when the process exits | `ClaudeAdapter` |
| `audit/audit-<UTC date>.jsonl` | every MCP tool call and approval decision | `AuditLog` |
| `logs/comando-<n>.log` | the application log, 5 files of 5 MB, rotated | `Logging` |
| `logs/intents-<epoch ms>.jsonl` | the intent log, when recording is on ([Quality](quality.md)) | `IntentLog` |
| `questdb/` | the embedded QuestDB's data, when the user starts it | `EmbeddedQuestDb` |

Outside the data root:

| Location | Holds |
|---|---|
| macOS login keychain, service `comando` | connection passwords, one item per connection name ([Approvals and safety](approvals-and-safety.md#5-credentials)) |
| Java preferences, node `dev/marregui/comando` | window size, position and maximised state, divider positions, whether the sidebar shows and the side pane is hidden, the last Gradle tasks, recording, jfrq arguments and export directory; tests use another node through `comando.prefsNode` |
| `~/.claude/projects/` | Claude Code's conversations; comando reads them and never writes them |

## 2. Atomic store files

`JsonFile` writes a file to a temporary file in the same directory, then moves it over the old one with `ATOMIC_MOVE`. A crash leaves the old content or the new, never a torn file. A file that cannot be parsed at start is renamed to `<name>.corrupt-<epoch ms>` and comando starts without it, so the next save does not destroy what the user may want to recover.

`Store` runs every write from an immutable snapshot of the state, so a save never races an edit. Sessions and panels are written on one thread; connections on another, because a connection save talks to the keychain, which may wait on an unlock prompt, and panel text must not queue behind it. Connection and session changes are written at once. Panel text is written 800 ms after the first change not yet saved, so continuous typing saves about every 800 ms; prompts likewise. `Store.close()` writes what is pending.

## 3. What comes back after a restart

| Thing | Restored as |
|---|---|
| connections | closed; a password is read from the keychain when the connection is loaded; when the keychain cannot be asked, the connection loads without it and the stored item is kept |
| sessions | closed; a session with a conversation id shows its stored transcript, read from Claude Code's file without starting a process |
| panels | their bindings, and their text from their files, edits made there while comando was closed included; a binding to a name that no longer exists is dropped; a file that cannot be read leaves its panel empty and is left alone; with no panel left, one panel named `scratch` with the welcome text |
| window | size, position, maximised state, dividers, sidebar and side pane visibility; saved a second after each resize or move and at quit; bounds are kept on a screen that still exists, else the window opens centred on the main screen; a side pane divider outside the window is ignored |
| views, results, terminals | not restored; they belong to one run |

Nothing is sent to a session until the user sends something; the first prompt resumes the conversation.

## 4. A fresh data root

On a data root without store files, comando writes defaults: a connection named local QuestDB with QuestDB's defaults, and one panel, scratch, bound to it, with the welcome text.

## 5. Panel files

Each panel's text is a Markdown file of its own in `panels/`, so a panel can be a full spec that other tools read, diff or keep in Git.

| Event | What happens to the file |
|---|---|
| a new panel | named after its title when it first saves: characters a file name cannot hold (`/`, `\`, `:`, control characters) become `-`, a leading dot goes, 80 characters at most; a name a file in the folder or another open panel has (compared ignoring case, as APFS does) gets ` 2`, ` 3`… |
| typing | the file is written 800 ms after the first unsaved change, through a temporary file moved over it; only panels whose text changed are written |
| rename | the file moves to a free name for the new title |
| close | the last text is written, also for a panel closed before its first save; the file stays when it holds text, and is deleted when blank; when the file cannot be named or written, the closed panel stays in `panels.json` with its text, keeps its file name from new panels, is tried again at every save, and comes back as a panel at the next start if it never lands; ⌘⇧T on it takes that text |
| a file that cannot be written | the panel's text goes into `panels.json` as `content`, which the next start prefers; the status bar says so once; comando tries again every 5 s and on every save |
| `panels.json` that cannot be written | the status bar says so once; comando tries again every 5 s; the panels' files are written regardless |
| Panels ▸ Open spec… (⌘O) | a file of the folder opens as a new panel titled after it, read on the store's thread rather than the EDT; a file already open brings its panel forward; a file another panel holds or is opening, a file elsewhere, or one gone is refused, checked on the store's thread with the binding |
| ⌘⇧T | the reopened panel takes its file again, read after the closing panel's last write to it; when the file is gone, the panel comes back with the text it had |
| an edit made outside comando (another editor, Claude's Edit tool) | the store checks each open panel's file once a second, on its writer thread, against the modification time and size of comando's last write or read; a changed file reloads into its panel when the panel has no unsaved edits: the caret stays, ⌘Z brings back the replaced text, and the status bar says "Reloaded <name>" ([record 27](../adr/0027-panels-reload-files-changed-outside.md)); when the panel has unsaved edits, the next save moves the outside version to `<name> (outside comando <date time>).md`, says so, and writes comando's text |
| a file deleted outside comando | the status bar says so once; the panel keeps its text and writes the file again on its next save |
| a `panels.json` from before panel files | its `content` moves into the panels' files at the first save |

