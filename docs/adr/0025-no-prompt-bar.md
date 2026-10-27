# 25. No prompt bar

| Date | Status | Decided by |
|---|---|---|
| 2026-10-14 | accepted | Miguel |

## 1. Context

[Record 24](0024-the-window-belongs-to-the-user.md) (item 24.6) gave each panel a prompt bar under its editor, with a
history of prompts per session kept in `prompts.json`. In use, the editor already carried every prompt: a block is sent
with ⌘⇧⏎, or with ⌘⏎ when the gutter marks it as a prompt. The bar took height from the editor and was not used. This
record replaces item 24.6.

## 2. Decision

Panels have no prompt bar. Prompts are written in the editor like SQL and run with the same keys.

Miguel decided.

## 3. Consequences

The prompt history goes with the bar: `prompts.json` is no longer read or written, and a file left by an earlier
version stays where it is. ⌘L no longer has a command. The run keys act only while the keyboard is in the editor. A
prompt sent while its session is stopping stays in the editor, to be sent again.
