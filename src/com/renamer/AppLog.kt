package com.renamer

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Central app log (2.43): one persistent, readable
 *  record of what every section did — connection
 *  attempts with engine state, hop types and
 *  results, plus event-level entries for the rest.
 *  Privacy rule: NEVER message text, config links
 *  or keys here — only names, types and outcomes.
 *  The log is a plain readable list; anything
 *  copied out of it must stay safe to share. */
object AppLog {
    private const val PREFS = "renamer_applog"
    private const val KEY = "lines"
    const val CAP = 500

    private fun stamp(): String = try {
        SimpleDateFormat("HH:mm", Locale.US).format(Date())
    } catch (_: Exception) { "" }

    /** Append one precise entry. fa/en are resolved
     *  in the language active right now (same rule
     *  as the engine reports). */
    fun append(ctx: Context, section: String, fa: String, en: String, ru: String = "") {
        try {
            val text = if (AppLanguage.isRussian(ctx) && ru.isNotBlank()) ru
                else AppLanguage.t(ctx, fa, en)
            val line = "[${stamp()}] [$section] " + text
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = sp.getString(KEY, "") ?: ""
            val lines = (if (old.isBlank()) mutableListOf() else old.lines().toMutableList())
            lines += line
            while (lines.size > CAP) lines.removeAt(0)
            sp.edit().putString(KEY, lines.joinToString("\n")).apply()
        } catch (_: Exception) {}
    }

    fun text(ctx: Context): String = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
    } catch (_: Exception) { "" }

    /** 1.61 — one stored log line's kind, for
     *  RENDER-time styling only. The stored format
     *  stays "[HH:mm] [section] text" (CAP 500) —
     *  nothing here changes what is stored; other
     *  sections read it as before. Pure, so the JVM
     *  test asserts it on real 1.60 log lines.
     *
     *  SUMMARY: the lines a human reads first —
     *  "[✓]"/"[✗]" results and stage headers
     *  ("AETHER:", "GOOL:", "H3:", "H2:", "دریافت…",
     *  "اسکن…", "جلسهٔ…"). Checked FIRST: a summary
     *  line may mention "aether" and still be a
     *  summary ("[✓] GOOL H2 …").
     *  ENGINE: raw aether engine lines — timestamped
     *  "[2026-…T…Z … aether]" or containing
     *  "aether]"/"aether::". These render dimmer +
     *  smaller so they do not drown the summary.
     *  OTHER: anything else (plain section lines). */
    enum class LogLineKind { SUMMARY, ENGINE, OTHER }

    fun classifyLine(line: String): LogLineKind {
        // Strip the stored "[HH:mm] [section] " prefix;
        // a line without it (e.g. the WG dialog's WgLog
        // part) is classified as-is.
        val text = line.replaceFirst(
            Regex("^\\[\\d{2}:\\d{2}\\] \\[[^\\]]+\\] "), "")
        if (text.startsWith("[\u2713") || text.startsWith("[\u2717") ||
            text.startsWith("AETHER:") || text.startsWith("GOOL:") ||
            text.startsWith("GOOL ") || text.startsWith("H3:") ||
            text.startsWith("H2:") || text.startsWith("H3 ") ||
            text.startsWith("H2 ") || text.startsWith("XRAY:") ||
            text.startsWith("Xray ") || text.startsWith("دریافت") ||
            text.startsWith("اسکن") || text.startsWith("جلسهٔ")
        ) return LogLineKind.SUMMARY
        if (text.startsWith("[20") || text.contains("aether]") ||
            text.contains("aether::")
        ) return LogLineKind.ENGINE
        return LogLineKind.OTHER
    }

    /** 1.61 — render stored lines for display: summary
     *  lines full color + bold ("[✓]" lines keep the
     *  win-green of 2.68), raw engine lines dimmer +
     *  smaller. ALL lines are kept — nothing is
     *  dropped, only styled. [isDark] picks the
     *  dark/light palette. Never throws: on any
     *  failure the plain joined text is returned. */
    fun styledLog(lines: List<String>, isDark: Boolean): CharSequence {
        return try {
            val raw = lines.joinToString("\n")
            val sb = android.text.SpannableStringBuilder(raw)
            var start = 0
            for (line in lines) {
                val end = start + line.length
                when (classifyLine(line)) {
                    LogLineKind.SUMMARY -> {
                        if (line.contains("[\u2713]")) {
                            val winGreen = if (isDark) "#66BB6A" else "#2E7D32"
                            sb.setSpan(
                                android.text.style.ForegroundColorSpan(
                                    android.graphics.Color.parseColor(winGreen)),
                                start, end,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        sb.setSpan(
                            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                            start, end,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    LogLineKind.ENGINE -> {
                        val dim = if (isDark) "#8A8FA3" else "#757575"
                        sb.setSpan(
                            android.text.style.ForegroundColorSpan(
                                android.graphics.Color.parseColor(dim)),
                            start, end,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(
                            android.text.style.RelativeSizeSpan(0.85f),
                            start, end,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    LogLineKind.OTHER -> {
                    }
                }
                start = end + 1
            }
            sb
        } catch (_: Exception) {
            lines.joinToString("\n")
        }
    }

    fun clear(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        } catch (_: Exception) {}
    }

    /** 1.52 — remove ONLY the lines matching [pred] (the
     *  [warp]/wireguard lines for the WG log dialog's Clear
     *  button). clear() deletes the WHOLE key — every
     *  section's lines — so it must never be called for a
     *  per-section clear. */
    fun clearMatching(ctx: Context, pred: (String) -> Boolean) {
        try {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = sp.getString(KEY, "") ?: ""
            if (old.isBlank()) return
            val kept = old.lines().filter { !pred(it) }
            if (kept.size == old.lines().size) return
            if (kept.isEmpty()) sp.edit().remove(KEY).apply()
            else sp.edit().putString(KEY, kept.joinToString("\n")).apply()
        } catch (_: Exception) {}
    }

    /** Config type label for a link, for precise log
     *  entries. Never returns the link itself. */
    fun cfgType(ctx: Context, link: String): String {
        return try {
            when {
                XraySpeedtest.isGroup(link) -> AppLanguage.t(ctx, "گروه", "Group")
                XraySpeedtest.isChain(link) -> AppLanguage.t(ctx, "زنجیره", "Chain")
                XraySpeedtest.isCustom(link) -> AppLanguage.t(ctx, "کاستوم", "Custom")
                link.startsWith("vless://") -> {
                    val low = link.lowercase()
                    when {
                        low.contains("security=reality") -> "VLESS Reality"
                        low.contains("type=ws") -> "VLESS WS"
                        low.contains("type=grpc") -> "VLESS gRPC"
                        else -> "VLESS"
                    }
                }
                link.startsWith("vmess://") -> "VMess"
                link.startsWith("trojan://") -> "Trojan"
                link.startsWith("ss://") -> "Shadowsocks"
                link.startsWith("hysteria2://") || link.startsWith("hy2://") -> "Hysteria2"
                link.startsWith("WARP::") -> "WARP (MASQUE)"
                link.startsWith("masque://") -> "MASQUE"
                link.startsWith("tuic://") -> "TUIC"
                link.startsWith("socks://") || link.startsWith("http://") -> AppLanguage.t(ctx, "پروکسی", "Proxy")
                else -> AppLanguage.t(ctx, "نامشخص", "Unknown")
            }
        } catch (_: Exception) { AppLanguage.t(ctx, "نامشخص", "Unknown") }
    }

}
