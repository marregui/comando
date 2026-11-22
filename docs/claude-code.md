# Claude Code in comando

![comando and Claude Code: you type in comando's window; comando runs one claude -p process per session and talks to it in stream-JSON over its standard input and output; Claude Code runs the agent loop with the model; its tool calls come back to comando's MCP server, which turns them into intents for the window](images/cc-overview.svg)

## In one paragraph

comando does not imitate Claude Code. It runs it. Every session is a real `claude -p` process with your own Claude
Code setup loaded: your login, your rules, your hooks and your skills. comando is the window where the session's work
lands, and the place where the session asks you before it acts. This page is for you if you want to know what a
session may do without asking, or if you work on the `claude` and `mcp` modules. It covers the process, the stream
comando reads, the checkpoint, the tools comando gives Claude, and the approval cards.

> **Learn: an LLM agent.** A language model does one thing: it reads text and writes the text that comes next. An
> agent is a loop around that. A program shows the model a task and a list of tools. The model answers with words, or
> with a request such as "run this command". The program runs it, adds the result to the text, and asks again. The
> loop ends when the model answers with no request. In comando, Claude Code is that program, and comando shows each
> step of the loop as it happens.

## 1. The Claude bridge

comando starts one child process per session. It writes your prompts to the process's standard input and reads
events from its standard output, one JSON object per line. That format is called stream-JSON. Each event becomes a
block in the transcript, an intent for the window, or both.

