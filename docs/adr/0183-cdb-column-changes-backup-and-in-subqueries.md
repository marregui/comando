# 183. cdb drops and renames columns, backs up and restores, and runs IN subqueries

| Date | Status | Decided by |
|---|---|---|
| 2026-11-19 | accepted; built in task 199 | Miguel and Claude |

## 1. Context

After [record 182](0182-cdb-destructive-sql-explain-and-access.md) cdb could remove tables, partitions and rows, but a
wrong column stayed until its table was recreated, and nothing copied the machine's metrics history anywhere: a bad
DROP or DELETE, or a lost disk, lost it for good. `x IN (SELECT …)` was refused by the planner. Miguel, 2026-11-19:
backup and restore, drop and rename column, IN subqueries; the types stay as they are; TLS is not needed while the
server listens on loopback.

## 2. Decision

| Statement | What it does |
|---|---|
| `ALTER TABLE t DROP COLUMN [IF EXISTS] c` | Rewrites the table without the column in one transaction, the path DELETE takes; a reader that holds a snapshot reads the old definition until it closes |
| `ALTER TABLE t RENAME COLUMN c TO d` | Rewrites the table with the column under its new name, in one transaction |
| `BACKUP [DATABASE] TO 'folder'` | Copies every table, each at one committed transaction, into a new store at an absolute folder outside the data root that does not exist; writes go on meanwhile; the rename into place is forced before the answer |
| `CdbServe --restore <folder> <data root>` | Offline: opens the backup as a store, then copies its tables through the store, as BACKUP does, into a data root that is absent or empty; starts no server |
| `x [NOT] IN (SELECT c …)` | The subquery runs once, before the outer query's first row; its values then act as an IN list; one column, uncorrelated, at most the result row cap |

The column changes and the backup go through the store's public API (a reader's snapshot, a writer's truncate and
re-append, a new store's create and commit) rather than through file operations: they add no log record kind, and they
are crash-safe by the paths that already are. The one file they add is a table's `_meta.prev`, the definition before a
column change, written and forced before the new `_meta`: an open that finds `_txn` short of the change's transaction
puts it back, so a power cut leaves the table as before or as after. A rewrite reads its snapshot with every answered
commit applied, whatever read lag the server runs with, so the truncate removes nothing it did not read. A plan holds
column indexes, so a statement's snapshots and writers are checked against the definitions it was planned under: one
whose table's columns no longer begin with the planned columns (a column dropped or renamed, the table made anew) is
refused and the statement planned again; a column added at the end keeps the indexes and passes. The cost is time in proportion to the
table, which the machine's metrics tables (megabytes) can pay.

## 3. Consequences

- A backup is a store: any cdb opens it, and restoring is copying it into place with the server stopped.
- Tables are backed up one after another, each consistent on its own; cdb has no transaction across tables, so two
  tables are not copied at one instant.
- DROP COLUMN and RENAME COLUMN on a large table hold its writer for the rewrite; line-protocol writes to it wait.
- Correlated subqueries and scalar subqueries stay refused; an IN subquery is taken in a WHERE only.
- A rename that changes only a column's case is refused: names match in any case, and the column files of both names
  would be one file on a file system that folds case.
- A cdb build older than this one does not know `_meta.prev`: after a power cut in the middle of a column change it
  would read the table under the new definition with the old files. Downgrading is not supported.
