# 56. HTML opens in the browser, not in comando

| Date | Status | Decided by |
|---|---|---|
| 2026-10-17 | accepted | Miguel (no rendering of HTML in comando, after seeing Swing's; a button that opens the page in the browser), Claude (the options weighed and their cost) |

## 1. Context

An HTML file (a test report, Javadoc, a generated document) opened as its source. A preview was tried with Swing's
`JEditorPane`, which already draws comando's Markdown: it understands HTML 3.2 and a small part of CSS, and ignores CSS
variables, flexbox, grid, web fonts, style sheets on the network and scripts. A page of today came out as bare text.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| rendering | comando does not render HTML; an HTML file opens as its text, to edit | Swing's renderer does not show today's pages as a browser does |
| browser | an HTML document's header has a Browser button that opens the file in the desktop's browser | full fidelity, one click |
| engines | no browser engine is embedded: JavaFX WebView (WebKit, about 40 MB and a second UI thread) and JCEF (Chromium, 100 MB or more and a helper process) were weighed and declined | their weight against a page the browser shows already |
| links | a link to an HTML file, double-clicked in rendered Markdown, opens it in a panel, as text | as links to Markdown |

## 3. Consequences

`EditorPanel` shows the Browser button on HTML documents (`.html`, `.htm`) and `linkedDocument` follows links to them;
`Browser.openFile` opens a file in the browser.
