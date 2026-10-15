# Approvals and safety

comando gives Claude sessions a database and a window. This document lists what a session may do without asking, what it must ask for, what it can never do, and where comando keeps secrets.

## 1. Approval cards

Claude Code asks comando's `approve` tool before each tool call its permission rules do not settle. Each request becomes a card in the Approvals tab; the MCP call waits on a future until the user answers.

| Kind | Raised by | Card shows | Answers |
|---|---|---|---|
| tool | Bash, Edit, Write, an MCP tool | a one-line summary; the command, or the edit as a diff; the line "Always: <rules>", or "For session: <rules>" for a tool whose rules are not saved | Allow once (⏎ or A), For session (S), Always (L twice; Bash and WebFetch only), Deny (D or Esc) |
| question | `AskUserQuestion` | each question with its options and their descriptions (radio buttons, or check boxes when it allows several), none chosen, and a free-text field | Answer (⏎ or A, once every question has an answer), Skip (D or Esc) |
| plan | `ExitPlanMode` | the plan rendered | Approve plan (⏎ or A), Approve, auto-accept edits (button), Keep planning (D or Esc) |
| SQL write | `db_run_sql` with a statement that is not read-only | the connection and the statement | Run (⏎ or A), Deny (D or Esc) |

- A denial carries the note typed on the card, so Claude learns why.
- "For session" and "Always" return an `addRules` permission update to Claude Code, with destination `session` or `localSettings`. The rules follow how Claude Code matches them, and never allow more than the command could do:

  | Tool | Rules | Always |
  |---|---|---|
  | Bash | one rule per subcommand of `&&`, `\|\|`, `;`, `\|`, `&` and newlines, as Claude Code splits and saves them; the wrappers Claude Code strips before matching (`timeout`, `time`, `nice`, `nohup`, `stdbuf`, `command`, `builtin`, `noglob`) come off first. Each rule is the command's prefix with `:*`: one word (`Bash(ls:*)`), two for a command with subcommands such as `git`, `npm`, `./gradlew` (`Bash(git commit:*)`), three for a runner (`npm run test:*`, `uv run pytest:*`) and for `python -m <module>`. A command typed with its path keeps it: `/usr/bin/git commit` gives `Bash(/usr/bin/git commit:*)`. The exact command instead for what runs other commands or code (`sudo`, `su`, `env`, `xargs`, `eval`, `source` and `.`, `command -v` and `command -V`, shells, interpreters and runtimes without `-m` (`python`, `node`, `java`, `awk`, `osascript`, …), `npx`, `ssh`, `rm`, the exec wrappers `watch`, `setsid`, `ionice`, `flock`, `docker exec`/`run`, `docker compose exec`/`run`, `kubectl exec`, `find` with `-exec` or `-delete`), for a nested command (`$(`, backticks, `<(`, `>(`), for a command that feeds a here-document (`<<EOF`: its lines are data, so the whole command is one exact rule), and when a prefix word holds a quote, a `$`, an `=` or is an option where the subcommand would be | yes |
  | WebFetch | `domain:<host>` | yes; a URL with no host: the tool, for the session only |
  | Edit, Write, MultiEdit, NotebookEdit | `Edit(//abs/dir/**)`, the edited file's directory; Claude Code consults only `Edit` path rules for every edit tool | no: for the session only, as Claude Code does for file edits |
  | Read, Glob, Grep | `Read(//abs/dir/**)`; for Glob or Grep without a `path`, the tool itself | no |
  | MCP tools, any other tool | the tool | no |

  The card prints the rules under its buttons ("Always: …", or "For session: …" where Always is not offered), and each scope button's tooltip names the rule and where it is saved. Because "Always" writes the project's local settings, its key must be pressed twice within 3 s ("Press L again to always allow …"); a click on the button opens a confirm row in the card. Esc cancels a pending Always before it denies.
- "Approve, auto-accept edits" on a plan answers with a `setMode` update, mode `acceptEdits`, destination `session`.
- A question's answers go back in `updatedInput.answers`, keyed by question text; several chosen options are joined with ", ".
- A card is withdrawn and its call denied when the call can no longer use the answer: Claude Code cancels the call (`notifications/cancelled`), the session's process exits, or the session is removed. A statement card is also withdrawn when a turn ends with nothing queued and no background work open, because answering Run after an interrupt would act after the fact; while background work runs every card stays, since the work may have asked and an approval must not be missed ([record 43](../adr/0043-a-session-waiting-on-background-work-shows-it.md)); other cards stay, since a background agent may still wait on them.
- The keys act on the card holding the keyboard, wherever in the card it is; a click on the card's text gives it the keyboard. In the note field letters type; ⏎ denies with the note once it holds text, and gives the primary answer while it is empty; ⌘⏎ denies with the note; Esc returns to the card. In "Other answer" ⏎ answers. ↑ and ↓ move between cards, except inside a code, diff or plan viewer, where they move the caret. After an answer given in the card, the keyboard goes to the next card, else the previous one; when the card holding the keyboard is withdrawn, or leaves for any other reason, the keyboard goes to the Approvals list itself, where no key answers anything. The keyboard is never taken from outside the Approvals tab.
- While a card waits, the tool call it holds reads "waiting for your approval · ⌃2" in the Session tab, and the status bar names the request until it is answered. The card's age ("2 min ago") updates every 30 s.
- A card nobody answers is withdrawn after 55 minutes and the call is denied with that reason. Claude Code's per-server timeout is 60 minutes, so comando answers first.
- At exit every waiting card is denied ("comando is shutting down"), so no call outlives the server.

