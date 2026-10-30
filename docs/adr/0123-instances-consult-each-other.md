# 123. Instances consult each other through the comando Edge: presence, ask, reply

| Date | Status | Decided by |
|---|---|---|
| 2026-10-29 | accepted; built on one machine, without per-instance credentials; presence amended by [record 124](0124-the-sidebar-shows-the-instances.md) | Miguel and Claude |

## 1. Context

Miguel, 2026-10-29: specialist instances (a Comando Expert, with its own kit and a curated brain) take the work of
their field; a session of one instance consults the expert, and later any instance consults any other, up to a
brainstorm. [Record 122](0122-instances-name-kit-brain-data-root-edge.md) §2.4 chose the comando Edge as the broker and
the topic layout; this record builds the part one machine needs, all of it at once, as Miguel asked.

## 2. Decision

| Question | Rule |
|---|---|
| connection | each instance connects to the comando Edge (`127.0.0.1:18883`, MQTT 5) whenever the registry `~/.comando-edge.json` names a live Edge, whichever instance started it; it reconnects when the Edge comes back; client id `comando-<slug>`, or the slug alone when it is `comando` or starts with `comando-` (`comando-expert`, `Peers.Me.clientId`) |
| presence | `comando/v1/local/instance/<slug>/presence`, retained: name, slug, version, its sessions, busy, started (and, since [record 124](0124-the-sidebar-shows-the-instances.md), role and kit); a last will with an empty retained payload clears it when the instance dies; cleared on close |
| ask | MCP tool `instance_ask {instance, text, wait_seconds}`: publishes to `…/<peer>/inbox` with the MQTT 5 response topic `…/<me>/reply` and correlation data; waits up to `wait_seconds` (≤ 600) for the reply, else returns an id for `instance_reply {id, wait_seconds}` |
| answering | the peer prompts its session `consult-<asker slug>` (created on the first ask, in its own default folder, created by `peer:<slug>`, started) through its panel, headed `[from instance <name> · session <s>]`, so its user sees the question; when that turn ends it replies with the turn's text. Busy: the prompt waits its turn, as `session_send`'s does |
| who may ask | a peer may read presence and prompt by default (record 122 §2.4, trust); the data root's `agents.json` `{"peers": {"deny": ["<slug>"]}}` refuses a peer (reply `refused`) |
| listing | MCP tool `instances_list`: the peers with presence, their names, sessions and whether busy |
| brainstorm | no primitive of its own: a session asks each peer in turn and passes the others' answers on; the tool descriptions say so |
| what travels | only the text a session chose to send and the reply's text; never brain or kit files (record 122 §2.4) |
| Edge down | the tools say so and how to start it (the Edge toggle in any instance); presence resumes when it is up |

Not built: per-instance credentials and the Edge's access rules (record 122 §2.4, trust): on one machine and one user
the broker listens on `127.0.0.1` only; any local process of that user could publish. Bridging beyond the machine, the
board and roles stay proposed.

## 3. Consequences

- A specialist is consulted without its brain or kit being read by anyone else: isolation (the kit's instance guard)
  and consultation are separate paths.
- Every consultation is visible in both instances: the asking session's tool call, and the peer's `consult-<slug>`
  panel.
- The MQTT client (`com.hivemq:hivemq-mqtt-client`) is a new dependency of the `app` module only; `mcp` sees the broker
  through an interface, so its tests run without one.
