# 7. Default permission mode

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted; amended 2026-11-01 (see Amendments) | Miguel |

## 1. Context

Claude Code has a configured default permission mode per user and project. comando routes permission prompts to its
own cards.

## 2. Decision

A session uses the CLI's configured default unless its spec names a mode. Approvals go through comando's `approve`
tool.

Miguel decided, on the default proposed in the review of the design plan.

## 3. Consequences

comando neither weakens nor strengthens the user's Claude Code settings. `--permission-mode` is passed only when the
session names a mode; `--permission-prompt-tool mcp__comando__approve` is passed with the MCP configuration
([The process](../claude-code.md#11-the-process)).

## Amendments

2026-11-01, Miguel's decision D1 of the first-release review: the kit's `settings.json` permission rules hold for every
session, and a session names no mode of its own (a project's own settings still apply, decision D11 below). comando
passes neither `--permission-mode` nor `--add-dir`; `SessionSpec` has no permission mode and no extra folders;
`session_new` and `session_edit` take neither (record 121); the session dialog asks neither and shows, in its context
line, the mode `permissions.defaultMode` of the kit's `settings.json` sets, read-only, with what it does ("permissions:
default, asks before each tool (the kit's settings.json)" when it sets none). No caller hands a session a mode through
comando. A `sessions.json` entry written before keeps its `permissionMode` and `addDirs` until the next save; they are
read and ignored. The mode Claude Code reports at a session's start (`system/init`) still shows in the start note of its
transcript. `--permission-prompt-tool mcp__comando__approve` is passed as before. Tested in `ClaudeCommandTest`,
`ResourceKindTest`, `ActionToolsTest` and `StoreTest`.

2026-11-01, Miguel's decision D11 of the first-release review: Claude Code's default stays. A session's working
folder's own `.claude/settings.json` and `settings.local.json` apply on top of the kit's and may change the mode and
widen the rules, and an agent may give a session it creates any working folder; so the kit's rules are not a bound on
what an agent's session may do, and this record makes no such claim. The session dialog says where the effective mode
comes from: under its context line, when the working folder has those files, a second line names them and the
`permissions.defaultMode` they set (the local file's over the shared one), which overrides the kit's. Tested in
`ProjectSettingsTest` and `ResourceDialogTest`.
