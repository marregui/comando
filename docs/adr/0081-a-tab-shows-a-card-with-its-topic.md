# 81. A tab shows a card with its topic

| Date | Status | Decided by |
|---|---|---|
| 2026-10-21 | accepted; amended 2026-10-22 (see Amendments); amended by [record 87](0087-a-session-row-shows-the-tabs-card.md) | Miguel and Claude |

## 1. Context

A tab's tooltip said the panel's title, bindings and status, and went away when the pointer moved. With many tabs open,
telling what each panel is about meant opening it. The application already ran about 85 platform threads with five
sessions (measured 2026-10-21: about 40 are the JVM's own, 5 per running session, the rest one per part), and a feature
that reads every tab every 10 s must not add a thread, a timer or a process per tab. This record replaces the panel
tabs' tooltip.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what shows | resting the pointer on a tab for the tooltip delay shows its card under it: the tooltip's text; set apart below it, "Summary:" and the panel's topic in gold, wrapped at 460 px to a few lines; at the bottom left, ↻ and ✕. The tab has no tooltip of its own; its close button keeps its own | Miguel chose the layout, the gold and the buttons' place, after trying a first version on 2026-10-21 |
| how long | until ✕ or a click on the card, or another tab's card replaces it, or its tab closes; the pointer leaving does not close it. ↻ reads the topic again and leaves the card | Miguel |
| what the topic says | two or three sentences, at most about 300 characters: the repository or project by its folder name, the task, and the step it is on now, naming the files, classes, tickets or commands the conversation names. A session's source is headed by its working folder and git branch; a document's by its file | Miguel asked for the repository and for two to three lines |
| what the topic is read from | a session panel: the end of its main conversation (prompts, Claude's text, tool calls with their input's start; no thinking, notes or subagents), at most 6 000 characters, always with the latest prompt (`TopicSource`). A panel without a session, or whose session has said nothing: the start of its own text | every tab works the same; a document's start says what it is, and does not move as a log grows |
| when it is read | when a card shows and the panel has no topic yet or its source changed; every 10 s for every open tab whose source changed since its last read (on the shell's 1 s tick, no timer of its own); always on ↻ | a panel at work changes every 10 s, an idle one never: idle tabs cost nothing |
| how it is read | `claude --safe-mode -p --model haiku --effort low --tools "" --system-prompt … --no-session-persistence --output-format json`, `MAX_THINKING_TOKENS=0`, the source on standard input, bounded at 30 s (`ClaudeTopics`); the whole answer is the topic, on one paragraph, cut at 400 characters. Measured with Claude Code 2.1.304: 1.7–1.9 s, US$0.0001–0.0003 a read; the default system prompt and thinking made a one-line read 6.6 s and 5.4 times the cost | the engine is Claude Code only; safe mode loads no hooks, plugins or MCP servers |
| threads | one `topic-reader` thread reads every ask, one at a time: at most one Haiku process runs. Asks wait in one queue, at most one per tab: an ask for a tab already waiting replaces it in its place; the card shown goes first. The card and the checks run on the EDT | the queue's length is bounded by the number of tabs and the processes by one, whatever the rate of asks |
| where the topic goes | from the reader to the workspace on the EDT, never through the reducer or the intent log (`TopicPort`, called by `ui`) | a topic is derived view data, like a drawn diagram; 6 000 characters every 10 s per working tab would fill the intent log |
| the name | "topic" in code, "Summary" on the card | "theme" already names the light or dark editor theme; Miguel calls what the card shows a summary |

Miguel decided a card on a panel tab instead of its tooltip: what the tooltip said, plus one line Haiku writes on what
the panel's session works on; a button that reads it again; the card staying until it is clicked; the line refreshed
every 10 s for the panels at work; every panel the same; no thread per tab. Claude decided the topic of a panel without
a session from its text, a read only when its source changed, one reader thread, the card in the popup layer, and the
name "topic". Miguel accepted the record after trying it.

## 3. Consequences

With five sessions at work the reader runs about one read every 2 s and stays behind by at most one round of the tabs;
a slow read delays the next, it does not add a thread. Topics are not kept across a restart: the first check after the
start reads each open tab once. A panel with nothing to read says so on its card and asks nothing. The card is a
component of the window's popup layer, so it moves with the window and is clipped to it; it takes no keyboard focus.
Since [record 82](0082-threads-by-kind-with-a-budget.md) the reader is a strand: `topic-reader` runs on one virtual
thread.

## Amendments

2026-10-22, decided by Miguel: the card closes when the pointer leaves it; the summary is read the first time the card
opens, again only on ↻ or, at a later show, when the panel changed, never on a timer; the panel's last paragraph, the
command most recently written or sent, is what the summary is about, read with the rest of the panel and with the
session's last response; a Linear ticket the panel names shows as its id, a link a double click opens in the browser.
These rules replace the rows above that they contradict.

| Question | Rule | Why |
|---|---|---|
| how long | the card closes when the pointer leaves it, or leaves its tab without reaching the card within 500 ms (`Workspace.LEAVE_GRACE_MILLIS`); ✕ and a click on the card (not on a link) close it as before | the grace lets the pointer travel from the tab to the card below it |
| when it is read | the first time the card shows; again when it shows and what it is read from changed since the last read; on ↻. No timer | Miguel |
| what it is read from | a session's panel: the end of the panel's own text (the last 8 000 characters), its last paragraph (after the last blank line, up to 600 characters) given as the command the summary is about; then the session's last response (the main conversation's latest text, its first 700 characters), to read with that command; then the text before the paragraph, its end kept, as context, within 2 000 characters in all; headed by the session's folder and branch. A new response changes what it is read from, so the next show reads it again. A blank panel falls back to the end of the session's conversation; a panel without a session, to the start of its text as before (`TopicSource.ofPanel`) | the panel is where the user writes the prompts, newest at the end |
| how much is read | the read is given 2 000 characters (`TopicSource.MAX_CHARS`, was 6 000) and asked for one or two sentences of at most 200 characters (was two or three, 300); the answer is cut at 260 characters (`ClaudeTopics.MAX_TEXT`, was 400). A read takes what it takes; one that hangs is stopped at 30 s as before. Measured 2026-10-22 with Claude Code 2.1.308, the whole command: about 4 200 characters in, 2.6 to 4.5 s; 2 000 in and 200 out, a median of 2.0 s, the slowest of five 3.15 s; 1 200 in was no faster, so the API's own time, not the input, is what remains | a smaller input for Haiku; a 3 s limit was tried the same day and withdrawn, since a read takes what it takes |
| tickets | under the heading, up to three Linear ids the panel's text names, the last paragraph's first, as links; a double click opens `https://linear.app/<workspace>/issue/<id>` in the desktop's browser. An id counts only when its team key was seen in a Linear link (`linear.app/<workspace>/issue/<KEY>-<n>`) in the panel's text or the session's last 300 blocks; the workspace and keys learnt are kept in the preferences (`LinearTickets`) | ids are found in the text, not by the model; learning from Linear's own links needs no setting and never takes `UTF-8` or `ISO-8859` for a ticket |
