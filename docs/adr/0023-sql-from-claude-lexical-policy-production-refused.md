# 23. SQL from Claude: lexical policy, production refused

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Claude |

## 1. Context

Claude may run SQL through `db_run_sql`. Asking for every `SELECT` would put a card before every read; running every
statement unasked would let a mistake write to a database.

## 2. Decision

A lexical check decides whether a statement is read-only ([SQL from Claude](../claude.md#32-sql-from-claude)). A
read-only statement runs. Any other statement needs an approval card on an ordinary connection and is refused on a
production one.

Claude decided during implementation; Miguel accepted the record on 2026-10-13.

## 3. Consequences

The check, `SqlPolicy`, errs towards "not read-only". It cannot see the side effects of functions called from a
`SELECT`; against those, a production connection needs a database role without write privileges.

## 4. Alternatives

| Option | Why rejected |
|---|---|
| a card for every statement | a card before every read |
| every statement run unasked | a mistake could write to a database |
