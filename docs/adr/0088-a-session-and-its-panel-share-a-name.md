# 88. A session and its own panel share a name, and are one

| Date | Status | Decided by |
|---|---|---|
| 2026-10-25 | proposed | Miguel ("always have sessions and panels share name and be bound by it"; documents may attach to a session; removing either removes both, after asking; a panel without a session offers a new session with its name; the session buttons show on every attached panel; a rename is refused while the session runs), Claude (the rules below, the edge cases) |

## 1. Context

Record 38 gave a session at most one panel, bound by name to a panel of any title, or none ("headless"). In use the
names had been aligned by hand: on 2026-10-25 all 7 sessions created in comando sat in a panel with exactly their name,
no session had two panels, and the two panels without a session were a document and a notes panel. The rules that pick
"which panel" (the checkpoint, the session row's card, Rewrite, `session_send`) and the text after a session's name in
the sidebar ("· scratch") existed only because the two names could differ.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| a session's own panel | every session has one panel of comando's own, titled with its name: saving a new session opens it (or makes a panel of comando's own with no session and that title its own); importing one conversation opens it; opening a session brings it forward, open or closed | one name for one thing |
| documents | any number of document panels (files from the Files tab, titled with their extension, record 77) may be attached to a session, from the session chip's menu ("Attach to ‹session›", "Detach from ‹session›"); a selection run there goes to that session, the session buttons act on it | a session writes documents; the documents are not its panel |
| binding | a panel of comando's own is never given another session: choosing a session in its chip's menu brings that session's own panel forward; its own panel is never detached | "Attach" only for documents |
| a panel without a session | its chip's menu offers "New session ‹title›…", the session dialog with the name filled in; saved, the panel becomes the session's own | the panel already holds the work |
| names | no panel takes a session's name: a new panel's title gets a number, a rename to it is refused, and a session named like a document is refused | the name is the bond |
| rename | renaming the tab renames the session and renaming the session renames its tab, refused while the session runs ("Stop session ‹name› before renaming it") | one name; Claude Code's `--name` is the running process's |
| removal | removing the session or its own panel removes both, after one question naming what goes ("removes its panel ‹name› with its text"); documents attached stay, detached; the conversation stays in Claude Code's files, importable | they are one |
| a file deleted outside | a session's own panel whose file is deleted outside comando is kept and its text written again, with a notice; any other panel is removed, as before (record 27) | removing it would remove and stop the session as a side effect of a file operation |
| which panel | the checkpoint, session commands and `session_send` use the session's own panel, then a document attached to it, then its own panel closed; the session row's card and Rewrite use the focused panel when it is the session's, else its own | the "first panel" rules become "its own panel" |
| the sidebar | the session row says where its own panel is only when it is not open ("· closed", "· no panel") and names the documents attached; the name turns the accent colour while one of its panels has the focus; "headless" is gone | the panel's title is the name already |
| the session dialog | "Assign to panel" reads "Open its panel" for sessions | it never assigns another panel |

## 3. Consequences

Amends record 38 (a session's panel) and record 13 (sessions bound to panels like connections). A session can still
be without an open panel: an imported conversation never opened ("no panel"), or its own panel closed. Two panels of
comando's own on one session, which no reducer path made but the test fixture `States.twoPanels` held, are gone from
the fixture. State already saved is not rewritten: a session bound to a panel of another title keeps it, its title
unchanged until renamed. Tested in `ReducerTest` (opening, adopting by name, binding, attaching documents, renaming
both ways, removal, commands from a closed panel), `ReducerNoticeAndLayoutTest` (import), `ResourceKindTest` and
`PanelsTabTest` (the removal question), `EditorPanelHeaderTest` (the chip's menu by kind), `SessionsSidebarTest` (the
row), `ResourceDialogTest`, and off screen in `CheckpointClearUiTest` and `SessionCardUiTest`.
