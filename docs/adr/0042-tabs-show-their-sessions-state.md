# 42. Tabs show their session's state, and comando has its own icon

| Date | Status | Decided by |
|---|---|---|
| 2026-10-15 | accepted; its statuses amended by [record 43](0043-a-session-waiting-on-background-work-shows-it.md) (waiting) | Miguel and Claude |

## 1. Context

With many panels open, each bound to its own session, the user moves between tabs to see whether a session has
finished, is waiting for an answer, or failed. The tab bar said nothing of this: every tab had the file icon, and
only a query that finished while another tab was in front put a "● " before its title. Finding out meant clicking the
tab and reading its Session tab.

The window and the Dock showed the Java runtime's icon.

## 2. Decision

Each tab shows one status, the first that applies; a change to a status that needs the user asks the desktop for
attention.

| Status | When | Icon | Title | Clears when |
|---|---|---|---|---|
| needs you | the panel's session has a request waiting: an approval, a question, a plan, a statement, a prompt to another session | the question mark | `● ` | the request is answered; looking at the tab does not clear it |
| error | the last turn failed, or the session died, while another tab was in front | the red error mark | `● ` | the tab is looked at, or a new turn starts |
| done | a turn or a query finished while another tab was in front | the green check | `● ` | the tab is looked at, or a new turn starts |
| working | a turn runs, or a query | the spinner | plain | the work ends |
| idle | a session is bound, nothing goes on | the Claude mark | plain | — |
| none | no session | the file icon | plain | — |

- The shapes differ, not only the colours, so the states read without colour.
- The tooltip carries the status: the number of requests, the failure's message, "finished", or what the session
  does and for how long ("Bash 12 s", refreshed by the shell's one-second tick).
- The spinner is the bar wheel of the original SQL client's `InfiniteSpinner`, as a 16 px icon: eight bars shaded
  along a colour wheel that turns one bar every 80 ms, the original's rate. One wheel serves every spinning tab, so
  they turn in step. The timer runs only while a tab spins, and each step repaints the spinning tabs' bounds only,
  never the editors below them. The cost is a few small repaints a second while a session works.
- The user's interrupt is not a failure: an interrupted turn leaves the tab done, not in error.
- Needs you and error ask the desktop for attention once per change while the window is not active: on macOS the
  Dock icon bounces until comando is brought to the front. Done does not; it has the Notification Center notice.
- The window and the Dock show comando's own icon: a command prompt on a dark glass tile with a rim light and a
  shadow, the chevron in phosphor green with its glow, the block cursor in Claude's colour. The name comando came
  from three ideas (the one who commands, going commando, and the 1985 arcade game played at home on an 8-bit
  machine); the icon takes the first, the glow the third. A first, pixel-drawn version was replaced the same day. The
  disk image bundles the same picture as `comando.icns`.

Miguel decided the states and their precedence, the spinner, the Dock bounce and an icon of comando's own; Claude
decided how.

## 3. Consequences

`SessionState` gains `lastTurnError`, set by a turn that ends in error (other than the user's interrupt) and cleared
by the next turn, so a failure is observable after the fact. `TabStatus` (ui, panels) holds the rule and is
unit-tested on its own; `Workspace` keeps, per panel, whether work was going on at the last render, and marks a panel
whose work ended while another tab was in front. `SpinnerIcon` joins the kit's icon package. `Notifier.attention` asks
the desktop; `AppIcon` installs the icon on the window and the Dock; `:app:dmg` passes it to jpackage.
