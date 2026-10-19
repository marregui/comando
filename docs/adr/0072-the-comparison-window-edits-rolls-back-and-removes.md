# 72. The comparison window edits, rolls back and removes

| Date | Status | Decided by |
|---|---|---|
| 2026-10-19 | proposed; extends [record 68](0068-git-changes-and-a-side-by-side-diff.md)'s read-only window | Miguel (Markdown rendered on both sides, its changes shaded as in the texts, with the code the panels render with; the working tree's side edited, saved on closing the window or comando and every second, no buttons for saving, undo, redo, roll back or removing, a cue for edits waiting and for edits saved; undo and redo of every edit made there; for any file; roll back to the committed state and remove; several files at once, from the view's items), Claude (how: the body as `ChangeEditor`, the writes, roll back as an undoable edit, what goes through git, the Trash first, the questions, the Edit menu's target) |

## 1. Context

Record 68 opened a changed file beside its `HEAD` version, read only. Fixing what the comparison shows meant opening
the file in a panel, and taking a change back meant a terminal. Markdown, which most of the Files tab holds, was
compared as text only, while its panels show it rendered.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the body | `ChangeEditor` (widgets `changes`) holds the comparison, its toolbar (differences, Markdown, theme), the working tree's menu and its logic; `DiffWindow` holds the window, its keys and the read every 3 s | testable without a window; one place for the logic |
| editing | the working tree's side is an editor for any text compared; the differences are worked out again 250 ms after typing pauses (`SplitDiff.remark`, which marks without setting the text) | Miguel: any file |
| saving | every second while edits wait, ⌘S, and on leaving the file: another file, the window closing, comando quitting (up to 2 s); written whole through `PanelTexts.write`, on the store's writer thread; a panel on the same file takes it as an outside change; a write that fails is tried again the next second | Miguel: saved on closing the window or comando, or every second; one writer per file |
| the cue | beside the working tree's title: "● edited" (amber) while edits wait, "✓ saved" (green) once written, "✗ not saved" (red) after a failed write, its notice saying why; macOS's modified dot on the window's close button while edits wait or failed | Miguel: a cue for edits made and for edits saved; only the working tree's side is edited |
| a read meanwhile | not shown while edits wait; a read of the text just saved keeps the caret, the place and the undo history | the edits win; undo survives a save |
| undo, redo | ⌘Z, ⌘⇧Z (⌘Y), no buttons, through every edit since the file was shown, roll back included; Edit ▸ Undo, Redo, Cut, Copy and Paste act on the window while it has the keyboard | Miguel: undo and redo all changes made through the window; macOS's menu bar takes those keys first |
| Markdown | a `.md` file offers both sides rendered by `RenderedMarkdown`, the renderer an editor panel's preview uses, scrolled together; the eye button and ⌘⇧M switch; the choice holds for the next Markdown file | Miguel: the same code underneath |
| what changed, rendered | the comparison of the texts marks the rendered sides too: commonmark's source spans tie each block and each run of text to its lines and columns, so a block whose lines changed is shaded red (`HEAD`) or green (working tree) and its changed words more strongly, in the theme's colours (`MarkdownHtml.Marks`) | Miguel: see what is new, edited and removed in the rendered view as in the texts |
| roll back in the window | Roll back to HEAD in the working tree's right-click menu: a changed or removed file whose text is edited takes `HEAD`'s text as one edit, is saved and unstaged (`git restore --staged`); undo takes it back | an undoable roll back |
| roll back through git | any other file (not in `HEAD`, renamed, copied, not compared), after a question: its version to the Trash, then `git restore --source=HEAD --staged --worktree` (a renamed file's old path back; an added one out of the index with `git rm --cached`) | nothing is lost: the Trash keeps what was replaced |
| remove | Move to the Trash… in the same menu: after a question, the file to the Trash; out of the index too when `HEAD` does not have it; not offered for a removed file | Miguel: remove the file |
| several files | the Changes view's menu rolls back or removes every file selected, a folder's, a group's or a repository's row standing for every file under it, after one question; the window drops its edits to a file so changed, and closes | Miguel: several files, from the items |
| without a Trash | nothing that would replace or remove a version happens; the notice says so | nothing deleted for good |

## 3. Consequences

`Intent.RollBack`, `RemoveFiles` and `Unstage` reach the tools adapter's `repo-write` thread, which runs one at a time
and reads the repositories again after each. `PanelTexts` gains `write`. The comparison window can no longer be
taken for read only: Esc and closing it write what was typed.
