# comando in ten minutes

A hands-on first session. You start comando, give it a database and a Claude Code session, and use both from one editor. Every step says what to do and what you should see. No prior knowledge of comando is assumed; you need to know a little SQL and to have used Claude Code once.

| Step | Time | You learn |
|---|---|---|
| [0. Before you start](#0-before-you-start) | — | what to install |
| [1. The window](#1-the-window) | 1 min | where everything is |
| [2. A database](#2-a-database) | 1 min | the embedded QuestDB, a connection, binding it to a panel |
| [3. SQL](#3-sql) | 2 min | running a block, the gutter, results, stopping a query |
| [4. Looking at results](#4-looking-at-results) | 1 min | plots, the schema explorer, keeping a result |
| [5. A Claude session](#5-a-claude-session) | 2 min | creating a session, prompts, the transcript |
| [6. Claude and your database](#6-claude-and-your-database) | 2 min | approval cards, Claude's window tools, asking about a result |
| [7. Terminal, panels, quitting](#7-terminal-panels-quitting) | 1 min | the shell, more panels, what is saved |
| [Cheat sheet](#cheat-sheet) | — | every key in one table |

## 0. Before you start

| You need | Check |
|---|---|
| macOS | — |
| JDK 25 | `java -version` prints 25 |
| Claude Code, logged in | `claude --version` works and `claude -p "hi"` answers |
| the comando repository | `cd ~/workspace/comando` |

Create an empty directory for the Claude session you will make in step 5:

```
mkdir -p ~/comando-tutorial
```

The session in step 5 uses the haiku model with a budget of 0.50 USD; the whole tutorial costs a few cents.

## 1. The window

Start comando from the repository:

```
./gradlew :app:run
```

The window opens with one panel, **scratch**, holding a short welcome text. Its parts, from left to right and top to bottom:

| Part | What it is |
|---|---|
| **Sidebar**, left | two lists: **Sessions** (Claude Code sessions) at the top, **Connections** (databases) below. Each list has a `+` button. ⌘B hides and shows it. |
| **Panel tabs**, top | each tab is a panel: one editor with its own text. `+` or ⌘T adds one. |
| **Panel header** | two chips: the panel's **connection** and its **session**, each with a status dot (○ closed, ● open) and a button to connect or start it. **Run** and **Stop** on the right. |
| **Editor** | where you write SQL and prompts, mixed in one text. |
| **Prompt bar**, under the editor | a one-line field for short messages to the panel's session. |
| **Lower tabs** | **Results** (SQL output), **Session** (the Claude transcript), **Approvals** (Claude's requests for permission), **Terminal** (a shell). Plots and kept results open more tabs here. |
| **Status bar**, bottom | the last message on the left; the panel's session, connection and running work on the right. |

One idea runs through the whole tool: **a panel is bound to at most one connection and one session**. SQL you run in the panel goes to its connection; anything else goes to its session.

The command palette lists every command with its key. Press **⌘⇧P**, type a few letters, press **⏎**. When you forget a key, use the palette.

## 2. A database

comando can run a QuestDB server for you, so you need nothing else installed.

1. Press **⌘⇧P**, type `embedded`, press **⏎** on "Start or stop the embedded QuestDB".
   *You should see* "QuestDB is up: PG wire on 8812, console on http://localhost:9000" in the status bar after a few seconds. The server keeps its data in `~/.comando/questdb` and listens on this machine only.
2. A new comando already has a connection, **local QuestDB**, bound to the panel. You will make your own to learn the dialog. Press **⌘⇧O** to open **Connections** and click **Add**. A new connection starts with QuestDB's defaults: host `localhost`, port `8812`, database `qdb`, user `admin`, password `quest`. Change only **Name** to `tutorial`. Click **Save**, then **Assign to panel**, then close the dialog.
   *You should see* `tutorial ○` in the panel header and in the sidebar.
3. Click the plug button next to `tutorial` in the panel header.
   *You should see* the dot turn to `●`. If the connection fails, the status bar says why in red.

Any PostgreSQL-wire database works the same way: PostgreSQL itself, or a QuestDB you run yourself. A connection marked **production** frames its panels in red and refuses every write Claude tries.

## 3. SQL

Read the welcome text in the editor, then clear it: click in the editor, press **⌘A**, then **Delete**.

One rule matters more than any other: **a block ends at an empty line**. Every new statement or question goes after an empty line (press **⏎** twice); text typed directly under another line joins that block and runs with it.

Type, or copy from this page, these three statements, with an empty line between them:

```
create table trades (sym symbol, price double, ts timestamp) timestamp(ts) partition by day;

insert into trades select rnd_symbol('AAPL', 'MSFT', 'NVDA'), 100 + rnd_double() * 50, timestamp_sequence('2026-01-01', 60000000L) from long_sequence(100000);

select sym, count(), avg(price) from trades;
```

1. Look at the **gutter**, left of the line numbers. Each block carries an icon: a **database** for SQL, Claude's **asterisk** for a prompt. comando decides before you run, and the icon tells you where the text will go.
2. Put the caret anywhere in the `create table` block and press **⌘⏎**.
   *You should see* "0 rows changed" in Results, and below it a status line with the connection, the outcome and the times.
3. Do the same on the `insert` block, then on the `select` block.
   *You should see* three rows, one per symbol, with counts adding up to 100 000.

What ran: the **block under the caret**, meaning the lines between two empty lines. If you select text, the selection runs instead. Running a block selects it, so you can see what ran; press an arrow key before you type, or your next keystroke replaces the block (⌘Z brings it back).

Three keys decide where text goes:

| Key | Sends the block |
|---|---|
| **⌘⏎** | where the gutter says |
| **⌥⏎** | to the database, as SQL, whatever the gutter says |
| **⌘⇧⏎** | to Claude, as a prompt, whatever the gutter says |

Now stop a query. Below the last block, after an empty line, type this block and run it with **⌘⏎**:

```
select sum(rnd_double()) from long_sequence(20000000000);
```

Press **⌘.** while it runs.
*You should see* "Cancelled." in Results. The database stops working on it, and the connection is ready for the next query.

## 4. Looking at results

1. After an empty line, type and run this block:

   ```
   select ts, price from trades where sym = 'NVDA' limit 2000;
   ```

2. Press **⌘J**.
   *You should see* a new lower tab, **plot**, with the prices over time. A timestamp column becomes the x axis; every numeric column becomes a line. **⌘⇧J** asks for an interval in seconds, then re-runs the query and redraws the plot at that interval, for data that changes.
3. Press **⌘⇧R** to keep the result in a tab of its own; the next query no longer replaces it.
4. Press **⌘⇧E** to explore the schema.
   *You should see* a side pane with the connection, its tables and each table's columns and types.

The Results grid holds millions of rows. **⌘F** searches the view in front of you, **⌘⇧C** copies it, **⌘S** exports it.

## 5. A Claude session

A session is a Claude Code process that comando starts for you in a working directory, with a model and a budget you choose.

1. Press **⌘⇧K** to open **Sessions**. Click **Add** and fill in:

   | Field | Value |
   |---|---|
   | Name | `tutor` |
   | Working directory | your `~/comando-tutorial` directory, typed in full or picked with `…` |
   | Model | `haiku` |
   | Budget (USD) | `0.50` |

   Leave the rest as it is. Click **Save**, then **Assign to panel**, then close the dialog.
   *You should see* `tutor` in the panel header, in the sidebar, and in the window title.
2. Click the **prompt bar** under the editor, type `In one sentence, what is QuestDB?` and press **⏎**.
   *You should see* the session start (its dot turns to `●`), the lower panel switch to **Session**, and the answer stream in. Under the answer: the time it took and the cost so far. If your Claude Code setup has hooks, their output appears as grey lines with a gear icon; that is normal.
3. Prompts can live in the editor too. At the end of the editor, after an empty line, type this block and press **⌘⏎**:

   ```
   Look up the columns of the trades table with db_describe, then put a SQL query that returns the hourly average price per symbol into my editor with ui_insert.
   ```

   The gutter shows the asterisk: the block is a question, not SQL. In the Session tab you see Claude call `db_describe` to read the table, then `ui_insert` to write into your editor.
   *You should see* the query appear in the editor under your question. Click in it and press **⌘⏎** to run it: one row per hour and symbol. Without the first half of the prompt, Claude has not seen the table and guesses column names; ask it to look first.
   Claude's inserted text ends without an empty line. Before you type the next block, press **⏎** twice at the end of the editor.

The **Session** tab shows the whole turn: your prompt, Claude's thinking, every tool call as a card with its input and result, and the answer. **⌘⇧.** interrupts a turn that is taking too long; the session stays alive for the next prompt. In the prompt bar, **↑** brings back earlier prompts.

## 6. Claude and your database

The session can read your database and draw in your window through tools comando gives it. Reading is free; **writing needs your approval**.

1. In the prompt bar, send:
   `Use db_run_sql to insert one row into trades: sym 'TEST', price 1.0, ts now().`
   *You should see* "1 waiting for approval" in the status bar and a count on the **Approvals** tab.
2. Open **Approvals**. The card shows the connection and the exact statement.
   - **Run** (or **⏎**) executes it.
   - **Deny** (or **D**) refuses it. Type a reason in the note field first, and Claude receives it.

   Click **Deny** with the note `just testing`.
   *You should see* in the Session tab that Claude reports the denial and your note.
3. Send it again: click the prompt bar, press **↑** to bring the prompt back, press **⏎**. Click **Run** on the new card this time. Then run `select * from trades where sym = 'TEST';` yourself to see the row.
4. Ask for a picture:
   `Call ui_plot with x=[1,2,3,4,5] and y=[1,4,9,16,25], title squares.`
   *You should see* a **squares** plot tab. Claude can also show tables, diffs and files, put text into your editor, and switch tabs.
5. Ask about a result. At the end of the editor, after an empty line, type and run `select sym, count(), avg(price) from trades;`, then press **⌘⇧I**.
   *You should see* the result appended to the editor as a block, with the caret on the line under it. Type `Which symbol has the highest average?` on that line, with no blank line in between, and press **⌘⏎**. The rows and the question go to Claude together.

Other permission requests, such as a shell command or a file edit, appear as the same kind of card: **Allow once** (⏎ or A), **For session** (S), **Always** (L), **Deny** (D or Esc). Whether a request reaches you depends on your Claude Code permission settings. comando keeps those settings as they are; a database write from Claude always asks.

## 7. Terminal, panels, quitting

1. Press **⌃4**. The **Terminal** tab opens a login shell in the session's working directory. Type `ls` and press **⏎**.
2. Back in the editor (**⌘E**), at the end, after an empty line, type `date` and press **⌃⏎**. The line under the caret is typed into the terminal and runs there.
3. Press **⌘T** for a second panel. It starts with the same connection and session as the panel you were in; change either from the chips in its header, or by double-clicking a name in the sidebar. **⌘1**…**⌘9** jump between panels.
4. Press **⌘Q**. comando stops the session's process and the embedded QuestDB and saves everything.
5. Start it again with `./gradlew :app:run`.
   *You should see* your panels, their text and their bindings as you left them. Sessions come back stopped, with their transcripts, and the next prompt resumes the same conversation: ask `What was the first question I asked you?` in the prompt bar.
   Connections come back closed, and the embedded QuestDB does not start by itself: start it again from the palette (step 2) before you connect.

Your data lives in `~/.comando`: `connections.json`, `sessions.json` and `panels.json`, plus the logs in `logs/`. Connection passwords are kept in the macOS keychain, not in the files.

## Cheat sheet

| Key | Does |
|---|---|
| ⌘⏎ | run the selection or block where the gutter says |
| ⌥⏎ | run it as SQL |
| ⌘⇧⏎ | send it to Claude |
| ⌃⏎ | type the line into the terminal |
| ⌘. | stop the query, else interrupt the session's turn |
| ⌘⇧. | interrupt the session's turn |
| ⌘L / ⌘E | focus the prompt bar / the editor |
| ⌘J / ⌘⇧J | plot the last result / plot it and keep refreshing |
| ⌘⇧R | keep the result in a tab |
| ⌘⇧I | attach the result (or selected rows, or the error) to the editor |
| ⌘⇧E | explore the schema |
| ⌃1 ⌃2 ⌃3 ⌃4 | Results, Session, Approvals, Terminal |
| ⌘T / ⌘W | new panel / close panel |
| ⌘1…⌘9, ⌘⇧[ ⌘⇧] | go to a panel |
| ⌘⇧O / ⌘⇧K | Connections / Sessions |
| ⌘⇧P | command palette |
| ⌘B / ⌘\ | sidebar / side pane |
| ⌘F, ⌘⇧C, ⌘S | find, copy, export the view |
| ⌘= ⌘- ⌘0 | zoom |
| ⌘Q | quit |

## Further reading, after the tutorial

These documents describe how comando works. You do not need them to use it.

| Document | Why read it |
|---|---|
| [Command dispatch](../design/command-dispatch.md) | the exact rules that decide SQL or prompt, and how to force either with a first line `-- @sql` or `-- @claude` |
| [Approvals and safety](../design/approvals-and-safety.md) | what Claude may do without asking, what counts as a read-only statement, production connections |
| [Build and run](build-and-run.md) | every Gradle task and setting, including a data root of your own (`COMANDO_HOME`) |
