# 61. The kit overrides a setup of the user's own, with the user's consent

| Date | Status | Decided by |
|---|---|---|
| 2026-10-18 | replaced by [record 80](0080-the-kit-is-a-claude-code-setup-comando-runs-its-sessions-on.md); not built | Miguel and Claude |

## 1. Context

[Record 50](0050-comando-ships-a-claude-code-kit.md) left the kit out wherever the user already had a `reflect` or
`deep-review` of their own: the kit was neither installed nor enabled beside it, the dialog offered only Skip, already
set up, and a user with such a setup was never asked. The Atlas design (comando's concept map for agents, not built)
makes the kit's `/reflect` the only writer of what sessions learn, fed by evidence and drafts that the Atlas prepares. A
`/reflect` comando did not install does not call the Atlas, so under record 50 such a user would get no learning at all.

This record replaces the rows "a setup of the user's own" and "offer at start" of record 50.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| a setup of the user's own | `reflect` or `deep-review` as a skill or a command in Claude Code's folder no longer keeps the kit out. The kit offers to override both, and installs only when the user agrees; once installed, the kit's `/reflect` and `/deep-review` are the ones that answer | `/reflect` is part of comando's way of working; two commands with one name must not both answer |
| what the dialog says | before anything is written, the dialog names the user's own `reflect` and `deep-review` that the kit will override, alongside the changes record 50 already lists | the user agrees knowing what stops answering |
| offer at start | on the default data root only, when Claude Code does not have the plugin, whether or not a setup of the user's own is found: Install, Not now (the default), Don't ask again; Help ▸ Claude Code kit asks at any time | a user with a setup of their own is now asked as well |
| declined | nothing is installed or written, as before; comando works without the kit, and the Atlas gives navigation only | consent stays the gate |
| the user's own files | the override does not delete them | the user can return to them by removing the kit |

Miguel decided that `/reflect` is part of comando, of the kit, and that the kit overrides a `reflect` of the user's
own, and their `deep-review` with it, once the user agrees; Claude decided the wording, and left how the override is
made to the implementation.

## 3. Consequences

`Kit`'s detection of a setup of the user's own stops refusing the install and instead names what will be overridden in
the dialog. How the override is made is not settled here: which of two skills with the same name Claude Code runs, or
whether the user's own must be disabled while the kit is enabled, has not been checked, and is settled with the change
to `Kit`, keeping record 50's rule that comando writes nothing into Claude Code's folder outside its contract. Record
50's other rows stand.

The change to `Kit` was not made. Record 80 moved the kit into a repository of its own, installed by Claude Code from
the kit's `INSTALL.md`, and removed `Kit` from comando.
