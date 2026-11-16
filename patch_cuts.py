#!/usr/bin/env python3
"""Apply the standalone cuts to the copied OVPN sources.

Every replacement uses an exact anchor from the 3.11 main-app source.
Fails loudly if any anchor is missing, so a silent partial patch is
impossible. Run once from the openvpn-app tree.
"""
import sys

def patch(path, pairs):
    with open(path, encoding="utf-8") as f:
        s = f.read()
    for old, new in pairs:
        if old not in s:
            print(f"ANCHOR MISSING in {path}:\n---\n{old[:300]}\n---")
            sys.exit(1)
        if s.count(old) != 1:
            print(f"ANCHOR NOT UNIQUE ({s.count(old)}x) in {path}:\n{old[:200]}")
            sys.exit(1)
        s = s.replace(old, new)
    with open(path, "w", encoding="utf-8") as f:
        f.write(s)
    print(f"patched {path}")

BASE = "/home/hatch/workspace/openvpn-app/src/com/renamer/"

# ---------- OvpnEngine.kt: cut every chain / v2ray / Smart / QuickConnect link ----------
patch(BASE + "OvpnEngine.kt", [
    # 1. chain fields
    ("""    /** Double-chain plumbing (consume-once port, plus
     *  a flag that lives until disconnect): when a
     *  chain uses OpenVPN as hop2, Xray runs as a
     *  loopback proxy and the OpenVPN client connects
     *  through it. Null/false for normal connects. */
    @Volatile var chainProxyPort: Int? = null
    /** Final http-proxy counts for the last chain
     *  service run, as "lines|args" (counts only,
     *  never values) — read by the central log's
     *  chain-failure detector. Cleared when a new
     *  chain attempt starts. */
    @Volatile var lastChainProxyDiag: String = ""
    @Volatile var chainProxyActive: Boolean = false
""", ""),
    # 2. VpnClientService stop block in connect()
    ("""    fun connect(activity: Activity, profile: OvpnProfile, preserveChainProxy: Boolean = false, callback: (Boolean) -> Unit) {
        // Stop any other VPN first (v2ray / WireGuard),
        // because Android allows only one VPN at a time.
        try {
            if (VpnClientService.isRunning) {
                val stop = Intent(activity, VpnClientService::class.java)
                stop.action = "STOP"
                stop.putExtra("stop_gen", VpnClientService.sessionGen)
                activity.startService(stop)
                VpnClientService.isRunning = false
            }
        } catch (_: Throwable) {}
        // Stop a previous OpenVPN3 session.
        // 2.38: disconnect() clears the chain proxy
        // flags — a chain caller must keep them, or
        // the service reads null and the "chain"
        // connects DIRECT (the bug that made every
        // OVPN chain decorative). Normal callers
        // keep the old clearing behaviour.
        val savedChainPort = chainProxyPort
        val savedChainActive = chainProxyActive
        try { disconnect() } catch (_: Throwable) {}
        if (preserveChainProxy) {
            chainProxyPort = savedChainPort
            chainProxyActive = savedChainActive
        }
""",
     """    fun connect(activity: Activity, profile: OvpnProfile, callback: (Boolean) -> Unit) {
        // Stop a previous OpenVPN3 session.
        try { disconnect() } catch (_: Throwable) {}
"""),
    # 3. QuickConnect.record in startNow()
    ("""            // 2.80: quick-connect card — a hand-started
            // OVPN connect (not a chain proxy hop, which
            // the chain page records itself) is the last method.
            // 2.81: with the profile's name, so the card
            // reconnects to exactly this profile.
            if (!chainProxyActive) try { QuickConnect.record(ctx, "openvpn", profile.name) } catch (_: Exception) {}
""", ""),
    # 4. chain flags + smart_owns_vpn write in disconnect()
    ("""    fun disconnect() {
        chainProxyActive = false
        chainProxyPort = null
        try { appCtx?.getSharedPreferences("renamer_smart", android.content.Context.MODE_PRIVATE)?.edit()?.putBoolean("smart_owns_vpn", false)?.apply() } catch (_: Exception) {}
""",
     """    fun disconnect() {
"""),
])

# ---------- Ovpn3VpnService.kt: cut the chain-proxy path ----------
svc = BASE + "Ovpn3VpnService.kt"
with open(svc, encoding="utf-8") as f:
    s = f.read()

# 1. TB relay condition + comment sentence
old = """                // 3.01: TunnelBear — the core never dials
                // the server directly. Probe the pool's
                // IPs on TCP 7011, start the loopback
                // split relay, and rewrite the config to
                // point at the relay (cipher appended,
                // proto tcp-client). A chain proxy wins
                // over this path: a chained TunnelBear
                // hop connects through hop1 instead.
                if (TunnelBear.isTunnelBear(profile) && OvpnEngine.chainProxyPort == null) {"""
new = """                // 3.01: TunnelBear — the core never dials
                // the server directly. Probe the pool's
                // IPs on TCP 7011, start the loopback
                // split relay, and rewrite the config to
                // point at the relay (cipher appended,
                // proto tcp-client).
                if (TunnelBear.isTunnelBear(profile)) {"""
assert s.count(old) == 1, "svc anchor 1"
s = s.replace(old, new)

# 2. Whole chain-proxy block: from the Double-chain comment to the 3.02 eval-fix comment
start = "                // Double-chain hop2: connect to the\n"
end = "                // 3.02: TunnelBear eval fix"
i = s.find(start)
j = s.find(end)
assert i != -1 and j != -1 and j > i, "svc anchor 2"
s = s[:i] + s[j:]

# 3. chainProxyActive = false in the eval-error path
old = """                    // survive an eval failure into the
                    // next attempt.
                    OvpnEngine.chainProxyActive = false
"""
assert s.count(old) == 1, "svc anchor 3"
s = s.replace(old, """                    // survive an eval failure into the
                    // next attempt.
""")

# 4. disallowed-app chain block in establishTun
old = """            // Double-chain hop2 only: our own app's
            // sockets (including Xray's loopback proxy
            // traffic) must bypass this TUN, or Xray's
            // outbound packets would loop back into
            // the tunnel they came from.
            if (OvpnEngine.chainProxyActive) {
                try { b.addDisallowedApplication(packageName) } catch (_: Exception) {}
            }
"""
assert s.count(old) == 1, "svc anchor 4"
s = s.replace(old, "")

# 5. TUN session name + GUI version strings
old = '            b.setSession("calculator")'
assert s.count(old) == 1, "svc anchor 5"
s = s.replace(old, '            b.setSession("Openvpn")')
old = '                        common.setGuiVersion("ConfigRenamer 3.02")'
assert s.count(old) == 1, "svc anchor 6"
s = s.replace(old, '                        common.setGuiVersion("Openvpn 1.0")')

with open(svc, "w", encoding="utf-8") as f:
    f.write(s)
print(f"patched {svc}")

# ---------- AppLog.kt: delete cfgType() + engineState() (Xray/AdaptiveEngine refs) ----------
log = BASE + "AppLog.kt"
with open(log, encoding="utf-8") as f:
    s = f.read()
start = "    /** Config type label for a link, for precise log\n"
i = s.find(start)
assert i != -1, "applog anchor"
s = s[:i] + "}\n"
with open(log, "w", encoding="utf-8") as f:
    f.write(s)
print(f"patched {log}")
print("ALL CUTS APPLIED")
