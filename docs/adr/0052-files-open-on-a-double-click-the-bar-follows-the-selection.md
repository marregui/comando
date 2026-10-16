# 52. Files open on a double click; the bar follows the selection and acts on files

| Date | Status | Decided by |
|---|---|---|
| 2026-10-16 | accepted; replaces the one click and the index opened with its folder of [record 49](0049-the-files-tab-shows-folders-the-user-chooses.md), and the row "files" of [record 51](0051-folder-buttons-together-read-only-folders-icons.md), to which it adds the scratchpad icon | Miguel (a click on a folder opens no file; only a double click on a file opens it; the bar follows the selection, add off for a file; the bar acts on files too, each tip saying whether it affects a file or a folder; a scratchpad icon; the New panel button at the right end of the panel tabs), Claude (how: what edit, clone and remove do to a file, the Trash, what a folder inside a folder and an empty selection turn on, the pencil, the button's placement) |

## 1. Context

Record 49 opened a file with one click and, when a folder's row was opened by hand, its index file in a panel: browsing
the tree opened panels the user had not asked for. The bar's add stayed on whatever was selected, while edit, clone and
remove followed only top-level rows, so the buttons read as not following the tree. The Scratchpad row had the plain
folder icon. The panel tabs' New panel button sat in the middle of the space the tabs leave, since the look stretches
the tab area's trailing component over that space.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| click | a click selects and opens nothing; a double click opens a folder, or a file in a panel; Enter and the menu's "Open in a panel" open a file | a panel opens only when the user asks for a file |
| index | opening a folder opens no panel; the index file leads its folder's entries and the row's menu offers it by name | the table of contents stays one double click away |
| bar | a folder row: add, edit, clone and remove on, a read-only one keeps add only; a file: edit, clone and remove on, add off (it adds a folder to the tab), none for a file of a read-only folder; a folder inside a folder: add only; nothing selected: add only, so an empty tab can gain its first folder | the bar acts on what is selected; what does not apply is off |
| tips | each names what it affects: "Edit folder KB…", "Remove folder KB from the Files tab…", "Edit file notes.md in a panel", "Move file notes.md to the Trash…"; an off button says why ("Edit file MEMORY.md: folder Memory is read-only"); add says "Add a folder to the Files tab…" | the user knows before the click whether a file or a folder changes |
| edit a file | opens it in a panel showing its text, Markdown included, or switches the panel that has it to its text | edit means the text |
| clone a file | asks for the copy's name, "notes copy.md" offered; a name with a folder in it, `.`, `..` or the original's is refused with a notice; the copy goes beside the original and never over another file | the Finder's habit; nothing is overwritten |
| remove a file | asks first, the safe answer the default: "Move notes.md to the Trash? It can be put back from there." where the desktop has a Trash, else "Delete notes.md for good? … it cannot be undone."; a Trash asked for and missing fails rather than deleting for good; folders are never deleted from the tab | a deletion can be taken back where the desktop allows; consent to the Trash is not consent to delete |
| read-only | the reducer refuses a copy into, or a deletion in, a read-only folder, with a notice; a copy out of one is allowed | as record 51 for the folder itself |
| scratchpad icon | `scratchpad`, the folder with a green pencil; the kit sets it on its Scratchpad | each of the kit's folders recognisable |
| New panel | at the right end of the panel tabs' row, centred in its height | a fixed place, as the lower panel's Clear |

## 3. Consequences

`Intent.CopyFile` and `Intent.DeleteFile` become `Effect.CopyFile` and `Effect.DeleteFile` unless the reducer refuses
them; `ToolPort.copyFile` and `deleteFile` run on virtual threads (`file-copy`, `file-delete`) in `ToolsAdapter`, which
says how each went in a notice and reads the open folders again at once. The Trash is
`ui.kit.desktop.DesktopTrash`, handed to the adapter by `App`, so the tools module stays free of AWT.
`ResourceActions.Target` lets a list decide what edit, clone and remove do and say; `FilesTab` gives one for every
selection. `SessionsSidebar.Dialogs.editFile` and `Workspace.editWhenOpen` open a file showing its text.
`TreeView` loses its one-click mode: every tree activates a leaf on a double click, and JTree opens a folder on one.
`FilesTab` no longer looks for the index file when a row opens, so it no longer takes an executor.
`ResourceActions.Selection.canAdd` (true by default) turns add off; `FilesTab` answers false while a file is selected.
`Workspace` wraps the New panel button in a panel that places it at the east end.
