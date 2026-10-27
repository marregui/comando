# 102. One type scale and a 4-point grid

| Date | Status | Decided by |
|---|---|---|
| 2026-10-27 | accepted | Miguel and Claude |

## 1. Context

Sizes of text were set widget by widget as offsets from the look and feel's 13 pt: −2.5 for the sidebar's group
headers, −1 for hints, chips, a fact's label and line numbers, +0.5 for the tab card's topic, ×1.55 and ×1.25 for its
headings, ×1.45, ×1.25 and ×1.1 for the transcript's headings. Insets were 5, 6, 7 and 10 pixels where they were not
`Theme.GAP` (6) or `Theme.PAD` (10). Miguel agreed to one scale and one grid (phase 3 of the plan of 2026-10-27, record
100).

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| sizes of text | six steps, `Theme.TextSize`: caption 11, small 12, body 13, subtitle 15, title 18, display 22; `Theme.font(f, step)` takes `f` as its context's body, so a zoomed transcript or editor keeps the steps from its own size | a step apart reads as a difference; half a point does not |
| where each sits | group headers caption; hints, chips, a fact's label, line numbers and code in the transcript small; the tab card's title title and its Summary subtitle; headings of answers and pages title, subtitle, then body in the serif, which sets the third level apart from the fourth | the closest step to each former size |
| spacing | a 4-point grid, `Theme.SPACE_XS` 4, `SPACE_S` 8, `SPACE_M` 12, `SPACE_L` 16; `GAP` is 8, `PAD` 12; 1 to 3 pixels stay where they nudge a line or an icon into place | one rhythm across cards, dialogs and bars |
| rows | a table's row is its text with 8 pixels round it, up to the grid (`Theme.rowHeight`): 24 for the 13 pt font | rows on the grid |
| exceptions | About keeps its own faces and sizes (record 60); a field's hint stays 6 pixels in, where FlatLaf's field puts its text | their own designs |

Miguel agreed to the phase. Claude chose the steps' assignments.

## 3. Consequences

The tab card's title is 18 pt (was 20), its Summary 15 (was 16); the transcript's third-level heading is the text's size
in the serif (was ×1.1); cards, dialogs and bars gained 2 pixels of padding, the status bar's gaps 4.

`TypeAndSpacingRulesTest` scans the ui module's sources: no line that makes a font adds to, takes from or multiplies a
size, no `deriveFont` names a size, no inset is 5, 6, 7, 9, 10 or 11. Mutation-checked: an offset of −1 and an inset of
7 planted in `AgentStrip` fail it. Before and after: the `:app:snapshot` pictures.
