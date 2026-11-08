# 138. A session shows the repository it writes to

| Date | Status | Decided by |
|---|---|---|
| 2026-11-07 | accepted; built | Miguel and Claude |

## 1. Context

Every session had `~/.claude` as its working folder, so the branch on the session bar was the kit's, whatever the session
worked on. `WorkedIn` in `ui` derives folders from tool calls, but it counts reads, and `ui` cannot call the adapter. A
conversation was also found only by its name: nothing searched the about 1 GB of stored transcripts, in which one line can
be megabytes and most bytes are tool results and metadata.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what a subject is | the repository of the latest `Write`, `Edit`, `MultiEdit` or `NotebookEdit` that finished; the others are listed by most recent; a failed or running write does not count | a write says what a session changes; a read says what it looked at; a failed write changed nothing |
| what is not parsed | `Bash` commands; any field but `file_path` and `notebook_path` | a command's paths cannot be read without guessing |
| the working folder | a path under it, or under Claude Code's folder, is dropped before any file is looked at, by its written form and its real one | the kit and the brain inside it are not a subject, and comando reads no `.git` under them |
| when it is read | the blocks added since the last pass, at a turn's end and after a history load, on the session's strand; a change dispatches `SessionSubjectRead` | the transcript only grows; the window hears of a change and of nothing else |
| a running write | the next pass starts at it | its result decides whether it counts |
| a cleared conversation | the scan starts again and keeps the repositories on a view's clear; a new conversation forgets them | the view is emptied but the work stands; a new conversation worked on nothing yet |
| the ticket | `[A-Za-z]{2,}-\d+` in the branch's name, in upper case | branch names carry it in lower case |
| the session bar | the name, `⎇ branch` and the repository's short name; with no subject, what it showed before | the branch means little without the repository |
| the card | the Folder fact stays; Repository, Branch, Ticket and Also follow it; the row's copy text gains them too | one line of the bar cannot hold the others; the folder is still where the session runs |
| no writes | no branch while the working folder is the kit; a real repository as working folder shows its branch as before, with no ticket | the kit's branch says nothing of the work |
| the sidebar | the row stays one line | the list is for telling sessions apart |
| where a transcript search looks | top-level `projects/*/*.jsonl`, newest first; sub-agents' files are left out | the conversations the user had; the source is `TranscriptSearch`, shaped as `SessionCatalogue` |
| what matches | bytes first: the JSON-encoded word in the mapped file; then only that line is parsed, and the word must be in the text of a typed prompt or an assistant text block | keys, metadata and tool results hold most of the bytes and were never said by anyone; the byte scan alone would answer `sessionId` with every line |
| what a prompt is | a user line of human origin that is not meta, a side chain or a compact summary, and whose text is not a command wrapper, an interruption mark or a continuation summary | those are written by Claude Code, not typed |
| a hit | one per message, at its first match: a one-line window of at most 200 characters, 60 before the match; the conversation's id and its group, the latest `custom-title`, else `agent-name`, else the id's first 8 characters | as `FolderSearch`; a message of megabytes never reaches the window whole |
| a file cut while mapped | counts as failed | the search goes on and the window says how many were not read |
| ending | `ended` is read per file and per 1 MB scanned | a line can be megabytes, so a line count bounds nothing |

## 3. Consequences

`SessionSubjects`, `TranscriptSearch` and `CardFacts`' new facts are added, `GitBranch` reads through `Mapped` and gains
`top`, and `Mapped` gains `stream(from, to)` for a line's slice. Tested in `SessionSubjectsTest`, `GitBranchTest`,
`TranscriptSearchTest`, `SessionBarTest`, `CardFactsTest` and `SubjectUiTest`.

A warm search of a 1.0 GB synthetic fixture (95 files, lines from 400 bytes to 3 MB) took 1.53 s for a word that is
absent, 1.58 s for a rare one, 2.48 s for one with 42,036 hits and 4.18 s for `sessionId`, a key on nearly every line, with
no hit: median of 7 runs, one thread, Apple M2 Max, Java 25, with other builds running.

A word in a non-ASCII alphabet is looked for as UTF-8, as Claude Code writes it, so a line that wrote it as `\uXXXX` is not
found. Case folds ASCII letters only. A session whose writes all fall under a real repository as working folder has no
subject and so no ticket; once it writes elsewhere, the bar keeps that repository. The Also fact has no limit: the card
clips it.

## 4. Alternatives

Parsing `Bash` finds more repositories and is wrong on any `cd`, variable or script. Reading the subject from `WorkedIn`
counts reads and keeps it in `ui`. Taking `cwd` from the transcript gives the working folder again. Parsing every line
without the byte scan costs every byte of the 1 GB in Jackson; an index would need its own store and its own staleness.
