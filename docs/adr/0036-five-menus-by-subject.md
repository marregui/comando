# 36. Five menus, by subject

| Date | Status | Decided by |
|---|---|---|
| 2026-10-14 | accepted; Help items carry icons since [47](0047-help-opens-its-files-as-documents.md) | Miguel (fewer menus, submenus, consistent icons; the proposal), Claude (layout and icon rule) |

## 1. Context

The menu bar had eight menus (Edit, Run, Panels, View, Sessions, Connections, Tools, Help), each a flat list in
registration order, with no submenus or separators; View held 20 items. The model and effort commands were in no
menu. One icon meant several things (`play` for Run, starting a session, starting QuestDB and Gradle; `file` for five
unrelated items), and half the items had none.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| menus | File, Edit, Panel, View, Help | where a Mac user looks for File, Edit and Help; Panel gathers what acts on the focused panel |
| Panel | Run, Send to Claude, Run as SQL, Run in the terminal, Stop; then submenus Session (with Model and Effort), Connection (with Embedded QuestDB), Result, Tools | a panel binds a session and a connection: running and what it is bound to sit together, Claude first |
| View | the window: palette, focus, sidebar, side pane, scratchpad, layout; submenus Lower panel, Panels, Zoom; recent notices | |
| Close | one command on ⌘W: the view that has the keyboard, else the panel | two items shared the key |
| structure | one declarative layout (`Menus`), built by the registry, which fails at start on an unknown or doubled command and names each command's path ("Panel ▸ Session") for the palette and the shortcuts list | the shape is read in one place and cannot drift |
| icons | a submenu of Panel carries its subject's icon (Claude, database, table, wrench); an item carries an icon only when comando shows the same action as a button with it (▶ Run, ■ Stop, + New panel, ✕ Close, the plug for connecting a session or a connection); nothing else has one | each icon means one thing |
| the panel header's session toggle | the plug, as on the session's row | a ▶ next to the session read as Run |
| out of the menus | Go to panel 1–9, the ⌘⌥1–4 alternates, the second completion key, Quit (the application menu's) | they keep their keys and the palette |
| palette | matches the label, then the menu path ("model" finds the models) | short labels in submenus stay findable |

## 3. Consequences

Labels are shorter where the menu path says the rest ("Connect or disconnect" under Panel ▸ Session and under Panel ▸
Connection). A test fixes the icon rule and the separators; the UI replay builds the real bar.
