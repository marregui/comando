# 133. Every file comando keeps has a schema and a version

| Date | Status | Decided by |
|---|---|---|
| 2026-11-01 | accepted; built | Miguel and Claude |

## 1. Context

comando keeps its state in files of the data root (`~/.comando` or the one it was started with), one machine-wide file
beside it, and reads a kit's `comando-kit.json`. None named a schema or a version. A file written by a later comando was
read as far as it went and then written over by this one, losing what it did not know; a file it could not read was
moved aside. A kit's manifest that broke a rule was dropped whole, logged and nothing more, and a Files-tab folder it
declared was checked by weaker rules than `folders.json`, so it showed at one start and vanished at the next.

Miguel's decisions of the first-release review, 2026-11-01: D5, every file comando persists has a schema and a
version, in JSON or another readable format, whichever is simpler and quicker for comando, panel contents included;
D7, `comando-kit.json` and comando's own files are edited by comando only, documented, with a schema and a version, and
when one is wrong comando says what is wrong and helps back to a good state, never silently dropping or flipping.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the version | a JSON file is an object whose `version` key names its schema's version, an integer from 1; a file of one entry a line starts with `# comando <format>, version <n>` | one key and one first line, the same everywhere |
| a file without a version | is version 1: a JSON list written before versions reads as version 1's list, an object without `version` as version 1, a line file without the first line as version 1 | every file written so far reads as it did |
| a later version | refused: the file is kept as it is, never written over during the run, its content not read; the user is told once, naming the file, both versions and that nothing is saved to it until a comando that reads it runs; this holds for the store files, `settings.json` and `opened-files` alike, the last two told by the window at its start | a downgrade loses nothing; the user knows why the state is empty |
| an unreadable file | moved aside as `<name>.corrupt-<time>` (store files) or `<name>.unreadable` (`settings.json`), as before | the bytes stay for the user |
| a new version | written when a format changes in a way an earlier comando would misread; adding an optional key is not such a change, as readers ignore keys they do not know | versions mark breaks, not growth |
| where the schemas live | the table below, one row a format; no JSON Schema files | the records in the code are the schemas' source; one table is one place to keep true, and no build step would read schema files |
| the kit's manifest | `comando-kit.json`'s version is its `kit` key (it named the format before this record); a manifest without it is version 1. A Files-tab folder it declares meets the rules every `folders.json` entry meets (`FolderKind`): a name used once, an icon from the list, an index inside the folder, a listing file that is a file name; a folder that breaks one is left out and named, by its place and name, with what is wrong. The window tells each at start and offers Kit ▸ Repair comando-kit.json…, which, once the user confirms, rewrites the file without those entries and keeps the old one beside it as `comando-kit.json.bak-<time>`, one per repair, never overwritten. A folder whose `root` is neither `kit` nor `brain` is left out and named the same way. A manifest refused whole (no JSON, a later version, no name, a folder outside its root) is told with its reason; comando runs without the kit | a folder never shows at one start to vanish at the next; the user sees what is wrong and has a way back |

### 2.1 The formats

