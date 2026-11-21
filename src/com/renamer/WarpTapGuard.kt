package com.renamer

/** 1.56 — PURE-KOTLIN double-tap guard for the WARP row.
 *
 *  1.55 device diagnosis (log 2026-11-20 04:11:47):
 *  "شروع اتصال: WARP (WireGuard)" → "قطع دستی" in the
 *  SAME SECOND. Code-proven, NOT an automatic cut:
 *  every disconnectWg() is either a user tap or a
 *  verify-failure path 15–45s later — never the same
 *  second. What happens is a user double-tap: tap 1
 *  starts warpConnect ("شروع اتصال"); the AWG backend
 *  reports UP in under a second, flipping the row to
 *  active while the real-traffic verdict (verify
 *  thread, up to 45s) is still far away — and the
 *  WARP UP line is suppressed (1.47), so the user
 *  sees a row that looks connected and taps again;
 *  tap 2 lands in warpDisconnect → warpVerifyGen++
 *  kills the verify → "قطع دستی", and the user never
 *  sees the truthful "وصل نشد" verdict. (The
 *  04:12:28 "قطع شد" is the delayed DOWN event from
 *  the 04:11:47 disconnect's async backend teardown.)
 *
 *  The guard: a disconnect tap inside the first
 *  GUARD_MS of a connect is ignored and logged, so
 *  the verify gets its chance to report. After the
 *  window the tap disconnects normally. The guard is
 *  deliberately narrow — it only fires while a
 *  connect is actually in progress (connecting ==
 *  true) AND the connect started less than GUARD_MS
 *  ago — so it can never trap a legitimate
 *  disconnect of a long-running connection.
 *  1.57: the same guard is now wired into the plain
 *  toggle too (WireGuardActivity.kt plain-toggle
 *  handler, fed by plainConnectStartedMs /
 *  plainConnecting recorded in connectTunnel) — the
 *  1.56 device log proved the plain toggle had the
 *  same race (13:16:23 start→cut in the same second).
 *
 *  Pure Kotlin, no Android dependency — JVM-testable. */
object WarpTapGuard {

    /** Ignore window: 3s. The backend reports UP in
     *  under a second, so 3s covers the double-tap
     *  window with margin, while staying far below
     *  the 15–45s verify window — a user who really
     *  wants to cancel waits 3s, not 45s. */
    const val GUARD_MS = 3000L

    /** True when a disconnect tap at nowMs must be
     *  ignored: a connect is in progress and it
     *  started less than GUARD_MS ago. */
    fun shouldIgnoreDisconnect(
        connectStartedMs: Long,
        nowMs: Long,
        connecting: Boolean
    ): Boolean {
        if (!connecting) return false
        if (connectStartedMs <= 0L) return false
        return nowMs - connectStartedMs < GUARD_MS
    }
}
