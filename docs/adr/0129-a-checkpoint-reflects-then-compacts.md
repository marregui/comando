# 129. A checkpoint reflects, then compacts; comando runs it at the context threshold

| Date | Status | Decided by |
|---|---|---|
| 2026-10-31 | accepted; built; amended by [record 130](0130-a-session-locks-while-its-checkpoint-runs.md) (the session locks while its checkpoint runs) and [record 131](0131-an-automatic-checkpoint-asks-nothing.md) (the automatic checkpoint carries `--auto`); the threshold amended 2026-11-01 (see Amendments: 10–100 %, no off); after an automatic checkpoint the session carries on, amended 2026-11-01 (see Amendments) | Miguel and Claude |

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
| after an automatic checkpoint (amended 2026-11-01) | once the compaction's turn has ended normally, comando sends the session one prompt from its panel, `Reducer.CONTINUE_PROMPT`: "Continue the work the checkpoint paused, from the summary above: its next step first."; not after the button's or a typed checkpoint; not when a prompt was sent or queued meanwhile (it runs instead), when the checkpoint's or the compaction's turn failed or was interrupted, when the process ended, or when the compaction was skipped (a blank answer); the prompt's turn is an ordinary one | Miguel's choice: after an automatic checkpoint the session "definitely wants to carry on working"; after the button only "likely", so it waits for his next prompt |

Miguel decided that the Checkpoint button reflects then compacts, that crossing the threshold runs the same sequence,
that comando runs it at a turn's end, and the 400k threshold. Claude decided how: the compaction as the Compact
command's prompt, the setting's place and its palette command, once per crossing and when it re-arms, the cases that
wait, and the environment variable's lifetime.

## 3. Consequences

