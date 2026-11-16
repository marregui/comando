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
| execute | `exec` | a tree of cursors over pages of 1,024 rows, a page belonging to its caller; a column (`Vector`) is read and written only through its accessors: a primitive array for fixed width, text as UTF-8 bytes (`TextColumn`), SYMBOL as its dictionary key ([record 167](adr/0167-cdb-heap-reduction.md)) |
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

VARCHAR is stored as 8-byte end offsets (`.i`) and UTF-8 bytes (`.d`). For the reads that return Strings
(`PartitionReader.texts`, no longer on the query path), a version keeps a text cache per column: up to
1,024 values of up to 128 bytes, keyed by their bytes compared in full, shared by every reader thread without a lock;
a row whose value is cached costs a hash and a compare instead of a decode and a String. A text term compares bytes:
the length from the offsets first, then eight bytes at a time and the tail in one masked word. SYMBOL compares 4-byte
dictionary keys.

In a page, a VARCHAR value is its UTF-8 bytes, copied from the mapped file into one byte array per vector without a
String, and a SYMBOL value read from a table is its dictionary key; text is decoded only where a String is the answer:
the result (`CdbResult`, JSON, PG) and the functions that build text (`concat`, `substring`, `upper`, `lower`,
`replace`, casts) or read its chars (`like`, `length`). Comparison is `String.compareTo`'s order and hashing
`String.hashCode`'s, computed from the bytes (UTF-8 byte order differs from UTF-16 order between U+E000–U+FFFF and
supplementary chars, which the compare handles), so answers and the output order of hash-based operators are the
same as with Strings. Two SYMBOL values of one dictionary compare by key. An unpaired surrogate (half a pair, from
`substring`) is held as the 3 bytes of its code unit, so it reads back as itself; the column files still store it as
`?`, as before.

### 1.3 Operators

| Operator | How |
|---|---|
| SAMPLE BY, fixed stride | the bucket from `floorDiv`, reused while time-ordered rows stay in it; runs of rows with one key found on the key's `long[]` and added per run (`Accumulator.addRange`) |
| GROUP BY | the same run detection in `AggTable`; per row when runs average under 4 rows |
| `percentile_cont`, percentiles | per group a growable primitive array; the rank selected in place (radix passes for 4,096 values or more, then quickselect, insertion sort under 16), ordered as `Arrays.sort` orders, so the result is bit-identical to a full sort |
| LATEST ON, newest first | the first row of each key is its answer: copied once; a later row of a seen key costs one hash lookup, or, for one text key with at most 16 values, a scan of those values |
| LATEST ON, time order | the best row per key tracked by offset, copied once per page |
| ORDER BY … LIMIT | a bounded heap that keeps the full sort's order, ties by input sequence; over a scan with n at most 10,000 (`LateReadCursor`), the scan reads only the filter's and the sort's columns plus each row's position, and the other columns are read for the n rows kept |

