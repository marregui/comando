# 27. Panels reload files changed outside

| Date | Status | Decided by |
|---|---|---|
| 2026-10-14 | accepted; its "file deleted outside" row replaced for documents by [record 34](0034-a-deleted-document-closes-its-panel.md), and for panels without a session by [record 39](0039-panels-are-kept-until-removed.md) and [record 88](0088-a-session-and-its-panel-share-a-name.md) | Miguel and Claude |

## 1. Context

[Record 26](0026-a-file-per-panel.md) kept an outside edit aside at comando's next write and never reloaded a file
while comando ran. Panel files are specs that other editors and Claude's Edit tool also change, and comando was to show
those changes. This record replaces that rule of record 26.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| how a change is seen | the store checks each open panel's file once a second, on its writer thread, by modification time and size | on macOS the JDK's `WatchService` is itself a poller (`PollingWatchService`); on the writer thread a check never races a write |
| a change and no unsaved edits | the panel shows the file's text: the caret stays, within the new text; the reload is one undoable edit; the status bar says "Reloaded <name>" | the file is the spec; ⌘Z is the way back, and saves the text it brings back |
| a change and unsaved edits | the panel keeps its text; its save moves the outside version aside, as record 26 did | text typed in comando is never replaced |
| where the choice is made | on the EDT, which is the only writer of editor text, so no keystroke lands between the check and the reload | |
| a file deleted outside | a notice, once; the panel keeps its text and writes the file again on its next save | |
| a file half written by another editor | a read whose stamp changed while reading is dropped; the next check reads the whole file | |

Miguel decided that panels reload; Claude decided how.

## 3. Consequences

A change shows within about a second. Only open panels' files are checked; a new file in the folder opens through
Open spec…. A change that keeps both the modification time and the size of comando's last write is not seen.

Two windows stay open, because closing them needs a lock every editor honours, and macOS editors share none. The
concurrency review of 2026-10-14 found both, and they were accepted as risks:

- an outside write landing in the instant between comando's check and its replacing move is overwritten. comando
  writes its temporary file before the check, so the window is that instant only;
- a file an editor has written in part and paused on reads as complete. This corrects itself at the next check, or,
  when the panel was typed in meanwhile, its complete version is kept aside.
