# cdb SQL: statements, grammar, functions and access

cdb speaks a subset of QuestDB's SQL: designated timestamps, `SAMPLE BY`, `LATEST ON`, `DEDUP UPSERT KEYS`, TTL, the
line protocol on `/write`, QuestDB's `/exec` JSON and PostgreSQL wire. How a statement is planned and run is in
[cdb](cdb.md) §1; why the destructive statements, `EXPLAIN` and the secrets exist is
[record 182](adr/0182-cdb-destructive-sql-explain-and-access.md); column changes, backups and IN subqueries,
[record 183](adr/0183-cdb-column-changes-backup-and-in-subqueries.md). One statement per request; a trailing `;` is allowed.

## 1. Statements

| Statement | Returns |
|---|---|
| `SELECT …`, `WITH …`, set operations, a bare table name (`turn` is `SELECT * FROM turn`) | rows |
| `CREATE TABLE [IF NOT EXISTS] t (…) [TIMESTAMP(ts)] [PARTITION BY unit] [TTL n unit] [WAL \| BYPASS WAL] [DEDUP UPSERT KEYS(…)]` | nothing |
| `ALTER TABLE t ADD [COLUMN] [IF NOT EXISTS] c type[, …]`, `ALTER TABLE t SET TTL n unit` | nothing |
| `ALTER TABLE t DROP PARTITION LIST 'p'[, …]`, `ALTER TABLE t DROP PARTITION WHERE <condition on the designated timestamp>` | the partitions dropped |
| `ALTER TABLE t DROP COLUMN [IF EXISTS] c`, `ALTER TABLE t RENAME COLUMN c TO d` | nothing |
| `BACKUP [DATABASE] TO 'folder'` | the tables copied |
| `DROP TABLE [IF EXISTS] t`, `TRUNCATE TABLE t` | nothing, the rows removed |
| `INSERT INTO t [(c, …)] VALUES (…)[, (…)]`, `INSERT INTO t [(c, …)] SELECT …` | the rows written |
| `UPDATE t SET c = e[, …] [WHERE …]`, `DELETE FROM t [WHERE …]` | the rows changed or removed |
| `SHOW TABLES`, `SHOW COLUMNS FROM t`, `SHOW CREATE TABLE t`, `SHOW PARTITIONS FROM t`, `SHOW WAL [FROM t]` | rows |
| `EXPLAIN <statement>` | one `QUERY PLAN` row per line; a change is planned, not run |

A DROP PARTITION takes whole partitions only: one is dropped when its whole time range lies in the range the LIST name
or the WHERE condition gives. DELETE removes the rows whose condition is TRUE; a condition is never NULL in cdb, as in
QuestDB, so `x != 3` and `NOT (x > 3)` are TRUE where `x` is NULL and remove those rows too. Each destructive statement is one
transaction: a reader that started before it reads the table as it was, one that starts after it reads the result.

