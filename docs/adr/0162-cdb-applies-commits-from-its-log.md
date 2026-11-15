# 162. cdb applies commits from its log, on a thread of its own

| Date | Status | Decided by |
|---|---|---|
| 2026-11-15 | accepted; built | Miguel and Claude |

## 1. Context

[Record 161](0161-cdb-write-ahead-log.md) made a small commit cost one force, its log record, but the commit still wrote
its rows into the column files and mapped them before it answered (about 40 file system calls for a 1-line commit to a
5-column table), an out-of-order commit rewrote its partition each time, and one commit in 256 paid a checkpoint on the
request path (72.5 ms, the max of its run). The log is a queue of instructions over the files: many commits to one
partition can be written as one. Miguel asked for a periodic task that applies the pending commits, adapts itself
between freshness and cost, and is observable; for the multi-instance end game (cdb as a server, writers on disjoint
tables, readers of every table) he chose bounded staleness: a read states how far behind the answered commits it may
be.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the commit | a logged commit appends its record, forces it, queues its rows in memory and answers; it writes no column file | the commit costs its log force and nothing else |
| the applier | one platform thread per store, `cdb-applier-<root>`; per table it writes the queued commits in log order, at most 100,000 rows, as one commit of the existing file code, and publishes them as the batch's last transaction | one write per column file and at most one merge per partition per batch; one implementation of merge, dedup, TTL and interning; a platform thread since its file I/O would pin a virtual one |
| same rows | a batch is the concatenation of its commits in log order; dedup keeps the last row written with a stable sort; the TTL's newest timestamp only grows; a truncate and a definition change are direct commits, which apply the queue first | a batch gives the rows the commits one at a time give |
| the schedule | per table: due once its oldest queued commit is older than its interval, at once when a reader waits or the queued commits pass half the store's budget; the interval starts at 1 s, stays in 100 ms to 5 s, halves after a batch a reader waited for, doubles after a batch that took over half of it, else returns a quarter of the way to 1 s | fresh where tables are read, larger batches where they are not or where applying is slow |
| reads | bounded staleness: a read waits for the applier only when a commit answered before it and older than its lag is not published, at most 10 s; the lag is 0 by default (every answered commit visible: record 161's read-your-writes); `max_lag_ms` per HTTP query, `SET cdb.max_lag_ms` per PG connection, `--read-max-lag-ms` per server, `Cdb.run(sql, maxRows, maxLagMillis)` in process, carried as a `ScopedValue` | a reader never writes files, so any number of readers keep the batches; a reader that needs its own writes keeps them |
| memory | the queued commits' heap, estimated, has a budget per store of 64 MiB; past it a commit waits for the applier to bring it under half, at most 10 s, then is answered anyway (it is durable): the bound holds while the applier makes progress, and a stalled applier shows as `memoryPressure` 2 | a writer faster than the applier slows to it instead of filling the heap |
| checkpoints | on the applier after a batch: 256 commits since the last one, 16 MiB of log, or 30 s after the last one with anything applied; the log is cut only when no record follows the checkpointed transaction; a log past 64 MiB is drained and cut under the writer lease | no checkpoint on a commit's path; the log never loses an unapplied commit |
| failures | a failed batch publishes nothing, keeps its commits queued and in the log, and is retried with the interval doubled; after 3 in a row the table is suspended until the store reopens (commits refused, reads that need a queued commit fail); a failed checkpoint poisons as in record 161 | an I/O failure after the answer cannot lose the commit, which is in the log |
| observability | `wal_tables()` with QuestDB's columns (`writerTxn` the applied transaction, `sequencerTxn` the logged one, `bufferedTxnSize` the queued commits, `suspended`, the error, `memoryPressure`); `wal_apply_stats()` with every figure of the applier per table | QuestDB's clients and dashboards read the same listing |
| recovery | the log's records replayed in batches as the applier writes them, then one checkpoint | the reopen after a kill replays faster |
| close | the applier stopped, then per table the queue applied and checkpointed | a clean shutdown still leaves every log empty |

## 3. Consequences

- cdb starts a thread: `ThreadRulesTest` exempts `Applier`, as it does cdb's launcher; record 161's "cdb starts no
  thread" no longer holds.
- A read at lag 0 right after a commit waits for that commit's batch; a client that writes and reads in a loop gets
  batches of one. A lag of a few hundred milliseconds lets the applier batch.
- The store format is unchanged: a task-161 engine opens a store this engine wrote (it replays a longer log).
- A crash or a reopen after one names the folders of a replayed batch by its last transaction, not each commit's.
- Measurements in [cdb](../cdb.md) §4.
