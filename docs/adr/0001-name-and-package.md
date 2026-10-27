# 1. Name and package

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel |

## 1. Context

The project started as a desktop client for PostgreSQL-wire databases. It now drives Claude Code sessions as well as
SQL. It is a personal repository, public since 2026-10-27.

## 2. Decision

The name stays "comando". The code of every module lives under the package `dev.marregui.comando`.

Miguel decided, on the default proposed in the review of the design plan.

## 3. Consequences

Every module, test and resource moved to the new package. Nothing refers to the old package.
