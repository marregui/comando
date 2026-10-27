# Markdown

What you write in a panel, and what its preview (⌘⇧M) shows. comando draws CommonMark with tables; what it does not draw is listed at the end.

## Text

| Write | Shows |
|---|---|
| `**bold**` | **bold** |
| `*italic*` or `_italic_` | *italic* |
| `***both***` | ***both*** |
| `` `code` `` | `code` |
| `\*` | a literal `*`: a backslash keeps any punctuation as it is |

## Blocks

| Write | Shows |
|---|---|
| `# Title` … `###### Small` | a heading, six sizes |
| `- item`, `* item` or `+ item` | a bulleted list; indent two spaces to nest |
| `1. item` or `1) item` | a numbered list |
| `> quoted` | a quotation |
| `---` alone on a line | a rule across the page |
| `---` on the first line, `key: value` lines, `---` | front matter: its keys and values at the top of the page, not a rule and a heading |
| a blank line | a new paragraph |
| a line break | a new line in the same paragraph (GitHub would join the two lines) |

## Links and pictures

| Write | Shows |
|---|---|
| `[text](https://example.org)` | a link; a double click opens the browser |
| `[text](notes.md)` | a link to a file beside the document; a double click opens it in a panel |
| `[[notes]]`, `[[notes#heading]]`, `[[notes\|text]]` | a link to a note, shown as its name or text: a double click opens the file named `notes` or `notes.md`, or the one whose front matter says `name: notes`, the nearest in the document's repository |
| `[text](#3-decisions)` or `[text](notes.md#3-decisions)` | a link to a heading of this document or another; a double click scrolls to it, opening the other document first. The id is the heading's text in lower case, punctuation dropped, each space a hyphen: `## 3. Decisions` is `#3-decisions`; a repeated heading adds `-1`, `-2` |
| `https://example.org` or `<https://example.org>` | the address as a link |
| `![what it shows](images/chart.png)` | the picture: a PNG, JPEG, GIF or SVG on this machine, named from the document's folder (`images/chart.png`, `../img/flow.svg`) or by its full path; one wider than the panel is shown narrower |
| `![what it shows](https://example.org/chart.png)` | *what it shows*, the alt text: comando fetches nothing from the web. A file that is not there also shows its alt text, with the reason |

## Code

A fence that names its language is coloured as the editor colours that language; one without, or a block indented by four spaces, is plain. A `mermaid` fence is drawn as a diagram where `mmdc` is installed.

````
```sql
SELECT * FROM trades LIMIT 10;
```
````

## Tables

A row of dashes under the header; a colon on the left, the right or both aligns the column.

```
| Name | Price |
|:-----|------:|
| A    |   1.5 |
```

## Not drawn here

| Write | Shows |
|---|---|
| `~~strike~~`, `- [ ] task`, footnotes, definition lists | the text as typed |
| an address without angle brackets | the address as text |
| HTML such as `<b>` | the tags as text |

## Prompts to Claude

- Claude reads the text as typed: nothing is rendered for it. Markdown helps as structure: headings and lists set out the parts of a request, and a fenced block shows where code, data or a log begins and ends.
- In the editor a blank line ends a block, and ⌘⏎ sends the block under the caret. To send a prompt of several paragraphs, select all of it first.
- A block that starts as SQL does (`SELECT …`, `UPDATE … SET …`) and holds no English words such as "the" or "me" runs on the database. A first line `-- @claude` sends the block to Claude; ⌘⇧⏎ sends it whatever it looks like.
