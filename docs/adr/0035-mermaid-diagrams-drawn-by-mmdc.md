# 35. Mermaid diagrams drawn by mmdc

| Date | Status | Decided by |
|---|---|---|
| 2026-10-14 | accepted | Miguel (fix the preview; the recommended way), Claude (how) |

## 1. Context

The Markdown preview is Swing's HTML 3.2 renderer, which runs no JavaScript, so a ```` ```mermaid ```` block showed
as code. Scratchpad plans (`NEVSKY-PLAN.md`) carry Mermaid diagrams.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| how a diagram is drawn | `mmdc` (Mermaid's command-line renderer, 11.12.0 here) draws a PNG, dark theme, transparent background, 2× for Retina; the preview shows it at half its pixels, no wider than the pane | Mermaid's own renderer; about 1.5 s for a diagram |
| when | the first time a preview shows the text; one drawing at a time, on a `mermaid` thread; the preview shows the source under "drawing the diagram…" and puts the picture in its place, where the reader left the view, when it is ready | the preview never waits |
| cache | `~/.comando/cache/mermaid/<hash>.png`, keyed by the text and how it is drawn; a text that changes is drawn again; a text `mmdc` rejects is remembered with its reason for the run | drawn once per version of a diagram |
| no `mmdc` | the source, with a note naming `npm install -g @mermaid-js/mermaid-cli` | |
| finding `mmdc` | the PATH, then the newest nvm Node, Homebrew, `/usr/local/bin`; its folder goes first on its PATH, since `mmdc` runs on `node` | an app started from Finder has a short PATH |
| images | only pictures comando drew, from its cache folder; images named in the Markdown still show as their alt text (since [record 58](0058-markdown-shows-the-pictures-beside-it.md), a picture that is a file on this machine shows) | nothing is fetched that the user did not make |
| exit | the drawing in progress is stopped with its Chromium | no process outlives comando |
| rejected | an embedded browser (JCEF, JavaFX WebView: over 100 MB and a second toolkit); mermaid.ink or Kroki (the text leaves the machine); a Java renderer (none for flowcharts and sequence diagrams) | |

## 3. Consequences

The first view of a new or changed diagram costs about 1.5 s of a headless Chromium. Every preview shows diagrams: a
scratchpad document, a panel's Markdown preview, a Markdown view from Claude, a plan in an approval card.

The refresh was amended on 2026-10-18: a page that waited for several diagrams was rendered again whole for each one
drawn, and each render asked again for the diagrams still being drawn, so the renders doubled with each diagram
(1 024 for a review with 10 diagrams; the panel stalled for 18 s). Now a diagram that is not drawn yet stands as an
element of its own, and the drawn picture replaces that element alone; the pane waits once for each diagram, however
often the page is rendered meanwhile. Pictures ([record 58](0058-markdown-shows-the-pictures-beside-it.md)) arrive
the same way.
