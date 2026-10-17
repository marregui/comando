# 51. Folder buttons together, read-only folders, an icon per folder

| Date | Status | Decided by |
|---|---|---|
| 2026-10-16 | accepted, but for the row "files", proposed; its rows bar, files and read-only removed by [record 55](0055-the-files-tab-only-shows-folders-json-configures-it.md); amends the bar, the sync interval and the order of [record 49](0049-the-files-tab-shows-folders-the-user-chooses.md) | Miguel (the folder buttons grouped at the left; no refresh button, the tab follows the disk, every 5 s; the memory folder read-only, written by Claude Code only; an icon for the memory, the knowledge base, the skills and the plugin; `MEMORY.md` first, then folders, then files), Claude (how: read-only and the icon as folder settings, the index file leading any folder, the reducer refusing as the bar does, the icons' shape) |

## 1. Context

Record 49's bar split the Files tab's buttons as on the Sessions and Connections tabs: add and refresh at the left,
edit, clone and remove at the right. In the Files tab most rows are files, which those buttons never act on, so the two
groups read as two sets with different targets. The refresh button read the open folders at once, though the tab
already reads them every 2 s. The memory folder is Claude Code's: comando could edit its files and edit or remove its
row like any other. Every folder row had the same icon, and a folder's index file sorted among its files.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| bar | add, edit, clone and remove in one group at the left edge, a gap between add and the three that act on the selected folder row; no refresh button, and no "Read again" in a row's menu | one set of buttons acting on folders; nothing to press to see the disk |
| sync | open folders are read every 5 s (was 2 s); a row opened, a folder edited while open and the filter still read at once | a session's writes show within seconds; half the reads of 2 s |
| files | the buttons act on folder rows only, as before; a file is opened, never edited as a resource, cloned or deleted from the tab | comando opens a user's files but does not delete them ([record 39](0039-panels-are-kept-until-removed.md)); the tips say "folder" because that is what the buttons act on |
| read-only | a folder setting, `readOnly` in `folders.json`, set by the kit on the memory folder, not offered in the dialog; its row's detail says "read-only"; its row is neither edited, cloned nor removed (buttons off, menu offers the index file only, the reducer refuses `SaveFolder` and `RemoveFolder` on it with a notice naming `folders.json`); a file under it opens in a panel that cannot be edited, Markdown rendered without the Edit switch | Claude Code writes the memory; a setting rather than a name keeps comando free of one user's layout |
| icon | a folder setting chosen in the dialog from folder, memory, knowledge, skills, plugin; the kit sets memory and knowledge on its folders; drawn as the folder with a badge in the colour of its kind (memory purple as the checkpoint, knowledge blue, skills amber, plugin orange) | each top-level row recognisable at 16 px |
| order | at a folder's top level, its index file first, then folders, then files; deeper levels as before | the folder's table of contents leads it (`MEMORY.md`, `ALPHA.md`) |

## 3. Consequences

`FolderRoot` gains `icon` and `readOnly`; `FolderKind` gains the Icon choice and rejects an unknown icon (a hand-edited
entry with one is left out at load with a notice); `Store.loadFolders` carries `readOnly` past the dialog's rules.
`ResourceActions.leftAligned` puts both groups at the left; only the Files tab uses it. `FilesTab` hides a read-only
folder from the bar's selection; `Workspace` passes `EditorPanel.file` whether the file is under a read-only folder.
`FolderTree` ranks the top-level index file first. `App.FOLDERS_EVERY` is 5 s. Icons `folder-memory`,
`folder-knowledge`, `folder-skills`, `folder-plugin`. A user's existing `folders.json` keeps the plain icon and no
read-only folder until edited; the kit's folders added later carry both.
