# 127. Light themes: a mode per theme, Paper and ochre built in

| Date | Status | Decided by |
|---|---|---|
| 2026-10-30 | accepted; built | Miguel and Claude |

## 1. Context

Every theme was dark: near-black grounds, the transcript's ivory, white laid over the ground for cards, the golds pale
on it, and FlatLaf's dark look with the macOS appearance dark. Miguel, 2026-10-30: "I think we need to add at least as
many light style versions (the opposite of dark mode)". [Record 126](0126-themes-are-json-files.md) made themes JSON
files with a `mode`, refused as `light` until this record.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the mode | a theme file's `mode`, `dark` (the default) or `light`, is its ground's and its tint's (`Ground.light`, `Tint.light`); `Theme.light()` is the chosen ground's | the ground decides what reads on it |
| the look and feel | a light ground takes FlatLaf's light look (`FlatMacLightLaf` on macOS, `FlatLightLaf` elsewhere), set up again with each theme as the dark one was | its controls, borders and text are drawn for a light ground |
| the tokens | each colour of the palette that a light ground cannot take as it is has a second value, read at each paint (`ByMode`): the transcript's text `#2B2620`, bold `#12100C`, muted `#6B6B6B` and `#555555`, link and information `#1A62A0`, Claude `#B8532F`, ok `#2E7D3E`, danger `#B3261E`, thinking `#6B4FBF`, attention a deep gold `#8A6300`, git's four, the selection, find's two, the fallback text; the surfaces laid over the ground (`OnGround`) take black at a small amount where the dark take white: a card at 9 %, its border 17 %, inline code 8 %, a table's rules 20 %, the prompt's bubble the same blue at 16 % | a colour set once keeps following the theme with no listener, as the ground's surfaces already did (record 103); each value reads on paper above the floor its dark one meets |
| the tint's steps | from the one that stands out most from the ground, the highlight, to the one that stands out least, the shade: pale to deep on a dark ground, deep to pale on a light one; the floors of `ThemeRules` are the same in both modes | the steps' uses (small text in the body, bands from the shade, a table head's text in the highlight) keep their contrast |
| content colours | `ContentPalette` on a light ground: the ink the dark text; the syntax categories at lightness 0.40, chroma 0.14, neighbouring hues alternating 0.03 up and down, and keywords in the tint's highlight, so that each stays apart from the keyword and from its neighbours where the dark end of the gamut holds less chroma; the terminal's six colours at 0.47 and their bright forms at 0.38, its black near the ink and its white near the ground; plot series at 0.50; inline code at 0.38 | the same hues as dark, drawn dark enough to read; the separations the tests set for dark hold on paper |
| the title bars | each window's root pane takes `apple.awt.windowAppearance`, Aqua under a light theme and DarkAqua under a dark one, when the theme is chosen and when a window opens; the application's appearance, set at launch, stays dark, so its menus and the native file dialogs stay dark until a restart under a light theme is built | a title bar of the theme's mode; the application's appearance is read once, before the toolkit starts |
| the pilot | Paper and ochre, built in after the six dark themes: ground `#F7F3EA`, ochre `#3D2900`, `#5E4210`, `#8A6524` (the body 8.4:1, the shade 4.8:1 on paper) | Miguel agreed the pilot; one light theme to look at before more |
| About | its card keeps its warm charcoal in every theme | it is a card of its own, not the window's ground |

Miguel asked for light themes and agreed the plan (one pilot, light as a mode of a theme file); Claude chose the
values and the rules.

## 3. Consequences

`ThemeSpec` reads `mode`; `Ground` and `Tint` carry `light`; `ByMode` joins the theme kit, `OnGround` takes a light
colour and amount, `Theme.fixed` gives a token's value for a ground other than the one chosen (what a palette or a
check made for that ground keeps). The tests that pair every tint with every ground pair those of one mode. Tested in
`ThemeFilesTest` (Paper and ochre's colours and mode, a light user file read as light, a light file too pale named),
`ContentPaletteTest` and `TooltipTintTest` (every floor on paper), `NamedThemeTest` (seven themes, the light one last)
and `ThemeFadeUiTest` (Paper and ochre chosen from the swatch: the light look, the panels on paper, the text dark, the
title bar's appearance Aqua; Graphite and gold again puts all of it back; a panel in a tab behind, brought forward
after the change, painted in the same event with no pixel of the ground before). What follows the tint through
`SidebarLook.whileShown` (the editors, the terminal, the sidebar's lists and tree, tab strips) listens while it is in a
window, shown or not: it listened only while shown, so an editor in a tab behind came forward in the theme before for
one frame, a blink of the dark ground (Miguel: "if I switch to another tab it first renders the previous background
which is dark, then the light one"; 68 % of the window's pixels on that frame, measured off screen). Not checked by a test: the title bar as
drawn, and the transcript, cards and approvals on paper, which no off-screen session draws.
