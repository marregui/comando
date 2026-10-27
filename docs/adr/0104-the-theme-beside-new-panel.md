# 104. The theme beside New panel; Clear from the palette

| Date | Status | Decided by |
|---|---|---|
| 2026-10-27 | accepted | Miguel and Claude |

## 1. Context

After [record 103](0103-curated-themes-in-actions.md) the theme's swatch sat at the right end of the lower panel's tab
strip, beside the Session tab's Clear. The theme belongs to the whole window, not to the lower panel, and its tooltip
did not say which theme was chosen. Clear empties the Session tab's view while the conversation goes on; Miguel uses it
rarely and asked for a cleaner strip.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| where the theme is chosen | the swatch (`ThemeButton`) sits at the top right of the panels' tab strip, left of New panel, which keeps the corner; Actions ▸ Theme stays | a control of the window, beside the window's other control |
| its tooltip | names the theme chosen, read each time it shows: "Theme: Midnight and electric blue; click for another, also in Actions" | the swatch shows colours, not a name |
| Clear | no button; the command "Clear the Session tab" (`transcript.clear`, area Session) in the palette, enabled while the Session tab shows a session's transcript with blocks and no turn runs | the only route to it was the button; a long transcript can still be emptied |
| the lower panel's strip | holds the tabs alone | nothing left at its end |

Miguel decided the move and Clear's removal; Claude kept Clear in the palette.

## 3. Consequences

Tested in `UiKeysTest`: the swatch's row is the swatch and New panel; its tooltip names Graphite and gold, then
Midnight and electric blue once chosen (mutation-checked with a fixed tooltip); no Clear button in the window; the
palette's Clear is enabled after an ended turn and sends `ClearTranscript` for the session shown.
