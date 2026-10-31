# 129. A checkpoint reflects, then compacts; comando runs it at the context threshold

| Date | Status | Decided by |
|---|---|---|
| 2026-10-31 | accepted; built; amended by [record 130](0130-a-session-locks-while-its-checkpoint-runs.md) (the session locks while its checkpoint runs) | Miguel and Claude |

## 1. Context

[Record 86](0086-a-checkpoint-clears-and-carries-its-summary.md) made the Checkpoint button send the session's
checkpoint command and, once that turn had finished, `/clear` the conversation and carry the turn's last answer into
the next prompt. The conversation started again from a summary of a few lines; what the turns before had read and
decided was gone from the context.

Miguel, 2026-10-31: "when I click the checkpoint button I want reflect and compact"; "reaching the checkpoint, crossing
the threshold means that at some point the session will automatically reflect, then compact". Asked who runs it at the
threshold, the session itself or comando, he chose comando, at a turn's end. The threshold is 400,000 tokens of
context: in his sessions, prompts that correct the session were 31 % of the prompts below 400k and 45 % between 400k
and 600k. Claude Code compacts on its own only near the end of the window.

A session already knows its context after every model call ([record 120](0120-the-context-follows-each-model-call.md)),
and `/compact` is a command comando sends from a session's panel ([record 53](0053-session-rows-compact-clear-copy-and-an-order-of-your-own.md)).
A hook in a session's own setup may also act when the context crosses a size; it has to know that comando will.

This record replaces record 86's clear and carried summary; the rest of record 86 stands.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what starts it | the Checkpoint button (`Intent.Checkpoint`, the `session_checkpoint` action), or a turn's end at the threshold; a checkpoint command typed or sent by another session is a prompt like any other | the button is the request; a typed `/reflect now` does not compact behind the user's back |
| when it compacts | when the checkpoint's turn has finished, as record 86 had it for the clear: nothing runs or is queued, no background work it started is still out, and the turn ran or carries text | the checkpoint's own work is done only then |
| what it sends | `/compact`, as a prompt from the session's panel, as the Compact command does (`TurnRules.sessionCommand`): a turn of its own, so the checkpoint's turn does not notify and the compaction's does | one way of compacting; the same conversation goes on |
| the Session tab | not emptied: the `/compact` prompt and the compaction's own notes show after the checkpoint's answer | the conversation is the same one; its history stays readable |
| a carried summary | none is written; a summary record 86 carried for a session still goes in front of its next prompt that is not a command, once, and a checkpoint while one waits runs its command alone | sessions saved before this record keep what they were promised |
| the threshold | a global setting, `sessions.checkpointThresholdTokens` in comando's `settings.json` ([record 106](0106-settings-in-the-data-root.md)), default 400,000 tokens, 0 for never; changed from the palette, Session ▸ Checkpoint threshold… | Miguel's figure; one value for every session, kept with the window's other settings |
| when it runs by itself | at the end of a session's turn that leaves the context at or above the threshold, when the session has a checkpoint command, runs, and its turn finished: no prompt queued (the queued prompt runs first and the check repeats at its end), no background work out, no checkpoint under way, not failed or interrupted; the checkpoint then runs as the button's does, `/compact` included | Miguel: comando, at a turn's end; a turn that is not the session's last, or that the user stopped, is not the moment |
| once per crossing | a checkpoint command sent, by any route, disarms the session; a turn that ends with the context below the threshold arms it again; a session armed when comando starts | the compaction brings the context under the threshold; a checkpoint whose conversation was kept is not repeated at every turn |
| a session with no checkpoint command | nothing runs | comando does not know what a checkpoint is; the user sets the command ([record 45](0045-fresh-start-and-checkpoint-buttons.md)) |
| the environment | a session launched while the threshold is above 0 gets `CONTEXT_CHECKPOINT_BY=comando`; every other process has the variable taken out. A session already running keeps what it was launched with | a hook that would checkpoint at that size on its own can leave the turn's end to comando |
| when it does not compact | the user sends a prompt after the checkpoint; the checkpoint's turn fails or is interrupted; the process ends; the answer is blank (a warning says the conversation is kept); a summary is still carried | record 86's cases: each keeps the conversation the user has |

Miguel decided that the Checkpoint button reflects then compacts, that crossing the threshold runs the same sequence,
that comando runs it at a turn's end, and the 400k threshold. Claude decided how: the compaction as the Compact
command's prompt, the setting's place and its palette command, once per crossing and when it re-arms, the cases that
wait, and the environment variable's lifetime.

## 3. Consequences

`TurnRules.turnEnded` sends `/compact` at the checkpoint's end where it sent `Effect.ClearConversation`, and runs the
checkpoint at the threshold; `Reducer` takes the threshold as a supplier, read at each turn's end, and runs none when
built without one, as tests do. `SessionState.checkpointClears` is `checkpointCompacts`; `checkpointArmed` is new.
`RootLaunch.checkpointBy` sets the variable; `ClaudeAdapter.checkpointThreshold` reads the threshold at each launch.
`App` holds the threshold for the reducer and the adapter; `ComandoUi.checkpointThreshold` hands it the setting at start
and after each change; `WindowLayout` names the key. `Effect.ClearConversation`, `ClaudePort.clearConversation`, the
adapter's clear and the stream mapper's quiet clear are gone; `StreamMapper.carried` stays, for a summary loaded with a
stored conversation.

Tested in `ReducerTest` (the button's compaction and its single notification; every case that keeps the conversation;
a checkpoint queued behind a prompt; a carried summary from before; the threshold's crossing, once per crossing, the
re-arming and a threshold of 0; a queued prompt, a failed or interrupted turn, background work and no command),
`ClaudeAdapterTest` (the variable present with a threshold, absent at 0), and off screen in `CheckpointCompactUiTest`
(`:app:uiTest`: the button clicked in the real window against a stand-in for `claude -p`, which receives `/reflect`
then `/compact`; the Session tab keeps the conversation).

## 4. Alternatives

| Option | Why not |
|---|---|
| the session runs `/reflect` itself when a hook sees the crossing | Miguel chose comando at a turn's end; a hook acts mid-turn, and comando would not know a checkpoint ran |
| keep the clear and the carried summary | Miguel asked for a compaction |
| the threshold in comando's state, set by an intent | it is a setting of the window, as the theme is; the reducer reads it as it reads the home folder, and an agent's action for it is not asked for |
