package com.renamer

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dual free accounts (v1.1) — creation model v1.10.
 *
 * One tap used to start TWO parallel creations (v1.1–v1.9). That
 * sent two signups from the same VPN exit IP within seconds — the
 * exact pattern the site's "an account was already created from
 * this device" limit trips on — and the waiting screen waited for
 * BOTH jobs (the v1.9 10-minute hang). v1.10:
 * - FOREGROUND: ONE creation only (createSingle). The waiting
 *   screen covers just this job (global deadline 130 s).
 * - BACKUP: when the first account is ready AND the user's VPN
 *   is up, a second account is created silently in the background
 *   (createBackupSilently — no screen, no popup, no progress).
 *   If it was not created (VPN off, or the attempt failed), the
 *   NEXT app open builds it silently instead — only while a VPN
 *   is up, else skip quietly and try a later open.
 * - Afterwards the dual behaviour is unchanged: first = active,
 *   second = backup; failover promotes the backup + silently
 *   creates a fresh backup; a rested account past 31 days is
 *   reused as the backup.
 *
 * LIFECYCLE (v1.10): the foreground creation is owned by the
 * APPLICATION, not the Activity. Minimizing must not stop it:
 * the job runs on plain worker threads with the application
 * context (no Activity reference anywhere in the creation path);
 * a foreground service (TbCreationService) holds the process
 * alive while it runs — with no foreground component the system
 * (and aggressive OEM killers) may reclaim the process within
 * seconds of backgrounding, which is what stopped creation on
 * minimize; and the live progress + terminal result live in this
 * object's state, so a recreated Activity re-attaches on resume
 * and the waiting dialog shows real progress again. Nothing here
 * is ever cancelled by onPause/onStop — only the user (لغو) or
 * the 130 s deadline ends it. (The Activity has no onStop at all;
 * onPause only ends the guide + removes the status poller.)
 */
object DualFree {

    /** v1.10: the foreground creation's live listener. One listener
     *  at a time (the visible Activity); the job itself never holds
     *  an Activity — progress and the result also live in the
     *  object state below, so a recreated Activity re-attaches. */
    interface SingleCallback {
        fun onProgress(pct: Int, secondsLeft: Int)
        fun onStage(fa: String, en: String, ru: String, index: Int)
        fun onReady()
        fun onFailed(fa: String, en: String, ru: String)
    }

    /** v1.9: a running creation. cancel() stops the job and the
     *  global deadline; the screen is dismissed by the caller.
     *  No callback fires after a cancel. */
    class CreationJob internal constructor(private val onCancel: () -> Unit) {
        fun cancel() { try { onCancel() } catch (_: Exception) {} }
    }

    /** Terminal result of the single creation (the Activity
     *  consumes it once via consumeSingleOutcome). */
    class SingleOutcome(val ok: Boolean, val fa: String = "", val en: String = "", val ru: String = "")

    /** v1.11: the global deadline — 130 s, DELIBERATELY longer
     *  than the job's own 120 s watchdog (TbAutoAccount), so the
     *  job normally settles first with its SPECIFIC outcome and
     *  this backstop almost never fires. When it does fire, it
     *  surfaces the job's last recorded failure (or the stage it
     *  stalled in) — never the generic timeout text alone.
     *  (v1.10's 110 s deadline always pre-empted the job: the
     *  user only ever saw the generic "took too long" message.) */
    private const val GLOBAL_DEADLINE_MS = 130_000L

    // ---------- v1.10: application-scoped single-creation state ----------
    // The Activity may be destroyed while the creation runs (minimize
    // + system reclaim, rotation). The job, its last progress and its
    // terminal result live here so the next Activity instance
    // re-attaches on resume instead of starting over. All of this is
    // main-thread only except the @Volatile reads.
    @Volatile private var singleActive = false
    @Volatile private var singleCancelled = false
    @Volatile private var singlePct = 0
    @Volatile private var singleLeft = 0
    @Volatile private var singleStageIdx = -1
    @Volatile private var singleStageText: Triple<String, String, String>? = null
    @Volatile private var singleStartedAtMs = 0L
    @Volatile private var singleAppCtx: Context? = null
    @Volatile private var singleOutcome: SingleOutcome? = null
    @Volatile private var singleOutcomeHandled = false
    @Volatile private var singleListener: SingleCallback? = null
    @Volatile private var singleTeardown: (() -> Unit)? = null

