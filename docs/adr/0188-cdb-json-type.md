# 188. cdb has a JSON type: checked when written, stored as VARCHAR, read in place

| Date | Status | Decided by |
|---|---|---|
| 2026-11-21 | accepted; built in task 222 | Miguel and Claude |

## 1. Context

comando's frontear packet log ([record 187](0187-frontear-lab-node-per-instance.md)) keeps each MQTT payload in the
machine's cdb, table `frontear_packet`. The payloads are JSON: presence documents of a few KB every few seconds per
instance, questions and replies. cdb had six column types and no JSON function. QuestDB, whose SQL cdb follows, has
`json_extract(varchar, path)` over plain `VARCHAR` and no JSON type. Miguel, 2026-11-21: "achieve the json type and
have it fully tested and measured … optimised, with no leaks, no unnecessary fluff, making json an integral type of
cdb."

## 2. Decision

| Question | Rule |
|---|---|
| the type | `CdbType.JSON`, named `JSON` in DDL, `table_columns`, `SHOW CREATE TABLE` and `/exec` |
| storage | a `VARCHAR` column's files: `.d` UTF-8 bytes, `.i` end offsets; the text kept as sent, whitespace and key order included. No binary form |
| the check | every non-NULL value is one JSON text (RFC 8259, any top-level value). `DefaultWriter.putText` checks it, the one door of SQL writes, line-protocol writes and log replay; a refused value drops its row and fails the statement or the line (`refuse invalid JSON [table=…, column=…, at=…]`). `CAST(x AS JSON)` checks too |
| comparison | as text: `=`, `ORDER BY`, `GROUP BY`, `DISTINCT`, dedup keys compare the bytes |
| reading | `json_extract(j, path)` and `j ->> key`: VARCHAR, a string's decoded text, any other value's JSON text as stored, NULL for JSON `null`, a missing path or a text that is not JSON up to the value; `j -> key`: the value's JSON text as JSON; `json_exists(j, path)`: BOOLEAN. They take any text, so JSON in a `VARCHAR` reads the same |
| paths | QuestDB's: `.name`, `["name"]`, `[n]`, after an optional `$`; constant per query, compiled once (`JsonPath`) |
| operators | `->` and `->>` bind as `||`: left to right, below `+ -`, above the comparisons (PostgreSQL's order) |
| PostgreSQL wire | RowDescription type OID 114 (`json`); the value's text; a `json` parameter binds as text |
| ILP | a string or symbol field fills an existing `JSON` column; a new column from a string field is `VARCHAR`, as before |

The scanner (`cdb.json.Json`) walks the UTF-8 bytes in place, one forward pass, with no recursion: an open container
is one byte on a stack it grows, so a text nested 100,000 deep scans. A lookup stops at the value its path names and
does not read past it. One scanner per compiled function and per writer's row block: over `VARCHAR` and `JSON`
input it allocates nothing per row but the copy of the value it answers; a `SYMBOL` held as a dictionary key is
decoded to bytes first. An `INSERT` or `UPDATE` of a text into a `JSON` column is not cast: the writer checks it
once, on the rows written, and its error names the table and the column.

## 3. Measured

Method: a throwaway harness on the embedded `Cdb` (appender ingest, then `Cdb.run` per query), Apple M2 Max, 12 cores, JDK 25, `-Xmx1g`, one
day partition of 100,000 rows of frontear-like presence documents (3,425 characters each, 14 nested session objects,
escapes, the looked-up keys after the sessions), each query run 9 times, p50.

| Query | p50 | Read rate |
|---|---|---|
| `payload ->> 'kind' = 'presence'`, the first key | 13–15 ms | stops after ~30 bytes a row |
| `payload ->> 'slug' = 'comando'`, a key after 3.3 KB | 210–221 ms | 1.55–1.63 GB/s |
| `GROUP BY payload ->> 'status'` | 215–225 ms | 1.52–1.59 GB/s |
| `json_extract(payload, '.sessions[13].busy')` | 205–218 ms | 1.57–1.67 GB/s |
| `json_exists(payload, '.nope')`, the whole text | 220–224 ms | 1.53–1.56 GB/s |
| the same filter as `payload LIKE '%"slug":"comando"%'` on `VARCHAR` | 820–853 ms | 0.40–0.42 GB/s |

Ingest through the appender, the second of two runs each: `VARCHAR` 490–533 ms, `JSON` 823–871 ms for the 100,000
rows (343 MB): the check costs 3.3–3.8 µs a row. jfrq-live (`profile` settings, 10 ms samples) put 36 % of the CPU in
`Json.string`, the scan of string contents. A word-at-a-time scan of strings (eight bytes per load, a SWAR test for a
quote, a backslash or a control byte) measured 15 % slower in two interleaved A/B runs (243–251 ms against 205–221
ms): the strings of these documents are short, so it was not kept. Class histograms after a full GC, before and after
the 81 queries: no class of cdb grew but the lambda forms of the first runs (16–176 B each) and the `JsonFns.Result`
constants; the heap after GC moved from 11.2 MB to 11.4 MB, class metadata.

## 4. Rejected

| Option | Why not |
|---|---|
| a binary form (jsonb) | parsing is 1.6 GB/s on the documents the first user writes; a binary form doubles the write path and the storage. Taken up when a measurement shows the scan dominates a query that matters |
| a tree (DOM) per value | allocates per row on the hot path; the scan answers in place |
| no type, functions over `VARCHAR` only (QuestDB's way) | no check at write, so bad payloads surface as NULLs at read; no type for clients to render |

## 5. Later

| Item | Condition |
|---|---|
| paths promoted to indexed virtual columns per partition | a query on the packet log that scans too long |
| a typed third argument to `json_extract` (QuestDB's `::double` form) | a caller that needs it; `::DOUBLE` on the answer does the same |
