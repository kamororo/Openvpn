# v1.3 patch — applied by the v1.3 coordinator.
# P1: About channel row — ROOT CAUSE FIX (see the long comment in the new block).
# P2: country menu re-test on popup open (forced fresh sweep) + priority probe on select.
# P3: version bump 1.2/3 -> 1.3/4.
# Fails loudly on any anchor mismatch; writes via a temp file + move.
import os, sys

ROOT = os.path.dirname(os.path.abspath(__file__))

def read(p):
    with open(os.path.join(ROOT, p), encoding="utf-8") as f:
        return f.read()

def write(p, text):
    tmp = os.path.join(ROOT, p + ".tmp13")
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, os.path.join(ROOT, p))

def rep(path, old, new):
    text = read(path)
    n = text.count(old)
    if n != 1:
        print(f"FAIL {path}: anchor occurs {n} times (expected 1): {old[:80]!r}")
        sys.exit(1)
    write(path, text.replace(old, new))
    print(f"ok {path}: replaced 1 anchor")

def rep_range(path, start_anchor, end_anchor, new):
    text = read(path)
    if text.count(start_anchor) != 1:
        print(f"FAIL {path}: start anchor occurs {text.count(start_anchor)} times: {start_anchor[:80]!r}")
        sys.exit(1)
    if text.count(end_anchor) != 1:
        print(f"FAIL {path}: end anchor occurs {text.count(end_anchor)} times: {end_anchor[:80]!r}")
        sys.exit(1)
    s = text.index(start_anchor)
    e = text.index(end_anchor)
    if e <= s:
        print(f"FAIL {path}: end anchor before start anchor")
        sys.exit(1)
    write(path, text[:s] + new + text[e:])
    print(f"ok {path}: replaced range ({e - s} chars)")

A = "src/com/renamer/OpenVpnActivity.kt"

# ---------- P3a: manifest ----------
rep("AndroidManifest.xml",
    '    android:versionCode="3"\n    android:versionName="1.2">',
    '    android:versionCode="4"\n    android:versionName="1.3">')

# ---------- P3b: build.sh ----------
rep("build.sh",
    '# Standalone «Openvpn» app (v1.2, code 3)',
    '# Standalone «Openvpn» app (v1.3, code 4)')
rep("build.sh",
    '    public static final String VERSION_NAME = "1.2";\n    public static final int VERSION_CODE = 3;',
    '    public static final String VERSION_NAME = "1.3";\n    public static final int VERSION_CODE = 4;')

# ---------- P3c: file header comment ----------
rep(A,
    "* Standalone Openvpn app v1.2 — the OpenVPN section of Config",
    "* Standalone Openvpn app v1.3 — the OpenVPN section of Config")

# ---------- P1: About channel row ----------
NEW_ABOUT = """        // v1.3: ROOT CAUSE of the v1.2 invisible row —
        // setSingleLine(true) switches the TextView to
        // horizontal-scrolling mode; with textDirection
        // RTL (fa) on a WRAP_CONTENT-width view inside
        // the RTL card, the single-line layout scrolls
        // the mixed RTL+Latin line out of the visible
        // viewport — the view keeps its measured height
        // (the empty gap in the screenshot) while no
        // glyph draws. The post-layout textSize-mutation
        // loop compounded it by re-triggering layout
        // after placement. v1.1's two-TextView row (no
        // setSingleLine, no LinkMovementMethod, no post
        // mutation) RENDERED on device. Fix: plain
        // single TextView, NO setSingleLine, NO
        // LinkMovementMethod, NO ClickableSpan, NO
        // post-layout mutation. The ID keeps its link
        // look (underline + bold + accent) via visual
        // spans only; the whole row is clickable and
        // opens the Telegram channel. If the line is
        // too long for a narrow screen it WRAPS to two
        // lines (as v1.1 did) rather than vanishing —
        // VISIBILITY BEATS ONE-LINE.
        val channelId = "@internet_azad_2026"
        val channelLabel = T("کانال تلگرام و ارتباط با ما: ",
            "Telegram channel and contact: ",
            "Канал Telegram и связь с нами: ")
        val channelView = TextView(this).apply {
            textSize = 14f
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.START
            setPadding(0, dp(10), 0, dp(10))
            val span = android.text.SpannableStringBuilder().apply {
                append(channelLabel)
                val start = length
                append(channelId)
                setSpan(android.text.style.UnderlineSpan(), start, length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(OvpnTheme.accent(this@OpenVpnActivity)),
                    start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text = span
            setOnClickListener { openTelegramChannel() }
        }
        card.addView(channelView)
"""

rep_range(A,
    "        // v1.2: label + channel ID on ONE line, the ID",
    "        card.addView(TextView(this).apply {\n            text = L(\"بستن\", \"Close\")",
    NEW_ABOUT)

