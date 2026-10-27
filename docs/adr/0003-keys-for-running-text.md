# 3. Keys for running text

| Date | Status | Decided by |
|---|---|---|
| 2026-10-12 | accepted | Miguel |

## 1. Context

One editor holds SQL and prompts, so one key must run either, and the user needs a key that forces each.

## 2. Decision

| Key | Runs the selection or the block |
|---|---|
| ⌘⏎ | as SQL or as a prompt, by automatic classification |
| ⌘⇧⏎ | as a prompt |
| ⌥⏎ | as SQL |

Miguel decided, on the default proposed in the review of the design plan.

## 3. Consequences

The classifier errs towards a prompt for prose that starts with a SQL keyword
([SQL or prompt](../workbench.md#22-sql-or-prompt)). ⌃⏎ was added later: it types the selection or line into the
panel's terminal, and is Ctrl+Alt+⏎ on other desktops ([record 65](0065-keys-on-desktops-other-than-macos.md)).
