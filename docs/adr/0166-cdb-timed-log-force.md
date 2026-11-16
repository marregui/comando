# 166. cdb's timed log force: an opt-in bound on what a power cut loses, with a per-request durable flag

| Date | Status | Decided by |
|---|---|---|
| 2026-11-16 | accepted; built in task 166 | Miguel and Claude |

Read against comando `7619532d`.

## 1. Context

[Record 161](0161-cdb-write-ahead-log.md) answers a logged commit after its `_wal` record is forced. Task 160's soak:
the cdb Cosa writes one `/write` at a time, each commit waits for its force (13 ms median, 184 ms longest on the Docker
VM's disk), and Edge dropped 4.3 % of the cdb Cosa's messages at its queue limit, the drops following the force time
minute by minute (r = 0.91). QuestDB's default `cairo.commit.mode` is `nosync`: it never waits for the disk and
dropped none. Miguel, 2026-11-15: cdb's commits stay durable by default; 2026-11-16: build the compromise between
the two.

## 2. Decision

| Part | Rule |
|---|---|
| Option | `StoreOptions(logForceMillis)`, per store, not persisted; 0 (the default) is the force per commit; `CdbServe --log-force-ms N`, `Cdb.open(root, options)` |
| Answer | with N > 0 a logged commit is answered after its record is written to the page cache |
| Force | `store.LogForcer`, one platform thread per store (`cdb-log-force-<root>`), forces each log at most N ms after its first unforced record; the force runs outside the log's monitor, so commits go on during it |
| Durable flag | `/write?durable=true`, PG `SET cdb.durable = on`: the request is answered after the force that covers its commits, which the forcer runs at once for it |
| Durable wait | on a record sequence number that is never reused; a transaction number is reused by a refused commit's retract |
| Loss | process crash: none (the kernel holds the written pages). OS crash or power cut: per table, the commits answered in the last N ms plus one round of forces |
| Failed force | no retry (a retry can succeed over pages the kernel dropped); the table refuses commits until the store reopens; durable waiters fail "commit in doubt"; ERROR with the table and the transaction range |
| Replay | the scan stops at the first short or bad-CRC frame and cuts there, so a power cut leaves a prefix of the commits; next transaction = max of `_txn` and the log's last |
| Log format 2 | a random salt in the header, new at each cut, seeds every frame's CRC: stale bytes of an earlier log generation, left in blocks a power cut did not overwrite, can no longer pass as a frame. Format 1 logs still read and become format 2 at their next cut |

## 3. Consequences

- A store opened with N > 0 trades at most N ms of answered commits on a power cut for one force per table per
  interval instead of one per commit.
- Requests that must survive a power cut ask for it per request; the rest of a client's traffic takes the timed force.
- A cdb build before this record cannot open a log of format 2.
- Known limits, measured against the power-cut model (`DiskModel`'s stale image), not fixed in task 166: a retract
  gives its txn back under the same salt, and a scan's cut of a torn tail at open keeps the salt, so a stale disk
  block holding the frame cut could pass its CRC after a later power cut; the forcer's figures are not yet in
  `wal_tables()`.

## 4. Alternatives not taken

| Alternative | Why not |
|---|---|
| A group-commit delay (PostgreSQL `commit_delay`) | groups only concurrent commits of a table; Cosa sends one at a time |
| No force (QuestDB `nosync`) | the loss is whatever the OS has not written back, unbounded by cdb |
| The applier forcing on its deadlines | a long apply batch would delay forces past N |
