# 108. Links in every card of the transcript, and file paths as links

| Date | Status | Decided by |
|---|---|---|
| 2026-10-28 | accepted; where a file path opens amended by [record 111](0111-a-file-link-opens-as-the-files-tab-does.md) | Miguel and Claude |

## 1. Context

A churning session fills the transcript with cards: commands, skill invocations, tool calls, delegations. They name
documents and addresses comando can open, yet only the prompt, the assistant's text and a tool's result had links
([record 94](0094-links-in-both-styles-everywhere.md)). A command's text, a delegation's prompt, the input of any other
call, thinking and hook notes had none, and a card's summary (the file a Read works on, the address a fetch reads) was
plain text. A file was a link only as `path:line`: a bare `docs/adr/0107-x.md` or `/tmp/build.log`, the way Claude
writes most paths, was text everywhere. Miguel: these should open at a double click as in the editor and the final
result.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| which texts | every text a card shows: the prompt, the text, thinking, hook notes, a tool's result, and what the call is given (a command, a delegation's prompt, the JSON of any other call) | one way of doing things: a link is a link wherever it is written |
| a card's summary | links what the call works on: a file tool's file, whole even when the summary is clipped; a fetch's web address; a skill's `SKILL.md` in the user's skills folder (`$CLAUDE_CONFIG_DIR/skills`, else `~/.claude/skills`) when it is there; nothing for the others | the summary is where the eye goes; a clipped path must not open a wrong file |
| file paths | a path with at least one folder and a file name with an extension (`docs/a.md`, `/tmp/b.log`, `~/c.md`) is a link, as `path:line` is, and opens the same way, at the file's top; a bare file name (`Lane.java`), a dotted word, a path inside a web address or after `@` stays text | the one finder (`TextLinks`, `SourceLinks`), so every surface gains them: the editor, the transcript, code spans, a tab's card. A bare name would link every class name in prose |
| following | at a double click, as everywhere (record 94) | unchanged |

Miguel decided that the cards' references open as in the editor and the result. Claude decided which texts, the
summary's targets and the path rule.

## 3. Consequences

`SourceLinks` finds paths without a line; `Items` links the input boxes, the summary, thinking and notes; `ToolText.target`
names a summary's link. A rendered HTML page still shows neither `path:line` nor paths as links (`MarkdownHtml`), as
before. Tested in `SourceLinksTest` (paths, what stays text), `TextLinksTest`, `ToolTextTest` (targets, a skill's file),
`TranscriptCanvasTest` (a clipped Read summary, a command's path and address, a delegation's `~/` path, a skill's
input, a double click following a command's path).
