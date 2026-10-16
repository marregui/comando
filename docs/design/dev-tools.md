# Development tools

The tools cover the work around a Claude session that otherwise needs another window: a shell, a build with its test results, a JFR recording, the folders of notes and documents. Runs go through `ToolPort`, implemented by the `tools` module; the terminal lives in the UI because JediTerm is a Swing component.

## 1. Terminal

| Property | Value |
|---|---|
| Component | JediTerm (JetBrains) on a pseudo-terminal from pty4j |
| Shell | `$SHELL -l` (else `/bin/zsh`, else `/bin/sh`), so an app started from Finder gets the user's `PATH` |
| Environment | `TERM=xterm-256color`, `COLORTERM=truecolor`, `TERM_PROGRAM=comando`, `LANG` when unset |
| Instances | one per editor panel; the Terminal tab shows the focused panel's |
| Start | the first time the panel's terminal is shown, in the working directory of the panel's session, else the home directory |
| Exit | a bar offers a restart, naming the directory of the panel's session as it is when the bar shows; Restart, and "Restart the panel's shell" in the palette, start there |
| Input | text sent to a starting shell (⌃⏎) waits for its first output and 200 ms of quiet, at most 3 s; text still waiting when the shell closes is dropped |
| Closing | closing the panel asks when the shell runs a job in front, when the shell was replaced by a program (`exec ssh …`), or when comando cannot check (`ps` failed); with job control off a job cannot be seen |

| Key | Action |
|---|---|
| ⌃3 | shows the panel's terminal |
| ⌃⏎ in the editor | types the selection, or the line under the caret, into the panel's terminal and presses Enter |

The terminal is for what Claude does not do: interactive logins, git by hand, ssh.

## 2. Gradle runs

⌥⌘G (Panel ▸ Tools ▸ Run Gradle tasks…, enabled when the panel has a session) asks for tasks and options, naming the directory they run in (default `build`, then the last ones used, kept across restarts with the last recording and jfrq arguments), and runs them in the build of the focused panel's session: the nearest directory at or above the session's working directory that holds an executable `gradlew`.

| Step | Detail |
|---|---|
| Command | `<wrapper> <root> <tasks>`, the wrapper being `tools.json`'s `gradleWrapper`, by default the `gradle-serial` of comando's Claude Code kit, which queues runs in one checkout; `<root>/gradlew --console=plain <tasks>` when it is `none` or no wrapper is found |
| Process | a child process on a virtual thread; standard error merged into standard output; `JAVA_HOME` set to comando's JDK when the environment has none |
| Arguments | split like a shell splits words, without expansion: quotes group (`--tests 'com.x.FooTest'`) and do not reach the tool |
| Output | a console view in the lower panel, filled as output arrives; output past 4 MB is held back, and its last 64 kB, with Gradle's summary, are shown when the run ends |
| Stop | the console's stop button, or closing the view: SIGTERM to the process and its descendants, SIGKILL 2 s later to any still alive |
| Output after exit | read for at most 2 s after the process exits; a child that keeps the output open (a background job, an orphan) does not keep the run going |
| Same command again | replaces the previous run's view unless it is pinned, stops that run when it still runs, and shows the new run's view |
| View closed mid-run | the run continues to its end; its output is released then |
| End | exit code and duration in the view header; a desktop notification when the run took 10 s or more and the window is not active |

After the run, `TestReports` reads the JUnit XML files (`TEST-*.xml` under a `test-results` directory) written since the run started; a folder it cannot read is skipped. The console shows them as a tree: totals at the root, failed tests first, passed tests up to 2 000. A failed test links to its test source at the line its stack trace names; a double click opens the file there. "Send failures to Claude", or "Send this failure to Claude" on one test, sends the failure messages and stacks (up to 60 lines each) as a prompt to the session of the panel the build was started from, or of the focused panel when that panel has been closed.

## 3. JFR recordings

| Command | Result |
|---|---|
| Open JFR recording… | one pass over the file with `jdk.jfr.consumer`: a table of event counts by type with first and last time, most frequent first; a CPU plot (JVM user, JVM system, machine) from `jdk.CPULoad`; a heap-after-GC plot from `jdk.GCHeapSummary`. Each plot appears only when the recording has the events |
| Run jfrq on a recording… | asks for a jfrq command and options (default `info`), split like shell words (`stalls --thread 'event-loop-*'`), runs `jfrq <command> <recording> <options>` in the recording's directory, shows the output in a console |

The jfrq launcher is `tools.json`'s `jfrq`, else `COMANDO_JFRQ`, else the first `jfrq` on the `PATH`. When it is missing, the console says where to set it.

