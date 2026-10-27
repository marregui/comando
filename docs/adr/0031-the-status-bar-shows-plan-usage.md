# 31. The status bar shows the plan's usage

| Date | Status | Decided by |
|---|---|---|
| 2026-10-14 | accepted | Miguel and Claude |

## 1. Context

A Claude subscription's limits are shown on claude.ai's usage page: the session window, the week across all models,
and the week for Fable. They were to be shown in comando, refreshed every 5 minutes, at no cost to the work.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| source | `claude --safe-mode -p /usage --no-session-persistence --output-format json`, the windows parsed from the command's text | `/usage` is a local command: no prompt, 0 tokens (measured: `total_cost_usd` 0, `num_turns` 0); it is the only source with the Fable week |
| safe mode | no hooks, plugins, MCP servers, skills or CLAUDE.md load; a read takes about 1.2 s of wall time (2.9 s without it) | a read every 5 minutes must not start the user's MCP servers or fire the user's hooks |
| cadence | at start, then every 5 minutes, on a thread of its own (`plan-usage`); a read is stopped after 30 s | Miguel's interval |
| where | the status bar, right side: `Session N% (resets <time>) · Week N% · Fable N%`; the tooltip has every reset time and when it was read | visible with the sidebar hidden |
| colour | muted; warning from 75% of any window, danger from 90% | the bar draws the eye only near a limit |
| a failed read | the last good read stays, muted, and the tooltip says why; with no good read, nothing shows | a passing failure does not blank the numbers |

Miguel decided the numbers, their place and the 5-minute interval; Claude decided the source and how.

## 3. Consequences

The parser depends on the wording of `/usage` ("Current week (Fable): N% used · resets …"). A Claude Code release
that rewords it leaves the bar on its last good read, with the reason in the tooltip and a warning in the log.
A model-only week such as "Current week (Sonnet only)" shows as `Sonnet`.

## 4. Alternatives

| Option | Why rejected |
|---|---|
| the stream's `rate_limit_event` | a session's `unifiedWindows` carry the 5-hour and 7-day shares, but only while a session works, and the Fable week only as `seven_day_overage_included`, a key no document names; an idle comando would show nothing |
| the usage endpoint | comando never reads Claude Code's OAuth token or calls an undocumented API; the credentials stay with the CLI |

## Amendments

2026-10-27, decided by Miguel: the status bar shows the Codex plan's usage too. After each Claude read, the same
`plan-usage` thread reads Codex's plan from the Codex CLI: `codex app-server`, JSON-RPC over stdio, method
`account/rateLimits/read`, which answers with the 5-hour window and the week, each with its share used and reset time
(no prompt, no tokens; about 1.1 s, measured on codex-cli 0.163.0). The label sits after Claude's,
`Codex Session N% (resets <time>) · Week N%`, with the same colours, tooltip and failed-read rule; a window whose reset
has passed shows 0 %. The read is bounded at 10 s. Codex is found as `COMANDO_CODEX`, else `~/.local/bin/codex`,
`/opt/homebrew/bin/codex`, `/usr/local/bin/codex`, else `codex` on the PATH; without Codex installed or signed in,
the label never shows and the log says why once. The session files Codex writes under `~/.codex/sessions` were not
used: they carry the same windows, but only as of the last local Codex run.
