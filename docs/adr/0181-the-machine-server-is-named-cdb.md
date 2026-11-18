# 181. The machine's server and its metrics are named for cdb, not QuestDB

| Date | Status | Decided by |
|---|---|---|
| 2026-11-18 | accepted; built in task 194 | Miguel and Claude |

Amends [record 180](0180-cdb-is-the-machine-server.md), whose §3 left the names for a separate change.

## 1. Context

Since record 180 the machine's server is cdb, yet its parts kept QuestDB's names: the class, the endpoint, the folder,
the property, the token table, the registry key, and the kit's `qdb` tool. Miguel, 2026-11-18: "those names qdb are
confusing, rename appropriately". The other comando instances were removed the same day, so no older reader of the old
names remains on the machine and no alias is kept.

## 2. Decision

| Part | Before | After |
|---|---|---|
| the server's class and test | `MachineQuestDb`, `MachineQuestDbTest` | `MachineCdb`, `MachineCdbTest` |
| the metrics sink's view of it | `QuestDbEndpoint` | `MetricsEndpoint` |
| the machine folder | `~/.comando-questdb` | `~/.comando-cdb` (the kit's `KIT_METRICS_HOME` moves it) |
| the switch that leaves metrics out | `-Dcomando.questdb=false` | `-Dcomando.metrics=false` |
| the shadow settings | `COMANDO_QDB_SHADOW`, `KIT_QDB_SHADOW` | `COMANDO_METRICS_SHADOW`, `KIT_METRICS_SHADOW` |
| the token table | `qdb_server` | `cdb_server` (same columns); `qdb_server` stays in the store, read by nothing, as cdb has no `DROP TABLE` |
| `server.json` | version 2, key `questdb` naming the jar | version 3, key `jar`; a comando of version 2 uses this server and does not manage it |
| errands and threads | `questdb`, `questdb-check`, `-toggle`, `-start`, `-stop` | `machine-cdb`, `machine-cdb-check`, `-toggle`, `-start`, `-stop` |
| the Help command | `help.questDbLog` | `help.cdbLog` |
| the kit's tool | `qdb` (`tools/qdb`) | `metrics` (`tools/metrics`) |

QuestDB stays named where it is a fact about QuestDB: a connection to a QuestDB, the formats cdb copies (`/exec`, ILP,
the PostgreSQL wire defaults `qdb`/`admin`/`quest`), the decision records, the benchmarks, and the `db` module's tests,
which still run an embedded QuestDB.

## 3. Consequences

- The live folder moved once, while its server was stopped; its data, launcher and registry are unchanged otherwise.
- A comando or kit built before this record looks for `~/.comando-questdb` and finds no server there: it must not be
  run against this machine's data again.
- A server started by such a build (on 2026-11-18, through a link left at the old path for the comando still running)
  writes a version 2 registry and keeps its token in `qdb_server`. This comando asks `cdb_server`, finds no live
  server and neither stops nor manages it. That server is ended by its pid, and this comando then starts its own. No
  fallback to `qdb_server` is kept, because the state exists only while the switch-over runs.