    fun isSingleActive(): Boolean = singleActive
    fun singleStartedAtMs(): Long = singleStartedAtMs
    /** Last live values for a re-attaching Activity: pct, seconds-left, stage index. */
    fun singleProgress(): Triple<Int, Int, Int> = Triple(singlePct, singleLeft, singleStageIdx)
    fun singleStageText(): Triple<String, String, String>? = singleStageText
    fun setSingleListener(cb: SingleCallback?) { try { singleListener = cb } catch (_: Exception) {} }

    /** Atomically claim the terminal result for handling; false when
     *  there is none or it was already claimed. The live listener
     *  and the re-attach path race on the main thread — exactly one
     *  wins, so the outcome is never handled twice. */
    fun tryClaimSingleOutcome(): Boolean {
        synchronized(this) {
            if (singleOutcome == null || singleOutcomeHandled) return false
            singleOutcomeHandled = true
            return true
        }
    }

    /** Take the terminal result exactly once (null when still
     *  running or already handled). The Activity calls this on
     *  resume when it finds no live job. */
    fun consumeSingleOutcome(): SingleOutcome? {
        synchronized(this) {
            val o = singleOutcome ?: return null
            if (singleOutcomeHandled) return null
            singleOutcomeHandled = true
            singleOutcome = null
            return o
        }
    }

    private fun clearSingleState() {
        singleActive = false
        singlePct = 0
        singleLeft = 0
        singleStageIdx = -1
        singleStageText = null
        singleStartedAtMs = 0L
        singleAppCtx = null
        singleOutcome = null
        singleOutcomeHandled = false
        singleListener = null
        singleTeardown = null
        // NOTE: singleCancelled is NOT reset here — a late posted
        // callback must still see the cancel. It is reset only at
        // the start of the next createSingle (main thread, before
        // any callback can run).
    }

