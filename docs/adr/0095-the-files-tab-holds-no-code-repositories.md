# 95. The Files tab holds no code repositories

| Date | Status | Decided by |
|---|---|---|
| 2026-10-27 | accepted | Miguel and Claude |

## 1. Context

[Record 71](0071-repositories-in-the-files-tab.md) added git checkouts to the Files tab as rows of one node,
Repositories, configured in a window of their own (Configure repositories). On 2026-10-27 the author's `folders.json`
held four: comando and three checkouts of one product repository. comando is not an IDE and not a browser of code
repositories: the author reads code in an IDE, and in comando reads the documents Claude Code's folder holds (the
brain, the kit). The Repositories node listed trees of source files that comando's reader of documents does not serve.

This record replaces the kinds, the tree, the configuring and the repository rows of record 71, and the repository's
double click of [record 78](0078-a-rows-marks-and-double-click-show-its-changes.md). The Changes view, its title, its
groups, a folder's Show changes and a changed file's Compare with HEAD stay as records 68, 71 and 78 set them.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the Repositories node | gone, with its icon and its count; every folder is a top-level row | the tab is no repository browser |
| configuring | one window, Configure ~/.claude folders, from the Files tab's menu and the Files menu; Configure repositories is gone from both and from the palette | one kind of entry, one window |
| a folder | lies inside `~/.claude`, as record 71 set; the window says "must be inside ~/.claude" of any entry elsewhere and saves only once it is taken out or moved | the rule record 71 gave the folders now holds for every entry |
| `folders.json` and `comando-kit.json` | no `repository` key; one written before is ignored when read | a key nothing reads |
| git in the tab | stays for the folders' own repositories (`~/.claude`'s, the brain's): colours, the dot, the git icon on a repository's top folder, the rising arrow, the tooltip, a row's double click, Show changes in X, Compare with HEAD | the author reviews the kit's and the brain's changes there before committing |
| ⌘K | the Changes view of every folder's repository, as before | the same key for the same view |
| a session's tab card | still names the session's repository and branch | the session's context, not a browser |

Miguel decided that the repositories go and that the git changes of the `~/.claude` folders stay; Claude decided that
an older `repository` key is ignored rather than migrated, and that the store does not refuse an entry outside
`~/.claude` (the window marks it), so that the snapshot and picture tools keep their scratch folders.

## 3. Consequences

`FolderRoot` loses `repository` and `asRepository`; `FolderKind` loses the attribute; `FoldersEditor` and
`FoldersDialog` lose their repositories mode; `FilesTab` loses the Repositories node, `isRepositories` and the row
index that depended on it; the `files.repositories` command and `icons/repositories.svg` are removed. Tested in
`FilesTabTest` (every folder a top-level row, its menus and double click), `FoldersEditorTest` (one list, the inside
rule, a name used once) and `StoreTest` (a file written without the key, an older one with it read as a folder).

The four checkouts were taken out of the author's `folders.json` by hand on 2026-10-27. Another user's older file
shows its repositories as folders outside `~/.claude` until they are taken out in the window. `Snapshot` no longer
paints the repositories window; `DocImages` keeps its sample repository at `~/.claude/lane`, so `changes.png` and
`diff.png` show `~/projects/lane` until `:app:docImages` runs again.
