# 75. The menu bar is Edit and Actions, flat

| Date | Status | Decided by |
|---|---|---|
| 2026-10-19 | proposed; replaces the bar of [record 59](0059-three-menus-for-what-has-no-key.md) | Miguel (no Files menu; comando, then Edit, then a menu renamed from Tools, Actions by his choice; no submenus: one flat list in groups, help one of them), Claude (how: the groups, the server's items named in full, where the Files menu's commands live now) |

## 1. Context

The bar held three menus after macOS's application menu: Files (Open file…, the two configuration windows of the Files
tab, Export view…), Edit, and Tools, with two submenus: Embedded QuestDB (Start, Stop) and Help (tutorial, keys,
Markdown sheet, kit, log, data root). macOS requires none of them: it draws the application menu itself.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the bar | the application menu (comando), Edit, Actions | Miguel: the Files menu is of little use; Tools was a poor name |
| Actions | flat; separators between the palette; Restart the shell and Stop refreshing plots; Start and Stop embedded QuestDB (only when comando has one); Tutorial, Keyboard shortcuts, Markdown sheet, Claude Code kit…, Show the log, Reveal comando folder | Miguel: no submenus, groups by meaning, help one of them |
| the server's items | Start embedded QuestDB, Stop embedded QuestDB: a flat item says what it starts | the submenu's title said it before |
| the Files menu's commands | Open file… (⌘O) and Export view… (⌘⇧S) by key and the palette, listed under Panels and Window; Configure ~/.claude folders and Configure repositories in the Files tab's menu and the palette, listed under Files | one home each (Miguel, 2026-10-19) |

## 3. Consequences

The keys work without a menu item: the registry binds them on the window. `MENU_TIPS` is empty while the bar has no
submenu.
