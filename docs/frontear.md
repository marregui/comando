# frontear

![An octopus network: each comando instance's node is a gateway on 127.0.0.1, linked to every other node and configured through its control plane, alexander; main's node is the border gateway, linked over pinned TLS 1.3 to another machine's border gateway](images/ft-octopus-network.svg)

**In one paragraph.** frontear is an MQTT 5 server with links between servers. One node is a complete MQTT 5 server
for your clients, its *gateway*. A link joins two nodes and forwards the topics you choose, keeping them on disk until
the peer has them. Nodes joined by links make an *octopus network*. You add, change and remove links while the node
runs, with signed MQTT messages that alexander, the control plane, checks and applies. comando hosts a node in every
instance and joins them into one octopus network, and you can run nodes as plain processes.

## 1. What frontear is

A frontear node is a gateway with its links and their logs. You reach every part over MQTT.

| Part | What it does | You use it through |
|---|---|---|
| Gateway | Publish and subscribe for clients on `127.0.0.1`: QoS 0 and 1, retained messages, wills, sessions. | Any MQTT 5 client. |
| Links | Forward chosen topics to and from a peer node. Each link has its own log on disk. | The four control methods (§5). |
| alexander, the control plane | Applies signed requests. Publishes the state of the links. | `$frontear/control/…`, `$frontear/state/…` |
| Watch | Records what the node does, for whoever looks. It changes nothing. | comando's frontear tab and the MCP tool `frontear_read`. |

> **Learn: publish and subscribe.** In MQTT nobody sends a message to a person. A publisher sends it to a *topic*, a
> name such as `jobs/1`. A subscriber tells the server which topics it wants, and may use wildcards: `jobs/#` means
> everything under `jobs/`. The server in the middle does the matching, so publishers and subscribers never need to
> know each other. A frontear gateway is that server. A link is a subscriber that carries its matches to the next
> gateway.

Know these limits before you start:

- A node listens on `127.0.0.1`, without TLS or client authentication: any local process can connect. Inside
  comando, the node of the instance `main`, the *border gateway*, can also listen on one network address, TLS 1.3
  only, for the machines paired with this one (§9.1); nothing else reaches a node from another machine.
- Only links are on disk: their configuration and their logs. Sessions and retained messages live in memory (§8.3).
- QoS 2 is not supported (§11).

## 2. The components

frontear is eight packages, each one component of a node. This section says what each one is and does. The packages
and their classes are in [the frontear module page](modules/frontear.md).

| Component | Package | In one line |
|---|---|---|
| [The gateway](#21-the-gateway) | `gateway` | The MQTT 5 server of one node. |
| [Channels](#22-channels-the-topic-tree) | `channels` | Which subscriber a topic reaches. |
| [The octopus](#23-the-octopus-links-between-gateways) | `octopus` | The links between gateways. |
| [The store](#24-the-store-link-logs) | `store` | Each link's log on disk. |
| [alexander](#25-alexander-the-control-plane) | `alexander` | Signed orders that change the links. |
| [The watch](#26-the-watch) | `watch` | A read-only view of what the node does. |
| [TLS](#27-tls-between-machines) | `tls` | Pinned, mutual TLS 1.3 between machines. |
| [The protocol](#28-the-protocol) | `protocol` | MQTT 5 packets as bytes, and a client. |

### 2.1 The gateway

![The gateway: MQTT 5 clients on the loopback, the sessions and retained messages it keeps, the gate it asks, the links and alexander subscribed inside it, and on the border gateway the TLS listener for other machines](images/ft-gateway.svg)

The gateway is the MQTT 5 server each node runs. It accepts connections on `127.0.0.1`, keeps each client's session
and the retained messages in memory, and hands every publish to the subscribers whose filters match. Some subscribers
are clients on a TCP connection. Others live inside the node: each link, and alexander. A QoS 1 publish gets its
PUBACK once every subscriber inside the node that it reached has finished with it, and the PUBACK carries the worst of
their reason codes. That is how a link makes the publisher wait for the disk (§2.4).

The gateway also says who is connected. It retains `{"online":true}` on `$frontear/presence/<clientId>` while a client
is connected and clears it when the connection ends (§7.2).

A *gate* decides what each client may do: connect, publish to a topic, subscribe to a filter, retain a message. Topics
under `$frontear/` belong to the node. alexander installs its gate when the node starts, which opens only
`$frontear/control/` to clients (§2.5).

The **border gateway** is the gateway of the instance `main`. It alone can also listen on one network address you
name, never the wildcard address, for the machines paired with this one. That listener speaks TLS 1.3 only, asks every
client for its certificate (§2.7), and takes at most 64 connections, each with 10 s to finish its handshake. Its gate
lets another machine connect only as a link and publish only a few named topics (§2.5). Every message that arrives
over it has its `frontear-origin` user property replaced by `@<machine>`, the machine its certificate names, so a
receiver knows which machine sent it whatever the sender wrote.

> **Learn: a bound on every queue.** A queue between a fast producer and a slow consumer grows until memory runs out.
> A bound stops that: past a fixed size the queue says no, and the producer has to wait or give up. Saying no early is
> better than failing late, because the producer still knows what it sent. MQTT 5 has a reason code for that answer,
> `0x97`, quota exceeded. The gateway bounds every queue it holds, sessions, queued messages, retained messages,
> bytes waiting for a slow client, and answers `0x97` or disconnects at the bound (§8.4).

### 2.2 Channels: the topic tree

![The topic tree: filters stored level by level, a topic walking one branch per level, and the matches merged into one per subscriber](images/ft-channels.svg)

Channels answer one question for every publish: which subscribers want this topic? The topic tree stores every filter
level by level. A publish walks the tree along its topic's levels and collects the subscriptions it meets. A
subscriber whose filters overlap, say `jobs/#` and `jobs/+`, gets the message once, at the highest QoS among its
matching filters.

The tree never changes in place. A subscribe or an unsubscribe builds a new tree that shares the unchanged branches,
then swaps it in with one write. A publish reads the current tree once and takes no lock, so publishing, which is
frequent, never waits for subscribing, which is rare.

> **Learn: matching a topic against a filter.** A topic is a path of levels separated by `/`, such as
> `plant/line1/temp`. A filter is a path too, with two wildcards: `+` stands for exactly one level and `#` for all the
> levels that follow. So `plant/+/temp` matches `plant/line1/temp` but not `plant/line1/motor/temp`, and `plant/#`
> matches both. Testing every filter against every topic gets slow as filters grow; a tree of levels tests only the
> branches the topic's levels lead to. frontear's tree also stops at 128 levels, the gateway's bound on a topic.

### 2.3 The octopus: links between gateways

![The octopus: one node's links reaching out to its peers, each with its out filters, its in filters and its hop limit](images/ft-octopus.svg)

The octopus is the set of links of one node, and the links of every node together make the octopus network. A link
joins this node's gateway to one peer's and has two directions, each with its own topic filters:

- **Out.** The link subscribes inside this gateway to its `out` filters. What matches goes to its log, then to the
  peer.
- **In.** The link connects to the peer as the MQTT client `<node>~<id>`, subscribes there to its `in` filters, and
  publishes on this gateway what arrives.

A link belongs to the node that holds it. alexander adds, changes and removes a node's links through the octopus's
four operations: put a link, remove one, set the filters of one direction, read the state of all (§5.1). Each link's
configuration is written to its `link.json` before it takes effect, so a restarted node brings its links back (§8.3).

![The path of one message through a link: publish, match, append to the log and force it, acknowledge, send to the peer, move the cursor, deliver](images/ft-link-path.svg)

How a link carries one message, its order, its retries and its reconnects, is in §6.

> **Learn: a hop limit.** A message passed from hand to hand can come back to where it started and go round forever. A
> hop count stops it: every hand that passes the message adds one to a number written on it. When the number reaches
> a limit, the next hand keeps the message instead of passing it on. The limit is the longest path you want a message
> to travel. Each frontear link adds 1 to the user property `frontear-hops` and does not carry a message that has
> already crossed `hopLimit` links; No Local stops the shortest loop, a message sent straight back.

### 2.4 The store: link logs

![A link's log: segment files on disk, records appended at the tail, one force for many records, the cursor the peer has reached, and the segments below it deleted](images/ft-store.svg)

Every link has a log of its own, in a folder named by the link's id. The log is a row of segment files; a new segment
starts at 64 MiB. A message that matches a link's `out` filters is appended at the log's *tail*, and the publisher's
PUBACK waits until it is forced to disk. The *cursor* marks how far the peer has acknowledged. Once the cursor has
passed a whole segment, the segment is deleted, so the disk is freed while the link runs.

| Piece | What it does |
|---|---|
| Force | One thread per log writes the waiting records and forces them to disk. Records that arrive together share one force, and the link's own thread never waits on the disk. |
| Cursor | Saved in its own small file, which is replaced whole, so a crash leaves the old cursor or the new one. |
| Trim | Segments wholly below the saved cursor are deleted. |
| Quota | A log holds at most 1 GiB. An append past it is refused, and the publisher gets `0x97` (§8.4). |
| Recovery | At start the log checks every record and cuts off a half-written one (§8.6). |
| Format | A record is stored as the bytes it differs by from the record before it, since a link's records repeat their topic and the properties the link stamps: a message with a 128-byte payload on a 35-character topic takes about 144 bytes on disk instead of 234. The first record of a segment, every 64th after it and the first after a start are stored whole. Each segment names its format version, and a log written by an older comando is read as it is; an older comando cannot read a segment this one starts ([record 191](adr/0191-frontear-compact-link-log-records.md)). |

> **Learn: the write-ahead log.** Memory is fast and forgets everything when the power goes. A disk is slow and
> remembers. A write-ahead log makes one promise from both: write the fact to the end of a file, force the file to
> the disk, and only then say "done". Appending to the end is the cheapest write a disk does. Each frontear record
> also carries a *checksum*, a number computed from its bytes. After a crash the node recomputes it: a half-written
> record does not match and is cut off.

### 2.5 alexander, the control plane

![The control plane, alexander: signed requests in from the control pod, the seal checked, the links changed through the octopus, the gates on local and remote clients, and the retained link state out](images/ft-alexander.svg)

alexander is the control plane of a node. The gateway and the links carry your messages; alexander decides what they
carry and to whom, and lets only a holder of a trusted key change that. It has four jobs:

| Job | What it does |
|---|---|
| Decides | Applies `PutLink`, `SetFilters`, `RemoveLink` and `GetState` (§5.1) to the node's links, through the octopus. A request that cannot be applied is refused as `failed`, and nothing changes. |
| Checks | Accepts a request only when its *seal* holds: a trusted key signed it, its time is within 30 s of the node's clock and not before the node started, the signature verifies, and its nonce has not been used. A failed check is a *refusal*, with its name in the reply and its code in the PUBACK (§10.1). The `Signer` makes such a seal for a pod (§5.5). |
| Gates | On the loopback, lets clients publish anything outside `$frontear/` and, under it, only to `$frontear/control/`; they may subscribe to anything. On the border gateway's listener, lets another machine connect only as a link client `<slug>~<node>`, publish only its own instances' presences and this machine's instances' `inbox` and `reply` topics, and subscribe to nothing. |
| Publishes | Retains the state of every link on `$frontear/state/<node>/links`, when it changed and at most once a second (§7.1). |

alexander handles one request at a time, on one strand of its own, `frontear-control-<node>`. Requests wait for it in
a bounded queue: 1,000 requests and 16 MiB, and a quarter of each for one client, so one client cannot crowd out
another. A request past the bound is refused as `busy` without a reply. Each request then goes through the steps of
the picture below: the seal is checked, the body read, the change applied and forced to disk, the reply sent and the
state published.

![The control flow: the pod writes, signs and publishes a request; the node checks it in a fixed order, applies it, replies and publishes its state](images/ft-control-flow.svg)

The seal checks the nonce last. A message that fails an earlier check therefore cannot use up the nonce of a genuine
request. A machine across the border gateway may keep at most 64 retained presences on this node at a time; an empty
retained message clears one.

> **Learn: control plane and data plane.** A road network carries two kinds of traffic: the cars, and the crews that
> repaint the lanes and change the signs. Networks split their work the same way. The *data plane* carries the
> messages and must be fast. The *control plane* decides where messages go and must be careful, because one wrong
> order reroutes everything. In frontear the gateway, the links and their logs are the data plane, and alexander is
> the control plane: its orders arrive as MQTT messages like any other, but on their own topics, on their own strand,
> and only with a valid signature.

### 2.6 The watch

![The watch: the gateway, the links and the logs writing events into a ring, and the frontear tab and frontear_read reading it without slowing them](images/ft-watch.svg)

The watch records what a node does, for whoever looks: each message the gateway routes, each step of a link (a message
appended, sent, arrived or dropped; a connection made or lost) and each force of a log. It also gives a snapshot of
the node: its counters, its connected clients, its links and their logs. The watch is read-only: nothing in it
changes the node.

The watch is off until someone turns it on, and while off it costs one memory read per event. While on, it never
locks and never waits. A message's correlation data is kept up to 16 bytes and its payload up to 8 KiB. In comando,
the [frontear tab](workbench.md#44-the-frontear-tab) and the MCP tool `frontear_read` read it: comando reads the events
every 250 ms and the snapshot every second. `frontear_read` has four views, `overview`, `traffic`, `octopus` and
`store`; `traffic` lists the messages seen, at most 2,000 per call, and shows a payload only when you ask for it.

> **Learn: a ring buffer.** A ring buffer is a row of slots whose end joins its start, like the seats of a carousel.
> A writer puts each new event in the next slot; after the last slot it starts again at the first, over the oldest
> event. A reader keeps its own place and reads the slots it has not seen yet. A reader that falls a whole turn
> behind has lost the oldest events, and is told how many. frontear's watch is such a ring of 16,384 slots in a
> node: a slow screen loses events and counts them as `missed`, and the messages never slow down for it.

### 2.7 TLS between machines

![TLS between machines: two border gateways, each with its machine key and self-signed certificate, both showing their certificate in a TLS 1.3 handshake and each trusting only the key fingerprints it has pinned](images/ft-tls.svg)

TLS protects the connections between the border gateways of two machines paired with each other (§9.1). In
comando nothing on the loopback uses it. Each machine has one Ed25519 key and a certificate it signed itself, for
the name of the machine, valid 20 years, in `~/.comando-machine/` (`machine.key` and `machine.crt`), readable by their owner only.
The key's *fingerprint*, the SHA-256 of its public half written as 64 hex digits, names it; pairing two machines
means each one pins the other's fingerprint.

| Rule | What frontear does |
|---|---|
| Protocol | TLS 1.3 only. |
| Both sides | The client shows its certificate as well as the server, and each side checks the other's. |
| Trust | A peer is trusted exactly when the key in its certificate is pinned. No certificate authority, no validity dates, no host name. |
| No resumption | Every connection makes a full handshake, so a machine unpaired is refused at its next connection. |
| Pins | The set of pinned fingerprints is replaced whole when the pairings change; a handshake sees the set before a change or after it, never half of each. |

> **Learn: mutual TLS and pinning.** TLS wraps a connection so nobody between the two ends can read or change it. In
> the handshake a server shows a certificate, a signed statement of its public key, and usually a certificate
> authority vouches for it. In *mutual* TLS the client shows one too, so both ends know whom they talk to. *Pinning*
> skips the authority: each end keeps the exact key it expects and refuses any other, even one an authority signed.
> frontear pins: you compare two fingerprints once, when you pair the machines, and from then on only those two keys
> are let in.

### 2.8 The protocol

![The protocol: an MQTT 5 packet as bytes, its type, its length as a variable byte integer and its body, read and written by the codec, and frontear's client on top](images/ft-protocol.svg)

The protocol component turns MQTT 5 packets into bytes and back, for the gateway and for frontear's own MQTT 5 client.
Every packet type is one record. The codec refuses what frontear declines: a protocol level other than 5, a user name
or password, enhanced authentication, topic aliases and subscription identifiers (§11). It also says which topic names
and filters are valid, and names what frontear reserves: the `$frontear/` topics and the user property
`frontear-origin`.

The client connects to one MQTT 5 server, over TLS 1.3 when it is given a TLS context. It has 10 s to connect and
10 s for each write, and holds at most 1,000 messages waiting to go out. Links use it to reach their peers, and
comando's instances use it to reach their nodes. A link's log stores each message as the PUBLISH packet MQTT 5 writes,
so any MQTT tool can read a record.

> **Learn: framing.** TCP delivers a stream of bytes with no gaps between messages, like one long ribbon of letters.
> To find where one packet ends and the next begins, each MQTT packet starts with its type and its length. A reader
> takes the length first, then exactly that many bytes. MQTT writes the length as a *variable byte integer*: seven
> bits per byte, the eighth saying that another byte follows, so a small length takes one byte and the largest,
> 268,435,455, takes four. frontear reads and writes these integers in one place, and refuses a packet whose
> length breaks the rule as malformed.

## 3. Quick start: one node, one client

You need JDK 25, OpenSSL 3 or later, and an MQTT 5 client that can set user properties. The examples use the
Mosquitto clients, `mosquitto_pub` and `mosquitto_sub`. The `openssl` that ships with macOS is LibreSSL and may lack
Ed25519: install OpenSSL when `genpkey` refuses the algorithm.

**1. Build.** From the root of the comando repository:

```sh
./gradlew :core:jar :frontear:jar
```

**2. Make the control key pair.** The private key signs control requests. The node gets the public key only.

```sh
openssl genpkey -algorithm ed25519 -out pod.pem
openssl pkey -in pod.pem -pubout -outform DER | openssl base64 -A > pod.pub
```

`pod.pub` holds one line of 60 characters that starts with `MCowBQYDK2VwAyEA`. Keep `pod.pem` private.

**3. Start the node.**

```sh
java -cp "frontear/build/libs/*:core/build/libs/*" \
  dev.marregui.comando.frontear.Frontear a 1883 data-a pod.pub
```

```
frontear serving [node=a, port=1883]
```

| Argument | Meaning |
|---|---|
| `node` | The node's name: one topic level, with no `/`, `+`, `#` or NUL. Control and state topics carry it. |
| `port` | The loopback port. `0` picks a free one, printed in the `serving` line. |
| `dataDir` | The folder for the link logs. The node creates it with the first link. |
| `trusted-key-file` | One or more files, each holding one trusted public key in the form of `pod.pub`. |

With fewer than four arguments the process prints its usage and exits with code 2.

**4. Connect a client.** Subscribe in one terminal, publish in another:

```sh
mosquitto_sub -V 5 -h 127.0.0.1 -p 1883 -t 'hello/#' -v
mosquitto_pub -V 5 -h 127.0.0.1 -p 1883 -q 1 -t hello/world -m 'first message'
```

The subscriber prints `hello/world first message`. You have a working MQTT 5 gateway.

## 4. An octopus network, step by step

An octopus network is several nodes joined by links. You build it one link at a time, and each link belongs to one
node. This walk builds the network of the picture at the top: `a` links to `b`, then `b` links to `c`.

**1. Save the two helper scripts.** `ftsign` signs a request (§5.2 explains the bytes). `ftctl` publishes it.

```sh
#!/bin/sh
# ftsign <private-key.pem> <node> <Method> <json-body> [<response-topic>]
# Prints the four signature properties of one frontear control request.
set -eu
key=$1; node=$2; method=$3; body=$4; reply=${5:-}
topic="\$frontear/control/$node/$method"
ts=$(( $(date +%s) * 1000 ))
nonce=$(openssl rand -hex 16)
keyid=$(openssl pkey -in "$key" -pubout -outform DER | openssl dgst -sha256 -r | cut -d' ' -f1)
tmp=$(mktemp)
# topic, ts, nonce, content type, response topic, each ended by a 0 byte;
# then the correlation data's 2-byte length (0, none here); then the body
printf '%s\0%s\0%s\0%s\0%s\0\0\0%s' \
  "$topic" "$ts" "$nonce" application/json "$reply" "$body" > "$tmp"
sig=$(openssl pkeyutl -sign -inkey "$key" -rawin -in "$tmp" | openssl base64 -A)
rm -f "$tmp"
echo "frontear-key=$keyid"
echo "frontear-ts=$ts"
echo "frontear-nonce=$nonce"
echo "frontear-sig=$sig"
```

```sh
#!/bin/sh
# ftctl <port> <private-key.pem> <node> <Method> <json-body>
# Signs one frontear control request and publishes it; the reply goes to reply/pod.
set -eu
port=$1; key=$2; node=$3; method=$4; body=$5
set --
for p in $(./ftsign "$key" "$node" "$method" "$body" reply/pod); do
  set -- "$@" -D PUBLISH user-property "${p%%=*}" "${p#*=}"
done
mosquitto_pub -V 5 -h 127.0.0.1 -p "$port" -q 1 \
  -t "\$frontear/control/$node/$method" -m "$body" \
  -D PUBLISH content-type application/json \
  -D PUBLISH response-topic reply/pod "$@"
```

Make both executable with `chmod +x ftsign ftctl`.

**2. Start three nodes**, each in its own terminal:

```sh
CP="frontear/build/libs/*:core/build/libs/*"
java -cp "$CP" dev.marregui.comando.frontear.Frontear a 1883 data-a pod.pub
java -cp "$CP" dev.marregui.comando.frontear.Frontear b 1884 data-b pod.pub
java -cp "$CP" dev.marregui.comando.frontear.Frontear c 1885 data-c pod.pub
```

All three trust the same `pod.pub`, so one private key configures the whole network.

**3. Watch node a.** One terminal for the replies, one for the link state:

```sh
mosquitto_sub -V 5 -h 127.0.0.1 -p 1883 -q 1 -t reply/pod -v
mosquitto_sub -V 5 -h 127.0.0.1 -p 1883 -q 1 -t '$frontear/state/a/links' -v
```

The state topic prints `{"links":[]}`: node `a` has no link yet.

**4. Link a to b.** Jobs flow out to `b`. Results flow in from `b`.

```sh
./ftctl 1883 pod.pem a PutLink \
  '{"id":"b","host":"127.0.0.1","port":1884,"out":["jobs/#"],"in":["results/#"],"hopLimit":4}'
```

The reply is `{"ok":true}`. The state topic shows the link appear, then connect:

```json
{"links":[{"id":"b","connected":true,"queued":0,"cursor":0,"tail":0,"sent":0,"dropped":0,"reordered":0}]}
```

**5. Send a message each way.** Subscribe on the far side first, then publish:

```sh
mosquitto_sub -V 5 -h 127.0.0.1 -p 1884 -q 1 -t 'jobs/#' -v      # on b
mosquitto_pub -V 5 -h 127.0.0.1 -p 1883 -q 1 -t jobs/1 -m 'job one'   # on a

mosquitto_sub -V 5 -h 127.0.0.1 -p 1883 -q 1 -t 'results/#' -v   # on a
mosquitto_pub -V 5 -h 127.0.0.1 -p 1884 -q 1 -t results/1 -m 'result one'   # on b
```

Each subscriber gets its message. The job arrives on `b` with the user property `frontear-hops` set to `1`. Node
`a`'s state now reads `"cursor":1,"tail":1,"sent":1`.

**6. Add the third node.** The link from `b` to `c` belongs to `b`, so the request goes to `b`:

```sh
./ftctl 1884 pod.pem b PutLink \
  '{"id":"c","host":"127.0.0.1","port":1885,"out":["jobs/#"],"in":["results/#"],"hopLimit":4}'
```

A job published on `a` now reaches a subscriber on `c` with `frontear-hops` set to `2`. A result published on `c`
reaches a subscriber on `a` the same way.

**7. Take node b away.** Stop `b` with Ctrl-C and publish three jobs on `a`. Node `a` still acknowledges each one.
Its state shows them waiting, for example:

```json
{"links":[{"id":"b","connected":false,"queued":3,"cursor":2,"tail":5,"sent":2,"dropped":0,"reordered":0}]}
```

Start `b` again. It brings back its links from its data folder (§8.3), so you send nothing. Node `a`
reconnects by itself and its state returns to `"connected":true,"queued":0`. The three jobs are on `b`.

## 5. Configuring links with signed control messages

A control request is an ordinary MQTT publish with a signature on it, and alexander is the part of the node that
checks and applies it (§2.5). Whoever holds a trusted private key is the *control pod* and can change the links.
Everyone else is refused.

### 5.1 The request

Publish at QoS 1 to `$frontear/control/<node>/<Method>`. The payload is a JSON object, and the content type must be
`application/json`. Set a response topic to get a reply.

| Method | Body | Effect |
|---|---|---|
| `PutLink` | `{"id","host","port","out","in","hopLimit"}`, and `"tls"` if the link uses TLS | Adds the link, or updates the link with the same id. |
| `SetFilters` | `{"id","direction","filters"}` | Replaces all the filters of one direction, `"OUT"` or `"IN"`. |
| `RemoveLink` | `{"id"}` | Removes the link and keeps its log. |
| `GetState` | `{}` | Replies with the state of every link (§7.1). |

Every property but `tls` is required, and an unknown property is refused: a node never applies half a request.

| Property | Rule |
|---|---|
| `id` | 1 to 64 characters from `A-Z a-z 0-9 . _ @ -`, and not `.` or `..`. It names the link's log folder. |
| `host`, `port` | Where the peer listens. `port` is 1 to 65535. |
| `out` | Topic filters. A message published on this node that matches one goes to the peer. |
| `in` | Topic filters. The link subscribes to them on the peer and publishes what arrives on this node. |
| `hopLimit` | At least 1. The link does not carry a message that has already crossed this many links. |
| `tls` | Optional, `false` when absent. `true` makes the link connect over TLS 1.3 with the TLS context its host gives it (§2.7). A node run from the command line has none, so such a link stays down. |

### 5.2 The signature

> **Learn: a digital signature.** A key pair is two numbers made together: a private key you keep and a public key
> you hand out. Signing mixes a message with the private key and gives a short code. Anyone with the public key can
> check that the code fits the message. Nobody can make a fitting code without the private key, and changing one byte
> of the message breaks the fit. frontear nodes hold public keys only, so a stolen node configuration cannot sign.

Every request carries four user properties:

| Property | Value |
|---|---|
| `frontear-key` | The key id: the lower-case hex SHA-256 of the public key's X.509 encoding. |
| `frontear-ts` | The signing time in epoch milliseconds, as a decimal number. |
| `frontear-nonce` | 16 random bytes as 32 lower-case hex digits, new for every request. |
| `frontear-sig` | The base64 of the Ed25519 signature. |

The signature covers these bytes, in this order:

1. five UTF-8 strings, each followed by one `0x00` byte and empty when absent: the topic, the ts as written, the
   nonce, the content type and the response topic;
2. the correlation data, as a 2-byte big-endian length and its bytes, with length 0 when absent;
3. the payload.

The response topic is signed too, so nobody can redirect the reply of a copied request. A node accepts a request
when the key is trusted, the signature verifies and the two freshness rules below hold.

> **Learn: nonce and replay.** A signature proves who wrote a message, not when. An attacker who records a valid
> `RemoveLink` could send the same bytes again tomorrow. So each request carries the time and a *nonce*, a random
> number used once. A frontear node refuses a request older or newer than 30 seconds. Inside that window it remembers
> every nonce and refuses a second use.

Nonces live in memory. A node therefore also refuses any request signed before it started.

### 5.3 A worked example of each method

Each command uses `ftctl` from §4. The reply arrives on `reply/pod`.

| Step | Command | Reply |
|---|---|---|
| Add a link | `./ftctl 1883 pod.pem a PutLink '{"id":"b","host":"127.0.0.1","port":1884,"out":["jobs/#"],"in":["results/#"],"hopLimit":4}'` | `{"ok":true}` |
| Widen what goes out | `./ftctl 1883 pod.pem a SetFilters '{"id":"b","direction":"OUT","filters":["jobs/#","alerts/#"]}'` | `{"ok":true}` |
| Read the state | `./ftctl 1883 pod.pem a GetState '{}'` | `{"ok":true,"links":[{"id":"b","connected":true,…}]}` |
| Remove the link | `./ftctl 1883 pod.pem a RemoveLink '{"id":"b"}'` | `{"ok":true}` |

Good to know:

- `PutLink` on an existing id updates it. A new `host` or `port` reconnects the link, and the filters are replaced.
- `SetFilters` replaces the whole list of one direction. The last request wins.
- `RemoveLink` of an id that does not exist succeeds. A later `PutLink` with the same id resumes the kept log.
- A request is applied at most once. A resent copy carries a used nonce and is refused as `replayed`.

### 5.4 Replies

| Outcome | PUBACK | Reply on the response topic |
|---|---|---|
| Applied | `0x00` | `{"ok":true}` |
| `GetState` | `0x00` | `{"ok":true,"links":[…]}` |
| Refused | The refusal's code (§10.1) | `{"ok":false,"reason":"<code>","detail":"<text>"}` |

The reply is sent at QoS 1 with your correlation data unchanged. A response topic under `$frontear/` gets no reply.

### 5.5 From Java

`Signer` builds the signed message. This example reads the key files of §3:

```java
KeyFactory keys = KeyFactory.getInstance("Ed25519");
String pem = Files.readString(Path.of("pod.pem")).replaceAll("-----[A-Z ]+-----|\\s", "");
PrivateKey key = keys.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
PublicKey pub = keys.generatePublic(new X509EncodedKeySpec(
        Base64.getDecoder().decode(Files.readString(Path.of("pod.pub")).strip())));

Signer signer = new Signer(key, Signer.keyId(pub));
Packet.Publish request = signer.sign("$frontear/control/a/GetState",
        "{}".getBytes(StandardCharsets.UTF_8), "reply/pod", null);
```

`Signer` is in `dev.marregui.comando.frontear.alexander`, `Packet` in `dev.marregui.comando.frontear.protocol`. Publish
`request` with any MQTT 5 client. frontear's own `MqttClient.publishAcknowledged(request)` completes with the PUBACK's
reason code, `0` for an applied request. One `Signer` may be shared by several threads.

## 6. How a link carries a message

A link is a subscriber inside the gateway that writes what it receives to its log (§2.4), then sends it on. The
publisher's PUBACK waits for the log. A message the node has acknowledged therefore survives a crash of the node. The
picture of §2.3 shows the path.

| Aspect | What frontear does |
|---|---|
| Out | Messages matching an `out` filter go to one internal subscriber, `$link/<id>`. A message matching several filters is queued once. |
| In | The link subscribes on the peer at QoS 1, with No Local and Retain As Published. It publishes on this node what arrives under its `in` filters. Anything else that arrives it drops and counts in `dropped`. |
| Identity | The link connects to its peer as the MQTT client `<node>~<id>`, for example `a~b`. |
| Order | Messages reach the peer in the order of the log. One exception: a message the peer refuses for now (`0x97` or `0x89`) is sent again alone, and counted in `reordered`. |
| Loops | The link subscribes with No Local and never sends back what came in through it. Each link also adds 1 to the user property `frontear-hops` and stops a message that has reached its `hopLimit`. |
| Expiry | A message's expiry keeps running while it waits in the log, across a restart too. An expired message is dropped and counted. |
| Backlog | What waits for the peer is bounded: 64 MiB and 24 h by default (`LinkOptions`). While the peer is down, the link drops the oldest messages past either bound and counts them in `dropped`; a retained message at the head of the backlog that a newer one on its topic supersedes is dropped too and counted in `coalesced`. While the peer is up, a message past the byte bound gets `0x97` (QoS 0: dropped). A superseded retained message is never sent, wherever it waits, so a presence republished while the peer is down reaches it once. A restart reads the backlog it left before it takes a message. `coalesced`, `refused` and the backlog's bytes are counters of the link's `LinkState`; the state topic of §7.1 does not carry them. |
| Refusals | In front of the log, the link queues at most 1,000 messages (§8.4). Publishers whose windows add up to more than that, such as 4 publishers with 512 in flight each, get `0x97` for the messages that arrive faster than the link logs them: a QoS 1 message is counted in `refused`, a QoS 0 one in `dropped`. No acknowledgement is lost: the publisher sees each `0x97` and may send again. |
| Reconnect | A lost connection is retried after 1 s, doubling to 30 s. Up to a fifth of each wait is taken off at random. A peer that is back does not wait out that backoff: when the peer's own link to this node connects (client `<peer>~<node>`), this node's link to it is tried at once, at most once a second. A peer that refuses with `0x88` (Server unavailable) is running, so the link tries it again after the first wait, 1 s, and the wait does not grow. |

> **Learn: at least once.** A sender that hears no acknowledgement cannot know whether its message arrived. It has two
> choices: give up, and maybe lose the message, or send again, and maybe deliver it twice. frontear links send again.
> After a reconnect or a restart, everything after the saved cursor goes out a second time. Design your receivers to
> tolerate a repeat, for example by remembering the correlation data of what they have handled.

## 7. Monitoring

A node reports on itself over MQTT. Any client may subscribe to these topics; only the node publishes them. Inside
comando, the watch (§2.6) shows the same and more in the frontear tab.

### 7.1 Link state

`$frontear/state/<node>/links` is a retained message. alexander publishes it when a link changes, at most once a
second.

```json
{"links":[{"id":"b","connected":true,"queued":0,"cursor":1520,"tail":1520,"sent":1520,"dropped":0,"reordered":0}]}
```

| Field | Meaning |
|---|---|
| `connected` | Whether the peer accepted the link's connection. |
| `queued` | Messages in the log that the peer has not acknowledged: `tail − cursor`. |
| `cursor` | The offset of the next message to deliver. |
| `tail` | The offset the next logged message takes. |
| `sent` | Messages sent to the peer. |
| `dropped` | Messages not carried: past the hop limit, expired, refused by the peer for good, QoS 0 over the queue bound, or let go from a down link's backlog past its bounds (§6); and messages from the peer outside the `in` filters. |
| `reordered` | Messages the peer refused for now and accepted after a later one. |

`cursor` and `tail` come from the log and survive a restart. `sent`, `dropped` and `reordered` start at 0 with the
link.

| You see | It means | Do |
|---|---|---|
| `connected:true`, `queued` near 0 | Healthy. | Nothing. |
| `connected:false`, `queued` growing | The peer is unreachable. The link keeps retrying. | Check the peer process and the link's `host` and `port`. |
| `connected:true`, `queued` growing | Publishers are faster than the peer takes messages. | Slow the publishers or narrow the `out` filters. |
| `dropped` rising | Messages reach the hop limit, expire in the log, or the peer refuses them for good; or the peer sends messages outside the link's `in` filters. | Compare `hopLimit` with the links a message crosses. Check message expiry. Compare the topics the peer sends with the link's `in` filters. |
| `reordered` rising | The peer is full for moments. | Nothing, unless your receivers need strict order. |

### 7.2 Presence

`$frontear/presence/<clientId>` holds the retained message `{"online":true}` while that client is connected. The
gateway clears it when the connection ends. Subscribe to `$frontear/presence/#` to see every client, links included:
the link of step 4 appears on node `b` as `$frontear/presence/a~b`.

### 7.3 The node's log

The node logs to standard error. These lines matter most:

| Line | Meaning |
|---|---|
| `link connected [link=b, window=…, cursor=…, tail=…]` | The peer accepted the link. `window` is how many messages may be in flight. |
| `link disconnected […]` | The connection to the peer ended. The link retries. |
| `link restored [link=…, host=…, port=…, file=…]` | At start, the node brought back a link from its `link.json`. |
| `link not restored, it stays down until a put replaces its spec […]` | At start, a `link.json` could not be read or held a value out of range. |
| `applied control request [node=…, method=…, key=…, nonce=…, origin=…]` | alexander applied a signed request. `origin` is the client id. |
| `refused control message [reason=…, detail=…, topic=…, origin=…]` | alexander refused a request. Logged at most once a minute. |
| `cut torn tail off log segment […]` | Recovery removed a half-written record after a crash. |

## 8. Operations

### 8.1 The data folder

```
data-a/
  b/                           one folder per link, named by the link's id
    00000000000000000000.log   a segment, named by the offset of its first record
    cursor                     how far the peer has acknowledged
    link.json                  the link's configuration, as its last PutLink and SetFilters left it
```

Links share nothing: each has its own folder, its own log and its own bounds. A new segment starts at 64 MiB. As
the peer acknowledges, the node saves the cursor and then deletes the segments that lie wholly below it. The
disk is freed while the link runs. A segment that a crash left below the cursor goes when the log opens.

### 8.2 Durability

| Event | What you keep |
|---|---|
| The node acknowledged a QoS 1 publish that matches a link | The message is on disk. It goes to the peer as soon as the link runs, after a crash too. |
| The node crashes before the PUBACK | The publisher has no acknowledgement and sends again. |
| The peer is down | Messages wait in the log, in order, up to the log's bound (§8.4). |
| The node crashes after sending, before saving the cursor | The messages after the saved cursor go out again (§6, at least once). |

Publishes that arrive together share one force to disk.

### 8.3 Restarts

Stop a node with a termination signal, Ctrl-C included. It stops alexander, closes the gateway, then closes the
links, whose logs force their last records. The close has 10 s.

A restarted node brings back its links and starts empty of everything else:

| Lives in memory, gone after a restart | Lives on disk, kept |
|---|---|
| Client sessions and subscriptions | Each link's configuration: id, peer, filters, hop limit, TLS (`link.json`) |
| Retained messages | Each link's log and cursor |
| Remembered nonces and all counters | |

A `PutLink` or `SetFilters` is written to the link's `link.json`, and forced to disk, before it takes effect and
before its reply goes out. A `RemoveLink` deletes the file first, and keeps the log. So the reply `{"ok":true}`
means the change survives a restart.

At start the node reads every `link.json` in its data folder and starts that link. A link with a backlog resumes at
its cursor and drains it once its peer answers. A file it cannot read is logged as `link not restored […]` and left
in place. That link stays down until a `PutLink` with its id replaces the file. The other links start as usual.

After every start:

1. Let clients reconnect and subscribe again.
2. Sign requests after the node is up. A request signed earlier is refused as `stale`.

The links start before alexander takes control requests, but the gateway already listens: a message that reaches it
in the moment before a link starts goes to the node's own subscribers only, and is not forwarded later.

### 8.4 Bounds, and what to do at each

Every queue in frontear has a limit, so a slow peer or a flooding client cannot exhaust the node.

| Bound | Limit | When you hit it | What to do |
|---|---|---|---|
| A link's log on disk | 1 GiB | Publishers of matching QoS 1 messages get PUBACK `0x97`. | Bring the peer back. Or discard the backlog (§8.5). |
| A link's backlog, what the peer has not acknowledged | 64 MiB and 24 h | While the peer is down, the oldest messages are dropped and counted in `dropped`; a superseded retained message is dropped and counted in `coalesced`. While it is up, a message past 64 MiB gets `0x97`. A link down for 5 min with its backlog growing says so in its state (`LinkState.backlogGrowing`). | Bring the peer back. Or discard the backlog (§8.5). |
| Messages a link has not yet logged | 1,000 messages, and 8 MiB waiting for the disk | QoS 1 gets `0x97` and is counted in `refused`. QoS 0 is dropped and counted in `dropped`. A burst of 4 publishers at 512 in flight each reaches it (§6). | Publish slower and retry on `0x97`. |
| Messages in flight to a peer | The peer's Receive Maximum, at most 1,000 | The link waits for acknowledgements. | Nothing. |
| Unacknowledged QoS 1 publishes from a client | 1,024 | The client is disconnected with `0x93`. | Honour the Receive Maximum of the CONNACK. |
| Packet size | 16 MiB | The client is disconnected with `0x95`. | Send smaller messages. |
| Sessions | 1,000 | CONNECT gets `0x97`. | Disconnect idle clients, or let their sessions expire. |
| Messages queued for one session | 1,000 and 64 MiB | The newest message is dropped and counted. | Keep the subscriber connected, or subscribe to less. |
| Bytes waiting to be written to one client | 64 MiB | The client is disconnected with `0x97`. | Read faster, or subscribe to less. |
| Retained messages | 10,000 and 64 MiB | A retained publish gets `0x97`. | Clear retained topics with an empty retained message. |
| Topic levels | 128 | A topic name is refused with `0x90`, a filter with `0x8F`. | Use shorter topics. |
| Filters | 256 per SUBSCRIBE, 1,000 per session | SUBSCRIBE gets `0x97`. | Use wildcards. |
| Control requests waiting | 1,000 and 16 MiB, a quarter of each per client | `busy`, PUBACK `0x97`, no reply. | Wait and send a newly signed request. |
| Nonces remembered | 10,000 in 30 s | `busy`, PUBACK `0x97`, with a reply. | Send fewer control requests. |

### 8.5 Discard a link's backlog

To drop everything a link still holds for its peer:

1. Send `RemoveLink` for the link.
2. Delete the link's folder, `<dataDir>/<id>/`.
3. Send `PutLink` again. The link starts an empty log at offset 0.

### 8.6 Recovery after a crash

When a link starts, its log checks itself before it takes a message. You do nothing.

1. It deletes the segments that lie wholly below the saved cursor.
2. It scans the rest in order and verifies each record's checksum.
3. It cuts the file at the first record that is short or does not match, and deletes every segment after it.
4. It forces what it kept to disk. The link then resumes at its saved cursor.

The file format is in [the frontear module page](modules/frontear.md).

## 9. frontear inside comando

Every comando instance hosts a frontear node of its own, and the nodes are linked into one octopus network; it is the
only MQTT 5 server comando has ([record 185](adr/0185-frontear-is-the-only-octopus-network.md) and
[record 187](adr/0187-frontear-lab-node-per-instance.md)).

| Fact | Value |
|---|---|
| When | At launch, and at each later look (every 5 s) while it is down, unless stopped by hand. `-Dcomando.peers=false` keeps an instance off the others: its node runs on a port the OS picks, with a registry in its data folder. |
| Node name | The instance's id, `main` for the main instance. Control topics are `$frontear/control/<id>/<Method>`. A link client named `<other>~<name>` is refused unless `<name>` is this node's. Until comando's own client of the node has its subscriptions acknowledged, every link client is refused with `0x88` (Server unavailable); the link tries again after the first wait, at most 1 s later. Each start of the node counts as a new one: an acknowledgement for the subscriptions made against an earlier start, the same port included, lets no link in. |
| Address | `127.0.0.1`, the lowest port from 18883 up that is free and no other instance holds; the instance keeps it from then on. |
| Data folder | The node's folder in the instance's data folder, `fabric/` (the name is kept for compatibility). |
| Control keys | `pod.key` and `pod.pub` in the node's folder, made at the first start. `pod.key` is readable by its owner only. |
| How many | One node per instance. Each instance's client connects to its own node only. |
| Links | At each look comando puts a link to every other node in the registry, with id the other instance's id: out that instance's `inbox` and `reply` topics and this one's `presence`, nothing in, hop limit 1. While a machine is paired, the border gateway's links to the instances have hop limit 3 and its link to each paired machine hop limit 2, and every link to or from the border gateway carries the paired machines' topics too ([§9.1](#91-other-machines)). It removes a link whose id is not in the registry, so a link you add by hand lasts until the next look. A link to an instance first seen present is put at once, and checked before each question to it, so a question asked in its first seconds is not lost. A link to a node that is down queues until it starts again, and drains once that node lets the links in. |
| Control replies | comando's own control requests ask for their reply under `comando/v1/local/fabric/` (the prefix is kept for compatibility), a topic no link's filter covers. |
| Start and stop | The window's frontear toggle, or an agent's `server_start frontear` and `server_stop frontear`, for this instance's node. The other nodes keep running. |
| Registry | One file per machine that lists every instance's node by id, with its port, its process while it runs and 0 once down, its start, its name and its data folder; version 2, and a file of version 1 is replaced. Its name, `~/.comando-fabric.json`, and its lock's, the same with `.lock` added, are kept for compatibility. |

comando makes the key pair, gives the node its public key and signs its own control requests with it. `pod.pub`
has the form of §3. `pod.key` is the private key as one line, the base64 of its PKCS#8 encoding. `ftsign` takes a
PEM file, so wrap that line first. Run this in the node's folder of the instance `main`, its node on the port the
toggle's tooltip names (18883 here), with `ftsign` and `ftctl` of §4 beside you:

```sh
umask 077
{ echo '-----BEGIN PRIVATE KEY-----'; cat pod.key; echo '-----END PRIVATE KEY-----'; } > comando.pem
./ftctl 18883 comando.pem main GetState '{}'
```

`umask 077` keeps `comando.pem` readable by you alone. Every method of §5 then works the same way, for example
`./ftctl 18883 comando.pem main GetState '{}'` to read each link's `connected`, `queued` and `sent`.

| Message | Meaning |
|---|---|
| `frontear is up: MQTT 127.0.0.1:<port>` | The node serves. |
| `frontear is down` | The node stopped. |
| `Cannot start frontear: another comando named <id> runs its node [owner=…, pid=…, port=…]` | Another instance of the same name, on another data folder, runs. Quit it, or rename one. |
| `Cannot start frontear: no port on 127.0.0.1 is free [from=…]` | Every port from 18883 up is held. |
| `Cannot read the frontear registry; remove it if no frontear runs [registry=…, cause=…]` | The registry does not read, or a newer comando wrote it. |
| `not an Ed25519 key pair; remove both files to make a new one` | `pod.key` and `pod.pub` do not read as a pair. Remove both and start again. |

### 9.1 Other machines

Instances on two machines of yours talk through one border gateway per machine, the node of its instance `main`
([record 189](adr/0189-frontear-across-machines.md)). Nothing listens on the network until you pair a machine and name
the address to listen on.

| Fact | Value |
|---|---|
| Pairing | The Machines window (Instances tab menu, or the palette's "Machines…"), or the MCP tool `machines`. Each machine shows its token, `comando-pair:1:<machine>:<host>:<port>:<fingerprint>`; paste the other machine's token on each side and compare the fingerprint it shows with the one the other machine shows. A pairing grants `peer` (the default) or `overseer`. |
| Listen address | One address of this machine you name, a Tailscale or LAN one; never the wildcard. Port 18893 unless set. Clear the address to stop listening. |
| Path | A question goes from the asker's node to its machine's border gateway, across to the other machine's border gateway, and on to the node asked; nothing goes further. Each border gateway keeps one link over TLS to each paired machine's. |
| Security | TLS 1.3 only, both sides authenticated by their Ed25519 key, pinned by fingerprint (§2.7); no TLS session is resumed, so a machine unpaired is refused at its next connection. A remote connection may publish only to the inboxes and replies of this machine's instances and to its own machine's presences; it subscribes to nothing (§2.5). |
| Names | An instance on another machine is `<slug>@<machine>`: `instance_ask main@laptop2`. A bare slug names this machine's instance first. |
| Authority | A question from another machine is headed `[from instance <slug>@<machine> · peer]`, and its consult session runs in plan mode. With the grant `overseer`, `main@<machine>` is the overseer here too and its session runs as a local one. |
| What the other machine sees | Each instance's name, version, role, whether it is busy, and its sessions' names. Folders, branches, prompts and replies stay here. |
| Files | `~/.comando-machine/` (`machine.key`, `machine.crt`, `machine.json`) and `~/.comando-remotes.json`, readable by their owner only; shared by this machine's instances. |
| Limits | Per other machine: 64 instances kept, 8 consult sessions; a question that would open a ninth is refused with a reply saying so. |
| Reachability | A machine whose `main` is not running is unreachable from the others. A question to it waits in the link's log until its asker stops waiting; presences expire after 30 s. |

## 10. Troubleshooting

### 10.1 A control request is refused

Read the `reason` in the reply, or the reason code in the PUBACK.

| `reason` | PUBACK | Cause | Fix |
|---|---|---|---|
| `unsigned` | `0x87` | One of the four signature properties is missing. | Send all four as user properties. |
| `malformed` | `0x87` | A property repeats or does not read: ts not decimal, nonce not 32 lower-case hex digits, bad base64. | Format each value as in §5.2. |
| `unknown-key` | `0x87` | The key id names no key the node trusts. | Start the node with this key's public file, or sign with a trusted key. |
| `stale` | `0x87` | The ts is over 30 s from the node's clock, or earlier than the node's start. | Sign right before sending. Synchronize the clocks. |
| `bad-signature` | `0x87` | The signed bytes differ from the published message. | Sign the exact topic, content type, response topic and payload you publish. |
| `replayed` | `0x87` | The nonce is already used. | Sign every request anew. Never resend a signed copy. |
| `busy` | `0x97` | Too many requests are waiting: no reply is sent. Or too many nonces are remembered: the reply says `busy`. | Wait, then send a newly signed request. |
| `unknown-method` | `0x90` | The last topic level names no method. | Use `PutLink`, `SetFilters`, `RemoveLink` or `GetState`, spelled exactly. |
| `bad-request` | `0x99` | The body is not the method's JSON object, or the content type is not `application/json`. | Send the properties of §5.1 and no other. The `detail` names the problem. |
| `failed` | `0x83` | The node cannot apply the change, for example `no such link [link=…]` on `SetFilters`. | Read the `detail`. Create the link with `PutLink` first. |

### 10.2 Other symptoms

| Symptom | Cause | Fix |
|---|---|---|
| The node prints its usage and exits with code 2 | Fewer than four arguments. | Pass node, port, data folder and at least one key file. |
| `not an Ed25519 public key [file=…]` at start | The key file is not the base64 of an X.509 public key, for example a PEM file. | Make `pod.pub` with the command of §3. |
| A publish under `$frontear/` gets PUBACK `0x87` | Clients may publish there only to `$frontear/control/`. | Use another topic. |
| A publish gets PUBACK `0x10` | No subscriber matches. This is a success code. | Nothing, or start the subscriber first. |
| A publish gets PUBACK `0x97` | A matching link's log or queue is full. | See §8.4. |
| The connection ends with `0x9B` after a publish | The publish used QoS 2. | Publish at QoS 0 or 1. |
| SUBSCRIBE is refused with `0x9E` | The filter starts with `$share/`. | Subscribe without a shared subscription. |
| CONNECT is refused with `0x85` | The client id starts with `$`. | Choose another client id. |
| A client is disconnected with `0x8E` | Another client connected with the same client id. | Give each client its own id. |
| A client is disconnected with `0x8B` | The node is shutting down. | Reconnect when it serves again. |
| A link stays at `connected:false` | The peer is not reachable at `host` and `port`. | Start the peer, or send `PutLink` with the right address. |
| A link with `"tls":true` stays down on a node run from the command line | That node has no TLS context to give the link. | Leave `tls` out on the loopback; TLS links are the border gateways' (§9.1). |
| A link is connected and nothing arrives | No `out` filter matches, the hop limit stops the message, or on the receiving side the topic is outside the `in` filters. | Publish a test message and read the state. `tail` rises when a filter matches, `dropped` when the hop limit stops it or the receiving link's `in` filters do not cover it. |
| A link is missing after a restart | Its `link.json` could not be read (`link not restored […]` in the log). | Send `PutLink` for it (§8.3). |
| Every request is `stale` right after a restart | The signer's clock runs behind the node's. | Wait that long, or synchronize the clocks. |

## 11. MQTT 5 support

A frontear gateway is a standard MQTT 5 server. It announces what it declines in the CONNACK, as the specification
allows.

| Feature | Support |
|---|---|
| QoS 0 and QoS 1 | Yes. Every acknowledgement carries a reason code. |
| QoS 2 | No. The CONNACK announces Maximum QoS 1. |
| Retained messages | Yes, with Retain As Published and Retain Handling. |
| Wildcards `+` and `#` | Yes. |
| Wills, with will delay | Yes, at QoS 0 or 1. |
| Session expiry, session takeover | Yes. |
| Message expiry | Yes. |
| Keep alive | Yes. |
| Receive Maximum, Maximum Packet Size | Yes, in both directions: 1,024 and 16 MiB on the node's side. |
| No Local | Yes. |
| Request and response: response topic, correlation data | Yes. |
| User properties, content type, payload format indicator | Yes, passed on unchanged. |
| An empty client id | Yes. The node assigns `frontear-<uuid>`. |
| Topic aliases | No. Topic Alias Maximum is 0. |
| Subscription identifiers | No. |
| Shared subscriptions | No. |
| Enhanced authentication, user name and password | No. |
| TLS | Only on the border gateway's listener for other machines, TLS 1.3 with pinned keys (§2.7). The loopback listener is plain TCP. |
| WebSocket | No. |

Topics under `$frontear/` are reserved for the node. Clients may subscribe to them and may publish only control
requests.

## 12. FAQ

**Can a client on another machine connect?** Only another comando paired with this one, through the border gateway of
§9.1: a TLS 1.3 connection whose certificate's key is pinned, with a link's client id, that may publish to the
inboxes, replies and presences record 189 lists and subscribe to nothing. Any other client on another machine cannot.

**Who may change the links?** Whoever can sign with a trusted private key. The node reads its trusted keys at start.
To add or replace a key, restart the node with the new list of key files.

**Are control requests secret?** No. They are signed, not encrypted, and any local client can subscribe to
`$frontear/control/#`. Never put a secret in a request.

**Can a message arrive twice?** Yes, over a link, after a reconnect or a restart (§6). Make receivers tolerate a
repeat.

**Can a message get lost?** Not on a running link, once the node has acknowledged it at QoS 1. Two exceptions: the
cases counted in `dropped` (§7.1), and a node whose links are not yet restored (§8.3). A QoS 0 message has no
acknowledgement and can be lost.

**Does a link survive a restart?** Yes. Its configuration and its log are both on disk, and it drains its
backlog once its peer answers (§8.3).

**Does a subscriber get what a link delivered during its absence?** Only through a session that is still alive on the
node. A node restart ends every session.

**What stops a message from circling an octopus network forever?** No Local, plus the hop count (§2.3). Set
`hopLimit` to the longest path you want a message to travel.

**Can several links carry the same message?** Yes. Each link whose `out` filters match logs its own copy.

**How do I see who is connected?** Subscribe to `$frontear/presence/#` (§7.2).

**Why does a publish succeed with `0x10`?** MQTT 5 tells you that nobody subscribes to the topic. The node still
accepted the message.

## Where next

- [The frontear module](modules/frontear.md): the packages, the classes, the threads and the link log's file format.
- [The decision records](adr/README.md): why frontear is built this way (records 184, 185, 187 and 189).
- [Workbench §4.4](workbench.md#44-the-frontear-tab): the frontear tab, which reads the watch.
- [Architecture](architecture.md): where the octopus network sits in comando.
- [All documentation](README.md).
