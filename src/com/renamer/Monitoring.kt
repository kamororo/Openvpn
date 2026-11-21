package com.renamer

import android.content.Context
import android.graphics.Color

/** App Monitoring (the "connection doctor" idea,
 *  deliberately NOT called a doctor): a compact,
 *  glanceable state board for every section.
 *
 *  Design rule — read only, never measure: every
 *  card is computed from state the app already
 *  keeps (live connection flags, prefs, the
 *  central log's last line per section). No new
 *  instrumentation, no background work, no new
 *  prefs, so this board cannot go stale when a
 *  section changes: it re-reads the same sources
 *  of truth on every open and resume.
 *
 *  Honesty rule — a green dot means a live
 *  session flag that is actually up right now.
 *  Anything else shows its last recorded event
 *  (amber when that event was a failure, grey
 *  when idle or unrecorded). Nothing here claims
 *  "healthy" from a log line alone. */
object Monitoring {
    const val DOT_GREEN = 0xFF2E7D32.toInt()
    const val DOT_AMBER = 0xFFF9A825.toInt()
    const val DOT_GREY = 0xFF9E9E9E.toInt()
    const val DOT_TEAL = 0xFF0F766E.toInt()

    data class Card(
        val key: String,
        val titleFa: String,
        val titleEn: String,
        val dot: Int,
        val lineFa: String,
        val lineEn: String,
        val time: String,
        val inventoryFa: String = "",
        val inventoryEn: String = "",
        // 2.91 (21d): show "↻ تست دوباره" on this card.
        val retest: Boolean = false
    )

    private data class LastEvent(val time: String, val text: String)

    private fun lineSection(line: String): String {
        return try {
            val first = line.indexOf(']')
            if (first < 0) return ""
            val second = line.indexOf(']', first + 1)
            if (second < 0) return ""
            line.substring(first + 1, second).trim().removePrefix("[").trim()
        } catch (_: Exception) { "" }
    }

    private fun lineTime(line: String): String {
        return try {
            if (line.startsWith("[") && line.length >= 7) line.substring(1, 6) else ""
        } catch (_: Exception) { "" }
    }

