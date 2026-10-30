# 124. The sidebar shows the instances: an Instances tab, a role and a kit in the presence

| Date | Status | Decided by |
|---|---|---|
| 2026-10-30 | accepted; built | Miguel and Claude |

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
