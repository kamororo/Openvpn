package com.renamer

import android.content.Context
import java.io.File

/** 1.55 — session facade over Asha's aether 2.4.0 engine.
 *  The engine is driven ONLY through the CLI session loop
 *  now (AetherCli — an exact copy of Asha's
 *  AetherCliProcessManager). The FFI tunnel_start path
 *  (1.50–1.54) is GONE: one tunnel_start per attempt, no
 *  session, no hunt, no reconnect, was not Asha's
 *  driving and is not what the user ordered. Nothing in
 *  this file loads a .so or calls native code any more.
 *
 *  What this object still owns:
 *  - the identity handoff: ensureIdentityFile writes OUR
 *    registered WARP identity into filesDir/aether/
 *    aether-masque.toml, which the CLI reads via
 *    AETHER_MASQUE_CONFIG (lib.rs masque_config_path).
 *    The file is written BEFORE every CLI start, so the
 *    engine NEVER provisions over the network (1.50:
 *    every probe died in aether's own 90s provisioning
 *    timeout). Same file, same atomic writeIfChanged,
 *    same 0600, same identity lock as 1.51–1.54.
 *  - the session contract the callers already use:
 *    startSessionTunnel(ctx, gatewayIp, log): Boolean
 *    (SOCKS on 127.0.0.1:1819), stopSessionTunnel(),
 *    lastSessionError / lastTunnelError /
 *    lastIdentityError, SESSION_SOCKS_PORT / PROBE_SOCKS_BASE.
 *  - the [warp] log plumbing: logListener (set by
 *    WarpFree) + the recent-logs ring (diagnostics).
 *
 *  Source: CluvexStudio/Aether v2.4.0 (tag v2.4.0,
 *  commit d58f9d5 — the rebuilt binary is checked out
 *  AT the tag), AGPL-3.0 (assets/agpl-3.0.txt).
 *  The MASQUE SNI default in 2.4.0 is
 *  "www.cloudflare.com" (consts.rs DEFAULT_MASQUE_SNI)
 *  — the single most important MASQUE change vs the
 *  2.3.0 core, which dialled with SNI
 *  consumer-masque.cloudflareclient.com and got no
 *  answer without a VPN on this network.
 *
 *  Threading: startSessionTunnel blocks (up to 40s per
 *  attempt). Call ONLY from worker threads — never the
 *  UI thread. */
object AetherBridge {
    /** SOCKS port of the long-lived session tunnel (Asha's convention). */
    const val SESSION_SOCKS_PORT = 1819
    /** Probe tunnels listen on their own ports: 1820 + probe index. */
    const val PROBE_SOCKS_BASE = 1820
    const val AETHER_VERSION = "2.4.0"

    /** 1.52 — the identity handoff is guarded by ONE lock:
     *  ensureIdentityFile runs exactly once, before any
     *  CLI start, and never while a probe is running. */
    private val identityLock = Any()

    /** 1.52 — the clean IP the engine's own API calls
     *  go to (AETHER_API_ADDRESS in the CLI child env).
     *  Default is Asha's default (162.159.192.1);
     *  WarpFree replaces it with the freshest scanned
     *  clean IP each fetch. */
    @Volatile var enrollIp: String = "162.159.192.1"
        private set

    fun setEnrollAddress(ip: String) {
        val t = ip.trim()
        if (t.isNotEmpty()) enrollIp = t
    }

    /** 1.60 — the SNI the enroll probe VERIFIED for
     *  the enroll IP (WarpReg.REG_HOST, or
     *  WarpReg.DEVICES_HOST when only that answered).
     *  ensureIdentityFile passes it to
     *  WarpIdentity.ensure: the 1.59 second
     *  registration paid the slow legs (REG_HOST
     *  fails → direct 15s timeout → devices) on
     *  every API call because it used the default
     *  SNI instead of the verified one. */
    @Volatile var enrollSni: String = WarpReg.REG_HOST
        private set

