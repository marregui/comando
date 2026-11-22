# 189. frontear across machines: paired machines, mutual TLS, authenticated origin

| Date | Status | Decided by |
|---|---|---|
| 2026-11-21 | accepted; built in task 224; amends [record 186](0186-instances-trust-the-overseer.md) §3 (authority of a remote asker) and [record 187](0187-frontear-lab-node-per-instance.md) §2.1 (links to other machines) | Miguel and Claude |

## 1. Context

Miguel, 2026-11-21, after installing comando on a second laptop: "there are hard coded 127.0.0.1 addresses so this
would hinder inter laptop instance communication. what could we do about this".

Since [record 187](0187-frontear-lab-node-per-instance.md) each instance hosts a node on loopback, and the nodes of
one machine are each linked to every other found through the machine's registry. Nothing reaches a node from another
machine: the gateway listens on loopback, links dial `127.0.0.1`, peers are found in a file of this machine, and a
crashed peer is found by its pid.

Opening a node to a network as it is would be unsafe. CONNECT carries no credentials (the codec refuses a user name,
a password and AUTH), any client may subscribe to anything (`#` reads every question), the asker of a question is the
`from` its payload claims, a question from `main` carries the user's authority ([record 186](0186-instances-trust-the-overseer.md)),
and a question runs as a prompt in a Claude session in bypass mode ([record 123](0123-instances-consult-each-other.md)).

His choices (form of 2026-11-21): the machines paired are his own; a question from another machine that is not
granted the overseer's authority runs in a session in plan mode; mutual TLS always, a Tailscale tailnet included;
built at once.

## 2. Decision

### 2.1 Security

