# 84. Claude shows what is better seen than read

| Date | Status | Decided by |
|---|---|---|
| 2026-10-21 | proposed | Miguel ("what a creative way of communicating with me … if this was a feature, a skill, a command, what would its best form be so that it can be repeated"; a show-options skill, "but I would also like the abstraction over it, the next time you might do something else, like an animation"), Claude (one skill with five forms, the helper, the view conventions) |

## 1. Context

Asked to choose a gold, the user was shown a sheet of the candidates drawn on comando's own backgrounds in a side view,
then the real transcript canvas painted in each; a choice argued over in prose took one look. comando's Markdown views
already show a local PNG, JPEG, GIF or SVG named by its path, and `ui_show_view` puts such a view beside the
conversation. Nothing told Claude when to do this or how, so it would happen again only by chance.

## 2. Decision

| Question | Rule | Why |
|---|---|---|
| the form | a skill, `show`, in the kit: Claude invokes it on its own judgement, not a command the user types | the value is in noticing that a choice is visual before asking it |
| the abstraction | show, don't tell, in five forms picked by what the answer is about: a sheet of options, the proof (the real component painted off screen), motion (an animated GIF), a diagram (Mermaid), data and diffs (the existing views) | an animation, a diagram and a sheet are the same act: evidence in a view, reasoning in the reply |
| the helper | `show.py` beside the skill (Pillow): a canvas at twice the size, gilded titles, a grid of options, crossfades and looping GIFs; `--test` checks it | sheets and animations look alike across sessions and take minutes, not a script from scratch each time |
| the view | `ui_show_view` markdown, side, one title per topic so a later round replaces it in place; pictures by absolute path under `###` captions | the user looks at one place that updates |
| the reply | one or two lines: what the view shows, the recommendation, the decision asked; no description of the picture | the user is looking at it |
| fidelity | colours and fonts read from the code, the real ground; a sheet before the code is labelled a mock-up, and the built result is shown as a proof | a mock-up that differs from the result misleads the choice |

## 3. Consequences

The kit needs Pillow for pictures; without it Claude asks before installing it and may write an SVG meanwhile. Pillow
draws no glyph a face lacks, so a sheet keeps to characters its faces have. The kit's version is 1.3.0.
