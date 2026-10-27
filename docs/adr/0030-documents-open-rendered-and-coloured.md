# 30. Documents open rendered and coloured

| Date | Status | Decided by |
|---|---|---|
| 2026-10-14 | accepted; its editor theme amended by [record 57](0057-a-light-editor-and-a-header-of-icons.md) and [record 93](0093-comando-is-dark-throughout.md) | Miguel and Claude |

## 1. Context

[Record 29](0029-the-scratchpad-is-a-navigator.md) opened a scratchpad file in a panel as text, with a Preview switch
for Markdown, and coloured every panel as SQL. Scratchpad documents are read more often than they are edited, and
source files were to be coloured as IntelliJ colours them. This record replaces the Markdown row of record 29.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| a document | a panel opened on a file outside `panels/` (scratchpad click, Open file…); a panel's own file is not one | the panel's own text is SQL and prompts, run block by block |
| a Markdown document | opens rendered; the header's button names the other view and switches to it: Edit (pencil) while rendered, Preview (eye) while editing; ⌘⇧M does the same | reading first, editing one click away; the label says what a click does |
| a panel's own Markdown file | opens in the editor; Preview shows the rendered page | it is written more than read |
| colours of a document | the syntax of its extension (or whole name, `Makefile`); the gutter marks no run targets and SQL completion does not pop up | Java, Python and the rest read as code, not as SQL and prompts |
| editor theme | IntelliJ's Dark scheme (new UI) colours, for every text area and the transcript's code | one palette across the editor, the viewers and the transcript, the text the author reads all day |
| code in rendered Markdown | a fence naming its language takes the same colours; a fence without one stays plain | a plan's code reads as it does in the editor |

Miguel decided rendered first, the Preview / Edit button and IntelliJ's colours; Claude decided how.

## 3. Consequences

The SQL editor, the side pane's viewers, diffs and approval cards change colour with the theme: background `#1E1F22`,
keywords `#CF8E6D`, strings `#6AAB73`, numbers `#2AACB8`, comments `#7A7E85`, annotations `#B3AE60`. Class names stay
in the plain foreground, as in IntelliJ; RSyntaxTextArea tags JDK class names and Python built-ins as functions, and
the theme gives functions no colour of their own. A document still runs: ⌘⏎ sends the block under the caret to the
panel's session or connection.
