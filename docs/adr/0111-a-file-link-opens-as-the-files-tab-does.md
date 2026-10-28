# 111. A file link opens as the Files tab opens a file, and is found where it was written from

| Date | Status | Decided by |
|---|---|---|
| 2026-10-28 | accepted | Miguel and Claude |

## 1. Context

Miguel double clicked `harness/07-fleet-vision.md` in a session's answer: the log says "Cannot open
harness/07-fleet-vision.md: no such file". The path was written relative to the scratchpad's day folder
(`~/.claude/BRAIN/SCRATCHPAD/2026-10-28/`), and comando read it from the session's folder (`~/.claude`) alone. Had it
been found, it would have opened in the side pane, as a `path:line` link does ([record 108](0108-links-in-every-card.md)),
not "as if I had chosen it and double clicked on it" in the Files tab, which opens a panel.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| where it opens | a file link without a line opens as the Files tab opens a file: a text file in a panel bound to it, or the panel that has it comes forward; a binary or large file in the side pane. A `path:line` link keeps the side pane, at its line | one way to open a file; a panel does not go to a line, the side pane does |
| where it is found | as written, read from the session's folder; when no file is there, a file whose path ends with the one written, under the session's folder (8 levels down), then under each folder of the Files tab, in order; of several under one folder, the newest. Build and version-control folders are skipped; at most 100 000 entries are visited; off the EDT. An absolute path, or one that climbs (`..`), is not looked for | sessions write paths relative to the folder they work in, which is often deeper than the session's own |
| not found | the notice as before: "Cannot open ‹path›: no such file" | unchanged |

Miguel decided that a file link opens as a Files-tab double click does. Claude decided the search, its order and its
bounds, and that `path:line` keeps the side pane.

## 3. Consequences

`FileSearch` is added; `ComandoUi.showFile` resolves through it and hands a line-less text file to `openFile`. Every
`Intent.OpenFile` without a line takes this route: the transcript's, the editor's, a tree view's file nodes.

Tested in `FileSearchTest` (a deeper folder, the first root and the newest, skipped folders, climbing and absolute
paths) and off screen in `LinksUiTest` (`:app:uiTest`: the link followed as a double click follows it opens one panel,
in front; a second follow opens no other; without the search the test fails).
