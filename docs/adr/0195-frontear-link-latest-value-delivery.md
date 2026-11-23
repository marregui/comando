# 195. a link sends only the newest retained message of each topic it holds for its peer

| Date | Status | Decided by |
|---|---|---|
| 2026-11-23 | accepted; built in task 232 without a record of its own, recorded and tested across restarts in task 221; amends the link backlog of [record 184](0184-frontear-comandos-own-mqtt-octopus-network.md) | Miguel and Claude |

## 1. Context

An instance publishes its state retained: its presence on `comando/v1/local/instance/<slug>/presence` and its
sessions' details on `…/sessions` beside it ([record 192](0192-presence-schema-2-and-a-sessions-topic.md)), each
time it changes, sessions first. Its questions and answers travel on `…/inbox` and `…/reply`, not retained. A link
logs every message under its `out` filters before it sends it ([frontear §6](../frontear.md#6-how-a-link-carries-a-message)),
so while its peer is down the log holds every presence the instance published since. The peer needs only the newest
of each retained topic: a retained message replaces the one before it on the peer's gateway at once. The inbox
messages between them it needs every one of, once and in order.

The link log is topic-blind: it stores records as the bytes each differs by from the one before
([record 191](0191-frontear-compact-link-log-records.md)), and a record's topic is known only once it is decoded.
The link above it decodes every record it sends and knows the topics.

## 2. Decision

| Question | Rule |
|---|---|
| where compaction sits | in the link, at send time; the log's records, segments and format do not change |
| what supersedes a message | a later retained message on the same topic in the link's backlog. The link keeps, per retained topic, the offset of its newest record that waits |
| what is never compacted | a message that is not retained (inbox, reply): each is sent, in log order |
| a superseded message | is not sent, wherever it waits; the cursor passes it as if acknowledged, and it is counted in `coalesced`; the watch detail reads `superseded by a newer retained message on its topic` |
| while the peer is down | a superseded message at the head of the backlog is passed at once, as the byte and age bounds pass the oldest, so the saved cursor moves past it |
| after a restart | the link reads the backlog it left, from the saved cursor, and learns each retained topic's newest record before it takes a message, so the same messages are superseded as before the restart |
| an acknowledgement | drops a topic's entry once the cursor passes its newest record |

## 3. Measured

`LinkLatestValueTest` publishes, with the peer down, presence p1, inbox message m1, presence p2, sessions s1 and
presence p3 on one link. The peer, once back, receives m1, s1 and p3, in that order, s1 and p3 retained, and neither
p1 nor p2: `sent` 3, `coalesced` 2, `dropped` 0. The same holds after a restart at each cursor the log stands at
(0 after one or two messages, 1 after three or four: p1 is passed as soon as p2 arrives), and m1, delivered before
a restart, is not sent again after it.

| Of the five messages | Records | Record bytes | Payload bytes |
|---|---|---|---|
| in the log | 5 | 1,068 | 558 |
| sent | 3 (m1 141 B, s1 195 B, p3 244 B) | 580 | 276 |
| not sent (p1, p2) | 2 | 488 (46%) | 282 (51%) |

Each message not sent saves a PUBLISH and its PUBACK. The log file grew 268, 399, 634, 746 and 908 bytes after each
append (a 16-byte header, then frames holding deltas).

## 4. Consequences

| What | Effect |
|---|---|
| the peer after a long outage | gets one presence and one sessions' detail per instance, not one per change: 2 of 5 messages, 488 of 1,068 record bytes, not sent in the test's backlog |
| inbox and reply | unchanged: at least once, in log order. A restart resends what follows the saved cursor; a close saves the cursor, so a clean restart resends nothing |
| the latest value | never superseded, so it is sent, unless the backlog's age or byte bound drops it while the peer is down (24 h and 64 MiB by default, counted in `dropped`, §6 of the frontear guide) |
| disk | not reclaimed per superseded record: a segment is deleted once it lies wholly below the saved cursor, as before |
| the backlog's byte bound | counts a superseded message until the cursor passes it: while the peer is down one behind an inbox message that waits stays counted |
| the hot path | one map entry per retained topic that waits and one lookup per retained record; a superseded record is still read and decoded before it is passed |

## 5. Alternatives not taken

| Alternative | Why not |
|---|---|
| rewrite sealed segments without superseded records, in the log | the log does not know topics, a record is a delta of the one before so dropping one rewrites the chain after it, and a rewrite would race the forcer and the cursor; the bytes it reclaims a segment's deletion reclaims anyway once the peer is back |
| skip a superseded record in the log's reader | moves the link's knowledge of topics and retain into the store, which serves any record, and gives two places that decide what is sent |
| replace the waiting message at append time | the log is append-only and its offsets are the cursor's; an in-place replace breaks both and a crash in it loses the newer value |
| compact every topic, not only retained | a non-retained message is an event, not a state: the peer needs each inbox question |