DROP COLUMN and RENAME COLUMN rewrite the table in one transaction, as DELETE does, so they take time in proportion to
the table and hold its writer meanwhile; neither applies to the designated timestamp, the last column cannot be
dropped, nor a DEDUP UPSERT KEYS column (a renamed key is renamed in the keys), and a rename takes a name no column has
in any case, the column's own in another case included. A statement planned before a column change commits is planned
again. `BACKUP TO 'folder'` copies every table, each at one committed transaction, into a new store at `folder`, an
absolute path outside the data root that must not exist (the folder is taken as an empty one when the backup starts, so a folder made meanwhile is
refused; a failure removes it, and a crash during the backup leaves it empty, beside a `<folder>.tmp-<random>` folder,
both to be removed by hand), while writes go on; tables are copied one after another, as
cdb has no transaction across tables. A backup is restored offline: `CdbServe --restore <folder> <data root>` opens the
backup as a store, then copies its tables through the store, as BACKUP does, into a data root that is absent or empty.
`x [NOT] IN (SELECT c …)`, in a WHERE, runs its subquery once, before the outer query's first row, and then reads as an
IN list of its values; the subquery returns one column of at most 1,000,000 rows, names no column of the outer query,
and does not compare text with a timestamp (cast the column to TIMESTAMP: a written-out text reads as an interval, a
subquery's cannot). In UPDATE and DELETE it runs before the change takes the table's writer.

An UPDATE sets a column once and takes a WHERE that is BOOLEAN; it does not set the designated timestamp or a DEDUP
UPSERT KEYS column. Each is refused at plan time, under `EXPLAIN` too: `cannot assign column twice`, `cannot update by
non-boolean condition` and `cannot update deduplication key [table=…, column=…]`. QuestDB keeps every row when an UPDATE
gives two rows one key; cdb's rewrite commits through deduplication, which would merge them, so the column is refused:
a documented difference. A value given with an explicit `CAST`, in UPDATE SET and in INSERT VALUES, is taken as written;
only an implicit conversion is checked against the column's type.

A TTL needs a partitioned table and a whole number of partitions, QuestDB's rule: hours, days or weeks give whole days
on DAY partitions and whole weeks on WEEK partitions and are refused on MONTH and YEAR partitions; months and years are
accepted on every partitioning, whole years only on YEAR. MONTH and YEAR count calendar months. A partition goes once it
ends the TTL before the newest row or now, whichever is earlier. A DEDUP UPSERT KEYS column named twice is refused.

## 2. Grammar

EBNF: `[ ]` optional, `{ }` repeated, quoted words are keywords in any case.

```
statement      ::= ( query | create_table | alter_table | drop_table | truncate | insert | update | delete | show
                   | backup | "EXPLAIN" statement ) [ ";" ]
query          ::= [ "WITH" cte { "," cte } ] set_expr [ "ORDER BY" order_item { "," order_item } ] [ "LIMIT" limit ]
cte            ::= name "AS" "(" query ")"                       -- non-recursive: sees only the CTEs before it
set_expr       ::= intersect_expr { ( "UNION" [ "ALL" ] | "EXCEPT" ) intersect_expr }
intersect_expr ::= operand { "INTERSECT" operand }
operand        ::= "(" query ")" | select | from select_tail     -- a FROM clause alone is SELECT * FROM it
select         ::= "SELECT" [ "DISTINCT" ] expr [ alias ] { "," expr [ alias ] } [ "FROM" from ] select_tail
select_tail    ::= [ "WHERE" expr ]
                   [ "LATEST" "ON" name "PARTITION" "BY" name { "," name } ]
                   [ "SAMPLE" "BY" integer unit [ "FILL" "(" fill { "," fill } ")" ]
                                         [ "ALIGN" "TO" "CALENDAR" ] ]  -- ALIGN TO FIRST OBSERVATION is refused
                   [ "GROUP BY" expr { "," expr } ]
unit           ::= "U" | "T" | "s" | "m" | "h" | "d" | "M" | "y"   -- case-sensitive
fill           ::= word | [ "-" ] number                            -- NONE, NULL, PREV, LINEAR or a constant
from           ::= from_item { join_kind from_item [ "ON" expr ] } -- ON required except for CROSS
join_kind      ::= "JOIN" | "INNER" "JOIN" | "LEFT" [ "OUTER" ] "JOIN" | "CROSS" "JOIN"
from_item      ::= ( "(" query ")" | string | name "(" [ expr { "," expr } ] ")" | name ) from_suffix
from_suffix    ::= [ "TIMESTAMP" "(" name ")" ] [ alias ] [ "TIMESTAMP" "(" name ")" ]   -- read and ignored
alias          ::= "AS" any_name | name
order_item     ::= expr [ "ASC" | "DESC" ]
limit          ::= expr [ "," expr ]                               -- n, -n (the last n), lo, hi

create_table   ::= "CREATE" "TABLE" [ "IF" "NOT" "EXISTS" ] name "(" column_spec { "," column_spec } ")"
                   [ "TIMESTAMP" "(" name ")" ] [ "PARTITION" "BY" word ] [ "TTL" number word ]
                   [ "BYPASS" "WAL" | "WAL" ] [ "DEDUP" "UPSERT" "KEYS" "(" name { "," name } ")" ]
column_spec    ::= name type [ "CAPACITY" integer ] [ "CACHE" | "NOCACHE" ] [ "INDEX" [ "CAPACITY" integer ] ]
alter_table    ::= "ALTER" "TABLE" name ( "SET" "TTL" number word
                                        | "ADD" [ "COLUMN" ] [ "IF" "NOT" "EXISTS" ] column_spec
                                                   { "," [ "IF" "NOT" "EXISTS" ] column_spec }
                                        | "DROP" "PARTITION" ( "LIST" string { "," string } | "WHERE" expr )
                                        | "DROP" "COLUMN" [ "IF" "EXISTS" ] name
                                        | "RENAME" "COLUMN" name "TO" name )
drop_table     ::= "DROP" "TABLE" [ "IF" "EXISTS" ] name
truncate       ::= "TRUNCATE" "TABLE" name
backup         ::= "BACKUP" [ "DATABASE" ] "TO" string
insert         ::= "INSERT" "INTO" name [ "(" name { "," name } ")" ]
                   ( "VALUES" "(" expr { "," expr } ")" { "," "(" expr { "," expr } ")" } | query )
update         ::= "UPDATE" name "SET" name "=" expr { "," name "=" expr } [ "WHERE" expr ]
delete         ::= "DELETE" "FROM" name [ "WHERE" expr ]
show           ::= "SHOW" ( "TABLES" | "COLUMNS" "FROM" name | "CREATE" "TABLE" name | "PARTITIONS" "FROM" name
                          | "WAL" [ "FROM" name ] )

expr           ::= and_expr { "OR" and_expr }
and_expr       ::= not_expr { "AND" not_expr }
not_expr       ::= "NOT" not_expr | predicate
predicate      ::= concat { comp_op concat | "IS" [ "NOT" ] "NULL"
                          | [ "NOT" ] "IN" ( "(" ( query | expr { "," expr } ) ")" | concat )
                          | [ "NOT" ] "BETWEEN" concat "AND" concat | [ "NOT" ] ( "LIKE" | "ILIKE" ) concat }
comp_op        ::= "=" | "!=" | "<>" | "<" | "<=" | ">" | ">="
concat         ::= additive { "||" additive }
additive       ::= multiplicative { ( "+" | "-" ) multiplicative }
multiplicative ::= unary { ( "*" | "/" | "%" ) unary }
unary          ::= "-" unary | cast
cast           ::= primary { "::" type }
primary        ::= number | string | "NULL" | "TRUE" | "FALSE" | "*" | "(" query ")" | "(" expr ")"
                 | "CASE" [ expr ] "WHEN" expr "THEN" expr { "WHEN" expr "THEN" expr } [ "ELSE" expr ] "END"
                 | "CAST" "(" expr "AS" type ")"
                 | word "(" [ "*" | [ "DISTINCT" ] expr { "," expr } ] ")"
                   [ "OVER" "(" [ "PARTITION" "BY" expr { "," expr } ] [ "ORDER BY" order_item { "," order_item } ] ")" ]
                 | name { "." any_name } [ "." "*" ]
```

A statement that parses can still be refused: by the binder (an unknown column or function), by the planner (an `IN`
subquery of several columns or naming an outer column, a scalar subquery, `SAMPLE BY` without a designated timestamp, a non-constant `LIMIT`, a DROP
PARTITION condition on other columns or on a table not partitioned), or by access (§5). The parser also refuses an expression, a chain of operators or a nesting of subqueries deeper than
256 levels: `cannot parse an expression nested or chained past the depth limit [limit=256, position=…]`.

A TIMESTAMP compares with a LONG, read as microseconds, as in QuestDB; an `IN` subquery over the two types is still
refused: cast its column. A window's ORDER BY orders as the query's ORDER BY does: a DOUBLE NULL last ascending, first
descending. `ORDER BY` the designated timestamp `DESC` over a scan reads the table newest first, rows with equal
timestamps last written first, as QuestDB's backward scan gives them. UNION, EXCEPT and INTERSECT compare a DOUBLE by its
bits, as DISTINCT does: -0.0 and 0.0 are two rows; `=` and a join's `ON` still treat them as equal. `FILL(LINEAR)` is
QuestDB's: interpolated in double over the bucket start times and truncated for a whole-number type; per key, before its
first value along its first two, after its last along it and the bucket before; a key with one value is NULL in the
buckets around it.

## 3. Lexical rules

| Element | Rule |
|---|---|
| Reserved words, not bare names | `all and as between by case cross distinct else end except false from full group having ilike in inner intersect is join latest left like limit not null on or order outer over right sample select then true union when where with`; after `AS` or a dot they are names |
| Identifiers | A letter (any Unicode letter) or `_`, then letters, digits, `_` or `$`; or `"double quoted"`, never a keyword |
| Strings | `'single quoted'`, `''` for a quote |
| Numbers | Integers are LONG (out of range is an error; QuestDB's `L` suffix accepted); decimals and exponents are DOUBLE |
| Comments | `-- …` to the end of the line, `/* … */` |

## 4. Types and functions

| Kind | Members |
|---|---|
| Types | BOOLEAN, LONG, DOUBLE, TIMESTAMP (microseconds, UTC), SYMBOL, VARCHAR; DDL maps INT, INTEGER, SHORT, BYTE to LONG, FLOAT to DOUBLE, STRING and CHAR to VARCHAR, DATE to TIMESTAMP; other types are refused |
| Scalar | `abs`, `coalesce`, `concat`, `date_trunc`, `dateadd`, `datediff`, `hour`, `length`, `lower`, `now`, `replace`, `round`, `substring`, `timestamp_floor`, `to_str`, `upper`; generators `rnd_double`, `rnd_symbol`, `timestamp_sequence` |
| Aggregate | `count`, `count(DISTINCT …)`, `count_distinct`, `sum`, `avg`, `min`, `max`, `first`, `last`, `approx_percentile`, `approx_median`, `percentile_cont`, `percentile_disc` |
| Window | `row_number`, `lag`, `lead`, `first_value`, `last_value`, and `sum`, `avg`, `count`, `min`, `max`, `first`, `last` over `PARTITION BY` and `ORDER BY` (no frames) |
| Table functions | `tables()`, `table_columns(t)`, `table_partitions(t)`, `wal_tables()`, `wal_apply_stats()`, `show_wal()`, `long_sequence(n)`; constant arguments only |

`DISTINCT` inside an aggregate is supported for `count` only: `sum(DISTINCT x)` and the other aggregates are refused
(`cannot run DISTINCT in this aggregate`). `approx_percentile` over a DOUBLE column answers the exact percentile, where
QuestDB answers an approximation; over a LONG it answers the top of the HdrHistogram bucket that holds the value, as
QuestDB does.

## 5. Access

A server started with `--auth-dir <folder>` (the machine server's is `~/.comando-cdb`) lets in only holders of a
secret from that folder: `secret` for every statement and line-protocol writes, `secret.readonly` for `SELECT`, `SHOW`
and `EXPLAIN`. Each file holds 64 hex characters; it is made at the first start and kept, and deleting it and
restarting the server rotates it.

| Listener | How the secret is given | Refusals |
|---|---|---|
| HTTP | `Authorization: Bearer <secret>` | 401 without a valid secret; 403 for a read-only secret on a change or on `/write`, for a `Host` that is not the loopback address and port, or for any `Origin` |
| PG wire | the password, any user name | SQLSTATE 28P01 without a valid secret; 42501 for a change in a read-only session |

Example: `psql "host=127.0.0.1 port=8812 user=admin password=$(cat ~/.comando-cdb/secret.readonly)"`.

## 6. Limits

| Limit | Value | Answer when met |
|---|---|---|
| Query time (a change runs to its end) | 60 s | HTTP 408; PG SQLSTATE 57014 |
| Result rows | 1,000,000 | HTTP 400; PG SQLSTATE 54000 |
| HTTP request read, response write, connections | 30 s, 120 s, 128 | the connection is closed |
| Request bodies held at once | 64 MiB (a `/write` body of at most 1 MiB is read into pooled 256 KiB buffers, 64 in all, and counts at least the buffers it holds; a body of unknown length counts the 16 MiB it may reach) | HTTP 503 after 5 s |
| PG wire startup (startup packet and password) | 10 s from the connection | the connection is closed |
| Tables, columns a table, name length | 1,000, 1,024, 127 characters | the CREATE, ADD COLUMN or line-protocol write is refused |

A `/write` field of type integer goes into a DOUBLE column, or into a TIMESTAMP column that is not the designated one,
and a boolean into a LONG or DOUBLE column as 1 or 0. A body whose designated timestamp lies before 1970 or in the year
10000 or later is refused, none of its rows written.

`/exec` streams its JSON answer in chunks as the rows are encoded, so a result is never held as one string. A failure after the 200 headers were sent (an `Error`, a write to a closed client) drops the connection instead of ending the body: the client sees a transport error, never a 200 whose JSON is cut short.