![A session's life: closed, opening, open (idle or with a turn running), closing, and failed when the process cannot start or ends by itself with an exit code that is not 0; below, the stop sequence: interrupt and close stdin, SIGTERM after 3 seconds, SIGKILL 2 seconds later](images/cc-session.svg)

| State | Means | Leaves it when |
|---|---|---|
| `CLOSED` | no process; the transcript shows from disk | you start it or send a prompt |
| `OPENING` | `claude -p` is starting | the process has started, or cannot |
| `OPEN` | the process runs, idle or in a turn | you stop it, or the process ends |
| `CLOSING` | the stop sequence is under way | the process exits |
| `FAILED` | the process could not start, or ended by itself with an exit code that is not 0; the error is kept | you start it again |

### 1.1 The process

```
claude -p --input-format stream-json --output-format stream-json --verbose \
  --include-partial-messages --forward-subagent-text --include-hook-events \
  ( --session-id <new uuid> | --resume <id> | --resume <id> --fork-session ) \
  --name <session name> \
  --mcp-config <run>/<session>-<uuid>.mcp.json --permission-prompt-tool mcp__comando__approve \
  --append-system-prompt <comando's UI instructions> \
  [--model m] [--effort e] [--max-budget-usd n]
```

| Part | What it means |
|---|---|
| Start mode | A new session gets a fresh id. A session with a conversation resumes it. An imported conversation is forked on its first start, and a cloned session forks its source, so two processes never write one conversation ([record 4](adr/0004-a-session-open-in-another-terminal.md)). |
| Your setup | No `--bare`: the session loads CLAUDE.md, hooks, plugins, skills and your MCP servers as a terminal session does. The login is Claude Code's own; comando stores no key. |
| Permissions | No `--permission-mode` and no `--add-dir`. A session runs under the root's `settings.json`. Its working folder's `.claude/settings.json` and `settings.local.json` apply on top and may change the mode and widen the rules ([record 7](adr/0007-default-permission-mode.md)). An agent may give a session any working folder, so the kit's rules do not bound a session it creates. The session dialog names the project's files and the mode they set. |
| MCP configuration | One file per running process under `run/` in the data root, readable by its owner only from creation. It names comando's server, a bearer token for this process, and a timeout of 24 hours and 5 minutes, so an approval card can wait its 24 hours ([record 150](adr/0150-a-card-only-when-a-session-cannot-go-on.md)). comando deletes the file and revokes the token when the process exits. Files a killed comando left are deleted at the next start. |
| Instructions | `--append-system-prompt` carries `UiInstructions`: comando's tools and when to use them. A view when a table, plot or diff shows a result better than text; explanations stay in text. |
| Working folder | The session's. comando reads its git branch from `.git/HEAD` after each turn. A folder inside Claude Code's own folder shows no branch ([§1.5](#15-what-a-session-works-on)). |

Every `claude` that comando starts runs on the root: the folder that holds your Claude Code setup, or your kit.
comando points the process at it with environment variables. [The kit](kit.md) lists them and says what reads
each.

A session costs one operating-system thread, `claude-out-<session>`, which reads the process's standard output.
Everything else runs on virtual threads, and every change to a session runs in order on its own strand. So the
transcript has one writer and the process one owner. The classes are in [claude](modules/claude.md); the thread
kinds are in [Architecture](architecture.md).

comando runs Claude Code four more ways, each with a time limit:

| Run | When | Limit |
|---|---|---|
| `claude --version` | once at start | 5 s, and 1 s more for its output |
| `claude --safe-mode -p /usage` | at start and every 20 minutes, for the plan's usage; no prompt, no saved session | 30 s |
| `claude --safe-mode -p --model haiku --effort low` | the summary on a tab's card, one at a time | 30 s |
| `codex app-server` | after each usage read, when Codex is installed, for the Codex plan's usage | 10 s |

comando is tested with Claude Code from 2.1.316 up to, not including, 2.2.0. Another version gets a warning at
start, not a refusal. comando reads what a session can do from the capabilities in its `system/init` event.

### 1.2 Mapping the stream

> **Learn: a stream of events.** A program can answer once, at the end, or in many small pieces while it works. The
> second way is a stream. Each piece is an event: "the model started", "here are three more words", "the tool
> returned". A reader that handles events one by one can show progress at once and never waits for the whole answer.
> comando reads one JSON event per line and updates the transcript block that the event belongs to.

| Event | Becomes |
|---|---|
| `system/init` | the conversation id (the next start resumes it), the model, the CLI version, the permission mode and the capabilities; a note when the model changed |
| `system/status`, `task_started`, `task_notification` | what the session is doing: calling the model, compacting, a task's description |
| `system/background_tasks_changed` | the whole set of open background tasks ([record 43](adr/0043-a-session-waiting-on-background-work-shows-it.md)) |
| `stream_event` `message_start` with no parent tool call | the turn runs, also a turn Claude Code starts by itself after background work |
| `stream_event` block start and deltas | a text, thinking or tool-call block that grows in place |
| `assistant` | the final form of each block; for the main thread, the model and effort the message ran at ([§1.7](#17-models-and-effort)) |
| `user` with `tool_result` | the result on its tool card; the card turns done or failed |
| `user` with text | a note: the compaction summary, a local command's output or the interrupt marker |
| `system/compact_boundary`, `hook_response`, `api_retry`, `permission_denied` | a note of that kind |
| `system/informational` | an info note with its text, at most 2 000 characters; "Warning: " in front at level `warning` |
| `result` | the turn's end: its duration, the error text when it failed, the time of day. Also the queued turn count, the context size and the context window |
| `rate_limit_event` | a note when its status is not `allowed` |
| `conversation_reset` | a note "Conversation cleared"; the context size is 0 until a model call reports the new one |

The context size is the input of the turn's last main-thread model call, and the compacted size after
`compact_boundary` ([record 120](adr/0120-the-context-follows-each-model-call.md)). comando shows no cost. A tool
result is kept to 64 kB of text and shown to 400 lines.

### 1.3 Turns, interrupt, stop

| Action | What happens |
|---|---|
| A prompt mid-turn | Claude Code folds it into the running turn or queues it. `result.queued_turn_count` says what is still queued, and the session is busy while that count is above zero. |
| A turn ends | With nothing queued, comando posts "Turn finished" or "Turn failed: <message>" as a desktop notification while the window is not active. A turn that handed work to the background notifies when the turn after that work ends. |
| Interrupt | comando writes a `control_request` with subtype `interrupt`. The turn ends marked "interrupted", in ivory, not red. The session stays open and no failure is notified. |
| Stop | Interrupt, close stdin, SIGTERM after 3 s, SIGKILL 2 s later. A prompt sent while stopping is refused with a notice and stays in the editor. |
| The process ends mid-tool | The tool card reads "no result: the session ended before the tool returned". |
| A prompt after the input closed | An error note, and the turn ends "session input closed". |
| A process that cannot start | Reported once, naming `COMANDO_CLAUDE` or the run folder when one of them is the cause. |

Slash commands (`/compact`, `/clear`, `/model x`, `/effort x`) go out as prompts. `/clear` ends its turn with no
model call and reports a new conversation in a new `system/init`. comando then sets the context to 0, notes the
clear, and resumes the new conversation from then on. The note says you can import the previous one again.

### 1.4 Stored conversations

Claude Code keeps every conversation as a file of JSON lines under `<root>/projects/`. comando reads those files; it
never writes them.

| What | How |
|---|---|
| Import | The session catalogue reads `<root>/projects/*/*.jsonl` and lists the newest 500. It reads a file's first 64 kB for the folder and branch, and its last 256 kB for the title, the last prompt and the latest branch. An imported session's next start is a fork. Its dialog can set "Conversation" to resume, which is safe only when no terminal holds the conversation; comando does not check. |
| History | A session with a conversation id shows its stored transcript without starting a process. After a restart every session comes back closed with its transcript. Nothing is sent until you send something, and the first prompt resumes the conversation. |
| Turn ends in history | A stored conversation records no turn results. A turn ends where a message stopped with `stop_reason` `end_turn`; a turn cut off mid-way gets no end ([record 137](adr/0137-a-turn-reads-as-its-report.md)). |
| Search | `TranscriptSearch` scans the top-level conversation files, newest first, with no index and no subagent files. It maps each file and looks for the word in the raw bytes, ASCII letters in either case. Only a line that holds the word is parsed. |

A search hit must lie in a prompt you typed or in an assistant's text block: not in a key, metadata, thinking or a
tool. A message gives one hit, with at most 200 characters around it. A word that is not ASCII is looked for as
UTF-8, so text stored as an escape sequence is not found. The hits go to the Search view
([Workbench](workbench.md), [record 139](adr/0139-one-window-finds-in-files-and-conversations.md)).

### 1.5 What a session works on

A session's working folder says where it runs, not what it changes. A session started in a kit's folder may write
to a repository elsewhere. So `SessionSubjects` reads the subject from the session's own writes
([record 138](adr/0138-a-session-shows-what-it-writes-to.md)).

| Question | Answer |
|---|---|
| What counts | A `Write`, `Edit`, `MultiEdit` or `NotebookEdit` call that succeeded, by its `file_path` or `notebook_path`. `Bash` is not parsed. |
| What is dropped | A path under the working folder or under Claude Code's folder. |
| The repository | The nearest folder up the path that holds `.git`. The latest write's repository is the subject; the others follow, the latest first. |
| The ticket | The first run of two or more letters, a dash and digits in the branch, in upper case. |
| When it is read | At a turn's end and after a history load, over the blocks added since the last pass. |
| Its life | A cleared view keeps the repositories; a new conversation forgets them. |

The session bar shows the branch and repository, and the session's card shows the ticket ([Workbench](workbench.md)).

### 1.6 Subagents

![A subagent shown twice: its stream events carry parent_tool_use_id and nest in the Agent card of the Session tab; Claude Code also writes the subagent's conversation to a file under the session's subagents folder, and comando tails that file every 2 seconds into a tab of its own, at most 4 tabs at once](images/cc-subagent.svg)

> **Learn: subagents.** A hard task splits into smaller ones. An agent can hand a part to a helper: a second agent
> loop with a fresh, empty context and one clear job. The helper reads twenty files and returns three sentences. The
> main agent keeps its context small and can run several helpers at once. The price is that the helper knows only
> what its brief says. comando shows a helper's work live, so you can see what it does.

A session calls Claude Code's Agent tool to start a subagent. comando shows it in two places.

| Place | Source | What you see |
|---|---|---|
| The Agent card in the Session tab | stream events with `parent_tool_use_id` set to the Agent call | the subagent's blocks, nested in the card that started them |
| A tab of its own in the lower panel | the subagent's file on disk, read as it grows | its whole conversation as a transcript ([record 109](adr/0109-a-sub-agent-is-a-session-tab.md)) |

A background subagent returns from its Agent call at once. Its messages keep arriving with the same parent, so its
blocks nest as a foreground one's do. The card reads as running in the background while the task is listed, and a
`task_notification` ends it ([record 64](adr/0064-sub-agents-are-shown-live.md)).

Claude Code does not document the subagent files. comando reads what it observes:

| File | Holds |
|---|---|
| `<session-id>.jsonl` | the session's conversation |
| `<session-id>/subagents/agent-<id>.jsonl` | one subagent's whole conversation |
| `<session-id>/subagents/agent-<id>.meta.json` | its type, its description, the Agent call's id, and `finished: true` once it ended |

`ClaudeAdapter.showSubagent` finds the meta file whose `toolUseId` matches the card. While the session runs, it
adds the whole lines the file gained, every 2 s. A file over 256 MB is read from where its last 256 MB start, and
the tab says so.

| The tail stops when | Note |
|---|---|
| the stream says the subagent ended | the Agent call's result, or a background task's `task_notification` |
| the meta file says `finished` | |
| the session stops | |
| the file has not grown for 10 minutes | a backstop: a subagent writes nothing while a tool runs or the model answers |
| a fifth tab starts to follow | at most 4 tabs follow at once; the oldest stops first |

The tab's last line says whether it still updates and, if not, why. A file that is missing or moved gives the
notice "transcript not found for this sub-agent".

### 1.7 Models and effort

A session has a model and an effort. The model is which Claude answers. The effort is how long it may think before
it answers.

| Setting | Values comando offers | At start | While the session is open |
|---|---|---|---|
| model | `fable`, `opus`, `sonnet`, `haiku` | `--model` | comando sends `/model <x>` as a prompt |
| effort | `low`, `medium`, `high`, `xhigh`, `max` | `--effort` | comando sends `/effort <x>` as a prompt |
| budget | an amount in US dollars, optional | `--max-budget-usd` | — |

Leave a setting empty and comando passes no flag, so Claude Code's own settings decide. An agent changes them with
`session_model` and `session_effort` ([§3.2](#32-the-tools)).

A turn can run at another model or effort than the session asked for: a skill may raise the effort, and Claude Code
may replace a model. So comando reads what each message ran at. `RunSettings` takes the model and effort from the
message's line in the conversation file, and the transcript marks the turn's first message with them. The base
effort comes from the root's `settings.json`: `modelSettings.<model>.effortLevel`, else `effortLevel`.

## 2. The checkpoint

![The checkpoint: a session's context grows turn by turn; when a turn ends at or above the threshold, 40 percent of the context window by default, comando sends the checkpoint command with --auto, then /compact, then one prompt to continue; the session is locked during the first two](images/cc-checkpoint.svg)

> **Learn: the context window.** A model has no memory between calls. Everything it knows about your task is the
> text sent with the call: the context. The context window is the most text one call can hold. A long session fills
> it, and a full context costs more, answers slower and loses detail. Compaction replaces the conversation so far
> with a short summary. A summary forgets, so first the session writes down what must survive. That pair, write down
> then shrink, is the checkpoint.

You start each half by hand, one at a time, from the session bar, the session's menu or the palette
([record 156](adr/0156-one-icon-set-and-a-split-checkpoint.md)):

| Action | Sends |
|---|---|
| Reflect | the session's checkpoint command alone, `/reflect` by default |
| Compact the conversation | `/compact` |
| Clear the conversation | `/clear` |

The automatic checkpoint and the `session_checkpoint` tool send the pair. comando sends the checkpoint command.
Once that turn has finished, with nothing queued and no background work still out, comando sends `/compact`. The
same conversation goes on, and the panel's text stays ([record 129](adr/0129-a-checkpoint-reflects-then-compacts.md)).
A checkpoint command typed by hand, or sent by another session, is a prompt like any other and compacts nothing.

**The automatic checkpoint.** The threshold is a share of the session's own context window:
`sessions.checkpointThresholdPercent` in comando's `settings.json`, 40 by default, from 10 to 100. The palette's
"Checkpoint threshold…" changes it. The threshold has no off. A session whose spec names no checkpoint command is not
checkpointed.

| Step | What comando does |
|---|---|
| A turn ends at or above the threshold | The session falls due; the session bar's context and checkpoint turn purple. |
| 1 | It sends the checkpoint command with `--auto`. |
| 2 | Once that turn has ended, it sends `/compact`. |
| 3 | Once the compaction has ended normally, it sends one prompt: "Continue the work the checkpoint paused, from the summary above: its next step first." |

The third step is an ordinary turn: the session is not locked, and the turn notifies at its end. Only the automatic
checkpoint continues by itself; after Reflect the session waits for your next prompt.

| Rule | Detail |
|---|---|
| Once per crossing | Any checkpoint command sent disarms the session. A model call that reads below the threshold, a fresh start or `/clear` arms it again. |
| Only a measured size arms it | The count Claude Code reports after a compaction is shown but never arms it. |
| A reflect already running | A checkpoint command you send, typed or by Reflect, owns the crossing its own work makes: its model calls arm nothing, and when its turn ends at or above the threshold comando sends `/compact` alone, with no `--auto` reflect and no continue prompt. Ending below, it compacts nothing. |
| No loop | After the continue prompt, a turn that ends above the threshold starts no checkpoint. A prompt from you or another session, or a model call below the threshold, allows the next one. |
| It waits | While a prompt is queued or background work is out. |
| It does not run | After a failed or interrupted turn, for a session without a checkpoint command, or while the context window is not known. |

**When nothing is compacted.** comando sends no `/compact` when a prompt arrives before the checkpoint's turn ends,
the turn fails or is interrupted, the process ends, or the answer is blank; a warning says so. It sends no continue
prompt in those cases either, nor when a prompt was sent or queued meanwhile: that prompt runs instead. A session
that holds a carried summary puts it in front of its next prompt that is not a slash command; until then a
checkpoint runs its command alone ([record 86](adr/0086-a-checkpoint-clears-and-carries-its-summary.md)).

**The lock.** While a checkpoint runs, by any route, the session is locked until its turn has ended and, when
`/compact` follows, until the compaction has ended ([record 130](adr/0130-a-session-locks-while-its-checkpoint-runs.md)).

| While locked | Behaviour |
|---|---|
| The session's panels | read only and grey, under one veil with its Session tab; Run and Clear are off |
| The session's menu | keeps Open, Stop, Interrupt, Copy details and Clone |
| Stop (⌘.) | interrupts the checkpoint: the lock lifts and nothing is compacted |
| A prompt from another session or an agent | queues, and runs once the checkpoint's turn has ended |
| Compact, from the palette or an agent's `session_command` | held: a notice says the session compacts once its checkpoint ends, and `/compact` follows that turn; a checkpoint that fails or is interrupted drops it |
| Anything else that acts on the session | refused without a notice: a prompt from its panel, Fresh, Checkpoint, its settings, Remove |
| A waiting approval card | lifts the veil from the Session tab, so the card looks as answerable as it is |

A failed turn, a blank answer or a process that ends lifts the lock too. The lock is live state and is never
stored. Other sessions are not touched.

**It asks nothing.** An automatic checkpoint never waits for you
([record 131](adr/0131-an-automatic-checkpoint-asks-nothing.md)). Its command carries `--auto`. A command that knows
the argument writes each decision it would have asked as a `**Needs you:**` line in its task's entry of the
scratchpad's `INDEX.md`. The Logbook tab lists those tasks, and its "Ask me its decisions in" prompts a session to
ask them as ordinary question cards. Reflect and a typed command carry no argument and ask as usual.

## 3. MCP tools

![The MCP server: a request from claude -p passes four gates, capacity, token, origin and method, each with its refusal; then one of five families of tools runs: approve, the ui tools, the database and result tools, the session tools, and the action tools; every tool sends intents to the reducer as the window does, and every call adds a line to the audit log](images/cc-mcp.svg)

> **Learn: tool use and MCP.** A model cannot click, query or save. It can only write text. So a program offers it
> tools: each has a name, a description and a list of arguments. The model writes a call, the program runs it and
> returns the result as text. The Model Context Protocol (MCP) is a standard way to offer tools, so any agent can
> use any server. comando is an MCP server: its tools let Claude show a table, run SQL or open a session in your
> window.

### 3.1 The server

| Property | Value |
|---|---|
| Transport | streamable HTTP, `POST /mcp` on `127.0.0.1`, a port the system picks |
| Authentication | a 256-bit random bearer token per process, which names the calling session; revoked when the process ends; an unknown token gets 401 |
| Origin | an `Origin` that names a host other than `localhost`, `127.0.0.1` or `::1` gets 403, so a web page cannot call the tools |
| Protocol versions | `2025-03-26`, `2025-06-18`, `2025-11-25`; `initialize` answers the client's when supported, else `2025-06-18` |
| Methods | `initialize`, `tools/list`, `tools/call`, `ping`; a notification gets 202; anything else gets method-not-found |
| Capacity | a virtual thread per request; at most 256 requests at once and 16 per session token, one past either gets 503 and `Retry-After: 1` |
| Bounds | a request has 5 s to arrive whole; at most 1 024 connections; a body of at most 64 MB |
| Implementation | JSON-RPC over the JDK's `HttpServer` and comando's own JSON; no MCP library ([mcp](modules/mcp.md)) |

> **Learn: loopback and a bearer token.** `127.0.0.1` is the loopback address: it reaches only programs on the same
> machine. That keeps the network out, but not other local programs or a web page in your browser. So each session
> gets a long random secret, a bearer token, and sends it with every request. Whoever bears the token is the
> session; nobody else can guess 256 random bits. comando keeps tokens in memory and in files only you can read.

### 3.2 The tools

| Tool | Arguments | Does |
|---|---|---|
| `approve` | `tool_name`, `input`, `tool_use_id` | Claude Code's permission prompt: a card, and a wait for the answer ([§4](#4-approvals-and-safety)) |
| `ui_show_table` | `title`, `columns`, `rows`, `placement` | a table view |
| `ui_plot` | `title`, `x_label`, `y_label`, `time_x`, `series`, or `x` and `y` for one series, `placement` | a plot; with `time_x`, x is epoch milliseconds |
| `ui_show_view` | `type`, `title`, `data`, `placement` | a `markdown`, `diff`, `text`, `tree`, `table` or `plot` view; `deliverables` shows the Files written view ([record 143](adr/0143-a-session-lists-the-files-it-wrote.md)) |
| `ui_set_editor` | `text`, `panel`, `force` | replaces a panel's text, as one step your ⌘Z undoes |
| `ui_insert` | `text`, `panel`, `mode`, `force` | adds text as its own block, at the end (`append`) or after the block that ran last (`insert`); your caret and selection stay put |
| `ui_open_file` | `path`, `line` | shows a file in the side pane: Markdown rendered when no `line` is given, other text read-only at the line; at most 4 MB |
| `ui_notify` | `text`, `level` | a status-bar message, and a desktop notification while the window is not active |
| `ui_get_state` | none | what the window holds: panels with bindings and last run, connections, sessions with model, effort and creator, open views, pending cards, the folder tabs' folders, the local servers, the other instances |
| `ui_set_layout` | `panel`, `lower_tab`, `sidebar`, `side_pane`, `lower_share`, `transcript_above` | arranges the window for this session's panel; panes and shares change once you have not typed for 2 s; "Restore layout" in the palette undoes it |
| `ui_close_view` | `id` | closes a view this session placed; yours and pinned views are refused |
| `db_run_sql` | `sql`, `connection`, `max_rows`, `show`, `title` | runs SQL under the policy ([§4.2](#42-sql-from-claude)); returns the first result as tab-separated text in PostgreSQL COPY text form (`\N` is SQL NULL); `show` also opens it as a table |
| `db_describe` | `table`, `connection` | a table's columns and types, in the same text form |
| `result_list` | none | the SQL results the panels hold: id, panel, connection, SQL, status, rows, columns with types ([record 145](adr/0145-agents-read-held-results-a-page-at-a-time.md)) |
| `result_read` | `result`, `offset`, `limit`, `columns` | a page of a held result, without running the query again; 200 rows by default, at most 10 000 and 64 kB |
| `result_stats` | `result`, `columns` | per column: type, NULLs, smallest and largest value, distinct values; a scan over 20 s answers with the rows it reached; at most two scans run at once |
| `session_send` | `session`, `text` | prompts another running session, headed `[from session <sender>]`, without a card ([record 44](adr/0044-sessions-prompt-each-other-without-asking.md)); returns a cursor for `session_read` |
| `session_read` | `session`, `after`, `wait_seconds` | another session's transcript as numbered blocks after a cursor, or the last 40; can wait up to 600 s until its turns are over |
| `instances_list` | none | the other comando instances on frontear: name, id, version, busy, and their sessions ([record 123](adr/0123-instances-consult-each-other.md)) |
| `instance_ask` | `instance`, `text`, `wait_seconds` | asks another instance, `<slug>` on this machine or `<slug>@<machine>` on a paired one ([record 189](adr/0189-frontear-across-machines.md)); the reply is Claude's text in the turn that answers; waits 300 s by default, at most 600 s, else returns an id |
| `instance_reply` | `id`, `wait_seconds` | the reply to an earlier `instance_ask`, read once |
| `machines` | `view` (`self`, `list`) or `action` (`listen`, `pair`, `unpair`) with `host`, `token`, `grant`, `machine` | this machine's id, gateway address, fingerprint and token, and the paired machines; listening and pairing are allowed by the user on a card ([record 189](adr/0189-frontear-across-machines.md)) |
| `frontear_read` | `view`, `since`, `max` | this instance's frontear node as its frontear tab shows it ([record 187](adr/0187-frontear-lab-node-per-instance.md)): `overview`, `octopus`, `store`, or `traffic`, the packets after `since`, 200 by default, at most 2 000, with how many were missed and the next `since` |

**Every action you have, an agent has** ([record 121](adr/0121-an-agent-has-every-action-the-user-has.md)). `core`
keeps one registry of actions, `Actions`. Each entry is a tool named `<area>_<verb>`, with a schema built from the
registry. A tool sends the same intents the window sends for the same gesture. `ActionParityTest` fails when the
window sends an intent that has no action.

| Area | Tools |
|---|---|
| sessions | `session_new`, `session_edit`, `session_open`, `session_start`, `session_stop`, `session_interrupt`, `session_fresh`, `session_checkpoint`, `session_command`, `session_model`, `session_effort`, `session_eta`, `session_order`, `session_import`, `session_subagent`, `session_clear`\*, `session_remove`\* |
| panels | `panel_add`, `panel_close`, `panel_reopen`, `panel_rename`, `panel_move`, `panel_place`, `panel_pin`, `panel_focus`, `panel_bind_session`, `panel_bind_connection`, `panel_run`, `panel_cancel`, `panel_refresh`, `panel_remove`\* |
| approvals | `approval_answer`: allow with a scope, deny with a message, or answer a question card |
| connections | `connection_save`, `connection_test`, `connection_open`, `connection_close`, `connection_schema`, `connection_remove`\* |
| views and the window | `view_pin`, `view_place`, `view_close`, `notice_dismiss` |
| servers | `server_start`, `server_stop` for `frontear` or `cdb`; `frontear` is this instance's own node |
| commands and folders | `gradle_run`, `command_cancel`, `folders_browse` |
| terminals | `terminal_list`, `terminal_start`, `terminal_send`, `terminal_read`, `terminal_interrupt`, `terminal_stop`\* ([record 148](adr/0148-agents-drive-a-panel-terminal.md)) |

Tools marked \* are destructive. Notes on single tools:

- `session_new` takes a name, a working folder, a model, an effort, a checkpoint command and an optional first
  `prompt`, which starts the session. It takes no permission mode ([§1.1](#11-the-process)).
- `session_eta` reports the step under way and the task's goal in minutes from now, the timebox you gave, and a
  percentage from 0 to 100. The session bar shows the run.
- `terminal_read` returns numbered lines of plain text, at most 64 kB, and can wait up to 120 s. `terminal_send`
  raises a card ([§4.1](#41-approval-cards)).
- The three instance tools are not in the registry: the window has no gesture for them. They work while this
  instance is connected to frontear ([Workbench](workbench.md)).

Rules every action follows:

| Rule | Detail |
|---|---|
| The answer | An action first asks the reducer whether it would refuse the intent (`Reducer.refusal`), and answers a refusal as a tool error. Otherwise it answers `sent: <intent>`, and the intent applies in order after what the window queued. |
| The creator | A session made by `session_new` or `session_import` records its caller in `createdBy`. Removing a session hands its children to its own creator, so no chain breaks. |
| No self-approval | `approval_answer` refuses a card for any caller in the asker's creation family: the asker, its creators, and every session any of them made. Only you or an unrelated session answers it. |
| Destructive tools | A destructive tool runs only for a caller you granted it in the data root's `agents.json`: `{"grants": {"<caller>": ["destructive"]}}`. `session_clear` and `session_remove` also run for the session's creator. |
| Starting a stopped session | Only you or one of its creators start it again, whichever action would start it. Stopping and interrupting are open to every caller. |
| A notice | Every act except a read and a refusal names its caller in the status bar. |

Rules the `ui_` and `db_` tools follow:

| Rule | Detail |
|---|---|
| Which panel | Without `panel`, an editor tool writes to the focused panel when it is bound to the calling session, else to the session's own panel. A panel bound to another session needs `force`. Every edit posts a notice. |
| Which connection | Without `connection`, a database tool uses the connection of that same panel. |
| Validation | Every view argument is checked before an intent is sent. An invalid one is a tool error that names the problem and changes nothing. |
| Limits | 100 000 table rows; 2 000 000 plot points; 200 SQL rows to Claude by default, up to 10 000 with `max_rows`; 64 kB of text to Claude. |
| Placement | A session's view replaces its own unpinned view with the same title and placement, without moving you to it. A pinned view is never replaced. Titles are clipped to 80 characters. |

**Other instances.** The instance asked makes a session `consult-<asker>` on the first question and prompts it in
its panel, so its user sees the question. It replies when the turn ends, with one of four statuses:

| Status | When |
|---|---|
| `ok` | the turn ended; the text is the reply, at most 64 kB |
| `refused` | its user denied the asker in `agents.json`: `{"peers": {"deny": ["<id>"]}}` |
| `timeout` | the turn has not ended within 600 s |
| `error` | anything else |

The deny list trusts the name an asker gives; frontear does not check it. So the list refuses an instance
that names itself truly, and is not access control.

**The audit log.** comando appends every tool call and approval decision to `audit/audit-<UTC date>.jsonl` in the
data root: time, session, event, tool, the arguments clipped to 2 000 characters, the outcome. It never reads the
log back.

**Metrics.** What sessions spend and how their work ends goes to the machine's cdb, and to no file
([record 146](adr/0146-every-metric-goes-to-the-machine-questdb.md),
[record 180](adr/0180-cdb-is-the-machine-server.md)). Each row carries the data root as `instance`.

| Table | A row per |
|---|---|
| `comando_turn` | turn: model, base effort, duration, model calls, token usage, context and window, how it ended |
| `comando_turn_model` | model of a turn's `modelUsage`, as running totals per conversation |
| `comando_agent` | subagent's end: type, description, model, status, duration, tool calls, and its spend read from its own file |
| `comando_usage` | plan-usage read: provider, window, percent, reset |
| `comando_queue` | queue reading: depth and peak |
| `comando_wait` | closed approval card: who asked, the kind and tool, how it ended, how long it waited |

A bounded queue and one thread write the rows in batches. A batch the server does not take is lost, and the first
of an outage is a warning notice. Money fields are kept as they arrive and shown nowhere. comando reads back one
value: a stored conversation's last context size, when the conversation loads. `-Dcomando.metrics=false` keeps no
metrics, as the tests do ([record 110](adr/0110-metrics-for-calibration.md)).

## 4. Approvals and safety

![Which tool calls reach you: when Claude Code's own permission rules settle a call, comando is not asked; otherwise Claude Code calls the approve tool; for a session in bypass comando allows Bash, Edit, Write, MultiEdit and NotebookEdit once by itself, unless the command holds rm or rmdir; every other call becomes a card that you answer with Allow once, For session, Always or Deny; an unanswered card is denied after 24 hours](images/cc-card.svg)

> **Learn: least privilege.** A program should be able to do what its job needs and nothing more. Then a mistake, or
> a trick hidden in a web page the agent reads, can only do limited harm. An agent that edits files and runs
> commands is powerful, so each new kind of action needs a yes from a person first. A rule you save turns one yes
> into a standing one, as narrow as the command you saw. comando's cards are that yes.

### 4.1 Approval cards

`--permission-prompt-tool mcp__comando__approve` makes Claude Code ask comando before each tool call that its own
permission rules do not settle. The call waits until the card has an answer.

![An approval: claude -p calls the approve tool on comando's MCP server with the tool's name, input and id; the server puts a card in the Approvals tab and waits until you answer, at most 24 hours; your answer goes back to Claude Code as allow or deny, with any changed input and any permission rule to remember](images/approval.svg)

| Kind | Raised by | Answers |
|---|---|---|
| tool | Bash, Edit, Write, an MCP tool | Allow once (⏎ or A), For session (S), Always (L twice; Bash and WebFetch only), Deny (D or Esc) |
| question | `AskUserQuestion` | Answer (⏎ or A, once every question has one), Skip (D or Esc); each question has a plain text box for another answer |
| plan | `ExitPlanMode` | Approve plan (⏎ or A), "Approve, auto-accept edits", Keep planning (D or Esc); a session in bypass stays in bypass |
| SQL write | `db_run_sql` with a statement that is not read-only | Run (⏎ or A), Deny (D or Esc); the card names the connection and the statement |
| connection | `connection_save` that clears `production` or lowers the TLS mode | Allow (⏎ or A), Deny (D or Esc), by you only: `approval_answer` refuses it for every agent |
| terminal | `terminal_send` | Allow (⏎ or A), Deny (D or Esc), by you only: `approval_answer` refuses it for every agent. A caller granted `"terminal"` in `agents.json` raises no card |

**A session in bypass** gets no card for Bash, Edit, Write, MultiEdit or NotebookEdit. comando allows the call once,
saves no rule, writes `Allow[self: session in bypass]` to the audit log and names the call in a notice. These keep
their card: an MCP tool, a question, a plan, a session that reports `plan` mode, comando's own cards, and a Bash
command that holds `rm` or `rmdir` as a word
([record 164](adr/0164-comando-allows-claude-codes-own-cards-in-bypass.md)).

**The rule an answer saves.** "For session" and "Always" return a rule to Claude Code, for the session or for the
project's `.claude/settings.local.json`. The card shows the rule before you answer. A rule follows how Claude Code
matches it and never allows more than the command could do (`PermissionRules`):

| Tool | Rule | Always |
|---|---|---|
| Bash | one rule per subcommand of `&&`, `\|\|`, `;`, `\|`, `&` and newlines, after the wrappers Claude Code strips (`timeout`, `time`, `nice`, `nohup`, `stdbuf`, `command`, `builtin`, `noglob`). The rule is the command's prefix with `:*`: one word (`Bash(ls:*)`), two for commands with subcommands (`Bash(git commit:*)`), three for runners (`npm run test:*`) and `python -m <module>`. The exact command instead for anything that runs other code (`sudo`, `env`, `xargs`, `eval`, shells, interpreters, `ssh`, `rm`, `docker exec`, `find -exec`, …), nested commands, here-documents, and prefixes that hold a quote, `$`, `=` or an option | yes |
| WebFetch | `domain:<host>` | yes; for a URL with no host, the tool for the session only |
| Edit, Write, MultiEdit, NotebookEdit | `Edit(//abs/dir/**)`, the file's folder | no |
| Read, Glob, Grep | `Read(//abs/dir/**)`; without a `path`, the tool | no |
| MCP and other tools | the tool | no |

**How the Approvals tab behaves.**

| Event | Behaviour |
|---|---|
| A new card | The Approvals tab comes forward at its top. The keyboard stays where it is, so your typing goes on. The tool call reads "waiting for your approval · ⌃2" in the Session tab. |
| The queue | One queue for the window, in groups: the focused panel's session first, then each other session, the group with the oldest card first. The title counts the cards: "Approvals (2)". |
| Always | It writes a project file, so press its key twice within 3 s. Esc cancels a pending Always before it denies. |
| Deny | It carries the note you type on the card. ⌘⏎ denies with the note. ↑ and ↓ move between cards. |
| The last card answered | The Session tab of the session that asked comes forward, with the keyboard. |
| No answer in 24 hours | The card is withdrawn and its call denied; the denial says you did not answer. |
| Claude Code cancels the call, the process exits, or the session is removed | The card is withdrawn and its call denied. At exit every waiting card is denied. |
| A turn ends with nothing queued and no background work | A SQL write card and a terminal card are withdrawn. While background work runs every card stays ([record 43](adr/0043-a-session-waiting-on-background-work-shows-it.md)). |

A card appears only when a session cannot go on without you
([record 150](adr/0150-a-card-only-when-a-session-cannot-go-on.md)). A decision that blocks nothing is a
`**Needs you:**` line in its task instead ([§2](#2-the-checkpoint)).

### 4.2 SQL from Claude

`db_run_sql` runs on the connection's lane, behind your own runs. It waits at most 90 s: a statement still queued
is then withdrawn, and a running one ends at its 60 s statement timeout. Your ⌘. does not stop it.

| Statement | Ordinary connection | Production connection |
|---|---|---|
| read-only | runs | runs |
| not read-only | a card; runs on Run | refused; nothing runs |

A statement is read-only when all three hold, with comments removed:

| Check | Words |
|---|---|
| its first word is one of | `SELECT`, `WITH`, `SHOW`, `EXPLAIN`, `DESCRIBE`, `TABLES` |
| no later word outside quotes is one of | `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `UPSERT`, `INTO`, `CREATE`, `DROP`, `ALTER`, `TRUNCATE`, `GRANT`, `REVOKE`, `COPY`, `CALL`, `VACUUM`, `REINDEX`, `CLUSTER`, `LOCK`, `REFRESH`, `NOTIFY`, `SHARE` |
| it holds none of | a semicolon between statements, a `$` outside quotes, a backslash inside a quoted string |

The check catches a write behind a read-only first word: a data-modifying CTE, `EXPLAIN ANALYZE` of a write,
`SELECT … INTO`, `SELECT … FOR UPDATE`. It refuses what it cannot settle, so text with several statements is never
read-only. It cannot see a function with side effects called from a `SELECT`. The check guards against mistakes and
is not access control: give a production connection a role without write privileges
([record 23](adr/0023-sql-from-claude-lexical-policy-production-refused.md)).

A production connection frames its panels in red, shows a red "prod" on its sidebar row, refuses Claude's writes,
and runs your own statements as typed. An agent cannot weaken a connection unasked. A `connection_save` that clears
`production`, or lowers the TLS mode, raises a connection card, and nothing changes until you allow it. The TLS
modes, weakest first: `disable`, `allow`, `prefer`, `require`, `verify-ca`, `verify-full`. `connection_save` keeps
every field the call leaves out ([§4.3](#43-secrets) has the one exception).

### 4.3 Secrets

| Secret | Where it lives |
|---|---|
| Claude login | Claude Code's own; comando never sees an API key |
| MCP tokens | in memory, and in the session's MCP configuration under `run/`, readable by its owner only; deleted when the process exits |
| Database passwords | the macOS login keychain, service `comando`, through `/usr/bin/security`; `connections.json` keeps an empty password ([record 20](adr/0020-passwords-in-the-macos-keychain-through-security.md)) |
| Intent log | every `password` field blanked |

The keychain holds passwords for the default data root, and for another data root only with
`-Dcomando.keychain=true`. Otherwise the password stays in `connections.json`.

A password belongs to the host, port, database and user it was given for. The keychain account is the connection's
name and that target. A save that changes one of the four drops the password, unless the save gives it again, and a
notice asks for it. In the dialog, only typing in the password field gives it again. From `connection_save`, a
`password` argument does. So a stored password is never sent to another target.

The keychain tool takes the password as an argument, so the process list shows it for the milliseconds the tool
runs. It never reaches a file. Every keychain call is bounded at 10 s. When the keychain cannot be asked, the
connection loads without its password and later saves keep the stored item. "Copy details" on a connection never
includes the password.

### 4.4 Text is never rendered as HTML

Swing renders a label, button, tooltip, tab title or table cell as HTML when its text starts with `<html>`, and
loads the images that HTML names. Text from Claude, a database or a stored conversation is someone else's text. So
it goes through `Plain`: renderers with HTML off, and a guard character before an `<html>` prefix.

Markdown shows only pictures that are files on this machine; any other image is its alt text
([record 58](adr/0058-markdown-shows-the-pictures-beside-it.md)). No content that reaches the window makes comando
send a network request. A link opens in the browser only at your double click.

## Where next

- [Tutorial](tutorial.md): start a session and answer your first card.
- [Workbench](workbench.md): the window the tools act on, and frontear.
- [claude](modules/claude.md) and [mcp](modules/mcp.md): the classes behind this page.
- [The kit](kit.md): the root a session runs on, and what a kit adds to it.
- [Architecture](architecture.md): intents, the reducer, and why a tool and a gesture are the same thing.
