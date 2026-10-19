# 73. The ~/.claude folders window explains its fields and creates the index

| Date | Status | Decided by |
|---|---|---|
| 2026-10-19 | proposed; amends the window of [record 69](0069-a-window-edits-the-files-tab-folders.md) and [record 71](0071-repositories-in-the-files-tab.md), and the Files tab's menu of [record 70](0070-the-sidebars-tabs-in-one-row-with-a-menu-each.md) | Miguel (the title; a tooltip on each field's name, the index file's saying what a missing file means; the repository line; a tighter form; picks only inside `~/.claude`; an index named but missing created and kept; the listing file likewise), Claude (how: one message line and red outlines, the pickers, the store creating the file, `/reflect` and `index-check` keeping it) |

## 1. Context

The folders window was titled Files tab folders. Its fields had no explanation under the mouse; Leave out and Listed in
were not understood from their names. Each field had a line of its own for its problem, empty most of the time, which
spread the form. The line under it read "inside the git repository ~/.claude · master · 1 change". A folder chosen
outside `~/.claude` was added and then refused; an index file that did not exist stayed missing, and nothing kept it.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| title | `~/.claude folders`, Claude Code's folder as the user's home shows it; the menu command the same with … | Miguel |
| explanations | each field's name and field carry a tooltip of one to four short lines: what the field is, and for the index file and the listing file what a named but missing file means and what empty means | Miguel: useful, short; the two fields were not understood |
| problems | no line per field: a failing field is outlined in red, and one line under the form names the first problem ("Index file: its folder does not exist"), else a note in grey ("INDEX.md does not exist yet: Save creates it") | Miguel: less space between the lines |
| the repository line | `repo: <its top folder> · <branch> · <n> uncommitted`; `detached HEAD` for no branch; `repo: none` outside git | Miguel: the words "inside the git repository" add nothing |
| picks | the folder chooser, + and … refuse a folder outside `~/.claude` (folders) and the file choosers a file outside the entry's folder; the message line says so and nothing changes | Miguel: only from `~/.claude`, enforced |
| index file | typed or picked (… gives the path relative to the folder); named, inside the folder, its own folder existing, but missing: Save creates it with a title and one line on how it is kept | Miguel: created and maintained |
| keeping it | `/reflect` keeps one line per entry of the folder's top level in that file, and `index-check` reports an entry missing from it or a missing file; for folders inside `~/.claude` only, leaving out the indexes it already checks (`MEMORY.md`, `KB/ALPHA.md`, the scratchpad's) | the same rule as every other index |
| listing file | typed or picked (… gives the file's name); not created by comando, since there is one per subfolder; `/reflect` creates a subfolder's with its first line, and `index-check` reports a subfolder whose entries it does not list | Miguel: the same semantics as the index |
| repositories | their index file is not created: a note says it is not in the repository | outside `~/.claude`, comando writes nothing there |
| the Files tab's menu | Configure ~/.claude folders and Configure repositories alone (no ellipsis), in the Files menu too: no Close every folder, no Uncommitted changes (⌘K and the Window menu keep it), no Hide the sidebar (⌘B and the ⋯ button keep it); replaces the Files menu row of record 70 | Miguel: closing every folder is not a use case; the rest does not belong there |

## 3. Consequences

The kit's `reflect` skill and `index-check` gain the rule, and the kit becomes 1.2.0. `index-check` reads
`~/.comando/folders.json` (`$COMANDO_FOLDERS`); a missing file checks nothing. The field tooltips run past the one-line
rule of the main window's tooltips: a form's field names explain the field, in at most four lines.

The same kit release carries Miguel's standard for the knowledge base (2026-10-19): the skeleton's `KB/` is a set
(`00_START_HERE.md` and `99_MAINTENANCE.md`, the latter with Rules, Source register, Drift check and its checks table,
Change log), `index-check` checks every set, and a scratchpad day's index is `INDEX.md`, formerly `ALPHA.md`. The kit's
folders follow: the knowledge base opens `00_START_HERE.md`, and the Scratchpad's listing file is `INDEX.md`.
