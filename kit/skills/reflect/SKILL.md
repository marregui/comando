---
name: reflect
description: Write the session so far into memory, correct the knowledge base where the session proved it wrong, and consolidate the scratchpad, keeping every index accurate and memory within its budgets. Run at comando's checkpoint (60% of the context window), at the end of a session, or when the user asks; `--no-ask` in a session the user is not watching.
argument-hint: "[--no-ask]"
---

Write the session so far into memory, then correct the KB where this session proved it wrong and consolidate the
`SCRATCHPAD/` day folders, keeping every index accurate. Compaction is never blocked on it. The knowledge base (`KB/`)
and the scratchpad (`SCRATCHPAD/`) are in Claude Code's folder (`$CLAUDE_CONFIG_DIR`, else `~/.claude`), as the comando
method read at session start names them. Arguments: `$ARGUMENTS`; `--no-ask` makes step 10 queue instead of asking, and
a session the user is not watching (`claude -p`, a comando `session_send` prompt) runs as if it was given.

**The aim of every run:** the next session recalls more of what matters from fewer bytes, and the KB is truer. A run
that leaves memory larger without making it sharper has not done its job; the report of step 11 shows which it was.

Memory is Claude Code's auto memory, one folder for every project (`autoMemoryDirectory`, named in the system prompt);
`MEMORY.md` is its index. Each memory file has YAML frontmatter with `name`, `description` and `metadata.type` (user,
feedback, project, reference). The session log is `session_log.md` in the same folder, a memory file like any other
(frontmatter, an index line): one dated line per topic per session, where displaced detail goes.

**Parallel sessions.** Several sessions may write memory and the scratchpad at once. Re-read every file immediately
before writing it, change only the lines this session owns, and change shared files with the Edit tool (an exact
replacement of your own lines), never a full-file Write: a concurrent change then fails the edit instead of being lost.

## A. Measure

0. `t=$(reflect-report begin)` (on the PATH): it snapshots the memory folder and prints a token; keep the token for
   step 11.

1. Read `MEMORY.md`, then only the memory files relevant to what happened this session.

## B. Memory, in this session

2. **Keep what lasts.** For each candidate observation ask: will this change what Claude does in a future session, in
   this project or another? Per-task implementation details, facts the code already states and context that only
   mattered today are not memory. The memory is shared by every project: write a fact so that it applies wherever it
   holds, and name the project in it when it holds for one project only.

3. **Place each observation:**
   - the user's role, preferences, way of communicating → `user` files;
   - corrections and confirmed approaches → `feedback` files, with a **Why:** and a **How to apply:** line;
   - ongoing work, goals and constraints the code does not show → `project` files, with absolute dates;
   - pointers to external systems → `reference` files.

   **Rewrite, do not append.** Most observations change an existing entry: rewrite it into its simplest unambiguous
   form (the rule plus one line of evidence) instead of appending a second dated paragraph. Displaced detail moves to
   the session log. `reflect-report end` (step 11) names every file whose only change was added lines; a file on that
   list other than a dated log is reopened and its new paragraph merged into the entry it extends before the report
   goes out.

4. **Recall.** Index lines are triggers, not summaries: a `MEMORY.md` line says when to open its file (situations, error
   strings, names, repositories), in about 150–250 characters; facts, versions and states go in the file. If this
   session found that a memory file held the answer but was not opened, or opened a file whose cue did not match,
   sharpen that file's cue now: these recall misses are what the index is tuned on. `cue-replay` (on the PATH; run it
   monthly, by a scheduler or by hand; `--dry-run` shows the pairs alone) replays the last month's prompts against the
   index with a small judge and writes a report to the day's scratchpad folder: the files most often opened without
   their cue matching, with the prompts the cue has to catch. Apply its §5, note the recall figure in the session log,
   then consolidate the report like any artefact.

