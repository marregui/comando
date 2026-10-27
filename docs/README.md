# comando's documentation

The documents below cover installing, using, building and changing comando. Each is self-contained and was checked
claim by claim against the code. A user starts with the tutorial; a contributor starts with the architecture.

| To | Read |
|---|---|
| install it | [Install](../INSTALL.md): the recipe Claude Code follows to install, upgrade and uninstall comando, and to offer the kit |
| use comando for the first time | [Tutorial](tutorial.md): a first session, step by step; also Actions ▸ Tutorial in the app |
| know what the window does | [Workbench](workbench.md): panels and bindings, running text, the database, the tools, the Files tab and git changes, what is kept on disk, the kit |
| know what Claude Code can do in it | [Claude](claude.md): the `claude -p` bridge, the MCP tools, approvals and safety |
| build, run and test it | [Build and run](build-and-run.md): prerequisites, Gradle tasks, CI, settings |
| change its code | [Architecture](architecture.md): modules, state and intents, ports, threads, ownership, code rules, quality; then [UI](ui.md): the UI's shared package (`ui.kit`), commands and keys, widgets, the painted transcript |
| change one module | its README: [core](../core/README.md), [testkit](../testkit/README.md), [db](../db/README.md), [claude](../claude/README.md), [mcp](../mcp/README.md), [tools](../tools/README.md), [ui](../ui/README.md), [app](../app/README.md) |
| know why it is so | [Decisions](adr/README.md): one record per decision, dated, with who decided |

`images/` holds the pictures of the README and the docs, painted at twice their size by `./gradlew :app:docImages`
from a live window on a scratch data root: one real Claude Code turn (haiku), the embedded QuestDB, a sample repository,
and fixed plan usage in the status bar.

| Picture | Shows | Used in |
|---|---|---|
| `hero.png` | a session's panel: the query, the prompt, the transcript of the turn, the plot it placed | [README](../README.md) |
| `approvals.png` | a tool's and a question's approval cards, a diff in the side pane | [README](../README.md), [Claude §3.1](claude.md#31-approval-cards) |
| `sql.png` | a `SAMPLE BY` query and its result grid | [README](../README.md), [Workbench §3.2](workbench.md#32-results) |
| `changes.png` | a repository in the Files tab and its uncommitted changes | [README](../README.md), [Workbench §5.1](workbench.md#51-changes) |
| `diff.png` | a file compared with `HEAD`, side by side | [README](../README.md), [Workbench §5.1](workbench.md#51-changes) |
| `comando.png` | the icon, drawn from `app/src/main/icon/comando.svg` | [README](../README.md) |
