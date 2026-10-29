# 105. A session bar under the lower panel; the window's status bar holds the window's status

| Date | Status | Decided by |
|---|---|---|
| 2026-10-28 | accepted | Miguel and Claude |

## 1. Context

The panel's header held, on its left, the session's chip (name, branch, state, what it does), its plug, a new
conversation, the checkpoint and compact, then the connection's chip and plug; on its right Browser, Preview, Run,
Stop and Clear. The status bar mixed the window's state (the standing notice, the kit, Claude's and Codex's plan
usage, the work running in every session and query) with the focused panel's session (model, effort, context, what it
does) and connection. The session was shown twice, the connection twice, and the header was crowded on a narrow panel.
It amends [record 32](0032-session-rows-carry-their-controls.md) (the session first in the header and the status
bar), [record 43](0043-a-session-waiting-on-background-work-shows-it.md) (the header says what a waiting session waits
on) and [record 86](0086-a-checkpoint-clears-and-carries-its-summary.md) (the header's Fresh, Checkpoint and Compact).

Miguel asked for the session's status in a bar of its own, so that a window may later hold two sessions side by side,
each with its panels, lower panel and session bar; the partitions themselves are not decided.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| where the session bar sits | under the lower panel, across the column of the panels and the lower panel, above the window's status bar; the sidebar's column is not under it | where a partition's own bar would be |
| what it shows, left | the focused panel's session: its chip, the session's state icon (record 90: orange running, grey stopped, faint starting, red failed; no status dot) with its name and ⎇ branch, whose menu is the chip's menu of record 88 and whose tooltip says the state and the folder; the plug; the new conversation; then what it does, "working · 12 s · 2 queued" or what it waits on, counted on the shell's one-second tick | the same mark for a session's state everywhere |
| what it shows, right | the model and the effort, each a menu of the sidebar's choices that changes it now and saves it, named as the session's card names them; the context, "ctx 412k (41%)", or "ctx –" until a turn reports it; the checkpoint button right after it; the context and the checkpoint turn purple together once it is due, and the context's tooltip says at what share it falls due | Miguel: model and effort changeable mid-session; the checkpoint's link to the context visible |
| nothing moves | what the session does is the left row's last item, so its changing width moves no control; the new conversation shows from the session's first conversation on, greyed out while the session runs, never hidden again; the context always holds its place | Miguel: the buttons flew around as the status changed |
| compact | no button; the palette's "Compact the conversation", the session's menu in the sidebar and typing `/compact` stay | Miguel: a command does it |
| a panel without a session | the chip says "no session ▾" and its menu binds one; nothing else shows | as the header's chip did |
| the panel's header | the connection's chip and plug on the left, Browser, Preview, Run, Stop and Clear on the right | Miguel kept the connection in the header: a connection is the panel's binding, not the session's |
| the window's status bar | the standing notice on the left; the kit, Claude's and Codex's plan usage and the work running in every session and query on the right; no session or connection | the window's state only |

Miguel decided the bar, its place, that the connection stays in the header, that the bar adds the model, effort and
context, that model and effort change from it, that compact goes, and that nothing moves as the session works; Claude
chose the state icon for the dot, the checkpoint beside the context, and kept the counts of running work in the
window's bar, as they cover every session.

## 3. Consequences

`SessionBar` (in `ui.panels`) renders the focused panel's session from the state; the shell puts it under the split of
the panels and the lower panel and moves its elapsed time on the shell's tick. Its model and effort menus are the
sidebar's (`SessionsSidebar.modelItems`, `effortItems`). `EditorPanel` keeps the connection and the editing controls;
the session's helpers (`chipText`, `busyText`, `showsFresh`, `showsCheckpoint`, the checkpoint's icon and tooltip) moved
to `SessionBar`, and the sidebar's rows and cards call them there. `StatusBar` lost its session and connection labels
and `sessionText`.

Tested in `SessionBarTest`: the bar follows the focus, a panel without a session shows only the chip, no compact button,
the controls keep their places while the session works (mutation-checked with the activity back in the chip), model and
effort menus check the session's own and dispatch the change, the context turns purple when the checkpoint is due
(mutation-checked with the colour fixed), the checkpoint sits in the context's group, the chip's menu by the panel's
kind, a name like HTML shown as text. `EditorPanelHeaderTest`: the header has no session control and keeps the
connection. `CheckpointClearUiTest` clicks the session bar's Checkpoint.

## 4. Alternatives

| Option | Why rejected |
|---|---|
| the bar between the panels and the lower panel | not where a partition's own bar would be |
| the connection in the session bar too | Miguel: the connection is the panel's |
| the session bar across the whole window, the sidebar included | a partition's bar spans its own column only |
