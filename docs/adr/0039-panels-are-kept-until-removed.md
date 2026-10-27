# 39. Panels are kept until removed

| Date | Status | Decided by |
|---|---|---|
| 2026-10-15 | accepted; amended by [record 88](0088-a-session-and-its-panel-share-a-name.md) and [record 91](0091-the-panels-tab-folds-into-the-sessions-tab.md) | Miguel and Claude |

## 1. Context

Under [record 26](0026-a-file-per-panel.md), closing a panel dropped it from `panels.json`: its file stayed when it held
text and was deleted when blank, its title and bindings were lost, and only ⌘⇧T (the last ten, until quit) or ⌘O (the
file, without bindings) brought it back. Nothing listed `~/.comando/panels/`. Double-clicking a session whose panel had
been closed opened a new panel, which took the next free file name, as the kept file held the first. This record
replaces that close rule.

The model adopted: comando's panels are a one-level file system, a file per panel, each with its bindings (a session,
a connection, both or neither). A file opened from outside (the scratchpad, later anywhere) is a document in a file
editor: mostly Markdown, also source code and binary formats such as JFR.

## 2. Decision

| Question | Panel (comando's own) | Document (an external file) |
|---|---|---|
| where it lives | its text in `panels/<title>.md`; its title, bindings and whether it is closed in `panels.json` | anywhere; the file is the user's |
| created | **+**, ⌘T, opening a session without a panel; the file is written at once, empty or not | opened from the Scratchpad tab or ⌘O |
| close | the tab goes; the file, title and bindings stay in `panels.json` with `"closed": true`; the text stays in memory, so it reopens at once; nothing is deleted, blank or not | the tab goes; nothing is kept |
| reopen | the Panels tab, ⌘⇧T, opening its session, or ⌘O on its file: after the focused panel, with its text and bindings | ⌘⇧T or opening the file again |
| gone | Remove (the Panels tab, its context menu), after a question that names the file, closes an open panel first, and says its session keeps running; or its file deleted outside comando, open or closed, unless edits were typed since comando saw the file gone, whose save writes it again. Removing the last open panel leaves an empty one in its place | when the user deletes the file; a document whose last write fails at close comes back on its own path at the next start, never as a file of `panels/` |
| listed | the Panels tab of the sidebar, open and closed, by title, with session, connection and file name | the Scratchpad tab |
| a closed panel's file edited outside | the text is taken at once: no editor shows it | as an open panel's, while open |
| a file of `panels/` that no panel names (left by an older comando, or put there by hand), also when the JSON files are gone | becomes a closed panel at the start, titled after it, with no bindings; a notice names it. A stored name names a file when it is the same file (case-insensitive on APFS by default, exact on a case-sensitive volume) | |
| an open panel whose file is missing at the start | gets an empty file: an older comando never wrote a blank panel's file | dropped, as before ([record 34](0034-a-deleted-document-closes-its-panel.md)) |
| a closed panel whose file is missing at the start | dropped, with a notice | |
| a session | its panel is its panel open or closed: opening the session reopens it ([record 38](0038-a-session-has-one-panel.md)); removing the session or a connection counts the closed panels it unbinds; Claude's SQL without a named connection uses the closed panel's connection, and an edit into a closed panel is refused naming it | |
| a new panel's default title | `panel N`, skipping titles that open and closed panels hold | |

The sidebar gains a fourth tab, Panels, after Sessions, with the same bar as Sessions and Connections
([record 37](0037-session-actions-act-on-the-selected-row.md)): **+** new; edit (rename), clone (text and connection,
not the session) and remove of the selected panel. Removing an open panel asks **Close and remove** or **Leave it**.

Miguel decided the model: comando's panels as a one-level file system, external files as documents; Claude decided how.

## 3. Consequences

The close rule of [record 26](0026-a-file-per-panel.md) is replaced; its naming rules hold. The folder can grow with
empty panels; Remove is the way to clean it. A version kept aside after an outside edit
(`<name> (outside comando …).md`) is a file of the folder, so it is listed as a closed panel at the next start.
