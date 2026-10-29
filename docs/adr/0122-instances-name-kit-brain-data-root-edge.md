# 122. Several instances: a name each, a kit and brain each, a data root each, and an Edge to find one another

| Date | Status | Decided by |
|---|---|---|
| 2026-10-29 | accepted in part: §2.1, the layout and the kit's brain paths of §2.2 and the broker of §2.4 built, the presence and inbox of §2.4 built on one machine by [record 123](0123-instances-consult-each-other.md); proposed: the backups of §2.2–§2.3, the trust and bridging of §2.4, §2.5 | Miguel and Claude |

## 1. Context

Miguel, 2026-10-29: several comando instances on one machine, each with its own kit and brain laid out as his
`~/.claude` (`~/.claude_elf`, `~/.claude_appa`, `~/.claude_tink`…), each with a role; they consolidate, share what
they know and decide together, with little of his attention. Instances find each other through an Edge acting as their
broker, which puts the Edge to daily use. Later, other people's instances join the same way.

An agent-side trial (2026-10-28/29, a second instance on a cloned kit) found that two instances can run side by side
but share window preferences unless told otherwise, look identical in the Dock, and cannot find each other: the MCP
port is written only to the log.

## 2. Decision

### 2.1 A name (built)

| Question | Rule |
|---|---|
| where it comes from | `-Dcomando.name` or `COMANDO_NAME`; else the data root's folder: `~/.comando` is the main instance and has no name, `~/.comando_b` or `~/.comando-b` is `b`, any other folder is its own name (`core` `InstanceName`) |
| where it shows | the application's name, `comando · b`, in the Dock's hover and the menu bar (`apple.awt.application.name`, set in `Main` before AWT starts); the window's title, the name alone (`comando · b`); the Dock icon's badge, the name's first letter |
| preferences | a named instance keeps its window layout in its own data root's `settings.json` ([record 106](0106-settings-in-the-data-root.md)); the preferences node read once for it is its own (`dev/marregui/comando-b`, `InstanceName.prefsNode`); the main instance's node is unchanged; `-Dcomando.prefsNode` still overrides |

### 2.2 A kit, a brain and a data root each

