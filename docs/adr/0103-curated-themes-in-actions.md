# 103. Curated themes in Actions ▸ Theme; surfaces follow the ground

| Date | Status | Decided by |
|---|---|---|
| 2026-10-27 | accepted; the swatch's place replaced by [record 104](0104-the-theme-beside-new-panel.md); its themes moved out of Actions by [record 114](0114-help-menu-and-corner-buttons.md); amended 2026-10-30 (see Amendments) | Miguel and Claude |

## 1. Context

Records 96 and 99 let the tint (six) and the ground (seven) be chosen apart: 42 pairs, most never looked at. Decision D3
of the plan of 2026-10-27: curated themes are the product; the two choosers stay until Miguel has chosen among them.
The theme sheet showed cards, borders and the prompt's bubble in one grey on every ground.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the themes | six, each a ground and the tint made for it (`NamedTheme`): Graphite and gold (the default), Espresso and champagne, Aubergine and yellow gold, Midnight and electric blue, Evergreen and tropical forest, Slate and silver | one theme per ground but Onyx; each looked at as a whole window |
| where | Actions ▸ Theme, first of the three submenus, each with its swatch (the ground with its tint's bar), the theme chosen ticked when the ground and tint chosen make one; choosing one keeps both choices | D3 |
| the two choosers | retired: Actions holds Theme alone, and one swatch beside Clear (the theme's ground with its tint's bar) opens the six; Miguel chose the set on 2026-10-27 ("I like the 6 of them") | D3 |
| a ground and tint kept from before | the theme they make; else the theme on the ground kept; else the theme of the tint kept (the gold kept before tints too); else Graphite and gold. The preferences keep `theme.ground` and `theme.tint` | a window opens in a theme of the set |
| running and waiting | the spinner's head is the tint's body and its bars fade into the ground; the hourglass is drawn in the tint; both were blue, which now means a link alone | records 100 and 101: activity is "where I am" |
| surfaces | a card, its border, the prompt's bubble, inline code's ground in the transcript and a table's rules are laid over the ground chosen (`OnGround`), read at each paint, so a background or border made once follows another ground | tier 1 of record 100: surfaces belong to the theme |

Miguel decided D3 and chose the set. Claude chose the six pairs.

## 3. Consequences

Supersedes the choosers of [record 96](0096-a-tint-chosen-in-actions.md) and
[record 99](0099-a-ground-chosen-beside-the-tint.md): the tint and the ground are no longer chosen apart. On Graphite
the surfaces are #2D2D2D, #3C3C3C and #2E3A4A, as before within a few levels.

Tested in `NamedThemeTest` (a pair kept from before maps to the closest theme), `MenusTest` (the submenu and its
swatches), `OnGroundTest` (a border and a background made once take another ground; every surface stands off every
ground) and `UiKeysTest` (Actions ▸ Theme and the swatch beside Clear set and keep a theme; every panel, the
transcript and an editor take it, the editor keeping its zoom; no Tint or Ground submenu; the gold kept before tints
opens in Espresso and champagne); `SpinnerIconTest` (the head in each theme's tint, no bar in the old blue;
mutation-checked with the old shades put back) and its hourglass check. Sheets: `app/build/snapshots/themes.png` and
`02f2-themes.png` of `:app:snapshot`, the whole window in each theme.

## Amendments

2026-10-30: a theme chosen is applied in one pass and fades in. Before, the ground and then the tint each set the look
and feel up again and ran every tint listener, so the window was rebuilt twice and could show the new ground in the old
tint; and the change was instant, which left the eye adapted to the old ground's hue. Now `Theme.choose` sets both and
rebuilds once, and `LayoutKeeper.fadeTo` lays a picture of the windows as they were over them and fades it out over
500 ms (first FlatLaf's `FlatAnimatedLafChange`, 160 ms by default; replaced the same day, below); the theme chosen already is applied without a fade, and
the theme restored at start is not faded. Miguel asked ("a hue or a cloud in the user's eyesight when the transition is
done"); tested in `ThemeChoiceTest` and `ThemeFadeUiTest`.

2026-10-30, later: the fade is comando's own (`ThemeFade`). FlatLaf's crossed from the picture of the theme before to
a second picture taken at once after the change, before the window had finished laying out and painting in the new
theme; when the pictures went, what it finished afterwards appeared with a jump, so the old ground seemed to linger and
then repaint (Miguel: "the background of the previous theme seems to linger and then repaints to what should be";
measured off screen: 2.7 % of the pixels differed between that second picture and the window after it, the panel's
header and the plan's usage among them). Now only the picture of the theme before is taken; it is laid over each
window, the theme is applied underneath, and it fades out over the live window, which repaints in the new theme as it
would without a fade. Tested in `ThemeFadeUiTest`: halfway through, one picture lies over the window and the window
under it paints as it does after the fade, within 0.5 % of the pixels, to Paper and ochre and back.

2026-10-30, later still: the fade blends two pictures. Fading the old picture over the live window repainted the whole
window at every frame (8 to 15 ms each in a nearly empty window, measured off screen, more with a transcript), and the
change between dark and light flickered (Miguel: "feels as if the electric current had a micro interruption"). Now the
old picture covers the window, opaque, while the theme is applied; two turns of the event queue later, once the window
has done what the change left for later, a picture of it in the new theme is taken and the old one fades into it; each
frame draws the two pictures alone (0.2 to 6.5 ms). The pictures are images in memory at the screen's scale, which the
screen cannot discard mid-fade as it can a volatile one. macOS's title bars, which no picture covers, change mode
halfway through the fade rather than at its start. Tested in `ThemeFadeUiTest`: halfway, one opaque picture lies over
the window, and the picture of the new theme is the window as it paints after the fade, within 0.5 % of the pixels.
