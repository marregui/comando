# 16. Unsigned macOS build

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel, on the defaults proposed in the plan review |

## 1. Context

Signing and notarisation need a paid Developer ID. The repository is private and the app is used by its author.

## 2. Decision

The DMG is unsigned until the repository is public; then Developer ID signing and notarisation.

## 3. Consequences

Gatekeeper asks for confirmation on first open (right-click, Open). `./gradlew :app:dmg` builds the image.
