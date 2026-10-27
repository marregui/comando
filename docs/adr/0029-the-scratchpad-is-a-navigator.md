# 29. The scratchpad is a navigator

| Date | Status | Decided by |
|---|---|---|
| 2026-10-14 | accepted; its Markdown row replaced by [record 30](0030-documents-open-rendered-and-coloured.md), its deletion sentence by [record 34](0034-a-deleted-document-closes-its-panel.md), its fixed folder and always-on read by [record 49](0049-the-files-tab-shows-folders-the-user-chooses.md); its JFR icon removed by [record 54](0054-comando-knows-nothing-of-jfr.md) | Miguel and Claude |

## 1. Context

[Record 28](0028-the-sidebar-has-three-tabs.md) put the scratchpad in the sidebar as a tree with a root row, three
levels deep, read every 10 s while its tab showed, and opening a file read-only in the side pane. Under
[record 26](0026-a-file-per-panel.md), specs opened only from `panels/`. The scratchpad was to be a file navigator:
entries at the top level, folders before files, date folders in order, icons by kind, a click that opens the file to
read and edit with a rendered view, and a tree in sync with the folder at all times. This record replaces the reading
rules of record 28 and that consequence of record 26.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| layout | no root row; the scratchpad's entries are the top level | the tab says where it is |
| order, at every level | folders not named after a date (by name), date folders oldest first, then files by name ignoring case | Miguel's order; "chronological" read as oldest first |
| icons | folder, `work` folder, and files by kind (plan, READ-, ALPHA.md, Markdown, code, data, shell, SQL, table, image, JFR, diff, text) | the kind shows before the name is read |
| a click on a file | opens a new editor panel bound to that path, or brings forward the panel that has it; one click, as in a file browser | a panel is where text is read, edited, saved and run; binding the existing file keeps one copy |
| a file elsewhere in a panel | saved, reloaded and kept aside like a panel's own file; never renamed or deleted by comando; restored after a restart by its absolute path | the file is the user's, not comando's |
| binary or over 2 MB | read-only in the side pane | an editor of a binary file corrupts it |
| Markdown | Edit / Preview switch in the panel header and ⌘⇧M, as IntelliJ's editor and preview | write the source, read the result |
| sync | the tools adapter reads the whole folder every 2 s on its own thread, whatever shows, and sends the tree only when it changed | the tree follows the folder whether or not it shows; a 70-entry read takes about 1.5 ms |

Miguel decided the layout, the order, the icons, files opening in panels, the preview and the sync at all times; Claude
decided how.

## 3. Consequences

Panels ▸ Open spec… becomes Open file… and opens any file. A file deleted from the scratchpad while open in a panel is
reported and written again on the panel's next save ([record 27](0027-panels-reload-files-changed-outside.md)). Opening
a file never changes it; only an edit does.
