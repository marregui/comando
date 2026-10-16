# Knowledge base

> **Last Updated:** <date> · **Status:** Current

What is known about the products and code you work on, written so that any reader (you, a colleague, a coding agent)
can load one document cold. [ALPHA.md](ALPHA.md) is the index: every document is listed there with its title and a
one-line summary. Written by the comando kit when it was installed; the rules below are yours to change.

## Layout

Top-level folders are **subjects** (a product, a library, a language, a tool), lowercase. Lifecycle is carried by the
Status header, never by location: files do not move because they aged.

```
KB/
├── README.md   these rules
├── ALPHA.md    the index
└── <subject>/  one folder per subject
```

## Conventions

- **Naming:** SCREAMING_SNAKE_CASE file names; numbered `NN_` prefixes for a set read in order.
- **Header:** every document has a blockquote directly under its title:
  `> **Last Updated:** <date> · **Status:** Current | Canonical | Needs review — <reason> | Historical record | Superseded`
- **Findable by title:** a document is found by its title and its row in `ALPHA.md`, nothing else.
- **Self-contained:** every document reads cold. It cites code by repository, path and symbol (class, method,
  constant), never by line number: line numbers change with every merge.
- **True to the code:** living documents must match the published code. `/reflect` corrects a document when a session
  proved it wrong against that code.
- **One owner per fact:** a canonical document is the single source for its topic; others link to it.
- **Historical records are frozen:** corrections are added as `[correction <date>: …]`, never silent rewrites.
- **Not here:** a task's report or findings not yet checked against the code go to the scratchpad (`SCRATCHPAD/`
  beside this folder), and `/reflect` moves what is proved true into the KB.

## Contributing

1. Pick the subject folder; a new subject gets a new folder and an `ALPHA.md` section in the same change.
2. Name the file and give it the header with a truthful Status.
3. Add its row to `ALPHA.md` in the same change: a document missing from the index is invisible.
