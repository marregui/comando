# 80. The kit is a whole Claude Code setup, installed reversibly

| Date | Status | Decided by |
|---|---|---|
| 2026-10-20 | proposed; when accepted, replaces the install and setup rows of [record 50](0050-comando-ships-a-claude-code-kit.md) and [record 61](0061-the-kit-overrides-a-setup-of-the-users-own-with-consent.md) | Miguel (the aim: the kit gives a user the whole working setup; a standard kit released when the setup improves; kits of other people with the same structure; uninstall and revert to an earlier version; no backup of all of `~/.claude`), Claude (the facts, the proposed shape and mechanisms, the task list; the open decisions in §4 are Miguel's) |

## 1. Context

A user installs Claude Code and keeps whatever setup they have. comando is usable without the kit. The kit is meant to
give a user, in one install, the whole way of working comando is built for, as it runs on its author's machine: the
memory system, the knowledge base and scratchpad, the instructions in `CLAUDE.md` that make them work, the settings,
the skills, the plugins, the hooks, agents and tools. The author releases a new kit when that setup improves and
becomes the new standard. Another person can package their own kit with the same structure and other content, and
tell comando to use it. A user can uninstall the kit and get `~/.claude` back as it was before, or go back to an
earlier version of the kit, without a copy of all of `~/.claude` being kept.

Record 50 shipped a first kit: one plugin, two skills, a skeleton. Record 61 decided that the kit overrides a user's
own `/reflect`, with consent; the code still follows record 50 and keeps the kit out instead (`Kit.install` refuses
when it finds a setup of the user's own). Record 61's reasoning rests on the Atlas, which was abandoned on
2026-10-18. This record takes both up.

**State on 2026-10-20.**

| Piece | What it holds | What it lacks |
|---|---|---|
| `kit/` in this repository, version 1.2.1 | a plugin: the skills `reflect` and `deep-review`; hooks: the method read at session start, the Mermaid check; scripts `gradle-serial`, `index-check`, `reflect-report`, `compact-check`, `cue-replay`; the agent-spec template; a skeleton for `MEMORY/`, `KB/`, `SCRATCHPAD/` | a small part of the author's setup |
| `Kit.install` | sets one settings key (`autoMemoryDirectory`), registers the marketplace, installs the plugin, writes missing skeleton files; each step can be undone while a failing install runs | no record survives the install, so no uninstall or revert; no instructions, no other settings, no user skills |
| The author's `~/.claude` | `CLAUDE.md` (about 110 lines); `settings.json` (19 keys); a plugin of his own with 4 agents, 15 scripts, 9 hooks, Semgrep rules and a workflow; 9 user skills; `CLAUDE_BOOTSTRAP.md`, a 536-line manual procedure for a new Mac | not packaged; the method and the author's work tools (Linear, Slack, Jenkins, scheduled jobs) are mixed; scripts exist twice (kit and plugin) and a test keeps the copies identical |

**What Claude Code lets a plugin carry** (its documentation as fetched 2026-10-01 and re-checked 2026-10-19):

| Part of a setup | In a plugin | Otherwise |
|---|---|---|
| agents, hooks, executables (`bin/`, put on the Bash PATH), workflows, templates, MCP and LSP servers, output styles, mods | yes; Claude Code installs, enables, versions, updates and uninstalls them | — |
| skills | yes, named after the plugin: `/comando:reflect`, never `/reflect` | an unprefixed name needs a user skill in `~/.claude/skills/` |
| instructions loaded in every session | no: a plugin's `CLAUDE.md` is not loaded | a user rules file in `~/.claude/rules/`, loaded at user scope like `CLAUDE.md` (documented; not tried live); or a SessionStart hook that prints the text, which the kit does today |
| settings keys (memory folder, compaction window, effort, deny rules, status line, environment) | no: plugin settings honour only `agent` and `subagentStatusLine` | written into `settings.json` by comando |
| the memory, KB and scratchpad skeleton | not a plugin's concern: once written, it is the user's data | written only where missing |
| tools outside `~/.claude` (Homebrew packages, launchd jobs, MCP servers in `~/.claude.json`, shell profile) | no | outside comando's install |

## 2. Decision (proposed)

| Question | Rule | Why |
|---|---|---|
| the kit's shape | one plugin for everything a plugin can carry, plus a residue comando writes and records: the settings keys, one user rules file, the user skills typed without a prefix, the skeleton | Claude Code owns a plugin's lifecycle and keeps its files in its plugin store, not across `~/.claude`; the residue is what a plugin cannot carry, and it is small and enumerable. A kit of copied files would make every file comando's to track and collide with the user's own; a kit wholly inside a plugin cannot carry settings or instructions |
| the kit's format | a `kit.json` manifest (name, version, the plugin, the settings fragment, the rules file, the user skills, the skeleton) and a fixed folder layout; comando checks a kit against it before offering to install | other people's kits have the same structure and other content |
| instructions | the kit's method in one rules file, `~/.claude/rules/<kit>.md`; the user's `CLAUDE.md` is never edited | one file to add and to remove; the user's own text stays theirs |
| settings | a key-level merge: each key the kit sets, with the value it had or that it was absent; a key the user set is changed only after asking | the record restores exactly what was there |
| the install record | kept by comando: the plugin's name and version; each settings key and its previous value; each file the kit wrote, with the hash of what it wrote; the full content of a user file the kit replaced (a `reflect` of the user's own), and only of such a file | bytes to kilobytes, not a copy of `~/.claude` |
| uninstall | from the record: the plugin uninstalled through Claude Code; each settings key put back; each file the kit wrote removed when it is unchanged, and kept and reported when the user changed it; each replaced user file put back; the user's memories, KB documents and scratchpad days are never touched | `~/.claude` as it was, except what the user made since |
| revert | comando keeps each kit version it installed (`~/.comando/kits/<name>/<version>/`, about 100 kB today); revert is uninstall followed by the install of the earlier version | no second mechanism |
| a setup of the user's own | the kit installs in its place with the user's consent; the record keeps the user's files, and uninstall puts them back | record 61's aim, made reversible |
| kit sources | the kit this build carries, a folder, or a git repository; a comando setting names which | the author's standard kit by default, another person's on request |
| consent | the install card lists every key, file and line the kit will change, and the plugin it installs | a kit runs its author's hooks and scripts |
| scope | comando writes only inside Claude Code's folder; what the kit needs outside it (python3, mmdc, git, gh, the `claude` CLI and its version) is checked and reported, never installed | the machine is the user's; `CLAUDE_BOOTSTRAP.md` remains the machine procedure |

## 3. The author's setup split (first estimate, for Miguel to mark)

| Today | To the standard kit | Stays the author's own (a second kit of the same structure) |
|---|---|---|
| the author's plugin | the kill and branch guards, the effort and context hooks, `gradle-serial`, `index-check`, `reflect-report`, `compact-check`, `cue-replay`, the Mermaid check, the four agent types, the spec template, `deliver` | `linear-*`, `slack-*`, `confluence-api.sh`, `kb-drift`, `curator`, Jenkins; undecided: `codex-run`, `semgrep-scan` and its rules |
| `CLAUDE.md` | the method: memory, KB, scratchpad, working a task, reviews, agents | role, team, Standing Contract, persona |
| `settings.json` | `autoMemoryDirectory`, `autoCompactWindow`, `cleanupPeriodDays`, the guards' deny rules | the OpenTelemetry environment, the status line, the iTerm hooks, models and effort |
| user skills | `reflect`, `deep`, `deep-review`, `merge-bar`, `qa-round` | `artesano`, `dwh-ask`, `linear`, `slack` |

## 4. Open decisions (Miguel)

| # | Question | Options | Recommended |
|---|---|---|---|
| 1 | the shape | plugin plus recorded residue; files copied into `~/.claude`; everything in the plugin | plugin plus residue (§2) |
| 2 | typed names | unprefixed `/reflect`, `/deep`, `/deep-review` as user skills the kit places and records; prefixed `/comando:reflect` with no extra files | unprefixed for the commands typed by hand |
| 3 | instructions | a user rules file; the SessionStart hook kept | the rules file, once a live check shows it loads in every project |
| 4 | what is standard | the whole setup in one kit; the method as the standard kit and the author's work tools as a second kit | two kits, §3 |
| 5 | the source of truth | the author's `~/.claude` stays the master and a tool extracts the kit; the kit is the master and the author's `~/.claude` installs it like any user's | the kit as master: one copy instead of two, every release tried on the author first |
| 6 | what uninstall keeps | the user's data always; or everything the kit created | the user's data always, §2 |
| 7 | reach outside `~/.claude` | checked and reported; installed | checked and reported |

## 5. Tasks

| # | Task | Depends on |
|---|---|---|
| 1 | inventory every artefact of the author's `~/.claude` as method, personal, runtime or machine, for Miguel to mark | — |
| 2 | this record accepted, with §4 decided | 1 |
| 3 | the kit format: `kit.json`, the layout, the structure check | 2 |
| 4 | the install record, written by `Kit.install` and kept by comando | 3 |
| 5 | the settings merge and the rules file | 3 |
| 6 | user skills placed and recorded, with a user's own file kept for uninstall | 4 |
| 7 | uninstall from the record | 4, 5, 6 |
| 8 | revert to an earlier version | 7 |
| 9 | the requirements check outside Claude Code's folder | 3 |
| 10 | kit sources: bundled, folder, git; the comando setting | 3 |
| 11 | the method's skills, agents, hooks and scripts moved into the kit; the author's work tools into his own kit | 2 |
| 12 | the release procedure of the standard kit: version, changelog, tried on the author's setup first | 11 |
| 13 | the author's `~/.claude` moved onto the standard kit and his own kit; the duplicate scripts and their drift test retired | 11, 7 |
| 14 | tests: install, uninstall and revert in a throwaway `CLAUDE_CONFIG_DIR`, each compared with the folder before the install (`KitLiveTest` extended) | 4–8 |

## 6. Consequences

Until this record is accepted and built, the kit installs as record 50 says and keeps out of a setup of the user's
own. Unchecked: whether a user rules file loads in every project, and whether Claude Code lets a plugin be pinned to
an earlier version from a local folder without a version bump in its marketplace.
