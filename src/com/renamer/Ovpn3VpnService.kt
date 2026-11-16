package com.renamer

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import net.openvpn.ovpn3.ClientAPI_Config

/**
 * VpnService that runs the OpenVPN3 core
 * in-process. It owns the TUN interface:
 * the core calls tun_builder_establish, this
 * service builds the Android TUN with
 * VpnService.Builder and returns its fd.
 * Sockets are protected with VpnService.protect
 * so transport traffic never goes through the
 * tunnel itself (the classic routing loop).
 *
 * Started by OvpnEngine after VpnService.prepare.
 * The profile is passed via OvpnEngine's static
 * pending fields, because a .ovpn can be large
 * for an Intent extra.
 *
 * v1.33 crash hardening (user reported
 * "calculator keeps stopping" on v1.32):
 * - NO startForeground() at all. A VpnService
 *   is not a plain started service; calling
 *   startForeground on it was the same class
 *   of crash as v1.24 (FGS type/timeout on
 *   Android 14+). The official WireGuard
 *   GoBackend VpnService also does not
 *   foreground itself. If the system needs
 *   a notification, VpnService provides its
 *   own VPN notification.
 * - ALL native interaction happens on the
 *   connect thread, never on the main thread.
 *   v1.32 constructed the SWIG client on the
 *   main thread in onStartCommand, so any
 *   native Error (UnsatisfiedLinkError,
 *   NoSuchMethodError, JNI director failure)
 *   killed the app process with the crash
 *   dialog. Now every native call is inside
 *   try/catch(Throwable), and a failure is
 *   reported as "وصل نشد: ..." in the UI,
 *   never as a crash.
 */
class Ovpn3VpnService : VpnService() {

    companion object {
        @Volatile var instance: Ovpn3VpnService? = null
        @Volatile var client: Ovpn3Client? = null
        @Volatile var connectThread: Thread? = null
        // 3.01: the TunnelBear split relay of the
        // current session (null for every other
        // profile). Owned by the service, closed on
        // stop and on the next connect.
        @Volatile var tbRelay: TunnelBearRelay? = null
    }

