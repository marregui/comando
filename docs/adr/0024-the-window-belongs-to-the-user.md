# 24. The window belongs to the user

| Date | Status | Decided by |
|---|---|---|
| 2026-10-13 | accepted; item 24.6 replaced by [record 25](0025-no-prompt-bar.md); its menus (24.8) replaced by [record 36](0036-five-menus-by-subject.md), [record 59](0059-three-menus-for-what-has-no-key.md) and [record 75](0075-the-menu-bar-is-edit-and-actions-flat.md); amended 2026-11-01 (see Amendments: the whole conversation, keys through it, no movement on updates away from the view) | Claude |

## 1. Context

A review of comando at `69c0ed9`, run live and in the code, found that the tool's own actions could take the user's
text, keyboard or layout:

- the block that ran stayed selected, so a session's `ui_insert` replaced the prompt that asked for it;
- ⌘W discarded a panel with no way back;
- a session in another panel switched the lower tab under the user;
- "Always" on an approval card wrote a tool-wide rule without showing it.

## 2. Decision

| # | Question | Rule |
|---|---|---|
| 24.1 | How the block that ran is shown | tinted until the next edit; never selected; the caret stays |
| 24.2 | Closing a panel | no question; ⌘⇧T reopens the last ten; a question only when the panel's shell runs a foreground job, was replaced by a program, or cannot be checked |
| 24.3 | Hook output in the transcript | consecutive hook notes fold into one closed line; a click opens them |
| 24.4 | "Always" on an approval card | the card shows the rule; L must be pressed twice within 3 s, a click asks in the card |
| 24.5 | A session placing views or arranging the window | a tab is selected only for the session of the focused panel; layout changes wait for a typing pause, post a notice and can be undone |
| 24.6 | The prompt bar | a growing text area; ⏎ sends, ⇧⏎ adds a line; history per session, kept across restarts (replaced by [record 25](0025-no-prompt-bar.md): no prompt bar) |
| 24.7 | Type names in the schema | QuestDB's own (`SYMBOL`, `TIMESTAMP`) when the server is QuestDB |
| 24.8 | Menus | Edit and Help menus; run Gradle moves from ⌘⇧G (macOS find previous) to ⌥⌘G |

Claude decided, from the UX review of 2026-10-13, taking in each question the option that protects the user's work;
the record was left for Miguel to accept or reverse.

## 3. Consequences

Commands carry an enablement, and a disabled command leaves its key to the focused component, so a key acts where the
keyboard is. Notices carry a subject, so the event that settles a subject clears its notice, and an error stays until
it is settled. The rules are written down in [Running text](../workbench.md#2-running-text),
[Approvals and safety](../claude-code.md#4-approvals-and-safety), [MCP tools](../claude-code.md#3-mcp-tools) and
[State, intents, effects](../architecture.md#4-state-intents-effects).

In the code: `Workspace.CLOSED_KEPT` is 10, `ApprovalInbox.CONFIRM_MILLIS` is 3 000 ms and
`LayoutKeeper.TYPING_PAUSE_MILLIS` is 2 000 ms. The menus of item 24.8 are replaced (see the status); ⌥⌘G still runs
Gradle.

## Amendments

2026-11-01: the Session tab holds the whole conversation, moves through it by key, and does not move under the reader.
Miguel, in the first-release review: "2K blocks is an arbitrary number and the likelyhood that I will ever scroll to see
the first block in a 2K list is low. I would make that size to a more reasonable limit, perhaps with better navigation,
to render more or less, then to move up or down the blocks with the possibility to always go the the last and never
perceiving scroll flicker when the panel receives updates away from the visual block." Miguel's decision: build it in
this release.

| # | Question | Rule |
|---|---|---|
| 24.9 | How much of a conversation the Session tab holds | all of it: every block is an item, and no button offers earlier blocks. Only a window of pixels around the view is laid out: the idle pass lays out two view heights (at least 1 600 px) above and below it; an item farther than twice that lets its layout go and keeps its height; an answer's Markdown is parsed at its first layout |
| 24.10 | Moving through it | Home the first block, End the latest (and follows it), ⌥↑ and ⌥↓ the previous and next prompt, PgUp and PgDn a page; each is in the palette under Session; the "↓ Latest" button stays; a component that has the key itself, as the editor has every one of them, keeps it |
| 24.11 | Blocks that arrive or change away from the view | while the view is not at the end, the block at its top keeps its place on screen when blocks arrive, a block changes (a result, streamed text), a card opens or closes elsewhere, or estimated heights become exact; at the end, the view follows |
| 24.12 | Find, select-all and copy over the whole conversation | ⌘F reads each block's text as its boxes would show it, Markdown marks and tabs as painted, so it lays out nothing to count or step through the matches; it opens the closed items that hold a match, as before, and lays out only the item of the match it shows, through the window. Select-all lays out nothing, and copying after it reads the blocks' text; ⌘⇧C with nothing selected copies every block as Markdown from the blocks, as before; a selection whose ends lie far apart copies the items between from their blocks when they are not laid out. On a long conversation the reading is spread over the cores while the EDT waits |

The window of 2 000 blocks went because the numbers allowed every block as an item once parsing waits for layout, and
the window in pixels came because laying out the whole conversation did not: on a synthetic conversation of 50 000
blocks, appending every block took 806 ms on the EDT (Markdown parsed and code highlighted per block), and the idle
pass laid out every block, about 25 KB each; a find then laid out all of them, 13 s and 2.6 GB. Measured after, on the
50 000 blocks and on the largest stored conversation on Miguel's machine, 1 887 blocks:

| | 50 000 blocks | 1 887 blocks |
|---|---|---|
| open, EDT | 85 ms, 13 items laid out | 27 ms, 14 items laid out |
| wheel step and paint, p99 | 6.6 ms | 2.3 ms |
| a jump into a part not laid out, and its paint, p99 | 19 ms | 35 ms (accepted for now: the items the view shows are laid out at once on the EDT) |
| first find, EDT | 114 ms, heap +0 MB (530 ms the first time in a JVM, before its code is compiled) | 46 ms (173 ms the first time in a JVM) |
| next match | 0.2 ms | 3.2 ms |
| select-all, then its text | 0.01 ms, then 32 ms | 0.00 ms, then 2.2 ms |
| ⌘⇧C with nothing selected: every block as Markdown | 91 ms (49.8 M characters) | 33 ms (2.2 M characters) |

In the code: `TranscriptCanvas.BAND_MIN` is 1 600 and `BAND_VIEWS` 2; `TranscriptWidget.Move` names the keys;
`Item.findTexts` is what find and copy read; tested in `TranscriptNavigationTest`, `TranscriptAnchorTest` and
`TranscriptFindTest`.
