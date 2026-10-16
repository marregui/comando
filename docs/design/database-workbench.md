# Database workbench

comando speaks to PostgreSQL and to QuestDB over the PostgreSQL wire protocol, through the PostgreSQL JDBC driver. This document covers how a statement runs, how its rows are stored and shown, and the tools built on a result.

## 1. Lanes

A JDBC connection runs one statement at a time, so each connection name has a lane: one connection and one thread that runs every open, close, statement and health check of that connection in order. The user's runs and Claude's `db_run_sql` calls on the same connection queue on the same lane. A lane lives while it holds a connection or has work queued: once its last task ends with no connection (closed, lost, or an open that failed) its thread stops, so a closed, removed or renamed connection keeps none; the next work starts a new lane, after every task of the old one has ended, so the connection's order holds. Removing, renaming or repointing a connection also forgets its schema, so completion never offers another server's tables. The connection test builds its driver properties as the lane does, TLS mode included, so Test and Connect agree.

| Property | Value |
|---|---|
| URL | `jdbc:postgresql://<host>:<port>/<database>` |
| Driver properties | `ApplicationName=comando`, `loginTimeout=20`, `tcpKeepAlive=true`, `cancelSignalTimeout=2`, `sslmode` from the connection |
| TLS | `sslmode` is one of `prefer` (default), `disable`, `allow`, `require`, `verify-ca`, `verify-full` |
| Autocommit | on |
| Fetch | 5 000 rows per round trip |
| Rows kept per run | 5 000 000; past that the fetch stops and the run is marked truncated |
| Health check | every 60 s each open connection is checked with `isValid(5)` as a task on its lane; a failed check closes the connection and reports it lost |
| Opening | running SQL in a panel opens its connection when closed |

**Cancel.** ⌘. marks the request cancelled, then reads the statement the lane is running; the lane publishes its statement, then reads the mark. Either the lane sees the mark before it runs, or the caller sees the statement and calls `Statement.cancel()`. A statement still running 2 s after its cancel has its connection aborted, which unblocks a socket read the server never answers; the lane reconnects on the next run.

## 2. Results

`ColumnarResult` stores a run's rows by column in primitive chunks of 16 384 values: no boxed value and no row object per cell.

| Column kind | Stored as |
|---|---|
| `smallint`, `integer`, `bigint` | `long` |
| `real`, `double precision` | `double` |
| `boolean` | `long`, 0 or 1 |
| `date`, `timestamp`, `timestamp with time zone` | `long`, epoch microseconds UTC |
| everything else, `numeric` included | text |

The lane appends rows and publishes the row count with a volatile write; the grid and plots read rows below that count while the fetch continues. A result lives in `ResultRegistry` under its request id; the state's run record (status, row and update counts, truncated flag, execution, fetch and total times, error) refers to it by that id.

The Results tab shows the focused panel's last run: the grid, the row count, and the times. Cells format into one reused buffer through `Formats`; painting still makes one short string per visible cell, since `JTable` takes a value object per cell. The cost is bounded by the visible cells, not the rows.

## 3. What can be done with a result

| Key | Command | Result |
|---|---|---|
| ⌘J | Plot last result | a plot view: x is the first time column, else the first numeric column; up to eight other numeric columns become series; at most the first 2 000 000 rows are plotted |
| ⌘⇧J | Plot last result, refreshing… | asks for a period (default 5 s); the panel's query runs again each period when it is not running, and each finished run replaces the plot without moving the lower panel's tab; running another statement in the panel, closing the plot's tab, "Stop refreshing plots", or its connection failing stops it, with a notice. A refresh posts no notice of its own; a refresh that fails posts a warning ("Live plot in <panel> failed: …"), never a desktop notification |
| ⌘⇧R | Keep the result in a tab | a table view of the rows, at most 100 000, that the next run does not replace |
| ⌘⇧I | Attach the result | the selected rows, else the first 50, else the error, as a block in the editor ([Command dispatch](command-dispatch.md#5-attaching-results)) |
| ⌘⇧S | Export | the grid as CSV, in the order shown; the file is written off the EDT from a snapshot; the save dialog opens in the last export directory and the status bar names the file written |

**Plans.** A finished `EXPLAIN` whose result has one column shows as a tree by default: each line becomes a node under the nearest line above it with less indentation, and PostgreSQL's `->` markers are dropped. A toggle switches between the tree and the rows.

## 4. Schema

When a connection opens, its schema loads in the background, without a view: first from QuestDB's own catalogue, `tables()` and a description of each table, so columns carry QuestDB's type names (`SYMBOL`, `TIMESTAMP`); when that fails, as on PostgreSQL, from `information_schema.columns` (system schemas excluded). At most 50 000 rows are read. QuestDB's catalogue is read table by table up to 50 tables; above that the single `information_schema` query is used, with PostgreSQL's type names. A statement that changes the schema (`CREATE`, `ALTER`, `DROP`, `TRUNCATE`, `RENAME`), anywhere in a block, from the editor or from Claude's `db_run_sql`, reloads it when it completes (or when a block of several statements fails), so completion knows the new table, and an open "schema · <connection>" explorer shows it.

| Use | Detail |
|---|---|
| Explore the schema (⌘⇧E, or the connection's menu in the sidebar) | a tree in the side pane: tables with their column count, columns with their types; Enter or "Insert into the editor" in a table's context menu puts `SELECT * FROM <table> LIMIT 100;` at the editor's caret; double-click on a table expands it, on a column inserts the column's name; names that need quotes are quoted |
| Completion in the editor | SQL keywords, then the tables and columns of the bound connection's schema; a column found in several tables is offered once with its tables |

## 5. Embedded QuestDB

comando can start a QuestDB server in the data root, on QuestDB's default ports (PostgreSQL wire 8812, web console 9000, ILP 9009), from a toggle in the window. It creates `questdb/` on first start. Every listener binds 127.0.0.1 and ILP over UDP is off, because QuestDB binds all interfaces by default and its default login is public; the settings go through QuestDB's `QDB_` environment overrides, so `server.conf` is left as it is. It stops when comando exits, after the lanes that may use it.
