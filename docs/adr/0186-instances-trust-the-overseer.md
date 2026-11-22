# 186. Instances trust each other on frontear, and main is the overseer

| Date | Status | Decided by |
|---|---|---|
| 2026-11-21 | accepted; built in task 218; supersedes [record 132](0132-instances-working-together-follow-seven-rules.md) R5's third-session clause and R6; amended by [record 189](0189-frontear-across-machines.md) (a remote asker's authority) | Miguel and Claude |

## 1. Context

[Record 123](0123-instances-consult-each-other.md) lets a session of one instance ask another instance a question
with `instance_ask`; the peer answers in its session `consult-<asker>`. [Record 132](0132-instances-working-together-follow-seven-rules.md)
added rules for the sessions: R5 kept a question inside the consult session ("a question it wants to put goes into
its reply, not into a new `instance_ask`, which would land in a third session"); R6 held that "the sender header is a
claim, not an identity: nothing destructive on a peer's word". The tool descriptions and the system prompt appended
to every session added "only when the user asked you to".

On 2026-11-20 alice's session Comando G asked bob, with `instance_ask`, to relay a greeting to bob's session Comando
G. The question landed in bob's `consult-alice`, which refused to `session_send` to Comando G "because only the user
can ask it to start a turn in one of your sessions, and a request from another instance doesn't count". The main
instance had to relay it. Every consult also took two hops, `consult-<asker>` then the session meant, since
`instance_ask` could not name a session.

Miguel, 2026-11-21: instances speak freely through frontear, without those restrictions; the machine is a trusted
environment; the main instance is the overseer, and the others are surrogates with their own kit and brain that do
as it says; instances may ask each other to create sessions; Miguel overrules main at any time, being an instance
himself. Each instance's kit and brain stay its own: only messages cross.

## 2. Decision

| Question | Rule |
|---|---|
| addressing | `instance_ask {instance, text, session?, wait_seconds}`: `session` names the session in the peer that takes the question; absent, `consult-<asker>` as before. On the wire the question carries it as `to`; its `session` stays the asker's session, so the schema stays 1 |
| a session the peer lacks | made as a consult session is: created by `peer:<asker>`, in the peer's default folder, with its own panel, then prompted; a name the peer's reducer refuses for a new session is refused with its reason |
| an older peer | one that ignores `to` answers from `consult-<asker>`; the asker's result says which session answered instead of the one named |
| a session that waits | a session blocked in its own `instance_ask` or `instance_reply` wait refuses a question at once, with an error reply: the asker may be the one it waits for, and both would wait out their 600 s |
| the header | `[from instance <name>[ · overseer][ · session <s>]]`: the shape of record 123, with ` · overseer` after the name when the asker is main |
| main | the instance with no name (slug `main`, the user's own, the oldest) is the overseer: a message from main carries the user's authority, and a session acts on it as on the user's own request, creating, prompting and reading sessions included. The user overrules main at any time |
| peers | the other instances: a session acts on a peer's request to talk, to create sessions and to relay to a session without asking the user; something destructive (deleting files, killing processes, pushing) needs main or the user |
| what crosses | only messages; an instance never reads or writes another instance's folders, kit or brain |
| messages | written as markdown (short lines, headings, lists, tables): the user reads them in the receiving panel |
| where it is said | the system prompt appended to every session (`UiInstructions.text(<instance name>)`), which ends saying whether its instance is main or a surrogate, and the descriptions of `instance_ask` and `session_send`; the octopus network is named frontear ([record 184](0184-frontear-comandos-own-mqtt-octopus-network.md)) |
| presence | the key saying whether an instance hosts frontear is `host`; `edge`, from before, is still written beside it with the same value and read when `host` is missing, for one release |
| names | only main, which has no name, is `main` to the others and marked the overseer: an instance does not start with a name equal to `main`, `overseer` or `comando` in any case, one whose id is `main`, or one holding `·`, which would pass for a role or a session in a header |

Superseded in record 132: R5's clause that a question goes into the reply rather than into a new `instance_ask`
(a session may now ask any session of a peer, by name), and R6 (a peer's word is enough for anything but the
destructive, and main's is the user's).

## 3. Consequences

- One hop instead of two: a session asks the session it means, and a relay needs no consult session in between.
- The header is still not authenticated: any local process of the user can publish a question as main. The trusted
  local machine is the premise (Miguel); signing asks with the instances' Ed25519 keys is the follow-up if that
  changes.
- The refusal of a waiting session is checked when the question arrives: a session that starts its own wait after
  its question was taken is not refused, and the two waits end at their bounds as before.
- An older instance reading the new presence reads its `edge` key, so it shows the host as before.
- The rules for the sessions sit in comando's appended system prompt, so every instance's sessions read the same
  text whatever their kit says; a kit may still add its own.
