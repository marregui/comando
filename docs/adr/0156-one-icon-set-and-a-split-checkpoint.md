# 156. One icon set and a split checkpoint

| Date | Status | Decided by |
|---|---|---|
| 2026-11-14 | accepted; built | Miguel and Claude |

## 1. Context

[Record 129](0129-a-checkpoint-reflects-then-compacts.md) gave the session bar one Checkpoint button that sent the
session's checkpoint command and, once that turn had finished, `/compact`. From the bar, reflecting and compacting came
together; compacting alone took the palette, the session's menu or a typed `/compact`, and clearing a typed `/clear`. The button told the user nothing until a turn ended at the threshold, when it turned purple: the session was
due, and at that same turn's end comando ran the checkpoint by itself.

The Sessions tab selected one row at a time. Starting, stopping or setting the model of several sessions took one menu
per session.

[Record 155](0155-icon-sets-switch-like-themes.md) shipped Classic and eight more icon sets, chosen as a theme is, so the
best set would be picked by use; its last consequence was that the others could go once one was chosen. Miguel,
2026-11-14: an icon carries a learned behaviour, and changing it is a disruption. comando builds 20 right-click menus
in 15 files, and made 27 menu items as plain `JMenuItem`s, outside `IconMenus.item`, which gives an item its icon.

This record replaces record 155. It amends record 129's button; the automatic checkpoint stands as written there.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the session bar's right end | three icon buttons after the context, in place of Checkpoint: **Reflect**, **Compact**, **Clear** | each step on its own, one click each |
| Reflect | sends the session's checkpoint command (`/reflect` by default) and nothing after it; usable while the session has a checkpoint command, is not stopping and no checkpoint runs; a stopped session starts to run it, as a prompt does. The session is no longer due and locks until the command's turn ends ([record 130](0130-a-session-locks-while-its-checkpoint-runs.md)) | reflecting is asked for alone; compacting is the next button's |
| Compact | sends `/compact`; usable while the session runs and no checkpoint runs | as the session's menu and the palette already did |
| Clear | sends `/clear` to the running session, which goes on in a new conversation; the old one stays importable; greyed while the session is stopped | a stopped session has Start a new conversation for the same end |
| Reflect's colour | a gradient from the grey of the `checkpoint` icon to the purple of `checkpoint-due` (`Theme.THINKING`), in proportion to the context over the threshold's share of the window, the threshold full purple; grey while the context is not known | the button says how near the threshold the session is before the crossing, not only after it |
| Reflect's blink | while the context is at two points below the threshold or more (38 % of the window at the default 40 %), until the checkpoint command is sent, by any route; it also blinks while the session is due, a compaction since included; once sent it blinks again only after a turn ended below that band and the context came back into it, or the session is due again | the user gets the warning in time to reflect first; once reflected there is nothing to warn of |
| the automatic checkpoint | unchanged: at a turn's end past the threshold, `/reflect --auto`, then `/compact` (record 129, [record 131](0131-an-automatic-checkpoint-asks-nothing.md)); the `session_checkpoint` tool still runs both | Miguel kept it |
| the siblings | the session's menu and the palette show Reflect, Compact the conversation and Clear the conversation in place of Checkpoint; one predicate per action (`SessionBar.canReflect`, `canCompact`, `canClear`) decides each, everywhere | the same action reads and behaves alike wherever it shows |
| the Sessions tab's selection | several rows: ⇧-click a range, ⌘-click one more | as every list on the desktop |
| a right-click on a selection of several | a group menu that acts on every selected session: Open in a panel (a new panel for each session with no panel open), Start, Stop, Start a new conversation (stopped sessions with a conversation to leave), Model ▸, Effort ▸. Each item skips a session it does not apply to, and is off when it applies to none | one gesture for several sessions; a session already in the state asked is left as it is |
| a right-click on one row | the session's own menu, as before | one session keeps every action it had |
| menu icons | every item of every right-click menu carries an icon, drawn as the others are where none fitted | an item is found by its icon as a button is |
| icon sets | one: Classic, in `icons/`, its gold recoloured to the tint chosen; the other sets, the row under the theme chooser's cards and the `Icons: <set>` commands are gone; a choice kept in the settings (`icons.id`) is ignored | an icon carries a learned behaviour; changing it is a disruption |

Miguel decided the three buttons, the gradient and the blink, the automatic checkpoint kept, the multiple selection and
its items with their rule for a session an item does not apply to, an icon on every menu item and one icon set, and
gave the reason for the last. Claude decided that a session whose checkpoint runs is skipped by every group item but
Stop, and that a stored choice of set is ignored rather than deleted.

## 3. Consequences

- `SessionBar` holds the three buttons and the predicates, intents and tooltips the menus and the palette share;
  `SessionSpec.CLEAR_COMMAND` is `/clear`. `Intent.Checkpoint` stays for the automatic checkpoint and the MCP tool.
- `SessionGroupMenu` builds the group menu; `SessionsSidebar` keeps the whole selection across a repaint.
- `IconSet`, `icons/sets/` and their tests are removed; `Icons` paints from `icons/` alone.
- A new icon is drawn once, in Classic's style; there is no set to keep it in step with.

