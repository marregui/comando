# Decision records

One record per decision, dated, with the context, the decision and its consequences. Records 1 to 16 are decisions D1 to D16 of the design plan of 2026-10-12, which is kept outside this repository; R (requirements), U (open questions) and M1 to M4 (milestones) in the records are that plan's labels. Miguel took them on 2026-10-12 on the defaults the plan review proposed. Records 17 to 23 were taken by Claude during implementation and accepted by Miguel on 2026-10-13. Record 24 came from the UX review of 2026-10-13 and is proposed, for Miguel to accept or reverse. Records 25 to 29 come from Miguel's use of comando on 2026-10-14.

A decision is changed by a new record that names the one it replaces; the old record stays.

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
| [15](0015-stopgap-transcript-in-m1.md) | Stopgap transcript in M1 | Miguel | accepted |
| [16](0016-unsigned-macos-build.md) | Unsigned macOS build | Miguel | accepted |
| [17](0017-system-logger-instead-of-questdb-s-logger.md) | System.Logger instead of QuestDB's logger | Claude | accepted |
| [18](0018-per-server-mcp-timeout-instead-of-progress-notifications.md) | Per-server MCP timeout instead of progress notifications | Claude | accepted |
| [19](0019-own-mcp-server-no-library.md) | Own MCP server, no library | Claude | accepted |
| [20](0020-passwords-in-the-macos-keychain-through-security.md) | Passwords in the macOS keychain through `security` | Claude | accepted |
| [21](0021-painted-transcript.md) | Painted transcript | Claude | accepted |
| [22](0022-no-parquet-export.md) | No Parquet export | Claude | accepted |
| [23](0023-sql-from-claude-lexical-policy-production-refused.md) | SQL from Claude: lexical policy, production refused | Claude | accepted |
| [24](0024-the-window-belongs-to-the-user.md) | The window belongs to the user | Claude | proposed; item 24.6 replaced by 25 |
| [25](0025-no-prompt-bar.md) | No prompt bar | Miguel | accepted |
| [26](0026-a-file-per-panel.md) | A file per panel | Miguel, Claude | file per panel and naming accepted; outside edits replaced by 27, close by 39 |
| [27](0027-panels-reload-files-changed-outside.md) | Panels reload files changed outside | Miguel, Claude | accepted; deleted documents replaced by 34 |
| [28](0028-the-sidebar-has-three-tabs.md) | The sidebar has three tabs | Miguel, Claude | accepted; reading rules replaced by 29; a fourth tab, Panels, by 39; one row replaced by two, 40 |
| [29](0029-the-scratchpad-is-a-navigator.md) | The scratchpad is a navigator | Miguel, Claude | accepted; Markdown row replaced by 30, deletion by 34; fixed folder and always-on read by 49 |
| [30](0030-documents-open-rendered-and-coloured.md) | Documents open rendered and coloured | Miguel, Claude | accepted |
| [31](0031-the-status-bar-shows-plan-usage.md) | The status bar shows the plan's usage | Miguel, Claude | accepted |
| [32](0032-session-rows-carry-their-controls.md) | Session rows carry their controls | Miguel, Claude | accepted; edit control and plug icons replaced by 37 |
| [33](0033-thinking-gives-way-and-the-conclusion-is-gold.md) | Thinking gives way, and the conclusion is gold | Miguel, Claude | accepted |
| [34](0034-a-deleted-document-closes-its-panel.md) | A deleted document closes its panel | Miguel, Claude | accepted |
| [35](0035-mermaid-diagrams-drawn-by-mmdc.md) | Mermaid diagrams drawn by mmdc | Miguel, Claude | accepted |
| [36](0036-five-menus-by-subject.md) | Five menus, by subject | Miguel, Claude | accepted; Help items carry icons since 47 |
| [37](0037-session-actions-act-on-the-selected-row.md) | Session and connection actions act on the selected row | Miguel, Claude | accepted |
| [38](0038-a-session-has-one-panel.md) | A session has one panel | Miguel, Claude | accepted |
| [39](0039-panels-are-kept-until-removed.md) | Panels are kept until removed | Miguel, Claude | accepted |
| [40](0040-one-resource-per-dialog-and-two-tab-rows.md) | One resource per dialog, and the sidebar's tabs in two rows | Miguel, Claude | accepted; the Scratchpad tab became Files, 49 |
| [41](0041-a-session-can-prompt-another.md) | A session can prompt another | Miguel, Claude | accepted; consent replaced by 44 |
| [42](0042-tabs-show-their-sessions-state.md) | Tabs show their session's state, and comando has its own icon | Miguel, Claude | accepted; a waiting status added by 43 |
| [43](0043-a-session-waiting-on-background-work-shows-it.md) | A session waiting on background work shows it | Miguel, Claude | accepted |
| [44](0044-sessions-prompt-each-other-without-asking.md) | Sessions prompt each other without asking | Miguel, Claude | accepted |
| [45](0045-fresh-start-and-checkpoint-buttons.md) | A fresh start and a checkpoint, as buttons | Miguel, Claude | accepted; the checkpoint says when it is due since 46 |
| [46](0046-clear-buttons-text-coloured-by-kind-checkpoint-due.md) | Clear buttons, text coloured by its kind, a checkpoint that says when it is due | Miguel, Claude | accepted; its rule on the user's setup replaced by 50 |
| [47](0047-help-opens-its-files-as-documents.md) | Help opens its files as documents, with icons of its own | Miguel, Claude | accepted |
| [48](0048-stalls-are-a-line-hangs-a-stack-the-heap-starts-small.md) | Stalls are a line, hangs a stack; the heap starts small | Miguel, Claude | accepted |
| [49](0049-the-files-tab-shows-folders-the-user-chooses.md) | The Files tab shows folders the user chooses | Miguel, Claude | accepted; bar, sync interval and order amended by 51; clicks and the index replaced by 52 |
| [50](0050-comando-ships-a-claude-code-kit.md) | comando ships a Claude Code kit, and relies only on what it installed | Miguel, Claude | accepted |
| [51](0051-folder-buttons-together-read-only-folders-icons.md) | Folder buttons together, read-only folders, an icon per folder | Miguel, Claude | accepted; its row "files" replaced and the scratchpad icon added by 52 |
| [52](0052-files-open-on-a-double-click-the-bar-follows-the-selection.md) | Files open on a double click; the bar follows the selection and acts on files | Miguel, Claude | accepted |
