# 134. The window says who works, and gives its room to what is read

| Date | Status | Decided by |
|---|---|---|
| 2026-11-06 | accepted; amended by [record 135](0135-the-comparison-follows-the-theme-and-lies-level.md) | Miguel and Claude |

## 1. Context

Claude proposed five changes from a screenshot and a day's use; Miguel took all five. Two questions that day ("are any
sessions waiting on Codex?", "are sessions deadlocking?") took reading transcripts, as the status bar only counted. The
rest were room and wording: a `Write` filled the transcript with green, "No headings" held 220 px, sizes kept their space
while names were cut, and two times side by side did not say what each measured.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| who works | a click on the status bar's count lists who needs you, who works and who waits, what each does and for how long; a click on a line focuses its panel; a running query has a line under its panel's title | the text already existed per session; the same list gesture as the recent notices |
| a `Write` | its card is the file's text with its line count, clipped as any input; no diff, in the card and in the Markdown export | a `Write` gives the whole text and not what it replaces, so every line read as added, on a new file and on an overwrite alike |
| the outline | the pane takes room only while the text has headings; the choice stays on, the headings are still read, the button says why nothing shows, and the keyboard stays in the text as the pane comes and goes | an empty pane is space for nothing; the first heading must bring it without a click |
| a tree's row | a row too narrow for all of it keeps its name and mark and gives up its detail, whole, to the tooltip; a resize measures the rows again | the name is what a row is for; a detail cut in half reads as a different one |
| the times | the session bar and tab say "Bash · turn 4 min"; "requesting" reads "calling the model" | the bar's time is the turn's, the card's the tool's |

## 3. Consequences

`StatusBar.WorkRow`, `workRows`, `workList`, `showWork` are added, `OutlinePane` loses its "No headings" label, and
`TreeView` measures each row against its viewport. Measuring again costs about 7 ms per resize on 3,000 rows. Tested in
`StatusBarWorkTest`, `EditorPanelOutlineTest`, `TreeViewTest`, `TranscriptReadingTest`, `PanelTextsTest`, `TabStatusTest`,
`StreamMapperTest`, `OutlineUiTest` and `WorkListUiTest`.

The list shows the state at its opening and does not tick while open. The sessions, panels and connections lists have no
detail to give up, so a row wider than the tab still scrolls sideways. Record 113's "when it reads" becomes: while the
outline is on, whether it shows or not.

## 4. Alternatives

A session-to-session wait graph needs each wait to name its session, which is free text today. Letting a row's detail run
past the edge, as before, keeps the sideways bar on for every long path.