    /** "HH:mm" for a millis timestamp. */
    private fun fmtHm(millis: Long): String = try {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(millis))
    } catch (_: Exception) { "" }

    /** True when a stored "HH:mm" test clock time is
     *  older than ~1h. Blank or unparsable times are
     *  not stale — there is nothing to judge them by. */
    private fun isStaleHhmm(hhmm: String): Boolean {
        return try {
            if (hhmm.isBlank()) return false
            val parts = hhmm.split(":")
            if (parts.size != 2) return false
            val h = parts[0].toInt()
            val m = parts[1].toInt()
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, h)
            cal.set(java.util.Calendar.MINUTE, m)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            var diff = System.currentTimeMillis() - cal.timeInMillis
            if (diff < 0) diff += 24 * 60 * 60 * 1000L
            diff > 60 * 60 * 1000L
        } catch (_: Exception) { false }
    }

    /** Body of a stored line, without its time and
     *  section tags, shortened for a card. */
    private fun lineBody(line: String): String {
        return try {
            val second = line.indexOf(']', line.indexOf(']') + 1)
            val body = if (second >= 0 && second + 1 < line.length) line.substring(second + 1).trim() else line
            if (body.length > 90) body.substring(0, 90) + "…" else body
        } catch (_: Exception) { line }
    }

    private fun isFailure(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("وصل نشد") || t.contains("ناموفق") || t.contains("خطا") ||
            t.contains("failed") || t.contains("error")
    }

    private fun lastEvents(ctx: Context): Map<String, LastEvent> {
        val out = mutableMapOf<String, LastEvent>()
        try {
            val text = AppLog.text(ctx)
            if (text.isBlank()) return out
            for (line in text.lines()) {
                if (line.isBlank()) continue
                val sec = lineSection(line)
                if (sec.isBlank()) continue
                out[sec] = LastEvent(lineTime(line), lineBody(line))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun targetFa(target: String): String = when (target) {
        "wireguard" -> "وایرگارد"
        "amnezia" -> "امنزیا"
        "openvpn" -> "OpenVPN"
        "emergency" -> "اضطراری"
        "cloudflare" -> "کلودفلر"
        // 1.29: "chain" is no longer a Smart target —
        // a stale saved value falls to v2ray below.
        else -> "v2ray"
    }

    /** Plain-language form of a raw engine error.
     *  Only known substrings are translated; anything
     *  else is shown raw, never guessed. */
    private fun plainError(text: String): String {
        val t = text
        return when {
            t.contains("BackendException") -> "خطای بک‌اند وایرگارد"
            t.contains("VPN permission denied") -> "مجوز VPN داده نشده"
            t.contains("UNKNOWN/UNSUPPORTED OPTIONS") -> "گزینه ناشناخته در پروفایل"
            t.contains("config error") -> "خطای پیکربندی"
            t.contains("No implementation found") -> "موتور با این گزینه سازگار نیست"
            else -> t
        }
    }

    /** One card per section, in home order. */
    fun collect(ctx: Context): List<Card> {
        val last = lastEvents(ctx)
        // 2.91: per-section test state, for the
        // running / failed / stale overrides below.
        var pingRunning = false
        var pingError = ""
        try {
            val subs = ctx.getSharedPreferences("renamer_subs", Context.MODE_PRIVATE)
            pingRunning = subs.getBoolean("ping_running", false)
            pingError = subs.getString("ping_error", "") ?: ""
        } catch (_: Exception) {}
        var wgRunning = false
        var wgError = ""
        var wgStale = false
        var wgTestTime = ""
        // Inventory + last-test health per section,
        // all from stores the sections already keep.
        val invFa = mutableMapOf<String, String>()
        val invEn = mutableMapOf<String, String>()
        try {
            val servers = ctx.getSharedPreferences("renamer_vpn", Context.MODE_PRIVATE)
                .getString("servers", "")?.lines()
                ?.filter { it.isNotBlank() && !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it) }
                ?: emptyList()
            if (servers.isNotEmpty()) {
                val dj = try {
                    org.json.JSONObject(ctx.getSharedPreferences("renamer_subs", Context.MODE_PRIVATE)
                        .getString("ping_delays", "{}") ?: "{}")
                } catch (_: Exception) { org.json.JSONObject() }
                if (dj.length() > 0) {
                    val healthy = servers.count { dj.optInt(XraySpeedtest.dedupKey(it), -1) >= 0 }
                    // 2.58: the last ping sweep's
                    // clock time, from the last
                    // [ping] central-log line.
                    val pingTime = last["ping"]?.time ?: ""
                    val pingSuffixFa = if (pingTime.isNotBlank()) " • تست $pingTime" else ""
                    val pingSuffixEn = if (pingTime.isNotBlank()) " • tested $pingTime" else ""
                    invFa["v2ray"] = "${servers.size} کانفیگ • $healthy سالم از آخرین تست$pingSuffixFa"
                    invEn["v2ray"] = "${servers.size} configs • $healthy healthy at last test$pingSuffixEn"
                } else {
                    invFa["v2ray"] = "${servers.size} کانفیگ • هنوز تست نشده"
                    invEn["v2ray"] = "${servers.size} configs • not tested yet"
                }
            }
        } catch (_: Exception) {}
        try {
            val wgArr = org.json.JSONArray(ctx.getSharedPreferences("renamer_wg", Context.MODE_PRIVATE)
                .getString("wg_list", "[]") ?: "[]")
            val wgNames = (0 until wgArr.length()).mapNotNull {
                wgArr.optJSONObject(it)?.optString("name", "")?.takeIf { it.isNotBlank() }
            }
            val n = wgNames.size
            if (n > 0) {
                // 2.59: WG health from the WG test
                // (WgTestService, separate Xray core,
                // never touches a live session).
                // Amnezia tunnels are untestable, not
                // dead, and shown as such.
                val wt = ctx.getSharedPreferences("renamer_wg_test", Context.MODE_PRIVATE)
                wgRunning = wt.getBoolean("wg_test_running", false)
                wgError = wt.getString("wg_test_error", "") ?: ""
                val tested = wt.getBoolean("wg_test_done", false)
                val res = try {
                    org.json.JSONObject(wt.getString("wg_test_results", "{}") ?: "{}")
                } catch (_: Exception) { org.json.JSONObject() }
                val untestable = wt.getInt("wg_test_untestable", 0)
                val untestableNames = try {
                    val a = org.json.JSONArray(wt.getString("wg_test_untestable_names", "[]") ?: "[]")
                    (0 until a.length()).mapNotNull { a.optString(it, "")?.takeIf { it.isNotBlank() } }.toSet()
                } catch (_: Exception) { emptySet<String>() }
                wgTestTime = wt.getString("wg_test_time", "") ?: ""
                if (tested && (res.length() > 0 || untestable > 0)) {
                    var healthy = 0
                    for (key in res.keys()) if (res.optBoolean(key, false)) healthy++
                    // 2.91: a tunnel counts as healthy
                    // if its probe was false BUT it has
                    // a recent real last-connect
                    // (wg_last_ok_$name, read from
                    // renamer_wg, written when a tunnel
                    // reaches UP). NOTE: no writer for
                    // that key exists in the tree yet, so
                    // this branch is inert until the WG
                    // owner adds the write — live tunnels
                    // already count as healthy via
                    // WgTestService, which records them
                    // true in the results.
                    var lastOk = 0L
                    try {
                        val wgPrefs = ctx.getSharedPreferences("renamer_wg", Context.MODE_PRIVATE)
                        val now = System.currentTimeMillis()
                        for (name in wgNames) {
                            if (res.optBoolean(name, false)) continue
                            val t = wgPrefs.getLong("wg_last_ok_$name", 0L)
                            if (t > 0 && now - t < 24 * 60 * 60 * 1000L) {
                                healthy++
                                if (t > lastOk) lastOk = t
                            }
                        }
                    } catch (_: Exception) {}
                    // 2.91: staleness — the stored test
                    // time is older than ~1h, or the
                    // results no longer match the current
                    // tunnel names (added, removed or
                    // renamed since the test ran).
                    val probedNames = mutableSetOf<String>()
                    for (key in res.keys()) probedNames += key
                    val covered = probedNames + untestableNames
                    wgStale = isStaleHhmm(wgTestTime) || covered != wgNames.toSet()
                    val probed = res.length()
                    if (wgStale) {
                        invFa["wireguard"] = "تست قدیمی — دوباره تست کن"
                        invEn["wireguard"] = "Test is stale — test again"
                    } else {
                        val timeFa = if (wgTestTime.isNotBlank()) " • تست $wgTestTime" else ""
                        val timeEn = if (wgTestTime.isNotBlank()) " • tested $wgTestTime" else ""
                        val okFa = if (lastOk > 0) " • آخرین وصل ${fmtHm(lastOk)}" else ""
                        val okEn = if (lastOk > 0) " • last connected ${fmtHm(lastOk)}" else ""
                        // 2.91: one explicit denominator
                        // for card and log alike — H
                        // healthy, U untestable, R probed.
                        invFa["wireguard"] = "$n تونل • $healthy سالم • $untestable تست نشد • $probed پروب شد$timeFa$okFa"
                        invEn["wireguard"] = "$n tunnels • $healthy healthy • $untestable not tested • $probed probed$timeEn$okEn"
                    }
                } else {
                    invFa["wireguard"] = "$n تونل"
                    invEn["wireguard"] = "$n tunnels"
                }
            }
        } catch (_: Exception) {}
        try {
            val ovpnArr = org.json.JSONArray(ctx.getSharedPreferences("renamer_ovpn", Context.MODE_PRIVATE)
                .getString("ovpn_list", "[]") ?: "[]")
            val ovpnNames = (0 until ovpnArr.length()).mapNotNull {
                ovpnArr.optJSONObject(it)?.optString("name", "")?.takeIf { it.isNotBlank() }
            }
            if (ovpnNames.isNotEmpty()) {
                // 2.91: healthy = profiles whose LAST
                // REAL dial succeeded, from the dial
                // records the OVPN section itself keeps
                // (renamer_ovpn: ovpn_dial_ok_$name /
                // ovpn_dial_time_$name, written by
                // recordDial on real dials). Only
                // records under a CURRENT profile name
                // count — a renamed profile's record
                // stays orphaned under its old name
                // until the rename migrates the keys
                // (OpenVpnActivity, not this file).
                val op = ctx.getSharedPreferences("renamer_ovpn", Context.MODE_PRIVATE)
                var healthy = 0
                var latest = 0L
                for (name in ovpnNames) {
                    if (!op.contains("ovpn_dial_time_$name")) continue
                    if (!op.getBoolean("ovpn_dial_ok_$name", false)) continue
                    healthy++
                    val t = op.getLong("ovpn_dial_time_$name", 0L)
                    if (t > latest) latest = t
                }
                // 3.07: session-proven profiles — the AUTH+PUSH probe
                // (ovpn_probe_session_$name == 1, fresh cache only),
                // counted DISTINCT from the last-real-dial healthy
                // count above. The probe keys are never the dial keys.
                val sessionProven = try {
                    OvpnSessionProbe.sessionProvenNames(ctx, ovpnNames).size
                } catch (_: Exception) { 0 }
                val sessFa = if (sessionProven > 0) " • $sessionProven اثبات‌شده با نشست" else ""
                val sessEn = if (sessionProven > 0) " • $sessionProven session-proven" else ""
                if (healthy > 0) {
                    invFa["openvpn"] = "${ovpnNames.size} پروفایل • $healthy سالم • آخرین وصل ${fmtHm(latest)}$sessFa"
                    invEn["openvpn"] = "${ovpnNames.size} profiles • $healthy healthy • last connected ${fmtHm(latest)}$sessEn"
                } else {
                    invFa["openvpn"] = "${ovpnNames.size} پروفایل$sessFa"
                    invEn["openvpn"] = "${ovpnNames.size} profiles$sessEn"
                }
            }
        } catch (_: Exception) {}
        val cards = mutableListOf<Card>()
        fun eventCard(
            key: String, titleFa: String, titleEn: String,
            live: Boolean, liveFa: String, liveEn: String
        ) {
            val ev = last[key]
            when {
                live -> cards += Card(key, titleFa, titleEn, DOT_GREEN, liveFa, liveEn, ev?.time ?: "")
                ev != null -> cards += Card(
                    key, titleFa, titleEn,
                    if (isFailure(ev.text)) DOT_AMBER else DOT_GREY,
                    ev.text, ev.text, ev.time
                )
                else -> cards += Card(key, titleFa, titleEn, DOT_GREY,
                    "رکوردی نیست", "No records yet", "")
            }
        }

        // v2ray: live only for a plain v2ray session
        // (an emergency session also runs through
        // VpnClientService, tagged "emergency").
        val vpnLive = try { VpnClientService.isRunning } catch (_: Exception) { false }
        val vpnTag = try { VpnClientService.sessionTag } catch (_: Exception) { null }
        val vpnName = try { VpnClientService.currentName } catch (_: Exception) { null }
        val vpnErr = try { VpnClientService.lastError } catch (_: Exception) { null }
        if (vpnLive && vpnTag != "emergency") {
            val name = if (!vpnName.isNullOrBlank()) ": $vpnName" else ""
            // 2.58: the truth-verifier verdict for
            // this live session, when the toggle
            // probed it (PROVEN/DEAD only; UNKNOWN
            // means not probed yet, never shown).
            val verify = try {
                ctx.getSharedPreferences("renamer_verify", Context.MODE_PRIVATE)
                    .getString("verify_state", "") ?: ""
            } catch (_: Exception) { "" }
            val verifyFa = when (verify) {
                "PROVEN" -> " • راستی‌آزمایی: ترافیک عبور می‌کند"
                "DEAD" -> " • راستی‌آزمایی: ترافیک عبور نمی‌کند"
                else -> ""
            }
            val verifyEn = when (verify) {
                "PROVEN" -> " • verified: traffic passes"
                "DEAD" -> " • verified: no traffic passes"
                else -> ""
            }
            cards += Card("v2ray", "v2ray", "v2ray", DOT_GREEN,
                "متصل$name$verifyFa", "Connected$name$verifyEn", last["v2ray"]?.time ?: "")
        } else if (!vpnErr.isNullOrBlank()) {
            cards += Card("v2ray", "v2ray", "v2ray", DOT_AMBER,
                "آخرین خطا: ${plainError(lineBody(vpnErr))}", "Last error: ${lineBody(vpnErr)}",
                last["v2ray"]?.time ?: "")
        } else {
            eventCard("v2ray", "v2ray", "v2ray", false, "", "")
        }

        // WireGuard: ground truth is the tunnel
        // OBJECTS' states (WgDirect.isReallyUp),
        // never the backend name set (isLive) or
        // the widget pref — both go stale when
        // Android revokes WG for another VPN.
        val wgLive = try { WgDirect.isReallyUp(ctx) } catch (_: Exception) { false }
        eventCard("wireguard", "وایرگارد و امنزیا", "WireGuard & Amnezia",
            wgLive, "متصل است", "Connected")

        // OpenVPN: live flag + last error are both
        // kept by the engine itself.
        val ovpnLive = try { OvpnEngine.isConnected } catch (_: Exception) { false }
        val ovpnName = try { OvpnEngine.activeName } catch (_: Exception) { null }
        val ovpnErr = try { OvpnEngine.lastError } catch (_: Exception) { null }
        if (ovpnLive) {
            val name = if (!ovpnName.isNullOrBlank()) ": ${TunnelBear.displayNameForStored(ovpnName)}" else ""
            cards += Card("openvpn", "OpenVPN", "OpenVPN", DOT_GREEN,
                "متصل$name", "Connected$name", last["openvpn"]?.time ?: "")
        } else if (!ovpnErr.isNullOrBlank()) {
            cards += Card("openvpn", "OpenVPN", "OpenVPN", DOT_AMBER,
                "آخرین خطا: ${plainError(lineBody(ovpnErr))}", "Last error: ${lineBody(ovpnErr)}",
                last["openvpn"]?.time ?: "")
        } else {
            eventCard("openvpn", "OpenVPN", "OpenVPN", false, "", "")
        }

        // Emergency: a live emergency session is a
        // v2ray session tagged "emergency".
        eventCard("emergency", "اتصال اضطراری", "Emergency connect",
            vpnLive && vpnTag == "emergency", "در حال اتصال اضطراری", "Emergency session active")

        // Smart: a setting, not a session — teal
        // when enabled, grey when off, plus target
        // and selected-app count from its prefs.
        try {
            val sp = ctx.getSharedPreferences("renamer_smart", Context.MODE_PRIVATE)
            val enabled = sp.getBoolean("smart_enabled", false)
            val target = sp.getString("smart_target", "v2ray") ?: "v2ray"
            val apps = sp.getString("smart_apps", "")?.lines()?.count { it.isNotBlank() } ?: 0
            val ev = last["smart"]
            if (enabled) {
                cards += Card("smart", "اتصال هوشمند", "Smart Connect", DOT_TEAL,
                    "فعال — هدف: ${targetFa(target)}، $apps برنامه",
                    "On — target: $target, $apps apps", ev?.time ?: "")
            } else {
                cards += Card("smart", "اتصال هوشمند", "Smart Connect", DOT_GREY,
                    "خاموش", "Off", ev?.time ?: "")
            }
        } catch (_: Exception) {
            eventCard("smart", "اتصال هوشمند", "Smart Connect", false, "", "")
        }

        // Cloudflare: one-shot work — last recorded
        // event is the honest signal, never a guess.
        // 1.29: the subs card is gone with the section.
        eventCard("cloudflare", "کلودفلر", "Cloudflare", false, "", "")

        return cards.map {
            var invF = invFa[it.key] ?: ""
            var invE = invEn[it.key] ?: ""
            var lineF = it.lineFa
            var lineE = it.lineEn
            when (it.key) {
                "v2ray" -> {
                    // 2.91 (21e): while the ping test
                    // runs, the card says so — unless
                    // it is live right now, where the
                    // live line stays (the dot semantics
                    // never change: green = live only).
                    // A test that ended in error hides
                    // the stale number instead.
                    if (pingRunning && it.dot != DOT_GREEN) {
                        lineF = "در حال تست…"
                        lineE = "Testing…"
                    } else if (pingError.isNotBlank() && !pingRunning) {
                        invF = "تست ناموفق — دوباره تست کن"
                        invE = "Test failed — test again"
                    }
                }
                "wireguard" -> {
                    if (wgRunning && it.dot != DOT_GREEN) {
                        lineF = "در حال تست…"
                        lineE = "Testing…"
                    } else if (wgError.isNotBlank() && !wgRunning) {
                        invF = "تست ناموفق — دوباره تست کن"
                        invE = "Test failed — test again"
                    } else if (wgStale) {
                        invF = "تست قدیمی — دوباره تست کن"
                        invE = "Test is stale — test again"
                    }
                }
            }
            it.copy(
                inventoryFa = invF,
                inventoryEn = invE,
                lineFa = lineF,
                lineEn = lineE,
                // 2.91 (21d): per-card retest, v2ray
                // and WG only.
                retest = it.key == "v2ray" || it.key == "wireguard"
            )
        }
    }

    /** How many sections hold a live session right
     *  now (green dots only). */
    fun liveCount(cards: List<Card>): Int = cards.count { it.dot == DOT_GREEN }

    /** Latest event time across all cards, or "". */
    fun latestTime(cards: List<Card>): String =
        cards.mapNotNull { it.time.ifBlank { null } }.maxOrNull() ?: ""

    /** "What worked" — the engine's recent winning
     *  methods, read from recent v2ray log lines
     *  only (no new measurement). Most recent
     *  first, distinct, at most 3. */
    fun winningMethods(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        try {
            val lines = AppLog.text(ctx).lines().asReversed()
            for (line in lines) {
                if (out.size >= 3) break
                val m = Regex("(?:روش برنده:|روش:|winning method:|method:)\\s*([^؛;]+)")
                    .find(line)?.groupValues?.getOrNull(1)?.trim()
                if (!m.isNullOrBlank() && m !in out &&
                    !m.contains("هیچ روشی") && !m.contains("no method")) {
                    out += m
                }
            }
        } catch (_: Exception) {}
        return out
    }
}
