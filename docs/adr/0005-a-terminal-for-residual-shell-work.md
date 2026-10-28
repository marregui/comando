# 5. A terminal for residual shell work

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted; amended 2026-10-28 (see Amendments) | Miguel |

## 1. Context

With Claude in comando, the remaining reasons to open iTerm2 are interactive logins, git by hand and ssh.

## 2. Decision

An embedded terminal, one per editor panel, after M1.

Miguel decided, on the default proposed in the review of the design plan.

## 3. Consequences

JediTerm and pty4j are dependencies: JediTerm is LGPL-3.0, pty4j EPL-1.0. Both are used unmodified, as libraries. The
terminal is described in [Terminal](../workbench.md#41-terminal).

## Amendments

2026-10-28: a question that a job in a panel's terminal raises (closing the panel, quitting comando) names the job as
typed, read from its process group's leader, a script by its own name rather than its interpreter's (`man cat`, not
`sh`), and says it runs in the panel's Terminal tab: "Quitting stops man cat in notes' Terminal tab.", or one line per
item when several things would stop. Before, quitting said "1 shell job", and a job that was a script was named by its
interpreter. Miguel asked; tested in `TerminalWidgetTest`, `TerminalTabsTest` and `QuitQuestionTest`.
