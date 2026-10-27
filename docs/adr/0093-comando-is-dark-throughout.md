# 93. comando is dark throughout

| Date | Status | Decided by |
|---|---|---|
| 2026-10-26 | accepted | Miguel and Claude |

## 1. Context

[Record 57](0057-a-light-editor-and-a-header-of-icons.md) gave the editors a light theme beside the dark one:
IntelliJ's Light colours, rendered Markdown on warm paper, Mermaid diagrams drawn in Mermaid's default theme, diff
shades of their own, switched for every panel from a button in each panel's header and in the comparison window, kept
in the preferences as `editor.light`. The comparison's editors took that theme
([record 68](0068-git-changes-and-a-side-by-side-diff.md), row "the theme"), and the comparison window had the switch
([record 72](0072-the-comparison-window-edits-rolls-back-and-removes.md)). The rest of the window was dark either way.
This record replaces record 57's theme, scope and "what turns light" rows and record 68's theme row, removes
record 72's theme switch, and removes the light page [record 83](0083-one-gold-chosen-beside-clear.md) left unchanged.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the theme | IntelliJ's Dark for every editor, as the whole window: no light theme | one theme, the dark one |
| the switch | gone from the panel header and the comparison window, with its icon | nothing to switch |
| what goes with it | the light editor colours (`editor-light.xml`), the light page of `HtmlPane` (its paper, text colour and rules), Mermaid's light drawings, the light diff shades, the preference `editor.light` (no longer read) | each existed only for the light theme |
| what comes next | the dark theme is made more ergonomic as use shows where it falls short | Miguel |

Miguel decided, after a period on the dark theme, that comando needs neither a light mode nor a switch between modes,
and that the dark theme throughout is kept and made more ergonomic over time. Claude decided what goes.

## 3. Consequences

Record 57's header of icons stands, without the theme button. `Editors.theme`, `Editors.colorOf`, `Highlighter.runs`,
`MarkdownHtml.render`, `MermaidDiagrams.diagram` and `HtmlPane.styles` take no theme. A Mermaid diagram already drawn
for the light page stays in the cache folder unused. The snapshot no longer paints the light frames (`02d2`, `02d3`,
`02e3`, `02m2`). The light page on paper that [record 83](0083-one-gold-chosen-beside-clear.md) left in its colours is
gone with it.
