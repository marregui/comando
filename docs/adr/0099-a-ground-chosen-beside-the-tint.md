# 99. A ground, chosen beside the tint, for every panel

| Date | Status | Decided by |
|---|---|---|
| 2026-10-27 | replaced by [record 103](0103-curated-themes-in-actions.md) | Miguel and Claude |

## 1. Context

[Record 98](0098-the-sidebars-look.md) put every panel of the window on one ground, `#121212`, set once in `Theme`.
On 2026-10-27 Miguel asked to try other grounds that sit well with the tints ([record 96](0096-a-tint-chosen-in-actions.md)),
applied everywhere when chosen, to decide by seeing them.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the grounds | `Ground`: Graphite `#121212` (the default, record 98's), Onyx `#0A0A0B`, Espresso `#16120E`, Midnight ink `#0D1220`, Evergreen `#0C1511`, Aubergine `#150F19`, Slate `#1B1D21`: near-black, each a little toward a hue the tints sit on (the golds on the warm, silver and electric blue on the cool, tropical forest on the green). On each, the transcript's ivory reads above 12:1, every tint's body above 7.5:1 and its shade above 4:1 | Claude chose the seven; the contrast floors are tested |
| where it is chosen | a swatch button beside the tint's, left of the Session tab's Clear (the ground with a bar of the tint chosen), whose menu lists the seven with their swatches, the chosen one ticked; and Actions ▸ Ground, after Actions ▸ Tint; a choice in either ticks both; kept in the preference `theme.ground` | Miguel: another chooser next to the tint's; as the tint, in both places |
| what takes it | `Theme.ground()` is the one source: choosing sets the look and feel up again on it (its background, and lists', trees', tables', text areas' and viewports') and every window takes it; what mixes a colour over the ground (a table head's band, a heading's rule, the selection's band) and listens to the tint lays itself out again; the editors (text, gutter, fold column) and the terminal read it | Miguel: when selected, applied all around |

## 3. Consequences

Record 98's `Theme.GROUND` and `Theme.TRANSCRIPT` constants become `Theme.ground()`. A component given the ground
explicitly takes it as one of the look's own colours, so a ground chosen later replaces it.

Tested in:

- `TooltipTintTest`: every tint on every ground above the floors; a ground chosen without the look is kept and heard;
- `MenusTest`: Actions ▸ Ground after Actions ▸ Tint, a swatch for each;
- the off-screen `UiKeysTest`: the swatch between the tint's and Clear, its menu ticked on the default, a choice kept
  under `theme.ground` and taken by the look, the sidebar's list, an editor and a panel; a new window on the ground
  kept; the window painted on every ground to `app/build/snapshots/grounds.png`.