| Question | Rule |
|---|---|
| the layout | instance `b`: kit `~/.claude_b` (a clone of the reference kit at its latest tag, laid out as `~/.claude`), brain `~/.claude_b/BRAIN` (its own repository), data root `~/.comando_b`; the main instance keeps `~/.claude`, `~/.claude/BRAIN`, `~/.comando`; the session `kit` that upgrades `~/.claude_b` runs on `~/.claude_b` and its memory, never on `~/.claude` (`RootLaunch`) |
| starting one | `COMANDO_HOME=~/.comando_b CLAUDE_CONFIG_DIR=~/.claude_b comando`; each is configurable on its own, the name follows the data root |
| the kit's tools | every path to the brain is the kit root's `BRAIN/` (`$CLAUDE_CONFIG_DIR`, else `~/.claude`); a setting that points outside the root (a clone's `autoMemoryDirectory` still naming the original) is not this kit's brain (kit `reflect-report`, `cue-replay`, `CLAUDE.md`, 2026-10-29) |
| login | each kit folder logs in once: `CLAUDE_CONFIG_DIR=<kit> claude auth login` opens the browser and completes with the browser's claude.ai session, no code to paste; comando is to offer it as "Log in the kit" ([record 121](0121-an-agent-has-every-action-the-user-has.md), increment 3, proposed) |
| backups (proposed) | as the main instance's, except that the repositories do not exist yet: the first backup asks where (owner, names, private), creates them with `gh repo create --private` and pushes; the brain and the kit are separate repositories |

### 2.3 What a data root holds, and what is worth backing up

| Entry | Holds | Back up |
|---|---|---|
| `sessions.json`, `connections.json`, `folders.json`, `panels.json`, `panels/*.md`, `agents.json`, `kit-folders`, `opened-files`, `settings.json` | the instance's desk: its sessions (with their conversation ids and creators), connections, folders, panels and their text, grants, window layout | yes: small text; losing it loses the desk, not the work. Except passwords: the main instance keeps them in the keychain, another data root in `connections.json` unless `-Dcomando.keychain=true` (build-and-run §4); a backup takes `connections.json` without them |
| `metrics/` | every turn's model, effort, tokens and outcome | yes: the calibration's history (record 110) |
| `audit/` | every tool call and approval decision | yes: the record of what agents did |
| `questdb/` | the embedded database's tables | the user's tables, by export, when they matter; not the files |
| `logs/`, `cache/`, `run/`, `help/`, `comando.lock`, `*.bak-*` | logs, redrawable images, live tokens, the tutorial copy, the lock, hand-made copies | no; `run/` holds tokens and must never leave the machine |

Proposed: the desk, `metrics/` and `audit/` go to the same repository as the brain's backup, under `instance/`, so one
restore brings an instance back.

### 2.4 Finding each other through an Edge (broker built; the rest proposed)

Miguel, 2026-10-29: the Edge starts and stops as the embedded QuestDB does, from a toggle at the top of the workspace
beside QuestDB's; only one Edge started by comando runs on the machine, so that every instance talks through it; the
user still starts and stops Edges of their own beside it without interference.

| Question | Decision or proposal |
|---|---|
| the broker (built) | the comando Edge (`EmbeddedEdge`): a start/stop toggle at the top of the workspace, right of QuestDB's (the corner of [record 114](0114-help-menu-and-corner-buttons.md)), and in the Connections tab's menu. Its distribution is the zip named by `comando.edge.zip` or `COMANDO_EDGE_ZIP`, else the open-source HiveMQ Edge release comando pins (2026.15), downloaded once into `edge/dist/` and checked against its published SHA-256 (the build it looked for first is not open source); it is installed in `edge/` of the data root, its data and logs in `edge/data` and `edge/log` so a newer zip keeps them. It runs as a child process on ports of its own, none an Edge's default, all on `127.0.0.1` (MQTT 18883, WebSocket 18800, admin API 18080, JMX 18010). One per machine: `~/.comando-edge.json`, shared by every instance, names its pid, the data root and name of the instance that started it, and its ports; every instance shows it running, only that instance stops it, and it stops with that instance. Only the recorded process is ever signalled, so the user's own Edges start and stop beside it ([workbench §3.5](../workbench.md#35-the-comando-edge)) |
| what travels (built on one machine by [record 123](0123-instances-consult-each-other.md): presence, inbox and reply under `comando/v1/local/instance/<id>/`; the board, roles, kit tag and brain commit not yet) | MQTT 5, small JSON: `comando/v1/<org>/instance/<id>/presence`, retained, with a last will that clears it (name, role, field, version, kit tag, brain commit, sessions, busy): a few hundred bytes, sent on change; `…/<id>/inbox`, requests with a response topic and correlation data (ask, task, review, vote) and their answers; `comando/v1/<org>/board/<decision>`, proposals and votes, retained. Never brain or kit files: a request carries only the text a session chose to send |
| trust | an instance connects with its own credentials; the Edge's access rules let it publish only under its own id and read the board and its inbox; a new peer is accepted once by the user (its id and key fingerprint on both sides); a peer may read and prompt by default, the rest by grant, as record 121 rules for outside clients |
| beyond the machine | the Edge bridges `comando/v1/<org>/#` to a shared broker when the user enables it; other people's instances join through theirs |
| the Edge in daily use | the instances exercise the Edge's broker, retained messages, last wills, access rules and bridges every day; a failure found there is reported to the Edge's project |

### 2.5 Roles (proposed)

Each instance's `comando-kit.json` names its role and field (`qa`, `architect`, `release`…), published in its presence;
a foreman routes an ask to the instance whose role fits, and a decision on the board needs the votes the board's rule
names, with the user's veto. Checkpoints on brains and kits (index checks, cue recall, the ledger's pass rates) are
published as digests, so the user reads one board instead of every instance.

## 3. Consequences

- Several instances stay apart without flags on the command line; the main instance looks and behaves as before.
- A data root's backup value is stated, so a new instance's first backup knows what to take.
- The comando Edge runs; what travels over it, trust and the bridge wait on a design pass of their own (topics, the
  board's rule) before they are built.
