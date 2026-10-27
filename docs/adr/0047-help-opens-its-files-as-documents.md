# 47. Help opens its files as documents, with icons of its own

| Date | Status | Decided by |
|---|---|---|
| 2026-10-16 | accepted | Miguel and Claude |

## 1. Context

Help ▸ Tutorial showed the tutorial from a resource in the build, and Help ▸ Show the log showed the log's last 2000
lines, both in the side pane, as views that last one run and have no file. [Record 36](0036-five-menus-by-subject.md)
let a menu item carry an icon only when the same action is a button with that icon, so the Help items had none. This
record amends that rule for the Help items.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| tutorial | written to `help/tutorial.md` in comando's folder at start and at each Help ▸ Tutorial, from the build's copy, whenever the file differs from it; opened in a panel like any document (rendered first, Preview / Edit) | a file the user can open, find and keep a panel on; the build's copy wins, so the manual never falls behind the app |
| log | Help ▸ Show the log opens `logs/comando-0.log` in a panel like any document | the same reading as a scratchpad file, with find and the whole file |
| a `.log` document | read-only wherever it is opened from; opens at its end; reloaded as it grows, following the end while the caret is there | the file belongs to the program writing it: a panel's save would interleave with the logger's appends |
| log rotation | 10 files of 1 MiB, instead of 5 of 5 MB | a panel takes files up to 2 MiB; a larger one would fall back to the side pane |
| Keyboard shortcuts | the side pane, as before | generated from the commands; nothing to keep as a file |
| Reveal | renamed "Reveal comando folder" | names the folder the user sees |
| icons | the Help items carry icons of their own, which nothing else uses: a mortarboard (Tutorial), a keyboard (Keyboard shortcuts), a page with green, amber and red lines (Show the log), a folder holding comando's chevron and cursor (Reveal comando folder) | each still means one thing |
| order | Tutorial, Keyboard shortcuts, then Show the log, Reveal comando folder | unchanged |

Miguel decided icons on the Help items, the tutorial and the log opening as scratchpad documents do (each backed by a
file that can also be opened as a file), Keyboard shortcuts staying in the side pane, the name "Reveal comando folder"
and the order; Claude decided how.

## 3. Consequences

`HelpFiles` (ui shell) writes the tutorial atomically and names the log; `EditorPanel` makes a `.log` document
read-only and follows its end; `Logging` rotates at 1 MiB. Edits to `help/tutorial.md` are replaced at the next start;
notes belong in a panel of one's own.
