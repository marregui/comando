# 115. The lower divider is each panel's

| Date | Status | Decided by |
|---|---|---|
| 2026-10-28 | accepted | Miguel and Claude |

## 1. Context

The divider between the panels and the lower panel was one for the window. Miguel: the height should be part of the
panel's state, so that moving between session tabs or file tabs readjusts it.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| whose | the panel in front's: every move of the divider (a drag, an arrangement, the window's resize) is recorded for it | the panel is what the user arranged the height for |
| a panel coming to the front | the divider goes where it was when that panel was last in front; a panel never in front keeps it as it is | a new panel starts from the height in use |
| where it is kept | the UI's layout, not `AppState`: `settings.json` keys `divider.lower.panel.<id>`, written with the window's layout, for the panels open or reopenable (`WindowLayout.panelDividers`) | a pixel position is the window's, as the other dividers are (record 106); a panel gone for good takes its key with it |

Miguel decided the divider per panel; Claude decided where it is kept and the rule for a new panel.

## 3. Consequences

`WindowLayoutTest` (kept, replaced on save) and off screen `LowerDividerUiTest` (`:app:uiTest`: two panels, each brings
its divider back; mutation-checked with the restore removed). The window's own `divider.lower` stays, as the height
at start for a panel with none.
