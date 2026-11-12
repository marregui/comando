# 154. A link to a page opens the browser

| Date | Status | Decided by |
|---|---|---|
| 2026-11-12 | accepted; built | Miguel |

## 1. Context

[Record 56](0056-html-opens-in-the-browser-not-in-comando.md) opened a link to an HTML file in a panel, as text, like a
link to Markdown. Sessions hand over reports as HTML pages and name them in their replies; a double click on such a
name opened the page's source in a panel, and the Browser button in its header was a second step to read it. The
sidebar's tabs open the same files to edit them.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| a link to a page | a double-clicked link to a local `.html` or `.htm` file, by its path or as a `[[name]]` note, from any surface (the transcript, a rendered page, a panel's text, a tool's result, a tab's card), opens the page in the desktop's browser; no panel opens | a page named in a reply is there to be read, and the browser is where it reads |
| a line | a link that names a line still shows the file in the side pane, at that line | a line is asked of the source |
| the sidebar | a page opened from the Kit, Brain or Scratchpad tab opens in a panel, as text, to edit; its Browser button stays; a page too large or binary for a panel opens in the browser | the sidebar is where files are edited; one that cannot be edited is shown where it reads |

## 3. Consequences

`Links.open` sends a page to the browser before the opener sees it, and `PanelFiles.showFile`, where a bare file
reference in the transcript lands once found, does the same for a page without a line. The links row of record 56 is
replaced by this record; its other rows stand.
