# <TASK-NAME> — agent spec (<MON-DD>)

<!-- Copy to SCRATCHPAD/YYYY-MM-DD/work/<TASK-NAME>-<MON-DD>.md in Claude Code's folder ($CLAUDE_CONFIG_DIR, else
     ~/.claude) and list it in that folder's ALPHA.md.
     Every section is required. A spec without an executable Acceptance section is not dispatched. Delete these
     comments. -->

## Kind and runner

- Kind: produce | test | review | investigate
- Runner: Claude subagent (model, effort) | another agent
- Checkout: `<absolute path>` on branch `<branch>` at `<sha>`
- Wave: serial | parallel with <other spec names> (readers ≤ 4, writers ≤ 4)

## Context

<!-- The agent does not see the memory, and loads no KB document unless told. Quote here what it needs, each item
     labelled verified (checked against code or source, say which) or inferred: the memory facts that bear on the task
     (conventions, corrections, decisions and their reasons, traps), the KB passages that describe the code it touches,
     the state of the work. A spec without this section is not dispatched. -->

- From memory: <fact> (verified | inferred)
- From the KB: <document title>: <passage> (verified against <ref> | inferred)

## Task

<One paragraph: the exact change or question, and the requirements it answers to, quoted.>

## Files

| File | Access | Note |
|------|--------|------|
| `<path>` | write | owned by this agent only |
| `<path>` | read | |

Shared files and cross-file seams, each with its one owner (in a parallel wave, the same table is in every spec):

| Shared file or seam | Owner | Rule for everyone else |
|---------------------|-------|------------------------|
| `<build file / dispatch table / exception raised here, caught there>` | `<spec name>` | `<do not edit; ask in the report>` |

## Must not

- Edit any file not listed as `write` above; create files only where listed.
- Commit, stage, stash, reset, rebase, check out or create branches.
- Run Gradle except as `gradle-serial <checkout> <args>`; run tests outside its own paths.
- <task-specific exclusions>

## Acceptance (executable)

<!-- Commands with expected results; every one is rerun before the result is accepted.
     produce/test: build, named tests with expected counts, a mutation each new test must kill.
     review/investigate: the output must match the findings schema below; every finding is verified. -->

| # | Command | Expected |
|---|---------|----------|
| 1 | `gradle-serial <checkout> :<project>:compileJava` | exit 0 |
| 2 | `gradle-serial <checkout> :<project>:test --tests '<Class>'` | exit 0; <N> tests, 0 failures |
| 3 | `<revert line X of <file>>`, rerun 2 | test `<name>` fails (mutant killed) |

Findings schema (review/investigate):

```
- head: the commit every finding was checked on (`git rev-parse HEAD`; `dirty` with uncommitted changes)
- context proof, before any finding: subsystem purpose, invariants, change intent, unknowns, confidence 0–100 each
- id, severity (P1–P3), file:line, claim, failure sequence (inputs → wrong outcome), evidence (command run or code
  quoted), kind: shipped defect (reachable on real input) | robustness wish
- verified-correct list; deliberately-not-counted list
```

## Output

- Report: `SCRATCHPAD/YYYY-MM-DD/work/<TASK-NAME>-REPORT-<MON-DD>.md` in Claude Code's folder
- Shape: files changed (one line each), acceptance results verbatim, what was not done, open questions.
