# 112. Closing a panel or view gives the keyboard back to what opened it

| Date | Status | Decided by |
|---|---|---|
| 2026-10-28 | accepted | Miguel and Claude |

## 1. Context

Miguel: "when I double click on something to open it, when I close it I want to regain focus to the component that
called it. so I can chain opening and when I close a document, the document or component that triggered the open
regains focus". Closing a panel focused its left neighbour (the reducer's rule), and the keyboard went wherever Swing
moved it when the panel's component left the window.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what is remembered | when a panel or view appears, where the keyboard was at that moment: the component, the panel or view holding it (none for the Files tab, the sidebar's lists, the Session tab), and the lower tab in front | the opener, whatever kind of component it is; read before the panes change, at the render that shows the new one |
| when it is given back | when the panel or view the keyboard is in closes, or the panel in front closes | the user closes what they are in; closing a tab they are not in moves nothing |
| where | to the opener: its panel comes forward (`FocusPanel`), or its lower view's tab, or the lower tab in front then; the component takes the keyboard once it shows. An opener that is gone hands on to its own opener, and so on | chained openings unwind in order |
| when it cannot show | the give-back waits at most three renders, then is dropped | the keyboard never jumps somewhere later |
| how long | for the window's life, the latest 256 openings; nothing is stored | focus is a matter of the session at hand |
| the reducer | unchanged: it still focuses the neighbour, which the give-back then replaces | one place decides the keyboard, the shell, which sees the components |

Miguel decided that closing gives focus back to the opener, with chaining. Claude decided what counts as an opening,
when the give-back applies, the hand-on to an opener's opener and the bound.

## 3. Consequences

`FocusOrigins` is added; `ComandoUi.render` reads the keyboard before the panes change. Any panel or view counts as
opened by where the keyboard was, also one a session opens (`ui_show_view`) or ⌘N makes. Tested in `FocusOriginsTest`
(the chain, a closed opener handing on, an opener outside the panels, closing what the keyboard was not in, no opener
left) and off screen in `FocusBackUiTest` (`:app:uiTest`: A → B → C closed back to B then A, the session list; without
the give-back the test fails).
