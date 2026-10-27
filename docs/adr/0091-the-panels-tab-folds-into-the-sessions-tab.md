# 91. The Panels tab folds into the Sessions tab

| Date | Status | Decided by |
|---|---|---|
| 2026-10-26 | accepted | Miguel and Claude |

## 1. Context

Since [record 88](0088-a-session-and-its-panel-share-a-name.md) a session and its own panel are one thing with one
name, and the Sessions tab opens, renames and removes both. On 2026-10-26 all 7 of comando's own panels in the author's
`~/.comando/panels.json` were sessions' own panels: the Panels tab listed the same 7 things as the Sessions tab. What
only the Panels tab did was list a closed panel that has no session, and clone a panel. comando makes such panels
itself, not only ⌘T: a file found in `panels/` at the start, and the copy kept aside after an outside edit, become
closed panels with no session ([record 39](0039-panels-are-kept-until-removed.md)), and a user who only writes SQL has
panels with a connection and no session. Without a list, those would be files that nothing in comando shows.

This record amends where panels are listed and removed in record 39, and the row of four tabs, the Panels menu and the
saved tab of [record 70](0070-the-sidebars-tabs-in-one-row-with-a-menu-each.md).

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the tabs | three: Sessions, Connections, Files; the Panels tab is gone | one thing, one row, one place |
| panels without a session | a section under the sessions, in their tab, titled "Without a session": comando's own panels that no session has (none named, or one named that is gone), open and closed, by title; a row shows open or closed, the title, the connection and the file | they stay reachable, and the session list keeps its order, drag and cards |
| when it shows | only while it has a row; at most 4 rows before it scrolls | with every panel a session's own, the tab is as it was |
| a row | double-click opens a closed panel or brings an open one forward; its menu: Open (or Bring forward), Rename…, New session ‹title›… (the session dialog with the name filled in, as the panel's chip offers), Remove… | the Panels tab's row, plus the way to make the panel a session's own |
| Clone | gone | not used |
| the Sessions tab's menu | gains the window's New panel and Reopen closed panel, which the Panels tab's menu held | the panel commands stay in a menu |
| a session's own panel, a document | not in the section: the session's row reaches the first, the Files tab the second | each in one place |
| the tab kept across restarts | saved under `sidebar.selected`; `sidebar.front` (Sessions, Connections, Panels, Files) and `sidebar.tab` (Sessions, Panels, Connections, Files) are read until it is saved; a saved Panels tab reads as Sessions | the indices changed again |
| texts naming the Panels tab | "⌘⇧T or the Sessions tab reopens it"; a loose file's notice "listed as closed under the sessions, without a session"; `ui_set_editor`'s error "reopens it by opening the session in the sidebar" | they named a tab that is gone |

Miguel decided that the Panels tab goes, being a second way to reach the same panels, that there is one list, and that
Clone goes. Claude decided the section under the sessions, its rows and menu, and the saved tab.

## 3. Consequences

`PanelsTab` becomes `PanelsWithoutSession`, a section of the Sessions tab. Tested in `PanelsWithoutSessionTest` (the
rows, the section hidden while every panel is a session's own, its place under the sessions, the menu, removal),
`SessionsSidebarTest` (three tabs, no Panels header, the tab menus) and `WindowLayoutTest` (both older keys).
