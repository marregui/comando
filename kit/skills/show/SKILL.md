---
name: show
description: Show, don't tell. When an answer or a choice is better seen than read, render it and put it in a comando view beside the reply. Use before asking the user to choose anything visual (colours, type, layout, an icon), to explain anything that moves or unfolds in time (an interleaving, a state machine, a queue draining, a transition), to lay out structure or numbers, and after building UI to show the real thing working.
---

# Show

The reply carries the reasoning; the view carries the evidence. When the user would otherwise have to imagine
something (how a colour looks on the real ground, how two threads interleave, what a layout does when narrow), render
it and show it. One look settles what a paragraph would argue.

## 1. Pick the form

| The answer is about | Form | Made with |
|---|---|---|
| how something looks, with options to choose from | **Sheet**: the options side by side on the real backgrounds, labelled A, B, C, the current one first as "Now" | `show.py`: `canvas`, `gild`, `grid` |
| whether a UI change looks and works as asked | **Proof**: the real component painted off screen to PNG, not a mock-up | a throwaway test in the repository that paints the component into an image (Swing: `paint` into a `BufferedImage`; a web page: a headless browser's screenshot); delete it after |
| something that moves or happens in time | **Motion**: an animated GIF, one frame per step, a caption or counter on each frame | `show.py`: `crossfade`, `gif` |
| structure: dependencies, a flow, a sequence | **Diagram** | a `mermaid` block in a Markdown view |
| numbers | **Data** | `ui_plot`, `ui_show_table` |
| a change to a text | **Diff** | `ui_show_view` type `diff` |

A choice that is not visual, or an answer one sentence settles, needs no view.

## 2. Render

- Work in the session's scratchpad, one folder per topic. Scripts import the helper from this skill's folder:
  `sys.path.insert(0, "<this folder>"); import show`, run with `python3 -I -B` (`-B` leaves no `__pycache__` in
  the skill's folder). `python3 show.py --test` checks it.
- Fidelity: read colours, fonts and sizes from the code, never from memory; draw on the real background. A sheet drawn
  before the code exists is a mock-up and says so on it; after building, show the proof.
- `show.SCALE` draws at twice the size: the view fits a picture to its width, so it stays sharp.
- Pillow draws no glyph a face lacks (no fallback): keep to characters the face has, or the proof will differ.
- At most four options on a sheet. A GIF runs at most about 12 s and stays under about 2 MB; it loops.
- Without Pillow (`python3 -c "import PIL"` fails): ask the user before installing it (`python3 -m pip install --user
  pillow`); meanwhile an SVG file written by hand shows in the view too (no motion).

## 3. Show

```
ui_show_view  type: markdown   placement: side   title: <the topic, the same each time>
data: { "markdown": "### A  Treasure gold\n![Treasure](/abs/path/a.png)\n\n### B  …" }
```

- A local picture's absolute path in `![caption](…)` shows as itself: PNG, JPEG, GIF (animated) or SVG. Nothing from
  the network is shown.
- The same title replaces the view in place, so a second round (the user's pick rendered for real, a refinement)
  updates what they are looking at instead of adding another.
- One view per topic; several pictures in it under `###` captions.

## 4. Say it

Above the view's content, the reply says in one or two lines what the view shows, which option is recommended and why,
and the decision asked of the user. Do not describe the picture at length: the user is looking at it. When the
result is built, name the proof in the view and what was checked beside it (tests, gates).
