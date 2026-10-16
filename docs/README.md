# comando documentation

Reference for how comando is built and why. The [repository README](../README.md) is the front page; these documents are the detail behind it.

## 1. Architecture

| Document | Contents |
|---|---|
| [Overview](architecture/README.md) | what comando is, the modules, the flow of one command, the principles |
| [State and intents](architecture/01-state-and-intents.md) | `AppState`, intents, the reducer, effects, the stores outside the state, the intent log and replay |
| [Threads](architecture/02-threads.md) | every thread, what it owns, how work crosses between threads, the bounded waits |
| [Modules and ports](architecture/03-modules-and-ports.md) | the module graph, the ports and their adapters, the test fakes, the enforced import rules |
| [Ownership](architecture/04-ownership.md) | who creates and closes what, and the close order at exit |

## 2. Design

| Document | Contents |
|---|---|
| [Claude bridge](design/claude-bridge.md) | the `claude -p` process, the stream-JSON mapping, turns, interrupts, versions, stored conversations |
| [Resources and panels](design/resources-and-panels.md) | connections and sessions as one resource model, panel bindings, the sidebar and the dialogs |
| [Command dispatch](design/command-dispatch.md) | the SQL-or-prompt classifier, the keys, the gutter, attaching results |
| [MCP UI tools](design/mcp-ui-tools.md) | the MCP server, each tool's contract, view-spec validation, the instructions Claude receives |
| [Widgets](design/widgets.md) | the widget contract, capabilities, the registry, the widgets, adding one |
| [UI kit](design/ui-kit.md) | theme tokens, icons, actions, overlays, EDT helpers, text and tree components |
| [UI packages](design/ui-packages.md) | the package tree and the import rules the build enforces |
| [Transcript renderer](design/transcript-renderer.md) | the painted transcript: block model, layout, virtualisation, streaming, the frame-time gate |
| [Approvals and safety](design/approvals-and-safety.md) | approval cards, SQL policy, production connections, caps, audit log, credentials |
| [Persistence](design/persistence.md) | the data root, atomic store files, passwords in the keychain, session restore |
| [Development tools](design/dev-tools.md) | terminal, Gradle runs and test trees, JFR recordings and jfrq, the Files tab |
| [Database workbench](design/database-workbench.md) | lanes and cancel, columnar results, plots, kept results, plans, schema explorer, completion, TLS |
| [Quality](design/quality.md) | the test suites, fixtures, replay tests, the renderer gate, the EDT watchdog, JFR events, CI, the DMG |

## 3. Decisions

[Decision records](adr/README.md), one per decision, dated.

## 4. Guides

| Document | Contents |
|---|---|
| [comando in ten minutes](guides/tutorial.md) | a first session, step by step: a database, SQL, plots, a Claude session, approvals, the terminal |
| [Build and run](guides/build-and-run.md) | prerequisites, Gradle tasks, configuration properties, the data root |
| [Coding guidelines](guides/coding-guidelines.md) | the rules the code follows and the ones it does not, with the reason |
