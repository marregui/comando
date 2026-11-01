# The kit and the brain

comando is one of three parts I think of as one thing, my flow: comando is the window, the kit is how the sessions in it
behave, and the brain is what they remember and know. I keep the three apart because they change at different speeds and
for different reasons. The kit changes when I decide it should, one reviewed commit at a time; the brain changes every
day, as the sessions work; comando changes when the window needs to. This document is the model of the kit and the
brain, and what comando does with them. A kit repository and a brain on disk are each one incarnation of the model. The
reference kit, `marregui/comando-kit`, is mine and private, and so is the brain beside it, so nothing here depends on
their content.

1. [The model: a session and its five parts](#1-the-model-a-session-and-its-five-parts)
2. [The kit](#2-the-kit)
3. [comando and a kit](#3-comando-and-a-kit)
4. [The brain](#4-the-brain)
5. [Flows: comando, the session, the kit and the brain](#5-flows-comando-the-session-the-kit-and-the-brain)
6. [/reflect: the consolidation step](#6-reflect-the-consolidation-step)
7. [The learning loop: how the brain gets sharper](#7-the-learning-loop-how-the-brain-gets-sharper)

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

A kit is a Claude Code setup kept in a git repository and versioned by tags `vMAJOR.MINOR.PATCH`. Its parts sit at the
top of the repository, each where Claude Code loads it from a config folder; no part is a plugin. comando runs every
session on it ([record 80](adr/0080-the-kit-is-a-claude-code-setup-comando-runs-its-sessions-on.md)).

### 2.1 Parts

| Part | What it holds |
|---|---|
| `CLAUDE.md` | the instructions every session loads |
| `settings.json` | Claude Code's settings, the hooks among them; each hook names its script as `${CLAUDE_CONFIG_DIR:-$HOME/.claude}/hooks/…` |
| `skills/` | the skills; `reflect` answers the checkpoint ([Claude Code §2](claude-code.md#2-the-checkpoint)) |
| `agents/` | the subagent types the sessions dispatch |
| `workflows/` | saved workflows |
| `hooks/` | the hooks' scripts; one, at every session start, puts `bin/` on the Bash tool's `PATH` |
| `bin/` | the scripts on the Bash tool's `PATH` |
| `tools/` | each script's code and its test |
| `templates/` | the agent spec and the knowledge base's set templates |
| `comando-kit.json` | what comando reads: the kit's name, its brain, the Files tab's folders, its scheduled jobs, and what it needs outside the folder |
| `INSTALL.md`, `install/` | the install, upgrade and uninstall recipe, and its installer with the brain's skeleton |

comando's code reads nothing of a kit but `comando-kit.json`; the rest of the layout is what the sessions comando starts
work by. Conformance is documented, not checked: a kit that lacks a part runs without what that part carries.

### 2.2 A session's life, and which part acts

| Moment | Claude Code event | What the kit does |
|---|---|---|
| start | `SessionStart` | `CLAUDE.md` and the settings load; a hook puts `bin/` on `PATH`; the brain's memory index loads (`autoMemoryDirectory`); a hook may prepare the brain (the scratchpad's day folder) |
| a prompt | `UserPromptSubmit` | hooks put context in: the memory files, KB lines and tasks that match, what other sessions did; a skill runs when named |
| a tool call | `PreToolUse` | guards may refuse a call: a kill by pattern, a delete without asking, another instance's files; agents run on dispatch; `bin/` runs through Bash |
| a tool done | `PostToolUse` | checks after the fact: the brain's indexes after a write, reporting what is now missing; diagrams in a written document; the context's size |
| a compaction | `SessionStart` (`compact`) | the rules that must survive go back in context |
| the checkpoint | comando's | the `reflect` skill writes memory, corrects the KB and folds the scratchpad ([§6](#6-reflect-the-consolidation-step)), then comando sends `/compact` ([record 129](adr/0129-a-checkpoint-reflects-then-compacts.md)) |

A hook adds context or blocks a call; it never rewrites a prompt. A session an agent created runs with
`COMANDO_CREATED_BY` set, so hooks can leave out what only a person needs ([Claude Code §1.1](claude-code.md#11-the-process)).

### 2.3 Outside any session

comando reads `comando-kit.json` at start: the brain's folder, the Files tab's folders, the jobs. The kit's installer
writes the jobs to the platform's scheduler; each runs headless, outside comando, and leaves a report in the brain's
scratchpad for `/reflect` ([§3.5](#35-scheduled-jobs),
[§7](#7-the-learning-loop-how-the-brain-gets-sharper)).

## 3. comando and a kit

This section is what comando does with a kit. comando does not install one: the agent that installs comando offers the
reference kit or one you name ([INSTALL.md §5](../INSTALL.md#5-the-kit)), and comando finds it at start
([record 80](adr/0080-the-kit-is-a-claude-code-setup-comando-runs-its-sessions-on.md)).

### 3.1 The root

The root is the Claude Code folder comando's sessions run on, decided once at start (`KitRoot`):

| When | Root |
|---|---|
| comando was started with `CLAUDE_CONFIG_DIR` | that folder |
| `~/.claude/comando-kit.json` exists and parses | `~/.claude`: the user's Claude Code folder is a kit checkout |
| `~/.claude/comando-kit/comando-kit.json` exists and parses | `~/.claude/comando-kit`: a clone of a kit at a tag, beside the user's own setup |
| otherwise | `~/.claude`, without a kit |

A `comando-kit.json` that does not parse is logged and counts as no kit. Every `claude` comando starts (sessions, the
plan usage reader, the topics, the version check) gets `CLAUDE_CONFIG_DIR=<root>` when the root is not `~/.claude`,
and, with a kit, `--settings '{"autoMemoryDirectory": "<root>/<brain>/MEMORY"}'`. Stored conversations and their import
are read from `<root>/projects`. A session on a root other than `~/.claude` neither reads nor
writes `~/.claude`. Claude Code signs a new root in once, with `CLAUDE_CONFIG_DIR=<root> claude auth login`.

### 3.2 The Files tab

`comando-kit.json` lists the kit's folders with the fields of a `folders.json` entry ([Workbench §6](workbench.md#6-the-files-tab)), each path
relative to the root, without `..`; `.` is the root itself, a folder whose excludes leave out the rest (the brain must
be a folder below the root). When `folders.json` holds none of them, comando adds them at the top, their paths made
absolute under the root, once, and saves; the user's own entries stay, and a kit folder whose name an entry already
takes is not added (`KitFolders`). An entry that names the same folder on disk as a kit folder (links followed, letter
case ignored) counts as that folder, so an entry through a link is not added twice. **Use the kit's folders** in the
Files tab's folders window adds the missing ones again.

### 3.3 Back up the brain and the kit

**Back up the brain** (the palette and the menu of the Files tab's BRAIN row) runs in
`<root>/<brain>`, off the EDT: `git add -A`, a commit when anything changed (nothing to commit is not an error), `git
push`. The commit's first line says what it adds, `Brain backup 2026-10-28: MEMORY 2 changed; SCRATCHPAD 3 added, 1
changed` (each top folder, or a file at the top, with its counts; past 120 characters the rest are counted), and its
body lists the files, one a line (`added MEMORY/x.md`, `renamed a -> b`; at most 200). **Back up the kit** (the same
places, the KIT row) does the same in the root, its message `Kit backup …`. A notice names the commit or the step that
failed. Each action is off while its folder is not the top folder of a git repository with a remote, and neither runs
by itself ([record 118](adr/0118-brain-and-kit-are-rows-each-one-unit.md)).

### 3.4 Version and upgrade

The Files tab's KIT row shows the kit's commit and, when `git -C <root> ls-remote --tags origin` lists a newer `vX.Y.Z`,
"‹commit› · vB available", with a notice the first time a newer release is seen
(`git -C <root> describe --tags --always` still gives the version the notice names). Both are read at start and every 6
hours, off the EDT, never holding the window.

**Upgrade the kit…** (the palette, and the KIT row's menu) asks for a tag, newest first, then opens or creates a session
named `kit`, working in the root and started on the user's own Claude Code setup rather than the root's (it replaces the
root's files) when the root is `~/.claude` or inside it, on the root itself when the root is elsewhere, another
instance's kit ([record 122](adr/0122-instances-name-kit-brain-data-root-edge.md)), and sends it: "Upgrade the kit at
<root> to <tag>, following <root>/INSTALL.md section Upgrade. Report what changed." Once the session reports, comando
offers to restart the sessions still running on the old kit.

### 3.5 Scheduled jobs

The kit's `comando-kit.json` lists its scheduled jobs (`jobs`); `install/kit.py` alone writes them to the platform's
scheduler and runs them, and the kit's `INSTALL.md` §5 gives the fields and the verbs
([record 117](adr/0117-kit-jobs-a-row-and-a-window.md)). The Files tab shows them as one **Jobs** row, the first inside
the KIT row ([record 118](adr/0118-brain-and-kit-are-rows-each-one-unit.md)): its detail counts them and names what runs
now or the next run (`5 jobs · 1 paused · next Thu 07:15`); the dot marks a change the scheduler has not taken (a job
running when it was saved is left as it was), a red name a failed last run or jobs that cannot be read; its tooltip says
the most pressing of these. comando reads them through `kit.py jobs --json` at start, after every change, and every
minute while the Files tab shows.

A double click, Enter, or **Configure kit jobs** (the row's menu, the Files tab's menu, the palette) reads the jobs
again and opens the **Kit jobs** window on them (a list that cannot be read opens none, and a notice says why): the jobs
on the left, +, −; the selected job's name, description, days of the week, time, program (a path inside the kit, picked
with …), arguments, environment (`NAME=value` lines), log and Enabled on the right, checked as typed, and under them,
read only, whether the scheduler holds it, its last run and its next. A job's row menu runs it now, through the
scheduler, opens its log, or removes it. **Save** hands the whole list to `kit.py jobs set`, which checks every job,
rewrites only the manifest's `jobs` block and brings the scheduler to it; the window closes when the list was taken and
stays open with `kit.py`'s words when it was refused. **Apply pending changes**, in the row's menu while something is
pending and nothing runs, reads the jobs again and hands them over. comando never writes the scheduler's files.

## 4. The brain

A brain is a folder of plain files, `<root>/<brain>/` (`brain` in the kit's `comando-kit.json`). The kit's installer
creates it from a skeleton; from then on the sessions write it.

| Store | Holds | Certainty | Changed by |
|---|---|---|---|
| `MEMORY/` | working context: the state of the work, the user's preferences and corrections, lessons, references; an index, `MEMORY.md`, with one cue per file | what was true when written | any session; the consolidation step, `/reflect` |
| `SCRATCHPAD/` | today's work: topic folders, numbered documents, raw agent output, a task index | provisional | any task, agent or scheduled job |
| `KB/` | checked knowledge: sets of documents, each kept true against named sources | must match its sources | `/reflect` only |
| `EVAL/` (optional) | an answer key: past queries labelled with the memory files they needed | labelled by hand | `/reflect`, with the user |

Three rules hold the model together:

1. **Memory and the KB never point at each other.** Memory changes daily and is private to the work; a KB document
   must read on its own, cold.
2. **One writer for the KB.** What the KB states as fact passed one step, `/reflect`, in a session that checked it.
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

A kit sets the budgets; whatever they are, the index stays within what Claude Code loads, and nothing is dropped to fit:
entries are merged, shortened to their trigger, or archived.

#### 4.1.2 Scratchpad

| Part | Role | Written by |
|---|---|---|
| the day folder | one folder, today's, renamed each day so topics and task numbers carry over; topic folders with numbered documents and `work/` for raw material | any session, agents; a hook renames it |
| `INDEX.md` | every task with its State and Next, and what waits on the user | sessions, scheduled jobs, `/reflect` |
| reports | the scheduled jobs' findings; agents' reports, stamped `generated`, then `verified` once checked | jobs, agents; `/reflect` folds or deletes them |
| `FRICTION.md` | what got in agents' way, one line each | agents append; `/reflect` turns each into a change |

#### 4.1.3 KB

| Part | Role | Written by |
|---|---|---|
| sets | a folder with `00_START_HERE.md` (the way in, one line per document) and `99_MAINTENANCE.md` (rules, sources, drift checks, change log) | `/reflect`; a new set when the user asks |
| the checks table | which source each set is kept true against, and how often it is checked | `/reflect`; read by a drift-check job |
| the change log | one line per correction | `/reflect` |

#### 4.1.4 EVAL

`EVAL/` is optional: a kit that measures memory recall keeps its answer key here, hand-labelled rows of past queries
with the memory files whose rules applied. It starts empty and grows by use ([§7.2](#72-eval-the-answer-key)).

### 4.2 The kit and the brain side by side

| | The kit | The brain |
|---|---|---|
| holds | how sessions behave | what sessions know |
| written by | the user, deliberately; each change reviewed and committed | the sessions as they work, under the kit's rules |
| versioned | tags; upgraded by a session named `kit` | commits; backed up from comando |
| shared | one kit can run many people's sessions | one per person or instance; it starts as a skeleton |
| in short | a recipe: it says how, never what was learned | a record: its content is what use has put there |

The brain is never part of the kit: the kit's repository ignores it, and it can be a repository of its own.

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
| the conversation | `<root>/projects/<dir>/<id>.jsonl`, sub-agents beside it | `claude` | `--resume`; comando, to draw history; the kit's measuring jobs | as long as the kit keeps transcripts |
| comando's desk | `~/.comando/sessions.json`, panels, folders, connections | comando | comando; a kit hook that reports on other sessions | permanent |
| metrics | `~/.comando/metrics/` | comando | meant for the kit ([record 110](adr/0110-metrics-for-calibration.md)) | permanent |
| audit | `~/.comando/audit/` | comando's MCP server | the user | permanent |
| live tokens | `~/.comando/run/*.mcp.json` | comando | `claude` | deleted at exit |
| the kit | `<root>` | the user | every `claude` | versioned |
| the brain | `<root>/<brain>` | the sessions, under the kit's rules | every session | grows by use |

`sessions.json` ties a sidebar session to its conversation through `claudeSessionId`; the kit and the brain reach a
process through `CLAUDE_CONFIG_DIR` and `autoMemoryDirectory` ([§3.1](#31-the-root)).

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
| the context crosses a threshold | a kit hook reminds; with comando's threshold set, comando runs it at the turn's end ([record 129](adr/0129-a-checkpoint-reflects-then-compacts.md)) |
| the Checkpoint button | comando: `/reflect`, then `/compact` |

### 6.2 Three depths

| Run | Does |
|---|---|
| full | every step |
| at the threshold | Measure, Memory and the scratchpad's liveness only, so it stays small; reports wait for a full run |
| headless | as full, but changes to `feedback` and `user` memory are queued in the task index and asked next time |

### 6.3 The steps

| Step | Reads | Changes |
|---|---|---|
| Measure | the memory index and the files the session touched | nothing; a snapshot for the report |
| Memory | the conversation | memory entries rewritten (a rule plus one line of evidence), cues sharpened to triggers, the topic's state, the logs through their tools, the recall report's fixes, new `EVAL` rows; kit edits proposed by a job, once the user confirms |
| KB | corrections proved against published code; the drift report | KB documents through their set's `99`: the statement, the header date, the sources' state, a change-log line |
| Scratchpad | the tasks the session touched | proved findings folded into the KB, folded artefacts deleted one by one, entries cut to State and Next; agent reports fold only when stamped `verified`; friction lines turned into changes |
| Indexes | every file written | `MEMORY.md`, each set's `00`, the day's `INDEX.md`; an index check after each write |
| Report | the snapshot | the index before and after, lines changed per file, files only appended to, budget warnings; four lines at most |

### 6.4 Why it is built this way

The KB's one writer, memory and KB never pointing at each other, and the index's budget are the brain's rules
([§4](#4-the-brain), [§4.1.1](#411-memory)). Two reasons are this step's own:

- Rewrite, not append: an entry stays one rule and its evidence; displaced detail goes to the session log or `archive/`.
- Sessions run in parallel: re-read before every write, change only your own lines, delete per artefact, never per folder.

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
| who adds rows | `/reflect`, a few each week, from a seeded sample of the week's queries, labelled with the user; a correction caused by a missed cue goes in too |
| who reads it | the recall tool: loaded over applicable, per query class, with a confidence interval |
| where | in the brain, not the kit: its rows describe the user's real prompts |

A weekly replay measures the same quantity without labels: the week's prompts against the index as it stood before the
week, judged by a small model. The answer key is the slower, exact check; the replay is the frequent, rough one.

### 7.3 The measuring jobs

| Job | Reads | Measures | Applied by |
|---|---|---|---|
| recall replay | transcripts, the memory index | whether the index routed each prompt to the files it needed; files opened without their cue matching | `/reflect`: cues sharpened |
| correction curator | the week's corrections from the transcripts | each correction's cause in the kit: cue missed, rule ignored, no rule | the user confirms; `/reflect` applies |
| drift check | each KB set's checks table and its sources | statements that no longer match their source | `/reflect`, each item checked first |
| agent runs | one record per agent run, filled from comando's `agent` event when there is one | pass rates per model and agent type, with time and output tokens | the routing of later agent work |

### 7.4 comando's metrics

comando records every turn, sub-agent and plan-usage read ([record 110](adr/0110-metrics-for-calibration.md)). The
record names the kit as their reader: which model and effort for which work, pacing against plan limits, the records of
agent runs. What comando reads back itself is in [Claude Code §3.2](claude-code.md#32-the-tools).

Agent runs are the first reader: a kit may keep one record per agent run, filled from comando's `agent` event (the agent
type, the model it ran on, its duration and output tokens), and route later agent work by the pass rates. Whether the
run passed stays a judgement the session records. Pacing against plan limits and model-and-effort calibration read
nothing yet.
