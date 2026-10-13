# Transcript renderer

The Session tab shows a session's transcript: prompts, Claude's text, thinking, tool calls with their results, notes, turn ends. It is painted with Java2D, not built from Swing text components, so a long session streams and scrolls within a frame.

## 1. Why painted

Swing's HTML renderer (`HTMLEditorKit`) supports HTML 3.2, lays out the whole document on each change and keeps one component tree per block. A transcript of a working day reaches thousands of blocks, and the last block changes on every stream delta. The painted renderer lays out a block once per width and style, re-lays only the block that changed, and paints only what intersects the clip.

## 2. Data

| Layer | Type | Notes |
|---|---|---|
| Store | `Transcript` in `core.transcript` | the session's block log; one writer (the session's strand), any number of readers; a block is appended or replaced by index, published through a volatile version |
| Block | `Block`: `UserPrompt`, `AssistantText`, `Thinking`, `ToolCall`, `Note`, `TurnEnd` | records; a block with a `parent` belongs to a subagent and names the tool call that started it |
| Paragraphs | `MarkdownDoc.parse` → `List<Para>` of `Span` runs | headings, text, list items, quotes, code with language, tables, rules; a single newline stays a line break; raw HTML stays literal text; `path:line` becomes a link |
| Rich text | `Rich` | text with a font and colour per character, links, per-line backgrounds; tabs expanded to 4 columns |
| Layout | `TextBox` | lines broken with `LineBreakMeasurer` at the width and at every newline; kept until the width or the style changes; a line without wrapping keeps its `TextLayout` |

`Para` records compare by value. While text streams into a block, `Paras.reuse` keeps the painter, and so the layout, of every paragraph equal to one before; only the growing last paragraph is laid out again.

## 3. Items and the canvas

`TranscriptCanvas` holds one `Item` per top-level block; a subagent's blocks are children of the tool card that started them. An item keeps its height and layout until its block, the width or the style changes.

| Item | Paints |
|---|---|
| prompt | the text in a bubble, with the panel it came from |
| text | the paragraphs; code highlighted with the editors' colours; tables as grids |
| thinking | collapsed to a line with the token count; opens on click |
| tool card | an icon for running, done or failed; a one-line summary of the input; open: the input (shell command, edit as a diff, write as a diff from empty, subagent prompt), the result clipped to 400 lines, and the subagent's blocks |
| note | an icon by kind (error, denial, retry, interrupt, command, hook) and the text, clipped to 600 characters |
| turn end | duration and cost |

Layout of the canvas at a width:

1. Items that changed and items intersecting the viewport are laid out now.
2. After a width or zoom change, the other items get an estimated height, and an idle pass on a Swing timer lays them out nearest the viewport first, in slices of at most 8 ms, keeping the first visible line in place (or the end, when the view was at the end).
3. Find, select-all and copy lay out every item exactly first.

Painting walks the items whose vertical range intersects the clip, found by binary search over their tops.

## 4. The widget

`TranscriptWidget` loads the last 2 000 blocks when it opens a transcript, with a button that adds the 2 000 before; blocks that arrive later are appended, so a long-running session grows past 2 000 on the canvas. On each change it compares every shown block with the store by identity, updates the items whose block was replaced, appends the new ones, and keeps following the end when the view was at the end. The shell renders transcript changes through a `Coalescer`, so a burst of stream deltas from the session strand is one update.

Selection runs across blocks with the mouse; ⌘⇧C copies the selection, or the loaded blocks as Markdown; ⌘F finds with every match marked; ⌘= and ⌘- change the style's font size, which makes every layout stale. A `path:line` link opens the file in the side pane at the line; a web link opens in the browser.

## 5. The frame-time gate

`./gradlew :ui:transcriptGate` replays `SyntheticSession` from `testkit` (8 turns, 40 tool calls including a subagent, Markdown with headings, lists, code, tables and source references, about 10 k tokens) one stream delta of 16 characters per frame. Each frame applies the change, lays out, and paints a 1200 × 800 viewport at the end, as the widget does while following a turn. The task fails when the 99th percentile exceeds 16 ms.

Run of 2026-10-12, Apple silicon, JDK 25, headless:

| Measure | Value |
|---|---|
| Frames | 1 798 |
| Frame p50 | 1.16 ms |
| Frame p99 | 3.26 ms |
| Frame max | 6.05 ms |
| Frames over 16 ms | 0 |
| Width change, visible part laid out and painted | 7.19 ms |
| Idle pass after it, in 8 ms slices | 24.86 ms |
| Full exact relayout | 67.02 ms |
| Paint after a scroll | 8.00 ms |

`TranscriptShot` (test sources) paints the synthetic session to a PNG for visual checks; `docs/images/transcript.png` is its output.