    fun setEnrollSni(sni: String) {
        val t = sni.trim()
        if (t.isNotEmpty()) enrollSni = t
    }

    /** Last native/CLI log lines (ring, newest last) for diagnostics. */
    private val logRing = java.util.Collections.synchronizedList(mutableListOf<String>())
    /** Optional live listener (the [warp] log writer). Set by WarpFree. */
    @Volatile var logListener: ((String) -> Unit)? = null

    /** A CLI log line for the diagnostics ring ONLY
     *  (the [warp] writer is fed by the callers' own
     *  emit lambdas — never double-forward here). */
    fun noteCliLine(line: String) {
        try {
            synchronized(logRing) {
                logRing.add(line)
                while (logRing.size > 200) logRing.removeAt(0)
            }
        } catch (_: Exception) {}
    }

    fun recentLogs(): List<String> = try { logRing.toList() } catch (_: Exception) { emptyList() }

    /** 1.51 — write OUR registered WARP identity into the TOML file
     *  the CLI reads (filesDir/aether/aether-masque.toml).
     *  The identity comes from WarpIdentity.ensure (which registers via
     *  the Kotlin path — direct, then the active tunnel's SOCKS —
     *  when absent, and re-enrols the MASQUE key when it is under 7
     *  days to expiry). Returns false when no usable identity exists
     *  or the TOML cannot be built — the caller must then fail
     *  WITHOUT letting the engine provision. The file is rewritten
     *  only when the bytes would change (writeIfChanged), mode 0600
     *  like aether's own writer. */
    fun ensureIdentityFile(ctx: Context): Boolean {
        synchronized(identityLock) {
            return try {
                // 1.60 — the verified enroll SNI goes
                // along (see enrollSni): a renewal here
                // must not pay the slow default-SNI legs.
                val idn = try { WarpIdentity.ensure(ctx, "main", -1, enrollIp, enrollSni) } catch (_: Exception) { null }
                    ?: return false
                if (idn.masquePrivateKeyB64.isBlank() || idn.masquePublicSpkiB64.isBlank()) return false
                val input = AetherIdentityToml.Input(
                    deviceId = idn.deviceId,
                    accessToken = idn.token,
                    wgPrivateKeyB64 = idn.wgPrivateKeyB64,
                    wgPeerPublicKeyB64 = idn.wgPeerPublicKeyB64,
                    clientIdB64 = idn.clientId,
                    wgPeerEndpointV4 = idn.wgPeerEndpointV4,
                    ipv4 = idn.ipv4,
                    ipv6 = idn.ipv6,
                    masquePrivateKeyB64 = idn.masquePrivateKeyB64,
                    masquePublicSpkiB64 = idn.masquePublicSpkiB64
                )
                val toml = AetherIdentityToml.buildToml(input) ?: return false
                val dir = File(ctx.filesDir, "aether").apply { mkdirs() }
                val dst = File(dir, AetherIdentityToml.MASQUE_FILE_NAME)
                // 1.52 RACE FIX: write ONLY when the bytes
                // would change. A concurrent reader can never
                // see the file deleted or half-written.
                val wrote = AetherRungPlan.writeIfChanged(dst, toml)
                if (wrote) {
                    try {
                        dst.setReadable(false, false)
                        dst.setWritable(false, false)
                        dst.setExecutable(false, false)
                        dst.setReadable(true, true)
                        dst.setWritable(true, true)
                    } catch (_: Exception) {}
                }
                true
            } catch (_: Exception) { false }
        }
    }

