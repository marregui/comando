# 28. The sidebar has three tabs

| Date | Status | Decided by |
|---|---|---|
| 2026-10-14 | accepted; its reading rules replaced by [record 29](0029-the-scratchpad-is-a-navigator.md), its one-row strip by [record 40](0040-one-resource-per-dialog-and-two-tab-rows.md) | Miguel and Claude |

## 1. Context

The sidebar stacked Sessions over Connections, and the scratchpad browser opened as a view in the side pane on the
right, gone with the next view. The scratchpad was to stay in view, on the same side as sessions and connections.

## 2. Decision

The left column is three tabs on one row: Sessions, Connections, Scratchpad. The `⋯` button that hides the column ends
the tab strip, so it shows on every tab; the column still collapses to its rail.

| Question | Rule | Why |
|---|---|---|
| how the tree arrives | the tools adapter reads the folder on a virtual thread and sends `ScratchpadLoaded` only when the tree changed; the reducer turns it into `UiPort.showScratchpad` | no state copy carries a tree no reducer decision needs; a quiet folder costs no intents |
| when it is read | the tab coming to the front, the sidebar shown with it in front, every 10 s while it shows, the refresh button, Tools ▸ Show the scratchpad | it shows what Claude wrote without a manual refresh, and reads nothing while hidden |
| a refresh | keeps opened folders and the selection, matched by labels | a tree that collapses every 10 s cannot be read |
| the strip | one row, never wrapped | a wrapped strip moves the selected tab's row, so tabs jump on a click |

Miguel decided the three tabs and the scratchpad in the sidebar; Claude decided the refresh and the data path.

## 3. Consequences

The side pane no longer shows the scratchpad. A folder that cannot be read shows as a one-line tree. The tab in front
is kept across restarts with the sidebar's visibility.
