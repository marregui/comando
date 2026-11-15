# 158. cdb, comando's own database

| Date | Status | Decided by |
|---|---|---|
| 2026-11-14 | accepted; built, in shadow beside QuestDB | Miguel and Claude |

## 1. Context

comando and the kit keep their metrics and records in a QuestDB on the machine (port 9000): 28 tables, about 329,000
rows, written over ILP (`/write`) and read over `/exec`. In the five days to 2026-11-14 they ran 604 distinct query
shapes on it, 234,973 executions, read from QuestDB's own query log. Miguel, 2026-11-14: "a bespoke cdb with the
minimum set of SQL, optimised with syntax for our use cases, compatible with postgres and questdb where possible, coded
in high performance java, with a simple architecture and the flexibility to develop it further"; it has no public
interface other than comando; it runs in tandem with QuestDB until data and query results agree, after which QuestDB
becomes one more vendor comando connects to.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| where | module `cdb` in this repository, package `dev.marregui.comando.cdb`, JDK only | no public interface other than comando |
| its surface | one public facade, `Cdb` (`open`, `run`, `explain`, `describe`, `appender`, `tables`, `close`), and `CdbServe`, the launcher; only `db.Cdb*` classes reach it, and only the facade, checked by `CdbArchitectureTest` | one way in keeps the inside free to change |
| layers | `sql` (text to syntax tree) → `plan` (names and types resolved; `Signatures` holds every type rule) → `exec` (a tree of cursors over pages of 1,024 rows; `exec/expr` compiles scalars, `exec/agg` holds accumulators) → `store`; beside them `catalog` (`tables()`, `table_columns()`, `SHOW`, `long_sequence`) and `serve` | no package depends upward |
| SQL | what the 604 shapes use and nothing more: SELECT with WHERE, LATEST ON, SAMPLE BY, implicit or explicit GROUP BY, ORDER BY, the LIMIT forms; UNION, EXCEPT, INTERSECT; WITH; joins; one window shape; CREATE TABLE as QuestDB writes it, UPDATE, SHOW; the 26 functions the corpus calls; and for Cosa (2026-11-15): `INSERT … VALUES` and `INSERT … SELECT`, `ALTER TABLE ADD COLUMN` and `SET TTL`, `coalesce`, `percentile_cont(x, p)` | a query that needs more fails with a clear error and is added when a real query asks |
| types | BOOLEAN, LONG, DOUBLE, TIMESTAMP (µs, UTC), SYMBOL, VARCHAR; INT, SHORT and BYTE are LONG, STRING is VARCHAR, DATE is TIMESTAMP; NULL is `Long.MIN_VALUE`, NaN or a null reference | six types cover every column of the 28 tables |
| storage | per table `_meta` (format 2: a SYMBOL column keeps its CAPACITY, NOCACHE and INDEX CAPACITY options; format 1 still reads, with the defaults), `_txn` (replaced through `_txn.tmp` and an atomic move), symbol files, and partition folders `<partition>.<txn>/` with one file per column, read through FFM mappings; a commit appends in place to a partition whose new rows all come after its committed ones, else rewrites that partition (its committed rows and the new merged, deduplicated on the upsert keys), then replaces `_txn`, whose row counts alone say what is committed (2026-11-15; before, every commit rewrote) | most writes add rows after the last: an append costs the new rows, a rewrite the whole partition; [cdb](../cdb.md) §2 |
| concurrency | one writer per table, any number of snapshot readers counted per folder (snapshots may share a folder at different row counts); a folder goes once no reader holds it and `_txn` does not name it; `/write` bodies parsed outside any lock and group-committed per table, tables in parallel (2026-11-15; before, one lock over every `/write`); one store per root, held by an exclusive file lock | a reader never sees a folder removed under it; concurrent writers of one table share one commit; two processes never publish over each other; [cdb](../cdb.md) §3 |
| the shadow | `CdbServe` serves `/exec` (QuestDB's JSON) and `/write` (ILP) on 127.0.0.1:9100 from `~/.comando/cdb`; it runs from the jar `./gradlew :cdb:jar` builds, kept up by a user agent of the machine's scheduler (launchd: started at login, restarted 10 s after it exits), so a reboot or a crash does not leave the dual writes failing unseen; the switch is the first line of `shadow` in the machine's QuestDB folder (`~/.comando-questdb`, or `$KIT_QDB_HOME`), read by both comando's metrics sink and the kit's `fastline`; `COMANDO_QDB_SHADOW` overrides it for comando and `$KIT_QDB_SHADOW` for the kit, empty meaning none; a shadow write that fails is ignored, each of its requests bounded at 0.5 s (a silent shadow costs a batch up to seven, its tables and the write); comando reads the switch only where the app builds its metrics log, so no test inherits it from the shell or the machine folder | the kit's clients work unchanged, and QuestDB stays the record until parity is shown |
| parity | the kit's `cdb-parity` captures QuestDB's answers to the logged queries, replays them on cdb and compares typed values, order, types and names; `cdb-parity seed` copies QuestDB's tables into cdb; the bar is a strict compare (`--strict`: order, type-name and column-name differences fail too) that passes with only named departures, each checked against the answers it absorbs | the evidence the cutover decision rests on |

## 3. Consequences

- The first parity run (2026-11-14, after seeding all 28 tables): 951 queries compared, 694 identical. The differences
  that remain are listed by class in the dream backlog; cutover waits until they are closed.
- The strict compare on the captured corpus (2026-11-14): 559 queries compared, 495 identical, 64 checked departures,
  0 failing.
- PostgreSQL wire (2026-11-15, for Cosa): `CdbServe ROOT PORT --pg-port N [--bind ADDR]` adds protocol 3.0 (simple and
  extended query, text and binary values, QuestDB's type OIDs, trust authentication, no transactions: the status is
  tracked for the driver only); off unless asked, so the shadow serves HTTP only. comando still reaches cdb in-process
  through `CdbAdapter`.
- Speed, memory and write concurrency (2026-11-15, Miguel: near QuestDB's speed and TimescaleDB's memory, "just better
  code, better data structures", writes concurrent as QuestDB's): scans read only the rows in range and the columns
  the query reads, terms select before columns are read, the operators work on runs and in place, commits append and
  group; measured in [cdb](../cdb.md) §4.
- Known gaps, each named where it is: `/write` keeps the rows of a batch before one the store refuses, and a body's
  other tables whole; the previous engine refuses a store whose last append failed until this one has opened it; a
  missing column file reads as NULLs rather than failing; each commit forces every file it writes, about 35 ms on the
  development machine, the floor of a write's latency.