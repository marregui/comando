# 68. Git changes and a side-by-side diff

| Date | Status | Decided by |
|---|---|---|
| 2026-10-19 | proposed; the double click on a repository's row, the view's title and its groups replaced by [record 71](0071-repositories-in-the-files-tab.md) | Miguel (the feature as IntelliJ has it: a project view, a changes view on the right, a floating side-by-side diff; changes per repository, whole repository; against `HEAD`; one diff window, reused; a repository's row marked with a git icon and a flag while it has changes, its double click opening the Changes view or saying there are none; the comparison in the editors' theme, with the panels' theme button, global), Claude (how: the git command line, the polling, the Files tab's ignored entries and bound, the view, the window, ⌘K) |

## 1. Context

Exploring a codebase from comando's Files tab gave no word on its commit status, and nothing compared a changed file
with its last commit, so reviewing work before a commit meant leaving for an IDE. IntelliJ's model is the one wanted:
a project view whose names carry git's colours, a list of what changed beside the editor, and a click that opens a
side-by-side comparison in a window of its own.

The Files tab read at most 5 000 entries per folder. With its standing exclusions (`build`, `node_modules`, `target`,
`__pycache__`, `out`, hidden entries) a repository such as `acme-edge2` still shows 9 851 entries, `acme-edge`
11 925, so a codebase was cut off. `git status --porcelain=v2 --ignored=matching --untracked-files=all` runs in 110 ms on
`acme-edge2` and 12 ms on `~/.claude` (measured 2026-10-19), and names both the changes and the ignored folders.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| git | the `git` command line, read only: `status --porcelain=v2 -z --branch --ignored=matching --untracked-files=all` and `cat-file blob HEAD:<path>`; `--no-optional-locks`, no prompt, 10 s and 32 MB per run, killed at quit | no library; the user's own git and configuration; never holds up the user's git commands |
| which repositories | the one holding a Files tab folder, else those directly inside it (at most 20); one entry per repository, however many folders share it | Miguel: per repository, whole repository |
| what counts | the working tree and the index together against `HEAD`; untracked files in a group of their own; at most 2 000 changes per repository, the rest counted | Miguel: against `HEAD`, as IntelliJ's commit view |
| when read | with the Files tab's poll, every 5 s: the repositories of its open folders, and of every folder while the Changes view is open; the other folders' every 30 s; at once when the folders change or either opens; sent only when something changed | a closed folder's row still says whether its repository has changes; a closed folder costs one `git status` per 30 s |
| a repository's row | a folder row or subfolder that is a repository's top folder has the git icon, and a flag after its name while the repository has changes; a double click opens the Changes view (bringing it forward when open) and neither opens nor closes the row; with nothing to see, a balloon beside the row says so | the repository's state at a glance, and one gesture to its changes |
| the Files tab | in a repository, git's ignored entries are left out; a folder git ignores as a whole shows all it holds; a changed file's name takes git's colour (changed blue, added green, untracked red), and so does a folder holding a change; the bound is 20 000 entries | the tree of a codebase is whole, and says what changed |
| the Changes view | a view in the side pane (⌘K opens and closes it; a focused terminal keeps ⌘K, its Clear Buffer, on macOS): a row per repository with its branch and count, then Changes and Not tracked, each by folder, a chain of single folders in one row; new rows come open, the user's closes stay | the commit view's list, read only: comando never stages, commits or reverts |
| the comparison | a double click or Enter on a file opens one window floating over comando's, reused for every file; moving the selection moves an open window along without taking the keyboard; the window reads its file again every 3 s while it shows, as git now sees it (staged, committed, removed), keeping the scroll, and a read again of a file it no longer shows is dropped; F7 and ⇧F7 go from difference to difference; Esc closes | Miguel: one window, reused |
| bounds of the diff | worked out off the event thread; the line match stops after 2 s and the texts are then compared as a whole, saying so; a text with a line over 10 000 characters is not compared | the match's cost grows with lines × differences (a 20 000-line rewrite took 11.7 s), and the editors lay out a long line in time that grows with its square (a 500 kB line took 2 s) |
| what is compared | `HEAD`'s blob and the tree's file; with `core.autocrlf` true or input, the tree's CRLF as LF, as git commits it; a symbolic link as its target; a Git LFS pointer, a submodule or a `HEAD` version git cannot read is not compared, and the window says why | the comparison shows what `git diff` would |
| the theme | the comparison's editors take the editors' theme, light or dark; the window has the panels' theme button, and it switches every editor, the comparison's included, and the choice is kept | one theme for every text the user reads |
| the diff | two read-only editors with the file's syntax colours and its own line numbers, no padding; lost lines red, gained lines green, the words that changed inside a changed stretch marked (not when more than half of a side's words changed); a band between the sides joins each difference; scrolling one side scrolls the other to the matching line | IntelliJ's fidelity; copied text and line numbers are the file's |
| not compared | a binary file, a version over 4 MB: the window says why | |

## 3. Consequences

`ToolPort` gains `watchRepos` and `readChange`, `UiPort` `showRepos` and `showChange`; the repositories travel as
intents and effects and are kept on the EDT, as folder trees are, not in `AppState`. Each change read carries why it
was asked (`ChangeAsk`: opened, followed, read again). Change reads run one at a time in the order asked, so the window
ends on the file the selection ends on.

A repository whose untracked files are not ignored (an untracked dependency folder) lists them all at each read, up to
the run's bounds. Unstaged renames show as a deletion and an untracked file, as `git status` reports them.
