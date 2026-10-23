# 85. A panel's text rewritten as a document, with one button

| Date | Status | Decided by |
|---|---|---|
| 2026-10-22 | proposed | Miguel (asked a session to "take the text in this editor pane, process it so that it is professionally written in markdown and then replace the editor's text with that version"; then "I will be asking you in future to take the editor content and professionalise it, so that we get this behaviour, in a button"), Claude (the button's place, the prompt) |

## 1. Context

Miguel writes in a panel as he thinks: notes, a strategy, prompts. Asked once to turn such a text into a document he
would read, the panel's session rewrote it as Markdown with numbered sections and tables, kept his intent and facts,
corrected a repository name, dropped the paragraph that was the instruction itself, and replaced the panel's text
through `ui_set_editor`. He wants that in one press.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| where | an icon in the panel header's session group, after compact and clear, shown while the panel's session runs (`markdown` icon, "Have the session rewrite this text as a professional Markdown document") | it acts on the panel's session, as compact and clear do; one home, no menu duplicate |
| what it sends | the panel's whole text, after a fixed instruction, to the panel's own session as a prompt (`Intent.RunText`, target Claude; `EditorPanel.polishPrompt`): rewrite as a professional Markdown document and replace the panel's text with `ui_set_editor` for this panel; keep the author's intent and every fact; correct spelling and names, checking them where possible; a title, numbered sections, tables where denser; drop the sentences addressed to Claude; add nothing but a short section of the questions left open; then a few lines on what changed beyond wording | the same act Miguel asked for by hand; the session's own register rules apply to the result |
| an empty panel | nothing is sent | nothing to rewrite |
| undo | the replacement is one edit: ⌘Z brings the original back | `ui_set_editor` replaces the text as one undoable change |

## 3. Consequences

The prompt and its answer appear in the transcript like any other turn. Tested in `EditorPanelHeaderTest` (hidden
without a running session, nothing sent for an empty panel, the prompt to the panel's session with the panel's title
and its text).
