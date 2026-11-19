# frontear

frontear is comando's own MQTT 5 broker, the links that join several brokers into a mesh, and the signed control
plane that changes those links at run time. This page is the reference: how to run a node, the control messages, the
state a node publishes, the files a link keeps and the bounds that apply. Why it is built this way is in
[record 184](adr/0184-frontear-comandos-own-mqtt-fabric.md).

## 1. Running a node

### 1.1 Inside comando

`-Dcomando.fabric=frontear` replaces the comando Edge with a frontear node hosted in the first instance's process, on
the Edge's MQTT port. The node's link logs and its control key pair are in `fabric/` in that instance's data root
([architecture §15](architecture.md#15-the-app-module)). The comando Edge stays the default until frontear is
accepted.

### 1.2 As a process

```
frontear <node> <port> <dataDir> <trusted-key-file>...
```

| Argument | Meaning |
|---|---|
| `node` | The node's name: one topic level, with no `/`, `+`, `#` or NUL. Control topics and the state topic carry it. |
| `port` | The loopback port; 0 picks a free one. |
| `dataDir` | The folder holding one log folder per link, named by the link's id. |
| `trusted-key-file` | One or more files, each holding a trusted Ed25519 public key as the base64 of its X.509 encoding. |

The node prints `frontear serving [node=…, port=…]` and runs until the JVM ends. On a shutdown signal it stops taking
control messages, then closes the broker, then closes the links, whose logs force their last records. The close is
given 10 s. With fewer than four arguments the process exits with code 2.

v1 binds loopback only. It has no TLS and no client authentication.

## 2. The MQTT 5 it speaks

| | |
|---|---|
| Supported | CONNECT to DISCONNECT, QoS 0 and 1, retained messages, wills with delay, session and message expiry, keep alive, receive maximum and maximum packet size in both directions, `+` and `#`, No Local, Retain As Published, Retain Handling, request and response, user properties, content type and payload format indicator passed on unchanged, a reason code on every acknowledgement, session takeover |
| Declined in CONNACK, as the specification allows | QoS 2, topic aliases, subscription identifiers, shared subscriptions, enhanced authentication, user name and password |

Topics under `$frontear/` are reserved: a client may publish there only to `$frontear/control/` (§4), where the
signature decides. A client may subscribe to any filter, so it can read presence and state (§5).

## 3. Links

A link joins this node's broker to one peer broker. A link with id `<id>` connects to its peer as the client
`<node>~<id>`.

| Direction | What it carries |
|---|---|
| `out` | Messages published on this broker that match any of the link's out filters. They are registered as one internal subscriber, `$link/<id>`, so a message matching several filters is queued once. Each is appended to the link's log before the publisher's PUBACK, then sent to the peer in order. |
| `in` | Messages the link subscribes to on the peer with its in filters. Each is published on this broker as coming from the link. |

**Order and delivery.** Delivery over a link is at least once: after a reconnect or a restart, the records after the
last saved cursor are sent again, so a receiver must deduplicate, by correlation data for example. Order on a link
holds across losses, reconnects and restarts, with one exception. When the peer refuses a message for now (reason
code 0x97 or 0x89) and accepts later messages of the same window, the link resends the refused message alone, at a
window of 1, and counts it in `reordered`.

**Loops** are stopped in three ways:
- the link subscribes with No Local;
- it never sends back what came in through it;
- each message carries a hop count in the user property `frontear-hops`, a decimal number that each link increments
  as it carries the message. A message whose count has reached the link's `hopLimit`, or whose count does not read as
  a number, is not carried. It is counted in `dropped`, and the publisher still gets a success.

**Expiry.** While a message waits in a link's log it carries `frontear-queued-ms`, the epoch millisecond it was
queued. Its message expiry is reduced by the time it waited, after a restart too. The property is never sent on to
the peer.

**Reconnect.** A lost connection is retried after 1 s, doubling after each failure up to 30 s, with up to a fifth of
each wait taken off at random, so links cut at the same moment do not reconnect together.

## 4. Control messages

### 4.1 Topic and form

A request is published, QoS 1, to `$frontear/control/<node>/<Method>`. Its payload is a JSON object, and its content
type must be `application/json`. The method name versions the body: a body that changes shape becomes a new method.
A node therefore refuses an unknown method and an unknown property, rather than ignore part of a request.

| Method | Body | Effect |
|---|---|---|
| `PutLink` | `{"id", "host", "port", "out", "in", "hopLimit"}` | Adds the link, or replaces the link with the same id. `port` is 1 to 65535, `hopLimit` at least 1, and `out` and `in` are arrays of topic filters, each checked as a SUBSCRIBE would check it. |
| `RemoveLink` | `{"id"}` | Removes the link. |
| `SetFilters` | `{"id", "direction", "filters"}` | Replaces the link's whole filter set for one direction, `"OUT"` or `"IN"`; the last message wins. |
| `GetState` | `{}` | Replies with the links' state (§5.2). |

Example, a link to a peer on port 1884 carrying `jobs/#` out and `results/#` in:

```json
{"id": "b", "host": "127.0.0.1", "port": 1884, "out": ["jobs/#"], "in": ["results/#"], "hopLimit": 4}
```

### 4.2 Signature

Every request carries four user properties:

| Property | Value |
|---|---|
| `frontear-key` | The signer's key id: the lower-case hex of the SHA-256 of the public key's X.509 encoding. |
| `frontear-ts` | The signing time, epoch milliseconds, in decimal. |
| `frontear-nonce` | 16 random bytes as 32 lower-case hex digits. |
| `frontear-sig` | The base64 of the Ed25519 signature. |

The signature covers these bytes, in this order:
1. the UTF-8 of the topic, the ts as written, the nonce, the content type and the response topic (each empty when
   absent), each followed by a 0x00 byte;
2. the correlation data, as a 2-byte big-endian length and its bytes (length 0 when absent);
3. the payload.

The response topic is signed too, so a copy of a request cannot be answered elsewhere. In Java, `Signer.sign` builds
a signed message.

A node accepts a request when all of these hold:
- the key id names a trusted key;
- the ts is within 30 s of the node's clock, and not earlier than the node's start;
- the signature verifies;
- the nonce has not been seen within the last 30 s.

Nonces are kept in memory only. That is why a request signed before the node started is refused: the node can't tell
whether a run before its restart already accepted it. A signer whose clock runs behind the node's has its requests
refused for that long after the node starts.

### 4.3 Replies and refusals

A request with a response topic gets a reply there, QoS 1, with its correlation data unchanged:

| Outcome | Reply |
|---|---|
| Applied | `{"ok":true}` |
| `GetState` | `{"ok":true,"links":[…]}`, the links as in §5.2 |
| Refused | `{"ok":false,"reason":"<code>","detail":"<text>"}` |

A response topic under `$frontear/` gets no reply. Every refusal is counted, and the PUBACK to the publisher carries
its reason code:

| `reason` | When | PUBACK |
|---|---|---|
| `unsigned` | A signature property is missing. | 0x87 |
| `malformed` | A signature property repeats or does not read: a ts not in decimal, a nonce not 32 hex digits, bad base64. | 0x87 |
| `unknown-key` | The key id names no trusted key. | 0x87 |
| `stale` | The ts is more than 30 s from the node's clock, or earlier than the node's start. | 0x87 |
| `bad-signature` | The signature does not verify. | 0x87 |
| `replayed` | The nonce was seen within the window. | 0x87 |
| `busy` | Too many requests or bytes are waiting, in all or from this client, or too many nonces are remembered. No reply is sent. | 0x97 |
| `unknown-method` | The topic names no method. | 0x90 |
| `bad-request` | The body is not the method's JSON object, or the content type is not `application/json`. | 0x99 |
| `failed` | The mesh refused the change. | 0x83 |

A request is applied at most once. A QoS 1 request that the client sends again carries a nonce already seen and is
refused as `replayed`. The PUBACK goes once the request has been handled.

## 5. What a node publishes

### 5.1 Presence

`$frontear/presence/<clientId>`: the retained message `{"online":true}` while the client is connected. Only the node
writes it.

### 5.2 Link state

`$frontear/state/<node>/links`, retained, published when the links changed and at most once a second:

```json
{"links":[{"id":"b","connected":true,"queued":0,"cursor":1520,"tail":1520,"sent":1520,"dropped":0,"reordered":0}]}
```

| Field | Meaning |
|---|---|
| `connected` | Whether the peer accepted the link's connection. |
| `queued` | The messages in the link's log not yet acknowledged by the peer: `tail − cursor`. |
| `cursor` | The offset of the next message to deliver. |
| `tail` | The offset the next append takes. |
| `sent` | The messages sent to the peer since the link started. |
| `dropped` | The messages not carried since the link started: refused by the peer for good, expired, past the hop limit, or QoS 0 messages over the link's queue bound. |
| `reordered` | The messages the peer refused for now and took only after a later one (§3). |

The counters live in memory and start at 0 when the link starts.

## 6. The link log

Each link has its own log in `<dataDir>/<id>/`. Nothing is shared between links. All numbers are little-endian.

| File | Content |
|---|---|
| `<base>.log` | A segment, named by its base offset in 20 digits. A 16-byte header (u32 magic `FTLG`, u32 version 1, u64 base offset), then one frame per record: u32 length, u32 CRC32C, the record's bytes. The CRC covers the record's offset (u64), its length (u32) and its bytes. |
| `cursor` | u32 magic `FTCR`, u32 version 1, u64 cursor, u32 CRC32C of the 16 bytes before it. It is replaced whole: written to `cursor.tmp`, forced, moved over the old one, and the folder forced. |

Offsets have no gaps: a segment's base is the previous segment's base plus its record count. A new segment starts at
64 MiB, and a record larger than a segment gets one of its own.

**Durability.** An append copies the record into the log's in-memory round and returns. The log's forcer thread then
writes the round, forces it, and completes the appends in offset order. The publisher's PUBACK waits for that force.
The cursor is saved at most about once a second while appends keep the forcer busy. A failed write or force poisons
the log: every append it left in doubt fails.

**Recovery on open:**
1. The segments wholly below the saved cursor are deleted.
2. The remaining segments are scanned in offset order. The log ends at the first segment whose header is torn or
   whose base does not follow on, or at the first frame that is short or fails its CRC, which is cut off.
3. Every segment after that end is deleted.
4. What is kept is forced, with the folder, before the first append.

## 7. Bounds

| Bound | Default | Past it |
|---|---|---|
| A link log's bytes on disk | 1 GiB (`Frontear` node) | The append is refused and the publisher gets 0x97. |
| A link log's in-memory round | 8 MiB (`LogOptions.maxRoundBytes`) | The append is refused and the publisher gets 0x97. |
| Messages waiting for a link's thread | 1,000 (`LinkOptions.queueMax`) | A QoS 0 message is dropped and counted in `dropped`; a QoS 1 message gets 0x97. |
| Messages in flight to a peer | The peer's Receive Maximum, capped at the client's publish queue (`LinkOptions.windowMax`) | The link waits for acknowledgements. |
| Broker Receive Maximum | 1,024 | The client is disconnected with 0x93. |
| Broker maximum packet size | 16 MiB | The client is disconnected with 0x95. |
| Broker sessions | 1,000 | The CONNECT gets 0x97. |
| Retained messages | 10,000 and 64 MiB | The retained message is refused. |
| Control requests waiting | 1,000 and 16 MiB in all, a quarter of each per client | `busy` |
| Nonces remembered | 10,000 | `busy` |

## 8. Threads

Each component runs on its own named strand: one virtual thread that runs its tasks in order. Each link log has one
platform thread, `frontear-log-force-<dir>`, because a force blocks in the file system. The full table and the rules
are in [record 184 §2.6](adr/0184-frontear-comandos-own-mqtt-fabric.md#26-threads-one-strand-per-resource-one-platform-thread-per-log).
