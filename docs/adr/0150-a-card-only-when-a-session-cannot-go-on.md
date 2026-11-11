# 150. A card only when a session cannot go on; the Logbook tab lists what waits on the user

| Date | Status | Decided by |
|---|---|---|
| 2026-11-10 | accepted; built | Miguel and Claude |

## 1. Context

Miguel, 2026-11-10: comando is to be his digital logbook. Reading what the Approvals tab and the scratchpad did with a
decision for him (scratchpad task 97) showed three faults.

| Finding | What it did | Why it was a fault |
|---|---|---|
| one decision, three places | a decision could be held in the Approvals tab's Later group (`decisions.json`), in the generated "Waiting on Miguel" table of the scratchpad's `INDEX.md`, and in a hand-written row under it | three copies drift apart: a row said a memory change was held in Later while another batch sat there too |
| the Later group | filled by automatic checkpoints (`decision_hold`) and by question cards nobody answered in time; answered by **Settle** (the session's checkpoint, which then asks), **Dismiss**, or toggles and **Send answers** ([record 131](0131-an-automatic-checkpoint-asks-nothing.md), [record 142](0142-one-decision-queue.md)) | a second answer flow that differs from an ordinary card; "Settle" names a mechanism, not an outcome; nothing interrupts, so held decisions piled up unseen |
| the card timeout | a card was withdrawn and denied after 55 minutes because comando's MCP configuration gave its server a 60 minute timeout | a question asked before a meeting was gone after it, and came back as a Later item |

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| when a card appears | only when a session cannot go on without the user: a tool, a question, a plan, a SQL write, a connection | the Approvals tab holds what blocks, and nothing else |
| the Later group | removed, with `decision_hold`, `decisions_settled`, `decision_settle`, `decision_dismiss`, `ui_get_state`'s `later`, Settle, Dismiss and Send answers | one way to answer: a card |
| how long a card waits | the approval timeout is 24 hours; comando's MCP configuration gives its server 24 hours and 5 minutes, so the server never gives up first; a question unanswered by then is denied, the denial saying the user did not answer in 24 hours, and nothing is held | a card waits while its session waits, as a person would |
| a decision that blocks nothing | it lives in its task's entry in the scratchpad's `INDEX.md`, as a `**Needs you:**` line with one sub-item per decision; the session that has it, or the kit's automatic checkpoint, writes it there | one place holds each decision, where the work is recorded |
| how it is asked | the Logbook tab's **Ask me its decisions in** sends a running session a prompt; the session asks the decisions as ordinary question cards, recommended option first, then writes the answers into the task | one answer flow for everything |
| the Logbook tab | the sidebar's fourth tab, after Files and before Instances, over the newest day folder's `INDEX.md`: **Needs you (N)** first, hidden at 0, then the open tasks by topic; a double click opens the task as a Markdown view; the right-click menu resumes it in a session, asks its decisions in a session, or opens its documents | what needs the user is read in one list, not by reading the whole index |
| the store | `INDEX.md` stays the store; comando only reads it, again after it changes, on the folder poll's thread; sessions edit it as they did | a concurrent edit by a session cannot be lost by a write of comando's |

Miguel decided the Later group's removal and the timeout (D1), the Logbook tab (D2) and the store (D3) of task 97 on
2026-11-10.

2026-11-11, after Miguel used the first build: the Logbook tab is built from the sidebar's parts, not new widgets. It
moved after Files, before Instances, and the window keeps the tab in front by its name (`sidebar.shown`), reading an
index kept by the earlier order as the tab it named. Its rows are the Files tab's tree, with its group headers, row
icons and menus: within a group the newest task first, a row the task's name (the title without its attribution, the
heading's last parenthetical that gives a start date or a name), then, muted, its number, the day it started and who
asked for it when that is not the logbook's owner. A row's hover shows the sidebar's card, as a session's row does
(Started, By, From, Topic, Ticket, State, Next, Needs you, and the Ask), not a tooltip. The task's page links each
Linear ticket id to the ticket once the window knows Linear's workspace, and each document to its file: a double click
opens a ticket in the browser and a document in a panel. The menu, each item with its icon, adds **Show the task** and
**Open ID in Linear**, one per ticket, to Resume, Ask me its decisions and Open document.

## 3. Consequences

- `Decision`, `DecisionRules`, the intents and effect that moved decisions, `AppState.later` and the four MCP tools
  above are gone; `ActionParityTest` keeps the actions and the tools in step as before. `AppState.logbook` holds what
  the tab shows, set by `Intent.LogbookRead`; the tab has no intent of its own to change the index.
- `decisions.json` in a data root is neither read nor deleted: a decision an older comando held there is not shown.
- The kit's checkpoint (`/reflect --auto`) writes a `**Needs you:**` line instead of calling `decision_hold`, and its
  settings no longer allow the decision tools; that change is the kit's, made with this one.
- A question unanswered in 24 hours is lost with its call, as a permission card is: nothing is left to answer once the
  call is gone.
- Unchecked: that Claude Code honours an MCP server timeout of 24 hours and 5 minutes. Task 97 settles it with a call
  held past 70 minutes; if Claude Code caps the wait, the session asks again and comando binds the second ask to the card
  that is showing.
- A task's page is its section of the index as written; a task with no `**Needs you:**` line and a State that does not
  begin `waiting on Miguel` is not listed under Needs you.

## 4. Alternatives

Keeping the Later group and raising the timeout would leave two answer flows and the three copies of F2. Re-asking a
card on expiry instead of a long wait was kept as the fallback above, since a card that vanishes and returns confuses
the user more than one that waits. One file per task, rendered to `INDEX.md`, would let comando write safely, and costs a
second store; Miguel kept `INDEX.md` for now. A Logbook that writes the index (mark a task done, edit a decision) was
left out: sessions already edit it, and a write of comando's against a session's is the conflict the store row avoids.

## 5. Amends

[Record 131](0131-an-automatic-checkpoint-asks-nothing.md): its Later group, `decision_hold` and the decision tools are
replaced; the automatic checkpoint that asks nothing, the grouped queue and the lifted veil stay.
[Record 142](0142-one-decision-queue.md): replaced as a whole.
[Record 133](0133-every-stored-file-has-a-schema-and-a-version.md): `decisions.json` is no longer a file comando keeps.
