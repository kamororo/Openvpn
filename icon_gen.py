# v1.3 icon fix — launcher icon WITHOUT the white frame.
# Root cause: legacy mipmap PNGs only, built from logo_circle.png (256x256,
# transparent corners), so the launcher (MIUI) wraps the icon in a white
# squircle. Fix: (1) full-bleed legacy PNGs on the logo's dark navy (sampled
# as the median colour of the ring just inside the circle's rim — no
# transparency, no white); (2) a proper ADAPTIVE icon (anydpi-v26 XML,
# background = the same navy, foreground = the circular logo filling the
# layer). The in-app header/drawer logos (assets/logo_circle.png) are
# untouched. Re-run this script to regenerate the icons deterministically.
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

# (1) Full-bleed legacy PNGs: navy square, circle pasted to fill the frame.
for dname, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    out = Image.new("RGB", (size, size), navy)
    circ = logo.resize((size, size), Image.LANCZOS)
    out.paste(circ, (0, 0), circ)
    path = os.path.join(ROOT, "res", f"mipmap-{dname}", "ic_launcher.png")
    out.save(path, "PNG")
    print("wrote", path, out.size, out.mode)

# (2a) Adaptive-icon foreground: circular logo, 432x432 (108dp @ xxxhdpi).
fg = logo.resize((432, 432), Image.LANCZOS)
fg_path = os.path.join(ROOT, "res", "mipmap-xxxhdpi", "ic_launcher_foreground.png")
fg.save(fg_path, "PNG")
print("wrote", fg_path, fg.size, fg.mode)

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
