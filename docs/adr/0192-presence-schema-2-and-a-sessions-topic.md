# 192. an instance's presence carries its sessions' state only; their details travel on a topic beside it

| Date | Status | Decided by |
|---|---|---|
| 2026-11-22 | accepted; built in task 232; changes the presence of [record 184](0184-frontear-comandos-own-mqtt-octopus-network.md) to schema 2 | Miguel and Claude |

## 1. Context

Each instance publishes its presence, retained, on `comando/v1/local/instance/<slug>/presence`, when it connects and
when it changes, at most every 2 s. Schema 1 held every session with its status, its activity text and its turn
timers. On 2026-11-22 main's presence was 5.9 KB and changed at almost every publish, since the activity text and the
timers move while a turn runs: one 5.9 KB retained message every ~4 s on each link, and one more record in the log of
every link that was down.

## 2. Decision

| Question | Rule |
|---|---|
| presence | schema 2: the instance's fields and, per session, its name, whether it is busy and its state; published when one of those changes |
| details | the other fields of each session go, retained, on `comando/v1/local/instance/<slug>/sessions`, published before the presence and only when they change; a link carries the topic beside the presence |
| activity | what a turn is doing at the moment is not sent; a peer's Instances tab shows a busy session as busy |
| reading | a reader takes schema 1 with every field it carries, and schema 2 with the details merged in from the sessions topic; details with no presence show no instance |
| questions | questions and replies stay schema 1, since a question of a later schema is refused |

## 3. Consequences

- 20 sessions take 1,345 bytes of presence against 5.9 KB, and a turn's progress publishes nothing.
- An instance on an older comando reads a schema 2 presence as one with no session details.
- An instance's will clears only its presence; its details stay retained until its next start, and are ignored with
  no presence beside them.
- Across machines only the presence travels; peers on another machine see name, busy and state.
