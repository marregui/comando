# 163. cdb's threads: one owner per kind of work, virtual for sockets, platform for file work

| Date | Status | Decided by |
|---|---|---|
| 2026-11-15 | accepted; built in task 155 (lock-free log counters, the applier's work queue, thread confinement) | Miguel and Claude |

Read against comando `d923633c` with task 155's seams; JDK internals from the JDK 25 sources.

## 1. Context

[Record 161](0161-cdb-write-ahead-log.md) made a commit one log force. [Record 162](0162-cdb-applies-commits-from-its-log.md)
moved the column-file writes to one platform applier per store. cdb now has five kinds of thread work: HTTP requests,
PG sessions, group commit, log append and force, and apply plus checkpoint. A sixth is coming: a long-poll ("wait past
transaction n") for messaging between instances over a table. Users: comando (embedded), the kit's metrics
(`fastline`, `store.py`, HTTP `/write` and `/exec`), and Martin's Cosa (PG wire, 12 to 1,600 msg/s). The end game is
option E: cdb as a server, writers on disjoint tables, reads with bounded lag (`max_lag_ms`).

Measured 2026-11-15, 1,600 msg/s flood, jfrq-live, 9 min 46 s:

| Figure | Value | Cause, read in the code |
|---|---|---|
| applier blocked on `Wal.bytes()` | up to 53 ms | `Wal.bytes()` and `Wal.append` are `synchronized`; `append` holds the monitor through `StoreFiles.append(file, frame, true)`, which opens the channel, writes, forces and closes it. `Applier.dueForCheckpoint` and `checkpointIfDue` call `wal.bytes()` |
| applier passes | ~225/s | `Applier.committed` calls `wake()` on every commit; `Applier.run` then walks `store.states()`, every table, and `serve(state)` takes `TableState.lock` several times per table (`oldestQueuedNanos` twice, `demanded`) |
| `ConcurrentHashMap$Node` from the JDK virtual-thread pollers | 3.2 GB in 10 min (~5.3 MB/s) | `sun.nio.ch.Poller.register` runs `map.put(fdVal, Thread.currentThread())` on a `ConcurrentHashMap<Integer, Thread>` for every blocking socket read or write that parks a virtual thread, and `deregister` removes it. One node, plus a boxed `Integer` for any fd above 127, per park |
| one PG session | 1.5 GB of `HashMap` and `Object[]` | unchecked: no stack trace read. `PgSession` keeps `statements` and `portals` as `HashMap`s, but a `put` over an existing key allocates no node. The allocation is more likely in the query path (`Cdb.run`). The jfrq allocation stacks for that session would settle it |
| GC share, JVM CPU | 1.4 %, 2.4 % | the allocation is short-lived. Today it costs little CPU and no pauses that matter |

Task 155 also confines thread creation to `CdbServe`, `CdbServer`, `PgServer` and `Applier` by a test, and adds a
"Threads and locks" section to `docs/cdb.md`.

Unknowns (confidence that the stated reading holds, 0–100):

| Unknown | Reading | Conf. | What settles it |
|---|---|---|---|
| U1: Cosa's statement shape on the PG wire | one row per `INSERT`, or several | 30 | Cosa's code or one PG capture. It decides (c) for PG, see Decision |
| U2: the share of the poller nodes from HTTP vs PG | not split | 40 | the jfrq allocation stacks by thread name (`cdb-http-*` vs `cdb-pg-session-*`) |
| U3: source of the PG session's 1.5 GB | the query path, not `PgSession`'s maps | 50 | the jfrq allocation top frames for `cdb-pg-session-<id>` |
| U4: the 53 ms tail | the macOS `F_FULLFSYNC` tail of one force, not a queue of forces | 70 | a histogram of `Wal.append` duration beside the applier's blocked time |

## 2. Decision

Invariants this model keeps (each read in the code):

- A table has one writer at a time (`TableState.lease`).
- Lock order: lease, then `applyLock`, then the `Wal` monitor, then `TableState.lock`. Checks: `TableState.applyLock`'s
  comment says "taken before lock"; `Store.checkpoint` takes the lease, then `applyLock`; `Applier.checkpointIfDue`
  holds `applyLock`, then calls `wal.holdingAppends`; `DefaultWriter.logged` calls `Wal.append`, then
  `TableState.enqueue`.
- A reader never writes files (record 162).
- A commit is answered only after its force.

The table gives each work kind its options, with today's first.

| Work | Options (cost) | Choice | What would change it |
|---|---|---|---|
| (a) HTTP request | **A1 today:** JDK `HttpServer`, its one dispatcher thread, one virtual thread per exchange through `CdbServer.Admission.execute`, at most 64 (`tryAcquire`, else `RejectedExecutionException`; `ServerImpl.Dispatcher.handle` catches `Throwable` and closes the connection). Cost: a virtual thread start per request (about 1 µs, reasoned), one poller node per parked read. **A2:** a fixed platform pool as the executor. Cost: no poller churn, ~1 MB of stack reserved per thread, the same 64 bound. **A3:** cdb's own NIO server (selector and state machine). Cost: a parser and keep-alive written and tested by us. Record 158 keeps cdb on the JDK alone | **A1** | the allocation from `cdb-http-*` pollers passing 5 % GC or showing in p99 at Cosa's top rate (U2); or (g) needing more concurrent waits than a request permit can bound. A2 is the first fallback, a one-line executor swap. A3 only if A2 fails too |
| (b) PG session | **B1 today:** a virtual thread per connection (`PgServer.admit`, `newVirtualThreadPerTaskExecutor`), at most 64 (`MAX_CONNECTIONS = MAX_REQUESTS`). The accept loop also runs on a virtual thread (`PgServer.start`, `threads.execute(server::accept)`). `setSoTimeout` turns every read into a timed park. Cost: one poller node and one timer per blocking read. **B2:** a platform thread per session. Cost: about 64 MB of stack reserve at the bound, no poller map and no carrier blocked on a force. **B3:** NIO with a session state machine. Cost: `PgSession` rewritten from blocking `DataInputStream` reads | **B1** | an A/B at 1,600 msg/s, B1 vs B2: allocation rate and session p99. Switch to B2 if allocation drops more than half and p99 does not rise. A count of sessions above about 500 would favour B1 |
| (c) group commit | **C1 today:** for HTTP `/write` only, a leader and followers per table (`CommitQueue.Table.await` / `commitGroup`): the first waiter with no committer running commits every queued batch, up to 100,000 rows, under a `ReentrantLock` and `Condition`, with no thread of its own. **C2:** one committer thread per table or per store. Cost: a thread and a handoff per group, the same one force per group. **C3:** group commit for PG INSERTs too. Today every PG `INSERT` is its own commit: `InsertRunner.run`, `store.writer` (the lease), then `DefaultWriter.logged`, `Wal.append` and its force. At a 3.95 ms median force (record 161), that is a serial ceiling of about 250 commits/s per table, whatever the number of sessions, because the lease serializes them | **C1** for HTTP. **C3 open**, see Deferred D6 | U1: if Cosa sends one row per `INSERT` into one table, C3 is needed before Cosa's 1,600 msg/s. If Cosa batches rows or spreads them over tables, C1 alone holds |
| (d) WAL append and force | **D1 today:** `Wal.append` is `synchronized`, through the force; `bytes()`, `records()`, `resetThrough`, `holdingAppends` and `reset` use the same monitor. Each append opens and closes a `FileChannel` (`StoreFiles.append`) and calls `Files.exists`. **D2 (task 155):** `bytes` and `records` become lock-free counters, so the applier's checks no longer wait for a force. **D3:** a commit sequencer: a position reserved under a short lock, the write and force done outside it, completion in order. **D4:** one channel kept open per log. Cost saved: the open, close and stat syscalls per commit, small beside a ~4 ms force | **D2** | D3 gains nothing while the lease admits one appender per table: the only other monitor user is the applier, and D2 takes it off the force. D3 is worth it only together with D7 (several appenders per table) |
| (e) apply and checkpoint | **E1 today:** one platform thread per store (`Applier`, `Thread.ofPlatform().daemon()`). Each wake scans every table; each commit wakes it. **E2 (task 155):** the same thread with a work queue and a heap of deadlines: a commit wakes it only when its table becomes due before the heap's head, or the budget presses. **E3:** a small platform pool pulling due tables from the same heap; `applyLock` keeps one thread per table. **E4:** an applier per table. Cost: one thread per table, idle most of the time. **E5:** one owner thread per table that appends, applies and checkpoints (an actor). It drops `applyLock` and the `Wal` monitor at the cost of a handoff per commit | **E2** | per-table lateness (due time to batch start) above the table's interval while another table's batch runs. Then E3 with a pool of 2–4. E5 only with D7 |
| (f) reads and lag waits | **F1 today:** the read runs on its request or session thread. In `Store.awaitFresh`, it returns at once when within its lag; else `TableState.demand`, `Applier.wake`, then `TableState.awaitApplied` (`Condition.awaitNanos`, 10 s cap). Publish wakes every waiter (`signalApplied` → `signalAll`; `publish`). **F2:** the reader applies the queue itself, as a direct commit does. Record 162 rules it out: a reader never writes files. **F3:** a published transaction sequence with a per-transaction waiter list. Cost: more code for a saving only with many waiters on one table | **F1** | more than about 100 waiters on one table (the `signalAll` wake-ups show in a profile); then F3 |
| (g) long-poll "wait past txn n" (future) | **G1:** a plain HTTP request on its virtual thread that calls `awaitApplied(n, timeout)`. Cost: each poll holds one of the 64 `Admission` permits for its whole wait, so 64 polls starve `/write` and `/exec`. **G2:** G1 with an admission of its own (a second `Semaphore` and its own bound), a timeout at most the client's, and the same `applied` condition. **G3:** a push channel: chunked HTTP, or PG `LISTEN`/`NOTIFY` (Cosa speaks PG) | **G2**, when built | more pollers than the poll bound (a few hundred): then G3. A G3 over PG also needs a session thread per listener, so B2 or B3 matters then |

## 3. Consequences

- Thread kinds after task 155:
  - one HTTP dispatcher (JDK platform);
  - virtual threads for HTTP exchanges, the PG accept loop and PG sessions;
  - one platform applier per store;
  - the launcher's shutdown hook (`CdbServe`).
- Commits that force run on virtual threads: the HTTP committer in `CommitQueue.Table.commit` and the PG session in
  `InsertRunner`. A force blocks the carrier while the JDK's scheduler compensates. The applier is platform for that
  reason (`Applier` javadoc); the log force is not. The two are consistent only because a log force is one call and an
  apply is many. Record it, or measure carrier growth (`jdk.virtualThreadScheduler.maxPoolSize`, default 256) at 64
  concurrent forces.
- `Applier.committed` makes a writer over the 64 MiB budget park in 1 ms steps, up to 10 s, while it holds its table's
  lease (`DefaultWriter.logged` calls it before the writer is closed). Back-pressure is per table, and other tables'
  writers keep going. The docs should say so.
- The poller allocation (3.2 GB in 10 min) is a cost of A1 and B1 that this record accepts at 1.4 % GC. Its owner is
  the JDK. The first lever is B2 or A2, not a new server.
- `docs/cdb.md` §3, the applier row, says "never a logged commit, which takes only the log's monitor". The code
  refutes it: the applier takes that monitor (`Wal.bytes`, `resetThrough`, `holdingAppends`). The new "Threads and
  locks" section should state the lock order above and that the applier waits for at most one force in flight on the
  cut.
- [Record 162](0162-cdb-applies-commits-from-its-log.md)'s checkpoints row says "a log past 64 MiB is drained and cut
  under the writer lease". The code drains it under the log's monitor (`Applier.checkpointIfDue` →
  `Wal.holdingAppends`), not the lease. The record's wording needs correcting.

## 4. Task 155 delivers, and what is deferred

| Item | In 155 | Evidence needed first |
|---|---|---|
| lock-free `Wal` counters (D2) | yes | the applier's blocked time on `Wal` under the same 1,600 msg/s flood: from 53 ms max to below 1 ms, except at a cut |
| applier work queue and deadline heap (E2) | yes | applier passes per second at the flood: from ~225/s to the number of due batches; `wal_apply_stats` lateness unchanged or better |
| thread creation confined to `CdbServe`, `CdbServer`, `PgServer`, `Applier` | yes | the test must also catch `Executors.newVirtualThreadPerTaskExecutor`. `app` `ThreadRulesTest.virtualThreadsAndExecutorsComeFromTheThreadKit` exempts that factory everywhere, so on its own it does not confine `PgServer`'s executor. A negative control (a probe class calling it, the test red) shows the rule bites |
| "Threads and locks" in `docs/cdb.md` | yes | the lock order and thread kinds above, the §3 applier row corrected |
| D1. one applier per table, or a pool (E3/E4) | deferred | per-table lateness from `wal_apply_stats` with one slow table (an out-of-order merge) beside a fast one |
| D2. force outside the `Wal` monitor, through a commit sequencer (D3) | deferred | the power-cut harness (`StoreCrashTest` family) proving in-order completion and no gap at replay after a cut mid-force; plus a measurement that lease wait dominates commit time. Without D7 it gains nothing |
| D3. a single owner thread per table (E5) | deferred | a design for the commit handoff (latency added per commit, measured at a 12 msg/s trickle and at 1,600 msg/s), and the power-cut harness |
| D4. replacing the JDK HTTP server (A3) | deferred | A2 tried first and still above the bound on GC or p99 from `cdb-http-*` allocation (U2) |
| D5. PG sessions on platform threads (B2) | deferred | the B1 vs B2 A/B: allocation rate, session p99 and RSS at 64 sessions |
| D6. group commit for PG INSERT (C3; added by this review) | deferred | U1 answered; if one row per `INSERT` into one table: a measurement of commits/s per table over PG (expected ceiling ~250/s at a 3.95 ms force) |
| D7. several appenders per table (lease relaxed; added by this review) | deferred | needed only for D2 or D3; the power-cut harness and a test that the dedup and TTL order holds |

## 5. Claims checked

| # | Claim | Read | Result |
|---|---|---|---|
| 1 | HTTP runs a virtual thread per request behind a `Semaphore` | `CdbServer.Admission.execute` (`tryAcquire` of `MAX_REQUESTS` = 64; `newThreadPerTaskExecutor(Thread.ofVirtual().name("cdb-http-", 0))`) | holds |
| 2 | A request over the bound has its connection closed | `Admission.execute` throws `RejectedExecutionException`; JDK 25 `sun.net.httpserver.ServerImpl.Dispatcher.handle` catches `Throwable` and calls `closeConnection` | holds |
| 3 | PG runs a virtual thread per session behind a `Semaphore` | `PgServer.admit` (`tryAcquire`, `threads.execute`); `PgServer.threads = newVirtualThreadPerTaskExecutor()`; `MAX_CONNECTIONS = CdbServer.MAX_REQUESTS` | holds; the accept loop is also a virtual thread (`PgServer.start`) |
| 4 | `CommitQueue` group commit uses a `ReentrantLock` | `CommitQueue.Table.lock` and `changed`, `await` and `commitGroup` (lock released around `commit`) | holds; leader and followers, no thread of its own |
| 5 | Group commit covers PG writes | `InsertRunner.run` → `store.writer` → `DefaultWriter.logged` → `Wal.append` | refuted: HTTP `/write` only; every PG `INSERT` forces on its own |
| 6 | `Wal.append` holds its monitor through the fsync | `Wal.append` (`synchronized`) → `StoreFiles.append(file, frame, true)` (open, write, force, close) | holds |
| 7 | The applier blocks on `Wal.bytes()` behind a writer's force | `Wal.bytes()` `synchronized`; `Applier.dueForCheckpoint` and `checkpointIfDue` call it | holds (mechanism); the 53 ms is the measurement's |
| 8 | One platform applier per store | `Applier.<init>` (`Thread.ofPlatform().daemon().name("cdb-applier-" + name)`) | holds |
| 9 | The applier scans all tables on each wake | `Applier.run` loops `store.states()`; `Applier.committed` calls `wake()` on every commit | holds |
| 10 | `TableState` has `applyLock` and `lock` | `TableState.applyLock`, `TableState.lock` with `writerFree` and `applied` | holds; `applyLock` before `lock` by its comment |
| 11 | A read at lag waits for the applier, at most 10 s | `Store.awaitFresh` → `TableState.demand`, `Applier.wake`, `TableState.awaitApplied(logged, readWaitNanos)`; `Store.readWaitNanos` = 10 s | holds |
| 12 | `max_lag_ms` is carried as a `ScopedValue` | `Cdb.run(sql, maxRows, maxLagMillis)` binds `Store.READ_MAX_LAG_MILLIS`; `PgSession.runQuery`; `ReadLag.parse` | holds |
| 13 | Virtual-thread pollers allocate `ConcurrentHashMap$Node` | JDK 25 `sun.nio.ch.Poller.register` (`map.put`), `deregister` (`map.remove`), `map` a `ConcurrentHashMap<Integer, Thread>` | holds (mechanism); the HTTP vs PG split is unchecked (U2) |
| 14 | `docs/cdb.md` §3: the applier "never [waits for] a logged commit, which takes only the log's monitor" | `Applier.dueForCheckpoint` / `checkpointIfDue` → `Wal.bytes`, `Wal.holdingAppends`; `Wal.resetThrough` `synchronized` | refuted |
| 15 | Record 162: the 64 MiB drain runs "under the writer lease" | `Applier.checkpointIfDue` → `state.wal.holdingAppends(...)`; no `lease` call there | refuted: it runs under the log's monitor |
| 16 | Thread creation is already confined by a test | `app` `ThreadRulesTest.platformThreadsComeOnlyFromTheKindsThatNeedThem` exempts `CdbServe` and `Applier`; `virtualThreadsAndExecutorsComeFromTheThreadKit` exempts `CdbServer$Admission` and allows `newVirtualThreadPerTaskExecutor` anywhere; `cdb` `CdbArchitectureTest` has no thread rule | partly: `PgServer`'s executor is not confined. Task 155's test must cover it |
| 17 | An over-budget writer parks while it holds its table's lease | `DefaultWriter.logged` → `Applier.committed` (`parkNanos` loop up to `readWaitNanos`), inside the writer obtained through `Store.writer` → `TableState.lease` | holds |
