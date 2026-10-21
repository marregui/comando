# 81. A tab shows a card with its topic

| Date | Status | Decided by |
|---|---|---|
| 2026-10-21 | proposed; replaces the panel tabs' tooltip | Miguel (a card on a panel tab instead of its tooltip: what the tooltip said, plus one line Haiku wrote on what the panel's session works on; a button that reads it again; the card stays until it is clicked; the line refreshed every 10 s for the panels at work; every panel the same; threads must not compound), Claude (the topic of a panel without a session from its text, read only when its source changed, one reader thread, the card in the popup layer, the name "topic") |

## 1. Context

A tab's tooltip said the panel's title, bindings and status, and went away when the pointer moved. With many tabs open,
telling what each panel is about meant opening it. The application already ran about 85 platform threads with five
sessions (measured 2026-10-21: about 40 are the JVM's own, 5 per running session, the rest one per part), and a feature
that reads every tab every 10 s must not add a thread, a timer or a process per tab.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what shows | resting the pointer on a tab for the tooltip delay shows its card under it: the tooltip's text; set apart below it, "Summary:" and the panel's topic in gold, wrapped at 460 px to a few lines; at the bottom left, ↻ and ✕. The tab has no tooltip of its own; its close button keeps its own | Miguel (layout, gold, the buttons' place, amended 2026-10-21 after trying it) |
| how long | until ✕ or a click on the card, or another tab's card replaces it, or its tab closes; the pointer leaving does not close it. ↻ reads the topic again and leaves the card | Miguel |
| what the topic says | two or three sentences, at most about 300 characters: the repository or project by its folder name, the task, and the step it is on now, naming the files, classes, tickets or commands the conversation names. A session's source is headed by its working folder and git branch; a document's by its file | Miguel ("more specific, including working on what repo"; "2-3 lines") |
| what the topic is read from | a session panel: the end of its main conversation (prompts, Claude's text, tool calls with their input's start; no thinking, notes or sub-agents), at most 6 000 characters, always with the latest prompt (`TopicSource`). A panel without a session, or whose session has said nothing: the start of its own text | every tab works the same (Miguel); a document's start says what it is, and does not move as a log grows |
| when it is read | when a card shows and the panel has no topic yet or its source changed; every 10 s for every open tab whose source changed since its last read (on the shell's 1 s tick, no timer of its own); always on ↻ | a panel at work changes every 10 s, an idle one never: idle tabs cost nothing |
| how it is read | `claude --safe-mode -p --model haiku --effort low --tools "" --system-prompt … --no-session-persistence --output-format json`, `MAX_THINKING_TOKENS=0`, the source on standard input, bounded at 30 s (`ClaudeTopics`); the whole answer is the topic, on one paragraph, cut at 400 characters. Measured with Claude Code 2.1.304: 1.7–1.9 s, US$0.0001–0.0003 a read; the default system prompt and thinking made a one-line read 6.6 s and 5.4 times the cost | the engine is Claude Code only; safe mode loads no hooks, plugins or MCP servers |
| threads | one `topic-reader` thread reads every ask, one at a time: at most one Haiku process runs. Asks wait in one queue, at most one per tab: an ask for a tab already waiting replaces it in its place; the card shown goes first. The card and the checks run on the EDT | the queue's length is bounded by the number of tabs and the processes by one, whatever the rate of asks |
| where the topic goes | from the reader to the workspace on the EDT, never through the reducer or the intent log (`TopicPort`, called by `ui`) | a topic is derived view data, like a drawn diagram; 6 000 characters every 10 s per working tab would fill the intent log |
| the name | "topic" in code, "Summary" on the card | "theme" already names the light or dark editor theme; Miguel calls what the card shows a summary |

## 3. Consequences

With five sessions at work the reader runs about one read every 2 s and stays behind by at most one round of the
tabs; a slow read delays the next, it does not add a thread. Topics are not kept across a restart: the first check
after the start reads each open tab once. A panel with nothing to read says so on its card and asks nothing. The
card is a component of the window's popup layer, so it moves with the window and is clipped to it; it takes no
keyboard focus.
