# `tools`

The development tools behind `core`'s `ToolPort`: Gradle runs with their console and test tree, and the Files tab's
folders read as trees and their entries moved to the Trash. Everything runs outside the reducer and the EDT and ends
in an intent. It depends on `core` only.

1. [Architecture](#1-architecture)
2. [Interface with the rest of the app](#2-interface-with-the-rest-of-the-app)
3. [Threads](#3-threads)
4. [Tests](#4-tests)

## 1. Architecture

| Class | Does |
|---|---|
| `ToolsAdapter` | the port: Gradle runs as child processes, folder reads, the folder poll, moves to the Trash |
| `GradleBuild` | the build a directory belongs to (the nearest directory at or above it with an executable `gradlew`) and the command: `<gradle-serial> <root> <tasks>`, else `<root>/gradlew --console=plain <tasks>` |
| `Args` | splits the tasks the user typed as a POSIX shell splits words, without expansion |
| `TestReports` | JUnit XML written since the run started, as a tree with failures first, each linked to its source line |
| `FolderTree` | a folder as a tree, ordered and bounded (12 levels; 20 000 names listed, 5 000 entries kept) |
| `ListingLines` | the lines a listing file (such as a scratchpad day's `ALPHA.md`) gives an entry, taken out once the entry went to the Trash |
| `ToolSettings` | where the tools are, by convention: the kit's `gradle-serial` when it is executable, and the JDK for child processes |

- **A Gradle run.** The process runs on a virtual thread that copies its output into the run's `Console` (past 4 MB
  the rest is held and its last 64 kB shown at the end) and sends `CommandFinished` with the exit code, the test tree
  and the failures. A stop sends SIGTERM to the process tree, SIGKILL 2 s later; output is read at most 2 s after the
  process exits.
- **Settings per run.** `ToolsAdapter` takes a `Supplier<ToolSettings>` and asks it at each run, so a kit installed
  while comando runs is used from the next run.
- **Folders.** `readFolders` reads once and sends each tree; `watchFolders` replaces the set the poll reads, and the
  poll sends a tree only when it changed. Reads are ticketed, so an older read never overwrites a newer one.
- **The Trash.** `deleteFiles` moves entries through a `ToolsAdapter.Trash`, then edits the folder's listing file and
  reads the open folders again. Nothing is deleted for good.

## 2. Interface with the rest of the app

```mermaid
flowchart LR
  ER["core EffectRouter"] -->|"ToolPort"| TA["ToolsAdapter"]
  APP["app"] -->|"pollFolders, settings supplier, Trash"| TA
  TA -->|"output"| CR[("ConsoleRegistry")]
  TA -->|"CommandFinished, FolderLoaded, Notify"| SINK["core IntentSink"]
  CR --> UI["ui console view"]
  SINK -->|"FolderLoaded → effect ShowFolder"| UIF["ui Files tab"]
```

**Provides.**

| Interface | Methods | Caller |
|---|---|---|
| `ToolPort` | `runGradle(runId, workDir, tasks)`, `cancelRun`, `forgetRun`, `readFolders`, `watchFolders`, `deleteFiles(folder, paths)` | the reducer's effects, through `EffectRouter` |
| `ToolsAdapter.pollFolders(every)` | starts the folder poll | `app` at start (every 5 s) |
| `ToolsAdapter.Trash` | `moveToTrash(file)`; `Trash.NONE` for tests and desktops without one | implemented in `ui` (`DesktopTrash`), passed in by `app`, so `tools` never imports Swing or AWT |

**Needs.** The `IntentSink`, the `ConsoleRegistry` it writes, a `Supplier<ToolSettings>` (`app` builds it from
`claude`'s `Kit.gradleSerial()`), and a `Trash`.

**Sends back.** `CommandFinished` (the reducer updates the run's console view and may ask for a desktop
notification), `FolderLoaded` (the reducer turns it into a `ShowFolder` effect for the Files tab), `Notify`.

The terminal is not here: JediTerm is a Swing component and lives in `ui`.

## 3. Threads

| Thread | Does |
|---|---|
| `gradle-<run>`, `tool-output-<run>`, `tool-kill-<run>` | virtual: the run's process, its output, the SIGKILL after a stop |
| `folder-read` | virtual: a read asked for now |
| `folder-poll` | platform, daemon: reads the watched folders every period |
| `file-trash` | virtual: moves to the Trash and the listing edits |

`close()` stops every run's process tree (SIGTERM, SIGKILL 2 s later) and the folder poll.

## 4. Tests

`ToolsAdapterTest` runs a fake `gradlew` (a shell script that prints its arguments and writes one failing JUnit report)
and folder reads in temporary directories; `ListingLinesTest` and `ToolSettingsTest` are unit tests.

Further reading: [Workbench §4.2](../docs/workbench.md#42-gradle-runs), [Workbench §5](../docs/workbench.md#5-the-files-tab),
[record 62](../docs/adr/0062-gradle-runs-by-convention.md).
