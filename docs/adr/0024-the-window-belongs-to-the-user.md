# 24. The window belongs to the user

| Date | Status | Decided by |
|---|---|---|
| 2026-10-13 | accepted, for Miguel to accept or reverse | Claude, from the UX review of 2026-10-13 |

## 1. Context

A review of comando at `69c0ed9`, run live and in the code, found that the tool's own actions could take the user's text, keyboard or layout: the ran block stayed selected, so a session's `ui_insert` replaced the prompt that asked for it; ⌘W discarded a panel with no way back; a session in another panel switched the lower tab under the user; "Always" wrote a tool-wide rule with no rule shown. Miguel asked for the best user experience possible, so each open question took the option that protects the user's work.

## 2. Decision

| # | Question | Decision |
|---|---|---|
| 24.1 | How the block that ran is shown | tinted until the next edit; never selected; the caret stays |
| 24.2 | Closing a panel | no question; ⌘⇧T reopens the last ten; a question only when the panel's shell runs a foreground job, was replaced by a program, or cannot be checked |
| 24.3 | Hook output in the transcript | consecutive hook notes fold into one closed line; a click opens them |
| 24.4 | "Always" on an approval card | the card shows the rule; L must be pressed twice within 3 s, a click asks in the card |
| 24.5 | A session placing views or arranging the window | a tab is selected only for the session of the focused panel; layout changes wait for a typing pause, post a notice and can be undone |
| 24.6 | The prompt bar | a growing text area; ⏎ sends, ⇧⏎ adds a line; history per session, kept across restarts |
| 24.7 | Type names in the schema | QuestDB's own (`SYMBOL`, `TIMESTAMP`) when the server is QuestDB |
| 24.8 | Menus | Edit and Help menus; run Gradle moves from ⌘⇧G (macOS find previous) to ⌥⌘G |

## 3. Consequences

Commands carry an enablement, and a disabled command leaves its key to the focused component, so a key acts where the keyboard is. Notices carry a subject, so the event that settles a subject clears its notice, and an error stays until it is settled. The rules are written down in [Command dispatch](../workbench.md#2-running-text), [Approvals and safety](../claude.md#3-approvals-and-safety), [MCP UI tools](../claude.md#2-mcp-tools) and [State and intents](../architecture.md#4-state-intents-effects).
