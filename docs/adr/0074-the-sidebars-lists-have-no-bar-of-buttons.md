# 74. The sidebar's lists have no bar of buttons

| Date | Status | Decided by |
|---|---|---|
| 2026-10-19 | accepted | Miguel and Claude |

## 1. Context

Each of the Panels, Sessions and Connections lists had a bar of buttons above it
([record 37](0037-session-actions-act-on-the-selected-row.md)): add (with import and sort for sessions) on the left;
edit, clone and remove of the selected row on the right. Every one of those actions was also in a menu: add in the
tab's menu ([record 70](0070-the-sidebars-tabs-in-one-row-with-a-menu-each.md)), edit (rename for a panel), clone and
remove in each row's menu. Each tab's menu ended with Hide the sidebar, which ⌘B and the ⋯ button also do. The Files
tab had its filter above the tree. This record replaces record 37's bar and amends record 70's tab menus.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the bar | gone from Panels, Sessions and Connections; the list fills its tab | every action is in a menu |
| adding | the tab's menu: New session… and Import stored conversations…; New connection…; the window's new and reopened panel | add belongs in the tab's context menu |
| the window's commands in a tab menu | Sessions and Connections take none (Session settings… and Connection settings… act on the focused panel, not the tab); Connections keeps the embedded QuestDB's Start and Stop; Panels its new and reopened panel; Files its two windows | the settings items act on the focused panel, so a tab's menu is the wrong place for them |
| editing, cloning, removing | each row's menu: Edit… (Rename… for a panel), Clone…, Remove…; removal keeps record 37's question | reachable from each item |
| sorting the sessions | the Sessions tab's menu, Sort: running first, then by name, enabled only after a drag ordered them by hand | the bar's sort button was off while sorted |
| hiding the sidebar | ⌘B and the ⋯ button; no tab menu offers it | Miguel |
| the Files tab's filter | under the tree | Miguel |

Miguel decided: no bar over the Panels, Sessions and Connections lists; add from the tab's menu, the rest from each
row's menu; no Hide the sidebar in any tab menu; the Files tab's filter under the tree. Claude decided how:
`ResourceActions` keeps add and the removal question, and Sort is enabled only after a drag.

## 3. Consequences

`ResourceActions` holds no buttons: it opens the editor on a new resource and asks before removing one. A row's
actions need a right click; the keyboard keeps ⌘T for a new panel and ⇧⌘K for the session settings.
[Record 91](0091-the-panels-tab-folds-into-the-sessions-tab.md) later folded the Panels tab into the Sessions tab, whose
menu took the new and reopened panel.
