# UI packages

## 1. The tree

```
dev.marregui.comando.ui
├── kit/            foundation: theme, icons, commands, overlays, EDT helpers, formatting, text, trees, desktop (file choosers, links)
├── widget/         the contract: Widget, WidgetContext, WidgetRegistry
│   └── capability/ Searchable, Copyable, Exportable, Zoomable, FindBar, Capabilities
├── widgets/        one package per widget
│   ├── approval/   ApprovalInbox (rules from core's PermissionRules)
│   ├── console/    ConsoleWidget
│   ├── diff/       DiffWidget
│   ├── editor/     CommandEditor, SqlCompletion
│   ├── markdown/   MarkdownWidget
│   ├── plot/       PlotWidget, PlotCanvas
│   ├── table/      TableViewWidget, GridTable, TableSource
│   ├── terminal/   TerminalWidget, Pty, InputGate
│   ├── text/       TextWidget
│   ├── transcript/ TranscriptWidget, TranscriptView, ToolText; doc/ (Markdown to paragraphs), render/ (layout, paint, Fold)
│   └── tree/       TreeWidget
├── panels/         the window's regions: Workspace, EditorPanel, LowerPanel, ResultsView, PlanTree,
│                   SessionsSidebar, ScratchpadTab, SidePane, StatusBar, TerminalTabs, ViewTabs
├── dialogs/
│   ├── resource/   ResourceDialog<S>, the one dialog for connections and sessions
│   └── sessions/   ImportSessionsDialog
├── shell/          ComandoUi (the frame and the UI port), Keys, LivePlots, ResultViews, Notifier, ServerToggle, Attachments, ClosingCover
└── PanelTexts      editor text between runs, written by the store
```

| Layer | May use |
|---|---|
| `kit` | `core`, Swing and the UI libraries |
| `widget` | `kit`, `core` |
| `widgets.*` | `kit`, `widget`, `core`; not another widget package |
| `panels` | everything above |
| `dialogs` | everything above |
| `shell` | everything in `ui` |

The direction runs one way: a lower layer never imports a higher one, so a widget can be tested on its own and the kit can change without touching panels.

## 2. Import rules

`UiArchitectureTest` checks the compiled classes with ArchUnit on every build:

| Rule | Check |
|---|---|
| No widget imports another widget | the slices `ui.widgets.(*)` do not depend on each other |
| Widgets stay below panels | no class in `ui.widgets` depends on `ui.panels`, `ui.shell` or `ui.dialogs` |
| The kit depends on nothing above it | no class in `ui.kit` depends on `ui.widget`, `ui.widgets`, `ui.panels` or `ui.shell` |
| The UI calls no adapter | no class in `ui` depends on the `db`, `claude`, `mcp` or `tools` modules or on `java.sql` |

The Gradle module graph enforces the last rule a second time: the `ui` module does not declare those modules as dependencies ([Modules and ports](../architecture/03-modules-and-ports.md)).

## 3. Where new code goes

| New code | Package |
|---|---|
| a view Claude or the user can place | `widgets/<name>`, one `ViewSpec` record in `core`, one registration in `ComandoUi` |
| behaviour two widgets share | `kit`, or a capability in `widget.capability` |
| a region of the window | `panels` |
| a command | a `Command`; today the shell registers all of them, through `WidgetContext.actions()` a widget can register its own |

## 4. Differences from the plan

| Plan | Built | Reason |
|---|---|---|
| `Widget.mount(WidgetContext)` | the context goes to the factory; `Widget` has `component`, `render`, `unmount` | a widget gets its context once, at construction |
| `WidgetContext` with theme, clipboard, export service | intent sink and action registry | theme is static tokens; clipboard and export are in `Capabilities` |
| `ViewSpec` in `ui.widget` | `ViewSpec` in `core.view` | the reducer and the MCP tools create view specs, and neither depends on `ui` |
| capabilities `Selectable`, `Refreshable` | not built | no widget needed them; live plots refresh through `LivePlots` |
| `dialogs/connections` | one `ResourceDialog` for both kinds | the resource model made a second dialog unnecessary |
| ⌘C copies a view | ⌘⇧C | ⌘C keeps its meaning of copying the selection in a text area or grid |
| a stale overlay state | empty, loading, error | no view goes stale without being re-rendered |
