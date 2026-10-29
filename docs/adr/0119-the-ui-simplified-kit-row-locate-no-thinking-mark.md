# 119. Fewer marks: BRAIN and KIT without icons and with their commit, the kit off the status bar, Locate, no thinking mark

| Date | Status | Decided by |
|---|---|---|
| 2026-10-28 | accepted | Miguel and Claude |

## 1. Context

Miguel, 2026-10-28: "the BRAIN and KIT folders should have no icons to the left … this way it will be visually
simpler. the kit part of the app status bar should be removed from there, and the commit number should be present next
to the repo name. from any panel in the workspace there should be a button next to the clear panel button, to its
left, after the stop button, that would take me to the files tab, with the file selected in the tree and the tree
expanded if nodes along the path were collapsed by the user. the session panel has three purple dots before the
thinking report line … I would much rather not have the icon there." Asked once, he chose: the short commit on the
BRAIN and KIT rows, the kit's newer release after KIT's; Upgrade the kit… in KIT's menu and the palette; on a panel
that is no file, the button shows the Sessions tab with the panel's session selected.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| a group's row (BRAIN, KIT) | no icon at all, not the look's own (`TreeView.NO_ICON`), nor git's badge; known by a link (`comando-group:<folder>`), no longer by the path it showed | the name alone, visually simpler |
| its detail | its repository's commit, seven characters (`RepoStatus.head` from `git status --branch`'s `branch.oid`); KIT's then "· vX.Y.Z available" when the kit's remote has a newer release. The path moves to the row's tooltip, before the branch and changes | the commit next to the repository's name |
| the status bar | no kit label, no kit menu; the notice of a newer release names KIT's menu | Miguel's ask |
| Upgrade the kit… | in KIT's menu after Back up the kit, enabled while a newer release is out; still in the palette and the Kit area | the kit's actions at the kit's row |
| Locate | a panel tool between Stop and Clear. On a document: the Files tab in front (the sidebar shown), the filter cleared, every row and folder on the file's way opened, whether the user closed it or it was never read (an unread folder is read first and the walk goes on when its tree arrives), the file selected, scrolled to and given the keyboard; a file in no folder of the tab is said beside the tree. On a panel with a session: the Sessions tab in front with the session selected. Neither: no button. Its icon is the tab's it goes to, after a small grey left arrow that tells it from the tab's own icon (Miguel, 2026-10-29) | where a panel comes from, one click away |
| the thinking line | no mark before it; its text keeps the tools' column | the purple mark beside themed text distracted |

## 3. Consequences

`RepoStatus` gains `head` and `shortHead()` (a five-field constructor keeps the others); `Git.parse` reads `branch.oid`.
`FilesTab` gains `GROUP_LINK`, `isGroupRow`, `groupDetail`, `groupTip`, `kitRelease` and `reveal`; it rebuilds when a
repository's commit changes. `TreeView.NO_ICON`. `StatusBar` loses the kit label and `onKitMenu`; `KitActions` sends
the newer release to `SessionsSidebar.kitRelease` and loses `tip`. `EditorPanel` gains the Locate button and
`onLocate`, `Workspace.onLocate`, `SessionsSidebar.revealFile` and `revealSession`. `Items` paints no thinking icon.
Tested in `FilesTabGroupsTest` (no icon, the commits, the release, the tooltip; reveal through a closed, unread folder,
a closed row, a file outside), `GitTest` (`branch.oid`, the initial commit), `EditorPanelHeaderTest` (the button's
place, visibility and what it asks) and off screen in `LocateUiTest` (the file selected in Files, the session in
Sessions).
