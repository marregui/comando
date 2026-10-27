# 100. A theme is four tiers of tokens; blue is a link; the tint is "where I am"

| Date | Status | Decided by |
|---|---|---|
| 2026-10-27 | accepted | Miguel and Claude |

## 1. Context

After [record 99](0099-a-ground-chosen-beside-the-tint.md), each part of the window still brought its own colours: the
editor's syntax (IntelliJ's), the terminal's ANSI colours, diff and plots, About; blue meant a link, focus, the tab in
front, a pinned view, a drag's gap and a statement that ran; the folders' icons took six colours. On 2026-10-27 Miguel
agreed to a plan to make the window one designed object (decisions D1 to D5) and handed its execution over. This record
is its first phase.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what a theme is | one set of tokens in four tiers: the palette (the ground chosen, surfaces, ink, the tint chosen, status, Claude's orange); the roles (`Theme`: a link, structure, the selection, focus, attention, danger…); the component map (each widget names roles); the content palettes derived from them (syntax, terminal, diff, plots), the next phase | D1 |
| literals | no class outside `ui.kit.theme` makes or names a `java.awt.Color` (`ColourRulesTest`, ArchUnit); the last four moved into `Theme` (`CLEAR`, `SHADOW`, `withAlpha`) | a theme reaches the whole window only if nothing keeps a colour of its own |
| blue | `Theme.LINK` is a link and nothing else; `Theme.INFO`, a status colour of the first tier, marks information inside content (a diff's hunk header, a shell command in a tool card) | D5 |
| the tint as "where I am" | the tab in front's underline (the sidebar's tabs), a focused session's, connection's or panel's name, the pinned view's mark, a drag's gap, the approval card with the keyboard, the plot's zoom box, the statement that ran, the line a click went to | D5: the tint already marks the selection and the structure |
| folders' icons | one ink, the secondary text's (`#A8A8A8`), told apart by their shape; the tabs keep their own colours; git's mark keeps git's | D4: the tabs' colours name three places; six colours for kinds of folder were decoration |

Miguel decided D1 to D5. Claude decided which marks are "where I am" and which blue is information.

## 3. Consequences

[Record 98](0098-the-sidebars-look.md)'s folder icons in a colour per kind are replaced. The content palettes
([record 101](0101-content-colours-derived-from-the-theme.md)) replace the editor's XML colours; `Theme.INFO` stays a
status colour.

Tested in `ColourRulesTest` (mutation-checked: a red planted in `StatusBar` fails it), `SessionsSidebarTest`
(a focused name in the tint), and the off-screen `TreeClicksUiTest` sheet `app/build/snapshots/sidebar.png`.
