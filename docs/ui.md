# UI

The Swing side of comando: the kit every part builds on, the widget contract, the widgets, and the painted transcript.
The package layers are in [Architecture §8](architecture.md#8-ui-packages).

1. [The kit](#1-the-kit)
2. [Commands and keys](#2-commands-and-keys)
3. [Widgets](#3-widgets)
4. [The transcript](#4-the-transcript)

## 1. The kit

`ui.kit` holds what would otherwise be written once per widget; it depends on no widget, panel or shell class.

| Package | Holds |
|---|---|
| `theme` | `Theme`: FlatLaf dark (`FlatMacDarkLaf` on macOS), the colour tokens (accent, Claude, ok, warn, danger, muted, thinking, diff, card, the plot palette), spacing, the monospaced font. Dark only; the macOS appearance is set dark by a JVM option at launch |
| `icon` | `Icons` (one SVG set under `/icons`, HiDPI), `SpinnerIcon` (one shared wheel, advanced by its owner's timer) |
| `action` | `Command`, `ActionRegistry`, `MenuEntry`, `CommandPalette`, `KeyPriority`, `ToolButton` ([§2](#2-commands-and-keys)) |
| `resource` | `ResourceActions`: the bar above a list of resources ([Workbench §1.3](workbench.md#13-the-sidebar)) |
| `overlay` | `Overlay`: the empty, loading and error states a widget switches between |
| `async` | `Edt`, `Coalescer`, `Debouncer`, `EdtWatchdog` (below) |
| `format` | `Labels` (times, uptimes, counts, short paths), `Json` |
| `text` | `Editors`, `TextSearch`, `Highlighter`, `Diffs`, `DiffPane`, `MarkdownHtml`, `HtmlPane`, `LocalImages`, `LocalLinks`, `MermaidDiagrams`, `SourceLinks`, `Plain` (below) |
| `tree`, `layout`, `tabs` | `TreeView` (the one tree; a double click activates a leaf, Enter the selected node); `ShrinkingRow` (chips give up width before buttons); `FixedRowTabs` (tab headers in fixed rows); `MovableTabs` (a tab dragged along the row reports where it lands) |
| `desktop` | `ExternalEditor` (IntelliJ, else the default application), `FileChoosers` (native on macOS), `Platform` (macOS or not, the menu modifier, `PATH` lookup), `Browser` |

Threads:

| Class | Does |
|---|---|
| `Edt.run`, `Edt.runAndWait` | run on the EDT now or posted; the bounded wait gives up and the task never starts late |
| `Coalescer` | many requests from any thread become at most one pending run on the EDT, which reads the latest data |
| `Debouncer` | runs once requests stop for a delay (the gutter: 250 ms) |
| `EdtWatchdog` | probes the EDT every 100 ms. A probe waiting 200 ms is a stall, classified once (`startup`, `drawing` when in Java2D's render queue, `comando`, `jdk`) with where it was; one INFO line when it ends. 1 s (2 s for `drawing`) is a hang, logged with the EDT's stack |

Text:

| Class | Does |
|---|---|
| `Editors` | configures every text area: IntelliJ's Dark colours (the panels' editors also Light), font, syntax by extension, file name or fence language |
| `TextSearch` | find in a text area with every match marked |
| `Highlighter` | syntax colours for text painted outside a text area (the transcript); any thread |
| `Diffs`, `DiffPane` | unified diff with three lines of context, coloured |
| `MarkdownHtml`, `HtmlPane` | CommonMark with tables to HTML for Swing; raw HTML escaped; an image is its picture when it is a file on this machine (`LocalImages`: PNG, JPEG, GIF as they are, an SVG drawn by jsvg into `cache/images/`), else its alt text; fences coloured as the editors; a light page or a dark one, each pane with its own style sheet; headings carry GitHub's ids; a web link opens in the browser at a click, a `#` link scrolls to its heading at a double click and keeps it there as pictures arrive until the reader scrolls, any other goes to the pane's owner at a double click (`Workspace.headingWhenOpen` scrolls another document's panel to the heading its link names) |
| `MermaidDiagrams` | Mermaid blocks drawn by `mmdc` into `cache/mermaid/`, once per diagram text, one at a time |
| `SourceLinks` | `path/File.java:42` becomes a link that opens the file at the line |
| `Plain` | `Plain.only(c)` turns HTML rendering off before text is set; `Plain.text(s)` guards a string starting with `<html>` |

Rules: colours, fonts and spacing come from `Theme` (excepted: the transcript's `Style`, the terminal's palette, the
light page `HtmlPane` draws documents on, and the selected tab of `FixedRowTabs`); placeholder, spinner and error states
are `Overlay` (excepted: the terminal, whose placeholder starts the shell on a click and whose exit bar offers a
restart); a user action is a `Command`; work from other threads reaches Swing
through `Edt` or a `Coalescer`.

## 2. Commands and keys

Every user action is a `Command`: id, label, icon, key, menu path or area, action, enablement. The menu bar is built
from `Menus`: Files, Edit and Tools (with Embedded QuestDB and Help), holding only what has no key or control in the
window and what macOS expects in a menu ([record 59](adr/0059-three-menus-for-what-has-no-key.md)). Every item and
submenu has an icon and a tooltip saying what it does. A command in no menu is named by its area (Panels, Run,
Session, Session ▸ Model, Session ▸ Effort, Connection, Results, Edit, Window, comando) in the palette and the keyboard
shortcuts; the registry refuses a command with neither. Key bindings and the palette (⌘⇧P) come from the registry.

The application menu's About comando, and the palette's, open `About`'s card: drawn by hand (warm charcoal, a gold
hairline, Helvetica Neue Light, Charter italic, spaced small capitals), worded by `about.md` beside the class,
whose `{version}`, `{java}`, `{claude}`, `{system}`, `{home}` and `{year}` take the run's values
([record 60](adr/0060-about-is-a-card-worded-by-a-file.md)).

- A command sits in at most one menu and has at most one key; registering an id twice throws.
- In the main window a command's key is claimed before the focused component sees it (`KeyPriority`), because
  RSyntaxTextArea binds ⌘⏎, ⌘J and ⌘⇧K, which comando's commands also use, and the terminal takes every key. A disabled command leaves its key to
  the component, so ⌘J (plot) stays enabled and says "no result" rather than joining lines; ⌘/ is left to the editor.
- Run keys act only in the focused panel's editor. Edit commands (undo, redo, cut, copy, paste, select all) run the focused
  component's own binding. Menu items grey out what cannot run; the palette lists only what can, ranked by label match.
- On macOS ⌘Q belongs to the application menu's quit handler.

## 3. Widgets

### 3.1 The contract

```java
public interface Widget<V> {
    JComponent component();
    void render(V view);          // on the EDT, when the value or its version changes
    default void unmount() { }    // about to leave the hierarchy
}
```

A widget holds no state beyond what Swing needs, changes nothing directly (it dispatches intents through
`WidgetContext.intents()`), calls no adapter, imports no other widget, and removes in `unmount()` every listener it added
to a long-lived object. `WidgetContext` carries the intent sink and the action registry.

### 3.2 The registry

`WidgetRegistry` maps a placed view's type to a factory; adding a widget is one registration and one `ViewSpec` record.
`ViewTabs` creates the widget when the view appears, renders it when the view's version changes, and unmounts it when
the view closes; a view tab has a pin toggle and a close button.

| Type | Widget | Shows |
|---|---|---|
| `table` | `TableViewWidget` over `GridTable` | rows; sort and find run on the EDT, so they stop at 200 000 rows; a growing result appends rows |
| `plot` | `PlotWidget` over `PlotCanvas` | series over shared axes, each pixel column reduced to min and max; more than 100 000 points are sorted off the EDT; time axes on clock steps; drag to zoom, double-click resets |
| `diff` | `DiffWidget` | a unified diff |
| `markdown` | `MarkdownWidget` | rendered Markdown |
| `text` | `TextWidget` | a file or text, read-only, highlighted, at a line; a header button opens it in IntelliJ |
| `tree` | `TreeWidget` | test results, a schema; file links open files, insert links fill the editor |
| `console` | `ConsoleWidget` | a Gradle run: output, state, stop, test tree |

Panels build the rest: `CommandEditor` (RSyntaxTextArea; per-block gutter icons; each block coloured as what it is,
SQL only while the panel's database is open; SQL completion from the bound schema while the database is open; a
session's insert is one undo step and never moves the caret), the Results grid, `TranscriptWidget`, `TerminalWidget`
(JediTerm on pty4j) and `ApprovalInbox` (the cards).

### 3.3 Capabilities

A widget marks its root with `Capabilities.mark` and implements what it can do. The shell binds each key once; the key
acts on the capability nearest the focused component, else on the lower tab in front.

| Capability | Key | Implemented by |
|---|---|---|
| `Searchable` | ⌘F opens the one find bar (finds as you type; ⌘G, ⌘⇧G; Esc closes) | transcript, text, diff, grid, console, editor, terminal |
| `Copyable` | ⌘⇧C | transcript, text, markdown, diff, tree, grid, console, Results |
| `Exportable` | ⌘⇧S (save dialog in the last export directory) | grid (CSV), plot (PNG), markdown, diff |
| `Zoomable` | ⌘= ⌘- ⌘0 | transcript, text, editor, plot, terminal (8–40 pt) |

## 4. The transcript

The Session tab paints a session's transcript with Java2D rather than Swing text components. Swing's HTML renderer lays
out the whole document on every change; a day's transcript reaches thousands of blocks and its last block changes on
every stream delta. The painted renderer lays out a block once per width and style, re-lays only what changed, and
paints only the clip.

### 4.1 Data and layout

| Layer | Type | Notes |
|---|---|---|
| Store | `Transcript` (`core.transcript`) | the session's blocks; one writer (the strand); appended or replaced by index, published through a volatile version |
| Block | `UserPrompt`, `AssistantText`, `Thinking`, `ToolCall`, `Note`, `TurnEnd` | a block with a `parent` belongs to a subagent |
| Paragraphs | `MarkdownDoc.parse` → `Para` of `Span` runs | headings, text, lists, quotes, code, tables, rules; `path:line` becomes a link |
| Layout | `TextBox` | lines broken with `LineBreakMeasurer`, kept until the width or style changes |

While text streams, paragraphs equal to earlier ones keep their layout; only the growing last one is laid out again.
`TranscriptCanvas` holds one item per top-level block, a subagent's blocks inside its tool card. Changed and visible
items are laid out at once; after a width or zoom change the rest get an estimated height and an idle pass lays them
out in 8 ms slices, nearest the viewport first, keeping the first visible line in place. Painting finds the items in the
clip by binary search.

| Item | Paints |
|---|---|
| prompt | a bubble, with the panel it came from |
| text | paragraphs, highlighted code, tables; a turn's conclusion (its last text before the turn end, a live one or one read from a stored conversation) in gold |
| thinking | only while it is the latest item of its list; collapsed with its token count once it ends |
| tool card | running, done or failed; a one-line summary; "running · 12 s", or "waiting for your approval · ⌃2"; open: input, result (400 lines), a subagent's blocks; opens by itself for edits, errors and running subagents. A subagent card adds the tool calls its blocks made, at any depth, and the latest: "running · 1 min 04 s · 9 tools · Read docs/ui.md" (the latest drops first when the card is narrow), then "done · 1 min 30 s · 12 tools". An Agent call whose result says it launched its subagent into the background reads "running in background · …" while the session lists that subagent among its open tasks, and done once it leaves the list. A card with no nested blocks shows no count: a stored conversation keeps a subagent's blocks in a file of its own. Its header links to its stored conversation |
| note | an icon by kind and the first line; error notes start open |
| hook group | consecutive hook outputs as one line, "hooks · UserPromptSubmit, SessionStart (3)" |
| turn end | the duration, no cost ([record 46](adr/0046-clear-buttons-text-coloured-by-kind-checkpoint-due.md)); an interrupted turn reads muted |

### 4.2 The widget

`TranscriptWidget` loads the last 2 000 blocks, with a button for the 2 000 before; later blocks are appended. It follows
the end until the user scrolls; a "↓ Latest" button returns, and sending a prompt follows again. Selection runs across
blocks; ⌘⇧C copies it, or the loaded blocks, as Markdown; ⌘F opens the cards that hold matches and closes them again
afterwards. A `path:line` link opens the file in the side pane.

While a subagent runs, a strip above the transcript holds one chip per running top-level Agent card, foreground or
background, in start order (spinner, description clipped to 28 characters, elapsed time), then one per background
subagent of the session (`local_agent` tasks, hourglass) that no card in the loaded window shows; shell tasks stay out,
they show in the session's waiting status. A click on a card's chip opens the card and scrolls it to the top of the
view. The strip is one line: the chips that do not fit are counted in a last "+N" chip, whose tooltip names them. It
hides when it is empty; its timer runs only while it shows; a transcript that followed its end still shows it when the
strip appears or goes. A card's counts are kept as blocks arrive, never by walking its blocks on a paint; its times
survive a rebuild of the tab (a session switch, "Show earlier").

The "transcript" link in a subagent card's header (`subagent:<toolUseId>`) opens the subagent's stored conversation as
a lower view, "agent · <description>": the task quoted, each text, each tool call as one list item with its result
clipped to 200 characters ([record 64](adr/0064-sub-agents-are-shown-live.md)). Two subagents with one description get
"agent · <description>" and "agent · <description> (2)". While the session runs and the file grows, the view is rendered
again every 2 s, keeping the reader where it was, or at the end when it was at the end.

### 4.3 The frame-time gate

`./gradlew :ui:transcriptGate` replays `SyntheticSession` (8 turns, 41 tool calls with a subagent, about 10 k tokens) one
16-character delta per frame, laying out and painting a 1200 × 800 viewport at the end, then streams a 9 000-character
open thinking block. It fails when either 99th percentile exceeds 16 ms. It also prints, without failing on them, the
frames of a 1 500-line code fence whose last 100 lines stream (a growing block keeps the layouts of its whole lines), and
the time of a find over 2 000 loaded blocks, for a word in most of them and for a word in a few. An exact layout of
many blocks, which a find needs for exact positions, spreads them over the cores. On 2026-10-18 (Apple silicon, 12
cores, JDK 25, headless, 1 798 frames) p99 was 3.39 ms, the thinking block's 1.41 ms and the code fence's 4.75 ms; the
find took 540 ms and 457 ms (1.4 s on one core: the font code contends, so 12 cores give about 2.7 times). `TranscriptShot` paints the session to a PNG; `docs/images/transcript.png` is its output.
