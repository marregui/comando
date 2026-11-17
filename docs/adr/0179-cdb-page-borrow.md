# 179. cdb's aggregating operators borrow the scan's page instead of taking a new one per call

| Date | Status | Decided by |
|---|---|---|
| 2026-11-17 | accepted; built in task 179 | Miguel and Claude |

Read against comando `c8de3fe6`. Supersedes the clause "not page reuse across `next()`" of
[record 167](0167-cdb-heap-reduction.md); the rest of record 167 stands.

## 1. Context

Every `Cursor.next()` returned a new page that belonged to the caller, so a result could keep pages without a copy.
A scan of 100,000,000 rows therefore allocated about 98,000 pages. jfrq-live windows on the deployed `c8de3fe6` (task
177, the benchmark table of 1e8 rows, three symbols) recorded 0.6 to 15.8 GB/s of allocation per query and about 4 GB
for one keyed aggregate (`select sym, count(), avg(price) from t`), 60 % of it in `TextColumn.ensure` growing a new
page's SYMBOL arrays from 16 values to 1,024. The CPU samples of that query: `Arrays.fill` in `AggTable.add` 26 %,
`Version.read` 22 % (one `MemorySegment.get` per value), `setSymbol` 31 %. QuestDB 10.1.0 on the same rows allocated
under 7 MB/s. Miguel, 2026-11-17: opt-in page reuse, through `Cursor.borrow()`.

## 2. Decision

| Part | Rule |
|---|---|
| Contract | `Cursor.borrow()` returns the next page lent: valid until the next `borrow`, `next` or `close` on that cursor, which may rewrite it; the caller keeps neither the page nor its vectors. The default lends what `next()` gives, so every cursor supports it. `next()` is unchanged: its page belongs to the caller |
| Lenders | `ScanCursor` refills one page of its own; `FilterCursor` borrows from its child and refills its packer's last page (`RowsPacker.giveBack`); `ProjectCursor` borrows from its child and refills one copy vector per computed expression; the parallel sort's per-frame wrapper passes it down |
| Borrowers | only `GroupByCursor`, `SampleByCursor`, `DistinctCursor`, `SortCursor` (its read and its top-k), `LatestOnCursor` (its full read) call `borrow`, plus the lenders above passing it down; each reads a lent page whole, or copies what it keeps, before its next call. `CdbArchitectureTest.onlyTheListedOperatorsBorrowPages` fails a call from any other class |
| Not borrowing | `ExchangeCursor`, `FramePartials.drain`, joins, `LIMIT`, window functions, set operations, `LatestOnCursor`'s newest-first read and the result drain keep `next()`: each keeps or hands on pages |
| Poison | under `-ea`, `Page.relend` overwrites the page it lent before (a LONG of `Long.MIN_VALUE + 1`, a NaN of payload `0x4_DEAD_BEEF_0001`, a SYMBOL key past any dictionary) and lends a new one, so a page kept past its loan reads values no store holds |
| Bulk reads | `Version.read` copies a LONG, TIMESTAMP or DOUBLE column's values with one `MemorySegment.copy` each, and a SYMBOL column's keys with one copy into a keyed vector of the same or no dictionary (`Vector.readSymbols`), negative keys then made NULL |
| Groups | `AggTable.add` no longer fills each run's rows with `Arrays.fill`; a single SYMBOL key whose dictionary keys have groups finds each row's group with one array read (`AggKeys.groupsOf`) |

## 3. Measured

Same machine, table and harness as §1 (median of 11 warm runs over pgjdbc, no recording; allocation from jfrq-live
windows of 12 executions each), `c8de3fe6` against this change; the allocation before is task 177's windows on
`5f6603a5`, whose query code `c8de3fe6` keeps:

| Query (1e8 rows) | before ms | after ms | allocation before | allocation after |
|---|---|---|---|---|
| `select sym, count(), avg(price) from t` | 205.1 | 73.3 | 48.2 GB | 0.94 GB |
| `select distinct sym from t order by sym` | 156.6 | 94.2 | 49.0 GB | 0.63 GB |
| `select ts, sym, avg(price) from t sample by 1h limit 30` | 388.9 | 322.1 | 49.0 GB | 1.21 GB |
| `select timestamp_floor('d', ts) day, sym, count(), max(price) … group by day, sym order by day, sym` | 423.4 | 339.4 | 47.8 GB | 1.13 GB |

The answers of seven battery queries hash the same on both builds, three runs each.

## 4. Consequences

- A lent page is shared state between two operators. The rule in `CdbArchitectureTest` and the poison under `-ea`
  (every test run) are the guards; an operator added to the list must read or copy a page before its next call.
- The refill itself runs only with assertions off: `:cdb:testPageReuse` (part of `check`) runs the borrowing
  operators' tests again with `-da` for `Page`.
- The scan's scratch page is cleared before each use, so its text bytes no longer grow across calls.
- A page that is lent or a scan's scratch is filled, so its text vectors are sized for all 1,024 rows at once
  (`Page.allocateFull`, task 181); other pages still start their per-value arrays at 16 values and grow.
- Results, row order and every sum's rounding are unchanged: the same values reach the same accumulators in the same
  order.
