# 103. Curated themes in Actions ▸ Theme; surfaces follow the ground

| Date | Status | Decided by |
|---|---|---|
| 2026-10-27 | accepted | Miguel and Claude |

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
| surfaces | a card, its border, the prompt's bubble, inline code's ground in the transcript and a table's rules are laid over the ground chosen (`OnGround`), read at each paint, so a background or border made once follows another ground | tier 1 of record 100: surfaces belong to the theme |

Miguel decided D3 and chose the set. Claude chose the six pairs.

## 3. Consequences

Supersedes the choosers of [record 96](0096-a-tint-chosen-in-actions.md) and
[record 99](0099-a-ground-chosen-beside-the-tint.md): the tint and the ground are no longer chosen apart. On Graphite the
surfaces are #2D2D2D, #3C3C3C and #2E3A4A, as before within a few levels.

Tested in `NamedThemeTest`, `MenusTest` (the submenu and its swatches), `OnGroundTest` (a border and a background made
once take another ground; every surface stands off every ground), `NamedThemeTest` (a pair kept from before maps to
the closest theme) and `UiKeysTest` (Actions ▸ Theme and the swatch beside Clear set and keep a theme; every panel, the
transcript and an editor take it, the editor keeping its zoom; no Tint or Ground submenu; the gold kept before tints
opens in Espresso and champagne). Sheets: `app/build/snapshots/themes.png` and `02f2-themes.png` of `:app:snapshot`,
the whole window in each theme.
