# 113. A document's outline at the panel's right, from a button in its tools

| Date | Status | Decided by |
|---|---|---|
| 2026-10-28 | accepted | Miguel and Claude |

## 1. Context

Miguel: "a left panel that would offer a toc or a navigable view of the sections of a markdown document, so that I
could double click there and have the panel scroll to that section. this should have its own icon in the editor's
tools." An earlier spec from the Obsidian review (2026-10-18, never dispatched) put the outline in a sidebar tab
following the focused panel; this record puts it in the panel, as asked.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| where | at the right of the panel's text or rendered page, in a split the user can drag, 220 px wide at first; a wider panel widens the text | the document and its outline side by side, per panel, the outline under the button that shows it (Miguel, 2026-10-28: first built at the left, moved right) |
| the button | in the panel's tools, its own icon (lines indented under square marks), beside Preview/Edit; shown wherever the text renders as a page (a Markdown file, a panel's own text) | the outline is of what renders as a page |
| what it lists | the headings, read with the renderer's own parse (`MarkdownHtml.sections`): their text as the page shows it, the ids the page gives them (GitHub's, `-1` on a repeat), their lines; front matter and `#` in code blocks are no heading. One a line, indented 12 px a level, the first level in bold; "No headings" when there are none | a double click must land where the page puts the heading |
| when it reads | when it shows, and 250 ms after typing pauses; off the EDT, a read for older text dropped | typing never waits on a parse |
| a double click | goes to the heading: the rendered page scrolls it to the top; the text puts its line at the top with the caret at its start; the keyboard goes there. A single click selects | as every double click in comando (record 94) |
| remembered | panels open with the outline when the last choice made in any panel was to show it (`editor.outline` in `settings.json`, record 106) | one choice, kept |

Miguel decided the outline at the panel's right, its button and the double click. Claude decided its content and look,
when it reads, and what is remembered.

## 3. Consequences

`OutlinePane`, `MarkdownHtml.Section` and `sections`, the `outline` icon, `WindowLayout.outlineShown` and
`Workspace.outline` are added. The outline does not follow the scroll (no mark on the section in view). Tested in
`EditorPanelOutlineTest` (the sections and ids against the rendered page, front matter and code fences, read again
after typing, the choice kept, a double click putting the line at the top, no outline for SQL) and off screen in
`OutlineUiTest` (`:app:uiTest`: the button, the outline right of the page, then a double click scrolling the rendered page to a far heading).
