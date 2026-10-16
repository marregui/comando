---
name: reflect
description: Write the session so far into memory, correct the knowledge base where the session proved it wrong, and consolidate the scratchpad, keeping every index accurate and the memory index within its budget. Run at comando's checkpoint (60% of the context window), at the end of a session, or when the user asks.
---

Write the session so far into memory, then correct the KB where this session proved it wrong and consolidate the
`SCRATCHPAD/` day folders, keeping every index accurate. Compaction is never blocked on it. The knowledge base (`KB/`)
and the scratchpad (`SCRATCHPAD/`) are in Claude Code's folder (`$CLAUDE_CONFIG_DIR`, else `~/.claude`), as the comando
method read at session start names them.

**The aim of every run:** the next session recalls more of what matters from fewer bytes, and the KB is truer. A run
that leaves memory larger without making it sharper has not done its job.

Memory is Claude Code's auto memory, one folder for every project (`autoMemoryDirectory`, named in the system prompt);
`MEMORY.md` is its index. Each memory file has YAML frontmatter with `name`, `description` and `metadata.type` (user,
feedback, project, reference). The session log is `session_log.md` in the same folder, a memory file like any other
(frontmatter, an index line): one dated line per topic per session, where displaced detail goes.

**Parallel sessions.** Several sessions may write memory at once. Re-read every file immediately before writing it,
change only the lines this session owns, and change shared files with the Edit tool (an exact replacement of your own
lines), never a full-file Write: a concurrent change then fails the edit instead of being lost.

0. **Measure before.** Note `MEMORY.md`'s size (`wc -c -l`) for the report.

1. Read `MEMORY.md`, then only the memory files relevant to what happened this session.

2. **Keep what lasts.** For each candidate observation ask: will this change what Claude does in a future session, in
   this project or another? Per-task implementation details, facts the code already states and context that only
   mattered today are not memory. The memory is shared by every project: write a fact so that it applies wherever it
   holds, and name the project in it when it holds for one project only.

3. **Place each observation:**
   - the user's role, preferences, way of communicating → `user` files;
   - corrections and confirmed approaches → `feedback` files, with a **Why:** and a **How to apply:** line;
   - ongoing work, goals and constraints the code does not show → `project` files, with absolute dates;
   - pointers to external systems → `reference` files.

   Most observations change an existing entry: rewrite it into its simplest unambiguous form (the rule plus one line of
   evidence) instead of appending a second dated paragraph. Displaced detail moves to the session log.

4. **Recall.** Index lines are triggers, not summaries: a `MEMORY.md` line says when to open its file (situations, error
   strings, names, repositories), in about 150–250 characters; facts, versions and states go in the file. If this
   session found that a memory file held the answer but was not opened, or opened a file whose cue did not match,
   sharpen that file's cue now: these recall misses are what the index is tuned on.

5. **Budget.** Claude Code loads `MEMORY.md` into every session only up to 200 lines or 25 KB; what lies past that is
   never seen. Keep it within 160 lines and 20 KB: when this run would pass that, first merge entries that answer the
   same trigger, move closed work to `archive/` (its cue goes too), and shorten cues to their triggers. Never drop a fact
   to make room: facts live in the files, which are read on demand.

6. **Memory and the KB are separate.** Memory never points into the KB, and the KB never cites memory. If this session
   checked a KB document against the published code or its source and found it wrong or incomplete, correct the
   document now: self-contained, citing code by path and symbol, never by line number. A fact memory holds is moved
   into the KB only once this session checked it against the code. Unchecked suspicions are not written anywhere.

7. **Consolidate the scratchpad, after memory** (rules: `SCRATCHPAD/README.md`). For each day folder this session wrote
   to, and each older one whose tasks it touched, go task by task through its `ALPHA.md`: fold what the session proved
   true against the code into the KB document it belongs to (or a new one, listed in `KB/ALPHA.md`); delete the
   artefacts that are folded or obsolete, noting in the entry what was dropped and why; rewrite the entry to carry only
   what a later session needs (State, Next, artefacts kept), or delete it when nothing is left, never renumbering the
   others; delete the folder when its index has no task left. A task that still needs the user (an open decision, an
   unverified finding) stays, and its State says so.

8. **Indexes are part of the write.** Every memory file created, renamed or deleted changes its line in `MEMORY.md` in
   the same step; every KB document its row in `KB/ALPHA.md`; every scratchpad artefact its line in its day's
   `ALPHA.md`. Finish with `index-check` (on the PATH): it must report no defect this session caused, and it warns when
   `MEMORY.md` passes its budget.

9. **Prune within the files already read.** Merge duplicates into the more specific file. Delete only facts proven
   false, and say what disproved them. Label what could not be verified "believed but unchecked".

10. **Ask once, briefly,** before changing `feedback` and `user` files: one line per change; the user's reply settles
    all of them. Write the rest without asking.

11. **Report in at most four lines:** files written; KB documents corrected or consolidated; the delta (`MEMORY.md`
    bytes and lines before → after, cues added or sharpened, entries merged or archived, recall misses fixed); anything
    awaiting the user.

Agents do not see the memory: when this session dispatches one, its spec's Context section quotes the memory facts and
KB passages the task needs (the agent-spec template).
