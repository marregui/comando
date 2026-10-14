# 25. No prompt bar

| Date | Status | Decided by |
|---|---|---|
| 2026-10-14 | accepted; replaces item 24.6 of [record 24](0024-the-window-belongs-to-the-user.md) | Miguel |

## 1. Context

Record 24 gave each panel a prompt bar under its editor, with a history of prompts per session kept in `prompts.json`. In daily use the editor already carries every prompt: a block is sent with ⌘⇧⏎, or with ⌘⏎ when the gutter shows it is a prompt. The bar took height from the editor and was not used.

## 2. Decision

Panels have no prompt bar. Prompts are written in the editor like SQL and run with the same keys.

## 3. Consequences

The prompt history goes with the bar: `prompts.json` is no longer read or written, and a file left by an earlier version stays where it is. ⌘L no longer has a command. The run keys act only while the keyboard is in the editor. A prompt sent while its session is stopping stays in the editor, to send again.
