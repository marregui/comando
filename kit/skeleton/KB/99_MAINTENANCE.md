# Knowledge base — maintenance

> **Last Updated:** @DATE@ · **Status:** Current

What is known about the products and code you work on, written so that any reader (you, a colleague, a coding agent)
can load one document cold. [00_START_HERE.md](00_START_HERE.md) is the root of the index: it lists the sets, and
each set's `00_START_HERE.md` lists its documents with a one-line summary each. Written by the comando kit when it was installed; the rules below are yours to change.

## 1. Rules

### Sets

Every folder that holds documents belongs to a **set**: a folder holding `00_START_HERE.md` and
`99_MAINTENANCE.md`. The root is one; every subject folder is one; a set may hold sets. A document belongs to the
nearest set at or above its folder. A folder holding `SOURCES.md` holds raw copies, of which only `SOURCES.md` is a
document.

- `00_START_HERE.md`: what the set covers, one line per document it owns and per set inside it, a link to its `99`.
- `99_MAINTENANCE.md`: four sections, by these titles and in this order, numbering free: **Rules**, **Source
  register**, **Drift check**, **Change log**. Drift check opens with the checks table:

  | Source | Kind | Where | Recorded | Cadence | Checker |
  |---|---|---|---|---|---|

  Kind is `code`, `document`, `web`, `toolchain`, `slack` or `none`; Cadence `weekly`, `monthly` or `never`; Checker
  `kb-drift`, `slack-sweep` or `none`. A set with no external source has one row `| — | none | — | — | never | none |`.
- `index-check` checks this structure; `/reflect` corrects a document through its set's `99` (the statement, the
  header date, the register's Recorded, a Change log line).

### Layout

Top-level folders are **subjects** (a product, a library, a language, a tool), lowercase. Lifecycle is carried by the
Status header, never by location: files do not move because they aged.

```
KB/
├── 00_START_HERE.md   the root set's entry
├── 99_MAINTENANCE.md  these rules
└── <subject>/         one set per subject: its own 00_START_HERE.md and 99_MAINTENANCE.md
```

### Conventions

- **Naming:** SCREAMING_SNAKE_CASE file names; numbered `NN_` prefixes for a set read in order.
- **Header:** every document has a blockquote directly under its title:
  `> **Last Updated:** <date> · **Status:** Current | Canonical | Needs review — <reason> | Historical record | Superseded`
- **Findable by title:** a document is found by its title and its line in its set's `00_START_HERE.md`, nothing else.
- **Self-contained:** every document reads cold. It cites code by repository, path and symbol (class, method,
  constant), never by line number: line numbers change with every merge.
- **True to the code:** living documents must match the published code. `/reflect` corrects a document when a session
  proved it wrong against that code.
- **One owner per fact:** a canonical document is the single source for its topic; others link to it.
- **Historical records are frozen:** corrections are added as `[correction <date>: …]`, never silent rewrites.
- **Not here:** a task's report or findings not yet checked against the code go to the scratchpad (`SCRATCHPAD/`
  beside this folder), and `/reflect` moves what is proved true into the KB.

### Contributing

1. Pick the subject folder; a new subject gets a new folder that is a set (§ Sets), with its line in the root's
   `00_START_HERE.md`, in the same change.
2. Name the file and give it the header with a truthful Status.
3. Add its line to its set's `00_START_HERE.md`, and its sources to that set's `99_MAINTENANCE.md`, in the same
   change: a document missing from its set's `00` is invisible.

## 2. Source register

The root keeps no source of its own: each set registers its sources in its own `99_MAINTENANCE.md`.

## 3. Drift check

| Source | Kind | Where | Recorded | Cadence | Checker |
|---|---|---|---|---|---|
| — | none | — | — | never | none |

## 4. Change log

- @DATE@ — Written by the comando kit when it was installed.
