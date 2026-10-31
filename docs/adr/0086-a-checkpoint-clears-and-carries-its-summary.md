# 86. The Checkpoint button clears the conversation and carries its summary into the next prompt

| Date | Status | Decided by |
|---|---|---|
| 2026-10-25 | accepted; its rule for which panel the rewrite acts on amended by [record 88](0088-a-session-and-its-panel-share-a-name.md); the header's buttons moved to the session bar by [record 105](0105-a-session-bar-under-the-lower-panel.md); its clear and carried summary replaced by a compaction by [record 129](0129-a-checkpoint-reflects-then-compacts.md) | Miguel and Claude |

## 1. Context

The author ends a stretch of work in a session with its checkpoint (`/reflect`): memory and the knowledge base are
written, and the turn's last answer summarises what was kept. He then wanted a fresh conversation that starts from that
summary, which took a `/clear` by hand and left the summary behind. Under `claude -p`, `/clear` takes no argument and a
conversation's context can only be given by a prompt, so the summary has to travel with one. The `result` event of a
turn carries its last answer as text.

This record amends the clear buttons of [record 46](0046-clear-buttons-text-coloured-by-kind-checkpoint-due.md), the
panel header's clear of [record 57](0057-a-light-editor-and-a-header-of-icons.md), the session row's controls of
[record 53](0053-session-rows-compact-clear-copy-and-an-order-of-your-own.md), and the place of the rewrite of
[record 85](0085-a-panel-rewritten-as-a-document.md).

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what starts it | the Checkpoint button, on the panel's header or the session's row (`Intent.Checkpoint`); a checkpoint typed or sent by another session is a prompt like any other | the button is the request; a typed `/reflect now` does not clear behind the user's back |
| when it clears | when the checkpoint's turn has finished: nothing runs or is queued, no background work it started is still out, and the turn ran or carries text | the summary is the turn's last answer, which exists only then |
| what clears | `/clear` is sent (one turn, no model call) and the Session tab is emptied to a note and the summary, shown as the answer of a turn; the clear's own prompt, note and turn end are not shown (`ClaudePort.clearConversation`) | the tab shows what the new conversation starts from |
| the panel's text | untouched | the user's notes are the user's |
| the tab card | the topics of the session's panels are dropped whenever the session's conversation changes (a checkpoint's clear, a fresh start, a `/clear` typed by hand) and read again at the next show | a summary of the old conversation is about something else |
| the context | the summary is kept in `SessionSpec.carried` (saved in `sessions.json`) and goes in front of the next prompt that is not a slash command, under a line saying what it is; the prompt's block shows the prompt as typed; then `carried` is empty | no turn is spent on it; it survives a stop, a restart of comando and an edit in the session dialog |
| when it does not clear | the user sends a prompt after the checkpoint (the user goes on); the turn fails or is interrupted; the process ends; the answer is blank (a warning says the conversation is kept); a summary is still carried (the conversation has had no prompt since its clear: nothing new to clear) | each keeps the conversation the user has |
| the `/clear` buttons | removed from the panel's header, the session's row and the session's menu | the checkpoint clears; one way of doing it. The Session tab's Clear, which empties the view only, stays |
| the rewrite | moves from the panel's header to the session's menu in the sidebar, "Rewrite ‹panel› as a document", while the session runs: the focused panel when it is the session's, else its first panel; disabled while that panel's text is blank | the place Miguel asked for |

Miguel decided that when the checkpoint's task finishes the session is cleared, the Session tab is cleared and shows the
summary, which becomes the session's context, the panel's text is left as it is, and the session's tab-card summary is
dropped; that the clear buttons go and the rewrite moves to the session's menu; and he chose every `/clear` button gone
and the summary put in front of the next prompt. Claude decided the lifecycle and the edge cases.

## 3. Consequences

The panel header's Fresh, Checkpoint and Compact now act on the session the panel shows: they read the panel as it was
made, so a panel bound to a session afterwards sent nothing.

Tested in `ReducerTest` (the lifecycle and every case that keeps the conversation), `StreamMapperTest` and
`ClaudeAdapterTest` (the quiet clear, the carried summary, the preface not shown), `WorkspaceTest` (the topic dropped on
a new conversation), `SessionsSidebarTest` (the rewrite item, no clear), `EditorPanelHeaderTest`, and off screen in
`CheckpointClearUiTest` (`:app:uiTest`: the button clicked in the real window against a stand-in for `claude -p`).
