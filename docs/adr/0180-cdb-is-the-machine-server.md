# 180. The machine's database server is cdb; QuestDB is no longer started

| Date | Status | Decided by |
|---|---|---|
| 2026-11-17 | accepted; built in task 187 | Miguel and Claude |

Read against comando `20109841`. Amends [record 146](0146-every-metric-goes-to-the-machine-questdb.md): the registry,
the token, the start lock, the owner rule and the ports stay; the process they describe is cdb's server, not
QuestDB's.

## 1. Context

Since 2026-11-14, every write to the machine's QuestDB was also written to cdb (the kit's `cdb-serve` job on
127.0.0.1:9100), and a nightly job compared the two on the SQL the machine runs (`kit_cdb_parity`). By 2026-11-17:
559 query shapes compared a night, 496 identical, 63 checked departures, 0 wrong answers on the nights of 11-16 and
11-17; the cutover list (store data safety, `/write` batch atomicity, `/exec` timestamps and offsets, routing) closed
in task 155; the kit's own queries at 1.13× QuestDB's time after task 185; Cosa soaks with equal rows, no leak, a
third of QuestDB's CPU. Miguel, 2026-11-17: replace QuestDB now, with two of the four parity nights he had asked for.

## 2. Decision

| Part | Rule |
|---|---|
| Server | `start.sh` runs `dev.marregui.comando.cdb.CdbServe` on the machine folder's `cdb/`, HTTP on the registry's `http` port (9000) and PG wire on its `pg` port (8812), bound to `127.0.0.1`, with the kit's `cdb-serve` JVM options (G1, two GC threads, periodic GC, 256 MiB heap) |
| Jar | the cdb jar on comando's runtime classpath, found by class name (`app` does not compile against `cdb`), is copied to `lib/cdb-<12 hex of its SHA-256>.jar`: a new build gets a new copy; when comando's own jar is gone, a start uses the newest copy in `lib/` |
| Registry | `server.json` is version 2 with the same keys: a comando built before this record reads a later version's server as used, never managed, so it never starts QuestDB on the old `root/` while cdb is down; the kit's `qdb` reads version 1 or later. `questdb` now names the jar copy; `httpMin` and `ilp` stay for its readers, though cdb does not listen on them |
| Log | cdb's only log is `stdout.log` in the machine folder; Help ▸ Show cdb's log opens it |
| Texts | menus, command titles, notices, the welcome panel (`SELECT now();`, as cdb has no `version()`) and a fresh data root's connection (`local cdb`) name cdb |
| Data | the kit's cdb data (`~/.comando/cdb`, in step with QuestDB) moved to the machine folder's `cdb/`; QuestDB's `root/` is left as it was, read by nothing |
| Dependency | `app` no longer depends on `org.questdb:questdb`; `db`'s tests still do |
| Kit | the `cdb-serve` and `cdb-parity` jobs are retired; the shadow switch file is removed; `dream` recognises the server by `CdbServe` and its `cdb/` folder |

## 3. Consequences

- Every client that names the ports (the kit's `qdb`, `fastline`, `MetricsSink`, connections to 8812) reaches cdb
  without a change: the registry and the token check are the same.
- There is no fallback that runs: going back means a build before this record and QuestDB's `root/`, which misses the
  rows written after the switch.
- cdb answers no `DROP TABLE`, `/ping` or QuestDB's internal tables (`sys.*`, `build()`); SQL outside the shapes the
  machine runs is not covered by the parity record.
- Names still say QuestDB (`MachineQuestDb`, `QuestDbEndpoint`, `~/.comando-questdb`, `comando.questdb`): a rename is
  a separate mechanical change.
