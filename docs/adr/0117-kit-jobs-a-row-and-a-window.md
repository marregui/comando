# 117. The kit's scheduled jobs: a row in the Kit group and a window; the manifest is their record

| Date | Status | Decided by |
|---|---|---|
| 2026-10-28 | accepted | Miguel and Claude |

## 1. Context

A kit declares scheduled jobs in its `comando-kit.json` (`jobs`), and its `install/kit.py` writes them to the platform's
scheduler ([record 80](0080-the-kit-is-a-claude-code-setup-comando-runs-its-sessions-on.md)): launchd, systemd user
timers or Task Scheduler. comando had no way to see them, change when or how they run, pause, add or remove one; on
Miguel's machine two of five jobs had been installed by hand and differed from the manifest.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the record | the manifest's `jobs` list is the only record of the jobs; `kit.py` alone writes the scheduler's files and calls `launchctl`, `systemctl` or `schtasks`; comando never does | one writer per platform, and the jobs follow the kit on Linux and Windows |
| where | one **Jobs** row in the Files tab's Kit group, after the kit's folders and under their header; absent without a kit | Miguel's choice: in the Kit group |
| the row | its detail counts the jobs and names what runs now or, after the paused count, when the next one runs (`5 jobs · 1 paused · next Thu 07:15`); the dot marks a change the scheduler has not taken; a failed last run, or jobs that cannot be read, red its name; its tooltip, one line, says the most pressing first: cannot be read, running, failed, pending, next | the state at a glance, in the tab's marks ([record 98](0098-the-sidebars-look.md)) |
| editing | a double click, Enter, the row's menu (Configure kit jobs) and the Files tab's own menu read the jobs again and open the **Kit jobs** window on them; a list that cannot be read opens no window and says why, since saving an empty list would remove every job: the jobs on the left, +, −, the selected job's fields on the right (name, description, days of the week, time, program inside the kit, arguments, environment, log, enabled), checked as typed; under them, read only, how the scheduler holds the job, its last run and its next | the folders window's shape ([records 69](0069-a-window-edits-the-files-tab-folders.md), [73](0073-the-claude-folders-window-explains-and-creates-the-index.md)); the tab itself still writes nothing ([record 55](0055-the-files-tab-only-shows-folders-json-configures-it.md)) |
| saving | Save hands the whole list to `kit.py jobs set`, which checks every job (refusing writes nothing), rewrites only the manifest's `jobs` block in its own format, then brings the scheduler to it; the window waits, closes when the list was taken, stays open with `kit.py`'s words when it was refused | one write, as the folders window's Save; the rules live in one place |
| running | a job's row in the window runs it now (`kit.py job run`: through the scheduler, never as comando's child, whose time limit would end it) and opens its log | a run outlives comando's bounded calls |
| a running job | `kit.py` never reloads or unloads a job while it runs: it skips it and says so; the row then shows the change as pending (a paused job still installed or loaded counts) and its menu's Apply pending changes, enabled while nothing runs, reads the jobs again and hands them over | a reload would end the run |
| reading | at start, after every change, and every minute while the Files tab shows | a run starts or ends, the next one moves on |
| the plumbing | `Setup` gains `jobs`, `saveJobs`, `runJob`, called off the EDT by `JobActions`, as `KitActions` calls the kit's status and the brain's backup; `KitJobsCli` in `claude` runs `kit.py` through `Processes` and reads its JSON | the kit's other calls go this way; the jobs are no part of the session state |

Miguel decided the manifest as the one record, `kit.py` as its one writer, the Kit group as the place, kit before
comando, and weekday sets beside daily and weekly; Fable's revision of the plan made `kit.py` the job's runner (the
scheduler's file holds only the schedule and `kit.py run <job>`, which reads the command, environment and log from the
manifest when it starts, keeps a pid file as the running guard and a line per run), the row and the window, and Save as
the one write. Claude decided how comando reaches it.

## 3. Consequences

A change of command, arguments, environment or log needs no reload; a change of name or schedule, a pause or a removal
does. If `comando-kit.json` is open with unsaved edits in a panel when the window saves, the panel's text wins and the
saved one is set aside with a notice; the row then
shows the jobs as pending until the window saves again or Apply pending changes runs. `kit.py`'s rules are checked a
second time as typed (`KitJob.problems`); whether the program is an executable file is `kit.py`'s alone to say. Tests:
`KitJobTest`, `KitJobsCliTest`, `FilesTabJobsTest`, `JobsEditorTest`, `JobActionsTest`; `kit.py`'s own in the kit's `install/test_kit.py`.
Not built: icons by state in the window's list (its rows show running, and a failed or invalid job in red), and a test of
the modal window itself. Known: a folder the user names `Jobs` after the Jobs row closes when the row's detail changes, as
the tree keeps rows open by name.
