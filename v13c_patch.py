# v1.3 patch C — (1) launcher icon: manifest roundIcon; (2) log DISPLAY-time
# display-name mapping so pre-v1.2 lines also render without the name.
# Fails loudly on any anchor mismatch; writes via a temp file + move.
import os, sys

ROOT = os.path.dirname(os.path.abspath(__file__))

def read(p):
    with open(os.path.join(ROOT, p), encoding="utf-8") as f:
        return f.read()

def write(p, text):
    tmp = os.path.join(ROOT, p + ".tmp13c")
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

# ---------- (1) manifest roundIcon ----------
rep("AndroidManifest.xml",
    '    <application android:label="Openvpn" android:icon="@mipmap/ic_launcher"',
    '    <application android:label="Openvpn" android:icon="@mipmap/ic_launcher" android:roundIcon="@mipmap/ic_launcher_round"')

# ---------- (2a) display-time mapping helper, next to logDisplayName ----------
rep(A,
    """    private fun logDisplayName(name: String?): String {
        if (name == TunnelBear.SINGLE_NAME) {
            val c = TunnelBear.selectedCountry(this)
            return if (TunnelBear.hasSelectedCountry(this))
                TunnelBear.DISPLAY_NAME + " — " + TunnelBear.displayCountry(c)
            else TunnelBear.DISPLAY_NAME
        }
        return name ?: ""
    }""",
    """    private fun logDisplayName(name: String?): String {
        if (name == TunnelBear.SINGLE_NAME) {
            val c = TunnelBear.selectedCountry(this)
            return if (TunnelBear.hasSelectedCountry(this))
                TunnelBear.DISPLAY_NAME + " — " + TunnelBear.displayCountry(c)
            else TunnelBear.DISPLAY_NAME
        }
        return name ?: ""
    }

    /** v1.3: DISPLAY-time mapping for old log lines. The
     *  v1.2 write-time mapping (logDisplayName) only fixes
     *  NEW lines; lines written by v1.1 still carry the raw
     *  stored name. The stored log is append-only history
     *  and is never rewritten — instead, each line is
     *  mapped when rendered, so old lines read as
     *  «Free OpenVpn config» (no country suffix — the
     *  country then is unknown). Applied in showLogDialog
     *  only; the stored text is untouched. */
    private fun displayLogLine(line: String): String =
        line.replace(TunnelBear.SINGLE_NAME, TunnelBear.DISPLAY_NAME)""")

# ---------- (2b) apply it where the log is rendered ----------
rep(A,
    '        val kept = appLog.lines().filter { it.contains("[freeovpn]") || it.contains("[openvpn]") }',
    '''        val kept = appLog.lines().filter { it.contains("[freeovpn]") || it.contains("[openvpn]") }
            .map { displayLogLine(it) }''')

print("ALL PATCH-C APPLIED")
