# 193. a link's queue in front of its log is bounded by how long its oldest message has waited

| Date | Status | Decided by |
|---|---|---|
| 2026-11-22 | accepted; built in task 234, §3 extended in task 236; changes the link queue bound of [record 184](0184-frontear-comandos-own-mqtt-octopus-network.md) | Miguel and Claude |

## 1. Context

A link's messages wait for its strand to append them to the log in a queue ([frontear §6](../frontear.md#6-how-a-link-carries-a-message)). The
queue held at most 1,000 messages, under one publisher's Receive Maximum of 1,024. Four publishers with 512 messages
in flight each fill it within one append batch. In task 232's load rounds, alice's link to bob answered 283,080
publishes `0x97` at 80,000 msg/s. `FrontearStress` (two nodes in one JVM, 128-byte payloads, 4 publishers × 512,
30 s steps) refused 2,960 messages at 40,000 msg/s and 13,514 by the end of the 80,000 msg/s step, and the queue's
peak read 1000/1000 in every 10 s interval. A refused publisher backs off and sends again, so a burst the strand
would have appended within a millisecond cost a retry instead.

A count fits one message size and one rate. At 80,000 msg/s, 1,000 messages are 12.5 ms of traffic; at 500 msg/s
they are two seconds. The bound that matters to a publisher is how long its message may wait before the link
refuses to take more.

## 2. Decision

| Question | Rule |
|---|---|
| the wait bound | a message that finds the queue's oldest message waiting longer than `maxQueueWait` (default 50 ms) is refused `0x97` (QoS 0: dropped). The queue then holds what arrives in 50 ms at the current rate: about 4,000 messages at 80,000 msg/s, 25 at 500 msg/s |
| the weight bound | at most `maxQueueBytes` (default 8 MiB, one round of the link log) of the messages' weight (`Packet.Publish.weight`). A message heavier than the bound alone is taken when nothing else waits |
| the count bound | `queueMax`, raised from 1,000 to 16,384: a memory guard behind the two above, 200 ms at 80,000 msg/s. The peer's QoS 0 messages that wait for the strand keep their bound of 1,000 (at most `queueMax`): they have no weight or wait bound |
| the append batch | the strand appends at most 1,000 deliveries a task, the old bound, so its other steps run between |
| what a refusal says | the same as before: `0x97`, counted in `refused` (QoS 1) or `dropped` (QoS 0); the debug log names the bound that refused |

Why 50 ms: the p99 at 60,000 msg/s, the highest rate the live link kept up with in task 232, was 30.7 ms. A strand
50 ms behind has fallen behind that.

## 3. Measured

`FrontearStress --minutes 0.5 --rate 40000,60000,80000,100000 --publishers 4 --window 512 --payload 128`, on an Apple
M2 Max with 12 cores, during other sessions' builds. Back to back, before and after:

| | before | after |
|---|---|---|
| `0x97` up to the end of the 80,000 msg/s step | 13,514 | 182, all in the warm-up |
| queue peak, per 10 s | 1,000 (the bound) in every interval | 1,121 to 1,942 |

A second run after the change, at a load average of 14 on 12 cores, refused 8,128 messages in the warm-up: a strand
starved of CPU passes the wait bound too, as it passed the count bound before. Above about 80,000 msg/s the link then fell behind. The strand was not the cause: it used about a third of a
core, and the link's whole encode work per message took 580 ns, single-threaded. The link's client wrote every
packet as its own write to the socket, and its writer's queue stood at the window, 1,000, in every interval. Task
236 buffered the client's writes, flushed when no queued write waits, as the gateway's connections already did:
`FrontearStress --rate 60000,80000,100000,120000,150000`, same shape, at a load average of 6 to 19 on 12 cores, kept
up to 120,000 msg/s (p50 8.3 ms and p99 47.7 ms at 80,000, against 216 to 289 ms and 537 to 646 ms before) and fell
behind at 150,000 (121,712 delivered). The same task made string encoding one pass and an all-ASCII string one array copy: the link's
per-message encode work, timed single-threaded over 2,000,000 messages a round, went from 579 to 557 ns (4%). The
buffered writes are pinned by the client's tests: a ping, a PUBACK and a QoS 0 publish each reach a gateway on a
quiet connection, so the last write of a burst is flushed.

The same task cut the link path's allocation (task 232's R2: alice's link to bob allocated 151 MB/s at 60,000
msg/s). JFR (`settings=profile`) over the same three `FrontearStress` runs, read with `jfrq alloc`; bytes divided by
the messages the link sent. jfrq weighs a virtual thread's samples by its carrier's allocation, which inflates the
absolute figures, the same way in every run:

| Run | JVM, per message | link strand | gateway readers |
|---|---|---|---|
| before | 14,623 B | 4,774 B | 5,086 B |
| `Packet`'s checks build their message only on a refusal | 11,685 B | 3,860 B | 3,928 B |
| and: the log takes the record the link encoded without a copy, an empty property section decodes to one shared empty set, user properties are walked by index | 10,291 B | 3,193 B | 3,419 B |

`CodecAllocationTest` pins the first: a PUBLISH allocates 48 B and a PUBACK 24 B with their values valid, measured
by the thread's allocation counter.

## 4. Consequences

| What | Effect |
|---|---|
| a burst the strand keeps up with | taken, whatever its count, up to the memory guard |
| a strand that stalls | refuses within 50 ms of the stall's start, at any rate, where a count bound refused after a number of messages that depended on the rate |
| memory | the gateway's messages: at most 16,384 or 8 MiB a link, against 1,000 before. The peer's QoS 0 messages: 1,000, as before. The queue's array is 64 KiB a link |
| Health | "log full" still reads the count against `queueMax`; a refusal by wait or weight marks the link "dropping" for a minute, as a dropped message does |

## 5. Alternatives not taken

| Alternative | Why not |
|---|---|
| a larger count only | fits one rate. At a low rate it lets messages wait seconds before any refusal, and at a high rate it still refuses bursts |
| a queue sized from a measured rate (rate × target wait) | the same bound by another name, with a rate estimate to keep and tune. The wait of the oldest message measures it directly |
| no bound, with the gateway's Receive Maximum as the only limit | a thousand sessions at 1,024 messages in flight each is a million messages a link could be asked to hold |
| making the publisher wait instead of refusing | MQTT has no way to slow a publisher except to hold its acknowledgements. Those are already held until the record is durable, so the publisher's own window is that wait |
