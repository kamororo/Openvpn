package com.renamer

import android.app.Activity
import android.content.Context

/** 2.92: one shared, cached, background loader for the installed-app
 *  list used by the app pickers (Smart Connect, v2ray per-app,
 *  Emergency per-app). The old code queried PackageManager and
 *  called getApplicationLabel() once per app — and again per
 *  comparison while sorting — all on the UI thread, on EVERY
 *  open. That is why the Smart picker opened slowly: the dialog
 *  only appeared after the whole query + sort + row build.
 *  This loader fetches each label ONCE on a background thread,
 *  sorts once, keeps only launchable apps, and caches the
 *  (packageName, label) pairs in memory. Later opens are served
 *  from the cache immediately, with a silent background refresh. */
object AppListLoader {
    @Volatile private var cached: List<Pair<String, String>>? = null

    fun query(ctx: Context): List<Pair<String, String>> {
        return try {
            val pm = ctx.packageManager
            pm.getInstalledApplications(0)
                .mapNotNull { info ->
                    try {
                        if (pm.getLaunchIntentForPackage(info.packageName) == null) null
                        else info.packageName to pm.getApplicationLabel(info).toString()
                    } catch (_: Exception) { null }
                }
                .sortedBy { it.second.lowercase() }
        } catch (_: Exception) { emptyList() }
    }

    /** Calls onDone on the UI thread. On a cache hit the cached
     *  list is handed over immediately and the cache is refreshed
     *  silently in the background; otherwise the query runs on a
     *  background thread first. */
    fun loadAsync(activity: Activity, onDone: (List<Pair<String, String>>) -> Unit) {
        val c = cached
        if (c != null) {
            onDone(c)
            Thread { try { cached = query(activity) } catch (_: Exception) {} }.start()
            return
        }
        Thread {
            val list = query(activity)
            cached = list
            try {
                activity.runOnUiThread { onDone(list) }
            } catch (_: Exception) {}
        }.start()
    }
}
