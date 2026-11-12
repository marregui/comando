# 152. The Files tab splits into the Kit, Brain and Scratchpad tabs

| Date | Status | Decided by |
|---|---|---|
| 2026-11-12 | accepted | Miguel and Claude |

## 1. Context

The sidebar had one Files tab. It held every folder `folders.json` configures, the kit, the brain and the scratchpad
among them, as rows of one tree ([record 49](0049-the-files-tab-shows-folders-the-user-chooses.md),
[118](0118-brain-and-kit-are-rows-each-one-unit.md)). [Record 118](0118-brain-and-kit-are-rows-each-one-unit.md) also
showed, inside a group's row, the folders the manifest names for the group and then the parent folder's other folders:
BRAIN held KB and Memory, and EVAL beside them, though no entry configured EVAL, while
[record 55](0055-the-files-tab-only-shows-folders-json-configures-it.md) had made the tab show only what `folders.json`
configures. Miguel, 2026-11-12: the one tab becomes three, Kit, Brain and Scratchpad, each the same component; and a
group's row holds only what is configured for it.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the tabs | the sidebar's tabs, in order: Sessions, Connections, Kit, Brain, Scratchpad, Logbook, Instances. The Files tab is gone. Each of the three is the same component: the tree, git's marks and trails, the filter under the tree, the row and file menus, the Trash where a folder is deletable, a double click on a top row for its changes. Each has an icon of its own, drawn like the Files icon | one behaviour in three places, so nothing learnt in one tab is unlearnt in another |
| which tab holds a folder | `folders.json` keeps its format. The Scratchpad tab holds the folders whose icon is `scratchpad`; the Brain tab, the other folders of the group `Brain`; the Kit tab, every other folder (the Kit group, and a folder in no group). In the Scratchpad tab the scratchpad's row reads SCRATCHPAD, as KIT and BRAIN read in theirs | the placement follows from what the file already says; no new key, no migration of the file |
| a group's row | holds only the folders `folders.json` configures for the group: BRAIN shows KB and Memory; EVAL, and any other folder of BRAIN that is not configured, never shows. [Record 55](0055-the-files-tab-only-shows-folders-json-configures-it.md)'s rule, now for groups too; it replaces the part of [record 118](0118-brain-and-kit-are-rows-each-one-unit.md)'s row "a group" that shows the parent's other folders after the group's | a tab shows what the user configured and nothing else |
| the Jobs row and the menus | the Jobs row stays first inside the KIT row, in the Kit tab. The tab menu of each of the three offers the folders window; the Kit tab's also the kit jobs window. The filter under a tree searches its own tab's folders | the jobs belong to the kit; each tab is complete by itself |
| a file shown from elsewhere | a file link, Locate and Search's Show in sidebar bring to the front the tab whose folder holds the file | the file is shown where it lives, whichever tab was in front |
| the tab in front | the sidebar remembers it by name: `kit`, `brain`, `scratchpad`; `files`, kept from before, reads as `kit`. `ui_set_layout`'s `sidebar` takes the same names | a settings file written by an earlier version still opens on a tab |
| the status bar | the week window says when it resets, with the weekday and the time, for Claude and for Codex: "Claude Session 5% (resets 7:19pm) · Week 43% (resets Thu 1:59pm) · Fable 17%" and "Codex Session 4% (resets 12:06) · Week 3% (resets Thu 13:00)" | the figure says how much is left; the reset says until when |

Miguel decided the three tabs, where each folder goes, what a group's row holds, the names the sidebar keeps and the
status bar's reset times; Claude decided how the docs say it.

## 3. Consequences

The sidebar's tab row grows from five tabs to seven, and its remembered tab is read by name with `files` as `kit`. A
folder of the brain that `folders.json` does not configure is no longer reachable from the sidebar; it is still on
disk, and an entry for it in `folders.json` shows it. [Workbench §6](../workbench.md#6-the-kit-brain-and-scratchpad-tabs) and [Kit
and brain §3.2](../kit-and-brain.md#32-the-kit-brain-and-scratchpad-tabs) describe the tabs, the tutorial's sidebar
table repeats them, and [Claude Code §3](../claude-code.md#3-mcp-tools) lists `ui_set_layout`'s `sidebar` names.
