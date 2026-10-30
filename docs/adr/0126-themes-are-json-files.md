# 126. Themes are JSON files: six built in, the user's from the data root

| Date | Status | Decided by |
|---|---|---|
| 2026-10-30 | accepted; built for dark themes; light themes (`mode: light`) are the next step | Miguel and Claude |

## 1. Context

The six curated themes of [record 103](0103-curated-themes-in-actions.md) were Java enums: seven grounds (`Ground`,
Onyx in no theme), six tints (`Tint`) and the six pairs (`NamedTheme`). Adding a theme meant changing code. Miguel,
2026-10-30: "the theme definitions could be backed by json files and offered in comando. then the widget to select
would offer options for the themes defined in the json file. this way we can add more"; the files "can be picked on
start". He also asked for light versions of the themes, which need a theme to say whether it is dark or light.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| a theme | one JSON file: `id`, `label`, `mode`, `ground` (`label`, `color`) and `tint` (`label`, `highlight`, `body`, `shade`), colours written `#RRGGBB`; the ground's and the tint's labels default to the theme's | few hand-picked values: everything else (surfaces, syntax, terminal, diffs, plots) stays derived from the ground and the tint ([records 101](0101-content-colours-derived-from-the-theme.md), 103) |
| the built-in themes | the six, unchanged, as files under `/themes` on the class path, in the swatch's order; `Ground`, `Tint` and `NamedTheme` are classes whose named constants (`Ground.GRAPHITE`, `Tint.TREASURE`, `NamedTheme.MIDNIGHT_BLUE`) are read from those files; Onyx, in no theme, is gone | the files are the one source of the colours; the code that names a built-in theme keeps compiling |
| the user's themes | every `*.json` in the data root's `themes` folder, read once at start in the order of the names, offered after the six in the swatch's menu and the palette | Miguel: picked on start |
| a file that is wrong | left out, with one notice per file naming it and the reason: not JSON, a field missing or of the wrong kind, an id not lower case letters, digits and underscores, an id another theme has, `mode` not `dark`, or a contrast floor not met | a hand-made theme cannot make text unreadable, and the user learns why it is missing |
| the floors | `ThemeRules`, the ones the built-in themes are tested on: the transcript's ivory above 12:1 on the ground, a card above 1.2:1, the tint's body above 7.5:1 and its shade above 4:1, its highlight above 7:1 on a table's head; of the derived palette, code above 12:1, keywords 7.5:1, comments 4.5:1, the syntax categories 6.5:1, the terminal's six colours 5:1 and their bright forms 8:1 | the tests' floors, now also checked at load |
| the theme kept | by its id (`theme.id` in `settings.json`), with the ground and tint names as before; an id no theme offered has (a file removed since) falls back to the ground and tint kept, and to Graphite and gold | a user theme is found again by its id; older settings keep working |
| light | `mode` is read; `light` is refused with "light themes are not offered yet" until the light step is built | the format is the one the light themes will use |

Miguel decided JSON files, offered in the swatch, read at start; Claude decided the format, the floors and the
fallbacks.

## 3. Consequences

`ThemeSpec` parses a file, `BuiltInThemes` reads the six, `ThemeFiles` reads the user's folder, `ThemeRules` holds the
floors and WCAG's contrast; `NamedTheme.offer` adds the user's themes after the built-in ones, called by `ComandoUi`
before anything names a theme, and the left-out files become warnings once the window is built. `Menus.areas()` lists
the themes offered when the window asks, no longer when the class loads. `OnGround` and `ContentPalette` cache by the
ground's and tint's identity instead of an enum ordinal. Tested in `ThemeFilesTest` (the built-in colours as before,
each built-in theme above every floor, a user's files offered in order with their own ground and tint, eight kinds of
broken file each named), `WindowLayoutTest` (kept by id, the fallbacks) and `ThemeFadeUiTest` (a file in the data root
offered in the swatch's menu, chosen and kept, a broken one named in a notice).
