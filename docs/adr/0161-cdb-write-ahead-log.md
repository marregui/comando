# 161. cdb commits through a write-ahead log

| Date | Status | Decided by |
|---|---|---|
| 2026-11-15 | accepted; built | Miguel and Claude |

## 1. Context

[Record 158](0158-cdb-comandos-own-database.md) made every cdb commit durable by forcing each file it wrote, then
replacing `_txn`. Measured 2026-11-15 on the development machine (Apple M2 Max, macOS, JDK 25): one `FileChannel.force`
costs 3.95 ms median and a folder force 0.16 ms; a one-row append forces about 8 files (five column files, a VARCHAR
index, `_txn.tmp`, then symbol files and the table folder); forces queue behind one another, so a commit cost about
35 ms whatever its size (seven serial forces took 62 ms in a microbenchmark), and 20 concurrent one-line writes over 5
tables answered in 147 ms median, 185 ms max. The same reading found two power-cut gaps: a new partition folder was
never forced, so its entry in the table folder could vanish while `_txn` named it, and the store root was never forced,
so a created table could vanish.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the commit point | a commit writes its column files and version folders unforced and interns its symbols in memory; a small commit then appends one record to the table's `_wal` and forces it once, then publishes | one force instead of about 8 |
| the log | per table `_wal`: an 8-byte header (magic `CWAL`, version 1), then records framed `u32 length, u32 crc32c`; a record holds the transaction number, a flags byte (truncate; a definition follows, as `_meta` text, which no commit writes while definition changes are direct), the rows count, the column count and the pending rows in arrival order, column-major (SYMBOL and VARCHAR as length-prefixed UTF-8, -1 for NULL) | replay runs the rows through the normal commit code, so merge, deduplication, TTL and interning have one implementation |
| direct commits | 100,000 rows or more, a log record of 16 MiB or more, a definition change (`ALTER TABLE ADD COLUMN`, `SET TTL`) or a truncate: a checkpoint names the commit directly, as every commit was before | no double write and no second 100 MB buffer on a 256 MB heap for `INSERT … SELECT`, `UPDATE` and bulk loads |
| the checkpoint | at 256 records or 16 MiB of log, at a direct commit and when the store closes (not when a writer closes: a `/write` group commit or a statement takes a writer each), on the committing thread: the column files and created folders forced, the symbol files appended and forced, `_meta` when the definition changed, then `_txn` (`_txn.tmp`, force, atomic move, table folder force); only when the folder force holds, the folders the new `_txn` no longer names and no reader holds are deleted and the log is cut back to its header | cdb starts no thread; a clean shutdown leaves every log empty |
| `_txn` | names the checkpointed transaction and gains `@columns`, `@ttl` and `@sym=<column>:<bytes>` lines | recovery trims `_meta` and cuts each symbol file to what the checkpoint named |
| folder deletion | a folder goes when no reader holds it and the checkpointed `_txn` does not name it | deleting a folder the durable `_txn` names would lose data at a power cut |
| recovery | when the store opens: `_meta` trimmed to `@columns` and `@ttl`; folders `_txn` does not name, `.tmp` files and the symbol files of columns past `@columns` deleted; named partitions' files cut to their checkpointed sizes, symbol files to their `@sym` bytes; the log scanned and each record past `_txn`'s number replayed with its transaction number; then one checkpoint. A short frame, a bad length or a bad CRC is a torn tail, cut and forced; a record whose CRC holds but that does not decode, or a gap in the numbers, stops the store from opening and cuts nothing; records at or before `_txn`'s number, left by a checkpoint stopped before its reset, are cut after the table folder is forced | an answered commit is in the log or in `_txn`; a torn record was never answered |
| a failed log force | the log cut back to its length before the append, the commit throws unpublished, and the table refuses commits until the store reopens; a failed checkpoint after a logged commit refuses them too, the commits kept in the log; the thread's interrupt status is saved and cleared around the log I/O and restored after | after a failed fsync the page cache cannot be trusted; an interrupted `/write` thread must not close the channel mid-force and poison a table |
| the two gaps | the checkpoint forces each folder it created; `Store.create` forces the root after the table folder | a power cut no longer loses a named folder or a created table |
| the format | one-way: once a store is checkpointed, an engine from before the log refuses `_txn`'s new lines and opens nothing | refused loudly rather than opened without the commits only the log holds, as record 158 accepted for downgrades |

## 3. Consequences

- One 1-line commit over `/write`: 7.0 ms median, 10.7 p99 over 1,000 single-writer commits (before: 26.2 / 37.5). 20
  concurrent one-line writes over 5 tables: 25.8 ms median, 50.4 max (before: 151.1 / 197.7). Measured in
  [cdb](../cdb.md) §4.
- One commit in 256 pays a checkpoint on the request path, the max of 72.5 ms in that run: taking it off would need a
  thread of cdb's own.
- Still one force per table: a body over 5 tables costs 5 forces; a log shared by a store's tables is the next lever.
- Disk: a partition rewritten since the last checkpoint keeps its previous folder until the checkpoint names the new.
- Opening a table replays its log: 352 ms to the first answer after a kill that left 232 commits in it (141 ms before
  the log, with nothing to replay).
- A record that fails on replay stops the store from opening, naming table and transaction (a record that does not decode: its file and offset), as a load failure
  did before.
- Tests: a power-cut model of the file system (durable and volatile contents per file, names per folder, strict POSIX)
  cut at every operation of commits, checkpoints and recovery; a process killed at each step of a commit and a
  checkpoint (`StoreCrashTest`); a logged and a direct store compared row by row, live and after replay; a failed log
  force.
