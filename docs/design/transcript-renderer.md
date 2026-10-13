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
| thinking | open while it streams, then collapsed to a line with the token count, unless the user clicked it; opens on click |
| tool card | an icon for running, done or failed; a one-line summary of the input; "running · 12 s" while it runs (a 1 s repaint, only while a card runs and the tab shows), "waiting for your approval · ⌃3" in the warn colour while an approval card holds the call; open: the input (shell command, edit as a diff, write as a diff from empty, subagent prompt), the result clipped to 400 lines, and the subagent's blocks. A card opens by itself for edits, errors and running subagents, and never closes by itself |
| note | an icon by kind (error, denial, retry, interrupt, command) and the first line with ▸; a click opens the full text, up to 400 lines; error notes start open |
| hook group | consecutive hook outputs as one closed line, "hooks · UserPromptSubmit, SessionStart (3)" ("hook · SessionStart" for one); open, each hook's text; a JSON reply that only adds context shows as `name: additionalContext`, any other reply raw |
| turn end | duration and cost, selectable; an interrupted turn reads muted, "interrupted · 12.0 s · $0.120 total" |

Layout of the canvas at a width:

1. Items that changed and items intersecting the viewport are laid out now.
2. After a width or zoom change, the other items get an estimated height, and an idle pass on a Swing timer lays them out nearest the viewport first, in slices of at most 8 ms, keeping the first visible line in place (or the end, when the view was at the end).
3. Find, select-all and copy lay out every item exactly first.

Painting walks the items whose vertical range intersects the clip, found by binary search over their tops.

## 4. The widget

`TranscriptWidget` loads the last 2 000 blocks when it opens a transcript, with a button that adds the 2 000 before; blocks that arrive later are appended, so a long-running session grows past 2 000 on the canvas. On each change it compares every shown block with the store by identity, updates the items whose block was replaced, appends the new ones, and keeps following the end when the view was at the end. Only a scroll by the user stops following; a prompt sent from a panel follows again, and while the view does not follow, a "↓ Latest" button at the bottom right returns to the end. The shell renders transcript changes through a `Coalescer`, so a burst of stream deltas from the session strand is one update.

Selection runs across blocks with the mouse; ⌘⇧C copies the selection, or the loaded blocks as Markdown, each tool call as `**Name** · summary` with its input and its result (clipped to 400 lines) fenced, and each turn end as an italic footer; ⌘F finds with every match marked, opening the cards, notes, hook groups and thinking that hold a match, and closing them again when find closes, except the one with the current match and any the user toggled meanwhile; ⌘= and ⌘- change the style's font size, which makes every layout stale. A `path:line` link opens the file in the side pane at the line; a web link opens in the browser.

## 5. The frame-time gate

`./gradlew :ui:transcriptGate` replays `SyntheticSession` from `testkit` (8 turns, 40 tool calls including a subagent, Markdown with headings, lists, code, tables and source references, about 10 k tokens) one stream delta of 16 characters per frame. Each frame applies the change, lays out, and paints a 1200 × 800 viewport at the end, as the widget does while following a turn. It then streams a 9 000-character thinking block, open, one delta per frame: an open thinking block keeps one text box per line and lays out only the last. The task fails when either 99th percentile exceeds 16 ms.

Run of 2026-10-13, Apple silicon, JDK 25, headless, 1 798 frames over 11 455 px of content: p99 about 3 ms, under 16 ms for every frame ([Quality](quality.md) keeps the measured figures). The open thinking block, 562 frames, also stays under 16 ms for every frame. The same run measured layout and paint:

| Measure | Value |
|---|---|
| Width change, visible part laid out and painted | 6.71 ms |
| Idle pass after it, in 8 ms slices | 25.25 ms |
| Full exact relayout | 68.14 ms |
| Paint after a scroll | 5.73 ms |

`TranscriptShot` (test sources) paints the synthetic session to a PNG for visual checks; `docs/images/transcript.png` is its output.
