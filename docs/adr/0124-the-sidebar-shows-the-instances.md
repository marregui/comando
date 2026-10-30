# 124. The sidebar shows the instances: an Instances tab, a role and a kit in the presence

| Date | Status | Decided by |
|---|---|---|
| 2026-10-30 | accepted; built; amended the same day (§4) | Miguel and Claude |

## 1. Context

Miguel, 2026-10-30: "we need another tab in the sidepanel, to reflect that other instances of comando are available,
with that at least I can know from comando". Until then the instances of [record 123](0123-instances-consult-each-other.md)
were visible to sessions only (`instances_list`) and to the user only as a `consult-<asker>` panel once a question had
arrived. He chose all three proposed tiers: what the presence already carries, two fields added to it, and asking and
refusing from the window; the tab last in the row, after Files, with an icon of the comando Edge's family.

## 2. Decision

| Question | Rule |
|---|---|
| where | a fourth sidebar tab, **Instances**, after Files, with its button on the rail; icon `instances`: the Edge's antenna (as on its start and stop buttons) beside three linked nodes |
| state | `AppState.instances()` (`InstancesState`): whether this instance is connected to the comando Edge, this instance as its presence says it, the others by name, the questions asked and answered since start (at most 50, each with direction, peer, session, question, status, times and reply text, clipped) and the instances `agents.json` refuses. `Peers` sends it as the event `InstancesChanged` after each change it sees: a presence, a question, a reply, a refusal, its link, and at its 2 s tick |
| header | "Connected as <name> · N other instances", or "Connecting to the comando Edge…", or "not running" with a **Start the Edge** button that does what the Edge's toggle does |
| instances | a tree: this instance first ("(this one)"), then the others; each row a busy dot (refused: red), its name, its role, and its comando version when it differs from this one's (in the attention colour); its tooltip names the id, version, kit, uptime and sessions; its sessions under it, each with a busy dot |
| questions | a list, newest first: `→` asked or `←` answered, the peer, the session (or "you"), the status, how long, the first line. Double click: a question asked shows the question and its reply in a side view; a question answered opens the session here that answered it |
| acting | an instance's menu: **Ask <name>…** (also a double click) types a question that goes as the user, from no session: the peer's prompt is headed `[from instance <name>]`, and a notice says when the reply came; **Refuse questions from <name>** writes the data root's `agents.json` `peers.deny`, keeping its other keys; **Copy the instance id**. This instance's own row only copies. Through `core`'s port `InstancesPort`, which `Peers` implements |
| presence (amends record 123) | adds `role`, from the data root's `instance.json` (`{"role": "comando specialist"}`; empty when unset), and `kit`, the kit's `git describe` as the window last read it, so a kit that is behind shows |
| agents | `ui_get_state` adds `instances`: connected, and each other instance's name, id, role, version, busy and whether it is refused. Refusing is the user's alone and is not an action; asking is `instance_ask`'s |

## 3. Consequences

- The user sees from comando which instances run, what each is for, whether one is busy, and what was asked between
  them, without opening a consult panel.
- A question the user asks from the tab leaves no session behind on the asking side; its reply lives in the tab until
  the instance closes.
- The questions list is held in memory: a restart starts it empty. The peers' presence is retained on the Edge, so the
  instances themselves reappear at once.

## 4. Amendment, 2026-10-30: what each instance is doing, from its presence alone

Miguel, evaluating the tab the same day: "the point of this tab is to know what other instances are up to, from any
instance … only from information that is available from edge, without having to tell anyone to do a task and report,
only by virtue of the protocol"; "double click should do nothing. single click should open the tooltip, same as for the
sessions"; the "Connected as …" header is not wanted; a closed instance must not vanish; which instance owns the Edge
must show; this instance's row must show what the others' show. Sessions of one instance talk to each other through
its MCP tools; instances talk only through the Edge, or any HiveMQ broker.

| Question | Rule (replaces §2 where they differ) |
|---|---|
| presence (amends record 123) | adds `edge`, true when the instance started the comando Edge, and per session: `status`, `activity` (what its turn does), `turnStarted`, `model`, `effort`, `context` (percent), `folder` (home as `~`), `branch`, `tasks` (background, by description), and `prompt` and `reply`, the first line of its last prompt and last reply, its own and not a subagent's, a consult's `[from instance …]` header skipped, at most 200 characters each. Still published at most every 2 s, and only when it changed |
| what leaves the machine | those two lines are the only words of a conversation a presence carries; on the comando Edge they stay on 127.0.0.1, on a broker elsewhere they leave the machine |
| header | none while connected; while not, why, and **Start the Edge** while the Edge is down |
| instances | this instance, then the others present, then those that left, greyed, "left HH:MM" (with the date when not today), with no dot and no sessions. With the link down every other instance shows as last seen. The data root's `instances-seen.json` (id, name, version, role, kit, started, last seen) keeps them across restarts |
| marks | the dot: green idle, Claude's orange while a session runs a turn, red when its questions are refused here; the Edge's antenna (`edge` icon) after it when the instance started the comando Edge; a session's dot is coloured as the Sessions tab colours its icon (orange running a turn, green idle, grey stopped, red failed), and its row adds what its turn is doing |
| click | as in the Sessions tab: one click on a row shows its card beside the rows, the sidebar's card (`TabCard`), which then follows the pointer onto other rows after a 150 ms rest and closes when the pointer leaves the rows for anywhere but the card; the window's renders keep it (its key, `other:…`, is the tab's, which refreshes it as the presences change). The card: an instance's state ("busy: 1 of 3 sessions running a turn"), role, version, kit, uptime, the Edge, id, and whether it is refused; a session's state ("busy: running Bash · 2 min"), model and effort, context, folder and branch, background tasks, its instance, and under "Last exchange" its last prompt's and reply's first lines. No tooltips. A double click does nothing at any level |
| menus | another instance present: **Ask <name>…**, **Refuse questions from <name>**, copy the id; one that left: **Forget <name>**, copy the id; this instance: **Set the role…** (writes `instance.json`'s `role`, its other keys kept, sent in the next presence), copy the id; a session's row: none |
| agents | `ui_get_state`'s `instances` adds `present` and `edge` per instance; `instances_list` adds, per busy session, what it is doing and its last prompt's first line |