| File | Where | Written by | Version | Schema (keys) |
|---|---|---|---|---|
| `connections.json` | data root | comando | 1 | `{"version", "connections": [{name, host, port, database, user, password, prod, sslMode}]}`; `password` empty where the keychain holds it ([record 20](0020-passwords-in-the-macos-keychain-through-security.md)) |
| `sessions.json` | data root | comando | 1 | `{"version", "sessions": [{name, workDir, model, effort, budgetUsd, claudeSessionId, startMode, checkpoint, carried, createdBy, family}]}`; `family` is the root of the session's creation family once the user removed it ([record 121](0121-an-agent-has-every-action-the-user-has.md)); `permissionMode` and `addDirs` of earlier entries are ignored ([record 7](0007-default-permission-mode.md)) |
| `folders.json` | data root | comando; the user may edit it, and each entry is checked by the folders' rules | 1 | `{"version", "folders": [{name, path, index, excludes, icon, deletable, listedIn, group}]}` |
| `decisions.json` | data root | comando | 1 | `{"version", "lastId", "decisions": [{id, session, title, detail, createdMillis}]}`; `lastId` at or above every id given ([record 131](0131-an-automatic-checkpoint-asks-nothing.md)) |
| `panels.json` | data root | comando | 1 | `{"version", "panels": [{id, title, connection, session, file, content, closed}]}`; `content` only for a panel whose file could not be written |
| `panels/*.md` | data root | comando, and the user in any editor | `panels.json`'s | the panel's text, plain UTF-8 Markdown, with no header: it is the user's document |
| `settings.json` | data root | comando | 1 | `{"version", "<key>": string, number or boolean, …}` in key order ([record 106](0106-settings-in-the-data-root.md)) |
| `kit-folders` | data root | comando | 2 | `# comando kit-folders, version 2`, then one kit root a line, the roots whose folders the Files tab gained once, and a line `folder<TAB>name<TAB>path` for each folder of the kit comando ran on last, where the kit put it; a version 1 file reads as roots only |
| `opened-files` | data root | comando | 1 | `# comando opened-files, version 1`, then one absolute path a line, oldest first |
| `instances-seen.json` | data root | comando | 1 | `{"version", "instances": [{slug, name, version, role, kit, started, lastSeen}]}` |
| `instance.json` | data root | the kit's instance tool, and comando for `role` | 1 | `{"version", "name", "role"}`; comando writes the role into it only while it is version 1 or has none, and reads the name and role as given |
| `agents.json` | data root | the user; comando writes `peers.deny` from the Instances tab | 1 | `{"version", "grants": {"<caller>": ["destructive"]}, "peers": {"deny": ["<instance>"]}}`; a later version grants nothing and refuses every peer, and is not written |
| `~/.comando-edge.json` | home folder | the comando that started the Edge | 1 | `{"version", "pid", "owner", "name", "ports": {mqtt, websocket, api, jmx}, "started"}`; read by every instance on the machine |
| `intents-*.jsonl` | log folder, with `-Dcomando.recordIntents=true` | comando | 1 | a first line `{"at", "type": "@state", "version", "checkpointThreshold", "state"}`, then one intent a line, a threshold change as `{"type": "@threshold", "percent"}` |
| `comando-kit.json` | the kit's root | the kit (an agent or the kit's own tool); comando repairs it | `kit`: 1 | `{"kit", "name", "brain", "folders": [{name, path, root, index, excludes, icon, deletable, listedIn, group}], "requirements": [{tool, check, install}], "credentials": [{key, for, where}], "jobs": [{name, schedule, command}]}`; `brain` optional (absolute, `~/…` or relative to the root; absent: the root's `BRAIN`; `COMANDO_BRAIN` wins over it, D10); a folder's `root` optional, `kit` (the default) or `brain`, its `path` relative to that root (`.` the root itself); other paths relative to the root |
| messages between instances | the comando Edge's topics | comando | `schema`: 1 | presence, question and reply carry `schema` (the presence's `version` is comando's release); a question of a later schema is answered refused, a presence or reply of a later schema is read for the keys this one has |

Not under this rule, each for its reason:

| Files | Why |
|---|---|
| themes (`themes/*.json`) | the user's own files, in the format of [record 126](0126-themes-are-json-files.md) |
| `metrics/*.jsonl`, `audit/*.jsonl`, comando's log files | append-only records for people and tools, read back by comando at most as a hint (the last context of a conversation); each line's shape is version 1 while it carries no `version`, and the first change to a line's shape adds one to that line |
| `cache/`, `help/`, the Edge's install record, `run/` | made again from their source (the jar, a diagram's text, the Edge's zip, the running session) when missing or unreadable |
| the Edge's `edge/conf/` (`config.xml` and the files copied from the release) and `edge/admin-password` | the Edge's own formats, which comando sets again at each start: the listeners and the admin API's account from comando's settings, the password made again when it does not read; both the user's alone (rule 15) |

## 3. Consequences

- `JsonFile` writes every store file as `{"version", <list>}` and reads a bare list as version 1; `Store` tells each file
  of a later version once and writes nothing to it; the window does the same for `settings.json` and `opened-files`,
  one notice each at its start (`Settings.refusal`, `OpenedFiles.refusal`). `VersionedLines` reads and writes the line files; `Settings`,
  `Peers`, `EmbeddedEdge` and `IntentLog` carry their version as the table says.
- A comando from before this record reads a store file written by this one as unreadable and moves it aside: going back
  to such a build loses no data, but its window starts without that state.
- `KitJson` validates each manifest folder with `FolderKind` and returns the entries it left out as
  `KitRoot.Manifest.problems`; `KitRoot.refused` names why a manifest was not used; `App` tells both at start;
  `KitJson.repair` and the palette's Kit ▸ Repair comando-kit.json… take the entries out.
- Tested in `StoreTest` (a versioned file, a bare list, a later version kept and told; a kit folder kept at both starts
  or left out with its reason), `SettingsTest`, `OpenedFilesTest`, `KitJsonTest` (no version is 1, a later one refused,
  folders left out and named, the repair), `KitRootTest`, `IntentLogTest` and `PeersTest`.

## 4. Alternatives

| Option | Why not |
|---|---|
| JSON Schema files under `docs/schemas/` | no part of the build validates against them, so they would drift from the records; one table is read with the decision |
| a version in the file name (`sessions.v1.json`) | two files would hold one state across an upgrade, and the user's backups name the files |
| refusing a manifest whole for a bad folder | the whole kit would go for one entry, and the user would see only a log line |
