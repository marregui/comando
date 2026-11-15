# 164. comando allows Claude Code's own cards in a session in bypass

| Date | Status | Decided by |
|---|---|---|
| 2026-11-15 | accepted; built | Miguel and Claude |

## 1. Context

A session launched in `bypassPermissions` still asks: Claude Code's parser rates some shell commands too complex to
check (zsh parameter flags such as `${(f)…}`, unscanned heredoc bodies, brace expansion) and sends them to the
permission prompt tool, `approve`. On 2026-11-15 the audit log held 16 such cards; Miguel allowed every one, most
without reading it, and "Always" saved a rule for the exact command, which never came again. He asked that comando
answer them: "I would rather you had to say sorry than have to ask."

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| which cards | a card for Bash, Edit, Write, MultiEdit or NotebookEdit from a session in bypass: the mode it reported, or the kit's while it reported none | for these tools the kit sets no ask rule, so a card in bypass is Claude Code's own check |
| the answer | allowed once, the input unchanged, no rule saved | nothing is kept that a later call could reuse |
| what still asks | an MCP tool (the kit's `permissions.ask` rules name its Slack and Gmail sends), a question (`AskUserQuestion`), a plan (`ExitPlanMode`), a session that reported `plan`, comando's own cards (a SQL write, a weakened connection, a Terminal send), and a Bash command holding `rm` or `rmdir` as a word, a path to either included, the version-control `rm` subcommand not | each is a choice only the user makes, or a rule the user set; a removal is the call that cannot be undone, and a false match costs one card |
| the trace | the audit log records the call with `Allow[self: session in bypass]`; an info notice names the session and the call | what comando allowed stays visible and searchable |

## 3. Consequences

- A session in bypass runs without a card for a command Claude Code cannot parse; the guard it loses is that parse
  check, which the user had answered with an unread allow.
- A session in any other mode keeps every card.
- An ask rule the user adds later for one of the five tools is not honoured in a session in bypass: comando answers its
  card. A rule meant to hold there names the tool in this record's list of what still asks.