# ---------- P2a: forced-fresh sweep on menu open + priority probe helper ----------
OLD_SWEEP = """    /** Background re-probe of the stale / unknown countries on
     *  a bounded 8-thread pool — the SESSION probe (TLS + AUTH
     *  + PUSH), not just the TLS flight; each result is cached
     *  (10 min TTL) and its row's circles are updated live.
     *  Plain sockets only — never touches the VPN. */
    private fun startTbCountryProbe() {
        val gen = ++tbCountryProbeGen
        try { tbCountryProbePool?.shutdownNow() } catch (_: Exception) {}
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        tbCountryProbePool = pool
        for (c in tbCountryRowCircles.keys.toList()) {
            val host = tbCountryHosts[c] ?: continue
            if (host.isBlank()) continue
            if (TunnelBear.cachedSession(this, host) != null) continue
            pool.submit {
                try {
                    TunnelBear.probeCountrySession(this@OpenVpnActivity, host)
                    handler.post {
                        if (gen == tbCountryProbeGen && tbCountryMenuOpen) refreshTbCountryRow(c)
                    }
                } catch (_: Exception) {}
            }
        }
    }"""

NEW_SWEEP = """    /** v1.3: fresh re-test of the listed countries on a
     *  bounded 8-thread pool — the SESSION probe (TLS +
     *  AUTH + PUSH), not just the TLS flight; each result
     *  is cached (10 min TTL) and its row's circles are
     *  updated live. Called on EVERY menu open with
     *  forceFresh = true: countries with a fresh cached
     *  verdict are re-probed anyway, so the dots show
     *  whether each config connects on this internet
     *  RIGHT NOW — never just the stale cache. The
     *  10-min cache still governs the background
     *  (page-entry) sweep. Skipped entirely while a VPN
     *  is up (a probe through a live tunnel measures the
     *  tunnel exit, not this line) and when no free
     *  account exists yet (nothing to test — dots stay
     *  grey hollow). Plain sockets only — never touches
     *  the VPN. */
    private fun startTbCountryProbe(forceFresh: Boolean = false) {
        if (TunnelBear.getAccount(this) == null) return
        if (anyVpnUp()) return
        val gen = ++tbCountryProbeGen
        try { tbCountryProbePool?.shutdownNow() } catch (_: Exception) {}
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        tbCountryProbePool = pool
        for (c in tbCountryRowCircles.keys.toList()) {
            val host = tbCountryHosts[c] ?: continue
            if (host.isBlank()) continue
            if (!forceFresh && TunnelBear.cachedSession(this, host) != null) continue
            pool.submit {
                try {
                    TunnelBear.probeCountrySession(this@OpenVpnActivity, host)
                    handler.post {
                        if (gen == tbCountryProbeGen && tbCountryMenuOpen) refreshTbCountryRow(c)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /** v1.3: priority probe for the country the user just
     *  picked in the menu — runs immediately on its own
     *  thread, ahead of the menu sweep, so the fresh
     *  result is in the cache right away (the next menu
     *  open shows its dots instantly). Skipped with no
     *  account or while a VPN is up. */
    private fun probeCountryNow(country: String) {
        if (TunnelBear.getAccount(this) == null) return
        if (anyVpnUp()) return
        val host = tbCountryHosts[country]
            ?: TunnelBear.remoteHostForCountry(this, country) ?: return
        if (host.isBlank()) return
        Thread {
            try {
                TunnelBear.probeCountrySession(this@OpenVpnActivity, host)
                handler.post {
                    if (tbCountryMenuOpen) refreshTbCountryRow(country)
                }
            } catch (_: Exception) {}
        }.start()
    }"""

rep(A, OLD_SWEEP, NEW_SWEEP)

# ---------- P2b: call the forced sweep on popup open ----------
rep(A,
    "        startTbCountryProbe()\n",
    "        startTbCountryProbe(forceFresh = true)\n")

# ---------- P2c: priority probe on country select ----------
rep(A,
    """                try {
                    TunnelBear.saveSelectedCountry(this@OpenVpnActivity, c)
                    TunnelBear.ensureSingleEntry(this@OpenVpnActivity)
                    profiles = OvpnStore.load(this@OpenVpnActivity)
                } catch (_: Exception) {}
                try { countryPopup?.dismiss() } catch (_: Exception) {}""",
    """                try {
                    TunnelBear.saveSelectedCountry(this@OpenVpnActivity, c)
                    TunnelBear.ensureSingleEntry(this@OpenVpnActivity)
                    profiles = OvpnStore.load(this@OpenVpnActivity)
                } catch (_: Exception) {}
                // v1.3: probe the picked country NOW (priority,
                // ahead of the menu sweep) so its dot shows the
                // fresh result right away.
                probeCountryNow(c)
                try { countryPopup?.dismiss() } catch (_: Exception) {}""")

print("ALL PATCHES APPLIED")
