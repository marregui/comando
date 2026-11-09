# 145. Agents read held results a page at a time

| Date | Status | Decided by |
|---|---|---|
| 2026-11-09 | accepted; built | Miguel and Claude |

## 1. Context

A panel's SQL run keeps up to 5,000,000 rows by column in the result registry, and the Results tab shows them. An agent
saw none of them: `db_run_sql` runs the statement again and returns at most 10,000 rows as text, and ⌘⇧I pastes the
selection or the first 50 rows into a prompt. On live data a second run is a different result, so the user and the agent
did not look at the same rows. Miguel asked on 2026-11-09 for agents to read the results comando holds a page at a time,
and to narrow them so a large result makes sense without reading it whole.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what an agent reads | the result each panel holds: the user's run, or one an agent ran in a panel with `panel_run` | one place for every result; the agent reads the rows the user sees |
| the tools | `result_list` (ids, panels, SQL, state, rows, typed columns), `result_read` (a page from an offset, chosen columns), `result_stats` (per column: NULLs, smallest, largest, distinct values) | shape first, then a page; aggregates stay with SQL |
| the id | `r` and the run's request id; `ui_get_state` names it on each panel's last run | an agent goes from the window's state to its result |
| the form | the COPY text of `db_run_sql`, at most 64 kB per answer, the closing line with the rows shown, the total and the next offset | one text form for rows across the tools |
| a run still fetching | read from the rows that have arrived, below the count read once per call, and said so | the registry's rows below its count stay valid while it appends |
| how long a result lives | as the panel's: until the panel runs again or closes | no second store and no memory budget of its own |
| stats limits | distinct values exact below 10,000 per column; a scan stops after 20 s and answers with the rows it reached | a bound on memory per column and on the agent's wait |
| approval | none: the three tools read | they run no SQL |

## 3. Consequences

`McpTools` gains `results(ResultRegistry)`, which `App` calls, and the three tools; `Actions` lists them as read
actions. `ResultText.page` and `ResultStats` in `core` do the slicing and the summary. Tested in `McpServerTest`,
`ResultTextTest` and `ResultStatsTest`.

A result kept with ⌘⇧R is a copy inside a view, not in the registry, and is not listed.

## 4. Alternatives

`db_run_sql` with a `keep` flag, holding an agent's result in a store of its own under a memory budget, was planned and
set aside: `panel_run` already puts an agent's result in the registry, where the user sees it too. Paging with `OFFSET`
in SQL runs the statement again for every page and reads a different result on live data.
