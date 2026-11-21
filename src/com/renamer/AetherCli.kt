package com.renamer

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 1.55 — EXACT COPY of Asha's driving of the aether engine.
 *  Source: Asha Scanner, app/src/main/java/com/ahoura/
 *  asha_scanner_ip/core/aether/AetherCliProcessManager.kt
 *  + AetherCliConfig.kt, driving CluvexStudio/Aether v2.4.0
 *  (tag v2.4.0, commit d58f9d5 — the rebuild is checked
 *  out AT the tag; AGPL-3.0, see assets/agpl-3.0.txt).
 *
 *  Asha's scanner runs the engine as a STANDALONE PROCESS
 *  (the same exec-pattern as libxray): the CLI's session
 *  loop (lib.rs run_with → run_masque) owns hunt / verify /
 *  establish / reconnect, and the app only watches the
 *  SOCKS port and the log lines. Our 1.50–1.54 driving
 *  called the FFI tunnel_start ONCE per attempt (no
 *  session, no hunt, no reconnect) — that is what this
 *  file replaces. The FFI path is gone; the engine is
 *  driven ONLY through this process manager now.
 *
 *  What is copied verbatim (adapted only for package,
 *  ports and our identity paths):
 *  - binary from nativeLibraryDir ("libaether_v24.so"),
 *    stdin closed immediately so the CLI never blocks
 *    on an interactive prompt (select_masque_transport
 *    also returns at once because --peer sets
 *    AETHER_PEER and --h2/--h3 sets AETHER_MASQUE_HTTP2);
 *  - env HOME / TMPDIR / AETHER_CONFIG /
 *    AETHER_MASQUE_CONFIG / AETHER_WG_CONFIG / SSL_CERT_DIR;
 *  - args: --bind 127.0.0.1:<port> --protocol masque
 *    --log-level info [--peer <ip>:<port>] [--masque-sni
 *    www.cloudflare.com] [--h2], plus "-4" when no
 *    IP-version flag (1.56: exactly Asha's scanner
 *    invocation — see buildMasqueArgs);
 *  - wait up to 35s for the SOCKS port to actually
 *    SERVE (a SOCKS5 handshake + CONNECT through it —
 *    a bare TCP connect is NOT enough: the CLI binds
 *    its listener at startup, before any tunnel
 *    exists); on exit, fail with the last 12 output
 *    lines.
 *  - the "selected MASQUE gateway <ip>:<port>" log
 *    matcher (2.4.0 public line: "[+] selected MASQUE
 *    gateway <ip>:<port> (rtt …)").
 *
 *  What differs from Asha, and why:
 *  - SOCKS ports are ours (session 1819, probes 1820+)
 *    because the TUN→SOCKS Xray config already points
 *    at 1819 (VpnConfigBuilder.buildAetherTun).
 *  - identity paths are ours (filesDir/aether/…): the
 *    CLI reads the SAME aether-masque.toml that
 *    AetherBridge.ensureIdentityFile writes, via
 *    AETHER_MASQUE_CONFIG (lib.rs masque_config_path
 *    uses the env var directly when set).
 *  - the Asha env block (startup/validate secs,
 *    fragments, camo, large windows, socket tuning,
 *    speed pick, QUIC tuned) is set in the CHILD env,
 *    not process-globally — the FFI path needed it
 *    global, the CLI child does not.
 *  - AETHER_API_ADDRESS=<enrollIp>:443 is the safety
 *    net for the engine's own API calls (2.4.0 reads
 *    AETHER_API_ADDRESS, account.rs:441) — our Kotlin
 *    registration already ran, so the engine should
 *    never call the API; if it ever does, it goes to
 *    a clean IP, never to DNS of the API hostname.
 *  - binary fallback: if the .so in nativeLibraryDir
 *    is not executable on this device, it is copied to
 *    filesDir/aether-bin/ and chmod +x there (Asha
 *    fails in that case; the copy keeps the same
 *    binary and the same behaviour).
 *
 *  CORRECTION (research report §3.10): there is NO
 *  automatic H3→H2 fallback inside the core in either
 *  version — the carrier is fixed per run by the --h2/
 *  --h3 flag. The ladder (H3 first, then H2) lives in
 *  the Kotlin callers, exactly like Asha's app level.
 *
 *  Threading: start() blocks (up to socksWaitMs).
 *  Call ONLY from worker threads — never the UI thread. */
