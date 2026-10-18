# 62. Gradle runs by convention; no `tools.json`

| Date | Status | Decided by |
|---|---|---|
| 2026-10-18 | accepted; replaces the row "tool paths" of [record 50](0050-comando-ships-a-claude-code-kit.md) and Tool settings in [record 59](0059-three-menus-for-what-has-no-key.md)'s bar | Miguel (no setting where a convention does; the menu item and the file go), Claude (the convention) |

## 1. Context

Since [record 54](0054-comando-knows-nothing-of-jfr.md) removed `jfrq`, `tools.json` held one key, `gradleWrapper`:
empty for the kit's `gradle-serial`, `none` for the checkout's `./gradlew`, or a path. Tools ▸ Tool settings opened it.
The default was the only value in use, and the kit's `gradle-serial` takes its lock in `/tmp/gradle-serial/`, keyed by
the checkout, the same lock as every other copy of it, so a run started from the menu already queues with the runs of
the sessions.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the command | `<kit>/bin/gradle-serial <root> <tasks>` when the kit's copy is executable, else `<root>/gradlew --console=plain <tasks>`; looked up at each run | a kit installed while comando runs serves the next run |
| configuration | none: no `tools.json`, no Tool settings item; a `tools.json` left in a data root is ignored | the one choice had one value in use |

## 3. Consequences

`ToolsFile` and `Setup.toolSettings` are gone; `ToolSettings.of(kitSerial)` replaces `ToolSettings.resolve`, and its
`problem` field, which carried what was wrong with the file to the run's console, is gone with it. A user who wants
plain `./gradlew` runs it in the Terminal tab.
