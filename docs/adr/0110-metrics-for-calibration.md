# 110. Metrics of turns, sub-agents and plan usage, kept for calibration

| Date | Status | Decided by |
|---|---|---|
| 2026-10-28 | accepted on 2026-10-29; amended 2026-11-01 and 2026-11-09 (see Amendments); amended by [record 149](0149-questdb-is-the-only-metrics-store.md) | Miguel and Claude |

## 1. Context

Miguel: nothing captured the metrics that would improve the work as it goes; the plans' per-day, per-week and Fable
allowances went unused while some tasks are better done by certain models at a certain effort, which he wants
calibrated automatically. The only record of who did what, at which model, and whether it passed was the agent
ledger, written by hand. comando sees every turn's `result`, every sub-agent's start and end, and reads the plans'
usage every few minutes, and kept none of it. The fleet vision, a design kept outside the repository, sets out the
schema, the calibration loop that reads it and the pacing of the allowances; this record builds its first increment,
the capture.

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
not run; retention and roll-ups; rows in the embedded QuestDB; the effort set per turn by `/effort`, which the stream
does not carry.

Tested in `MetricsTest` (a turn's and a sub-agent's events and fields, a background one ended by its notification, a
replay writing nothing, a sub-agent's spend each message once, no data root).

## Amendments

2026-11-01, Miguel's decision D6 of the first-release review: a fourth event, `queues`, measures comando's unbounded
queues before any is bounded: the reducer's intent queue, every strand (a session's among them) and each `claude`
process's stdin, each with its `name`, its `depth` when read and its `peak` since the reading before (`core`'s
`Queues`). `App` reads them every minute on its `kit-check` strand and writes the event only when the reading changed.
One gauge is kept per queue: when a queue is made again under a name in use, the newest gauge of that name is read. A
bound for each queue is set later from how it is used. Tested in `QueuesTest` and `MetricsTest`.

2026-11-09, [record 146](0146-every-metric-goes-to-the-machine-questdb.md): every event is also written to the machine's QuestDB, one table per event (`comando_turn`,
`comando_turn_model`, `comando_agent`, `comando_usage`, `comando_queue`), each row naming its data root; the files stay
as they are and stay the record until the switch that record names, after which they stop.

2026-11-10, [record 149](0149-questdb-is-the-only-metrics-store.md): the files are dropped with no switch. Every event goes to
the machine's QuestDB alone; no file is written or read, the last context of a loaded conversation comes from
`comando_turn` alone, and a write QuestDB does not take is lost and told in a notice, once an outage.

2026-11-11: 78% of `comando_queue`'s rows measured on one machine were idle (depth 0, peak at most 1), and seven timed
strands (`kit-check`, `db-timer`, `peer-broker`, …) read depth 1 or more at every reading, as each holds its next run.
`MetricsLog.queues` enters a queue only when its reading (depth, peak) differs from the one last written for it (a
queue not written before has none): its last row holds until the next, so a return to idle is written, and the peak's
fall to 0 after it; an event left with no queue is not sent. The state is one reading a queue name, on the
`kit-check` strand, the only caller. Tested in `MetricsTest`.

2026-11-12: a closed approval card is a `wait` event, a `comando_wait` row: the session, the card's `kind` and `tool`,
its `outcome` (`allowed`, `denied`, `answered`, `no_answer`, `session_ended`, `cancelled`, `shutdown`, `failed`), its
id as `card`, when it was shown as `opened`, and `wait_ms`, measured on a monotonic clock from its showing to its close.
It answers how long work sits waiting on the user, by kind of card. `McpTools.ask` writes it through `core`'s `WaitLog`
port, which `MetricsLog` implements; a card never shown (its session already gone) is not written. `denied` also holds
the cards comando withdraws at a turn's end, which reach the wait as a denial. A card still open at quit is not
written: the metrics log closes before the MCP server denies what still waits, which is not time spent on the user.
Tested in `McpServerTest`, `MetricsLinesTest` and `MetricsTest`.
