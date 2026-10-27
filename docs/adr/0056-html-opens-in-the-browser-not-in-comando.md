# 56. HTML opens in the browser, not in comando

| Date | Status | Decided by |
|---|---|---|
| 2026-10-17 | accepted | Miguel and Claude |

## 1. Context

An HTML file (a test report, Javadoc, a generated document) opened as its source. A preview was tried with Swing's
`JEditorPane`, which already draws comando's Markdown: it understands HTML 3.2 and a small part of CSS, and ignores CSS
variables, flexbox, grid, web fonts, style sheets on the network and scripts. A current web page came out as bare text.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| rendering | comando does not render HTML; an HTML file opens as its text, to edit | Swing's renderer does not show current pages as a browser does |
| browser | an HTML document's header has a Browser button that opens the file in the desktop's browser | the browser's rendering, one click away |
| engines | no browser engine is embedded | their weight, against a page the browser already shows (§4) |
| links | a link to an HTML file, double-clicked in rendered Markdown, opens it in a panel, as text | as links to Markdown |

Miguel decided that comando renders no HTML, after seeing Swing's rendering, and that a button opens the page in the
browser; Claude weighed the options and their cost.

## 3. Consequences

`EditorPanel` shows the Browser button on HTML documents (`.html`, `.htm`); `Browser.openFile` opens a file in the
browser. A link to an HTML file is followed by `Links.open`, the one way every surface follows links since
[record 94](0094-links-in-both-styles-everywhere.md).

## 4. Alternatives

| Option | Why rejected |
|---|---|
| a preview in Swing's `JEditorPane` | HTML 3.2 and a small part of CSS: a current page shows as bare text |
| JavaFX `WebView` (WebKit) | about 40 MB and a second UI thread, for a page the browser already shows |
| JCEF (Chromium) | 100 MB or more and a helper process, for the same page |
