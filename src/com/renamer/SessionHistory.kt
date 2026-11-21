package com.renamer

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Monitoring 1.72 — session history, live counters and
 *  the pure helpers behind them.
 *
 *  What it records: one record per real tunnel session
 *  (v2ray / smart / emergency / cloudflare / warp through
 *  VpnClientService, wireguard/amnezia through WgDirect,
 *  openvpn through OvpnEngine). A record holds ONLY what
 *  the app already knows or measured itself: section,
 *  config/tunnel/profile NAME (never a config link, key
 *  or password), protocol, gateway host, start/end time,
 *  bytes through the tunnel interfaces, exit IP + country
 *  (measured through the tunnel, see below), latency
 *  samples (generate_204 through the tunnel) and peak
 *  speeds. Nothing is invented: a field with no data
 *  stays blank and the UI shows "—" for it.
 *
 *  Where the numbers come from:
 *   - bytes: /proc/net/dev, tunnel interfaces only
 *     (tun|wg|awg|amnezia|ovpn|ppp) — the same source
 *     WidgetCard.totalTunnelBytes() and
 *     VpnNotification.currentSpeedsKbps() already read.
 *     parseTunnelBytes() is the pure parser both share
 *     now; WidgetCard delegates to it. EXCEPTION —
 *     the "warp" (MASQUE) section, 1.73 (D): its real
 *     traffic is the aether CLI's MASQUE stream, which
 *     runs as a child of the app (same UID) and is
 *     excluded from the VPN (the app's own package is
 *     disallowed from the TUN), so it bypasses tun0
 *     entirely and the TUN counters never move while
 *     the tunnel carries real traffic (the user saw
 *     52.5 KB/s in the status bar with 0 B here).
 *     A warp session therefore reads the app UID's
 *     counters (TrafficStats.getUidRxBytes/TxBytes —
 *     the CLI's sockets are tagged with the app UID),
 *     falling back to the TUN counters when the
 *     platform does not provide UID stats. See
 *     selectSessionBytes() / sessionBytesSplit().
 *     Every other section keeps the TUN counters.
 *   - live speeds: VpnNotification.currentSpeedsKbps(),
 *     sampled by the Monitoring page's poller.
 *   - exit IP: ConnVerify.exitIpProbe() (plain fetch,
 *     rides the TUN while a session is live) + geo via
 *     GeoFlag.lookupSync(); v2ray sessions ALSO have the
 *     through-tunnel egress GeoFlag.measureEgress() stores
 *     per config — the page prefers that when present.
 *   - latency: GET https://www.gstatic.com/generate_204
 *     (the same address the ping routes use), timed.
 *   - drops/reconnects: counted, not guessed. A session
 *     counts as a DROP when it ends with an error or
 *     lasted under FLAP_MS. A session counts as a
 *     RECONNECT when it starts within QUICK_RESTART_MS
 *     of the previous session's end (same section, or
 *     while another session was still open and got
 *     superseded). Both counters live in prefs and are
 *     shown as totals, never per-session blame.
 *   - a session under MIN_KEEP_MS with zero bytes is
 *     NOT stored (a failed tap, not a session) — but it
 *     still counts as a drop when it ended in error.
 *
 *  Storage: prefs "renamer_monitor_sessions" —
 *  "sessions" (JSON array, newest first, cap 30),
 *  "open" (the currently-open session, if any),
 *  "rev" (bumped on every mutation, so the page knows
 *  when to rebuild the history list), today/total byte
 *  counters and the drops/reconnects counters. No new
 *  permissions, no background service, no files.
 *
 *  Honesty rule: every public function here is wrapped
 *  so it can never throw into a connect/disconnect
 *  path — the recorders are called from engine hooks
 *  and must be invisible when they fail. */
object SessionHistory {

    const val PREFS = "renamer_monitor_sessions"
    const val KEY_SESSIONS = "sessions"
    const val KEY_OPEN = "open"
    const val KEY_REV = "rev"
    const val KEY_TODAY_DATE = "today_date"
    const val KEY_TODAY_RX = "today_rx"
    const val KEY_TODAY_TX = "today_tx"
    const val KEY_TOTAL_RX = "total_rx"
    const val KEY_TOTAL_TX = "total_tx"
    const val KEY_DROPS = "drops"
    const val KEY_RECONNECTS = "reconnects"
    const val MAX_SESSIONS = 30
    const val MAX_LATENCY_SAMPLES = 30
    const val QUICK_RESTART_MS = 90_000L
    const val FLAP_MS = 10_000L
    const val MIN_KEEP_MS = 5_000L

