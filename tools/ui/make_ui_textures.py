"""Generate the UI kit's textures: the glyph set, the hero fade and the no-art hero plate.

Every output is generated here and committed; rerun this script to change one, never edit a PNG by hand.

    python -I tools/ui/make_ui_textures.py        (from the ziggfreed-common repository root, Pillow 11)

Writes under zc-presentation/src/main/resources/Common/UI/Custom/Common/:

  Glyphs/<Name>.png (20 x 20) and Glyphs/<Name>@2x.png (40 x 40) for Pin, PinFilled, Lock, ChevronRight,
      ChevronDown, Dot, Star and Blank. White on transparent, so a PatchStyle Color tints a glyph to any tone
      (a tint multiplies). Blank is fully transparent: the fallback an AssetImage shows for a missing picture.
  ZigHeroFade.png (962 x 140) and @2x: a vertical ramp of #0a1119 from transparent at the top to 0.85 at the
      bottom, laid over hero art so the season's name reads on any picture.
  ZigHeroPlate.png (962 x 240) and @2x: the hero's plate when a season has no art, #101925 with a faint vignette.
  Kit/HeroGradient.png (4 x 240) and @2x: white, opaque at the top to transparent at the bottom; stretched across
      a hero and tinted by its PatchStyle Color, it lays a season's colour down from the top of the plate.
  Kit/HeroGlow.png (256 x 256) and @2x: a soft white radial glow, opaque at the centre to transparent at the
      edge; tinted the same way, it lights the space behind a hero's picture.

Each image is drawn at twice its output size and downsampled (Lanczos), so edges stay smooth at 1x and 2x.
The output is deterministic: the same script writes the same bytes.
"""
import math
import os
import sys

from PIL import Image, ImageDraw

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
OUT = os.path.join(ROOT, "zc-presentation", "src", "main", "resources", "Common", "UI", "Custom", "Common")

GLYPH_SIZE = 20
SUPERSAMPLE = 2
WHITE = (255, 255, 255, 255)

FADE = (962, 140)
FADE_COLOUR = (0x0A, 0x11, 0x19)
FADE_MAX_ALPHA = 0.85

PLATE = (962, 240)
PLATE_COLOUR = (0x10, 0x19, 0x25)
VIGNETTE = 0.28  # how much darker the corners are than the middle

GRADIENT = (4, 240)
GLOW = 256


def canvas(px):
    """A transparent square drawn at SUPERSAMPLE times the output size."""
    big = px * SUPERSAMPLE
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    return image, ImageDraw.Draw(image), big


def pt(big, x, y):
    """A point given in the unit square, scaled to the canvas."""
    return (x * big, y * big)


def poly(big, points):
    return [pt(big, x, y) for x, y in points]


def stroke(draw, big, points, width):
    """A polyline of the given unit width with round caps and joins."""
    w = max(1, round(width * big))
    scaled = poly(big, points)
    draw.line(scaled, fill=WHITE, width=w, joint="curve")
    r = w / 2
    for x, y in (scaled[0], scaled[-1]):
        draw.ellipse((x - r, y - r, x + r, y + r), fill=WHITE)


def pin(draw, big, filled):
    """A push pin, upright: a cap, a body flaring to a base, and the needle."""
    body = [(0.36, 0.20), (0.64, 0.20), (0.62, 0.46), (0.76, 0.58), (0.24, 0.58), (0.38, 0.46)]
    cap = (0.30, 0.08, 0.70, 0.20)
    if filled:
        draw.rounded_rectangle([*pt(big, cap[0], cap[1]), *pt(big, cap[2], cap[3])], radius=0.04 * big, fill=WHITE)
        draw.polygon(poly(big, body), fill=WHITE)
    else:
        w = 0.075
        draw.rounded_rectangle([*pt(big, cap[0], cap[1]), *pt(big, cap[2], cap[3])], radius=0.04 * big,
                               outline=WHITE, width=max(1, round(w * big)))
        stroke(draw, big, body + [body[0]], w)
    stroke(draw, big, [(0.5, 0.58), (0.5, 0.93)], 0.085)


def lock(draw, big):
    """A padlock: a shackle over a body, the keyhole cut out."""
    w = max(1, round(0.10 * big))
    draw.arc([*pt(big, 0.30, 0.10), *pt(big, 0.70, 0.52)], start=180, end=360, fill=WHITE, width=w)
    draw.line([pt(big, 0.30 + 0.05, 0.31), pt(big, 0.30 + 0.05, 0.48)], fill=WHITE, width=w)
    draw.line([pt(big, 0.70 - 0.05, 0.31), pt(big, 0.70 - 0.05, 0.48)], fill=WHITE, width=w)
    draw.rounded_rectangle([*pt(big, 0.20, 0.44), *pt(big, 0.80, 0.92)], radius=0.08 * big, fill=WHITE)
    hole = (0, 0, 0, 0)
    draw.ellipse([*pt(big, 0.44, 0.56), *pt(big, 0.56, 0.68)], fill=hole)
    draw.rectangle([*pt(big, 0.475, 0.64), *pt(big, 0.525, 0.80)], fill=hole)


