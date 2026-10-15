# 37. Session and connection actions act on the selected row

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
| buttons above the list | two groups: add (+) and import on the left, which bring sessions in; edit, clone and remove on the right, which act on the selected row and are off while none is selected (Miguel, 2026-10-15) | one place for every session action; + does one thing; the groups separate adding from acting on a row |
| add | opens the dialog on a new session, whatever is selected | **+** read as "new" and opened an existing session |
| clone | opens the dialog on a copy of the selected session, not saved until Save | as the dialog's Clone |
| remove | asks, naming what removal does; a running session (not closed, not failed) offers **Stop and remove** or **Leave it**, a stopped one **Remove** or **Cancel**; the safe answer is the default, and closing the question is the safe answer | removing a running session stops it; the user decides that in the question |
| the row's edit button | removed | the button above the list edits; the context menu keeps Edit… and gains Clone… |
| the plug, on the row and in the panel header | shows the state: green while the session runs (or the connection is open), grey while not; a click toggles; the tooltip says the state and what the click does | a control that shows its action read as the wrong state |
| where a row is bound | the titles of its panels, the focused one first, in the accent colour when the focused panel is one of them; "headless" when no panel binds it | "this panel" did not say which; an unbound session said nothing |
| connections | the same bar, without import: add on the left; edit, clone and remove of the selected connection on the right; removing an open connection offers **Disconnect and remove** or **Leave it**; the context menu gains Clone… (Miguel, 2026-10-15) | one behaviour for both resource lists |
| one component | `ResourceActions` in `ui.kit.resource` draws the bar for any resource list: the kind names its buttons and asks its removal question, the owner passes what add, edit and clone open and what remove dispatches | the two tabs share the code; it is a kit component rather than a widget, since a widget in comando is a view placed in a tab ([Widgets](../design/widgets.md)) |
| the dialog's close button | does what Esc does: with unsaved changes it asks "Discard changes to X?" first; an open question is answered with its safe button | a closed dialog never holds an unsaved edit that reappears at the next opening |

## 3. Consequences

[Record 32](0032-session-rows-carry-their-controls.md)'s edit control and plug icons are replaced by this record. The
dialog's close button asks in the connection dialog too. Removal from the sidebar asks with a modal question; removal in
the dialog asks in its own question row, as before.
