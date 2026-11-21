package com.renamer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** 1.59 — PER-NETWORK winner profiles + the persistence for
 *  the shared clean-IP scanner (SharedScanner).
 *
 *  WHY PER NETWORK: a clean IP is clean ON ONE NETWORK.
 *  198.2/H2 wins on MCI (1.57: the fragmentation fix was
 *  MCI-gated and the device log proves it), but the same
 *  IP may be dead on Irancell or on WiFi — and the
 *  fragment setting itself is carrier-dependent (1.57).
 *  So every network (MCI / Irancell / Rightel / WiFi)
 *  keeps its OWN profile: its clean lists (WARP + the
 *  Cloudflare section's), its winner (gateway +
 *  transport + fragment setting), and its last sweep
 *  time. When the network changes, the app looks up
 *  THAT network's profile instead of re-using another
 *  network's winner — and a background re-scan fills
 *  a profile a network does not have yet.
 *
 *  THE NETWORK KEY (pure, JVM-testable): the carrier
 *  class from AshaCarrier (mci / irancell / rightel /
 *  unknown) + the transport. WiFi is ONE class ("wifi")
 *  — no SSID: reading it needs a location permission,
 *  which this build does NOT add. Ethernet is "fixed"
 *  (AshaCarrier's term). The key is for BUCKETING only;
 *  the fragment flag on a winner is computed from the
 *  CARRIER class at save time (a MCI SIM on WiFi still
 *  needs fragment OFF — 1.57).
 *
 *  THE STORE (one prefs file, one JSON string): the
 *  JSON is encoded/decoded by hand (pure Kotlin, no
 *  org.json) for exactly the reason AetherRungPlan is
 *  pure Kotlin + java.* only — so the JVM test can
 *  exercise the whole store (round-trip, migration,
 *  next-IP) without Android stubs. The schema is
 *  fixed and tiny: {"<key>": {"warp": [...], "cf":
 *  [...], "winner": {"gw": ..., "carrier": ...,
 *  "frag": ..., "at": ...}, "sweep": ...}}.
 *
 *  MIGRATION (1.58 → 1.59, once): the 1.58 GLOBAL
 *  winner (first saved record + global clean list +
 *  last winning carrier) is copied into the CURRENT
 *  network's profile. The global list itself is never
 *  deleted — the old readers that still need it keep
 *  working. The Cloudflare section's global list is
 *  NOT migrated (its readers keep the global pref;
 *  the per-network CF list fills from the next scan). */
object NetworkProfile {

    const val PREFS = "renamer_net_profiles"
    private const val KEY_STORE = "net_store_json"
    private const val KEY_MIGRATED = "net_migrated_v159"
    const val MAX_CLEAN_LIST = 16
    /** A sweep result is fresh for a day — the same
     *  window as the 1.58 fast path's winner age. */
    const val SWEEP_FRESH_MS = 24L * 60 * 60 * 1000

    data class NetData(
        var warpClean: List<String> = emptyList(),
        var cfClean: List<String> = emptyList(),
        var winnerGw: String = "",
        var winnerCarrier: String = "",
        var winnerFrag: Boolean = false,
        var winnerAt: Long = 0L,
        var lastSweepAt: Long = 0L
    )

    // ---- the network key (pure) ----

    /** Pure network key (JVM-testable). "wifi" is one
     *  class whatever the SIM is; mobile keys are the
     *  carrier class; ethernet is "fixed". */
    fun networkKeyFor(carrierClass: String, transport: String): String {
        val t = transport.trim().lowercase()
        val c = carrierClass.trim().lowercase().ifBlank { "unknown" }
        return when (t) {
            "wifi" -> "wifi"
            "mobile", "cellular" -> c
            "ethernet" -> "fixed"
            else -> c
        }
    }

    /** The active transport: "wifi" / "mobile" /
     *  "ethernet" / "unknown". Never throws. */
    fun transportOf(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "unknown"
            val net = cm.activeNetwork ?: return "unknown"
            val caps = cm.getNetworkCapabilities(net) ?: return "unknown"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "unknown"
            }
        } catch (_: Exception) { "unknown" }
    }

    /** The current network's key (carrier + transport). */
    fun currentKey(ctx: Context): String {
        val carrier = runCatching { AshaCarrier.carrierClass(ctx) }.getOrDefault("unknown")
        return networkKeyFor(carrier, transportOf(ctx))
    }

    /** The carrier class right now (for the fragment
     *  flag on a winner — the key alone cannot tell it
     *  on WiFi, where the key is just "wifi"). */
    fun currentCarrierClass(ctx: Context): String =
        runCatching { AshaCarrier.carrierClass(ctx) }.getOrDefault("unknown")

    // ---- the store: manual JSON (pure, JVM-testable) ----

    private fun escapeJson(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Encode the whole store. Pure. */
    fun encodeStore(store: Map<String, NetData>): String {
        val sb = StringBuilder()
        sb.append("{")
        var firstKey = true
        for ((key, d) in store) {
            if (!firstKey) sb.append(",")
            firstKey = false
            sb.append("\"").append(escapeJson(key)).append("\":{")
            sb.append("\"warp\":[")
            sb.append(d.warpClean.joinToString(",") { "\"" + escapeJson(it) + "\"" })
            sb.append("],\"cf\":[")
            sb.append(d.cfClean.joinToString(",") { "\"" + escapeJson(it) + "\"" })
            sb.append("],\"winner\":{\"gw\":\"").append(escapeJson(d.winnerGw))
                .append("\",\"carrier\":\"").append(escapeJson(d.winnerCarrier))
                .append("\",\"frag\":").append(if (d.winnerFrag) "true" else "false")
                .append(",\"at\":").append(d.winnerAt)
                .append("},\"sweep\":").append(d.lastSweepAt)
                .append("}")
        }
        sb.append("}")
        return sb.toString()
    }

    /** Minimal JSON parser for the store schema only
     *  (objects, arrays, strings with escapes, numbers,
     *  booleans, null). Returns null on any malformed
     *  input — the caller then treats the store as
     *  empty, never as corrupt data to trust. Pure. */
    private class JsonParser(private val s: String) {
        var pos = 0
        fun parseValue(): Any? {
            skipWs()
            if (pos >= s.length) return null
            return when (s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> if (s.startsWith("true", pos)) { pos += 4; true } else null
                'f' -> if (s.startsWith("false", pos)) { pos += 5; false } else null
                'n' -> if (s.startsWith("null", pos)) { pos += 4; null } else null
                else -> parseNumber()
            }
        }
        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }
        private fun parseObject(): Map<String, Any?>? {
            pos++ // {
            val out = mutableMapOf<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return out }
            while (pos < s.length) {
                skipWs()
                val k = parseString() ?: return null
                skipWs()
                if (pos >= s.length || s[pos] != ':') return null
                pos++
                val v = parseValue() ?: return null
                out[k] = v
                skipWs()
                if (pos >= s.length) return null
                when (s[pos]) {
                    ',' -> { pos++; continue }
                    '}' -> { pos++; return out }
                    else -> return null
                }
            }
            return null
        }
        private fun parseArray(): List<Any?>? {
            pos++ // [
            val out = mutableListOf<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return out }
            while (pos < s.length) {
                val v = parseValue() ?: return null
                out.add(v)
                skipWs()
                if (pos >= s.length) return null
                when (s[pos]) {
                    ',' -> { pos++; continue }
                    ']' -> { pos++; return out }
                    else -> return null
                }
            }
            return null
        }
        private fun parseString(): String? {
            if (pos >= s.length || s[pos] != '"') return null
            pos++
            val sb = StringBuilder()
            while (pos < s.length) {
                val ch = s[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) return null
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) return null
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: return null
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> return null
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            return null
        }
        private fun parseNumber(): Long? {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            while (pos < s.length && s[pos].isDigit()) pos++
            if (pos == start) return null
            return s.substring(start, pos).toLongOrNull()
        }
    }

    /** Decode the store JSON. Pure — malformed input
     *  yields an empty map, never an exception. */
    fun decodeStore(json: String): Map<String, NetData> {
        val out = mutableMapOf<String, NetData>()
        if (json.isBlank()) return out
        val root = try {
            JsonParser(json.trim()).parseValue() as? Map<*, *>
        } catch (_: Exception) { null } ?: return out
        for ((k, v) in root) {
            val key = k as? String ?: continue
            val obj = v as? Map<*, *> ?: continue
            val d = NetData()
            (obj["warp"] as? List<*>)?.let { arr ->
                d.warpClean = arr.mapNotNull { (it as? String)?.trim() }
                    .filter { it.isNotEmpty() }.distinct().take(MAX_CLEAN_LIST)
            }
            (obj["cf"] as? List<*>)?.let { arr ->
                d.cfClean = arr.mapNotNull { (it as? String)?.trim() }
                    .filter { it.isNotEmpty() }.distinct().take(MAX_CLEAN_LIST)
            }
            (obj["winner"] as? Map<*, *>)?.let { w ->
                d.winnerGw = (w["gw"] as? String)?.trim().orEmpty()
                d.winnerCarrier = (w["carrier"] as? String)?.trim().orEmpty()
                d.winnerFrag = w["frag"] as? Boolean ?: false
                d.winnerAt = (w["at"] as? Long) ?: 0L
            }
            d.lastSweepAt = (obj["sweep"] as? Long) ?: 0L
            out[key] = d
        }
        return out
    }

    // ---- Android glue (thin; the logic above is pure) ----

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadStore(ctx: Context): Map<String, NetData> {
        ensureMigrated(ctx)
        return try {
            decodeStore(prefs(ctx).getString(KEY_STORE, "") ?: "")
        } catch (_: Exception) { emptyMap() }
    }

    private fun saveStore(ctx: Context, store: Map<String, NetData>) {
        try {
            prefs(ctx).edit().putString(KEY_STORE, encodeStore(store)).apply()
        } catch (_: Exception) {}
    }

    private fun mutate(ctx: Context, key: String, f: (NetData) -> NetData) {
        val store = loadStore(ctx).toMutableMap()
        val cur = store[key] ?: NetData()
        store[key] = f(cur)
        saveStore(ctx, store)
    }

    fun warpCleanList(ctx: Context, key: String): List<String> =
        loadStore(ctx)[key]?.warpClean ?: emptyList()

    fun saveWarpCleanList(ctx: Context, key: String, list: List<String>) {
        val clean = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_CLEAN_LIST)
        mutate(ctx, key) { it.copy(warpClean = clean) }
    }

    /** Merge one verified gateway into a network's WARP
     *  clean list (most-recent first, capped) — the
     *  per-network form of WarpFree.saveCleanGateway. */
    fun mergeWarpClean(ctx: Context, key: String, ip: String) {
        val w = ip.trim()
        if (w.isEmpty()) return
        val cur = warpCleanList(ctx, key)
        if (cur.firstOrNull() == w) return
        val out = mutableListOf<String>()
        out += w
        for (x in cur) if (x !in out) out += x
        saveWarpCleanList(ctx, key, out.take(MAX_CLEAN_LIST))
    }

    fun cfCleanList(ctx: Context, key: String): List<String> =
        loadStore(ctx)[key]?.cfClean ?: emptyList()

    fun saveCfCleanList(ctx: Context, key: String, list: List<String>) {
        val clean = list.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_CLEAN_LIST)
        mutate(ctx, key) { it.copy(cfClean = clean) }
    }

    fun winner(ctx: Context, key: String): NetData? = loadStore(ctx)[key]

    /** Save a network's winner (gateway + transport +
     *  fragment flag for the CURRENT carrier). Pure
     *  apart from the store write. */
    fun saveWinner(ctx: Context, key: String, gatewayIp: String, carrier: String) {
        val gw = gatewayIp.trim()
        if (gw.isEmpty()) return
        val frag = AshaCarrier.fragmentOnForCarrier(currentCarrierClass(ctx))
        mutate(ctx, key) {
            it.copy(
                winnerGw = gw,
                winnerCarrier = carrier.trim().lowercase(),
                winnerFrag = frag,
                winnerAt = System.currentTimeMillis()
            )
        }
    }

    /** The winning transport for a network ("" when
     *  it has no winner yet) — the connect path starts
     *  with it. Pure apart from the store read. */
    fun winnerCarrier(ctx: Context, key: String): String =
        loadStore(ctx)[key]?.winnerCarrier.orEmpty()

    fun lastSweepAt(ctx: Context, key: String): Long =
        loadStore(ctx)[key]?.lastSweepAt ?: 0L

    fun touchSweep(ctx: Context, key: String) {
        mutate(ctx, key) { it.copy(lastSweepAt = System.currentTimeMillis()) }
    }

    /** The next clean IP for a network that has not
     *  been tried yet (pure pick rule — JVM-testable):
     *  the list head first, skipping tried IPs. */
    fun nextIp(list: List<String>, tried: Set<String>): String? {
        for (ip in list) {
            val t = ip.trim()
            if (t.isNotEmpty() && t !in tried) return t
        }
        return null
    }

    fun nextWarpCleanIp(ctx: Context, key: String, tried: Set<String>): String? =
        nextIp(warpCleanList(ctx, key), tried)

    /** Pure merge of a 1.58 global winner into one
     *  network's profile (JVM-testable): fills only
     *  what the network does not already have. */
    fun mergeGlobalInto(
        store: Map<String, NetData>,
        key: String,
        globalWarp: List<String>,
        winnerGw: String,
        winnerCarrier: String,
        winnerFrag: Boolean,
        winnerAt: Long
    ): Map<String, NetData> {
        val out = store.toMutableMap()
        val data = (out[key] ?: NetData()).copy()
        var changed = false
        if (data.warpClean.isEmpty() && globalWarp.isNotEmpty()) {
            data.warpClean = globalWarp.take(MAX_CLEAN_LIST)
            changed = true
        }
        if (data.winnerGw.isBlank() && winnerGw.isNotBlank()) {
            data.winnerGw = winnerGw
            data.winnerCarrier = winnerCarrier
            data.winnerFrag = winnerFrag
            data.winnerAt = winnerAt
            changed = true
        }
        if (changed) out[key] = data
        return out
    }

    /** One-time migration (1.58 → 1.59): the 1.58
     *  GLOBAL winner (first saved record + global
     *  clean list + last winning carrier) is copied
     *  into the CURRENT network's profile. Idempotent
     *  (flagged); a missing global winner is a no-op.
     *  Called lazily from loadStore — every per-network
     *  read migrates first, so no caller can read a
     *  pre-migration empty profile. */
    fun ensureMigrated(ctx: Context) {
        try {
            val p = prefs(ctx)
            if (p.getBoolean(KEY_MIGRATED, false)) return
            p.edit().putBoolean(KEY_MIGRATED, true).apply()
            val key = currentKey(ctx)
            val store = decodeStore(p.getString(KEY_STORE, "") ?: "")
            val rec = WarpFree.loadAll(ctx).firstOrNull()
            val merged = mergeGlobalInto(
                store, key,
                globalWarp = WarpFree.loadCleanListGlobal(ctx),
                winnerGw = rec?.gatewayIp.orEmpty(),
                winnerCarrier = rec?.alpn.orEmpty(),
                winnerFrag = AshaCarrier.fragmentOnForCarrier(currentCarrierClass(ctx)),
                winnerAt = rec?.createdAt ?: 0L
            )
            if (merged !== store) saveStore(ctx, merged)
        } catch (_: Exception) {
            // Migration must never break a fetch or a
            // connect — the per-network store simply
            // starts empty and fills from the next scan.
        }
    }
}

