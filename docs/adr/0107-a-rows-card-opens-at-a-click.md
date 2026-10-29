# 107. A sidebar row's card opens at a click, beside the rows, and follows the pointer

| Date | Status | Decided by |
|---|---|---|
| 2026-10-28 | accepted; amended on 2026-10-29 (see Amendments) | Miguel and Claude |

## 1. Context

A session row showed its card when the pointer rested on its name, after a tooltip's wait
([record 87](0087-a-session-row-shows-the-tabs-card.md)); a connection row the same, with a card of facts
([record 92](0092-a-connection-row-is-its-icon-and-name.md)). Miguel wanted the card at a click rather than at a rest,
and, once one is open, the card of each name the pointer goes on to without a click. Under its row, the card covered
the rows below it, so the pointer could follow only onto the names above; Miguel asked for it beside the longest name.
This record amends where and when records 87 and 92 show the card; what it shows is unchanged.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| opening | a click on the ⓘ at the right edge of the row the pointer is over shows its card at once; a click on the name selects the row and shows nothing; resting on a name shows nothing | Miguel's ask; the ⓘ's place is kept on every row, drawn only on the hovered one, so no row changes width |
| a press | a right-click on a row opens its menu and closes its card; any press on a panel tab, a click or the menu's, closes the tab's card or keeps it from showing until the pointer leaves the tab | the card came up beside the context menu |
| following | while a card opened by a click is open, the pointer resting on another row's name shows that row's card in its place after 150 ms, as does the pointer leaving the card onto a name | Miguel's ask; the wait lets the pointer cross the next row's edge into the card, which hangs 2 px under its row |
| closing | the pointer leaving the row or the card for anything that is not a name (a control, the gap under the rows, the rest of the window), the card's ✕, a click on the card; the card follows no more, and the next card needs a click | Miguel's ask; the grace off the row is the tab card's, 500 ms |
| where | beside the rows: its left edge after the widest row's icon, name and marks, reaching 4 px (the row's right padding) back over the rows; its top at the clicked row's, raised to stay in the window. With no room for it whole beside the rows, under the row, as before. A panel tab's card stays under its tab | no name is under the card, so the pointer can follow onto any; the overlap leaves no gap (border, scrollbar, divider) between the card and the rows, so leaving the card toward the names always lands on a row |
| a double click | acts on the row (opens the session, binds the connection) and closes the card | the row acts; the card is in the way |
| siblings | the Connections rows behave as the Sessions rows; a panel tab keeps its card at a rest | one list kind, one way; a click on a tab selects it |

Miguel decided the click, the following, the closing and the card beside the longest name. Claude decided the wait, the
overlap, the fallback under the row, the double click, and which siblings take the rule.

## 3. Consequences

`SessionsSidebar` keeps whether the card follows and says where beside the rows it starts (`beside`); `TabCard` places
a row's card there when it fits, and tells who hears it when it closes, with the point the pointer left it for
(`Cards.onClose`), and the sidebar follows on when that point is on a name. Tested in
`SessionsSidebarTest` (a click shows, a rest does not, the card follows onto another name, closes off the names, a
double click closes it, a connection's name, where beside the rows), `WorkspaceTest` (a row's card tells its close, a
tab's does not; beside the rows, and under the row without room), and off screen in `SessionCardUiTest` (`:app:uiTest`:
the click, the card beside the rows with no name under it, following onto a name across the state icons, out of the
card onto a name and out of its left edge, onto the window, ✕).

## Amendments

2026-10-29 (Miguel): the card opens at a click on the row's ⓘ, no longer on its name, since on the name the first click
of a double click showed the card before the double click opened the panel; the row "a press" is added.