`TurnRules.turnEnded` sends `/compact` at the checkpoint's end where it sent `Effect.ClearConversation`, runs the
checkpoint at the threshold, and, after an automatic checkpoint's compaction, sends the carry-on prompt
(`SessionState.checkpointContinues` carries the automatic origin from the checkpoint command to the compaction's end); `Reducer` takes the threshold as a supplier, read at each turn's end, and runs none when
built without one, as tests do. `SessionState.checkpointClears` is `checkpointCompacts`; `checkpointArmed` is new.
`RootLaunch.checkpointBy` sets the variable; `ClaudeAdapter.checkpointThreshold` reads the threshold at each launch.
`App` holds the threshold for the reducer and the adapter; `ComandoUi.checkpointThreshold` hands it the setting at start
and after each change; `WindowLayout` names the key. `Effect.ClearConversation`, `ClaudePort.clearConversation`, the
adapter's clear and the stream mapper's quiet clear are gone; `StreamMapper.carried` stays, for a summary loaded with a
stored conversation.

Tested in `ReducerTest` (the button's compaction and its single notification; every case that keeps the conversation;
a checkpoint queued behind a prompt; a carried summary from before; the threshold's crossing, once per crossing, the
re-arming and a threshold of 0; a queued prompt, a failed or interrupted turn, background work and no command; the
carry-on prompt after an automatic checkpoint, and each case that sends none),
`ClaudeAdapterTest` (the variable present with a threshold, absent at 0), and off screen in `CheckpointCompactUiTest`
(`:app:uiTest`: the button clicked in the real window against a stand-in for `claude -p`, which receives `/reflect`
then `/compact`; the Session tab keeps the conversation).

## 4. Alternatives

| Option | Why not |
|---|---|
| the session runs `/reflect` itself when a hook sees the crossing | Miguel chose comando at a turn's end; a hook acts mid-turn, and comando would not know a checkpoint ran |
| keep the clear and the carried summary | Miguel asked for a compaction |
| the threshold in comando's state, set by an intent | it is a setting of the window, as the theme is; the reducer reads it as it reads the home folder, and an agent's action for it is not asked for |

## Amendments

2026-11-01, [record 131](0131-an-automatic-checkpoint-asks-nothing.md): the automatic checkpoint sends the checkpoint command with `--auto`, so the command asks the user nothing and holds its decisions back for the Approvals tab's Later group; the Checkpoint button and a typed command are unchanged.

2026-11-01, Miguel's decision D3 of the first-release review: one threshold, a share of the session's own context
window, 40 % until the user chooses another. It replaces both the 400,000 tokens of the automatic checkpoint and the 60 %
at which `Reflection` made a session due: a session falls due and its automatic checkpoint runs at the same turn's end.
The setting is `sessions.checkpointThresholdPercent` in comando's `settings.json`, 10 to 100: the minimum is 10 % and
there is no off (Miguel, same review), so the reducer reads any other value as the nearest of the two (`Reflection.clamp`)
and every session comando launches carries `CONTEXT_CHECKPOINT_BY=comando`; the palette's Session ▸ Checkpoint
threshold… asks for a percentage. The token count kept before under
`sessions.checkpointThresholdTokens` is not read, since no window converts it to a share: the default holds until the user
chooses, and the first choice removes the old key. A session whose context window is not known yet falls due at no size.
A checkpoint command sent by any route leaves the session not due and not armed until a turn ends below the threshold,
as the automatic checkpoint was. The intent log records the threshold in its first line and each change before the next
intent, so a replay runs the checkpoints the run had. Tested in `ReflectionTest`, `ReducerTest`, `SettingsTest`,
`ClaudeAdapterTest` and `IntentLogTest`.

2026-11-01, Miguel's choice in the first-release review: after an automatic checkpoint the session carries on. Asked
whether a session should carry on working after a checkpoint, he said: "when the session reflects and then compacts via
the checkpoint, I click it, I likely want to carry on working. when it is done automatically, it definitely wants to
carry on working." Offered a carry-on after both the automatic and the button's checkpoints, or after the automatic one
only, he chose the automatic one only. Once the compaction that follows an automatic checkpoint has ended normally, the
reducer sends the session one prompt from its panel, as it sends `/compact`: `Reducer.CONTINUE_PROMPT`, "Continue the
work the checkpoint paused, from the summary above: its next step first.", generic text that names no kit. None follows
the Checkpoint button, a typed checkpoint command or Settle; none when a prompt was sent or queued meanwhile (that prompt
runs instead), when the checkpoint's turn or the compaction's failed or was interrupted, when the process ended, or when
the checkpoint gave a blank answer and nothing was compacted. `SessionState.checkpointContinues` holds the automatic
origin from the checkpoint command to the end of the compaction's turn and is cleared on each of those ends; a session
that stops or fails clears it with its status. The carry-on prompt's turn is an ordinary one: not locked, it notifies
at its end, and the session, re-armed by the compaction, falls due again at the next crossing. The compaction's turn no
longer notifies, since the session works on. The same review made the intent log's replay apply each intent under the
threshold logged for it (`IntentLog.Replay.loop`), where it had applied every intent under the first. Tested in
`ReducerTest` (`anAutomaticCheckpointCarriesOnOnceItsCompactionHasEnded`,
`noCarryOnAfterTheButtonsCheckpointOrAnAutomaticOneThatEndsOtherwise`) and `IntentLogTest`
(`theCheckpointThresholdIsLoggedAndEachIntentReplaysUnderItsOwn`).

2026-11-01, the first-release review's last round: a crossing starts at a turn whose last model call read a
context below the threshold. The count Claude Code reports after a compaction (`post_tokens`, carried as
`SessionTurnEnded.estimated`) is shown but never re-arms the automatic checkpoint, so the paragraph above no longer
holds where it says the compaction re-arms the session. After a carry-on (`SessionState.carriedOn`), a turn that ends
above the threshold runs no automatic checkpoint until a prompt from the user or another session has run, so the
checkpoint and its carry-on never repeat on their own. A fresh start and a `/clear`'s new conversation still arm it. The
threshold logged for an intent is the value the reducer applied to it, read once per intent; without a log the reducer
reads the setting. Tested in `ReducerTest` and `IntentLogTest`.
