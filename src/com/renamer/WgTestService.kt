package com.renamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2.59: WireGuard tunnel test WITHOUT connecting.
 *
 * The user asked for WG health without cutting a live VPN.
 * The trick is the same one the v2ray ping test uses: a
 * SEPARATE Xray core (Libv2ray.measureOutboundDelay)
 * runs the tunnel's own outbound and fetches the test
 * URL through it. No system VPN is touched, so a live
 * session keeps running untouched.
 *
 * Three deliberate limits, all honest:
 *  - AmneziaWG tunnels are NOT probed: the Xray core
 *    does not support Amnezia junk parameters, so a
 *    probe without junk would fail even for a healthy
 *    Amnezia tunnel and report a false DEAD. They are
 *    counted as untestable, never as dead.
 *  - A tunnel that is LIVE right now is counted
 *    healthy WITHOUT probing: it is connected, so it
 *    is alive by definition, and a second handshake
 *    from the same key must never disturb it.
 *  - OpenVPN profiles cannot be tested this way
 *    (they need the ovpn3 client, not an Xray
 *    outbound), so they stay count + last-state only.
 *
 * Prefs "renamer_wg_test" (prepared by the activity,
 * like the ping flow):
 *  input  - nothing; the service reads "wg_list" from
 *           "renamer_wg" itself.
 *  live   - "wg_test_progress_done" / "wg_test_total" /
 *           "wg_test_healthy" / "wg_test_untestable".
 *  output - "wg_test_done": true when finished.
 *           "wg_test_running": true only while running.
 *           "wg_test_results": JSON name -> true/false
 *           for probed tunnels (live ones included as
 *           true). "wg_test_untestable_names": JSON
 *           array of Amnezia names. "wg_test_time":
 *           finish clock time. "wg_test_error".
 */
class WgTestService : Service() {

    companion object {
        const val PREFS = "renamer_wg_test"
        const val CHANNEL_ID = "wgtest"
        const val NOTIF_ID = 1002
    }

