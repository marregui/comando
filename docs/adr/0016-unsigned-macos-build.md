# 16. Unsigned macOS build

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted; amended 2026-10-27 (see Amendments) | Miguel |

## 1. Context

Signing and notarisation need a paid Developer ID. The repository is private and the app is used by its author.

## 2. Decision

The disk image is unsigned until the repository is public; then Developer ID signing and notarisation.

Miguel decided, on the default proposed in the review of the design plan.

## 3. Consequences

Gatekeeper asks for confirmation on first open (right-click, Open). `./gradlew :app:dmg` builds the image. The release
packages for macOS, Linux and Windows are unsigned as well
([record 80](0080-the-kit-is-a-claude-code-setup-comando-runs-its-sessions-on.md)), and on macOS the install clears
the quarantine flag.

## Amendments

2026-10-27: the repository is public, and the packages stay unsigned; Developer ID signing and notarisation wait
until comando is announced or its packages are meant to be downloaded by hand. Every supported install goes through
`INSTALL.md`, whose agent clears the quarantine flag on macOS, so an unsigned disk image installs without a manual
step. Miguel decided.
