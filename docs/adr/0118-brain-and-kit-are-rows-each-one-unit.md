# 118. BRAIN and KIT are rows of the Files tab, each one unit: its changes and its backup at its row alone

| Date | Status | Decided by |
|---|---|---|
| 2026-10-28 | accepted | Miguel and Claude |

## 1. Context

[Record 98](0098-the-sidebars-look.md) showed a folder's `group` as a header above the first of a run of folders: the
kit's Brain over Scratchpad, KB and Memory, Kit over the kit's folder, nothing nested. Miguel, 2026-10-28: no Brain and
Kit headers; Scratchpad, KB and Memory under their root folder, the brain, the kit being a folder already; the themed
line kept between the two; the brain folder's menu backs the brain up, a commit and a push whose message says what the
commit adds, and shows its changes; no folder under the brain offers its own changes, the brain being one unit; the same
for the kit; both names in capitals. Asked once, he chose: BRAIN shows every folder inside it but no file and nothing
hidden; the Jobs row first inside KIT; the message a first line and a list of the files; the kit backed up too.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| a group | one top-level row named by the group in capitals. A group of one folder is that folder's row (KIT, `~/.claude`). A group of two or more whose folders share a parent folder is that parent's row (BRAIN, `~/.claude/BRAIN`), holding the group's folders in their configured order, then the parent's other folders (EVAL); no file of the parent, nothing whose name starts with a dot. A group whose folders share no parent shows them as rows of their own | the brain and the kit, each a unit, as the repositories they are |
| the parent's own entries | read as a folder of its own, `FolderRoot.groupRoots`: named by the group in capitals, the group's folders left out by name, watched while its row is open, like any folder; the reducer resolves its name beside the configured folders. Not configured, not saved, not in the folders window | no new setting: `group` already says which folders go together |
| a group's folders inside its row | keep their icon, index, Trash and `listedIn`; read only while open; a scratchpad's newest day opens at its first read, as before | they stay the folders they were |
| the separator | a hairline, no words, above a top-level row of another group than the row above it (KIT, then BRAIN; a folder in no group after a group). The record-98 header with its text is gone | Miguel kept the themed line as it was |
| the first row | as far below the tab's top as a row sits above the hairline under it: a separator's space without its line (Miguel, 2026-10-28) | the top and the separator spaced alike |
| changes | Show changes in X and the double click that shows them only on a top-level row (BRAIN, KIT, a folder in no group); a group's folder or a subfolder offers neither. A changed file still compares with HEAD | the brain is a whole unit; so is the kit |
| the backup | the brain's row (or a top-level folder inside the brain) offers **Back up the brain**; the kit's root's row **Back up the kit**, new, in the palette, the Kit area and the status bar's kit menu too. Both `git add -A`, a commit when anything changed, `git push`, off the EDT, on the user's click only | Miguel chose the kit backed up too |
| the message | first line `Brain backup <date>: MEMORY 2 changed; SCRATCHPAD 3 added, 1 changed` (each top folder, or a file at the top, with its counts, added, changed, renamed, removed; past 120 characters the rest counted as "N more areas"), then one line a file (`added MEMORY/x.md`, `renamed a -> b`, at most 200, then "and N more"). The kit's: `Kit backup …` | says what the commit adds; Miguel chose the list too |
| Jobs | the first row inside KIT; without a folder of the Kit group, a top-level row of its own, last | the kit's jobs are the kit's |

Miguel decided the rows, their capitals, the separator, where changes and the backup sit, BRAIN's content, the Jobs
row's place, the message's list and the kit's backup. Claude decided the parent's read as a folder of its own, the
fallback for a group without a shared parent, the message's first line and its caps, and that a top-level folder
inside the brain keeps the brain's backup.

## 3. Consequences

`FolderRoot` gains `groupRoots` and `withGroupRoots`, and its `group` now names a row rather than a header; the
reducer's `WatchFolders`, `BrowseFolders` and `FolderLoaded` take a group's row as a folder. `FilesTab` builds the rows
by group (`rows`), knows a folder's row by its name and detail at either depth (`rowFolder`, `folderIndex`), and offers
changes and the window's items on a top-level row only; `TreeView` paints an empty header as the hairline alone, and
`spaceAbove` puts that hairline's distance above the first row. `KitGit.backUp(repo, what, today)` writes the message
(`subject`, `body`, from `git diff --cached --name-status -M`); `Setup` gains `backUpKit` and `Status.canBackUpKit`;
`KitActions.backUpKit` and the command `kit.backupKit`. Tested in `FilesTabGroupsTest` (the rows, BRAIN's other folders,
what is watched, the menus, the Trash path, the double click), `FilesTabJobsTest`, `SidebarLookTest` (the hairline in
three tints, the first row's space above), `ReducerTest` (a group's row read as a folder), `KitGitTest` (the messages of
the brain's and the kit's backups, renames, removals, the caps), `KitActionsTest` (both backups' notices) and off screen
in `TreeClicksUiTest` (a kit's Brain group as one row). Record 98's headers for folder groups are replaced; its look
otherwise stands.
