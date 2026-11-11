# 151. A theme has a style: the console

| Date | Status | Decided by |
|---|---|---|
| 2026-11-11 | accepted; built | Miguel and Claude |

## 1. Context

A theme was colour only: a ground and a tint, everything else derived from them
([records 100](0100-a-theme-is-four-tiers-of-tokens.md), [126](0126-themes-are-json-files.md)). Miguel, 2026-11-11: "I
have always been fascinated by such user interfaces, looking so neat and nice and futuristic. I wonder if comando could
have that look and feel, still following the tenets of user experience, simplicity, luxury." The interfaces are the
ones films draw. They come in two schools: the hologram (translucent, glowing, always moving), made for the camera,
and the instrument console (a near-black ground, hairlines, one accent colour, every panel labelled, gauges), which is
how real instruments are drawn. Only the second keeps text readable through a working day, and comando has what a film
lacks: real figures to put on the gauges.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| where the look is chosen | a theme file's optional `style`: `plain`, the default, or `console`; another value leaves the file out with the reason, as a bad colour does | the theme's file is its one persisted form; a user's theme may have a style too |
| the fourteen themes | the twelve carry no `style` and draw exactly as before; two more are built in, dark, without a twin, after them: Console amber and Console cyan | amber and cyan are the two palettes of the film consoles, so both ship |
| what `console` changes | square corners (the look and feel's arcs, the scroll bar's thumb, the agents' chips); a hairline with four corner brackets round the sidebar, the editors, the lower panel and the side pane (`BracketBorder`); the tabs' titles and the sidebar's group headers in the technical face; gauges where plain shows text | the frame names a region; the transcript and the editors inside stay a plain reading surface |
| the technical face | macOS's DIN Condensed, else DIN Alternate, else the look's own font | the face of instrument panels, already on the machine: no font is bundled |
| the gauges | the status bar's plan usage, one bar per window and provider, coloured at the thresholds the text is; the session bar's context, a bar with a tick at the checkpoint threshold, purple once the checkpoint is due; the figures stay in the tooltips | a gauge is read at a glance, and each one shows a real reading |
| names | a file's name in a tab keeps its case | a name is read as a name |
| what it leaves out | motion, sound, a start-up sequence, glow, transparency | none carries information, and the last two cost contrast |
| the floors | a console theme passes every contrast floor of `ThemeRules`, as any theme | the look never costs legibility |

Miguel decided the look, the three layers (colour, style, instruments) and that it is a theme; Claude decided the
format, both palettes, the face, what is framed and what is left out.

## 3. Consequences

`Style` (`PLAIN`, `CONSOLE`) is in the theme kit; `ThemeSpec` reads `style`, `NamedTheme` carries it and `Theme.style()`
is the style of the theme chosen, read at paint and at layout. `Theme.technical()` picks the face. `BracketBorder` and
`Gauge` are in the theme kit, which alone makes colours. The theme chooser shows the two console themes after the
pairs, each in a place of its own. Tested in `ThemeFilesTest` (the style read, defaulted and refused; both console
themes above every floor), `StyleTest`, `BracketBorderTest`, `GaugeTest`, `StatusBarUsageTest`, `SessionBarTest` and,
in the real window, `ConsoleStyleUiTest`: a plain theme's bounds are the same after the console was chosen and left.
