# 110. Metrics of turns, sub-agents and plan usage, kept for calibration

| Date | Status | Decided by |
|---|---|---|
| 2026-10-28 | proposed | Claude, on Miguel's ask |

## 1. Context

Miguel: "no capture of metrics that can help improve overall as we work"; the plans' per-day, per-week and Fable
allowances go unused while "some tasks are better done by certain models at a certain effort", which he wants
"automatically callibrated and improved". Today the only record of who did what, at which model, and whether it
passed is the agent ledger, written by hand. comando sees every turn's `result`, every sub-agent's start and end, and
reads the plans' usage every few minutes, and kept none of it. The fleet vision (scratchpad `harness/07-fleet-vision.md`
§3–§4) sets out the schema, the calibration loop that reads it and the pacing of the allowances; this record builds
its first increment, the capture.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| where | `metrics/metrics-<UTC date>.jsonl` in the data root, one JSON object a line, appended whole under a lock (`MetricsLog`) | as `audit/`: plain, appendable, readable by any tool; the data root holds comando's state (record 106) |
| `turn` | at each live `result`: session, conversation, model, base effort, `duration_ms`, `api_ms`, model calls, context and window, queued turns, error and subtype, the turn's `usage` and `modelUsage` as sent, the money field | what a turn cost and how long it took, per model; the stream carries no effort, so the base one Claude Code's settings give |
| `agent` | at each sub-agent's end seen live (a foreground call's result, a background one's task notification, never its launch): the call's `subagent_type`, description and `model`, the parent's model, background, status, start and duration, the tool calls inside it at any depth, and its `spend` read off the session's strands from its own file (model calls, each message once; tokens by kind; the models it ran on) | the unit of delegation calibration scores: who ran it, on what, for how long, at what spend |
| `usage` | at each plan-usage read that succeeded: provider, window, percent, reset | the allowance draw the pacing reads |
| replays | a stored conversation read again, and a sub-agent's tab (record 109), write nothing | an event is something that happened now |
| money | kept as it arrives, shown nowhere | Miguel's rule: reports carry no money figures; a flat plan's limits are percentages |
| who reads it | the kit (calibration, pacing, the ledger), not comando | comando captures; the routing rules live in the kit |
| retention | not pruned yet | the roll-up and its period are the next increment's |

Miguel asked for metrics that calibrate who does what at which model and effort. Claude decided the files, the events,
their fields and sources, and the scope of this first increment. Open with Miguel: on 2026-10-27 he asked for such data
in QuestDB as time series, not in files; the files here can feed it (the vision's ILP rows) or give way to it.

## 3. Consequences

`MetricsLog`, `StreamMapper.metrics`, `ClaudeAdapter.metrics`, `SubagentFiles.spend` and `AppConfig.metricsDir` are
added. A sub-agent's spend reads its file once more at its end, on the sub-agent thread.

Not built here, from the fleet vision: `verdict` events (acceptance, review findings, corrections) that score each run,
which calibration needs; `tool`, `send`, `approval`, `stall` and `reflect` events; the importer of sessions comando did
not run; retention and roll-ups; rows in the embedded QuestDB; the effort set per turn by `/effort`, which the stream does
not carry.

Tested in `MetricsTest` (a turn's and a sub-agent's events and fields, a background one ended by its notification, a
replay writing nothing, a sub-agent's spend each message once, no data root).
