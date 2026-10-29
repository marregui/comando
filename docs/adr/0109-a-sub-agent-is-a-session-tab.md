# 109. A sub-agent is a session tab, and the session card outlines its sub-agents

| Date | Status | Decided by |
|---|---|---|
| 2026-10-28 | accepted | Miguel and Claude |

## 1. Context

A session that delegates runs sub-agents for minutes. [Record 64](0064-sub-agents-are-shown-live.md) showed one, from
its card's "transcript" link, as a lower Markdown view: the task, then each step one list item, results clipped to 200
characters. Miguel: a delegating session gave no cue but scrolling and no way to reach the work under way; a sub-agent
should open as a tab in the lower panel beside the Session tab and act as a session tab, with another icon in the same
colour, and the sessions sidebar's card should outline the sub-agents, each a go-to at a double click. This record
amends record 64's view and adds the outline to the card of records 87 and 107.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the tab | a sub-agent opens as a view of its own type (`ViewSpec.Agent`: the session, the Agent call's id, the description, the following state) in the lower panel, titled "agent · ‹description›", with the agent icon: Claude's orange bubble with a branch, the session icon's colour and stroke | a session's tab, related by colour, told apart by its mark |
| what it shows | the sub-agent's conversation as a session's transcript (`AgentWidget` around `TranscriptWidget`): the same cards, links (record 108), strip and search; a line above it says whether it is followed, or why no longer | the sub-agent observed "as if it was its own session"; one renderer |
| how it is read | Claude Code's sub-agent file, a line at a time as it grows (`SubagentFiles.Conversation`), mapped by a `StreamMapper` as a stored conversation is, into a transcript kept under `TranscriptRegistry.subagentKey(session, toolUseId)`; the mapper sends the window nothing (a sub-agent is no session: its init, turns and usage must not touch its parent's state) | the stored-conversation mapping exists and is tested; the file grows by whole lines |
| following | as record 64: every 2 s the file grew, until the stream or the meta file says it ended, the session stops, 10 min without growth, or four newer tabs follow; at its end a call left running gets the no-result error | unchanged rules, new rendering |
| opened again | read again from the start into the same transcript; the same title | a tab is never stale after it is reopened |
| nested | a sub-agent's own sub-agents open from its tab's card links, each a tab of its own | the same link, read in the parent session's folder |
| lifetime | the tab stays until its ✕; its transcript goes with its session (`TranscriptRegistry.forget`) | as any view |
| the card's outline | a session's card (its panel's, or a headless session's) lists under its facts, as "Sub-agents", the latest 8 sub-agents its own turns started: a mark and state (▸ running, ▸ running in background, ✓ done, ✗ failed in red), the description, and the tool calls that ran inside it at any depth; a double click on a line opens its tab. Read from the session's transcript, again only when its version or its background tasks change | where the delegation is seen at a glance, with go-tos; bounded work on the card's tick |
| a background launch | reads running while the session lists its id among its `local_agent` tasks, then done | as its card in the transcript (record 64) |

Miguel decided the tab, its colour and icon, and the outline with go-tos. Claude decided the view type, the reading
through the stored-conversation mapper, the outline's content and its limit of 8.

## 3. Consequences

`ViewSpec.Agent`, `AgentWidget`, `TranscriptRegistry.subagentKey`, `Delegation` and `TabCard.agents` are added; the
adapter's Markdown rendering of a sub-agent goes, and its follow states are plain sentences. The view sits among the
lower panel's views, after the fixed tabs, as every placed view does. A launch seen in the stream before the session's
task list holds it reads "done" in the outline for those seconds (the transcript card's 10 s grace is not repeated).

Proposed, not built here: the tab's footer with the sub-agent's model, effort and tokens; `session_send` peers and Codex
runs as tabs of the same kind; a fan-out mark on the sidebar row.

Tested in `SubagentViewTest`, `SubagentFollowTest` and `SubagentTailEdgesTest` (the tab's spec and transcript as the
file grows, the stops, two with one description, four at most), `AgentWidgetTest` (the transcript followed, the state,
a session's sub-agents forgotten with it), `WorkspaceTest` (the outline: states, a background one, the count at any
depth, the double click), and off screen in `SessionCardUiTest` (`:app:uiTest`: the tab in the real window).
