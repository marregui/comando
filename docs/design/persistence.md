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
| `cache/mermaid/<hash>.png` | Mermaid diagrams `mmdc` drew for the previews, one per diagram text; safe to delete ([ADR 35](../adr/0035-mermaid-diagrams-drawn-by-mmdc.md)) | `MermaidDiagrams` |

Outside the data root:

| Location | Holds |
|---|---|
| macOS login keychain, service `comando` | connection passwords, one item per connection name ([Approvals and safety](approvals-and-safety.md#5-credentials)) |
| Java preferences, node `dev/marregui/comando` | window size, position and maximised state, divider positions, whether the sidebar shows and which of its tabs is in front, whether the side pane is hidden, the last Gradle tasks, recording, jfrq arguments and export directory; tests use another node through `comando.prefsNode` |
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
| window | size, position, maximised state, dividers, sidebar and side pane visibility, the sidebar's tab; saved a second after each resize or move and at quit; bounds are kept on a screen that still exists, else the window opens centred on the main screen; a side pane divider outside the window is ignored |
| views, results, terminals | not restored; they belong to one run |

Nothing is sent to a session until the user sends something; the first prompt resumes the conversation.

## 4. A fresh data root

On a data root without store files, comando writes defaults: a connection named local QuestDB with QuestDB's defaults, and one panel, scratch, bound to it, with the welcome text.

## 5. Panel files

Each panel's text is a Markdown file of its own in `panels/`, so a panel can be a full spec that other tools read, diff or keep in Git.

| Event | What happens to the file |
|---|---|
| a new panel | its file is written at once, empty or not ([record 39](../adr/0039-panels-are-kept-until-removed.md)); named after its title: characters a file name cannot hold (`/`, `\`, `:`, control characters) become `-`, a leading dot goes, 80 characters at most; a name a file in the folder or another open panel has (compared ignoring case, as APFS does) gets ` 2`, ` 3`… |
| typing | the file is written 800 ms after the first unsaved change, through a temporary file moved over it; only panels whose text changed are written |
| rename | the file moves to a free name for the new title |
| close | a panel of comando's own: the last text is written, the file stays, blank or not, and `panels.json` keeps the panel with `"closed": true`, its title and bindings; its text stays in memory, so it reopens at once (the Panels tab, ⌘⇧T, opening its session, ⌘O on its file); when the file cannot be named or written, the text waits in `panels.json` as `content` and is tried again at every save. A document: its last text is written and comando lets go of it |
| remove | the panel leaves `panels.json` and its own file is deleted; a document's file never is |
| a file that cannot be written | the panel's text goes into `panels.json` as `content`, which the next start prefers; the status bar says so once; comando tries again every 5 s and on every save |
| `panels.json` that cannot be written | the status bar says so once; comando tries again every 5 s; the panels' files are written regardless |
| File ▸ Open file… (⌘O), a click in the Scratchpad tab | the file opens as a new panel titled after it (a `.md` name without its extension), read on the store's thread rather than the EDT; a file of `panels/` is bound by its name, a file elsewhere by its absolute path; a file already open brings its panel forward; a file another panel holds or is opening, or one gone, is refused, checked on the store's thread with the binding; a binary file or one over 2 MB shows read-only in the side pane |
| a panel on a file elsewhere (a scratchpad document) | saved, reloaded and kept aside like a panel's own file; never renamed when the panel is, never deleted when blank; comes back on that path after a restart (`panels.json` holds the absolute path) |
| ⌘⇧T | a closed panel of comando's own comes back with its text and bindings; a document opens on its file again |
| a file of `panels/` that no panel names, at the start | becomes a closed panel titled after it, with no bindings, and a notice names it; also when `connections.json`, `sessions.json` and `panels.json` are gone. A stored name names a file only when it is the same file on the volume |
| a document whose last write fails at close | kept in `panels.json` with its text and its absolute path; it comes back as an open document at the next start, never as a file of `panels/` |
| an edit made outside comando (another editor, Claude's Edit tool) | a closed panel takes it at once; the store checks each open panel's file once a second, on its writer thread, against the modification time and size of comando's last write or read; a changed file reloads into its panel when the panel has no unsaved edits and was not edited after the change was seen, saved or not: the caret stays, ⌘Z brings back the replaced text, and the status bar says "Reloaded <name>" ([record 27](../adr/0027-panels-reload-files-changed-outside.md)); when the panel has unsaved edits, the next save moves the outside version to `<name> (outside comando <date time>).md`, says so, and writes comando's text |
| a file deleted outside comando | its panel, open or closed, is removed, unless it has unsaved edits, typed before or after comando saw the file gone, which its save writes back. At the start: a document's or a closed panel's missing file leaves no panel, with a notice ([ADR 34](../adr/0034-a-deleted-document-closes-its-panel.md)); an open panel's gets an empty file, as an older comando never wrote a blank panel's |
| a `panels.json` from before panel files | its `content` moves into the panels' files at the first save |

