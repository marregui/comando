# 112. Closing a panel or view gives the keyboard back to what opened it

| Date | Status | Decided by |
|---|---|---|
| 2026-10-28 | accepted; what is remembered amended the same day (see Amendments) | Miguel and Claude |

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
opened by where the keyboard was, also one a session opens (`ui_show_view`) or ⌘N makes. ## Amendments

2026-10-28, the rule itself (Miguel, "model 2", after reasoning it from first principles: opened from reviews, a
click on main, the file closed, and he expected to understand where he should land). Three models were weighed:
provenance (back to what opened the document), the return path (back to where the user was just before they last
arrived at it; an IDE's editor tabs), and context ownership (always back to the opener, even from the background). The
return path is the rule: each panel and view remembers where the user was when they last arrived at it, an opening
being one such arrival, told at the request; closing the place the user is at returns there; closing a tab they are
not at moves nothing; a place reached because another closed keeps its own way back; a way back that closed meanwhile
hands on to its own. The place the user is at is a view the keyboard is in, else the panel in front, its lower tabs
included. The amendments below were made under provenance and hold under the return path. Tested in
`FocusOriginsTest` (chains unwinding, the last way in winning, a background close, an opening told at the request, a
closed way back handing on) and `FocusBackUiTest` (opened from review, a click on main and back, closed: main; a
background close moves nothing; with the first arrival kept instead, both fail).

2026-10-28 (Miguel: a link double clicked in the reviews session's panel opened a file; closing it went to the main
session's panel, the tab at its left). Two holes, both closed: a file already open was only brought forward, so it kept
the opener of its first opening; and a keyboard left in a panel behind the one in front was taken for the opener. Now
the shell tells the opening at the request (`FocusOrigins.opened`), for a file opened, a panel brought forward or a
closed one brought back, and the latest opening wins; a keyboard in a panel not in front, or nowhere, counts as the
panel in front, whose page or text (`EditorPanel.focusTarget`) takes it back. Tested in `FocusOriginsTest` and
`FocusBackUiTest` (the report reopened from the review panel and closed goes back there, not to main; without the
fix it went to main).

2026-10-28, later (Miguel: a document opened from the reviews session's Session tab, closed, brought the main panel
forward with its Session tab). The lower tabs (Session, Approvals, Terminal, Results) show the panel in front, so the
transcript the keyboard was in belongs to that panel, which the opening did not remember. An opening now remembers the
panel in front too (`Origin.front`); giving back brings forward the panel the keyboard was in, else that one, then the
lower tab, then the component. Tested in `FocusBackUiTest` (a document opened from the reviews Session tab, with main
at the reviews panel's right, closed: reviews in front, Session in front, the keyboard in its transcript; without the
fix it fails).

Tested in `FocusOriginsTest`
(the chain, a closed opener handing on, an opener outside the panels, closing what the keyboard was not in, no opener
left) and off screen in `FocusBackUiTest` (`:app:uiTest`: A → B → C closed back to B then A, the session list; without
the give-back the test fails).
