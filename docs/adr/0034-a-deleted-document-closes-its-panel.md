# 34. A deleted document closes its panel

| Date | Status | Decided by |
|---|---|---|
| 2026-10-14 | accepted; replaces, for documents, the "file deleted outside" row of [record 27](0027-panels-reload-files-changed-outside.md) and the deletion sentence of [record 29](0029-the-scratchpad-is-a-navigator.md) | Miguel (the tab goes with the file), Claude (how) |

## 1. Context

Records 27 and 29 kept a panel open when its file was deleted outside comando, warned once, and wrote the file again
on the next save. For a scratchpad document that left a tab on a file that no longer exists, and a warning in the
status bar; Miguel deleted a scratchpad day folder while one of its files was open and expected the tab to go.

## 2. Decision

| Case | Rule | Why |
|---|---|---|
| a document (a file opened in a panel, outside `panels/`) deleted while comando runs, no unsaved edits | the panel closes, as its tab's close button closes it (asking first when its shell runs a job); the notice says "Closed ALPHA: ALPHA.md was deleted outside comando"; ⌘⇧T does not offer it | the file is the user's, and it is gone |
| the same, with unsaved edits | the panel stays; its save writes the file again (and its folder) | edits are never lost |
| a document deleted while comando was closed | its panel is dropped at the start, with one notice naming the files; a panel with text that was never saved is kept | nothing to show |
| a panel's own file in `panels/` deleted outside | unchanged: a notice, once; the panel writes the file again on its next save | the panel's text is the user's work in comando |

## 3. Consequences

Deleting a scratchpad folder closes the tabs of its documents within about a second (the store's file check). A
closed document is not written: closing it does not bring a deleted folder back.
