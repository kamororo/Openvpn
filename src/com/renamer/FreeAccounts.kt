package com.renamer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dual free accounts (v1.1). Each free account is
 * 2 GB per month, provider-side. Storage is a LIST
 * of accounts (not one), each with email, password,
 * createdAt, windowStart, bytesUsed, state and
 * country. States: "active" (the one in use),
 * "backup" (ready to take over), "resting" (used
 * up — its 2 GB recharges 31 days after its
 * windowStart, then it may become a backup again).
 *
 * Rules (user order, v1.1):
 * - One tap creates TWO accounts; the first ready
 *   connects, the second becomes the backup.
 * - Connect failure on the active account fails
 *   over to the backup automatically, and a fresh
 *   backup is created silently.
 * - Usage failover at ~1900 MB in the 31-day
 *   window (hard ceiling 1950 MB): switch to the
 *   backup (new tunnel up, old dropped — at most
 *   a momentary blip), then create a fresh backup.
 * - NEVER delete a free account. An exhausted
 *   account rests; after 31 days its window resets
 *   and it may re-enter the backup list.
 * - Both accounts are created for the selected
 *   country; the backup follows the same country
 *   when it takes over.
 *
 * Bytes come from the ovpn3 client's transport
 * stats (bytesIn + bytesOut), polled by
 * Ovpn3VpnService while the free tunnel is up —
 * the same measurement the engine already
 * reports, never an invented one.
 */
object FreeAccounts {
    private const val PREFS = "renamer_ovpn"
    private const val KEY_LIST = "tb_accounts_v2"
    const val WINDOW_MS = 31L * 24 * 60 * 60 * 1000
    const val WARN_BYTES = 1900L * 1024 * 1024
    const val HARD_BYTES = 1950L * 1024 * 1024

    data class Account(
        val id: String,
        val email: String,
        val password: String,
        val createdAt: Long,
        var windowStart: Long,
        var bytesUsed: Long,
        var state: String, // active | backup | resting
        var country: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("email", email); put("password", password)
            put("createdAt", createdAt); put("windowStart", windowStart)
            put("bytesUsed", bytesUsed); put("state", state); put("country", country)
        }
        companion object {
            fun fromJson(o: JSONObject): Account? = try {
                Account(
                    id = o.optString("id").takeIf { it.isNotBlank() }
                        ?: ("acc-" + o.optString("email").hashCode().toString()),
                    email = o.optString("email"),
                    password = o.optString("password"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    windowStart = o.optLong("windowStart", o.optLong("createdAt", System.currentTimeMillis())),
                    bytesUsed = o.optLong("bytesUsed", 0),
                    state = o.optString("state", "backup").takeIf { it.isNotBlank() } ?: "backup",
                    country = o.optString("country", TunnelBear.DEFAULT_COUNTRY)
                ).takeIf { it.email.isNotBlank() && it.password.isNotBlank() }
            } catch (_: Exception) { null }
        }
    }

