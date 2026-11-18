# 182. cdb drops, deletes and explains, and its server lets in only holders of its secrets

| Date | Status | Decided by |
|---|---|---|
| 2026-11-18 | accepted; built in task 197 | Miguel and Claude |

## 1. Context

cdb could create tables, add columns, insert and update, but not remove anything: a wrong metrics table, a test table
or bad rows stayed until their files were deleted by hand, and the only retention was a table's TTL. Nothing showed a
table's partitions or its write-ahead log, and `Cdb.explain` printed the plan object's raw text, not the plan tree. Four
functions the executor implements (`abs`, `length`, `upper`, `lower`) were refused by the binder, and three window
functions it types (`lead`, `first_value`, `last_value`) failed when run.

The machine server ([record 180](0180-cdb-is-the-machine-server.md)) answered any client: no authentication on HTTP or
PG wire, a state-changing GET with no Host or Origin check (so a web page in the user's browser could reach it), no
request, query or result bounds, the SQL of a failed request in the log, and no cap on what line-protocol writes create.
Miguel, 2026-11-18: drop, delete and truncate for tables and partitions, SHOW for partitions and the WAL, EXPLAIN, the
three defects fixed and every security gap closed.

## 2. Decision

### 2.1 SQL

| Statement | What it does |
|---|---|
| `DROP TABLE [IF EXISTS] t` | Removes the table in one step that a crash finishes or undoes; a reader that holds a snapshot reads on until it closes |
| `TRUNCATE TABLE t` | Removes every row in one transaction; the definition stays |
| `ALTER TABLE t DROP PARTITION LIST 'p'[, …]` / `… WHERE <condition on the designated timestamp>` | Removes whole partitions in one transaction, a direct commit named by a checkpoint, as a truncate is, so no logged record can bring a dropped row back |
| `DELETE FROM t [WHERE …]` | Rewrites the table without the matching rows in one transaction, as UPDATE does; the rows whose condition is TRUE go (a condition is never NULL: `x != 3` is TRUE where `x` is NULL) |
| `SHOW PARTITIONS FROM t`, `table_partitions(t)` | One row per partition, with QuestDB's column names |
| `SHOW WAL [FROM t]`, `show_wal()` | The write-ahead log's state per table |
| `EXPLAIN <statement>` | One row per line: the plan tree and the executor's choices (columns read after pruning, parallel or serial, late read, join build side); a change is planned, not run |

### 2.2 Access

| Part | Rule |
|---|---|
| Secrets | `secret` (owner: everything) and `secret.readonly` (SELECT, SHOW, EXPLAIN) in the auth folder, `~/.comando-cdb` for the machine server: 64 hex characters, mode 0600, made at the first start, kept across restarts; rotated by deleting the file and restarting |
| HTTP | `Authorization: Bearer <secret>`; 401 without a valid one, 403 for a read-only secret on a change or on `/write`; on a loopback bind the `Host` must be the loopback address with the port, and a request with an `Origin` is refused |
| PG wire | The password is the secret, any user name; a read-only session refuses changes (SQLSTATE 42501) |
| Bind | A non-loopback `--bind` is refused unless `--auth-dir` is given |
| Limits | A query runs at most 60 s (a change takes no cancel and runs to its end) and returns at most 1,000,000 rows; HTTP requests are read within 30 s and written within 120 s, 128 connections at most; request bodies held at once at most 64 MiB; at most 1,000 tables, 1,024 columns a table, names of 127 characters |
| Logs | A failed request logs its path, never its query string or statement text |
| Clients | comando's launcher passes `--auth-dir`; `MachineCdb`, the metrics sink and a JDBC connection to the machine server with an empty password use the owner secret; the kit's `metrics` tools read it from the machine folder |

## 3. Consequences

- A client that does not send the secret is refused: a comando or kit built before this record cannot write metrics to
  a server built after it. The switch-over restarts comando first, then the server.
- External clients (psql, a BI tool) connect with the password from `~/.comando-cdb/secret` or `secret.readonly`.
- TLS is not added: the server listens on loopback only, where authentication closes the gaps found; TLS becomes the
  next step when a client reaches the server from another machine.
- `qdb_server`, left in the store by [record 181](0181-the-machine-server-is-named-cdb.md), can now be dropped.
