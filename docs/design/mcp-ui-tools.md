# MCP UI tools

comando runs an MCP server inside its own JVM. Each Claude Code session it starts gets the server's address in its MCP configuration, so the session can change what the window shows, run SQL under a policy, and route its permission prompts to comando's cards.

## 1. The server

| Property | Value |
|---|---|
| Transport | streamable HTTP, `POST /mcp` on `127.0.0.1`, an ephemeral port chosen at start |
| Authentication | one bearer token per session, created when the session starts and dropped when it ends; the token identifies the calling session |
| Origin | a request whose `Origin` names a host other than `localhost`, `127.0.0.1` or `::1` is refused (403), so a web page cannot reach the server; the CLI sends no `Origin` |
| Protocol versions | `2025-03-26`, `2025-06-18`, `2025-11-25`; `initialize` answers the client's version when supported, else `2025-06-18` |
| Methods | `initialize`, `tools/list`, `tools/call`, `ping`; notifications get 202; anything else, including `server/discover` that Claude Code sends first, gets method-not-found |
| Threads | a virtual thread per request; a tool call never blocks the reducer |
| Implementation | JSON-RPC over the JDK's `HttpServer` and Jackson; no MCP library |

## 2. Tools

| Tool | Arguments | Effect |
|---|---|---|
| `approve` | `tool_name`, `input`, `tool_use_id` | Claude Code's permission prompt; shows a card and waits for the answer ([Approvals and safety](approvals-and-safety.md)) |
| `ui_show_table` | `title`, `columns`, `rows`, `placement` | a table view |
| `ui_plot` | `title`, `x_label`, `y_label`, `time_x`, `series` (`name`, `x`, `y`), `placement` | a plot view; with `time_x`, x is epoch milliseconds |
| `ui_show_view` | `type`, `title`, `data`, `placement` | a view of type `markdown`, `diff` (path, before, after), `text` (path, text, syntax, line), `tree` (nested label, detail, children), `table` or `plot` |
| `ui_set_editor` | `text`, `panel`, `force` | replaces a panel's text as one step the user's ⌘Z undoes |
| `ui_insert` | `text`, `panel`, `mode` (`append`, the default, as a new block at the end; `insert` as a new block right after the block that ran last from that editor, or at the end when nothing ran), `force` | inserts text as its own block; the user's caret and selection stay exactly where they were, and one ⌘Z takes back only the session's text |
| `ui_open_file` | `path`, `line` | shows a file read-only in the side pane at a line; a relative path resolves against the session's directory; at most 4 MB |
| `ui_notify` | `text`, `level` | status bar message and desktop notification |
| `ui_get_state` | — | the panels with bindings and last SQL run, the connections, the sessions, the open views |
| `ui_set_layout` | `panel`, `lower_tab`, `sidebar`, `side_pane`, `lower_share` | focuses a panel bound to this session (another session's panel is refused), and selects `lower_tab` with it, once the user pauses typing and only if the panel is still bound to this session then (the reply says so when the panel already has the focus); without `panel`, selects a lower tab (results, session, approvals, terminal, or a view) only while the user looks at a panel of this session; shows or hides panes and sets the lower panel's share of the height once the user has not typed for 2 s. View ▸ Restore layout puts the panes back as they were before the first arrangement not yet undone. Every argument is checked before anything changes |
| `ui_close_view` | `id` | closes a view this session placed; views the user opened or pinned are refused |
| `db_run_sql` | `sql`, `connection`, `max_rows`, `show`, `title` | runs SQL on a connection's lane and returns the first result's rows as tab-separated text; text with several statements always needs approval, runs whole, and returns only the first result; `show` also opens the rows as a table |
| `db_describe` | `table`, `connection` | a table's columns and types |

Without a `panel` argument, the editor tools use the focused panel when it is bound to the calling session, else the session's first panel. A `panel` bound to another session is refused unless `force` is set. Every edit posts a notice naming the session, the panel and what changed ("work added 4 lines to scratch"). Without a `connection`, the database tools use the connection of that panel. A session bound to no panel must name the panel or the connection; it never writes into another session's panel or runs SQL on its connection by default. Every call gets an answer: an unexpected failure inside a tool is returned as a tool error.

## 3. View specs

Every view argument is validated before an intent is sent; an invalid spec is a tool error that names the problem ("row 0 must be an array of 2 values", "x has 2 points, y has 1"), and nothing changes on screen.

| Limit | Value |
|---|---|
| Table rows | 100 000 |
| Plot points, all series together | 2 000 000 |
| SQL rows returned to Claude | 200 by default, `max_rows` up to 10 000 |
| Text returned to Claude | 64 kB |

A view placed by a session replaces that session's unpinned view with the same title and placement, so Claude can update a table in place; a session's update does not move the user to its tab. The first placement selects the new tab only when the session is the one bound to the focused panel; a session in another panel adds its tab and posts a notice ("work opened squares") instead, and its side views do not bring back a side pane the user hid. A title longer than 80 characters is clipped. A file the session opens is titled with its folder, `docs/README.md`, so two files with one name get two tabs. A view the user placed (a plot from ⌘J, a recording) shows its tab again when the command is repeated; a live plot's new data does not. The user can pin a view (it is then never replaced) or close it.

## 4. Instructions for the session

Each session starts with `--append-system-prompt` text that names the tools and when to use them: a view when a table, plot or diff shows a result better than text; explanations stay in text. The text is in `UiInstructions`.

## 5. Audit

Every tool call and every approval decision is appended to `audit/audit-<date>.jsonl` in the data root, with the session, the tool, the arguments (one JSON string, clipped to 2 000 characters) and the outcome.
