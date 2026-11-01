# comando's documentation

These documents are comando's manual and its specification. If you want to use comando, start with the tutorial; if you
want to change it, start with the architecture. Each concept is described in one document, its canonical place, and
the others link to it.

| To | Read |
|---|---|
| install it | [Install](../INSTALL.md): the recipe Claude Code follows to install, upgrade and uninstall comando, and to offer a kit |
| use it for the first time | [Tutorial](tutorial.md): comando in ten minutes, step by step; also Help ▸ Tutorial in the app |
| know what every gesture does | [Workbench](workbench.md): the window, panels, themes, the sidebar, running text, the database, instances and the comando Edge, the terminal and Gradle, the Files tab and git changes, what is kept on disk |
| know what a session can do and what it must ask | [Claude Code](claude-code.md): the `claude -p` bridge, the checkpoint, the MCP tools, approvals and safety |
| understand the other two parts of the flow | [Kit and brain](kit-and-brain.md): the kit's parts and what each does in a session, what comando does with a kit, the brain's stores, its flows, `/reflect` and the learning loop, and how an agent creates a default kit and brain or checks one the user brings |
| change the code | [Architecture](architecture.md): modules, state and intents, ports, threads, ownership, then each module's classes, interface, threads and tests |
| build, test and release it | [Development](development.md): prerequisites, Gradle tasks, CI, configuration, the code rules, quality gates, how these documents and their pictures are kept |
| know why something is the way it is | [Decisions](adr/README.md): one record per decision, dated, with who decided |

Every picture is in [images/](images/), drawn by hand as SVG in comando's graphite and gold;
[Development](development.md) lists them and the rules they follow.
