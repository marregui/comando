# 93. comando is dark throughout

| Date | Status | Decided by |
|---|---|---|
| 2026-10-26 | accepted | Miguel ("now that I have become accustomed to the dark mode, I think we do not need a light mode nor a switch to change those modes"; "the dark theme all around the app is what we keep from now on, and we will make it more ergonomical as we go along"), Claude (what goes) |

## 1. Context

[Record 57](0057-a-light-editor-and-a-header-of-icons.md) gave the editors a light theme beside the dark one: IntelliJ's
Light colours, rendered Markdown on warm paper, Mermaid diagrams drawn in Mermaid's default theme, diff shades of
their own, switched for every panel from a button in each panel's header and in the comparison window, kept in the
preferences as `editor.light`. The rest of the window was dark either way.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the theme | IntelliJ's Dark for every editor, as the whole window: no light theme | Miguel: one theme, the dark one |
| the switch | gone from the panel header and the comparison window, with its icon | nothing to switch |
| what goes with it | the light editor colours (`editor-light.xml`), the light page of `HtmlPane` (its paper, text colour and rules), Mermaid's light drawings, the light diff shades, the preference `editor.light` (no longer read) | each existed only for the light theme |
| what comes next | the dark theme is made more ergonomic as use shows where it falls short | Miguel |

## 3. Consequences

Replaces record 57's theme, scope and "what turns light" rows; its header of icons stands, without the theme button.
`Editors.theme`, `Editors.colorOf`, `Highlighter.runs`, `MarkdownHtml.render`, `MermaidDiagrams.diagram` and
`HtmlPane.styles` take no theme. A Mermaid diagram already drawn for the light page stays in the cache folder unused.
The snapshot no longer paints the light frames (`02d2`, `02d3`, `02e3`, `02m2`).
