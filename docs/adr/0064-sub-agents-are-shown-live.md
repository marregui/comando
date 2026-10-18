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
| how counted | as each block is appended, on every card around it; never by walking the blocks on a paint | the transcript gate holds |
| strip | above the transcript while sub-agents run: a chip per running top-level Agent card in start order, then a chip per background sub-agent (`local_agent`) that no running card shows; a card's chip opens and scrolls to it; hidden when empty; its timer runs only while it shows | four parallel agents compared at a glance |
| shell tasks | not in the strip | the session's waiting status shows them ([record 43](0043-a-session-waiting-on-background-work-shows-it.md)) |
| stored conversation | the card's header links to it (`subagent:<toolUseId>`); comando reads Claude Code's files, off the EDT, and shows a lower view named `agent · <description>` | what a sub-agent read and did, beyond the steps the card shows |
| following | the view is rendered again every 2 s while the file grows; it stops after 30 s without growth, when the meta file says `finished`, or when the session stops; at most 4 follow at once, the oldest stops first | live, at a bounded cost |
| large files | only the last 2 MB are read, and the view says so; each tool result is clipped to 200 characters | a long sub-agent cannot stall the reader or the view |

## 3. Consequences

The undocumented layout is a dependency on Claude Code. Its failure mode: when a later version moves or renames the
files, the link stays and following it gives the notice "transcript not found for this sub-agent"; the card status and
the strip, built from the stream alone, are unaffected.

`Intent.OpenSubagent` and `Effect.ShowSubagent` are added; `ClaudePort.showSubagent` is routed to `ClaudeAdapter`,
which reads with the package-private `SubagentFiles` on a thread of its own. `TranscriptCanvas` gains `runningAgents`,
`reveal` and the header link; `TranscriptWidget` gains the `AgentStrip` and `setBackgroundTasks`, fed by `LowerPanel`.
