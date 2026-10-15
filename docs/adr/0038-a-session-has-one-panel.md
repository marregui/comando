# 38. A session has one panel

| Date | Status | Decided by |
|---|---|---|
| 2026-10-15 | accepted | Miguel (the rule, a new panel's connection, the start-up rule, no broadcast), Claude (the proposal, how) |

## 1. Context

Binding a session to a panel that had one replaced it: the previous session kept running with no panel. Miguel did
not want that. Several panels could also share a session: they showed one transcript, fed one conversation, and a
session's insert landed in whichever of them was focused. A new panel (⌘T, a file opened from the scratchpad) took the
focused panel's session, so it shared it.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| panels per session | at most one; a session with none is headless | one transcript, one conversation, one place for Claude's inserts |
| opening a session (double-click in the sidebar, Open in a panel, Panel ▸ Session ▸ Open a session…, the dialog's Assign to panel, the header chip of a panel without a session) | `OpenSession`: the panel that has it comes forward; else the target panel (the focused one, or the chip's) takes it when it has no session; else a new panel opens, titled after the session (`edge`, then `edge 2` when the title is taken), bound to it and to the target's connection, and stored like any panel | as a file opens in an editor; nothing is replaced as a side effect |
| changing a panel's session | the header chip of a panel with a session offers only **Detach**: the session keeps running headless, the text stays; then the panel can open another | replacing is a step the user takes |
| `BindSession` | binding a session takes it from any other panel; an empty name detaches | the rule holds for every path, the intent log's included |
| a new panel (⌘T, a file opened as a panel) | the focused panel's connection, no session | it would share the session |
| a reopened panel (⌘⇧T) | its session only when no other panel has taken it | |
| at the start | a session bound to several panels (from before this record) keeps its first, in panel order; the others keep their text, lose the binding, and a notice names them; `panels.json` is written again | Miguel's choice over leaving them |
| one prompt to several sessions | not built | Miguel: never |

## 3. Consequences

A document opened from the scratchpad has no session until one is opened in it. Claude's tools that write into "the
caller's panel" have one panel to choose. The tutorial's ⌘T step and the docs describing shared sessions changed.
