# Command dispatch

One editor holds SQL and prompts. Running text sends it to one of two places: SQL to the panel's connection, anything else to the panel's Claude session.

## 1. What runs

The selection runs when there is one. Otherwise the block under the caret runs: a block is a run of non-blank lines between blank lines, so a query and the question below it are two blocks. Fences carry no meaning here, so a stray fence cannot join the statements around it. With the caret on a blank line, the nearest block above runs. A block holding several SQL statements runs them all in one call; the Results tab shows the first one's result, and its status line says how many statements ran. When one of them fails, the driver reports only the failing statement's error, so the run fails with that error and the line "The block holds N statements; the ones before the failing statement may have run."

The block that ran is tinted until the next edit; it is not selected, and the caret stays where it was, so a session's insert or the next keystroke cannot replace it. A selection that ran stays selected, as any selection does: typing replaces it, and ⌘Z brings it back. After a run the editor scrolls back to the first column.

## 2. The classifier

`Classifier.classify` decides, in this order:

1. **Marker.** A first line `-- @sql` or `-- @claude` decides, and is removed before the text is sent.
2. **Slash.** A leading `/` followed by a letter is a Claude command (`/compact`, `/model sonnet`, a skill).
3. **Comments only.** A block left with only `--` and `/* */` comments has nothing to run: it shows no gutter icon, and ⌘⏎ or ⌥⏎ on it says "Nothing to run"; ⌘⇧⏎ still sends it to the session, as it forces a prompt.
4. **SQL shape.** A leading SQL keyword followed by the shape that keyword takes is SQL, unless an English function word appears outside quotes or the text ends with `?` or `.`. The keywords are `SELECT`, `WITH`, `INSERT`, `UPDATE`, `DELETE`, `CREATE`, `ALTER`, `DROP`, `TRUNCATE`, `EXPLAIN`, `SHOW`, `COPY`, `VACUUM`, `REINDEX`, `SET`, `CANCEL`, `BACKUP`, `CHECKPOINT`, `SNAPSHOT`, `DECLARE`, `BEGIN`, `COMMIT`, `ROLLBACK`, each with its own shape (`SELECT` needs an expression, `UPDATE` a table and `SET`, `SHOW` an object, and so on).
5. **Otherwise** the text is a prompt.

Rule 4 is strict on purpose. "update the README" and "show me the log" start with keywords and are prompts: the first lacks the shape (no `SET`), and both contain function words. Words are whole identifiers: letters, digits, `_` and `$` belong to the word, so `my_table`, `can_id` and `us_east` are not prose, and a word next to a dot is part of a qualified name (`i.k`). The pronoun counts only as a capital "I", so the alias `i` stays SQL. Quoted strings and `--` and `/* */` comments are skipped. A word with an apostrophe ("don't") counts as one word. A statement the classifier misses is forced with a key.

## 3. Keys

| Key | Runs | As |
|---|---|---|
| ⌘⏎ | selection or block | classified |
| ⌘⇧⏎ | selection or block | prompt |
| ⌥⏎ | selection or block | SQL |
| ⌃⏎ | selection or line | typed into the panel's terminal |
| ⌘. | — | stops the panel's SQL run, else interrupts its session's turn |
| ⌘⇧. | — | interrupts the session's turn |
| ⌘L | — | focuses the prompt bar |
| ⌘E | — | focuses the editor |

The run keys work while the keyboard is in the focused panel's editor or prompt bar (⌃⏎ in the editor only); elsewhere the key belongs to the component that has the keyboard (in an approval card's note field ⌘⏎ denies with the note; the terminal; the results grid). In the prompt bar ⌘⏎ and ⌘⇧⏎ send the prompt, and ⌥⏎ is a new line.

## 4. The gutter

Each block shows where it goes before it runs: a database icon for SQL, Claude's icon for a prompt. The icons follow the text as it is edited, 250 ms after the last keystroke.

## 5. The prompt bar

A field under each panel's editor carries the short prompts that make most of the traffic ("go", "fix", an option number). ⏎ sends to the panel's session; ⇧⏎ (or ⌥⏎) adds a line, and the field grows with the text, wrapped lines included, to six rows before it scrolls. ↑ on the first row and ↓ on the last walk the history (elsewhere they move one row), keeping the text being typed; the history holds the last 200 prompts of each session, shared by its panels and kept across restarts. Esc gives the keyboard back to the editor.

A prompt that cannot go stays in the field, outlined in red, and the status bar says why: the panel has no session, or the session is stopping. While a turn runs, the bar says so and a prompt sent then is folded into the running turn by Claude Code ([Claude bridge](claude-bridge.md#3-turns)); the session chip counts the turn's seconds and the prompts queued behind it.

## 6. Attaching results

⌘⇧I appends to the editor, as a block of its own, what the panel's last run produced:

- the selected rows of the result grid, when rows are selected, up to 500;
- else the first 50 rows;
- the error, when the run failed.

The block names the SQL and the row counts and holds the rows as tab-separated text in a fence. It never contains a blank line, which would end the block: an empty row reads "(empty row)", a newline inside a cell reads "⏎", and blank lines of an error are dropped. The caret lands on the line after the fence, so a question typed there belongs to the same block; ⌘⏎ classifies the block as a prompt (it starts with prose) and sends the rows with the question. The text sent is the text on screen.

## 7. Where things land

SQL results fill the Results tab; an error there is selectable text, and a position the database names ("Position: 38") is underlined in the editor until the next edit, when the block was a single statement (in a block of several, the position counts from the failing statement, which the driver does not name). A prompt's turn streams into the Session tab. A failed classification is never silent: the gutter showed the target before the run, and the Results or Session tab shows the outcome.
