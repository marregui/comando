# 106. comando's settings in the data root, as settings.json

| Date | Status | Decided by |
|---|---|---|
| 2026-10-28 | accepted | Miguel and Claude |

## 1. Context

comando kept its state in two places. Connections, sessions, panels, the Files tab's folders and the files opened were
JSON files in the data root (`~/.comando` by default). The theme, the window's bounds and dividers, the sidebar's state
and tab, the side pane, the sessions' manual order, Linear's site, the folder of the last export and the last Gradle
tasks were in Java's preferences, which on macOS is `~/Library/Preferences/dev.marregui.comando.plist`. Miguel's rule:
application state is always stored in the data root, the theme included, in one file for global settings.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| where | `settings.json` in the data root, beside the other store files | Miguel's rule: one place for comando's state |
| what | every key the preferences held: `theme.ground`, `theme.tint`, `window.*`, `divider.*`, `sidebar.*`, `sessions.manualOrder`, `sidePane.hidden`, `linear.workspace`, `linear.teams`, `export.dir`, `tools.gradle` | the whole of the window's kept state, not the theme alone |
| shape | one JSON object, keys in order, each value a string, a number or a boolean; written whole to a file beside it and moved over it, after each change | readable and safe against a crash, as the other store files |
| from before | when `settings.json` does not exist, the preferences' values are read once, typed, and written to it; the preferences are left as they were | nothing chosen is lost; nothing is deleted |
| a file that cannot be read | kept aside as `settings.json.unreadable`; the settings start empty | the user's file is never overwritten unseen |
| without a data root | the settings live in memory with the window | tests that open a window without a data root write nothing |

Miguel decided where the settings live and asked for one file; Claude moved every key, not only the theme, and kept the
preferences as the one-time source.

## 3. Consequences

`Settings` (in `ui.shell`) holds the keys and writes the file; `WindowLayout` stays the one place each key is named and
writes after each change; `PanelFiles` reads the export folder through it and `Commands` the Gradle tasks.
`comando.prefsNode` now names only the preferences node read once. Tested in `SettingsTest` (the one-time copy, typed,
preferences untouched, the file winning afterwards; a change read by the next window, mutation-checked with the theme's
write removed; an unreadable file kept aside), `WindowLayoutTest`, and `UiKeysTest`, whose theme test keeps the theme in
a data root's `settings.json` across windows.
