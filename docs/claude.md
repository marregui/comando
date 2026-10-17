# Claude

How comando runs Claude Code, what the session can do in the window, and what it must ask for.

1. [The Claude bridge](#1-the-claude-bridge)
2. [MCP tools](#2-mcp-tools)
3. [Approvals and safety](#3-approvals-and-safety)

## 1. The Claude bridge

comando runs Claude Code as one child process per session, speaks stream-JSON over its standard input and output, and
maps the stream into transcript blocks and intents. The CLI facts below were verified with recorded probes against Claude
Code 2.1.296 (fixtures in `testkit`), and against 2.1.300 for background tasks.

### 1.1 The process

```
claude -p --input-format stream-json --output-format stream-json --verbose \
  --include-partial-messages --forward-subagent-text --include-hook-events \
  ( --session-id <new uuid> | --resume <id> | --resume <id> --fork-session ) \
  --name <session name> \
  --mcp-config <run>/<session>-<uuid>.mcp.json --permission-prompt-tool mcp__comando__approve \
  --append-system-prompt <comando's UI instructions> \
  [--model m] [--effort e] [--permission-mode p] [--add-dir d]... [--max-budget-usd n]
```

- **Start mode.** A new session gets a fresh id; a session resumes its own conversation. An imported conversation is
  forked on its first start, and a cloned session forks its source's conversation, so two processes never write one
  conversation ([decision 4](adr/0004-a-session-open-in-another-terminal.md)).
- **Not `--bare`.** The session loads CLAUDE.md, hooks, plugins, skills and the user's MCP servers as a terminal session
  does. The login is the CLI's own; comando stores no key.
- **MCP configuration.** One file per running process under `run/` in the data root, mode `0600` from creation, named
  after the session and a random UUID. It names comando's server, the process's bearer token, and `"timeout": 3600000`,
  which lets an approval card wait up to 55 minutes. Deleted when the process exits.
- **Working directory.** The session's; the panel header shows its git branch, read from `.git/HEAD` after each turn.
- **Threads.** A stdout reader, a stdin writer fed by a queue, a stderr reader keeping the last 40 lines, an exit
  waiter. Every change to the session runs on its strand, a single-threaded virtual executor, so the transcript has one
  writer.
- **Instructions.** `--append-system-prompt` carries `UiInstructions`: the tools and when to use them (a view when a
  table, plot or diff shows a result better than text; explanations stay in text).

### 1.2 Mapping the stream

| Event | Becomes |
|---|---|
| `system/init` | an init note when the model changed; `SessionInit` with the conversation id (the next start resumes it), model, CLI version, permission mode, capabilities |
| `system/status`, `task_started`, `task_notification` | `SessionActivity` (requesting, compacting, a task's description) |
| `system/background_tasks_changed` | `SessionTasksChanged`: the open background tasks, the whole set ([record 43](adr/0043-a-session-waiting-on-background-work-shows-it.md)) |
| `stream_event` `message_start` with no parent tool call | `SessionTurnRunning`, also for a turn Claude Code started itself after background work |
| `stream_event` block start and deltas | a streaming text, thinking or tool-call block, updated in place |
| `assistant` | the final form of each block |
| `user` with `tool_result` | the result on its tool card; the card turns done or failed |
| `user` with text | the compaction summary, local command output or the interrupt marker, as notes |
| `system/compact_boundary`, `hook_response`, `api_retry`, `permission_denied` | a note of that kind |
| `result` | a turn end (duration only, no cost shown); `SessionTurnEnded` with the queued turn count, cost, context size and context window. The context size is the input of the turn's last main-thread call; after `compact_boundary`, the compacted size; after `conversation_reset`, 0 |
| `rate_limit_event` | a note when its status is not `allowed` |
| `conversation_reset` | a note "Conversation cleared"; the context size becomes 0 until a call reports the new one |

Subagent blocks carry `parent_tool_use_id` and nest inside the card that started them. Tool results are kept to 64 kB of
text and shown to 400 lines.

### 1.3 Turns, interrupt, stop

- **Turns.** A prompt sent mid-turn is folded into the running turn; `result.queued_turn_count` reports what is still
  queued. comando counts pending turns per session and resets the count at each turn end; the session is busy while it
  is above zero. A turn that ends with nothing queued posts "Turn finished" or "Turn failed" as a desktop notification while
  the window is not active.
- **Interrupt** writes a `control_request` with subtype `interrupt`. The turn ends with a `result` marked interrupted,
  shown muted; the session stays open and no "Turn failed" notification is sent.
- **Stop** interrupts, closes stdin, sends SIGTERM after 3 s and SIGKILL 2 s later. A prompt sent while stopping is
  refused with a notice and stays in the editor. A process that cannot start is reported once, naming `COMANDO_CLAUDE`
  or the run directory when those are the cause. A tool call left running when the process ends reads "no result: the
  session ended before the tool returned".
- **Slash commands** (`/compact`, `/clear`, `/model x`, `/effort x`) are sent as prompts; `/compact` and `/clear`
  work under `-p`. `/clear` writes `conversation_reset`, ends its turn with no model call and reports the new
  conversation in a new `system/init`: comando zeroes the context, notes the clear, and resumes the new id from then on,
  saying the previous one can be imported again.
- **Versions.** At start `claude --version` runs with a 5 s bound. A version outside the tested line (2.1.296 up to,
  not including, 2.2.0) gets a warning, not a refusal; features are read from `system/init`'s capabilities.

### 1.4 Stored conversations

- **Import.** The session catalogue reads `~/.claude/projects/*/*.jsonl` (the first 64 kB for the directory and
  branch; the last 256 kB for the title, the last prompt and the latest branch) and lists the newest 500 first. An imported session's next start is a fork;
  its dialog's "Next start" can be set to resume, which is safe only when no terminal holds the conversation (comando
  does not check).
- **History.** A session with a conversation id shows its stored transcript without starting a process. After a restart
  every session comes back closed with its transcript; nothing is sent until the user sends something, and the first
  prompt resumes the conversation.

## 2. MCP tools

### 2.1 The server

| Property | Value |
|---|---|
| Transport | streamable HTTP, `POST /mcp` on `127.0.0.1`, an ephemeral port |
| Authentication | a 256-bit random bearer token per process (`SecureRandom`), naming the calling session; revoked when the process ends; no known token, 401 |
| Origin | an `Origin` naming a host other than `localhost`, `127.0.0.1` or `::1` gets 403, so a web page cannot call the tools; the CLI sends none |
| Protocol versions | `2025-03-26`, `2025-06-18`, `2025-11-25`; `initialize` answers the client's when supported, else `2025-06-18` |
| Methods | `initialize`, `tools/list`, `tools/call`, `ping`; notifications get 202; anything else, including the `server/discover` Claude Code sends first, gets method-not-found |
| Implementation | JSON-RPC over the JDK's `HttpServer` and Jackson, a virtual thread per request; no MCP library |

### 2.2 The tools

| Tool | Arguments | Does |
|---|---|---|
| `approve` | `tool_name`, `input`, `tool_use_id` | Claude Code's permission prompt: a card, and a wait for the answer ([§3](#3-approvals-and-safety)) |
| `ui_show_table` | `title`, `columns`, `rows`, `placement` | a table view |
| `ui_plot` | `title`, `x_label`, `y_label`, `time_x`, `series` (`name`, `x`, `y`), `placement` | a plot; with `time_x`, x is epoch milliseconds |
| `ui_show_view` | `type`, `title`, `data`, `placement` | a `markdown`, `diff`, `text`, `tree`, `table` or `plot` view |
| `ui_set_editor` | `text`, `panel`, `force` | replaces a panel's text, one step the user's ⌘Z undoes |
| `ui_insert` | `text`, `panel`, `mode`, `force` | adds text as its own block: at the end (`append`), or after the block that ran last (`insert`); the user's caret and selection stay put |
| `ui_open_file` | `path`, `line` | shows a file in the side pane: Markdown rendered when no `line` is given, other text read-only at the line, binary said so; `~/` and relative paths resolve; at most 4 MB |
| `ui_notify` | `text`, `level` | a status-bar message, and a desktop notification while the window is not active |
| `ui_get_state` | — | panels with bindings and last run, connections, sessions (busy, `waitingOn`), open views |
| `ui_set_layout` | `panel`, `lower_tab`, `sidebar`, `side_pane`, `lower_share` | without `panel`, selects `lower_tab` at once while the user looks at a panel of this session; with `panel` (one of this session's), focuses it and selects the tab once the user has not typed for 2 s, if the panel is still this session's; panes and the lower share change after the same pause; View ▸ Restore layout undoes it |
| `ui_close_view` | `id` | closes a view this session placed; the user's or pinned views are refused |
| `db_run_sql` | `sql`, `connection`, `max_rows`, `show`, `title` | runs SQL on the connection's lane under the policy ([§3.2](#32-sql-from-claude)); returns the first result as tab-separated text; `show` also opens it as a table |
| `db_describe` | `table`, `connection` | a table's columns and types |
| `session_send` | `session`, `text` | prompts another running session with an open panel, headed `[from session <sender>]`, without a card ([record 44](adr/0044-sessions-prompt-each-other-without-asking.md)); returns a cursor for `session_read` |
| `session_read` | `session`, `after`, `wait_seconds` | another session's transcript as numbered blocks after a cursor, or the last 40; `wait_seconds` (≤ 600) waits until its turns are over |

- **Scope.** Without `panel`, the editor tools use the focused panel when it is bound to the calling session, else the
  session's first panel; a panel bound to another session needs `force`. Without `connection`, the database tools use
  that panel's connection. Every edit posts a notice naming the session, panel and change. Every call gets an answer;
  an unexpected failure is a tool error.
- **Validation.** Every view argument is checked before an intent is sent; an invalid spec is a tool error that names
  the problem and changes nothing. Limits: 100 000 table rows, 2 000 000 plot points, 200 SQL rows to Claude by default
  and up to 10 000 with `max_rows`, 64 kB of text to Claude.
- **Placement.** A session's view replaces that session's unpinned view with the same title and placement, without
  moving the user to it. The first placement selects the new tab only for the session bound to the focused panel;
  another session's adds the tab and posts a notice. Titles are clipped to 80 characters; a file is titled with its
  folder (`docs/README.md`). A pinned view is never replaced.
- **Audit.** Every tool call and approval decision is appended to `audit/audit-<UTC date>.jsonl` in the data root:
  time, session, event, tool, the arguments (clipped to 2 000 characters), the outcome. Never read back.

## 3. Approvals and safety

### 3.1 Approval cards

`--permission-prompt-tool mcp__comando__approve` makes Claude Code ask comando before each tool call its permission
rules do not settle. Each request is a card in the Approvals tab; the MCP call waits on a future until the user answers.

| Kind | Raised by | Answers |
|---|---|---|
| tool | Bash, Edit, Write, an MCP tool | Allow once (⏎ or A), For session (S), Always (L twice; Bash and WebFetch only), Deny (D or Esc) |
| question | `AskUserQuestion` | Answer (⏎ or A, once every question has one), Skip (D or Esc); each question's Other answer is a plain text box in the editors' font, five lines of 120 characters (⏎ a new line; undo, redo, cut, copy, paste, select all; only Answer sends it), gone with the card; answers return in `updatedInput.answers` |
| plan | `ExitPlanMode` | Approve plan (⏎ or A), "Approve, auto-accept edits" (`setMode` `acceptEdits` for the session), Keep planning (D or Esc) |
| SQL write | `db_run_sql`, not read-only | Run (⏎ or A), Deny (D or Esc); the card names the connection and statement |

"For session" and "Always" return an `addRules` update with destination `session` or `localSettings`. The rules follow
how Claude Code matches them and never allow more than the command could do:

| Tool | Rule | Always |
|---|---|---|
| Bash | one per subcommand of `&&`, `\|\|`, `;`, `\|`, `&` and newlines, after the wrappers Claude Code strips (`timeout`, `time`, `nice`, `nohup`, `stdbuf`, `command`, `builtin`, `noglob`): the command's prefix with `:*`, one word (`Bash(ls:*)`), two for commands with subcommands (`Bash(git commit:*)`), three for runners (`npm run test:*`) and `python -m <module>`. The exact command instead for anything that runs other code (`sudo`, `env`, `xargs`, `eval`, shells, interpreters, `ssh`, `rm`, `docker exec`, `find -exec`, …), nested commands, here-documents, and prefixes holding a quote, `$`, `=` or an option | yes |
| WebFetch | `domain:<host>` | yes; a URL with no host, the tool for the session only |
| Edit, Write, MultiEdit, NotebookEdit | `Edit(//abs/dir/**)`, the file's directory | no |
| Read, Glob, Grep | `Read(//abs/dir/**)`; without a `path`, the tool | no |
| MCP and other tools | the tool | no |

- The card shows the rule its answer adds. Always writes the project's local settings, so its key must be pressed twice
  within 3 s; Esc cancels a pending Always before it denies.
- A denial carries the note typed on the card. In the note, ⏎ denies once it holds text and gives the primary answer
  while empty; ⌘⏎ denies with the note. ↑ and ↓ move between cards. The keyboard is never taken from outside the tab.
- While a card waits, its tool call reads "waiting for your approval · ⌃2" in the Session tab.
- Answering the last card while the Approvals tab is in front brings the Session tab forward, on the panel of the
  session that asked, with the keyboard; a card withdrawn, or answered from another tab, moves nothing.
- A card is withdrawn and its call denied when Claude Code cancels the call, the process exits, or the session is
  removed. A SQL card is also withdrawn at a turn end with nothing queued and no background work; while background work
  runs every card stays ([record 43](adr/0043-a-session-waiting-on-background-work-shows-it.md)).
- A card unanswered for 55 minutes is withdrawn and denied; at exit every waiting card is denied.

### 3.2 SQL from Claude

`db_run_sql` runs on the connection's lane behind the user's runs, waits at most 90 s (a statement still queued is then
withdrawn; one running ends at its 60 s statement timeout), and is not stopped by the user's ⌘..

| Statement | Ordinary connection | Production connection |
|---|---|---|
| read-only | runs | runs |
| not read-only | a card; runs on Run | refused; nothing runs |

A statement is read-only when, comments removed: its first word is `SELECT`, `WITH`, `SHOW`, `EXPLAIN`, `DESCRIBE` or
`TABLES`; no later word outside quotes writes or locks (`INSERT`, `UPDATE`, `DELETE`, `MERGE`, `UPSERT`, `INTO`,
`CREATE`, `DROP`, `ALTER`, `TRUNCATE`, `GRANT`, `REVOKE`, `COPY`, `CALL`, `VACUUM`, `REINDEX`, `CLUSTER`, `LOCK`,
`REFRESH`, `NOTIFY`, `SHARE`); and it holds no semicolon between statements, no `$` outside quotes and no backslash
inside a quoted string. This catches writes behind a read-only first word (a data-modifying CTE, `EXPLAIN ANALYZE` of a
write, `SELECT … INTO`, `SELECT … FOR UPDATE`) and refuses what the scanner cannot settle. It cannot see a function
with side effects called from a `SELECT`, so a production connection should use a role without write privileges: the
check is a guard against mistakes, not access control. Text with several statements is never read-only: a card on an
ordinary connection, refused on a production one.

A production connection frames its panels in red, shows "prod" in the sidebar, refuses Claude's writes, and runs the
user's own statements as typed.

### 3.3 Secrets

| Secret | Where it lives |
|---|---|
| Claude login | Claude Code's own; comando never sees an API key |
| MCP tokens | the session's MCP configuration under `run/`, mode `0600`; deleted when the process exits and at exit; files a killed comando left are deleted at the next start |
| Database passwords | the macOS login keychain, service `comando`, account = connection name, through `/usr/bin/security`; `connections.json` keeps an empty password. Used for the default data root, and for another only with `-Dcomando.keychain=true`; otherwise the password stays in `connections.json` |
| Intent log | every `password` field blanked |

The keychain tool takes the password as an argument, so it is visible in the process list for the milliseconds the tool
runs; it never reaches a file. Passwords are read with `find-generic-password -g`, so any UTF-8 password round-trips.
Every keychain call is bounded at 10 s; when the keychain cannot be asked, the connection loads without its password
and later saves keep the stored item.

### 3.4 Text is never rendered as HTML

Swing renders a label, button, tooltip, tab title or table cell as HTML when its text starts with `<html>`, and loads
the images it names. Text from Claude, a database or a stored conversation goes through `Plain` (renderers with HTML
off; a guard character before an `<html>` prefix), and Markdown is converted with images replaced by their alt text.
No content reaching the window makes comando issue a network request.
