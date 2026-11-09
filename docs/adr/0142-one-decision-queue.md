# 142. One decision queue

| Date | Status | Decided by |
|---|---|---|
| 2026-11-08 | accepted; built | Miguel and Claude |

## 1. Context

Record 131 gave decisions a place to wait: the Approvals tab's Later group, filled by `decision_hold`, settled or
dismissed one at a time. Three ways of asking Miguel stayed apart: a question card (AskUserQuestion) that nobody
answered within the approval timeout was lost with its call; a held decision could only be settled, not answered;
and the scratchpad's "Waiting on Miguel" table was written by hand beside the Later group, so the two drifted. Miguel
accepted one queue on 2026-11-08 (task 77, proposal 4).

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what a decision carries | its title, detail and options; no options means the answer is a typed line | the card offers what the session would have asked |
| how it is answered | each Later card has one toggle per option and an Other answer line, as the question card has, or a typed line alone when it has no options; one "Send answers" button sends every answered card at once, in the group's order; unanswered cards stay | a batch of decisions is answered in one sitting, the same way a question is |
| where answers go | one prompt per session, `Answers to the decisions you held for me:` and a line per decision, to the decision's session, or the focused panel's session when it is gone; the answered decisions leave the group, saved once; a session running its checkpoint (record 130) or stopping keeps its answers on the cards, and a notice says so | a session gets its answers together, not one turn per answer, and a checkpoint is never cut short |
| a question that times out | its card is withdrawn and the call denied as before, and each of its questions becomes a Later decision, its options the question's labels; a question that took several options says so in its detail, to be answered in Other answer | an unanswered question waits instead of being lost |
| a permission card that times out | denied, as before | nothing is left to answer once the call is gone |
| `decisions.json` | gains `options`; a file written before reads with none | no new version, as record 133 |
| the scratchpad's table | the kit's `waiting-board` generates "Waiting on Miguel" from `decisions.json`; reports file decisions with `decision_hold` | one source for both lists |

## 3. Consequences

`Decision` gains `options`, `DecisionHeldBack` takes them, and `DecisionsAnswered` is added; it is not offered to
agents, since a session holds decisions for the user and does not answer its own. A decision leaves the group only once
its prompt went out; with no session to take it, it stays and a notice says so. `decision_hold` takes `options`.
`McpTools.ask` holds a timed-out question back. Tested in the reducer, store and tool tests, `ApprovalInboxAnswersTest`,
and `LaterDecisionsUiTest` and `QuestionLaterUiTest` on `:app:uiTest`.

An older comando reading `decisions.json` ignores `options` and shows the decisions as before.

## 4. Alternatives

An agent tool that answers held decisions would let a session answer for Miguel; the queue exists for his answers.
Each timed-out question kept as a card of its own kind would give the Later group two sorts of card for one thing.