| Question | Rule |
|---|---|
| exposure | the loopback listener stays as it is; a second listener, off until a machine is paired, binds one address the user names (a Tailscale or LAN address, never the wildcard), on the port of the machine file `~/.comando-machine/machine.json` (default 18893), opened by the node of the instance `main` alone, the border gateway (a machine whose `main` is down is unreachable from the others); at most 64 remote connections, a TLS handshake bounded to 10 s, each failure counted and logged at most once a minute |
| transport | TLS 1.3 only, both sides authenticated: each machine has one Ed25519 key and a self-signed certificate, `machine.key` (PKCS#8) and `machine.crt` in `~/.comando-machine/`, readable by its owner alone; a TLS session is never resumed, so a machine unpaired is refused at its next connection; a pinning trust manager accepts exactly the certificates whose public key's SHA-256 is a paired machine's fingerprint, no certificate authority, no host-name check; the JDK's own TLS, no library |
| the unit of trust | the machine: a paired machine's instances may reach this machine's instances; inside one machine the trust of [record 186](0186-instances-trust-the-overseer.md) holds unchanged |
| what a remote connection may do | a connection authenticated as machine `m` must use a link's client id, `<slug>~<node>` with `<node>` a node of this machine, else CONNACK 0x87; it may publish only to `comando/v1/m/instance/+/presence` and to `comando/v1/<this machine>/instance/+/inbox` and `…/reply`; it may subscribe to nothing; `$frontear/` is refused to it; a refused PUBLISH is acknowledged 0x87; an instance keeps at most 64 instances of one other machine, a presence of a new one past them dropped and counted once those no longer present are forgotten; an instance makes at most 8 consult sessions for the instances of one other machine, and a question that would make a ninth is refused with a reply that says so |
| who sent it | on a remote connection the gateway removes every user property `frontear-origin` the client sent and adds `frontear-origin=@<m>`, `m` from the certificate; a border gateway's link carries the user properties on, so the instance asked reads which machine sent the question; a question from another machine is answered only when its payload's `from` is `<slug>@<m>` with that `m` and its response topic is `comando/v1/<m>/instance/<slug>/reply`; a client on this machine is trusted as today ([record 186](0186-instances-trust-the-overseer.md)), so a `frontear-origin` it sets is not checked; a question from this machine carries no `frontear-origin` and is read as today |
| authority | the instance `main` of a machine is the overseer on that machine only; a question from another machine is marked `[from instance <slug>@<m> · peer]`, and its consult session runs in plan mode (`SessionSpec.planMode`, kept in `sessions.json`), unless the machine's pairing grants `overseer`: then `main@m` is marked `· overseer` and its consult session runs as a local one |
| what another machine sees | a presence published for other machines carries the instance's name, slug, version, role, whether it is busy and its sessions' names; folders, branches, prompts and replies stay on this machine, unless the machine is granted `overseer` |

### 2.2 Names, topology, liveness

| Question | Rule |
|---|---|
| names | a machine has an id, `[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?` (1 to 32 characters, no dash first or last), by default its host name lowercased with other characters as `-`, kept in `~/.comando-machine/machine.json`; an instance is `slug@machine`, and a bare slug still names an instance of this machine; an instance's name may not contain `@` |
| topics | traffic between machines uses `comando/v1/<machine>/instance/<slug>/{inbox,reply,presence}`; `comando/v1/local/…` stays the traffic of one machine; a node subscribes for its instance to both |
| pairing | by hand, on both machines, from the Machines window or the MCP tool `machines`: each shows this machine's token, one line `comando-pair:1:<machine>:<host>:<port>:<fingerprint>`; given another machine's token it stores it in `~/.comando-remotes.json` (version 1, owner-only: per machine its host, port, fingerprint and `grant` `peer` or `overseer`, `peer` by default) after the user confirms the fingerprint; a machine is unpaired the same way; a change is read at the next look (5 s) |
| links to another machine | the border gateway keeps one link per paired machine, id `main@<m>` (its client id `main~main@<m>`, which the other border gateway takes as aimed at its node `main`), over TLS to that machine's border gateway, out filters `comando/v1/<m>/instance/+/inbox`, `comando/v1/<m>/instance/+/reply` and `comando/v1/<this machine>/instance/+/presence`, hop limit 2; every other node of this machine adds to its local link to the border gateway the out filters `comando/v1/<m>/instance/+/inbox` and `…/+/reply` of each paired machine and `comando/v1/<this machine>/instance/<its slug>/presence`; the border gateway adds to its local link to each instance `X` the out filters `comando/v1/<this machine>/instance/X/inbox`, `…/X/reply` and `comando/v1/<m>/instance/+/presence` of each paired machine, and these links have hop limit 3: a question goes asker's node → border gateway → the other border gateway → the node asked, and nothing goes further |
| liveness | the pid check stays for this machine's entries; a remote instance is known from its presence (retained on the border gateways) and present while that presence is younger than 30 s; its machine shows the state of the border gateway's link to it, connected or not connected since it last was, as the border gateway last saw it; a question to it still queues |
| expiry | a presence sent to another machine expires in 30 s (MQTT message expiry), a question when its asker stops waiting; a link drops an expired message at send |

### 2.3 What stays on this machine

The signed control plane (`$frontear/control`), each instance's own client to its node, the registry
`~/.comando-fabric.json`, the port probe, `frontear_read`, the frontear tab and MCP.

## 3. Alternatives not taken

| Alternative | Why not |
|---|---|
| plain MQTT bound to a Tailscale address | any process on any device of the tailnet reaches a session in bypass mode |
| a user name and password in CONNECT | a shared secret in files on both sides and, without TLS, on the wire |
| a certificate authority, or a TLS library | a few laptops of one user need no authority; [record 184](0184-frontear-comandos-own-mqtt-octopus-network.md) allows the JDK and `core` only |
| a signed question (`Seal`) instead of an authenticated connection | the seal's 30 s window refuses a question that waited in a link's log for a laptop asleep, which is what the links are for |
| every instance's node linked to every remote node, hop limit 1 | each instance would open a network listener, and the octopus network would need a directory of the other machine's ports before its first link; one border gateway per machine is one address to protect and one link per pair of machines |
| discovery by mDNS | none in the JDK, no multicast over Tailscale, and it announces the laptop on every network it joins |
| the remote `main` as the overseer | two overseers, and a stolen laptop would be one; the grant is per machine and off by default |
