# Decision records

One record per decision, dated, with the context, the decision and its consequences, and who decided. Records 1 to 16
are decisions D1 to D16 of the design plan of 2026-10-12 (kept outside this repository; R, U and M labels in them are
that plan's). Later records come from implementation and from Miguel's daily use. A decision is changed by a new record
that names the one it replaces, or, for a small change, by a dated paragraph at the end of the record (records 33, 35,
64); this index's status column says what replaced or amended it.

| Record | Decision | Decided by | Status |
|---|---|---|---|
| [1](0001-name-and-package.md) | Name and package | Miguel | accepted |
| [2](0002-swing-and-flatlaf-as-the-ui-toolkit.md) | Swing and FlatLaf as the UI toolkit | Miguel | accepted |
| [3](0003-keys-for-running-text.md) | Keys for running text | Miguel | accepted |
| [4](0004-a-session-open-in-another-terminal.md) | A session open in another terminal | Miguel | accepted |
| [5](0005-a-terminal-for-residual-shell-work.md) | A terminal for residual shell work | Miguel | accepted |
| [6](0006-jdk-25.md) | JDK 25 | Miguel | accepted |
| [7](0007-default-permission-mode.md) | Default permission mode | Miguel | accepted |
| [8](0008-the-lower-panel-follows-the-focused-panel.md) | The lower panel follows the focused panel | Miguel | accepted |
| [9](0009-running-a-prompt-on-a-stopped-session.md) | Running a prompt on a stopped session | Miguel | accepted |
| [10](0010-own-table-and-plot-on-java2d.md) | Own table and plot on Java2D | Miguel | accepted |
| [11](0011-the-documented-cli-route-to-claude-code.md) | The documented CLI route to Claude Code | Miguel | accepted |
| [12](0012-scope-of-the-first-milestone.md) | Scope of the first milestone | Miguel | accepted |
| [13](0013-sessions-configured-like-connections.md) | Sessions configured like connections | Miguel | accepted |
| [14](0014-mcp-over-http-on-localhost.md) | MCP over HTTP on localhost | Miguel | accepted |
| [15](0015-stopgap-transcript-in-m1.md) | Stopgap transcript in M1 | Miguel | accepted; replaced by 21 |
| [16](0016-unsigned-macos-build.md) | Unsigned macOS build | Miguel | accepted |
| [17](0017-system-logger-instead-of-questdb-s-logger.md) | System.Logger instead of QuestDB's logger | Claude | accepted |
| [18](0018-per-server-mcp-timeout-instead-of-progress-notifications.md) | Per-server MCP timeout instead of progress notifications | Claude | accepted |
| [19](0019-own-mcp-server-no-library.md) | Own MCP server, no library | Claude | accepted |
| [20](0020-passwords-in-the-macos-keychain-through-security.md) | Passwords in the macOS keychain through `security` | Claude | accepted |
| [21](0021-painted-transcript.md) | Painted transcript | Claude | accepted |
| [22](0022-no-parquet-export.md) | No Parquet export | Claude | accepted |
| [23](0023-sql-from-claude-lexical-policy-production-refused.md) | SQL from Claude: lexical policy, production refused | Claude | accepted |
| [24](0024-the-window-belongs-to-the-user.md) | The window belongs to the user | Claude | accepted; item 24.6 replaced by 25 |
| [25](0025-no-prompt-bar.md) | No prompt bar | Miguel | accepted |
| [26](0026-a-file-per-panel.md) | A file per panel | Miguel, Claude | file per panel and naming accepted; outside edits replaced by 27, close by 39, specs opened only from `panels/` by 29 |
| [27](0027-panels-reload-files-changed-outside.md) | Panels reload files changed outside | Miguel, Claude | accepted; deleted documents replaced by 34 |
| [28](0028-the-sidebar-has-three-tabs.md) | The sidebar has three tabs | Miguel, Claude | accepted; reading rules replaced by 29; a fourth tab, Panels, by 39; one row replaced by two, 40 |
| [29](0029-the-scratchpad-is-a-navigator.md) | The scratchpad is a navigator | Miguel, Claude | accepted; Markdown row replaced by 30, deletion by 34; fixed folder and always-on read by 49; its JFR icon removed by 54 |
| [30](0030-documents-open-rendered-and-coloured.md) | Documents open rendered and coloured | Miguel, Claude | accepted |
| [31](0031-the-status-bar-shows-plan-usage.md) | The status bar shows the plan's usage | Miguel, Claude | accepted; Codex's plan added 2026-10-27 |
| [32](0032-session-rows-carry-their-controls.md) | Session rows carry their controls | Miguel, Claude | accepted; edit control and plug icons replaced by 37 |
| [33](0033-thinking-gives-way-and-the-conclusion-is-gold.md) | Thinking gives way, and the conclusion is gold | Miguel, Claude | accepted; the gold superseded 2026-10-19 by one amber text on near-black |
| [34](0034-a-deleted-document-closes-its-panel.md) | A deleted document closes its panel | Miguel, Claude | accepted |
| [35](0035-mermaid-diagrams-drawn-by-mmdc.md) | Mermaid diagrams drawn by mmdc | Miguel, Claude | accepted; refresh amended 2026-10-18 |
| [36](0036-five-menus-by-subject.md) | Five menus, by subject | Miguel, Claude | superseded by 59; Help items carry icons since 47 |
| [37](0037-session-actions-act-on-the-selected-row.md) | Session and connection actions act on the selected row | Miguel, Claude | accepted; the bar replaced by menus, 74 |
| [38](0038-a-session-has-one-panel.md) | A session has one panel | Miguel, Claude | accepted |
| [39](0039-panels-are-kept-until-removed.md) | Panels are kept until removed | Miguel, Claude | accepted; where they are listed amended, 91 |
| [40](0040-one-resource-per-dialog-and-two-tab-rows.md) | One resource per dialog, and the sidebar's tabs in two rows | Miguel, Claude | accepted; the Scratchpad tab became Files, 49; the two rows replaced by one, 70 |
| [41](0041-a-session-can-prompt-another.md) | A session can prompt another | Miguel, Claude | accepted; consent replaced by 44 |
| [42](0042-tabs-show-their-sessions-state.md) | Tabs show their session's state, and comando has its own icon | Miguel, Claude | accepted; a waiting status added by 43 |
| [43](0043-a-session-waiting-on-background-work-shows-it.md) | A session waiting on background work shows it | Miguel, Claude | accepted |
| [44](0044-sessions-prompt-each-other-without-asking.md) | Sessions prompt each other without asking | Miguel, Claude | accepted |
| [45](0045-fresh-start-and-checkpoint-buttons.md) | A fresh start and a checkpoint, as buttons | Miguel, Claude | accepted; the checkpoint says when it is due since 46 |
| [46](0046-clear-buttons-text-coloured-by-kind-checkpoint-due.md) | Clear buttons, text coloured by its kind, a checkpoint that says when it is due | Miguel, Claude | accepted; its rule on the user's setup replaced by 50; the rows' start time moved to the copied details by 53 |
| [47](0047-help-opens-its-files-as-documents.md) | Help opens its files as documents, with icons of its own | Miguel, Claude | accepted |
| [48](0048-stalls-are-a-line-hangs-a-stack-the-heap-starts-small.md) | Stalls are a line, hangs a stack; the heap starts small | Miguel, Claude | accepted; its JFR row removed by 54 |
| [49](0049-the-files-tab-shows-folders-the-user-chooses.md) | The Files tab shows folders the user chooses | Miguel, Claude | accepted; bar, sync interval and order amended by 51; clicks and the index replaced by 52; bar, folder dialog and configuration row removed by 55 |
| [50](0050-comando-ships-a-claude-code-kit.md) | comando ships a Claude Code kit, and relies only on what it installed | Miguel, Claude | accepted; its JFR key removed by 54; own-setup and offer rows replaced by 61; tool paths by 62; install and setup replaced by 80 |
| [51](0051-folder-buttons-together-read-only-folders-icons.md) | Folder buttons together, read-only folders, an icon per folder | Miguel, Claude | accepted, the row "files" proposed; bar, files and read-only removed by 55; its row "files" replaced and the scratchpad icon added by 52 |
| [52](0052-files-open-on-a-double-click-the-bar-follows-the-selection.md) | Files open on a double click; the bar follows the selection and acts on files | Miguel, Claude | accepted; bar, tips, clone, remove and read-only removed by 55; clicks amended by 78 |
| [53](0053-session-rows-compact-clear-copy-and-an-order-of-your-own.md) | Session rows: compact, clear, copy, and an order of the user's own | Miguel, Claude | accepted |
| [54](0054-comando-knows-nothing-of-jfr.md) | comando knows nothing of JFR | Miguel, Claude | accepted |
| [55](0055-the-files-tab-only-shows-folders-json-configures-it.md) | The Files tab only shows; folders.json configures it; every file opens to edit | Miguel, Claude | accepted; the Trash for deletable folders, 63; configuration row replaced by 69 |
| [56](0056-html-opens-in-the-browser-not-in-comando.md) | HTML opens in the browser, not in comando | Miguel, Claude | accepted |
| [57](0057-a-light-editor-and-a-header-of-icons.md) | A light editor, and a panel header of icons | Miguel, Claude | accepted; the light editor withdrawn, 93 |
| [58](0058-markdown-shows-the-pictures-beside-it.md) | Markdown shows the pictures beside it | Miguel, Claude | accepted |
| [59](0059-three-menus-for-what-has-no-key.md) | Three menus, for what has no key or control | Miguel, Claude | accepted; Tool settings removed by 62; the bar replaced, 75; Claude Code kit… removed by 80 |
| [60](0060-about-is-a-card-worded-by-a-file.md) | About comando is a card worded by a file | Miguel, Claude | accepted |
| [61](0061-the-kit-overrides-a-setup-of-the-users-own-with-consent.md) | The kit overrides a setup of the user's own, with the user's consent | Miguel, Claude | accepted, not built; replaced by 80 |
| [62](0062-gradle-runs-by-convention.md) | Gradle runs by convention; no `tools.json` | Miguel, Claude | accepted; the command's `gradle-serial` found on the `PATH` by 80 |
| [63](0063-a-tabs-menu-closes-many-and-a-scratchpads-entries-go-to-the-trash.md) | A tab's menu closes many; a scratchpad's entries go to the Trash | Miguel, Claude | accepted |
| [64](0064-sub-agents-are-shown-live.md) | Sub-agents are shown live | Miguel, Claude | accepted; following and large files amended 2026-10-18 |
| [65](0065-keys-on-desktops-other-than-macos.md) | Keys on desktops other than macOS | Miguel, Claude | accepted |
| [66](0066-the-reducer-is-watched-as-the-edt-is.md) | The reducer is watched as the EDT is | Miguel, Claude | accepted |
| [67](0067-reflect-measures-what-it-does-to-the-memory.md) | `/reflect` measures what it does to the memory | Miguel, Claude | accepted; amended 2026-10-25: no fork |
| [68](0068-git-changes-and-a-side-by-side-diff.md) | Git changes and a side-by-side diff | Miguel, Claude | accepted; a repository's double click, the view's title and groups replaced, 71; window extended, 72; colours amended, 76; double click amended, 78 |
| [69](0069-a-window-edits-the-files-tab-folders.md) | A window edits the Files tab's folders | Miguel, Claude | accepted; where it opens replaced, 71; title, problems line and index creation amended, 73 |
| [70](0070-the-sidebars-tabs-in-one-row-with-a-menu-each.md) | The sidebar's tabs in one row, with a menu each | Miguel, Claude | accepted; the Files tab's menu replaced, 73; Hide the sidebar out of the tab menus, 74; the Panels tab folded into Sessions, 91 |
| [71](0071-repositories-in-the-files-tab.md) | Repositories in the Files tab | Miguel, Claude | accepted; window amended, 73; double click amended, 78 |
| [72](0072-the-comparison-window-edits-rolls-back-and-removes.md) | The comparison window edits, rolls back and removes | Miguel, Claude | accepted |
| [73](0073-the-claude-folders-window-explains-and-creates-the-index.md) | The ~/.claude folders window explains its fields and creates the index | Miguel, Claude | accepted |
| [74](0074-the-sidebars-lists-have-no-bar-of-buttons.md) | The sidebar's lists have no bar of buttons | Miguel, Claude | accepted |
| [75](0075-the-menu-bar-is-edit-and-actions-flat.md) | The menu bar is Edit and Actions, flat | Miguel, Claude | accepted; Claude Code kit… removed by 80 |
| [76](0076-new-files-are-gold-and-marked-from-the-top.md) | New files are gold and marked from the top | Miguel, Claude | accepted; marks amended, 78 |
| [77](0077-a-files-panel-keeps-its-extension.md) | A file's panel keeps its extension | Miguel, Claude | accepted |
| [78](0078-a-rows-marks-and-double-click-show-its-changes.md) | A row's marks and double click show its changes; one click opens a folder | Miguel, Claude | accepted; amends 52, 68, 71, 76 |
| [79](0079-a-notice-takes-you-to-what-it-is-about.md) | A notice takes you to what it is about | Miguel, Claude | accepted; amends the status bar's click |
| [80](0080-the-kit-is-a-claude-code-setup-comando-runs-its-sessions-on.md) | The kit is a Claude Code setup in a repository of its own, and comando runs its sessions on it | Miguel, Claude | accepted; replaces the install and setup rows of 50 and 61 |
| [81](0081-a-tab-shows-a-card-with-its-topic.md) | A tab shows a card with its topic | Miguel, Claude | accepted; replaces the panel tabs' tooltip; amended 2026-10-22 |
| [82](0082-threads-by-kind-with-a-budget.md) | Threads by kind, with a budget | Miguel, Claude | accepted; amends the architecture's thread rule |
| [83](0083-one-gold-chosen-beside-clear.md) | One gold, chosen beside Clear; serif headings; a turn's report is framed; tooltips and dark pages as answers | Miguel, Claude | proposed; amends the transcript's amber and record 76's gold |
| [84](0084-claude-shows-what-is-better-seen.md) | Claude shows what is better seen than read | Miguel, Claude | proposed; the kit's `show` skill |
| [85](0085-a-panel-rewritten-as-a-document.md) | A panel's text rewritten as a document, with one button | Miguel, Claude | proposed; its place amended by 86 |
| [86](0086-a-checkpoint-clears-and-carries-its-summary.md) | The Checkpoint button clears the conversation and carries its summary into the next prompt | Miguel, Claude | proposed; amends 46, 53 and 85 |
| [87](0087-a-session-row-shows-the-tabs-card.md) | A session row shows the tab's card, with the session's details | Miguel, Claude | proposed; amends 53 and 81 |
| [88](0088-a-session-and-its-panel-share-a-name.md) | A session and its own panel share a name, and are one | Miguel, Claude | proposed; amends 13 and 38 |
| [89](0089-a-session-row-is-one-line.md) | A session row is one line, its state an icon | Miguel, Claude | proposed; amends 53 and 87 |
| [90](0090-a-session-row-is-its-icon-and-name.md) | A session row is its icon and its name; the card says each fact once | Miguel, Claude | proposed; amends 87 and 89 |
| [91](0091-the-panels-tab-folds-into-the-sessions-tab.md) | The Panels tab folds into the Sessions tab | Miguel, Claude | accepted; amends 39 and 70 |
| [92](0092-a-connection-row-is-its-icon-and-name.md) | A connection row is its icon and its name, as a session's | Miguel, Claude | accepted; extends 90 to connections |
| [93](0093-comando-is-dark-throughout.md) | comando is dark throughout | Miguel, Claude | accepted; withdraws 57's light editor |
| [94](0094-links-in-both-styles-everywhere.md) | Links in both styles, followed everywhere at a double click | Miguel, Claude | accepted |
