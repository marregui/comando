# 26. A file per panel

| Date | Status | Decided by |
|---|---|---|
| 2026-10-14 | the file per panel accepted; the rules in 2 proposed, for Miguel to accept or reverse | Miguel (a file per panel), Claude (naming, closing, edits outside comando) |

## 1. Context

Panel text lived inside `panels.json`, one JSON string per panel, which nothing but comando reads. Miguel wants each panel to be a full spec, a document that stands on its own.

## 2. Decision

Each panel's text is a Markdown file in `panels/` under the data root; `panels.json` keeps the panel's title, bindings and file name.

| Question | Rule | Why |
|---|---|---|
| file name | the panel's title, made a safe file name, with ` 2`, ` 3`… when taken | the folder reads as a list of specs |
| rename | the file follows the title | the name stays the spec's name |
| close | the file stays when it holds text; a blank one is deleted | a spec outlives its panel; empty panels leave nothing behind |
| reopen | Panels ▸ Open spec… (⌘O) opens a file of the folder as a panel; ⌘⇧T takes the file again | a kept file needs a way back |
| a new panel's title equal to a kept file's | the new panel takes the next free name | a closed spec is never overwritten |
| an edit made outside comando | before writing, a file whose modification time or size differs from comando's last write or read is kept aside as `<name> (outside comando <date time>).md`, with a notice | nothing typed anywhere is lost |

## 3. Consequences

The folder can be opened in an IDE, diffed or kept in Git. comando does not reload a file changed outside while it runs: the next save keeps the outside version aside, and the panel shows comando's text; a reload on change is open. Specs open only from `panels/`; a file elsewhere is refused rather than adopted. A `panels.json` from before this record moves its text into files at the first save. A panel whose file cannot be written, open or just closed, keeps its text in `panels.json` until it can; a closed one comes back as a panel at the next start if it never lands. One file is never bound to two panels (amended 2026-10-14 after the concurrency review of `bfb5f63`).
