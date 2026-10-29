# 98. One ground for every panel; the sidebar's look: groups as headers, one colour for names

| Date | Status | Decided by |
|---|---|---|
| 2026-10-27 | accepted; the folder groups' headers replaced by rows by [record 118](0118-brain-and-kit-are-rows-each-one-unit.md); its folder icons in a colour per kind replaced by [record 100](0100-a-theme-is-four-tiers-of-tokens.md) | Miguel and Claude |

## 1. Context

The sidebar (Sessions, Connections, Files) had a layout and no look of its own: FlatLaf's grey, its cool white text,
row icons drawn as grey folders with a small badge in Claude Code's colours (a green pencil, a blue book, a violet chip,
git's orange diamond), and tab icons in orange, blue and green. On 2026-10-27 Miguel found it quiet, which he wants to
keep, but neither premium nor in the tint chosen ([record 96](0096-a-tint-chosen-in-actions.md)), and nothing told that
Scratchpad, KB and Memory are together the brain, which he did not want nested in a brain folder.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| ground | one colour for every panel, set once (`Theme.ground()`, `#121212` by default ([record 99](0099-a-ground-chosen-beside-the-tint.md)), the transcript's): `Theme.install` gives it to the look and feel as its background and as lists', trees', tables', text areas' and viewports' backgrounds; the editors' theme (text, gutter, fold column) and the terminal read it. Cards, bubbles and fields keep their own | Miguel: "set black in one place and that sets the background colour for the panels, all of them" |
| names | every name in the transcript's ivory, `#E2DCCD`, the selected one in the tint's highlight; details (a folder's path) muted. In the Files tab no name takes git's colour: how a file differs from `HEAD` is said by the marks after the names leading to it ([record 97](0097-a-changed-files-trail-of-arrows.md)), and only a file in conflict is red, as an error | Miguel: folder names had no consistent colour and file names' colours no clear meaning; he chose one colour with the marks carrying git |
| groups | a folder's `group` (in `folders.json`, the kit's `comando-kit.json` and the folders window): the Files tab shows the group's name above the first of a run of folders in it, in spaced capitals in the tint's body, with a hairline of the tint's shade on to the edge; no folder nests, nothing opens or closes. The kit sets Brain on Scratchpad, KB and Memory and Kit on the kit's folder. "Without a session" is the same kind of header | Miguel chose a header from config over one read from the shared repository (the kit's would read `.claude`) and over a hairline alone |
| icons | the sidebar's tabs keep their icons (a speech bubble in Claude's orange, a database in the accent's blue, a folder in green), an outline 1.2 px wide in one colour; the folders' icons are drawn the same way (`icons/sidebar/`), one colour per kind: folder grey, scratchpad (a sheet and a pencil) green, knowledge (an open book) blue, memory (a bookmark) violet, skills (a spark) and work (a case) in attention's gold, plugin teal. A repository's folder carries git's mark in git's colours | Miguel preferred the tabs' icons to a family in the tint, and asked for the others to follow them |
| selection | a band of the tint's shade (22 % over the ground), the name in the tint's highlight, and the report's bar, 3 px along the tint's ramp, down the left edge; in the lists and the tree alike | Miguel chose the tint on the headers and the selection, not on every name |
| where else | the folders window shows the same icons and has a Group field | one icon per kind of folder in every place |

Miguel decided one ground set once, the groups as headers from config, the tabs' icons as the style, one colour for
names, and the tint on headers and the selection only. Claude drew the folders' icons, chose their colours, and decided
the header's type, the hairline, the band and the bar.

## 3. Consequences

`FolderRoot` gains `group` (empty when none; a `folders.json` without it reads as before, and an older comando ignores
it). A header's row is taller by the header's space; the tree centres that row's open-and-close mark on the row below
the header.

Tested in:

- `SidebarLookTest`: one header over each group's first folder and none over the others, the header's space, the
  ground, the bar and the headers in three tints;
- `FilesTabTest`: no name takes git's colour, a file in conflict is red;
- `SessionsSidebarTest`: the Files tab's own icon;
- `ResourceKindTest`, `StoreTest`: a folder's group through the folders window's fields and `folders.json`;
- the off-screen `TreeClicksUiTest`: a kit's groups reach the Files tab; the sidebar painted in three tints to
  `app/build/snapshots/sidebar.png`.
