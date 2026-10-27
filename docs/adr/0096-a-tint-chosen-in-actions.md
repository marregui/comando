# 96. A tint, chosen in Actions or beside Clear; the ground stays the transcript's; attention stays gold

| Date | Status | Decided by |
|---|---|---|
| 2026-10-27 | accepted; the tint chosen apart replaced by [record 103](0103-curated-themes-in-actions.md) | Miguel and Claude |

## 1. Context

[Record 83](0083-one-gold-chosen-beside-clear.md) made one gold the colour of the transcript's structure (headings,
list marks, table heads, the report's bar), of tooltips, the tab card, new files and About's accents, and folded the
warning amber into it. The choice was three golds, from an ingot button beside the Session tab's Clear. Tooltips, the
tab card and the report lay on a ground lifted 3 % towards the gold. An inventory of the type and colours on
2026-10-27 found the button changing the look of every window from a control in one tab, and five divergences: heading
sizes differed between the transcript (×1.45, ×1.25, ×1.1) and rendered pages (+6, +4, +2 pt); the answer's bold and
inline code colours were literals in two files; rendered pages set `code` and `blockquote` twice, the first rule of each
dead; find matches were a fixed ochre that read as a second gold beside Champagne; About kept its colours as literals.
This record amends record 83's "choosing", "the report", "the tab card", "tooltips" and "the warning amber" rows.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what is chosen | a tint (`Tint`): highlight, body and shade, as record 83's golds. Six: Treasure gold (default), 18k yellow gold, Champagne gold, Silver (`#F4F6F9` / `#C3CAD3` / `#7B8591`), Tropical forest (`#CFF5DC` / `#62CC94` / `#339966`), Electric blue (`#D6ECFF` / `#63B5FF` / `#3A80E0`). On the transcript's ground each body reads above 8:1 and each shade above 4.5:1 | Miguel: some users want silver, tropical forest or electric blue; the gold button becomes a tint chooser. Claude chose the three new tints' values |
| where it applies | everywhere record 83 put the gold, once chosen: headings, rules, list marks, quote bars, table heads, the report's bar, tooltips' bar, the tab card's title and Summary, rendered pages, new files and their dots, About's hairlines and links | Miguel: the general theme applies everywhere once selected |
| where it is chosen | Actions ▸ Tint, the bar's one submenu: the six, each with a swatch of its ramp, the one chosen ticked; also in the palette. And beside the Session tab's Clear, where record 83's ingot was: a button showing a swatch of the tint chosen, whose menu holds the same six, ticked the same; a choice in either ticks both | Miguel: the choice changes several kinds of component, so it belongs in Actions; he chose the submenu over six flat items; then asked for the button back beside Clear as well, the quicker way to change the tint |
| kept | the preference `theme.tint`; a gold kept before under `theme.gold` is read once and replaced | the three golds keep their names |
| the ground | tooltips, the tab card and the report lie on the transcript's ground, `#121212`, with no lift towards the tint; the tint shows in the bar, the letters and the marks | Miguel: the ground of these components black |
| attention | `Theme.ATTENTION`, Treasure gold's body, in every tint: an approval waiting, a session opening or closing, an older CLI, "● edited", the flash, the usage at 75 %, the SVGs' amber (work and skills folders, images, logs, the warning, the interrupt). The SVGs' gold (a new file's dot) takes the tint | Miguel: a status colour, fixed as danger is; in silver it would read as muted text, in electric blue as a link |
| what a theme is | the design tokens in seven layers: grounds, text, the tint, status (accent, ok, danger, attention, thinking, Claude), data (git states, diff, plot series, find matches), type (faces, the size scale) and space. Only the tint is chosen; status and data colours are the same in every tint | Miguel accepted the definition |
| the divergences | one heading scale for the transcript and rendered pages (`Theme.HEADING_SCALE`, ×1.45, ×1.25, ×1.1); the answer's bold, inline code, code grounds, selection, table rule and About's palette as `Theme` tokens; one rule each for `code` and `blockquote`; find matches violet (`#4A3560`, current `#6F4C91`), a hue no tint, selection, diff or attention uses | Miguel accepted Claude's recommendations |
| unchanged | the faces and record 83's ivory text, Claude's orange, links, errors, plot series, the editor's syntax colours | data and identity colours |

## 3. Consequences

Record 83's three golds remain the first three tints; their names, ramp and roles are unchanged. A rendered page's
headings move by up to 1 pt (h1 19, h2 16, h3 14 at 13 pt text, were 19, 17, 15). With Tropical forest a new file in
the Files tab (tint) and an added one (`VCS_ADDED`, `#629755`) are both green, told apart by lightness; with Electric
blue the headings and links are both blue, told apart by the serif and the underline on hover.

Tested in:

- `TooltipTintTest`: the bar in any tint, the ground not changed by a choice, every tint's contrast, attention fixed;
- `GildedIconsTest`: the SVGs' gold takes the tint, their amber stays attention's gold;
- `TranscriptCanvasTest`: the report on the transcript's ground and repainted in another tint, find matches and the
  heading scale;
- `TintSheetTest`: one answer in every tint, painted to `ui/build/snapshots/tints.png`, each with its own colours only;
- `DarkPageTest`: one rule each for code and quotes, the heading scale, a page in another tint;
- `MenusTest`: Actions ▸ Tint after the palette, a swatch for each item and the submenu;
- the off-screen `UiKeysTest`: the submenu ticked on the default, a choice ticks only itself, is kept under
  `theme.tint` and repaints the window's transcript at once; the swatch left of Clear opens the same six, its choice
  ticks Actions ▸ Tint too; a gold kept under `theme.gold` is read;
- the off-screen `DarkPageTintUiTest`: a page in a window drawn again at once in the tint chosen.
