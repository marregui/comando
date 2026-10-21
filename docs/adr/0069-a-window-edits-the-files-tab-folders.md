# 69. A window edits the Files tab's folders

| Date | Status | Decided by |
|---|---|---|
| 2026-10-19 | accepted; replaces the configuration row of [record 55](0055-the-files-tab-only-shows-folders-json-configures-it.md); its "where" row replaced by [record 71](0071-repositories-in-the-files-tab.md) | Miguel (a window from the Files menu, a list with details, Save and Cancel, no JSON route, an Edit folder… item on a folder row, a new folder chosen first, every field shown, Leave out as text, a missing folder blocks Save), Claude (how: the window's parts, keys, what the details show, the intent, an outside edit while it is open) |

## 1. Context

Record 55 left `folders.json` as the only way to add, remove or change the Files tab's folders: Files ▸ Files tab
folders opened the file in a panel. Eight folders later (three of them git checkouts), editing JSON by hand is the
slow path, and nothing checks an entry until the file is saved.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| where | Files ▸ Files tab folders opens the window; a folder row's menu in the Files tab adds Edit folder…, which opens it with that folder selected; the tab keeps no buttons | Miguel: the menu, and the row the user is looking at |
| shape | one modal window over comando's: the folders on the left in the tab's order, each with its row icon (the git icon for a repository's top folder) and its name; under the list +, −, ↑, ↓ (also ⌥↑ ⌥↓); the selected folder's fields on the right; Cancel and Save at the bottom | Miguel: list and details |
| fields | Name, Folder (with a … chooser), Index file, Leave out (comma-separated; its hint names the names always left out), Icon (a choice showing each icon), Trash, Listed in (off unless Trash is on); all shown | Miguel: every field, Leave out as text |
| facts | under the fields, read only: whether the folder is a git repository's top folder or inside one, with its branch and count of changes, or "not in a git repository" | what the tab will show, before saving |
| add | + opens the desktop's folder chooser; the folder picked becomes a new entry, selected: name the folder's name (made unique with a number), index the first of `README.md`, `ALPHA.md`, `MEMORY.md` found in it, icon folder; Cancel in the chooser adds nothing | Miguel: the folder first |
| remove | − takes the selected folder out of the list; nothing on disk changes; Cancel brings it back | the window's own Cancel is the undo |
| check | each field is checked as it is typed, with `FolderKind`'s rules, a name used once, and a folder that exists and can be read; a field that fails says why beside it in red, the list marks its folder, and Save is off until every folder passes | Miguel: a missing folder blocks Save |
| apply | Save writes the whole list to `folders.json` once (`Intent.SaveFolders`) and the tab follows; Cancel, Esc or the window's close drop the edits, asking first when there are any | Miguel: Save and Cancel |
| JSON | the window has no JSON route; `folders.json` stays the store, is still read again when edited outside comando, and the kit still adds its folders to it | Miguel: drop it |
| outside edit | a change to `folders.json` while the window is open does not reach the window; Save replaces the file, and says it replaced a change made meanwhile | one writer at a time; nothing lost silently |

## 3. Consequences

`Intent.SaveFolders(folders)` joins `AddFolder` and `FoldersRead`: it replaces the folders, saves them, and asks for
the repositories again. The check that a folder exists is the window's, not `FolderKind`'s: an entry in `folders.json`
naming a missing folder still loads and shows "✗ cannot be read" in the tab, as before, but the window will not save
the list until that folder is fixed or removed.
