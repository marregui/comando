# 40. One resource per dialog, and the sidebar's tabs in two rows

| Date | Status | Decided by |
|---|---|---|
| 2026-10-15 | accepted; its Scratchpad tab replaced by [record 49](0049-the-files-tab-shows-folders-the-user-chooses.md), its tab rows and headers by [record 70](0070-the-sidebars-tabs-in-one-row-with-a-menu-each.md) | Miguel and Claude |

## 1. Context

The session and connection dialogs repeated the sidebar: a list of resources with Add, Clone, Remove and Import
beside the form, while the sidebar's bar above each list ([record 37](0037-session-actions-act-on-the-selected-row.md))
already adds, edits, clones and removes. The sidebar's four tabs sat on one row
([record 28](0028-the-sidebar-has-three-tabs.md)), which a narrow sidebar clips. This record replaces that one-row rule.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the dialog | the form of one resource with Test, Start or Connect, Assign to panel and Save; no list, no Add, Clone, Remove or Import | the sidebar picks the resource and the action; the dialog only sets its parameters |
| what it edits | what the caller asks: a stored resource (edit, or the sidebar's double-click path), a new one (**+**), or an unsaved copy (clone); its title says which ("Session work", "New session") | one form, one meaning |
| ⌘⇧K, ⌘⇧O | "Session settings…" and "Connection settings…": the focused panel's session or connection, else a new one | the list that used to choose is gone |
| removal | only from the sidebar ([record 37](0037-session-actions-act-on-the-selected-row.md)) | one way to remove |
| the sidebar's tabs | two fixed rows: Panels and Scratchpad on top, Sessions and Connections below; the `⋯` hide button ends the top row; a click selects a tab and moves nothing; the tab indices, and the one kept across restarts, are unchanged | the order Miguel chose; documents on top, resources below; four titles fit a narrow sidebar |
| a tab's header | a bordered toggle button with an icon (Panels: text, Scratchpad: work folder, Sessions: session, Connections: database), icon and title at the leading edge, 3 px apart; the selected one in the list-selection blue | each tab's bounds show where to click; titles of one column start at the same x |

Miguel decided no list in the dialog, the two rows and their order, and visible tab bounds; Claude decided how.

## 3. Consequences

`FixedRowTabs` in `ui.kit.tabs` draws tabs in fixed rows. The one-row rule of
[record 28](0028-the-sidebar-has-three-tabs.md) is replaced.

## 4. Alternatives

| Option | Why rejected |
|---|---|
| a wrapped `JTabbedPane` | it moves the selected row next to the content, so tabs move on a click |
