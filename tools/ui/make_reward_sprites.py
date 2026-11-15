"""Generate the reward chest's sprite sheets: the sheen a rare reward card wears and the flash behind an opening chest.

Every output is generated here and committed; rerun this script to change one, never edit a PNG by hand.

    python -I tools/ui/make_reward_sprites.py        (from the ziggfreed-common repository root, standard library only)

Writes under zc-presentation/src/main/resources/Common/UI/Custom/Common/:

  Kit/RewardShine.png (192 x 160) and @2x (384 x 320): 30 frames of 32 x 32 (64 x 64 at 2x), six a row, the sheet
      Pages/ZigRewardCard.ui's #Shine and #PlainShine play. A soft white band sweeps diagonally across a rounded square
      in frames 0 to 11; frames 12 to 29 are empty, the pause before the next sweep. The Sprite loops it (no
      RepeatCount) at 20 frames a second: a 0.6 s sweep every 1.5 s.
  Kit/RewardBurst.png (400 x 240) and @2x (800 x 480): 13 frames of 80 x 80 (160 x 160 at 2x), five a row, the sheet
      Pages/ZigRewardChestPage.ui's #OpenBurst plays behind the chest's 48px picture. A gold halo flares around the
      picture, a ring and four rays spread out and fade; frame 12 is empty, so the Sprite, which plays it once
      (RepeatCount 1, KeepLastFrame true), ends showing nothing.

A Sprite's Frame is written in 1x pixels and the client scales it for the @2x sheet, so both sheets keep one layout.
Each pixel is the mean of a 3 x 3 supersample, its colour stepped in eights and its alpha in fours (a glow reads the
same and the file is far smaller), and each row is PNG-filtered with Sub. The output is deterministic: the same script
writes the same bytes.
"""
import math
import os
import struct
import sys
import zlib

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
OUT = os.path.join(ROOT, "zc-presentation", "src", "main", "resources", "Common", "UI", "Custom", "Common")

SUPERSAMPLE = 3
COLOUR_STEP = 8
ALPHA_STEP = 4


def png(path, width, height, pixels):
    """Write `pixels` (RGBA rows, 8 bits each) as a PNG, every row filtered with Sub (type 1)."""
    raw = bytearray()
    stride = width * 4
    for y in range(height):
        row = pixels[y * stride:(y + 1) * stride]
        raw.append(1)
        prev = [0, 0, 0, 0]
        for x in range(width):
            for c in range(4):
                v = row[x * 4 + c]
                raw.append((v - prev[c]) & 0xFF)
                prev[c] = v

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    out = b"\x89PNG\r\n\x1a\n"
    out += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    out += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    out += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(out)


def clamp01(v):
    return 0.0 if v < 0.0 else 1.0 if v > 1.0 else v


def smooth(edge0, edge1, x):
    t = clamp01((x - edge0) / (edge1 - edge0))
    return t * t * (3.0 - 2.0 * t)


def quantize(value, step):
    return min(255, int(round(value / step)) * step)


