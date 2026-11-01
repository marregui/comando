# 131. An automatic checkpoint asks nothing; its decisions wait in the Approvals tab's Later group

| Date | Status | Decided by |
|---|---|---|
| 2026-11-01 | accepted; built; amended 2026-11-01 (see Amendments) | Miguel and Claude |

## 1. Context

[Record 129](0129-a-checkpoint-reflects-then-compacts.md) runs a session's checkpoint command, then `/compact`, from the
Checkpoint button or by comando when a turn ends past the context threshold. [Record 130](0130-a-session-locks-while-its-checkpoint-runs.md)
locks the session until that ends. A checkpoint command that asks the user something (a `/reflect` that wants a memory
change or a knowledge-base statement approved) holds its turn, and so the lock, until the card is answered. With several
sessions working, several can cross the threshold together: each locks, and their questions join the tool approvals of
the other sessions in one queue, in arrival order, with only the card's first word saying which session asked.

Miguel, 2026-11-01: automatic checkpoints should not end up producing decision cards; those come only from a checkpoint
he starts, and there answering is compulsory before the task goes on. He still wants to see the decisions held back, so
that when he is idle he can check and progress through them.

Two smaller faults came up on the way. The Approvals tab, which sits beside the focused session, shows every session's
cards with nothing grouping them. And while a session's checkpoint runs, its Session tab is greyed under the lock's veil,
so the cards shown in it look disabled although they answer (scratchpad task 42).

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| how an automatic checkpoint is told apart | comando sends the checkpoint command with `--auto` (`SessionSpec.AUTO_ARGUMENT`: `/reflect --auto`); the Checkpoint button and a typed command carry no argument | the prompt is the only channel to the command; `Reducer.isCheckpoint` already takes a command followed by arguments, so the lock of record 130 is unchanged |
| what `--auto` means | the command asks the user nothing and holds back each decision it would have asked; the kit's `/reflect` runs its watermark scope (memory and the scratchpad) | the session never waits on the user, so its lock lasts only as long as the work |
| where a decision held back goes | the session calls the MCP tool `decision_hold` (title, detail); comando keeps it in `AppState.later`, saved to `decisions.json` in the data root, at most 200, the oldest dropped first | comando reads nothing of a kit or a brain; a tool keeps it generic: any checkpoint command can hold decisions back |
| how it is shown | the Approvals tab's **Later** group, after the live cards, grouped by session: title, detail, time, **Settle** and **Dismiss**; the tab's title adds "· M later"; nothing comes forward and no desktop notification goes out | Miguel: for when he is idle |
| Settle | sends the decision's session its checkpoint command without `--auto`, from its panel, so it asks; the focused panel's session when the decision's own is gone; refused while the target's checkpoint runs (record 130) | a checkpoint the user starts is where decisions are answered; the session settles each with `decisions_settled` once answered |
| Dismiss | the decision leaves unasked | a decision that no longer matters needs no session |
| the live cards | one queue for the window, grouped: the focused session's cards first, under its name; then each other session's, the group holding the oldest card first | the cards that concern the session in front of the user come first, and none is hidden |
| the veil over the Session tab | lifted while the checkpointing session has a waiting card, back when none waits and the checkpoint still runs; the reducer's refusals are unchanged | a card that answers must look as if it answers (task 42) |
| agents | `decision_hold`, `decisions_settled`, `decision_settle` and `decision_dismiss` are actions (record 121); `ui_get_state` lists `later` with ids | an agent has every action the user has |

## 3. Consequences

- An automatic checkpoint never locks a session on a question; parallel sessions crossing the threshold together each
  checkpoint and go on.
- A checkpoint command that does not know `--auto` receives it as an argument; the kit's `/reflect` knows it. A command
  that ignores it may still ask, and its cards then appear as before.
- Decisions held back survive a restart; a session removed leaves its decisions to the focused session's Settle.

## 4. Amends

[Record 129](0129-a-checkpoint-reflects-then-compacts.md): the automatic checkpoint's prompt carries `--auto`.
[Record 130](0130-a-session-locks-while-its-checkpoint-runs.md): the Session tab's veil lifts while a card waits.

## Amendments

2026-11-01, first-release review: a decision's id is never given twice. It comes from the state's counter, which never
goes back, so a checkpoint that read ids earlier cannot settle a newer decision; `decisions.json` keeps the highest id
given (`lastId`), and the ids after a restart start above it. The Settle row reads: the decision's own session while it
exists with a checkpoint command, else the focused panel's session when it has one. Under Miguel's decision D3 the
automatic checkpoint runs at the threshold of [record 129](0129-a-checkpoint-reflects-then-compacts.md)'s amendment, a share
of the context window. Tested in `ReducerDecisionsTest` and `StoreTest`.

2026-11-01, Miguel's choice in the first-release review ("when it is done automatically, it definitely wants to carry on
working"; offered both kinds of checkpoint or the automatic one only, he chose the automatic one only): once the
compaction that follows an automatic checkpoint has ended normally, comando prompts the session to carry on with its
work, as [record 129](0129-a-checkpoint-reflects-then-compacts.md)'s amendment of the same date describes. The checkpoint
that asks nothing is thus also the one that resumes by itself; a checkpoint the user starts, whose decisions are answered
there, waits for the user's next prompt as before.
