# 23. SQL from Claude: lexical policy, production refused

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted; amended 2026-11-01 (see Amendments) | Claude |

## 1. Context

Claude may run SQL through `db_run_sql`. Asking for every `SELECT` would put a card before every read; running every
statement unasked would let a mistake write to a database.

## 2. Decision

A lexical check decides whether a statement is read-only ([SQL from Claude](../claude-code.md#42-sql-from-claude)). A
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

## Amendments

2026-11-01, Miguel's decision D2 of the first-release review: the production flag guards the database against the
agent itself, so the agent does not clear it unasked. A `connection_save` that clears `production`, or lowers the TLS
mode (weakest to strongest: `disable`, `allow`, `prefer`, `require`, `verify-ca`, `verify-full`), raises an approval
card (`ApprovalKind.CONNECTION`: the connection, what would weaken, the connection as it would be saved; Allow or Deny),
and nothing changes until the user allows it; a card denied, unanswered, or answered after the connection changed
leaves it as it was. The card is the user's alone: `approval_answer` refuses it for every session, of whatever family
(D2's strict reading, settled by Claude-main in the review round on the fixes), so a second agent cannot allow what
the first may not do. Tested in `ActionToolsTest`.
