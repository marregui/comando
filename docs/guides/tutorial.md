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
| **Menu bar** | **File** (panels and files), **Edit**, **Panel** (run, then the focused panel's **Session**, **Connection**, **Result** and **Tools** submenus), **View** (the window: sidebar, lower panel, zoom), **Help**. |
| **Sidebar**, left | three tabs: **Sessions** (Claude Code sessions; each row's controls connect or disconnect it, change its model or effort, and open its editor), **Connections** (databases) and **Scratchpad** (your `~/.claude/SCRATCHPAD`; one click on a file opens it in a panel: Markdown shows rendered, Mermaid diagrams drawn as pictures; the header's **Edit** button or **⌘⇧M** switches to the text, and **Preview** switches back; source files show in their language's colours). Sessions and Connections each have a `+` button. ⌘B or the `⋯` button at the end of the tabs hides it to a thin rail on the left edge; the rail's `⋯` brings it back. |
| **Panel tabs**, top | each tab is a panel: one editor with its own text. `+` or ⌘T adds one. |
| **Panel header** | two chips: the panel's **session** and its **connection**, each with a status dot (○ closed, ● open) and a button to start or connect it. **Run** and **Stop** on the right. |
| **Editor** | where you write SQL and prompts, mixed in one text. |
| **Lower tabs** | **Session** (the Claude transcript), **Approvals** (Claude's requests for permission), **Terminal** (a shell), **Results** (SQL output). Plots and kept results open more tabs here. |
| **Status bar**, bottom | the current message on the left (click it for the recent ones); your plan's usage (session, week, Fable; hover for the reset times; read every 5 minutes), the panel's session, connection and running work on the right. |

One idea runs through the whole tool: **a panel is bound to at most one connection and one session**. SQL you run in the panel goes to its connection; anything else goes to its session.

The command palette lists every command that can run now, with its key. Press **⌘⇧P**, type a few letters, press **⏎**. When you forget a key, use the palette, or **⌘⇧/** for the table of keys. The menus grey out what cannot run now.

## 2. A database

comando can run a QuestDB server for you, so you need nothing else installed.

1. Press **⌘⇧P**, type `embedded`, press **⏎** on **Start** (Panel ▸ Connection ▸ Embedded QuestDB).
   *You should see* "QuestDB is up: PG wire on 8812, console on http://localhost:9000" in the status bar after a few seconds; **Stop**, in the same places, stops it. The server keeps its data in `~/.comando/questdb` and listens on this machine only.
2. A new comando already has a connection, **local QuestDB**, bound to the panel. You will make your own to learn the dialog. Press **⌘⇧O** to open **Connections** and click **Add**. A new connection starts with QuestDB's defaults: host `localhost`, port `8812`, database `qdb`, user `admin`, password `quest`. The keyboard is in **Name**, its default selected: type `tutorial` and change nothing else. Click **Assign to panel**: it saves the new connection first, and the line above the buttons says so. Close the dialog with **Esc**. (**⏎** saves, **⌘⏎** saves and assigns; Esc asks before it drops unsaved changes.)
   *You should see* `tutorial ○` in the panel header and `○ tutorial` in the sidebar.
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
   *You should see* "Done." in Results, and below it a status line with the connection, the outcome and the times. The schema the completion uses reloads after a `create`, `alter` or `drop`.
3. Do the same on the `insert` block, then on the `select` block.
   *You should see* three rows, one per symbol, with counts adding up to 100 000.

What ran: the **block under the caret**, meaning the lines between two empty lines. If you select text, the selection runs instead. On an empty line, the nearest block above runs. The block that ran is tinted until your next edit; nothing is selected and the caret stays where it was, so you can keep typing. A block of `--` comments only has nothing to run.

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
*You should see* "Running on tutorial · 3 s" counting up while it runs, then "Cancelled." in Results. The database stops working on it, and the connection is ready for the next query.

## 4. Looking at results

1. After an empty line, type and run this block:

   ```
   select ts, price from trades where sym = 'NVDA' limit 2000;
   ```

2. Press **⌘J**.
   *You should see* a new lower tab, **plot · scratch** (a plot is named after its panel), with the prices over time. A timestamp column becomes the x axis; up to eight numeric columns become lines. **⌘⇧J** asks for an interval in seconds, then re-runs the query and redraws the plot at that interval, for data that changes.
3. Press **⌘⇧R** to keep the result in a tab of its own; the next query no longer replaces it.
4. Press **⌘⇧E** to explore the schema.
   *You should see* a side pane with the connection, its tables and each table's columns and types.

The Results grid holds millions of rows. **⌘F** searches the view in front of you (the one with the keyboard, else the lower tab in front), **⌘G** and **⌘⇧G** step through the matches, **⌘⇧C** copies it, **⌘⇧S** exports it. **⌘S** saves the panel now; panels also save as you type.

When a statement fails, Results shows the database's error as text you can select and copy. If the error names a position and the block was a single statement, the token there is underlined in the editor.

## 5. A Claude session

A session is a Claude Code process that comando starts for you in a working directory, with a model and a budget you choose.

1. Press **⌘⇧K** to open **Sessions**. With no session yet it opens on a new one; otherwise click **Add**. The keyboard is in **Name**, its default selected. Fill in:

   | Field | Value |
   |---|---|
   | Name | `tutor` |
   | Working directory | your `~/comando-tutorial` directory, typed in full or picked with `…` |
   | Model | `haiku` |
   | Budget (USD) | `0.50` |

   Leave the rest as it is; the line under each field says what it does. Click **Assign to panel**, then press **Esc**.
   *You should see* `tutor` in the panel header, in the sidebar (marked "· this panel"), and in the window title.
2. At the end of the editor, after an empty line, type `In one sentence, what is QuestDB?` and press **⌘⇧⏎**, which sends the block to the panel's session.
   *You should see* the session start (its dot turns to `●`), the lower panel switch to **Session**, and the answer stream in. Under the answer: the time it took and the cost so far. While the turn runs, the session chip counts the seconds. If your Claude Code setup has hooks, their output folds into grey "hooks · …" lines, one before the answer and one after it; click one to read it.
3. **⌘⏎** sends a block to Claude as well when the gutter shows the asterisk. At the end of the editor, after an empty line, type this block and press **⌘⏎**:

   ```
   Look up the columns of the trades table with db_describe, then put a SQL query that returns the hourly average price per symbol into my editor with ui_insert.
   ```

   The gutter shows the asterisk: the block is a question, not SQL. In the Session tab you see Claude call `db_describe` to read the table, then `ui_insert` to write into your editor.
   *You should see* the query appear at the end of the editor as a block of its own, and the status bar say how many lines tutor added, such as "tutor added 4 lines to scratch". Your question stays as it was, and your caret does not move. Click in the query and press **⌘⏎** to run it: one row per hour and symbol. Without the first half of the prompt, Claude has not seen the table and guesses column names; ask it to look first.

The **Session** tab shows the whole turn: your prompt, Claude's text, every tool call as a card with its input and result, and the answer that ends the turn in gold. A purple "Thinking…" line shows while Claude thinks, and gives way to whatever comes next. **⌘⇧.** interrupts a turn that is taking too long: the turn ends with a grey "interrupted" line and the session stays alive for the next prompt. While you read further up, new output does not scroll the view; **↓ Latest** jumps back, and sending a prompt follows again.

## 6. Claude and your database

The session can read your database and draw in your window through tools comando gives it. Reading is free; **writing needs your approval**.

1. At the end of the editor, after an empty line, type this block and press **⌘⇧⏎**:
   `Use db_run_sql to insert one row into trades: sym 'TEST', price 1.0, ts now().`
   *You should see* "1 waiting for approval" in the status bar, a count on the **Approvals** tab, and the tool's card in the Session tab reading "waiting for your approval · ⌃2".
2. Open **Approvals**. The card shows the connection and the exact statement.
   - **Run** (or **⏎**) executes it.
   - **Deny** (or **D**) refuses it. Type a reason in the note field first, and Claude receives it.

   The keys work anywhere in the card. In the note field, letters type; **⏎** denies with the note once you have typed one (on an empty note it runs), **⌘⏎** denies with the note, **Esc** returns to the card. Click **Deny** with the note `just testing`.
   *You should see* in the Session tab that Claude reports the denial and your note.
3. Send it again: put the caret in that block and press **⌘⇧⏎**. Click **Run** on the new card this time. Then run `select * from trades where sym = 'TEST';` yourself to see the row.
4. Ask for a picture:
   `Call ui_plot with title squares and one series named squares, x=[1,2,3,4,5] and y=[1,4,9,16,25].`
   *You should see* a **squares** plot tab. Claude can also show tables, diffs and files, put text into your editor, and switch tabs.
5. Ask about a result. At the end of the editor, after an empty line, type and run `select sym, count(), avg(price) from trades;`, then press **⌘⇧I**.
   *You should see* the result appended to the editor as a block, with the caret on the line under it. Type `Which symbol has the highest average?` on that line, with no blank line in between, and press **⌘⏎**. The rows and the question go to Claude together.

Other permission requests, such as a shell command or a file edit, appear as the same kind of card: **Allow once** (⏎ or A), **For session** (S), **Deny** (D or Esc), and for a shell command or a web fetch also **Always** (L). The card shows the rule the answer adds, such as `Bash(git status:*)` for a command or `Edit(//Users/you/project/**)` for an edit; an edit, a read or another tool can be allowed for the session only. **L** must be pressed twice within 3 seconds, because Always saves the rule in the project's local settings. **↑** and **↓** move between cards. Whether a request reaches you depends on your Claude Code permission settings. comando keeps those settings as they are; a database write from Claude always asks.

## 7. Terminal, panels, quitting

1. Press **⌃3** (or **⌘⌥3**, if Mission Control takes ⌃3). The **Terminal** tab starts a login shell in the session's working directory and gives it the keyboard. Type `ls` and press **⏎**.
2. Back in the editor (**⌘E**), at the end, after an empty line, type `date` and press **⌃⏎**. The line under the caret is typed into the terminal and runs there; a shell that is still starting gets it once it is ready.
3. Press **⌘T** for a second panel. It starts with the same connection and session as the panel you were in; change either from the chips in its header, or by double-clicking a name in the sidebar. **⌘1**…**⌘9** jump between panels.
4. Press **⌘W** to close the new panel, then **⌘⇧T**: it comes back with its text and bindings. comando asks first only when the panel's shell is running a program.
5. Press **⌘Q**. comando stops the session's process and the embedded QuestDB and saves everything. If a query, a turn, a Gradle run or a shell job is running, it asks first; while it closes, the window stays, greyed, titled "closing…".
6. Start it again with `./gradlew :app:run`.
   *You should see* your panels, their text and their bindings as you left them. Sessions come back stopped, with their transcripts, and the next prompt resumes the same conversation: type `What was the first question I asked you?` in the editor and press **⌘⇧⏎**.
   Connections come back closed, and the embedded QuestDB does not start by itself: start it again from the palette (step 2) before you connect. The window comes back where it was, with the sidebar shown or hidden as you left it. Views (plots, files, kept results) belong to one run: the side pane comes back empty.

Your data lives in `~/.comando`: `connections.json`, `sessions.json`, `panels.json`, and each panel's text as its own Markdown file in `panels/`, named after the panel (**⌘O** opens one as a panel; edit one in another editor and its panel shows the change within a second), the embedded QuestDB's data in `questdb/`, and the logs in `logs/`. Connection passwords are kept in the macOS keychain, not in the files.

## Cheat sheet

The Edit menu's standard keys (⌘Z ⌘⇧Z ⌘X ⌘C ⌘V ⌘A) work as in any macOS application.

| Key | Does |
|---|---|
| ⌘⏎ | run the selection or block where the gutter says |
| ⌥⏎ | run it as SQL |
| ⌘⇧⏎ | send it to Claude |
| ⌃⏎ | type the line into the terminal |
| ⌘. | stop the query, else interrupt the session's turn |
| ⌘⇧. | interrupt the session's turn |
| ⌘E | focus the editor |
| ⌃Space or ⌥⎋ | complete SQL (⌥⎋ where macOS takes ⌃Space for the input source) |
| ⌘J / ⌘⇧J | plot the last result / plot it and keep refreshing |
| ⌘⇧R | keep the result in a tab |
| ⌘⇧I | attach the result (or selected rows, or the error) to the editor |
| ⌘⇧E | explore the schema |
| ⌃1 ⌃2 ⌃3 ⌃4 (or ⌘⌥1…4) | Session, Approvals, Terminal, Results, with the keyboard |
| ⌃⇥ / ⌃⇧⇥ | next / previous lower tab |
| ⌘T / ⌘W / ⌘⇧T | new panel / close the panel, or the view that has the keyboard / reopen the last closed panel |
| ⌘O | open a file in a panel |
| ⌘⇧M | switch a Markdown panel between its text and the rendered page |
| ⌘⇧W | close the lower view in front |
| ⌘1…⌘9, ⌘⇧[ ⌘⇧] | go to a panel |
| ⌘⇧O / ⌘⇧K | Connections / Sessions |
| ⌘⇧P | command palette |
| ⌘B / ⌘\ | sidebar / side pane |
| ⌘F, ⌘G ⌘⇧G | find in the view, next, previous |
| ⌘⇧C, ⌘⇧S | copy, export the view |
| ⌘S | save the panel now |
| ⌥⌘G | run Gradle tasks |
| ⌘⇧/ | the table of every key |
| ⏎ or A / D or ⎋ / S / L twice / ↑ ↓ | on an approval card: allow, run, answer or approve / deny / for session, on a tool card / always, on a tool card that offers it / previous or next card |
| ⌘/ | comment or uncomment the line, in the editor |
| ⌘= ⌘- ⌘0 | zoom |
| ⌘Q | quit |

## Further reading, after the tutorial

These documents describe how comando works. You do not need them to use it.

| Document | Why read it |
|---|---|
| [Command dispatch](../design/command-dispatch.md) | the exact rules that decide SQL or prompt, and how to force either with a first line `-- @sql` or `-- @claude` |
| [Approvals and safety](../design/approvals-and-safety.md) | what Claude may do without asking, what counts as a read-only statement, production connections |
| [Build and run](build-and-run.md) | every Gradle task and setting, including a data root of your own (`COMANDO_HOME`) |