**Parallel operators** ([record 165](adr/0165-cdb-parallel-queries.md)). A segment, a scan under only filters and
projections whose expressions read the current row, is cut into frames: per partition in the scan's range, its rows at
multiples of 65,536 counted from the partition's first row, so the frames depend on the snapshot and the query, never
on the pool. Each operator over a segment runs one job of its frames on the shared query pool (`exec.QueryPool`) and
merges the partials on the consumer in frame order: GROUP BY and SAMPLE BY a partial table per frame (`AggTable.merge`
appends a partial's new groups in its first-seen order), LATEST ON the latest row per key per frame, DISTINCT
deduplicated again in frame order, ORDER BY a stable sort per frame k-way merged with ties by frame, LIMIT frames in
order with the rest not started once met, set operations the right side read once, joins the right side built once and
the left probed per frame (a frame past 64 pages joined again on the consumer page by page), window functions per
frame or per partition. A streaming input reads its frames' pages through `exec.ExchangeCursor`. The workers run at
most the pool's threads + 1 frames past the one awaited. A frame's failure stops the frames after it; the frames before
it run, so the failure met is the lowest failed frame's, as in a serial run. The answer is the serial operator's, bit
for bit, at every pool size, except a double sum or average, whose additions are grouped by frame: the same bits on
every run, and usually the serial value but for the last digits (1, 1e17, -1e17 sums to 0 serially, 1 when a frame
ends after the 1). A cancel is checked before each frame and at each page.

### 1.4 Listings

Table functions answer from the catalog and the store's status, never from a table's data. `tables()`,
`table_columns(t)`, `SHOW TABLES`, `SHOW COLUMNS FROM t` and `SHOW CREATE TABLE t` carry QuestDB's columns, with
the two below:

| Listing | Shows |
|---|---|
| `wal_tables()` | QuestDB's eight columns, one row per table: `writerTxn` is the last transaction published to readers, `sequencerTxn` the last one answered, `bufferedTxnSize` the commits logged and not yet applied; no reader of the table is opened |
| `wal_apply_stats()` | cdb's own, one row per table: every figure of its log and applier (pending commits, rows and bytes, lag and interval in ms, batches and their durations, checkpoints, log size, memory pressure, last error) |

## 2. Storage

A table is a folder: `_meta` (the definition), `_txn` (the checkpointed transaction: per partition its folder, row
count and how many leading columns have files in it, the column count, the TTL, and per SYMBOL column the bytes of its symbol file), `_wal` (the commits since that
checkpoint), symbol files, and partition folders `<partition>.<txn>/` with one file per column (fixed width: 8 bytes
for LONG, TIMESTAMP and DOUBLE, 1 for BOOLEAN, 4-byte keys for SYMBOL; VARCHAR as in 1.2), little-endian
([record 161](adr/0161-cdb-write-ahead-log.md)).

| Commit case | What is written |
|---|---|
| a new partition | a new folder |
| every pending row of a partition after its committed rows (or equal to the last, without dedup keys) | the rows appended in place to the partition's current files, from their committed ends |
| otherwise | the partition rewritten as a new folder: the committed rows and the new, two sorted runs merged and deduplicated on the upsert keys |

| Commit | Its commit point | Then |
|---|---|---|
| logged: fewer than 100,000 rows, no definition change, no truncate | one record appended to `_wal` and forced (or, under the timed force below, written and forced within the interval): the pending rows in arrival order, framed with their length and a CRC32C, with the transaction number | the commit is answered and queued in memory; the store's applier writes it into the column files later, with the commits queued beside it |
| direct: 100,000 rows or more, a log record of 16 MiB or more (text counted at 3 bytes a char), `ALTER TABLE ADD COLUMN`, `SET TTL`, a truncate | the queued commits applied first, then the commit written and named by a checkpoint, as every commit was before the log | answered and visible |

**The applier.** One platform thread per store, `cdb-applier-<root>` (its batches write files, which would pin a
virtual thread). Per table it takes the queued commits in log order, at most 100,000 rows, and writes them with the
commit's file code as one commit: one sort, per partition one dedup, one append in place or one rewrite, one mapping,
one publish, named by the batch's last transaction. That gives the same rows as one commit at a time: dedup keeps the
last row written and the sort is stable, so the last write of an upsert key wins as before; the TTL's newest timestamp
only grows, so one expiry at the end drops the same partitions; a truncate or a definition change is never inside a
batch. A commit puts its table on the applier's queue, once until it is served; a deadline list, kept by the applier
thread alone, holds each table's next due time (its interval, a retry back-off, an idle checkpoint), and the thread
parks until the earliest of them or the next commit, so an idle store costs no work. A table is due once its oldest
queued commit is older than its interval, at once when a reader waits for it or
the store's queued commits pass half their budget (64 MiB of native memory, estimated). The interval starts at 1 s and stays in
100 ms to 5 s: halved after a batch a reader waited for, doubled after a batch that took more than half of it, else
drawn a quarter of the way back to 1 s. Over the budget a commit waits for the applier to catch up, at most 10 s, so a
writer faster than the applier slows to it; past that wait it is answered anyway (it is durable), so the budget holds
while the applier makes progress. A batch writes no force: the column files are made durable by the
checkpoint, the commits by their log records.

| Case | What is written |
|---|---|
| a new partition | a new folder |
| every row of a batch's partition after its committed rows (or equal to the last, without dedup keys) | the rows appended in place to the partition's current files, from their committed ends |
| otherwise | the partition rewritten as a new folder: the committed rows and the new, two sorted runs merged and deduplicated on the upsert keys |

A checkpoint runs on the applier after a batch once 256 commits were applied since the last one, once the log holds
16 MiB, or 30 s after the last one with anything applied; at a direct commit; and when the store closes (the queue
applied first: a clean shutdown leaves every log empty); not when a writer closes, since `/write` group commits and
statements take a writer each. It cuts the log back to its header only when no record follows the transaction it
names; a commit logged meanwhile keeps the log until the next checkpoint (a scan skips the records at or before
`_txn`), and a log past 64 MiB is drained and cut under the writer lease. Its order: the column
files and folders written since the last checkpoint forced, the symbol files appended and forced, `_meta` when the
definition changed, then `_txn` written to `_txn.tmp`, forced, moved over `_txn` atomically, and the table folder
forced. Only when that folder force holds are the folders the new `_txn` no longer names deleted (those no reader
holds) and the log cut back to its header, a new salt written in it and forced. `Store.create` forces the root after the table folder. `_txn` and
the log's records together say what is committed: a reader maps only the published rows, and a file may run past them.

| Failure | After it |
|---|---|
| a crash or power cut before a commit's record is forced | the previous commit. When the store opens: the folders `_txn` does not name and every `.tmp` file are deleted, each named partition's files cut to their checkpointed sizes and each symbol file to its checkpointed bytes, the log's records replayed in batches as the applier writes them (a truncate or a definition change a commit of its own), then one checkpoint |
| a crash or power cut after the record is forced | the commit, replayed when the store opens, answered or not |
| a crash during a checkpoint, before `_txn` is replaced | the old `_txn` and the log reach the same state: the log is replayed when the store opens |
| the log's last record torn (a short frame, a bad length, a bad CRC) | cut there and forced: only a commit that was never answered can be torn |
| a record whose CRC holds but that does not decode, or a gap in the transaction numbers | the store does not open (as for any table it cannot load), the error naming the log file, the offset and the transaction; nothing is cut |
| forcing the log fails | the log cut back to its length before the append, the commit throws and is not published, and the table refuses commits until the store reopens (after a failed force the page cache cannot be trusted); an interrupt that closed the force's channel is cleared for the cut and restored after; when the cut also fails the commit is in doubt, logged at ERROR |
| the applier's checkpoint fails | logged at ERROR; the commits stand, they are in the log, and the table refuses commits until the store reopens and replays it (a retry after a failed force could take lost data for durable and cut the log that holds it); the applier still publishes the commits queued before it |
| a batch fails (a write, a mapping) | nothing published, its written folders deleted and its appended ones rewritten next time, the commits kept queued and in the log; retried with the interval doubled; after 3 failures in a row the table is suspended, logged at ERROR: commits refused, a read that needs a queued commit fails, `wal_tables()` shows it; the store's next open replays the log |
| a direct commit fails before `_txn` is replaced | the previous transaction, its version folders and files cleaned up as before the log |
| a commit that fails after appending | the bytes past the committed ends stay while readers may map them; the next commit rewrites that partition instead of appending |
| forcing the table folder fails after `_txn` was moved | the commit stands (it is visible); the log and the old folders are kept; after a direct commit the table refuses commits until the store reopens, logged at ERROR |
| a partition folder or a column file `_txn` and `_meta` name is missing | the read fails, naming the table, the partition and the file, the last column's file included (`_txn` records how many leading columns each partition has files for); a column added after a partition was written has no file there and reads NULL. A partition line written before that count (two fields) keeps the tail rule: a missing last column reads NULL |
| a symbol write fails | the dictionary is cut back to its committed length before the next write, and a dictionary shorter than its checkpoint fails the open: a committed symbol never reads NULL |
| a `/write` body with a malformed or refused line | none of the body's rows is written (`CdbAppender.discard`); the other bodies of its group commit are written |
| the store closes while a writer is still open after the close's 10 s wait | the close fails naming the tables and keeps the root's `_lock`, so no other store opens the folder under that writer; a later close releases it once the writer is closed |
| an `Error` while a commit writes its files | the files it wrote unmapped and deleted, its writers released, and the table refusing commits until the store reopens |
| the previous engine opening a store after a failed or crashed append | it refuses the longer files and opens nothing; opening once with this engine cuts them first |
| an engine from before the log opening a checkpointed store | it refuses `_txn`'s new lines and opens nothing: the format is one-way |

**The log's format** ([record 166](adr/0166-cdb-timed-log-force.md)). Format 2: a 16-byte header (magic `CWAL`, u32
version 2, a u64 salt drawn anew at the log's creation and at every cut), then per record its length and a CRC32C of
the salt's 8 bytes then the body, so a frame of an earlier generation that a power cut left past the header fails its
CRC and is cut. A format 1 log (an 8-byte header, unsalted CRCs) still reads and becomes format 2 at its first cut;
the format is one-way: a cdb before record 166 cannot open a format 2 log.

**The timed force** (record 166). A store opened with `StoreOptions(logForceMillis > 0)` (`CdbServe --log-force-ms N`)
answers a logged commit once its record is written, without its force; its thread `cdb-log-force-<root>` forces each
table's log at most N ms after the first record written since the last force, so a power cut loses at most the last N
ms of answered commits, one force per table per interval instead of one per commit. A request that must survive a
power cut asks for it: `/write?durable=true`, or `SET cdb.durable = true` on a PG connection; its answer waits for a
force covering its commit (the forcer asked to force that log at once), outside the committer, so plain writes queue
on. A failed timed force poisons the table, as a failed force per commit does: no retry, its commits refused until the
store reopens, the commits since the last force that held logged at ERROR as in doubt, and every durable waiter on
them failed with "commit in doubt". A cut whose new header's force fails counts as a failed force. The store's close
forces every dirty log once more; a record written after the forcer closed is forced on its writer's thread. Default
0: a force per commit, as before.

| Timed force: known limits | Effect |
|---|---|
| a retract (a commit refused after it was logged) gives its txn back without a new salt | a disk block still holding the retracted frame could pass its CRC at the next open after a power cut |
| a scan's cut of a torn tail at open keeps the salt | the same exposure for the frame cut |
| the forcer's figures (`forcedTxn`, the most a force ran late) | kept per log, not yet in `wal_tables()` |

Snapshots: a reader holds a version per partition; several snapshots may share a folder at different row counts. The
table counts holders per folder and deletes a folder only when no snapshot holds it and the checkpointed `_txn` does
not name it: a folder the durable `_txn` still names is the data a power cut would come back to.

**Mappings** ([record 167](adr/0167-cdb-heap-reduction.md)). A version maps a column's files on the first read of
the column, not at open. The applier's pass releases, every 10 s, the mappings of the versions no read used for 60 s;
the next read maps them again. A store counts its mappings (`store.Mappings`) against the process's limit, read once
from `/proc/sys/vm/max_map_count` (65,530 by default on Linux; macOS has none and is not counted against one), and
logs one WARNING when the count passes 81 % of it, again only after it fell under 76 %.

**Commit memory.** A commit's rows wait for the applier in a row block in native memory (`store.RowBlock`), its bytes
taken from the current epoch of `store.BlockArenas`: one shared arena serves the blocks opened in 100 ms (or 16 MiB) and
closes when its last block left, so 10 arena closes a second, each a handshake of every thread, plus one per 16 MiB of
commits ([record 170](adr/0170-cdb-row-block-epochs.md)): the block that queued them is freed once the applier published
them, a replay's blocks once the replay ends, the blocks never applied when their table closes. Each table log keeps one
file channel for its forces, closed with the store.

## 3. Concurrency

| Who | Holds | Waits for |
|---|---|---|
| a reader (`/exec`, the PG wire's SELECT, `Cdb.run`) | a snapshot, taken without blocking a writer | the applier, only when a commit answered before the read and older than its lag (§3.1) is not yet published: at most 10 s, then the read fails |
| the applier | per table, the apply lock, which a direct commit, the checkpoint and the close also take | a direct commit of the table in progress; a logged commit's force only at a checkpoint, whose cut and drain of an overgrown log take the log's monitor (its size and record count are read without it) |
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

### 3.1 Read freshness

By default a read sees every commit answered before it began. A caller may relax that to a lag of at most N
milliseconds, so a read stops waiting for the applier that writes answered commits into the column files:

| Where | How |
|---|---|
| one HTTP query | `/exec?query=...&max_lag_ms=N` |
| a PG connection | `SET cdb.max_lag_ms = N` (`TO` and a quoted value too); `RESET cdb.max_lag_ms` or `SET cdb.max_lag_ms = DEFAULT` goes back to the default; every later query of the connection runs with it |
| the launcher | `cdb-serve ... --read-max-lag-ms N` sets the default of every read that names none |
| in process | `Cdb.run(sql, maxRows, maxLagMillis)`; `Cdb.readMaxLagMillis(n)` for the default |

N is a whole number of milliseconds, 0 or more; anything else is refused (HTTP 400, a PG error, a launcher error). A lag
means: a read waits for the applier only when a commit older than N ms is not yet published; a commit younger than N ms
may be missing from the read, and one older never is. N = 0 is the default.

### 3.2 Threads and locks

Threads:

| Thread | Kind | Owner | May block on |
|---|---|---|---|
| `cdb-applier-<root>` | platform, daemon, one per store (its batches force files, which would pin a virtual thread); also releases idle versions' mappings every 10 s | `store.Applier`, started by `Store.open`, stopped by `Store.close` (joined up to 10 s) | a table's apply lock (a direct commit of it in progress), the log's monitor when it drains a log past 4 checkpoints' worth, file writes and forces; parks until its earliest deadline or a signal, at most 1 s |
| `cdb-query-<n>` | platform, daemon, cores − 1 by default (`CdbServe --query-threads N`), one pool per process shared by every store (CPU-bound frames) | `exec.QueryPool`, started at the first submit; the shared pool's threads end with the process | a frame's reads (mapped files), a nested job's frames, which it runs itself rather than wait; parks idle at most 1 s |
| `cdb-log-force-<root>` | platform, daemon, one per store opened with the timed force (a force blocks in the file system) | `store.LogForcer`, started by `Store.open`, stopped by `Store.close` after a last force of every dirty log (joined up to 10 s) | a log's force; parks until the earliest deadline or a signal, at most 1 s |
| `cdb-http-<n>` | virtual, one per request | `serve.CdbServer` | a table's lease and its commit queue, the read wait for the applier (at most 10 s), the socket |
| a PG connection's thread | virtual, one per connection | `serve.PgServer` | as an HTTP request, and the client's socket |
| `cdb-serve-shutdown` | platform, the JVM's shutdown hook | `CdbServe` | the launcher's close, at most its close wait |

The applier serves the tables a commit queued (each table at most once in the queue), then the tables whose deadline
came: the schedule of its queued commits, a failed batch's retry, its idle checkpoint, a paused applier's next look. A
reader that must wait for it, and a commit over half the queued budget, wake it to serve every table once. An idle
store's thread makes no pass over its tables.

Locks, in the order they are taken (a thread holding one takes only those below it):

| Lock | Guards | Taken by |
|---|---|---|
| a table's lease (`TableState.lease`) | one writer per table | a writer, a `/write` committer, the store's checkpoint and close |
| a table's apply lock (`TableState.applyLock`) | the column files, dictionaries and checkpoints | the applier, a direct commit, the store's checkpoint and close |
| a log's monitor (`Wal`) | the log file: append and its force, write, cut, scan | a logged commit, the applier's checkpoint (its drain of an overgrown log holds it while it applies); not the timed force, which reads the last record's number without it and forces outside it |
| a log's force monitor (`Wal.forced`) | the forced sequence number, the first failed force, the durable waiters | taken inside the log's monitor or alone, never the other way round |
| a table's state lock (`TableState.lock`) | the queued commits, the published snapshot, the waiting readers | committers, readers, the applier |

The log's size and record count are read without its monitor (volatile fields written under it), so the applier's
checkpoint test never waits for a commit's force. `Store`'s create lock serialises the creation of tables and is held
alone. A commit queue's lock (`serve.CommitQueue`) guards one table's queued batches and is never held while the batch
is written.

The rule `CdbThreadConfinementTest` enforces: only `CdbServe`, `serve.CdbServer`, `serve.PgServer`,
`store.Applier`, `exec.QueryPool` and `store.LogForcer` (nested classes and lambdas included) create a thread or an executor (`new Thread`,
`Thread.ofPlatform`, `Thread.ofVirtual`, `Thread.startVirtualThread`, `Executors.new*`), read from the module's
bytecode.

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

Parallel queries (record 165), 2026-11-16: the same 2 M rows loaded over ILP into a fresh store per jar, the jar before
the record (comando `7619532d`) against the jar with it, a pool of 11 workers on the M2 Max's 12 cores, best of 7, client
time, two interleaved runs each. Every answer is the same but B's averages, which differ in the last digits (30 of 56
rows, at the 14th significant digit). E reads the text column of every row the filter rejects: 42 % of its CPU is the
text decode (`TextCache.text`, jfrq `hot`), which a late read of the columns not in the filter would save.

| Query | before (ms) | after (ms) |
|---|---|---|
| A | 1.0 / 1.0 | 1.1 / 1.1 |
| B | 13.1 / 11.7 | 3.2 / 3.8 |
| C | 17.4 / 16.5 | 12.3 / 11.7 |
| D | 58.6 / 57.7 | 12.6 / 9.0 |
| E | 23.0 / 22.1 | 16.7 / 20.4 |
| F | 14.4 / 14.0 | 3.3 / 3.2 |
| load of the 2 M rows | 5.5 s | 5.4 s |

Heap reduction (record 167), 2026-11-16: the same bench, the jar of `96b0bba3` against the jar with record 167,
interleaved runs on a fresh store per jar, best of 7, client time on the M2 Max. Every answer is the same. E reads
the text of its 500 rows only; D's newest-first scan reverses pages in place. Allocation was not measured again.

| Query | before (ms) | after (ms) |
|---|---|---|
| A | 1.2 / 1.0 | 1.2 / 1.0 |
| B | 3.4 / 3.3 | 3.5 / 3.4 |
| C | 11.3 / 12.1 | 12.2 / 13.6 |
| D | 9.1 / 10.7 | 11.1 |
| E | 7.6 / 24.7 | 5.8 / 5.5 |
| F | 3.1 / 3.0 | 3.4 / 3.2 |
| load of the 2 M rows | 5.6 s | 5.8 s |

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

With the applier ([record 162](adr/0162-cdb-applies-commits-from-its-log.md)) a logged commit writes its log record
alone. Both engines measured back to back on 2026-11-15, same machine and JVM options, one at a time:

| Over HTTP, client time | log, commit writes files (record 161) | log, applier (record 162) |
|---|---|---|
| one 1-line commit, single writer, 1,000 in a row: p50 / p99 / max, ms | 5.0 / 7.2 / 56.4 | 4.1 / 7.4 / 41.5 |
| 1 / 5 / 10 / 20 writers at once, median, ms | 5.0 / 13.5 / 20.8 / 24.7 | 5.0 / 11.6 / 18.6 / 22.5 |
| 20 writers at once, max, ms | 45.6 | 39.3 |
| 2 M rows over `/write` | 4.6 s | 4.4 s |
| reopen after a kill following the 1,000 commits: to the first answer, ms | 345 | 244 |
| queries A–F, ms | 1.03 / 26.7 / 32.4 / 61.1 / 1.83 / 17.4 | 1.12 / 15.3 / 25.7 / 61.6 / 1.82 / 16.3 |

The queries answered the same on both. The benchmark reads at lag 0 right after its writes, so most of its batches hold
one commit; on Cosa's live load (2026-11-15, the default lag) a table written by several producers and not read at once
applied 138 commits in 46 batches, and tables read after each write applied one commit per batch.
On Cosa the engine with the applier computed the same windows as TimescaleDB and QuestDB: 30 of 30 ten-second windows
of `analytics_line1_temperature_avg_10s` and `analytics_lines_temperature_spread_10s`, max difference 1.07e-14; its
container used 78 MiB (98 MiB with the previous engine just before).

## 5. Where the time still goes

- D: every row's text is looked up in the text cache and hashed for its key; QuestDB reads the same VARCHAR column.
- F: the scan's term selects about a million rows of 2 M; the count reads no column, so what is left is the selection.
- Writes: one log force per table per commit, so a body over 5 tables costs 5 forces (a log shared by the store's
  tables would cost one). The checkpoints run on the applier, off the commits' path.
- Reads at the default lag 0 right after a commit wait for its batch: a client that writes and reads in a loop gets
  batches of one commit; a lag of a few hundred milliseconds lets the applier batch.
- Disk: a partition rewritten since the last checkpoint keeps its previous folder until the checkpoint names the new.
