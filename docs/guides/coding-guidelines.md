# Coding guidelines

comando follows the author's high-throughput Java guidelines where a path is hot or a resource is owned. Rule identifiers (`G-<section>.<n>`) refer to that document and are cited in reviews. Most of comando is UI, orchestration and text shown once, which those guidelines exempt or do not target; the tables below say which rules apply where, so that code not following an inapplicable rule is not a review finding.

## 1. Where the rules apply

| Area | Rules | As built |
|---|---|---|
| Result fetch and storage | G-1.1, G-1.3, G-1.8, G-3.1 | rows stored by column in primitive chunks (`ColumnarResult`); no boxed value and no row object per cell |
| Grid, plot and transcript painting | G-2.1, G-2.3, G-2.4, G-3.4 | cells format into one reused buffer (`Formats`); the plot reduces each pixel column to min and max; the transcript keeps layouts and paints only the clip |
| Ownership: processes, connections, statements, the MCP server | G-4.1 to G-4.6, G-5.6 | idempotent `close()` safe on half-built objects; `App` rolls back a failed construction; one ownership tree ([Ownership](../architecture/04-ownership.md)) |
| Concurrency: reducer queue, lanes, transcripts, MCP waits | G-7.2, G-7.6, G-7.7, G-7.8, G-7.9 | release and acquire publication (`Transcript`, `ColumnarResult`); every wait bounded ([Threads](../architecture/02-threads.md)); a closed flag plus wake-up in the state loop |
| Errors and logging | G-5.2, G-5.3, G-6.1, G-6.2 | messages as `verb phrase [key=value]`; errors from external input are explicit exceptions with the input named |
| Configuration and platform seams | G-9.1, G-9.2, G-9.5 | the clock, the database, Claude, the MCP gateway, tools and the UI behind ports in `core.port` |
| Style | G-10.1 to G-10.8 | `final` where it applies; comments say why; no streams in per-row, per-frame or per-line paths |
| Tests | G-11.1, G-11.2, G-11.3 | AssertJ only; fault injection through port fakes (`RecordingPorts`, fake CLI scripts) |

## 2. Not applied, on purpose

G-3.3 object pools, G-4.7 off-heap memory, G-7.1 ring queues, G-7.3 cache-line padding, G-7.4 and G-7.5 worker pools, G-8.1 and G-8.5 pooled resumable contexts. comando has no sustained multi-producer hot path; its event rates are UI rates. Swing, JDBC and Jackson types cross module boundaries as they are.

## 3. Differences from the plan

| Plan | Built | Reason |
|---|---|---|
| QuestDB's `Log` chain ending in `$()` | `System.Logger` | [decision 17](../adr/0017-system-logger-instead-of-questdb-s-logger.md) |
| an incremental stream-JSON parser (G-8.4) | Jackson `readTree` per line | lines arrive at most a few hundred per second; a turn's cost is the model, not the parse |
| JetBrains annotations | none in main code | nullability is carried by records' constructors and `Optional` returns |
| no streams outside setup | streams in the reducer and adapters over small lists | the lists are panels, sessions and views, tens of items; hot paths have none |

## 4. Project rules

1. A change to state is an intent; the reducer is the only writer of `AppState`.
2. Every thread a part starts ends when the part closes, or is a daemon.
3. Every wait has a bound and an action on expiry.
4. Nothing in `ui` imports an adapter module; nothing in a widget imports another widget ([UI packages](../design/ui-packages.md)).
5. Tests never touch the user's data root, keychain or window preferences unless a flag says so.
6. Code and comments name no tool, agent or review finding; they say what the code does and why.
7. Compiler warnings are errors.
