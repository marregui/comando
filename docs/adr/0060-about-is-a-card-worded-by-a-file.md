# 60. About comando is a card worded by a file

| Date | Status | Decided by |
|---|---|---|
| 2026-10-18 | accepted | Miguel (About's words from a file, premium, made with care; no native code), Claude (the card's design and the file's shape) |

## 1. Context

The application menu's About comando showed the standard panel of the Java runtime. Its words cannot be set from
comando without native code, and in a run from Gradle it describes the runtime rather than comando.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| how About opens | `Desktop.setAboutHandler`, the Java API comando already uses for Quit; the palette's About comando does the same | no native code; the same in a development run and in the packaged app |
| the words | `about.md` beside `About`: its heading is the name, its first paragraph the tagline, its table the facts, its paragraph of links the links, its last paragraph the maker's line | changed by editing one file |
| the facts | `{version}` (comando's), `{java}` (runtime and vendor), `{claude}` (what the latest session reported), `{system}`, `{home}` (the data root, from `~`), `{year}`; a fact left empty is not shown; an unknown placeholder stays as written | true for this run |
| the links | a web address opens the browser; `comando:tutorial` the tutorial; `comando:home` the data root in the Finder | |
| the card | drawn by hand on a borderless, rounded window with the system's shadow: warm charcoal, a gold hairline, the icon over a soft shadow, the name in Helvetica Neue Light, the tagline and the maker's line in Charter italic, the facts' labels in spaced small capitals, the links in gold; other fonts where these are missing | quiet and exact, nothing on it that is not needed |
| closing | Esc, ⌘W, a click outside it, or following a link; it moves by its body | |

## 3. Consequences

`About` holds the parser, the facts and the card; `ComandoUi.showAbout` opens it, one at a time. The snapshot draws it
as `02k-about.png`.
