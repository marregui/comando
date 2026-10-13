# UI kit

`ui.kit` is the foundation every widget, panel and dialog builds on. It holds what would otherwise be written once per widget: colours, icons, commands, the empty and error states, thread hand-off, text areas, trees. The kit depends on no widget, panel or shell class.

## 1. Packages

| Package | Classes | Purpose |
|---|---|---|
| `theme` | `Theme` | FlatLaf dark (`FlatMacDarkLaf` on macOS); design tokens: accent, Claude, ok, warn, danger, muted, thinking, diff added and removed, card, card border, user bubble, the plot series palette; spacing `GAP` 6, `PAD` 10, `RADIUS` 8; the monospaced font (JetBrains Mono when installed, else the platform's) |
| `icon` | `Icons` | one SVG icon set under `/icons`, scaled for HiDPI and recoloured by the theme |
| `action` | `Command`, `ActionRegistry`, `CommandPalette` | every user action is a `Command` (id, label, icon, key, menu group, action, enablement); menus, key bindings and the palette are built from the registry |
| `overlay` | `Overlay` | the empty, loading and error states; a widget wraps its content once and switches state |
| `async` | `Edt`, `Coalescer`, `Debouncer`, `EdtWatchdog` | thread hand-off and paint pacing (§3) |
| `format` | `Labels` | short labels: time since, dollars, token counts, counts, shortened paths |
| `text` | `Editors`, `TextSearch`, `Highlighter`, `Diffs`, `DiffPane`, `MarkdownHtml`, `HtmlPane`, `SourceLinks` | text areas and text rendering (§4) |
| `tree` | `TreeView` | the one tree over `ViewSpec.TreeNode` |
| `desktop` | `ExternalEditor` | opens a file at a line in IntelliJ IDEA, else in the system's default application, on a virtual thread |

## 2. Commands

A command appears in at most one menu (its group) and carries at most one key stroke. The palette (⌘⇧P) lists every enabled command whose label matches what the user types, with its key and menu. A command in a menu gets its key as the menu item's accelerator, not as a second binding, so it runs once per key press. Registering an id twice throws. The shell registers every command today; `WidgetContext.actions()` lets a widget register its own, and none does yet.

## 3. Threads in the UI

| Class | Behaviour |
|---|---|
| `Edt.run` | runs now when on the EDT, else posts |
| `Edt.runAndWait` | runs on the EDT and waits; used only when closing |
| `Edt.assertEdt` | a Java `assert` that the caller is on the EDT; active only with `-ea` (tests) |
| `Coalescer` | many requests from any thread become at most one pending run on the EDT; the run reads the latest data, so nothing is lost and the EDT is not flooded. State renders, transcript updates and console output go through one each |
| `Debouncer` | runs once requests stop for a delay; the editor's gutter uses 250 ms |
| `EdtWatchdog` | every 100 ms posts a probe to the EDT; a probe still waiting after 200 ms logs the EDT's stack once, and the answer records a `comando.EdtBlocked` JFR event with the blocked time |

## 4. Text

| Class | Purpose |
|---|---|
| `Editors` | the one place a text area is configured: theme, font, syntax by file type; `colorOf` gives a token type's colour |
| `TextSearch` | find in a text area with every match marked; the search of every text-based widget |
| `Highlighter` | syntax colours for text painted outside a text area (the transcript), from the same token makers and colours as the editors; safe on any thread |
| `Diffs`, `DiffPane` | unified diff of two texts with three lines of context; the pane colours added and removed lines |
| `MarkdownHtml`, `HtmlPane` | Markdown (CommonMark with tables) to HTML for Swing's HTML renderer; raw HTML in the source is escaped; an image becomes its alt text, so nothing is fetched; links open in the browser |
| `SourceLinks` | turns `path/File.java:42` references into links that open the file at the line; leaves code blocks and existing links alone |
| `Plain` | text shown as text: `Plain.only(component)` turns HTML rendering off (before the text is set), `Plain.text(s)` guards a single string that starts with `<html>` ([Approvals and safety](approvals-and-safety.md#6-text-is-never-rendered-as-html)) |

## 5. Rules

1. A colour, font or spacing comes from `Theme`. Exceptions: the transcript's `Style` and the terminal's palette define their own colours, tuned to their surfaces.
2. A placeholder, spinner or error message is an `Overlay` state.
3. A user action is a `Command`; nothing binds a key outside the registry, except keys local to one component (Enter in the prompt bar, the approval card keys).
4. Work arriving from other threads reaches Swing through `Edt` or a `Coalescer`.

## 6. Differences from the plan

| Plan | Built | Reason |
|---|---|---|
| light and dark themes | dark only | one palette to maintain; the transcript and plot colours are tuned to it |
| a find bar in `kit.text` | `FindBar` in `widget.capability` | it is the user side of `Searchable` |
| formatting into reused buffers | `Labels` returns strings; the grid formats cells into one reused buffer | labels are produced a few times per render; the grid is the hot path |
