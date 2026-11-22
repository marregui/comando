# frontear: the message fabric

![The frontear module: one node holds a broker, links with a log each, and a signed control plane](../images/mod-frontear-map.svg)

## In one paragraph

`frontear` is comando's own MQTT 5 fabric: a broker, a client, links that carry messages between brokers, and a
control plane that takes signed orders. comando's instances find each other and talk through it. This page is the
engineering view: the packages, the broker, a link and its log on disk, the control plane and the threads. To
run and configure a node, read [frontear](../frontear.md). The reasons are in
[ADR 0184](../adr/0184-frontear-comandos-own-mqtt-fabric.md).

## 1. The idea: publish, subscribe, forward

A client publishes a message to a topic, a path such as `plant/line1/temp`. Other clients subscribe to filters
such as `plant/#`. The broker hands each message to every subscriber whose filter matches. A link is a
subscriber too: it forwards what it receives to another broker.

> **Learn: publish and subscribe.** Think of a school notice board with labelled sections. A teacher pins a note
> under "sports"; every pupil who watches "sports" reads it. The teacher does not know who reads, and the pupils do
> not know who wrote. A broker is that board for programs. MQTT is the protocol they speak to it; a topic is the
> section; `+` matches one level of a topic and `#` matches all the rest. In frontear a subscriber can be a client on
> a TCP connection or code inside the node.

## 2. What it offers

`Frontear.start(node, port, dataDir, trusted keys…)` starts one node: the broker, the links and the control plane
together. `close()` stops the control plane, then the broker, then the links. `Frontear.main` runs a node from the
command line.

| Package | Role | Main classes |
|---|---|---|
| `frontear` | One node, started and stopped as a unit | `Frontear` |
| `gateway` | Connections, sessions, retained messages, routing | `Gateway`, `GatewayOptions`, `Connection`, `Session`, `InternalSubscriber`, `Gate` |
| `channels` | Which subscriber matches which topic | `TopicTree`, `Entry`, `Match` |
| `octopus` | Forwarding to and from a peer broker | `Links`, `Link`, `LinkSpec`, `LinkFile`, `LinkOptions`, `LinkState`, `Forwarding` |
| `store` | A link's log on disk | `LinkLog`, `LogForcer`, `LogOptions`, `LogFiles`, `WholeFile`, `Record` |
| `alexander` | Signed requests that change the links | `ControlPlane`, `ControlGate`, `Seal`, `Signer`, `Request`, `Refusal`, `StateTopic` |
| `protocol` | The wire format and a client | `Codec`, `Wire`, `Packet`, `MqttClient`, `Topics`, `PublishRecord` |

What the broker speaks, and what it declines:

| Speaks | Declines |
|---|---|
| QoS 0 and 1, retained messages, wills with a delay | QoS 2 |
| Session and message expiry, keep alive | Topic aliases, subscription identifiers |
| Receive Maximum, Maximum Packet Size, reason codes | Shared subscriptions |
| No Local, Retain As Published, Retain Handling | Enhanced authentication |
| Request and response, user properties, session takeover | User name and password |

A node listens on the loopback only, without TLS and without client authentication.

## 3. The broker

The broker keeps three things in memory: the sessions, the retained messages and the topic tree. Clients on TCP
and subscribers inside the node share all three.

- **Routing.** `TopicTree` stores the filters as a tree of topic levels. The tree is immutable: a subscribe builds
  a new one and swaps the root. A publish reads the root once and takes no lock.
- **Acknowledgement.** A QoS 1 publish gets its PUBACK once every internal subscriber it reached has finished.
  The PUBACK carries the worst of their reason codes.
- **Presence.** The broker retains `{"online":true}` on `$frontear/presence/<client-id>` and clears it when the
  connection ends.
- **The gate.** A `Gate` decides what a client may publish and subscribe to. Without one, `$frontear/` is closed
  to clients.
- **Bounds.** `GatewayOptions` caps everything that can grow: 1,000 sessions, 1,000 queued messages and 64 MiB per
  session, 10,000 retained messages, 128 topic levels. Past a bound the broker answers 0x97, quota exceeded.
- **Liveness.** A connection must send CONNECT within 10 s. Silence for 1.5 times its keep alive closes it.

