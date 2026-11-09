# 147. A theme says what it does to the mind

| Date | Status | Decided by |
|---|---|---|
| 2026-11-09 | accepted; built | Miguel and Claude |

## 1. Context

The chooser ([record 128](0128-a-chooser-of-twelve-themes.md)) shows each theme small in its own colours, under its
name. Nothing said what a theme's colours do to the person working in it. Miguel asked on 2026-11-09 for each theme's
file to carry a short description of its psychological effect, shown in the theme's tooltip, as an input when he
chooses one. The tooltips keep the look of the theme chosen, as every tooltip does.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| where it lives | a `description` field in the theme's JSON file ([record 126](0126-themes-are-json-files.md)), optional, plain text, at most 140 characters; a blank or longer one leaves the file out with the reason, as a bad colour does | the theme's file is its one persisted form; a user's theme may have one too |
| where it shows | the chooser's card tooltip, its first line as before, the description on a second line; the theme button's tooltip, the same for the theme chosen | the two places a theme is named on hover |
| text from a file | the label and the description are escaped inside the two-line tooltip; a tooltip with no description is guarded as plain text (`Plain.text`) | development rule 6: text from a file reaches Swing as text, never as HTML |
| the tooltip's look | the theme chosen's, as every tooltip's | Miguel's choice |
| the built-in texts | each states the tint on the ground, the arousal its saturation gives, and, where it holds, the light it suits | brightness and saturation have the most reliable measured effects on mood (Valdez and Mehrabian, 1994): pleasure rises with brightness, arousal with saturation; dark text on a light ground is read more accurately in good light (Piepenbrock and others, 2013); the effects of single hues are small and replicate poorly, so the texts lean on them least |

The twelve built-in descriptions:

| Theme | Description |
|---|---|
| Graphite and gold | Vivid gold on neutral black: alert and commanding. Easiest on the eyes in a dim room. |
| Espresso and champagne | Muted champagne on brown: warm and calm. Comfortable for long sessions. |
| Aubergine and yellow gold | Gold against its near-opposite, purple: the strongest accent. Rich, medium arousal. |
| Midnight and electric blue | Saturated blue on navy: the most stimulating of the twelve. Least suited to late nights. |
| Evergreen and tropical forest | Green on deep green: medium arousal, calm, suggests nature. |
| Slate and silver | Silver on slate, nearly colourless: the lowest arousal, neutral, least distracting. |
| Paper and ochre | Ochre on warm paper: calm, like print. Dark on light reads most accurately in good light. |
| Cream and champagne bronze | Bronze on cream: the softest of the twelve, warm and low in contrast, like old paper. |
| Lilac and deep gold | Deep gold on lilac: quiet and slightly cool. The gold reads dense. |
| Ice and navy | Navy on ice: formal and orderly, crisp in good light. |
| Mint and forest | Forest on mint: fresh and calm. |
| Fog and slate | Slate on fog: the most neutral light theme, cool and quiet. |

Miguel asked for the field and its use and chose the tooltip's look and both places; Claude wrote the texts and the
rules.

## 3. Consequences

`ThemeSpec` reads `description` (`DESCRIPTION_MAX`, 140), `NamedTheme` carries it, `ThemeChooser.tip` builds both
tooltips. A file written for an earlier comando reads as before, with no description. Tested in `ThemeFilesTest` (every
built-in theme has one; a user's is read as written, left out is empty, blank or 141 characters is refused, 140 is read)
and `ThemeChooserTest` (the tooltip escaped, the head alone guarded, a card's tooltip carrying its description).
