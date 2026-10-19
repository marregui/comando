# 70. The sidebar's tabs in one row, with a menu each

| Date | Status | Decided by |
|---|---|---|
| 2026-10-19 | proposed; replaces the tab rows and headers of [record 40](0040-one-resource-per-dialog-and-two-tab-rows.md) | Miguel (one row of icons drawn as tabs, packed, the order, a menu on a right click, tooltips that are short and useful), Claude (how: the header's look, each menu's items, the tooltip bound, the saved tab) |

## 1. Context

Record 40 put the four tabs in two fixed rows of bordered toggle buttons, Panels and Files over Sessions and
Connections, because four titles did not fit one row of a 260 px sidebar. Two rows cost a row of height above every
list, and the buttons did not read as tabs. No header had a menu: a tab's actions sat only in its bar of buttons and
in the menu bar. A first version of this record kept every title in the row, which needed a 340 px sidebar; Miguel
chose icons alone, named by their tooltips.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the row | one row: Sessions, Connections, Panels, Files, then the `⋯` hide button at its end; a click selects a tab and moves nothing | Miguel: one row, in this order |
| a header | FlatLaf's tab button with the tab's icon alone, side by side with no gap, 7 px side margins; the selected one underlined in the accent colour; the accessible name is the tab's title | Miguel: they look like tabs, packed; the sidebar keeps its 260 px default |
| a header's tooltip | the title, then what the tab holds and what a click or right click does, in one line: "Sessions: Claude Code sessions; right-click to add, import or sort" | Miguel: the tooltip names the tab |
| right click | selects the tab, then a menu: its own actions, the window's commands for it with their keys, a separator, Hide the sidebar (⌘B) | Miguel: actions relevant to the tab |
| Sessions menu | New session…, Import stored conversations…, Sort: running first, then by name; the window's Session settings… | the bar's add, import and sort |
| Connections menu | New connection…; the window's Connection settings…, and starting or stopping comando's MCP server | the bar's add; what connects to the outside |
| Panels menu | the window's New panel and Reopen closed panel | what the menu bar offers for panels |
| Files menu | Close every folder; the window's Files tab folders… and Changes | the tree, its folders, and the git changes ([record 68](0068-git-changes-and-a-side-by-side-diff.md)) |
| every tooltip | one line of at most 80 characters, imperative ("Save the form (⏎)"), no closing period: what the control does and one fact it does not show (its key, where the result goes, what it costs); a tooltip reporting live state (a path, a status, plan usage) keeps its data | Miguel: useful information, short and brief |
| the tab kept across restarts | saved under a new key, `sidebar.front`, in the new order; the old key `sidebar.tab` is read once, in the old order, so the tab in front before the change stays in front | the order changed the indices |

## 3. Consequences

`TabStrip` in `ui.kit.tabs` replaces `FixedRowTabs`. The menu bar's tooltips, which ran to three sentences, are one
line each. `UiKeysTest` holds every tooltip in the window and its menu bar to one line of at most 80 characters; the
tooltips of dialogs, approval cards and session rows are held to it by review.