5. **Budgets** (`index-check` warns on each):
   - `MEMORY.md`: Claude Code loads it into every session only up to 200 lines or 25 KB; what lies past that is never
     seen. Keep it within 160 lines and 20 KB: when this run would pass that, first merge entries that answer the same
     trigger, move closed work to `archive/` (its cue goes too), and shorten cues to their triggers. Never drop a fact
     to make room: facts live in the files, which are read on demand.
   - Each memory file: 32 KB. A file past it takes no addition until, in the same run, an entry of it is merged or
     moved to `archive/`; one file per run is enough, named in the report. A merge or a move is checked with
     `compact-check <copy taken before> <file> [<archive>]` (on the PATH): every date, backtick span, quote, ticket,
     number, link and heading of the original must survive somewhere; a `missing` line is put back.
   - A dated log (`session_log.md`, any file whose entries are dated lines) rolls by month: lines dated before the
     previous month move to `archive/<name>_YYYY-MM.md` (one file per month, created when needed, with frontmatter and
     an index line).

9. **Prune within the files already read.** Merge duplicates into the more specific file. Delete only facts proven
   false, and say what disproved them. Label what could not be verified "believed but unchecked".

10. **Ask once, briefly,** before changing `feedback` and `user` files: one line per change; the user's reply settles
    all of them. Write the rest without asking. With `--no-ask`, or in a session the user is not watching, these changes
    are neither written nor asked: they go as one task entry in today's day folder's `INDEX.md`
    (`## N. Pending memory changes from /reflect --no-ask`, one line per change, **State:** waiting on the user); the
    next `/reflect` the user watches asks them first and deletes the entry.

## C. KB and scratchpad, in this session

Steps 6–7 run here, after steps 2–10 are written, never in a fork or another agent: this session checks every KB and
scratchpad change itself, and the report names the files changed and the entries rewritten.

6. **Memory and the KB are separate.** Memory never points into the KB, and the KB never cites memory. If this session
   checked a KB document against the published code or its source and found it wrong or incomplete, correct the
   document now: self-contained, citing code by path and symbol, never by line number. A KB set with a maintenance file
   of its own (a `99_MAINTENANCE.md` or the like) is corrected by its procedure: the statement, the header date, the
   source register, a change-log line. A fact memory holds is moved into the KB only once this session checked it
   against the code. Unchecked suspicions are not written anywhere.

7. **Consolidate the scratchpad, after memory** (rules: `SCRATCHPAD/README.md`). For each day folder this session wrote
   to, and each older one whose tasks it touched, go task by task through its `INDEX.md`: fold what the session proved
   true against the code into the KB document it belongs to (or a new one, with its line in its set's `00_START_HERE.md`); delete the
   artefacts that are folded or obsolete, noting in the entry what was dropped and why; rewrite the entry to carry only
   what a later session needs (State, Next, artefacts kept), or delete it when nothing is left, never renumbering the
   others. A task that still needs the user (an open decision, an unverified finding) stays, and its State says so.

   **Deletes are per artefact, never per folder.** `rm -rf` is never run on a day folder: delete only the files and
   `INDEX.md` entries this session created or consolidated; an artefact this session does not know, or newer than this
   session's last write to the folder, belongs to another session and stays. A day folder goes only when `ls -A` shows
   just an `INDEX.md` with no task left: delete that file, then `rmdir`.

## D. Indexes and report

8. **Indexes are part of the write.** Every memory file created, renamed or deleted changes its line in `MEMORY.md` in
   the same step; every KB document its line in its set's `00_START_HERE.md`, and a new set its line in its parent's; every scratchpad artefact its line in its day's
   `INDEX.md`; in a folder comando's Files tab shows (`~/.comando/folders.json`, inside Claude Code's folder), every
   entry at the folder's top level its line in the folder's `index` file (comando creates the file when it is named and
   missing), and, in a folder whose entries go to the Trash, every entry of a subfolder its line in that subfolder's
   `listedIn` file, created with its first line. `index-check` (on the PATH) must report no defect this session caused.

11. **Report:** the output of `reflect-report end $t` (`MEMORY.md` before → after, lines added and removed per file,
    the append-only list, the budget warnings, the index check), then at most four lines: files written; KB documents
    corrected or consolidated; cues added or sharpened, entries merged or archived, recall misses fixed; anything
    awaiting the user.

Agents do not see the memory: when this session dispatches one, its spec's Context section quotes the memory facts and
KB passages the task needs (the agent-spec template).
