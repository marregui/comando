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
| macOS, Linux or Windows | — |
| comando, installed or built | installed from a release by Claude Code (`INSTALL.md` at the top of the repository), or a clone of the repository with JDK 25 (`java -version` prints 25) |
| Claude Code, logged in | `claude --version` works and `claude -p "hi"` answers |

Create an empty directory for the Claude session you will make in step 5:

```
mkdir -p ~/comando-tutorial
```

The session in step 5 uses the haiku model with a budget of 0.50 USD; the whole tutorial costs a few cents.

## 1. The window

Start comando: the installed application (Launchpad, the Start menu, or `comando` in a terminal on Linux), or, from
the repository:

```
./gradlew :app:run
```

The window opens with one panel, **scratch**, holding a short welcome text. With a kit installed (`INSTALL.md` §5), the
sessions run on it, the Files tab opens on its folders and the status bar names its version. The window's parts, from
left to right and top to bottom:

| Part | What it is |
|---|---|
| **Menu bar** | **Edit**, and **Actions**: the palette; restarting the shell and stopping live plots; starting or stopping the embedded QuestDB; the **Tutorial**, **Keyboard shortcuts**, the **Markdown sheet**, **Show the log** and **Reveal comando folder**. The rest has a key, a button or a tab's menu. |
| **Sidebar**, left | three tabs in one row, icons whose tooltips name them, in this order: **Sessions**, **Connections**, **Files**; a right click on a tab opens its menu. **Files** (folders you choose, at first the kit's folders, or Claude Code's own folder without a kit; one click opens or closes a folder, a double click opens a file in a panel, to read or edit, or shows the changes inside a folder; in the Scratchpad, a right click moves the selected files and folders to the Trash, taking their lines out of `INDEX.md`; in a git repository, changed files and the folders holding them are named in git's colours, a file git does not track yet is gold, with a dot after each folder on the way down to it until you open it, and what git ignores is left out; the tab itself has no buttons; the git repositories you add sit under one **Repositories** node; a folder's right click shows the changes inside it; the Files tab's own right click opens the windows that add, remove, reorder and set the folders (inside `~/.claude`) and the repositories), **Sessions** (Claude Code sessions, running first; a row is the session's state icon, which a click starts or stops, and its name; resting on the name shows its card, with its facts and a summary, and a right click its menu, which does the rest; drag a row to order them yourself; under them, while there is one, **Without a session** lists comando's own panels that belong to no session, open and closed) and **Connections** (databases, each row as a session's: its state icon, which a click connects or disconnects, and its name, red **prod** for production; resting on the name shows a card of its facts, without a summary; a double click binds it to the focused panel). A right click on the Sessions or Connections tab adds one; the Sessions tab's also imports stored conversations, sorts, and makes a new panel or reopens a closed one, the Connections tab's starts or stops the embedded QuestDB. ⌘B or the `⋯` button hides the sidebar to a thin rail on the left edge; the rail's `⋯` brings it back. |
| **Panel tabs**, top | each tab is a panel: one editor with its own text. `+` or ⌘T adds one; drag a tab along the row to move it. The tab's icon says what its session is up to: a spinner while it works, an hourglass while it waits on work it left running in the background (an agent, a shell command), a green check when it finished while you were on another tab, a red mark when it failed, a question mark while it waits for your answer; the last three put a `●` before the title until you look (the question mark stays until you answer). Rest the pointer on a tab, or on a session's row in the **Sessions** tab, for its card: its name in gold, then its facts, each once (state and what it does, model and effort, context, folder and branch, conversation, Claude Code version, connection), the Linear tickets the panel's text names (a double click opens one in your browser), and under **Summary**, in gold, two or three sentences Haiku wrote on what the panel is about: for a session's panel, the last paragraph you wrote there read with the session's last response, the rest of the panel as context; for a document, what it says. The summary is written the first time the card shows, again when it shows after the panel's text or the session's last response changed, and when you press ↻; never on a timer. Move the pointer off the card, or off the tab without going into the card, and it closes; ✕ or a click on it closes it too. |
| **Panel header** | two chips: the panel's **session** and its **connection**, each with a status dot (○ closed, ● open) and a button to start or connect it. Next to the session's plug: a button that starts a stopped session on a **new conversation** (shown when it has one to leave), and a **checkpoint** button that sends the session's checkpoint command (`/reflect` by default; change or clear it in the session dialog): once that turn has finished, the conversation is cleared, the **Session** tab shows only its summary, and the summary goes in front of your next prompt; while the session runs, **compact** its conversation. **Rewrite ‹panel› as a document** is in the session's menu in the sidebar. On the right, icons: **Preview** (Markdown), **Run**, **Stop** and **Clear**; hover one for its name. |
| **Editor** | where you write SQL and prompts, mixed in one text. |
| **Lower tabs** | **Session** (the Claude transcript), **Approvals** (Claude's requests for permission), **Terminal** (a shell), **Results** (SQL output). Plots and kept results open more tabs here. |
| **Uncommitted changes**, right, ⌘K | the side pane lists what the Files tab's git repositories hold that their last commits do not, in three groups (Changed, Untracked, Removed), each by folder; the window a file opens edits it (saved every second and on leaving, "● edited" then "✓ saved" beside its title, ⌘Z and ⌘⇧Z), shows Markdown rendered, rolls it back or removes it, and the view's menu does the same to every file selected; double-click a file to compare it with its last commit side by side in a window over comando's (F7 for the next difference, Esc to close); moving the selection moves that window along. |
| **Status bar**, bottom | the current message on the left (click it to go where it is about, such as the log at a failure's line, which flashes; right-click it for the recent ones); your plan's usage (session, week, Fable; hover for the reset times; read every 5 minutes), then Codex's (session, week) when Codex is installed, the panel's session, connection and running work on the right. Usage takes the warning colour at 75 % of a window and the danger colour at 90 %. |

One idea runs through the whole tool: **a panel is bound to at most one connection and one session**. SQL you run in the panel goes to its connection; anything else goes to its session.

The command palette lists every command that can run now, with its key. Press **⌘⇧P**, type a few letters, press **⏎**. When you forget a key, use the palette, or **⌘⇧/** for the table of keys: every command's, with its menu or area, then the keys of an approval card and of single places (find bar, transcript, sessions list, Files tab, dialogs). **Actions ▸ Markdown sheet** puts beside the editor what Markdown the preview draws, and how a prompt reaches Claude. The menus hold only what has no key, button or tab menu (the table above). Each item's tooltip says what it does; the menus grey out what cannot run now.

## 2. A database

comando can run a QuestDB server for you, so you need nothing else installed.

1. Press **⌘⇧P**, type `embedded`, press **⏎** on **Start embedded QuestDB** (also in the Actions menu).
   *You should see* "QuestDB is up: PG wire on 8812, console on http://localhost:9000" in the status bar after a few seconds; **Stop**, in the same places, stops it. The server keeps its data in `~/.comando/questdb` and listens on this machine only.
2. A new comando already has a connection, **local QuestDB**, bound to the panel. You will make your own to learn the dialog. Right-click the sidebar's **Connections** tab and choose **New connection…**: the dialog opens on a new connection. A new connection starts with QuestDB's defaults: host `localhost`, port `8812`, database `qdb`, user `admin`, password `quest`. The keyboard is in **Name**, its default selected: type `tutorial` and change nothing else. Click **Assign to panel**: it saves the new connection first, and the line above the buttons says so. Close the dialog with **Esc**. (**⏎** saves, **⌘⏎** saves and assigns; Esc asks before it drops unsaved changes.)
   *You should see* `tutorial ○` in the panel header and `○ tutorial` in the sidebar.
3. Click the plug button next to `tutorial` in the panel header.
   *You should see* the dot turn to `●` and the plug turn from grey to green. If the connection fails, the status bar says why in red.

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

1. Right-click the sidebar's **Sessions** tab and choose **New session…**: it opens the session dialog on a new session, titled **New session**. The keyboard is in **Name**, its default selected. Fill in:

   | Field | Value |
   |---|---|
   | Name | `tutor` |
   | Working directory | your `~/comando-tutorial` directory, typed in full or picked with `…` |
   | Model | `haiku` |
   | Budget (USD) | `0.50` |

   Leave the rest as it is; the line under each field says what it does. Click **Save**, then press **Esc**.
   *You should see* a new panel, **tutor**: a session comes with its own panel, titled with its name, and the two are one (renamed and removed together). `tutor` shows in the panel header, in the sidebar and in the window title.
2. In the **tutor** panel, type `In one sentence, what is QuestDB?` and press **⌘⇧⏎**, which sends the block to the panel's session.
   *You should see* the session start (its icon in the sidebar and on the tab turns Claude's orange), the lower panel switch to **Session**, and the answer stream in. Under the answer: the time it took. While the turn runs, the session chip counts the seconds. If your Claude Code setup has hooks, their output folds into grey "hooks · …" lines, one before the answer and one after it; click one to read it.
3. **⌘⏎** sends a block to Claude as well when the gutter shows the asterisk. At the end of the editor, after an empty line, type this block and press **⌘⏎**:

   ```
   Look up the columns of the trades table with db_describe, then put a SQL query that returns the hourly average price per symbol into my editor with ui_insert.
   ```

   The gutter shows the asterisk: the block is a question, not SQL. In the Session tab you see Claude call `db_describe` to read the table, then `ui_insert` to write into your editor.
   *You should see* the query appear at the end of the editor as a block of its own, and the status bar say how many lines tutor added, such as "tutor added 4 lines to tutor". Your question stays as it was, and your caret does not move. Click in the query and press **⌘⏎** to run it: one row per hour and symbol. Without the first half of the prompt, Claude has not seen the table and guesses column names; ask it to look first.

The **Session** tab shows the whole turn: your prompt in a bubble, every tool call as a card with its input and result, and everything else (Claude's text, notes, the turn's time, the "Thinking…" line while Claude thinks) in warm ivory on near-black, with headings, list marks and table heads in gold; the answer that ends a turn sits on a lifted ground with a gold bar beside it. The gold ingot beside the tab's Clear chooses the gold. The "Thinking…" line shows while Claude thinks and gives way to whatever comes next. **⌘⇧.** interrupts a turn that is taking too long: the turn ends with a grey "interrupted" line and the session stays alive for the next prompt. While you read further up, new output does not scroll the view; **↓ Latest** jumps back, and sending a prompt follows again.

## 6. Claude and your database

The session can read your database and draw in your window through tools comando gives it. Reading is free; **writing needs your approval**.

1. At the end of the editor, after an empty line, type this block and press **⌘⇧⏎**:
   `Use db_run_sql to insert one row into trades: sym 'TEST', price 1.0, ts now().`
   *You should see* "1 waiting for approval" in the status bar and the **Approvals** tab come forward with the card; the Session tab's card for the tool reads "waiting for your approval · ⌃2".
2. The card shows the connection and the exact statement.
   - **Run** (or **⏎**) executes it.
   - **Deny** (or **D**) refuses it. Type a reason in the note field first, and Claude receives it.

   The keys work anywhere in the card. In the note field, letters type; **⏎** denies with the note once you have typed one (on an empty note it runs), **⌘⏎** denies with the note, **Esc** returns to the card. Click **Deny** with the note `just testing`.
   *You should see* the Session tab come forward, the list being empty, with Claude reporting the denial and your note.
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
3. Press **⌘T** for a second panel. It starts with the same connection as the panel you were in and no session, since a session has one panel, its own; double-click `tutor` in the sidebar and the **tutor** panel comes forward. The new panel's session chip offers **New session ‹its title›…**, which would make it a session's own panel. Change the new panel's connection from the chip in its header. **⌘1**…**⌘9** jump between panels.
4. Press **⌘W** to close the new panel, then **⌘⇧T**: it comes back with its text and bindings. A closed panel is kept, with its file, until you remove it: the sidebar's Sessions tab lists it as closed under **Without a session**, and a double-click there reopens it. comando asks before closing only when the panel's shell is running a program. Right-click a tab for **Close others**, **Close to the right** and **Close to the left**: these leave open the tabs waiting for you and those whose shell runs a program.
5. Press **⌘Q**. comando stops the session's process and the embedded QuestDB and saves everything. If a query, a turn, a session's background work, a Gradle run or a shell job is running, it asks first; while it closes, the window stays, greyed, titled "closing…".
6. Start it again, as in step 1.
   *You should see* your panels, their text and their bindings as you left them. Sessions come back stopped, with their transcripts, and the next prompt resumes the same conversation: type `What was the first question I asked you?` in the editor and press **⌘⇧⏎**.
   Connections come back closed, and the embedded QuestDB does not start by itself: start it again from the palette (step 2) before you connect. The window comes back where it was, with the sidebar shown or hidden as you left it. Views (plots, files, kept results) belong to one run: the side pane comes back empty.

Your data lives in `~/.comando`: `connections.json`, `sessions.json`, `panels.json`, and each panel's text as its own Markdown file in `panels/`, named after the panel (**⌘O** opens one as a panel; edit one in another editor and its panel shows the change within a second), the embedded QuestDB's data in `questdb/`, this tutorial in `help/`, the Files tab's folders in `folders.json` (the Files tab's menu, Configure ~/.claude folders and Configure repositories, edits them), the files you opened in `opened-files` (a new file stops being marked gold once opened), and the logs in `logs/`; Actions ▸ Reveal comando folder opens it. Connection passwords are kept in the macOS keychain, not in the files.

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
| double-click | on a link anywhere (a rendered page, a panel's text, the transcript, a tool's result, a tab's card): a web address opens in the browser; a file, or a `[[name]]` note, opens in a panel |
| ⌘⇧W | close the lower view in front |
| ⌘1…⌘9, ⌘⇧[ ⌘⇧] | go to a panel |
| ⌘⇧O / ⌘⇧K | the focused panel's connection / session settings (a new one when the panel has none) |
| ⌘⇧P | command palette |
| ⌘B / ⌘\ | sidebar / side pane |
| ⌘K | show or hide Uncommitted changes in the side pane |
| ⌘F, ⌘G ⌘⇧G | find in the view, next, previous |
| ⌘⇧C, ⌘⇧S | copy, export the view |
| ⌘S | save the panel now |
| ⌥⌘G | run Gradle tasks |
| ⌘⇧/ | the table of every key; Actions ▸ Markdown sheet for Markdown |
| ⏎ or A / D or ⎋ / S / L twice / ↑ ↓ | on an approval card: allow, run, answer or approve / deny / for session, on a tool card / always, on a tool card that offers it / previous or next card |
| ⌘/ | comment or uncomment the line, in the editor |
| ⌘= ⌘- ⌘0 | zoom |
| ⌘Q | quit |

## Further reading, after the tutorial

These documents describe how comando works. You do not need them to use it.

| Document | Why read it |
|---|---|
| [Workbench](workbench.md#2-running-text) | the exact rules that decide SQL or prompt, and how to force either with a first line `-- @sql` or `-- @claude` |
| [Claude](claude.md#3-approvals-and-safety) | what Claude may do without asking, what counts as a read-only statement, production connections |
| [Build and run](build-and-run.md) | every Gradle task and setting, including a data root of your own (`COMANDO_HOME`) |
