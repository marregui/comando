# 71. Repositories in the Files tab

| Date | Status | Decided by |
|---|---|---|
| 2026-10-19 | accepted; the window amended by [record 73](0073-the-claude-folders-window-explains-and-creates-the-index.md); the double click amended by [record 78](0078-a-rows-marks-and-double-click-show-its-changes.md); the Repositories node and window replaced by [record 95](0095-the-files-tab-holds-no-code-repositories.md) | Miguel and Claude |

## 1. Context

The Files tab showed Claude Code's folders (`~/.claude/MEMORY`, `KB`, `SCRATCHPAD`, …) and git checkouts (comando and
other projects) as one list of rows. A repository's double click opened one Changes view of every repository, titled
"Changes" ([record 68](0068-git-changes-and-a-side-by-side-diff.md)). The folders window
([record 69](0069-a-window-edits-the-files-tab-folders.md)) listed both kinds with the same fields, and each row's menu
opened it.

This record replaces the row "where" of record 69, and the double click on a repository's row, the view's title and
its groups of record 68.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| kinds | an entry of `folders.json` with `"repository": true` is a repository; any other is a folder, and lies inside `~/.claude` (Claude Code's folder), whose repository is `~/.claude`'s | outside Repositories, the repository is `~/.claude` |
| the tree | the folders' rows, then one node, Repositories, with its own icon and its count, holding the repositories' rows; it starts open, its rows closed; closing it stops reading them | one root node with an icon of its own |
| configuring | the Files tab's menu, and the Files menu, offer Files tab folders… and Repositories…, each a window over its own list; no row's menu opens either | from the tab's menu, not a repository's |
| a folder | chosen inside `~/.claude`; every field of record 69 | only from within `~/.claude` |
| a repository | chosen; must exist and be a git repository's top folder; a name, its folder, an index file and the names left out | repositories exist and are chosen |
| the path | set by the chooser only, never typed | a chosen folder exists |
| names | unique across both lists; Save writes both, the other window's entries as they were | one `folders.json` |
| a repository's row | a plain folder icon, the git icon being implied; while it has changes a green rising arrow after its name, in place of record 68's flag; its tooltip says its branch, or a detached `HEAD`, and its uncommitted changes by kind, or nothing to commit | a normal folder; a mark that evokes change and progress; git facts on hover |
| a repository's double click | the Changes view of that repository alone, its row named as the repository's; nothing to see says so in a balloon, as before | that repository's changes alone |
| a folder's menu | Show changes in X, on a folder's row and on any subfolder: the Changes view of the repository holding it, inside that folder alone, its row named after the folder's row and the subfolders leading to it ("KB/design"); off, as No changes in X, when it holds none | the changes of `~/.claude` in that folder |
| a changed file's menu | adds Compare with HEAD: the diff window of record 68 | the file at hand |
| ⌘K | the view of every repository; it closes the view when that is what it shows | one view of all repositories stays at one key |
| the view | one, always titled Uncommitted changes, as IntelliJ's commit view; what opens it replaces what it showed; `ViewSpec.Changes(root, folder, name)`, an empty `root` for every repository | one title for every scope |
| its groups | Changed (modified, added, renamed, copied, in conflict), Untracked (new files git does not track), Removed (deleted), each with a folder icon badged in git's colour (a pencil, a plus, a minus), in place of record 68's Changes and Not tracked | three groups, icons to match |

Miguel decided one view titled Uncommitted changes with its three groups, a Repositories node with its icon, the
folders window from the Files tab's menu rather than a row's, a repository's double click showing that repository alone
titled with its name, a folder's menu showing the changes of `~/.claude` inside it, folders only inside `~/.claude`,
and repositories that exist and are chosen; Claude decided how: the `repository` key, two windows, the view's scope, a
changed file's Compare with HEAD, and the checks.

## 3. Consequences

The record drops record 69's Edit folder… on a folder's row. `FolderRoot` gains `repository`; an older `folders.json`
has none, so its checkouts show as folders until marked, by hand or by removing and adding them in the repositories
window. The store does not check that a folder lies inside `~/.claude`: a hand-written entry outside it still shows;
the window will not save it until it is moved or made a repository.
