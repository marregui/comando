# 64. Sub-agents are shown live

| Date | Status | Decided by |
|---|---|---|
| 2026-10-18 | accepted | Miguel (the feature, sub-agents only and not shell tasks in the strip), Claude (how: the status text, the strip's chips, the link in the card's header, the reading of Claude Code's sub-agent files and its limits) |

## 1. Context

A session often starts several sub-agents through the Agent tool, each running for minutes. Their blocks arrive whole,
nested in the Agent call's card (Claude Code 2.1.300+ forwards no streaming events for sub-agents). Between start and
result the user could not tell a stuck sub-agent from a busy one, which of four is slow, or what one has read, so
waiting or stopping was decided blind.

Claude Code keeps each sub-agent's whole conversation in a file of its own. The layout is not documented: beside the
session's `<session-id>.jsonl`, the directory `<session-id>/subagents/` holds `agent-<id>.jsonl` and
`agent-<id>.meta.json`, whose `toolUseId` is the id of the Agent call that started it (2.1.302, read on 2026-10-17).

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| card status | a running sub-agent card reads `running · <elapsed> · <n> tools · <last>`: `n` counts the tool calls nested in it at any depth, `last` names the latest, clipped to 60 characters; once done, `done · <elapsed> · <n> tools`; a failure keeps its mark | progress, and what it works on, without opening the card |
| how counted | as each block is appended, on every card around it; never by walking the blocks on a paint; a card with no nested blocks shows no count (a stored conversation keeps a sub-agent's blocks in its own file) | the transcript gate holds; "0 tools" would be false |
| background | an Agent call whose result reads "…launched… agentId: <id>" reads `running in background · …` while the session lists `<id>` among its `local_agent` tasks; a launch seen live counts as running for 10 s before the list holds it; then done. Its blocks keep arriving in the stream, so it counts as a foreground one does | on this machine 39 of the last 40 sub-agents ran in the background |
| strip | above the transcript while sub-agents run: a chip per running top-level Agent card in start order, then a chip per background sub-agent (`local_agent`) that no card shows; a card's chip opens and scrolls to it; one line, the chips that do not fit counted in a "+N" chip; hidden when empty; its own timer runs only while it shows | four parallel agents compared at a glance |
| shell tasks | not in the strip | the session's waiting status shows them ([record 43](0043-a-session-waiting-on-background-work-shows-it.md)) |
| stored conversation | the card's header links to it (`subagent:<toolUseId>`); comando reads Claude Code's files, off the EDT, and shows a lower view named `agent · <description>` | what a sub-agent read and did, beyond the steps the card shows |
| following | while the session runs, the view is rendered again every 2 s while the file grows, keeping the reader's place; it stops after 30 s without growth, when the meta file says `finished`, or when the session stops; at most 4 follow at once, the oldest stops first; two sub-agents with one description get distinct titles, numbered | live, at a bounded cost; a view is found by its title |
| large files | only the task and the last 2 MB are read, and the view says so, under the task, so a refreshed view keeps its start; each tool result is clipped to 200 characters | a long sub-agent cannot stall the reader or the view |

## 3. Consequences

The undocumented layout is a dependency on Claude Code. Its failure mode: when a later version moves or renames the
files, the link stays and following it gives the notice "transcript not found for this sub-agent"; the card status and
the strip, built from the stream alone, are unaffected. The launch text is a second one: when it changes, a background
sub-agent's card reads done at its launch, as before this record, and its chip is the hourglass one.

A running sub-agent whose card lies before the 2 000 blocks the tab loads has no card chip; a background one keeps its
hourglass chip.

`Intent.OpenSubagent` and `Effect.ShowSubagent` are added; `ClaudePort.showSubagent` is routed to `ClaudeAdapter`,
which reads with the package-private `SubagentFiles` on a thread of its own. `TranscriptCanvas` gains `runningAgents`,
`reveal` and the header link; `TranscriptWidget` gains the `AgentStrip` and `setBackgroundTasks`, fed by `LowerPanel`.
