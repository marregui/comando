# 27. Panels reload files changed outside

| Date | Status | Decided by |
|---|---|---|
| 2026-10-14 | accepted; replaces the rule for edits made outside comando in [record 26](0026-a-file-per-panel.md) | Miguel (reload), Claude (how) |

## 1. Context

Record 26 kept an outside edit aside at comando's next write and never reloaded a file while comando ran. Panel files are specs that other editors and Claude's Edit tool also change; Miguel wants comando to show those changes.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| how a change is seen | the store checks each open panel's file once a second, on its writer thread, by modification time and size | on macOS the JDK's `WatchService` is itself a poller (`PollingWatchService`); on the writer thread a check never races a write |
| a change and no unsaved edits | the panel shows the file's text: the caret stays, within the new text; the reload is one undoable edit; the status bar says "Reloaded <name>" | the file is the spec; ⌘Z is the way back, and saves the text it brings back |
| a change and unsaved edits | the panel keeps its text; its save moves the outside version aside, as record 26 did | text typed in comando is never replaced |
| where the choice is made | on the EDT, which is the only writer of editor text, so no keystroke lands between the check and the reload | |
| a file deleted outside | a notice, once; the panel keeps its text and writes the file again on its next save | |
| a file half written by another editor | a read whose stamp changed while reading is dropped; the next check reads the whole file | |

## 3. Consequences

A change shows within about a second. Only open panels' files are checked; a new file in the folder opens through Open spec…. A change that keeps both the modification time and the size of comando's last write is not seen.
