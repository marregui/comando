# 170. cdb's row blocks share an arena per 100 ms epoch instead of a shared arena each

| Date | Status | Decided by |
|---|---|---|
| 2026-11-16 | accepted; built in task 170 | Miguel and Claude |

Read against comando `40e88b65`. Amends the Commit blocks row of [record 167](0167-cdb-heap-reduction.md).

## 1. Context

Record 167 gave each queued commit's row block its own `Arena.ofShared()`, closed by the block's owner. Closing a shared
arena stops every thread of the process for a handshake. A Cosa soak on `40e88b65` (task 169, 17 min, the Edge flood)
recorded 2,460,048 `HandshakeAllThreads` VM operations, 2,377 a second, 221 s of the VM thread's time, the longest
5.3 s, against 1,418,594 applied commits; the six longest fell in the minutes the Cosa loader's reads of `poc_tables`
timed out (time-correlated, causation unchecked). Creating an arena per block was also 7.5 % of the Java CPU samples.

## 2. Decision

| Part | Rule |
|---|---|
| Epochs | `store.BlockArenas`: blocks join the current epoch, one `Arena.ofShared()` tagged as row blocks with its byte count; an epoch takes new blocks until it is 100 ms old or has handed out 16 MiB, then the next join starts a new one |
| Close | an epoch's arena closes when it takes no more blocks and its last block left: at that leave, or by the applier's 10 s pass for an idle epoch past its age; so 10 shared-arena closes a second plus one per 16 MiB of commits, and none held while no commit comes |
| Owners | unchanged from record 167: request threads fill a block, the applier closes it after publish, the replay in a `finally`, a table's close for blocks never applied; a block's close gives its bytes back to the epoch |
| After close | a closed block points its columns at a one-byte segment of a closed shared arena, so a read or write throws (`IllegalStateException`, or `IndexOutOfBoundsException` past that byte) instead of reaching bytes still mapped in an open epoch |

## 3. Consequences

- Native memory is returned per epoch, not per block: an epoch holds every byte its blocks were given until its last
  block leaves, and a block never closed (a leaked block, a writer's pending block while it is leased) keeps its
  whole epoch.
- Measured on a fresh three-way Cosa soak (new volumes, 18 min window, the same Edge flood, `--log-force-ms 100`):
  12,793 handshakes in 20m46s, 10.3 a second (2,377 before), 5.4 s of VM-thread time (221 s in 17 min before),
  the longest 75.6 ms (5.3 s); GC paused 0.95 % of the time, longest 125 ms (3.47 %, 2.14 s); the Cosa loader
  created its 53 tables in 20 s with no failed read (before: 18 reads timed out over 4 min); cdb 1,703,327 rows,
  QuestDB 1,704,322, TimescaleDB 1,029,246; cdb's container memory at 283–316 MiB from minute 5 on, no growth. Creating
  the segments (`Arena.allocate`) is still 6.6 % of the Java CPU samples.
