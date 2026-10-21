# 76. New files are gold and marked from the top

| Date | Status | Decided by |
|---|---|---|
| 2026-10-20 | accepted; amends the colours of [record 68](0068-git-changes-and-a-side-by-side-diff.md) | Miguel (new files in the Session tab's gold, stronger on the sidebar's grey; a trail of icons from the top down to them; a new file opened once reads as any other), Claude (how: one colour token, a dot after the name of each folder on the way down, the opened files kept in `opened-files`) |

## 1. Context

The Files tab coloured an untracked file `#D1675A`, a red close to the conflicted red `#E0525C`. Miguel read the red as
"needs my attention" and wanted it in the gold of the Session tab's text (`#E0C48A`, on `#121212`). The sidebar's
ground is `#1E1E1E`, lighter than the transcript's, so the same gold would stand out less there. A folder holding any
change already had a blue name, but nothing told a closed folder holding a new file apart from one holding a modified
file, and he had to look around the tree to find the new files.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| an untracked file's colour | `Theme.VCS_UNTRACKED` = `#F0BA4C`: the transcript amber's hue (40°), saturation 0.85 instead of 0.58; contrast 9.4:1 on `#1E1E1E` | Miguel: the same gold, a bit stronger on the sidebar's grey |
| where the colour shows | the token, so the Files tab, the Changes view's untracked badge (`changes-untracked.svg`) and every other user follow together | one meaning, one colour |
| the trail | a gold dot (`new-file.svg`) after the name of a new file and of every folder between it and the folder's row, open or closed | Miguel: a trail from the root to the leaves, to open those folders first |
| a repository's row | keeps its rising arrow while it has changes; the dots start under it (replaced by [record 78](0078-a-rows-marks-and-double-click-show-its-changes.md): a row shows the arrow, then the dot) | one mark per row; the arrow already says the repository has changes |
| a marked folder's tooltip | "Open to reach the new files inside, which git does not track yet" | the dot names nothing by itself |
| a selected row | keeps its dot (selection drops the name's colour) | the end of the trail stays visible on the row the user is on |
| a new file once opened | reads as any other file: no gold, no dot; a folder holding only opened new files is neither blue nor dotted | Miguel: once opened for the first time, render it as a normal read file |
| what counts as opening | opening it in a panel (the Files tab, Open file…, the Changes view, a reopened panel) or showing it in the side pane (a link in the transcript or a tree) | every way a file reaches the user's eyes in comando |
| where it is kept | `opened-files` in the data root: one absolute path per line, oldest first, at most 20 000 (the oldest dropped), written whole after each newly opened file on a virtual thread, temp file then atomic move | survives a restart; a path list is the smallest form that answers "opened before?" |

## 3. Consequences

`RepoChanges` stays git's view: the Changes view and a folder's Show changes inside still count an opened new file.
The Files tab works out its own two sets, the folders holding a marked file and those on the way down to an unopened
new file, when the repositories are read again or a file is opened. Untracked (gold) and conflicted (red) no longer
share a hue. A repository's rising arrow and its tooltip still count every change, opened new files included. A file
opened, then rewritten by a session, stays unmarked: opening is recorded once, not per version. A file git starts to track (`git add`) leaves
the trail at the next read (every 5 s for an open folder).
