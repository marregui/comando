#!/usr/bin/env python3
"""Helpers for the show skill: sheets of options, gilded titles, animations, all as PNG or GIF files for a comando view.

Import it from a script in the session's scratchpad, run with `python3 -I -B`:

    import sys; sys.path.insert(0, "<this skill's folder>")
    import show

Needs Pillow (`python3 -c "import PIL"`). `python3 show.py --test` checks this file.
"""
import os
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFont

# Pixels per point: drawn twice as large; the view fits a picture to its width, so it stays sharp on a Retina screen.
SCALE = 2

# Faces tried in order when a face is not found: macOS, Windows, Linux.
FALLBACK_FACES = [
    "/System/Library/Fonts/SFNS.ttf",
    "C:/Windows/Fonts/segoeui.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/TTF/DejaVuSans.ttf",
]


def rgb(hex_colour):
    """'#E7C55A' or 'E7C55A' as an (r, g, b) tuple."""
    h = hex_colour.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def mix(a, b, t):
    """The colour t of the way from a to b, t from 0 to 1."""
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def font(path, points, index=0, variation=None):
    """The face at `path` (a .ttf, .otf or .ttc and its index) at `points`, scaled; else the first fallback face found,
    else Pillow's own. `variation` names a variable font's instance, such as b"Semibold". PIL draws no glyph a face
    lacks: keep to characters the face has (an arrow is often missing from a serif)."""
    for candidate in [path] + FALLBACK_FACES:
        if candidate and os.path.exists(candidate):
            try:
                f = ImageFont.truetype(candidate, round(points * SCALE), index=index if candidate == path else 0)
                if variation and candidate == path:
                    f.set_variation_by_name(variation)
                return f
            except OSError:
                continue
    return ImageFont.load_default(round(points * SCALE))


def canvas(width, height, ground="#121212"):
    """An image of `width` by `height` points and its drawing context, filled with `ground`."""
    img = Image.new("RGB", (round(width * SCALE), round(height * SCALE)), rgb(ground))
    return img, ImageDraw.Draw(img)


def pt(value):
    """Points to pixels, for coordinates passed to Pillow."""
    return round(value * SCALE)


def gild(img, xy, text, face, highlight, body, shade):
    """Draws `text` at `xy` (pixels) filled along a metal's ramp: highlight at the letters' tops, body, then shade."""
    d = ImageDraw.Draw(img)
    left, top, right, bottom = d.textbbox(xy, text, font=face)
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).text(xy, text, font=face, fill=255)
    ramp = Image.new("RGB", img.size)
    rd = ImageDraw.Draw(ramp)
    hi, bo, sh = rgb(highlight), rgb(body), rgb(shade)
    for y in range(top, bottom + 1):
        u = (y - top) / max(1, bottom - top)
        rd.line([(left, y), (right, y)], fill=mix(hi, bo, u / 0.45) if u < 0.45 else mix(bo, sh, (u - 0.45) / 0.55 * 0.8))
    img.paste(ramp, (0, 0), mask)


def grid(images, columns=2, gap=0, ground="#121212"):
    """`images` laid out in rows of `columns`, each cell as large as the largest image: a sheet of options."""
    w = max(i.width for i in images)
    h = max(i.height for i in images)
    rows = (len(images) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * w + (columns - 1) * gap, rows * h + (rows - 1) * gap), rgb(ground))
    for n, img in enumerate(images):
        sheet.paste(img, ((n % columns) * (w + gap), (n // columns) * (h + gap)))
    return sheet


def fit(img, width):
    """`img` scaled to `width` pixels wide, keeping its proportions."""
    if img.width == width:
        return img
    return img.resize((width, max(1, round(img.height * width / img.width))), Image.LANCZOS)


def crossfade(images, hold=8, steps=6):
    """Frames that show each image for `hold` frames and fade to the next over `steps`, ending where they began."""
    images = [i.convert("RGB") for i in images]
    frames = []
    for n, img in enumerate(images):
        nxt = images[(n + 1) % len(images)]
        frames += [img] * hold
        frames += [Image.blend(img, nxt, s / (steps + 1)) for s in range(1, steps + 1)]
    return frames


def gif(frames, path, millis=100):
    """Writes `frames` as a looping GIF at `millis` per frame; returns the path. Keep it under about 12 s and 2 MB."""
    frames = [f.convert("RGB").quantize(colors=255, method=Image.Quantize.MEDIANCUT) for f in frames]
    frames[0].save(path, save_all=True, append_images=frames[1:], duration=millis, loop=0, optimize=True, disposal=2)
    return path


def _test():
    with tempfile.TemporaryDirectory() as tmp:
        img, d = canvas(200, 80)
        face = font("/no/such/face.ttf", 18)
        gild(img, (pt(10), pt(10)), "Result", face, "#FFEDAA", "#E7C55A", "#9E8230")
        assert img.size == (400, 160), img.size
        assert img.getbbox() is not None and img.convert("L").getextrema()[1] > 18, "the title drew nothing"
        other, _ = canvas(200, 80, "#2B2D30")
        sheet = grid([img, other], columns=2, gap=4)
        assert sheet.size == (804, 160), sheet.size
        assert fit(sheet, 402).size == (402, 80)
        frames = crossfade([img, other], hold=2, steps=3)
        assert len(frames) == 10, len(frames)
        out = gif(frames, os.path.join(tmp, "a.gif"), millis=50)
        with Image.open(out) as g:
            assert g.n_frames >= 2 and g.info.get("loop") == 0, (g.n_frames, g.info)
        assert rgb("#E7C55A") == (231, 197, 90) and mix((0, 0, 0), (10, 20, 30), 0.5) == (5, 10, 15)
    print("show.py: ok")


if __name__ == "__main__":
    if sys.argv[1:] == ["--test"]:
        _test()
    else:
        print(__doc__)
