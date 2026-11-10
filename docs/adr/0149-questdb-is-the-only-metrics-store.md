# 149. QuestDB is the only metrics store

| Date | Status | Decided by |
|---|---|---|
| 2026-11-10 | accepted; built | Miguel and Claude |

## 1. Context

[Record 146](0146-every-metric-goes-to-the-machine-questdb.md) put every metric in the machine's QuestDB beside the
files of [record 110](0110-metrics-for-calibration.md): `MetricsLog` appended each event to the day's
`metrics/metrics-<UTC date>.jsonl` in the data root, then handed it to `MetricsSink`; the sink imported the files on its
first connection (listing them in `metrics/qdb-imported.txt`), and `lastContext` read the last 14 days of files when
QuestDB had no answer. The files were the record until a switch after two days of dual writes. On 2026-11-10 Miguel
abandoned the metric files: QuestDB is the one store for metrics and analytics. comando alone manages the machine's
QuestDB (`MachineQuestDb`), so a server that does not answer is a failure to report, not a case to keep events on disk
for.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| where an event goes | to the sink only: its bounded queue, one thread, batches over ILP; no file is created or appended | one copy, in QuestDB (record 146's choice of no backup) |
| a failed batch | lost, counted, logged at most once a minute; the log says the rows are lost | the queue is batching, not a spool |
| telling the user | the first failed batch of an outage is a warning notice, through the state loop as `MachineQuestDb`'s notices go, its log entry linked; later failures of that outage are not; a batch the server takes ends the outage, and the next failure is a notice again | a lost row is visible once, without a notice per batch |
| the close | the sink sends what is queued for at most 2 s, then interrupts its thread; the batch it cut and the events still queued are counted (lines failed, events dropped) and logged once as lost at the close, no notice | no accepted event is lost without a trace; the state loop closes too |
| a start while none runs | as before: the sink asks the endpoint to start the server at most once a minute | the next metric starts it (record 146) |
| comando run without the machine's QuestDB | `-Dcomando.questdb=false`, as the tests run: `MetricsLog.NONE`, which keeps nothing | a test never writes the user's data, on disk or in the server |
| a loaded conversation's last context | from `comando_turn` alone; no server, no answer within 2 s or no row: none, as when QuestDB has no row | no fallback to files that are no longer written |
| the old files | `metrics/` is no longer made or narrowed at the start; files already there are left as they are | Claude removes `~/.comando/metrics/` only after the kit reads from QuestDB and a last `qdb verify` |

Miguel decided the files go and a missing server is reported. Claude decided the notice's shape (once an outage, again
after a recovery) and that a run without the machine's QuestDB keeps nothing.

## 3. Consequences

`MetricsLog` loses its folder, its files-only constructor, the write to the day's file and the file fallback of
`lastContext` (`CONTEXT_DAYS`); it is built from the data root it names in its rows, the endpoint and the state loop for
the notice. `MetricsSink` loses the import (`importFiles`, `qdb-imported.txt`) and gains the outage notice.
`AppConfig.metricsDir` goes, and `App` narrows `logs`, `run` and `audit` only. Record 110's "where" row and record
146's rows "comando's write path", "history" and, for comando's metrics, "the switch" are replaced by this record.

The kit was the other reader of these files: its `ledger pending` and `ledger add --agent` read `comando_agent` from
QuestDB since the same change (kit task 95), so no reader is left on the files once both land. `~/.comando/metrics/` is
removed only after the kit has switched and a last `qdb verify` shows QuestDB holds their rows.

Tested in `MetricsTest` (the lines a loopback QuestDB receives for a turn, a sub-agent, a background sub-agent and the
queues, none from a replay; an event lost with a notice and no file; the last context from QuestDB alone),
`MetricsSinkTest` (a notice once an outage, again after a recovery and a new failure; a close in its bound counting
the batch it cut, the part of it never tried and the events still queued), `AppMetricsLogTest` (`MetricsLog.NONE` under
`-Dcomando.questdb=false`) and `DataRootFoldersTest` (no `metrics` folder made).

## 4. Alternatives

Keeping the files as a spool for the batches a down server misses: a second copy and its clean-up, which is what Miguel
wanted gone, for a server comando itself starts. A notice per failed batch: one a batch while the server is down.
