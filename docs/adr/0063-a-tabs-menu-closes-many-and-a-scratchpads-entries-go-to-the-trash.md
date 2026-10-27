# 63. A tab's menu closes many; a scratchpad's entries go to the Trash

| Date | Status | Decided by |
|---|---|---|
| 2026-10-18 | accepted | Miguel and Claude |

## 1. Context

Following links opens many documents, each in a tab; closing them was one × at a time. The scratchpad fills with day
folders, reports and agent outputs that are read once; deleting them meant leaving comando, and each left its line in
the day's `ALPHA.md`. [Record 55](0055-the-files-tab-only-shows-folders-json-configures-it.md) removed every file
operation from the Files tab, the Trash included.

This record makes an exception to record 55's "the tab writes nothing", for folders marked deletable.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| tab menu | a right click on a tab's header: Close, Close others, Close to the right, Close to the left, in the order shown | the user arranges tabs by dragging, then closes what is done |
| what a close is | [record 39](0039-panels-are-kept-until-removed.md)'s: a panel of comando's own is hidden and kept, a document closes; never Remove | one meaning of close |
| what a bulk close keeps | a tab waiting for the user (an approval, a question) and a tab whose shell runs a program; the tabs are taken as the menu opens; one notice, once they are gone, says how many closed and stayed, in place of one per tab; the clicked tab goes in front | an approval is never hidden; a single close asks before ending a program, a bulk one does not ask, so it keeps it |
| mid-turn tabs | closed; the session runs on | the Panels tab and the Dock still show its outcome |
| which folders | an entry of `folders.json` with `"deletable": true`; the kit sets it on its Scratchpad; no other folder offers it | the memory, the KB and the skills are kept by `/reflect` and git, not by hand |
| what goes | files and subfolders, several at once; never the folder's row; a selection reaching another folder offers nothing | a day folder is a unit of work |
| how | one question naming what goes, then the desktop's Trash; without a Trash nothing moves; an entry the Trash refuses is named and the rest go; each entry's listing is found before anything moves | nothing is deleted for good |
| the listing | `"listedIn": "ALPHA.md"`: the nearest such file in the entry's folder or above, up to the configured folder, loses the entry's own lines | the day's index stays true without a manual pass |
| own lines | a list item or table row whose first element (code span, link target, bare text) is the entry's path relative to the listing's folder, a folder's with its slash, with the lines indented under an item; never a line in a fenced code block; other lines naming it by its whole name stay, and the notice gives their numbers in the file as it is now; no renumbering; `\r\n` kept | a line about several things is the user's to reword |

Miguel decided the menu's items, which tabs a close keeps, the Trash for files and folders of the scratchpad only,
multi-selection, a flag in `folders.json`, the scratchpad's index lines removed, own lines only, and the rule kept
general; Claude decided how: the keys' names, what a line's own subject is, the notice, and the shell job that keeps a
tab.

## 3. Consequences

`FolderRoot` gains `deletable` and `listedIn` (`withTrash`), and `FolderKind` checks that `listedIn` is a file name.
`Intent.DeleteFiles` and `Effect.DeleteFiles` return, now for many paths of one folder; the reducer refuses a folder
not deletable, the folder itself and a path outside it. `ToolPort.deleteFiles` and `ToolsAdapter.Trash` return;
`ToolsAdapter` moves the entries one at a time under a lock, edits each listing file once through a temporary file,
and reads the open folders again. `ListingLines` finds an entry's own lines. `ui.kit.desktop.DesktopTrash` returns,
injected by `App`. `TreeView` keeps a multi-selection on a right click inside it and across a refresh. `Workspace`
gains the tab menu and `onRunsProgram`, which `ComandoUi` answers from the panel's terminal.
