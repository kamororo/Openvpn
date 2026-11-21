package com.renamer

/**
 * 1.71 (item 9) — the relay feature is RETIRED from the UI (user order
 * 2026-11-21 ~01:14): the free-WireGuard section no longer offers a
 * country/relay selection; its card connects plain free warp (direct
 * MASQUE) only. Everything relay-only lived here — the bundled relay
 * list (BundledRelays.kt, deleted), the chain test, the exit measure,
 * the real-app test, the MTProto probe, dead-marking, the tap budgets,
 * and the per-network selected-country / selected-relay prefs — and it
 * is all deleted with the feature. The plain free-warp path never
 * used any of it (it dials direct MASQUE via AetherBridge +
 * VpnClientService), so nothing else in the app changes. The 1.70
 * code is preserved in the 1.70 backup (src-1.70.tar.gz) if the
 * feature is ever ordered back.
 *
 * What stays, and why: the two MASQUE session-restart budget
 * constants below. They are NOT relay code — AetherBridge's own
 * restartAllowed() / restartDeadlineMs() read them
 * (RelayChain.SESSION_RESTART_BUDGET_MS /
 * RelayChain.SESSION_RESTART_MAX_ATTEMPTS) to bound a mid-connect
 * restart of a plain MASQUE session. Moving them would mean
 * touching AetherBridge, which is outside the 1.71 WG cluster.
 */
object RelayChain {
    /** One MASQUE session restart may run at most 30s. */
    const val SESSION_RESTART_BUDGET_MS = 30_000L

    /** At most 2 MASQUE session restarts per connect attempt. */
    const val SESSION_RESTART_MAX_ATTEMPTS = 2
}
