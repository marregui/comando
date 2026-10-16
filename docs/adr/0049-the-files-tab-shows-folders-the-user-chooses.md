# 49. The Files tab shows folders the user chooses

| Date | Status | Decided by |
|---|---|---|
| 2026-10-16 | accepted; replaces the fixed folder and the always-on read of [record 29](0029-the-scratchpad-is-a-navigator.md), and the Scratchpad tab of [record 40](0040-one-resource-per-dialog-and-two-tab-rows.md) | Miguel (one tab named Files rather than a tab per folder; folders as top-level rows, configured like connections; a filter; an index file opened with its folder; only open folders kept in sync; build output left out; `~/.claude` as the default), Claude (how) |

## 1. Context

The Scratchpad tab showed one fixed folder, `~/.claude/SCRATCHPAD`, read every 2 s whether the tab showed or not.
Miguel also reads and edits the knowledge base, the memory files, the skills and the plugin under `~/.claude`, and had
no way to browse them from comando. A tab per folder would crowd the sidebar. The fixed path was also one user's
convention: comando is to be public, and it may not depend on how a user lays out `~/.claude`
([record 46](0046-clear-buttons-text-coloured-by-kind-checkpoint-due.md)).

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| tab | one tab, **Files**, in the Scratchpad tab's place (top row, after Panels) | one place for every folder; the sidebar keeps four tabs |
| top level | one row per configured folder, in the order configured; its detail is the path | each folder reads as a root, the tree below it as before |
| configuration | a folder is a resource like a connection: name (unique), path, index file (optional, inside the folder), names left out (comma-separated); added, edited, cloned and removed from the tab's bar or a row's context menu, in the resource dialog with Save only; a hand-edited `folders.json` meets the same rules at load, and an entry that does not (a duplicate name, a relative path, an index outside the folder) is left out with a notice | the dialog the user knows; a folder is neither tested, opened nor assigned |
| stored | `folders.json` in comando's folder; without it, one folder: Claude Code's own `~/.claude`, opening `CLAUDE.md`, leaving out Claude Code's transcripts, caches and per-session state | a new user sees something useful; a list the user emptied stays empty |
| left out | hidden entries; `node_modules`, `build`, `target`, `__pycache__`, `out` everywhere; then the folder's own names, ignoring case | build output and dependencies are never documents, and can be large |
| bounds | 12 levels and 5,000 entries per folder; at most 20,000 names listed (hidden ones included) and 5,000 entries looked at; past either, a folder ends with `…`; a symbolic link to a folder is listed, not followed; an unreadable subfolder says so and the rest stands | a folder read every 2 s costs a bounded read, whatever its size |
| index | opening a folder's row by hand opens its index file in a panel, when the file exists | the folder's table of contents comes with it (`ALPHA.md`, `MEMORY.md`) |
| sync | only folders whose rows are open are read every 2 s, and sent when they changed; a row opened is read at once; a closed one costs nothing | five folders, one or two open at a time; `~/.claude/KB` alone has hundreds of files |
| filter | a field above the tree; with text, a second tree shows the entries whose names contain it, with the folders leading to them open and a matching folder closed; every folder never read is read once so closed ones are searched; Esc clears it and the tree is as the user left it | finding a file by name across folders without opening each |
| order of reads | a tree carries the settings it was read with, and is shown only while the folder still has them; reads with the same settings take a ticket, and one finishing after a newer one has sent sends nothing | the tab never shows an older tree over a newer one, nor a tree under settings it was not read with |

## 3. Consequences

`FolderRoot` (core model) and `FolderKind` (core resource) describe a folder; `AppState.folders`, intents
`BrowseFolders`, `WatchFolders`, `FolderLoaded`, `SaveFolder`, `RemoveFolder` and effects `ReadFolders`,
`WatchFolders`, `ShowFolder`, `SaveFolders` replace `BrowseScratchpad`, `ScratchpadLoaded` and `ShowScratchpad`.
`FolderTree` (tools) reads a folder; `ToolsAdapter.readFolders`, `watchFolders` and `pollFolders` replace
`browseScratchpad` and `watchScratchpad`. `FilesTab` (ui panels) replaces `ScratchpadTab`; `ResourceActions` takes
any selection (a tree's top-level rows); `ResourceDialog` leaves out Test, Open/Close and Assign when the kind has none.
View ▸ Files shows the tab and puts the keyboard in its filter. The icons of record 29 stay; the index icon marks any
configured folder's index file name rather than `ALPHA.md`.
