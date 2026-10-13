# 22. No Parquet export

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted by Miguel 2026-10-13 | Claude, during implementation |

## 1. Context

M4 listed CSV and Parquet export. Parquet writers for Java (parquet-java) depend on Hadoop's configuration and file-system classes, tens of megabytes for one menu item.

## 2. Decision

Results export as CSV only.

## 3. Consequences

Open: a Parquet export without Hadoop, for example through DuckDB's JDBC driver or a small writer, if a need appears.