/** 1.59 — network-change monitor: when the user switches
 *  networks (MCI → Irancell → Rightel → WiFi), the new
 *  network's profile is filled by a background sweep —
 *  the app never waits on it, and the fetch/connect keep
 *  reading the cached per-network lists. Registered once
 *  per process (idempotent), on the main looper (safe
 *  from any calling thread), with the application
 *  context (no activity leak). Only a NEW usable
 *  network triggers a sweep; losing a network does not. */
object NetworkMonitor {
    private val started = AtomicBoolean(false)
    @Volatile private var lastKey: String? = null

    fun ensureStarted(appCtx: Context) {
        if (started.getAndSet(true)) return
        try {
            lastKey = NetworkProfile.currentKey(appCtx)
        } catch (_: Exception) { lastKey = null }
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                        ?: return@post
                    val req = NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            try {
                                val key = NetworkProfile.currentKey(appCtx)
                                val prev = lastKey
                                lastKey = key
                                if (prev != null && prev != key) {
                                    AppLog.append(appCtx, "warp",
                                        "شبکه عوض شد: $prev ← $key؛ اسکن پس‌زمینه برای شبکهٔ جدید شروع شد.",
                                        "Network changed: $prev → $key; background scan started for the new network.")
                                    SharedScanner.sweepIfNeeded(appCtx)
                                }
                            } catch (_: Exception) {}
                        }

                        override fun onLost(network: Network) {
                            try {
                                lastKey = NetworkProfile.currentKey(appCtx)
                            } catch (_: Exception) {}
                        }
                    })
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
