# cdb: engine, storage, concurrency and performance

cdb is comando's own time-series database, in the module `cdb` (Java 25, JDK only). Why it exists and its surface are
in [record 158](adr/0158-cdb-comandos-own-database.md); its classes and tests are in [Architecture](architecture.md).
This document says how a query and a write run, what each step costs, and how the numbers were measured.

## 1. The query path

| Step | Package | What it does |
|---|---|---|
| parse | `sql` | text to a syntax tree |
| plan | `plan` | names and types resolved; the time range of the designated timestamp narrowed from WHERE (`Pruning`); the WHERE's top-level AND comparisons of a column with a constant become the scan's terms (`ScanTerms`); `LATEST ON` over a scan reads it newest first; `ORDER BY … LIMIT` tells the sort how many rows are read |
| prune | `plan.ColumnUse` | from the root down, each node asks its child for the columns it reads; a scan reads only those |
| execute | `exec` | a tree of cursors over pages of 1,024 rows, one primitive array per column (`String[]` for text); a page belongs to its caller |
| read | `store` | a snapshot of the table: per partition a `Version`, its column files mapped through FFM |

### 1.1 The scan

A scan reads, per partition, only the rows inside `[from, to)`, found by binary search on the timestamp column. With
terms, each chunk of up to 1,024 rows is selected term by term (`PartitionReader.select*`), the offsets intersected, and
only the kept rows are gathered (late materialisation); without terms, the columns are copied in bulk
(`MemorySegment.copy`). Newest first reads the partitions from the last and the rows of each from the end: exactly the
time-order scan reversed, rows with equal timestamps included.

| Term kind | Contract | So |
|---|---|---|
| LONG, TIMESTAMP, DOUBLE (`=`, `!=`, `<`, `<=`, `>`, `>=`, BETWEEN as two terms) | conservative: a NULL or NaN row is kept | the filter above stays and decides |
| SYMBOL, VARCHAR (`=`, `!=`), a non-NULL constant whose UTF-8 bytes decode back to it | exact, as the filter compares: `=` keeps the equal rows, `!=` the others and NULLs | a WHERE made only of such terms plans no filter |

Left out of the terms: NULL and NaN constants, DOUBLE zero (`-0.0` and `0.0` compare equal in the filter), OR, NOT,
functions of a column, and a constant that does not survive UTF-8 (a lone surrogate encodes as `?`).

Each column of a version has, built on first use, a zone map: min, max and a has-NULL flag per block of 4,096 rows. A
numeric select skips a block whose range cannot hold the constant; a block holding a NULL or NaN is never skipped.

### 1.2 Text

VARCHAR is stored as 8-byte end offsets (`.i`) and UTF-8 bytes (`.d`). A version keeps a text cache per column: up to
1,024 values of up to 128 bytes, keyed by their bytes compared in full, shared by every reader thread without a lock;
a row whose value is cached costs a hash and a compare instead of a decode and a String. A text term compares bytes:
the length from the offsets first, then eight bytes at a time and the tail in one masked word. SYMBOL compares 4-byte
dictionary keys.

### 1.3 Operators

| Operator | How |
|---|---|
| SAMPLE BY, fixed stride | the bucket from `floorDiv`, reused while time-ordered rows stay in it; runs of rows with one key found on the key's `long[]` and added per run (`Accumulator.addRange`) |
| GROUP BY | the same run detection in `AggTable`; per row when runs average under 4 rows |
| `percentile_cont`, percentiles | per group a growable primitive array; the rank selected in place (radix passes for 4,096 values or more, then quickselect, insertion sort under 16), ordered as `Arrays.sort` orders, so the result is bit-identical to a full sort |
| LATEST ON, newest first | the first row of each key is its answer: copied once; a later row of a seen key costs one hash lookup, or, for one text key with at most 16 values, a scan of those values |
| LATEST ON, time order | the best row per key tracked by offset, copied once per page |
| ORDER BY … LIMIT | a bounded heap that keeps the full sort's order, ties by input sequence |

## 2. Storage

A table is a folder: `_meta` (the definition), `_txn` (the checkpointed transaction: per partition its folder and row
count, the column count, the TTL, and per SYMBOL column the bytes of its symbol file), `_wal` (the commits since that
checkpoint), symbol files, and partition folders `<partition>.<txn>/` with one file per column (fixed width: 8 bytes
for LONG, TIMESTAMP and DOUBLE, 1 for BOOLEAN, 4-byte keys for SYMBOL; VARCHAR as in 1.2), little-endian
([record 161](adr/0161-cdb-write-ahead-log.md)).