    /** 1.58 — write the GOOL (WOM) inner identity
     *  into filesDir/aether/aether-masque-gool.toml,
     *  the sibling the engine derives itself from
     *  AETHER_MASQUE_CONFIG (lib.rs:955-958
     *  gool_identity_path). Same lock, same atomic
     *  writeIfChanged, same 0600 as the masque file.
     *  The identity is the already-registered "gool"
     *  identity (its MASQUE keys come from the same
     *  registration as the main one). Returns false
     *  when no usable gool identity exists — the
     *  caller then lets the engine provision the
     *  inner identity in-tunnel (lib.rs
     *  gool_inner_identity), never over the direct
     *  network. */
    fun ensureGoolIdentityFile(ctx: Context): Boolean {
        synchronized(identityLock) {
            return try {
                val gidn = try { WarpFree.goolIdentity ?: WarpIdentity.load(ctx, "gool") } catch (_: Exception) { null }
                    ?: return false
                if (gidn.masquePrivateKeyB64.isBlank() || gidn.masquePublicSpkiB64.isBlank()) return false
                val input = AetherIdentityToml.Input(
                    deviceId = gidn.deviceId,
                    accessToken = gidn.token,
                    wgPrivateKeyB64 = gidn.wgPrivateKeyB64,
                    wgPeerPublicKeyB64 = gidn.wgPeerPublicKeyB64,
                    clientIdB64 = gidn.clientId,
                    wgPeerEndpointV4 = gidn.wgPeerEndpointV4,
                    ipv4 = gidn.ipv4,
                    ipv6 = gidn.ipv6,
                    masquePrivateKeyB64 = gidn.masquePrivateKeyB64,
                    masquePublicSpkiB64 = gidn.masquePublicSpkiB64
                )
                val toml = AetherIdentityToml.buildToml(input) ?: return false
                val dir = File(ctx.filesDir, "aether").apply { mkdirs() }
                val dst = File(dir, AetherIdentityToml.GOOL_FILE_NAME)
                val wrote = AetherRungPlan.writeIfChanged(dst, toml)
                if (wrote) {
                    try {
                        dst.setReadable(false, false)
                        dst.setWritable(false, false)
                        dst.setExecutable(false, false)
                        dst.setReadable(true, true)
                        dst.setWritable(true, true)
                    } catch (_: Exception) {}
                }
                true
            } catch (_: Exception) { false }
        }
    }

    /** The CLI binary is present and runnable (the
     *  .so is in nativeLibraryDir and executable, or
     *  copyable to filesDir/aether-bin/). False when
     *  the engine cannot start at all. */
    fun cliAvailable(ctx: Context): Boolean {
        return try {
            val libDir = File(ctx.applicationInfo.nativeLibraryDir, AetherCli.BINARY_NAME)
            if (libDir.exists() && libDir.canExecute()) return true
            if (libDir.exists()) return true // copy fallback in AetherCli.binary()
            false
        } catch (_: Exception) { false }
    }

    fun version(): String = AETHER_VERSION

    // ---- the long-lived session tunnel (CLI) ----

    /** 1.54 — the session connect's accumulated failure
     *  (all attempts, with timings), so the caller can
     *  show WHY instead of only the last leg's error. */
    @Volatile var lastSessionError: String? = null
        private set

    @Volatile var lastTunnelError: String? = null
        private set

    @Volatile var lastIdentityError: String? = null
        private set

    /** The gateway the session loop ACTUALLY selected,
     *  when it differs from the requested one (the loop
     *  hunts when the forced peer is dead — the SOCKS
     *  port is the contract, not the requested IP).
     *  Null when the requested gateway was used. Read
     *  right after startSessionTunnel returns true. */
    @Volatile var lastSessionGateway: String? = null
        private set

    /** 1.58 (Part B) — WARM SESSION (instant connect). The
     *  fetch's validated CLI session stays alive as a
     *  standby instead of being torn down, so the
     *  connect attaches to it with only a ~1–2s health
     *  probe instead of a fresh handshake+validation.
     *  ONE warm session at most (the CLI is a single
     *  process — AetherCli.start() stops whatever runs,
     *  so a later probe kills an earlier warm session;
     *  in practice the warm one is the LAST CLI-probed
     *  winner, and the fast path's single probe is the
     *  common case). The holder is a RECORD only — the
     *  process is owned by AetherCli; taking it hands
     *  ownership to the connect, stopping it kills the
     *  process. A warm session is usable ONLY for the
     *  record it was validated for, while fresh (TTL);
     *  the connect still health-probes it with real
     *  traffic and still runs the post-TUN verify — the
     *  gate is never weakened for speed. */
    data class WarmSession(val port: Int, val gatewayIp: String, val carrier: String, val rung: String, val validatedAtMs: Long)
    const val WARM_TTL_MS = 120_000L
    private val warmLock = Any()
    @Volatile private var warmSession: WarmSession? = null

