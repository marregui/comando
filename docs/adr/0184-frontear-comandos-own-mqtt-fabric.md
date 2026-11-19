# 184. frontear: comando's own MQTT 5 broker, mesh link and signed control

| Date | Status | Decided by |
|---|---|---|
| 2026-11-19 | proposed; v1 being built in task 200 | Miguel and Claude |

## 1. Context

comando instances talk to each other over MQTT 5 ([record 123](0123-instances-consult-each-other.md)):
`MqttPeerBroker` connects to the comando Edge, a separate broker process that `EmbeddedEdge` downloads and runs.
comando already speaks MQTT 5 with its own client and codec ([record 157](0157-comando-owns-its-json-mqtt-diff-completion-and-icons.md)).
What it does not own is the broker in the middle, nor a way for several brokers to form a mesh, nor a way to change a
mesh at run time other than by editing a broker's configuration.

Miguel, 2026-11-19: a broker of comando's own, from scratch, with no code dependency on any HiveMQ product and as
few third-party libraries as possible; the smallest MQTT 5 that comando's use needs, without breaking the
specification; configuration only by MQTT messages, signed, from a control pod; one link per peer with one internal
subscriber per link and one append-only log per link; production quality, tested for parity against an external
broker; then optimised for throughput and latency. Not a UI, REST, config file, adapter, data transformation or
persisted session.

## 2. Decision

### 2.1 One module, JDK and `core` only

`frontear` (Gradle `:frontear`) holds the MQTT 5 client and codec (moved from `app.mqtt`), the broker, the topic tree,
the link log, the links and the control plane; `app` depends on it. Its main code depends on the JDK and `core`
alone; its tests on JUnit, AssertJ, Awaitility and `testkit`.

### 2.2 The MQTT 5 it speaks

Supported: CONNECT to DISCONNECT, QoS 0 and 1, retained messages, wills with delay, session and message expiry,
keep alive, receive maximum and maximum packet size both ways, `+` and `#`, No Local, Retain As Published, Retain
Handling, request and response, user properties, content type and payload format passed unchanged, a reason code on
every acknowledgement, session takeover. Declined in CONNACK, as the specification allows: QoS 2, topic aliases,
subscription identifiers, shared subscriptions, enhanced authentication, user name and password.

### 2.3 Links: one internal subscriber and one log per peer

A link registers all its outgoing filters as one internal subscriber, `$link/<id>`, so a message that matches several
of them is queued once. Each queued message is appended to the link's own log (segments, a CRC per record, a forced
cursor) before the publisher's PUBACK; one sender per link drains the log in order within the peer's receive maximum,
and advances the cursor on the peer's PUBACK. Order on a link is kept across losses, reconnects and restarts; it breaks
only when the peer refuses a message for a while (0x97, 0x89) and accepts the ones after it: the link then resends
the refused message alone, at a window of 1, and counts it in `reordered`. Nothing is shared between links. Loops are stopped by No Local, by the
link never sending back what came in through it, and by a hop count in a user property. Each link connects as
`<node>~<link>`.

### 2.4 Configuration is a signed control message

`$frontear/control/<node>/<Method>`, JSON, signed with Ed25519 over the topic, a timestamp, a nonce, the content type,
the response topic, the correlation data and the payload; accepted within 30 s of the node's clock and once per nonce.
Methods: `PutLink`, `RemoveLink`, `SetFilters` (whole set per direction, last message wins), `GetState`. Presence
(`$frontear/presence/<client>`) and state (`$frontear/state/<node>/links`) are written by the node alone.

### 2.5 A node is a process or a part of comando

`Frontear.main <node> <port> <dataDir> <key>...` runs one node on loopback; `Frontear.start` runs it inside a host.
v1 binds loopback only: it has no TLS and no client authentication.

## 3. Consequences

- comando can run its instances' broker in process: `-Dcomando.fabric=frontear` puts a frontear node, hosted by the
  first instance, where the comando Edge (`EmbeddedEdge`) is; the Edge stays the default until frontear is accepted.
  Parity with the pinned HiveMQ Edge release is tested by `FrontearParityTest` (`COMANDO_PARITY=true`): 31 scenarios,
  19 the same, 12 differing only where MQTT 5 allows it and section 2.2 declares it.
- At-least-once over a link: a message queued on a link may arrive twice after a reconnect; receivers deduplicate by
  correlation data.
- A durable append costs one file force (about 4 ms on an Apple SSD, measured in task 200); throughput comes from
  grouping appends behind a force. An append only copies the record into the log's in-memory round (at most
  `LogOptions.maxRoundBytes`, 8 MiB by default, usage in `LinkLog.roundBytes()`; past it the append is refused and the
  publisher gets 0x97); the log's forcer thread writes the round's frames, forces them, then acknowledges, so the
  link's thread never blocks on the file (on APFS a write waits for a running force of the same file). A failed write
  poisons the log, as a failed force does.
- The design is the shape of a larger streaming layer at small scale: what is learned here (one internal subscriber
  per link, one log per link, in-band signed configuration) is meant to carry over.
