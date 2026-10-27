# 64. Subagents are shown live

| Date | Status | Decided by |
|---|---|---|
| 2026-10-18 | accepted; following and large files amended on 2026-10-18 | Miguel and Claude |

## 1. Context

A session often starts several subagents through the Agent tool, each running for minutes. Their blocks arrive whole,
nested in the Agent call's card (Claude Code 2.1.300 and later forward no streaming events for subagents). Between
start and result the user could not tell a stuck subagent from a busy one, which of four is slow, or what one has
read, so the choice between waiting and stopping was made without data.

Claude Code keeps each subagent's whole conversation in a file of its own. The layout is not documented: beside the
session's `<session-id>.jsonl`, the directory `<session-id>/subagents/` holds `agent-<id>.jsonl` and
`agent-<id>.meta.json`, whose `toolUseId` is the id of the Agent call that started it (2.1.302, read on 2026-10-17).

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| card status | a running subagent card reads `running · <elapsed> · <n> tools · <last>`: `n` counts the tool calls nested in it at any depth, `last` names the latest, clipped to 60 characters; once done, `done · <elapsed> · <n> tools`; a failure keeps its mark | progress, and what it works on, without opening the card |
| how counted | as each block is appended, on every card around it; never by walking the blocks on a paint; a card with no nested blocks shows no count (a stored conversation keeps a subagent's blocks in its own file) | the transcript gate holds; "0 tools" would be false |
| background | an Agent call whose result reads "…launched… agentId: <id>" reads `running in background · …` while the session lists `<id>` among its `local_agent` tasks; a launch seen live counts as running for 10 s before the list holds it; then done. Its blocks keep arriving in the stream, so it counts as a foreground one does | on the author's machine 39 of the last 40 subagents ran in the background |
| strip | above the transcript while subagents run: a chip per running top-level Agent card in start order, then a chip per background subagent (`local_agent`) that no card shows; a card's chip opens and scrolls to it; one line, the chips that do not fit counted in a "+N" chip; hidden when empty; its own timer runs only while it shows | four parallel agents compared side by side |
| shell tasks | not in the strip | the session's waiting status shows them ([record 43](0043-a-session-waiting-on-background-work-shows-it.md)) |
| stored conversation | the card's header links to it (`subagent:<toolUseId>`); comando reads Claude Code's files, off the EDT, and shows a lower view named `agent · <description>` | what a subagent read and did, beyond the steps the card shows |
| following | while the subagent runs, the view is rendered again every 2 s its file grew, keeping the reader's place; it stops when the stream says the subagent ended (the Agent call's result unless it is a launch; a background one's `task_notification`, which names the call), when the meta file says `finished`, when the session stops, or after 10 min without growth; its last line says it is updated, or why it no longer is; at most 4 follow at once, the oldest stops first; two subagents with one description get distinct titles, numbered | a subagent writes nothing while a tool runs or the model answers: 138 of 150 subagent files on the author's machine have a gap over 30 s, the median longest 113 s, so a quiet file is no end; the stream and the meta file are |
| large files | the file is read as it grows, only the whole lines added since the last read; the view keeps the task and the steps from the last 100 tool calls on, says under the task that earlier steps are left out, and ends with how many of all it shows; a file over 256 MB is read from where its last 256 MB start; each tool result is clipped to 200 characters | a byte window held few steps (the last 2 MB of a 16.5 MB file held 15 of 187 tool calls); read incrementally, that file costs 80–170 ms once, then its growth |

Miguel decided the feature, with subagents only and no shell tasks in the strip; Claude decided how: the status text,
the strip's chips, the link in the card's header, and the reading of Claude Code's subagent files and its limits.

## 3. Consequences

The undocumented layout is a dependency on Claude Code. Its failure mode: when a later version moves or renames the
files, the link stays and following it gives the notice "transcript not found for this subagent"; the card status and
the strip, built from the stream alone, are unaffected. The launch text is a second dependency: when it changes, a
background subagent's card reads done at its launch, as before this record, and its chip is the hourglass one.

A running subagent whose card lies before the 2 000 blocks the tab loads has no card chip; a background one keeps its
hourglass chip.

`Intent.OpenSubagent` and `Effect.ShowSubagent` are added; `ClaudePort.showSubagent` is routed to `ClaudeAdapter`,
which reads with the package-private `SubagentFiles.Conversation` on a thread of its own and asks the session's
`StreamMapper` whether the subagent ended. `TranscriptCanvas` gains `runningAgents`, `reveal` and the header link;
`TranscriptWidget` gains the `AgentStrip` and `setBackgroundTasks`, fed by `LowerPanel`.

## Amendments

2026-10-18 (Miguel): the rows "following" and "large files" were amended to the rules above. The first rules stopped
following after 30 s without growth and read the last 2 MB of the file; they left most views stale mid-run and showed
only a long subagent's last few steps.
