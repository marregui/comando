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
   the next turn's; a Claude command Codex lacks (`/compact`, `/clear`, the checkpoint) is answered with a note.
5. **Items map onto blocks.** `agentMessage` → assistant text, `reasoning` → thinking, `commandExecution`, `fileChange`
   and `mcpToolCall` → tool calls, `turn/completed` → turn end; `thread/tokenUsage/updated` → context; each turn writes
   the metrics `turn` event.

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
- Not yet: compaction, the subagent view, the history of threads started outside comando.
