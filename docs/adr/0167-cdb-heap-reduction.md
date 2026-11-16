# 167. cdb holds less on the heap: text as bytes, symbols as keys, a late read for ORDER BY LIMIT, mappings made lazily and released, commit blocks off-heap, one channel per log

| Date | Status | Decided by |
|---|---|---|
| 2026-11-16 | accepted; built in task 167 | Miguel and Claude |

Read against comando `96b0bba3`.

## 1. Context

Task 166's soak (`--log-force-ms 100`, 18 min, the Cosa three-way rig): live heap about 6 MB and no leak; allocation
7.7 MB/s, `long[]` 32 % and `double[]` 11 % (page vectors), the applier's row blocks the top allocator (1.97 MB/s); each
timed force opened the log file. Bench query E (`select * from bench where seq > 1000000 order by seq limit 500`)
spent 42 % of its CPU decoding a VARCHAR column for rows the sort dropped. Every column file of every version was
mapped at open, and Linux bounds a process's mappings by `vm.max_map_count` (65,530 by default). Miguel, 2026-11-16:
symbols as keys, text as bytes, native page vectors, native ingest, one channel per log, the late read, and mappings
made lazily, released when idle and warned of at 81 % of the limit; not page reuse across `next()`.

## 2. Decision

| Part | Rule |
|---|---|
| Accessors | `exec.Vector` is read and written only through its accessors (`getLong`, `setLong`, …, the text accessors); its backing is private, so it changes in one class |
| Text | a vector's SYMBOL and VARCHAR values are UTF-8 bytes in one array per vector (`exec.TextColumn`), copied from the mapped file with no String; compared in `String.compareTo` order and hashed as `String.hashCode`, computed from the bytes; decoded at output and in the functions that build or read chars |
| Symbols | a SYMBOL value read from a table carries its key; two values of one dictionary compare by key (GROUP BY, count distinct, DISTINCT, joins, set operations) |
| Late read | ORDER BY … LIMIT n, n at most 10,000, over a scan with or without a filter: the scan reads the filter's and the sort's columns plus each row's position; after the sort keeps n rows, their other columns are read (`PartitionReader.fetch`); rows and tie order are the eager plan's |
| Mappings | a version maps a column's files on its first read; the applier's pass releases every 10 s the mappings of versions no read used for 60 s, and the next read maps them again; a store counts its mappings and logs one WARNING past 81 % of `/proc/sys/vm/max_map_count`, re-armed under 76 %; no limit file (macOS), no warning |
| Commit blocks | a queued commit's rows are a row block in native memory, one shared arena per block with its byte count (per 100 ms epoch since [record 170](0170-cdb-row-block-epochs.md)), freed by its owner: the applier once published, the replay once done, the table's close for the blocks never applied |
| Log channel | each table log keeps one `FileChannel` for its forces, closed with the store |
| Not built: native page vectors | page vectors stay on the heap. Native memory freed by the GC per query is a defect under the Java standard (G-4.7), and a per-query arena closed at query end needs `CdbResult`, which holds the query's pages and is read from any thread, to be closed by every caller (app and servers), which it is not. With the accessors this is a change of `Vector` and `CdbResult` alone |

## 3. Consequences

- Same answers: `AnswerDigestTest` holds the SHA-256 of 44 queries' answers recorded on `96b0bba3` (every type, NULLs,
  non-ASCII text including U+E000–U+FFFF and supplementary chars, joins, set operations, windows, ORDER BY with ties)
  and checks them at pools of 1, 2 and 11 workers.
- A window function whose result is text runs its partitions on the consumer, not the pool: a text vector's values
  share one byte array, so tasks may not write rows of one vector at once.
- A read after a version went idle maps the columns it reads again; a file removed under an open table now fails at
  the first read of its column rather than at open.
- `like`, `length` and the functions that build text decode each VARCHAR row they read (a SYMBOL resolves to its
  dictionary's String); before, the version's text cache served repeated values without a decode.
- Each read of a column by a query thread updates the version's read count and last-read time, shared by its readers.
- Measured on the 2 M-row bench (`docs/cdb.md` §4): every answer the same; E (ORDER BY … LIMIT 500 with a filter)
  5.5–5.8 ms against 7.6–24.7 before; A, B, F within 0.3 ms; C 12.2–13.6 against 11.3–12.1; D 11.1 against
  9.1–10.7. The heap's allocation rate under the Cosa load was not measured again.
