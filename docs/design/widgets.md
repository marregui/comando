# Widgets

A widget renders one value and sends intents. Everything the window shows inside a tab is a widget: a result grid, a transcript, a plot Claude placed, a Gradle run.

## 1. The contract

```java
public interface Widget<V> {
    JComponent component();
    void render(V view);          // on the EDT, whenever the value or its version changes
    default void unmount() { }    // the component is about to leave the hierarchy
}
```

| Rule | Why |
|---|---|
| A widget holds no state beyond what Swing needs | the value comes from `AppState` or a store; a widget can be rebuilt from it at any time |
| It changes nothing directly; it dispatches intents through `WidgetContext.intents()` | every change goes through the reducer, so it is logged and replayable |
| It calls no adapter and touches no store file; the threads it starts are short virtual threads that hand a link or file to the desktop or write an export, and the terminal's, which JediTerm and pty4j own | the UI has one way out: intents |
| It imports no other widget | widgets compose in panels, not in each other |
| `unmount()` removes every listener it added to a long-lived object | a closed view must not be kept alive by a store |

`UiArchitectureTest` enforces the import rules with ArchUnit ([UI packages](ui-packages.md#2-import-rules)).

`WidgetContext` carries what a widget may use: the intent sink and the action registry.

## 2. The registry

A view placed by Claude or by the user names a view type. `WidgetRegistry` maps the type to a factory; adding a widget is one registration and one `ViewSpec` record.

| View type | `ViewSpec` | Widget |
|---|---|---|
| `table` | `Table` | `TableViewWidget` over `GridTable`, with an empty state |
| `plot` | `Plot` | `PlotWidget` over `PlotCanvas` |
| `diff` | `Diff` | `DiffWidget` |
| `markdown` | `Markdown` | `MarkdownWidget` |
| `text` | `Text` | `TextWidget` |
| `tree` | `Tree` | `TreeWidget` |
| `console` | `Console` | `ConsoleWidget` |

Registering a type twice throws. `ViewTabs` creates a widget when a view appears, calls `render` when the view's version changes (a pin change updates only the tab's pin button), and calls `unmount` when the view closes. A view tab has a pin toggle and a close button; a pinned view is never replaced by a session.

Widgets outside the registry are built by panels: the command editor and prompt bar (`CommandEditor`, `PromptBar`), the result grid (`GridTable` in the Results tab), the transcript (`TranscriptWidget`), the terminal (`TerminalWidget`) and the approval cards (`ApprovalInbox`).

## 3. Capabilities

A widget declares what it can do by implementing capability interfaces and marking its root component with `Capabilities.mark`. The shell binds each capability's key once; the key acts on the capability owner nearest the focused component, else on the one in the lower tab in front, so ⌘F, ⌘⇧C and zoom reach the results or the transcript while the editor has the keyboard. With neither, the command is disabled and the key goes to the focused component.

| Capability | Methods | Key | Implemented by |
|---|---|---|---|
| `Searchable` | `find(text, forward)` returns the match count, `clearFind()` | ⌘F opens the shared find bar, which finds as you type (250 ms after the last key); Enter or ⌘G next, ⇧Enter or ⌘⇧G previous, Esc closes | transcript, text, diff, grid, console, editor, terminal |
| `Copyable` | `copyText()`; the grid copies tab-separated text | ⌘⇧C | transcript, text, markdown, diff, tree, grid, console, the Results tab (the error of a failed run, else the grid) |
| `Exportable` | `exportName()`, `prepare()` on the EDT returns the writer that runs off it | ⌘⇧S opens the save dialog (the native one on macOS) in the last export directory; the status bar names the file written, or the error | grid (CSV), plot (PNG), markdown (`.md`), diff |
| `Zoomable` | `zoom(steps)`, `resetZoom()` | ⌘= in, ⌘- out, ⌘0 reset | transcript, text, editor, plot, terminal (8–40 pt, not kept across restarts) |

There is one find bar per window (`FindBar`); it attaches to the `Searchable` that had focus when ⌘F was pressed.

## 4. The widgets

| Widget | Shows | Notes |
|---|---|---|
| `CommandEditor` | SQL and prompts in one document | RSyntaxTextArea; gutter icons per block ([Command dispatch](command-dispatch.md)); SQL completion from the bound connection's schema, opening by itself only in a SQL block or a block that starts like a statement being typed: a statement keyword in lower or upper case, no prose word, and the words that must follow it (`TABLE` after `CREATE`, `ALTER`, `DROP`, `TRUNCATE`; `INTO` after `INSERT`; `FROM` after `DELETE`; `SET` after `UPDATE` and its table) (⌃Space or ⌥⎋ opens it anywhere in the editor); a session's write is one undo step of its own and never moves the user's caret or selection |
| `PromptBar` | a prompt to the panel's session | a text area growing with its wrapped text to six rows; ⏎ sends, ⇧⏎ or ⌥⏎ a new line, ↑ ↓ the session's history (kept across restarts), Esc back to the editor; a refused prompt stays, outlined in red ([Command dispatch](command-dispatch.md#5-the-prompt-bar)) |
| `GridTable` | a SQL result or a placed table | JTable paints visible rows only; cells format into one reused buffer; sorting keeps a permutation of row indices, offered up to 1 000 000 rows and computed on the EDT; a growing result appends rows without rebuilding columns and drops a sort made before it grew; ⌘⇧I and copy map selected rows through the sort |
| `TranscriptWidget` | a session's transcript | painted, virtualised ([Transcript renderer](transcript-renderer.md)) |
| `PlotWidget` | series over shared axes | each pixel column reduced to min and max of the visible points; points without a finite x or y are skipped; time axes tick on clock steps; a click focuses it; drag to zoom, double-click resets; an empty state when there are no points |
| `DiffWidget` | a unified diff of two texts | |
| `TextWidget` | a file or text, read-only | highlighted by file type, scrolled to a line; a header button opens the file in IntelliJ |
| `MarkdownWidget` | rendered Markdown | |
| `TreeWidget` | test results, a schema, the scratchpad | a node with a file link opens the file; an insert link puts its text into the editor |
| `ConsoleWidget` | a Gradle or jfrq run | output as it arrives, state and stop button, test results as a tree; a failure can be sent to the panel's session |
| `TerminalWidget` | the user's login shell | JediTerm on a pseudo-terminal (pty4j); restart bar when the shell exits |
| `ApprovalInbox` | pending approvals of every session | cards with keys ([Approvals and safety](approvals-and-safety.md)) |
