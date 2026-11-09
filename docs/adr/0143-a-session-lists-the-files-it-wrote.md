# 143. A session lists the files it wrote

| Date | Status | Decided by |
|---|---|---|
| 2026-11-08 | accepted; built | Miguel and Claude |

## 1. Context

A session's work ends in files: code, a report, a spec. To review them Miguel read the transcript for paths, or the
Files tab's gold marks (record 76), which show every changed file of the checkout, other sessions' included. He
accepted a view of what one session wrote on 2026-11-08 (task 77, proposal 5).

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what it lists | the files written by the session that has the keyboard: its Write, Edit, MultiEdit and NotebookEdit calls in its transcript, in the order it first wrote each, each once | the session's deliverables, not the checkout's changes |
| where | a side-pane view, "Files written", from the Window menu and the agent tool `ui_show_view` with type `deliverables` | a list beside the work, as the Search and Changes views |
| reading a row | gold until opened, as in the Files tab; ↓/↑ show the file in the preview tab without taking the keyboard, Enter or a double click keeps the tab (record 141) | the Search view's gestures, so one way to read a list of files |
| when it changes | it follows the focused session and grows as the session writes; the transcript is read off the EDT | nothing blocks the window |

## 3. Consequences

`ViewSpec.Deliverables` and `ui/widgets/deliverables/` are added; `ComandoUi` registers the widget. Tested by the
widget's unit tests, the `ui_show_view` parsing test, and `DeliverablesUiTest` on `:app:uiTest`.

## 4. Alternatives

The Files tab filtered to one session's files would need the tab to know sessions; the transcript already holds the
list. The list inside the Session tab would compete with the conversation for its height.
