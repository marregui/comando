# 23. SQL from Claude: lexical policy, production refused

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted by Miguel 2026-10-13 | Claude, during implementation |

## 1. Context

Claude may run SQL through `db_run_sql`. Asking for every `SELECT` would make the tool useless; running every statement unasked would let a mistake write to a database.

## 2. Decision

A lexical check decides read-only ([Approvals and safety](../claude.md#32-sql-from-claude)). Read-only runs; anything else needs a card on an ordinary connection and is refused on a production one.

## 3. Consequences

The check errs towards "not read-only". It cannot see side effects of functions called from a `SELECT`; production connections should use a role without write privileges.
