# 94. Links in both styles, followed everywhere at a double click

| Date | Status | Decided by |
|---|---|---|
| 2026-10-27 | accepted | Miguel and Claude |

## 1. Context

Claude Code's memory files link to each other as `[[name]]`, where `name` is the `name:` in the other file's front
matter (kebab case, `feedback-verification-discipline`), not its file name (snake case,
`feedback_verification_discipline.md`); a brain read in comando holds 87 such targets. CommonMark has neither: a
renderer shows `[[name]]` as literal text, and front matter as a rule followed by a heading made of its lines. Before
this record comando followed links in rendered Markdown only: a web link at a single click, a link to a Markdown or
HTML file at a double click; the transcript followed its links at a single click and opened only `path:line`
references; tool results, prompts, a panel's text and the tab card's summary had no links.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the link kinds | web addresses, written as links or bare; Markdown links to files; `[[name]]`, `[[name#heading]]`, `[[name\|label]]` note links; the existing `path:line` references | the files stay as they are; comando reads both styles |
| where | a rendered page (a panel's preview, a view, the comparison window, a plan), a panel's text, the transcript's text and prompts, a tool's result, the tab card's summary | every surface: panel, session, session results, tooltip |
| the gesture | a double click, for every kind of link; a single click follows nothing. In the transcript a link's double click selects no word; in a panel's text the editor's word selection stays | one way of doing things |
| what opens | a web address: the desktop's browser; a file of any kind: a new panel, or the panel that has it brought forward (a binary or large file goes to the side pane, as the Files tab's do); a `path:line` reference: the side pane at the line, as before | the Files tab's way |
| how `[[name]]` resolves | in the git repository that holds the document (else its folder), hidden folders and `node_modules` left out, the first of: a file named `name` or `name.md`; a Markdown file whose front matter says `name: <name>`; a file named with `-` and `_` swapped. Names match in any letter case; of several, the nearest to the document wins. A name with `/` is a path from the document's folder or the root first | the file name is Obsidian's rule; the front matter's name is Claude Code's memory format |
| when it resolves | at the double click, off the EDT; a note that names no file is told as a notice ("No file for [[name]] under …"), and the link is not drawn differently | a resolution at every render would walk the repository each time a page is drawn |
| where a session's links are read from | its working directory; a panel's own text, its session's | a relative link or a note in a conversation names the session's files |
| how a note link shows | a rendered page and the transcript show its label (or its name, `name › heading`), as a link; a panel's text, a tool's result and the card keep the text as written, the link underlined | rendered text is read; raw text is edited or quoted |
| a code span | as written, as GitHub and Obsidian show it: a web address or `[[name]]` inside backticks is not a link, in a rendered page or the transcript; a `path:line` reference in one still is, in the transcript | the convention of both; Claude writes file references in backticks |
| front matter | a `---` block at the top, of `key: value` lines, closed by `---` or `...`, shows as a quiet table of its facts above the page, a nested key named by its path (`metadata.type`), a list's items joined by commas; a block that does not look so stays Markdown. Its lines become blank in place, so a comparison's shaded lines still match | the tab card's facts are shown the same way |
| the tab card | links in its summary underlined, followed at a double click; a press on a link does not close the card | the card already opened ticket links at a double click |

Miguel decided that both link styles are rendered and resolved; that a double click on any surface opens a file as a
new panel and a web address in the browser; that front matter is rendered; and the tab card included where feasible. He
accepted the feature on 2026-10-27. Claude decided how a note link resolves, the gesture's details and the surfaces.

## 3. Consequences

`TextLinks` is the one finder of links in plain text; `NoteLinks` reads and resolves note links; `FrontMatter` reads
front matter; `Links` follows a link from any surface, through the opener, the notice and the session folders the shell
installs. `HtmlPane` follows every link at a double click and, without a handler of its own, through `Links`.
`EditorPanel.linkedDocument` and `linkedHeading` are gone: a link to a file of any kind opens it. The Markdown sheet
says what the links and front matter do. `LinksUiTest` double-clicks a note link and a web address in a memory file's
rendered page in the window. Not covered: the About card, whose links are commands; the facts of a tab card; a note
link in a view shown by a tool, which has no folder to read it from.
