# 17. System.Logger instead of QuestDB's logger

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Claude |

## 1. Context

The earlier code logged through QuestDB's `io.questdb.log`, which ties logging to the QuestDB jar and its
configuration.

## 2. Decision

Code logs through `System.Logger`. `Logging` routes it through `java.util.logging` to rotating files under the data
root, and warnings to standard error. SLF4J output from libraries goes to the same place through `slf4j-jdk14`.

Claude decided during implementation; Miguel accepted the record on 2026-10-13.

## 3. Consequences

The QuestDB jar is needed only for the embedded server. One log file has every module's lines: `comando-%g.log`, at
most 1 MiB each, ten files kept.

## 4. Alternatives

| Option | Why rejected |
|---|---|
| QuestDB's `io.questdb.log` | ties every module's logging to the QuestDB jar and its configuration |