    /** Pure match rule (JVM-testable): the warm session
     *  is usable only for the record it was validated
     *  for, while fresh. */
    fun isWarmUsable(warm: WarmSession?, gatewayIp: String, carrier: String, rung: String, nowMs: Long): Boolean {
        if (warm == null) return false
        if (warm.gatewayIp != gatewayIp.trim()) return false
        if (warm.carrier != carrier.trim().lowercase()) return false
        if (warm.rung != rung) return false
        val age = nowMs - warm.validatedAtMs
        if (age < 0 || age > WARM_TTL_MS) return false
        return true
    }

    /** Hold the fetch's validated CLI session as a warm
     *  standby. A daemon thread stops it after the TTL
     *  if nobody consumes it — a warm session must
     *  never leak past its freshness window. */
    fun holdWarmSession(port: Int, gatewayIp: String, carrier: String, rung: String) {
        val s = WarmSession(port, gatewayIp.trim(), carrier.trim().lowercase(), rung, System.currentTimeMillis())
        synchronized(warmLock) { warmSession = s }
        Thread({
            try { Thread.sleep(WARM_TTL_MS) } catch (_: Exception) {}
            synchronized(warmLock) {
                if (warmSession === s) {
                    warmSession = null
                    try { AetherCli.stop() } catch (_: Exception) {}
                }
            }
        }, "aether-warm-ttl").apply { isDaemon = true }.also { it.start() }
    }

    /** Take the warm session for a connect (consumes
     *  it — the process now belongs to the connect,
     *  and stopSessionTunnel kills it on disconnect).
     *  Returns null when there is no usable warm
     *  session for this record. Does NOT stop the
     *  process. */
    fun takeWarmSession(gatewayIp: String, carrier: String, rung: String): WarmSession? {
        synchronized(warmLock) {
            val s = warmSession
            if (!isWarmUsable(s, gatewayIp, carrier, rung, System.currentTimeMillis())) return null
            warmSession = null
            return s
        }
    }

    /** Drop the warm record WITHOUT stopping the
     *  process (a new CLI session is about to start
     *  anyway — AetherCli.start stops the old process
     *  first). */
    fun clearWarmSession() {
        synchronized(warmLock) { warmSession = null }
    }

    /** Drop the warm record AND stop the CLI process
     *  (disconnect, new fetch, stale session). Safe
     *  any time. */
    fun stopWarmSession() {
        synchronized(warmLock) { warmSession = null }
        AetherCli.stop()
    }

