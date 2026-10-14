# 31. The status bar shows the plan's usage

| Date | Status | Decided by |
|---|---|---|
| 2026-10-14 | accepted | Miguel (the numbers, where, every 5 minutes), Claude (source and how) |

## 1. Context

Miguel checks his Max plan's limits on claude.ai's usage page: the session window, the week across all models, and
the week for Fable. He wants them in comando, refreshed every 5 minutes, without cost to the work.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| source | `claude --safe-mode -p /usage --no-session-persistence --output-format json`, the windows parsed from the command's text | `/usage` is a local command: no prompt, 0 tokens (measured: `total_cost_usd` 0, `num_turns` 0); it is the only source with the Fable week |
| not the stream's `rate_limit_event` | a session's `unifiedWindows` carry the 5-hour and 7-day shares, but only while a session works, and the Fable week only as `seven_day_overage_included`, a key no document names | an idle comando would show nothing |
| not the usage endpoint | comando never reads Claude Code's OAuth token or calls an undocumented API | the credentials stay with the CLI |
| safe mode | no hooks, plugins, MCP servers, skills or CLAUDE.md load; a read takes about 1.2 s of wall time (2.9 s without it) | a read every 5 minutes must not start the user's MCP servers or fire the user's hooks |
| cadence | at start, then every 5 minutes, on a thread of its own (`plan-usage`); a read is stopped after 30 s | Miguel's interval |
| where | the status bar, right side: `Session 5% (resets 7:19pm) · Week 43% · Fable 17%`; the tooltip has every reset time and when it was read | visible with the sidebar hidden |
| colour | muted; warning from 75% of any window, danger from 90% | the bar draws the eye only near a limit |
| a failed read | the last good read stays, muted, and the tooltip says why; with no good read, nothing shows | a passing failure does not blank the numbers |

## 3. Consequences

The parser depends on the wording of `/usage` ("Current week (Fable): 17% used · resets …"). A Claude Code release
that rewords it leaves the bar on its last good read, with the reason in the tooltip and a warning in the log.
A model-only week such as "Current week (Sonnet only)" shows as `Sonnet`.
