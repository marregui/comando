# 137. A turn reads as its report

| Date | Status | Decided by |
|---|---|---|
| 2026-11-07 | accepted | Miguel and Claude |

## 1. Context

A prompt runs a median of 7 tool calls, 44 at the 90th percentile and 224 at most, and the block the user reads is the
last one. Each card folds by itself, so a long turn still leaves one row per call between the prompt and its report. The
report is known at `TurnEnd` (`TranscriptCanvas.frameReport`), after every item it would fold has been laid out and
possibly read.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what folds | at `TurnEnd`, the items back to the turn's prompt, interim text included, go under one row, "44 tools · 6 min"; the report stays open beneath it | the report is what is read; the row is the sibling of the hook group, so `Fold`, `children()` and the find's reveal work unchanged |
| what does not | a live turn, a turn with no report, a run of only hooks and notes, and items after the report; a turn with a running sub-agent stays open until it ends | nothing is known to be the report before the end, and a row hiding a live card hides what is happening |
| the row's numbers | calls at any depth; "· 1 failed" in the danger colour, closed; no tools reads "no tools"; the time is the turn's, else the stored span, else left out | a failed call inside a closed row must still be seen |
| prompts | a prompt never goes under a row, and steps before a prompt sent mid-turn stay open | ⌥↑/⌥↓ walks top-level prompts, as before |
| the reader's place | a fold forms open, and stays so until a click, when the view is away from the end and shows one of its steps, or the selection meets them; the row's height is taken above the view, so nothing moves; over estimated heights the step at the view's top places the row | folding text under someone reading it loses their place |
| a click | opens and closes as any fold, and is kept for the life of the transcript's canvas, through a sub-agent's end | `Fold` says who opened it; a rebuild folds anew, as card folds reset |
| a stored turn | folds too, with the span between its first and last history lines, in `TurnEnd.spanMillis`; `timed()` and `summary()` do not read it | a stored conversation has no duration, and `timed()` still says so |
| a find | reads inside rows and opens them; a row that forms or closes under a find is read again; a find that moves on closes what it opened, and clearing keeps the current match's row open, as for any fold | one rule for every fold; the row's own title is not searched, so "tools" does not open every row |
| the export, the copy | Export to Markdown is what it was, step by step; a closed row gives nothing to ⌘A, as a closed hook group | the document is the record and the row a view of it |
| a turn cut short | a failed or interrupted turn does not fold | it has no conclusion to stand for its steps: they are the record of how far it got |
| opening a row | lays out from the first step down to what the view shows, for at most 8 ms at a time; the idle pass lays out the rest in slices, and until then the steps below hold estimates | a row of 200 steps with open `Edit` cards took a second on the event thread; steps are laid from the first, so what lies above the view is exact and a step in view does not move |
| going to the report | `TranscriptWidget.showReport()` stops following and scrolls the last turn's report to the view's top, and does nothing when that turn has none; a following transcript stays at its end when a turn ends | a long report starts above the end; the view moves when the user asks, not by itself |

## 3. Consequences

`Items.StepsItem` is the row, next to `HookGroupItem`; `TranscriptCanvas.foldSteps`, `spliceTops`, `readerOn`,
`selectionMeets` and `closeHeld` rewrite `items` at `TurnEnd`; `Finder.folded` keeps a find under way true;
`Block.TurnEnd` gains `spanMillis`, `stored(long)` and `tookMillis()`, and `StreamMapper`'s history load fills the span.
Fold state is not saved: a session switch or "Show earlier" folds every turn again.

Most of a finished transcript is now one row per turn, so the frames of `TranscriptGate` lie over far less content. The
gate replays the session again with each row opened as it forms, and times folding, opening and closing a turn of 224
calls of the shapes real turns have (edits that open by themselves, reads, commands with long results); each stays under
its 16 ms p99. A reader who jumps to the end of an open row not yet laid out sees it fill in over a few idle slices. A find or a
sub-agent chip that lands on a step still lays the row from its first step down to that step in one go: about 250 ms for
a match 2,000 steps down (measured on synthetic rows), as before this record; laying out from the target outwards, with
the steps above it placed by estimate, would bound it, and is not built.

Tested in `TranscriptFoldTest`, `TranscriptWidgetTest`, `StreamMapperTest` and `ReportFirstUiTest`.
`TranscriptCanvasTest`, `TranscriptReadingTest` and `TranscriptCanvasViewportTest` open rows or measure against the
folded height where they read a whole session.

## 4. Alternatives

Folding each tool call group as it ends keeps a turn's interim text between rows and leaves several rows per turn. A
second, report-only view mode needs a switch and two renderings of one transcript. Folding in `core/reduce` would make
the fold state, which is a view of a transcript, part of `AppState`, and every open and close an intent.
