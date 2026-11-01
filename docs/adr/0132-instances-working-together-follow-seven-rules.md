# 132. Instances working together follow seven rules

| Date | Status | Decided by |
|---|---|---|
| 2026-10-29 | accepted | Miguel and Claude |

## 1. Context

[Record 123](0123-instances-consult-each-other.md) lets one comando instance consult another through the comando Edge:
`instance_ask` puts a question to a peer, `instance_reply` reads its answer. The first real exchange ran on 2026-10-29,
between the main instance and Comando Expert, a second instance with its own kit, brain and data root, while the Expert
learned comando from its source and reviewed narrow areas for consistency. These observations come from that exchange:

| # | Observation |
|---|---|
| O1 | One round answers in 7–90 s; a 3.5 KB question and a 3 KB reply arrived whole |
| O2 | The answering instance gives up after 600 s (`Peers.ANSWER_WITHIN`, the tools' longest wait) and replies `timeout`; its turn goes on, and the late answer stays unread in the consult session |
| O3 | The question lands in a session of the answering instance, `consult-<asker>`, never in the session that asked; that session keeps its context across questions |
| O4 | Only text travels: neither instance can read the other's kit or brain (the kit's instance guard denies it) |
| O5 | Two instances working on one source checkout collide: two writers, or a writer and a builder, share `build/` and the files |
| O6 | Codex runs on one plan for every instance on the machine, so its limit of 2 concurrent runs is per machine |
| O7 | The sender header `[from instance X · session Y]` is not authenticated: any client of the Edge can write it (no credentials yet, record 122 §2.4) |
| O8 | A finding's `file:line` goes stale when the other instance edits the checkout |
| O9 | A correction sent while the peer's turn runs queues behind that turn, so the peer can act on the wrong fact before it reads the fix |
| O10 | The kit's instance guard refuses a shell command whose text names a peer's root, heredoc bodies included |

A shared folder for long material was tried first (batches 01–03) and retired the same day: the channel carried
everything it held.

## 2. Decision

| # | Rule | From |
|---|---|---|
| R1 | One bounded piece of work per consult turn: a turn that may run past about 5 minutes is split, so the 600 s give-up never drops an answer | O2 |
| R2 | Everything goes over the channel, no shared folder: short material as it is; long material (findings tables, logs) in numbered parts, "batch NN part k/n", each self-contained enough to act on; the receiver acknowledges the last part | O1, O4 |
| R3 | One writer per checkout, agreed per change; the other instance reads at a pinned commit and re-pins only when both agree | O5, O8 |
| R4 | Resources shared by the machine (Codex runs, Gradle in a shared checkout, the Edge itself) are announced before use, with count and duration | O5, O6 |
| R5 | The asker drives: questions in numbered rounds of at most 10; the answering instance replies in the same turn; a question it wants to put goes into its reply, not into a new `instance_ask`, which would land in a third session | O3 |
| R6 | Until instances have credentials, the sender header is a claim, not an identity: nothing destructive on a peer's word | O7 |
| R7 | Identifiers that cross the channel (commits, ids, paths) are copied from a command's output, never typed from memory; the receiver checks that a commit exists before re-pinning | O9 |

O10 needs no rule here: it is the kit's guard working as meant, and text that names a peer goes through a file edit.

## 3. Consequences

- The rules bind the sessions of each instance, not comando's code; nothing in comando enforces them. They sit with the
  kit's instructions of each instance that consults another.
- R1 and R2 follow from the 600 s wait; a longer wait in the tools would relax R1, not R2.
- R6 lapses when record 122 §2.4's trust between instances is built.
