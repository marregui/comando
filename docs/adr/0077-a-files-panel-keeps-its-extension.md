# 77. A file's panel keeps its extension

| Date | Status | Decided by |
|---|---|---|
| 2026-10-20 | accepted | Miguel and Claude |

## 1. Context

A panel's title names it to the tools and keys its plots, so no two panels, open or closed, share one, compared
ignoring case; a clash took a number. A file opened in a panel was titled with its name without `.md`. The author had a
session called reviewer, whose panel was called reviewer, and opened `agents/reviewer.md`: its panel was titled
"reviewer 2", which read as a second panel of the session. Reproduced on a scratch data root with the intent log on:
one `AddPanel {title: "reviewer", document: true}`, the reducer made it "reviewer 2", bound to the file, no session.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| a file outside `panels/` (a document) | titled with its file name and extension: `reviewer.md` | the tab then matches the Files tab and never takes a session's or a panel's name; the icon already says what kind of panel it is |
| that title already some panel's | the file's folder follows it: `README.md · docs`; the folder after the name, as a tab clips the end of a long title | the folder tells two files apart, a number does not |
| that one taken too | the reducer's number, `README.md · docs 2` | the reducer stays the one place that keeps titles unique |
| a file of `panels/` | titled without `.md`, as before | its file is named after its title: a title with `.md` would rename `main.md` to `main.md.md` |
| when the title is chosen | after the store bound the file, when it is known whether the file is a document | the same path can be either only by its folder, which the store decides |
| the rule "taken" | `AppState.titleTaken`: open or closed panels, ignoring case; the reducer and the window both ask it | one rule |

Miguel decided that a file opened elsewhere than `panels/` is titled with its extension, and that the folder, not a
number, breaks a tie. Claude decided how: the title chosen after the bind, one "title taken" rule in `AppState`.

## 3. Consequences

Document panels already in `panels.json` keep the titles they were saved with until they are opened again. The tools
see `reviewer.md` where they saw `reviewer`. A panel of comando's own can still be renamed to a title with `.md`;
renaming is not changed. The window builds the title in `PanelTexts.documentTitle`.
