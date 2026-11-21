package com.renamer

/** 1.74 (C) — PURE free-warp last-connection logic: NO Android
 *  imports, so the JVM tests compile and run it standalone
 *  (TestMain174C). The home «آخرین اتصال» card and the
 *  last-connection widget both read the same prefs QuickConnect
 *  writes; this file is the warp half of that contract:
 *
 *  - METHOD_WARP is the method value QuickConnect records for a
 *    verified free-warp connection ("warp" — the same tag the
 *    VpnClientService session carries).
 *  - resolve() picks the EXACT record the card must reconnect
 *    to: the record whose saved card name ("free warp",
 *    "free warp 2", ...) equals the recorded config name.
 *    A name that matches nothing resolves to null — the card
 *    then opens the WireGuard page instead of connecting a
 *    DIFFERENT warp record (the 2.81 exact-config rule).
 *    There is deliberately NO fallback to "first" or "active":
 *    a re-fetch keeps the same card name (WarpFree.deliver
 *    removes the same-rung record before naming the new one),
 *    so a name lookup survives a re-fetch; a deleted name
 *    must not silently become another record.
 *  - displayMethodName() is the section name for the card's
 *    line 1 («آخرین اتصال وارپ رایگان» / "Last connection:
 *    Free WARP"); the card's line 2 is the record's own name
 *    ("free warp"), which configDisplayName() passes through
 *    unchanged (a plain name, not a link — QuickConnect's
 *    link branch never sees it).
 */
object WarpLastConnectPure {

    const val METHOD_WARP = "warp"

    /** One saved free-warp record, by the two fields the
     *  reconnect needs: the stable id and the card name. */
    data class WarpRef(val id: String, val name: String)

    /** The record whose card name is exactly [recordedName],
     *  or null when there is none (blank name, or the record
     *  was deleted / never existed). Never falls back. */
    fun resolve(records: List<WarpRef>, recordedName: String?): WarpRef? {
        val name = recordedName?.trim().orEmpty()
        if (name.isEmpty()) return null
        return records.firstOrNull { it.name == name }
    }

    /** The warp section's name for the card's line 1. */
    fun displayMethodName(english: Boolean): String =
        if (english) "Free WARP" else "وارپ رایگان"

    /** The card's line-2 name for a recorded warp config:
     *  the record's own name, as-is. Null when nothing was
     *  recorded (Emergency-style: no config to show). */
    fun configDisplayName(config: String?): String? {
        val c = config?.trim().orEmpty()
        return if (c.isEmpty()) null else c
    }
}
