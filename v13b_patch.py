# v1.3 patch B — COUNTRY DOT BUG (user order, folded into v1.3).
# ROOT CAUSE: in refreshTbCountryRow() the SESSION-probe branch outranked the
# real-dial branch — a session probe that only proves TLS (tls==2, session==0)
# painted ONE green and returned before the dial==1 branch, so the two-dot
# states (2 green, green+red) never appeared even right after a real successful
# connect. recordDial DOES record per-country outcomes (7-day window), so the
# data existed — the paint priority hid it. FIX: the dial outcome now promotes /
# demotes the second dot whenever TLS is healthy; a TLS-only session result
# NEVER hides a recent successful real dial. tbCountryTier gets the same order
# so the sort agrees with the dots. Probe semantics and VPN gate untouched.
# Fails loudly on any anchor mismatch; writes via a temp file + move.
import os, sys

ROOT = os.path.dirname(os.path.abspath(__file__))

def read(p):
    with open(os.path.join(ROOT, p), encoding="utf-8") as f:
        return f.read()

def write(p, text):
    tmp = os.path.join(ROOT, p + ".tmp13b")
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

A = "src/com/renamer/OpenVpnActivity.kt"

# ---------- tier: same order as the paint logic ----------
rep(A,
    """    /** Sort tier for one country, from the SESSION probe when
     *  one exists (2nd green = AUTH + PUSH_REPLY with an IP),
     *  else the probe cache + last 7 days of REAL dials:
     *  0 = TLS green + session proven, 1 = TLS green, session
     *  untested, 2 = TLS green + session failed, 3 = TLS not green.
     *  Alphabetical inside a tier. */
    private fun tbCountryTier(c: String): Int {
        val host = tbCountryHosts.getOrPut(c) { TunnelBear.remoteHostForCountry(this, c) ?: "" }
        val session = if (host.isBlank()) null else TunnelBear.cachedSession(this, host)
        if (session != null) {
            if (session.tls != 2) return 3
            return when (session.session) {
                1 -> 0
                -1 -> 2
                else -> 1
            }
        }
        val probe = if (host.isBlank()) null else TunnelBear.cachedProbe(this, host)
        if (probe != 2) return 3
        return when (TunnelBear.dialOutcome(this, c)) {
            1 -> 0
            -1 -> 2
            else -> 1
        }
    }""",
    """    /** Sort tier for one country — SAME order as
     *  refreshTbCountryRow, so the sort agrees with the
     *  dots: 0 = full session proven OR TLS healthy +
     *  recent successful real dial, 1 = TLS healthy,
     *  2 = TLS healthy but session/dial failed,
     *  3 = TLS not healthy. A TLS-only session result
     *  never hides a recent successful real dial.
     *  Alphabetical inside a tier. */
    private fun tbCountryTier(c: String): Int {
        val host = tbCountryHosts.getOrPut(c) { TunnelBear.remoteHostForCountry(this, c) ?: "" }
        val session = if (host.isBlank()) null else TunnelBear.cachedSession(this, host)
        val probe = if (host.isBlank()) null else TunnelBear.cachedProbe(this, host)
        val dial = TunnelBear.dialOutcome(this, c)
        val tlsHealthy = (session != null && session.tls == 2) || probe == 2
        return when {
            session != null && session.tls == 2 && session.session == 1 -> 0
            tlsHealthy && dial == 1 -> 0
            session != null && session.tls == 2 && session.session == -1 -> 2
            tlsHealthy && dial == -1 -> 2
            tlsHealthy -> 1
            else -> 3
        }
    }""")

# ---------- paint: dial outcome promotes/demotes the second dot ----------
rep(A,
    """    /** Paints one row's circles IN PLACE from the SESSION
     *  probe when one exists (never re-sorts the open list).
     *  Green = server TLS-healthy, second green = AUTH accepted
     *  + PUSH_REPLY with an assigned IP, red = probe not
     *  TLS-healthy, or green + red = healthy now but the session
     *  failed; grey hollow = still testing / unknown. */""",
    """    /** Paints one row's circles IN PLACE (never re-sorts
     *  the open list). Green = server TLS-healthy; second
     *  green = full session proven (AUTH + PUSH_REPLY with
     *  an assigned IP) OR a recent successful REAL DIAL;
     *  green + red = TLS healthy but the session failed
     *  OR a recent real dial failed; red = TLS not healthy;
     *  grey hollow = still testing / unknown. A TLS-only
     *  session result NEVER hides a recent successful
     *  real dial — the old order returned one green on
     *  session=(2,0) before ever reaching the dial branch. */""")

rep(A,
    """        when {
            session != null && session.tls == 2 && session.session == 1 -> { dot(green, false); dot(green, false) }
            session != null && session.tls == 2 && session.session == 0 -> dot(green, false)
            session != null && session.tls == 2 && session.session == -1 -> { dot(green, false); dot(red, false) }
            session != null -> dot(red, false)
            probe == 2 && dial == 1 -> { dot(green, false); dot(green, false) }
            probe == 2 && dial == 0 -> dot(green, false)
            probe == 2 && dial == -1 -> { dot(green, false); dot(red, false) }
            probe == null -> dot(null, true)
            else -> dot(red, false)
        }""",
    """        // v1.3: the dial outcome promotes/demotes the
        // second dot whenever TLS is healthy. Order matters:
        // a full session proof wins first; a TLS-only session
        // result never hides a recent successful real dial.
        val tlsHealthy = (session != null && session.tls == 2) || probe == 2
        when {
            session != null && session.tls == 2 && session.session == 1 -> { dot(green, false); dot(green, false) }
            tlsHealthy && dial == 1 -> { dot(green, false); dot(green, false) }
            session != null && session.tls == 2 && session.session == -1 -> { dot(green, false); dot(red, false) }
            tlsHealthy && dial == -1 -> { dot(green, false); dot(red, false) }
            tlsHealthy -> dot(green, false)
            session != null || probe != null -> dot(red, false)
            else -> dot(null, true)
        }""")

print("ALL DOT-FIX PATCHES APPLIED")
