# 80. The kit is a Claude Code setup in a repository of its own, and comando runs its sessions on it

| Date | Status | Decided by |
|---|---|---|
| 2026-10-26 | accepted; amended 2026-10-27 (see Amendments) | Miguel and Claude |

## 1. Context

[Record 50](0050-comando-ships-a-claude-code-kit.md) shipped a kit inside comando: the repository's `kit/` folder, a
Claude Code plugin with two skills, a few scripts and a skeleton, bundled into the build, copied to `~/.comando/kit` at
every start, and installed through Claude Code's plugin commands from **Actions ▸ Claude Code kit**
([record 75](0075-the-menu-bar-is-edit-and-actions-flat.md)), or from an offer at start.
[Record 61](0061-the-kit-overrides-a-setup-of-the-users-own-with-consent.md) had the kit override a user's own `reflect`
and `deep-review`, with consent; it was not built. A Gradle run ([record 62](0062-gradle-runs-by-convention.md)) used
the kit's `gradle-serial` by default. A first version of this record (proposed 2026-10-20) planned a
manifest, an install record kept by comando, uninstall, and revert to an earlier version.

The author's working setup is his Claude Code folder itself: `CLAUDE.md`, `settings.json`, user skills, and a plugin
with agents, scripts, hooks, Semgrep rules and scheduled jobs. comando's kit carried a small part of it, and its scripts
existed in both places, kept equal by a test.

Claude Code 2.1.310, checked on 2026-10-26: `CLAUDE_CONFIG_DIR=<folder>` makes a session use that folder for
everything (settings, `CLAUDE.md`, skills, plugins, transcripts in `<folder>/projects`); a new folder answers "Not
logged in" until `CLAUDE_CONFIG_DIR=<folder> claude auth login`; `--settings '<json>'` adds a settings layer above the
user's and accepts `autoMemoryDirectory` as an absolute path.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| what the kit is | the setup files of a Claude Code folder, in a private repository of their own (`marregui/comando-kit`, branch `master`), versioned by tags `vMAJOR.MINOR.PATCH`: `CLAUDE.md`, `settings.json`, `skills/`, the plugin, `comando-kit.json`, `INSTALL.md` and its installer `install/`; the author's work tools included | one copy: the author's `~/.claude` is a checkout of the kit, so a release has run on his setup before anyone else's |
| `comando-kit.json` | the kit's name, its brain folder, the Files tab's folders, the tools it needs, the credentials it names, the scheduled jobs; owned by the kit, read by comando | what comando needs from a kit is declared, not inferred from one user's folder |
| the root | the folder comando's sessions run on: `CLAUDE_CONFIG_DIR` when comando is started with it; else `~/.claude` when it holds `comando-kit.json`; else `~/.claude/comando-kit` when that holds `comando-kit.json`; else `~/.claude`, without a kit; a `comando-kit.json` that does not parse counts as none | the author's folder is the kit; another user's kit is a clone beside their own setup |
| sessions on the root | every `claude` comando starts gets `CLAUDE_CONFIG_DIR=<root>` when the root is not `~/.claude`, and, with a kit, `--settings` with `autoMemoryDirectory` set to `<root>/<brain>/MEMORY`; stored conversations are read from `<root>/projects` | Claude Code's documented switch of folder; a session on another root never reads or writes the user's own `~/.claude` |
| the brain | `<root>/BRAIN/`: `MEMORY/`, `KB/`, `SCRATCHPAD/`; never part of the kit, whose repository ignores it; a repository of its own when the user makes one | the setup is shared and versioned, what a user's sessions learn is theirs |
| installing comando | a GitHub release, made when a version tag `v*` is pushed: a macOS disk image, a Linux `tar.gz` and a Windows `zip`, each with its own Java runtime, unsigned; a Claude Code agent installs it by `INSTALL.md` at the repository's top, and on macOS clears the quarantine flag | the user asks Claude Code, which has the shell; [record 16](0016-unsigned-macos-build.md) stands: no Developer ID while the repository is private |
| installing the kit | not comando's: after installing comando, the agent asks whether to install the kit and, on a yes, follows the kit's `INSTALL.md` (a clone into `~/.claude/comando-kit` at a tag, the plan shown, one consent for tools, jobs and credentials, each recorded in `<root>/.comando-kit-install.json` for its uninstall) | consent and the install happen in one conversation; comando's code carries no installer |
| the Files tab | when `folders.json` holds none of the kit's folders, they are added at the top, once; an entry with the same real path counts as the kit's; **Use the kit's folders** adds them again | the kit's skills, plugin and brain are one click away for the user who improves them |
| upgrade | comando shows the kit's version and a newer tag; on the user's word, **Upgrade the kit…** sends a session named `kit`, on the user's own setup, to follow the kit's `INSTALL.md` section Upgrade; the user's changes are commits on a local branch, merged with the new tag | the user decides when there is time; a merge keeps their changes |
| the brain's backup | **Back up the brain**: `git add -A`, a commit, `git push` in `<root>/BRAIN`, only on the user's click | a commit and a push of what the sessions learnt are the user's act; nothing sends it unseen |
| platforms | macOS, Linux and Windows, for comando's packages and for the kit's installer (Python 3, standard library only) | the users of comando and of the kit work on all three |

Miguel decided: the user installs Claude Code and asks it to install comando from a release; that agent installs comando
and asks whether to install the kit, which it installs on a yes from the kit's own `INSTALL.md`; a release is made when
he pushes a version tag; comando, when it starts, notices that `~/.claude` has the kit and runs its sessions on it; the
user decides when there is time to upgrade, and comando then tells the agent to proceed; the Files tab is laid out for
improving skills and the brain; the brain is backed up only from comando, by hand; macOS, Linux and Windows. Claude
decided the root's resolution, the session environment, the Files tab's merge, the release workflow and the install
recipe.

## 3. Consequences

This record replaces records 50 and 61 in what they say of installing the kit and of a setup of the user's own; record
50's one memory for every project stands, now set per session through `autoMemoryDirectory`. The kit inside comando is
gone: the `kit/` folder and its bundling, `Kit`, `KitSetup`, the offer at start and the system property that switched
it, **Actions ▸ Claude Code kit** (record 75's item), and the tests `KitTest`, `KitLiveTest` and `KitAtStartTest`. Of
the proposal of 2026-10-20, the manifest is `comando-kit.json` and the install record is the kit's
`.comando-kit-install.json`; revert to an earlier version is an upgrade to an earlier tag. A Gradle run
([record 62](0062-gradle-runs-by-convention.md)) goes through the first `gradle-serial` on comando's `PATH`, the kit's
copy in comando's folder being gone, else the checkout's `./gradlew`.

`KitRoot` (core) resolves the root; `KitJson` (claude) reads `comando-kit.json` into it. `./gradlew :app:package` builds
the running system's release file in `app/build/package`; `.github/workflows/release.yml` builds it on the three
systems on a tag, and makes the release with the commits since the previous tag as its notes; run by hand, it builds
the three files as the run's artifacts without a release. [Workbench §7](../workbench.md#7-the-kit) describes the root,
the brain, the Files tab, the upgrade and the backup.

## Amendments

2026-10-27: the repository is public; the packages stay unsigned, as record 16's amendment of the same day decides.