`tools.json` in comando's folder holds the two paths; Panel ▸ Tools ▸ Tool settings opens it in a panel, written with both keys empty when missing. Each run reads it, so an edit applies to the next run; what is wrong with the file (not JSON, a second document, a value that is not text, a wrapper that is not executable) is the run's first console line, and the defaults apply ([record 50](../adr/0050-comando-ships-a-claude-code-kit.md)).

## 4. The Files tab

The sidebar's Files tab shows the folders the user chose, one top-level row each, named and with its path ([record 49](../adr/0049-the-files-tab-shows-folders-the-user-chooses.md)). A folder is configured like a connection, in the resource dialog with Save only: a unique name, the folder (a full path; `~` is the home folder), an index file inside it (optional), and names to leave out, comma-separated. The list is `folders.json` in comando's folder ([Persistence](persistence.md)); without it there is one folder, Claude Code's own `~/.claude`, which opens `CLAUDE.md` and leaves out Claude Code's transcripts, caches and per-session state.

Under each row, every level is ordered the same way: folders not named after a date first, by name; then date-named folders (`yyyy-MM-dd`) oldest first; then files by name, ignoring case. Hidden entries, build output and dependencies (`node_modules`, `build`, `target`, `__pycache__`, `out`) and the folder's own names are left out. A symbolic link to a folder is listed as a folder, "link to a folder, not followed", and not read into. A subfolder that cannot be read says why, in red, and the rest of the tree stands. A folder is read to 12 levels and 5,000 entries in all, and the read itself is bounded: at most 20,000 names are listed, hidden and left-out ones included, and at most 5,000 entries are looked at and sorted, so a folder of millions costs no more than one of 20,000 names (past a bound, which entries show is the file system's listing order). A folder past either bound ends with `…`, and one 12 levels down says it was not read. A row not yet read shows "reading…", an empty folder "empty", one that cannot be read why, in red.

| Entry | Icon |
|---|---|
| folder; `work` folder | folder; folder with a gear |
| `*plan*.md`; `READ-*`; a configured index file name (`ALPHA.md`); other Markdown | plan; book; index; Markdown |
| code (`.java`, `.py`, `.kt`, `.js`, …); data (`.json`, `.yaml`, `.xml`, …); shell; `.sql`; tables (`.csv`, `.tsv`); images; `.jfr`; diffs; `.txt`/`.log` | code; braces; terminal; database; table; image; chart; diff; text |

One click opens a folder or a file; Enter and the context menu's "Open in a panel" open a file too. Opening a folder's row by hand opens its index file in a panel when it exists; the first time a read folder's row is open, its newest date-named folder opens too. A text file of at most 2 MB opens in a new editor panel bound to that file ([Persistence](persistence.md#5-panel-files)); a file another panel holds brings that panel forward; a binary file shows a view in the side pane saying so; a text file over 2 MB shows read-only there, up to 4 MB, and a larger one is refused with a notice. A Markdown panel switches between the text and the rendered page (header button, ⌘⇧M).

Only open folders are kept in sync. The tab sends the names of the folders whose rows are open (`WatchFolders`) whenever that set changes, or a folder's settings change while it is open; the tools adapter reads a folder newly watched at once, and every 2 s, on its own thread (`folder-poll`), reads each watched folder and sends its tree (`FolderLoaded`, then `UiPort.showFolder`) only when something was added, removed, renamed or resized. A closed folder is not read. The refresh button, a folder row's "Read again" and the filter read at once and always send (`BrowseFolders`, on a virtual thread). A tree travels with the settings it was read with, and the reducer passes it on only while the folder still has them: a read of a folder since edited or removed is dropped, so a tree is never shown under settings it was not read with. Reads of one folder with the same settings are ordered by a ticket taken before the read: one that finishes after a newer one has sent sends nothing. The tree keeps the folders the user opened and the selection, matching nodes by their labels; a folder renamed and otherwise unchanged keeps its tree and stays open.

The filter above the tree finds entries by name, ignoring case, across every folder: while it has text a second tree shows the matches with the folders leading to them open (a matching folder stays closed, with its entries), and every folder never read is asked for once so the closed ones are searched too. A read that arrives while the text stands keeps what the user opened, closed and selected in that tree, and opens only a folder new to it. While the filter shows, the bar's edit, clone and remove act on the folder row selected in its tree. Esc clears it, and the tree is as the user left it. View ▸ Files shows the tab and puts the keyboard in the filter. The index file is looked for off the event thread; a folder row's menu offers it by name, and opening one that is missing says so in the status bar.
