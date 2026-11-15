# 160. Each panel keeps its lower tab, and a request no longer opens a document's lower area for good

| Date | Status | Decided by |
|---|---|---|
| 2026-11-15 | accepted; built | Miguel and Claude |

## 1. Context

The lower tab was one value for the window (`AppState.lowerTab`): Approvals, selected for a request on one panel,
stayed selected on every panel the user went to next. A document no session works in hides the lower area until
it is asked for, and the window counted a request arriving as asking (`lowerAsked` grew with the approvals). A request
arriving while the user read a document therefore opened that document's lower area for the rest of the window's
life; answering it took the user to the asking session's panel, and back on the document the lower area showed at
the last height shown. Miguel, 2026-11-15: the tab selected for a panel is application state.

## 2. Decision

1. `AppState` keeps `lowerTab`, the focused panel's tab, and `panelTabs`, every other open panel's tab by id. Focusing
   a panel stores the tab of the one it leaves and selects its own; one with no tab, or whose tab has nothing to show
   (Approvals with nothing waiting, a view closed), selects Session. A closed panel's entry is dropped.
2. A request selects Approvals on the panel in front and on the asking session's panels (`withTabFor`); answering the
   last one from Approvals still brings the asking session's panel forward on Session, as before.
3. A document's lower area is asked for only by a tab chosen while the same document stays in front (⌃1–⌃4, a tab
   chosen): a tab that changes with the focus (a side panel focused, another panel in front) is that panel's own,
   and one that changes as a request comes or goes is the request's. A waiting request shows the document's lower area
   only while Approvals is its tab and a request waits.
4. Once no request waits, no panel keeps Approvals as its stored tab: a later request selects it afresh, on the panels
   rule 2 names.

## 3. Consequences

- The intent log's `@state` line carries `panelTabs`; logs written before read it as empty.
- Per-panel tabs are not saved across restarts; every panel starts on Session.
