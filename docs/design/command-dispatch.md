# Command dispatch

One editor holds SQL and prompts. Running text sends it to one of two places: SQL to the panel's connection, anything else to the panel's Claude session.

## 1. What runs

The selection runs when there is one. Otherwise the block under the caret runs: a block is a run of non-blank lines between blank lines, so a query and the question below it are two blocks. Fences carry no meaning here, so a stray fence cannot join the statements around it.

## 2. The classifier

`Classifier.classify` decides, in this order:

1. **Marker.** A first line `-- @sql` or `-- @claude` decides, and is removed before the text is sent.
2. **Slash.** A leading `/` is a Claude command (`/compact`, `/model sonnet`, a skill).
3. **SQL shape.** A leading SQL keyword followed by the shape that keyword takes is SQL, unless an English function word appears outside quotes or the text ends with `?` or `.`. The keywords are `SELECT`, `WITH`, `INSERT`, `UPDATE`, `DELETE`, `CREATE`, `ALTER`, `DROP`, `TRUNCATE`, `EXPLAIN`, `SHOW`, `COPY`, `VACUUM`, `REINDEX`, `SET`, `CANCEL`, `BACKUP`, `CHECKPOINT`, `SNAPSHOT`, `DECLARE`, `BEGIN`, `COMMIT`, `ROLLBACK`, each with its own shape (`SELECT` needs an expression, `UPDATE` a table and `SET`, `SHOW` an object, and so on).
4. **Otherwise** the text is a prompt.

Rule 3 is strict on purpose. "update the README" and "show me the log" start with keywords and are prompts: they do not have the shape, and they contain function words. Words are whole identifiers: letters, digits, `_` and `$` belong to the word, so `my_table`, `can_id` and `us_east` are not prose, and a word next to a dot is part of a qualified name (`i.k`). The pronoun counts only as a capital "I", so the alias `i` stays SQL. Quoted strings and `--` and `/* */` comments are skipped. A word with an apostrophe ("don't") counts as one word. A statement the classifier misses is forced with a key.

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

## 4. The gutter

Each block shows where it goes before it runs: a database icon for SQL, Claude's icon for a prompt. The icons follow the text as it is edited, 250 ms after the last keystroke.

## 5. The prompt bar

A one-line field under each panel's editor carries the short prompts that make most of the traffic ("go", "fix", an option number). Enter sends to the panel's session; ↑ and ↓ walk the history. While a turn runs, the bar says so and a prompt sent then is folded into the running turn by Claude Code ([Claude bridge](claude-bridge.md#3-turns)).

## 6. Attaching results

⌘⇧I appends to the editor, as a block of its own, what the panel's last run produced:

- the selected rows of the result grid, when rows are selected, up to 500;
- else the first 50 rows;
- the error, when the run failed.

The block names the SQL and the row counts and holds the rows as tab-separated text in a fence. It never contains a blank line, which would end the block: an empty row reads "(empty row)", a newline inside a cell reads "⏎", and blank lines of an error are dropped. The caret lands on the line after the fence, so a question typed there belongs to the same block; ⌘⏎ classifies the block as a prompt (it starts with prose) and sends the rows with the question. The text sent is the text on screen.

## 7. Where things land

SQL results fill the Results tab. A prompt's turn streams into the Session tab. A failed classification is never silent: the gutter showed the target before the run, and the Results or Session tab shows the outcome.
