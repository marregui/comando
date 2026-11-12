# 155. Icon sets switch like themes

| Date | Status | Decided by |
|---|---|---|
| 2026-11-12 | accepted; built | Miguel and Claude |

## 1. Context

comando's icons were one set of SVGs, drawn over months in several hands. Miguel asked for consistent alternatives and
for a way to compare them in the running window, chosen as a theme is chosen, so the best set is picked by use rather
than from pictures.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the sets | Classic (today's) and eight more: Hairline, Gold duotone, Solid glyph, Console, Chevron line, Graphite tile, Signal lamp, Monospace | four styles first proposed as pictures and four drawn as SVG, all made real to compare |
| what a set covers | every icon in use, 105 names; the Locate button's 7 × 16 arrow stays Classic's, and the theme swatches, painted in code, stay | a switch changes the whole look, not part of it |
| one geometry | every set is rendered from one drawing per icon (its body, the mark that carries its meaning, its owner, its state lamp, its characters for Monospace) | the same metaphor in every set; only the style changes |
| colour by owner | grey the kit, gold the brain, Claude's orange, ivory you and comando (blue comando's own in Solid glyph); state as a lamp in the corner | the app icon's three steps ([record 153](0153-the-icon-is-three-steps-ahead.md)) carried into the icons |
| where it is chosen | a row of sets under the theme chooser's cards, each showing that set's session icon, and one palette command per set (`Icons: <set>`), the chosen one ticked | beside the theme, where a look is chosen |
| when it applies | at once, in every window; kept in the settings (`icons.id`) and applied at start | as a theme |
| light themes | a set's ivory paints in the theme's text colour on a light ground | ivory on a pale ground cannot be seen |
| a missing icon | a set without a name shows Classic's | a new icon never shows blank |
| Monospace's font | Menlo, then DejaVu Sans Mono, then the system's monospace | Menlo is on every Mac |

## 3. Consequences

- `IconSet` names the sets; `Icons.get` hands out one icon per name and size that paints the chosen set's file, so
  components keep their icons and a switch repaints. The sets live in `icons/sets/<id>/`, generated from the geometry
  by a script kept outside the repository; Classic stays in `icons/`.
- The tint recolours Classic's gold only; the sets' colours are fixed.
- Once a set is chosen as the best, the others can go, and the chosen one can become Classic.
