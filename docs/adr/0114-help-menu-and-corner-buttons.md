# 114. Help is the menu; the theme, the shell, the plots and the server are buttons

| Date | Status | Decided by |
|---|---|---|
| 2026-10-28 | accepted; amended on 2026-10-29 (see Amendments); the corner gains the comando Edge's toggle by [record 122](0122-instances-name-kit-brain-data-root-edge.md) | Miguel and Claude |

## 1. Context

Actions held the palette, the theme's submenu, restart the shell, stop refreshing plots, start and stop the embedded
QuestDB, and the help. The theme had a second route in the swatch beside New panel
([record 104](0104-the-theme-beside-new-panel.md)). Miguel asked for the theme out of the menu, buttons beside the
swatch for the shell, the plots and the server, and the menu that is left named Help.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the menu bar | Edit, then Help: the palette, a separator, Tutorial, Keyboard shortcuts, Markdown sheet, Show the log, Show the Edge's log (in `<data root>/edge/log/`), Show QuestDB's log (the day's `<data root>/questdb/log/questdb-YYYYMMDD.log`, written through comando's `conf/comando-log.conf` unless `conf/log.conf` has a file writer), Reveal comando folder; no submenu | what is left is help; supersedes the Actions layout of [record 75](0075-the-menu-bar-is-edit-and-actions-flat.md) and [record 103](0103-curated-themes-in-actions.md) |
| the theme | the swatch alone, and the palette (area Theme) | the swatch was already its control |
| the corner | left of the swatch, in order: the shell, the refreshing plots, the embedded QuestDB when comando has one (`Menus.corner`) | controls of the window, beside the window's other controls |
| a button of two commands | `CommandButton` shows the first of its commands that can run now (Start the shell or Stop the shell; Start or Stop embedded QuestDB), its icon, tooltip and accessible name; disabled when none can; it reads them on each click and once a second while shown | a server and a plot change state on their own |
| the shell | new commands `terminal.start`, `terminal.stop` on the focused panel's shell; `terminal.restart` stays in the palette (area Run) | start/stop was asked for; restart is the pair in one step |

## 3. Consequences

Tested in `MenusTest` (the bar, the corner with and without a server, icons and tooltips, every corner command in an
area) and `UiKeysTest` (the menu titles and Help's items; the corner's buttons before the swatch; the themes chosen
from the swatch). `Snapshot` and `DocImages` start QuestDB from the corner's button.

## Amendments

2026-10-29: Help gains Show the Edge's log and Show QuestDB's log, after Show the log.