    data class Session(
        val section: String,
        val name: String,
        val protocol: String,
        val gateway: String,
        val startMs: Long,
        val endMs: Long, // 0 = still open
        val rxBytes: Long,
        val txBytes: Long,
        val exitIp: String,
        val exitCc: String,
        val exitCountry: String,
        val exitCity: String,
        val latencyAvgMs: Long,
        val latencyMinMs: Long,
        val latencyMaxMs: Long,
        val latencySamples: List<Int>,
        val downPeakKbps: Long,
        val upPeakKbps: Long,
        val quickRestart: Boolean,
        val endedWithError: Boolean,
        val errorText: String,
        // Internal only: session byte-source baselines
        // taken at begin(); never exported, never shown.
        // (1.73 (D): the source is per-section — the TUN
        // counters, or the app-UID counters for warp;
        // see sessionBytesSplit().)
        val baseRx: Long = 0L,
        val baseTx: Long = 0L
    ) {
        val bytes: Long get() = rxBytes + txBytes
        val durationMs: Long get() = if (endMs > startMs) endMs - startMs else 0L
        val isOpen: Boolean get() = endMs == 0L
    }

    // ---------------------------------------------------------------------------------------------
    // Pure helpers (no Context — JVM-tested in TestMain172).
    // ---------------------------------------------------------------------------------------------