    @Synchronized
    fun list(ctx: Context): MutableList<Account> {
        return try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LIST, null) ?: return mutableListOf()
            val arr = JSONArray(raw)
            val out = mutableListOf<Account>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { Account.fromJson(it)?.let { a -> out += a } }
            }
            out
        } catch (_: Exception) { mutableListOf() }
    }

    @Synchronized
    private fun save(ctx: Context, accounts: List<Account>) {
        try {
            val arr = JSONArray()
            for (a in accounts) arr.put(a.toJson())
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LIST, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    /** v1.0 stored one account in tb_username /
     *  tb_password: adopt it as the active account
     *  (window starts now — its real age is
     *  unknown, so "now" is the honest start). */
    @Synchronized
    fun migrateLegacy(ctx: Context) {
        try {
            if (list(ctx).isNotEmpty()) return
            val acc = TunnelBear.getLegacyAccount(ctx) ?: return
            val now = System.currentTimeMillis()
            val a = Account(
                id = "acc-" + now,
                email = acc.first, password = acc.second,
                createdAt = now, windowStart = now, bytesUsed = 0,
                state = "active", country = TunnelBear.selectedCountry(ctx)
            )
            save(ctx, listOf(a))
        } catch (_: Exception) {}
    }

    @Synchronized
    fun active(ctx: Context): Account? = list(ctx).firstOrNull { it.state == "active" }

    @Synchronized
    fun backup(ctx: Context): Account? = list(ctx).firstOrNull { it.state == "backup" }

    @Synchronized
    fun hasBackup(ctx: Context): Boolean = backup(ctx) != null

    @Synchronized
    fun add(ctx: Context, email: String, password: String, country: String, asActive: Boolean): Account {
        val accounts = list(ctx)
        val now = System.currentTimeMillis()
        val a = Account(
            id = "acc-" + now + "-" + (accounts.size + 1),
            email = email, password = password,
            createdAt = now, windowStart = now, bytesUsed = 0,
            state = if (asActive) "active" else "backup",
            country = country
        )
        if (asActive) accounts.forEach { if (it.state == "active") it.state = "resting" }
        accounts += a
        save(ctx, accounts)
        // Keep the legacy single-account keys mirroring
        // the active account, so every existing caller
        // (probe, relay, service credential fill) keeps
        // working unchanged.
        if (asActive) TunnelBear.saveLegacyAccount(ctx, email, password)
        return a
    }

    /** A resting account whose 31-day window has
     *  passed: its 2 GB recharged provider-side, so
     *  it may become a backup again (window and
     *  bytes reset on re-entry). */
    @Synchronized
    fun eligibleResting(ctx: Context): Account? {
        val now = System.currentTimeMillis()
        return list(ctx).firstOrNull {
            it.state == "resting" && now - it.windowStart >= WINDOW_MS
        }
    }

    /** Reuse an eligible resting account as the
     *  backup (window/bytes reset). Null when none
     *  is eligible. */
    @Synchronized
    fun reuseRestingAsBackup(ctx: Context, country: String): Account? {
        val accounts = list(ctx)
        val now = System.currentTimeMillis()
        val cand = accounts.firstOrNull {
            it.state == "resting" && now - it.windowStart >= WINDOW_MS
        } ?: return null
        cand.state = "backup"
        cand.windowStart = now
        cand.bytesUsed = 0
        cand.country = country
        save(ctx, accounts)
        return cand
    }

    /** Promote the backup to active (it follows the
     *  current selected country); the old active
     *  becomes resting with its window untouched.
     *  Returns the new active account, or null when
     *  there is no backup. */
    @Synchronized
    fun promoteBackup(ctx: Context): Account? {
        val accounts = list(ctx)
        val bk = accounts.firstOrNull { it.state == "backup" } ?: return null
        val country = TunnelBear.selectedCountry(ctx)
        for (a in accounts) {
            if (a.state == "active") a.state = "resting"
        }
        bk.state = "active"
        bk.country = country
        save(ctx, accounts)
        TunnelBear.saveLegacyAccount(ctx, bk.email, bk.password)
        return bk
    }

    /** Add tunnel bytes to the active account's
     *  31-day window total. Called from the stats
     *  poller (every ~5s while the free tunnel is
     *  up); a no-op when there is no active free
     *  account. */
    @Synchronized
    fun addBytes(ctx: Context, delta: Long) {
        if (delta <= 0) return
        val accounts = list(ctx)
        val a = accounts.firstOrNull { it.state == "active" } ?: return
        a.bytesUsed += delta
        save(ctx, accounts)
    }

    @Synchronized
    fun activeBytes(ctx: Context): Long = active(ctx)?.bytesUsed ?: 0L

    /** True when the active account is at/past the
     *  usage warn level (1900 MB) inside its window. */
    @Synchronized
    fun activeNearLimit(ctx: Context): Boolean {
        val a = active(ctx) ?: return false
        return a.bytesUsed >= WARN_BYTES
    }
}
