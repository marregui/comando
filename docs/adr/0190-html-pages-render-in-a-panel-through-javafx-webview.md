# 190. HTML pages render in a panel through JavaFX WebView

| Date | Status | Decided by |
|---|---|---|
| 2026-11-22 | accepted; built in task 229; the pages that run their scripts widened on 2026-11-23 (task 244) to every page comando's own work makes; takes up the WebView fallback named in [record 2](0002-swing-and-flatlaf-as-the-ui-toolkit.md) §2; a library [record 157](0157-comando-owns-its-json-mqtt-diff-completion-and-icons.md) admits; OpenJFX pinned to 25.0.4 on 2026-11-27, as 25.0.5 and 25.0.5.1 freeze comando on a page whose `localStorage` an earlier run left (§3) | Miguel and Claude |

## 1. Context

Miguel, 2026-11-22: "when I double click on an html document, instead of travelling to chrome, it opens locally on a
panel. ideally I can attach to that panel a session and have it work on it."

Before this record an HTML file opened as text in a panel, and a page a link or a session named opened in the
browser. Swing's own HTML renderer (`HTMLEditorKit`, behind `HtmlPane`) reads HTML 3.2 and about CSS 1: no flexbox or
grid, no inline SVG, no `<details>`, no JavaScript, so it cannot show the pages the kit produces (learn maps, reports).
[Record 2](0002-swing-and-flatlaf-as-the-ui-toolkit.md) §2 named "A JavaFX `WebView` is the fallback for documents
only". The candidates compared on 2026-11-22:

| Renderer | Engine | Cost |
|---|---|---|
| JCEF (JetBrains' Chromium Embedded Framework) | Chromium | about 150 to 250 MB of native code per platform, renderer and GPU processes of its own |
| JxBrowser | Chromium | the same, under a commercial licence |
| JavaFX WebView (OpenJFX 25) | WebKit, OpenJFX's fork | the JavaFX jars per platform, in the comando process (sizes in §3) |
| WKWebView through the FFM API | the system's WebKit | macOS only; an `NSView` inside AWT by hand |
| Flying Saucer | Java, XHTML and CSS 2.1 | no JavaScript, no flexbox, no SVG |

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| Renderer | OpenJFX 25 `javafx-web` in a `JFXPanel`, behind `ui.kit.web.PageView` | WebKit quality in the process, a few jars, the fallback record 2 already named |
| Where | an editor panel's rendered view, as Markdown's: HTML opens rendered, the mode switch shows its text | the panel and its session already exist; no new kind of panel |
| Links | a web address opens in the browser; a local file opens as the sidebar opens it; an anchor scrolls the page | the page never navigates away from its file |
| JavaScript | on for a page comando's own work makes or keeps: inside a folder the Files tab lists (the kit's, the brain's, the user's own), a session's working directory, or Claude Code's temporary folder of this user (`/tmp/claude-<uid>`); off elsewhere; no Java object exposed to it | a script on a `file:` page reads any local file: measured on OpenJFX 25.0.5.1, a page's `XMLHttpRequest` read a file beside it and `/etc/hosts`; with scripts off it read nothing, and comando still reads the page's text. The kit's pages need their scripts; a page from anywhere else does not get them. A bridge would let a page call comando |
| Agents | `page_list`, `page_snapshot` (a PNG of what the user sees, by path) and `page_read` (title, selection, visible text) through `core.port.PagePort` | the session attached to the panel works on the page as the user sees it |
| Threads | FX calls on the FX thread, Swing calls on the EDT; agents wait off both, with a timeout; `Platform.setImplicitExit(false)` | neither UI thread blocks the other; closing the last page does not end JavaFX |
| Colours | a page with no style sheet of its own takes the theme's: the look of rendered Markdown in the theme and tint chosen (`PageTheme`), given again at a theme change; a page with any style sheet keeps its own look whole | Miguel, 2026-11-22, on a report that looked like comando's but ignored the theme. Filling only what a styled page leaves unset was rejected: a report that sets its ground but not its cells would get the theme's cells on its own ground |
| No renderer | headless, or JavaFX fails to start: the HTML file stays in its text view | tests and CI run headless |

## 3. Consequences

- New dependency: the OpenJFX jars for the build's platform, 25.0.5.1 when built, 25.0.4 since 2026-11-27 (below).
  Measured in task 229 on an M2 Max
  (`:app:appImage`, `mac-aarch64`): the six jars are 48.3 MiB, `javafx-web` 40 MB of it; the application image is
  188 MB. The runtime gains `java.net.http`, `jdk.jsobject`, `jdk.unsupported.desktop` and `jdk.xml.dom`.
- OpenJFX is pinned to 25.0.4 (2026-11-27). On 25.0.5 and 25.0.5.1 a page that reads `localStorage` which an
  earlier run left never loads: WebKit's main thread, which on macOS is both JavaFX's and AppKit's, waits for WebKit's
  storage thread, which waits in a one-time initialisation; the EDT then waits for the main thread when it adds a
  native drop target, and comando freezes at the page's panel, at every start that restores it. The learn-map pages
  keep their side panel's width there. Measured on an M2 Max with a WebView in a `JFXPanel` over one data folder, two
  runs each: 21.0.9, 24.0.2, 25.0.1, 25.0.4 and 26.0.2 load both times, 25.0.5 and 25.0.5.1 only the first.
  `StoredPageUiTest` runs the two runs in child JVMs and fails on 25.0.5.1; Dependabot skips those two versions.
- The first page opened starts JavaFX on the EDT (`Platform.startup`), once per process; its length is not measured
  yet. Every JVM that opens a page logs JavaFX's warning that its classes came from the unnamed module: comando runs
  on the class path.
- JavaFX starts its own threads (the JavaFX application thread, its renderer, WebKit's), owned by the toolkit, not by
  comando's thread kit; they live until the process exits. `ThreadRulesTest` reads comando's classes and passes
  unchanged; `ThreadBudgetTest` passes with no page opened.
- A page with its scripts on can read any file the user can, under WebKit's rules for `file:` pages; only pages in those places get them, decided again whenever the panel's state changes, since a panel is given its file before its first state (Miguel, 2026-11-23: anything comando makes, comando opens). A page elsewhere that needs its scripts opens in the browser (its header button).
- Every load a page schedules is checked, not the address: the same link followed twice changes no address the second time.
- `page_snapshot` writes into one folder per process (`comando-pages-*` in the system's temporary folder), which keeps the newest 20 pictures and is deleted when comando exits.
- Markdown keeps `HtmlPane`; moving it to WebView is a later decision.

## 4. Alternatives

JCEF gives Chrome's rendering at several times the size and with processes of its own; JavaFX WebView was enough for
the pages the kit writes. Swing's `HTMLEditorKit` stays for Markdown, where its subset suffices.
