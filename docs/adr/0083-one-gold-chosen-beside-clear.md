# 83. One gold, chosen beside Clear; serif headings; a turn's report is framed; tooltips and dark pages as answers

| Date | Status | Decided by |
|---|---|---|
| 2026-10-21 | accepted; the light page it left unchanged removed by [record 93](0093-comando-is-dark-throughout.md) | Miguel and Claude |

## 1. Context

comando had three golds at hue 40–44° and saturation 53–68 %: `Theme.GOLD` `#D9B44A` (About, the tab card's
Summary), `Theme.VCS_UNTRACKED` `#F0BA4C` (new files and their dots,
[record 76](0076-new-files-are-gold-and-marked-from-the-top.md)) and About's `#F0CF72`; and Claude's answers, thinking,
notes and the turn's time were all one amber, `#E0C48A`, since the amendment of 2026-10-19 to
[record 33](0033-thinking-gives-way-and-the-conclusion-is-gold.md) (`af75885`). Real gold has about the same hue (its
measured reflectance is near `#FFE39E` on screen) but is lighter and less saturated, and reads as metal through the run
from a pale highlight to a darker shade; a flat, saturated mid-tone reads as brass or copper. With the answer text
itself amber, a report had no colour to set its structure apart: headings differed from the text only by size. This
record replaces that amber and record 76's gold.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the golds | `Gold`: Treasure (`#FFEDAA` / `#E7C55A` / `#9E8230`), 18k yellow (`#F7E9BE` / `#DDC27C` / `#A08442`), Champagne (`#F1E6CA` / `#D2BE90` / `#8F7C55`), each a highlight, a body and a shade; Treasure by default | Miguel chose Treasure from a sheet of the three beside the old gold |
| where gold shows | the transcript's answers: headings (the first two levels along the ramp, highlight at the letters' tops to the shade at the baseline; the others flat), the rule under them, list marks, a quote's bar, a table's head (highlight on a band of shade); new files and their dots; the tab card's Summary; About's hairlines and links. Small text takes the body alone | a ramp on 13 px text muddies it; gold on structure, never on the running text, keeps it from distracting |
| the answer's text | warm ivory `#E2DCCD` (`Theme.TRANSCRIPT_TEXT`), for every text outside a bubble or card as before; bold a brighter `#FAF7F0`; inline code teal `#8FC9C0` on `#26282C` | the gold needs a ground of a different colour to stand out; amber text next to gold read as two golds |
| the report | the turn's last text, when only notes, hooks or thinking came after it, lies on a ground lifted 3 % towards the highlight with a 3 px bar down its left edge along the ramp; framed when its turn ends. A turn that ended on a tool call has none | the answer is found again among the turn's cards when scrolling back |
| choosing | an ingot button left of the Session tab's Clear opens a menu of the three, each with its ingot, the chosen one ticked; the choice repaints every window at once and is kept in the preferences (`theme.gold`) | Miguel chose the button's place; one home for the choice, as [record 74](0074-the-sidebars-lists-have-no-bar-of-buttons.md) asks |
| how it repaints | at once, on the choice: `Theme.gold` tells what listens (`Theme.onGold`): the transcript lays its answers out again (`TranscriptCanvas.restyle`), the tab card recolours, every dark rendered Markdown page is drawn again; each listens only while in a window. The tooltips' ground is set again; every window repaints, and the colours set from the state are set again (`render()`); the rest read `Theme.gold()` as they paint, the SVGs through FlatLaf's global colour filter | Miguel: the choice takes effect at once and the answer is drawn again |
| headings' face | the first three levels in New York bold, loaded from `/System/Library/Fonts/NewYork.ttf`; else the first installed of Georgia, Cambria, Noto Serif, DejaVu Serif, Liberation Serif; else Java's logical serif, which every installation has (`Theme.serif()`). Levels 4 to 6 stay the text's face, bold | measured 2026-10-21: macOS lists New York under no family name, so `new Font("New York")` silently became Dialog; loaded from its file it draws, but Java's semibold weight is ignored, so bold |
| missing glyphs | any run of transcript text whose font lacks a character takes Java's logical sans serif (`Theme.fallback`) for those characters only | measured: a font loaded from a file, and installed ones such as Charter, draw a missing glyph as a box or nothing (New York lacks `→`; Charter drew no `ö` or `ß`); the logical fonts map to faces covering most of Unicode on every desktop |
| the warning amber | `#EDA200` folded into the chosen gold (`Theme.warn()`): an approval waiting (⚑, the Approvals tab, the status bar), a session opening or closing, an older CLI, "● edited", the flash, the usage at 75 %; the SVGs' amber (work and skills folders, images, logs, the warning, the interrupt) mapped like their gold | Miguel, 2026-10-22: the chosen gold applies wherever comando uses gold, the sidebar's tabs and panels included; an amber beside the gold read as a second, coppery gold. A gold mark already meant "needs my attention" (record 76) |
| the tab card | laid out as a turn's report: the lifted ground and the gold bar, the status line in grey, "Summary" in the headings' serif along the ramp over a gold rule, the summary in the transcript's ivory (`GoldTitle`) | Miguel: the Haiku card in the format of a session's answer |
| tooltips | every tooltip as a report: the lifted ground, the gold bar down its left edge (`GoldBarBorder`), the transcript's ivory | Miguel, 2026-10-22: every tooltip in the gold theme |
| rendered Markdown, dark | an editor tab's or a view's page reads as an answer: ivory text, the first three headings in the serif and the gold over a gold rule, table heads gold on the shade's band, bold brighter, inline code teal (was Claude's orange), quotes beside a gold bar; a heading's character the serif lacks in the sans serif. The light page on paper keeps its colours | Miguel asked for the rendered page to look like a session's answer, unless that were a large change; the editor's own text keeps IntelliJ's syntax colours, which carry meaning, and changing them would be that large change |
| the Session tab's buttons | the ingot and Clear as icons alone, as every icon button of the window | Miguel: "no contour, just the icons" |
| unchanged | Claude's orange, links, errors, the plots' series colours, the editor's syntax colours | data and identity colours, not gold |

Miguel decided: the old gold read as copper rather than gold; treasure gold by default; a button left of the Session
tab's Clear to change it and paint again in another; the final report reads as a report, with nothing distracting; New
York for headings, with a fallback font on every computer that installs comando. Claude decided the three golds and
their ramp, the ivory text, which elements take gold, the report's frame, and where the choice is kept.

## 3. Consequences

The amber of the transcript (2026-10-19) and the gold of record 76 are replaced. Changing the gold clears the
transcript's selection, as a zoom change does, since every layout is made again.

Tested in:

- `TranscriptCanvasTest`: the colours by style, the report, a repaint in another gold; both mutation-checked;
- `SerifTest`: the chain without New York, New York on macOS;
- `RichFallbackTest`: a lacked character in the fallback, mutation-checked;
- `GildedIconsTest`: the SVGs' gold and amber;
- the off-screen `UiKeysTest`: the button left of Clear, the menu, the preference, the window's transcript laid out
  again in the gold chosen with no other event, mutation-checked; a new window in the gold kept;
- `WorkspaceTest`: a card shown recolours, mutation-checked; its heading in the serif and gold, its summary ivory;
- `DarkPageTest`: the dark page's rules, the heading fallback;
- `TooltipGoldTest`: the bar, the ground, the listeners;
- the off-screen `DarkPageGoldUiTest`: a page in a window drawn again at once, none out of it; mutation-checked.
