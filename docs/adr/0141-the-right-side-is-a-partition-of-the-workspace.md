# 141. The right side is a partition of the workspace

| Date | Status | Decided by |
|---|---|---|
| 2026-11-08 | accepted; built | Miguel and Claude |

## 1. Context

The side pane held views only: help pages, a file at a line, the Changes view, and since record 139 the Search view.
Miguel tried the Search view on 2026-11-08 and found the side pane not integrated: a thick black tab strip and black
dividers in the light theme, and a floating hit viewer that covered comando with no way back to the list, whose "Open
in comando" seemed to do nothing and whose text view offered IntelliJ. Two causes were found in the source. The theme
is applied only to components inside a window, and the side pane was taken out of the window while hidden, so it kept
the dark look comando starts with; and its tabs were "underlined" where the workspace's are "card". "Open in comando"
showed the file as a side view, under the floating viewer. He asked for the right side to be a partition of the
workspace that any tab can be sent to from its menu, and for buttons to be icons with themed tooltips.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what the right side is | a partition of the workspace: one tab strip with the main strip's look, holding panels (sessions, files, SQL editors) and views (help pages, Changes, Search, results) | Miguel's ask: the side is part of the workspace, not a drawer for one kind of thing |
| how a tab gets there | its tab's right-click menu: a panel "Move to the right side" and back "Move to the main tabs"; a view "Move to the right side" and "Move to the lower panel"; `PlacePanel`, `PlaceView`, and the agent tools `panel_place`, `view_place` | one gesture for every tab; every UI action has a tool |
| what is kept | `PanelState.side` is saved in `panels.json` (an optional key: missing reads as the main tabs, no new version, record 133); a closed panel reopens where it was; the Search and Changes views that were on the right reopen there | a layout the user made survives a restart |
| where a view is found again | `ViewState.home`, its first placement: a view shown again under its title is found there and stays where the user moved it | a session updating its view must not lose it, nor move it back |
| the lower panel and the side's width | a tab moved to the right side changes nothing else: the lower panel is shown or hidden for the main tabs' front panel, so a file to read moved there no longer hides it; the side opens at the width a side view (Search, Changes) opens at | Miguel, 2026-11-08: the file opens on the side as the search or the diff does, no other space taken |
| numbering | ⌘1–9 count the main tabs first, then the right side's panels; ⌘W closes the focused panel wherever it is | the main tabs keep their numbers when a panel moves right |
| what stays | the lower panel's fixed tabs (Session, Approvals, Terminal, Results) follow the focused panel and do not move; the main tabs keep one panel at least | they are about the focused panel, not tabs of their own |
| the theme | the partition stays in the window, collapsed by its divider when empty, and follows the theme as well when it is not shown | the cause of the black strip |
| reading a search's hit | the preview tab: one tab of the main strip, its title in italics, reused for each hit; the file in comando's editor, every hit of the file marked and the current one brighter, F7 and ⇧F7 across hits; a double click on the tab, an edit, or Open keeps it as a tab that stays; a preview is never saved nor in the ⌘⇧T list | IntelliJ's preview tab, which Miguel chose over a window docked under the tree or a floating one beside comando |
| buttons | icons with themed tooltips, the shortcut in the tooltip, from `ToolButton` and `CommandButton` | the look of the rest of comando |
| IntelliJ | no longer offered: the text view's button opens the file in a panel | comando is the IDE |

## 3. Consequences

`PanelState` gains `side` and `preview`, `ViewState` gains `home`, `AddPanel` gains `preview`, and `PlacePanel`,
`PinPanel` and `PlaceView` are added. `SidePane` holds panels as well as views; `Workspace` hands a panel's component
between the two strips as its `side` changes. `SearchViewer` is removed; `SearchHub.Previews`, given by the shell, opens
the preview tab, and `EditorPanel.showHits` marks the hits. `ExternalEditor` is removed with the IntelliJ button.
Tested in the reducer, store and parity tests, the panels tests, and `PartitionUiTest` and `SearchUiTest` on
`:app:uiTest`.

An older comando reading `panels.json` shows every panel in the main tabs.

## 4. Alternatives

A second workspace split of the same kind, left and right, is the general form; one right partition answers the ask
and keeps one place for views. The hit read in a window docked under the tree keeps the file beside its list but
halves both; a floating window placed beside comando's still leaves two windows to manage.
