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
| its surface | one public facade, `Cdb` (`open`, `run`, `explain`, `appender`, `tables`, `close`), and `CdbServe`, the launcher; only `db.Cdb*` classes reach it, and only the facade, checked by `CdbArchitectureTest` | one way in keeps the inside free to change |
| layers | `sql` (text to syntax tree) → `plan` (names and types resolved; `Signatures` holds every type rule) → `exec` (a tree of cursors over pages of 1,024 rows; `exec/expr` compiles scalars, `exec/agg` holds accumulators) → `store`; beside them `catalog` (`tables()`, `table_columns()`, `SHOW`, `long_sequence`) and `serve` | no package depends upward |
| SQL | what the 604 shapes use and nothing more: SELECT with WHERE, LATEST ON, SAMPLE BY, implicit or explicit GROUP BY, ORDER BY, the LIMIT forms; UNION, EXCEPT, INTERSECT; WITH; joins; one window shape; CREATE TABLE as QuestDB writes it, UPDATE, SHOW; the 26 functions the corpus calls | a query that needs more fails with a clear error and is added when a real query asks |
| types | BOOLEAN, LONG, DOUBLE, TIMESTAMP (µs, UTC), SYMBOL, VARCHAR; INT, SHORT and BYTE are LONG, STRING is VARCHAR, DATE is TIMESTAMP; NULL is `Long.MIN_VALUE`, NaN or a null reference | six types cover every column of the 28 tables |
| storage | per table `_meta` (format 2: a SYMBOL column keeps its CAPACITY, NOCACHE and INDEX CAPACITY options; format 1 still reads, with the defaults), `_txn` (replaced through `_txn.tmp` and an atomic move), symbol files, and partition folders `<partition>.<txn>/` with one file per column, read through FFM mappings; a commit rewrites each partition it touches, ordered and deduplicated on the upsert keys, then replaces `_txn` | the writes are small; a rewrite per commit is the simple correct protocol, and appending in place can come later behind it |
| concurrency | one writer per table, any number of snapshot readers counted per version folder; a version folder goes once no reader holds it; one store per root, held by an exclusive file lock | a reader never sees a folder removed under it; two processes never publish over each other |
| the shadow | `CdbServe` serves `/exec` (QuestDB's JSON) and `/write` (ILP) on 127.0.0.1:9100 from `~/.comando/cdb`; the switch is the first line of `shadow` in the machine's QuestDB folder (`~/.comando-questdb`, or `$KIT_QDB_HOME`), read by both comando's metrics sink and the kit's `fastline`; `COMANDO_QDB_SHADOW` overrides it for comando and `$KIT_QDB_SHADOW` for the kit, empty meaning none; a shadow write that fails is ignored, each of its requests bounded at 0.5 s (a silent shadow costs a batch up to seven, its tables and the write); comando reads the switch only where the app builds its metrics log, so no test inherits it from the shell or the machine folder | the kit's clients work unchanged, and QuestDB stays the record until parity is shown |
| parity | the kit's `cdb-parity` captures QuestDB's answers to the logged queries, replays them on cdb and compares typed values, order, types and names; `cdb-parity seed` copies QuestDB's tables into cdb; the bar is a strict compare (`--strict`: order, type-name and column-name differences fail too) that passes with only named departures, each checked against the answers it absorbs | the evidence the cutover decision rests on |

## 3. Consequences

- The first parity run (2026-11-14, after seeding all 28 tables): 951 queries compared, 694 identical. The differences
  that remain are listed by class in the dream backlog; cutover waits until they are closed.
- The strict compare on the captured corpus (2026-11-14): 559 queries compared, 495 identical, 64 checked departures,
  0 failing.
- PG wire is not built: comando reaches cdb in-process through `CdbAdapter`.
- Known gaps, each named where it is: `/write` keeps the rows before one the store refuses; a missing column file reads
  as NULLs rather than failing; the table folder is not fsynced after a rename.
