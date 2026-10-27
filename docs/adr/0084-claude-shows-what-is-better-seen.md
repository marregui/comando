# 84. Claude shows what is better seen than read

| Date | Status | Decided by |
|---|---|---|
| 2026-10-21 | proposed | Miguel and Claude |

## 1. Context

Asked to choose a gold ([record 83](0083-one-gold-chosen-beside-clear.md)), the user was shown a sheet of the
candidates drawn on comando's own backgrounds in a side view, then the real transcript canvas painted in each; a choice
argued over in prose took one look. comando's Markdown views already show a local PNG, JPEG, GIF or SVG named by its
path, and `ui_show_view` puts such a view beside the conversation. Nothing told Claude when to do this or how, so it
would happen again only by chance.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the form | a skill, `show`, in the kit, used when the user asks to see something, never on Claude's own initiative | a view the user did not ask for interrupts more than it helps |
| the abstraction | show, don't tell, in five forms picked by what the answer is about: a sheet of options, the proof (the real component painted off screen), motion (an animated GIF), a diagram (Mermaid), data and diffs (the existing views) | an animation, a diagram and a sheet are the same act: evidence in a view, reasoning in the reply |
| the helper | `show.py` beside the skill (Pillow): a canvas at twice the size, gilded titles, a grid of options, crossfades and looping GIFs; `--test` checks it | sheets and animations look alike across sessions and take minutes, not a script from scratch each time |
| the view | `ui_show_view` markdown, side, one title per topic so a later round replaces it in place; pictures by absolute path under `###` captions | the user looks at one place that updates |
| the reply | one or two lines: what the view shows, the recommendation, the decision asked; no description of the picture | the user is looking at it |
| fidelity | colours and fonts read from the code, the real ground; a sheet before the code is labelled a mock-up, and the built result is shown as a proof | a mock-up that differs from the result misleads the choice |

Miguel asked for the act to be repeatable as a skill, and for the abstraction over it, so that another time it may be
an animation rather than a sheet; on 2026-10-22 he decided that it runs only when he asks. Claude decided one skill with
five forms, the helper, and the view conventions.

## 3. Consequences

The kit needs Pillow for pictures; without it Claude asks before installing it and may write an SVG meanwhile. Pillow
draws no glyph a face lacks, so a sheet keeps to characters its faces have. The kit's version is 1.3.0. The skill and
its helper live in the kit, outside this repository
([record 80](0080-the-kit-is-a-claude-code-setup-comando-runs-its-sessions-on.md)).

## 4. Alternatives

| Option | Why rejected |
|---|---|
| Claude shows a view when it judges one useful (the first proposal) | Miguel, 2026-10-22: an unasked view got in his way |
