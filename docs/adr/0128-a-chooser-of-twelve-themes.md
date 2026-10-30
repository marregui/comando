# 128. A chooser of twelve themes: six dark, their six light twins, drawn as cards

| Date | Status | Decided by |
|---|---|---|
| 2026-10-30 | accepted; built; amended 2026-10-30 (see Amendments) | Miguel and Claude |

## 1. Context

[Record 127](0127-light-themes.md) added one light theme, Paper and ochre, to the six dark ones, listed in the swatch's
menu by name. Miguel, 2026-10-30: "I absolutely love the Paper and ochre theme ... I would like some kind of party when
it comes to the number of light and dark themes in the choose, and they could be shown in a more clever way than the
list. possibly a custom dialog." Asked, he chose light twins of the dark themes, a dialog of previews, and no live
preview of the window on hover.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| parity | six light themes, one twin per dark theme, its tint the same family drawn deep: Paper and ochre (Graphite and gold), Cream and champagne bronze (Espresso and champagne), Lilac and deep gold (Aubergine and yellow gold), Ice and navy (Midnight and electric blue), Mint and forest (Evergreen and tropical forest), Fog and slate (Slate and silver) | Miguel: light twins; each passes every floor of `ThemeRules`, and every light tint passes them on every light ground |
| the pairing | a theme file's optional `twin` names the theme of the other mode it pairs with | the chooser lays the pairs out from the files, the user's included |
| the chooser | the swatch opens a window, Theme, of cards: Dark and Light in two columns, each light theme on the row of the dark one it is the twin of, then the themes no row claimed, a dark one without a twin with its light side empty, a light one without a dark side; each card draws its theme small in its own colours (`ThemePreview`: the ground, the report's bar and a heading in the tint, a line of code in its syntax colours, a card with muted text) over its name; the theme chosen has a ring in its tint and its name in bold; a click or ⏎ chooses and closes, Esc or the window losing the focus closes; the cards scroll past 85 % of the screen's height | Miguel: a custom dialog rather than a list; a card shows what the theme looks like where a name does not |
| live preview | none: the window behind changes when a theme is chosen, with the fade of record 103 | Miguel |

## 3. Consequences

The swatch's menu is gone; `ThemeChooser` (ui, panels) opens from `ThemeButton`, and the palette keeps a command per
theme. `ThemePreview` is in the theme kit, which alone makes colours. `ThemeSpec` reads `twin`; `NamedTheme.twin`
holds it. Tested in `ThemeChooserTest` (six rows of twins; a theme without a twin on a row of its own),
`ThemePreviewTest` (a light theme drawn in its own colours while a dark one is chosen), `NamedThemeTest` (six dark, six
light, each light one the twin of one dark one, in their order) and `UiKeysTest` (the chooser's cards in twin order,
the theme chosen marked, a click chooses and closes the window).

## Amendments

2026-10-30: the chooser is a popover under the swatch with a Dark | Light toggle. Miguel: "the dialog should show
closer to the button that triggered its open, otherwise I have to travel to it", and only one mode's themes shown at a
time, since two columns grow only downwards and would not hold many palettes (twelve themes made the window 860 px
tall). Now the window opens under the swatch, its right edge on the swatch's, kept on the swatch's screen (above it
when there is no room below; on no screen, as an off-screen test's window is, left where it falls); the toggle starts
at the mode of the theme chosen and only filters; the cards lie three to a row, scrolling past four rows, each light
theme in the slot of the dark one it is the twin of, so the toggle turns each card into its twin in place; the themes
no twin pairs follow the pairs in their mode. The arrows move between cards, ⏎ chooses, D and L flip the toggle, Esc
or a click elsewhere closes. Miguel agreed the design as proposed. Tested in `ThemeChooserTest` (slots shared by twins,
the unpaired after, the placement) and `UiKeysTest` (under the swatch and right-aligned, the dark themes first with the
chosen one marked, the toggle showing each slot's twin without changing the theme, a click choosing and closing).

2026-10-30, later: the toggle turns the whole window. Miguel: "the switch should actually repaint the whole app on
toggle, and the selection is what you do now". Flipping it chooses the theme of the other mode in the slot of the theme
chosen, its twin (the mode's first theme when the theme chosen is in no slot or the slot is empty), with the fade; the
chooser stays open on that mode's cards, the twin marked, and fades with the window. A card still chooses and closes.
Tested in `ThemeChooserTest` (the twin both ways, the fallbacks) and `UiKeysTest` (Light turns the window to Paper and
ochre with the chooser open and Paper and ochre marked; Dark turns it back).

2026-10-30, later still: a card's theme is the window's at once, and the chooser stays open. Miguel: "when you click on
one theme, the app adopts it while the dialog remains open, I will close it when I am done trying the themes, the last
selection is the selection". A click or ⏎ on a card chooses its theme, with the fade, and the cards are drawn again
with the ring on it; Esc, a click elsewhere, or the swatch again closes the chooser, the theme last chosen kept. The
swatch's click that closed the chooser by taking its focus does not open it again (a click within 300 ms of that close).
Tested in `UiKeysTest` (a card chosen: the window takes it, the chooser open with the ring on it; Esc closes it, the
theme kept) and `ThemeFadeUiTest` (each pick leaves the chooser open, then closes it with Esc).

2026-10-30, the final touches: a close button, the switch and the button's icon. Miguel: "could it not have a close
button? and could the toggle be more imaginative and stylistic? could the icon that trigger the dialog feel more
luxurious". The chooser has an ✕ at its top right, as a card has. From a sheet of three icons and three toggles drawn
on Graphite and gold and on Paper and ochre, Miguel agreed the recommended two: the icon is a gilded split
(`GildedSplitIcon`), a rounded square in a rim along the tint's ramp with a glint, split corner to corner between the
ground chosen and its twin's, the dark one above, 16 px as the row's other buttons; the toggle is a sliding pill
(`ModeSwitch`), a track with a knob of polished metal that slides in 160 ms, eased, a moon on it in dark and a sun in
light, the mode's label lit in the tint; the knob slides first and the window turns once it is there, so the slide is
seen before the fade covers it. A click on the pill flips it, a click on a label goes to its mode, D and L as before.
The swatch icons of the palette's theme commands stay `GroundIcon`, each naming its own theme. Tested in `UiKeysTest`
(the switch starts at the mode chosen, L slides it and turns the window to the twin, D back) and `ThemeFadeUiTest` (the
switch's fade ends on the window as it then paints).

