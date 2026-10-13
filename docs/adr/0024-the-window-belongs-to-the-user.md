# 24. The window belongs to the user

| Date | Status | Decided by |
|---|---|---|
| 2026-10-13 | proposed, for Miguel to accept or reverse | Claude, from the UX review of 2026-10-13 |

## 1. Context

A review of comando at `69c0ed9`, run live and in the code, found that the tool's own actions could take the user's text, keyboard or layout: the ran block stayed selected, so a session's `ui_insert` replaced the prompt that asked for it; ⌘W discarded a panel with no way back; a session in another panel switched the lower tab under the user; "Always" wrote a tool-wide rule with no rule shown. Miguel asked for the best user experience possible, so each open question took the option that protects the user's work.

## 2. Decision

| # | Question | Decision |
|---|---|---|
| D1 | How the block that ran is shown | tinted until the next edit; never selected; the caret stays |
| D2 | Closing a panel | no question; ⌘⇧T reopens the last ten; a question only when the panel's shell runs a foreground job |
| D3 | Hook output in the transcript | consecutive hook notes fold into one closed line; a click opens them |
| D4 | "Always" on an approval card | the card shows the rule; L must be pressed twice within 3 s, a click asks in the card |
| D5 | A session placing views or arranging the window | a tab is selected only for the session of the focused panel; layout changes wait for a typing pause, post a notice and can be undone |
| D6 | The prompt bar | a growing text area; ⏎ sends, ⇧⏎ adds a line; history per session, kept across restarts |
| D7 | Type names in the schema | QuestDB's own (`SYMBOL`, `TIMESTAMP`) when the server is QuestDB |
| D8 | Menus | Edit and Help menus; run Gradle moves from ⌘⇧G (macOS find previous) to ⌥⌘G |

## 3. Consequences

Commands carry an enablement, and a disabled command leaves its key to the focused component, so a key acts where the keyboard is. Notices carry a subject, so the event that settles a subject clears its notice, and an error stays until it is settled. The rules are written down in [Command dispatch](../design/command-dispatch.md), [Approvals and safety](../design/approvals-and-safety.md), [MCP UI tools](../design/mcp-ui-tools.md) and [State and intents](../architecture/01-state-and-intents.md).