    /** v1.10: the keeper service — started when a foreground
     *  creation begins, stopped the moment it settles. If starting
     *  it fails (background-start restriction), the creation still
     *  runs on its threads; only the process-lifetime protection
     *  is lost, never the creation itself. */
    private fun startCreationService(ctx: Context) {
        try {
            val intent = android.content.Intent(ctx, TbCreationService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        } catch (_: Exception) {
            // v1.10: creation-method traces go to logcat only,
            // never to the in-app log (user order).
            try { android.util.Log.d("DualFree", "Keeper service did not start (creation continues)") } catch (_: Exception) {}
        }
    }

    private fun stopCreationService(ctx: Context?) {
        if (ctx == null) return
        try { ctx.stopService(android.content.Intent(ctx, TbCreationService::class.java)) } catch (_: Exception) {}
    }

    /** v1.10: create the FIRST (and only) foreground account. One
     *  job, one temp mail, one signup, one verification — provider
     *  rotation intact, global deadline 130 s. The waiting screen
     *  covers just this job; the backup comes later, silently. */
    fun createSingle(ctx: Context): CreationJob {
        val appCtx = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        val country = TunnelBear.selectedCountry(appCtx)
        var settled = false
        var timeoutFired = false
        var job: TbAutoAccount.JobHandle? = null
        var timeoutRunnable: Runnable? = null

        // Register the application-scoped state BEFORE the job
        // starts, so an Activity recreated a second later
        // re-attaches instead of starting over.
        singleCancelled = false
        singleActive = true
        singlePct = 0
        singleLeft = 0
        singleStageIdx = -1
        singleStageText = null
        singleStartedAtMs = System.currentTimeMillis()
        singleAppCtx = appCtx
        singleOutcome = null
        singleOutcomeHandled = false
        startCreationService(appCtx)

        // v1.8: ONE displayed value, MONOTONIC — it never goes
        // backwards. (With one job the clamp is trivially true,
        // but the seconds-left is still derived from this single
        // percentage + the EMA total for one clean attempt, so the
        // countdown always agrees with the bar and never jumps up
        // on a retry. During a provider retry the numbers FREEZE —
        // they cannot go backwards, so they wait for real progress;
        // the dots keep moving.)
        var shownPct = 0
        val totalMs = TbAutoAccount.estimatedTotalMs(appCtx).coerceAtLeast(10_000L)
        fun reportProgress(pct: Int) {
            if (singleCancelled) return
            val p = pct.coerceIn(0, 100)
            if (p > shownPct) shownPct = p
            singlePct = shownPct
            singleLeft = maxOf(5, ((100 - shownPct) * totalMs / 100 / 1000).toInt())
            val s = shownPct
            val l = singleLeft
            main.post { try { singleListener?.onProgress(s, l) } catch (_: Exception) {} }
        }
        fun reportStage(fa: String, en: String, ru: String, index: Int) {
            if (singleCancelled) return
            singleStageIdx = index
            singleStageText = Triple(fa, en, ru)
            main.post { try { singleListener?.onStage(fa, en, ru, index) } catch (_: Exception) {} }
        }
        fun settle(ok: Boolean, fa: String = "", en: String = "", ru: String = "") {
            if (singleCancelled) return
            synchronized(this) {
                if (settled || singleCancelled) return
                settled = true
                try { timeoutRunnable?.let { main.removeCallbacks(it) } } catch (_: Exception) {}
                singleOutcome = SingleOutcome(ok, fa, en, ru)
                singleActive = false
                stopCreationService(appCtx)
                val o = singleOutcome!!
                val listener = singleListener
                singleListener = null
                main.post {
                    try {
                        if (o.ok) listener?.onReady()
                        else listener?.onFailed(o.fa, o.en, o.ru)
                    } catch (_: Exception) {}
                }
            }
        }

        val saveHook: (String, String) -> Unit = { email, password ->
            if (FreeAccounts.active(appCtx) == null) {
                FreeAccounts.add(appCtx, email, password, country, asActive = true)
            }
            try { TunnelBear.ensureSingleEntry(appCtx, restoreDeleted = true) } catch (_: Exception) {}
        }

        // v1.11: the global deadline is only a BACKSTOP now (130 s,
        // longer than the job's own 120 s watchdog, so the job's
        // specific outcome normally wins). If it ever fires, it
        // must NOT hide the real reason behind the generic timeout
        // text (v1.10's 110 s deadline always pre-empted the job —
        // that is why the user only ever saw "took too long").
        // So: cancel the job, then read its last recorded failure
        // (device-limit first) and show THAT; when no attempt ever
        // finished, name the stage it stalled in.
        timeoutRunnable = Runnable {
            if (singleCancelled) return@Runnable
            synchronized(this) {
                if (timeoutFired || settled || singleCancelled) return@Runnable
                timeoutFired = true
                try { job?.cancel() } catch (_: Exception) {}
                val specific = try { job?.lastFailure() } catch (_: Exception) { null }
                if (specific != null) {
                    settle(false, specific.first, specific.second, specific.third)
                } else {
                    val st = try { job?.lastStageText() } catch (_: Exception) { null }
                    if (st != null) {
                        settle(false,
                            "ساخت حساب خیلی طول کشید (بیشتر از ۲ دقیقه) — در مرحله‌ی «${st.first}» متوقف شد. دوباره تلاش کن.",
                            "Account creation took too long (over 2 minutes) — it stalled at the “${st.second}” stage. Try again.",
                            "Создание аккаунта заняло слишком много времени (больше 2 минут) — остановка на этапе «${st.third}». Попробуйте снова.")
                    } else {
                        settle(false,
                            "ساخت حساب خیلی طول کشید (بیشتر از ۲ دقیقه). دوباره تلاش کن.",
                            "Account creation took too long (over 2 minutes). Try again.",
                            "Создание аккаунта заняло слишком много времени (больше 2 минут). Попробуйте снова.")
                    }
                }
            }
        }
        timeoutRunnable?.let { main.postDelayed(it, GLOBAL_DEADLINE_MS) }

        job = TbAutoAccount.start(appCtx, object : TbAutoAccount.Callback {
            override fun onProgress(pct: Int, secondsLeft: Int) {
                reportProgress(pct)
            }
            override fun onStage(fa: String, en: String, ru: String, index: Int) {
                reportStage(fa, en, ru, index)
            }
            override fun onSuccess() {
                reportProgress(100)
                settle(true)
            }
            override fun onFailure(fa: String, en: String, ru: String) {
                settle(false, fa, en, ru)
            }
        }, saveHook)
        singleTeardown = {
            try { timeoutRunnable?.let { main.removeCallbacks(it) } } catch (_: Exception) {}
            try { job?.cancel() } catch (_: Exception) {}
        }

        return CreationJob { cancelSingle() }
    }

    /** Cancel a live single creation (the waiting screen's «لغو»).
     *  A no-op when nothing is running — in particular it never
     *  wipes an unconsumed terminal result: after settle, the
     *  outcome stays for the re-attach path. The job's callbacks
     *  are suppressed after a cancel, so no screen follows. */
    fun cancelSingle() {
        synchronized(this) {
            if (!singleActive) return
            singleActive = false
            singleCancelled = true
        }
        try { singleTeardown?.invoke() } catch (_: Exception) {}
        try { stopCreationService(singleAppCtx) } catch (_: Exception) {}
        clearSingleState()
    }

    /** Create a fresh backup silently (failover follow-up, v1.10
     *  post-first-creation backup, next-open retry): reuse a rested
     *  account when eligible, else create one with up to 3 silent
     *  attempts. Never shows anything — no screen, no popup, no
     *  progress. Logcat only (never the in-app log — v1.10).
     *  v1.10: lifecycle-proof — a plain daemon thread on the
     *  application context (no Activity reference); an in-flight
     *  guard stops overlapping runs (first-ready + app-open +
     *  failover can all ask at once). If the process dies
     *  mid-backup, the next app open retries — by design, the
     *  backup is best-effort and the first account is never
     *  touched. */
    private val backupInFlight = AtomicBoolean(false)

    fun createBackupSilently(ctx: Context) {
        val appCtx = ctx.applicationContext
        if (!backupInFlight.compareAndSet(false, true)) return
        Thread {
            try {
                if (FreeAccounts.backup(appCtx) != null) return@Thread
                val country = TunnelBear.selectedCountry(appCtx)
                val reused = try { FreeAccounts.reuseRestingAsBackup(appCtx, country) } catch (_: Exception) { null }
                if (reused != null) {
                    try { android.util.Log.d("DualFree", "Backup account restored from a rested account") } catch (_: Exception) {}
                    return@Thread
                }
                try { android.util.Log.d("DualFree", "Silent background backup creation started") } catch (_: Exception) {}
                // v1.13: exit-IP awareness — do NOT burn a silent
                // creation on an exit IP that already refused with
                // the device limit. The next app open (or the
                // exit-change check in the Activity) retries when
                // the exit IP changes to an unseen one.
                if (!TbAutoAccount.exitIpAllowsRetry(appCtx)) {
                    try { android.util.Log.d("DualFree", "Silent backup skipped — current exit IP already refused (device limit)") } catch (_: Exception) {}
                    return@Thread
                }
                var attempt = 0
                while (attempt < 3 && FreeAccounts.backup(appCtx) == null) {
                    attempt++
                    val done = java.util.concurrent.CountDownLatch(1)
                    var ok = false
                    TbAutoAccount.start(appCtx, object : TbAutoAccount.Callback {
                        override fun onSuccess() { ok = true; done.countDown() }
                        override fun onFailure(fa: String, en: String, ru: String) { done.countDown() }
                    }, saveHook = { email, password ->
                        try { FreeAccounts.add(appCtx, email, password, country, asActive = false) } catch (_: Exception) {}
                    }, startProviderIndex = 1)
                    try { done.await(6, java.util.concurrent.TimeUnit.MINUTES) } catch (_: Exception) {}
                    if (ok) break
                }
                try {
                    if (FreeAccounts.backup(appCtx) != null) {
                        android.util.Log.d("DualFree", "Silent background backup account created")
                    } else {
                        android.util.Log.d("DualFree", "Silent background backup creation failed; will retry on the next app open")
                    }
                } catch (_: Exception) {}
            } catch (_: Exception) {
            } finally {
                backupInFlight.set(false)
            }
        }.apply { isDaemon = true }.start()
    }
}