## 2. SQL from Claude

`db_run_sql` runs on the connection's lane, behind the user's own runs. The call waits at most 90 s for its result; on timeout a statement still queued is withdrawn and never runs, and one already running ends at its 60 s statement timeout. ⌘. stops the user's own run, not a statement Claude started.

| Statement | Ordinary connection | Production connection |
|---|---|---|
| read-only | runs | runs |
| not read-only | card; runs on Run | refused; the tool returns an error and nothing runs |

A statement is read-only when, after comments are removed:

1. its first word is `SELECT`, `WITH`, `SHOW`, `EXPLAIN`, `DESCRIBE` or `TABLES`;
2. no later word outside quotes is one that writes or locks: `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `UPSERT`, `INTO`, `CREATE`, `DROP`, `ALTER`, `TRUNCATE`, `GRANT`, `REVOKE`, `COPY`, `CALL`, `VACUUM`, `REINDEX`, `CLUSTER`, `LOCK`, `REFRESH`, `NOTIFY`, `SHARE`;
3. it contains no semicolon between statements, no `$` outside quotes, and no backslash inside a quoted string.

Rule 2 catches the writes that start with a read-only word: a data-modifying CTE (`WITH d AS (DELETE ...) SELECT ...`), `EXPLAIN ANALYZE` of a write (it executes the write), `SELECT ... INTO`, `SELECT ... FOR UPDATE`. Rule 3 refuses what the scanner cannot settle: dollar quoting and backslash escapes could hide a second statement from it. The check errs towards "not read-only": a column named like a keyword without quotes costs a card, not a write.

What the check cannot see: a function with side effects called from a `SELECT` (`pg_terminate_backend`, `setval`, a user function that writes). A production connection should therefore use a database role without write privileges; the lexical check is a guard against mistakes, not an access control.

Rows returned to Claude are capped at 200 by default and at 10 000 with `max_rows`; a call waits at most 90 s for its turn on the lane and its result.

## 3. Production connections

A connection marked production:

- frames every panel bound to it in red and carries a red "prod" label in the sidebar;
- refuses every statement from Claude that is not read-only (§2);
- runs the user's own statements from the editor as typed; the red frame is the warning.

## 4. The MCP server

| Control | Detail |
|---|---|
| Network | bound to `127.0.0.1` on an ephemeral port |
| Authentication | a 256-bit random bearer token per process from `SecureRandom`; the token names the calling session; it is revoked when that process ends, and a later process of a session with the same name gets a new one; a request without a known token gets 401 |
| Origin | a request whose `Origin` header names a host other than `localhost`, `127.0.0.1` or `::1` gets 403, so a page in a browser cannot call the tools |
| Token at rest | in the session's MCP configuration file under `run/`, created with mode `0600`; deleted when the process exits and at comando's exit; files a killed comando left behind are deleted at the next start |

## 5. Credentials

| Secret | Where it lives |
|---|---|
| Claude login | Claude Code's own; comando never sees or stores an API key |
| Database passwords | macOS login keychain, service `comando`, account = connection name, written and read through `/usr/bin/security`; `connections.json` keeps an empty password. The keychain is used for the default data root `~/.comando`, and for another root only with `-Dcomando.keychain=true`, so a test root never writes the user's keychain. Without it (another OS, another root, or `-Dcomando.keychain=false`) the password stays in `connections.json` |
| Intent log | every `password` field is blanked before a line is written ([Quality](quality.md)) |

The keychain tool takes the password as a command-line argument, so it is visible to the user's own processes in the process list for the few milliseconds the tool runs. It never reaches a file. Passwords are read with `find-generic-password -g`, which marks a non-ASCII password as hex, so any UTF-8 password round-trips. Every keychain call is bounded at 10 s. When the keychain cannot be asked (locked, the unlock prompt cancelled, no user session), the connection loads without its password, a warning is logged, and later saves keep the stored item instead of deleting it.

## 6. Text is never rendered as HTML

Swing renders the text of a label, button, tooltip, tab title or table cell as HTML when it starts with `<html>`, and loads the images it names while laying it out. Text from Claude (question options, view titles, notices), from a database (cells, column names, schema trees) or from a stored conversation (import titles) is shown through `Plain`: renderers have HTML turned off, and single labels get an invisible guard character in front of an `<html>` prefix. Markdown is converted with images replaced by their alt text ([UI kit](ui-kit.md#4-text)). So no content reaching the window can make comando issue a network request.

## 7. The audit log

Every tool call and every approval decision is appended to `audit/audit-<UTC date>.jsonl` in the data root: time, session, event, tool, the arguments as one JSON string, clipped to 2 000 characters, and the outcome. The file is append-only; comando never reads it back.
- A session prompting another (`session_send`) asks nothing: the sessions are trusted with each other; the prompt runs in the receiving panel, headed with the sender, and the audit log records it; a session that is not running is refused, so stopping one breaks a loop of sessions prompting each other ([record 44](../adr/0044-sessions-prompt-each-other-without-asking.md)).
