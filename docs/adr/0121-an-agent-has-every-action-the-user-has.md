# 121. An agent has every action the user has: one registry of actions, a parity test, two rules

| Date | Status | Decided by |
|---|---|---|
| 2026-10-29 | accepted; increment 1 built, increments 2–4 proposed (§3) | Miguel and Claude |

## 1. Context

Miguel, 2026-10-29: comando should offer an agent the same actions a person has in the window, so that an agent uses
comando as he does: creating sessions and doing any action on them, for every feature.

An agent-side trial on 2026-10-29 (a second instance, the latest build, a fresh kit and brain) asked a session to set up
a writer and a reviewer as sessions of their own. It found that no tool creates a session, declined to edit
`sessions.json` by hand, and asked a person for two clicks. The window dispatches 52 kinds of user intent; the MCP
server offers 15 tools, none of which creates, starts, stops, renames or removes a session or a panel
([record 44](0044-sessions-prompt-each-other-without-asking.md) gave sessions `session_send` and `session_read` only).

The interview settled four choices: a registry of actions with a parity test; every action exposed, with two rules; the
callers are the instance's sessions and, later, outside clients with a grant (read and prompt by default, the rest by
grant); built in increments, this one first.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the record | `core`'s `action/Actions` lists every user action once: its tool name, its area, a one-line description, its arguments, its class (`read`, `act`, `destructive`) and the intent (or `Setup` call) it performs | one place says what a person can do; the MCP tools are built from it |
| what counts as a user action | every `Intent` the window dispatches on a person's gesture, and every `Setup` call a dialog or menu makes (kit, brain, jobs, folders); view state (scrolling, selection, the search box, copy) is not an action: an agent reads the content directly | parity is about what changes or opens, not about pointer gestures |
| the parity test | `ActionParityTest`: every permitted subtype of `Intent` is classified, as exposed by an action, as an event (sent by an adapter, never by a person) or as excluded with a reason; every `Intent` constructed under `ui/src/main` is exposed or excluded with a reason; every `Setup` method likewise | a feature added to the window without its action fails the build |
| the tools | one MCP tool per action, named `<area>_<verb>` (`session_new`, `session_start`, `panel_add`, `approval_answer`…); the 15 tools then keep their names and become entries of the registry; arguments are named as the intent's fields, in snake case | each tool has its own schema and description; Claude Code loads MCP tools on demand, so their number does not fill the context |
| what an agent sees | `ui_get_state` returns what the window shows: panels, sessions with status, model, effort, context, busy and queued turns, pending approvals, connections, folders, open views, and the local servers (Edge, QuestDB) with whether each runs | an agent decides from the same facts as the person |
| rule 1: approvals | `approval_answer` is refused when the approval was raised by the caller or by a session the caller created, directly or through others (`SessionSpec.createdBy`) | an approval card exists so that someone other than the asker decides |
| rule 2: destructive actions | the `destructive` class (removing a session, panel or connection, clearing a transcript; later deleting or rolling back files and unstaging) runs only for a caller the user granted it: the data root's `agents.json`, `{"grants": {"<caller>": ["destructive"]}}`, read at each call; no file, no grant | work an agent can destroy is the user's to hand over; a file of its own, because the window rewrites `settings.json` |
| a creator cleans up (2026-10-30) | `session_clear` and `session_remove` also run, without a grant, for a caller that created the session, directly or through others | a session an agent made is that agent's work, as it may start it again; found when a peer instance could stop but not remove the team it built |
| record 44's circuit breaker | `session_start` and `session_fresh` run only for a caller that created the session, directly or through others; any caller may stop or interrupt a session ([record 44](0044-sessions-prompt-each-other-without-asking.md) is amended accordingly) | stopping a session stays the user's way to end a loop; an agent still starts the team it made |
| identity | a session calls with its process token as before; a session an agent creates records its creator in `createdBy` (a session name, or `""` for the user) | rule 1 needs the chain |
| audit and notice | every call is audited as before, with the caller; every `act` or `destructive` call posts a notice naming the caller and the action | the user sees what agents did in the window |
| servers (2026-10-30) | `server_start` and `server_stop` (`edge`, `questdb`) do what the toggles at the top of the workspace do, through `core`'s port `ServerSwitch`, which names the direction: a start of a running server or a stop of a stopped one does nothing and says so, and an Edge another instance started is refused, as for its toggle | the toggles call the servers and send no intent, so the parity test could not see them; a direction, because the toggle's "running" is up to a second old |
| an agent's session (2026-10-30) | a session with a creator runs with `COMANDO_CREATED_BY=<creator>` in its environment (`RootLaunch`); every other process has it taken out | a kit's hooks can leave out what only a person needs (the kit skips its effort reminder, CLI-version notice, peer digest and scratchpad roll); guards stay |
| outside clients | a discovery file `run/instance.json` (port, pid, instance name, kit root, version; mode 0600) and client tokens the user issues, read and prompt by default, `act` and `destructive` by grant | the callers' rule above; a later increment |

## 3. Increments

| # | Delivers | Acceptance |
|---|---|---|
| 1 (built 2026-10-29) | a `PromptSession` intent (a prompt to a session's own panel, resolved in the reducer, so `session_new` with a prompt never depends on focus); the registry (`core` `action/Actions`: 56 actions, every kind of intent classified), the parity test, session actions (new with an optional first prompt, edit, open, start, stop, interrupt, fresh, checkpoint, command, model, effort, order, import, clear, remove, sub-agent), panel actions (add, close, reopen, remove, rename, move, focus, bind session, bind connection, run, cancel, refresh), approval answer, connection actions (save, test, open, close, schema, remove), views (pin, close), notice dismiss, Gradle run, command cancel, folders browse; `createdBy`; rules 1 and 2 and the start rule; `ui_get_state` adds model, effort, creator, closed panels, pending approvals, folders | `ActionParityTest` and `ActionToolsTest` green (a removed action and a weakened rule 1 each fail them) |
| 2 | files and changes: open a change, delete, roll back, remove, unstage (they take a git change or a tree entry the window holds and the state does not; listed in `Actions.NOT_YET`) | parity test with `NOT_YET` empty of them |
| 3 | `Setup` actions: kit upgrade and version, brain backup, kit jobs read and save and run, folders window, log in a kit (`claude auth login`) | parity test over `Setup` |
| 4 | outside clients: `run/instance.json`, client tokens, Settings ▸ Agents to issue and revoke them and set grants | an outside client with a token reads and prompts; without `act` its `session_new` is refused |

## 4. Consequences

- A person and an agent can do the same things; what differs is who decides on approvals and on destroying work.
- Adding a feature to the window means adding its action, or saying in the registry why there is none.
- `SessionSpec` gains a field; `sessions.json` written before it reads with `createdBy` empty (the user).
