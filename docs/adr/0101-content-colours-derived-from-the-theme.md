# 101. The content's colours are derived from the tint and the ground

| Date | Status | Decided by |
|---|---|---|
| 2026-10-27 | accepted | Miguel and Claude |

## 1. Context

[Record 100](0100-a-theme-is-four-tiers-of-tokens.md) defined a theme as four tiers of tokens and left the fourth, the
content palettes, to a record of its own. Until this one, the editors took IntelliJ's Dark colours from an XML file, the
terminal JediTerm's xterm colours, diffs and plots fixed tokens (`DIFF_*`, `SERIES`), and code in answers a fixed teal;
none of them changed with the tint or the ground. Miguel agreed to derive them (decision D2 of the plan of
2026-10-27).

## 2. Decision

`ContentPalette`, in `ui.kit.theme`, is made once per tint and ground; `Theme.content()` is the one chosen. Its colours
are computed in OKLCH, a perceptual space in which colours of one lightness and chroma weigh the same whatever their
hue.

| Content | Rule | Why |
|---|---|---|
| syntax | keywords and markup tags in the tint's body; identifiers, functions and operators in the ink; comments muted, documentation in italic, its tags the muted grey half-way to the tint; errors red | the tint marks structure (record 100); the ink is the text's |
| syntax categories | annotation, number, string, regular expression, type, variable: one hue each at lightness 0.76 and chroma 0.11, 60° apart, on a wheel turned at most 30° so that the tint's hue falls midway between two of them; a tint without a hue (silver) leaves the wheel where custom puts it | each category within 30° of its customary hue (strings green, numbers orange), none nearer the tint than 30°; the golds lie near a midpoint, so they share one palette but for the keywords |
| the editor around the text | the ground; the selection the tint's shade at 45 % over it; the current line, guides, line numbers, a matched bracket and folds mixed from the ink or the tint's shade over the ground; links `Theme.LINK`; find marks `Theme.FIND_MATCH` | the selection is "where I am" (D5) |
| terminal | sixteen colours, the names' hues kept (red is red in every tint); black, white and the greys mixed from the ink over the ground; its text the ink | programs mean a colour by its name |
| diffs | green and red at 30 % over the ground for a line, 48 % for a changed word | they follow the ground, the text on them stays legible |
| plots | the tint's body first, then seven hues 45° apart from it | a plot's first series is the theme's |
| code in answers and rendered pages | inline code a quiet teal from the regular expression's hue on a ground 8 % lighter than the panels'; a code block's ground 5 % lighter | the code ground follows the ground chosen |

Every editor shown, the terminal, rendered pages and the transcript take the new palette when another tint or ground is
chosen; an editor keeps its font and zoom. The editors' theme is built in code (`EditorThemes`); the XML file is gone.

Miguel decided D2. Claude decided the wheel, its lightness and chroma, and the mixes.

## 3. Consequences

`Theme.DIFF_*`, `Theme.SERIES`, `Theme.ANSWER_CODE`, `ANSWER_CODE_GROUND` and `CODE_GROUND` are gone. `Theme.INFO`
stays a status colour of the first tier. Record 93 stands: comando is dark throughout.

Tested in `ContentPaletteTest` on every tint and ground (42 pairs): contrast floors for each syntax colour, selected
text, diff lines and words, inline code, the terminal's colours; each category apart from the others, the keywords and
the ink in OKLab, near its customary hue and away from the tint; the golds' categories within 0.02 of each other.
Mutation-checked: syntax at lightness 0.55 fails it, and so does a wheel that does not turn. `EditorsTest` reads the
editors' colours from the palette and a changed tint; `UiKeysTest` checks that an editor shown takes a new ground's
selection and string colour and keeps its zoom. The sheet `ui/build/snapshots/content.png` (`ContentSheetTest`) paints
every tint and every ground: code, the terminal's sixteen colours, diff lines, inline code and the plot series.
