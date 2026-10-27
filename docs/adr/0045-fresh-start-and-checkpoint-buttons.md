# 45. A fresh start and a checkpoint, as buttons

| Date | Status | Decided by |
|---|---|---|
| 2026-10-15 | accepted; the checkpoint counting it left out added by [record 46](0046-clear-buttons-text-coloured-by-kind-checkpoint-due.md) | Miguel and Claude |

## 1. Context

A session carries its conversation across every stop and start and across comando's restarts
([record 4](0004-a-session-open-in-another-terminal.md)). Starting over meant the dialog's advanced "Next start"
field. Sessions may also be asked to write their memory with a prompt such as `/reflect` before a conversation is left
behind, and that prompt was typed each time.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| fresh start | a button right of the plug, on the panel chip and the sidebar row, shown only while the session is stopped (closed or failed) and has a conversation; it sets the spec to a new conversation (Next start NEW, no id), saves `sessions.json`, then starts | visible where the plug is; hidden when there is nothing to leave behind, and while the session runs: unplug first, no stop-then-start sequence to carry |
| the old conversation | stays in Claude Code's files; the status bar names its id, and the import dialog lists it again | nothing is deleted |
| the Session tab | a start on a new conversation clears it; the tab shows one conversation | the old blocks belong to the old conversation |
| checkpoint | a button next to the fresh one, shown while the session has a checkpoint command; it sends the command as a prompt from the session's panel, as if typed there; a session with no open panel is refused with a notice | one click for the prompt sent most often before leaving a conversation |
| the command | a session attribute, "Checkpoint command", default `/reflect`, also for sessions saved before the field existed; emptied, the button is hidden | comando does not know what the command does; the user sets it |
| what is left out | counting turns since the last checkpoint, a "checkpoint first" question on the fresh start, a fresh start of a running session | small in use, large in state; added if missed |

Miguel decided a second button next to the plug that starts the session fresh, a button that sends `/reflect`, and
the lean version without sequencing; Claude decided how.

## 3. Consequences

`SessionSpec` gains `checkpoint` and `restarted()`; `Intent` gains `StartFresh` and `Checkpoint`; the adapter clears
the transcript on a NEW start. The session row has five controls: plug, fresh, checkpoint, model, effort.
