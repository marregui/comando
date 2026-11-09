# 144. The transcript sits above the prompt

| Date | Status | Decided by |
|---|---|---|
| 2026-11-08 | accepted; built; trial ended 2026-11-09, off by default | Miguel and Claude |

## 1. Context

A session's panel is a prompt editor; the conversation it drives is read in the lower panel's Session tab, under it.
The eye moves down to read the answer and up again to write the next prompt, while chat tools put the conversation
above the input. Miguel accepted a trial of that order on 2026-11-08 (task 77, proposal 6).

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| when the lower panel goes above | while a session's panel is the main tabs' front panel and the lower panel is shown; a file, an SQL editor or a document in front puts it back below | the order matters where a conversation is read; elsewhere the lower panel holds results under what made them |
| its height | the divider is kept as the panels' height with the lower panel below, whichever side it is on, so a flip keeps the lower panel's height; a flip before the window has a height waits for its first layout, and a lower panel hidden by a file comes back at the height last shown | a flip must not resize what the user set |
| the setting | Window ▸ "Transcript above the prompt" and "Transcript below the prompt"; `layout.transcriptAbove` in `settings.json`, off by default since 2026-11-09 (Miguel ended the trial: editors on top and output below is the Eclipse and IntelliJ paradigm he and developers of his experience work in) | a trial is turned off in one gesture, and the choice survives a restart |
| what moves | only the order of the split's two sides; the lower panel's tabs, its focus and its shown or hidden state are as before | the trial changes the order, nothing else |

## 3. Consequences

`WindowLayout` gains `transcriptAbove`; `LayoutKeeper` places the lower panel above or below as the front panel
changes, keeps the lower panel's height across a flip, and gives the keyboard back to the component that had it.
Tested by `TranscriptAboveUiTest` on `:app:uiTest`, beside
`LowerDividerUiTest` and `LayoutKeeperUiTest`. The agent tool `ui_set_layout` takes `transcript_above`.

## 4. Alternatives

The transcript inside the session's panel, above its editor, is the chat tools' form; it moves the Session tab out of
the lower panel and changes every gesture that tab serves. The flip of the existing split keeps every one of them.
