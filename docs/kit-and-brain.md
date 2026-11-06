# The kit and the brain

comando is one of three parts I think of as one thing: comando is the window, the kit is how the sessions in it behave,
and the brain is what they remember and know. I keep the three apart because they change at different speeds and for
different reasons. The kit changes when its user decides it should, one reviewed commit at a time; the brain changes
every day, as the sessions work; comando changes when the window needs to. This document is the model of the kit and the
brain, and what comando does with them. No kit is published with comando: an agent creates a kit and a brain for each
user from this document ([§8](#8-creating-a-kit-and-a-brain)), in two folders of their own outside `~/.claude`, or the
user brings their own. Each is one incarnation of the model.

1. [The model: a session and its five parts](#1-the-model-a-session-and-its-five-parts)
2. [The kit](#2-the-kit)
3. [comando and a kit](#3-comando-and-a-kit)
4. [The brain](#4-the-brain)
5. [Flows: comando, the session, the kit and the brain](#5-flows-comando-the-session-the-kit-and-the-brain)
6. [/reflect: the consolidation step](#6-reflect-the-consolidation-step)
7. [The learning loop: how the brain gets sharper](#7-the-learning-loop-how-the-brain-gets-sharper)
8. [Creating a kit and a brain](#8-creating-a-kit-and-a-brain)

## 1. The model: a session and its five parts

![One session at the centre, five parts around it](images/brain-overview.svg)

A comando session is one Claude Code conversation that comando runs. Five parts surround it; each answers one question.

| Part | Question | In short | Detail |
|---|---|---|---|
| Brain | what does it remember and know? | memory, today's scratchpad, the checked KB | [§4.1](#41-anatomy-the-parts-and-who-changes-them) |
| Process | how does it run? | `claude -p`, a child of comando; prompts in, events out; comando starts and stops it | [Claude Code §1](claude-code.md#1-the-claude-bridge) |
| Kit | how does it behave? | instructions, settings, hooks, skills, agents, tools, scheduled jobs | [§2](#2-the-kit) |
| Persistence | what outlives it? | the transcript (the conversation itself), comando's desk, metrics and audit | [§5.2](#52-what-persists) |
| Instances | who does it talk to? | other comando instances, each with its own kit, brain and data root, through the comando Edge | [Workbench §4.1](workbench.md#41-the-comando-edge) |

The process lives as long as comando keeps it; the conversation lives in its transcript and resumes in a new process.

## 2. The kit

![A kit's parts, which part acts at each moment of a session, and the kit beside the brain](images/kit.svg)

A kit is a Claude Code setup kept in a git repository. Its parts sit at the top of the repository, each where Claude
Code loads it from a config folder; no part is a plugin. comando runs every session on it
([record 80](adr/0080-the-kit-is-a-claude-code-setup-comando-runs-its-sessions-on.md)).

Parts of this document are a **contract**: what comando's code reads or calls, and what the sessions it starts rely on.
A kit honours the contract; everything else here is the recommended way of working, which a kit may change and which
drifts with its user's habits, as it should. The contract is: the manifest ([§2.4](#24-the-manifest)), where comando finds the kit and the brain
([§3.1](#31-the-root)), the settings
comando reads and the retention it needs ([§2.5](#25-settings)), what the repository ignores
([§2.6](#26-what-the-repository-ignores)), the launch environment ([§3.1](#31-the-root)), the Files tab's layout
([§3.2](#32-the-files-tab)), the jobs tool when the manifest lists jobs ([§3.5](#35-scheduled-jobs)) and the checkpoint
skill with its automatic form ([§6.2](#62-depths)). A kit created from this document, or brought by the user, is checked
against these sections alone.

### 2.1 Parts

| Part | What it holds | |
|---|---|---|
| `comando-kit.json` | the manifest: the kit's name, its brain, the Files tab's folders, its scheduled jobs, what it needs outside the folder ([§2.4](#24-the-manifest)) | required |
| `.gitignore` | what the kit's repository leaves out: credentials, Claude Code's runtime files, the brain when it sits inside the kit ([§2.6](#26-what-the-repository-ignores)) | required |
| `settings.json` | Claude Code's settings: permissions, retention, the hooks; each hook names its script as `${CLAUDE_CONFIG_DIR:-$HOME/.claude}/hooks/…` ([§2.5](#25-settings)) | required |
| `skills/` | the skills; `reflect` answers the checkpoint ([§6](#6-reflect-the-consolidation-step)) | `reflect` required |
| `CLAUDE.md` | the instructions every session loads | recommended |
| `hooks/` | the hooks' scripts, and the files they put in context | recommended |
| `agents/` | the subagent types the sessions dispatch ([§2.7](#27-agents-specs-and-reviews)) | recommended |
| `templates/` | the agent spec ([§2.7](#27-agents-specs-and-reviews)) and the KB's set templates ([§4.1.3](#413-kb)) | recommended |
| `tools/` | one folder per tool: its contract, its command and its test; the command folders are on the Bash tool's `PATH` | recommended |
| `workflows/` | saved multi-agent workflows | optional |
| `INSTALL.md` | the kit's own recipe; its section *Upgrade* is what the `kit` session follows ([§3.4](#34-version-and-upgrade)) | when tagged on a remote |
| `install/kit.py` | the jobs tool, which alone writes the jobs to the platform's scheduler ([§3.5](#35-scheduled-jobs)) | when the manifest lists jobs |

comando's code reads five things of a kit: the manifest; the root's `settings.json`, for a new session's default model,
effort and permission mode; the root's `projects/`, for stored conversations; the root's `skills/<name>/SKILL.md`, to
link a skill call in a transcript to its file; and, when the kit has one, the jobs tool, which it calls. It writes two:
a missing index file of a Files-tab folder inside the kit or the brain ([§3.2](#32-the-files-tab)), and the manifest
when the user repairs it, keeping the old one as `comando-kit.json.bak-<time>` ([§2.4](#24-the-manifest)). Its upgrade
prompt names the kit's `INSTALL.md`. The rest of the layout is what the sessions work by: a kit that lacks a recommended
part runs without what that part carries.

### 2.2 A session's life, and which part acts

| Moment | Claude Code event | What the kit does |
|---|---|---|
| start | `SessionStart` | `CLAUDE.md` and the settings load; a hook puts the tools on `PATH`; the brain's memory index loads (`autoMemoryDirectory`); a hook may prepare the brain (the scratchpad's day folder) |
| a prompt | `UserPromptSubmit` | hooks put context in: the memory files, KB lines and tasks that match, what other sessions did; a skill runs when named |
| a tool call | `PreToolUse` | guards may refuse a call: a kill by pattern, a delete without asking, another instance's files; agents run on dispatch; the tools run through Bash |
| a tool done | `PostToolUse` | checks after the fact: the brain's indexes after a write, reporting what is now missing; diagrams in a written document |
| a command failed | `PostToolUseFailure` | a tripwire may point the session at the memory file that documents that error string, without restating its fix |
| a compaction | `SessionStart` (`compact`) | the rules that must survive go back in context |
| the checkpoint | comando's | the `reflect` skill writes memory, corrects the KB and folds the scratchpad ([§6](#6-reflect-the-consolidation-step)), then comando sends `/compact` ([record 129](adr/0129-a-checkpoint-reflects-then-compacts.md)) |
| the checkpoint, automatic | comando's | at a turn's end past comando's threshold, comando sends the checkpoint command with `--auto`: the skill asks the user nothing and holds each decision back with comando's MCP tool `decision_hold`; a checkpoint the user starts asks the held decisions and settles them with `decisions_settled` ([§6.2](#62-depths), [record 131](adr/0131-an-automatic-checkpoint-asks-nothing.md)) |

A hook adds context or blocks a call; it never rewrites a prompt. The variables comando sets in a session's environment
(`CLAUDE_CONFIG_DIR`, `COMANDO_BRAIN`, `COMANDO_HOME`, `COMANDO_CREATED_BY`, `CONTEXT_CHECKPOINT_BY`) are in
[§3.1](#31-the-root): a hook reads them to find the kit, the brain and comando's data root, to leave out what only a
person needs in a session an agent made, and to stay silent at a size where comando runs the checkpoint itself.

Guards fail closed: a guard hook sets Claude Code's `"onFailure": "block"`, so one that crashes, times out or cannot
start refuses the call instead of letting it through, and it refuses with exit code 2. The hook that puts the tools on
`PATH` appends `export PATH=…` lines to the file Claude Code names in `CLAUDE_ENV_FILE`, at every start, so the Bash
tool of the session and of its subagents finds the scripts. The rules that must survive compaction come back after each
compaction as the plain output of a `SessionStart` hook with the matcher `compact` (plain standard output is context on
`SessionStart` and `UserPromptSubmit`; other events need Claude Code's JSON `additionalContext`).

### 2.3 Outside any session

comando reads `comando-kit.json` at start for the brain's folder and the Files tab's folders; the jobs it asks the
kit's jobs tool for. The tool writes the jobs to the platform's scheduler; each runs headless, outside comando, and leaves a report in the brain's
scratchpad for `/reflect` ([§3.5](#35-scheduled-jobs), [§7](#7-the-learning-loop-how-the-brain-gets-sharper)).

### 2.4 The manifest

`comando-kit.json`, at the top of the root, is a JSON object. Its schema is version 1, recorded with every other file
comando reads in [record 133](adr/0133-every-stored-file-has-a-schema-and-a-version.md) §2.1:

| Key | Required | Rule |
|---|---|---|
| `kit` | no; write it | the schema's version, `1`; a file without it is read as version 1, any other value is refused ("the kit is newer than comando" for a higher one) |
| `name` | yes | the kit's name, not empty; lower-case letters, digits and `-` keep it usable in the scheduler's ids |
| `brain` | no; write it | the brain's folder: absolute, `~/…`, or relative to the kit; `BRAIN` (inside the kit) when absent; `COMANDO_BRAIN` overrides it ([§3.1](#31-the-root)); memory is its `MEMORY/` |
| `folders` | no | the Files tab's folders, a list of objects: `name` (used once), `root` (`kit`, the default, or `brain`: the folder the path starts from), `path` (relative to that root, without `..`; `"."` is the root itself), `index` (a file inside the folder, or empty), `excludes` (a list of names left out), `icon` (`folder`, `scratchpad`, `memory`, `knowledge`, `skills` or `plugin`; empty is `folder`), `deletable` (`true`: its entries go to the Trash from the Files tab), `listedIn` (a file name, no `/`), `group` |
| `requirements` | no | the tools the kit needs outside the folder: `tool`, `check` (a command as a list of words), `install` (an object, one command per `macos`, `linux`, `windows`); for the agent that installs or checks the kit; comando reads them and uses none of them |
| `credentials` | no | the keys the kit's tools read from the root's `settings.local.json`: `key`, `for`, `where` (where the user gets it); never a value; comando uses none of them |
| `jobs` | no | the scheduled jobs, written by the jobs tool only ([§3.5](#35-scheduled-jobs)) |

comando ignores keys it does not know, so a kit may add its own (`required` and `for` on a requirement, say). A file
that is not a JSON object, has a later version, no `name`, or a folder whose path leaves its root, is refused whole:
comando tells the user why at start and runs without the kit, ending "Correct the file, then restart comando to run on
the kit". A folder that breaks one of the rules every Files-tab folder meets
([Workbench §6](workbench.md#6-the-files-tab): its icon, an index inside it, a listing file that is a file name, a name
used once) is left out, and the window names it at start, by its place and name, with what is wrong; **Kit ▸ Repair
comando-kit.json…** in the palette rewrites the file without those entries once the user confirms (and removes nothing
when the file's problems changed since, as after a kit upgrade), a notice naming each entry it took out, and keeps the
old one beside it as `comando-kit.json.bak-<time>`, one per repair, never overwritten. comando writes the manifest only
then; the jobs tool writes its `jobs` block; anything else is edited by the kit's user or the agent that made it.

A manifest for a kit at `~/comando-kit` with its brain at `~/comando-brain`, laid out as [§3.2](#32-the-files-tab)
wants, is in [§8.2](#82-the-kits-files).

### 2.5 Settings

The root's `settings.json` is Claude Code's, and comando reads four of its keys:

| Key | comando uses it for |
|---|---|
| `model`, `effortLevel` | a new session's model and effort when it names none; the session dialog shows them as the kit's defaults |
| `modelSettings.<model>.effortLevel` | the base effort comando takes for a session of that model (Claude Code starts its turns at it), before `effortLevel` |
| `permissions.defaultMode` | shown, read-only, in the session dialog's context line |

**Permissions live here alone.** comando passes no permission mode and no extra folders to `claude`: the kit's
`permissions` (its `defaultMode`, `allow` and `deny` rules) hold for every session, a session an agent creates included;
a session's working folder may add its own `.claude/settings.json` and `settings.local.json` on top, as Claude Code does
for any session, and they may widen the rules ([record 7](adr/0007-default-permission-mode.md),
[Claude Code §1.1](claude-code.md#11-the-process)). A tool call the rules leave to ask comes to comando's approval cards
([Claude Code §4.1](claude-code.md#41-approval-cards)). comando allows nothing of its own. An automatic checkpoint must
ask nothing and runs while the session is locked ([§6.2](#62-depths)), so a kit used with comando allows everything that
run does, or each call is a card and the locked session waits on the user:

| Allow | Why |
|---|---|
| `mcp__comando__decision_hold`, `mcp__comando__decisions_settled`, `mcp__comando__ui_get_state` | the three MCP tools the checkpoint calls |
| `Read(/<brain>/**)` | reads of memory, the scratchpad and the KB, which sit outside the session's working folder; `Read` rules also cover `Grep` and `Glob` |
| `Edit(/<brain>/**)` | the checkpoint's writes to memory and the day's index; an `Edit` rule covers the `Write` tool too, as Claude Code checks file writes against `Edit` rules only (a `Write(…)` path rule never matches) |
| `permissions.additionalDirectories`: `["<brain>"]` | the brain as a working directory, so reads there need no rule in any mode |

`<brain>` is the brain's absolute path as the filesystem gives it (`/Users/alice/comando-brain`), so with its own
leading slash the rule reads `Edit(//Users/alice/comando-brain/**)`: in a rule, `//` anchors at the filesystem root,
while a single `/` anchors at the folder of the settings file. The checkpoint writes nothing in the kit: with `--auto`
it leaves the day folder's roll to the start hook and kit changes to a run the user starts ([§8.2](#82-the-kits-files)
`SKILL.md`), so no rule opens the kit's own files, `settings.json` among them, to unasked edits.

**The brain is not a protected path.** Claude Code always prompts for writes to a `.claude` folder (and to `.git`,
`.vscode` and a few others) in the `default` and `acceptEdits` modes, and no `allow` rule can pre-approve them. A brain
inside `~/.claude` would therefore turn every memory, scratchpad and KB write into a card, the automatic checkpoint's
included. A new kit and brain live outside `~/.claude` ([§3.1](#31-the-root)), so brain writes follow the kit's rules,
the allow rules above among them; a kit that keeps its brain under a `.claude` path runs its sessions in a mode that
does not prompt for protected paths, or accepts the cards.

**Transcripts are kept.** comando resumes a session's conversation and draws its history from the transcript in
`<root>/projects/`. Claude Code deletes session data older than `cleanupPeriodDays`, 30 days unless set, each time it
starts. A kit used with comando sets it high (`36500`); a kit that wants old transcripts gone sweeps them itself, by a
job, and spares every transcript that a `sessions.json` entry of comando's data root names ([§5.2](#52-what-persists)).

**The checkpoint comes before compaction.** comando's checkpoint reflects, then compacts, when a turn ends past its
threshold, a share of the session's context window ([Claude Code §2](claude-code.md#2-the-checkpoint)). Claude Code's
own auto-compaction summarises the conversation with no reflect. A kit therefore leaves `autoCompactWindow` unset (the
model's window, near its end) or sets it above comando's threshold, never below.

Optional: `autoMemoryDirectory` set to the brain's `MEMORY/` gives a session started outside comando, on this root, the
same memory; comando passes it to its own sessions anyway ([§3.1](#31-the-root)).

### 2.6 What the repository ignores

The root is also Claude Code's config folder for every session on it, so the kit's repository ignores what Claude Code
and the machine write there. **Back up the kit** commits everything else in the root and pushes it
([§3.3](#33-back-up-the-brain-and-the-kit)), so an entry missing here is a file sent to the kit's remote. The list is
what Claude Code is known to write into a config folder; a later Claude Code may add names, so before each backup
`git -C <root> status --short` is worth a look, and a new runtime name joins the list:

| Ignored | Why |
|---|---|
| the brain's folder, when it sits inside the kit (`/BRAIN/`) | a repository of its own |
| `settings.local.json` | the credentials' values |
| `/.claude.json`, `/.claude.json.*`, `/.credentials.json` | Claude Code's global config and, on Linux and Windows, its login, written into a root used through `CLAUDE_CONFIG_DIR` |
| `projects/`, `sessions/`, `session-env/`, `file-history/`, `history.jsonl`, `todos/`, `tasks/`, `plans/`, `jobs/` | conversations and their state |
| `cache/`, `paste-cache/`, `shell-snapshots/`, `debug/`, `backups/`, `ide/`, `downloads/`, `statsig/`, `telemetry/`, `state/`, `usage-data/`, `daemon/`, `daemon.*`, `dev-mods/`, `bridge-spawn/`, `seed-admin/`, `.cc-writes/`, `claude-code/`, `plugins/`, `skills/.trash/`, `/.claude/` | Claude Code's caches, runtime and install folders |
| `stats-cache.json`, `mcp-needs-auth-cache.json`, `mcp-discover-verdicts.json`, `.last-cleanup`, `.last-update-result.json`, `settings.json.bak*`, `*.bak-doctor` | Claude Code's runtime files and the backups its updates and checks leave |
| the hooks' and tools' state folders, the jobs runner's folder (`/.kit-jobs/`) | per-machine state |
| `__pycache__/`, `.DS_Store` | the machine's |

The same names, without the leading `/` and the patterns, and with `settings.local.json`, are the `excludes` of the
root's Files-tab folder, so the KIT row shows the kit and not Claude Code's runtime ([§3.2](#32-the-files-tab)); the
Files tab hides names that start with a dot anyway. The file itself is in [§8.2](#82-the-kits-files).

### 2.7 Agents, specs and reviews

A kit that dispatches subagents gives each a narrow kind of work and a spec to do it from:

| Agent type | Does |
|---|---|
| reviewer | read-only: one lens of a review (correctness, concurrency, security, docs…), one report of findings |
| writer | edits only the files its spec lists, and proves its work by running the spec's acceptance |
| sweeper | mechanical find-and-count work, on a cheap model |
| final look | one read of the finished whole, after every other check |

| Rule | How |
|---|---|
| a spec first | written to the scratchpad topic's `work/` from the kit's template: kind and runner, the context it needs quoted (agents do not see memory), the task, the files with their access, what it must not do, an **executable acceptance** (commands and their expected results; for a review, the findings schema: each finding with its place in the code, a failure sequence and its evidence), the output path |
| no acceptance, no dispatch | a task whose done cannot be checked by a command is not handed out |
| caps | readers in parallel within a small bound, each writing its own report; writers in parallel only on disjoint files, every shared file with one named owner; one build at a time per checkout |
| verification | the session checks every report against its acceptance and every finding before acting on it; a review splits into lenses, one reader each, and runs again only when the fixes changed the code materially |
| provenance | each report carries a stamp, `generated` then `verified` ([§4.1.2](#412-scratchpad)) |
| friction | each agent appends what got in its way to the scratchpad's `FRICTION.md` ([§4.1.2](#412-scratchpad)) |

## 3. comando and a kit

This section is what comando does with a kit. comando does not install one: the agent that installs comando offers to
create a kit and a brain from this document, or to use the ones the user brings ([INSTALL.md §5](../INSTALL.md#5-the-kit)),
and comando finds the kit at start ([record 80](adr/0080-the-kit-is-a-claude-code-setup-comando-runs-its-sessions-on.md)).

### 3.1 The root

The root is the kit: the Claude Code config folder comando's sessions run on. comando decides it once at start
(`KitRoot`), then the brain from it. The first folder of the table below that holds a `comando-kit.json` is the kit,
whether its manifest reads or not; comando never passes over it for the next one:

| When | Root |
|---|---|
| comando was started with `CLAUDE_CONFIG_DIR` | that folder |
| `~/.claude/comando-kit.json` exists | `~/.claude`: the user's own Claude Code folder is the kit |
| `~/comando-kit/comando-kit.json` exists | `~/comando-kit`: the kit a new install creates |
| `~/.claude/comando-kit/comando-kit.json` exists | `~/.claude/comando-kit`: a kit made by an earlier install |
| otherwise | `~/.claude`, without a kit |

A brain applies only with a kit: without one there is no brain, `COMANDO_BRAIN` names nothing, sessions get neither
`COMANDO_BRAIN` nor `autoMemoryDirectory`, and the session dialog, **Back up the brain** and the Files tab's folder
roots show none. The brain, in this order:

| When | Brain |
|---|---|
| comando runs with no kit | none |
| comando was started with `COMANDO_BRAIN` | that folder |
| the manifest names `brain` | that folder: absolute, `~/…`, or relative to the root |
| otherwise | `<root>/BRAIN` |

The kit and the brain are two folders, each anywhere and each a git repository of its own (a folder inside a local
repository checkout serves too). A new install never touches `~/.claude`: it creates the kit at `~/comando-kit` and the
brain at `~/comando-brain`, and names the brain in the manifest ([§8](#8-creating-a-kit-and-a-brain)), so a user trying
comando keeps their own Claude Code setup exactly as it was. A user who wants every Claude Code session, inside comando
or not, to run on a kit makes `~/.claude` itself the kit instead. A `comando-kit.json` that does not read (not JSON, a
later version, no name, a folder outside the root) leaves comando without a kit, on `~/.claude`, never on the next
folder's kit: a notice at start names the file and why it does not read, and ends "Correct the file, then restart
comando to run on the kit" ([§2.4](#24-the-manifest)).

Every `claude` comando starts (sessions, the plan usage reader, the topics, the version check) gets this environment
from its instance:

| Variable | Value | When |
|---|---|---|
| `CLAUDE_CONFIG_DIR` | the root | the root is not `~/.claude` (where Claude Code looks anyway); taken out for the `kit` session that upgrades a kit at or inside `~/.claude` ([§3.4](#34-version-and-upgrade)) |
| `COMANDO_BRAIN` | the brain's folder | with a kit; taken out without one, so a session on no kit has no brain: a hook or skill finds the brain here |
| `COMANDO_HOME` | comando's data root, the instance's own, whichever way comando was started (`-Dcomando.home`, `COMANDO_HOME` or `~/.comando`) | always: a hook reads comando's files there ([§5.2](#52-what-persists)) |
| `COMANDO_CREATED_BY` | the agent that made the session | a session an agent created ([Claude Code §1.1](claude-code.md#11-the-process)) |
| `CONTEXT_CHECKPOINT_BY` | `comando` | comando runs the session's checkpoint at its threshold ([Claude Code §2](claude-code.md#2-the-checkpoint)); a hook that would remind or checkpoint at that size leaves it to comando |

With a kit, and only then, each also gets `--settings '{"autoMemoryDirectory": "<brain>/MEMORY"}'`, so the brain's
memory loads whatever the user's own settings say. Stored conversations and their import are read from
`<root>/projects`. A session on a root other than `~/.claude` neither reads nor writes `~/.claude`. Each comando
instance is isolated: its sessions get its root, its brain and its data root, and nothing of another instance's
([record 122](adr/0122-instances-name-kit-brain-data-root-edge.md)).

**Signing in.** Claude Code keeps its login per config folder, so a new root is signed in once, by the user, in their
own terminal: `CLAUDE_CONFIG_DIR=~/comando-kit claude auth login` (PowerShell: `$env:CLAUDE_CONFIG_DIR = "$HOME\comando-kit";
claude auth login`) opens the browser to sign in with the same account as the user's own setup;
`CLAUDE_CONFIG_DIR=~/comando-kit claude auth status --text` then names the account. The user's own `~/.claude` stays
signed in as it was.

### 3.2 The Files tab

`comando-kit.json` lists the kit's folders with the fields of a `folders.json` entry
([Workbench §6](workbench.md#6-the-files-tab)), each path relative to its `root`, the kit or the brain, without `..`;
`"."` is that root itself (the kit's folder, its excludes leaving out the rest). The brain's folders name `root`
`brain`, so `COMANDO_BRAIN` moves them with the brain, at every start: comando records where it put each kit folder last
(`kit-folders`), and a folder still there follows the kit and the brain, while one the user pointed elsewhere stays.
When `folders.json` holds none of them, comando adds them at the top, their paths made absolute under their root, once,
and saves; the user's own entries stay, and a kit folder whose name an entry already takes is not added (`KitFolders`).
An entry that names the same folder on disk as a kit folder (links followed, letter case ignored) counts as that folder,
so an entry through a link is not added twice. **Use the kit's folders** in the Files tab's folders window adds the
missing ones again.

The rows the Files tab gives a kit depend on its layout:

| Layout | Row |
|---|---|
| one folder, path `"."`, alone in group `Kit` (that spelling) | the KIT row: the Jobs row first inside it, the kit's commit and a newer release ([§3.4](#34-version-and-upgrade)), and in its menu **Back up the kit** and **Upgrade the kit…** |
| the brain's folders in one group (`Brain`), all inside the brain's folder | the BRAIN row: the brain's folder, those folders first, then its other folders; **Back up the brain** in its menu and in the menu of each folder inside the brain |
| a `Kit` folder other than the root | no kit backup or upgrade in its row's menu (the palette keeps them) |
| two folders in group `Kit` | the Jobs row in a row of its own, last, not inside the KIT row |

A folder inside the kit's folder or the brain's that names an `index` file which does not exist, in a folder that does,
gets one when the folders are saved, with a title and the line "`/reflect` keeps it current"; without a kit, comando
creates none in the user's own `~/.claude`. A new brain also has its index files from its skeleton
([§4.3](#43-the-skeleton)). Keeping them is the kit's ([§6.3](#63-the-steps)). In a `deletable` folder the Trash takes
out, with an entry, its own line in the nearest `listedIn` file at or above it: a list item or table row whose first
element is the entry's path relative to that file's folder (`topic/01-x.md`, `work/` for a folder). Any other line
naming it stays and is reported ([§4.1.2](#412-scratchpad)).

### 3.3 Back up the brain and the kit

**Back up the brain** (the palette and the menu of the Files tab's BRAIN row) runs in
the brain's folder, off the EDT: `git add -A`, a commit when anything changed (nothing to commit is not an error), `git
push`. The commit's first line says what it adds, `Brain backup 2026-10-28: MEMORY 2 changed; SCRATCHPAD 3 added, 1
changed` (each top folder, or a file at the top, with its counts; past 120 characters the rest are counted), and its
body lists the files, one a line (`added MEMORY/x.md`, `renamed a -> b`; at most 200). **Back up the kit** (the same
places, the KIT row) does the same in the root, its message `Kit backup …`: what it stages is what the kit's
`.gitignore` lets through ([§2.6](#26-what-the-repository-ignores)). A notice names the commit or the step that failed.
Each action is off while its folder is not the top folder of a git repository with a remote, and neither runs by itself
([record 118](adr/0118-brain-and-kit-are-rows-each-one-unit.md)). A created kit and brain have no remote until their
user adds one, a private repository each ([§8.1](#81-the-steps)).

### 3.4 Version and upgrade

The Files tab's KIT row shows the kit's commit and, when `git -C <root> ls-remote --tags origin` lists a newer `vX.Y.Z`,
"‹commit› · vB available", with a notice the first time a newer release is seen
(`git -C <root> describe --tags --always` still gives the version the notice names). Both are read at start and every 6
hours, off the EDT, never holding the window.

**Upgrade the kit…** (the palette, and the KIT row's menu) asks for a tag, newest first, then opens or creates a session
named `kit`, working in the root and started on the user's own Claude Code setup rather than the root's (it replaces the
root's files) when the root is `~/.claude` or inside it, on the root itself when the root is elsewhere, another
instance's kit ([record 122](adr/0122-instances-name-kit-brain-data-root-edge.md)), and sends it: "Upgrade the kit at
<root> to <tag>, following <root>/INSTALL.md section Upgrade. Report what changed." Once the session has ended that turn
without an error, comando reads the kit's version again, and only when the kit now reads the tag asked for does it offer
to restart the sessions still running on the old kit (each resumes its conversation; a busy one is left as it is). A
session that stopped before it reported, a turn that ended in an error, or a kit that reads another version restarts
nothing, and a notice says which.

A kit created from this document has no upstream: until its user pushes it to a remote and tags it, the KIT row shows no
newer release and **Upgrade the kit…** has nothing to offer. A kit that is tagged on a remote, to be shared or kept on
several machines, carries an `INSTALL.md` with a section *Upgrade*, which the `kit` session follows ([§8.2](#82-the-kits-files)).
When a comando release changes a contract section ([§2](#2-the-kit)), the user asks a session to bring the kit in line
with that release's document ([§8.5](#85-after-a-comando-upgrade)); nothing else in a kit needs to follow the model.

### 3.5 Scheduled jobs

The manifest's `jobs` list is the only record of the kit's scheduled jobs. The kit's jobs tool, `install/kit.py`, alone
writes them to the platform's scheduler (launchd, systemd timers or cron, Task Scheduler) and runs them
([record 117](adr/0117-kit-jobs-a-row-and-a-window.md)). A job in the manifest:

| Key | Rule |
|---|---|
| `name` | lower-case letters, digits and `-`, from a letter, at most 32; used once |
| `description` | optional; one line of at most 80 characters |
| `schedule` | `{"daily": "HH:MM"}` or `{"weekly": "<days> HH:MM"}`, the days `MON` … `SUN`, comma-separated, a run of three or more as a range (`MON-FRI`, `MON,THU`) |
| `command` | a list: the program, a path inside the root with `/` and no `..`, then its arguments; `{scheduler}` in any of them becomes the scheduler that started it |
| `env` | optional; an object of variables, names in capitals, digits and `_` |
| `log` | optional; a file inside the root for the job's output (otherwise `.kit-jobs/<name>.log`) |
| `enabled` | optional; `false` pauses the job |

comando calls the tool with `python3` (`python` on Windows) from the root, three ways, and waits at most 60 seconds:

| Call | Answers |
|---|---|
| `install/kit.py jobs --json --root <root>` | exit 0 and `{"jobs": [...]}` on standard output; each job with `name`, `description`, `days` (a list of `MON` … `SUN`), `time` (`HH:MM`), `command` (a list), `env` (an object), `log`, `enabled`, then the scheduler's view: `installed`, `current` (the scheduler holds what the tool would write now), `loaded` (`null` when the tool cannot tell), `running`, `last` (`{"start": <time>, "exit": <code>}`), `next` (a time, empty when paused), `logPath` |
| `install/kit.py jobs set --root <root>` | the whole list as a JSON array on standard input, in the manifest's form: every job checked first, then only the manifest's `jobs` written, then the scheduler brought to it |
| `install/kit.py job run <name> --root <root>` | starts the job now through the scheduler, never as a child of the caller |

Exit codes: 0 done; 1 done in part (a running job left as it was, a load that failed); 2 refused, nothing written; 3 an
unexpected error, part may be done. Standard output and standard error are shown to the user when a call is refused. The
runner the scheduler starts keeps, per job, its process id while it runs and one line per run (start, exit), so
`running` and `last` can be answered, and never lets two runs of one job overlap. A kit with no scheduled jobs leaves
out `jobs` and the tool, and the Files tab then shows no Jobs row. A kit with the tool and no job shows the row as
"none", its tooltip "No scheduled jobs in comando-kit.json; double-click to add one"; a manifest that lists jobs without
the tool shows "cannot be read", its tooltip naming the missing `install/kit.py`.

The Files tab shows the jobs as one **Jobs** row, the first inside the KIT row
([record 118](adr/0118-brain-and-kit-are-rows-each-one-unit.md)): its detail counts them and names what runs
now or the next run (`5 jobs · 1 paused · next Thu 07:15`); the dot marks a change the scheduler has not taken (a job
running when it was saved is left as it was), a red name a failed last run or jobs that cannot be read; its tooltip says
the most pressing of these. comando reads them through `jobs --json` at start, after every change it makes, and,
while the Files tab shows, when `comando-kit.json` or a runner file in `.kit-jobs/` changed (a job's pid or its runs;
looked at every 10 s, logs left out).

A double click, Enter, or **Configure kit jobs** (the row's menu, the Files tab's menu, the palette) reads the jobs
again and opens the **Kit jobs** window on them (a list that cannot be read opens none, and a notice says why): the jobs
on the left, +, −; the selected job's name, description, days of the week, time, program (a path inside the kit, picked
with …), arguments, environment (`NAME=value` lines), log and Enabled on the right, checked as typed, and under them,
read only, whether the scheduler holds it, its last run and its next. A job's row menu runs it now, through the
scheduler, opens its log, or removes it. **Save** hands the whole list to `jobs set`; the window closes when the list was
taken and stays open with the tool's words when it was refused. **Apply pending changes**, in the row's menu while
something is pending and nothing runs, reads the jobs again and hands them over. comando never writes the scheduler's
files.

## 4. The brain

A brain is a folder of plain files, wherever [§3.1](#31-the-root) finds it (`~/comando-brain` for a new install), and a
git repository of its own. It starts as a skeleton ([§4.3](#43-the-skeleton)); from then on the sessions write it.

| Store | Holds | Certainty | Changed by |
|---|---|---|---|
| `MEMORY/` | working context: the state of the work, the user's preferences and corrections, lessons, references; an index, `MEMORY.md`, with one cue per file | what was true when written | any session; the consolidation step, `/reflect` |
| `SCRATCHPAD/` | today's work: topic folders, numbered documents, raw agent output, a task index | provisional | any task, agent or scheduled job |
| `KB/` | checked knowledge: sets of documents, each kept true against named sources | must match its sources | `/reflect` only |
| `EVAL/` (optional) | an answer key: past queries labelled with the memory files they needed | labelled by hand | `/reflect`, with the user |

Three rules hold the model together:

1. **Memory and the KB never point at each other.** Memory changes daily and is private to the work; a KB document
   must read on its own, cold.
2. **One writer for the KB, behind a gate.** Only `/reflect` writes the KB, and each statement passes three checks: the
   session verifies it against its source (the code at a named commit, or the source the set registers); an independent
   reviewer agent tries to refute it; the user approves what survives, one batch per set. A run that cannot ask the user
   writes nothing to the KB.
3. **The index is part of every write.** A file written without its line in its index is invisible to the next session.

### 4.1 Anatomy: the parts and who changes them

![Rows: the brain's parts. Columns: who writes them](images/brain-anatomy.svg)

Read the picture by row. A filled mark means the column writes that part; a ring means it only checks or reads it.

#### 4.1.1 Memory

| Part | Role | Written by |
|---|---|---|
| `MEMORY.md` | the index: one line per memory file saying *when* to open it (situations, error strings, names), not what it holds. Claude Code loads it at every session start, up to 200 lines and 25,000 characters | any session, `/reflect` |
| rules and state | files of four kinds: `feedback` (corrections, confirmed ways of working), `user` (who the user is), `project` (work, constraints), `reference` (pointers to outside systems); opened when a cue matches | any session (re-read before writing, own lines only); `/reflect`; `feedback` and `user` changes confirmed by the user |
| the current work | one file with a section per topic: where each stands | the session working that topic |
| logs | dated records that only grow (a session log, agent runs): a small head plus monthly files, written through their tools, queried rather than loaded | `/reflect` through the kit's tools |
| `archive/` | closed work, verbatim, not loaded | `/reflect` |

Each memory file opens with front matter: `name`, `description` (one line) and `type` (one of the four kinds). Its index
line is a trigger of about one line, never its facts:

```markdown
- [<title>](<file>.md) — read when <the situations, names and error strings that should open it>.
```

The index's first line tells a session to search memory when no cue fits.

Budgets, as defaults a kit may change; nothing is dropped to fit them, entries are merged, shortened to their trigger,
or archived:

| Budget | Default |
|---|---|
| the index | 160 lines and 24,000 bytes, a margin under what Claude Code loads |
| a memory file | 32 KB; a file past it takes no addition until one of its entries is merged or archived |
| a log's head | 16 KB |
| a file that has become a log | 40 dated entries: split into a head and monthly files |

A merge or an archive move is checked by a no-loss tool: every date, code span, quote, number, link and heading of the
original must survive somewhere. Parallel sessions share these files, so a change is an anchored replacement (an edit
that fails unless its anchor is there exactly once), never a whole-file write over another session's lines.

#### 4.1.2 Scratchpad

| Part | Role | Written by |
|---|---|---|
| the day folder | one folder, today's, renamed each day so topics and task numbers carry over; topic folders with numbered documents and `work/` for raw material | any session, agents; a hook renames it |
| `INDEX.md` | every task with its State and Next, and what waits on the user | sessions, scheduled jobs, `/reflect` |
| reports | the scheduled jobs' findings; agents' reports, stamped `generated`, then `verified` once checked | jobs, agents; `/reflect` folds or deletes them |
| `FRICTION.md` | what got in agents' way, one line each | agents append; `/reflect` turns each into a change |
| `README.md` | these rules, at the scratchpad's top | the user |

| Rule | How |
|---|---|
| the roll | at session start and before a job writes: when today's folder is missing, the newest day folder is renamed to today; any other day folder is merged into today's topic by topic, its `INDEX.md` parked in `work/` to be folded; anything else at the top moves to today's `inbox/` to be filed |
| the index | `# Scratchpad`; a table of the topic folders; `## Waiting on <user>` (every open decision, each naming its task); `## Open`, one `### <topic>/` heading per topic folder, each task as `#### N. <title>`; last `## Scheduled reports`, where jobs file their reports until `/reflect` moves them |
| a task | **Ticket** or **Repo**, **State** (done, waiting on whom, in progress, with the last verified fact), **Next** (who moves it, and how), **Resume from**, and its documents, each a list item of its own led by its path relative to the index (`` `topic/01-x.md` — what it holds ``), so comando's Trash takes the line out with the file ([§3.2](#32-the-files-tab)) |
| task numbers | a new task takes the highest number in the index plus one, read just before writing; numbers never change |
| names | sized for comando's Files tab, which does not scroll sideways: topics lower-case, at most 20 characters; documents `NN-<what>.md`, at most 25; `work/` files at most 22; no date in a name (the folder carries it); the name alone says what the file holds; a topic's living plan is `00-<topic>-plan.md` |
| pointers | carry no date, as the folder's name changes daily: `topic/01-x.md`, task 3 |
| reports | an agent report in `work/` is `<name>-report.md` (or `.json`) with a stamp in its front matter: `generated` (`by` the runner, `at` in UTC, `against` the checkout and its commit, `+dirty` when it had uncommitted changes) when it lands; `verified` (`by`, `at`, `result`) once the session checked it; stamping `generated` again drops `verified` |
| `FRICTION.md` | lines `- <date> <agent type> <spec or task>: <what got in the way> → <what would have prevented it>`; agents only append; `/reflect` deletes the lines it handled and keeps the file, even empty |
| deleting | per artefact, never per folder: files go to the Trash, an emptied folder goes with `rmdir`; never a recursive delete, as parallel sessions write the same day folder |

#### 4.1.3 KB

| Part | Role | Written by |
|---|---|---|
| sets | a folder with `00_START_HERE.md` (the way in: what the set covers, one line per document and per set inside, a link to its `99`) and `99_MAINTENANCE.md` | `/reflect`; a new set when the user asks |
| the checks table | which source each set is kept true against, and how often it is checked | `/reflect`; read by a drift-check job |
| the change log | one line per correction | `/reflect` |

| Rule | How |
|---|---|
| a set's `99` | four sections, by these titles and in this order: **Rules**, **Source register**, **Drift check**, **Change log** |
| the checks table | opens the drift check: `Source \| Kind \| Where \| Recorded \| Cadence \| Checker`; Kind `code`, `document`, `web`, `toolchain` or `none`; Cadence `weekly`, `monthly` or `never`; Checker the drift job or `none`; a set with no outside source has one row `— \| none \| — \| — \| never \| none` |
| ownership | a document belongs to the nearest set at or above its folder; a folder holding a sources list holds verbatim copies, not documents |
| a document | opens with its title and the line `> **Last Updated:** <date> · **Status:** <Current, Draft or Historical>`; reads on its own; carries no `file:line` anchors |
| sources | a statement cites what it was verified against, never a scratchpad file: the scratchpad is provisional |
| structure | an index check verifies both files in every set, every document in its set's `00`, every set in its parent's `00`, the four headings and the checks table |

#### 4.1.4 EVAL

`EVAL/` is optional: a kit that measures memory recall keeps its answer key here, hand-labelled rows of past queries
with the memory files whose rules applied. It starts empty and grows by use ([§7.2](#72-eval-the-answer-key)).

### 4.2 The kit and the brain side by side

| | The kit | The brain |
|---|---|---|
| holds | how sessions behave | what sessions know |
| written by | the user, deliberately; each change reviewed and committed | the sessions as they work, under the kit's rules |
| versioned | commits, and tags when shared; upgraded by a session named `kit` | commits; backed up from comando |
| shared | one kit can run many people's sessions | one per person or instance; it starts as a skeleton |
| in short | a recipe: it says how, never what was learned | a record: its content is what use has put there |

The brain is never part of the kit: it is a folder and a repository of its own, outside the kit for a new install; a
brain kept inside the kit's folder is ignored by the kit's repository.

### 4.3 The skeleton

A new brain is these files and nothing more; their text is in [§8.3](#83-the-brains-files):

| File | First content |
|---|---|
| `MEMORY/MEMORY.md` | the index's head: one italic line (index only; each line a trigger; search memory when no cue fits), `## Start here`, the front matter and index-line form |
| `KB/00_START_HERE.md` | the title, the header line, what the KB is, `## Sets inside` (none yet), `## Reading order` (none yet), a link to `99` |
| `KB/99_MAINTENANCE.md` | the four sections of [§4.1.3](#413-kb), the register and the checks table each with its empty row, one change-log line for the creation |
| `SCRATCHPAD/README.md` | the scratchpad's rules ([§4.1.2](#412-scratchpad)) |
| `SCRATCHPAD/FRICTION.md` | a title only |
| `SCRATCHPAD/<today>/INDEX.md` | the index's headings, empty |
| `EVAL/` | left out until the kit measures recall ([§7.2](#72-eval-the-answer-key)) |

## 5. Flows: comando, the session, the kit and the brain

![Who runs, writes and reads what](images/brain-flows.svg)

The picture has six regions. Solid boxes run; dashed boxes are files on disk. Colour names the owner.

### 5.1 Who runs what

| Region | Runs as | Owner |
|---|---|---|
| comando | the JVM; one strand and one stdout reader per running session; the MCP server inside it | comando |
| `claude -p` | a child process of comando, one per running session; its own children (shells, MCP servers over stdio, hook scripts) are invisible to comando | Claude Code, owned by comando |
| hooks | children of `claude`, at fixed events | the kit |
| scheduled jobs | headless `claude -p` runs or scripts, started by the platform's scheduler, not by comando | the kit |

How comando starts, drives and stops the process: [Claude Code §1](claude-code.md#1-the-claude-bridge).

### 5.2 What persists

| Layer | Path (defaults) | Written by | Read by | Lifetime |
|---|---|---|---|---|
| the conversation | `<root>/projects/<dir>/<id>.jsonl`, sub-agents beside it | `claude` | `--resume`; comando, to draw history; the kit's measuring jobs | as long as the kit keeps transcripts ([§2.5](#25-settings)) |
| comando's desk | `$COMANDO_HOME/sessions.json`, panels, folders, connections | comando | comando; a kit hook that reports on other sessions | permanent |
| metrics | `$COMANDO_HOME/metrics/` | comando | meant for the kit ([record 110](adr/0110-metrics-for-calibration.md)) | permanent |
| audit | `$COMANDO_HOME/audit/` | comando's MCP server | the user | permanent |
| live tokens | `$COMANDO_HOME/run/*.mcp.json` | comando | `claude` | deleted at exit |
| the kit | `<root>` | the user | every `claude` | versioned |
| the brain | `$COMANDO_BRAIN` | the sessions, under the kit's rules | every session, with a kit only | grows by use |

`$COMANDO_HOME` is the data root comando passes to every `claude` it starts, `~/.comando` unless comando was started
with another ([§3.1](#31-the-root)); a hook outside a comando session falls back to `~/.comando`. Its `sessions.json`
(`{"version": 1, "sessions": [...]}`, [record 133](adr/0133-every-stored-file-has-a-schema-and-a-version.md)) lists the
sidebar's sessions, each with `name`, `workDir` and `claudeSessionId`, which ties it to its transcript
`<root>/projects/<dir>/<claudeSessionId>.jsonl`. The kit and the brain reach a process through `CLAUDE_CONFIG_DIR` and
`autoMemoryDirectory`, and the brain through `COMANDO_BRAIN`, the brain's two only with a kit ([§3.1](#31-the-root)).

### 5.3 Between the session and the brain

Start, each prompt, after a write and the checkpoint are in [§2.2](#22-a-sessions-life-and-which-part-acts). Two
moments are not:

| Moment | Flow |
|---|---|
| during the work | the session opens memory on cue and KB documents by title; writes task output to the scratchpad |
| weekly | jobs measure and leave reports in the scratchpad ([§7](#7-the-learning-loop-how-the-brain-gets-sharper)) |

### 5.4 Between instances

What travels between instances is in [Workbench §4.2](workbench.md#42-what-travels-between-instances); on the kit's
side, a guard stops a session reading another instance's roots
([record 122](adr/0122-instances-name-kit-brain-data-root-edge.md)).

## 6. /reflect: the consolidation step

![When it runs, what each step changes, why](images/brain-reflect.svg)

`/reflect` is the one step that turns a session into memory and checked knowledge. In a kit it is a skill; comando
runs it as a session's checkpoint command ([Claude Code §2](claude-code.md#2-the-checkpoint)). The aim of every run:
the next session recalls more of what matters from fewer bytes, and the KB is truer.

### 6.1 When

| Trigger | Who |
|---|---|
| the user asks | the user |
| a session ends | the user, or a kit rule |
| the Checkpoint button, a typed `/reflect`, **Settle** | comando sends `/reflect` with no argument, then `/compact` |
| the context crosses comando's threshold | comando, at the turn's end: `/reflect --auto`, then `/compact` ([record 129](adr/0129-a-checkpoint-reflects-then-compacts.md), [record 131](adr/0131-an-automatic-checkpoint-asks-nothing.md)); the session runs with `CONTEXT_CHECKPOINT_BY=comando`, so a kit hook that would remind at that size stays silent |
| a context threshold outside comando | a kit hook reminds |

### 6.2 Depths

| Run | Does |
|---|---|
| full: the user starts it (the button, a typed command, **Settle**) | every step; first reads the decisions held back earlier (`ui_get_state`, its `later` list: `id`, `session`, `title`, `detail`), asks them with its own, one form at a time, and after the answers calls `decisions_settled` with the ids it settled |
| automatic: `--auto` | Measure, Memory and the scratchpad's liveness only, writing inside the brain alone (the day folder's roll is left to the start hook, kit changes to a run the user starts); asks the user nothing and never waits on them, which needs the kit's settings to allow its reads, writes and MCP calls ([§2.5](#25-settings)); each decision it would have asked (a memory change to confirm, a KB statement for the gate, a done task to clear, a report to apply) is held back twice: one row in the index's waiting table, and one call of comando's MCP tool `decision_hold` (`title`: the decision in one line; `detail`: what the user needs to decide it, a few lines), which puts it in the Approvals tab's Later group ([Claude Code §4.1](claude-code.md#41-approval-cards)); outside comando, the row alone |
| cannot ask: headless (a scheduled job, a prompt another session sent) | as automatic; its decisions wait as one row in the index's waiting table |

A skill that ignores `--auto` may still ask; its cards then lock the session until the user answers
([record 130](adr/0130-a-session-locks-while-its-checkpoint-runs.md)), which is what the automatic form exists to
prevent. A comando panel session is interactive though it runs as `claude -p`: it shows question cards, so only a job or
a prompt from another session counts as headless.

### 6.3 The steps

| Step | Reads | Changes |
|---|---|---|
| Measure | the memory index and the files the session touched | nothing; a snapshot for the report |
| Memory | the conversation | memory entries rewritten (a rule plus one line of evidence), cues sharpened to triggers, the topic's state, the logs through their tools, the recall report's fixes, new `EVAL` rows; kit edits proposed by a job, once the user confirms |
| KB | corrections the session proved; the drift report | each statement through the gate ([§4](#4-the-brain) rule 2), then written through its set's `99`: the statement, the header date, the register's state, a change-log line |
| Scratchpad | the index as the root set | a collection: a file no open task points at (a code span naming it exactly) is read, its true content folded, and moved to the Trash; every done task of any session has its decisions settled with the user, its proved content folded through the gate, its files trashed and its entry deleted; open tasks rewritten to State and Next; anything changed after the run started is live work and stays; agent reports fold only when stamped `verified`; friction lines turned into changes |
| Indexes | every file written | `MEMORY.md`, each set's `00`, the day's `INDEX.md`, and, in a folder the Files tab shows, the folder's `index` file and the `listedIn` lines ([§3.2](#32-the-files-tab)); an index check after each write |
| Report | the snapshot | the index before and after, lines changed per file, files only appended to, budget warnings, decisions held or settled; four lines at most |

### 6.4 Why it is built this way

The KB's one writer, memory and KB never pointing at each other, and the index's budget are the brain's rules
([§4](#4-the-brain), [§4.1.1](#411-memory)). Three reasons are this step's own:

- Rewrite, not append: an entry stays one rule and its evidence; displaced detail goes to the session log or `archive/`.
- Sessions run in parallel: re-read before every write, change only your own lines, delete per artefact, never per folder.
- After a compaction: when a reflect ran just before it (the checkpoint does), the summary is not a source and only the
  turns after it are; when none ran, the summary is mined for what memory lacks, each item checked against the memory
  files first.

## 7. The learning loop: how the brain gets sharper

![Use, record, measure, report, reflect; EVAL](images/brain-loop.svg)

Every session is used, recorded, measured and reflected on; the next one starts from the result.

### 7.1 The five stations

| Station | What happens | Writes |
|---|---|---|
| 1. Use | the session loads the memory index, opens memory on cue (a hook names the matching files per prompt), reads KB documents by title, picks up today's tasks | — |
| 2. Record | Claude Code keeps the transcript: prompts, files opened, corrections, agent runs. comando keeps metrics (turns, sub-agents, plan usage) and an audit (its MCP calls and approvals) | the transcript; comando's data root |
| 3. Measure | the kit's weekly jobs score the week ([§7.3](#73-the-measuring-jobs)) | reports in the scratchpad |
| 4. Report | findings wait as tasks; what needs a decision waits on the user | the task index |
| 5. Reflect | `/reflect` turns findings and the session into memory, KB, `EVAL` and confirmed kit changes ([§6](#6-reflect-the-consolidation-step)) | the brain; the kit |

### 7.2 EVAL: the answer key

`EVAL/` measures one thing: does the memory index lead a session to the memory files whose rules applied to what it
was doing?

| Aspect | Rule |
|---|---|
| a row | one query (a prompt, or an action the session took), the memory files whose rules applied, and for each whether the session had it loaded |
| format | a tab-separated file with a header: `id`, `kind` (`prompt` or `action`), `session`, `time`, `index_sha` (the brain's commit of the index then), `query` (short, no third-party names), `files` (a comma list), `rules` (the headings that applied), `loaded` (one `y` or `n` per file), `source` (`sample` or `correction`), `note` |
| who adds rows | `/reflect`, a few each week, from a seeded sample of the week's queries, labelled with the user; a correction caused by a missed cue goes in too |
| who reads it | the recall tool: loaded over applicable, per query class, with a confidence interval |
| where | in the brain, not the kit: its rows describe the user's real prompts |

A weekly replay measures the same quantity without labels: the week's prompts against the index as it stood before the
week, judged by a small model. The answer key is the slower, exact check; the replay is the frequent, rough one.

### 7.3 The measuring jobs

| Job | Reads | Measures | Applied by |
|---|---|---|---|
| recall replay | transcripts, the memory index | whether the index routed each prompt to the files it needed; files opened without their cue matching | `/reflect`: cues sharpened |
| correction curator | the week's corrections from the transcripts | each correction's cause in the kit: cue missed, rule ignored, no rule | the user confirms; `/reflect` applies; a declined proposal is recorded so it is not proposed again |
| drift check | each KB set's checks table and its sources | statements that no longer match their source | `/reflect`, each item through the gate |
| agent runs | one record per agent run, filled from comando's `agent` event when there is one | pass rates per model and agent type, with time and output tokens | the routing of later agent work |

### 7.4 comando's metrics

comando records every turn, sub-agent and plan-usage read ([record 110](adr/0110-metrics-for-calibration.md)). The
record names the kit as their reader: which model and effort for which work, pacing against plan limits, the records of
agent runs. What comando reads back itself is in [Claude Code §3.2](claude-code.md#32-the-tools).

Agent runs are the first reader: a kit may keep one record per agent run, filled from comando's `agent` event (the agent
type, the model it ran on, its duration and output tokens), and route later agent work by the pass rates. Whether the
run passed stays a judgement the session records. Pacing against plan limits and model-and-effort calibration read
nothing yet.

## 8. Creating a kit and a brain

What an agent does to give a user a working kit and brain, given only this document; [INSTALL.md §5](../INSTALL.md#5-the-kit)
sends it here. The kit it creates is the smallest that honours the contract ([§2](#2-the-kit)) and works the brain's
method; no script is required beyond the one below, and every other part of [§2.1](#21-parts) is added later, when the
user asks for what it carries. The kit goes to `~/comando-kit` (`K` below) and the brain to `~/comando-brain` (`B`); the
user's own `~/.claude` is never written. Paths are for macOS and Linux; on Windows `~` is `%USERPROFILE%`.

### 8.1 The steps

1. **Where.** Stop and ask when `K` or `B` exists and is not empty, or when the user names a kit or a brain of their own
   ([§8.4](#84-a-kit-or-a-brain-the-user-brings)). The one look at `~/.claude` is whether `~/.claude/comando-kit.json`
   exists: if it does, that folder is a kit already and comando runs on it before `~/comando-kit`
   ([§3.1](#31-the-root)), so ask. Never read or write anything else there.
2. **Check the tools.** `claude --version`, `git --version`, `python3 --version` and `sh -c 'echo ok'` answer. When one
   does not, ask the user to install it, then check again: macOS `xcode-select --install` (git and python3);
   Debian and Ubuntu `sudo apt-get install -y git python3`; Windows `winget install --id Git.Git -e` (git, and the `sh`
   the hooks run under) and `winget install --id Python.Python.3.13 -e`. `python3` is needed only by the checks of step
   6. On Windows without `sh`, write `settings.json` without its `hooks` key: the skill rolls the day folder itself in
   a run the user starts.
3. **Show the plan and ask once.** One table: every folder and file below, the hooks and what each does, what the
   settings allow (the three MCP tools, reads and edits inside the brain, [§2.5](#25-settings)), that no job is
   scheduled, and that `~/.claude` is not touched. Change nothing before the user agrees.
4. **The kit.** `mkdir -p ~/comando-kit && git -C ~/comando-kit init -b main`, then write the files of
   [§8.2](#82-the-kits-files) with their names and text as given, `<today>` as `YYYY-MM-DD` and `<brain>` in
   `settings.json` as the brain's absolute path (`printf '%s\n' "$HOME/comando-brain"`).
5. **The brain.** `mkdir -p ~/comando-brain && git -C ~/comando-brain init -b main`, then write the files of
   [§8.3](#83-the-brains-files).
6. **Check.** Each command prints the line in the comment after it:

   ```sh
   K=~/comando-kit; B=~/comando-brain
   python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["kit"]==1 and d["name"]; print("manifest ok")' "$K/comando-kit.json"
   # manifest ok
   python3 -c 'import json,sys; p=json.load(open(sys.argv[1]))["permissions"]; b=sys.argv[2]; assert {"Read(/"+b+"/**)", "Edit(/"+b+"/**)"} <= set(p["allow"]) and b in p["additionalDirectories"]; print("settings ok")' "$K/settings.json" "$HOME/comando-brain"
   # settings ok
   for p in settings.local.json .claude.json .credentials.json projects/ usage-data/ plugins/; do git -C "$K" check-ignore -q "$p" || echo "not ignored: $p"; done; echo checked
   # checked
   COMANDO_BRAIN="$B" sh "$K/hooks/scratchpad-roll.sh"; ls "$B/SCRATCHPAD"
   # <today>  FRICTION.md  README.md
   ```

7. **Commit.** When `git config user.email` prints nothing, ask the user for the name and address their commits carry
   and set them in both repositories (`git -C ~/comando-kit config user.name '<name>'`, `… user.email '<address>'`, and
   the same for `~/comando-brain`). Then `git -C ~/comando-brain add -A && git -C ~/comando-brain commit -m 'Brain
   created'`, and the same in `~/comando-kit` with `'Kit created'`. Add a remote to either only when the user names one,
   a private repository; **Back up the brain** and **Back up the kit** stay off until then
   ([§3.3](#33-back-up-the-brain-and-the-kit)).
8. **Sign the kit in.** The kit is a Claude Code config folder of its own, signed in once: ask the user to run, in
   their own terminal, the commands of [§3.1 *Signing in*](#31-the-root), and to report the account they name.
9. **Restart comando** when it runs, so it finds the kit: the KIT and BRAIN rows appear in the Files tab, and every
   session's Checkpoint answers through the `reflect` skill. The KIT row has no Jobs row, as this kit has no jobs and no
   jobs tool ([§3.5](#35-scheduled-jobs)). Report what was created, in a table.

### 8.2 The kit's files

`K/comando-kit.json`: the kit's folder as the KIT row, the brain's three folders as the BRAIN row
([§3.2](#32-the-files-tab)).

```json
{
  "kit": 1,
  "name": "comando-kit",
  "brain": "~/comando-brain",
  "folders": [
    {"name": "Kit", "path": ".", "index": "", "icon": "folder", "deletable": false, "listedIn": "", "group": "Kit",
     "excludes": ["projects", "sessions", "session-env", "file-history", "history.jsonl", "todos", "tasks", "plans",
                  "jobs", "cache", "paste-cache", "shell-snapshots", "debug", "backups", "ide", "downloads", "statsig",
                  "telemetry", "state", "usage-data", "daemon", "dev-mods", "bridge-spawn", "seed-admin",
                  "claude-code", "plugins", "stats-cache.json", "mcp-needs-auth-cache.json",
                  "mcp-discover-verdicts.json", "settings.local.json"]},
    {"name": "Scratchpad", "root": "brain", "path": "SCRATCHPAD", "index": "", "icon": "scratchpad", "deletable": true,
     "listedIn": "INDEX.md", "group": "Brain", "excludes": []},
    {"name": "KB", "root": "brain", "path": "KB", "index": "00_START_HERE.md", "icon": "knowledge", "deletable": false,
     "listedIn": "", "group": "Brain", "excludes": []},
    {"name": "Memory", "root": "brain", "path": "MEMORY", "index": "MEMORY.md", "icon": "memory", "deletable": false,
     "listedIn": "", "group": "Brain", "excludes": []}
  ],
  "requirements": [
    {"tool": "git", "check": ["git", "--version"],
     "install": {"macos": ["xcode-select", "--install"], "linux": ["sudo", "apt-get", "install", "-y", "git"],
                 "windows": ["winget", "install", "--id", "Git.Git", "-e"]}}
  ]
}
```

`K/.gitignore` ([§2.6](#26-what-the-repository-ignores)):

```gitignore
# credentials, and Claude Code's global config and login in a root used through CLAUDE_CONFIG_DIR
settings.local.json
/.claude.json
/.claude.json.*
/.credentials.json
# conversations and their state
projects/
sessions/
session-env/
file-history/
history.jsonl
todos/
tasks/
plans/
jobs/
# Claude Code's caches, runtime and install folders
cache/
paste-cache/
shell-snapshots/
debug/
backups/
ide/
downloads/
statsig/
telemetry/
/state/
/usage-data/
/daemon/
/daemon.*
/dev-mods/
/bridge-spawn/
/seed-admin/
/.cc-writes/
/claude-code/
/plugins/
skills/.trash/
/.claude/
# Claude Code's runtime files, and the backups its updates and checks leave
stats-cache.json
mcp-needs-auth-cache.json
mcp-discover-verdicts.json
/.last-cleanup
/.last-update-result.json
/settings.json.bak*
*.bak-doctor
# a brain kept inside the kit is a repository of its own
/BRAIN/
# the jobs runner's state, and the machine's
/.kit-jobs/
__pycache__/
.DS_Store
```

`K/settings.json` ([§2.5](#25-settings)): transcripts kept, permissions that ask before each tool but allow what the
automatic checkpoint does (the three MCP tools it calls, reads and edits inside the brain, the brain as a working
directory), and two hooks. `<brain>` is the brain's absolute path, written in at step 4.

```json
{
  "cleanupPeriodDays": 36500,
  "permissions": {
    "defaultMode": "default",
    "allow": ["mcp__comando__decision_hold", "mcp__comando__decisions_settled", "mcp__comando__ui_get_state",
              "Read(/<brain>/**)", "Edit(/<brain>/**)"],
    "additionalDirectories": ["<brain>"],
    "deny": []
  },
  "hooks": {
    "SessionStart": [
      {"matcher": "startup", "hooks": [{"type": "command", "command": "sh \"${CLAUDE_CONFIG_DIR:-$HOME/.claude}/hooks/scratchpad-roll.sh\"", "timeout": 15}]},
      {"matcher": "compact", "hooks": [{"type": "command", "command": "cat \"${CLAUDE_CONFIG_DIR:-$HOME/.claude}/hooks/critical-rules.md\"", "timeout": 5}]}
    ]
  }
}
```

`K/hooks/scratchpad-roll.sh`, the roll ([§4.1.2](#412-scratchpad)). It finds the brain in `COMANDO_BRAIN`, which
comando sets, else `~/comando-brain`; it renames and reports, and leaves merging and filing to the session:

```sh
#!/bin/sh
# Keeps the scratchpad to one day folder, today's: renames the newest day folder to today, so topics and task
# numbers carry over; names any other day folder for the session to merge. Prints only what it did.
pad="${COMANDO_BRAIN:-$HOME/comando-brain}/SCRATCHPAD"
[ -d "$pad" ] || exit 0
today=$(date +%Y-%m-%d)
days=$(cd "$pad" && ls -d 2[0-9][0-9][0-9]-[0-1][0-9]-[0-3][0-9] 2>/dev/null | sort)
if [ ! -d "$pad/$today" ]; then
  last=$(printf '%s\n' "$days" | grep -v "^$today\$" | tail -n 1)
  if [ -n "$last" ]; then
    mv "$pad/$last" "$pad/$today" && echo "scratchpad: $last renamed to $today"
  else
    mkdir "$pad/$today" && printf '%s\n' '# Scratchpad' '' '| Folder | Holds |' '|---|---|' '' \
      '## Waiting on the user' '' '| # | Decision or action | Task | By |' '|---|---|---|---|' '' \
      '## Open' '' '## Scheduled reports' > "$pad/$today/INDEX.md" && echo "scratchpad: $today created"
  fi
fi
for d in $(cd "$pad" && ls -d 2[0-9][0-9][0-9]-[0-1][0-9]-[0-3][0-9] 2>/dev/null); do
  [ "$d" = "$today" ] || echo "scratchpad: $d is another day folder: merge it into $today by topic, then remove it"
done
```

`K/hooks/critical-rules.md`, put back after every compaction:

```markdown
After a compaction: the brain is the folder in $COMANDO_BRAIN. Memory and the KB never point at each other; only
/reflect writes the KB; every write updates its index; nothing is deleted for good (files to the Trash, folders with
rmdir).
```

`K/CLAUDE.md`, the instructions every session loads:

```markdown
# How sessions work here

The brain is the folder comando names in `$COMANDO_BRAIN` (outside comando: `brain` in this kit's comando-kit.json,
`~/comando-brain`). Paths below are inside it.

- **Memory** (`MEMORY/`) holds working context: the state of the work, the user's preferences and corrections,
  lessons. `MEMORY.md` is loaded at start: one line per file, saying when to open it. Open a file when its cue matches
  the task; when none does, search `MEMORY/` before answering. Re-read a file right before writing it and change only
  your own lines; changes to `feedback` and `user` files are confirmed by the user.
- **Scratchpad** (`SCRATCHPAD/<today>/`) holds provisional work by topic; every task has its entry in `INDEX.md` with
  State and Next. Rules: `SCRATCHPAD/README.md`.
- **KB** (`KB/`) holds checked knowledge, found by title from `00_START_HERE.md`. Only `/reflect` writes it, each
  statement verified against its source, refuted by an independent reviewer and approved by the user.
- **The checkpoint** is the `reflect` skill. Run with `--auto` it asks nothing and holds decisions back.
- **Agents** work from a spec in the topic's `work/` folder with an executable acceptance; writers only on disjoint
  files; every finding is verified before it is acted on.
- **Nothing is deleted for good**: files go to the Trash, an emptied folder goes with `rmdir`.
```

`K/skills/reflect/SKILL.md`, the checkpoint ([§6](#6-reflect-the-consolidation-step)):

```markdown
---
name: reflect
description: Consolidate the session into the brain - memory first, then the KB through its gate, then the scratchpad, every index kept true. comando sends it as the checkpoint, with --auto when it runs it at its threshold.
argument-hint: "[--auto]"
---

# /reflect

Arguments: `$ARGUMENTS`. The brain is the folder in `$COMANDO_BRAIN` (outside comando: `brain` in the kit's
comando-kit.json, `~/comando-brain`); the paths below are inside it.

**Asking.** With `--auto`, or in a headless run (a scheduled job, a prompt another session sent), ask the user nothing
and write nothing outside the brain: do steps 1, 2 and the liveness check of 4, then 5 and 6, in the newest day folder
when today's is missing (the start hook rolls it). Hold back each decision you would have asked (a memory change to
confirm, a KB statement, a done task to clear, a report to apply): one row in the day index's waiting table and, when
comando's MCP tool `decision_hold` is there, one call of it (`title`: the decision in one line; `detail`: what the user
needs to decide it, at most three lines). Otherwise, first call `ui_get_state` and ask the decisions in its `later` list
with this run's own, one form at a time; after the answers, call `decisions_settled` with the ids you settled.

0. **Roll.** When `SCRATCHPAD/<today>/` is missing, rename the newest day folder to today.
1. **Measure.** Note `MEMORY.md`'s lines and bytes and the files this session touched.
2. **Memory.** For each thing learned that will change behaviour in a later session, rewrite its entry as a rule and
   one line of evidence; never append. A new file has front matter (`name`, `description`, `type`: feedback, user,
   project or reference) and one index line, `- [<title>](<file>.md) — read when <triggers>`. Keep the index under 160
   lines and 24,000 bytes and each file under 32 KB: merge, shorten to the trigger or move to `archive/`, losing no
   date, code span, number or link. When the context opens with a compaction summary and a reflect ran before it,
   reflect only the turns after it.
3. **KB** (not with `--auto` or headless). Only what this session proved against its source. Verify each statement,
   have a reviewer agent try to refute it, ask the user to approve what survives, one batch per set; then write it
   through the set's `99_MAINTENANCE.md`: the statement, the header date, the source register, a change-log line.
4. **Scratchpad.** Liveness: every open task has State and a live Next. Collection (not with `--auto` or headless): a
   file no open task names in a code span is read, its true content folded into memory or the KB, then moved to the
   Trash; each done task has its decisions settled with the user, its files trashed and its entry deleted; anything
   changed after this run started is another session's live work and stays. Agent reports fold only when stamped
   `verified`. Turn each `FRICTION.md` line into a change, with the user, and delete the line.
5. **Indexes.** `MEMORY.md`, each set's `00_START_HERE.md`, the day's `INDEX.md`, and the index files of the folders the
   Files tab shows; a file without its index line is invisible.
6. **Report.** `MEMORY.md` before and after, files written, decisions held or settled; four lines at most.
```

`K/INSTALL.md`, needed once the kit is tagged on a remote ([§3.4](#34-version-and-upgrade)):

```markdown
# The kit

Created from comando's docs/kit-and-brain.md. Its brain is the folder its comando-kit.json names, a repository of its
own.

## Upgrade

1. `git fetch --tags origin`.
2. Show the user the local changes; commit them once they agree.
3. `git merge <tag>`; resolve every conflict so both changes hold.
4. Check that comando-kit.json and settings.json are valid JSON and the manifest's `kit` is 1.
5. Report what changed, file by file.

## Uninstall

Back the brain up first. When the kit has a jobs tool, remove its scheduled jobs:
`echo '[]' | python3 install/kit.py jobs set --root <kit>` (exit 0 or 1). Then the user moves the kit's folder to the
Trash; the brain's folder stays unless the user moves it too.
```

### 8.3 The brain's files

`B/MEMORY/MEMORY.md`:

```markdown
*Index only: one line per memory file, each a trigger saying when to open it. The facts live in the files. When no
line fits the task, search this folder before answering.*

## Start here

- Each memory is one file here with front matter (`name`, `description`, `type`: feedback, user, project or reference)
  and one line in this index: `- [<title>](<file>.md) — read when <the situations, names and error strings that should open it>.`
```

`B/KB/00_START_HERE.md`:

```markdown
# Knowledge base: start here

> **Last Updated:** <today> · **Status:** Current

Checked knowledge about the work this brain serves, each document readable on its own. The KB is a tree of sets: a
folder with its own 00_START_HERE.md and 99_MAINTENANCE.md. This file is the root set's way in; its rules are in
[99_MAINTENANCE.md](99_MAINTENANCE.md).

## Sets inside

None yet.

## Reading order

None yet.
```

`B/KB/99_MAINTENANCE.md`:

```markdown
# Knowledge base: maintenance

> **Last Updated:** <today> · **Status:** Current

## 1. Rules

A document belongs to the nearest set at or above its folder and is listed in that set's 00_START_HERE.md. It opens
with its title and the Last Updated line, reads on its own, carries no file:line anchors and never cites the
scratchpad. Only /reflect writes the KB, each statement verified against its source, refuted by a reviewer agent and
approved by the user.

## 2. Source register

| Source | What the set takes from it | Recorded at last sync | Documents that depend on it |
|---|---|---|---|
| — | — | — | — |

## 3. Drift check

| Source | Kind | Where | Recorded | Cadence | Checker |
|---|---|---|---|---|---|
| — | none | — | — | never | none |

## 4. Change log

- <today> — KB created
```

`B/SCRATCHPAD/README.md`:

```markdown
# Scratchpad

Provisional work: it may be wrong or superseded; read it as evidence, not as truth. Checkpoints move what is true into
the KB and working context into memory, and the scratchpad empties as tasks finish.

- **One day folder, today's.** At session start the newest day folder is renamed to today, so topics and task numbers
  carry over. Another day folder is merged into today's topic by topic, its INDEX.md parked in `work/` and folded;
  anything else at the top moves to today's `inbox/` and is filed into a topic.
- **Topics.** Short lower-case topic folders (at most 20 characters), each with numbered documents `NN-<what>.md` (at
  most 25 characters) and `work/` for raw material (names at most 22). No date in a name; the name says what the file
  holds; a topic's living plan is `00-<topic>-plan.md`.
- **The index.** `INDEX.md` in the day folder: `# Scratchpad`, the table of topic folders, `## Waiting on the user`
  (every open decision, each naming its task), `## Open` with one `### <topic>/` heading per topic and each task as
  `#### N. <title>`, and last `## Scheduled reports`.
- **A task.** Ticket or Repo, State (done, waiting on whom, in progress, with the last verified fact), Next (who moves
  it, and how), Resume from, and its documents, each a list item led by its path relative to the index
  (`` `topic/01-x.md` — what it holds ``), so comando's Trash takes the line out with the file. A new task takes the
  highest number in the index plus one, read just before writing; numbers never change.
- **Pointers** to the scratchpad carry no date: `topic/01-x.md`, task 3.
- **Agent reports** in `work/` are `<name>-report.md` with a stamp in their front matter: `generated` (by, at in UTC,
  against the checkout and its commit, `+dirty` with uncommitted changes) when they land, `verified` (by, at, result)
  once checked. Only verified reports are folded.
- **FRICTION.md** lines read `- <date> <agent type> <spec or task>: <what got in the way> → <what would have prevented
  it>`; agents only append; /reflect deletes the lines it handled and keeps the file.
- **Collection** (a /reflect the user starts): the index's open tasks are the only pointers into the scratchpad; a file
  none names in a code span is read, its true content folded, then moved to the Trash; each done task has its
  decisions settled with the user, its files trashed and its entry deleted; anything changed after the run started is
  live work and stays.
- **Deleting** is per artefact, never per folder: files go to the Trash, an emptied folder goes with `rmdir`, never a
  recursive delete; parallel sessions write the same day folder.
```

`B/SCRATCHPAD/FRICTION.md`: the line `# Friction`.

`B/SCRATCHPAD/<today>/INDEX.md`:

```markdown
# Scratchpad

| Folder | Holds |
|---|---|

## Waiting on the user

| # | Decision or action | Task | By |
|---|---|---|---|

## Open

## Scheduled reports
```

### 8.4 A kit or a brain the user brings

A kit is any folder whose `comando-kit.json` reads, at one of the roots of [§3.1](#31-the-root); a brain is the folder
`COMANDO_BRAIN` or the manifest's `brain` names, anywhere, a local repository's folder included, and applies only with a
kit. A user who brings a kit puts it at `~/comando-kit`, or starts comando with `CLAUDE_CONFIG_DIR` naming it. A user
who brings only a brain gets the kit of [§8.2](#82-the-kits-files) with `brain` set to that folder (and the roll hook's
fallback path changed to it). Either is checked against the contract alone, and each gap reported to the user with its
fix, never fixed unasked:

| Check | Passes when |
|---|---|
| the manifest | the commands of [§8.1](#81-the-steps) step 6 pass, and comando names no left-out folder at start |
| the Files tab | one folder `"."` alone in group `Kit`; the brain's folders with `root` `brain`, in one group ([§3.2](#32-the-files-tab)) |
| the ignore list | the loop of [§8.1](#81-the-steps) step 6 prints `checked` alone; a brain inside the kit is ignored too |
| retention | `cleanupPeriodDays` set high, or a sweep that spares comando's sessions |
| compaction | `autoCompactWindow` unset or above comando's threshold |
| permissions | the rules the user wants in `settings.json`, with what the automatic checkpoint does allowed ([§2.5](#25-settings)): the three MCP tools, `Read` and `Edit` of the brain by its absolute path, the brain in `additionalDirectories`; comando adds none |
| protected paths | the brain is not under a `.claude` path, or the user accepts that every brain write asks ([§2.5](#25-settings)) |
| the checkpoint | `skills/reflect/SKILL.md` exists and handles `--auto` with `decision_hold`, and a user-started run settles with `decisions_settled` |
| jobs | no `jobs`, or an `install/kit.py` that answers the three calls of [§3.5](#35-scheduled-jobs) |

### 8.5 After a comando upgrade

A created kit drifts from the model as its user adds rules, skills and jobs: that is expected. What must not drift is
the contract. When the new release's document changes a contract section ([§2](#2-the-kit)), the user asks a session
working in the kit's folder to compare the kit with that document's contract sections, read at the new tag, and to
propose the changes; the user approves them, and the session commits them in the kit.
