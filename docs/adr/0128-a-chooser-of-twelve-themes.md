# 128. A chooser of twelve themes: six dark, their six light twins, drawn as cards

| Date | Status | Decided by |
|---|---|---|
| 2026-10-30 | accepted; built | Miguel and Claude |

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
