# 135. The comparison follows the theme and lies level, and cards say what they ask

| Date | Status | Decided by |
|---|---|---|
| 2026-11-06 | accepted; built | Miguel and Claude |

## 1. Context

Claude proposed eight changes from five screenshots of daily use; Miguel took all eight and added that the comparison
window must follow the theme. It stayed black on a light theme. Its body is built at startup, before the saved theme is
chosen, and the look reaches windows only, so a body waiting for its first open kept the default dark look while the
shading took the theme's light colours: the changed row was pale on pale. Amends [record 134](0134-the-window-says-who-works-and-gives-room-to-what-is-read.md):
its `Write` rule missed the approval card.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| a body waiting for its window | `Theme.followWithoutWindow`: what is built before its window opens follows the theme itself; the comparison, the connections and sessions dialogs, the import dialog | the look reaches windows only |
| a rendered page | a page styled under another theme restyles as it joins a window; the comparison's shading is drawn again when a theme is chosen | a page's rules are text, fixed when written |
| the rendered comparison | the two sides lie level: unchanged blocks are paired from the line comparison, scrolling maps between pairs, and a block one side lacks leaves a gap of its height | sharing the length put the same row 33 px apart |
| a `Write` to approve | the file's text and its line count, no diff | as its transcript card: a `Write` gives no text it replaces |
| a Bash card | in the transcript, titled by its `description` when it has one, the command on expanding it; an approval's head stays the command; a cut title ends in … | the description says why; what is approved is what runs, and the agent's words about it could be wrong |
| an approval's words | "wants to write ~/path", and the scope in plain words: any edit under a folder, commands starting a prefix | the rule's syntax is Claude Code's, not the reader's |
| the sidebar's lists | the band is the focused panel's session, connection or panel, none without one | the band said where a click had been |
| a rendered panel's tools | connection, Run and Clear hide while a panel shows rendered, Stop while nothing runs | a page has nothing to run |
| the status bar | Claude's usage is named as Codex's is, and its tooltip says when `/usage` gives no session window; only "waiting for approval" is gold | the gold marks what needs the user |

## 3. Consequences

`:app:uiTest` selects its classes by file, not by a filter: with `forkEvery` a filter still started a JVM for every test
class, and the run fell from 7 min 22 s to 3 min 44 s on the same 20 classes. A theme chosen with the comparison open
renders both sides again. Levelling takes one event per pass, so a long page does not hold the EDT past its budget, and a
file read again keeps the reader's place in the rendered sides as it does in the texts. A gap above a table row makes the row taller rather than leaving a band. For about 200 ms after
a switch to the rendered sides they can differ by some 28 px, until the scroll bar's width settles and levelling runs.
Tested in `DiffThemeUiTest`, `DiffWindowThemeTest`, `ScrollerGroundTest`, `ContentPaletteTest`, `HtmlPaneTest`, `ChangeEditorTest`,
`MarkdownHtmlTest`, `SplitDiffTest`, `ApprovalInboxWordsTest`, `ToolCallItemTest`, `SessionsSidebarTest`,
`EditorPanelHeaderTest`, `StatusBarUsageTest`, `StatusBarWorkTest` and `PlanUsageReaderTest`.

## 4. Alternatives

The comparison's dialog made at startup, so the look would reach it: rejected, as the dialogs' tests run headless, where
no dialog can be made. A sideways bar under a preview was looked into: the page in the
screenshot holds a 192-character line in a code block, so the bar is true and stays.