object AetherCli {

    private const val TAG = "AetherCli"
    const val BINARY_NAME = "libaether_v24.so"
    /** The engine version this binary was built from. */
    const val CLI_VERSION = "2.4.0"
    /** Asha's SOCKS wait (AetherCliProcessManager). */
    const val SOCKS_WAIT_MS = 35_000L
    /** SOCKS wait for a probe attempt (the probe's own
     *  real-traffic check follows, so a shorter wait
     *  keeps the round-robin moving). */
    const val PROBE_SOCKS_WAIT_MS = 20_000L
    /** SOCKS wait for a GOOL probe attempt (1.61): the
     *  engine's inner WireGuard validation takes 15s
     *  per attempt and retries once internally, so the
     *  20s probe wait expired mid-second-attempt on
     *  device ("verify timeout" at 15s, attempt 2
     *  started, our wait gone). 45s lets the second
     *  inner attempt finish; the GOOL rung deadline
     *  still bounds the rung (20% of Stage B). */
    const val GOOL_PROBE_SOCKS_WAIT_MS = 45_000L

    private val running = AtomicBoolean(false)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var logThread: Thread? = null

    @Volatile
    var lastError: String = ""
        private set

    /** The gateway the session loop actually selected,
     *  "ip:port" — from the engine's own log line
     *  "[+] selected MASQUE gateway <ip>:<port> …".
     *  Empty when the loop has not selected one yet
     *  (e.g. still hunting). Cleared on every start(). */
    @Volatile
    var lastSelectedEndpoint: String = ""
        private set

    /** 1.61 — a gateway answering "connect-ip status:
     *  400" refuses MASQUE outright, yet the engine's
     *  session loop retries the SAME dead gateway ~8
     *  times (~2.5s each, ~25s total) before start()
     *  gives up — the 1.60 run covered only 7 of 16
     *  gateways before the deadline. The log thread
     *  sets this flag on the first 400 line; start()'s
     *  wait loop aborts early on it, so a 400 gateway
     *  costs ~2–3s. Reset on every start(), like
     *  lastError. */
    @Volatile
    var sawConnectIp400: Boolean = false
        private set

    /** 1.61 — the 400 detector, pure (JVM-testable):
     *  true only for the engine's own "connect-ip
     *  status: 400" line. A 200/204 line, a scan line
     *  or any other line is not a 400. */
    internal fun isConnectIp400(line: String): Boolean =
        line.contains("connect-ip status: 400")

    /** IP part of [lastSelectedEndpoint], or "". */
    val lastSelectedGatewayIp: String
        get() = lastSelectedEndpoint.substringBefore(":").trim()

    /** Last log lines the CLI printed — the reason it
     *  refused a flag/config, or died. */
    private val recentOutput = ArrayDeque<String>()

    private val masqueGatewayRegex = Regex("""selected MASQUE gateway (\S+)""")

    /** The gateway the engine selected, from one log
     *  line ("[+] selected MASQUE gateway <ip>:<port>
     *  (rtt …)"), or null. Internal for the JVM test. */
    internal fun parseSelectedGateway(line: String): String? =
        masqueGatewayRegex.find(line)?.groupValues?.getOrNull(1)

    private val goolReadyRegex = Regex("""gool ready: masque (\S+) carries wireguard""")

    /** 1.58 — the outer gateway of a GOOL (WOM)
     *  tunnel, from the engine's own line ("[+]
     *  gool ready: masque <ip>:<port> carries
     *  wireguard <inner>"), or null. Internal for
     *  the JVM test. */
    internal fun parseGoolReady(line: String): String? =
        goolReadyRegex.find(line)?.groupValues?.getOrNull(1)

    /** 1.59 — the edge the engine is dialling, from
     *  its own line ("[+] using cloudflare edge
     *  <ip>:<port>"). In a --scan run this is the
     *  gateway the hunt is currently trying; the
     *  "selected MASQUE gateway" line (above) is the
     *  final selection and wins over it. Cleared on
     *  every start(). */
    @Volatile
    var lastScanEdge: String = ""
        private set

    private val scanEdgeRegex = Regex("""using cloudflare edge (\S+)""")