> **Learn: copy-on-write.** Suppose a class shares one printed timetable. To change it, the teacher prints a new
> sheet and swaps it on the wall in one move. Nobody ever reads a half-edited timetable, and nobody waits for the
> teacher's pen. `TopicTree` works that way: readers follow one pointer to a finished tree. Subscribing costs a
> little copying, and publishing, far more frequent, costs no lock at all.

## 4. A link and its log

A link joins this node's broker to a peer's. It has two directions, each with its own topic filters.
`LinkSpec(id, host, port, out, in, hopLimit)` describes it.

![A message crossing a link: logged and forced before the publisher hears, trimmed after the peer acknowledges](../images/mod-frontear-link.svg)

**Out.** The link subscribes inside the broker as `$link/<id>`. Each message it receives joins the link's log
with its hop count raised by one. The broker hears success (0x00) only once the record is on disk. The sender
then reads from the log's cursor and publishes to the peer at QoS 1. The cursor moves past what the peer has
acknowledged.

**In.** The link subscribes on the peer with No Local, so its own messages do not come back. What arrives is
published on the local broker; the peer's PUBACK waits for that local publish.

| Event | What the link does |
|---|---|
| The log or the queue of 1,000 is full | Answers the broker 0x97. A QoS 0 message is dropped and counted. |
| The peer answers 0x97 or 0x89 | Resends that one message alone after a backoff, 1 s growing to 30 s. |
| The connection drops | Resends everything past the cursor, in order. The peer may see a message twice. |
| A message has reached its `hopLimit` | Does not forward it, so a ring of nodes cannot loop forever. |
| A put or a filter change | Writes the spec to `link.json` beside the log (`LinkFile`, version 1), forced, before it takes effect. |
| The link is removed | Deletes `link.json` first; keeps its log on disk. |
| The node starts | `Links` starts every link whose `link.json` it reads, in id order; one it cannot read is logged and skipped. |

The guarantee is **at least once**: no message acknowledged to its publisher is lost, and a duplicate is possible.
`LinkState` reports `connected`, `queued`, `cursor`, `tail`, `sent`, `dropped` and `reordered`.

**The log.** `LinkLog` is a folder of segment files named by their first offset, plus a `cursor` file.

| Piece | Format |
|---|---|
| Segment header | magic `FTLG`, version, the base offset |
| Frame | length, a CRC32C over offset, length and bytes, then the PUBLISH as MQTT 5 writes it |
| `cursor` | magic `FTCR`, version, the cursor, a CRC32C; replaced by a rename |
| Size | 64 MiB per segment and 1 GiB per link in a node (`Frontear.LOG_OPTIONS`) |

The link's strand never writes to the disk. It copies each record into a round in memory, at most 8 MiB. One
platform thread, `frontear-log-force-<folder>`, writes the round, forces it, completes the appends in order,
saves the cursor and deletes segments below it. Many messages share one force.

> **Learn: an append-only log.** Imagine a diary you only ever add lines to. Finding "where was I?" needs one
> bookmark. If a line is half written when the power fails, every earlier line is still whole. A link's log is such
> a diary: new messages go to the end, the cursor is the bookmark, and each frame's CRC exposes a half-written line.
> Old pages go when the bookmark has passed them. Writing only at the end is the fastest thing a disk does.

> **Learn: at least once.** A courier who gets no signature returns the next day with the same parcel. You may
> receive it twice, but never zero times. The other choice, "at most once", never repeats and sometimes loses.
> frontear links choose the courier's rule. A message leaves the log only after the peer signs for it with a PUBACK.

## 5. The control plane

You change a running node by publishing to it. A request is an MQTT message on
`$frontear/control/<node>/<Method>`, with a JSON body and a signature.

![A signed control request and the five checks before the node acts](../images/mod-frontear-control.svg)

| Method | Body | Does |
|---|---|---|
| `PutLink` | `{id, host, port, out, in, hopLimit}` | Adds a link or replaces it. |
| `RemoveLink` | `{id}` | Stops a link. |
| `SetFilters` | `{id, direction, filters}` | Changes one direction's filters. |
| `GetState` | `{}` | Answers the state of every link. |

The reply goes to the request's response topic: `{"ok":true}` or `{"ok":false,"reason":…,"detail":…}`. The node
also retains its links' state on `$frontear/state/<node>/links`, refreshed every second.

