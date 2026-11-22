# 191. frontear link logs store a record as the bytes it differs by from the one before

| Date | Status | Decided by |
|---|---|---|
| 2026-11-22 | accepted; built in task 232; changes the link log's segment format of [record 184](0184-frontear-comandos-own-mqtt-octopus-network.md) to version 2 | Miguel and Claude |

## 1. Context

A link's log ([frontear §2.4](../frontear.md#24-the-store-link-logs)) kept each record whole: a frame of a u32
length and a u32 CRC32C, then the PUBLISH as MQTT 5 writes it. A message a link queues with a 128-byte payload on a
35-character topic took 234 bytes on disk, 71 over its payload and topic:

| Bytes | What |
|---|---|
| 8 | the frame: length and CRC |
| 3 | the PUBLISH's fixed header and remaining length |
| 37 | the topic and its length |
| 2 | the packet id, always 1 in a link's log |
| 56 | the properties: their length, `frontear-hops=1` (19) and `frontear-queued-ms` with 13 digits (36) |
| 128 | the payload |

Every record of a link repeats the topic, the packet id, the property names and most of the queue time's digits. The
goal set for task 232: at most 40 bytes over payload and topic for that shape, crash safety as before, a versioned
format whose reader reads the logs written before it with no migration step, and no loss of throughput.

## 2. Decision

| Question | Rule |
|---|---|
| version | a segment's header carries the format: version 2 is written, version 1 is still read; a log that ends at open on a version 1 segment appends to it in version 1 (records whole), and every segment it starts is version 2. No file is rewritten |
| a delta frame | in a version 2 segment, a length field with its top bit set (`0x8000_0000`) marks a delta: the frame's bytes are the record as it differs from the record before it in the same segment. A length without that bit is a record whole, a frame byte for byte as version 1 writes it |
| the delta | the record's length, then at most 8 steps; a step is where a run copied from the previous record starts there, how long it is, then how many of the record's own bytes follow, and those bytes; every number an unsigned LEB128 varint. After a difference the encoder looks up to 8 bytes ahead, the copy moved by up to 3 bytes either way, for a run of 4 or more bytes in common, so a remaining length that grew by a byte or a changed digit costs a short run of own bytes. The store knows nothing of MQTT: any record a link's records resemble is stored small |
| when a record is whole | when a delta would save fewer than 16 bytes; a segment's first record and every 64th after it; the first record after an open; every record in a version 1 segment. A read that starts at any offset decodes at most 63 records before its first, and a read that goes on from the last one decodes none |
| crash safety | the CRC covers the record's offset, the length field with its delta bit, and the frame's bytes, as before; the scan at open ends at the first frame short, past the end or failing its CRC, and cuts it off. A delta frame whose CRC holds but which does not decode against the record before it fails the read as a corrupt frame |
| the round's bound | `maxRoundBytes` and `roundBytes()` count each waiting append as its record's frame written whole, what the round holds in memory; `maxBytes` and `bytes()` count the frames as written |
| cursor | unchanged, its own version 1 |

Measured by `LinkLogFormatTest` (10,000 such records, random payloads, 1 MiB segments, so a roll's whole records
count): 143.5 bytes a record against 234, the bound 203. `FrontearStress --minutes 1 --rate 20000` on an Apple M2 Max
shared with other builds, before and after: delivered 20,011 and 20,012 messages a second of 20,000 offered, p99
847.25 ms and 713.03 ms, every message arrived (1,400,000 of 1,400,000) both times.

## 3. Consequences

| What | Effect |
|---|---|
| a downgrade | a comando from before this record reads a version 2 segment's header as one that does not follow, ends the log there and deletes that segment and every later one: the messages a link had queued in them are lost. The format is one-way once a link has started a segment after the upgrade, as cdb's store format is ([record 161](0161-cdb-write-ahead-log.md)), but without cdb's loud refusal, which the older reader does not have |
| reading a record with another tool | a record stored whole is still a PUBLISH any MQTT tool reads; a delta is not, and is read through `LinkLog` |
| CPU | an append encodes its record against the one before, under the log's lock; a read from a cold offset decodes up to 63 records it does not return |

## 4. Alternatives not taken

| Alternative | Why not |
|---|---|
| a table of topics and property names in the store | the store would parse MQTT and depend on `protocol`, and a record would be decoded and written again on every append; the delta saves the same bytes and needs no knowledge of the record |
| a common prefix only | the remaining length, three bytes in, changes with the payload's size, and the queue time changes in the middle: little in common is left past either |
| a general compressor (`Deflater`) per record | what repeats is across records, not inside one: a record of 226 bytes with a random payload leaves a compressor little to find, and a dictionary primed with the record before is a delta by another name, at a compressor's cost |
| compressing a whole round | a read from any offset would decompress the round, and a torn tail would lose the whole round, not one frame |
| dropping the queue time or the hop count from the record | the record is the PUBLISH `PublishRecord` writes, owned by the protocol; the link reads the queue time back when it sends, to decrement the message's expiry, and the hop count travels on with the message |
