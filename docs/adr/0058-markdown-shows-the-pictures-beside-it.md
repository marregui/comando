# 58. Markdown shows the pictures beside it

| Date | Status | Decided by |
|---|---|---|
| 2026-10-17 | accepted | Miguel (pictures a document names on the disk, SVG included), Claude (the reading, the drawing and the limits) |

## 1. Context

Claude writes reports into the knowledge base, a scratchpad or any folder, with the charts and diagrams beside them,
in `images/`, `img/` or another folder, named by relative image links. The preview showed every image link as its alt
text ([record 35](0035-mermaid-diagrams-drawn-by-mmdc.md)), so a report's pictures were missing. Swing's HTML renderer
draws PNG, JPEG and GIF; it does not draw SVG.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| which pictures | a PNG, JPEG, GIF or SVG that is a file on this machine: a path from the document's folder (`images/chart.png`, `../img/flow.svg`, `%20` for a space, or a `<…>` destination with spaces), a full path, or a `file:` URL with no host | the pictures Claude writes beside a document |
| what is not shown | a web address, a scheme-relative `//host/…`, `file://host/…`, a `data:` URI, another kind of file: the alt text, as before. A missing or unreadable file, or one over 32 MB: the alt text and the reason | nothing is fetched from the network |
| PNG, JPEG, GIF | shown as they are; only the size is read from the header, then the `<img>` names the file | Swing draws them |
| SVG | drawn by jsvg 2.1.0 into `~/.comando/cache/images/<hash>.png`, twice its size for Retina, shown at its own size; no larger than 4096 points a side; 300 points a side when it states no size or view box | Swing draws no SVG; jsvg is a pure-Java renderer (784 KB) already on the classpath, through `flatlaf-extras` for comando's SVG icons; declared now as a direct dependency |
| what an SVG loads | only files beside it (jsvg's `ALLOW_RELATIVE`): an `<image>`, `<use>`, a style sheet or a paint naming the network is not fetched | an SVG may come from anywhere |
| when | the first time a preview shows the link: read on one `local-images` thread; the preview shows "loading chart.png…" and is redrawn, where the reader left it, when the picture is ready | the preview never waits on the disk |
| a changed file | a picture is known by its path, modification time and size: a file changed on disk is read again at the next render | |
| width | no wider than the pane; the height kept in proportion | |
| security | the alt text and the file's URI are escaped as attribute values, quotes included | an alt text cannot add an attribute |

## 3. Consequences

`LocalImages` reads and draws the pictures; `MarkdownHtml.Pictures` lets the caller turn an image link into HTML;
`HtmlPane.setMarkdown(markdown, folder)` takes the document's folder, which `EditorPanel` passes. `App` installs the
cache at start and stops the thread at exit. The cache is not pruned: one PNG per version of an SVG; it is safe to
delete. Supersedes the image row of [record 35](0035-mermaid-diagrams-drawn-by-mmdc.md).