    private var tunFd: ParcelFileDescriptor? = null
    // it, and the connect thread's finally closes the TUN
    // only while its own generation is still current, so
    // a stale thread finishing after a quick reconnect
    // can never kill the new session's TUN. The TUN
    // itself is also tagged (tunOwnerGen) at establish.
    private val tunGen = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var currentConnectGen = -1
    @Volatile private var tunOwnerGen = -1

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            System.loadLibrary("ovpn3")
        } catch (t: Throwable) {
            try { OvpnEngine.addLog("load ovpn3 failed: " + (t.message ?: t.javaClass.simpleName)) } catch (_: Throwable) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val action = intent?.action
            if (action == "STOP") {
                stopClient()
                stopSelf()
                return START_NOT_STICKY
            }
            val profile = OvpnEngine.pendingProfile3 ?: return START_NOT_STICKY
            startClient(profile)
            return START_NOT_STICKY
        } catch (t: Throwable) {
            try {
                OvpnEngine.lastError = AppLanguage.t(this, "وصل نشد: ", "Failed to connect: ") + (t.message ?: t.javaClass.simpleName)
                OvpnEngine.isConnecting = false
                OvpnEngine.isConnected = false
            } catch (_: Throwable) {}
            return START_NOT_STICKY
        }
    }

    private fun startClient(profile: OvpnProfile) {
        try { client?.stop() } catch (t: Throwable) {}
        // 3.01: a previous session's relay never
        // lingers into the next connect.
        try { tbRelay?.close() } catch (t: Throwable) {}
        tbRelay = null
        OvpnEngine.isConnecting = true
        OvpnEngine.isConnected = false
        OvpnEngine.activeName = profile.name
        OvpnEngine.lastError = null
        OvpnEngine.lastState = "CONNECTING"
        val svc = this
        val myGen = tunGen.incrementAndGet()
        currentConnectGen = myGen
        val t = Thread {
            var c: Ovpn3Client? = null
            // 3.01: this thread's own relay, declared
            // OUTSIDE the try so the finally below can
            // close exactly this session's relay (a
            // stale thread must never kill a newer
            // session's relay).
            var myRelay: TunnelBearRelay? = null
            try {
                c = Ovpn3Client(
                    onEvent = { name, info, error, fatal ->
                        OvpnEngine.onOvpn3Event(name, info, error, fatal)
                    },
                    onLog = { line -> OvpnEngine.addLog(line) },
                    protectSocket = { fd ->
                        try { svc.protect(fd) } catch (t: Throwable) { false }
                    },
                    establishTun = { spec -> establishTun(spec) }
                )
                client = c
                // v1.1: usage accounting for the dual free
                // accounts — poll the core's transport
                // stats (bytesIn + bytesOut, the same
                // measurement the engine reports) every
                // 5s while the free tunnel is up, and add
                // the delta to the active account's 31-day
                // total. A no-op for own profiles and when
                // the stats call is unavailable.
                Thread {
                    var lastIn = -1L
                    var lastOut = -1L
                    try {
                        while (client != null) {
                            try { Thread.sleep(5000) } catch (_: Exception) { break }
                            val cl = client ?: break
                            if (OvpnEngine.activeName != TunnelBear.SINGLE_NAME ||
                                !OvpnEngine.isConnected) {
                                lastIn = -1L; lastOut = -1L; continue
                            }
                            try {
                                val st = cl.transport_stats()
                                val bin = st.bytesIn
                                val bout = st.bytesOut
                                if (lastIn >= 0 && bin >= lastIn && bout >= lastOut) {
                                    val d = (bin - lastIn) + (bout - lastOut)
                                    if (d > 0) FreeAccounts.addBytes(this@Ovpn3VpnService, d)
                                }
                                lastIn = bin; lastOut = bout
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }.apply { isDaemon = true }.start()
                val cfg = ClientAPI_Config()
                cfg.content = profile.ovpn
                // 3.01: TunnelBear — the core never dials
                // the server directly. Probe the pool's
                // IPs on TCP 7011, start the loopback
                // split relay, and rewrite the config to
                // point at the relay (cipher appended,
                // proto tcp-client).
                if (TunnelBear.isTunnelBear(profile)) {
                    try {
                        val hosts = TunnelBear.probeCandidates(this@Ovpn3VpnService, profile.hostname)
                        val relay = TunnelBearRelay(this@Ovpn3VpnService, hosts)
                        relay.start()
                        myRelay = relay
                        tbRelay = relay
                        cfg.content = TunnelBear.effectiveConfig(cfg.content, relay.localPort)
                        OvpnEngine.addLog("Free OpenVpn: relay on 127.0.0.1:${relay.localPort}, TCP 7011, ${hosts.size} candidate(s).")
                    } catch (t: Throwable) {
                        OvpnEngine.addLog("Free OpenVpn relay failed: " + (t.message ?: t.javaClass.simpleName))
                    }
                }
                // 3.02: TunnelBear eval fix — the core died
                // at config-evaluation time with "Missing
                // External PKI alias" for every cert-less
                // profile: given a bare `auth-user-pass` +
                // inline <ca> profile with disableClientCert
                // false (the default), the core attempts
                // mutual certificate auth via External PKI
                // and demands an alias that was never set.
                // Mirror MLM's configuration() exactly,
                // for EVERY profile (any cert-less profile
                // in the app failed the same way at eval):
                // compressionMode "asym" (TB pushes
                // comp-lzo-style compression; the default
                // "no" ends the session right after
                // "Connected via tun"), and via the
                // ConfigCommon view: disableClientCert
                // whenever the text has no <cert> block,
                // connTimeout 40, tunPersist, no retry on
                // auth failure, 1000 ms clock tick.
                // NEVER call cfg.disableClientCert (or the
                // other ClientAPI_Config setters for these
                // fields): the shipped .so has no native
                // implementation for them — calling one
                // throws UnsatisfiedLinkError. The
                // ConfigCommon_* symbols DO exist; the
                // view is non-owning (same pointer, base
                // subobject at offset 0) and never frees.
                // Also NEVER set a dummy externalPkiAlias
                // (that would make the core look up a
                // nonexistent Keystore alias instead).
                try {
                    cfg.compressionMode = "asym"
                    val common = cfg.asConfigCommon()
                    try {
                        common.setDisableClientCert(!cfg.content.contains("<cert>"))
                        common.setConnTimeout(40)
                        common.setTunPersist(true)
                        common.setRetryOnAuthFailed(false)
                        common.setClockTickMS(1000)
                        common.setGuiVersion("Openvpn 1.0")
                    } finally {
                        try { common.delete() } catch (_: Throwable) {}
                    }
                } catch (t: Throwable) {
                    OvpnEngine.addLog("ovpn3 config fields failed: " + (t.message ?: t.javaClass.simpleName))
                }
                val eval = c.eval_config(cfg)
                if (eval.error) {
                    OvpnEngine.lastError = AppLanguage.t(this, "وصل نشد: ", "Failed to connect: ") + (eval.message ?: "config error")
                    OvpnEngine.isConnecting = false
                    OvpnEngine.isConnected = false
                    // survive an eval failure into the
                    // next attempt.
                    try {
                                            } catch (_: Exception) {}
                    OvpnEngine.addLog("eval_config error: " + (eval.message ?: ""))
                    return@Thread
                }
                // 3.01: a TunnelBear profile's credentials
                // come from the saved TunnelBear account,
                // not from the profile itself — fill them
                // here so every caller (page, widget,
                // quick-connect) connects with the account.
                // In-memory only; nothing is persisted.
                if (TunnelBear.isTunnelBear(profile) &&
                    (profile.username.isBlank() || profile.password.isBlank())) {
                    try {
                        TunnelBear.getAccount(this@Ovpn3VpnService)?.let { (u, pw) ->
                            if (profile.username.isBlank()) profile.username = u
                            if (profile.password.isBlank()) profile.password = pw
                        }
                    } catch (_: Exception) {}
                }
                if (profile.username.isNotBlank() || profile.password.isNotBlank()) {
                    try {
                        val st = c.provideCreds(profile.username, profile.password)
                        if (st.error) {
                            OvpnEngine.addLog("provide_creds: " + (st.message ?: ""))
                        }
                    } catch (t: Throwable) {
                        OvpnEngine.addLog("provide_creds failed: " + (t.message ?: t.javaClass.simpleName))
                    }
                }
                val status = c.connect()
                if (status.error) {
                    if (OvpnEngine.isConnecting) {
                        OvpnEngine.lastError = AppLanguage.t(this, "وصل نشد: ", "Failed to connect: ") + (status.message ?: status.status ?: "")
                        OvpnEngine.isConnecting = false
                        OvpnEngine.isConnected = false
                    }
                }
            } catch (t: Throwable) {
                // Never let a native Error kill
                // the app: report it as a status.
                try {
                    OvpnEngine.lastError = AppLanguage.t(this, "وصل نشد: ", "Failed to connect: ") + (t.message ?: t.javaClass.simpleName)
                    OvpnEngine.isConnecting = false
                    OvpnEngine.isConnected = false
                    OvpnEngine.addLog("ovpn3 connect failed: " + (t.message ?: t.javaClass.simpleName))
                } catch (_: Throwable) {}
            } finally {
                // close only when BOTH tokens still say
                // this session owns the TUN: this thread
                // is still the current connect, and the
                // TUN was tagged with this generation.
                // A stale thread finishing after a quick
                // reconnect therefore cannot kill the new
                // session's TUN.
                try {
                    if (currentConnectGen == myGen && tunOwnerGen == myGen) closeTun()
                } catch (_: Throwable) {}
                // 3.01: the same guard for the relay —
                // only this session's own relay is closed
                // here, so a stale thread can never kill
                // a newer session's relay.
                try {
                    if (myRelay != null && tbRelay === myRelay) {
                        myRelay!!.close()
                        tbRelay = null
                    }
                } catch (_: Throwable) {}
            }
        }
        connectThread = t
        t.start()
    }

    private fun establishTun(spec: Ovpn3Client.TunSpec): Int {
        try {
            closeTun()
            val b = Builder()
            b.setSession("Openvpn")
            try { b.setMtu(spec.mtu) } catch (t: Throwable) {}
            for ((addr, prefix) in spec.addresses) {
                try { b.addAddress(addr, prefix) } catch (t: Throwable) {}
            }
            for ((addr, prefix) in spec.routes) {
                try {
                    if (addr == "::") b.addRoute(addr, 0) else b.addRoute(addr, prefix)
                } catch (t: Throwable) {}
            }
            val dns = spec.dnsServers.filter { it.contains(".") }.distinct()
            for (d in dns) {
                try { b.addDnsServer(d) } catch (t: Throwable) {}
            }
            if (dns.isEmpty()) {
                try { b.addDnsServer("8.8.8.8") } catch (t: Throwable) {}
                try { b.addDnsServer("1.1.1.1") } catch (t: Throwable) {}
            }
            for (d in spec.searchDomains) {
                try { b.addSearchDomain(d) } catch (t: Throwable) {}
            }
            val pfd = b.establish()
            if (pfd == null) {
                tunOwnerGen = -1
                return -1
            }
            tunOwnerGen = currentConnectGen
            tunFd = pfd
            try {
                            } catch (_: Exception) {}
            return pfd.detachFd()
        } catch (t: Throwable) {
            try { OvpnEngine.addLog("tun establish failed: " + (t.message ?: t.javaClass.simpleName)) } catch (_: Throwable) {}
            return -1
        }
    }

    private fun closeTun() {
        try { tunFd?.close() } catch (_: Throwable) {}
        tunFd = null
        tunOwnerGen = -1
    }

    private fun stopClient() {
        try { client?.stop() } catch (_: Throwable) {}
        client = null
        // 3.01: the relay belongs to this session.
        try { tbRelay?.close() } catch (_: Throwable) {}
        tbRelay = null
        try { closeTun() } catch (_: Throwable) {}
        OvpnEngine.isConnected = false
        OvpnEngine.isConnecting = false
        OvpnEngine.activeName = null
    }

    override fun onRevoke() {
        try { stopClient() } catch (_: Throwable) {}
        super.onRevoke()
    }

    override fun onDestroy() {
        try { stopClient() } catch (_: Throwable) {}
        if (instance === this) instance = null
        super.onDestroy()
    }
}
