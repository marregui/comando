# 125. The session dialog asks what matters first and names its defaults

| Date | Status | Decided by |
|---|---|---|
| 2026-10-30 | accepted; §2 built (§2.1 amended 2026-10-31), §3 proposed for the packaging work | Miguel and Claude |

## 1. Context

Miguel, 2026-10-30, opening New session in the Comando Expert: "I have no idea what the defaults would be in that view,
I dont know what the working directory means, no way of choosing a kit, or a brain, no idea what budget means, or extra
directories, and the advanced is not advanced and should be at the top." The form listed the CLI's flags in their
order, its hints named flags (`--add-dir`, `--max-budget-usd`), "empty" meant the kit's `settings.json` without saying
what that held (effort `medium`, permission mode `bypassPermissions`: tools run without asking), a new session started
in the user's home folder, and the conversation it continues sat folded under Advanced. Checked against his own
instance: every one of its seven sessions runs in the kit's folder, `~/.claude`, and reaches projects by path.

## 2. Decision

| Question | Rule |
|---|---|
| order | **Name** first, its hint "what this session is for: it names its panel and its ongoing conversation" (Miguel, same day: the name gives the session its meaning and purpose, and it goes on); then Conversation (NEW, RESUME, FORK) and Stored conversation; then Model, Effort, Permission mode; then **More**, folded: Working directory, Extra folders, Checkpoint prompt |
| defaults | an empty Model, Effort or Permission mode shows, in the choice itself and muted, the value it stands for and where it comes from: "medium · the kit's default", "bypassPermissions · the kit's default: runs every tool unasked", "claude-opus-5-5 · Claude Code's default". Empty still means "follow the kit", so a later change there reaches the session; a value picked holds for that session; a clone shows its source's. The application reads the root's `settings.json` (`model`, `effortLevel`, `permissions.defaultMode`) at start into `SessionKind.Defaults`, and Claude Code's own model from the first start of a session that names none (`SessionInit`). `bypassPermissions` is a choice, as it is the kit's default. Generic: `Attribute.whenEmpty`, drawn by the dialog's renderer and, for the open model choice, as its placeholder |
| explanations | every session field has an ⓘ after its label (the sidebar rows' mark). A click shows the sidebar's card (`TabCard`) in its explaining form under the label: the field's name, "What it is" and two or three sentences (`Attribute.about`), with the close button, no facts and nothing to read again. It closes at its close button, at a second click on the ⓘ, or once the pointer has left the label for `TabCard.LEAVE_GRACE_MILLIS` (500 ms) without reaching the card, the grace the workspace's tab cards use, now kept by the card itself. A tooltip was tried first and dropped the same day: its timer closed it before the text was read. The line under the field stays one short hint |
| where it runs | a line above the form: "Runs on kit ~/.claude_comando_expert · brain …/BRAIN · instance Comando Expert". A session does not choose a kit or a brain: one kit and one brain per instance ([record 122](0122-instances-name-kit-brain-data-root-edge.md)); another kit is another instance |
| working directory | a new session starts in the kit's folder, as the user's sessions do (it was the focused session's folder, else the last used, else home); the field is under More, its hint "this folder's CLAUDE.md and git branch; the kit's by default". More opens by itself when the folder is not the kit's, there are extra folders, or the checkpoint prompt is not `/reflect` |
| budget | not asked: `--max-budget-usd` binds only on API billing, and the user's plan is a subscription. A stored budget is kept by an edit (the form keeps the values it does not show) and still reaches the CLI |
| wording | hints say what a field does, not its flag; "Next start" is "Conversation", "Extra directories" "Extra folders", "Checkpoint command" "Checkpoint prompt" |

### 2.1 Amendment, 2026-10-31: the conversation is picked, never typed

Miguel, opening New session: "the stored conversation is a text area, it does not make my life simple, there is no
guidance there, just 'Stored conversation' ... I do not register what that could possibly mean or the relation with
the fork resume etc". The form asked for a Claude Code conversation id in a free text field, which nobody knows, beside a
NEW/RESUME/FORK choice whose link to it was not shown; any text was taken, and only a warning at the bottom said
anything.

| Question | Rule |
|---|---|
| one row | **Conversation** is one row (`Attribute.Kind.CONVERSATION`, `ConversationField`) editing both `claudeSessionId` and `startMode`; the free text field is gone. Hint: "a new one, or one stored on disk to continue or copy" |
| the picker | **A new conversation** first; a saved session's own next, "Its own · title · folder · age"; then the conversations Claude Code stored on disk, newest first, each "title · folder · age" (the last prompt when it has no title), the list Sessions ▸ Import… reads (`SessionCatalogue`, read off the EDT each time the dialog opens). The id shows only in a row's tooltip. A conversation another session holds is not offered: two sessions never write one conversation; Clone copies one |
| how | beside a stored or own conversation, **Continue it** (RESUME) or **A copy of it** (FORK); hidden for a new one. A saved session's own defaults to Continue it, a clone's to A copy of it |
| a stored one picked | defaults to A copy of it and runs the session in its folder, where Claude Code keeps it, as an import does (`SessionSpec.imported`); More opens to show the folder |
| a new one over its own | keeps the own id with NEW, as before: the next start replaces it. The line at the bottom reads "The next start begins a new conversation; the one before stays on disk" |

Tested in `ResourceDialogTest` (the rows by title, held ones left out, a pick copied in its folder, Continue it
resumes, a saved session's own and a new one keeping its id) and `SessionCardUiTest` (⌘⇧K in the real window: a
transcript under the projects folder offered by its title, picked, saved as a fork in its folder).

## 3. Proposed: where an installed comando keeps its kit and brain

Miguel, same day: a comando downloaded as a zip or a DMG and started by a double click needs to know, once, where its
kit, its brain and its data root live; the folder comando itself runs from, and the working directory of the process
that started it (a Gradle checkout today), mean nothing to its sessions. The first start of an instance with no kit
asks: "Where should this comando keep its kit and brain?", default `~/.claude` for the first instance and
`~/.claude_<name>` for another, as `kit.py instance new` does, with the data root `~/.comando` or `~/.comando_<name>`
beside it. Every session then runs on that kit, in its folder by default (§2). Owner: the packaging work.

## 4. Consequences

- A new session needs a name and nothing else; what it runs with is on screen before Save.
- A user who wants a project's own `CLAUDE.md` and branch opens More and picks the project's folder.
- `session_new` keeps `work_dir` required: an agent names the folder it means.
