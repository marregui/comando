# 73. The ~/.claude folders window explains its fields and creates the index

| Date | Status | Decided by |
|---|---|---|
| 2026-10-19 | accepted | Miguel and Claude |

## 1. Context

The window that edits the Files tab's folders ([record 69](0069-a-window-edits-the-files-tab-folders.md)) and its
repositories ([record 71](0071-repositories-in-the-files-tab.md)) was titled Files tab folders. Its fields had no
explanation under the pointer, and the names Leave out and Listed in did not say what the fields do. Each field had a
line of its own for its problem, empty most of the time, which spread the form. The line under the list read "inside
the git repository ~/.claude · master · 1 change". A folder chosen outside `~/.claude` was added and then refused; an
index file that did not exist stayed missing, and nothing kept it. The Files tab's menu
([record 70](0070-the-sidebars-tabs-in-one-row-with-a-menu-each.md)) also held Close every folder, Uncommitted changes
and Hide the sidebar.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| title | `~/.claude folders`, Claude Code's folder as the user's home shows it; the menu command Configure ~/.claude folders | Miguel |
| explanations | each field's name and field carry a tooltip of one to four short lines: what the field is, and for the index file and the listing file what a named but missing file means and what empty means | the two fields were not understood from their names |
| problems | no line per field: a failing field is outlined in red, and one line under the form names the first problem ("Index file: its folder does not exist"), else a note in grey ("INDEX.md does not exist yet: Save creates it") | less space between the lines |
| the repository line | `repo: <its top folder> · <branch> · <n> uncommitted`; `detached HEAD` for no branch; `repo: none` outside git | the words "inside the git repository" carried nothing |
| picks | the folder chooser, + and … refuse a folder outside `~/.claude` (folders) and the file choosers a file outside the entry's folder; the message line says so and nothing changes | folders come only from `~/.claude`, and the window enforces it |
| index file | typed or picked (… gives the path relative to the folder); named, inside the folder, its own folder existing, but missing: Save creates it with a title and one line on how it is kept | the index is created, then maintained |
| keeping it | `/reflect` keeps one line per entry of the folder's top level in that file, and `index-check` reports an entry missing from it or a missing file; for folders inside `~/.claude` only, leaving out the indexes it already checks (`MEMORY.md`, `KB/ALPHA.md`, the scratchpad's) | the same rule as every other index |
| listing file | typed or picked (… gives the file's name); not created by comando, since there is one per subfolder; `/reflect` creates a subfolder's with its first line, and `index-check` reports a subfolder whose entries it does not list | the same semantics as the index |
| repositories | their index file is not created: a note says it is not in the repository | outside `~/.claude`, comando writes nothing |
| the Files tab's menu | Configure ~/.claude folders and Configure repositories alone (no ellipsis), in the Files menu too; no Close every folder, no Uncommitted changes (⌘K and the Window menu keep it), no Hide the sidebar (⌘B and the ⋯ button keep it); replaces the Files menu row of record 70 | closing every folder is not a use case; the other two have a key elsewhere |

Miguel decided the title; a tooltip on each field's name, the index file's saying what a missing file means; the
repository line; a tighter form; picks only inside `~/.claude`; that an index named but missing is created and kept,
and the listing file likewise. Claude decided how: one message line and red outlines, the pickers, the store creating
the file, `/reflect` and `index-check` keeping it.

## 3. Consequences

The kit's `reflect` skill and `index-check` gain the rule, and the kit becomes 1.2.0. The kit was then the repository's
`kit/` folder ([record 50](0050-comando-ships-a-claude-code-kit.md)); since
[record 80](0080-the-kit-is-a-claude-code-setup-comando-runs-its-sessions-on.md) it is a repository of its own.
`index-check` reads `~/.comando/folders.json` (`$COMANDO_FOLDERS`); a missing file checks nothing. The field tooltips
run past the one-line rule of the main window's tooltips: a form's field names explain the field, in at most four
lines.

The same kit release carries the author's standard for the knowledge base (2026-10-19): the skeleton's `KB/` is a set
(`00_START_HERE.md` and `99_MAINTENANCE.md`, the latter with Rules, Source register, Drift check and its checks table,
Change log), `index-check` checks every set, and a scratchpad day's index is `INDEX.md`, formerly `ALPHA.md`. The kit's
folders follow: the knowledge base opens `00_START_HERE.md`, and the Scratchpad's listing file is `INDEX.md`.
