# 3. Keys for running text

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel, on the defaults proposed in the plan review |

## 1. Context

One editor holds SQL and prompts, so one key must run either, and the user needs a way to force each.

## 2. Decision

⌘⏎ runs the selection or block with automatic classification; ⌘⇧⏎ forces a prompt; ⌥⏎ forces SQL.

## 3. Consequences

The classifier errs towards prompts for prose that starts with a SQL keyword ([Command dispatch](../design/command-dispatch.md)). ⌃⏎ was added later for the terminal.