| Commit case | What is written |
|---|---|
| a new partition | a new folder |
| every pending row of a partition after its committed rows (or equal to the last, without dedup keys) | the rows appended in place to the partition's current files, from their committed ends |
| otherwise | the partition rewritten as a new folder: the committed rows and the new, two sorted runs merged and deduplicated on the upsert keys |

The order of a commit: the column files and new version folders written without a force, the symbols interned in
memory; then the commit point; then the snapshot published, which makes the rows visible and answers the commit.

| Commit | Its commit point |
|---|---|
| logged: fewer than 100,000 rows, no definition change, no truncate | one record appended to `_wal` and forced: the pending rows in arrival order, framed with their length and a CRC32C, with the transaction number |
| direct: 100,000 rows or more, a log record of 16 MiB or more (text counted at 3 bytes a char), `ALTER TABLE ADD COLUMN`, `SET TTL`, a truncate | a checkpoint, as every commit was before the log |

A checkpoint runs on the committing thread once the log holds 256 records or 16 MiB, at a direct commit, and when
the store closes (a clean shutdown leaves every log empty); not when a writer closes, since `/write` group commits and
statements take a writer each. Its order: the column
files and folders written since the last checkpoint forced, the symbol files appended and forced, `_meta` when the
definition changed, then `_txn` written to `_txn.tmp`, forced, moved over `_txn` atomically, and the table folder
forced. Only when that folder force holds are the folders the new `_txn` no longer names deleted (those no reader
holds) and the log cut back to its 8-byte header. `Store.create` forces the root after the table folder. `_txn` and
the log's records together say what is committed: a reader maps only the published rows, and a file may run past them.

| Failure | After it |
|---|---|
| a crash or power cut before a commit's record is forced | the previous commit. When the store opens: the folders `_txn` does not name and every `.tmp` file are deleted, each named partition's files cut to their checkpointed sizes and each symbol file to its checkpointed bytes, the log's records replayed through the commit code with their transaction numbers, then one checkpoint |
| a crash or power cut after the record is forced | the commit, replayed when the store opens, answered or not |
| a crash during a checkpoint, before `_txn` is replaced | the old `_txn` and the log reach the same state: the log is replayed when the store opens |
| the log's last record torn (a short frame, a bad length, a bad CRC) | cut there and forced: only a commit that was never answered can be torn |
| a record whose CRC holds but that does not decode, or a gap in the transaction numbers | the store does not open (as for any table it cannot load), the error naming the log file, the offset and the transaction; nothing is cut |
| forcing the log fails | the log cut back to its length before the append, the commit throws and is not published, and the table refuses commits until the store reopens (after a failed force the page cache cannot be trusted); an interrupt that closed the force's channel is cleared for the cut and restored after; when the cut also fails the commit is in doubt, logged at ERROR |
| a checkpoint after a logged commit fails | logged at ERROR; the commits stand, they are in the log, and the table refuses commits until the store reopens and replays it (a retry after a failed force could take lost data for durable and cut the log that holds it) |
| a direct commit fails before `_txn` is replaced | the previous transaction, its version folders and files cleaned up as before the log |
| a commit that fails after appending | the bytes past the committed ends stay while readers may map them; the next commit rewrites that partition instead of appending |
| forcing the table folder fails after `_txn` was moved | the commit stands (it is visible); the log and the old folders are kept; after a direct commit the table refuses commits until the store reopens, logged at ERROR |
| the previous engine opening a store after a failed or crashed append | it refuses the longer files and opens nothing; opening once with this engine cuts them first |
| an engine from before the log opening a checkpointed store | it refuses `_txn`'s new lines and opens nothing: the format is one-way |

Snapshots: a reader holds a version per partition; several snapshots may share a folder at different row counts. The
table counts holders per folder and deletes a folder only when no snapshot holds it and the checkpointed `_txn` does
not name it: a folder the durable `_txn` still names is the data a power cut would come back to.

## 3. Concurrency

