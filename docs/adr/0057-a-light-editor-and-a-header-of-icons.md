# 57. A light editor, and a panel header of icons

| Date | Status | Decided by |
|---|---|---|
| 2026-10-17 | accepted; the rows theme, scope and "what turns light" replaced by [record 93](0093-comando-is-dark-throughout.md), which takes the theme button out of the header; the header's clear removed by [record 86](0086-a-checkpoint-clears-and-carries-its-summary.md) | Miguel and Claude |

## 1. Context

Every editor was dark (IntelliJ's Dark colours). The panel header's right-hand buttons (Preview, Run, Stop, Clear) were
shaped buttons with words, wider than the session's icon buttons at the left. Compact and clear were on the session's
row in the sidebar only.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| theme | a light editor theme with IntelliJ's Light colours, mapped onto the same token types as the dark one; the gutter's background set in both, so a switch back restores it | the same colours a reader knows from the IDE |
| scope | one choice for every panel, switched from any panel's theme button and kept in the user's preferences (`editor.light`); a panel opened later follows it | a theme is a reader's preference, not a panel's |
| what turns light | the editor's text and gutter, and its rendered Markdown: dark text on warm paper (`#F5F0E6`), not white, code blocks in the light theme's colours, Mermaid diagrams drawn in Mermaid's default theme and cached apart from the dark ones; the sidebar, lower panel and side pane stay dark | where reading happens |
| header | Browser, Preview or Edit, the theme, Run, Stop, Clear as icons without a button's shape, 2 px apart; the tooltip names each and its key; the accessible name is the action | as compact as the session's controls |
| session | compact and clear after the checkpoint while the session runs, sending `/compact` and `/clear` from the panel as the row's buttons do | in the panel where the conversation is |

Miguel decided a light theme for the editor beside the dark one, from a button after Preview with its own icon; the
header's buttons closer together, without a button's shape, as the session's at the left; compact and clear among the
session's buttons; one choice for all panels, kept; the editor and its preview light, the window dark; icons without
words; and a tinted ground, not white. Claude decided how: the colours, what the preview's light page changes, and where
the choice is kept.

## 3. Consequences

`Editors.theme` applies either theme and keeps the text area's font, zoom included; `Editors.colorOf`,
`Highlighter.runs` and `MarkdownHtml.render` take the theme; `MermaidDiagrams.diagram` draws for either page.
`HtmlPane` has a kit with a style sheet of its own (Swing's `HTMLEditorKit.setStyleSheet` sets one shared by every
kit), starts a new document when the theme changes (`setText` keeps the document's style sheet) and sets its
foreground, which an HTML pane that honours display properties writes into the body's rule. `Workspace` holds the
choice; `ComandoUi` keeps it.

## 4. Alternatives

| Option | Why rejected |
|---|---|
| a white page for light Markdown | the author asked for a tinted ground: white tires the eye |
