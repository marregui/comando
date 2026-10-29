# 24. The window belongs to the user

| Date | Status | Decided by |
|---|---|---|
| 2026-10-13 | accepted; item 24.6 replaced by [record 25](0025-no-prompt-bar.md); its menus (24.8) replaced by [record 36](0036-five-menus-by-subject.md), [record 59](0059-three-menus-for-what-has-no-key.md) and [record 75](0075-the-menu-bar-is-edit-and-actions-flat.md) | Claude |

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
[Approvals and safety](../claude.md#3-approvals-and-safety), [MCP tools](../claude.md#2-mcp-tools) and
[State, intents, effects](../architecture.md#4-state-intents-effects).

In the code: `Workspace.CLOSED_KEPT` is 10, `ApprovalInbox.CONFIRM_MILLIS` is 3 000 ms and
`LayoutKeeper.TYPING_PAUSE_MILLIS` is 2 000 ms. The menus of item 24.8 are replaced (see the status); ⌥⌘G still runs
Gradle.