| Who | Holds | Waits for |
|---|---|---|
| a reader (`/exec`, the PG wire's SELECT, `Cdb.run`) | a snapshot, taken without blocking a writer | nothing |
| a table's writer | the table's one-writer lease | another writer of the same table |
| `/write` | nothing while parsing; then its batches go to one queue per table (`serve.CommitQueue`) | the committer of each of its tables |

Group commit: the request that finds no committer running for a table becomes it, takes every batch queued for that
table, replays them into one appender in arrival order and commits once, then completes each request. Different tables
commit in parallel. A row the store refuses fails only its own request with today's 400: the rows of its batch before
it are written, the ones from it on are not, and the other batches of the group are written. A body over several tables
is written table by table, not atomically across tables, and answers when all are done; its other tables' batches are
written whole even when one table refuses a row (before, the rows after the refused line were dropped in every table). Bounds: 64 requests in flight, a group of at most 100,000 rows, a wait of at most 30 s. A committer holds one
table's lease and no other lock while it writes, so committers, the PG wire's INSERT and `Cdb.run` cannot wait on one
another in a cycle.

## 4. Measurements

Method: a 2 M-row table (`time`, `seq`, `temperature`, `humidity`, `status` VARCHAR; 3 daily partitions), exported from
Cosa's TimescaleDB and loaded over ILP in 50,000-line bodies; six queries over HTTP `/exec`, client time of the whole
request, best of 7, on an Apple M2 Max (macOS, JDK 25); QuestDB 10.2.0 (`questdb/questdb:10.2.0`) in Docker on the same machine, from
the Cosa comparison of 2026-11-15. The answers of every query are compared with the previous engine's.

| Query | | before (ms) | after (ms) | QuestDB (ms) |
|---|---|---|---|---|
| A | `avg`, `count` over the 100 rows after a timestamp | 33.9 | 1.0 | 0.93 |
| B | `SAMPLE BY 1h`, `avg`, `max` | 107 | 11.1 | 8.5 |
| C | `SAMPLE BY 1d`, `percentile_cont(…, 0.95)` | 244 | 16.5 | 38.2 |
| D | `LATEST ON time PARTITION BY status` (VARCHAR) | 129 | 54.0 | 44.2 |
| E | `WHERE seq > … ORDER BY seq LIMIT 500` | 71.3 | 1.4 | 3.6 |
| F | `count() WHERE status = 'warning'` | 116 | 16.0 | 7.1 |

| Load and writes | before | after |
|---|---|---|
| load of the 2 M rows | 12.2 s | 5.8 s |
| 20 concurrent 1-line writes over 5 tables, median / max | 891 / 1,543 ms | 147 / 185 ms |
| `INSERT INTO … SELECT` of the 2 M rows | 0.90 s | 0.70 s |
| memory of Cosa's cdb container at Cosa's rate (`docker stats`; TimescaleDB 148 MiB, QuestDB 1.05 GiB) | 244–378 MiB | 111–112 MiB |

The memory figure is with the JVM options the kit's `cdb-serve` and Cosa's cdb image use: G1 with two GC threads and
a collection every 10 s when idle, compact object headers, a heap of 16 to 256 MB kept 10–20 % free (before: `-Xmx1g`).
cdb's data is in mapped files outside the heap and a query's garbage is short-lived, so the heap stays small; 256 MB
holds the 2 M-row `INSERT … SELECT`. Query times are the same under both.

Before the log, a commit forced each file it wrote: a one-row append forced about 8 (five column files, a VARCHAR
index, `_txn.tmp`, then symbol files and the table folder); on this machine a force costs 3.95 ms median, a folder
force 0.16 ms, and forces queue behind one another, so a commit cost about 35 ms whatever its size, the floor of the
write latencies above. With the log a small commit forces one file, `_wal`:

| Commit latency, ms, over HTTP `/write` (client time) | before the log | with the log |
|---|---|---|
| one 1-line commit, single writer, 1,000 in a row: p50 / p99 / max | 26.2 / 37.5 / 64.4 | 7.0 / 10.7 / 72.5 |
| 20 concurrent 1-line writes over 5 tables, 20 rounds: median / max | 151.1 / 197.7 | 25.8 / 50.4 |
| 1 / 5 / 10 writers at once, median | 22.1 / 76.5 / 120.4 | 5.0 / 13.7 / 24.8 |
| 2 M rows over `/write` (50,000-line bodies, direct commits) | 5.5 s | 4.5 s |
| reopen after a kill that left 232 commits in the log: to the first answer | 141 | 352 |

Measured 2026-11-15 on the same machine, one engine at a time, with the JVM options above, while reviews ran beside
them; the queries A–F returned the same answers on both and took the same time (within 15 %). The max with the log is
a commit that ran a checkpoint (one in 256); the reopen replays the log before it answers.

## 5. Where the time still goes

- D: every row's text is looked up in the text cache and hashed for its key; QuestDB reads the same VARCHAR column.
- F: the scan's term selects about a million rows of 2 M; the count reads no column, so what is left is the selection.
- Writes: one log force per table per commit, so a body over 5 tables costs 5 forces (a log shared by the store's
  tables would cost one), and one commit in 256 pays a checkpoint on its own thread (cdb starts no thread).
- Disk: a partition rewritten since the last checkpoint keeps its previous folder until the checkpoint names the new.
