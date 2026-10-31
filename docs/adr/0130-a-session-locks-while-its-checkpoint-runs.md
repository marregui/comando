# 130. A session locks while its checkpoint runs

| Date | Status | Decided by |
|---|---|---|
| 2026-10-31 | accepted; built | Miguel and Claude |

## 1. Context

[Record 129](0129-a-checkpoint-reflects-then-compacts.md) made a checkpoint the session's checkpoint command, then
`/compact` once that turn has finished, run by the Checkpoint button or by comando at the context threshold. Until the
compaction has ended, a prompt typed in the session's panel, a fresh start, another checkpoint or a compaction would
act on a conversation that is being summarised: record 129 keeps the conversation when a prompt follows the
checkpoint, so a prompt typed by habit cancels the compaction the user asked for.

Miguel, 2026-10-31: "while I am working on a session and then I hit reflect, I would like the tab and all views
associated with it to become read only and collectively visually 'disabled' so that I cannot do anything other than
stop the turn that is reflecting and carry on with the session."

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what locks | every run of the session's checkpoint command: the Checkpoint button, the automatic checkpoint at the threshold, and the command typed or sent by another session | the reducer treats them alike already (`TurnRules`); a checkpoint is a checkpoint whoever sent it |
| how long | from the command's send until its turn, and every turn queued before it, has ended; when `/compact` follows (record 129), until the compaction's turn has ended | the compaction is the checkpoint's last step |
| what lifts it | the compaction ending, or the checkpoint ending without one: interrupted, failed, a blank answer, the process ended, a prompt another session queued after it (its turn then runs unlocked) | record 129's keep-cases; a session with no turn running is never locked |
| the state | `SessionState.isCheckpointing()`: a turn runs and either `checkpointTurns`, the turns still to end up to the checkpoint or its `/compact`, is above 0, or `checkpointCompacts` is set; derived from the session's live state, never stored | a restart has no process and so no lock; a lock cannot outlive the turns it covers |
| what is refused | a run from the session's panels, the checkpoint, any session command (Compact), a fresh start, its settings, model and effort, removing it or its own panel, clearing its Session tab; refused by the reducer with no notice | the window offers none of them meanwhile; a refusal it never asked for needs no words |
| what goes through | an interrupt (the panel's Stop, ⌘.), a stop (the plug), a prompt another session or an agent sends, which queues as it does while the session is busy | Miguel: stop the turn and carry on; other sessions' prompts are not the user's typing |
| an agent's action on it | refused with an answer: the action tools ask `Reducer.refusedWhileCheckpointing` before dispatching and reply "refused: the session is running its checkpoint (record 130)"; `session_interrupt` and `session_stop` go through | Miguel, 2026-10-31: an agent learns why its action did nothing (tested in `ActionToolsTest`) |
| the panel | its text reads only (`setEditable(false)`); Run and Clear off; Stop on; text a prompt from another session appends is written in | read-only, not disabled: the text can still be selected and copied |
| the look | the session's panels and its Session tab under the veil that greys a closing window (`Theme.VEIL`); their tabs' titles in the look and feel's disabled text colour; every session-bar control but the plug off, the activity purple: "checkpoint · compacting · 12 s · Stop to carry on" | one disabled group in colours the window has; purple is the checkpoint's colour in the bar |
| the sidebar | the session's menu keeps Open, Stop, Interrupt, Copy details and Clone; Fresh, Checkpoint, Compact, Rewrite, Model, Effort, Edit and Remove are off | Clone and Copy read the session, Open only shows it |
| the Session tab | greyed, not blocked: an approval card the checkpoint's own turn asks for can still be answered | a checkpoint waiting on an approval would otherwise wait until stopped |
| background work | while the checkpoint's turn has handed work to the background and no turn runs, the session is not locked; the turn that reads the work locks it again | Stop interrupts a turn; with none running, a lock would leave nothing to stop |
| other sessions | untouched: their panels, the file and SQL panels, the Approvals tab | Miguel: the session's views |

Miguel asked for the lock, its scope (the session's tab and views) and the one way out (stop the turn). Claude decided
the rest by consistency with the window's rules: which checkpoints lock, where it lifts, the refusals, the look, the
background-work gap and the Session tab's approvals.

## 3. Consequences

`SessionState` gains `checkpointTurns`, `withCheckpointTurns` and `isCheckpointing()`. `TurnRules.runPrompt` sets
`checkpointTurns` to the queue's length when it sends a checkpoint command; `TurnRules.turnEnded` counts it down by the
turns that ended, clears it when nothing is left to run, and sets it again for the `/compact` it sends;
`SessionRules.sessionExited` clears it. `TurnRules.refused` lists the refused intents and `Reducer` drops them before
any rule runs. In the window: `LockVeil` (ui, panels) paints the veil; `EditorPanel.locked()`, read-only text, Run and
Clear off; `EditorPanel.editable()` answers for a log only, so another session's prompt is still written into a locked
panel; `Workspace` greys the tab titles, `LowerPanel` the Session tab, `SessionBar` and `SessionsSidebar` turn their
controls off, and the palette's Compact, model and effort commands and the last result's insertion wait.

Tested in `ReducerTest` (the lock for the button, the automatic and the typed checkpoint, through `/compact`, lifted
when the compaction ends; a checkpoint queued behind a prompt; lifted by an interrupt with no `/compact`, by a failed
turn, an interrupted compaction and a process that ends; every refused intent refused with no effect and no notice;
another session, a panel without one and a prompt another session sends going through), `CheckpointLockTest` (the
panel, its tab, the Session tab, the bar and the sidebar's menu, locked and unlocked, another session's untouched) and
off screen in `CheckpointLockUiTest` (`:app:uiTest`: the Checkpoint button clicked against a stand-in for
`claude -p`; the panel not editable, Checkpoint, Fresh and Compact off, Stop on; Stop unlocks it with no `/compact`
sent; a second checkpoint locked through its `/compact` and unlocked when the compaction ends).

## 4. Alternatives

| Option | Why not |
|---|---|
| a modal cover over the whole window, as when it closes | locks the other sessions, which Miguel keeps working in |
| disable the panel's text instead of making it read-only | the text could not be selected or copied, and another session's prompt could not be written in |
| a notice for each refused intent | the controls are off; a refusal comes only from a key or an agent, and a notice per keystroke is noise |
| lock only the button's and the automatic checkpoint | a typed `/reflect` summarises the same conversation; the user would type into it by habit just the same |
| keep the lock through background work the checkpoint handed off | nothing runs to interrupt meanwhile; the lock could only be left by stopping the process |
