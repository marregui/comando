# 187. The frontear lab: a node per instance, linked, and observed from a lower tab

| Date | Status | Decided by |
|---|---|---|
| 2026-11-21 | accepted; built in task 220; amended in task 221 (§2.1 a peer down, §2.2 the events, §2.3 persistence and agents); supersedes the one shared node of [record 185](0185-frontear-is-the-only-fabric.md) §2 | Miguel and Claude |

## 1. Context

Since [record 185](0185-frontear-is-the-only-fabric.md) one frontear node serves every instance on the machine. Its
links (octopus) and their logs (store) never run: no link exists, so nothing is queued, forwarded or forced. The
fabric's traffic is read only as `mqtt sub` output.

Miguel, 2026-11-21: "I am interested in the actual mqtt traffic. I sort of want to have frontear more integrated with
comando, possibly to explore channels, octopus, store, all the individual components and the traffic exchanged with
other instances, so that I can have a feel, observe the design working as I use it, and refine concepts in this
model, so that I can transfer them to the real deal which is edge."

His choices (form of 2026-11-21): a node per instance, linked; a lower tab named `frontear`; the packet log kept in
a memory ring and in cdb; bridge queue semantics shown first (Overview, Traffic, Octopus and Store views).

## 2. Decision

### 2.1 Topology: every instance hosts its own node

| Question | Rule |
|---|---|
| the node | every instance hosts a frontear node in its JVM, named after its slug (`Peers.slug`), data in `fabric/` of its data root, trusting its own pod key only; its peer link connects to it alone |
| the port | each slug keeps a port in the registry for good: the lowest port from 18883 up that no other slug holds and no process listens on, taken at the first start; a later start reuses it, and takes a new one only when another process holds it |
| the registry | `~/.comando-fabric.json`, schema version 2: `{"version":2,"nodes":{"<slug>":{"port","pid","started","name","owner"}}}`; `pid` 0 for a node that is down; read-modify-write under the lock of record 185; a version 1 file is replaced |
| the links | node X keeps one link per other slug Y in the registry, id `Y`, to `127.0.0.1:<Y's port>`, out filters `comando/v1/local/instance/Y/inbox`, `…/Y/reply` and `…/X/presence`, no in filters, hop limit 1: each node pushes to each peer what that peer reads, and nothing it got from a link goes further; the reconcile runs at every look of the peer link (5 s) through X's own control plane, signed with X's pod key |
| a peer down | its link stays with its port: it backs off, and the messages for that peer queue in its log; the peer's restart on the same port drains them, in order: the peer's own link connecting to this node (client `<peer>~<this>`) wakes this node's link to it at once, at most once a second, and a refusal `0x88` (a node that runs and holds its links) is tried again after the first backoff, so the drain waits neither on a backoff grown while the peer was down nor on one that grows while it holds |
| a peer forgotten | the Instances tab's forget removes the link (its log stays, record 184) and the slug's registry entry |
| a crashed peer | its last will dies with its node, so its retained presence stays on the others; a node whose registry entry names a process that is gone is reported as gone to `Peers`, which drops that peer from its table only when the presence it holds has the same `started`, so a presence of the restarted process is never dropped |
| stop by hand | `server_stop frontear` stops this instance's node only; the peer link is down until it starts again |
| tests | `-Dcomando.peers=false`: a registry in the data root, a port the OS picks, as before |

### 2.2 Observation: frontear records, it never waits

| Question | Rule |
|---|---|
| the events | one sealed type, `Observation`, in `dev.marregui.comando.frontear.watch`: a publish the gateway accepted (origin, topic, QoS, retain, bytes, matches, correlation data, and the payload, by reference up to 8 KiB, past it a copy of its first 8 KiB, the only copy made on the publisher's thread, so the ring holds at most 16,384 times 8 KiB: a publish's payload is never written after it is made; a reader decodes the head and the kept text on its own thread), and link steps (appended with offset, sent, arrived from the peer, dropped with reason, connected, disconnected), and store force rounds (records, bytes, nanoseconds) |
| the ring | a fixed array of 2^n slots written by sequence (`getAndIncrement`, then a release store): a writer never waits and never allocates beyond the event; the newest overwrite the oldest; a reader names the sequence it has read up to and learns how many it missed |
| off by default | the hooks cost one volatile read when nobody watches; `Frontear.watch()` turns them on |
| snapshots | `Frontear.snapshot()`: the broker's stats and connections, the links' states, each log's cursor, tail, bytes and segments; each value copied under the lock that guards it, held for the copy alone (two field reads), once a second |
| the cost | the stress harness's p99 with the ring on is within 5% of the p99 with it off, measured, method and machine stated in the task's report |

### 2.3 The lab

| Question | Rule |
|---|---|
| where | a lower tab `frontear`, beside Session, Approvals, Terminal and Results; views Overview, Traffic, Octopus and Store in this build; Gateway, Channels and Alexander later |
| how it reads | one virtual thread per instance drains the ring every 250 ms into a bounded window model (10 000 packets), shown with pause; the UI reads the model on the EDT |
| persistence | the same drain writes each packet to cdb table `frontear_packet` through a bounded queue; a full queue drops and counts, never waits; each publish with its payload as the watch keeps it (whole up to 8 KiB, else its first 8 KiB): in column `payload JSON` when it is kept whole and comando's JSON parser reads it as one document, else in `payload_text VARCHAR`, never both; the check runs on the sink's thread, not the drain's; kept 7 days (`TTL 7 DAYS`); the writer creates the table with both columns, then sends `ALTER TABLE frontear_packet ADD COLUMN IF NOT EXISTS` for each, once per server, so a table made before them gets them; a table that already has `payload` as VARCHAR, from an earlier write, keeps it: the ALTER is a no-op and its JSON payloads land as text, with no migration; a batch goes in requests of at most 4 Mi characters (cdb refuses a body over 16 MiB, a character is at most 3 bytes of UTF-8), and a line longer than that is dropped with a warning |
| agents | MCP tool `frontear_read {view, since, max, payload}` returns the same snapshot and events; traffic packets carry their payload (whole up to 8 KiB, else its first 8 KiB) only with `payload` true (default false), so an answer stays small |

## 3. Consequences

- Every message between instances crosses a link: it is queued in the store log, forced, forwarded and acknowledged,
  which is the path of an Edge bridge to a broker with its per-bridge queue.
- A question to a peer that is down waits in the link's log and is answered once the peer is back, where before it
  failed at once.
- N instances make N(N-1) links; at the handful of instances a machine runs this is a few dozen connections.
- An instance of an older comando reads a version 2 registry as unreadable and hosts nothing; mixed versions do not
  meet.
- A port a peer kept, taken meanwhile by another frontear node, does not take that peer's messages: a node started
  with the guard refuses a link client `<a>~<b>` whose `<b>` is not its name (CONNACK 0x85), and the link backs off.
  A process that speaks MQTT but is not frontear is not guarded.
