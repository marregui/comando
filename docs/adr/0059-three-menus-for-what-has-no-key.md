# 59. Three menus, for what has no key or control

| Date | Status | Decided by |
|---|---|---|
| 2026-10-18 | accepted; Tool settings removed by [record 62](0062-gradle-runs-by-convention.md); the bar replaced by [record 75](0075-the-menu-bar-is-edit-and-actions-flat.md) | Miguel and Claude |

## 1. Context

The five menus of [record 36](0036-five-menus-by-subject.md) held about 70 items. They were made when commands had no
other way to reach their keys, and to keep the keys in sight. Every command's key is now claimed in the window by
`KeyPriority`, whether or not the command is in a menu; the palette (⌘⇧P) lists every command that can run, and the
keyboard shortcuts sheet lists every key. Most items repeated a key and a button already on screen.

This record replaces record 36.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what a menu holds | a command with no key and no control in the window, and what macOS expects in a menu (undo and the clipboard) | the menus are no longer where keys are learned |
| the bar | **Files**: Open file…, Files tab folders…, Export view… · **Edit**: Undo, Redo, Cut, Copy, Paste, Select all · **Tools**: Command palette, Restart the shell, Stop refreshing plots, Embedded QuestDB ▸ (Start, Stop), Help ▸ (Tutorial, Keyboard shortcuts, Markdown sheet, Claude Code kit…, Show the log, Reveal comando folder) | Files matches the sidebar tab; Tools holds the palette, the way to every other command |
| the comando menu | as macOS makes it (About, Services, Hide, Quit) | changing it takes native code |
| icons | every item and submenu has one, in the house style (16-point SVG, IntelliJ's grey with a blue, green or red accent); the bar's own titles are words | macOS draws an item's icon in its menu bar |
| tooltips | every item and submenu has one: what it does, where its result goes, what else does the same | the menu documents itself |
| commands in no menu | keep their key, their control and the palette; the palette and the shortcuts sheet name their area (Panels, Run, Session, Connection, Results, Edit, Window) instead of a menu path | the sheet stays ordered |
| a command with neither | the registry refuses it at start | nothing is left unlisted |
| removed | Jump to the last prompt, with the transcript's jump and its highlight | not used |

Miguel decided menus only for what is needed, Files for File and Panel, Help and the palette in Tools, the comando menu
left as macOS makes it, and no native code; Claude decided what stays, the areas, the icons and the tooltips.

## 3. Consequences

`Menus` holds the bar, the icons, the tooltips (`TIPS`, `MENU_TIPS`) and the areas (`AREAS`);
`ActionRegistry.menuBar(menus, tips, menuTips)` sets the tooltips and `ActionRegistry.areas` names the rest. Nine icons
are new: `open-file`, `folders`, `undo`, `redo`, `cut`, `paste`, `select-all`, `terminal-restart`, `chart-stop`.
