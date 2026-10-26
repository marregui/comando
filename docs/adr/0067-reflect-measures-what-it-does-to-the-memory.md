# 67. `/reflect` measures what it does to the memory

| Date | Status | Decided by |
|---|---|---|
| 2026-10-19 | accepted | Miguel (improve `/reflect` along every dimension found), Claude (the dimensions and how) |

## 1. Context

The kit's `/reflect` ([record 50](0050-comando-ships-a-claude-code-kit.md)) was a prose procedure of eleven steps, every
step judged by the model, with two executable parts: `wc` on the memory index and `index-check`. Its author's own
memory, kept by the same procedure for a month, showed what the prose did not enforce: the index doubled in eighteen
days after a trim; the session log and the agent ledger grew without rotation; feedback files reached 50–70 KB by
appending dated paragraphs where the rule said to rewrite; a day folder of the scratchpad was deleted with `rm -rf`
and took another session's document with it; the one measurement of the index's quality (a replay of past prompts
against the cues, 2026-10-01) existed as a number in a log, not as a tool; and the step that asks the user before
changing `feedback` and `user` files stalls a session nobody is watching.

## 2. Decision

| Dimension | Rule | Why |
|---|---|---|
| growth | budgets per file: the index 160 lines / 20 KB (as before), every memory file 32 KB, dated logs rolled by month into `archive/`; `index-check` warns on each | the index budget alone let the other files grow unseen |
| rewrite, not append | `reflect-report begin` snapshots the memory; `end` prints the index before → after, lines added and removed per file, and the files that only grew; a file that only grew is reopened before the report | "rewrite the entry" is checked by a diff, not by intent |
| recall | `cue-replay`: the last month's prompts and the memory files opened for them, from the transcripts, replayed against the index with a small headless judge (`claude -p`, no tools, no settings, no persistence); a report in the day's scratchpad folder with the recall and the files most often opened without their cue matching, with example prompts; a history line per run | the index is tuned on recall misses; a monthly number shows whether the tuning works |
| cost | the KB and scratchpad steps run in the session itself, after memory is written (amended 2026-10-25; until then in a `fork` agent when the harness offered one) | a fork returned only its summary, so its KB and scratchpad changes went unchecked by the session, and the skill behaved differently where no fork was offered; its saving, file reads kept out of a context about to be compacted, matters less since the Checkpoint button clears the conversation after the run (record 86) |
| safety | deletes are per artefact, never per folder; a day folder goes by `rmdir` once only an empty `ALPHA.md` is left; written in the skill and in the scratchpad rules | parallel sessions write the same day folder |
| KB lane | a KB set with a maintenance file of its own is corrected by its procedure (statement, header date, source register, change log) | a correction without its change-log line is a silent rewrite |
| unattended | `/reflect --no-ask`, and any session the user is not watching: changes to `feedback` and `user` files are queued as a task in the day's `ALPHA.md`, asked at the next watched `/reflect` | a question nobody answers blocks or is skipped |
| shape | the skill is four phases (measure, memory, KB and scratchpad, indexes and report), each step naming its executable part | the executable parts are what a run can be checked against |

## 3. Consequences

`kit/bin/` gains `reflect-report` (bash), `cue-replay` and `compact-check` (Python 3; the latter the no-loss check a
file budget needs, so that a merge forced by the budget drops no date, identifier, number, quote or link); all are copied
verbatim into the author's own plugin, whose table tests (`tools/cue-replay/`, `tools/compact-check/`) check the copies
are byte-identical. `cue-replay`
needs the `claude` CLI on the PATH and spends tokens on the judge (a small model, one call per prompt, a cap per call);
the kit does not schedule it, the user does (the author runs it monthly from launchd). The kit's `index-check` treats a
file with twenty or more dated lines as a log; the author's names its logs. The method tells an unattended session to
use `--no-ask`. Plugin version 1.1.0.