    @Volatile private var worker: Thread? = null
    @Volatile private var stopped = false
    private var wakeLock: PowerManager.WakeLock? = null

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker?.isAlive == true) return START_NOT_STICKY
        stopped = false
        startForegroundWithNotification(0, 0)
        val wl = try {
            (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "renamer:wgtest")
        } catch (_: Throwable) { null }
        wakeLock = wl
        try { wl?.acquire() } catch (_: Throwable) {}
        worker = Thread { runTest() }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification(done: Int, total: Int) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    L("تست وایرگارد", "WireGuard test"),
                    NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(ch)
            }
            val text = if (total > 0)
                L("در حال تست وایرگارد... $done از $total", "Testing WireGuard... $done of $total")
            else
                L("در حال تست وایرگارد...", "Testing WireGuard...")
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val notif = builder
                .setContentTitle(L("تست تونل‌های وایرگارد", "WireGuard tunnel test"))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Throwable) {}
    }

    private fun runTest() {
        try {
            if (!CoreInit.init(applicationContext)) {
                finishError("core init failed")
                return
            }
            val wgPrefs = getSharedPreferences("renamer_wg", MODE_PRIVATE)
            val arr = try {
                JSONArray(wgPrefs.getString("wg_list", "[]") ?: "[]")
            } catch (_: Exception) { JSONArray() }
            val live = try {
                WgDirect.liveTunnelNames(applicationContext)
            } catch (_: Exception) { emptySet<String>() }
            val results = JSONObject()
            val untestable = JSONArray()
            var done = 0
            var healthy = 0
            val total = arr.length()
            for (i in 0 until arr.length()) {
                if (stopped) break
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "")
                val conf = o.optString("conf", "")
                if (name.isBlank()) continue
                when {
                    name in live || WgDirect.ifaceNameOf(name) in live -> {
                        // Live tunnel: healthy by
                        // definition, never probed.
                        results.put(name, true)
                        healthy++
                    }
                    WgDirect.isAwgConf(conf) -> {
                        // Amnezia junk is not supported
                        // by the Xray core: probing would
                        // falsely report a healthy tunnel
                        // as dead. Untestable, not dead.
                        untestable.put(name)
                    }
                    else -> {
                        val ok = probe(name)
                        results.put(name, ok)
                        if (ok) healthy++
                    }
                }
                done++
                prefs().edit()
                    .putInt("wg_test_progress_done", done)
                    .putInt("wg_test_healthy", healthy)
                    .putInt("wg_test_untestable", untestable.length())
                    .apply()
                startForegroundWithNotification(done, total)
            }
            val time = SimpleDateFormat("HH:mm", Locale.US).format(Date())
            prefs().edit()
                .putString("wg_test_results", results.toString())
                .putString("wg_test_untestable_names", untestable.toString())
                .putInt("wg_test_total", total)
                .putInt("wg_test_progress_done", done)
                .putInt("wg_test_healthy", healthy)
                .putInt("wg_test_untestable", untestable.length())
                .putString("wg_test_time", time)
                .putBoolean("wg_test_done", true)
                .putBoolean("wg_test_running", false)
                .putString("wg_test_error", "")
                .apply()
            AppLog.append(this, "wireguard",
                "تست تونل‌ها تمام شد؛ سالم: $healthy از $done.",
                "Tunnel test done; healthy: $healthy of $done.")
        } catch (t: Throwable) {
            finishError(t.message)
        } finally {
            try { wakeLock?.release() } catch (_: Throwable) {}
            stopSelf()
        }
    }

    // 1.31: moved verbatim from the deleted ChainEngine.kt —
    // the WireGuard probe below is its only remaining caller
    // (the chain section itself is gone).
    private fun wgOutbound(ctx: Context, tunnelName: String, tag: String): JSONObject? {
        return try {
            val arr = JSONArray(ctx.getSharedPreferences("renamer_wg", Context.MODE_PRIVATE)
                .getString("wg_list", "[]") ?: "[]")
            var conf: String? = null
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("name") == tunnelName) {
                    conf = o.optString("conf")
                    break
                }
            }
            if (conf.isNullOrBlank()) return null
            wgOutboundConf(conf, tag)
        } catch (_: Exception) { null }
    }

    /** 3.07: the same outbound builder from raw .conf
     *  TEXT (no saved tunnel needed), so the free-config
     *  test can probe a freshly fetched config before it
     *  is saved — and so can any other caller that holds
     *  a conf in memory. The parse, the domain-endpoint
     *  pre-resolve and the reserved-bytes handling are
     *  exactly the saved-tunnel path's. */
    private fun wgOutboundConf(conf: String, tag: String): JSONObject? {
        return try {
            var section = ""
            var priv = ""
            var addr = ""
            var pub = ""
            var endpoint = ""
            val allowed = mutableListOf<String>()
            var keepAlive = 0
            var psk = ""
            var reservedRaw = ""
            for (raw in conf.lines()) {
                val line = raw.trim()
                if (line.startsWith("[")) {
                    section = line
                    continue
                }
                val kv = line.split("=", limit = 2)
                if (kv.size != 2) continue
                val k = kv[0].trim()
                val v = kv[1].trim()
                when {
                    section.contains("Interface") && k == "PrivateKey" -> priv = v
                    section.contains("Interface") && k == "Address" -> addr = v.split(",").firstOrNull()?.trim() ?: ""
                    section.contains("Peer") && k == "PublicKey" -> pub = v
                    section.contains("Peer") && k == "Endpoint" -> endpoint = v
                    section.contains("Peer") && k == "AllowedIPs" -> allowed += v.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    section.contains("Peer") && k == "PersistentKeepalive" -> keepAlive = v.toIntOrNull() ?: 0
                    section.contains("Peer") && k == "PresharedKey" -> psk = v
                    section.contains("Interface") && k == "Reserved" -> reservedRaw = v
                }
            }
            if (priv.isBlank() || pub.isBlank() || endpoint.isBlank()) return null
            if (addr.isBlank()) addr = "10.0.0.2/32"
            if (allowed.isEmpty()) allowed += "0.0.0.0/0"
            // 2.60: pre-resolve a DOMAIN endpoint to
            // an IP before handing it to Xray. The
            // WireGuard device resolves its peer
            // through Go's resolver, which on
            // Android often cannot use the system
            // DNS — so a domain endpoint silently
            // never handshakes, and the WG test
            // looks connected but carries
            // no traffic, while the same tunnel
            // connects fine directly (the system
            // backend resolves via Android DNS).
            // WireGuard has no SNI, so pinning the
            // resolved IP is cryptographically
            // safe; on any failure the domain is
            // kept exactly as before. Runs only on
            // chain-start / test threads, never UI.
            val effEndpoint = try {
                val host: String
                val port: String
                if (endpoint.startsWith("[")) {
                    host = endpoint.substringAfter("[").substringBefore("]")
                    port = endpoint.substringAfter("]:", "")
                } else {
                    host = endpoint.substringBeforeLast(":")
                    port = endpoint.substringAfterLast(":", "")
                }
                val isIp = host.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")) || host.contains(":")
                if (!isIp && port.isNotBlank()) {
                    val addrs = java.net.InetAddress.getAllByName(host)
                    val v4 = addrs.firstOrNull { it.hostAddress?.contains(".") == true && !it.hostAddress!!.contains(":") }
                    val picked = v4 ?: addrs.firstOrNull()
                    if (picked?.hostAddress != null) {
                        val ip = picked.hostAddress!!
                        if (ip.contains(":")) "[$ip]:$port" else "$ip:$port"
                    } else endpoint
                } else endpoint
            } catch (_: Exception) { endpoint }
            JSONObject().apply {
                put("protocol", "wireguard")
                put("tag", tag)
                put("settings", JSONObject().apply {
                    put("secretKey", priv)
                    put("address", JSONArray().put(addr))
                    put("mtu", 1420)
                    put("peers", JSONArray().put(JSONObject().apply {
                        put("publicKey", pub)
                        put("endpoint", effEndpoint)
                        put("allowedIPs", JSONArray().apply {
                            for (a in allowed) put(a)
                        })
                        if (keepAlive > 0) put("keepAlive", keepAlive)
                        if (psk.isNotBlank()) put("preSharedKey", psk)
                    }))
                    // 2.51: Xray's WireGuard settings take
                    // "reserved" (exactly 3 bytes, base64
                    // in JSON). Our builder dropped it, so
                    // a server expecting reserved bytes
                    // rejected the handshake — the chain
                    // looked connected but carried no
                    // traffic. Conf forms: "1, 2, 3" |
                    // base64 | hex; anything else keeps
                    // the old behaviour (omitted).
                    val reservedB64: String? = try {
                        val bytes: ByteArray? = when {
                            reservedRaw.contains(",") -> {
                                val parts = reservedRaw.split(",").map { it.trim().toIntOrNull() ?: -1 }
                                if (parts.size == 3 && parts.all { it in 0..255 }) {
                                    parts.map { it.toByte() }.toByteArray()
                                } else null
                            }
                            reservedRaw.isNotBlank() -> {
                                try {
                                    val d = android.util.Base64.decode(reservedRaw, android.util.Base64.DEFAULT)
                                    if (d.size == 3) d else null
                                } catch (_: Exception) {
                                    val h = reservedRaw.trim()
                                    if (h.length == 6 && h.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                                        ByteArray(3) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                                    } else null
                                }
                            }
                            else -> null
                        }
                        bytes?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
                    } catch (_: Exception) { null }
                    if (reservedB64 != null) put("reserved", reservedB64)
                })
            }
        } catch (_: Exception) { null }
    }

    /** One tunnel through its own Xray WG outbound,
     *  same shape as XraySpeedtest's speedtest config. */
    private fun probe(name: String): Boolean {
        return try {
            val ob = wgOutbound(applicationContext, name, "proxy") ?: return false
            val outbounds = JSONArray().put(ob)
            outbounds.put(JSONObject().apply { put("protocol", "freedom"); put("tag", "direct") })
            outbounds.put(JSONObject().apply { put("protocol", "blackhole"); put("tag", "block") })
            val config = JSONObject().apply {
                put("log", JSONObject().apply { put("loglevel", "warning") })
                put("outbounds", outbounds)
                put("routing", JSONObject().apply {
                    put("domainStrategy", "IPIfNonMatch")
                    put("rules", JSONArray())
                    put("final", "proxy")
                })
            }.toString()
            Libv2ray.measureOutboundDelay(config, XraySpeedtest.TEST_URL) >= 0
        } catch (_: Throwable) { false }
    }

    private fun finishError(err: String?) {
        try {
            prefs().edit()
                .putBoolean("wg_test_running", false)
                .putBoolean("wg_test_done", true)
                .putString("wg_test_error", err ?: "")
                .apply()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopped = true
        super.onDestroy()
    }
}