    /** The edge IP from one log line, or null.
     *  Internal for the JVM test. */
    internal fun parseScanEdge(line: String): String? =
        scanEdgeRegex.find(line)?.groupValues?.getOrNull(1)?.substringBefore(":")?.trim()

    private fun noteOutput(line: String) {
        synchronized(recentOutput) {
            recentOutput.addLast(line)
            while (recentOutput.size > 12) recentOutput.removeFirst()
        }
    }

    private fun recentOutputText(): String =
        synchronized(recentOutput) { recentOutput.joinToString(" | ").take(400) }

    val isRunning: Boolean
        get() = running.get() && process?.isAlive == true

    /** The CLI binary: nativeLibraryDir first (Asha),
     *  filesDir/aether-bin/ copy as fallback when the
     *  extracted .so is not executable. Returns null
     *  when neither works, with [lastError] set. */
    private fun binary(ctx: Context): File? {
        val libDir = File(ctx.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (libDir.exists() && libDir.canExecute()) return libDir
        if (libDir.exists()) {
            // Not executable where the installer put it —
            // copy to our own dir and chmod +x (same bytes).
            try {
                val dir = File(ctx.filesDir, "aether-bin").apply { mkdirs() }
                val copy = File(dir, BINARY_NAME)
                if (!copy.exists() || copy.length() != libDir.length()) {
                    libDir.copyTo(copy, overwrite = true)
                }
                copy.setExecutable(true, true)
                if (copy.canExecute()) {
                    Log.i(TAG, "using copied binary at ${copy.absolutePath}")
                    return copy
                }
            } catch (e: Exception) {
                lastError = "Aether v2.4 binary copy failed: ${e.message}"
                Log.e(TAG, lastError, e)
                return null
            }
            lastError = "Aether v2.4 binary is not executable"
            Log.e(TAG, lastError)
            return null
        }
        lastError = "Aether v2.4 binary missing at ${libDir.absolutePath}"
        Log.e(TAG, lastError)
        return null
    }

    /** Build the MASQUE CLI arguments — EXACTLY Asha's
     *  scanner invocation (AetherCliConfig.buildArgs,
     *  masque only, with a forced peer; AshaVpnService
     *  .kt:489):
     *  --bind 127.0.0.1:<socksPort> --protocol masque
     *  --log-level info --peer <ip>:<port>
     *  --masque-sni www.cloudflare.com [--h2].
     *  1.56 — three 1.55 divergences removed, each
     *  verified against the clone + the 2.4.0 source:
     *  - NO --quick-reconnect: Asha passes it ONLY
     *    with --scan, never with a forced peer
     *    (buildArgs adds it inside the scan branch
     *    only); and in the core it is a no-op with a
     *    forced peer anyway (lib.rs
     *    want_quick_reconnect is consulted only when
     *    forced.is_none(); the "retrying last
     *    known-good" line is the loop's own
     *    last_good_peer retry, independent of the
     *    flag).
     *  - NO --h3: Asha passes no carrier flag for H3
     *    (the default); --h3 only sets
     *    AETHER_MASQUE_HTTP2=0, which is the default
     *    already. H2 is the only carrier flag.
     *  - NO --ech: AetherCliConfig has no ech field
     *    and Asha's scanner passes none (the PoW's
     *    ECH is the Guard/FFI path, not the scanner;
     *    our 1.55 device run had ECH off too — the
     *    Kotlin DoH fetch never returned a key — so
     *    this changes no observed device behaviour
     *    and removes a divergence). P2 if H2 still
     *    fails on device: --ech auto on H2 runs
     *    (the core's own lookup), for PoW parity. */
    fun buildMasqueArgs(
        socksPort: Int,
        gatewayIp: String,
        gatewayPort: Int,
        h2: Boolean
    ): List<String> {
        require(socksPort in 1024..65535) { "SOCKS port out of range" }
        val args = mutableListOf(
            "--bind", "127.0.0.1:$socksPort",
            "--protocol", "masque",
            "--log-level", "info",
            "--peer", "$gatewayIp:$gatewayPort",
            "--masque-sni", "www.cloudflare.com"
        )
        if (h2) args += "--h2"
        return args
    }

    /** 1.59 — the --scan CLI arguments (SharedScanner's
     *  background sweep): drive the engine's OWN scan
     *  instead of forcing a peer — EXACTLY Asha's
     *  scanner invocation (AetherCliConfig.buildArgs,
     *  scan branch; AshaVpnService.kt:489):
     *  --bind 127.0.0.1:<socksPort> --protocol masque
     *  --log-level info --scan <mode> [--quick-reconnect]
     *  --masque-sni www.cloudflare.com.
     *  --quick-reconnect is passed ONLY here (Asha
     *  passes it only with --scan, never with a forced
     *  peer — 1.56). The mode is one of the CLI's own
     *  (cli.rs:101): turbo | balanced | thorough |
     *  verified | ironclad; "balanced" is Asha's
     *  auto-scan default, "turbo" stops at the first
     *  candidate that answers (cli.rs:102). */
    fun buildScanArgs(
        socksPort: Int,
        scanMode: String,
        quickReconnect: Boolean
    ): List<String> {
        require(socksPort in 1024..65535) { "SOCKS port out of range" }
        require(scanMode in setOf("turbo", "balanced", "thorough", "verified", "ironclad")) {
            "Unknown scan mode: $scanMode"
        }
        val args = mutableListOf(
            "--bind", "127.0.0.1:$socksPort",
            "--protocol", "masque",
            "--log-level", "info",
            "--scan", scanMode
        )
        if (quickReconnect) args += "--quick-reconnect"
        args += "--masque-sni"
        args += "www.cloudflare.com"
        return args
    }

    /** 1.58 — the GOOL (WOM: WireGuard-in-MASQUE)
     *  CLI arguments — Asha's WOM, NOT Asha's
     *  scanner "gool": the scanner's --gool-classic
     *  is WG-in-WG (UDP both hops, dead on MCI like
     *  H3 and direct WG); the non-classic --protocol
     *  gool carries the inner WireGuard inside the
     *  outer MASQUE stream, so no raw UDP ever
     *  touches the user network (lib.rs
     *  needs_udp_to_warp=false for WarpInWarp
     *  non-classic). Args mirror buildMasqueArgs:
     *  --bind 127.0.0.1:<socksPort> --protocol gool
     *  --log-level info --peer <ip>:<port>
     *  --masque-sni www.cloudflare.com [--h2]
     *  [--gool-peer <innerIp>:2408].
     *  H2-only on MCI (the outer that is proven on
     *  this network); H3 is the caller's fallback,
     *  never first. NO --gool-classic, NO --scan,
     *  NO --quick-reconnect. The inner peer is the
     *  gool identity's assigned endpoint (or
     *  engage.cloudflareclient.com:2408); the engine
     *  derives the inner identity file itself as the
     *  sibling of AETHER_MASQUE_CONFIG
     *  (aether-masque-gool.toml, lib.rs:955-958). */
    fun buildGoolArgs(
        socksPort: Int,
        gatewayIp: String,
        gatewayPort: Int,
        h2: Boolean,
        goolPeer: String?
    ): List<String> {
        require(socksPort in 1024..65535) { "SOCKS port out of range" }
        val args = mutableListOf(
            "--bind", "127.0.0.1:$socksPort",
            "--protocol", "gool",
            "--log-level", "info",
            "--peer", "$gatewayIp:$gatewayPort",
            "--masque-sni", "www.cloudflare.com"
        )
        if (h2) args += "--h2"
        val inner = goolPeer?.trim().orEmpty()
        if (inner.isNotBlank()) {
            args += "--gool-peer"
            args += inner
        }
        return args
    }

    /** Child-process environment (Asha's manager +
     *  the Asha env block, in child-env form). The
     *  carrier flags (--h2/--h3, --peer) are args, not
     *  env — AETHER_MASQUE_HTTP2 / AETHER_PEER are
     *  explicitly removed from the inherited env so a
     *  stale value from our process can never leak in.
     *  1.57 — the two fragment envs are gated on
     *  [fragmentOn] (AshaCarrier, ported from Asha):
     *  "1" only for a determined non-MCI carrier;
     *  null (unset — the core default, OFF) on MCI or
     *  an undetermined carrier. Asha's scanner unsets
     *  both on MCI because its H2 ClientHello must go
     *  out unfragmented there; our 1.56 build set both
     *  to "1" unconditionally, and the 1.56 device log
     *  proves ours fragmented (size=8..16
     *  delay=2..10ms) while the H2 TLS handshake then
     *  never completed. Pure (takes explicit dirs +
     *  enroll IP + the gate) so the JVM test can check
     *  it without an Android Context. */
    fun childEnvFor(filesDir: File, cacheDir: File, enrollIp: String, fragmentOn: Boolean): Map<String, String?> {
        val aetherDir = File(filesDir, "aether")
        val certDirs = listOf(
            "/apex/com.android.conscrypt/cacerts",
            "/system/etc/security/cacerts"
        ).filter { File(it).isDirectory }
        return mapOf(
            "HOME" to filesDir.absolutePath,
            "TMPDIR" to cacheDir.absolutePath,
            "AETHER_CONFIG" to File(aetherDir, "aether.toml").absolutePath,
            "AETHER_MASQUE_CONFIG" to File(aetherDir, "aether-masque.toml").absolutePath,
            "AETHER_WG_CONFIG" to File(aetherDir, "aether.toml").absolutePath,
            "SSL_CERT_DIR" to if (certDirs.isNotEmpty()) certDirs.joinToString(":") else null,
            // Safety net for the engine's own API calls
            // (2.4.0 account.rs:441 reads this): a clean
            // IP, never the API hostname's DNS.
            "AETHER_API_ADDRESS" to "$enrollIp:443",
            // Asha's env block (Guard, per run):
            "AETHER_MASQUE_STARTUP_SECS" to "35",
            "AETHER_MASQUE_VALIDATE_SECS" to "15",
            "AETHER_WG_VALIDATE_SECS" to "15",
            // 1.57 — MCI-gated (see the header): null
            // means start() removes the key, exactly
            // Asha's unsetenv.
            "AETHER_MASQUE_H2_FRAGMENT" to if (fragmentOn) "1" else null,
            "AETHER_API_FRAGMENT" to if (fragmentOn) "1" else null,
            "AETHER_API_CAMO" to "1",
            "AETHER_MASQUE_H2_LARGE_WINDOWS" to "1",
            "AETHER_MASQUE_H2_SOCKET_TUNING" to "1",
            "AETHER_MASQUE_H2_SPEED_PICK" to "1",
            "AETHER_QUIC_TUNED" to "1",
            // Carrier is carried by flags, never env:
            "AETHER_MASQUE_HTTP2" to null,
            "AETHER_PEER" to null
        )
    }

    fun childEnv(ctx: Context, enrollIp: String): Map<String, String?> {
        // 1.57 — the carrier is read here, once per
        // session start; an undetermined carrier falls
        // back to "unknown", which keeps the fragment
        // envs UNSET (AshaCarrier.fragmentOnForCarrier).
        val carrier = runCatching { AshaCarrier.carrierClass(ctx) }.getOrDefault("unknown")
        return childEnvFor(ctx.filesDir, ctx.cacheDir, enrollIp,
            AshaCarrier.fragmentOnForCarrier(carrier))
    }

    /** Start the CLI session loop and wait until its
     *  SOCKS listener answers (or the process dies, or
     *  [socksWaitMs] passes). Any previous session is
     *  stopped first. Returns true when the SOCKS port
     *  answers — a bound port means the tunnel is
     *  ready (Asha). [onOutput] gets every stdout
     *  line (the [warp] log writer in our app). */
    @Synchronized
    fun start(
        ctx: Context,
        args: List<String>,
        socksPort: Int,
        socksWaitMs: Long = SOCKS_WAIT_MS,
        enrollIp: String = "162.159.192.1",
        onOutput: ((String) -> Unit)? = null
    ): Boolean {
        stop()
        lastError = ""
        sawConnectIp400 = false
        val bin = binary(ctx) ?: return false
        synchronized(recentOutput) { recentOutput.clear() }
        lastSelectedEndpoint = ""
        lastScanEdge = ""

        val effectiveArgs = if (args.none { it == "-4" || it == "-6" || it == "--ip" }) {
            args + "-4"
        } else {
            args
        }

        val builder = ProcessBuilder(listOf(bin.absolutePath) + effectiveArgs)
        builder.directory(ctx.filesDir)
        builder.redirectErrorStream(true)
        val env = builder.environment()
        for ((k, v) in childEnv(ctx, enrollIp)) {
            if (v == null) env.remove(k) else env[k] = v
        }

        val proc = try {
            builder.start().apply {
                // Immediately close stdin so the CLI NEVER
                // blocks on an interactive prompt (Asha).
                runCatching { outputStream.close() }
            }
        } catch (e: Exception) {
            lastError = "Could not start Aether v2.4: ${e.message}"
            Log.e(TAG, lastError, e)
            return false
        }

        process = proc
        running.set(true)

        logThread = Thread({
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).forEachLine { line ->
                    if (line.isNotBlank()) {
                        Log.d(TAG, line)
                        noteOutput(line)
                        try { onOutput?.invoke(line) } catch (_: Exception) {}
                        if (isConnectIp400(line)) sawConnectIp400 = true
                        parseSelectedGateway(line)?.let {
                            lastSelectedEndpoint = it
                        } ?: parseGoolReady(line)?.let {
                            lastSelectedEndpoint = it
                        }
                        // 1.59: the edge the hunt is
                        // currently trying (the --scan
                        // run's fallback signal when no
                        // selection line arrived yet).
                        parseScanEdge(line)?.let {
                            lastScanEdge = it
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "aether24-log").apply { isDaemon = true }.also { it.start() }

        // Wait for the SOCKS listener to actually SERVE
        // (see socksReady: a bound port means nothing —
        // the CLI binds it at startup, before any tunnel
        // exists). A serving SOCKS means the tunnel is up.
        val deadline = System.currentTimeMillis() + socksWaitMs
        var accepts = false
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                runCatching { logThread?.join(800) }
                lastError = "Aether v2.4 exited: ${recentOutputText()}"
                running.set(false)
                return false
            }
            if (sawConnectIp400) {
                // 1.61 — the gateway refused MASQUE
                // (connect-ip 400): the engine would
                // retry it ~8 times (~25s). Abort now
                // (~2–3s) so the rung moves on to the
                // next gateway instead.
                runCatching { logThread?.join(800) }
                lastError = "Aether v2.4 connect-ip status 400 (gateway refused MASQUE): ${recentOutputText()}"
                running.set(false)
                stop()
                return false
            }
            if (socksReady(socksPort, 1500)) {
                accepts = true
                break
            }
            try { Thread.sleep(500) } catch (_: Exception) { break }
        }

        if (!accepts) {
            runCatching { logThread?.join(800) }
            lastError = "Aether v2.4 SOCKS port $socksPort failed to respond: ${recentOutputText()}"
            stop()
            return false
        }

        return true
    }

    /** Is the SOCKS port actually SERVING (not just
     *  bound)? The CLI binds its listener at startup
     *  (lib.rs run_with binds Listeners before
     *  run_masque), so a bare TCP connect succeeds
     *  minutes before the tunnel exists — Asha's
     *  portAccepts wait is NOT enough for our flow
     *  (our connect kills the session if no traffic
     *  passes 4s after the TUN starts). This does a
     *  real SOCKS5 handshake + CONNECT to 1.1.1.1:443
     *  through the port: success means the tunnel
     *  carries TCP to the internet right now. Internal
     *  for the JVM test. */
    internal fun socksReady(port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                socket.soTimeout = timeoutMs
                val out = socket.getOutputStream()
                val inp = socket.getInputStream()
                // SOCKS5 greeting: version 5, 1 method, no-auth.
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()
                val greet = ByteArray(2)
                if (readExact(inp, greet) != 2) return false
                if (greet[0] != 0x05.toByte() || greet[1] != 0x00.toByte()) return false
                // CONNECT 1.1.1.1:443 (raw IP — no DNS).
                out.write(byteArrayOf(
                    0x05, 0x01, 0x00, 0x01,
                    0x01, 0x01, 0x01, 0x01,
                    0x01, 0xBB.toByte()))
                out.flush()
                val reply = ByteArray(10)
                if (readExact(inp, reply) != 10) return false
                reply[0] == 0x05.toByte() && reply[1] == 0x00.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun readExact(inp: java.io.InputStream, buf: ByteArray): Int {
        var got = 0
        while (got < buf.size) {
            val n = try { inp.read(buf, got, buf.size - got) } catch (_: Exception) { -1 }
            if (n <= 0) break
            got += n
        }
        return got
    }

    private fun portAccepts(port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            true
        }
    } catch (_: Exception) {
        false
    }

    @Synchronized
    fun stop() {
        running.set(false)
        process?.let { proc ->
            try {
                proc.destroy()
                if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
            } catch (_: Exception) {
            }
        }
        process = null
        logThread = null
    }
}
