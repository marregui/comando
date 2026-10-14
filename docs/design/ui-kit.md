# UI kit

`ui.kit` is the foundation every widget, panel and dialog builds on. It holds what would otherwise be written once per widget: colours, icons, commands, the empty and error states, thread hand-off, text areas, trees. The kit depends on no widget, panel or shell class.

## 1. Packages

| Package | Classes | Purpose |
|---|---|---|
| `theme` | `Theme` | FlatLaf dark (`FlatMacDarkLaf` on macOS); design tokens: accent, Claude, ok, warn, danger, muted, muted strong (secondary text on lists, for contrast), thinking, diff added and removed, card, card border, user bubble, the plot series palette; spacing `GAP` 6, `PAD` 10, `RADIUS` 8; the monospaced font (JetBrains Mono when installed, else the platform's) |
| `icon` | `Icons` | one SVG icon set under `/icons`, scaled for HiDPI, drawn in the colours of the SVG files (no theme filter) |
| `action` | `Command`, `ActionRegistry`, `MenuEntry`, `CommandPalette`, `KeyPriority` | every user action is a `Command` (id, label, icon, key, menu path, action, enablement); the menu bar is built from a `MenuEntry` layout (menus, submenus, separators, command ids), and key bindings and the palette from the registry |
| `overlay` | `Overlay` | the empty, loading and error states; a widget wraps its content once and switches state |
| `async` | `Edt`, `Coalescer`, `Debouncer`, `EdtWatchdog` | thread hand-off and paint pacing (§3) |
| `format` | `Labels` | short labels: time since, dollars, token counts, counts, shortened paths |
| `text` | `Editors`, `TextSearch`, `Highlighter`, `Diffs`, `DiffPane`, `MarkdownHtml`, `HtmlPane`, `MermaidDiagrams`, `SourceLinks`, `Plain` | text areas and text rendering (§4) |
| `tree` | `TreeView` | the one tree over `ViewSpec.TreeNode` |
| `desktop` | `ExternalEditor`, `FileChoosers` | opens a file at a line in IntelliJ IDEA, else in the system's default application, on a virtual thread; folder, open and save choosers, the native `FileDialog` on macOS |

## 2. Commands

A command appears in at most one menu and carries at most one key stroke; the layout (`Menus`, in the shell) places it, and the registry names its path ("Panel ▸ Session"). Two commands may share a key when at most one is enabled at a time. Every state-dependent command has an enablement, except where a disabled command would hand its key to a component action the user does not mean: ⌘J (plot) stays claimed, because the editor binds it to joining lines, and says "no result" instead. Run keys (⌘⏎, ⌘⇧⏎, ⌥⏎, ⌃⏎) act only while the keyboard is in the focused panel's editor. ⌘/ is left to the editor (toggle comment); the key table is ⌘⇧/. On macOS ⌘Q belongs to the application menu, which calls the quit handler; the registry binds it only elsewhere. menu items grey out (the registry refreshes them when the focus or the state changes, because the macOS menu bar answers a key from the state it last saw), and the palette lists only what can run. The palette (⌘⇧P) snapshots what can run as it opens, then ranks the commands whose label matches what the user types: prefix first, then a word start, then any substring, then a loose subsequence, then a match in the menu path only; keys print as the Mac writes them (⌃⌥⇧⌘, ⏎, ⇥). The menus are File, Edit, Panel (with Session, Connection, Result and Tools submenus), View and Help ([ADR 36](../adr/0036-five-menus-by-subject.md)); a Panel submenu carries its subject's icon, an item an icon only where the same action is a button with it. The Edit commands (undo, redo, cut, copy, paste, select all) run the focused component's own binding for the key, so a component without one, the terminal, keeps the key. A command in a menu gets its key as the menu item's accelerator, not as a second binding, so it runs once per key press. In the main window a command's key is claimed before the focused component sees it (`KeyPriority`), because Swing offers a key to the focused component's own bindings first: RSyntaxTextArea binds ⌘⏎ (complete word), ⌘J (join lines), ⌘K and ⌘⇧K (next and previous occurrence), ⌘D (delete line) and ⌘/ (toggle comment), and the terminal consumes every key. A disabled command leaves its key to the component. Registering an id twice throws. The shell registers every command today; `WidgetContext.actions()` lets a widget register its own, and none does yet.

## 3. Threads in the UI

| Class | Behaviour |
|---|---|
| `Edt.run` | runs now when on the EDT, else posts |
| `Edt.runAndWait` | runs on the EDT and waits; used only when closing |
| `Edt.assertEdt` | a Java `assert` that the caller is on the EDT; active only with `-ea` (tests) |
| `Coalescer` | many requests from any thread become at most one pending run on the EDT; the run reads the latest data, so nothing is lost and the EDT is not flooded. State renders, transcript updates and console output go through one each |
| `Debouncer` | runs once requests stop for a delay; the editor's gutter uses 250 ms |
| `EdtWatchdog` | every 100 ms posts a probe to the EDT; a probe still waiting after 200 ms logs the EDT's stack once, and the answer records a `comando.EdtBlocked` JFR event with the blocked time. A stall spent in the JDK's drawing (a `sun.java2d` frame and no comando frame, as when Java2D waits for its lock while macOS's Metal pipeline flushes to the GPU) logs one INFO line when it ends instead; past 2 s it is reported with its stack like any other |

## 4. Text

| Class | Purpose |
|---|---|
| `Editors` | the one place a text area is configured: theme (IntelliJ's Dark colours, `editor-dark.xml`), font, syntax by file extension, file name or code-fence language; `colorOf` gives a token type's colour |
| `TextSearch` | find in a text area with every match marked; the search of every text-based widget |
| `Highlighter` | syntax colours for text painted outside a text area (the transcript), from the same token makers and colours as the editors; safe on any thread |
| `Diffs`, `DiffPane` | unified diff of two texts with three lines of context; the pane colours added and removed lines |
| `MarkdownHtml`, `HtmlPane` | Markdown (CommonMark with tables) to HTML for Swing's HTML renderer; raw HTML in the source is escaped; an image becomes its alt text, so nothing is fetched; a code fence naming its language takes the editors' colours; links open in the browser |
| `MermaidDiagrams` | Mermaid blocks as pictures `mmdc` drew into `~/.comando/cache/mermaid/`, once per diagram text, one at a time on its own thread; `HtmlPane` shows the source under a note until the picture is ready, then refreshes where the reader left it |
| `SourceLinks` | turns `path/File.java:42` references into links that open the file at the line; leaves code blocks and existing links alone |
| `Plain` | text shown as text: `Plain.only(component)` turns HTML rendering off (before the text is set), `Plain.text(s)` guards a single string that starts with `<html>` ([Approvals and safety](approvals-and-safety.md#6-text-is-never-rendered-as-html)) |

## 5. Rules

1. A colour, font or spacing comes from `Theme`. Exceptions: the transcript's `Style` and the terminal's palette define their own colours, tuned to their surfaces.
2. A placeholder, spinner or error message is an `Overlay` state.
3. A user action is a `Command`; nothing binds a key outside the registry, except keys local to one component (the approval card keys).
4. Work arriving from other threads reaches Swing through `Edt` or a `Coalescer`.

## 6. Differences from the plan

| Plan | Built | Reason |
|---|---|---|
| light and dark themes | dark only | one palette to maintain; the transcript and plot colours are tuned to it; the macOS appearance is set to dark too, by a JVM option (`apple.awt.application.appearance`) in the launcher and the disk image, because AppKit reads it once at start; title bars, menus and file dialogs are then dark whatever the system setting is |
| a find bar in `kit.text` | `FindBar` in `widget.capability` | it is the user side of `Searchable` |
| formatting into reused buffers | `Labels` returns strings; the grid formats cells into one reused buffer | labels are produced a few times per render; the grid is the hot path |
