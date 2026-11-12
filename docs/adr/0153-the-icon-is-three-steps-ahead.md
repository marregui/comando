# 153. The icon is three steps ahead

| Date | Status | Decided by |
|---|---|---|
| 2026-11-12 | accepted; built | Miguel and Claude |

## 1. Context

comando's first icon (2026-10-15, `f8965107`) was a shell prompt: a green chevron with a glow and an orange block
cursor on a dark glass tile. It said "a terminal for Claude", and nothing of what comando is besides: the kit that
holds the tools and the rules, the brain that keeps what was learned, and the flow of work between them and Claude.
Miguel asked for an icon that is simple, elegant and distinctive, carries meaning, and shows comando's philosophy.
Seven options were drawn and compared in a Dock at 64, 48, 32 and 16 px; Miguel chose the three chevrons, which he
reads as "three steps ahead of any IDE".

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the picture | three chevrons in a row, left to right, on comando's graphite tile | a chevron is the prompt, the place a command starts; three in a row is a command carried forward |
| the first, grey (`#8C877C`) | the kit: the tools, the hooks, the rules and the standards every session works under | the ground the work stands on: present, quiet, never the subject |
| the second, gold (`#E7C55A` with its gradient) | the brain: memory, the knowledge base, the logbook | gold is the brain's colour in every comando picture; what was learned is what is worth most |
| the third, Claude's orange (`#C96442` to `#F0A07F`) | Claude doing the work | the step that moves, and the only warm one |
| the order | kit, then brain, then Claude, each pushing the next | the work is only as good as the rules and the memory behind it |
| the name of the idea | three steps ahead | each layer puts the session a step further than an editor alone takes it |
| the tile | the graphite ground of comando's themes, a top sheen, a rim light and a drop shadow, as the first icon had | a Dock tile at home beside macOS's own |
| what it leaves out | glow, the green phosphor, text, a letter | none survives 16 px, and none says more |

## 3. Consequences

- `app/src/main/icon/comando.svg` is the source; `ui/src/main/resources/icons/comando.png` (window and Linux) and
  `app/src/main/icon/comando.icns` (macOS) are redrawn from it by the recipe in `app/build.gradle.kts`, and
  `docs/images/comando.svg` is its copy for the README.
- The three chevrons hold at 16 px, where the window's title bar and the menus show the icon.
- Asked what the icon means: the kit, the brain and Claude, in that order, three steps ahead.
