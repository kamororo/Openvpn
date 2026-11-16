# v1.4 icon fix — launcher icon sized into the adaptive-icon SAFE ZONE.
# v1.3 removed the white frame but made the circle FILL the whole 108dp
# foreground layer, so the launcher's squircle mask cropped the rim and
# clipped the «اینترنت آزاد» text at the bottom (user's 22:22 screenshot).
# Fix: the circle is now 66dp in diameter, centred in the 108dp layer
# (66/108 = 61% of the layer width) — the whole circle, rim + text, sits
# inside the safe zone under ANY launcher mask. The dark navy background
# (#011C53, sampled from the circle's rim) fills the rest. The SAME
# composition goes into the legacy mipmap PNGs (circle 61% of the frame,
# centred on full-bleed navy, no transparency, no white). The in-app
# header/drawer logos (assets/logo_circle.png) are untouched. Re-run this
# script to regenerate the icons deterministically.
import os, statistics, math
from PIL import Image

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "assets", "logo_circle.png")

logo = Image.open(SRC).convert("RGBA")
w, h = logo.size
cx, cy = w / 2.0, h / 2.0
R = 127.0
px = logo.load()
rs, gs, bs = [], [], []
for y in range(h):
    for x in range(w):
        r, g, b, a = px[x, y]
        if a < 200:
            continue
        d = math.hypot(x - cx, y - cy)
        if 0.90 * R <= d <= 0.985 * R:
            rs.append(r); gs.append(g); bs.append(b)
navy = (int(statistics.median(rs)), int(statistics.median(gs)), int(statistics.median(bs)))
navy_hex = "#%02X%02X%02X" % navy
print("sampled edge navy:", navy_hex, f"from {len(rs)} ring pixels")

# Safe-zone fraction: 66dp circle inside the 108dp adaptive layer.
SAFE = 66.0 / 108.0

# (1) Full-bleed legacy PNGs: navy square, circle 61% of the frame, centred.
for dname, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    out = Image.new("RGB", (size, size), navy)
    d = int(round(size * SAFE))
    circ = logo.resize((d, d), Image.LANCZOS)
    off = (size - d) // 2
    out.paste(circ, (off, off), circ)
    path = os.path.join(ROOT, "res", f"mipmap-{dname}", "ic_launcher.png")
    out.save(path, "PNG")
    print("wrote", path, out.size, out.mode, "circle", d, "px, margin", off, "px")

# (2a) Adaptive-icon foreground: 432x432 (108dp @ xxxhdpi), circle 264px
# (66dp) centred, transparent elsewhere.
FG = 432
fg = Image.new("RGBA", (FG, FG), (0, 0, 0, 0))
d = int(round(FG * SAFE))
circ = logo.resize((d, d), Image.LANCZOS)
off = (FG - d) // 2
fg.paste(circ, (off, off), circ)
fg_path = os.path.join(ROOT, "res", "mipmap-xxxhdpi", "ic_launcher_foreground.png")
fg.save(fg_path, "PNG")
print("wrote", fg_path, fg.size, fg.mode, "circle", d, "px, margin", off, "px")

# (2b) Adaptive-icon XML (background = sampled navy, foreground = the circle).
xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
"""
anydpi = os.path.join(ROOT, "res", "mipmap-anydpi-v26")
os.makedirs(anydpi, exist_ok=True)
for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
    p = os.path.join(anydpi, name)
    with open(p, "w", encoding="utf-8") as f:
        f.write(xml)
    print("wrote", p)

# (2c) Background colour (res/values/colors.xml — build.sh only rewrites
# strings.xml, so this file survives every build).
colors = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">%s</color>
</resources>
""" % navy_hex
with open(os.path.join(ROOT, "res", "values", "colors.xml"), "w", encoding="utf-8") as f:
    f.write(colors)
print("wrote res/values/colors.xml with", navy_hex)
print("ICON GEN DONE")