def chevron(draw, big, points):
    stroke(draw, big, points, 0.13)


def dot(draw, big):
    draw.ellipse([*pt(big, 0.08, 0.08), *pt(big, 0.92, 0.92)], fill=WHITE)


def star(draw, big):
    """A five-point star, centred a little low so it sits on the line."""
    cx, cy, outer, inner = 0.5, 0.53, 0.46, 0.19
    points = []
    for i in range(10):
        r = outer if i % 2 == 0 else inner
        a = -math.pi / 2 + i * math.pi / 5
        points.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    draw.polygon(poly(big, points), fill=WHITE)


GLYPHS = {
    "Pin": lambda d, b: pin(d, b, filled=False),
    "PinFilled": lambda d, b: pin(d, b, filled=True),
    "Lock": lock,
    "ChevronRight": lambda d, b: chevron(d, b, [(0.38, 0.22), (0.64, 0.50), (0.38, 0.78)]),
    "ChevronDown": lambda d, b: chevron(d, b, [(0.22, 0.38), (0.50, 0.64), (0.78, 0.38)]),
    "Dot": dot,
    "Star": star,
    "Blank": lambda d, b: None,
}


def glyph(name, px):
    image, draw, big = canvas(px)
    GLYPHS[name](draw, big)
    return image.resize((px, px), Image.LANCZOS)


def fade(scale):
    """A vertical alpha ramp of FADE_COLOUR, eased so the top edge never shows."""
    w, h = FADE[0] * scale, FADE[1] * scale
    column = Image.new("RGBA", (1, h))
    for y in range(h):
        t = y / (h - 1)
        eased = t * t * (3 - 2 * t)
        column.putpixel((0, y), (*FADE_COLOUR, round(255 * FADE_MAX_ALPHA * eased)))
    return column.resize((w, h), Image.NEAREST)


def plate(scale):
    """An opaque plate with a soft elliptical vignette (darker towards the corners)."""
    w, h = PLATE[0] * scale, PLATE[1] * scale
    small_w, small_h = 241, 61  # a coarse grid, smoothed up: the vignette has no detail to lose
    grid = Image.new("RGB", (small_w, small_h))
    for y in range(small_h):
        for x in range(small_w):
            dx = (x / (small_w - 1)) * 2 - 1
            dy = (y / (small_h - 1)) * 2 - 1
            d = min(1.0, math.sqrt(dx * dx + dy * dy) / math.sqrt(2))
            k = 1 - VIGNETTE * d * d
            grid.putpixel((x, y), tuple(round(c * k) for c in PLATE_COLOUR))
    return grid.resize((w, h), Image.BICUBIC).convert("RGBA")


def gradient(scale):
    """White, its alpha falling evenly from opaque at the top row to transparent at the bottom row."""
    w, h = GRADIENT[0] * scale, GRADIENT[1] * scale
    column = Image.new("RGBA", (1, h))
    for y in range(h):
        column.putpixel((0, y), (255, 255, 255, round(255 * (1 - y / (h - 1)))))
    return column.resize((w, h), Image.NEAREST)


def glow(scale):
    """White, its alpha a smooth radial falloff: opaque at the centre, transparent at and beyond the edge."""
    px = GLOW * scale
    image = Image.new("RGBA", (px, px))
    centre = (px - 1) / 2
    for y in range(px):
        for x in range(px):
            r = min(1.0, math.hypot(x - centre, y - centre) / centre)
            falloff = 1 - r * r * (3 - 2 * r)
            image.putpixel((x, y), (255, 255, 255, round(255 * falloff)))
    return image


def save(image, *parts):
    path = os.path.join(OUT, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    image.save(path, format="PNG", optimize=True)
    return os.path.relpath(path, ROOT)


def main():
    if not os.path.isdir(os.path.join(ROOT, "zc-presentation")):
        sys.exit("run from a ziggfreed-common checkout: no zc-presentation beside " + ROOT)
    written = []
    for name in GLYPHS:
        written.append(save(glyph(name, GLYPH_SIZE), "Glyphs", name + ".png"))
        written.append(save(glyph(name, GLYPH_SIZE * 2), "Glyphs", name + "@2x.png"))
    written.append(save(fade(1), "ZigHeroFade.png"))
    written.append(save(fade(2), "ZigHeroFade@2x.png"))
    written.append(save(plate(1), "ZigHeroPlate.png"))
    written.append(save(plate(2), "ZigHeroPlate@2x.png"))
    written.append(save(gradient(1), "Kit", "HeroGradient.png"))
    written.append(save(gradient(2), "Kit", "HeroGradient@2x.png"))
    written.append(save(glow(1), "Kit", "HeroGlow.png"))
    written.append(save(glow(2), "Kit", "HeroGlow@2x.png"))
    for path in written:
        print(path)


if __name__ == "__main__":
    main()
