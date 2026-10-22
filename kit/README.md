# comando's Claude Code kit

A Claude Code plugin that sets Claude Code up to work the way comando is built to be used: what Claude learns is kept
in memory, a knowledge base and a scratchpad, kept current by `/reflect`; changes are reviewed by `/deep-review`;
agents are given specs with an executable acceptance check; Gradle runs in one checkout queue instead of colliding.

| Part | What it does |
|---|---|
| `hooks/method.md` | the method, read by Claude at every session start (new, resumed, cleared) and after each compaction: where knowledge is kept, how a task is worked, what a compaction summary keeps |
| `skills/reflect` | `/reflect`: writes the session into memory, corrects the knowledge base, consolidates the scratchpad |
| `skills/deep-review` | `/deep-review`: an adversarial review against the stated requirements, every finding proved |
| `skills/show` | show, don't tell: Claude renders what is better seen than read (a sheet of options, the real component, an animation, a diagram) and puts it in a comando view beside its reply; `show.py` draws sheets and GIFs |
| `templates/agent-spec.md` | the spec an agent is given: files, limits, executable acceptance |
| `bin/gradle-serial` | one Gradle run per checkout at a time; on Claude's PATH, and what comando runs Gradle through |
| `bin/index-check` | checks that every memory file, knowledge-base document and scratchpad artefact is in its index, and every entry of a Files-tab folder inside Claude Code's folder (repositories excepted) in the folder's index file and listing files; warns when the memory index, a memory file or a dated log passes its budget |
| `bin/reflect-report` | `begin` snapshots the memory before a `/reflect`; `end` prints what the run did to it: the index's size, lines added and removed per file, files that only grew, the budget warnings |
| `bin/compact-check` | the no-loss check of a memory file merged or compacted by `/reflect`: every date, backtick span, quote, ticket, number, link and heading of the original must survive in the result or its archive |
| `bin/cue-replay` | replays the last month's prompts against the memory index with a small headless judge and reports its recall and the cues to sharpen, to the day's scratchpad folder; run it monthly |
| `hooks/mermaid-check.py` | after Claude writes a Markdown file, renders its Mermaid diagrams with `mmdc` and reports a parse error |
| `skeleton/` | the memory index's header, written to the memory folder (`MEMORY.md`), and the rules of the knowledge base and the scratchpad, written to `KB/` and `SCRATCHPAD/` in Claude Code's folder (`$CLAUDE_CONFIG_DIR`, else `~/.claude`); each only when missing, by comando's install |

## Install

From comando: Actions ▸ Claude Code kit. comando keeps a copy of this folder in its own folder (`~/.comando/kit`), sets
Claude Code's `autoMemoryDirectory` to `~/.claude/MEMORY` (one memory for every project; the one key comando writes in
Claude Code's `settings.json`, never over a value you set), registers it with Claude Code as the marketplace `comando`, installs the plugin `comando@comando` for the user, writes
the skeleton files that are missing (never over an existing file) and adds the memory, the knowledge base and the
scratchpad to the Files tab. A `/reflect` or `/deep-review` of your own (a skill or a command in Claude Code's folder) keeps the kit out:
comando does not install it beside them.

Without comando, from a clone of the repository, set `"autoMemoryDirectory": "~/.claude/MEMORY"` in
`~/.claude/settings.json`, then:

```
claude plugin marketplace add <clone>/kit
claude plugin install comando@comando
```

Then copy the skeleton's files that are missing: `skeleton/KB/` and `skeleton/SCRATCHPAD/` into `~/.claude/`, and
`skeleton/MEMORY/MEMORY.md` into `~/.claude/MEMORY/`, with today's date for each `@DATE@`.
The method points at their rules.

Remove it with `claude plugin uninstall comando@comando` and `claude plugin marketplace remove comando`; take
`autoMemoryDirectory` out of `~/.claude/settings.json` to go back to a memory per project. The knowledge base, the
scratchpad and the memory stay.

Requirements: `python3` for the Mermaid check, `index-check`, `compact-check` and `cue-replay`; Pillow for `show`'s
pictures (`python3 -m pip install --user pillow`); the `claude` CLI for
`cue-replay`'s judge; `mmdc` to draw diagrams (`npm i -g @mermaid-js/mermaid-cli`).
