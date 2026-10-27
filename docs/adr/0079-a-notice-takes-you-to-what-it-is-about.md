# 79. A notice takes you to what it is about

| Date | Status | Decided by |
|---|---|---|
| 2026-10-20 | accepted | Miguel and Claude |

## 1. Context

A notice said what had happened and, at best, where to look by hand: the stall notices ended "the log has where
(Tools ▸ Help ▸ Show the log)", which opened the log at its end, among thousands of lines. A click on the notice in
the status bar opened the list of recent notices, and a click in the list copied one. This record changes that click.
The inventory of every place comando raises a notice (111 sites, 18 kinds of target) is kept outside the repository
with the session's work.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| where a notice goes | its subject names it: `panel:`, `connection:`, `session:`, `folder:`, `approval:` as before, and `log:<millis>` for an entry of comando's log; the subject's kind and key are `Notice.kind()` and `Notice.key()` | the subject already names what the notice is about; one field, no second one to keep in step |
| a click on the standing notice | goes there (`StatusBar.Targets`, given by the shell); a notice with nowhere to go opens the recent notices, as before | Miguel |
| the recent notices | a right click on the notice (a control-click on the Mac); a click on one goes where it is about, Copy is on its right click | the same gesture as every other list's menu |
| the hint | the line, row or block brought into view flashes the warning amber, fading to nothing over 2 s (`Flash`); one flash at a time on a component | one hint everywhere; it leaves nothing behind |
| how a log entry is found | the time read just before the entry is written goes in the subject; the entry is the first line that starts with a time from then to a second after (`LogLines.offsetOf`), read back from the end, stopping a second before; the newest of the 10 logs that holds it opens (`HelpFiles.logHolding`) | an entry carries no id; the time is in every line and in the notice. A second covers a pause between reading the time and writing the line; other threads take their time before waiting for the file, so a neighbour can be a few milliseconds out of order |
| a log panel shown for an entry | the caret goes to the entry's line, which scrolls to the upper third of the view and flashes; following the end stops until the user goes back to it. A text that does not hold the entry yet waits for the file to be read again, for 5 s | the entry is written just before the click can come; the panel's text may lag the file by one reload |
| where the stall notices go | the EDT's: the stack logged at the hang limit, or the "busy" line naming where for a stall below it; the reducer's: its stack at the hang, or the end's line when the intent ended before the watchdog looked (`StateLoop.Stall.since` ties the end to its hang) | the stack is where the problem is |
| failures told only by a notice | `Intent.Notify.logged`: the failure goes to the log first (an error for an error notice, a warning otherwise, with the cause's stack) and the notice names that entry; 13 sites (the kit, QuestDB's start, a schema read, git's unstage and writes, opening a file, saving the panels, an export, the tutorial, the kit's state and install, revealing a folder, saving a change) | a notice summarises; the log keeps the cause, one click away |
| which targets come first | the log, and focusing a panel; then the other kinds of the inventory (a session's block, a connection's or session's row, an approval card, a folder's row, a Changes view, a reopened panel, the kit, QuestDB) | Miguel named the stall notice; the rest follow the same rule |

Miguel decided that a click on a notice brings its source into view, scrolled to the place, with a hint of what to
read; that every notice follows it; that the stall notice opens the log at the stall. Claude decided the subject as the
target, the log anchor by time, the amber fade, the right click for the recent notices, and the order of the work.

## 3. Consequences

The flash is a text highlight painted the line's whole width, not an `RTextArea` line highlight: the caret's line
paints its own highlight over line highlights, and the caret sits on the entry's line, so a line highlight was never
seen (found on the probe). A failure some code already logged with its stack (the panel store's) is logged once more as
the notice's one line; the click finds the first of the two, the one with the stack, written in the same millisecond
on the probe. Notices of the other kinds keep opening the recent notices until their targets land. At HEAD, 11 sites
call `Intent.Notify.logged`.