    /** Start the session tunnel (fixed port 1819) via the
     *  CLI session loop and remember the process. Returns
     *  true when the SOCKS port answers. Any previous
     *  session tunnel is stopped first.
     *
     *  1.55 — REWRITTEN on the CLI loop (Asha's scanner
     *  MASQUE surface): the loop quick-verifies the
     *  forced peer, establishes, and reconnects by
     *  itself (hunt with the avoid-set, GatewayStrikes)
     *  for as long as the process runs. H3/:1701 first
     *  per candidate (record gateway, then saved-clean,
     *  max 4), then H2/TCP:443 on the record gateway —
     *  the H3→H2 switch is at APP level (the core has
     *  no in-run carrier fallback in either version).
     *  The H2 CONNECT-IP path returns 400 on this
     *  server, so H2 is tried ONLY after H3 fails on
     *  every candidate. Every attempt (carrier,
     *  gateway, ms, outcome) is logged through [log]
     *  (or the log listener when null) so the next
     *  failure is diagnosable from the [warp] log
     *  alone — including the VPN state at connect
     *  time. Errors accumulate in [lastSessionError].
     *  1.58 — the attempt order comes from the saved
     *  winner's transport ([preferCarrier]): an H2
     *  winner tries H2 directly, NO H3 attempt at
     *  all (the 14:09 device connect wasted ~41s on
     *  H3 first); a legacy/unknown carrier keeps the
     *  H3-first order above. GOOL records ([rung] =
     *  "gool") run --protocol gool with the same
     *  winning-transport-first order. */
    fun startSessionTunnel(
        ctx: Context,
        gatewayIp: String,
        log: ((String) -> Unit)? = null,
        // 1.58 — the transport that won the fetch
        // ("h2" / "h3" / null for legacy records).
        // The winning transport is tried FIRST.
        preferCarrier: String? = null,
        // 1.58 — the record's rung ("aether-masque" /
        // "gool"): GOOL sessions run --protocol gool
        // instead of --protocol masque.
        rung: String = "aether-masque",
        // 1.58 — the inner WireGuard endpoint for
        // GOOL ("<ip>:2408"), null for masque.
        goolPeer: String? = null,
        // 1.59 — cap the attempt list (PART 3: a
        // connect-time IP replacement tries ONE leg —
        // the current network's winning transport on
        // the replacement IP — instead of the whole
        // order, so a replacement costs at most one
        // 20s/40s leg). The default keeps the full
        // order, so every existing caller is unchanged.
        maxAttempts: Int = Int.MAX_VALUE
    ): Boolean {
        stopSessionTunnel()
        lastSessionError = null
        lastTunnelError = null
        lastIdentityError = null
        lastSessionGateway = null
        val emit: (String) -> Unit = { s ->
            try { log?.invoke(s) ?: logListener?.invoke(s) } catch (_: Exception) {}
        }
        val t0 = System.currentTimeMillis()
        val vpnOn = try { WarpIdentity.isAnyVpnOn(ctx) } catch (_: Exception) { false }
        // 1.58 — the attempt order comes from the saved
        // winner's transport (AetherRungPlan): an H2
        // winner tries H2 directly, NO H3 attempt at
        // all; a legacy/unknown carrier keeps the old
        // H3-first order (record gateway + saved-clean
        // gateways on H3, then H2 on the record one).
        val extra = try { WarpFree.loadCleanList(ctx) } catch (_: Exception) { emptyList() }
        val fullOrder = AetherRungPlan.sessionAttemptOrder(rung, preferCarrier, gatewayIp, extra)
        // 1.59 — a replacement attempt runs ONE leg
        // only (see maxAttempts above); the default
        // keeps the full order.
        val order = if (maxAttempts >= fullOrder.size) fullOrder else fullOrder.take(maxAttempts.coerceAtLeast(0))
        val first = order.firstOrNull()
        val firstLabel = if (first == null) "H3"
            else (if (first.protocol == "gool") "GOOL " else "") + first.carrier.uppercase()
        emit("اتصال: دروازه $gatewayIp؛ VPN ${if (vpnOn) "روشن است" else "خاموش است"}؛ اول $firstLabel (تأیید + تونل).")
        // Identity files FIRST (the CLI loads them from
        // disk; never let it provision over the network).
        if (!ensureIdentityFile(ctx)) {
            lastIdentityError = "هویت وارپ برای aether آماده نشد (ثبت‌نام وارپ ناموفق بود یا کلید MASQUE ندارد)"
            lastSessionError = lastIdentityError
            emit("اتصال ناموفق: $lastSessionError.")
            return false
        }
        if (rung == "gool" && !ensureGoolIdentityFile(ctx)) {
            // 1.58 — no gool inner identity file: the
            // engine provisions the inner identity
            // in-tunnel (lib.rs gool_inner_identity),
            // never over the direct network — a
            // warning, not a failure.
            emit("اتصال: فایل هویت داخلی GOOL آماده نشد؛ موتور آن را داخل تونل می‌سازد.")
        }
        val errors = mutableListOf<String>()
        for (attempt in order) {
            val tt0 = System.currentTimeMillis()
            val isGool = attempt.protocol == "gool"
            val port = if (attempt.carrier == "h3") AetherRungPlan.H3_PORT else AetherRungPlan.H2_PORT
            val args = if (isGool) {
                AetherCli.buildGoolArgs(SESSION_SOCKS_PORT, attempt.ip, port, attempt.carrier == "h2", goolPeer)
            } else {
                AetherCli.buildMasqueArgs(SESSION_SOCKS_PORT, attempt.ip, port, attempt.carrier == "h2")
            }
            val ok = AetherCli.start(ctx, args, SESSION_SOCKS_PORT, attempt.waitMs, enrollIp) { line ->
                noteCliLine(line)
                emit(line)
            }
            val tms = System.currentTimeMillis() - tt0
            if (ok) {
                val sel = AetherCli.lastSelectedGatewayIp
                if (sel.isNotBlank() && sel != attempt.ip) {
                    lastSessionGateway = sel
                    emit("اتصال: موتور دروازهٔ دیگری را انتخاب کرد: $sel (به‌جای ${attempt.ip}).")
                }
                val protoLabel = if (isGool) "GOOL " else ""
                emit("اتصال: تونل $protoLabel${attempt.carrier.uppercase()} روی ${sel.ifBlank { attempt.ip }} بالا آمد (${tms}ms)؛ SOCKS $SESSION_SOCKS_PORT.")
                return true
            }
            val e = "${if (isGool) "GOOL " else ""}${attempt.carrier.uppercase()} تونل ${attempt.ip}: ${AetherCli.lastError} (${tms}ms)"
            errors += e
            emit("اتصال: $e.")
        }
        lastTunnelError = AetherCli.lastError
        lastSessionError =
            "اتصال ناموفق (${System.currentTimeMillis() - t0}ms): " + errors.joinToString("; ")
        emit("اتصال: $lastSessionError.")
        return false
    }

