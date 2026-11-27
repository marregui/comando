# 197. Codex sessions over `codex app-server`

| Date | Status | Decided by |
|---|---|---|
| 2026-11-27 | accepted; built in task 258 | Miguel and Claude |

## 1. Context

Miguel's ChatGPT Pro plan makes OpenAI's Codex a second agent beside Claude Code. A Codex session must feel like a
Claude session: its own panel, the same transcript blocks, the session list, start and stop, model and effort, prompts
from other sessions, comando's MCP tools and metrics. Codex reads everything and writes only the brain's scratchpad;
Claude verifies what it proposes.

## 2. Decision

1. **The model names the engine.** `Engine.of(model)`: the Codex families `sol`, `astra`, `luna` and any `gpt-…` id run
   on Codex, everything else on Claude. No new session field, so `sessions.json` is unchanged.
2. **One router.** `EnginePort` implements `ClaudePort` and sends each session's calls to `ClaudeAdapter` or
   `CodexAdapter`; the reducer and `EffectRouter` stay engine-blind.
3. **One `codex app-server` process per session**, JSON-RPC over stdio. `thread/start` carries the model (`sol` →
   `gpt-6.2-sol`, effort `medium` by default), approval `never`, developer instructions, and a `config` map with a
   permission profile that reads the whole disk and writes only `BRAIN/SCRATCHPAD`, no network, plugins and apps off,
   the user's MCP servers off and comando's MCP server on with the session's bearer ticket and its tools approved
   (`default_tools_approval_mode = "approve"`: under approval `never` a tool that needs approval fails; comando's
   write tools ask the user through its own cards). Probed 2026-11-27 on 0.172.0:
   the thread reports `workspaceWrite` with the scratchpad as its only writable root and `networkAccess: false`; a
   write to `/tmp` or `$HOME` and a `curl` fail.
   Before the thread call, `skills/extraRoots/set` gives Codex the kit's `skills/` folder, when the kit has one, to
   list beside its own; a refusal is a note in the session, not a failed start.
   The `config` map also carries `bypass_hook_trust` and a `PreToolUse` hook on the shell tools
   (`Bash|shell|exec_command`) that runs the kit's `kill-guard` and `instance-guard` through `bin/kit-python`, each
   whose script exists; probed the same day, `kill-guard` denied a `pkill -f` unchanged.
4. **Prompts map onto turns.** A prompt is `turn/start`; Interrupt is `turn/interrupt`; `/model X` and `/effort X` change
   the next turn's. `/compact` (the compact buttons, the automatic checkpoint) is `thread/compact/start` on the running
   thread, with a note; its `contextCompaction` item shows as the compaction note a Claude session shows, and a refusal
   is a note. `/clear` starts a new thread on the running server with the parameters the session started with, between
   turns only: the transcript empties and the new thread's id reaches the session as its conversation id. A Claude
   command Codex lacks is answered with a note; the checkpoint's `/reflect` says a Codex session cannot write memory.
   A stopped session's history is its stored thread, read by a short-lived `codex app-server` of its own (no MCP
   ticket): `thread/read` with `includeTurns`, each stored item shown as the live turn showed it and a turn end per
   turn, without a turn event or an intent; a thread that cannot be read is a log line and a note. Notifications
   of a thread the session left by `/clear` are dropped, and `/compact`, like `/clear`, runs between turns only. A resume keeps sending the
   whole permission table: without it Codex 0.172.0 refuses the saved profile (probed 2026-11-27).
5. **Items map onto blocks.** `agentMessage` → assistant text, `reasoning` → thinking, `commandExecution`, `fileChange`
   and `mcpToolCall` → tool calls, `turn/completed` → turn end; `thread/tokenUsage/updated` → context; each turn writes
   the metrics `turn` event.
6. **Sub-agents show as Claude's do.** A `subAgentActivity` item of kind `started` (the spawn call's id, the child's
   `agentThreadId` and `agentPath`) is an `Agent` call, running until the activity of kind `completed` or
   `interrupted`. The child thread's notifications arrive on the session's connection with the child's `threadId`
   (probed 2026-11-27, 0.172.0) and go to a mapper of its own, into the transcript the sub-agent view shows, so the view
   follows it live; notifications of any other thread are dropped. A sub-agent of a stored thread is read, when its
   view opens, from its own stored thread (`thread/read`), by the running server or a short-lived one.

## 3. Consequences

- Approval cards are not used: under approval `never` a denied write returns to the model.
- A session refuses to start while any execpolicy rule exists (`~/.codex/rules/*.rules`, a project's
  `.codex/rules/`): an `allow` rule runs a command outside the sandbox and `codex app-server`, unlike
  `codex exec --ignore-rules`, cannot skip them. Codex adds a rule each time a command is approved for always in its
  own app, so the file may need emptying again. A configuration it cannot read refuses the start too.
- An imported or copied session forks the thread (`thread/fork`); a restarted one resumes it.
- A session's engine is fixed by the model it starts with: `/model` of a Claude model on a Codex session is refused
  with a note. Changing the model to another engine's in the session's dialog leaves a conversation id of the other
  engine, and the next start fails; make a new session instead.
- Known limits: the turn end reports no queued prompts; an interrupt asked before a turn's id arrives is sent once it
  arrives.
- `thread/read` with `includeTurns` is deprecated for paginated threads in 0.172.0
  (`thread/turns/list` and `thread/items/list` replace it); the history moves to them when `includeTurns` goes.
