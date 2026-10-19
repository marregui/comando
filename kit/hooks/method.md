# The comando method

*Read at every session start (new, resumed, cleared) and after each compaction, from the comando kit (a Claude Code plugin, at `@KIT@`). `@CLAUDE@` is Claude Code's folder.*

## Where knowledge is kept

| What | Where | Holds |
|---|---|---|
| Memory | Claude Code's auto memory, one folder for every project: the `autoMemoryDirectory` your system prompt names (the kit sets it to `@CLAUDE@/MEMORY` unless you chose another); `MEMORY.md` is its index, loaded into every session up to 200 lines or 25 KB | working context that accrues across projects: the user's preferences, corrections, lessons, the state of ongoing work. One fact per file, with frontmatter; one line per file in the index, a cue saying when to open it. Open a file when its cue matches the task. Never a copy of the KB or of the code |
| Knowledge base | `@CLAUDE@/KB/`; `ALPHA.md` is its index | self-contained documents about the products and code the user works on; each must match the published code. Rules: `@CLAUDE@/KB/README.md` |
| Scratchpad | `@CLAUDE@/SCRATCHPAD/YYYY-MM-DD/`; each day's `ALPHA.md` is its index | provisional reports, agent specs and results, not yet checked against the code. Rules: `@CLAUDE@/SCRATCHPAD/README.md` |

## Working a task

1. Load the context first, in parallel: the memory files the task needs, the KB documents found by title in `KB/ALPHA.md`, the code.
2. A fact in doubt: check the KB, then check the KB against the code. The code wins.
3. An agent is given a spec, copied from `@KIT@/templates/agent-spec.md` to the day's `SCRATCHPAD/…/work/` folder: the context it needs, quoted from memory and the KB (an agent does not see the memory), the files it may touch, what it must not do, and an executable acceptance check. No context or no acceptance, no dispatch. At most 4 agents read in parallel; at most 4 write in parallel, and only on disjoint files.
4. Gradle runs go through `gradle-serial <checkout> <tasks>` (on the PATH), so parallel runs in one checkout queue instead of breaking each other.
5. Done means tested, every acceptance passing on the final tree, the review's findings resolved, and the affected docs true again.
6. Reviews: `/deep-review`. Checkpoints: `/reflect` when comando's checkpoint is due (60% of the context window) and at the end of a session; each one leaves the memory's recall sharper and the KB truer than it found them, and reports what it did to the memory (`reflect-report`). In a session the user is not watching (a prompt sent from another session, `claude -p`), `/reflect --no-ask`: it queues the changes that need the user instead of asking.

## Compaction

A compaction summary keeps the task in progress, the decisions taken and their reasons, what is done and what is pending, open questions, the commands that worked, and the user's corrections. It ends with a `Modified files` list: every file written or edited since the session started, one absolute path per line, carried over from any earlier summary and extended, never reset.

## Output and git

- Result first, then any decision the user must make. No recap.
- Label uncertainty: verified, inferred, unchecked.
- No commit and no push unless the user asks for one.
