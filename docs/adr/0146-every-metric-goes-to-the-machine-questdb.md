# 146. Every metric goes to the machine's QuestDB

| Date | Status | Decided by |
|---|---|---|
| 2026-11-09 | accepted; dual write built; amended by [record 149](0149-questdb-is-the-only-metrics-store.md) | Miguel and Claude |

## 1. Context

comando's metrics ([record 110](0110-metrics-for-calibration.md)) are a JSON line per event in a file a day in each data
root; the kit's measurements (the agent ledger, the evaluation records, the cue-replay history) are tab-separated files
in each kit's brain. Each question about them is a script over text files, each instance keeps its own, and the brain's
git holds files that only grow. comando already ran QuestDB, embedded in its own JVM and in one data root, started by
hand. On 2026-11-09 Miguel decided to move every metric into QuestDB: one place, aggregating SQL, no text files to
process or back up, and QuestDB experience that may carry into the Edge. There are three instances on his machine, each
with its own data root and kit.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| how many servers | one QuestDB per machine, in `~/.comando-questdb/`, its own process | every instance and kit writes to the same tables; the load stays out of comando's JVM |
| who starts it | whichever writer finds none up: a comando instance (its metrics sink or the toggle) or the kit (`qdb`); comando writes the launcher `bin/start.sh` and copies the QuestDB jar to `lib/` at each start, the kit runs the launcher | metrics are never lost to a server nobody started |
| who stops it | the instance or kit that started it, named in the registry `server.json`; it outlives its starter's quit | as the comando Edge ([record 122](0122-instances-name-kit-brain-data-root-edge.md)), without stopping at quit |
| after a stop | the next metric any instance or kit writes starts it again, and that writer is the new owner (Miguel, 2026-11-09) | the server is the metrics' home; a stop is a restart, not an off switch |
| which server is ours | the registry's pid is alive and `/exec` answers the registry's random token from table `qdb_server` | a QuestDB someone else runs on port 9000 is never taken for ours |
| two starters at once | one POSIX lock, `start.lock`, taken by Java and Python alike | probed: `FileChannel.lock` and `fcntl.lockf` exclude each other |
| the tables | comando: `comando_turn`, `comando_turn_model`, `comando_agent`, `comando_usage`, `comando_queue`; kit: one table per kind of file, its columns as text, plus `ts`, `kit`, `file`, `line`, `row_id`; all WAL, partitioned by month, deduplicated on keys that include `ts` and the instance or kit | an import, a replay or a dual write never doubles a row; each row names its instance |
| how rows are written | ILP over HTTP, tables made by DDL only (auto-create off) | a misspelt column is refused, not added |
| comando's write path | the file line as before, then a bounded queue and one thread that writes batches; a failed batch is dropped while the files remain the record | a slow or absent server never slows a session |
| history | each instance imports its own files on its first connection; `qdb import` imports a kit's | one mapping per side |
| the switch | two days of dual writes checked daily by `qdb verify` (one day with two instances, one Monday), then readers move, file writes stop, and the ledger and evaluation data leave the brain's git | a mismatch is found while both copies exist |
| ports | QuestDB's defaults on loopback: 8812, 9000, 9003, 9009 | the saved connections keep working |

The data has one copy, in QuestDB; Miguel chose no backup.

## 3. Consequences

`MachineQuestDb` replaces the embedded server: the QuestDB toggle and `server_start`/`server_stop` start and stop the
machine's server, and a server another instance started is shown as theirs. The data of the embedded server in
`<data root>/questdb/db` is copied into the machine's folder at the first start; the old folder stays until Miguel
removes it. `MetricsLog` gains `MetricsSink` and `MetricsLines`; `lastContext` asks QuestDB first and the files after.
The kit gains `tools/qdb/`, and `ledger`, `measure` and `cue-replay` mirror each row they append. `instance-guard` shares
`.comando-questdb` between instances.

Rule 12 of the development guide: the machine's QuestDB is the one child process comando starts and does not end with
its own close; `start.sh` is a short command under `Processes`, and the server it leaves is the registry's.

## 4. Alternatives

Keeping the embedded server, one per instance: three servers on one machine, and the kit without one when comando is
closed. Files with SQL views over them (DuckDB over the JSON lines): no new process, but the files and their backups
stay, which is what Miguel wanted gone.

## 5. Amendments

2026-11-13: a batch QuestDB refuses (400) for some of its lines, a missing table aside, is sent again in halves, down to
the lines it refuses alone; only those are dropped, and a line refused alone is logged at most once a minute, with no
notice: a refusal of its data is no outage.
