# 97. A changed file's trail of arrows, as a new file's trail of dots

| Date | Status | Decided by |
|---|---|---|
| 2026-10-27 | accepted | Miguel and Claude |

## 1. Context

[Record 76](0076-new-files-are-gold-and-marked-from-the-top.md) marks a new (untracked) file not opened yet with a dot,
and every folder on the way down to it, so the closed folders to open are marked from the folder's row down.
[Record 78](0078-a-rows-marks-and-double-click-show-its-changes.md) put the rising arrow on a folder's row and a
repository's top folder while what they hold differs from `HEAD`. A file git tracks that differs (changed, added,
renamed, in conflict) and the folders holding it had only git's colour, blue for a folder: on 2026-10-27 Miguel edited
`CLAUDE.md` in the kit, saw the Kit row's arrow, and found no mark to follow down to the file.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what is marked | every file git tracks that differs from `HEAD`, and every folder on the way down to one, carries the rising arrow after its name, as the row does | Miguel: "there is no icon in the file so I cannot follow the breadcrumb"; the arrow already means "differs from `HEAD`" on the rows |
| new files | unchanged: the dot down to a new file not opened yet; once opened, neither mark | record 76 |
| both | a folder on the way down to both shows the arrow, then the dot, as a row does | record 78 |
| until when | while the file differs from `HEAD`: a commit or a roll back clears the trail; opening the file does not | a change is cleared by git, a new file's novelty by reading it |
| deleted files | have no row; the folders that held them carry the arrow | the trail ends at the folder to look in |
| tooltip | a folder on the trail: "Open to reach the files inside that differ from the last commit"; the file: "Differs from the last commit; right-click to compare with HEAD" | as the dot's tooltip |
| where | both trees, the folders' and the filter's | one renderer for both |

## 3. Consequences

Tested in `FilesTabTest`: the arrows run down to the changed file and the dots to the new one, each tooltip, and the
changed file's trail stays after the new file is opened.
