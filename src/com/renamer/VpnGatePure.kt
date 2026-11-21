package com.renamer

/** 1.76 — PURE free-OpenVPN-account VPN gate: NO Android
 *  imports, so the JVM tests compile and run it standalone
 *  (TestMain176A). The gate answers ONE question — "is a VPN
 *  up right now, so the TunnelBear signup sites are reachable
 *  through it?" — from LIVE state only.
 *
 *  Why this file exists: OpenVpnActivity.anyVpnUp() used to
 *  read ONLY OvpnEngine.isConnected/isConnecting plus a
 *  system-level foreign-VPN observation (TRANSPORT_VPN bit +
 *  tun/ppp/tap interface + link address). It never asked the
 *  app's own tunnel state, so a live free-WARP session read
 *  as "no VPN" and the account button showed «برای ساخت اکانت
 *  رایگان فیلترشکن خود را روشن کنید» with warp connected:
 *    - Warp WG rung: the WireGuard/Amnezia backend runs the
 *      tunnel directly (no VpnClientService, no OvpnEngine)
 *      under an interface named after the tunnel
 *      ("WARP (WireGuard)" -> sanitised "WARP__WireGuard_",
 *      WireGuardActivity SimpleTunnel/AwgSimpleTunnel) —
 *      never tun/ppp/tap, so foreignVpnUp() is false.
 *    - Warp MASQUE rungs (aether/gool/H3/H2): VpnClientService
 *      IS running with sessionTag "warp" and launchCore() DOES
 *      establish a TUN — but the app is disallowed from its
 *      own VPN (launchCore addDisallowedApplication(packageName)),
 *      so the app's own activeNetwork observation is the
 *      underlying Wi-Fi/mobile network, not the VPN network;
 *      the TRANSPORT_VPN bit the gate waited on is false from
 *      inside the app. The gate never consulted
 *      VpnClientService.isRunning, so that single failed
 *      observation was the whole verdict.
 *  The fix is to ask the app's own live flags first — the
 *  same three QuickConnect.isLive() reads
 *  (VpnClientService.isRunning || WgDirect.isLive ||
 *  OvpnEngine.isConnected) — plus OvpnEngine.isConnecting
 *  (which QuickConnect.isLive omits and the old gate had)
 *  and the unchanged foreign-VPN observation.
 *
 *  LIVE state only, by design: this function takes NO
 *  record, no activeId, no last-connection pref, no
 *  SharedPreferences of any kind. A finished warp session
 *  (service down, WG tunnel down) with a leftover
 *  WarpFree record / activeId / last-connection entry
 *  MUST still block — and it does, because there is no
 *  input through which a record could open the gate.
 *  Callers must never pass a persisted value in here.
 *
 *  sessionTag is carried (so tests can name the session)
 *  but NEVER narrows the verdict: ANY running service
 *  session — v2ray, warp, emergency, smart, chain, hydra,
 *  or a tag-less one — is a live VPN for this gate.
 *  A service that is starting (isRunning true, verify not
 *  yet passed) counts as up, exactly as QuickConnect.isLive
 *  and the old isConnecting branch already treated it. */
object VpnGatePure {

    /** Allow the free-account creation iff ANY live input
     *  is true. All-false = block (the caller shows the
     *  «turn on your VPN» toast and returns). */
    fun isVpnUp(
        serviceRunning: Boolean,
        @Suppress("UNUSED_PARAMETER") sessionTag: String?,
        wgLive: Boolean,
        ovpnConnected: Boolean,
        ovpnConnecting: Boolean,
        foreignUp: Boolean
    ): Boolean {
        // sessionTag is intentionally not consulted: a
        // running service is up whatever session it carries.
        return serviceRunning || wgLive || ovpnConnected || ovpnConnecting || foreignUp
    }
}
