# comando in ten minutes

A first session, hands on. You start comando, give it a database and a Claude Code session, and use both from one
editor. Each step says what to do and what you should see. It assumes a little SQL and one earlier use of Claude Code,
and no knowledge of comando.

| Step | Time | You learn |
|---|---|---|
| [0. Before you start](#0-before-you-start) | — | what to install |
| [1. The window](#1-the-window) | 1 min | where everything is |
| [2. A database](#2-a-database) | 1 min | the machine's QuestDB, a connection, binding it to a panel |
| [3. SQL](#3-sql) | 2 min | running a block, the gutter, results, stopping a query |
| [4. Looking at results](#4-looking-at-results) | 1 min | plots, the schema explorer, keeping a result |
| [5. A Claude session](#5-a-claude-session) | 2 min | creating a session, prompts, the transcript |
| [6. Claude and your database](#6-claude-and-your-database) | 2 min | approval cards, Claude's window tools, asking about a result |
| [7. Terminal, panels, quitting](#7-terminal-panels-quitting) | 1 min | the shell, more panels, what is saved |
| [Cheat sheet](#cheat-sheet) | — | every key in one table |

## 0. Before you start

| You need | Check |
|---|---|
| macOS or Linux; Windows has a package and no test run | — |
| comando, installed or built | installed from a release (`INSTALL.md` at the top of the repository), or a clone of the repository and JDK 25 (`java -version` prints 25) |
| Claude Code, logged in | `claude --version` prints a version and `claude -p "hi"` answers |

Create an empty directory for the Claude session of step 5:

```
mkdir -p ~/comando-tutorial
```

## 1. The window

Start comando: the installed application (Launchpad, the Start menu, or `comando` in a terminal on Linux), or from the
repository:

```
./gradlew :app:run
```

The window opens with one panel, **scratch**, holding a short welcome text. With a kit, which the install offers to
create for you from the docs (`INSTALL.md` §5), the sessions run on the kit, and the Kit and Brain tabs show its folders,
the KIT row naming the kit's commit.

| Part | What it is |
|---|---|
| **Menu bar** | **Edit**, **Find** (the finds of the view in front, and **Find in files…**), and **Help**: the palette; **Tutorial**, **Keyboard shortcuts**, **Markdown sheet**, **Show the log**, **Show the Edge's log**, **Show QuestDB's log**, **Reveal comando folder**. Everything else has a key, a button or a tab's menu |
| **Tab row's right end** | stop refreshing plots, start or stop the machine's QuestDB, start or stop the comando Edge, the theme's swatch (themes and icon sets), **+** |
| **Sidebar**, left | seven tabs in one row, icons named by their tooltips: **Sessions**, **Connections**, **Kit**, **Brain**, **Scratchpad**, **Logbook**, **Instances**. A right click on a tab opens its menu. ⌘B, or the `⋯` button, hides the sidebar to a thin rail; the rail's `⋯` brings it back at its width, and its tab icons bring it back at that tab |
| **Panel tabs**, top | each tab is a panel: one editor with its own text. `+` or ⌘T adds one; drag a tab along the row to move it |
| **Panel header** | at the right, first the panel's **connection**: a database icon in its state's colour (grey closed, blue open, amber on its way, red failed) whose click connects or disconnects, and its name, whose click binds another; then **Outline** and **Preview** (Markdown), **Run**, **Stop**, **Locate**, **Clear**; rest the pointer on one for what a click does |
| **Editor** | where you write SQL and prompts, mixed in one text |
| **Lower tabs** | **Session** (the Claude transcript), **Approvals** (Claude's requests for permission), **Terminal** (a shell), **Results** (SQL output). Plots and kept results open more tabs here |
| **Uncommitted changes**, right, ⌘K | what the git repositories of your folders hold that their last commits do not ([§1.4](#14-uncommitted-changes)) |
| **Session bar**, under the lower tabs | the focused panel's **session** chip, its icon showing whether it runs and, once the session has written to a repository, that branch and repository; a plug button that starts it, and what it does ([§1.3](#13-the-session-bar)); on the right its model and effort, which you can change there, and its context with the checkpoint button |
| **Status bar**, bottom | the current message on the left; plan usage and the work running in every session and query on the right ([§1.5](#15-the-status-bar)) |

One idea runs through the whole tool: **a panel is bound to at most one connection and one session**. SQL you run in
the panel goes to its connection; anything else goes to its session.

The command palette lists every command that can run now, with its key: press **⌘⇧P**, type a few letters, press
**⏎**. **⌘⇧/** shows the table of keys: every command's, with its menu or area, then the keys of an approval card and
of single places (find bar, transcript, sessions list, folder tabs, dialogs). **Help ▸ Markdown sheet** shows, beside
the editor, what Markdown the preview draws and how a prompt reaches Claude. Each menu item's tooltip says what it
does; the menus grey out what cannot run now.

### 1.1 The sidebar

| Tab | Holds | Mouse |
|---|---|---|
| **Sessions** | Claude Code sessions in four groups, each under its heading: **Needs you**, **Working**, **To read** (finished work you have not looked at) and **Idle**; inside a group, running first. A row is the session's state icon and its name. Under the sessions, while there is one, **Without a session** lists comando's own panels that belong to no session, open and closed | a click on the icon starts or stops the session; a click on the ⓘ at the row's right edge shows its card (facts and a summary), which then follows the pointer onto other names until it closes; a double click on the name opens its panel, and under **To read** opens it at the report; a right click opens its menu, which does the rest; drag a row to order its group yourself |
| **Connections** | databases. A row is the connection's state icon (grey while closed, blue while connected) and its name, with a red **prod** for production; the name is in the accent colour while the focused panel uses it | a click on the icon connects or disconnects; a click on the ⓘ shows a card of its facts, without a summary; a double click on the name binds the connection to the focused panel |
| **Kit**, **Brain**, **Scratchpad** | the folders you choose, in three tabs of one kind: the Scratchpad tab holds the folders with the scratchpad icon, the Brain tab the other folders of the group Brain (KB and Memory, as configured; a folder of the brain that is not configured never shows), the Kit tab every other folder: at first the kit's, or Claude Code's own folder without a kit. In a git repository, an arrow follows a changed file and each folder on the way down to it, until you commit; a dot follows a new file git does not track yet and each folder on the way down to it, until you open it; names keep one colour, red only for a conflict; what git ignores is left out. Every folder lies inside Claude Code's folder, the kit's or the brain's; code repositories are read in an IDE | one click opens or closes a folder; a double click opens a file in a panel, to read or edit; a top-level row's right click shows the changes inside it |
| **Logbook** | the open tasks of the scratchpad's newest day folder, read from its `INDEX.md`: first **Needs you**, the tasks that wait on you, then each topic, newest first; a row is the task's name, then its number, the day it started and who asked for it when that is not you | a click on a row shows its card, which then follows the pointer (when it started, who asked, its ticket, state, next step and ask); a double click opens the task as a page, its tickets linked to Linear and its documents to their files; a right click shows it, opens its tickets in Linear, resumes it in a session, asks you its decisions there, or opens its documents |
| **Instances** | the other comando instances on this machine and what their sessions are doing, while the comando Edge runs; then the questions they asked each other | a click on a row shows its card |

The tabs' right-click menus:

| Tab | Menu |
|---|---|
| **Sessions** | New session…, Import stored conversations…, sorting, a new panel or a closed one reopened |
| **Connections** | New connection…, starting or stopping the machine's QuestDB and the comando Edge |
| **Kit** | the window that adds, removes, reorders and sets the folders; with a kit, the Kit jobs window |
| **Brain**, **Scratchpad** | the folders window |
| **Logbook** | none of its own; a task's row has its own menu |
| **Instances** | none of its own; an instance's row asks it, refuses its questions or forgets one that left |

### 1.2 The panel tabs and their cards

A tab shows one icon, which says what its session is doing; its title never changes:

| Icon | Means |
|---|---|
| a spinner | it works |
| an hourglass | it waits on work it left running in the background (an agent, a shell command) |
| a green check | it finished while you were on another tab |
| a red mark | it failed |
| a question mark | it waits for your answer |

The check and the red mark stay until you look at the tab or the session starts another turn, also across a restart; the question mark stays until you answer.

Rest the pointer on a tab, or click the ⓘ on a session's row in the **Sessions** tab, for its card: the name in the
tint; the facts, each once (state and what it does, model and effort, context, folder and branch, the repository the
session writes to with its branch and ticket, other repositories it wrote to, conversation, Claude Code version,
connection); the Linear tickets the panel's text names, once a `linear.app/<workspace>/issue/` link has
shown which workspace to use, each opened in your browser by a double click; and under **Summary**, in the tint, one or
two short sentences (at most 200 characters) Haiku wrote on what the panel is about. For a session's panel the summary
reads the last paragraph you wrote there with the session's last response, the rest of the panel as context; for a
document, what it says. The summary is written the first time the card shows, again when it shows after the panel's text
or the session's last response changed, and when you press ↻; never on a timer. The card closes when the pointer leaves
it, or leaves the tab without entering the card; ✕ or a click on the card closes it too.

### 1.3 The session bar

The bar under the lower tabs belongs to the focused panel's session. Next to its plug is **new conversation**; the
**checkpoint** sits at the right end, beside the context it depends on:

| Button | Does |
|---|---|
| **new conversation** | starts a stopped session on a new conversation; shown once it has one, greyed out while the session runs |
| **checkpoint** | sends the session's checkpoint command (`/reflect` by default; change or clear it in the session dialog). Once that turn has finished, it sends `/compact`: the same conversation goes on, smaller, and the **Session** tab keeps it. comando does the same by itself when a turn ends with the context at 40% of the session's window or more, once each time it crosses, and after that automatic checkpoint the session carries on with its work by itself; after the button it waits for your next prompt. **Checkpoint threshold…** in the palette changes the percentage, from 10 to 100. Until the checkpoint and its compaction have ended, the session's panel reads only and greys with its tab and the **Session** tab, whose grey lifts while the session has a card waiting for your answer; **Stop** (⌘.) interrupts it and you carry on in the same conversation, nothing compacted |

The context and the checkpoint turn purple together when a checkpoint is due: a turn ended at or past that same
threshold. Click the model or the effort to change it, even mid-session. To compact the conversation, use **Compact the
conversation** in the palette or type `/compact`. **Rewrite ‹panel› as a document** is in the session's menu in the
sidebar; the palette has it too, with **Checkpoint**, **Start a new conversation** and **Start or stop the session**, and
so the rest of the session's and the connection's menus: copy details, clone, remove, and a connection's test.

### 1.4 Uncommitted changes

⌘K shows or hides, in the side pane, what the git repositories of your folders hold that their last commits do not, in
three groups (Changed, Untracked, Removed), each by folder.

- Double-click a file to compare it with its last commit, side by side, in a window over comando's; F7 goes to the
  next difference, Esc closes. Moving the selection moves that window along.
- The window edits the file: it saves every second and when you leave, shows "● edited" then "✓ saved" beside its
  title, and undoes with ⌘Z and ⌘⇧Z. It shows Markdown rendered, and its menu rolls the file back or removes it.
- The view's menu rolls back or removes every selected file.

### 1.5 The status bar

On the left, the current message: click it to go where it is about (a failure's line in the log, which flashes);
right-click it for the recent messages. On the right: your Claude plan's usage (the session window, the week, and a
model's weekly window where the plan has one, each window saying when it resets, the week with its weekday and time: "Claude Session 5% (resets 7:19pm) · Week 43% (resets Thu 1:59pm) · Fable 17%"; read every 20 minutes), then
Codex's (session, week; "Codex Session 4% (resets 12:06) · Week 3% (resets Thu 13:00)") when Codex is installed, then the queries and sessions working, the requests waiting for
approval and the sessions with finished work to read. Click that count for the list; a click on a **to read** line
opens the session at its report. Usage takes the warning colour at 75% of a window and the danger colour at 90%.

### 1.6 Themes of your own

The theme button at the tab row's right end, a gilded square split between your theme's ground and its twin's, opens
the theme chooser under it: a **Dark** | **Light** switch over the six
themes of that mode, each drawn small in its own colours, and under Dark two more, Console amber and Console cyan,
which draw the window as an instrument console: frames with corner brackets, and gauges for the plan usage and the
context. Each light theme sits where its dark twin does. The toggle
(or **D** and **L**) turns the whole window to the twin of your theme and each card into its twin in place, the
chooser still open. Click a card, or move to it with the arrows and
press ⏎: the window takes that theme and the chooser stays open, so you can try the next; the last you chose is
kept. The ✕ at its top right, Esc, a click elsewhere or the button again closes it.

Under the cards, a row of **Icons** changes every icon in the window at once: click a set, or run **Icons: ‹set›** from the palette; the choice is kept like the theme ([Workbench §1.3](workbench.md#13-themes)).

To add a theme of your own, put a JSON file in the `themes` folder of comando's data root (`~/.comando/themes/`;
**Help ▸ Reveal comando folder** opens the root) and start comando again:

```json
{
  "id": "ink_rose",
  "label": "Ink and rose",
  "description": "Pale rose on violet ink: soft and warm, medium arousal.",
  "mode": "dark",
  "ground": {"label": "Ink", "color": "#14101A"},
  "tint": {"label": "Rose", "highlight": "#FFE3EC", "body": "#F2A7C0", "shade": "#B5627F"}
}
```

| Field | Means |
|---|---|
| `id` | lower case letters, digits and underscores; it names the theme's command (`theme.ink_rose`) and keeps your choice across restarts |
| `label` | the name in the chooser and the palette |
| `description` | optional: what the theme's colours do to the mind, in short, plain text of at most 140 characters; shown under the name in the card's tooltip and in the theme button's ([record 147](adr/0147-a-theme-says-what-it-does-to-the-mind.md)) |
| `mode` | `dark`, or `light` for a pale ground under dark text: the window takes the light look, and its syntax, terminal and plot colours are drawn dark enough to read |
| `twin` | optional: the id of the theme of the other mode it pairs with; the chooser puts the two in the same place |
| `ground` | the background of every panel; its `label` may be left out |
| `tint` | the accent in three steps, from the one that stands out most from the ground, the `highlight`, through the `body`, to the `shade`, as on polished metal: pale to deep on a dark ground, deep to pale on a light one; its `label` may be left out |

*You should see* the theme in the chooser, under its mode, in its twin's place or after the built-in ones. comando reads
the files only at start. A file that is not valid, repeats an id, or would make text hard to read (the tint's body below
7.5:1 on the ground, for one) is left out, and the status bar names the file and the reason. The fourteen built-in themes
are files of the same shape inside the app. [Workbench §1.3](workbench.md#13-themes) has the full rules.

## 2. A database

comando can run a QuestDB server for you, so you need nothing else installed.

1. Press **⌘⇧P**, type `machine's`, and press **⏎** on **Start the machine's QuestDB** (also the database button at
   the tab row's right end). *You should see* "QuestDB is up: PG wire on 8812, console on http://localhost:9000" in the
   status bar after a few seconds; when another instance, or your own metrics, started it already, the button shows it
   running. **Stop the machine's QuestDB**, in the same places, stops it, when this comando started it. The server is
   one per machine, keeps its data in `~/.comando-questdb` and listens on this machine only.
2. A new comando already has a connection, **local QuestDB**, bound to the panel. Make your own to learn the dialog:
   right-click the sidebar's **Connections** tab and choose **New connection…**. The dialog opens on a new connection
   with QuestDB's defaults: host `localhost`, port `8812`, database `qdb`, user `admin`, password `quest`. The keyboard
   is in **Name**, its default selected: type `tutorial` and change nothing else. Click **Assign to panel**: it saves
   the new connection first, and the line above the buttons says so. Close the dialog with **Esc**. In the dialog **⏎**
   saves, **⌘⏎** saves and assigns, and **Esc** asks before it drops unsaved changes. *You should see* `tutorial ▾`
   beside a grey database icon in the panel header, and **tutorial** in the sidebar with a grey database icon.
3. Click the database icon next to `tutorial` in the panel header.
   *You should see* the icon turn from grey to blue, and the sidebar's icon turn blue. If the
   connection fails, the status bar says why in red.

Any PostgreSQL-wire database works the same way: PostgreSQL itself, or a QuestDB you run yourself. A connection marked
**Production** frames its panels in red and refuses every write Claude tries.

## 3. SQL

Read the welcome text in the editor, then clear it: click in the editor, press **⌘A**, then **⌫**.

One rule matters more than any other: **a block ends at an empty line**. Start every new statement or question after
an empty line (press **⏎** twice); text typed directly under another line joins that block and runs with it.

Type, or copy from this page, these three statements, with an empty line between them:

```
create table trades (sym symbol, price double, ts timestamp) timestamp(ts) partition by day;

insert into trades select rnd_symbol('AAPL', 'MSFT', 'NVDA'), 100 + rnd_double() * 50, timestamp_sequence('2026-01-01', 60000000L) from long_sequence(100000);

select sym, count(), avg(price) from trades;
```

1. Look at the **gutter**, left of the line numbers. Each block carries an icon: a **database** for SQL, Claude's
   **asterisk** for a prompt. comando decides before you run, and the icon shows where the text will go.
2. Put the caret anywhere in the `create table` block and press **⌘⏎**.
   *You should see* "Done." in Results, and below it a status line with the connection, the outcome and the times.
   The schema the completion uses reloads after a statement that changes it (`create`, `alter`, `drop`).
3. Do the same on the `insert` block, then on the `select` block.
   *You should see* three rows, one per symbol, with counts adding up to 100 000.

What ran is the **block under the caret**: the lines between two empty lines. If you select text, the selection runs
instead. On an empty line, the nearest block above runs. The block that ran is tinted until your next edit; nothing is
selected and the caret stays where it was, so you can keep typing. A block of `--` comments alone has nothing to run.
A click on a block's icon in the gutter runs that block as **⌘⏎** would: the selection when it lies inside the block,
otherwise the whole block; the caret and a selection elsewhere stay.

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
*You should see* "Running on tutorial · 3 s" counting up while it runs, then "Cancelled." in Results. The database
stops working on it, and the connection is ready for the next query.

## 4. Looking at results

1. After an empty line, type and run this block:

   ```
   select ts, price from trades where sym = 'NVDA' limit 2000;
   ```

2. Press **⌘J**.
   *You should see* a new lower tab, **plot · scratch** (a plot is named after its panel), with the prices over time.
   The first timestamp column becomes the x axis, else the first numeric one; up to eight other numeric columns become
   lines. **⌘⇧J** asks for an interval in seconds, then runs the query again at that interval and redraws the plot,
   for data that changes.
3. Press **⌘⇧R** to keep the result, up to its first 100 000 rows, in a tab of its own; the next query no longer
   replaces it.
4. Press **⌘⇧E** to explore the schema.
   *You should see* a side pane with the connection, its tables, and each table's columns and types.

Results keeps up to 5 000 000 rows of a query. **⌘F** searches the view in front of you (the one with the keyboard,
else the lower tab in front), **⌘G** and **⌘⇧G** step through the matches, **⌘⇧C** copies the view, **⌘⇧S** exports
it. **⌘S** saves the panel now; panels also save as you type. **⌘⇧F** finds in the folders of the Kit, Brain and Scratchpad tabs and the stored
conversations, in the Search view of the side pane: type, press ↓ or ⏎ to reach the first hit, and the file shows in a preview tab as you move; ⏎ or a double click gives the tab the keyboard. ⌘⇧F again, with the keyboard in the view, closes it.

When a statement fails, Results shows the database's error as text you can select and copy. When the error names a
position and the block was a single statement, the token there is underlined in the editor.

## 5. A Claude session

A session is a Claude Code process that comando starts for you in a working directory, with a model you choose.

1. Right-click the sidebar's **Sessions** tab and choose **New session…**. The session dialog opens on a new session,
   titled **New session**, with the keyboard in **Name** and its default selected. Fill in:

   | Field | Value |
   |---|---|
   | Name | `tutor` |
   | Model | `haiku` |
   | Working directory, under **▸ More** | your `~/comando-tutorial` directory, typed in full or picked with `…` |

   Click **▸ More** to show **Working directory**. Leave the rest as it is; the line under each field says what it
   does. Click **Save**, which also closes the dialog.
   *You should see* a new panel, **tutor**. A session comes with its own panel, titled with its name, and the two are
   one: renamed and removed together. `tutor` shows in the session bar and in the sidebar.
2. In the **tutor** panel, type `In one sentence, what is QuestDB?` and press **⌘⇧⏎**, which sends the block to the
   panel's session. *You should see* the session start (its icon in the sidebar and on the tab turns Claude's orange),
   the lower panel switch to **Session**, and the answer stream in, with the time it took and when it ended under it
   (your prompt's bubble says when you sent it). While the turn runs,
   the session chip counts the seconds. If your Claude Code setup has hooks, their output folds into grey "hook · …"
   lines ("hooks · …" for several), one before the answer and one after it; click one to read it.
3. **⌘⏎** also sends a block to Claude when the gutter shows the asterisk. At the end of the editor, after an empty
   line, type this block and press **⌘⏎**:

   ```
   Look up the columns of the trades table with db_describe, then put a SQL query that returns the hourly average price
   per symbol into my editor with ui_insert.
   ```

   The gutter shows the asterisk: the block is a question, not SQL. In the Session tab, Claude calls `db_describe` to
   read the table, then `ui_insert` to write into your editor.
   *You should see* the query at the end of the editor, as a block of its own, and the status bar say how many lines
   tutor added, such as "tutor added 4 lines to tutor". Your question stays as it was, and your caret does not move.
   Click in the query and press **⌘⏎** to run it: one row per hour and symbol. Without the first half of the prompt,
   Claude has not seen the table and guesses the column names; ask it to look first.

The **Session** tab shows the whole turn: your prompt in a bubble, every tool call as a card with its input and
result, and everything else (Claude's text, notes, the turn's time, the "Thinking…" line while Claude thinks) in warm
ivory on near-black, with headings, list marks and table heads in the tint, Treasure gold at first. The answer that
ends a turn has a bar of the tint beside it. When a turn ends with an answer, the steps before it fold into one row,
such as "3 tools · 40 s", and the answer stays open under it; click the row to open the steps, click again to close
them, and ⌘F finds what is inside. The theme button at the top right changes the ground and tint
([§1.6](#16-themes-of-your-own)); the editors', terminal's and diffs' colours follow.

**⌘⇧.** interrupts a turn: the turn ends with an "interrupted" line and the session stays alive for the next prompt.
While you read further up, new output does not scroll the view, and the block at the top stays where it is; **↓ Latest**
or **End** jumps back, and sending a prompt follows again. **Home** goes to the first block of the conversation, **⌥↑**
and **⌥↓** to the previous and next prompt, **PgUp** and **PgDn** by a page; the editor keeps these keys for itself, so
click in the transcript first (or press ⌃1).

## 6. Claude and your database

The session can read your database and draw in your window through tools comando gives it. Reading runs without
asking; **writing needs your approval**.

1. At the end of the editor, after an empty line, type this block and press **⌘⇧⏎**:
   `Use db_run_sql to insert one row into trades: sym 'TEST', price 1.0, ts now().`
   *You should see* "1 waiting for approval" in the status bar and the **Approvals** tab come forward with the card;
   the Session tab's card for the tool reads "waiting for your approval · ⌃2".
2. The card shows the connection and the exact statement, and when it was asked: "asked 13:25 · now", then its age.
   - **Run** (or **⏎**) executes it.
   - **Deny** (or **D**) refuses it. Type a reason in the note field first, and Claude receives it.

   The keys work anywhere in the card. In the note field letters type; **⏎** denies with the note once it holds text
   (on an empty note it runs), **⌘⏎** denies with the note, **Esc** returns to the card. Click **Deny** with the note
   `just testing`.
   *You should see* the Session tab come forward, as the list is empty, with Claude reporting the denial and your note.
3. Send it again: put the caret in that block and press **⌘⇧⏎**. This time click **Run** on the new card. Then run
   `select * from trades where sym = 'TEST';` yourself to see the row.
4. Ask for a picture:
   `Call ui_plot with title squares and one series named squares, x=[1,2,3,4,5] and y=[1,4,9,16,25].`
   *You should see* a **squares** plot tab. Claude can also show tables, diffs and files, put text into your editor,
   and switch tabs.
5. Ask about a result. At the end of the editor, after an empty line, type and run
   `select sym, count(), avg(price) from trades;`, then press **⌘⇧I**.
   *You should see* the result appended to the editor as a block, with the caret on the line under it. Type
   `Which symbol has the highest average?` on that line, with no empty line in between, and press **⌘⏎**. The rows
   and the question go to Claude together.

Other permission requests, such as a shell command or a file edit, appear as the same kind of card:

| Button | Key | Does |
|---|---|---|
| **Allow once** | ⏎ or A | allows this call |
| **For session** | S | adds the card's rule for the rest of the session |
| **Always** | L, then L again within 3 s | adds the rule to the project's `.claude/settings.local.json`; offered for a shell command or a web fetch |
| **Deny** | D or Esc | refuses the call |

The card says in plain words what its answer adds, such as commands starting `git status`, or any edit under the edited
file's folder. **↑** and **↓** move between cards. Whether a request reaches you depends on
your Claude Code permission settings, which comando leaves as they are; a database write from Claude always asks.

## 7. Terminal, panels, quitting

1. Press **⌃3** (or **⌘⌥3**, if Mission Control takes ⌃3). The **Terminal** tab starts your login shell in the
   session's working directory and gives it the keyboard. Type `ls` and press **⏎**.
2. Go back to the editor with **⌘E**. At the end, after an empty line, type `date` and press **⌃⏎**. The line under
   the caret is typed into the terminal and runs there; a shell still starting gets it once it is ready.
3. Press **⌘T** for a second panel. It starts with the connection of the panel you were in and no session, as a session
   has one panel, its own. Double-click `tutor` in the sidebar, and the **tutor** panel comes forward. With the new
   panel in front, the session bar's chip offers **New session ‹its title›…**, which would make it a new session's own
   panel. Change the new panel's connection from the chip in its header. **⌘1**…**⌘9** jump between panels.
4. Press **⌘W** to close the new panel, then **⌘⇧T**: it comes back with its text and bindings. A closed panel is kept,
   with its file, until you remove it: the Sessions tab lists it as closed under **Without a session**, and a double
   click there reopens it. comando asks before closing a panel only when its shell runs a program. Right-click a tab
   for **Close others**, **Close to the right** and **Close to the left**; these leave open the tabs that wait for you
   and those whose shell runs a program.
5. Press **⌘Q**. comando stops the session's process and saves everything; the machine's QuestDB keeps running. If a query, a
   turn, a session's background work, a Gradle run or a shell job is running, it asks first. While it closes, the
   window stays, greyed, its title followed by "— closing…".
6. Start comando again, as in step 1.
   *You should see* your panels, their text and their bindings as you left them. Sessions come back stopped, with their
   transcripts, and the next prompt resumes the same conversation: type `What was the first question I asked you?` in
   the **tutor** panel and press **⌘⇧⏎**. Connections come back closed; the machine's QuestDB is still up, or the
   first metric written starts it again. The window comes back where it was, with the
   sidebar shown or hidden as you left it. Panels you moved to the right side are there again, and so are the Search and
   Changes views; other views (plots, files, kept results) belong to one run.

Your data lives in `~/.comando`; **Help ▸ Reveal comando folder** opens it.

| File or folder | Holds |
|---|---|
| `connections.json`, `sessions.json`, `folders.json`, `panels.json` | the connections, the sessions, the folders of the three folder tabs, the panels and their bindings |
| `panels/` | each panel's text as a Markdown file named after the panel. **⌘O** opens a file as a panel; edit a panel's file in another editor and the panel shows the change within a second |
| `settings.json` | the window's kept state: the theme, the window's size and dividers, the sidebar, the last Gradle tasks |
| `themes/` | your own themes ([§1.6](#16-themes-of-your-own)) |
| `opened-files` | the files you opened: a new file loses its dot once opened |
| `instances-seen.json` | the other comando instances seen, so the Instances tab lists those that left until you forget them |
| `questdb/` | the tables of the QuestDB comando embedded before, copied into `~/.comando-questdb` at its first start |
| `edge/` | the comando Edge: its download, its installation, its configuration, data and logs; `admin-password` holds the password of its web UI's user `admin` (on port 18080) |
| `help/` | this tutorial |
| `logs/` | comando's logs; **Help ▸ Show the log** opens them |

[Workbench §7.1](workbench.md#71-the-data-root) has the full rules. On macOS, with the default data root, connection
passwords are kept in the login keychain, not in the files; otherwise they stay in `connections.json`.

## Cheat sheet

The Edit menu's standard keys (⌘Z ⌘⇧Z ⌘X ⌘C ⌘V ⌘A) work as in any application.

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
| ⌘⇧I | attach the result (or the selected rows, or the error) to the editor |
| ⌘⇧E | explore the schema |
| ⌃1 ⌃2 ⌃3 ⌃4 (or ⌘⌥1…4) | Session, Approvals, Terminal, Results, with the keyboard |
| ⌃⇥ / ⌃⇧⇥ | next / previous lower tab |
| ⌘T / ⌘W / ⌘⇧T | new panel / close the view that has the keyboard, else the panel / reopen the last closed panel |
| ⌘O | open a file in a panel |
| ⌘⇧M | switch a Markdown panel between its text and the rendered page |
| ⌘⇧W | close the lower view in front |
| ⌘1…⌘9, ⌘⇧[ ⌘⇧] | go to a panel: the main tabs first, then the right side's |
| ⌘⇧O / ⌘⇧K | the focused panel's connection / session settings (a new one when the panel has none) |
| ⌘⇧P | command palette |
| ⌘B / ⌘\ | sidebar / side pane |
| ⌘K | show or hide Uncommitted changes in the side pane |
| ⌘F, ⌘G ⌘⇧G | find in the view, next, previous |
| ⌘⇧F | find in files and conversations |
| F7 / ⇧F7 | the next / previous hit of the search, from the Search view or the preview tab; the next / previous file in Uncommitted changes; the next / previous difference in the comparison window |
| Home / End | the Session tab's first block / its latest, following it |
| ⌥↑ / ⌥↓ | the Session tab's previous / next prompt |
| PgUp / PgDn | a page up / down in the Session tab |
| ⌘⇧C, ⌘⇧S | copy, export the view |
| ⌘S | save the panel now |
| ⌥⌘G | run Gradle tasks |
| ⌘⇧/ | the table of every key; Help ▸ Markdown sheet for Markdown |
| ⌘/ | comment or uncomment the line, in the editor |
| ⌘= ⌘- ⌘0 | zoom |
| ⌘Q | quit |
| double click | on a link anywhere (a rendered page, a panel's text, the transcript, a tool's result, a tab's card): a web address, or an HTML page, opens in the browser; any other file, or a `[[name]]` note, opens in a panel |

On an approval card:

| Key | Does |
|---|---|
| ⏎ or A | the green answer: allow, run, answer, approve |
| D or ⎋ | deny; ⎋ first cancels a pending Always |
| S | a tool card: allow for the session |
| L, then L again within 3 s | a tool card: allow always |
| ↑ ↓ | the card above, the card below |

## Further reading

These documents describe how comando works; using it does not need them.

| Document | Read it for |
|---|---|
| [Workbench](workbench.md#2-running-text) | the rules that decide SQL or prompt, and how a first line `-- @sql` or `-- @claude` forces either; every gesture of the window |
| [Claude Code](claude-code.md) | what Claude may do without asking, what counts as a read-only statement, production connections |
| [Development](development.md) | every Gradle task and setting, including a data root of your own (`COMANDO_HOME`) |
