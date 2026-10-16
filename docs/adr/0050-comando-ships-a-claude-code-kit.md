# 50. comando ships a Claude Code kit, and relies only on what it installed

| Date | Status | Decided by |
|---|---|---|
| 2026-10-16 | accepted; replaces the last paragraph of [record 46](0046-clear-buttons-text-coloured-by-kind-checkpoint-due.md) ("comando reads nothing from such a setup") | Miguel (comando carries the kit and installs it; through Claude Code's own plugin mechanism, not by copying files into `~/.claude`; a record that replaces 46's rule; one memory for every project is part of the comando flow, warned about and agreed to, or no kit; each `/reflect` leaves recall sharper within the index's budget; agents primed from memory and the KB), Claude (how: what the kit holds, the offer at start, the copy kept equal to the build, `tools.json`) |

## 1. Context

comando is built for a way of working with Claude Code: memory and a knowledge base that a checkpoint (`/reflect`)
keeps current, reviews against stated requirements, agents given specs with an executable acceptance, Gradle runs that
queue per checkout. That way lived in one user's private Claude Code plugin and skills. comando's code leaned on it in
two places: Gradle runs looked for `gradle-serial` in that plugin's folder, and jfrq in that user's workspace. Record
46 ruled that comando reads nothing of a user's Claude Code setup, so that it works for anyone; a public comando that
leaves the way of working out gives a new user the window without the method.

Claude Code installs plugins from a marketplace, and a marketplace can be a local folder. Verified on Claude Code
2.1.301: `claude plugin marketplace add <folder> --json` and `claude plugin install <plugin>@<marketplace> -y --json`
run without a prompt and declare their entries in the user's `settings.json` themselves; a plugin from a local folder
loads from that folder, not from a copy; `claude plugin uninstall` and `claude plugin marketplace remove` undo both; a
plugin's skill answers both as `/<plugin>:<skill>` and as `/<skill>`.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what the kit is | a Claude Code plugin in the repository's `kit/` folder, named `comando` in a marketplace named `comando`, versioned in its manifest and carried in comando's build | one install gives the window and the method; the kit is also installable without comando, from a clone |
| memory | the install sets Claude Code's `autoMemoryDirectory` to `MEMORY` in Claude Code's folder, unless the user set it (their value stands and is used), so every project shares one memory; listed first, with its consequence (memories kept per project stay where they were); the kit comes whole: no consent, no kit. It is the one key comando writes into Claude Code's `settings.json` itself (Claude Code 2.1.301 has no command that sets it), read again just before, every other key, Claude Code's layout and a byte-order mark kept, through a link to where the file lives, with its permissions; a file Claude Code would refuse (not one JSON object, a value neither absolute nor `~/`, a link to nothing) is never written and stops the install | auto memory is per project by default; the comando flow accrues what is learnt across projects |
| recall | `MEMORY.md` holds one cue per memory file; `/reflect` keeps it within 160 lines and 20 KB (Claude Code loads 200 lines or 25 KB), sharpens the cues of recall misses, and reports its delta; `index-check` warns past the budget | what lies past the limit is never seen; every checkpoint leaves the next session better primed |
| agents | the agent-spec template opens with a Context section quoting the memory facts and KB passages the task needs; no Context, no dispatch | subagents do not get the main session's memory |
| what it holds | the method, read by Claude at every session start (new, resumed, cleared) and after each compaction; `/reflect` and `/deep-review`; the agent-spec template; `gradle-serial` and `index-check` on Claude's PATH; a Mermaid check after Claude writes Markdown; the memory index's header and the rules of a knowledge base and a scratchpad as skeleton files | what the way of working needs and nothing tied to one employer's systems; memory is Claude Code's own auto memory |
| comando's copy | `kit/` in comando's folder (the data root's path normalised), made equal to the build's at every start (files written when they differ, scripts made executable, files the build no longer carries deleted) | the installed plugin loads from this copy, so it follows comando's version without a second update step, as the tutorial does ([record 47](0047-help-opens-its-files-as-documents.md)) |
| install | Help ▸ Claude Code kit lists every change first: the memory setting, the marketplace registered (or a marketplace of that name reading another folder pointed at comando's copy, which makes Claude Code uninstall what was installed from it, in any project, as the line says), the plugin installed for the user (or enabled, when the user disabled it), each skeleton file created, each folder the Files tab gains; on Install, comando runs those `claude plugin` commands, each bounded to a minute, writes the skeleton files that are missing, never over one that exists, and adds the memory, the knowledge base and the scratchpad to the Files tab unless a folder shows their path or has their name; a step that fails undoes the steps before it, last first (the memory key taken out, or the file put back as it was when nobody wrote it since; the plugin uninstalled or disabled again; the marketplace removed, or registered again where it was; the skeleton files deleted unless written to since), and the notice gives Claude Code's message, what was undone and what could not be | Claude Code edits its own settings, but for the memory key; the user's files stay the user's |
| a setup of the user's own | `reflect` or `deep-review` as a skill or a command in Claude Code's folder, while the plugin is not installed or is disabled: the kit is neither installed nor enabled beside it, by the dialog (its one button: Skip, already set up) or by any other path (the install refuses), and nothing else is written; one in another plugin or in a project's `.claude/skills` is not looked for | the kit's skills would answer the same commands: duplicate nothing |
| offer at start | on the default data root only, when Claude Code does not have the plugin and no setup of the user's own is found: Install, Not now (the default), Don't ask again; Help ▸ Claude Code kit asks at any time | a user with a setup of their own is not asked; a test or second data root never asks Claude Code about the user's plugins |
| after an update | when the copy changed and Claude Code loads it (installed for the user, enabled, from comando's copy), a notice says the kit's new version loads in new sessions; Help ▸ Claude Code kit names the version that loads (Claude Code's `folderVersion`) and the folder it loads from | the change is visible |
| what comando relies on | only what it installed, through this contract: the kit's folder in comando's folder, its manifest's version, the plugin id `comando@comando`, the checkpoint command `/reflect` (record 45), the skeleton's folders `KB` and `SCRATCHPAD` in Claude Code's folder (`CLAUDE_CONFIG_DIR`, else `~/.claude`), which the method names to Claude by the same rule | the rule of record 46, kept for everything comando did not install |
| tool paths | `tools.json` in comando's folder, opened as a document by Panel ▸ Tools ▸ Tool settings (written with its keys empty first when missing), read at each run: `gradleWrapper` empty for the kit's `gradle-serial`, `none` for the checkout's `./gradlew`, or a path; `jfrq` empty for `COMANDO_JFRQ`, else `jfrq` on the `PATH`, or a path; what is wrong with the file is the run's first console line, and the defaults apply | the paths into one user's folders are gone from the code; an edit applies to the next run without a restart |

## 3. Consequences

`Kit` (claude) holds the bundle (`Kit.Bundle`, the build's resources indexed by `files.txt`, which the `kitIndex` task
writes: a jar cannot list a folder, and the kit has hidden ones), the `claude` runs (`Kit.Cli`), `refresh`, `status`,
`install` and `folders`; `Kit.Cli.process` gives a run no input, bounds it, and reads its output on a thread of its own. `App` refreshes the copy on a virtual thread at start and decides on the offer (`App.atStart`);
`ComandoUi.offerKit` shows it once the window is up; `Setup.KitOffer` holds the dialog's text and buttons, `Setup.missingFolders` the folders to add. The menus reach the kit and `tools.json` through `Setup` (ui
shell), which `KitSetup` (app) implements over `Kit` and `ToolsFile`. `ToolsAdapter` takes a supplier of
`ToolSettings` and reads it at each run; `ToolSettings.defaults()` and its two paths are gone. `AppConfig` gains
`offerKit` and `kitDir()`. `KitLiveTest` installs, disables and re-enables the kit with the real Claude Code, through `Kit.Cli.process`, in a
throwaway `CLAUDE_CONFIG_DIR` when `COMANDO_KIT_LIVE=true`.

The kit does not carry a context watermark: comando's checkpoint already says when `/reflect` is due, at 60% of the
window it reads from the stream (record 46). The checkpoint stays `/reflect`, which the kit's skill answers to.
