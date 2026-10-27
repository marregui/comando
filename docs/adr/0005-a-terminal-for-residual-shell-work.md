# 5. A terminal for residual shell work

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel |

## 1. Context

With Claude in comando, the remaining reasons to open iTerm2 are interactive logins, git by hand and ssh.

## 2. Decision

An embedded terminal, one per editor panel, after M1.

Miguel decided, on the default proposed in the review of the design plan.

## 3. Consequences

JediTerm and pty4j are dependencies: JediTerm is LGPL-3.0, pty4j EPL-1.0. Both are used unmodified, as libraries. The
terminal is described in [Terminal](../workbench.md#41-terminal).