    /** (rx, tx) bytes across the tunnel interfaces in
     *  /proc/net/dev lines. Unknown/short lines are
     *  skipped, never counted. */
    fun parseTunnelBytes(lines: List<String>): Pair<Long, Long> {
        var rx = 0L
        var tx = 0L
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val name = line.substring(0, colon).trim()
            if (!name.startsWith("tun") && !name.startsWith("wg") &&
                !name.startsWith("awg") && !name.startsWith("amnezia") &&
                !name.startsWith("ovpn") && !name.startsWith("ppp")) continue
            val parts = line.substring(colon + 1).trim().split(Regex("\\s+"))
            if (parts.size < 9) continue
            rx += parts[0].toLongOrNull() ?: 0L
            tx += parts[8].toLongOrNull() ?: 0L
        }
        return rx to tx
    }

    /** Live tunnel bytes right now (rx, tx). 0/0 when
     *  /proc/net/dev cannot be read. */
    fun tunnelBytesSplit(): Pair<Long, Long> = try {
        parseTunnelBytes(File("/proc/net/dev").readLines())
    } catch (_: Exception) { 0L to 0L }

    // ---------------------------------------------------------------------------------------------
    // 1.73 (D) — the MASQUE byte source.
    //
    // ROOT CAUSE (device report 2026-11-21 ~04:29):
    // a live free-warp session showed duration, ping
    // and exit IP correctly but «مصرف این نشست: 0»
    // and an empty live graph, while the status bar
    // showed 52.5 KB/s of real traffic. Every byte
    // display read the /proc/net/dev TUN counters
    // (tun0), and for a MASQUE session those counters
    // NEVER move: the tunnel's real traffic is the
    // aether CLI's MASQUE/QUIC stream to Cloudflare,
    // and the CLI is a child process of the app — the
    // same UID — which the VpnService builder
    // disallows from the VPN, so the CLI's traffic
    // bypasses tun0 entirely. The app's own traffic
    // (including every Monitoring probe) is excluded
    // the same way. Only OTHER apps' traffic crosses
    // tun0 — and a user watching the Monitoring page
    // generates none of that. So live bytes, stored
    // bytes, today/total and the graph (a delta of
    // the same counters) all read 0 together, while
    // duration/ping/exit/reconnects — which never
    // touch those counters — all land.
    //
    // FIX: a warp session measures the APP UID's
    // counters instead (TrafficStats.getUidRxBytes /
    // getUidTxBytes). The CLI's sockets are tagged
    // with the app UID, so its MASQUE traffic — the
    // tunnel's real, outer traffic, i.e. what the
    // status bar and the carrier meter — IS counted
    // there, and it moves whenever the tunnel carries
    // traffic. The only contamination is the app's
    // own direct traffic (probes, geo lookups, a
    // subscription fetch mid-session — kilobytes).
    // When the platform does not provide UID stats
    // (both calls return -1) the TUN counters are
    // used instead, i.e. the old behaviour. Every
    // other section keeps the TUN counters untouched.
    // ---------------------------------------------------------------------------------------------

    /** This UID's cumulative (rx, tx) bytes since
     *  boot, or (-1, -1) when the platform does not
     *  provide per-UID stats. Never throws. */
    fun uidBytesSplit(): Pair<Long, Long> = try {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx < 0 || tx < 0) -1L to -1L else rx to tx
    } catch (_: Exception) { -1L to -1L }

    /** Pure selector (JVM-tested in TestMain173D):
     *  which cumulative counter pair is this
     *  session's byte source. "warp" reads the UID
     *  counters when the platform provides them;
     *  anything else — and warp when UID stats are
     *  unavailable — reads the TUN counters. */
    fun selectSessionBytes(
        section: String,
        uidAvailable: Boolean,
        uidRx: Long,
        uidTx: Long,
        tunRx: Long,
        tunTx: Long
    ): Pair<Long, Long> =
        if (section == "warp" && uidAvailable) uidRx to uidTx else tunRx to tunTx

    /** Cumulative (rx, tx) for [section]'s byte source
     *  right now. 0/0 only when both sources fail. */
    fun sessionBytesSplit(section: String): Pair<Long, Long> = try {
        val (trx, ttx) = tunnelBytesSplit()
        val (urx, utx) = if (section == "warp") uidBytesSplit() else -1L to -1L
        selectSessionBytes(section, urx >= 0 && utx >= 0, urx, utx, trx, ttx)
    } catch (_: Exception) { 0L to 0L }

    /** (down, up) KB/s from two cumulative (rx, tx)
     *  samples; (-1, -1) when unusable (no previous
     *  sample, gap under 1s, clock backwards, a
     *  counter reset). Pure — JVM-tested in
     *  TestMain173D. The Monitoring poller feeds it
     *  with the open session's own bytes, so the
     *  live graph is the delta of exactly the numbers
     *  the «مصرف این نشست» line shows. */
    fun speedFromSamples(
        prevRx: Long,
        prevTx: Long,
        prevMs: Long,
        rx: Long,
        tx: Long,
        nowMs: Long
    ): Pair<Long, Long> {
        if (prevMs <= 0) return -1L to -1L
        val down = rateKbps(rx - prevRx, nowMs - prevMs)
        val up = rateKbps(tx - prevTx, nowMs - prevMs)
        if (down < 0 || up < 0) return -1L to -1L
        return down to up
    }

    /** KB/s from a byte delta over a time delta; -1 when
     *  the delta is unusable (no baseline yet, clock went
     *  backwards, counters reset). */
    fun rateKbps(deltaBytes: Long, deltaMs: Long): Long {
        if (deltaBytes < 0 || deltaMs < 1000) return -1L
        return deltaBytes * 1000 / deltaMs / 1024
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        else -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
    }

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }

    /** "HH:mm" for a millis timestamp. */
    fun formatClock(millis: Long): String = try {
        SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))
    } catch (_: Exception) { "" }

    /** "yyyy-MM-dd" for a millis timestamp (local day). */
    fun dayKey(millis: Long): String = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
    } catch (_: Exception) { "" }

    /** "2026-11-21 02:31" — the history row's date. */
    fun formatDayTime(millis: Long): String = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))
    } catch (_: Exception) { "" }

    /** Protocol from a config link's scheme, or "" when
     *  the link is null, has no scheme, or its scheme is
     *  not a tunnel protocol the app knows. Never returns
     *  the link itself. */
    fun protocolFromLink(link: String?): String {
        if (link.isNullOrBlank()) return ""
        val scheme = link.substringBefore("://", "").trim().lowercase(Locale.US)
        if (scheme.isEmpty() || scheme.contains(" ") || scheme.contains("@")) return ""
        return when (scheme) {
            "vless", "vmess", "trojan", "trojan-go", "ss", "shadowsocks",
            "hysteria2", "hysteria", "tuic", "socks", "http", "wireguard" -> scheme
            else -> ""
        }
    }

    /** Gateway host from a config link ("uuid@host:port"
     *  form), or "" when it cannot be read. vmess links
     *  carry a base64 blob, not a host — they return ""
     *  here; the v2ray engine hook passes the real host
     *  from the built config JSON instead. Never returns
     *  the link itself. */
    fun gatewayFromLink(link: String?): String {
        if (link.isNullOrBlank()) return ""
        return try {
            val after = link.substringAfter("://", "")
            if (after.isBlank() || after == link) return ""
            val hostPart = after.substringAfterLast("@", "")
            if (hostPart.isBlank()) return ""
            val host = hostPart.substringBefore(":").substringBefore("?")
                .substringBefore("/").substringBefore("#").trim()
            if (host.isBlank() || host.contains(" ") || host == after) "" else host
        } catch (_: Exception) { "" }
    }

    /** Gateway host from a WireGuard/Amnezia config text
     *  ("Endpoint = host:port"), or "" when absent. */
    fun gatewayFromWgConf(conf: String?): String {
        if (conf.isNullOrBlank()) return ""
        return try {
            for (raw in conf.lines()) {
                val line = raw.trim()
                if (!line.startsWith("Endpoint", ignoreCase = true)) continue
                val value = line.substringAfter("=", "").trim()
                if (value.isBlank()) continue
                val host = value.substringBefore(":").trim()
                if (host.isNotBlank() && !host.contains(" ")) return host
            }
            ""
        } catch (_: Exception) { "" }
    }

    /** (avg, min, max) latency in ms; (0, 0, 0) when no
     *  samples exist yet — callers check isEmpty first
     *  and show "not measured". */
    fun latencyStats(samples: List<Int>): Triple<Long, Long, Long> {
        if (samples.isEmpty()) return Triple(0L, 0L, 0L)
        var sum = 0L
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        for (s in samples) {
            sum += s
            if (s < min) min = s
            if (s > max) max = s
        }
        return Triple(sum / samples.size, min.toLong(), max.toLong())
    }

    /** True when a session that starts [gapMs] after the
     *  previous session's end counts as a reconnect:
     *  a positive gap under 90s (0 = superseded while
     *  still open, which is also a reconnect). */
    fun isQuickRestart(gapMs: Long): Boolean = gapMs in 0..QUICK_RESTART_MS

    /** True when a session this short counts as a flap
     *  (a drop) even without an error. */
    fun isFlap(durationMs: Long): Boolean = durationMs in 1 until FLAP_MS

    /** A session is worth keeping in history when it
     *  lasted at least 5s or moved any bytes. Shorter,
     *  zero-byte attempts (a failed tap) are dropped
     *  from history — they still count as a drop when
     *  they ended in error (see end()). */
    fun shouldKeep(durationMs: Long, bytes: Long): Boolean =
        durationMs >= MIN_KEEP_MS || bytes > 0

    /** Today's counters after a day rollover: the stored
     *  date differs from today, so today resets to zero
     *  (totals are untouched). Returns (todayRx, todayTx). */
    fun rolloverToday(storedDate: String, todayDate: String, rx: Long, tx: Long): Pair<Long, Long> =
        if (storedDate.isBlank() || storedDate == todayDate) rx to tx else 0L to 0L

    /** Section display name ("v2ray" stays "v2ray" in
     *  both languages; smart/emergency/warp get a real
     *  name instead of the raw tag). */
    fun sectionDisplay(section: String, fa: Boolean): String = when (section) {
        "v2ray" -> "v2ray"
        "smart" -> if (fa) "هوشمند" else "Smart"
        "emergency" -> if (fa) "اضطراری" else "Emergency"
        "cloudflare" -> if (fa) "کلودفلر" else "Cloudflare"
        "warp" -> if (fa) "وارپ" else "Warp"
        "wireguard" -> if (fa) "وایرگارد" else "WireGuard"
        "openvpn" -> "OpenVPN"
        else -> section
    }

    // ---------------------------------------------------------------------------------------------
    // JSON (pure — JVM-tested round-trip).
    // ---------------------------------------------------------------------------------------------

    fun encode(s: Session): JSONObject = JSONObject().apply {
        put("section", s.section)
        put("name", s.name)
        put("protocol", s.protocol)
        put("gateway", s.gateway)
        put("startMs", s.startMs)
        put("endMs", s.endMs)
        put("rx", s.rxBytes)
        put("tx", s.txBytes)
        put("exitIp", s.exitIp)
        put("exitCc", s.exitCc)
        put("exitCountry", s.exitCountry)
        put("exitCity", s.exitCity)
        put("latAvg", s.latencyAvgMs)
        put("latMin", s.latencyMinMs)
        put("latMax", s.latencyMaxMs)
        val arr = JSONArray()
        for (v in s.latencySamples) arr.put(v)
        put("latSamples", arr)
        put("downPeak", s.downPeakKbps)
        put("upPeak", s.upPeakKbps)
        put("quickRestart", s.quickRestart)
        put("endedWithError", s.endedWithError)
        put("errorText", s.errorText)
        put("baseRx", s.baseRx)
        put("baseTx", s.baseTx)
    }

    /** Null when the object is not a session at all
     *  (missing/zero start time, blank section). A
     *  corrupt entry never kills the whole list —
     *  decodeList() skips it. */
    fun decode(obj: JSONObject): Session? {
        return try {
        val start = obj.optLong("startMs", 0L)
        val section = obj.optString("section", "")
        if (start <= 0L || section.isBlank()) return null
        val samples = mutableListOf<Int>()
        val arr = obj.optJSONArray("latSamples")
        if (arr != null) for (i in 0 until arr.length()) samples += arr.optInt(i)
        Session(
            section = section,
            name = obj.optString("name", ""),
            protocol = obj.optString("protocol", ""),
            gateway = obj.optString("gateway", ""),
            startMs = start,
            endMs = obj.optLong("endMs", 0L),
            rxBytes = obj.optLong("rx", 0L),
            txBytes = obj.optLong("tx", 0L),
            exitIp = obj.optString("exitIp", ""),
            exitCc = obj.optString("exitCc", ""),
            exitCountry = obj.optString("exitCountry", ""),
            exitCity = obj.optString("exitCity", ""),
            latencyAvgMs = obj.optLong("latAvg", 0L),
            latencyMinMs = obj.optLong("latMin", 0L),
            latencyMaxMs = obj.optLong("latMax", 0L),
            latencySamples = samples,
            downPeakKbps = obj.optLong("downPeak", 0L),
            upPeakKbps = obj.optLong("upPeak", 0L),
            quickRestart = obj.optBoolean("quickRestart", false),
            endedWithError = obj.optBoolean("endedWithError", false),
            errorText = obj.optString("errorText", ""),
            baseRx = obj.optLong("baseRx", 0L),
            baseTx = obj.optLong("baseTx", 0L)
        )
        } catch (_: Exception) { null }
    }

    fun encodeList(list: List<Session>): String {
        val arr = JSONArray()
        for (s in list) arr.put(encode(s))
        return arr.toString()
    }

    /** Newest first, corrupt entries skipped, capped at
     *  MAX_SESSIONS. Blank/corrupt input -> empty list. */
    fun decodeList(json: String): List<Session> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = mutableListOf<Session>()
            for (i in 0 until arr.length()) {
                val s = decode(arr.optJSONObject(i) ?: continue) ?: continue
                out += s
                if (out.size >= MAX_SESSIONS) break
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    // ---------------------------------------------------------------------------------------------
    // Shareable session summary (pure — JVM-tested).
    // Never contains a config link, key or password:
    // the record never stores one.
    // ---------------------------------------------------------------------------------------------

    fun exportText(s: Session, fa: Boolean): String {
        val sb = StringBuilder()
        sb.append(if (fa) "خلاصه نشست — ${sectionDisplay(s.section, true)}"
            else "Session summary — ${sectionDisplay(s.section, false)}").append("\n")
        if (s.name.isNotBlank()) sb.append(if (fa) "نام: " else "Name: ").append(s.name).append("\n")
        if (s.protocol.isNotBlank()) sb.append(if (fa) "پروتکل: " else "Protocol: ").append(s.protocol).append("\n")
        if (s.gateway.isNotBlank()) sb.append(if (fa) "سرور: " else "Gateway: ").append(s.gateway).append("\n")
        sb.append(if (fa) "شروع: " else "Started: ").append(formatDayTime(s.startMs)).append("\n")
        if (s.isOpen) {
            sb.append(if (fa) "وضعیت: هنوز متصل است" else "Status: still connected").append("\n")
            sb.append(if (fa) "مدت تا حالا: " else "Duration so far: ")
                .append(formatDuration(System.currentTimeMillis() - s.startMs)).append("\n")
        } else {
            sb.append(if (fa) "مدت: " else "Duration: ").append(formatDuration(s.durationMs)).append("\n")
        }
        sb.append(if (fa) "مصرف: " else "Data: ").append(formatBytes(s.bytes))
            .append(" (↓ ").append(formatBytes(s.rxBytes))
            .append(" / ↑ ").append(formatBytes(s.txBytes)).append(")\n")
        if (s.exitIp.isNotBlank() || s.exitCountry.isNotBlank()) {
            sb.append(if (fa) "خروج: " else "Exit: ")
            if (s.exitCountry.isNotBlank()) sb.append(s.exitCountry).append(" ")
            if (s.exitIp.isNotBlank()) sb.append(s.exitIp)
            sb.append("\n")
        } else {
            sb.append(if (fa) "خروج: اندازه‌گیری نشده" else "Exit: not measured").append("\n")
        }
        if (s.latencySamples.isNotEmpty()) {
            sb.append(if (fa) "پینگ: میانگین " else "Latency: avg ")
                .append(s.latencyAvgMs).append("ms")
                .append(if (fa) "، کمترین " else ", min ").append(s.latencyMinMs).append("ms")
                .append(if (fa) "، بیشترین " else ", max ").append(s.latencyMaxMs).append("ms\n")
        } else {
            sb.append(if (fa) "پینگ: اندازه‌گیری نشده" else "Latency: not measured").append("\n")
        }
        if (s.downPeakKbps > 0 || s.upPeakKbps > 0) {
            sb.append(if (fa) "بیشترین سرعت: ↓ " else "Peak speed: ↓ ")
                .append(s.downPeakKbps).append(" KB/s / ↑ ")
                .append(s.upPeakKbps).append(" KB/s\n")
        }
        if (s.quickRestart) sb.append(if (fa) "وصل مجدد پس از قطع کوتاه" else "Reconnected after a short drop").append("\n")
        if (s.endedWithError) {
            sb.append(if (fa) "پایان با خطا" else "Ended with an error")
            if (s.errorText.isNotBlank()) sb.append(": ").append(s.errorText)
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    // ---------------------------------------------------------------------------------------------
    // Persistence (Context — device only, never throws).
    // ---------------------------------------------------------------------------------------------

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun open(ctx: Context): Session? = try {
        val raw = prefs(ctx).getString(KEY_OPEN, "") ?: ""
        if (raw.isBlank()) null else decode(JSONObject(raw))
    } catch (_: Exception) { null }

    fun list(ctx: Context): List<Session> = try {
        decodeList(prefs(ctx).getString(KEY_SESSIONS, "") ?: "")
    } catch (_: Exception) { emptyList() }

    fun rev(ctx: Context): Int = try {
        prefs(ctx).getInt(KEY_REV, 0)
    } catch (_: Exception) { 0 }

    fun counters(ctx: Context): Pair<Long, Long> = try {
        val p = prefs(ctx)
        p.getLong(KEY_DROPS, 0L) to p.getLong(KEY_RECONNECTS, 0L)
    } catch (_: Exception) { 0L to 0L }

    /** (todayRx, todayTx, totalRx, totalTx) — today
     *  rolls over to zero when the stored date is not
     *  today. */
    fun totals(ctx: Context): LongArray = try {
        val p = prefs(ctx)
        val today = dayKey(System.currentTimeMillis())
        val stored = p.getString(KEY_TODAY_DATE, "") ?: ""
        val (tRx, tTx) = rolloverToday(stored, today,
            p.getLong(KEY_TODAY_RX, 0L), p.getLong(KEY_TODAY_TX, 0L))
        longArrayOf(tRx, tTx, p.getLong(KEY_TOTAL_RX, 0L), p.getLong(KEY_TOTAL_TX, 0L))
    } catch (_: Exception) { longArrayOf(0L, 0L, 0L, 0L) }

    /** Live bytes of the open session right now
     *  (rx, tx), from the baselines taken at begin().
     *  0/0 when no session is open. */
    fun openBytesNow(ctx: Context): Pair<Long, Long> {
        return try {
            val o = open(ctx) ?: return 0L to 0L
            val (rx, tx) = sessionBytesSplit(o.section)
            maxOf(0L, rx - o.baseRx) to maxOf(0L, tx - o.baseTx)
        } catch (_: Exception) { 0L to 0L }
    }

    /** Start a session. If one is already open it is
     *  closed first (superseded — a reconnect, not a
     *  drop: the old session simply ended unobserved).
     *  No-op when section is blank. */
    fun begin(ctx: Context, section: String, name: String?, protocol: String?, gateway: String?) {
        try {
            if (section.isBlank()) return
            val p = prefs(ctx)
            val now = System.currentTimeMillis()
            val prev = open(ctx)
            var quickRestart = false
            if (prev != null) {
                // Same session re-reported within 2s (the
                // engine hook and the page's poller racing
                // on the same connect): keep the one
                // record, fill in blanks, do NOT close and
                // reopen it (that would fake a reconnect).
                if (prev.section == section && now - prev.startMs < 2_000) {
                    val merged = prev.copy(
                        name = if (prev.name.isBlank()) name ?: "" else prev.name,
                        protocol = if (prev.protocol.isBlank()) protocol ?: "" else prev.protocol,
                        gateway = if (prev.gateway.isBlank()) gateway ?: "" else prev.gateway)
                    p.edit().putString(KEY_OPEN, encode(merged).toString()).apply()
                    return
                }
                // Close the superseded session the same
                // way end() would, minus the error path.
                // 1.73 (D): the closing session's own
                // byte source (its section decides).
                val (rx, tx) = sessionBytesSplit(prev.section)
                val r = maxOf(0L, rx - prev.baseRx)
                val t = maxOf(0L, tx - prev.baseTx)
                val closed = prev.copy(endMs = now, rxBytes = r, txBytes = t)
                finishClosed(ctx, p, closed, endedWithError = false, errorText = "")
                // Gap 0 (still open when the new one
                // started) is a reconnect by definition.
                quickRestart = true
            } else {
                val last = list(ctx).firstOrNull()
                if (last != null && last.endMs > 0) {
                    quickRestart = isQuickRestart(now - last.endMs)
                }
            }
            // 1.73 (D): baseline from this session's
            // own byte source (UID counters for warp,
            // TUN counters otherwise).
            val (rx, tx) = sessionBytesSplit(section)
            val sess = Session(
                section = section,
                name = name ?: "",
                protocol = protocol ?: "",
                gateway = gateway ?: "",
                startMs = now,
                endMs = 0L,
                rxBytes = 0L,
                txBytes = 0L,
                exitIp = "",
                exitCc = "",
                exitCountry = "",
                exitCity = "",
                latencyAvgMs = 0L,
                latencyMinMs = 0L,
                latencyMaxMs = 0L,
                latencySamples = emptyList(),
                downPeakKbps = 0L,
                upPeakKbps = 0L,
                quickRestart = quickRestart,
                endedWithError = false,
                errorText = "",
                baseRx = rx,
                baseTx = tx
            )
            val ed = p.edit()
            ed.putString(KEY_OPEN, encode(sess).toString())
            if (quickRestart) ed.putLong(KEY_RECONNECTS, p.getLong(KEY_RECONNECTS, 0L) + 1)
            ed.putInt(KEY_REV, p.getInt(KEY_REV, 0) + 1)
            ed.apply()
        } catch (_: Exception) {}
    }

    /** Close the open session, if any. [errorText] is
     *  the engine's last error (short, human-readable);
     *  blank/null means a clean end. When [section] is
     *  given, only a session of that section is closed
     *  — a WireGuard disconnect must never close a live
     *  v2ray record (and vice versa). */
    fun end(ctx: Context, errorText: String?, section: String? = null) {
        try {
            val p = prefs(ctx)
            val o = open(ctx) ?: return
            if (section != null && o.section != section) return
            // 1.73 (D): the closing session's own byte
            // source (its section decides).
            val (rx, tx) = sessionBytesSplit(o.section)
            val closed = o.copy(
                endMs = System.currentTimeMillis(),
                rxBytes = maxOf(0L, rx - o.baseRx),
                txBytes = maxOf(0L, tx - o.baseTx)
            )
            val (avg, min, max) = latencyStats(closed.latencySamples)
            val withLat = closed.copy(
                latencyAvgMs = avg, latencyMinMs = min, latencyMaxMs = max)
            finishClosed(ctx, p, withLat,
                endedWithError = !errorText.isNullOrBlank(),
                errorText = errorText?.take(120) ?: "")
        } catch (_: Exception) {}
    }

    /** Shared tail of begin()-supersede and end():
     *  drop accounting, keep/drop rule, counters,
     *  history prepend (newest first, capped), rev. */
    private fun finishClosed(
        ctx: Context,
        p: android.content.SharedPreferences,
        closed: Session,
        endedWithError: Boolean,
        errorText: String
    ) {
        try {
            val final = closed.copy(endedWithError = endedWithError, errorText = errorText)
            val isDrop = endedWithError || isFlap(final.durationMs)
            val ed = p.edit()
            ed.remove(KEY_OPEN)
            if (isDrop) ed.putLong(KEY_DROPS, p.getLong(KEY_DROPS, 0L) + 1)
            if (shouldKeep(final.durationMs, final.bytes)) {
                val list = list(ctx).toMutableList()
                list.add(0, final)
                while (list.size > MAX_SESSIONS) list.removeAt(list.size - 1)
                ed.putString(KEY_SESSIONS, encodeList(list))
                // Counters only for kept sessions (a
                // discarded <5s zero-byte attempt moved
                // ~0 bytes anyway).
                val today = dayKey(System.currentTimeMillis())
                val stored = p.getString(KEY_TODAY_DATE, "") ?: ""
                val (tRx, tTx) = rolloverToday(stored, today,
                    p.getLong(KEY_TODAY_RX, 0L), p.getLong(KEY_TODAY_TX, 0L))
                ed.putString(KEY_TODAY_DATE, today)
                ed.putLong(KEY_TODAY_RX, tRx + final.rxBytes)
                ed.putLong(KEY_TODAY_TX, tTx + final.txBytes)
                ed.putLong(KEY_TOTAL_RX, p.getLong(KEY_TOTAL_RX, 0L) + final.rxBytes)
                ed.putLong(KEY_TOTAL_TX, p.getLong(KEY_TOTAL_TX, 0L) + final.txBytes)
            }
            ed.putInt(KEY_REV, p.getInt(KEY_REV, 0) + 1)
            ed.apply()
        } catch (_: Exception) {}
    }

    /** Idempotent observer for the Monitoring page's
     *  poller (and any other observer): reconcile the
     *  open session with the live section right now.
     *  [section] null means nothing is live. Unknown
     *  name/protocol/gateway stay blank — the engine
     *  hooks record the real values on the normal path;
     *  this is only the safety net for paths they miss. */
    fun noteLive(ctx: Context, section: String?, name: String?, protocol: String?, gateway: String?) {
        try {
            val o = open(ctx)
            when {
                section.isNullOrBlank() -> {
                    if (o != null) end(ctx, null)
                }
                o == null -> begin(ctx, section, name, protocol, gateway)
                o.section != section -> {
                    end(ctx, null)
                    begin(ctx, section, name, protocol, gateway)
                }
                else -> Unit // same session, still live
            }
        } catch (_: Exception) {}
    }

    /** Append one latency sample (ms) to the open
     *  session; no-op when none is open. */
    fun recordLatency(ctx: Context, ms: Int) {
        try {
            val o = open(ctx) ?: return
            if (ms < 0) return
            val samples = (o.latencySamples + ms).takeLast(MAX_LATENCY_SAMPLES)
            val (avg, min, max) = latencyStats(samples)
            val updated = o.copy(
                latencySamples = samples,
                latencyAvgMs = avg, latencyMinMs = min, latencyMaxMs = max)
            prefs(ctx).edit().putString(KEY_OPEN, encode(updated).toString()).apply()
        } catch (_: Exception) {}
    }

    /** Peak live speeds (KB/s) into the open session. */
    fun recordPeak(ctx: Context, downKbps: Long, upKbps: Long) {
        try {
            val o = open(ctx) ?: return
            if (downKbps < 0 || upKbps < 0) return
            val updated = o.copy(
                downPeakKbps = maxOf(o.downPeakKbps, downKbps),
                upPeakKbps = maxOf(o.upPeakKbps, upKbps))
            prefs(ctx).edit().putString(KEY_OPEN, encode(updated).toString()).apply()
        } catch (_: Exception) {}
    }

    /** Exit IP + geo into the open session (measured
     *  through the tunnel by the caller). */
    fun setExit(ctx: Context, ip: String, cc: String, country: String, city: String) {
        try {
            val o = open(ctx) ?: return
            if (ip.isBlank() && country.isBlank()) return
            val updated = o.copy(exitIp = ip, exitCc = cc, exitCountry = country, exitCity = city)
            prefs(ctx).edit().putString(KEY_OPEN, encode(updated).toString()).apply()
        } catch (_: Exception) {}
    }
}
