<p align="center">
  <img src="docs/images/comando.png" width="128" alt="comando's icon: a green prompt chevron and an orange cursor on a dark glass tile">
</p>

<h1 align="center">comando</h1>

<p align="center">
  <b>One editor for SQL and Claude Code.</b><br>
  A macOS workbench where a block of text runs on your database or goes to your Claude Code session,<br>
  and Claude answers with tables, plots, diffs and approval cards in the same window.
</p>

<p align="center">
  <a href="https://github.com/marregui/comando/actions/workflows/build.yml"><img src="https://github.com/marregui/comando/actions/workflows/build.yml/badge.svg" alt="build"></a>
  <img src="https://img.shields.io/badge/Java-25-E76F00?logo=openjdk&logoColor=white" alt="Java 25">
  <img src="https://img.shields.io/badge/macOS-native%20app-000000?logo=apple&logoColor=white" alt="macOS">
  <img src="https://img.shields.io/badge/Claude%20Code-2.1-D97757?logo=anthropic&logoColor=white" alt="Claude Code 2.1">
  <img src="https://img.shields.io/badge/QuestDB%20%C2%B7%20PostgreSQL-PG%20wire-336791?logo=postgresql&logoColor=white" alt="QuestDB and PostgreSQL">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue" alt="Apache 2.0"></a>
</p>