    /** Stop the session tunnel, if any (kills the CLI
     *  process). Also drops any warm record — the
     *  process it pointed at is gone. Safe to call
     *  any time. */
    fun stopSessionTunnel() {
        synchronized(warmLock) { warmSession = null }
        AetherCli.stop()
    }

    /** Is a session tunnel process running? */
    val isSessionRunning: Boolean
        get() = AetherCli.isRunning

    // ---- 1.70 (D): the mid-tap restart policy (pure) ----
    // A MASQUE session that dies mid-tap is restarted,
    // bounded twice: each restart gets at most
    // RelayChain.SESSION_RESTART_BUDGET_MS (30s), and it
    // never runs past the tap deadline minus a 5s reserve
    // — so a restart can never push the tap past its
    // budget (the restart runs on the relay thread while a
    // watchdog stops the CLI at this deadline; AetherCli's
    // wait loop notices the dead process within ~500ms).
    // At most RelayChain.SESSION_RESTART_MAX_ATTEMPTS
    // restarts happen in one tap. Both rules are pure so
    // the JVM test can drive them (see
    // relay-fix-tests.txt). The reserve leaves room for at
    // least a chain test after a restart: a restart that
    // would finish inside the reserve is refused, and the
    // tap fails honestly at the session stage instead.

    /** The deadline for one mid-tap restart: the earlier
     *  of (now + the restart cap) and (tap deadline −
     *  reserve). */
    fun restartDeadlineMs(nowMs: Long, tapDeadlineMs: Long, reserveMs: Long = 5_000L): Long =
        minOf(nowMs + RelayChain.SESSION_RESTART_BUDGET_MS, tapDeadlineMs - reserveMs)

    /** May a mid-tap restart start now? Only while restarts
     *  are left AND the restart deadline is still in the
     *  future. */
    fun restartAllowed(nowMs: Long, tapDeadlineMs: Long, restartsUsed: Int): Boolean =
        restartsUsed < RelayChain.SESSION_RESTART_MAX_ATTEMPTS &&
            restartDeadlineMs(nowMs, tapDeadlineMs) > nowMs
}