def sheet(frame, per_row, count, scale, shade):
    """A sheet of `count` frames of `frame` x `frame` (1x) at `scale`; shade(i, u, v) -> (r, g, b, a) in 0..1, with
    u, v a sample point in the frame's unit square."""
    size = frame * scale
    rows = (count + per_row - 1) // per_row
    width, height = per_row * size, rows * size
    pixels = bytearray(width * height * 4)
    sub = SUPERSAMPLE
    for i in range(count):
        ox, oy = (i % per_row) * size, (i // per_row) * size
        for py in range(size):
            for px in range(size):
                ar = ag = ab = aa = 0.0
                for sy in range(sub):
                    for sx in range(sub):
                        u = (px + (sx + 0.5) / sub) / size
                        v = (py + (sy + 0.5) / sub) / size
                        r, g, b, a = shade(i, u, v)
                        ar += r * a
                        ag += g * a
                        ab += b * a
                        aa += a
                a = aa / (sub * sub)
                if a <= 0.0:
                    continue
                alpha = quantize(clamp01(a) * 255, ALPHA_STEP)
                if alpha == 0:
                    continue
                at = ((oy + py) * width + ox + px) * 4
                pixels[at] = quantize(clamp01(ar / aa) * 255, COLOUR_STEP)
                pixels[at + 1] = quantize(clamp01(ag / aa) * 255, COLOUR_STEP)
                pixels[at + 2] = quantize(clamp01(ab / aa) * 255, COLOUR_STEP)
                pixels[at + 3] = alpha
    return width, height, pixels


# ---- the sheen: a diagonal band across a rounded square ----

SHINE_FRAMES = 30
SHINE_SWEEP = 12
SHINE_PEAK = 0.55
SHINE_HALF_WIDTH = 0.16
SHINE_INSET = 0.06
SHINE_RADIUS = 0.16


def rounded_square(u, v, inset, radius):
    """1 inside a rounded square inset from the frame's edge, falling off over a hair at its edge."""
    half = 0.5 - inset
    dx = max(abs(u - 0.5) - (half - radius), 0.0)
    dy = max(abs(v - 0.5) - (half - radius), 0.0)
    outside = math.hypot(dx, dy) - radius
    return 1.0 - smooth(-0.015, 0.015, outside)


def shine(i, u, v):
    if i >= SHINE_SWEEP:
        return 0.0, 0.0, 0.0, 0.0
    t = i / (SHINE_SWEEP - 1)
    centre = -0.25 + 1.5 * t
    along = (u + (1.0 - v)) / 2.0
    d = abs(along - centre) / SHINE_HALF_WIDTH
    band = max(0.0, 1.0 - d)
    a = SHINE_PEAK * band * band * rounded_square(u, v, SHINE_INSET, SHINE_RADIUS)
    return 1.0, 0.98, 0.92, a


# ---- the flash: a gold halo, a spreading ring and four rays ----

BURST_FRAMES = 13
GOLD = (0.98, 0.78, 0.36)
WHITE = (1.0, 0.97, 0.88)


def ease_out(t):
    return 1.0 - (1.0 - t) ** 3


def burst(i, u, v):
    """The chest's picture covers the middle 0.6 of the frame (48 of 80), so everything that shows lies outside it."""
    last = BURST_FRAMES - 1
    if i >= last:
        return 0.0, 0.0, 0.0, 0.0
    t = i / (last - 1)
    x, y = (u - 0.5) * 2.0, (v - 0.5) * 2.0
    d = math.hypot(x, y)
    if d >= 1.0:
        return 0.0, 0.0, 0.0, 0.0
    fade = (1.0 - t) ** 1.4
    # The halo: a soft glow hugging the picture's edge that flares in the first frames and dims.
    bloom = math.sin(math.pi * min(1.0, t * 1.5 + 0.2)) if t < 0.53 else 0.0
    halo = 0.8 * bloom * math.exp(-(((d - 0.6) / 0.2) ** 2))
    # The ring: spreading from the picture's edge to the frame's, thinning as it goes.
    radius = 0.58 + 0.36 * ease_out(t)
    thick = 0.09 * (1.0 - 0.5 * t)
    ring = 0.85 * fade * max(0.0, 1.0 - abs(d - radius) / thick) ** 2
    # The rays: four spokes turning a little, reaching further as the flash spreads.
    angle = math.atan2(y, x) + 0.3 * t
    spoke = max(0.0, math.cos(4.0 * angle)) ** 10
    reach = 0.7 + 0.3 * ease_out(min(1.0, t * 1.4))
    rays = 0.7 * fade * spoke * smooth(0.5, 0.62, d) * (1.0 - smooth(reach - 0.15, reach, d))
    a = 1.0 - (1.0 - halo) * (1.0 - ring) * (1.0 - rays)
    if a <= 0.001:
        return 0.0, 0.0, 0.0, 0.0
    # The halo whitens towards its peak; the ring and the rays stay gold.
    white = 0.55 * clamp01(halo / max(a, 1e-6))
    r = GOLD[0] + (WHITE[0] - GOLD[0]) * white
    g = GOLD[1] + (WHITE[1] - GOLD[1]) * white
    b = GOLD[2] + (WHITE[2] - GOLD[2]) * white
    edge = 1.0 - smooth(0.92, 1.0, d)
    return r, g, b, clamp01(a) * edge


SHEETS = (
    # name, 1x frame size, frames a row, frame count, what draws a frame
    ("RewardShine", 32, 6, SHINE_FRAMES, shine),
    ("RewardBurst", 80, 5, BURST_FRAMES, burst),
)


def main():
    if not os.path.isdir(os.path.join(ROOT, "zc-presentation")):
        sys.exit("run from a ziggfreed-common checkout: no zc-presentation beside " + ROOT)
    for name, frame, per_row, count, shade in SHEETS:
        for scale, suffix in ((1, ""), (2, "@2x")):
            width, height, pixels = sheet(frame, per_row, count, scale, shade)
            path = os.path.join(OUT, "Kit", name + suffix + ".png")
            png(path, width, height, pixels)
            print(os.path.relpath(path, ROOT))


if __name__ == "__main__":
    main()
