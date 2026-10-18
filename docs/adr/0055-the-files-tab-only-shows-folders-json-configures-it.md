# 55. The Files tab only shows; folders.json configures it; every file opens to edit

| Date | Status | Decided by |
|---|---|---|
| 2026-10-17 | accepted; removes the bar, the folder dialog and the configuration row of [record 49](0049-the-files-tab-shows-folders-the-user-chooses.md), the rows bar, files and read-only of [record 51](0051-folder-buttons-together-read-only-folders-icons.md), the rows bar, tips, clone a file, remove a file and read-only of [record 52](0052-files-open-on-a-double-click-the-bar-follows-the-selection.md); the Trash returns for deletable folders in [record 63](0063-a-tabs-menu-closes-many-and-a-scratchpads-entries-go-to-the-trash.md) | Miguel (no buttons in the Files tab, which is read-only; the files it opens are edited, saved and added to like any other; folders configured by a menu entry that opens `folders.json`; the read-only flag and the tab's file operations dropped), Claude (how: the menu entry's place, the file read again when it changes, what a file that does not read does) |

## 1. Context

The Files tab had a bar (add, edit, clone, remove) and a context menu that added, edited and removed its folders through
the resource dialog, and cloned files or moved them to the Trash. A folder marked `readOnly` (the kit marked the memory
folder so) opened its files in panels that could not be edited. The tab is where files are found and opened; the
writing belongs to the panel that opens a file, and to Claude.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| tab | no buttons; the filter stays; a file's menu offers Open in a panel and Edit in a panel, a folder row's its index file; nothing in the tab writes a file or the configuration | the tab shows and opens |
| files | every file opens in a panel that can be edited and saved, Markdown rendered with its Edit switch; a `.log` stays read-only, as the program writing it owns it | the user's files are the user's to change |
| read-only | gone: `FolderRoot` has no `readOnly`; a `readOnly` key in an existing `folders.json` is ignored; the kit marks no folder | nothing is left for it to protect |
| configuration | Panel ▸ Tools ▸ Files tab folders opens `folders.json` in a panel, written first with the tab's folders when missing; the folder dialog is gone | one file, edited like `tools.json`; the entries' rules are unchanged |
| follow | the store checks `folders.json` every second, with the open panels' files; a change it did not write is read again and the tab shows the new folders; entries that break the rules are left out with a notice; a file that does not parse keeps the tab's folders, says why and stays where it is; its own writes (the kit adding its folders) are not read back | a save shows within a second; a file half edited is never moved aside |
| kit | adds its folders as before (`Intent.AddFolder`), which saves `folders.json` | the one writer of the configuration besides the user |

## 3. Consequences

`Intent.RemoveFolder`, `Intent.CopyFile`, `Intent.DeleteFile`, their effects, `ToolPort.copyFile` and `deleteFile`,
`ToolsAdapter.Trash` and `ui.kit.desktop.DesktopTrash` are gone; `Intent.SaveFolder` becomes `Intent.AddFolder`, and
`Intent.FoldersRead` carries a re-read `folders.json`, saving nothing. `FolderKind` stays as the entries' rules.
`ResourceActions` loses `leftAligned`, `addTip` and `Target`, which only the Files tab used. `SessionsSidebar.Dialogs`
loses `folders()`; `EditorPanel.file` loses its read-only argument. `Store` gains `foldersFile`, `onFoldersChanged`
and its check; `JsonFile.readAsIs` reads without moving a bad file aside. `Setup.folderSettings` reaches the file from
the menu.
