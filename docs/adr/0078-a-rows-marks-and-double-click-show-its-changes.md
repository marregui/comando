# 78. A row's marks and double click show its changes; one click opens a folder

| Date | Status | Decided by |
|---|---|---|
| 2026-10-20 | proposed; amends the clicks of [record 52](0052-files-open-on-a-double-click-the-bar-follows-the-selection.md), the double click of [records 68](0068-git-changes-and-a-side-by-side-diff.md) and [71](0071-repositories-in-the-files-tab.md), and the repository row's marks of [record 76](0076-new-files-are-gold-and-marked-from-the-top.md) | Miguel (the changes mark on the `~/.claude` folders' rows too; one click opens or closes a folder; a double click on a row shows the changes of that row's subtree, on rows only), Claude (how: the arrow and the dot together on a row that has both; the double click's first click undone) |

## 1. Context

The rising arrow marked only a repository's top folder; the Files tab's folders of `~/.claude` (KB, Scratchpad,
Memory, Skills, Plugin) sit inside the `~/.claude` repository and never showed it, so their uncommitted changes were
seen only by opening the Changes view of the whole repository. A click on a folder selected it; opening it took a
double click, and a repository's row kept its double click for its Changes view.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| which rows show the arrow | every folder's row (a folder of `~/.claude` or a repository) while what it holds differs from `HEAD`, counted as git does (an opened new file included); a repository's top folder lower down, as before | Miguel: the folders of `~/.claude`, and any added later, show their changes as repositories do |
| a row holding a change and an unopened new file | the arrow, then the dot (`RowIcon`); replaces record 76's one mark per row | the arrow says "changed", the dot "unread inside": two facts |
| a row's tooltip | the branch and what the row holds uncommitted, by kind; "here" for a folder's row: "On master · 2 uncommitted here: 1 modified, 1 untracked" | the repository row's tooltip, scoped to the folder |
| one click on a folder | opens or closes it; on a file it selects | Miguel |
| a double click on a row | the Changes view of what the row holds: all of a repository for its row, only its folder for a folder's row (`KB/`, not `~/.claude`); a balloon when there is nothing to show or no repository holds it | Miguel |
| a double click on a subfolder | nothing more than its first click | Miguel: rows only |
| the double click's first click | opens or closes the row; the second click undoes that, so a double click leaves the row as it was | a double click that also closed an open row would hide what the user was reading |
| how a click toggles | `TreeView.toggleOnClick`: the look toggles on a press whose click count is 1, never on the second; off for every other tree, which keep the look's double click | one tree class for every widget |

## 3. Consequences

A mark that appears or goes after a row's first layout widened the cell but not the row, cutting the folder's path off
the end (seen on the probe as "KB ↗ ●" without `~/.claude/KB`); the Files tab now lays its shown rows out again
whenever the marks change (`TreeView.rowsChanged`). `TreeView.noToggle` is gone: no node keeps its double click from the look any more. The press that opens a folder is
tested where a display is (`TreeClicksUiTest`, in `./gradlew :app:uiTest`): the look reads the menu shortcut key on a
press, which a headless toolkit refuses.
