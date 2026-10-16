# Scratchpad — provisional artefacts

> **Last Updated:** <date> · **Status:** Current

Reports, findings, agent specs and agent results that a task produced and that are worth keeping, but that have not yet
been checked against the code or folded into the knowledge base. Everything here is **provisional**: read it as
evidence, not as truth. Written by the comando kit when it was installed; the rules below are yours to change.

## 1. Layout

```
SCRATCHPAD/
├── README.md        these rules
└── 2026-10-16/      the tasks started that day
    ├── ALPHA.md     the tasks, and a summary of each document to read
    ├── READ-01-…    documents the user should read, in reading order
    └── work/        everything else: specs, raw agent reports, briefs
```

- **One folder per day, named `YYYY-MM-DD`,** for the day a task started. A task stays in its start-day folder for as
  long as it lives.
- **Only what the user should read sits at the top of a day folder**, named `READ-NN-<TASK>-<WHAT>-<MON>-<DD>.md`,
  `NN` being the reading order. Everything else goes in the day's `work/`.
- `file:line` anchors are allowed here; they are not allowed in the knowledge base.

## 2. ALPHA.md

Each day folder has an `ALPHA.md`, and every artefact in the folder appears in it. It opens with **To read**: each
`READ-NN-…` document in order, with two or three lines on its main ideas. Then one section per task, `## N. <title>`:

| Line | Holds |
|------|-------|
| **Repo** | repository, branch or base commit |
| **State** | done, waiting on whom, or in progress, with its last verified fact |
| **Next** | the next concrete step |
| **Resume from** | what to read to pick it up |
| artefact table | each document and what it holds; `work/` in one line |

Task numbers are stable: a task is never renumbered. "Resume task 3 from yesterday's scratchpad" means: read entry 3,
the artefacts it lists, then continue from **Next**, re-verifying any state older than a few days.

## 3. Consolidation

`/reflect` consolidates the scratchpad after memory: it folds what a session proved true into the knowledge base,
deletes the artefacts that are folded or obsolete, rewrites each task entry to carry only what a later session needs,
and deletes a day folder whose `ALPHA.md` has no task left. A task that still needs the user stays, and its State says so.
