# 1. Name and package

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel, on the defaults proposed in the plan review |

## 1. Context

The project started as a desktop client for PostgreSQL-wire databases. It now drives Claude Code sessions as well as SQL, and stays a personal, private repository.

## 2. Decision

Keep the name "comando". All code lives under `dev.marregui.comando`.

## 3. Consequences

Every module, test and resource moved to the new package. Nothing refers to the old package.
