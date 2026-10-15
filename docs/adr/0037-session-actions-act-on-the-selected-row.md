# 37. Session actions act on the selected row

| Date | Status | Decided by |
|---|---|---|
| 2026-10-15 | accepted | Miguel (the buttons, the removal question, the plug colours, the panel's title), Claude (how) |

## 1. Context

The Sessions tab had two buttons: **+**, which opened the session dialog on the selected session (or the first), and
import. Miguel clicked **+** to add a session, typed over the form of the existing session `standard`, then closed
the dialog with its close button. The close button hid the dialog without a question, the edits were not saved, and
the next opening on `standard` showed them again, because the dialog keeps unsaved edits to the resource it reopens
on. Each row also carried an edit button (pencil), and its plug showed the action (green plug: connect; grey plug:
disconnect) rather than the state. A row bound to the focused panel said "this panel" instead of the panel's title.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| buttons above the list | add (+), edit, clone, remove, import, in that order; edit, clone and remove act on the selected row and are off while none is selected | one place for every session action; + does one thing |
| add | opens the dialog on a new session, whatever is selected | **+** read as "new" and opened an existing session |
| clone | opens the dialog on a copy of the selected session, not saved until Save | as the dialog's Clone |
| remove | asks, naming what removal does; a running session (not closed, not failed) offers **Stop and remove** or **Leave it**, a stopped one **Remove** or **Cancel**; the safe answer is the default, and closing the question is the safe answer | removing a running session stops it; the user decides that in the question |
| the row's edit button | removed | the button above the list edits; the context menu keeps Edit… and gains Clone… |
| the plug, on the row and in the panel header | shows the state: green while the session runs (or the connection is open), grey while not; a click toggles; the tooltip says the state and what the click does | a control that shows its action read as the wrong state |
| where a row is bound | the titles of its panels, the focused one first, in the accent colour when the focused panel is one of them; "headless" when no panel binds it | "this panel" did not say which; an unbound session said nothing |
| the dialog's close button | does what Esc does: with unsaved changes it asks "Discard changes to X?" first; an open question is answered with its safe button | a closed dialog never holds an unsaved edit that reappears at the next opening |

## 3. Consequences

[Record 32](0032-session-rows-carry-their-controls.md)'s edit control and plug icons are replaced by this record. The
Connections tab keeps its single **+** button, which still opens the dialog on the selected connection; the dialog's
close button asks there too. Removal from the sidebar asks with a modal question; removal in the dialog asks in its
own question row, as before.
