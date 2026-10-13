# Development tools

The tools cover the work around a Claude session that otherwise needs another window: a shell, a build with its test results, a JFR recording, the scratchpad. Runs go through `ToolPort`, implemented by the `tools` module; the terminal lives in the UI because JediTerm is a Swing component.

## 1. Terminal

| Property | Value |
|---|---|
| Component | JediTerm (JetBrains) on a pseudo-terminal from pty4j |
| Shell | `$SHELL -l` (else `/bin/zsh`, else `/bin/sh`), so an app started from Finder gets the user's `PATH` |
| Environment | `TERM=xterm-256color`, `COLORTERM=truecolor`, `TERM_PROGRAM=comando`, `LANG` when unset |
| Instances | one per editor panel; the Terminal tab shows the focused panel's |
| Start | the first time the panel's terminal is shown, in the working directory of the panel's session, else the home directory |
| Exit | a bar offers a restart; "Restart the panel's shell" in the palette does the same |

| Key | Action |
|---|---|
| ⌃4 | shows the panel's terminal |
| ⌃⏎ in the editor | types the selection, or the line under the caret, into the panel's terminal and presses Enter |

The terminal is for what Claude does not do: interactive logins, git by hand, ssh.

## 2. Gradle runs

⌘⇧G asks for tasks and options (default `build`, then the last ones used) and runs them in the build of the focused panel's session: the nearest directory at or above the session's working directory that holds an executable `gradlew`.

| Step | Detail |
|---|---|
| Command | `gradle-serial <root> <tasks>` when the per-checkout lock wrapper is installed, else `<root>/gradlew --console=plain <tasks>` |
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

The jfrq launcher is `COMANDO_JFRQ`, else `~/workspace/jfr/cli/build/install/jfrq/bin/jfrq`. When it is missing, the console says how to build it.

## 4. The scratchpad

"Browse the scratchpad" shows `~/.claude/SCRATCHPAD` as a tree in the side pane: top-level documents first, then day folders newest first; in a day folder the `READ-` documents in reading order, then `ALPHA.md`, then the rest, with `work/` last; three levels deep. A file opens in the side pane, rendered when it is Markdown.
