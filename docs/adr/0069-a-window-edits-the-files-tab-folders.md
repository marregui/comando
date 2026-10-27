# 69. A window edits the Files tab's folders

| Date | Status | Decided by |
|---|---|---|
| 2026-10-19 | accepted; the row "where" replaced by [record 71](0071-repositories-in-the-files-tab.md); the title, the problems line and the index creation amended by [record 73](0073-the-claude-folders-window-explains-and-creates-the-index.md) | Miguel and Claude |

## 1. Context

[Record 55](0055-the-files-tab-only-shows-folders-json-configures-it.md) left `folders.json` as the only way to add,
remove or change the Files tab's folders: Files ▸ Files tab folders opened the file in a panel. With eight folders,
three of them git checkouts, editing JSON by hand is the slow path, and nothing checks an entry until the file is
saved.

This record replaces the configuration row of record 55.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| where | Files ▸ Files tab folders opens the window; a folder row's menu in the Files tab adds Edit folder…, which opens it with that folder selected; the tab keeps no buttons | the menu, and the row the user is looking at |
| shape | one modal window over comando's: the folders on the left in the tab's order, each with its row icon (the git icon for a repository's top folder) and its name; under the list +, −, ↑, ↓ (also ⌥↑ ⌥↓); the selected folder's fields on the right; Cancel and Save at the bottom | a list and its details |
| fields | Name, Folder (with a … chooser), Index file, Leave out (comma-separated; its hint names the names always left out), Icon (a choice showing each icon), Trash, Listed in (off unless Trash is on); all shown | every field in view, Leave out as text |
| facts | under the fields, read only: whether the folder is a git repository's top folder or inside one, with its branch and count of changes, or "not in a git repository" | what the tab will show, before saving |
| add | + opens the desktop's folder chooser; the folder picked becomes a new entry, selected: name the folder's name (made unique with a number), index the first of `README.md`, `ALPHA.md`, `MEMORY.md` found in it (since record 73: `00_START_HERE.md`, `INDEX.md`, `README.md`, `MEMORY.md`), icon folder; Cancel in the chooser adds nothing | the folder first |
| remove | − takes the selected folder out of the list; nothing on disk changes; Cancel brings it back | the window's own Cancel is the undo |
| check | each field is checked as it is typed, with `FolderKind`'s rules, a name used once, and a folder that exists and can be read; a field that fails says why beside it in red, the list marks its folder, and Save is off until every folder passes | a missing folder blocks Save |
| apply | Save writes the whole list to `folders.json` once (`Intent.SaveFolders`) and the tab follows; Cancel, Esc or the window's close drop the edits, asking first when there are any | Save and Cancel, as in a dialog |
| JSON | the window has no JSON route; `folders.json` stays the store, is still read again when edited outside comando, and the kit still adds its folders to it | one way to edit, in the window |
| outside edit | a change to `folders.json` while the window is open does not reach the window; Save replaces the file, and says it replaced a change made meanwhile | one writer at a time; nothing lost without a notice |

Miguel decided a window from the Files menu, a list with details, Save and Cancel, no JSON route, an Edit folder… item
on a folder row, a new folder chosen first, every field shown, Leave out as text, and a missing folder blocking Save;
Claude decided how: the window's parts, its keys, what the details show, the intent, and an outside edit while it is
open.

## 3. Consequences

`Intent.SaveFolders` joins `AddFolder` and `FoldersRead`: it replaces the folders, saves them, and asks for the
repositories again. The check that a folder exists is the window's, not `FolderKind`'s: an entry in `folders.json`
naming a missing folder still loads and shows "✗ cannot be read" in the tab, as before, but the window will not save
the list until that folder is fixed or removed.
