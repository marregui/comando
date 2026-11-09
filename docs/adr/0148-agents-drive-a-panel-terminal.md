# 148. Agents drive a panel's Terminal

| Date | Status | Decided by |
|---|---|---|
| 2026-11-09 | accepted; built | Miguel and Claude |

## 1. Context

Each editor panel has a Terminal in the lower area: the user's login shell on a pseudo-terminal, shown by JediTerm. An
agent could not reach it. On 2026-11-09 Miguel ran `gh auth refresh` there and the agent could not read the answer; he
asked for tools to start a panel's shell, type into it, read its output and stop it.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the tools | `terminal_list`, `terminal_start`, `terminal_send`, `terminal_read`, `terminal_interrupt`, `terminal_stop` | the user's gestures on the Terminal, plus a read |
| which terminal | any editor panel's, by title or id; a start does not bring the Terminal to the front | an agent works beside the user, not in front of them |
| typing | `terminal_send` asks a TERMINAL card that only the user answers (`approval_answer` refuses it), unless `agents.json` grants the caller `"terminal"`; text is at most 4,096 characters, without control characters other than tab and newline; a shell that does not run is refused, never started | what an agent types runs with the user's rights |
| reading | `terminal_read`: plain text of logical lines (a wrapped line counts once), at most 64 kB, from a `since` line number or the last `lines` lines (default 200, at most 2,000); it can wait up to 120 s for a pattern (`until`), for quiet output (`quiet_ms`, 100 ms to 10 s) or for the shell to end, looking every 100 ms and reading again while the page may lag the shell's output by less than 200 ms | an agent reads what a command answered without guessing when it is done; a read runs `ps` for the job, so an unchanged screen is not read again |
| line numbers | per panel, they only grow: lines trimmed from the 20,000-line history or cleared are reported as `skipped`, never renumbered, and a restart continues the count; under a full-screen program (vim, less) a read is the screen alone, flagged `alternate`. Known gap: a width change while the history is full drops its oldest rows without an event, and the lines held are numbered anew above every number given, so a kept `since` reads them again | a `since` an agent kept stays valid |
| interrupting | `terminal_interrupt` writes Ctrl-C, which reaches the foreground job only; refused when no job runs | a line the user is typing is never wiped, and the shell survives |
| stopping | `terminal_stop` ends the shell and what runs under it: destructive, refused without the `destructive` grant | its scrollback is lost |
| a card left open | withdrawn when the turn that asked it ends, as a statement card | Allow after the fact would type into a later prompt |

## 3. Consequences

`core` gains the port `TerminalPort` and the approval kind `TERMINAL`; `ui` implements the port in
`panels/TerminalAccess` over `TerminalTabs`, and the terminal widget numbers its lines in `LineLedger`, fed by JediTerm's
buffer events (discards, clears, width changes). `App` gives the port to `McpTools` once the window is built; before
that the tools refuse. Reads take the buffer's lock off the event thread and never wait on it while holding the lock.

## 4. Alternatives

Reading the terminal through a screenshot (Peekaboo): no text, no history, and the Terminal must be in front. A send
without a card, by the ACT kind's notice alone: a command typed with the user's rights would run unseen. A plain line
index into JediTerm's buffer: it shifts when history is trimmed or cleared, so a kept cursor would reread or skip lines.
