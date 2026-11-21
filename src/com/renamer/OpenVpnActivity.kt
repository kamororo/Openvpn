package com.renamer

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.graphics.RenderEffect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.widget.*

/**
 * Standalone Openvpn app v1.8 — the OpenVPN section of Config
 * Renamer, alone. Opens DIRECTLY into this page: no
 * disguise, no lock, no PIN. Design = the main app's
 * Profiles page (1.41, approved mockup): «Profiles»
 * header with the app-log and "?" guide at the top
 * (1.29: the ☰ drawer is gone; theme, language and
 * About live in Settings), a DISCONNECTED / CONNECTED /
 * CONNECTING status label, the free-account top box
 * (same sapphire as the v2ray free box; its guide
 * third leaves once the account exists), then the
 * profile cards — each with a connect toggle, a pin,
 * «OpenVPN Profile» + name, and info /
 * edit / delete circles — and a teal→violet + FAB
 * above the nav bar. v1.6 adds the «راهنمای اتصال»
 * coach-marks tutorial (until the first free
 * connection), the VPN gate, and accurate
 * probe-only dots sorted by two-green then probe-handshake
 * speed (low data: no downloads). v1.7: drawn-pointer overlay
 * (no square, no ring), step-2 box in an attached window
 * above the waiting dialog (failed on the phone — the
 * dialog's dim still faded it), stage-based account-creation
 * progress (real percentage + remaining time), no «بعدی»
 * button, faster creation (5 s inbox poll, 60 s provider
 * window, 10 s request timeout, staggered dual providers),
 * dots legend while the tutorial is active. v1.8: the tutorial SPOTLIGHTS
 * the target (dim veil with a hole exactly around it — no
 * drawn pointer at all), the step-2 box is a second dialog
 * above the waiting dialog's dim for real, the percentage
 * is monotonic (never backwards) with seconds-left derived
 * from it, the percentage sits on a dark pill, and creation
 * is faster again (2 s inbox poll, 30 s provider window,
 * providers ordered by measured speed). NO status pill under the orb, NO
 * «TunnelBear» anywhere user-visible (log included), NO on-page
 * connect-error text (log only). The top box IS the free
 * flow — one tap creates TWO accounts in the background (first
 * ready connects, second is the backup, with auto-failover +
 * ~1900 MB usage failover). Tap a card to SELECT it (light
 * green); its toggle connects it. NO Smart engine, NO
 * chain, NO v2ray/WG — every connect here is one DIRECT ovpn3
 * connect (plus the internal split relay for the free entry,
 * exactly as the main app).
 */
class OpenVpnActivity : Activity() {
    companion object {
        const val NAVY = "#1B365D"
        const val ORANGE = "#ED7D31"
        // 1.71 (item 3): the last-CONNECTED free location
        // — the country of the free tunnel that actually
        // reached CONNECTED, in "renamer_ovpn" prefs.
        // The widget's connect target resolves to this,
        // never to the last-picked-but-never-connected
        // country. Public so the widget provider can
        // read the same key.
        const val LAST_FREE_COUNTRY_KEY = "tb_last_free_country"
        // v1.22: the crash catcher is installed once per
        // process (onCreate can run again on recreate —
        // re-installing would nest handlers and log the
        // same crash twice).
        @Volatile private var crashCatcherInstalled = false
        @Volatile var engineChainName: String? = null
        val proxyOwnerLock = Any()
        @Volatile var proxyOwner: String? = null
        fun releaseChainProxyOwner() {
            synchronized(proxyOwnerLock) {
                if (proxyOwner == "chain") proxyOwner = null
            }
        }
    }

    // ---------- state ----------
    private var profiles: MutableList<OvpnProfile> = mutableListOf()
    private var selectedProfile: String? = null

    // Free-account box state (3.03-3.06, main app).
    @Volatile private var tbCreating = false
    // v1.1 dual accounts: one failover attempt per user
    // tap (connect failure), and one usage-failover
    // backup spawn per session while no backup exists.
    private var freeFailoverTried = false
    // 1.71 (item 3): the country the current free connect
    // attempt is for (captured in connectProfile, recorded
    // as the last-connected free country only when the
    // tunnel reaches CONNECTED — a pick made DURING the
    // connect must not move the record).
    private var connectingFreeCountry: String? = null
    // 1.71 (item 3): the widget auto-connect fires once
    // per activity instance (onCreate and onNewIntent
    // both route through handleWidgetAutoConnect).
    private var widgetAutoConnectDone = false
    private var backupSpawnedForUsage = false
    private var exitIpCheckAtMs = 0L
    // v1.12: detailed connection log (item 2) — the attempt
    // start (ms) and the rung currently running; the log
    // lines name the method, the rung, the duration and the
    // last core state, so a problem can be followed up from
    // the log alone.
    private var connectAttemptStartMs = 0L
    @Volatile private var currentRungFa: String? = null
    @Volatile private var currentRungEn: String? = null
    @Volatile private var currentRungRu: String? = null
    // v1.12: whether the ladder started any rung in the
    // current attempt (item 3) — the finally in
    // connectDirect clears a connecting state only when
    // no rung ever started.
    private var ladderStartedAnyRung = false
    // v1.12: list self-heal (item 4) — the signature of the
    // last render mismatch logged, so it is logged once.
    private var lastListMismatchSig: String? = null
    private var tbWaitDialog: android.app.AlertDialog? = null
    private var tbWaitDotsView: TextView? = null
    private var tbWaitProgressView: android.widget.ProgressBar? = null
    private var tbWaitPctView: TextView? = null
    private var tbWaitCountdownView: TextView? = null
    private var tbWaitDots = 0
    private val tbWaitDotsLoop = object : Runnable {
        override fun run() {
            try {
                val v = tbWaitDotsView
                if (tbWaitDialog?.isShowing == true && v != null) {
                    tbWaitDots = (tbWaitDots % 3) + 1
                    v.text = L("لطفا کمی صبر کنید", "Please wait a moment") + ".".repeat(tbWaitDots)
                    handler.postDelayed(this, 1000)
                }
            } catch (_: Exception) {}
        }
    }

    // v1.10 retry ladder (imported profiles): a generation guard
    // — a fresh tap or a user stop supersedes a running ladder —
    // the live flag that suppresses the poller's per-rung failure
    // line (the ladder logs exactly one failure line itself, only
    // when every rung has failed), and the winning rung's name
    // for the poller's success line.
    private val retryGen = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var retryLadderRunning = false
    @Volatile private var retryWonFa: String? = null
    @Volatile private var retryWonEn: String? = null
    @Volatile private var retryWonRu: String? = null

    // v1.9: honest waiting screen — elapsed time + a way out.
    // v1.10: the stage DESCRIPTION line is gone from the screen
    // (user order) — the stage index still drives the
    // elapsed/countdown line, and the stages still go to the app
    // log. The creation job itself is owned by DualFree (not the
    // Activity) so minimizing cannot stop it.
    private var tbWaitCancelView: TextView? = null
    private var tbWaitStageIdx = -1
    private var tbWaitStartMs = 0L
    private val tbWaitElapsedLoop = object : Runnable {
        override fun run() {
            try {
                // During the unpredictable mail wait the
                // screen shows the stage + the elapsed
                // seconds — never a frozen fake countdown.
                if (tbWaitDialog?.isShowing == true && tbWaitStageIdx == 4) {
                    val el = ((System.currentTimeMillis() - tbWaitStartMs) / 1000).toInt()
                    tbWaitCountdownView?.text = T("$el ثانیه گذشته", "$el s elapsed", "прошло $el с")
                    handler.postDelayed(this, 1000)
                }
            } catch (_: Exception) {}
        }
    }

    private fun startElapsedLoop() {
        try { handler.removeCallbacks(tbWaitElapsedLoop) } catch (_: Exception) {}
        try { handler.post(tbWaitElapsedLoop) } catch (_: Exception) {}
    }

    // 1.42 (G): the card health dots are GONE (user
    // order) — pins replace them (see renderCards).
    // The session-probe sweep still runs: its cache
    // feeds Monitoring's session-proven count, not
    // any card. localHealth is the sweep's memory.
    private val localHealth = HashMap<String, Int>()
    private var healthSweepGen = 0
    private var lastVpnBusy = false

    // Central-log flip detector (2.43).
    private var appLogOvpnWasConnected = false
    private var appLogOvpnLastName = ""
    private var appLogOvpnPending = false
    private var appLogOvpnPendingName = ""
    private var appLogOvpnLastError: String? = null
    private var needDialogShownFor: String? = null
    private var lastShownState: String? = null
    private var lastLogState: String? = null
    private var ovpnPollLogNoted = false

