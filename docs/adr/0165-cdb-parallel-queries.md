# 165. cdb runs a query's scan-based operators over frames on a shared pool, merged in frame order

| Date | Status | Decided by |
|---|---|---|
| 2026-11-16 | accepted; built in task 166 | Miguel and Claude |

Read against comando `7619532d`; QuestDB `ad347c73bf` for comparison.

## 1. Context

A cdb query ran its cursor tree on one thread, the request's. Task 160's soak (2 M rows, the six bench queries under
the Cosa flood) measured QuestDB 1.5–6× faster than cdb on five of six; QuestDB spreads a filter, GROUP BY or SAMPLE BY
over its worker pool (12 cores in that container), compiles filters (`jit=true` in its log) and runs SIMD aggregates.
cdb's thread-confinement test allowed no executor below the servers and the applier. Miguel, 2026-11-16: spread every
operator over the cores; the same answer every run; one shared pool of cores − 1.

## 2. Decision

| Part | Rule |
|---|---|
| Pool | `exec.QueryPool`: one per process (`shared()`), platform daemon threads `cdb-query-<n>`, cores − 1 by default, `CdbServe --query-threads N`. Workers claim one frame of the oldest job with frames left, so concurrent queries share the cores. A frame not started when its consumer awaits it runs on the consumer: no query waits on a busy or empty pool. A sixth thread owner in `CdbThreadConfinementTest` |
| Frames | `exec.Frames`: per partition in the scan's time range, its rows cut at multiples of 65,536 counted from the partition's first row. Depends on the snapshot and the query only, never on the pool's size |
| Segment | `exec.FrameSource`: a `Scan` under only `Filter` and `Project` nodes whose expressions depend on the current row only. It owns one `TableReader` snapshot for all its frames and closes it after every frame of the operator is done |
| Merge | partials merged on the consumer in frame order, never completion order: the same bits for any pool size. `Accumulator.merge` per aggregate (first keeps its own, last takes the later frame's, exact and approximate percentiles append their value lists); `AggTable.merge` appends a partial's new groups in its first-seen order, so the group order is the serial one |
| Operators | GROUP BY and SAMPLE BY: a partial table per frame. LATEST ON: the latest row per key per frame. DISTINCT: per frame, deduplicated again in frame order. ORDER BY: per-frame stable sort (or the first k rows), k-way merged with ties by frame. LIMIT: frames in order, later frames not started once met. Set operations: the right side read once, the left per frame. Joins: the right side built once and shared read-only, the left probed per frame, out in left frame order. Window: input per frame; with PARTITION BY, each partition as a pool task. An operator over a non-segment input runs as before |
| Streaming | `exec.ExchangeCursor`: a segment's pages in frame order, one job of all its frames; a job's workers run at most the pool's threads + 1 frames past the one awaited, so the partials held ahead of the merge are bounded |
| Failure | a frame's failure stops the frames after it not yet started; the frames before it run, so the consumer, reading in frame order, meets the lowest failed frame's failure, as the serial run would; a LIMIT met before it answers |
| Cancel | `QueryContext(CdbCancel, QueryPool)`: checked before each frame and page, so a cancel reaches frames already on the pool |

## 3. Consequences

- A double sum or average groups its additions by frame, so it may differ from the serial cdb's: usually in the last
  digits, but cancellation or overflow can change the whole value (1, 1e17, -1e17 gives 0 serially and 1 when a
  frame ends after the 1), as any change of summation order can. Every other answer is the serial one, and every
  run gives the same bits.
- A query's CPU can reach the pool's size: concurrent heavy queries share the pool, and the applier keeps its own
  thread.
- Measured in task 166 (`docs/cdb.md` §4): on the 2 M-row bench, D 57 → 37 ms, F 14 → 10 ms, B 10 → 8.7 ms, A and C
  unchanged, E 22 → 30 ms (cause not yet measured); at about 10 ms of serial work the hand-offs cost about as
  much as the work they spread, so the gain grows with the rows a query reads.

## 4. Alternatives not taken

| Alternative | Why not |
|---|---|
| A pool per query | thread churn per query; G-7.10 (a platform thread per repeated work) |
| Frames sized by the pool | answers would change with the core count |
| Merge in completion order | different bits on each run |
| Virtual threads per frame | CPU-bound work (G-16.4) |
