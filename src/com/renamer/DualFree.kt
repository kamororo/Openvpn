package com.renamer

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Dual free-account creation (v1.1). One tap starts
 * TWO background creations in parallel (two temp
 * mails, two signups, two verifications — the same
 * TbAutoAccount flow, provider rotation intact).
 * Whichever account verifies first becomes the
 * ACTIVE account; the other becomes the BACKUP.
 * The waiting screen covers creation until the
 * first account is ready; the second continues
 * silently and never bothers the user.
 * If the backup job fails, it retries silently up
 * to 3 attempts. A resting account whose 31-day
 * window has passed is reused as the backup
 * instead of creating a new one. If BOTH jobs fail,
 * the caller shows the failure dialog (with retry)
 * — the only visible failure path.
 */
object DualFree {

    interface DualCallback {
        fun onProgress(pct: Int, secondsLeft: Int)
        fun onFirstReady()
        fun onBackupReady()
        fun onBothFailed(fa: String, en: String)
    }

    fun createDual(ctx: Context, cb: DualCallback) {
        val appCtx = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        val startMs = System.currentTimeMillis()
        val country = TunnelBear.selectedCountry(appCtx)
        val lock = Any()
        var progressA = 0
        var progressB = 0
        var settledA = false
        var settledB = false
        var firstReadyFired = false
        var lastFail: Pair<String, String>? = null

        fun secondsLeft(): Int {
            val elapsed = ((System.currentTimeMillis() - startMs) / 1000).toInt()
            return maxOf(3, 75 - elapsed)
        }
        fun reportProgress() {
            val pct = (progressA + progressB) / 2
            val left = secondsLeft()
            main.post { try { cb.onProgress(pct, left) } catch (_: Exception) {} }
        }
        fun maybeFirstReady() {
            synchronized(lock) {
                if (firstReadyFired) return
                val active = FreeAccounts.active(appCtx)
                if (active != null) {
                    firstReadyFired = true
                    main.post { try { cb.onFirstReady() } catch (_: Exception) {} }
                    return
                }
                // No active yet, but a backup exists (the
                // primary job failed while the other one
                // succeeded): promote it so the user still
                // gets a working account.
                val bk = FreeAccounts.backup(appCtx)
                if (bk != null && settledA && settledB) {
                    try { FreeAccounts.promoteBackup(appCtx) } catch (_: Exception) {}
                    if (FreeAccounts.active(appCtx) != null) {
                        firstReadyFired = true
                        main.post { try { cb.onFirstReady() } catch (_: Exception) {} }
                    }
                }
            }
        }
        fun checkBothFailed() {
            synchronized(lock) {
                if (!settledA || !settledB) return
                if (FreeAccounts.active(appCtx) == null && !firstReadyFired) {
                    val f = lastFail ?: ("ساخت حساب ناموفق بود. دوباره تلاش کن." to
                        "Account creation failed. Try again.")
                    main.post { try { cb.onBothFailed(f.first, f.second) } catch (_: Exception) {} }
                }
            }
        }

        val saveHook: (String, String) -> Unit = { email, password ->
            synchronized(lock) {
                if (FreeAccounts.active(appCtx) == null) {
                    FreeAccounts.add(appCtx, email, password, country, asActive = true)
                    maybeFirstReady()
                } else {
                    FreeAccounts.add(appCtx, email, password, country, asActive = false)
                    main.post { try { cb.onBackupReady() } catch (_: Exception) {} }
                }
                try { TunnelBear.ensureSingleEntry(appCtx, restoreDeleted = true) } catch (_: Exception) {}
            }
        }

        // Slot B: the backup job. Reuse a rested
        // account when one is eligible; otherwise
        // create one, retrying silently up to 3 times.
        var backupJobStarted = false
        fun runBackupJob(attempt: Int) {
            backupJobStarted = true
            TbAutoAccount.start(appCtx, object : TbAutoAccount.Callback {
                override fun onProgress(pct: Int, secondsLeft: Int) {
                    progressB = pct.coerceIn(0, 99)
                    reportProgress()
                }
                override fun onSuccess() {
                    progressB = 100
                    synchronized(lock) { settledB = true }
                    reportProgress()
                    maybeFirstReady()
                }
                override fun onFailure(fa: String, en: String) {
                    if (attempt < 3) {
                        runBackupJob(attempt + 1)
                    } else {
                        lastFail = fa to en
                        synchronized(lock) { settledB = true }
                        checkBothFailed()
                    }
                }
            }, saveHook)
        }

        val reused = try { FreeAccounts.reuseRestingAsBackup(appCtx, country) } catch (_: Exception) { null }
        if (reused != null) {
            progressB = 100
            settledB = true
            main.post { try { cb.onBackupReady() } catch (_: Exception) {} }
        }

        // Slot A: the primary job (one attempt — the
        // backup job carries the silent retries).
        TbAutoAccount.start(appCtx, object : TbAutoAccount.Callback {
            override fun onProgress(pct: Int, secondsLeft: Int) {
                progressA = pct.coerceIn(0, 99)
                reportProgress()
            }
            override fun onSuccess() {
                progressA = 100
                synchronized(lock) { settledA = true }
                reportProgress()
                maybeFirstReady()
            }
            override fun onFailure(fa: String, en: String) {
                lastFail = fa to en
                synchronized(lock) { settledA = true }
                maybeFirstReady()
                checkBothFailed()
            }
        }, saveHook)

        if (!backupJobStarted && reused == null) runBackupJob(1)
        reportProgress()
    }

    /** Create a fresh backup silently (failover
     *  follow-up): reuse a rested account when
     *  eligible, else create one with up to 3
     *  silent attempts. Never shows anything. */
    fun createBackupSilently(ctx: Context) {
        val appCtx = ctx.applicationContext
        Thread {
            try {
                if (FreeAccounts.backup(appCtx) != null) return@Thread
                val country = TunnelBear.selectedCountry(appCtx)
                val reused = try { FreeAccounts.reuseRestingAsBackup(appCtx, country) } catch (_: Exception) { null }
                if (reused != null) return@Thread
                var attempt = 0
                while (attempt < 3 && FreeAccounts.backup(appCtx) == null) {
                    attempt++
                    val done = java.util.concurrent.CountDownLatch(1)
                    var ok = false
                    TbAutoAccount.start(appCtx, object : TbAutoAccount.Callback {
                        override fun onSuccess() { ok = true; done.countDown() }
                        override fun onFailure(fa: String, en: String) { done.countDown() }
                    }) { email, password ->
                        try { FreeAccounts.add(appCtx, email, password, country, asActive = false) } catch (_: Exception) {}
                    }
                    try { done.await(6, java.util.concurrent.TimeUnit.MINUTES) } catch (_: Exception) {}
                    if (ok) break
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }
}
