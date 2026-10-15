# Claude bridge

comando runs Claude Code as a child process per session, speaks stream-JSON over its standard input and output, and maps the stream into transcript blocks and intents. The facts below about the CLI were verified against Claude Code 2.1.296 with recorded probes; the recordings are fixtures in `testkit`.

## 1. The process

```
claude -p --input-format stream-json --output-format stream-json --verbose \
  --include-partial-messages --forward-subagent-text --include-hook-events \
  ( --session-id <new uuid> | --resume <id> | --resume <id> --fork-session ) \
  --name <session name> \
  --mcp-config <run>/<session>-<uuid>.mcp.json --permission-prompt-tool mcp__comando__approve \
  --append-system-prompt <comando's UI instructions> \
  [--model m] [--effort e] [--permission-mode p] [--add-dir d]... [--max-budget-usd n]
```

- **Start mode.** A new session gets a fresh id; a session resumes its own conversation by id. An imported conversation is forked on its first start, and cloning a session with a conversation forks it, so two processes never write one conversation ([decision 4](../adr/0004-a-session-open-in-another-terminal.md)).
- **Not `--bare`.** The session loads CLAUDE.md, hooks, plugins, skills and the user's MCP servers as a terminal session does; the login is the CLI's own and comando stores no key.
- **MCP configuration.** One file per running process, named after the session (characters outside `A-Za-z0-9._-` become `_`) and a random UUID, mode `0600` from its creation, under `run/` in the data root. It names comando's server with its URL, the process's bearer token in a header, and `"timeout": 3600000`. That per-server timeout also raises Claude Code's idle window for a tool call, so an approval card can wait up to 55 minutes without progress notifications. The file is deleted when the process exits.
- **Working directory.** The session's directory; the panel header shows its git branch, read from `.git/HEAD` after each turn.

Threads per process: a stdout reader, a stdin writer fed by a queue, a stderr reader that keeps the last 40 lines for the exit message, and an exit waiter. Everything that changes the session runs on the session's strand, a single-threaded virtual executor, so the transcript has one writer.

## 2. Mapping the stream

| Event | Becomes |
|---|---|
| `system/init` | an init note; `SessionInit` with the conversation id, model, CLI version, permission mode, capabilities. The id makes the next start a resume. |
| `system/status`, `task_started`, `task_notification` | `SessionActivity` (requesting, compacting, a task's description) |
| `system/compact_boundary` | a compaction note |
| `system/hook_response` | a hook note with the hook's output; `--include-hook-events` makes every hook event report, not only SessionStart |
| `system/api_retry` | a retry note with attempt and limit |
| `system/permission_denied` | a denial note |
| `stream_event` block start and deltas | a streaming text, thinking or tool-call block, updated in place, keyed by message id and block index |
| `assistant` | the final form of each block; one event arrives per content block |
| `user` with `tool_result` | the result attached to its tool card by tool-use id; the card turns done or failed |
| `user` with text | the compaction summary, local command output, or the interrupt marker, as notes |
| `result` | a turn-end block (duration, cost); `SessionTurnEnded` with the queued turn count, cost, the context size, and the model's context window from `modelUsage`. The context size is the input of the turn's last main-thread API call (`result.usage` sums every call of the turn); after `compact_boundary` it is the compacted size until the next call reports one |
| `rate_limit_event` | a note when its status is not `allowed` |

Subagent blocks carry `parent_tool_use_id`; the transcript nests them inside the card of the call that started them. Text is clipped per block (tool results to 400 lines in the view) so one runaway tool output cannot stall rendering.

## 3. Turns

A prompt sent while a turn runs is not rejected: Claude Code folds it into the running turn and reports what is still queued in the `result`'s `queued_turn_count`. comando counts pending turns per session (one per prompt sent) and sets the count to the reported queue length at each turn end; the session is busy while the count is above zero. A session has at most one panel ([record 38](../adr/0038-a-session-has-one-panel.md)); each prompt block in the transcript names the panel it came from.

When a turn ends and nothing is queued, a desktop notification says so, while the window is not active. An interrupted turn is its own outcome: the transcript's turn end reads muted "interrupted", the status bar says "<session>: interrupted", and no "Turn failed" notification is sent. Any ERROR notice also goes to the desktop notification centre while the window is behind.

## 4. Interrupt and stop

- **Interrupt** writes `{"type":"control_request","request_id":…,"request":{"subtype":"interrupt"}}`. The session's activity reads "interrupting" at once; the turn ends with a `result` marked interrupted; the session stays open.
- **Stop** interrupts, closes stdin, sends SIGTERM after 3 s if the process is still alive and SIGKILL 2 s later. A process that exits on its own is reported with its exit code and the stderr tail. A prompt sent while a session is stopping is refused with a notice, because the old process would take it after its input ended; the text stays in the editor, to send again once the session has stopped. A process that cannot start at all is reported once, "Session X could not start: …"; a prompt sent to it adds no second error. The message names `COMANDO_CLAUDE` when starting the executable fails, and the directory when the run directory cannot be written. A tool call left running when the process ends, or at the end of a stored conversation, ends as "no result: the session ended before the tool returned", so its card stops counting.
- **comando's exit** sends SIGTERM to every process, SIGKILL to any still alive 2 s later, and deletes every MCP configuration file.
- **Slash commands** (`/compact`, `/model x`, `/effort x`) are sent as prompts; `/compact` works under `-p` and produces a compaction boundary and a local-command result.

## 5. Approvals and questions

`--permission-prompt-tool mcp__comando__approve` makes Claude Code ask comando before each tool call its permission rules do not settle. `AskUserQuestion` reaches the same tool; comando shows the question as a card and returns the answers in `updatedInput.answers` ([Approvals and safety](approvals-and-safety.md)).

## 6. Versions

At start comando runs `claude --version` off the EDT with a 5 s limit. The stream format carries no version, so a version outside the tested line (2.1.296 up to, not including, 2.2.0) gets a warning, not a refusal; features are detected from the `capabilities` that `system/init` lists. A missing executable is reported with the setting that fixes it (`COMANDO_CLAUDE`).

## 7. Stored conversations

- **Import.** The session catalogue reads `~/.claude/projects/*/*.jsonl`: the first 64 kB for the working directory, branch and title, the last 256 kB for the last prompt; the import dialog lists them newest first. An imported session's next start is a fork; its dialog's "Next start" can be set to resume when no terminal holds the conversation.
- **History.** A session with a conversation id shows its stored transcript without starting a process: the strand reads the file and maps user and assistant messages and compaction boundaries. Starting the session then resumes the conversation.
- **Restore.** After a restart every session comes back closed with its transcript loaded; nothing is sent until the user sends something.

## 8. Findings from the probes

| Question | Answer (CLI 2.1.296) |
|---|---|
| Does `AskUserQuestion` reach the permission-prompt tool? | yes; answers go back in `updatedInput.answers` |
| What happens to a prompt sent mid-turn? | folded into the running turn; `result.queued_turn_count` reports the rest |
| Does `/compact` work under `-p`? | yes; `compact_boundary` plus a local-command result |
| How is a turn interrupted? | a `control_request` with subtype `interrupt` on stdin |
| How does Claude Code open an HTTP MCP server? | `server/discover` first (comando answers method-not-found), then `initialize` |
| How long may an MCP tool call wait? | the per-server `timeout` in the MCP configuration bounds it |
| Can a `--bg` session be driven through `-p --resume`? | not tested |