`Seal` defines the signature. It covers the topic, the time, the nonce, the content type, the response topic,
the correlation data and the payload. The node checks the nonce last, so a forged message cannot use up a real
one's nonce. `ControlGate` lets clients publish under `$frontear/` only to `$frontear/control/`.

| Refusal | Means |
|---|---|
| `unsigned`, `malformed` | The seal is missing or broken. |
| `unknown-key` | The node does not trust the signer. |
| `stale` | The time is outside the 30 s window, or before the node started. |
| `bad-signature` | The message changed after signing, or another key signed it. |
| `replayed` | The node has seen this nonce. |
| `busy` | 1,000 requests or 16 MiB are pending, one sender holds a quarter of either, or 10,000 nonces are remembered. |
| `unknown-method`, `bad-request`, `failed` | The method or the body is wrong, or the change failed. |

> **Learn: a digital signature.** You hold two matching keys: a private one you keep, a public one you hand out.
> With the private key you compute a short code from a message. Anyone with the public key can check that the code
> fits the message, but cannot make one. Change a single letter and the check fails. frontear signs with Ed25519 and
> trusts a list of public keys. A timestamp and a random number used once, the nonce, stop a recorded message from
> working a second time.

## 6. Threads

Everything runs on strands, virtual threads that each do one thing in order, made by `core`'s `Threads`. One
kind of thread is a platform thread: the log forcer, because a force blocks in the operating system.

| Thread | Work |
|---|---|
| `frontear-broker-accept-<port>` | Accepts connections. |
| `frontear-broker-reader-<client>`, `-writer-<client>` | One pair per client connection. |
| `frontear-broker-publish-<port>` | Runs publishes that start inside the node. |
| `frontear-broker-sweep-<port>` | Expires sessions, messages and wills. |
| `link-<id>` | Every step of one link; the only thread on its log's state. |
| `mqtt-<client id>`, `mqtt-writer-<client id>` | A client's reader and writer. |
| `frontear-control-<node>` | Verifies and runs control requests. |
| `frontear-log-force-<folder>` | The platform thread: writes and forces one link's log. |

## 7. Dependencies allowed

- `frontear` depends on the JDK and on `core`, of which it uses three packages: `loop`, `json` and `files`.
- `app` depends on `frontear`: it can host a node, and its peer client is `frontear.protocol`'s `MqttClient`.
- Test fixtures (`FakeGateway`, `Recorder`, `Relay`) are published for `app`'s tests.

## 8. Tests

Run them with `./gradlew :frontear:test`.

| Tests | Prove |
|---|---|
| `mqtt/` `CodecTest`, `CodecCorpusTest`, `WireTest`, `MqttClientTest` | Every packet encodes and decodes; the client's flows. |
| `broker/` `GatewayProtocolTest`, `GatewayBoundsTest`, `GatewayRaceTest` | Sessions that outlive a connection, every bound, a drop and an immediate reconnect. |
| `tree/TopicTreeTest` | Matching with `+` and `#`. |
| `queue/LinkLogCrashTest` | A cut at every file operation: a kill, a kill mid-write, a power cut. |
| `link/ForwardingTest`, `LinksTest`, `LinkRefusalBackoffTest` | Hop counts and expiry, order and no loss across restarts, the backoff on a refusal. |
| `control/SealTest`, `ControlPlaneTest`, `ControlGateTest` | The seal's checks, the methods, what the gate lets a client do. |
| `sim/` `LineSimTest`, `RingSimTest`, `ChainSimTest`, `ShortcutSimTest` | Real nodes on the loopback behind faulty relays; a ledger checks loss, order and hops. |

A simulation prints its two seeds. Replay it with `COMANDO_SIM_SEED0`, `COMANDO_SIM_SEED1` and
`COMANDO_SIM_RUNS=1`. `COMANDO_SOAK=true` runs ten times as many. `ClientInteropTest` runs against another
broker when you set `COMANDO_MQTT_BROKER=host:port`.

## Where next

- [frontear](../frontear.md): run a node, link two nodes, sign a request.
- [app](app.md): how comando hosts a node and finds its peers.
- [core](core.md): strands and the thread rules frontear follows.
- [cdb](cdb.md): comando's other log on disk, the write-ahead log.
- [Architecture](../architecture.md): the fabric among comando's instances.