![comando at work: a query in the editor, coloured as SQL because the database is open; the prompt below it went to a Claude Code session, which ran the query through comando's tools and plotted the result beside the editor](docs/images/hero.png)

<p align="center"><sub>A real turn, unedited. The query is coloured as SQL because the panel's QuestDB is open. The prompt under it went to a Claude Code session (haiku), which ran the query with comando's <code>db_run_sql</code> tool and placed the plot in the side pane with <code>ui_plot</code>: three tool calls, 9.8 s.</sub></p>

## Why

In a terminal, Claude Code's work is text that scrolls away. comando turns it into a window you can work in:

- **The database sits next to the conversation.** Write a query, run it, ask Claude about the result, in one document.
- **Claude can show, not only tell.** It puts tables, plots, diffs, files and Markdown on screen through tools that comando serves.
- **Approvals are cards, not prompts.** Allow once, for the session or always, with the rule shown before it is saved; nothing is missed while you look elsewhere.
- **Many sessions, each in its own panel.** Each tab shows whether its session is working, waiting on background work, needs you, failed or finished.

## How it works

```mermaid
flowchart LR
    B["a block in the editor"] -->|"SQL"| L[("the panel's database")]
    B -->|"prompt or /command"| C["the panel's claude -p session"]
    L --> R["results, plots, EXPLAIN trees"]
    C -->|"comando's MCP tools"| W["tables, plots, diffs and files in the window"]
    C -->|"permission requests"| A["approval cards"]
```

A block is a run of lines between blank lines. comando decides where it goes and shows it in the gutter before it runs: ⌘⏎ lets comando decide, ⌘⇧⏎ sends it to Claude, ⌥⏎ runs it as SQL.

## What it does

| | |
|---|---|
| **One editor, two targets** | SQL and prompts in one Markdown file per panel, each block coloured as what it is (SQL only while the panel's database is open). SQL completion from the live schema. |
| **Claude Code, headless** | One `claude -p` process per session: start, resume, fork, start fresh, compact, clear, interrupt, stop; model and effort per session; each session's context and uptime on its row, its id in the row's tooltip, all copied with a click; stored conversations import from `~/.claude/projects`. A checkpoint button sends `/reflect` (or your command) and turns purple at 60% of the context window. |
| **A painted transcript** | Prompts, text, tool calls with results, nested subagents, notes and turn ends, painted with Java2D so long sessions stream within a frame. Thinking gives way to the answer; the conclusion reads in gold. |
| **Claude drives the window** | An MCP server inside the app: tables, plots, diffs, files, Markdown and trees on screen; text into the editor; SQL under a lexical policy (writes refused on production connections); the window's state. Sessions can prompt one another and read each other's transcripts. |
| **Sessions at a glance** | Tab icons for working, waiting on background work, needs you, failed and finished; the Dock bounces when you are needed or something failed. The status bar shows the plan's usage limits; no spend is shown anywhere. |
| **Databases** | PostgreSQL and QuestDB over the PostgreSQL wire protocol, with an embedded QuestDB one click away. One lane per connection, cancel within two seconds, results stored by column (millions of rows), plots and live plots, `EXPLAIN` trees, a schema explorer. |
| **Documents** | The folders you choose as trees in one tab, searchable by name and kept in sync while open; a double click opens a file in a panel to read and edit (Markdown rendered with Mermaid diagrams, an HTML page one click from the browser, source coloured by language); the folders are set in `folders.json`, and the tab follows its changes. |
| **Development tools** | A terminal per panel; Gradle runs with test results as a tree and failures sent to Claude. |
| **A Claude Code kit** | A Claude Code plugin ([`kit/`](kit/README.md)) for the way of working comando is built for: one memory for every project, a knowledge base and scratchpad kept current by `/reflect`, reviews by `/deep-review`. Tools ▸ Help ▸ Claude Code kit shows what it would change and installs it through Claude Code's own plugin commands. |

![comando's documents and approvals: a Markdown plan rendered with its Mermaid diagram, a diff Claude placed in the side pane, and two approval cards, one for a shell command and one for a question](docs/images/documents-and-approvals.png)

## Quick start

You need macOS, JDK 25 and the [Claude Code](https://github.com/anthropics/claude-code) CLI (tested with 2.1.296 and later 2.1 releases).

```sh
./gradlew :app:run     # start comando
./gradlew build        # compile and test
./gradlew :app:dmg     # an unsigned macOS disk image in app/build/dmg
```

Then open **Tools ▸ Help ▸ Tutorial**: *comando in ten minutes* takes you from an empty window to a database, a Claude session, approvals and plots. [Build and run](docs/build-and-run.md) lists every task and setting.

Your data stays in `~/.comando`: panels as Markdown files, connections, sessions, logs. Connection passwords go to the macOS keychain.

## Documentation

| Document | Contents |
|---|---|
| [Tutorial](docs/tutorial.md) | comando in ten minutes, step by step (also Tools ▸ Help ▸ Tutorial) |
| [Build and run](docs/build-and-run.md) | prerequisites, Gradle tasks, settings |
| [Workbench](docs/workbench.md) | panels and bindings, running text, the database, the tools, the Files tab and git changes, what is kept on disk |
| [Claude](docs/claude.md) | the `claude -p` bridge, the MCP tools, approvals and safety |
| [UI](docs/ui.md) | the kit, commands and keys, widgets, the painted transcript |
| [Architecture](docs/architecture.md) | modules, state and intents, ports, threads, ownership, code rules, quality |
| Module READMEs | one per module, on its classes and its interface with the rest: [core](core/README.md), [testkit](testkit/README.md), [db](db/README.md), [claude](claude/README.md), [mcp](mcp/README.md), [tools](tools/README.md), [ui](ui/README.md), [app](app/README.md) |
| [Decisions](docs/adr/README.md) | one record per decision, dated, with who decided |

## Built with

Java 25 and Swing, FlatLaf, jsvg, RSyntaxTextArea, JediTerm and pty4j, commonmark-java, Jackson, the PostgreSQL JDBC driver and QuestDB. Eight Gradle modules (core, db, claude, mcp, tools, ui, app, testkit); CI builds, tests and replays the UI on macOS and Linux.

## Status

In daily use by its author; version 2.0.0-SNAPSHOT, not yet released. The macOS build is unsigned.

comando is under the [Apache License 2.0](LICENSE). Third-party components keep their licences: JediTerm (LGPL-3.0) and pty4j (EPL-1.0) for the terminal, FlatLaf, jsvg (MIT), RSyntaxTextArea and AutoComplete, commonmark-java, java-diff-utils, Jackson, SLF4J, the PostgreSQL JDBC driver, QuestDB.
