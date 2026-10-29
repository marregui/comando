# 75. The menu bar is Edit and Actions, flat

| Date | Status | Decided by |
|---|---|---|
| 2026-10-19 | accepted; its Claude Code kit… item removed by [record 80](0080-the-kit-is-a-claude-code-setup-comando-runs-its-sessions-on.md); its menu bar replaced by [record 114](0114-help-menu-and-corner-buttons.md) | Miguel and Claude |

## 1. Context

The bar of [record 59](0059-three-menus-for-what-has-no-key.md) held three menus after macOS's application menu: Files
(Open file…, the two configuration windows of the Files tab, Export view…), Edit, and Tools, with two submenus:
Embedded QuestDB (Start, Stop) and Help (tutorial, keys, Markdown sheet, kit, log, data root). macOS requires none of
them: it draws the application menu itself. This record replaces record 59's bar.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the bar | the application menu (comando), Edit, Actions | the Files menu was little used; Actions names the menu's content, Tools did not |
| Actions | flat; separators between the palette; Restart the shell and Stop refreshing plots; Start and Stop embedded QuestDB (only when comando has one); Tutorial, Keyboard shortcuts, Markdown sheet, Claude Code kit…, Show the log, Reveal comando folder | no submenus; groups by meaning, help one of them |
| the server's items | Start embedded QuestDB, Stop embedded QuestDB: a flat item says what it starts | the submenu's title said it before |
| the Files menu's commands | Open file… (⌘O) and Export view… (⌘⇧S) by key and the palette, listed under Panels and Window; Configure ~/.claude folders and Configure repositories in the Files tab's menu and the palette, listed under Files | one home each |

Miguel decided: no Files menu; comando, then Edit, then a menu renamed from Tools to Actions; no submenus, one flat list
in groups, help one of them. Claude decided how: the groups, the server's items named in full, where the Files menu's
commands live now.

## 3. Consequences

The keys work without a menu item: the registry binds them on the window. `Menus.MENU_TIPS` is empty while the bar has
no submenu. Claude Code kit… left Actions with the kit inside comando
([record 80](0080-the-kit-is-a-claude-code-setup-comando-runs-its-sessions-on.md)); the help group is now Tutorial,
Keyboard shortcuts, Markdown sheet, Show the log, Reveal comando folder.
