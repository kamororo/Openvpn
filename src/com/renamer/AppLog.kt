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
     *  as the engine reports). ru is the explicit
     *  Russian form for dynamic lines (with values
     *  interpolated); when empty, the RU map is
     *  tried for static lines. */
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

    fun clear(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        } catch (_: Exception) {}
    }

}
