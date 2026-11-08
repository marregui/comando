# 139. Find in files and conversations: a view, and one window to read a hit

| Date | Status | Decided by |
|---|---|---|
| 2026-11-07 | accepted; built | Miguel and Claude |

## 1. Context

Finding information is what the knowledge kept in comando's folders is for, and IntelliJ's Find in Files is the bar
Miguel set. comando had ⌘F in one view (`FindBar`, `Searchable`) and a filter by file name in the Files tab, and nothing
that looked inside files. The folders in daily use hold about 15 MB of Markdown; the stored conversations about 1 GB
(record 140). The Atlas decision bans a stored index and any work in the background, so every search reads what it
searches.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| where | ⌘⇧F, "Find in files…" in the palette and in a Find menu that holds the view's Find too: the Search view in the side pane, as ⌘K opens the Changes view; ⌘⇧F with the keyboard in the view closes it; elsewhere, the viewer included, it brings the view forward with the keyboard in its field | Miguel rejected the first build, a window with a flat list: it gave no orientation of where the information is, and shared nothing with the comparison it should resemble |
| what is searched | the Files tab's folders, their excludes and `FolderRoot.ALWAYS_EXCLUDED` honoured, hidden entries left out as that tab leaves them out, and the stored conversations; a Files and a Conversations toggle, both on | one question, two stores; a toggle is the way out when one store is noise |
| how a file is read | `FolderSearch`: names first, then `Mapped` and `ByteScan`, ASCII case-insensitive on the bytes in place; a NUL in the first 8 kB skips the file; the line number counts newlines up to the match; only the line's slice is decoded, clipped to 200 chars; one hit per line | record 140: map, scan in place, decode the slice needed; no text of a file exists as a String unless it is shown |
| no index | every search walks and reads; 15.1 MB of Markdown in 1,500 files answers in 28 ms (section 3) | at this size a search takes less time than the 200 ms pause after a keystroke, and the ban stands |
| who owns the threads | `Searcher`: one strand runs the sources in turn, another hands the hits over every 100 ms, so a stalled source still shows what it found; `FolderSearch` reads files on forks, at most half the cores, never the shared pool | the window and the sessions keep the other half; a batch is one repaint |
| a new search | it ends the running one by generation, and that sink hears nothing more, not even its hits still queued | a keystroke must not be overtaken by the search before it |
| the cap | 1,000 hits, in walk order; the 1,001st found sets "cut at 1,000" | more than anyone reads, and a cut search is the same each time |
| what cannot be read | a folder or file that fails counts as failed and the foot says "n not read"; the search goes on | a hit list that silently omits is worse than one that says so |
| the rows | `SearchTree`: each Files-tab folder, then its folder chain (a folder holding one folder joins it, as in the Changes view), each file with its count, each hit named by its snippet with its Markdown heading path and line as detail; the conversations last, each under its title, its hits named by who wrote them and when. Every row is open but those the user closed; the hit selected stays selected as hits arrive, rebuilt at most every 100 ms | where a hit is, folder, file and section, is what a flat list lacked; the Changes view is the sibling the user already reads |
| the keys | ↓ or ⏎ in the field moves to the first hit; moving the selection shows the hit in the preview tab without taking the keyboard; ⏎ or a double click gives the tab the keyboard (on a conversation: opens it in its session); F7 and ⇧F7 walk the hits in the view's order from the view or the preview tab | the comparison window's keys, so one habit serves both |
| reading and opening a hit | the preview tab of record 141: selecting a file's hit shows the file in comando's editor in one reused tab, every hit of the file marked and the selected one current, the keyboard left in the tree; ⏎ or a double click gives the tab the keyboard; Open keeps it as a tab that stays; a conversation's hit opens in the session that holds it, with the transcript's Find on the query, else the Import dialog; Show in Files tab reveals the file | the first build's floating window covered comando and hid the list (record 141) |
| the field | the main window's selection, one line of it, else the last query; both toggles off says there is nothing to search | as IntelliJ's window opens on it; a selection over several lines is no query |

## 3. Consequences

`ByteScan`, `FolderSearch`, `Searcher`, `ViewSpec.Search`, `SearchHub`, `SearchTree` and `SearchWidget` are added (the
floating `SearchViewer` of the second build was replaced by the preview tab, record 141); `FindBar.open(Searchable, String)` opens the Find on a query. A hit carries the folder walked to it and its
heading: the Markdown headings above a file's line (a `#` line inside a fence is code, `#tag` is a paragraph), or who
wrote a conversation's message and when. The Find menu takes the view's Find commands out of the Edit menu. Query and
toggles live in `SearchHub` while comando runs, the hits in the view; nothing is persisted, and the only intents are the
view's own `ShowView` and `CloseView`.

Measured on an Apple M2 Max (12 cores, 6 scanners), load average 4.7, from `search` to `done`, 31 runs after 5 of warm-up,
files in the page cache: 15.1 MB of generated Markdown in 1,500 files takes a median of 27.6 ms (27.0 to 28.9) for 20 hits,
27.5 ms (26.7 to 30.0) with no hit; a word on every line is cut at 1,000 hits in 7.1 ms. A read from cold disk was not
measured.

The forks read files on virtual threads, the kind comando has for a capped fan-out, though G-16.4 keeps file I/O off them:
comando has no bounded platform pool, the cap is the search's own, and carriers are not the common pool. Tested in
`ByteScanTest`, `MappedTest`, `FolderSearchTest`, `SearcherTest` and `SearchUiTest`.

A link in a folder is read where it leads, as the Files tab lists and opens it: the search shows nothing that tab does not.
The Import dialog lists the 500 newest conversations and the search reads all of them, so ⏎ on an older hit opens a dialog
without it; a store of 158 conversations does not reach that, and passing 500 is the moment to let the dialog filter.

The transcript's selection does not fill the field: the view is not a text component, and the command cannot read it
without a change there.

## 4. Alternatives

A stored index answers faster on far larger folders; banned, and not needed at 28 ms. Regular expressions and a
case-sensitive switch are IntelliJ's next options: left out until asked for, as the byte scan would need a second path. A
list that takes the keyboard needs a way back to the field for every correction, which the palette's shape avoids. Reading
files on the search strand alone needs no fork, and reads one file at a time. The first build, a floating window with a
flat list and a raw preview below it, was rejected in use (section 2, where).
