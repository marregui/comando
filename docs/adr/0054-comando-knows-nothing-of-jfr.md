# 54. comando knows nothing of JFR

| Date | Status | Decided by |
|---|---|---|
| 2026-10-17 | accepted | Miguel and Claude |

## 1. Context

comando had Open JFR recording… (event counts, CPU and heap plots), Run jfrq on a recording… (an external launcher
found through `tools.json`, `COMANDO_JFRQ` or the `PATH`), a chart icon for `.jfr` files, and five JFR events of its own
(`comando.Intent`, `SqlRun`, `ClaudeTurn`, `McpCall`, `EdtBlocked`). None of it was asked for as a feature of the
product: JFR and the author's jfrq are how comando is measured while it is developed, not something it offers.

This record removes JFR and jfrq from the tools of the first implementation, the JFR row of
[record 48](0048-stalls-are-a-line-hangs-a-stack-the-heap-starts-small.md), the `jfrq` key of
[record 50](0050-comando-ships-a-claude-code-kit.md) and the JFR icon of
[record 29](0029-the-scratchpad-is-a-navigator.md).

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| features | Open JFR recording, Run jfrq, their intents, effects, port methods and the recording reader are removed | comando is a front-end for Claude Code and databases; profiling tools are the user's own |
| events | the five custom events are removed; no class imports `jdk.jfr` | comando is profiled from outside, with the JDK's own events, as any JVM |
| stalls | the EDT watchdog keeps its log lines (one INFO per stall, a WARNING with the stack per hang) | a stall stays visible without a recording |
| settings | `tools.json` keeps `gradleWrapper` only; a `jfrq` key left in a user's file is ignored; `COMANDO_JFRQ` is no longer read | nothing reads them |
| files | a `.jfr` file in the Files tab is a file like any other | no format comando does not handle gets an icon of its own |
| runtime | the packaged runtime keeps `jdk.jfr` and adds `jdk.management.jfr` and `jdk.management.agent` | no code uses them; they let the installed app be recorded, and a recording be read over JMX while it runs, like any JVM |

Miguel decided that comando has no JFR menu items and is unaware of JFR in any way, its own events included, JFR being
how his Claude Code setup checks that comando is fast, stable and lean; Claude decided how: what goes, what stays, and
where a stall is still seen.

## 3. Consequences

`Intent.RunJfrq`, `Intent.OpenRecording`, their effects, `ToolPort.runJfrq` and `openRecording`, `Recordings`,
`ToolSettings.jfrq` and the file choosers' extension filter, which only the recording chooser used, are gone. The SQL
lane's run and the MCP call lose their measuring wrappers. `:app:snapshot -Ptools=true` runs Gradle in a scratch build
whose wrapper only prints, so it needs no project on the machine. The flaky `recordingBecomesATableAndPlots` test went
with the feature.
