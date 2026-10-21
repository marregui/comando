# comando's documentation

Each document is self-contained and checked against the code. Start with the tutorial to use comando, with
architecture to change it.

| To | Read |
|---|---|
| use comando for the first time | [Tutorial](tutorial.md): a first session, step by step; also Actions ▸ Tutorial in the app |
| know what the window does | [Workbench](workbench.md): panels and bindings, running text, the database, the tools, the Files tab and git changes, what is kept on disk |
| know what Claude Code can do in it | [Claude](claude.md): the `claude -p` bridge, the MCP tools, approvals and safety |
| build, run and test it | [Build and run](build-and-run.md): prerequisites, Gradle tasks, settings |
| change its code | [Architecture](architecture.md): modules, state and intents, ports, threads, ownership, code rules, quality; then [UI](ui.md): the kit, commands and keys, widgets, the painted transcript |
| change one module | its README: [core](../core/README.md), [testkit](../testkit/README.md), [db](../db/README.md), [claude](../claude/README.md), [mcp](../mcp/README.md), [tools](../tools/README.md), [ui](../ui/README.md), [app](../app/README.md); the kit: [kit](../kit/README.md) |
| know why it is so | [Decisions](adr/README.md): one record per decision, dated, with who decided |

`images/` holds the pictures the front page shows: `hero.png` (`./gradlew :app:hero`) and
`documents-and-approvals.png` (frame `02e` of `./gradlew :app:snapshot`), both painted off screen from a live window.