    private val handler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            val statusState = (OvpnEngine.activeName ?: "") + "|" + OvpnEngine.isConnected + "|" + OvpnEngine.isConnecting + "|" + (OvpnEngine.lastError ?: "") + "|" + (OvpnEngine.lastState ?: "") + "|" + (OvpnEngine.lastStateMsg ?: "") + "|" + OvpnEngine.waitingForInput
            val logState = OvpnEngine.logLines.size.toString() + "|" + OvpnEngine.logText().takeLast(200)
            if (statusState != lastShownState) {
                lastShownState = statusState
                lastLogState = logState
                refreshStatus()
                refreshList()
            } else if (logState != lastLogState) {
                lastLogState = logState
                refreshStatus()
                if (!ovpnPollLogNoted) {
                    ovpnPollLogNoted = true
                    try {
                    } catch (_: Exception) {}
                }
            }
            // When the line becomes idle after having been busy
            // (disconnect, failed attempt), re-test the dots.
            val nowBusy = anyVpnUp()
            if (lastVpnBusy && !nowBusy) handler.post { startHealthSweep() }
            lastVpnBusy = nowBusy
            // v1.1: usage-based failover while the free
            // tunnel is up (checked every poll).
            try { maybeUsageFailover() } catch (_: Exception) {}
            // v1.6: advance/retreat the «راهنمای اتصال»
            // tutorial from the real state (creating ->
            // account ready -> country picked -> connected).
            try { checkGuideState() } catch (_: Exception) {}
            // v1.7: the first free-tunnel connection ends
            // the tutorial for good, even if the guide
            // was never opened.
            // v1.13: NOT during a throughput-sweep measurement
            // (a measurement is not the user connecting).
            try { if (freeConnected()) noteFirstFreeConnection() } catch (_: Exception) {}
            // v1.13: when the exit IP changes to an unseen
            // one, retry the pending second-account job on
            // its own (throttled to once per 5 min).
            try { maybeExitChangeBackupRetry() } catch (_: Exception) {}
            handler.postDelayed(this, 2000)
        }
    }

    // ---------- view refs (built once, updated in place) ----------
    // 1.41 (E): the page is the main app's Profiles look —
    // header, status label, top box, profile
    // cards, + FAB. The tabs, orb, country chip/popup,
    // floating guide button is GONE with the
    // old Free/Open split. 1.42: pins are BACK on the
    // cards (user order, overriding the 1.41 dots).
    private lateinit var rootFrame: FrameLayout
    private var statusLabel: TextView? = null
    private var topBoxView: LinearLayout? = null
    private var topBoxMain: LinearLayout? = null
    private var topBoxTitle: TextView? = null
    private var topBoxGuidePart: LinearLayout? = null
    private var topBoxGuideDivider: View? = null
    private var topBoxGuidePulseAnim: ValueAnimator? = null
    // The top box's connect toggle in state B (the
    // tutorial's step-4 target) — set when
    // renderTopBox builds the state-B box.
    private var tbToggleView: android.widget.ImageView? = null
    // 1.42 (D): the state-B country menu opens INSIDE
    // the top box (main app renderTbBox).
    private var tbCountryMenuOpen = false
    // 1.42: renderTopBox rebuilds the box only when
    // its state signature changes (else the 2s
    // poller would rebuild it — and reset an open
    // country menu / the tutorial spotlight —
    // every 2 seconds).
    private var lastTopBoxSig: String? = null
    private var cardsContainer: LinearLayout? = null
    private var fab: Button? = null
    // v1.6: «راهنمای اتصال» guided tutorial — coach
    // marks over the real UI. 1.41: its entry points
    // are the header "?" and the top box's guide
    // third (the floating button is gone).
    private var guideStep = 0
    // 1.41: step 4's connect was really tried (set when
    // the engine is connecting during step 4), so a
    // stale lastError from an older attempt cannot end
    // the guide before the user taps.
    private var guideStep4Tried = false
    private var guideOverlay: FrameLayout? = null
    private var guideDimView: GuideOverlayView? = null
    private var guideBox: LinearLayout? = null
    private var guideBoxTitle: TextView? = null
    private var guideBoxText: TextView? = null
    // v1.7: no «بعدی» button anymore — the tutorial
    // advances only by tapping the target or by real
    // state changes. v1.8: step 2's box is a SECOND
    // dialog shown after the waiting dialog (its own
    // top-level window, dimAmount 0) — the v1.7
    // attached window still sat under the waiting
    // dialog's dim and came out faded on the phone.
    private var guideStep2Dialog: android.app.Dialog? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Float): Int = (v * resources.displayMetrics.density).toInt()
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private fun T(fa: String, en: String, ru: String): String = AppLanguage.t3(this, fa, en, ru)
    private fun rtlDir(): Int =
        if (AppLanguage.lang(this) == "fa") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    private fun appTextDir(): Int =
        if (AppLanguage.lang(this) == "fa") View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
    private fun tbProfile(): OvpnProfile? = profiles.firstOrNull { it.tunnelBear }
    private fun ownProfiles(): List<OvpnProfile> = profiles.filter { !it.tunnelBear }
    // v1.1: ONLY an explicitly tapped profile is
    // selected — never silently the first one. With
    // no selection the orb shows «No profile
    // selected» and does nothing on tap.
    private fun selectedProfileObj(): OvpnProfile? =
        ownProfiles().firstOrNull { it.name == selectedProfile }

    // ---------- lifecycle ----------
    /** v1.22: crash catcher — the app sometimes closes
     *  suddenly with no trace in the app log (there was
     *  no uncaught-exception handler at all). This records
     *  ONE entry in the app log (fa/en/ru, no provider
     *  name — class names are redacted at write time)
     *  with the exception name + message + first stack
     *  lines, then chains to the previous default handler
     *  so the crash behaves EXACTLY as before (same
     *  dialog, same process death) — it only records, it
     *  never swallows. The next sudden close is then
     *  diagnosable from the log. */
    private fun installCrashCatcher() {
        if (crashCatcherInstalled) return
        crashCatcherInstalled = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val stack = e.stackTrace.take(6).joinToString("\n") { "  at $it" }
                val raw = "${e.javaClass.simpleName}: ${e.message ?: ""}\n$stack"
                val clean = raw.replace("TunnelBear", "TB").take(800)
                AppLog.append(applicationContext, "openvpn",
                    "برنامه بسته شد (کرش): $clean",
                    "App crashed: $clean",
                    "Приложение закрылось (сбой): $clean")
            } catch (_: Exception) {}
            try {
                prev?.uncaughtException(t, e)
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashCatcher()
        OvpnTheme.applyPage(this)
        selectedProfile = getSharedPreferences("renamer_ovpn", MODE_PRIVATE).getString("ovpn_last", null)
        // 3.02: the single free entry (one stored profile whose
        // country follows the pick). 3.01 users arrive with 47
        // imported profiles; the first 3.02 run collapses them
        // into the one entry. A deleted entry is restored only
        // by the auto-account flow — never silently from here.
        try { TunnelBear.ensureSingleEntry(this) } catch (_: Exception) {}
        // v1.1: adopt a v1.0 single account as the
        // active dual account (window starts now).
        try { FreeAccounts.migrateLegacy(this) } catch (_: Exception) {}
        profiles = OvpnStore.load(this)
        buildUi()
        refreshAll()
        // 1.71 (item 3): a widget tap opens this activity
        // with widget_auto_connect=true — connect to the
        // last-connected free location.
        handleWidgetAutoConnect()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        // 1.71 (item 3): a widget tap while this activity
        // is already alive arrives here.
        handleWidgetAutoConnect()
    }

    override fun onResume() {
        super.onResume()
        profiles = OvpnStore.load(this)
        // The phone's day/night may have flipped while we were
        // away (auto mode): rebuild if the effective mode changed.
        if (OvpnTheme.isDark(this) != lastDark) recreate()
        handler.post(poller)
        // v1.12: instant orb sync (item 5) — the engine
        // calls this at the end of every state event so
        // the orb refreshes immediately, not only on the
        // 2s poller.
        OvpnEngine.stateListener = {
            handler.post {
                try {
                    if (!isFinishing && !isDestroyed) refreshStatus()
                } catch (_: Throwable) {}
            }
        }
        refreshAll()
        val toConnect = OvpnStore.pendingConnect
        if (toConnect != null) {
            OvpnStore.pendingConnect = null
            connectProfile(toConnect)
        }
        startHealthSweep()
        // v1.10: a creation started by a previous (destroyed)
        // instance may still be running — re-attach the waiting
        // dialog to it (or handle its result if it finished while
        // we were away).
        try { maybeReattachCreation() } catch (_: Exception) {}
        // v1.10: if the first account exists but the backup was
        // never created, build it silently now — no screen — but
        // only while a VPN is up; otherwise skip quietly and try
        // a later open.
        try { maybeCreateBackupOnOpen() } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
        OvpnEngine.stateListener = null
        // 1.41: stop the top box's guide pulse while away
        // (renderTopBox restarts it if the guide third
        // shows) and end the tutorial overlay.
        try { topBoxGuidePulseAnim?.cancel() } catch (_: Exception) {}
        topBoxGuidePulseAnim = null
        endGuide()
        healthSweepGen++
    }

    private var lastDark = false

    private fun refreshAll() {
        lastDark = OvpnTheme.isDark(this)
        refreshStatus()
        refreshList()
    }



    // ---------- UI construction (1.41: main-app Profiles look) ----------
    // ONE scrolling page — header, status label, top
    // box, profile cards — with the + FAB
    // floating above the nav bar. The tabs, orb and
    // the Open tab's own scroll are gone.
    private fun buildUi() {
        val dark = OvpnTheme.isDark(this)
        rootFrame = FrameLayout(this)
        // 1.42 (E): the page background lives on the
        // root AND the scroll too — in 1.41 only the
        // short inner page had it, so the empty area
        // below the content showed the white window
        // background (two-tone page).
        rootFrame.background = OvpnTheme.pageBackground(this)
        // 1.41 (E): ONE scrolling page, exactly the main
        // app's Profiles look — header, status label,
        // top box, profile cards. The tabs,
        // orb and the Open tab's own scroll are gone;
        // the whole page scrolls as one. Bottom padding
        // (96dp) clears the + FAB.
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = OvpnTheme.pageBackground(this@OpenVpnActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp(96))
        }
        buildHeader(page, dark)
        // Status label ("DISCONNECTED" / "CONNECTED: …" /
        // "CONNECTING: …"), main-app style, 20sp bold.
        statusLabel = TextView(this).apply {
            text = "DISCONNECTED"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(if (dark) "#26A69A" else "#5B7A99"))
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(20), dp(16), dp(20), dp(8))
            }
        }
        page.addView(statusLabel)
        buildTopBox(page, dark)
        cardsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        page.addView(cardsContainer)
        // 1.43 (C): the empty-hint text is GONE — no
        // «هنوز پروفایلی اضافه نشده است…» on this page.
        val pageScroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = true
            // 1.43 (A): fill the viewport — the short inner
            // page stretches to the full screen height, so
            // its background covers the empty area below
            // the content too (no second colour below it).
            // 1.44 (A): fillViewport STAYS (the uniform
            // background needs it) — but it is also what
            // blew the state-A box up to ~60% of the
            // screen in 1.43. ROOT CAUSE, verified in
            // code: with the page measured EXACTLY
            // viewport-high, the box's WRAP_CONTENT
            // height is measured AT_MOST the remaining
            // viewport, and its plain 1dp divider View
            // (MATCH_PARENT height, no content of its
            // own) measures itself to that whole AT_MOST
            // size — a content-less View takes the full
            // spec size — so the box took the divider's
            // height and the title sat vertically centred
            // in it (the main part's CENTER gravity).
            // In 1.42 the page was measured UNSPECIFIED,
            // so the divider measured 0 and the box was
            // 67dp. (The v2ray free box has the same
            // divider pattern but its ScrollView has no
            // fillViewport, so it never blew up.) The
            // box now gets an EXACT height per state
            // (setBoxHeight), which also caps the
            // MATCH_PARENT children to that height.
            isFillViewport = true
            // 1.42 (E): same background as the page,
            // so the whole screen is one uniform
            // background even where there is no content.
            background = OvpnTheme.pageBackground(this@OpenVpnActivity)
            addView(page)
        }
        rootFrame.addView(pageScroll)
        // + FAB (72dp), always visible — EXACTLY the main
        // app's (config-renamer OpenVpnActivity.kt:296-
        // 313): a light glass circle with a gradient
        // border, blue "+" in day, white at night.
        // Positioned ABOVE the nav bar via the insets
        // listener.
        fab = Button(this).apply {
            text = "+"
            textSize = 32f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor(if (dark) "#FFFFFF" else "#8B7CF6"))
            background = Theme.gradientGlowCircle(this@OpenVpnActivity,
                if (dark) Theme.darken(Color.parseColor("#CC2A3048")) else Color.parseColor("#F2EEFB"), dark)
            layoutParams = FrameLayout.LayoutParams(dp(72), dp(72)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(dp(24), dp(24), dp(24), dp(24))
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener { importFile() }
        }
        rootFrame.addView(fab)
        setContentView(rootFrame)
        // Safe area: status-bar inset on top, nav-bar
        // inset under the FAB (never fitsSystemWindows,
        // which wipes padding).
        rootFrame.setOnApplyWindowInsetsListener { _, insets ->
            try {
                val top = insets.systemWindowInsetTop
                val bottom = insets.systemWindowInsetBottom
                page.setPadding(0, top, 0, dp(96))
                (fab?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.bottomMargin = dp(24) + bottom
                    fab?.layoutParams = lp
                }
            } catch (_: Exception) {}
            insets
        }
        renderTopBox()
    }

    // v1.2 header: FORCED LTR row [Openvpn] [log].
    // The manifest has no supportsRtl, so the framework
    // forces LTR layout everywhere. 1.29: the ☰ drawer is
    // gone — Log (app log) sits at the top of the page,
    // like the main app's OpenVPN section. 1.35: the
    // header "?" is gone too — the floating guide
    // button is the single guide entry.
    // Theme, language and About live in Settings.
    // 1.41 (E): «Profiles» header, exactly the main
    // app's — 26sp bold title, a "?" circle and the
    // log icon on the right (no ☰; the drawer is gone
    // since 1.29). 1.43 (D): "?" opens the GENERAL
    // OpenVPN guide dialog — exactly the main app's
    // showOvpnGuide, NOT the account-creation tutorial
    // (that lives in the top box's guide third).
    // The log icon opens the log. Forced LTR (the
    // manifest has no supportsRtl).
    private fun buildHeader(page: LinearLayout, dark: Boolean) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }
        header.addView(TextView(this).apply {
            text = "Profiles"
            textSize = 26f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // "?" circle (28dp), like the main app's.
        header.addView(TextView(this).apply {
            text = "?"
            textSize = 15.4f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER
            background = OvpnTheme.glassCircle(this@OpenVpnActivity)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginEnd = dp(8)
            }
            // 1.43 (D): the general OpenVPN guide, like
            // the main app's "?" — NOT the tutorial.
            setOnClickListener { showOvpnGuide() }
        })
        // Log icon (28dp), at the right end.
        header.addView(android.widget.ImageView(this).apply {
            setImageBitmap(HydraUi.logIconBitmap(this@OpenVpnActivity, 28))
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setOnClickListener { showLogDialog() }
        })
        page.addView(header)
    }

    // 1.41 (E): the top box — the SAME texture and
    // background as the v2ray free box (OvpnTheme.
    // freeBoxBackground, 20dp radius, 1dp stroke),
    // minHeight 84dp like the main app's state A.
    // Before the account is created: title «ساخت
    // اکانت openvpn رایگان» + subtitle «برای ساخت
    // اکانت رایگان فیلترشکن خود را روشن کنید», and
    // the right ~30% is the guide («راهنما», gently
    // blinking) behind a 1dp divider. After the
    // account is created the guide part is gone and
    // the box text is UNCHANGED (approved mockup
    // frame 4) — the main part then fills the whole
    // box. Tapping the main part = onFreeEntryTap
    // (the old orb tap); tapping the guide part =
    // the interactive tutorial. While creating, the
    // title reads «لطفا کمی صبر کنید» (main app
    // state A) and the tap only toasts.
    // 1.42 (B/C/D): the top box, main-app form
    // (config-renamer OpenVpnActivity.kt renderTbBox
    // ~line 803). State A (no account): a single-line
    // title + the guide third, WRAP_CONTENT with
    // minHeight 53dp — 1.71 item 5: the SAME height
    // as the WireGuard free box (was 67dp; the 84dp
    // of 1.41 is gone), and
    // the subtitle («برای ساخت اکانت رایگان فیلترشکن
    // خود را روشن کنید») is gone from the box: it
    // lives in the Guide now (GuideDetailActivity,
    // openvpn section). State B (account saved): the
    // main app's box — minHeight 105dp, top row =
    // toggle + «Free OpenVpn config» + DELETE only,
    // picker row «Select country: … ▾», inline country
    // menu inside the box when open. The box keeps
    // the SAME sapphire texture as the v2ray box
    // (OvpnTheme.freeBoxBackground); heights differ
    // by content (53 / 105dp — state B stays 105dp:
    // it is a TWO-row box, toggle + picker, and cannot
    // fit 53dp). The box is rebuilt by
    // renderTopBox() when its state signature changes.
    private fun buildTopBox(page: LinearLayout, dark: Boolean) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = OvpnTheme.freeBoxBackground(this@OpenVpnActivity)
            elevation = 2 * resources.displayMetrics.density
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(20), 0, dp(20), dp(14))
            }
        }
        topBoxView = box
        page.addView(box)
    }

    /** 1.44 (A): set the top box's height EXACTLY.
     *  minimumHeight alone does NOT cap a box: it is a
     *  floor, and a WRAP_CONTENT box still grows to its
     *  tallest child — which, under fillViewport, was
     *  the 1dp divider measuring itself to the whole
     *  remaining viewport (see the fillViewport note
     *  in buildUi). An EXACT layout height also makes
     *  the MATCH_PARENT children (main, divider,
     *  guide part) measure to exactly that height. */
    private fun setBoxHeight(hPx: Int) {
        val box = topBoxView ?: return
        try {
            val lp = box.layoutParams as? LinearLayout.LayoutParams ?: return
            if (lp.height != hPx) {
                lp.height = hPx
                box.layoutParams = lp
            }
        } catch (_: Exception) {}
    }

    /** 1.42: rebuilds the top box for the current state
     *  (called from buildUi, refreshStatus and
     *  refreshList). State A: title + guide third.
     *  State B-no-entry: account saved but no stored
     *  entry — the entry is restored first (main app
     *  renderTbBox ~line 903); if it still has none,
     *  a centred 53dp «Free OpenVpn config» label
     *  whose tap restores the entry. State B: the
     *  main-app box (105dp). */
    private fun renderTopBox() {
        val box = topBoxView ?: return
        try {
            val dark = OvpnTheme.isDark(this)
            val d = resources.displayMetrics.density
            // Account saved but no stored entry: bring
            // the entry back so the toggle can connect
            // (main app renderTbBox ~line 903). Only
            // when an account is still saved — after a
            // box delete the account is cleared too, so
            // nothing is resurrected.
            if (TunnelBear.hasAccount(this) && profiles.none { it.tunnelBear }) {
                try {
                    TunnelBear.ensureSingleEntry(this, restoreDeleted = true)
                    profiles = OvpnStore.load(this)
                } catch (_: Exception) {}
            }
            val hasAccount = TunnelBear.hasAccount(this)
            val p = tbProfile()
            val connected = OvpnEngine.isConnected || OvpnEngine.isConnecting
            val isActive = connected && p != null && OvpnEngine.activeName == p.name
            val isBusy = OvpnEngine.isConnecting && p != null && OvpnEngine.activeName == p.name
            val country = if (p != null) TunnelBear.selectedCountry(this) else ""
            val sig = listOf(hasAccount, tbCreating, p?.hostname ?: "", country,
                tbCountryMenuOpen, isActive, isBusy,
                // 1.43 (G): a pin tap must rebuild the box
                // (the open menu re-sorts pinned-first).
                TunnelBear.pinnedCountries(this).sorted().joinToString(",")).joinToString("|")
            if (sig == lastTopBoxSig) {
                // Same state: only make sure the state-A
                // guide pulse is running (onPause cancels
                // it, and this early return would else
                // leave the guide third unblinking).
                if (!hasAccount && topBoxGuidePart != null && topBoxGuidePulseAnim == null) {
                    val part = topBoxGuidePart ?: return
                    topBoxGuidePulseAnim = ValueAnimator.ofFloat(1f, 0.6f, 1f).apply {
                        duration = 2000
                        repeatCount = ValueAnimator.INFINITE
                        addUpdateListener { part.alpha = it.animatedValue as Float }
                        start()
                    }
                }
                return
            }
            lastTopBoxSig = sig
            topBoxGuidePulseAnim?.cancel()
            topBoxGuidePulseAnim = null
            topBoxGuidePart = null
            topBoxGuideDivider = null
            tbToggleView = null
            box.removeAllViews()
            if (!hasAccount) {
                // ---- State A: single-line title + guide. ----
                box.orientation = LinearLayout.HORIZONTAL
                box.layoutDirection = View.LAYOUT_DIRECTION_LTR
                box.gravity = Gravity.CENTER_VERTICAL
                box.isClickable = false
                box.minimumHeight = dp(53)
                // 1.44 (A) + 1.71 item 5: EXACT 53dp — the
                // WireGuard free box's height (was 67dp).
                // minimumHeight alone cannot cap the height
                // (see setBoxHeight); the exact-height pin
                // STAYS because this page's ScrollView has
                // fillViewport (1.44 — a minHeight-only box
                // blew up to ~60% of the screen in 1.43).
                setBoxHeight(dp(53))
                box.setPadding(0, 0, 0, 0)
                val main = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 70f)
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    isClickable = true
                    setOnClickListener { onFreeEntryTap() }
                }
                topBoxMain = main
                topBoxTitle = TextView(this).apply {
                    text = if (tbCreating)
                        L("لطفا کمی صبر کنید", "Please wait a moment")
                    else
                        // 1.43 (B): «اکانت openvpn رایگان»
                        // (was «ساخت اکانت openvpn رایگان»),
                        // 16.2sp (-10%), one line.
                        L("اکانت openvpn رایگان", "Free OpenVpn account")
                    textSize = 16.2f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(OvpnTheme.orbText(this@OpenVpnActivity))
                    textDirection = appTextDir()
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                main.addView(topBoxTitle)
                box.addView(main)
                val divider = View(this).apply {
                    setBackgroundColor(if (dark) Theme.darken(Color.parseColor("#33507A")) else Color.parseColor("#BFD9F5"))
                    layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
                }
                topBoxGuideDivider = divider
                box.addView(divider)
                val guidePart = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadii = floatArrayOf(
                            0f, 0f, 20f * resources.displayMetrics.density,
                            20f * resources.displayMetrics.density,
                            20f * resources.displayMetrics.density,
                            20f * resources.displayMetrics.density, 0f, 0f)
                        setColor(if (dark) Theme.darken(Color.parseColor("#14FFFFFF")) else Color.parseColor("#26FFFFFF"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 30f)
                    isClickable = true
                    setOnClickListener { startGuide() }
                }
                guidePart.addView(TextView(this).apply {
                    text = T("راهنما", "Guide", "Руководство")
                    // 1.43 (B): guide font -10% too.
                    textSize = 12.6f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(OvpnTheme.orbText(this@OpenVpnActivity))
                    textDirection = appTextDir()
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                topBoxGuidePart = guidePart
                box.addView(guidePart)
                topBoxGuidePulseAnim = ValueAnimator.ofFloat(1f, 0.6f, 1f).apply {
                    duration = 2000
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { guidePart.alpha = it.animatedValue as Float }
                    start()
                }
                return
            }
            if (p == null) {
                // ---- State B, no stored entry: 53dp label; tap restores. ----
                box.orientation = LinearLayout.VERTICAL
                box.gravity = Gravity.CENTER
                box.minimumHeight = dp(53)
                // 1.44 (A): EXACT 53dp here too (1.71 item 5).
                setBoxHeight(dp(53))
                box.setPadding(dp(16), dp(12), dp(16), dp(12))
                topBoxMain = box
                box.addView(TextView(this).apply {
                    text = "Free OpenVpn config"
                    // 1.43 (B): box title -10%.
                    textSize = 16.2f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
                    textDirection = View.TEXT_DIRECTION_LTR
                    gravity = Gravity.CENTER
                })
                box.isClickable = true
                box.setOnClickListener {
                    try {
                        TunnelBear.ensureSingleEntry(this, restoreDeleted = true)
                        profiles = OvpnStore.load(this)
                    } catch (_: Exception) {}
                    refreshList()
                }
                return
            }
            // ---- State B: account + entry (main app form). ----
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.NO_GRAVITY
            box.isClickable = false
            box.minimumHeight = dp(105)
            // 1.44 (A): EXACT 105dp while the menu is
            // closed; WRAP_CONTENT while it is open so
            // the box grows to fit the menu (mockup
            // frame 3). 105dp is the menu-CLOSED height
            // only.
            // 1.44 (C): any stale box-body click
            // listener on this same View object (the
            // state-B-no-entry restore tap) is cleared
            // here — in state B ONLY the left toggle
            // connects. The state-A creation tap (the
            // main part's own listener) is untouched.
            box.setOnClickListener(null)
            setBoxHeight(if (tbCountryMenuOpen)
                LinearLayout.LayoutParams.WRAP_CONTENT else dp(105))
            box.setPadding(dp(16), dp(12), dp(16), dp(12))
            topBoxMain = box
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val boxToggle = android.widget.ImageView(this).apply {
                setImageBitmap(if (isBusy) HydraUi.toggleBitmapBusy(this@OpenVpnActivity)
                    else if (dark) Theme.pillToggleBitmap(this@OpenVpnActivity, (62 * 0.7f).toInt(), (41 * 0.7f).toInt(), isActive,
                        "#8B9098", "#9AA0B0", "#1B746C", "#B2DFDB")
                    else Theme.pillToggleBitmap(this@OpenVpnActivity, (62 * 0.7f).toInt(), (41 * 0.7f).toInt(), isActive,
                        "#FFF3D6", "#FFFFFF", "#C8E6C9", "#81C784"))
                layoutParams = LinearLayout.LayoutParams((48 * 0.7f * d).toInt(), (32 * 0.7f * d).toInt()).apply {
                    setMargins(0, 0, (8 * 0.7f * d).toInt(), 0)
                }
                setOnClickListener { onFreeEntryTap() }
            }
            tbToggleView = boxToggle
            topRow.addView(boxToggle)
            val labelCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding((24 * d).toInt(), 0, (12 * d).toInt(), 0)
            }
            labelCol.addView(TextView(this).apply {
                text = "Free OpenVpn config"
                // 1.43 (B): box title -10%.
                textSize = 16.2f
                setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
                if (isActive) setTypeface(null, android.graphics.Typeface.BOLD)
                textDirection = View.TEXT_DIRECTION_LTR
                gravity = Gravity.START
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            topRow.addView(labelCol)
            // Delete only (no edit, no info): the main
            // app's pink trash circle.
            val trashPad = (8 * 0.7f * d).toInt()
            topRow.addView(android.widget.ImageView(this).apply {
                setImageBitmap(Theme.trashGlyphBitmap(this@OpenVpnActivity,
                    if (dark) "#F48FB1" else "#D32F2F", (20 * 0.7f).toInt()))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (dark) Theme.darken(Color.parseColor("#33FCE4EC")) else Color.parseColor("#FCE4EC"))
                    setStroke((1 * d).toInt(), Color.parseColor(if (dark) "#F48FB1" else "#F8BBD0"))
                }
                val s = (36 * 0.7f * d).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    marginStart = (6 * 0.7f * d).toInt()
                }
                setPadding(trashPad, trashPad, trashPad, trashPad)
                setOnClickListener { confirmDeleteTb(p) }
            })
            box.addView(topRow)
            // Picker row (no «Country: …» line — main app 3.06).
            // 1.44 (B): START-aligned EXACTLY under the
            // name (the 1.43 centring is reversed), text
            // «Location : <flag> <Country> ▾» — flag
            // BEFORE the name (the 1.43 flag-after-name
            // order is reversed), small ▾ after it.
            // 1.44 (B): the row's left edge IS the
            // name's left edge — toggle width + toggle
            // margin + the label column's start padding,
            // all in px, so it stays aligned at any
            // density (the name sits in labelCol after
            // the toggle, inside the box's 16dp padding).
            val nameIndentPx = (48 * 0.7f * d).toInt() +
                (8 * 0.7f * d).toInt() + (24 * d).toInt()
            val pickerRow = TextView(this).apply {
                val flag = TunnelBear.flagEmoji(country)
                text = if (flag.isEmpty()) "Location : $country ▾"
                    else "Location : $flag $country ▾"
                textSize = 15f
                setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
                textDirection = View.TEXT_DIRECTION_LTR
                gravity = Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                setPadding(nameIndentPx, (10 * d).toInt(), 0, (4 * d).toInt())
                isClickable = true
                setOnClickListener {
                    tbCountryMenuOpen = !tbCountryMenuOpen
                    refreshList()
                }
            }
            box.addView(pickerRow)
            // Inline menu INSIDE the box: fixed-height
            // ScrollView showing ~5 countries at a time.
            if (tbCountryMenuOpen) {
                val menuList = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                }
                // 1.43 (G): pinned countries FIRST, then
                // alphabetical — the same order as the cards.
                val pinnedMenu = TunnelBear.pinnedCountries(this)
                val menuCountries = TunnelBear.countries(this).sortedWith(
                    compareByDescending<String> { pinnedMenu.contains(it) }
                        .thenBy { it.lowercase() })
                for (c in menuCountries) {
                    val isSel = c.equals(country, ignoreCase = true)
                    val isPinned = pinnedMenu.contains(c)
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutDirection = View.LAYOUT_DIRECTION_LTR
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT)
                        isClickable = true
                    }
                    // 1.44 (D): [flag] [name] on the LEFT,
                    // pin on the RIGHT edge (the 1.43 pin
                    // between flag and name is moved).
                    row.addView(TextView(this).apply {
                        text = TunnelBear.flagEmoji(c)
                        textSize = 16f
                        textDirection = View.TEXT_DIRECTION_LTR
                        gravity = Gravity.START
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT)
                        setPadding((12 * d).toInt(), (10 * d).toInt(), 0, (10 * d).toInt())
                    })
                    row.addView(TextView(this).apply {
                        text = if (isSel) "✓ $c" else c
                        textSize = 16f
                        if (isSel) setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(if (isSel) Color.parseColor("#2E9E5B") else OvpnTheme.fg(this@OpenVpnActivity))
                        textDirection = View.TEXT_DIRECTION_LTR
                        gravity = Gravity.START
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        setPadding((4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt())
                    })
                    // Pin at the RIGHT edge, small gap.
                    // Its own click listener toggles the
                    // pin ONLY — no select, no connect.
                    row.addView(android.widget.ImageView(this).apply {
                        setImageBitmap(OvpnTheme.pinBitmap(this@OpenVpnActivity,
                            if (isPinned) "#2E9E6B" else "#9E9E9E", 16, isPinned))
                        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                            marginEnd = dp(8)
                        }
                        isClickable = true
                        setOnClickListener {
                            try {
                                TunnelBear.setPinned(this@OpenVpnActivity, c, !isPinned)
                            } catch (_: Exception) {}
                            // Rebuilds the box (sig carries
                            // the pinned set): the open menu
                            // re-sorts pinned-first.
                            renderTopBox()
                        }
                    })
                    row.setOnClickListener {
                        try {
                            TunnelBear.saveSelectedCountry(this@OpenVpnActivity, c)
                            TunnelBear.ensureSingleEntry(this@OpenVpnActivity)
                            profiles = OvpnStore.load(this@OpenVpnActivity)
                        } catch (_: Exception) {}
                        tbCountryMenuOpen = false
                        refreshList()
                        refreshStatus()
                    }
                    menuList.addView(row)
                }
                box.addView(ScrollView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (5 * 44 * d).toInt())
                    isVerticalScrollBarEnabled = true
                    isNestedScrollingEnabled = true
                    setOnTouchListener { v, ev ->
                        when (ev.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN,
                            android.view.MotionEvent.ACTION_MOVE ->
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            android.view.MotionEvent.ACTION_UP,
                            android.view.MotionEvent.ACTION_CANCEL ->
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false
                    }
                    addView(menuList)
                })
            }
        } catch (_: Exception) {}
    }

    /** 1.41 (E): the status label, main-app style —
     *  "CONNECTED: <name>" (green), "CONNECTING:
     *  <name>  [state] [OpenVPN3]" (orange) or
     *  "DISCONNECTED" (steel/teal). A sweep measurement
     *  must never rewrite it (v1.20). The name is the
     *  display name (never the raw "TunnelBear"). */
    private fun renderStatusLabel() {
        val label = statusLabel ?: return
        val dark = OvpnTheme.isDark(this)
        val connecting = OvpnEngine.isConnecting
        val connected = OvpnEngine.isConnected
        val name = OvpnEngine.activeName
        when {
            connected && name != null -> {
                label.text = "CONNECTED: " + TunnelBear.displayNameForStored(name)
                label.setTextColor(Color.parseColor("#2E9E5B"))
            }
            connecting && name != null -> {
                val st = OvpnEngine.lastState
                label.text = if (st != null)
                    "CONNECTING: " + TunnelBear.displayNameForStored(name) + "  [$st] [OpenVPN3]"
                else
                    "CONNECTING: " + TunnelBear.displayNameForStored(name) + " [OpenVPN3]"
                label.setTextColor(Color.parseColor("#E67E22"))
            }
            else -> {
                label.text = "DISCONNECTED"
                label.setTextColor(Color.parseColor(if (dark) "#26A69A" else "#5B7A99"))
            }
        }
    }

    /** 1.41 (E): the top box's main-part tap — the old
     *  free-tab orb tap (onOrbTap's free branch), moved
     *  to the box. No account → create one; creating →
     *  toast (the waiting dialog has its own cancel);
     *  connecting → cancel; connected → disconnect;
     *  SMART → smartConnect(); else connectProfile(tb). */
    private fun onFreeEntryTap() {
        val p = tbProfile()
        if (!TunnelBear.hasAccount(this)) {
            startTbAutoCreation()
            return
        }
        if (p == null) return
        if (tbCreating) {
            android.widget.Toast.makeText(this,
                L("لطفا کمی صبر کنید", "Please wait a moment"),
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // v1.12: tapping WHILE connecting CANCELS the
        // attempt (item 3) — the old code just started
        // a second ladder over the first one.
        if (OvpnEngine.isConnecting && OvpnEngine.activeName == TunnelBear.SINGLE_NAME) {
            cancelCurrentConnect()
            return
        }
        if (freeConnected()) {
            OvpnEngine.disconnect()
            refreshStatus(); refreshList()
        } else if (TunnelBear.isSmartSelection(this)) {
            // v1.26: Smart Connect — the app picks the
            // best location itself (first pinned, else
            // the default country). No checking.
            smartConnect()
        } else {
            connectProfile(p)
        }
    }






    // 1.41: the status label NEVER shows a connect
    // error (log only) — same rule as the old status
    // line.
    // ---------- «راهنمای اتصال» guided tutorial (v1.6, v1.7, v1.8) ----------
    // 1.36: the same interactive spotlight pattern as the
    // v2ray tutorial — the page dims 80% except the
    // target, and every step has a «رد کردن» skip. The
    // bright target IS the pointer: nothing is drawn
    // around it. The tutorial advances only by tapping
    // the target or by real state changes.
    // 1.40: the guide button STAYS — the 1.7 rule that
    // hid it for good after the first free connection
    // is dropped at the user's «برشگردون» order.
    // 1.41: the floating guide button is GONE — the
    // entries are the header "?" and the top box's
    // guide third. Steps follow the REAL free flow:
    //  1. top box — turn your own VPN on, tap the box
    //     to start creating the free account.
    //  2. top box — wait while the account is created.
    //  4. Top-box toggle — tap it to connect (shown
    //     only when not already connected; if the
    //     auto-connect already connected, the tutorial
    //     simply ends). A failed step-4 connect ENDS
    //     the guide (no country to pick anymore).
    // v1.12: while the guide is active, the account does NOT
    // auto-connect on creation (handleCreationReady) — the
    // guide walks the whole post-creation flow instead.
    // The state machine (checkGuideState, driven by the 2s
    // poller + a check after every forwarded target tap)
    // advances/retreats from the real state, so the tutorial
    // can never point at a step whose precondition is gone.
    // 1.41: steps 1/2 spotlight the top box's main
    // part (tap = create the account); step 4
    // spotlights the top box's connect toggle
    // (1.42: the TB card is gone).
    // Steps 3/5 (country picking) are gone.
    private fun guideTargetView(step: Int): View? = when (step) {
        1, 2 -> topBoxMain
        4 -> tbToggleView
        else -> null
    }

    private fun guideTitle(step: Int): String =
        T("راهنمای ساخت اکانت - مرحله $step",
            "Account guide — step $step",
            "Руководство — шаг $step")

    /** v1.8: the target's name is BOLD in accent colour
     *  inside the box text («باکس ساخت اکانت» /
     *  «دکمه اتصال») — the bright target plus its bold
     *  name IS the pointer now. Step 2 has no target
     *  name. 1.41: the country steps (3/5) are gone. */
    private fun guideText(step: Int): CharSequence = when (step) {
        1 -> boldTarget(
            T("اول فیلترشکن خودت را روشن کن، بعد روی ", "First turn on your own VPN, then tap the ", "Сначала включите свой VPN, затем нажмите на "),
            T("باکس ساخت اکانت", "account box", "окно создания аккаунта"),
            T(" بزن.", ".", "."))
        2 -> T("کمی صبر کنید تا اکانت رایگان ساخته بشود",
            "Please wait while the free account is being created.",
            "Подождите, создаётся бесплатный аккаунт.")
        else -> boldTarget(
            T("حالا روی ", "Now tap the ", "Теперь нажмите на "),
            T("دکمه اتصال", "connect button", "кнопку подключения"),
            T(" بزن تا وصل شوی.", " to connect.", ", чтобы подключиться."))
    }

    private fun boldTarget(prefix: String, target: String, suffix: String): CharSequence {
        val sb = android.text.SpannableStringBuilder()
        sb.append(prefix)
        val start = sb.length
        sb.append(target)
        sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            start, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(android.text.style.ForegroundColorSpan(OvpnTheme.accent(this)),
            start, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(suffix)
        return sb
    }


    private fun startGuide() {
        if (guideStep > 0) return
        // 1.40: the old `|| TunnelBear.hasFirstFreeConnection(this)`
        // guard refused to start the tutorial once the user had
        // ever connected for free — together with the old
        // guide button's hide rule, that is why the guide
        // was gone. The user's «برشگردون» order drops it:
        // the guide opens any time, from the real state.
        // 1.41: start from the real state — a fresh user
        // starts at account creation (1); a user who
        // already has an account starts at connect (4).
        // The country-picking start (3) is gone with
        // the country chip.
        val first = when {
            !TunnelBear.hasAccount(this) -> 1
            freeConnected() -> return
            else -> 4
        }
        guideStep = first
        guideStep4Tried = false
        buildGuideOverlay()
        showGuideStep(first)
    }

    private fun endGuide() {
        guideStep = 0
        guideStep4Tried = false
        removeGuideStep2Window()
        try { guideOverlay?.let { rootFrame.removeView(it) } } catch (_: Exception) {}
        guideOverlay = null
        guideDimView = null
        guideBox = null
        guideBoxTitle = null
        guideBoxText = null
    }

    /** v1.7: the first free-tunnel connection ends the
     *  tutorial for good — mark it, close the guide
     *  (1.40: the BUTTON no longer hides with it —
     *  it stays on the Free tab). Called from the state
     *  machine and from the poller (so it also fires
     *  when the guide was never opened). */
    private fun noteFirstFreeConnection() {
        try {
            // v1.20: ALWAYS persist the flag when a real free
            // connect is observed — the old `if (!has...)`
            // guard skipped the write whenever the 7-day dial
            // seed was true, so the pref was NEVER stored and
            // the flag depended on the volatile dial seed: a
            // failed dial (a failed user tap, or a failed
            // sweep measurement overwriting the last success)
            // flipped it false, so the guide button reappeared
            // (and, before 1.72 moved it to Home, the speed box
            // hid until the next successful dial). The pref, once
            // written, is the source of truth; the seed stays
            // only as a fallback.
            TunnelBear.markFirstFreeConnection(this)
        } catch (_: Exception) {}
        // 1.71 (item 3): this is the exact moment a free
        // tunnel is observed CONNECTED — record which
        // country it is, so the widget resolves to the
        // last-CONNECTED free location.
        try { recordLastFreeCountry() } catch (_: Exception) {}
        if (guideStep > 0) endGuide()
        renderTopBox()
    }

    // ---------- 1.71 item 3: widget connects to the LAST-CONNECTED free location ----------
    // The widget opens this activity with
    // widget_auto_connect=true (WidgetProviderBase.
    // openSectionActivity), but this activity NEVER read
    // that extra — the tap just opened the page and did
    // nothing. And the widget's own target
    // (WidgetProviderBase.lastOvpnProfile) resolves by
    // the stored profile NAME "ovpn_last", while the
    // single free entry's CONTENT follows the latest
    // PICK (the country menu saves the pick and rebuilds
    // the entry at once) — so after picking a country
    // without connecting, the widget would have connected
    // the picked-but-never-connected location, not the
    // last-connected one. The fix, activity-side: record
    // the country of the free tunnel that actually reaches
    // CONNECTED (recordLastFreeCountry, called from
    // noteFirstFreeConnection — the exact CONNECTED
    // observation) under LAST_FREE_COUNTRY_KEY in the
    // same "renamer_ovpn" prefs, and make the widget tap
    // connect the free entry built for THAT country
    // (handleWidgetAutoConnect). The provider side reads
    // the same key (another worker). Narrow edge: if the
    // free tunnel was connected by another component
    // (Smart service) this process never ran
    // connectProfile, so the record falls back to
    // selectedCountry at CONNECTED time — still the
    // connected location unless the user picked another
    // country mid-connection.

    /** The last-CONNECTED free country ("renamer_ovpn"
     *  pref LAST_FREE_COUNTRY_KEY), or null when the
     *  free entry never connected yet. Only a real
     *  47-list country counts. */
    private fun lastFreeCountry(): String? {
        return try {
            getSharedPreferences("renamer_ovpn", MODE_PRIVATE)
                .getString(LAST_FREE_COUNTRY_KEY, null)
                ?.takeIf { it.isNotBlank() && TunnelBear.isKnownCountry(this, it) }
        } catch (_: Exception) { null }
    }

    /** Record the country of the free tunnel that just
     *  reached CONNECTED. The attempt's country was
     *  captured at connect start (connectingFreeCountry
     *  — a pick made DURING the connect must not move
     *  the record); when this process never ran
     *  connectProfile for it (Smart service), fall back
     *  to the selection at CONNECTED time. */
    private fun recordLastFreeCountry() {
        val country = connectingFreeCountry
            ?.takeIf { TunnelBear.isKnownCountry(this, it) }
            ?: TunnelBear.selectedCountry(this)
                .takeIf { TunnelBear.isKnownCountry(this, it) }
            ?: return
        try {
            getSharedPreferences("renamer_ovpn", MODE_PRIVATE).edit()
                .putString(LAST_FREE_COUNTRY_KEY, country).apply()
        } catch (_: Exception) {}
    }

    /** Widget tap: auto-connect 600ms after the page
     *  opens (the VpnActivity pattern), once per
     *  activity instance. Fires from onCreate (fresh
     *  instance — the normal widget-tap path) and from
     *  onNewIntent (activity already alive). */
    private fun handleWidgetAutoConnect() {
        if (widgetAutoConnectDone) return
        if (!intent.getBooleanExtra("widget_auto_connect", false)) return
        widgetAutoConnectDone = true
        handler.postDelayed({
            try {
                if (isFinishing || isDestroyed) return@postDelayed
                if (OvpnEngine.isConnected || OvpnEngine.isConnecting) return@postDelayed
                if (tbCreating) return@postDelayed
                connectWidgetLastFree()
            } catch (_: Exception) {}
        }, 600)
    }

    /** Connect the free entry built for the
     *  LAST-CONNECTED free country — never the
     *  last-picked-but-never-connected one. When no
     *  free connection was ever recorded, behave like
     *  an in-app tap (Smart re-picks, otherwise the
     *  selected / default location). Aligning the
     *  selection to the connected country keeps the
     *  box, log and chip truthful; a SMART selection
     *  stays SMART (only the smart choice moves). */
    private fun connectWidgetLastFree() {
        val target = lastFreeCountry()
        if (target == null) {
            if (TunnelBear.isSmartSelection(this)) { smartConnect(); return }
            val p = tbProfile() ?: return
            connectProfile(p)
            return
        }
        try {
            if (TunnelBear.isSmartSelection(this)) {
                if (target != TunnelBear.smartChoice(this)) {
                    TunnelBear.saveSmartChoice(this, target)
                    TunnelBear.ensureSingleEntry(this)
                    profiles = OvpnStore.load(this)
                }
            } else if (target != TunnelBear.selectedCountry(this)) {
                TunnelBear.saveSelectedCountry(this, target)
                TunnelBear.ensureSingleEntry(this)
                profiles = OvpnStore.load(this)
            }
        } catch (_: Exception) {}
        val p = tbProfile() ?: return
        connectProfile(p)
    }

    /** Advance/retreat from the REAL state. Called by the
     *  2s poller and after every forwarded target tap.
     *  1.41: steps 3/5 (country picking) are gone — step
     *  2 goes straight to step 4 (connect), and a
     *  failed step-4 connect ENDS the guide (there is
     *  no other location to pick; the toggle stays
     *  tappable and the failure is in the log). */
    private fun checkGuideState() {
        if (guideStep <= 0) return
        if (guideStep == 1 && tbCreating) { showGuideStep(2); return }
        if (guideStep == 2) {
            if (TunnelBear.hasAccount(this)) { showGuideStep(4); return }
            if (!tbCreating) { showGuideStep(1); return }
        }
        if (guideStep in 1..2 && TunnelBear.hasAccount(this)) { showGuideStep(4); return }
        if (guideStep == 4 && freeConnected()) { noteFirstFreeConnection(); endGuide(); return }
        // 1.41: remember that the user really tried step 4's
        // connect, so a stale lastError cannot end the guide.
        if (guideStep == 4 && OvpnEngine.isConnecting) guideStep4Tried = true
        // 1.41: the step-4 connect failed (and is not
        // still trying) — end the guide; the user can
        // tap the toggle again, and the failure line
        // is in the app log.
        if (guideStep == 4 && guideStep4Tried && !OvpnEngine.isConnecting && !freeConnected()
            && OvpnEngine.lastError != null) {
            endGuide()
            return
        }
    }

    private fun showGuideStep(n: Int) {
        if (guideOverlay == null) return
        guideStep = n
        if (n == 2) {
            // v1.8: the waiting step has no pointer and
            // its box is a second dialog above the
            // waiting dialog (the v1.7 attached window
            // still sat under that dialog's dim and came
            // out faded on the phone). The overlay box
            // stays hidden for this step.
            guideBox?.visibility = View.GONE
            guideOverlay?.post { positionGuideBox() }
            showGuideStep2Window()
            return
        }
        removeGuideStep2Window()
        guideBoxTitle?.text = guideTitle(n)
        guideBoxText?.text = guideText(n)
        guideBox?.visibility = View.INVISIBLE
        guideOverlay?.post { positionGuideBox() }
    }

    /** v1.8: step 2's box is a SECOND dialog, shown AFTER
     *  the waiting dialog. WHY this provably sits above
     *  the waiting dialog's dim: both dialogs are plain
     *  top-level windows of the same type
     *  (TYPE_APPLICATION), and the WindowManager stacks
     *  same-type windows in insertion order — the later
     *  one is on top. The waiting dialog's dim is a layer
     *  attached BELOW its own window, so anything in a
     *  later top-level window renders above that dim.
     *  (v1.7's TYPE_APPLICATION_ATTACHED_DIALOG window
     *  was a sub-window of the ACTIVITY, not of the
     *  dialog — the dialog's dim covered it, which is
     *  why the box still came out faded on the phone.)
     *  This dialog adds no dim of its own (dimAmount 0)
     *  and its window background is transparent, so only
     *  the opaque pill shows — fully bold in day and
     *  night. It is not cancelable and never takes
     *  touches outside the pill (FLAG_NOT_TOUCH_MODAL).
     *  The text is just the one sentence the user
     *  asked for, plus a 1.36 «رد کردن» — the waiting
     *  step has a skip now, like every other step. */
    private fun showGuideStep2Window() {
        if (guideStep2Dialog?.isShowing == true) return
        if (isFinishing || isDestroyed) return
        try {
            val outer = FrameLayout(this).apply {
                setPadding(dp(16), 0, dp(16), 0)
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = OvpnTheme.dialogCard(this@OpenVpnActivity)
                setPadding(dp(16), dp(13), dp(10), dp(13))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT)
            }
            val tv = TextView(this).apply {
                text = guideText(2)
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
                textDirection = appTextDir()
                gravity = Gravity.START
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            box.addView(tv)
            // 1.36: skip on the waiting step too.
            box.addView(TextView(this).apply {
                text = T("رد کردن", "Skip", "Пропустить")
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(OvpnTheme.accent(this@OpenVpnActivity))
                textDirection = appTextDir()
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(2), dp(4))
                isClickable = true
                setOnClickListener { endGuide() }
            })
            outer.addView(box)
            val dlg = android.app.Dialog(this).apply {
                setContentView(outer)
                setCancelable(false)
                setCanceledOnTouchOutside(false)
            }
            val win = dlg.window
            if (win != null) {
                win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                win.setDimAmount(0f)
                win.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                val lp = win.attributes
                lp.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
                lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                lp.y = dp(48)
                win.attributes = lp
            }
            guideStep2Dialog = dlg
            dlg.show()
        } catch (_: Exception) {}
    }

    private fun removeGuideStep2Window() {
        try { guideStep2Dialog?.dismiss() } catch (_: Exception) {}
        guideStep2Dialog = null
    }

    private fun buildGuideOverlay() {
        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
            isClickable = true
            isFocusable = true
        }
        val dim = GuideOverlayView(this).apply {
            // 1.36: 80% dim (was 60%) — the page goes
            // dark except the spotlight hole.
            dimColor = Color.parseColor("#CC000000")
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
        }
        guideDimView = dim
        overlay.addView(dim)
        // Small box: header + 1–2 lines.
        // 1.36: «رد کردن» in the header — the tutorial
        // exits at any step now (v1.10 had no ✕ on
        // purpose; the user ordered a skip). Exiting
        // never hides the guide button: it stays until
        // the first free connection.
        // v1.7: no «بعدی» button — the tutorial advances
        // only by tapping the target or by real state
        // changes.
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = OvpnTheme.glassCard(this@OpenVpnActivity, 16f)
            layoutParams = FrameLayout.LayoutParams(dp(280),
                FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.INVISIBLE
        }
        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = rtlDir()
        }
        guideBoxTitle = TextView(this).apply {
            textSize = 13.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headRow.addView(guideBoxTitle)
        // 1.36: skip — exits the tutorial at any step.
        headRow.addView(TextView(this).apply {
            text = T("رد کردن", "Skip", "Пропустить")
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.accent(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(0), dp(4))
            isClickable = true
            setOnClickListener { endGuide() }
        })
        box.addView(headRow)
        guideBoxText = TextView(this).apply {
            textSize = 13f
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        box.addView(guideBoxText)
        guideBox = box
        overlay.addView(box)
        // Taps: on the target → perform its real click and
        // re-check the state shortly after; on the box →
        // the box's own buttons (they are children, so this
        // listener only fires for touches no child consumed);
        // anywhere else → swallowed, the tutorial blocks them.
        overlay.setOnTouchListener { _, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_UP) {
                val target = guideTargetView(guideStep)
                if (target != null) {
                    val loc = IntArray(2)
                    target.getLocationOnScreen(loc)
                    val inTarget = ev.rawX >= loc[0] && ev.rawX <= loc[0] + target.width &&
                        ev.rawY >= loc[1] && ev.rawY <= loc[1] + target.height
                    if (inTarget) {
                        try { target.performClick() } catch (_: Exception) {}
                        handler.postDelayed({ checkGuideState() }, 600)
                    }
                }
            }
            true
        }
        rootFrame.addView(overlay)
        guideOverlay = overlay
    }

    /** Position the small box for the current step and cut
     *  the spotlight hole around the target. Below the
     *  target when it fits, else above; step 2 (the
     *  centred waiting dialog) has no box — the second
     *  dialog carries the text. Never covers the target
     *  itself. */
    private fun positionGuideBox() {
        val overlay = guideOverlay ?: return
        val dim = guideDimView ?: return
        val box = guideBox ?: return
        val target = guideTargetView(guideStep) ?: return
        val oloc = IntArray(2)
        overlay.getLocationOnScreen(oloc)
        val tloc = IntArray(2)
        target.getLocationOnScreen(tloc)
        val targetRect = RectF(
            (tloc[0] - oloc[0]).toFloat(), (tloc[1] - oloc[1]).toFloat(),
            (tloc[0] - oloc[0] + target.width).toFloat(),
            (tloc[1] - oloc[1] + target.height).toFloat())
        if (guideStep == 2) {
            // v1.8: no box, no pointer on the waiting step —
            // the second dialog above the waiting dialog
            // carries the text. The veil stays (it blocks
            // page taps while the account is being created)
            // and the hole keeps the top box bright
            // behind the dialog.
            dim.targetRect = targetRect
            dim.invalidate()
            return
        }
        val wSpec = View.MeasureSpec.makeMeasureSpec(dp(280), View.MeasureSpec.AT_MOST)
        val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        box.measure(wSpec, hSpec)
        val bw = box.measuredWidth
        val bh = box.measuredHeight
        val ow = overlay.width
        val oh = overlay.height
        if (ow <= 0 || oh <= 0 || bw <= 0 || bh <= 0) return
        val margin = dp(16)
        val gap = dp(14)
        val bx = (targetRect.centerX() - bw / 2f).toInt()
            .coerceIn(margin, (ow - bw - margin).coerceAtLeast(margin))
        var by = run {
            val below = (targetRect.bottom + gap).toInt()
            if (below + bh <= oh - dp(24)) below
            else (targetRect.top - gap - bh).toInt().coerceAtLeast(dp(24))
        }
        by = by.coerceIn(dp(24), (oh - bh - dp(24)).coerceAtLeast(dp(24)))
        (box.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = bw
            it.height = bh
            it.leftMargin = bx
            it.topMargin = by
            it.gravity = Gravity.TOP or Gravity.START
            box.layoutParams = it
        }
        box.visibility = View.VISIBLE
        dim.targetRect = targetRect
        dim.invalidate()
    }


    /** v1.2: open the Telegram channel WITH THE
     *  TELEGRAM APP when it is installed — never the
     *  browser. Order: tg:// deep link (Telegram /
     *  Telegram X packages), generic tg://, https
     *  targeted at the Telegram packages, and only
     *  then a generic https link (browser, last
     *  resort). The manifest <queries> block makes
     *  the package checks work on Android 11+. */
    private fun openTelegramChannel() {
        val domain = "internet_azad_2026"
        val pm = packageManager
        fun canResolve(i: Intent): Boolean = try {
            pm.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY) != null
        } catch (_: Exception) { false }
        fun tryStart(i: Intent): Boolean = try {
            startActivity(i); true
        } catch (_: Exception) { false }
        val tgPackages = listOf("org.telegram.messenger", "org.thunderdog.challegram")
        for (pkg in tgPackages) {
            val i = Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("tg://resolve?domain=$domain")).apply { setPackage(pkg) }
            if (canResolve(i) && tryStart(i)) return
        }
        val tgGeneric = Intent(Intent.ACTION_VIEW,
            android.net.Uri.parse("tg://resolve?domain=$domain"))
        if (canResolve(tgGeneric) && tryStart(tgGeneric)) return
        for (pkg in tgPackages) {
            val i = Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://t.me/$domain")).apply { setPackage(pkg) }
            if (canResolve(i) && tryStart(i)) return
        }
        tryStart(Intent(Intent.ACTION_VIEW,
            android.net.Uri.parse("https://t.me/$domain")))
    }

    // ---------- orb / pill / status rendering ----------
    private fun freeConnected(): Boolean =
        OvpnEngine.isConnected && OvpnEngine.activeName == TunnelBear.SINGLE_NAME

    /** v1.12: cancel a running connect attempt (item 3) —
     *  stop the ladder (the generation guard supersedes
     *  it), stop the engine, clear the connecting state
     *  and refresh the orb to idle. The pending log flag
     *  is cleared too: a user cancel is not a failed
     *  dial, so no failure line is written for it. */
    private fun cancelCurrentConnect() {
        retryGen.incrementAndGet()
        retryLadderRunning = false
        currentRungFa = null; currentRungEn = null; currentRungRu = null
        try { OvpnEngine.disconnect() } catch (_: Exception) {}
        appLogOvpnPending = false
        refreshStatus(); refreshList()
    }

    // ---------- status + list ----------

    /** Display name for AppLog lines (v1.2): the stored
     *  free-entry name («TunnelBear») NEVER reaches the
     *  log. The free entry logs as «Free OpenVpn config»
     *  + the selected country when one is chosen; own
     *  profiles log with their own name. The RAW stored
     *  name is still kept in appLogOvpnPendingName /
     *  appLogOvpnLastName — recordDial and the failover
     *  check need it — only the LOG TEXT is mapped. */
    private fun logDisplayName(name: String?): String {
        if (name == TunnelBear.SINGLE_NAME) {
            val c = TunnelBear.selectedCountry(this)
            return if (TunnelBear.hasSelectedCountry(this))
                TunnelBear.DISPLAY_NAME + " — " + TunnelBear.displayCountry(c)
            else TunnelBear.DISPLAY_NAME
        }
        return name ?: ""
    }

    /** v1.3: DISPLAY-time mapping for old log lines. The
     *  v1.2 write-time mapping (logDisplayName) only fixes
     *  NEW lines; lines written by v1.1 still carry the raw
     *  stored name. The stored log is append-only history
     *  and is never rewritten — instead, each line is
     *  mapped when rendered, so old lines read as
     *  «Free OpenVpn config» (no country suffix — the
     *  country then is unknown). Applied in showLogDialog
     *  only; the stored text is untouched. */
    private fun displayLogLine(line: String): String =
        line.replace(TunnelBear.SINGLE_NAME, TunnelBear.DISPLAY_NAME)

    private fun refreshStatus() {
        // Central-log flip detector (2.43): direct connects,
        // direct failures and manual stops. No engine, no chain.
        val ovpnNow = OvpnEngine.isConnected && OvpnEngine.activeName != null
        if (ovpnNow && !appLogOvpnWasConnected) {
            if (appLogOvpnPending) {
                val dispName = logDisplayName(OvpnEngine.activeName)
                // v1.10: when a retry-ladder rung won, the line
                // names the rung; otherwise it is a plain direct
                // connect (free entry or first-rung win).
                val wonFa = retryWonFa; val wonEn = retryWonEn; val wonRu = retryWonRu
                retryWonFa = null; retryWonEn = null; retryWonRu = null
                // v1.12: the free entry connects through the
                // internal split relay (TCP 7011), not a plain
                // direct dial — the old line said «اتصال
                // مستقیم» for it (item 2). Plus the duration.
                val howFa = if (wonFa != null) "روش «$wonFa»" else if (OvpnEngine.activeName == TunnelBear.SINGLE_NAME) "ریلی داخلی (TCP 7011)" else "اتصال مستقیم"
                val howEn = if (wonEn != null) "method “$wonEn”" else if (OvpnEngine.activeName == TunnelBear.SINGLE_NAME) "internal relay (TCP 7011)" else "direct connect"
                val howRu = if (wonRu != null) "способ «$wonRu»" else if (OvpnEngine.activeName == TunnelBear.SINGLE_NAME) "внутренний ретранслятор (TCP 7011)" else "прямое подключение"
                val durSec = takeConnectDurationSec()
                val durFa = if (durSec != null) "؛ مدت ${"%.1f".format(durSec)} ثانیه" else ""
                val durEn = if (durSec != null) "; took ${"%.1f".format(durSec)} s" else ""
                val durRu = if (durSec != null) "; заняло ${"%.1f".format(durSec)} с" else ""
                AppLog.append(this, "openvpn",
                    "«$dispName» وصل شد؛ $howFa$durFa.",
                    "“$dispName” connected; $howEn$durEn.",
                    "«$dispName» подключён; $howRu$durRu.")
                try { recordDial(OvpnEngine.activeName ?: "", true, null); refreshList() } catch (_: Exception) {}
                appLogOvpnPending = false
            }
            // v1.20: the first free-tunnel connection ends the
            // tutorial for good — mark it HERE, at the exact
            // moment the connect is observed (orb tap, SMART,
            // auto-connect, failover all flow through this state
            // observation), not up to 2s later via the poller.
            try { if (OvpnEngine.activeName == TunnelBear.SINGLE_NAME) noteFirstFreeConnection() } catch (_: Exception) {}
        } else if (!ovpnNow && appLogOvpnWasConnected) {
            if (appLogOvpnLastName.isNotBlank()) {
                AppLog.append(this, "openvpn",
                    "«${logDisplayName(appLogOvpnLastName)}»؛ قطع شد.",
                    "“${logDisplayName(appLogOvpnLastName)}”; disconnected.",
                    "«${logDisplayName(appLogOvpnLastName)}»; отключён.")
            }
        }
        // v1.10: suppressed while the retry ladder runs — a failed
        // rung must not log (or record a failed dial) while the
        // next rung is still to try; the ladder logs exactly one
        // failure line itself when every rung has failed.
        if (appLogOvpnPending && !retryLadderRunning && !ovpnNow && !OvpnEngine.isConnecting && OvpnEngine.lastError != null && OvpnEngine.lastError != appLogOvpnLastError) {
            appLogOvpnLastError = OvpnEngine.lastError
            // v1.12: method + last core stage + duration
            // (item 2) — the old line had only the error.
            val durSec = takeConnectDurationSec()
            val durFa = if (durSec != null) "؛ مدت ${"%.1f".format(durSec)} ثانیه" else ""
            val durEn = if (durSec != null) "; took ${"%.1f".format(durSec)} s" else ""
            val durRu = if (durSec != null) "; заняло ${"%.1f".format(durSec)} с" else ""
            val methFa = connectMethodFa(); val methEn = connectMethodEn(); val methRu = connectMethodRu()
            val st = OvpnEngine.lastState ?: ""
            AppLog.append(this, "openvpn",
                "«${logDisplayName(appLogOvpnPendingName)}» وصل نشد: ${OvpnEngine.lastError}؛ روش $methFa؛ مرحله «$st»$durFa.",
                "“${logDisplayName(appLogOvpnPendingName)}” did not connect: ${OvpnEngine.lastError}; method $methEn; stage “$st”$durEn.",
                "«${logDisplayName(appLogOvpnPendingName)}» не подключился: ${OvpnEngine.lastError}; способ $methRu; этап «$st»$durRu.")
            try { recordDial(appLogOvpnPendingName, false, OvpnEngine.lastError); refreshList() } catch (_: Exception) {}
            val failedName = appLogOvpnPendingName
            appLogOvpnPending = false
            // v1.1 free failover: the active free account
            // failed to connect — try the backup
            // automatically, once per tap. A fresh backup
            // is created silently afterwards.
            // v1.13: no failover during a sweep measurement
            // (a failed measurement is not a failed user connect).
            if (failedName == TunnelBear.SINGLE_NAME && !freeFailoverTried) {
                freeFailoverTried = true
                tryFailoverToBackup()
            }
        }
        if (ovpnNow) appLogOvpnLastName = OvpnEngine.activeName ?: appLogOvpnLastName
        appLogOvpnWasConnected = ovpnNow
        if (OvpnEngine.isConnected && OvpnEngine.activeName != null) {
            needDialogShownFor = null
        } else if (OvpnEngine.isConnecting && OvpnEngine.activeName != null) {
            // Server is waiting for credentials that were not
            // supplied: surface it as a prompt instead of a
            // silent CONNECTING.
            if (OvpnEngine.waitingForInput && needDialogShownFor != OvpnEngine.activeName) {
                needDialogShownFor = OvpnEngine.activeName
                showNeedCredentialsDialog()
            }
        } else {
            needDialogShownFor = null
        }
        renderTopBox()
        renderStatusLabel()
    }

    private fun refreshList() {
        val dark = OvpnTheme.isDark(this)
        // v1.12: self-healing (item 4) — if the in-memory
        // list lost the stored profiles (a failed render
        // left it empty), reload from disk before rendering;
        // the stored list is the truth, and a restart used
        // to be the only way to get it back.
        if (profiles.isEmpty()) {
            try {
                val stored = OvpnStore.load(this)
                if (stored.isNotEmpty()) profiles = stored
            } catch (_: Exception) {}
        }
        // Drop health state of deleted profiles.
        try {
            val names = profiles.map { it.name }.toSet()
            localHealth.keys.retainAll(names)
        } catch (_: Exception) {}
        renderCards(dark)
        renderTopBox()
    }

    private fun renderCards(dark: Boolean) {
        val container = cardsContainer ?: return
        container.removeAllViews()
        // v1.12: one bad row must never blank the whole list
        // (item 4) — a failing row is skipped, the rest
        // render, and the mismatch is logged (once).
        var rendered = 0
        var renderFailed = 0
        // 1.42 (A): the free entry is rendered by the
        // dedicated top box, never as a normal row — the
        // list below the box holds ONLY the user's own
        // imported profiles (main app OpenVpnActivity
        // :630-631: `if (p.tunnelBear) continue`).
        val own = ownProfiles()
        val connected = OvpnEngine.isConnected || OvpnEngine.isConnecting
        // 1.42 (F): the main app's circleBtn EXACTLY
        // (config-renamer OpenVpnActivity.kt:609-628).
        fun circleBtn(glyph: String, sizeSp: Float, bold: Boolean, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = glyph
                textSize = sizeSp * 0.7f
                if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(if (dark) "#EDEFF5" else "#283593"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (dark) Theme.darken(Color.parseColor("#22FFFFFF")) else Color.parseColor("#F3EFFF"))
                    setStroke((1 * resources.displayMetrics.density).toInt(),
                        if (dark) Theme.darken(Color.parseColor("#88FFFFFF")) else Color.parseColor("#C5B8F0"))
                }
                val s = (36 * 0.7f * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    marginStart = (6 * 0.7f * resources.displayMetrics.density).toInt()
                }
                setPadding(0, 0, 0, 0)
                setOnClickListener { onClick() }
            }
        }
        // 1.42 (G): pinned-first, then alphabetical by
        // name (v1.26 sort, sorted at render). The loop
        // runs over the sorted display list; edit /
        // diagnosis / delete still take the index in
        // the stored `profiles` list.
        val sorted = own.sortedWith(
            compareByDescending<OvpnProfile> { TunnelBear.isPinned(this, it.name) }
                .thenBy { it.name.lowercase() })
        for (p in sorted) {
            // 1.42 (A): the free entry is rendered by the dedicated box above, never as a normal row.
            if (p.tunnelBear) continue
            val index = profiles.indexOf(p)
            try {
            val isActive = connected && OvpnEngine.activeName == p.name
            val isSel = selectedProfileObj()?.name == p.name
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = rtlDir()
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 18 * resources.displayMetrics.density
                    // v1.1: the SELECTED card's background is
                // light green (tap a card to select it,
                // then the orb connects it). No toggle.
                if (dark) {
                    setColor(if (isSel) Theme.darken(Color.parseColor("#CC1E3A2F"))
                        else if (isActive) Theme.darken(Color.parseColor("#B32A3D5C")) else Theme.darken(Color.parseColor("#D2121A34")))
                    setStroke(((if (isSel) 1.5f else 1f) * resources.displayMetrics.density).toInt(),
                        if (isSel) OvpnTheme.accent(this@OpenVpnActivity)
                        else if (isActive) Color.parseColor("#26A69A") else Theme.darken(Color.parseColor("#592DD4BF")))
                } else {
                    setColor(if (isSel) Color.parseColor("#DFF3E4")
                        else if (isActive) Color.parseColor("#E8EEF7") else Color.parseColor("#E0FFFFFF"))
                    setStroke(((if (isSel) 1.5f else 1f) * resources.displayMetrics.density).toInt(),
                        if (isSel) OvpnTheme.accent(this@OpenVpnActivity)
                        else if (isActive) Color.parseColor(NAVY) else Color.parseColor("#F2FFFFFF"))
                }
                }
                elevation = 2 * resources.displayMetrics.density
                setPadding(dp(14), dp(14), dp(14), dp(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(20), dp(5), dp(20), dp(5)) }
                setOnClickListener {
                    // v1.12: auto-connect on switch (item 6) —
                    // tapping another card while a tunnel is up
                    // disconnects the current one and connects
                    // the newly selected profile.
                    val wasConnected = OvpnEngine.isConnected
                    val wasActive = OvpnEngine.activeName
                    selectedProfile = p.name
                    try {
                        getSharedPreferences("renamer_ovpn", MODE_PRIVATE).edit().putString("ovpn_last", p.name).apply()
                    } catch (_: Exception) {}
                    if (wasConnected && wasActive != null && wasActive != p.name) {
                        connectProfile(p, fromSwitch = true)
                    }
                    renderCards(OvpnTheme.isDark(this@OpenVpnActivity))
                    renderTopBox()
                    renderStatusLabel()
                }
            }
            // 1.41 (E): the connect toggle — FIRST child
            // (leftmost, like the main app). The SAME
            // themed pill toggle (62×41 bitmap at 0.7,
            // 48×32 layout at 0.7); the ladder state
            // keeps its amber busy bitmap. It triggers
            // the same connect path the orb used.
            val isBusy = OvpnEngine.isConnecting && OvpnEngine.activeName == p.name
            val toggle = android.widget.ImageView(this).apply {
                setImageBitmap(if (isBusy) HydraUi.toggleBitmapBusy(this@OpenVpnActivity)
                    else if (dark) Theme.pillToggleBitmap(this@OpenVpnActivity, (62 * 0.7f).toInt(), (41 * 0.7f).toInt(), isActive,
                        "#8B9098", "#9AA0B0", "#1B746C", "#B2DFDB")
                    else Theme.pillToggleBitmap(this@OpenVpnActivity, (62 * 0.7f).toInt(), (41 * 0.7f).toInt(), isActive,
                        "#FFF3D6", "#FFFFFF", "#C8E6C9", "#81C784"))
                val dd = resources.displayMetrics.density
                layoutParams = LinearLayout.LayoutParams((48 * 0.7f * dd).toInt(), (32 * 0.7f * dd).toInt()).apply {
                    setMargins(0, 0, (8 * 0.7f * dd).toInt(), 0)
                }
                setOnClickListener {
                    if (isBusy) {
                        // 2.41: re-tapping the profile its
                        // own ladder is working on CANCELS
                        // it — it used to restart the
                        // ladder, so rapid re-taps could
                        // never let one finish.
                        cancelCurrentConnect()
                    } else if (isActive) {
                        // v1.10: a user stop aborts any
                        // running retry ladder, so it can
                        // never reconnect behind it.
                        retryGen.incrementAndGet()
                        OvpnEngine.disconnect()
                        refreshStatus(); refreshList()
                    } else {
                        connectProfile(p)
                    }
                }
            }
            row.addView(toggle)
            // 1.42 (G): the v1.26 pin between the toggle
            // and the name — filled #2E9E6B when pinned,
            // faint grey #9E9E9E outline when not.
            // Tapping it toggles the pin ONLY: no
            // selection change, no connect.
            val pinned = TunnelBear.isPinned(this@OpenVpnActivity, p.name)
            row.addView(android.widget.ImageView(this).apply {
                setImageBitmap(OvpnTheme.pinBitmap(this@OpenVpnActivity,
                    if (pinned) "#2E9E6B" else "#9E9E9E", 16, pinned))
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                    marginEnd = dp(6)
                }
                isClickable = true
                setOnClickListener {
                    try {
                        TunnelBear.setPinned(this@OpenVpnActivity, p.name,
                            !TunnelBear.isPinned(this@OpenVpnActivity, p.name))
                    } catch (_: Exception) {}
                    renderCards(OvpnTheme.isDark(this@OpenVpnActivity))
                }
            })
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                // 1.41 (E): the label is "OpenVPN Profile"
                // in English, exactly as in the main app's
                // Profiles page (screenshot 3).
                text = "OpenVPN Profile"
                textSize = 12f
                setTextColor(OvpnTheme.subFg(this@OpenVpnActivity))
                textDirection = View.TEXT_DIRECTION_LTR
                gravity = Gravity.START
            })
            info.addView(TextView(this).apply {
                text = p.name
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
                textDirection = appTextDir()
                gravity = Gravity.START
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                // v1.12: no tap handler here (item 7) — the
                // whole card selects; only ✎ opens edit.
            })
            // v1.12: the info column no longer opens edit
            // (item 7) — only the ✎ icon does.
            row.addView(info)
            row.addView(circleBtn("i", 16f, true) { showDiagnosis(index) })
            row.addView(circleBtn("✎", 18f, false) { editProfile(index) })
            // 1.42 (F): delete = the main app's drawn trash
            // glyph in the pink oval (not the red ✕ text).
            row.addView(android.widget.ImageView(this).apply {
                setImageBitmap(Theme.trashGlyphBitmap(this@OpenVpnActivity,
                    if (dark) "#F48FB1" else "#D32F2F", (20 * 0.7f).toInt()))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (dark) Theme.darken(Color.parseColor("#33FCE4EC")) else Color.parseColor("#FCE4EC"))
                    setStroke((1 * resources.displayMetrics.density).toInt(),
                        Color.parseColor(if (dark) "#F48FB1" else "#F8BBD0"))
                }
                val s = (36 * 0.7f * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    marginStart = (6 * 0.7f * resources.displayMetrics.density).toInt()
                }
                val pad = (8 * 0.7f * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                setOnClickListener { confirmDelete(index) }
            })
            container.addView(row)
                rendered++
            } catch (_: Exception) { renderFailed++ }
        }
        // v1.12: post-render count check (item 4) — if the
        // rows on screen do not match the stored profiles,
        // say so in the log (once per distinct mismatch) so
        // the empty-list bug can be followed up from the log.
        try {
            val sig = "${own.size}|$rendered|$renderFailed"
            if ((rendered != own.size || renderFailed > 0) && sig != lastListMismatchSig) {
                lastListMismatchSig = sig
                AppLog.append(this, "openvpn",
                    "لیست پروفایل‌ها ناقص رندر شد: ${own.size} پروفایل، $rendered ردیف، $renderFailed خطا.",
                    "Profile list rendered incompletely: ${own.size} profiles, $rendered rows, $renderFailed errors.",
                    "Список профилей отрисован не полностью: профилей ${own.size}, строк $rendered, ошибок $renderFailed.")
            }
        } catch (_: Exception) {}
    }

    // ---------- v1.13: Smart Connect ----------

    /** 1.42 (G): v1.26 Smart pick restored — the first
     *  pinned entry (alphabetical) that is a known
     *  country, else the default country (Germany).
     *  Pins are keyed by profile name (see renderCards). */
    private fun pickSmartCountry(): String {
        return TunnelBear.pinnedCountries(this)
            .sortedBy { it.lowercase() }
            .firstOrNull { TunnelBear.isKnownCountry(this, it) }
            ?: TunnelBear.DEFAULT_COUNTRY
    }


    /** v1.26: Smart Connect — no testing anymore. The
     *  pick is the first pinned country, else the
     *  default country; connecting is the normal orb
     *  connect (full ladder for the free entry). */
    private fun smartConnect() {
        connectSmartCountry(pickSmartCountry())
    }

    /** Connect to the country Smart Connect picked — it
     *  becomes the active location (chip, log and the dial
     *  outcome are recorded for THAT country). */
    private fun connectSmartCountry(country: String) {
        try { TunnelBear.saveSmartChoice(this, country) } catch (_: Exception) {}
        try {
            TunnelBear.ensureSingleEntry(this)
            profiles = OvpnStore.load(this)
        } catch (_: Exception) {}
        val p = tbProfile() ?: return
        connectProfile(p)
    }




    // ---------- connect ----------
    private fun connectProfile(p: OvpnProfile, fromSwitch: Boolean = false, maxRungs: Int = Int.MAX_VALUE, forceLadder: Boolean = false): Int {
        // v1.10: a fresh tap supersedes any running retry
        // ladder for another profile.
        retryGen.incrementAndGet()
        // v1.1: a fresh user tap is a fresh failover
        // allowance (one backup try per tap).
        if (TunnelBear.isTunnelBear(p)) freeFailoverTried = false
        // 1.71 (item 3): remember which country THIS free
        // attempt is for — noteFirstFreeConnection records
        // it only when the tunnel actually reaches
        // CONNECTED, so a failed/cancelled attempt never
        // becomes the "last-connected" location.
        if (TunnelBear.isTunnelBear(p)) connectingFreeCountry = TunnelBear.selectedCountry(this)
        // The free entry needs the user's own account. With one
        // saved, its credentials fill the profile (in memory
        // only — the stored profile keeps blank credentials);
        // without one, the account is asked for first instead
        // of connecting.
        if (TunnelBear.isTunnelBear(p)) {
            val acc = TunnelBear.getAccount(this)
            if (acc == null) {
                // No account yet — start the automatic creation
                // (the VPN gate inside startTbAutoCreation shows
                // the 3-second message when no VPN is on, else
                // the waiting screen).
                startTbAutoCreation()
                return -1
            }
            if (p.username.isBlank()) p.username = acc.first
            if (p.password.isBlank()) p.password = acc.second
        }
        try { getSharedPreferences("renamer_ovpn", MODE_PRIVATE).edit().putString("ovpn_last", p.name).apply() } catch (_: Exception) {}
        selectedProfile = p.name
        // Require BOTH username and password when the profile
        // needs auth: a blank username alone used to let
        // openvpn start, then wait forever for input (NEED
        // state) while the UI just showed CONNECTING.
        if (p.needsAuth() && (p.username.isBlank() || p.password.isBlank()) && !p.hasEmbeddedCredentials()) {
            // v1.12: a card-switch (item 7) never pops the
            // username/password page — just select the profile
            // and tell the user to fill the credentials via
            // the ✎ edit icon, keeping the current tunnel up.
            if (fromSwitch) {
                android.widget.Toast.makeText(this,
                    T("برای اتصال به «${p.name}»، اول نام کاربری و رمز را از دکمه ویرایش ✎ وارد کن",
                        "To connect to “${p.name}”, first fill in the username and password via the ✎ edit button",
                        "Чтобы подключиться к «${p.name}», сначала введите имя пользователя и пароль через кнопку редактирования ✎"),
                    android.widget.Toast.LENGTH_LONG).show()
                return -1
            }
            val userInput = EditText(this).apply {
                hint = L("نام کاربری", "Username")
                setText(p.username)
            }
            val passInput = EditText(this).apply {
                hint = L("رمز عبور", "Password")
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val saveBox = CheckBox(this).apply {
                text = "Save password"; isChecked = true
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 16, 32, 16)
                addView(userInput); addView(passInput); addView(saveBox)
            }
            android.app.AlertDialog.Builder(this)
                .setTitle(p.name)
                .setView(col)
                .setPositiveButton("CONNECT") { _, _ ->
                    p.username = userInput.text.toString().trim()
                    p.password = passInput.text.toString()
                    p.savePassword = saveBox.isChecked
                    if (p.savePassword) OvpnStore.save(this, profiles)
                    connectDirect(p)
                }
                .setNegativeButton(L("لغو", "Cancel")) { _, _ -> refreshList() }
                .show().also { OvpnTheme.styleDialog(it, this) }
            return -1
        }
        return connectDirect(p, maxRungs, forceLadder)
    }

    /** v1.12: the connect duration for the log (item 2) —
     *  seconds with one decimal, from the attempt start
     *  (set in connectDirect). Consumes the start time:
     *  the first caller gets the duration, later callers
     *  get null. */
    private fun takeConnectDurationSec(): Double? {
        if (connectAttemptStartMs <= 0) return null
        val s = (System.currentTimeMillis() - connectAttemptStartMs) / 1000.0
        connectAttemptStartMs = 0
        return s
    }

    /** v1.12: the method string for the failure log (item
     *  2) — the rung running when it failed, else the
     *  free relay, else a plain direct connect. */
    private fun connectMethodFa(): String = when {
        currentRungFa != null -> "«$currentRungFa»"
        appLogOvpnPendingName == TunnelBear.SINGLE_NAME -> "ریلی داخلی (TCP 7011)"
        else -> "اتصال مستقیم"
    }
    private fun connectMethodEn(): String = when {
        currentRungEn != null -> "“$currentRungEn”"
        appLogOvpnPendingName == TunnelBear.SINGLE_NAME -> "internal relay (TCP 7011)"
        else -> "direct connect"
    }
    private fun connectMethodRu(): String = when {
        currentRungRu != null -> "«$currentRungRu»"
        appLogOvpnPendingName == TunnelBear.SINGLE_NAME -> "внутренний ретранслятор (TCP 7011)"
        else -> "прямое подключение"
    }

    /** Direct connect for the free entry; the retry ladder for
     *  imported profiles (v1.10 — see OvpnRetry): the original
     *  config first, then direct-IP, DoH-IP, proto-swap and
     *  mssfix rungs until one holds. The saved profile is never
     *  rewritten; rungs run on in-memory copies. */
    private fun connectDirect(p: OvpnProfile, maxRungs: Int = Int.MAX_VALUE, forceLadder: Boolean = false): Int {
        // v1.12: the method in the attempt line (item 2) —
        // the free entry uses the internal relay, imported
        // profiles use the retry ladder.
        val methodFa = if (TunnelBear.isTunnelBear(p)) "ریلی داخلی" else "نردبان روش‌ها"
        val methodEn = if (TunnelBear.isTunnelBear(p)) "internal relay" else "method ladder"
        val methodRu = if (TunnelBear.isTunnelBear(p)) "внутренний ретранслятор" else "лестница способов"
        // Every connect through here is a user connect —
        // log the attempt and arm the pending-connect log.
        AppLog.append(this, "openvpn",
            "تلاش اتصال «${logDisplayName(p.name)}»؛ $methodFa.",
            "Connect attempt “${logDisplayName(p.name)}”; $methodEn.",
            "Попытка подключения «${logDisplayName(p.name)}»; $methodRu.")
        appLogOvpnPending = true
        appLogOvpnPendingName = p.name
        // v1.12: the attempt starts NOW (item 2 — duration
        // for the log; item 3 — the orb shows the connecting
        // state immediately, before the ladder thread even
        // starts, instead of looking idle while the rung
        // list is built and the endpoint is probed).
        connectAttemptStartMs = System.currentTimeMillis()
        OvpnEngine.isConnecting = true
        OvpnEngine.activeName = p.name
        OvpnEngine.lastError = null
        refreshStatus(); refreshList()
        if (TunnelBear.isTunnelBear(p) && !forceLadder) {
            OvpnEngine.connect(this, p) { runOnUiThread { refreshStatus(); refreshList() } }
            return -1
        }
        val myGen = retryGen.get()
        retryLadderRunning = true
        ladderStartedAnyRung = false
        Thread {
            try {
                runRetryLadder(p, myGen)
            } catch (_: Exception) {
            } finally {
                retryLadderRunning = false
                // v1.12: if the ladder exited without starting
                // any rung (or was superseded before the first
                // rung), clear the connecting state the instant
                // set above — otherwise the orb would sit on
                // «در حال اتصال» with nothing running. The
                // generation check keeps a newer attempt's
                // state untouched.
                if (!ladderStartedAnyRung && retryGen.get() == myGen) {
                    OvpnEngine.isConnecting = false
                    OvpnEngine.activeName = null
                }
                runOnUiThread { try { refreshStatus(); refreshList() } catch (_: Exception) {} }
            }
        }.apply { isDaemon = true }.start()
        return myGen
    }

    /** The ladder itself (background thread). Aborts when a newer
     *  connect supersedes it (retryGen) or the user stops it. */
    private fun runRetryLadder(p: OvpnProfile, myGen: Int, maxRungs: Int = Int.MAX_VALUE) {
        fun aborted(): Boolean = retryGen.get() != myGen
        val rungs = try { OvpnRetry.buildRungs(p.ovpn) } catch (_: Exception) {
            listOf(OvpnRetry.Rung("original", "اصلی", "Original", "Оригинал", p.ovpn))
        }
        for (rung in rungs.take(maxRungs)) {
            if (aborted()) return
            // v1.12: the rung now running, for the log (item 2).
            currentRungFa = rung.nameFa; currentRungEn = rung.nameEn; currentRungRu = rung.nameRu
            // Fast probe: a dead TCP endpoint is skipped at once
            // (its TLS handshake cannot succeed); a silent UDP
            // endpoint still runs, with a shorter budget.
            val ep = try { OvpnRetry.firstEndpoint(rung.text) } catch (_: Exception) { null }
            var timeoutMs = 40000L
            if (ep != null) {
                val res = try { OvpnRetry.probeEndpoint(ep) } catch (_: Exception) { 2 }
                when (res) {
                    -1, 1 -> { currentRungFa = null; currentRungEn = null; currentRungRu = null; continue }
                    2 -> timeoutMs = 25000L
                }
            }
            // v1.12: settle the previous core BEFORE starting
            // a new one (item 3 — the stale-core race).
            // disconnect() only SIGNALS the old core to stop;
            // its thread stays alive in native c.connect()
            // long after isConnecting=false, and its late
            // events used to reset the NEW rung's state
            // mid-rung. Wait for the thread to actually exit
            // (bounded 10s); if it will not, abort the ladder
            // instead of starting a second core over it.
            try { OvpnEngine.disconnect() } catch (_: Exception) {}
            val settled = try { Ovpn3VpnService.awaitClientExit(10_000) } catch (_: Exception) { true }
            if (!settled) {
                AppLog.append(this, "openvpn",
                    "«${logDisplayName(p.name)}» وصل نشد: هسته‌ی قبلی خاموش نشد و روش «${rung.nameFa}» شروع نشد.",
                    "“${logDisplayName(p.name)}” did not connect: the previous core did not stop, so method “${rung.nameEn}” never started.",
                    "«${logDisplayName(p.name)}» не подключился: предыдущее ядро не остановилось, поэтому способ «${rung.nameRu}» не запустился.")
                appLogOvpnPending = false
                OvpnEngine.isConnecting = false
                OvpnEngine.isConnected = false
                return
            }
            ladderStartedAnyRung = true
            val rungProfile = p.copy(ovpn = rung.text)
            val started = java.util.concurrent.CountDownLatch(1)
            runOnUiThread {
                try { OvpnEngine.connect(this@OpenVpnActivity, rungProfile) {} } catch (_: Exception) {} finally { started.countDown() }
            }
            try { started.await(5, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            if (aborted()) return
            runOnUiThread { try { refreshStatus(); refreshList() } catch (_: Exception) {} }
            when (OvpnRetry.waitRung(p.name, timeoutMs) { aborted() }) {
                0 -> {
                    // Connected — but it must HOLD 3s.
                    retryWonFa = rung.nameFa; retryWonEn = rung.nameEn; retryWonRu = rung.nameRu
                    val holdEnd = System.currentTimeMillis() + 3000
                    var stable = true
                    while (System.currentTimeMillis() < holdEnd) {
                        if (aborted()) return
                        if (!OvpnEngine.isConnected || OvpnEngine.activeName != p.name) { stable = false; break }
                        try { Thread.sleep(200) } catch (_: Exception) {}
                    }
                    if (!stable) {
                        retryWonFa = null; retryWonEn = null; retryWonRu = null
                        try { OvpnEngine.disconnect() } catch (_: Exception) {}
                        try { Thread.sleep(800) } catch (_: Exception) {}
                        continue
                    }
                    // Held: the poller's success branch logs it
                    // (with the rung name, from retryWon*).
                    return
                }
                1, 2 -> {
                    // v1.12: one line per failed rung (item 2) —
                    // the method, the stage and the reason, so
                    // the log alone shows which rungs were tried
                    // and why each died.
                    AppLog.append(this, "openvpn",
                        "روش «${rung.nameFa}» برای «${logDisplayName(p.name)}» جواب نداد: ${OvpnEngine.lastError ?: OvpnEngine.lastState ?: "—"}.",
                        "Method “${rung.nameEn}” for “${logDisplayName(p.name)}” did not work: ${OvpnEngine.lastError ?: OvpnEngine.lastState ?: "—"}.",
                        "Способ «${rung.nameRu}» для «${logDisplayName(p.name)}» не сработал: ${OvpnEngine.lastError ?: OvpnEngine.lastState ?: "—"}.")
                    try { OvpnEngine.disconnect() } catch (_: Exception) {}
                    try { Thread.sleep(800) } catch (_: Exception) {}
                    continue
                }
                else -> return // aborted
            }
        }
        // No rung connected: ONE failure line (the poller's
        // failure branch is suppressed while the ladder runs,
        // so this is the only one).
        if (aborted()) return
        retryWonFa = null; retryWonEn = null; retryWonRu = null
        val err = OvpnEngine.lastError
        // v1.12: the last rung + the last core stage + the
        // duration (item 2).
        val methFa = connectMethodFa(); val methEn = connectMethodEn(); val methRu = connectMethodRu()
        currentRungFa = null; currentRungEn = null; currentRungRu = null
        val durSec = takeConnectDurationSec()
        val durFa = if (durSec != null) "؛ مدت ${"%.1f".format(durSec)} ثانیه" else ""
        val durEn = if (durSec != null) "; took ${"%.1f".format(durSec)} s" else ""
        val durRu = if (durSec != null) "; заняло ${"%.1f".format(durSec)} с" else ""
        val st = OvpnEngine.lastState ?: ""
        AppLog.append(this, "openvpn",
            "«${logDisplayName(p.name)}» وصل نشد: ${err ?: "سرور با هیچ روشی جواب نداد"}؛ روش $methFa؛ مرحله «$st»$durFa.",
            "“${logDisplayName(p.name)}” did not connect: ${err ?: "the server answered with no method"}; method $methEn; stage “$st”$durEn.",
            "«${logDisplayName(p.name)}» не подключился: ${err ?: "сервер не ответил ни одним способом"}; способ $methRu; этап «$st»$durRu.")
        try { recordDial(p.name, false, err); refreshList() } catch (_: Exception) {}
        appLogOvpnPending = false
    }

    private fun editProfile(index: Int) {
        OvpnStore.pendingIndex = index
        OvpnStore.pending = profiles[index].copy()
        startActivity(Intent(this, OpenVpnImportActivity::class.java))
    }

    private fun confirmDelete(index: Int) {
        val p = profiles[index]
        // 1.42: the free entry never renders as a card
        // anymore (it lives in the top box), so this
        // branch is unreachable from the list — but a
        // stale caller still lands on the same box
        // delete below.
        if (p.tunnelBear) {
            confirmDeleteTb(p)
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("حذف پروفایل", "Delete profile"))
            .setMessage(L("پروفایل «", "Profile \"") + p.name + L("» حذف شود؟", "\" will be deleted."))
            .setPositiveButton(L("حذف", "Delete")) { _, _ ->
                val wasActive = OvpnEngine.activeName == p.name
                profiles.removeAt(index)
                OvpnStore.save(this, profiles)
                if (wasActive) OvpnEngine.disconnect()
                refreshList()
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show().also { OvpnTheme.styleDialog(it, this) }
    }

    /** 1.42 (D): delete the free-account box (state B),
     *  main app confirmDeleteTb (config-renamer
     *  OpenVpnActivity.kt:1358) — clears the stored
     *  account AND the entry, and the box returns to
     *  state A. The entry is NOT re-added silently
     *  (markRemoved) — the next auto-account success
     *  restores it. Standalone adaptation: the stored
     *  account here is the DUAL FreeAccounts list
     *  (v1.1), which TunnelBear.clearAccount does NOT
     *  touch (legacy keys only) — without
     *  FreeAccounts.clearAll, hasAccount() would stay
     *  true and renderTopBox's no-entry restore would
     *  resurrect the entry on the next render. */
    private fun confirmDeleteTb(p: OvpnProfile) {
        android.app.AlertDialog.Builder(this)
            .setTitle(L("حذف پروفایل", "Delete profile"))
            .setMessage(L("«Free OpenVpn config» حذف شود؟ حساب ذخیره‌شده هم پاک می‌شود.", "Delete “Free OpenVpn config”? The saved account is deleted too."))
            .setPositiveButton(L("حذف", "Delete")) { _, _ ->
                val wasActive = OvpnEngine.activeName == p.name
                try { TunnelBear.clearAccount(this) } catch (_: Exception) {}
                try { FreeAccounts.clearAll(this) } catch (_: Exception) {}
                try { TunnelBear.markRemoved(this) } catch (_: Exception) {}
                profiles.removeAll { it.tunnelBear }
                OvpnStore.save(this, profiles)
                if (wasActive) OvpnEngine.disconnect()
                tbCountryMenuOpen = false
                refreshList()
                refreshStatus()
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show().also { OvpnTheme.styleDialog(it, this) }
    }

    // ---------- free-account auto-create (exactly as the main app) ----------
    private fun showTbWaitingDialog() {
        dismissTbWaitingDialog()
        tbWaitStageIdx = -1
        tbWaitStartMs = System.currentTimeMillis()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        tbWaitDotsView = TextView(this).apply {
            text = L("لطفا کمی صبر کنید", "Please wait a moment")
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.CENTER
        }
        col.addView(tbWaitDotsView)
        col.addView(TextView(this).apply {
            text = L("لطفا صفحه را نبندید", "Please do not close this page")
            textSize = 14f
            setTextColor(OvpnTheme.subFg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(14))
        })
        // v1.10: NO stage-description line on this screen anymore
        // (user order) — the bar + % + time line stay; the stages
        // still go to the app log.
        // v1.8: the bar is a little thicker (18dp — not too
        // thick) and carries the percentage INSIDE it,
        // centred, small, white on a solid dark pill
        // (readable at every fill level, day AND night).
        // The percentage is the real stage progress
        // (TbAutoAccount/DualFree), monotonic — it never
        // goes backwards.
        val barWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(18))
        }
        tbWaitProgressView = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
        }
        barWrap.addView(tbWaitProgressView)
        tbWaitPctView = TextView(this).apply {
            text = "0٪"
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            // v1.8: solid dark pill behind the white text —
            // readable at EVERY fill level, day AND night.
            // (v1.7's bare white text straddled the
            // green-fill / grey-track boundary and was
            // unreadable where it crossed it.)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#CC000000"))
            }
            setPadding(dp(10), dp(1), dp(10), dp(1))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER)
        }
        barWrap.addView(tbWaitPctView)
        col.addView(barWrap)
        tbWaitCountdownView = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(OvpnTheme.subFg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(tbWaitCountdownView)
        // v1.9: a clear way out — the user is never
        // trapped on this screen. Cancelling stops
        // both jobs; only a verified account is ever
        // saved, so nothing half-created is left.
        tbWaitCancelView = TextView(this).apply {
            text = L("لغو", "Cancel")
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.accent(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(6))
            isClickable = true
            setOnClickListener { cancelTbAutoCreation() }
        }
        col.addView(tbWaitCancelView)
        val dlg = android.app.AlertDialog.Builder(this)
            .setView(col)
            .setCancelable(false)
            .create()
        tbWaitDialog = dlg
        dlg.show()
        try { OvpnTheme.styleDialog(dlg, this) } catch (_: Exception) {}
        tbWaitDots = 0
        handler.post(tbWaitDotsLoop)
    }

    private fun dismissTbWaitingDialog() {
        try { handler.removeCallbacks(tbWaitDotsLoop) } catch (_: Exception) {}
        try { handler.removeCallbacks(tbWaitElapsedLoop) } catch (_: Exception) {}
        try { tbWaitDialog?.dismiss() } catch (_: Exception) {}
        tbWaitDialog = null
        // v1.8: the step-2 box says "wait while the account
        // is being created" — once this dialog is gone,
        // creation is over (success or failure), so the
        // box must go with it and never linger.
        removeGuideStep2Window()
        tbWaitDotsView = null
        tbWaitProgressView = null
        tbWaitPctView = null
        tbWaitCountdownView = null
        tbWaitCancelView = null
        tbWaitStageIdx = -1
    }

    /** v1.10 single-first creation — ONLY behind the VPN gate:
     *  with no VPN active the option does nothing but a ~3-second
     *  message; there is no auto-Emergency here (this app has no
     *  Emergency at all). With a VPN on, ONE tap creates ONE
     *  account (the waiting screen covers just this job — global
     *  deadline 110 s). When it is ready and the user's VPN is
     *  still up, the second (backup) account is created silently
     *  in the background (no screen). Only if the creation fails
     *  does the failure dialog appear.
     *  The creation is owned by the application (DualFree state +
     *  TbCreationService), so minimizing cannot stop it — if this
     *  Activity is destroyed mid-creation, the next instance
     *  re-attaches on resume (maybeReattachCreation). */
    private fun startTbAutoCreation() {
        if (tbCreating || DualFree.isSingleActive()) return
        if (!anyVpnUp()) {
            android.widget.Toast.makeText(this,
                L("برای ساخت اکانت رایگان فیلترشکن خود را روشن کنید", "Turn on your VPN to create a free account"),
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        tbCreating = true
        showTbWaitingDialog()
        renderTopBox()
        renderStatusLabel()
        try {
            DualFree.createSingle(this)
            DualFree.setSingleListener(singleCreationCallback())
        } catch (_: Exception) {
            tbCreating = false
            dismissTbWaitingDialog()
            refreshList()
            refreshStatus()
        }
    }

    /** The foreground creation's live listener — used both by the
     *  Activity that started it and by a recreated Activity that
     *  re-attaches on resume. Every method guards the destroyed
     *  case: a dead Activity must never handle the outcome (the
     *  result stays in DualFree state for the next instance, and
     *  the claim below guarantees exactly-once handling). */
    private fun singleCreationCallback(): DualFree.SingleCallback = object : DualFree.SingleCallback {
        override fun onProgress(pct: Int, secondsLeft: Int) {
            if (isFinishing || isDestroyed) return
            try { tbWaitProgressView?.progress = pct } catch (_: Exception) {}
            try { tbWaitPctView?.text = T("$pct٪", "$pct%", "$pct%") } catch (_: Exception) {}
            // v1.9: during the mail wait (stage 4) the countdown is
            // replaced by the elapsed time — a fake "seconds left"
            // that never moves is worse than no estimate.
            try {
                if (tbWaitStageIdx == 4) {
                    val el = ((System.currentTimeMillis() - tbWaitStartMs) / 1000).toInt()
                    tbWaitCountdownView?.text = T("$el ثانیه گذشته", "$el s elapsed", "прошло $el с")
                } else {
                    tbWaitCountdownView?.text = T("حدود $secondsLeft ثانیه مانده", "About $secondsLeft s left", "Осталось около $secondsLeft с")
                }
            } catch (_: Exception) {}
        }
        override fun onStage(fa: String, en: String, ru: String, index: Int) {
            if (isFinishing || isDestroyed) return
            tbWaitStageIdx = index
            // v1.10: the stage text is NOT shown on the waiting
            // screen anymore (user order) — the index still drives
            // the elapsed/countdown line, and the stages still go
            // to the app log (TbAutoAccount.logStage).
            if (index == 4) startElapsedLoop()
        }
        override fun onReady() {
            if (isFinishing || isDestroyed) return
            if (!DualFree.tryClaimSingleOutcome()) return
            handleCreationReady()
        }
        override fun onFailed(fa: String, en: String, ru: String) {
            if (isFinishing || isDestroyed) return
            if (!DualFree.tryClaimSingleOutcome()) return
            handleCreationFailed(fa, en, ru)
        }
    }

    /** The first account is ready: dismiss, refresh, tell the user,
     *  then connect to it at once (as before). v1.10: also start the
     *  backup silently now — but only while the user's VPN is still
     *  up (the signup sites need it). No screen, no popup. */
    private fun handleCreationReady() {
        tbCreating = false
        dismissTbWaitingDialog()
        if (isFinishing || isDestroyed) return
        try { TunnelBear.ensureSingleEntry(this, restoreDeleted = true) } catch (_: Exception) {}
        try { profiles = OvpnStore.load(this) } catch (_: Exception) {}
        refreshList()
        refreshStatus()
        android.widget.Toast.makeText(this,
            L("حساب ساخته و تایید شد؛ حالا می‌توانی وصل شوی", "Account created and verified; you can connect now"),
            android.widget.Toast.LENGTH_LONG).show()
        if (anyVpnUp()) {
            try { DualFree.createBackupSilently(this) } catch (_: Exception) {}
        }
        // v1.12: when the guide is active, do NOT auto-connect
        // (item 1) — the guide walks the user through picking
        // a country first (step 3) and then tapping connect
        // (step 4); auto-connecting here would end the guide
        // before the user ever saw the country flow. When the
        // guide is not active, connect as before.
        if (guideStep <= 0) {
            val p = tbProfile()
            if (p != null) connectProfile(p)
        }
    }

    private fun handleCreationFailed(fa: String, en: String, ru: String) {
        tbCreating = false
        dismissTbWaitingDialog()
        if (isFinishing || isDestroyed) return
        refreshList()
        refreshStatus()
        showTbAutoFailDialog(fa, en, ru)
    }

    /** v1.10: re-attach to a creation that outlived this Activity
     *  instance (minimize + system reclaim, rotation). The job, its
     *  progress and its result live in DualFree — this instance just
     *  re-shows the waiting dialog with the live values and
     *  re-subscribes. If the job already finished while we were
     *  away, its outcome is handled exactly once here. */
    private fun maybeReattachCreation() {
        if (tbCreating) return
        if (DualFree.isSingleActive()) {
            tbCreating = true
            showTbWaitingDialog()
            tbWaitStartMs = DualFree.singleStartedAtMs().takeIf { it > 0 } ?: System.currentTimeMillis()
            tbWaitStageIdx = DualFree.singleProgress().third
            restoreWaitingDialogFromState()
            DualFree.setSingleListener(singleCreationCallback())
            return
        }
        val out = try { DualFree.consumeSingleOutcome() } catch (_: Exception) { null } ?: return
        if (out.ok) handleCreationReady() else handleCreationFailed(out.fa, out.en, out.ru)
    }

    private fun restoreWaitingDialogFromState() {
        try {
            val (pct, left, idx) = DualFree.singleProgress()
            tbWaitProgressView?.progress = pct
            tbWaitPctView?.text = T("$pct٪", "$pct%", "$pct%")
            if (idx == 4) {
                val el = ((System.currentTimeMillis() - tbWaitStartMs) / 1000).toInt()
                tbWaitCountdownView?.text = T("$el ثانیه گذشته", "$el s elapsed", "прошло $el с")
                startElapsedLoop()
            } else {
                tbWaitCountdownView?.text = T("حدود $left ثانیه مانده", "About $left s left", "Осталось около $left с")
            }
        } catch (_: Exception) {}
    }

    /** v1.10: if the first account exists but the backup was never
     *  created (VPN was off when it finished, or the background
     *  attempt failed), build it silently now — no screen, no popup
     *  — but only while a VPN is up (the signup sites need it);
     *  otherwise skip quietly and try again on a later open. */
    private fun maybeCreateBackupOnOpen() {
        if (tbCreating) return
        if (FreeAccounts.active(this) == null) return
        if (FreeAccounts.backup(this) != null) return
        if (!anyVpnUp()) return
        try { DualFree.createBackupSilently(this) } catch (_: Exception) {}
    }

    /** v1.13: the pending second-account job retries on its
     *  own when the VPN exit IP changes to an unseen one
     *  (the user does not have to keep tapping retry).
     *  Throttled to once per 5 min; never fires on a refused
     *  exit IP, and never while a creation runs. */
    private fun maybeExitChangeBackupRetry() {
        if (tbCreating) return
        if (FreeAccounts.active(this) == null) return
        if (FreeAccounts.backup(this) != null) return
        if (!anyVpnUp()) return
        if (TbAutoAccount.lastDeviceLimitAt(this) <= 0) return
        val now = System.currentTimeMillis()
        if (now - exitIpCheckAtMs < 5 * 60 * 1000L) return
        exitIpCheckAtMs = now
        Thread {
            try {
                val ip = TbAutoAccount.fetchExitIp() ?: return@Thread
                val lastKnown = TbAutoAccount.lastKnownExitIp(this@OpenVpnActivity)
                if (ip == lastKnown) return@Thread
                if (TbAutoAccount.isRefusedExitIp(this@OpenVpnActivity, ip)) return@Thread
                DualFree.createBackupSilently(this@OpenVpnActivity)
            } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }

    /** v1.10: stop the creation from the waiting
     *  screen — cancels the single job cleanly
     *  (DualFree) and drops the dialog, so the user
     *  is never trapped. Only a verified account is
     *  ever saved (the save happens inside the job
     *  after the mail is verified), so a cancel can
     *  never leave a half-created account behind. */
    private fun cancelTbAutoCreation() {
        try { DualFree.cancelSingle() } catch (_: Exception) {}
        tbCreating = false
        dismissTbWaitingDialog()
        refreshList()
        refreshStatus()
    }

    /** v1.1: connect-failure failover — the active free
     *  account failed; promote the backup to active
     *  (it follows the selected country), create a
     *  fresh backup silently, and connect again. */
    private fun tryFailoverToBackup() {
        if (!FreeAccounts.hasBackup(this)) return
        try { FreeAccounts.promoteBackup(this) } catch (_: Exception) { return }
        try { TunnelBear.ensureSingleEntry(this, restoreDeleted = true) } catch (_: Exception) {}
        try { profiles = OvpnStore.load(this) } catch (_: Exception) {}
        try {
            AppLog.append(this, "freeovpn",
                "اتصال با حساب فعال نشد؛ به حساب پشتیبان رفتیم.",
                "The active account did not connect; switched to the backup account.",
                "Активный аккаунт не подключился; переключено на резервный аккаунт.")
        } catch (_: Exception) {}
        DualFree.createBackupSilently(this)
        val p = tbProfile() ?: return
        refreshList()
        refreshStatus()
        connectProfile(p)
    }

    /** v1.1: usage failover — called from the 2s poller
     *  while the free tunnel is up. At ~1900 MB in the
     *  active account's 31-day window, switch to the
     *  backup (new tunnel up right after the old one
     *  drops — at most a momentary blip) and create a
     *  fresh backup silently. With no backup yet, start
     *  one silently so the switch can happen later. */
    private fun maybeUsageFailover() {
        if (!freeConnected()) return
        if (!FreeAccounts.activeNearLimit(this)) return
        if (!FreeAccounts.hasBackup(this)) {
            if (!backupSpawnedForUsage) {
                backupSpawnedForUsage = true
                DualFree.createBackupSilently(this)
            }
            return
        }
        try { FreeAccounts.promoteBackup(this) } catch (_: Exception) { return }
        try { TunnelBear.ensureSingleEntry(this, restoreDeleted = true) } catch (_: Exception) {}
        try { profiles = OvpnStore.load(this) } catch (_: Exception) {}
        try {
            AppLog.append(this, "freeovpn",
                "مصرف حساب فعال نزدیک به سقف رسید؛ به حساب پشتیبان رفتیم.",
                "The active account is almost used up; switching to the backup account.",
                "Лимит активного аккаунта почти исчерпан; переключение на резервный аккаунт.")
        } catch (_: Exception) {}
        DualFree.createBackupSilently(this)
        val p = tbProfile() ?: return
        try { OvpnEngine.disconnect() } catch (_: Exception) {}
        refreshList()
        refreshStatus()
        connectProfile(p)
    }

    /** Auto-creation failed AFTER the flow already retried by
     *  itself — a simple failure dialog with a retry button
     *  ONLY. No manual entry anywhere in this flow. */
    private fun showTbAutoFailDialog(fa: String, en: String, ru: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle(L("ساخت حساب ناموفق بود", "Account creation failed"))
            .setMessage(T(fa, en, ru))
            .setPositiveButton(L("تلاش دوباره", "Try again")) { _, _ -> startTbAutoCreation() }
            .setNegativeButton(L("لغو", "Cancel")) { _, _ -> refreshList() }
            .show().also { dlg ->
                OvpnTheme.styleDialog(dlg, this)
                try {
                    val win = dlg.window
                    if (win != null) {
                        val lp = win.attributes
                        lp.width = dp(340)
                        lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
                        win.attributes = lp
                    }
                } catch (_: Exception) {}
            }
    }

    // ---------- log / credentials / diagnosis / name / guide ----------
    private fun showLogDialog() {
        val engineLog = OvpnEngine.logText()
        val appLog = try { AppLog.text(this) } catch (_: Exception) { "" }
        val kept = appLog.lines().filter { it.contains("[freeovpn]") || it.contains("[openvpn]") }
            .map { displayLogLine(it) }
        val text = buildString {
            if (engineLog.isNotBlank()) append(engineLog)
            if (kept.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append(kept.joinToString("\n"))
            }
        }.ifBlank {
            L("هنوز گزارشی ثبت نشده است. اول یک بار وصل شو.", "No log yet. Connect once first.")
        }
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.BLACK)
            setPadding(32, 16, 32, 16)
        }
        scroll.addView(tv)
        android.app.AlertDialog.Builder(this)
            .setTitle(L("گزارش اتصال OpenVPN", "OpenVPN connection log"))
            .setView(scroll)
            .setPositiveButton(L("کپی گزارش", "Copy log")) { _, _ ->
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("openvpn log", text))
                    android.widget.Toast.makeText(this, L("گزارش کپی شد", "Log copied"), android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
            .setNeutralButton(L("پاک کردن", "Clear")) { _, _ ->
                try { OvpnEngine.clearLog() } catch (_: Exception) {}
                try { AppLog.clear(this) } catch (_: Exception) {}
            }
            .setNegativeButton(L("بستن", "Close"), null)
            .show().also { OvpnTheme.styleDialog(it, this); OvpnTheme.applyCompactDialogSize(it, this) }
    }

    private fun showNeedCredentialsDialog() {
        val p = profiles.firstOrNull { it.name == OvpnEngine.activeName } ?: return
        val userInput = EditText(this).apply { hint = L("نام کاربری", "Username"); setText(p.username) }
        val passInput = EditText(this).apply {
            hint = L("رمز عبور", "Password")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val saveBox = CheckBox(this).apply {
            text = L("ذخیره‌ی رمز", "Save password"); isChecked = p.savePassword
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 16)
            addView(userInput); addView(passInput); addView(saveBox)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("سرور یوزرنیم/پسورد می‌خواهد", "Server needs username/password"))
            .setMessage(L("اتصال در انتظار یوزرنیم/پسورد مانده است. آن‌ها را وارد کن تا دوباره وصل شود.", "The connection is waiting for username/password. Enter them to reconnect."))
            .setView(col)
            .setPositiveButton(L("وصل شو", "Connect")) { _, _ ->
                p.username = userInput.text.toString().trim()
                p.password = passInput.text.toString()
                p.savePassword = saveBox.isChecked
                if (p.savePassword) OvpnStore.save(this, profiles)
                try {
                } catch (_: Exception) {}
                OvpnEngine.disconnect()
                needDialogShownFor = null
                connectProfile(p)
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show().also { OvpnTheme.styleDialog(it, this) }
    }

    private fun showDiagnosis(index: Int) {
        val p = profiles[index]
        fun yn(b: Boolean): String = if (b) T("بله", "Yes", "Да") else T("خیر", "No", "Нет")
        val text = p.ovpn
        fun has(re: String): Boolean = Regex(re, RegexOption.IGNORE_CASE).containsMatchIn(text)
        fun lineOpt(name: String): String {
            val m = Regex("(?m)^\\s*$name\\s+(.+)$", RegexOption.IGNORE_CASE).find(text)
            return m?.groupValues?.getOrNull(1)?.trim() ?: "—"
        }
        var parserInfo = ""
        try {
            val cp = de.blinkt.openvpn.core.ConfigParser()
            cp.parseConfig(java.io.StringReader(text))
            val vp = cp.convertProfile()
            val check = vp.checkProfile(this)
            val checkStr = try { getString(check) } catch (_: Exception) { "res $check" }
            val authType = when (vp.mAuthenticationType) {
                de.blinkt.openvpn.VpnProfile.TYPE_USERPASS -> L("USERPASS (فقط یوزرنیم/پسورد)", "USERPASS (username/password only)")
                de.blinkt.openvpn.VpnProfile.TYPE_USERPASS_CERTIFICATES -> L("USERPASS_CERTIFICATES (یوزرنیم/پسورد + گواهی)", "USERPASS_CERTIFICATES (username/password + certificate)")
                de.blinkt.openvpn.VpnProfile.TYPE_CERTIFICATES -> L("CERTIFICATES (فقط گواهی)", "CERTIFICATES (certificate only)")
                de.blinkt.openvpn.VpnProfile.TYPE_USERPASS_PKCS12 -> "USERPASS_PKCS12"
                de.blinkt.openvpn.VpnProfile.TYPE_PKCS12 -> "PKCS12"
                else -> T("نوع ${vp.mAuthenticationType}", "Type ${vp.mAuthenticationType}", "Тип ${vp.mAuthenticationType}")
            }
            parserInfo = T("نوع احراز هویت (موتور): $authType\n", "Auth type (engine): $authType\n", "Тип проверки (движок): $authType\n") +
                T("بررسی پروفایل (موتور): $checkStr\n", "Profile check (engine): $checkStr\n", "Проверка профиля (движок): $checkStr\n") +
                T("ca در پروفایل موتور: ${yn(!vp.mCaFilename.isNullOrBlank())}\n", "ca in engine profile: ${yn(!vp.mCaFilename.isNullOrBlank())}\n", "ca в профиле движка: ${yn(!vp.mCaFilename.isNullOrBlank())}\n") +
                T("cert در پروفایل موتور: ${yn(!vp.mClientCertFilename.isNullOrBlank())}\n", "cert in engine profile: ${yn(!vp.mClientCertFilename.isNullOrBlank())}\n", "cert в профиле движка: ${yn(!vp.mClientCertFilename.isNullOrBlank())}\n") +
                T("key در پروفایل موتور: ${yn(!vp.mClientKeyFilename.isNullOrBlank())}\n", "key in engine profile: ${yn(!vp.mClientKeyFilename.isNullOrBlank())}\n", "key в профиле движка: ${yn(!vp.mClientKeyFilename.isNullOrBlank())}\n") +
                T("tls-auth در موتور: ${if (vp.mUseTLSAuth) yn(true) + " (${vp.mTLSAuthDirection ?: ""})" else yn(false)}\n", "tls-auth in engine: ${if (vp.mUseTLSAuth) yn(true) + " (${vp.mTLSAuthDirection ?: ""})" else yn(false)}\n", "tls-auth в движке: ${if (vp.mUseTLSAuth) yn(true) + " (${vp.mTLSAuthDirection ?: ""})" else yn(false)}\n")
        } catch (e: Exception) {
            parserInfo = T("خطای تجزیه موتور: ${e.message ?: e.javaClass.simpleName}\n", "Engine parse error: ${e.message ?: e.javaClass.simpleName}\n", "Ошибка разбора в движке: ${e.message ?: e.javaClass.simpleName}\n")
        }
        var certInfo = ""
        try {
            val block = Regex("<cert>(.*?)</cert>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(text)?.groupValues?.getOrNull(1)
            if (block != null) {
                val cf = java.security.cert.CertificateFactory.getInstance("X.509")
                val cert = cf.generateCertificate(java.io.ByteArrayInputStream(block.toByteArray())) as java.security.cert.X509Certificate
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                certInfo = T("گواهی کلاینت تا: ${fmt.format(cert.notAfter)}\n", "Client certificate until: ${fmt.format(cert.notAfter)}\n", "Сертификат клиента до: ${fmt.format(cert.notAfter)}\n") +
                    T("صادرکننده گواهی کلاینت: ${cert.issuerX500Principal.name.take(80)}\n", "Client certificate issuer: ${cert.issuerX500Principal.name.take(80)}\n", "Издатель сертификата клиента: ${cert.issuerX500Principal.name.take(80)}\n")
            } else {
                certInfo = L("بلوک <cert> در فایل نیست.\n", "No <cert> block in file.\n")
            }
        } catch (e: Exception) {
            certInfo = T("خواندن گواهی کلاینت ناموفق: ${e.message ?: ""}\n", "Failed to read client certificate: ${e.message ?: ""}\n", "Не удалось прочитать сертификат клиента: ${e.message ?: ""}\n")
        }
        val info = buildString {
            append(T("نام: ${p.name}\n", "Name: ${p.name}\n", "Имя: ${p.name}\n"))
            append(T("سرور: ${p.hostname}\n", "Server: ${p.hostname}\n", "Сервер: ${p.hostname}\n"))
            append("proto: ${lineOpt("proto")}\n")
            append("remote: ${lineOpt("remote")}\n")
            append("cipher: ${lineOpt("cipher")}\n")
            append("auth: ${lineOpt("auth")}\n")
            append(T("ca دارد: ${yn(has("<ca>") || has("(?m)^\\s*ca\\s+"))}\n", "Has ca: ${yn(has("<ca>") || has("(?m)^\\s*ca\\s+"))}\n", "Есть ca: ${yn(has("<ca>") || has("(?m)^\\s*ca\\s+"))}\n"))
            append(T("گواهی کلاینت (cert) دارد: ${yn(has("<cert>") || has("(?m)^\\s*cert\\s+"))}\n", "Has client certificate (cert): ${yn(has("<cert>") || has("(?m)^\\s*cert\\s+"))}\n", "Есть сертификат клиента (cert): ${yn(has("<cert>") || has("(?m)^\\s*cert\\s+"))}\n"))
            append(T("کلید کلاینت (key) دارد: ${yn(has("<key>") || has("(?m)^\\s*key\\s+"))}\n", "Has client key (key): ${yn(has("<key>") || has("(?m)^\\s*key\\s+"))}\n", "Есть ключ клиента (key): ${yn(has("<key>") || has("(?m)^\\s*key\\s+"))}\n"))
            append(T("tls-auth دارد: ${yn(has("tls-auth"))}\n", "Has tls-auth: ${yn(has("tls-auth"))}\n", "Есть tls-auth: ${yn(has("tls-auth"))}\n"))
            append(T("tls-crypt دارد: ${yn(has("tls-crypt"))}\n", "Has tls-crypt: ${yn(has("tls-crypt"))}\n", "Есть tls-crypt: ${yn(has("tls-crypt"))}\n"))
            append(T("یوزرنیم/پسورد می‌خواهد: ${yn(p.needsAuth())}\n", "Needs username/password: ${yn(p.needsAuth())}\n", "Требует имя пользователя и пароль: ${yn(p.needsAuth())}\n"))
            append(L("\n--- تشخیص موتور ---\n", "\n--- Engine diagnosis ---\n"))
            append(parserInfo)
            append(certInfo)
        }
        val tv = TextView(this).apply {
            this.text = info
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(32, 16, 32, 16)
        }
        val note = TextView(this).apply {
            this.text = L("موتور: OpenVPN3 (مثل برنامه رسمی) — فایل .ovpn دقیقاً همان‌طور که هست استفاده می‌شود.", "Engine: OpenVPN3 (like the official app) — the .ovpn file is used verbatim.")
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(32, 0, 32, 16)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv); addView(note)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("تشخیص پروفایل", "Profile diagnosis"))
            .setView(col)
            .setPositiveButton(L("بستن", "Close"), null)
            .show().also { OvpnTheme.styleDialog(it, this) }
    }

    /** One-line name tap popup: a small glass popup exactly the
     *  size of the row, showing the full name, auto-closing
     *  after 2s. */
    private fun showNamePopup(row: View, name: String) {
        try {
            val tv = TextView(this).apply {
                text = name
                textSize = 16f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                setPadding(dp(16), 0, dp(16), 0)
            }
            val dlg = android.app.Dialog(this).apply {
                requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                setContentView(tv)
                setCanceledOnTouchOutside(true)
            }
            val win = dlg.window
            if (win != null) {
                win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                tv.background = OvpnTheme.glassBg(
                    Color.parseColor("#FFFFFF"), Color.parseColor("#DDDDDD"), 18f, this)
                val loc = IntArray(2)
                row.getLocationOnScreen(loc)
                val lp = win.attributes
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.y = loc[1]
                lp.width = row.width
                lp.height = if (row.height > 0) row.height
                    else android.view.WindowManager.LayoutParams.WRAP_CONTENT
                lp.dimAmount = 0f
                win.attributes = lp
            }
            tv.setOnClickListener { try { dlg.dismiss() } catch (_: Exception) {} }
            dlg.show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { try { if (dlg.isShowing) dlg.dismiss() } catch (_: Exception) {} }, 2000)
        } catch (_: Exception) {}
    }


    /** 1.43 (D): the GENERAL OpenVPN guide — exactly what the main
     *  app's "?" opens (config-renamer OpenVpnActivity.kt:3140
     *  showOvpnGuide): a 340x560dp dialog, «راهنمای OpenVPN» title,
     *  one section per OpenVPN option. NOT the account-creation
     *  tutorial (that is startGuide, from the box's guide third). */
    private fun showOvpnGuide() {
        try {
            val d = resources.displayMetrics.density
            val en = AppLanguage.isEnglish(this)
            fun sec(faT: String, faB: String, enT: String, enB: String): Pair<String, String> {
                return if (en) enT to enB else faT to faB
            }
            val sections = listOf(
                sec("دکمه خاموش و روشن",
                    "لمس آن وصل و قطع می‌کند.",
                    "Connect toggle",
                    "Tap it to connect or disconnect."),
                sec("Free OpenVpn config",
                    "بالای فهرست پروفایل‌ها یک جعبه هست با نوشته «اکانت openvpn رایگان» و جلوی اسم هر کشور پرچمش را نشان می‌دهد. اگر هنوز حسابی نداری، با زدن آن جعبه برنامه خودش در پس‌زمینه برایت حساب می‌سازد و تاییدش می‌کند؛ فقط کمی صبر کن (حدود یک دقیقه). ساخت حساب فقط وقتی شروع می‌شود که VPN خودت (از هر برنامه‌ای) روشن باشد و حساب را از همان VPN می‌سازد. اگر ساخت حساب نشد، برنامه خودش تا ۳ بار با ایمیل تازه دوباره تلاش می‌کند و فقط بعد از آن پیامش را می‌بینی و می‌توانی «تلاش دوباره» بزنی؛ ورود دستی وجود ندارد، همه‌چیز خودکار است. وقتی حساب آماده شد، همان جعبه مثل بقیه پروفایل‌ها می‌شود ولی کوتاه‌تر: سوییچ وصل/قطع دارد، کشورش را از ردیف «Select country» پایین جعبه عوض می‌کنی (۴۷ کشور با پرچم، همان‌جا توی جعبه باز و اسکرول می‌شود)، و با دکمه حذف، هم جعبه و هم حساب پاک می‌شود و جعبه به حالت اول برمی‌گردد. اسم و رمز حساب هیچ‌وقت روی جعبه نشان داده نمی‌شود. وصل شدن از راه یک رله داخلی انجام می‌شود: هسته مستقیم به سرور وصل نمی‌شود، چون از این خط دست‌تکان همان اول قطع می‌شود؛ سرورها اول با دست‌تکان کامل TLS روی پورت ۷۰۱۱ تست می‌شوند تا سراغ آن‌هایی برود که واقعاً جواب می‌دهند.",
                    "Free OpenVpn config",
                    "At the top of the profile list there is a box reading “اکانت openvpn رایگان”, with each country's flag before its name. If you have no account yet, tapping that box makes the app create and verify one for you in the background; just wait a little (about a minute). Account creation only starts when your own VPN (from any app) is on, and the account is created through that VPN. If creating the account fails, the app retries by itself up to 3 times and only then shows the message, with a “Try again” button; there is no manual entry — everything is automatic. Once the account is ready, that same box behaves like the other profiles, only shorter: it has a connect/disconnect toggle, you change its country from the “Select country” row at the bottom of the box (47 countries with flags, opening and scrolling right inside the box), and its delete button removes both the box and the account, returning the box to its first state. The account's name and password are never shown on the box. Connecting goes through an internal relay: the core never dials the server directly, because on this line the handshake is cut right at the start; servers are first tested with a full TLS handshake on port 7011 so the ones that really answer are tried first."),
                sec("دکمه +",
                    "وارد کردن فایل .ovpn. هر فایل یک پروفایل می‌شود و اسمش از اسم فایل می‌آید.",
                    "Add button",
                    "Import an .ovpn file. Each file becomes one profile, named after the file."),
                sec("علامت ℹ (تشخیص)",
                    "تشخیص فایل؛ فقط می‌گوید فایل چه بخش‌ها و گزینه‌هایی دارد و هیچ مقداری را نشان نمی‌دهد.",
                    "Diagnosis info",
                    "File diagnosis; it only says which blocks and options the file has and never shows any values."),
                sec("مداد ✎ و لمس اسم",
                    "مداد پروفایل را ویرایش می‌کند. لمس اسم پروفایل، اسم کامل را در پنجره کوچکی نشان می‌دهد.",
                    "Edit and name tap",
                    "The pencil edits the profile. Tapping the profile name shows the full name in a small popup."),
                sec("سطل زباله (حذف)",
                    "حذف با پنجره تأیید انجام می‌شود، هیچ‌وقت فوری نیست؛ حذف پروفایلی که وصل است، اتصال را قطع می‌کند.",
                    "Trash (delete)",
                    "Deletion asks for confirmation and is never instant; deleting the connected profile disconnects it."),
                sec("آیکون لاگ",
                    "آیکون بالای صفحه، متن کامل لاگ‌ها را نشان می‌دهد.",
                    "Log icon",
                    "The icon at the top shows the full log text.")
            )
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
            }
            box.addView(TextView(this).apply {
                text = L("راهنمای OpenVPN", "OpenVPN guide")
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(OvpnTheme.fg(context))
                setPadding(0, 0, 0, (14 * d).toInt())
            })
            for ((t, b) in sections) {
                box.addView(TextView(this).apply {
                    text = t; textSize = 15f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(if (OvpnTheme.isDark(context)) Color.parseColor("#A5C8FF") else Color.parseColor("#1B365D"))
                    setPadding(0, (8 * d).toInt(), 0, (2 * d).toInt())
                })
                box.addView(TextView(this).apply {
                    text = b; textSize = 13f
                    setTextColor(if (OvpnTheme.isDark(context)) Color.parseColor("#D8DEEA") else Color.parseColor("#37474F"))
                    setPadding(0, 0, 0, (6 * d).toInt())
                })
            }
            val scroll = ScrollView(this)
            scroll.addView(box)
            val dlg = android.app.Dialog(this).apply {
                requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                setContentView(scroll)
                setCanceledOnTouchOutside(true)
            }
            val win = dlg.window
            if (win != null) {
                win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                box.background = OvpnTheme.dialogCard(this)
                val lp = win.attributes
                lp.width = (340 * d).toInt()
                lp.height = (560 * d).toInt()
                win.attributes = lp
            }
            dlg.show()
        } catch (_: Exception) {}
    }

    /** Last REAL dial per profile — a real ovpn3 connect on
     *  this line, not a port probe. Only direct attempts from
     *  this page are recorded. */
    private fun recordDial(name: String, ok: Boolean, err: String?) {
        if (name.isBlank()) return
        try {
            val e = getSharedPreferences("renamer_ovpn", MODE_PRIVATE).edit()
            e.putBoolean("ovpn_dial_ok_$name", ok)
            e.putLong("ovpn_dial_time_$name", System.currentTimeMillis())
            if (ok) e.remove("ovpn_dial_err_$name") else e.putString("ovpn_dial_err_$name", err ?: "")
            e.apply()
            // When the dialled profile is the single free entry,
            // record the outcome per country too — the country
            // list's second circle. Only real ovpn3 dials land
            // here, never a probe.
            if (name == TunnelBear.SINGLE_NAME) {
                try { TunnelBear.recordDialOutcome(this, TunnelBear.selectedCountry(this), ok) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // 1.42 (G): the card dots are gone — probeDots,
    // probeLineText, dialLineText, dialDotState,
    // paintDots and showDotInfo are deleted with
    // them. The session-probe sweep below stays: it
    // fills OvpnSessionProbe's cache, which
    // Monitoring reads for its session-proven count
    // (no card reads it anymore).

    // ---------- page-entry health sweep (phase 1 only: session probe) ----------
    /** Phase 1 fills the session-probe cache (no card
     *  shows dots since 1.42 — Monitoring reads the
     *  cache): TCP
     *  profiles get the full TLS + AUTH + PUSH probe (tight 9s
     *  deadline each, 6-thread pool, best 1-2 IPs only); UDP-only
     *  profiles keep the UDP dot probe for the 1st circle (2nd
     *  stays grey — UDP AUTH+PUSH is phase 2); tls-auth /
     *  tls-crypt profiles are recorded as silent-by-design (grey,
     *  never red). The probe NEVER runs while a VPN is up: a probe
     *  through a live tunnel measures the tunnel exit, not this
     *  line — the 2s poller re-runs the sweep when the line goes
     *  idle. Stops dead on page exit (gen) or on a newer sweep.
     *  (The old phase 2 — the outside test through a tunnel exit
     *  — needed the chain's Xray core and does not exist here.) */
    private fun startHealthSweep() {
        val gen = ++healthSweepGen
        val snap = profiles.map { it.name to it.ovpn }
        if (snap.isEmpty()) return
        val profByName = profiles.associateBy { it.name }
        Thread {
            try {
                val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
                val done = java.util.concurrent.CountDownLatch(snap.size)
                for ((name, text) in snap) {
                    pool.submit {
                        try {
                            if (healthSweepGen != gen) return@submit
                            val trulyConnected = OvpnEngine.isConnected && OvpnEngine.activeName == name
                            if (trulyConnected) {
                                localHealth[name] = 1
                                return@submit
                            }
                            if (anyVpnUp()) {
                                return@submit
                            }
                            val profile = profByName[name] ?: return@submit
                            when {
                                OvpnSessionProbe.hasTlsAuthOrCrypt(text) -> {
                                    OvpnSessionProbe.storeProbe(this@OpenVpnActivity, name,
                                        OvpnSessionProbe.SessionVerdict(0, 0, null, "tls_auth_silent"))
                                    localHealth[name] = 0
                                }
                                OvpnSessionProbe.endpointsOf(text).none { it.proto == "tcp" } -> {
                                    val (state, _) = dotLocalResult(text)
                                    localHealth[name] = state
                                }
                                else -> {
                                    val verdict = try {
                                        OvpnSessionProbe.probeProfile(this@OpenVpnActivity, profile)
                                    } catch (_: Exception) {
                                        OvpnSessionProbe.SessionVerdict(0, 0, null, "error")
                                    }
                                    OvpnSessionProbe.storeProbe(this@OpenVpnActivity, name, verdict)
                                    localHealth[name] = when {
                                        verdict.tls == 2 -> 1
                                        verdict.tls == 1 || verdict.detail == "tcp_dead" -> -1
                                        else -> 0
                                    }
                                }
                            }
                        } catch (_: Exception) {} finally { done.countDown() }
                    }
                }
                done.await(30, java.util.concurrent.TimeUnit.SECONDS)
                try { pool.shutdownNow() } catch (_: Exception) {}
            } catch (_: Exception) {}
        }.start()
    }

    /** LIVE "is any tunnel up" check — the app's own live
     *  tunnel state plus a foreign VPN. v1.24: a bare TRANSPORT_VPN
     *  bit is NOT trusted — the user's phone reports it on
     *  plain mobile/Wi-Fi with no VPN up (phantom VPN), so
     *  a foreign VPN counts as up ONLY when the active
     *  network reports TRANSPORT_VPN AND its link properties
     *  show a real tunnel interface (tun/ppp/tap) with a
     *  link address. A VPN bit on a wlan/rmnet interface is
     *  ignored; missing link properties fail OPEN (not up).
     *  1.76: the app's own live state counts FIRST, via
     *  VpnGatePure — the same three flags QuickConnect.isLive()
     *  reads (VpnClientService.isRunning || WgDirect.isLive ||
     *  OvpnEngine.isConnected) plus OvpnEngine.isConnecting.
     *  Before 1.76 this read ONLY the engine flags plus the
     *  foreign observation, so a live free-WARP session read
     *  as "no VPN" (WG rung: backend-only tunnel named after
     *  the tunnel, never tun/ppp/tap; MASQUE rungs: the app is
     *  disallowed from its own VPN, so its own activeNetwork
     *  observation never shows the VPN bit) and the account
     *  button showed the "turn on your VPN" toast with warp
     *  connected. No persisted record is consulted here — a
     *  finished warp session with a leftover record still
     *  blocks (VpnGatePure takes live inputs only).
     *  Used by the account-creation gate (the signup sites
     *  need a VPN), the backup-account jobs, the guide and
     *  the Open-tab health sweep — never by the country
     *  menu anymore (v1.26: the menu opens unconditionally). */
    private data class ForeignVpn(val vpnBit: Boolean, val iface: String?, val hasAddress: Boolean)

    /** v1.24: the foreign-VPN observation — the raw
     *  TRANSPORT_VPN bit plus the active interface name
     *  and whether it carries a link address. Read in
     *  one pass; used by the gate and by its diagnostic
     *  line. */
    private fun foreignVpnObservation(): ForeignVpn {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = try { cm?.activeNetwork } catch (_: Exception) { null } ?: return ForeignVpn(false, null, false)
            val caps = try { cm?.getNetworkCapabilities(net) } catch (_: Exception) { null }
            val bit = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            val lp = try { cm?.getLinkProperties(net) } catch (_: Exception) { null }
            val iface = try { lp?.interfaceName } catch (_: Exception) { null }
            val hasAddr = try { lp?.linkAddresses?.isNotEmpty() == true } catch (_: Exception) { false }
            ForeignVpn(bit, iface, hasAddr)
        } catch (_: Exception) { ForeignVpn(false, null, false) }
    }

    /** v1.24: a foreign VPN counts as up ONLY on a real
     *  tunnel interface (tun/ppp/tap) with a link
     *  address — a bare TRANSPORT_VPN bit on a wlan/rmnet
     *  interface (the phantom) is ignored. */
    private fun foreignVpnUp(): Boolean {
        val o = foreignVpnObservation()
        if (!o.vpnBit) return false
        val low = try { o.iface?.lowercase() } catch (_: Exception) { null } ?: return false
        if (!(low.startsWith("tun") || low.startsWith("ppp") || low.startsWith("tap"))) return false
        return o.hasAddress
    }

    private fun anyVpnUp(): Boolean {
        return try {
            // 1.76: the app's own live flags first (same
            // sources QuickConnect.isLive() reads, plus
            // isConnecting which it omits); the foreign
            // observation stays as the last branch, so
            // another app's VPN still counts. The decision
            // itself lives in VpnGatePure (pure, JVM-tested);
            // this only gathers the live inputs. No record,
            // activeId or last-connection pref is read here.
            VpnGatePure.isVpnUp(
                serviceRunning = try { VpnClientService.isRunning } catch (_: Exception) { false },
                sessionTag = try { VpnClientService.sessionTag } catch (_: Exception) { null },
                wgLive = try { WgDirect.isLive(this) } catch (_: Exception) { false },
                ovpnConnected = OvpnEngine.isConnected,
                ovpnConnecting = OvpnEngine.isConnecting,
                foreignUp = foreignVpnUp()
            )
        } catch (_: Exception) { false }
    }

    private data class OvpEndpoint(val host: String, val port: Int, val proto: String)

    /** ALL remotes of a profile, each with its own port/proto;
     *  the directive proto is the fallback per line. */
    private fun ovpnEndpoints(text: String): List<OvpEndpoint> {
        val out = ArrayList<OvpEndpoint>()
        var directiveProto: String? = null
        for (line in text.lines()) {
            val t = line.trim()
            if (t == "proto udp" || t.startsWith("proto udp ")) directiveProto = "udp"
            else if (t.startsWith("proto tcp")) directiveProto = "tcp"
        }
        for (line in text.lines()) {
            val t = line.trim()
            if (!t.startsWith("remote ")) continue
            val parts = t.split(Regex("\\s+"))
            if (parts.size < 2) continue
            val host = parts[1]
            val port = if (parts.size >= 3) parts[2].toIntOrNull() ?: 1194 else 1194
            var proto: String? = null
            if (parts.size >= 4) {
                val pr = parts[3].lowercase()
                if (pr.startsWith("tcp")) proto = "tcp" else if (pr.startsWith("udp")) proto = "udp"
            }
            out += OvpEndpoint(host, port, proto ?: directiveProto ?: "udp")
        }
        return out
    }

    private fun resolveHostIps(host: String, max: Int = 2): List<String> {
        if (host.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")) || host.contains(":")) return listOf(host)
        return try {
            java.net.InetAddress.getAllByName(host)
                .filterIsInstance<java.net.Inet4Address>()
                .mapNotNull { it.hostAddress }.distinct().take(max)
        } catch (_: Exception) { emptyList() }
    }

    /** One endpoint's direct verdict: 0 = answered, 1 = TCP
     *  reliably dead, 3 = UDP port closed (ICMP unreachable),
     *  -1 = unresolvable, 2 = UDP silent (inconclusive).
     *  Background threads only. */
    private fun probeDotEndpoint(ep: OvpEndpoint): Int {
        val hosts = resolveHostIps(ep.host, 2)
        if (hosts.isEmpty()) return -1
        return if (ep.proto == "tcp") {
            var ok = false
            for (h in hosts) {
                if (probeTcpAlive(h, ep.port)) { ok = true; break }
            }
            if (ok) 0 else 1
        } else {
            probeUdpDot(hosts[0], ep.port)
        }
    }

    private fun probeTcpAlive(host: String, port: Int): Boolean {
        return try {
            val s = java.net.Socket()
            try {
                s.connect(java.net.InetSocketAddress(host, port), 3000)
                true
            } finally {
                try { s.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) { false }
    }

    /** UDP liveness with ICMP feedback: a CONNECTED socket turns
     *  an ICMP port-unreachable into PortUnreachable, i.e. proof
     *  the port is CLOSED (red) — distinct from silence (grey). */
    private fun probeUdpDot(host: String, port: Int): Int {
        return try {
            val sock = java.net.DatagramSocket()
            try {
                sock.connect(java.net.InetSocketAddress(host, port))
                sock.soTimeout = 500
                val addr = sock.inetAddress ?: return 2
                val sess = ByteArray(8)
                java.util.Random().nextBytes(sess)
                val pkt = ByteArray(13)
                pkt[0] = 0x38.toByte()
                System.arraycopy(sess, 0, pkt, 1, 8)
                val start = System.currentTimeMillis()
                val deadline = start + 5000
                var sent = 0
                while (System.currentTimeMillis() < deadline) {
                    val now = System.currentTimeMillis()
                    if (sent < 3 && now - start >= sent * 1500L) {
                        try { sock.send(java.net.DatagramPacket(pkt, pkt.size, addr, port)) } catch (_: java.net.PortUnreachableException) { return 3 } catch (_: Exception) {}
                        sent++
                    }
                    try {
                        val buf = ByteArray(512)
                        sock.receive(java.net.DatagramPacket(buf, buf.size))
                        return 0
                    } catch (_: java.net.PortUnreachableException) { return 3 }
                    catch (_: java.net.SocketTimeoutException) {}
                }
                2
            } finally {
                try { sock.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) { 2 }
    }

    /** Local probe verdict (health sweep only — the card
     *  dots are gone in 1.42) for one profile: green if ANY remote
     *  answers; else red if any is definitively dead/closed/
     *  unresolvable; else grey. */
    private fun dotLocalResult(text: String): Pair<Int, String> {
        val eps = ovpnEndpoints(text)
        if (eps.isEmpty()) return 0 to "no_endpoint"
        val results = eps.map { probeDotEndpoint(it) }
        return when {
            results.any { it == 0 } -> 1 to "ok"
            results.any { it == 1 } -> -1 to "tcp_dead"
            results.any { it == 3 } -> -1 to "udp_closed"
            results.any { it == -1 } -> -1 to "unresolved"
            else -> 0 to "udp_silent"
        }
    }

    // ---------- import ----------
    private fun importFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, L("انتخاب فایل OpenVPN", "Choose an OpenVPN file")), 700)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 701) {
            OvpnEngine.onPrepareResult(this, resultCode == RESULT_OK)
            refreshStatus()
            refreshList()
            return
        }
        if (requestCode == 700 && resultCode == RESULT_OK) {
            try {
                val uri = data?.data ?: return
                var fileName: String? = null
                try {
                    contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) fileName = c.getString(idx)
                        }
                    }
                } catch (_: Exception) {}
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                val parsed = OvpnStore.parse(text, fileName)
                if (parsed == null) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle(L("فایل نامعتبر", "Invalid file"))
                        .setMessage(L("این فایل پروفایل OpenVPN نیست؛ باید یک فایل .ovpn با خط remote باشد.", "This is not an OpenVPN profile; it must be an .ovpn file with a remote line."))
                        .setPositiveButton(L("بستن", "Close"), null)
                        .show().also { OvpnTheme.styleDialog(it, this) }
                    return
                }
                if (profiles.any { it.name == parsed.name && it.hostname == parsed.hostname }) {
                    android.app.AlertDialog.Builder(this)
                        .setMessage(L("این پروفایل قبلاً اضافه شده است.", "This profile was already added."))
                        .setPositiveButton(L("بستن", "Close"), null)
                        .show().also { OvpnTheme.styleDialog(it, this) }
                    return
                }
                if (parsed.needsAuth() && !parsed.hasEmbeddedCredentials()) {
                    // Open the Imported Profile page: first two
                    // fields auto-filled, username and password
                    // to be entered.
                    OvpnStore.pendingIndex = -1
                    OvpnStore.pending = parsed
                    startActivity(Intent(this, OpenVpnImportActivity::class.java))
                } else {
                    profiles += parsed
                    OvpnStore.save(this, profiles)
                    refreshList()
                    android.app.AlertDialog.Builder(this)
                        .setMessage(L("پروفایل «", "Profile \"") + parsed.name + L("» اضافه شد.", "\" added."))
                        .setPositiveButton(L("بستن", "Close"), null)
                        .show().also { OvpnTheme.styleDialog(it, this) }
                }
            } catch (e: Exception) {
                android.app.AlertDialog.Builder(this)
                    .setMessage(L("خواندن فایل ناموفق: ", "Reading the file failed: ") + (e.message ?: ""))
                    .setPositiveButton(L("بستن", "Close"), null)
                    .show().also { OvpnTheme.styleDialog(it, this) }
            }
        }
    }
}
