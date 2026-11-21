package com.renamer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import java.util.concurrent.Executors

/**
 * Emergency quick connect.
 *
 * Visible UI: one big button, plus one small info line
 * under it (connecting time, then the "connected, tap
 * to see ping" line, then the measured ping). Nothing
 * else is shown: no subscription link, no config list,
 * no config names, no ping numbers during the search,
 * no counts, no status text, no back button (system
 * Back closes the page).
 *
 * On tap, every time, in the background:
 *  1. the emergency subscriptions are fetched fresh
 *     (the main link below, plus any custom links the
 *     user added in the hidden settings),
 *  2. their configs are ping-tested (exactly like
 *     v2rayNG), without ever being saved to the app's
 *     server list or shown anywhere,
 *  3. the app connects to the lowest-ping healthy config.
 * If nothing is healthy, the button simply returns to
 * its off state; no detail is shown.
 *
 * The emergency configs live only in memory during this
 * flow. They are never written to SharedPreferences,
 * never added to the v2ray server list, and the VPN is
 * started under the generic name "اتصال اضطراری", never
 * under the real config name, so opening the v2ray page
 * later cannot reveal which emergency config was used.
 *
 */
class EmergencyActivity : Activity() {
    companion object {
        // The main emergency subscription, as supplied.
        // It is shown in the hidden settings, and it is
        // never deletable there.
        const val EMERGENCY_SUB_URL =
            "https://raw.githubusercontent.com/inaz266/In-az-26/refs/heads/main/filtee.txt"

        fun customSubs(ctx: android.content.Context): MutableList<String> {
            return ctx.getSharedPreferences("emergency_settings", MODE_PRIVATE)
                .getString("custom_subs", "")?.lines()
                ?.map { it.trim() }?.filter { it.isNotBlank() }?.toMutableList()
                ?: mutableListOf()
        }

        /** 2.95: per-link checkboxes in the emergency settings
         *  choose which subs are actually fetched. The main
         *  link is on by default; customs are all on until
         *  the user unchecks one for the first time (the
         *  enabled list is only written once a checkbox is
         *  touched, so existing users keep their behaviour). */
        fun isMainSubEnabled(ctx: android.content.Context): Boolean =
            ctx.getSharedPreferences("emergency_settings", MODE_PRIVATE)
                .getBoolean("main_sub_enabled", true)

        fun enabledCustomSubs(ctx: android.content.Context): List<String> {
            val all = customSubs(ctx)
            val stored = ctx.getSharedPreferences("emergency_settings", MODE_PRIVATE)
                .getString("custom_subs_enabled", null) ?: return all
            val set = stored.lines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
            return all.filter { it in set }
        }

        fun enabledSubUrls(ctx: android.content.Context): List<String> {
            val out = mutableListOf<String>()
            if (isMainSubEnabled(ctx)) out += EMERGENCY_SUB_URL
            out += enabledCustomSubs(ctx)
            return out
        }

    }

    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private lateinit var btn: Button
    private lateinit var outerRingView: android.view.View
    private lateinit var sweepView: android.view.View
    private lateinit var sweepDrawable: HydraUi.SweepRingDrawable
    private lateinit var rotAnim: android.view.animation.RotateAnimation
    private lateinit var infoText: TextView
    // 1.29: the engine is gone — no report button,
    // no selector state.
    // 1.82: subtle connecting pulse (classier button).
    private var pulseAnimator: android.animation.ObjectAnimator? = null
    private var busy = false
    private var pendingBest: String? = null
    private val handler = Handler(Looper.getMainLooper())
    // Ordered healthy configs for the current emergency
    // attempt, best first. In memory only, never saved.
    private var pendingSorted: List<String> = emptyList()
    private var pendingIndex = 0
    // Guards the 6-second retry check: cleared on manual
    // disconnect, on success, and when attempts run out,
    // so a stale check can never reconnect by itself.
    private var emergencyAttemptActive = false
    private var emergencyRetryRunnable: Runnable? = null
    // 1.29 (order 6): generation guard for in-flight
    // connect flows. A tap-while-connecting cancels the
    // flow; the background thread's late completion must
    // then do nothing instead of starting Emergency over
    // whatever the user connected to meanwhile.
    private var connectGeneration = 0

    /** 1.29 (order 6): is ANOTHER VPN up right now — any
     *  section except our own emergency session? The old
     *  code only checked VpnClientService.isRunning, which
     *  is blind to WireGuard/Amnezia (WgDirect) and OpenVPN
     *  (OvpnEngine) sessions — that blindness is exactly
     *  how Emergency re-activated over the user's other VPN.
     *  Ground truth per section: Xray flag + tag, WG live
     *  backend tunnel, OVPN engine flag. */
    private fun anotherVpnActive(): Boolean {
        if (VpnClientService.isRunning && !isEmergencySession()) return true
        try { if (WgDirect.isReallyUp(this)) return true } catch (_: Exception) {}
        if (OvpnEngine.isConnected) return true
        return false
    }

    /** 1.29 (order 6): human label of the other VPN that
     *  is up, for the "stop it first" message. */
    private fun activeOtherVpnLabel(): String {
        if (VpnClientService.isRunning && !isEmergencySession())
            return VpnClientService.sectionLabel(this, VpnClientService.sessionTag)
        try { if (WgDirect.isReallyUp(this)) return L("وایرگارد و امنزیا", "WireGuard & Amnezia") } catch (_: Exception) {}
        if (OvpnEngine.isConnected) return "OpenVPN"
        return L("فیلترشکن دیگر", "another VPN")
    }

    /** 1.29 (order 6): cancel an in-flight connect (tap
     *  while connecting). Stops every re-activation path:
     *  the retry runnable, the pending list, the watch,
     *  and — via the generation bump — any late
     *  background completion. Never stops another
     *  section's live VPN. If an emergency session did
     *  come up meanwhile, it is stopped too (tap = stop). */
    private fun cancelInFlight() {
        connectGeneration++
        emergencyAttemptActive = false
        emergencyRetryRunnable?.let { handler.removeCallbacks(it) }
        emergencyRetryRunnable = null
        pendingSorted = emptyList()
        pendingBest = null
        confirmedEmergency = false
        manualStop = true
        autoReconnectActive = false
        autoReconnectRounds = 0
        stickyInfo = null
        busy = false
        if (isEmergencySession()) {
            val stopIntent = Intent(this, VpnClientService::class.java).apply {
                action = "STOP"
                putExtra("stop_gen", VpnClientService.sessionGen)
            }
            try { startService(stopIntent) } catch (_: Exception) {}
            VpnClientService.isRunning = false
            VpnClientService.currentName = null
            VpnClientService.sessionTag = null
            VpnClientService.emergencyConfig = null
        }
        stopPulse()
        refreshAll()
        appLogEmergencyPageAttempt = false
        AppLog.append(this, "emergency", "قطع دستی.", "Stopped manually.")
    }
    // 2.61: NO fast path any more (user order): a
    // config that connected in the last 6h is NEVER
    // tried first. Every tap runs the ping race:
    // first sub-200ms wins at once, else the full
    // list is tested to the end and the lowest ping
    // leads. The old direct-first flow
    // (recentLastConfig / recordEmergencyLast /
    // directPhase / directCfg / freshConfigs) is
    // gone, not just disabled.
    // Dead list (2.61, emergency-only): configs
    // proven dead by a real attempt (session never
    // came up, or two dead traffic probes, or an
    // unbuildable config) are remembered by
    // fingerprint + time in the emergency settings
    // file — never the config itself — and filtered
    // out of every fresh fetch for 24h, so the ping
    // race stays short and an auto-reconnect never
    // wastes time on them. A config that later
    // proves healthy is removed at once.
    // Auto-reconnect (2.61, page-lifetime only):
    // after a CONFIRMED emergency session drops on
    // its own (not a manual stop, not another
    // section taking over), the page reconnects by
    // itself to a low-ping config from a fresh
    // fetch, up to 3 rounds, then gives up with a
    // message. It only runs while this page is
    // resumed: if Android destroys the page, the
    // watch ends with it — a persisted flag would
    // wrongly reconnect after the user moved on.
    private var confirmedEmergency = false
    private var manualStop = false
    private var autoReconnectActive = false
    private var autoReconnectRounds = 0
    private var stickyInfo: String? = null
    private var watchRunnable: Runnable? = null

    // 2.63: emergency pre-ping. When the page is
    // entered, the ping race starts by itself
    // (before any tap), so a later tap can use the
    // already-ordered list at once. One-shot: a
    // tap consumes the cache; leaving the page
    // without tapping cancels a still-running race.
    // Emergency page only — no other section reads
    // or writes these fields.
    private var prePingThread: Thread? = null
    private var prePingCancelled = false
    private var prePingOrdered: List<String>? = null
    private var prePingAt = 0L
    private var prePingGeneration = 0


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2.90: ONLY the background changes — night is the
        // shared aurora 20% darker, day is the pearl home
        // background as before. The button, its rings and
        // the 14s rotating rim are untouched.
        Theme.applyPage(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Theme.pageBackgroundDarker(this@EmergencyActivity)
            setPadding(32, 32, 32, 32)
        }
        // Dual-circle button: a static outer ring, a
        // slowly rotating inner rim (mostly the state
        // color, one translucent glass-water segment
        // that is never removed), and the glass face.
        // The rim spins in ALL three states.
        val emWrap = android.widget.FrameLayout(this).apply {
            val s = (248 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        outerRingView = android.view.View(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
        }
        emWrap.addView(outerRingView)
        sweepView = android.view.View(this).apply {
            val s = (220 * resources.displayMetrics.density).toInt()
            layoutParams = android.widget.FrameLayout.LayoutParams(s, s, Gravity.CENTER)
        }
        emWrap.addView(sweepView)
        btn = Button(this).apply {
            text = L("اتصال اضطراری", "Emergency connect")
            textSize = 20f
            val s = (200 * resources.displayMetrics.density).toInt()
            layoutParams = android.widget.FrameLayout.LayoutParams(s, s, Gravity.CENTER)
            setOnClickListener { onButton() }
        }
        emWrap.addView(btn)
        root.addView(emWrap)
        sweepDrawable = HydraUi.SweepRingDrawable(Color.parseColor("#E57373"), this)
        sweepView.background = sweepDrawable
        rotAnim = android.view.animation.RotateAnimation(0f, 360f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 14000
            interpolator = android.view.animation.LinearInterpolator()
            repeatCount = android.view.animation.Animation.INFINITE
        }
        sweepView.startAnimation(rotAnim)
        infoText = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Theme.subFg(this@EmergencyActivity))
            setPadding(0, 32, 0, 0)
            visibility = android.view.View.GONE
        }
        root.addView(infoText)
        setContentView(root)
        root.fitsSystemWindows = true
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        // The inner rim keeps spinning in every state;
        // restart it if the window dropped it.
        try { if (sweepView.animation == null) sweepView.startAnimation(rotAnim) } catch (_: Exception) {}
        // 2.61: auto-reconnect watch (page-lifetime
        // only — see the field comment above).
        watchRunnable?.let { handler.removeCallbacks(it) }
        val w = Runnable {
            try { watchEmergencySession() } catch (_: Exception) {}
            watchRunnable?.let { handler.postDelayed(it, 2000) }
        }
        watchRunnable = w
        handler.postDelayed(w, 2000)
        // 2.63: pre-ping on entry.
        maybeStartPrePing()
    }

    override fun onPause() {
        // 2.63: leaving without tapping stops a
        // still-running pre-ping.
        cancelPrePing()
        watchRunnable?.let { handler.removeCallbacks(it) }
        super.onPause()
    }

    /** 2.63: start the ping race in the background
     *  on page entry. Never over a live session,
     *  never while busy, never twice, and never
     *  when a fresh cache already exists. Only a
     *  non-empty order is cached. */
    private fun maybeStartPrePing() {
        if (VpnClientService.isRunning) return
        // 1.29 (order 6): never pre-ping over another
        // section's live VPN either (WG/OVPN included).
        if (anotherVpnActive()) return
        if (busy || emergencyAttemptActive) return
        if (prePingOrdered != null && System.currentTimeMillis() - prePingAt < 5 * 60 * 1000L) return
        if (prePingThread?.isAlive == true) return
        prePingCancelled = false
        val gen = ++prePingGeneration
        prePingThread = Thread {
            try {
                val fetched = fetchEmergencyConfigs()
                val configs = filterDead(fetched)
                if (configs.isEmpty()) return@Thread
                val ordered = pingFirstUnder200Order(configs) { prePingCancelled }
                if (gen != prePingGeneration || prePingCancelled) return@Thread
                if (ordered.isNotEmpty()) {
                    prePingOrdered = ordered
                    prePingAt = System.currentTimeMillis()
                }
            } catch (_: Exception) {}
        }.apply { start() }
    }

    /** 2.63: stop a still-running pre-ping race. A
     *  finished cache survives for its 5 minutes;
     *  the generation guard discards any late
     *  result from the stopped race. */
    private fun cancelPrePing() {
        if (prePingThread?.isAlive == true) {
            prePingCancelled = true
            prePingGeneration++
        }
        prePingThread = null
    }

    /** 2.61: the auto-reconnect watch. While a
     *  confirmed emergency session is alive, do
     *  nothing. If another section took over, the
     *  user moved on: stop watching, never
     *  reconnect over another section's session.
     *  If no session runs and the drop was not
     *  manual, auto-reconnect (up to 3 rounds). */
    private fun watchEmergencySession() {
        if (!confirmedEmergency || manualStop) return
        if (busy || emergencyAttemptActive || autoReconnectActive) return
        // 1.29 (order 6): ANOTHER VPN is up (any section,
        // WG/OVPN included) — the user moved on. Stop
        // watching for good; never reconnect over it.
        if (anotherVpnActive()) {
            confirmedEmergency = false
            autoReconnectActive = false
            refreshAll()
            return
        }
        if (VpnClientService.isRunning) {
            // An emergency session: still ours, keep watching.
            return
        }
        if (autoReconnectRounds >= 3) {
            confirmedEmergency = false
            autoReconnectActive = false
            stickyInfo = L("اتصال اضطراری قطع شد و اتصال خودکار پس از ۳ بار ناموفق ماند؛ دوباره دکمه را بزن.", "Emergency disconnected and auto-reconnect failed 3 times; tap the button again.")
            refreshAll()
            return
        }
        autoReconnectActive = true
        autoReconnectRounds++
        stickyInfo = null
        startConnectFlow(auto = true)
    }

    /** 2.61: end of one connect round. Success
     *  keeps the watch armed (the session is
     *  confirmed again). Failure chains the next
     *  auto round while rounds remain, else gives
     *  up; a manual round just refreshes. */
    private fun finishConnectFlow(auto: Boolean, success: Boolean) {
        if (success) {
            confirmedEmergency = true
            autoReconnectActive = false
            stickyInfo = null
            refreshAll()
            return
        }
        if (auto && autoReconnectRounds < 3) {
            autoReconnectRounds++
            startConnectFlow(auto = true)
            return
        }
        if (auto) {
            confirmedEmergency = false
            autoReconnectActive = false
            stickyInfo = L("اتصال اضطراری قطع شد و اتصال خودکار پس از ۳ بار ناموفق ماند؛ دوباره دکمه را بزن.", "Emergency disconnected and auto-reconnect failed 3 times; tap the button again.")
        }
        refreshAll()
    }





    // --- Button + info line ---
    /** Glass button, same 220dp size as before: a
     *  translucent fill with a thin stroke ring. The
     *  ring is always a little stronger than the fill,
     *  and no state uses a harsh bright color. */
    // 2.10: no blink any more. The state colors live
    // here; glassButton applies them to the face,
    // the outer ring and the rotating rim at once,
    // so the three circles can never drift apart.
    private var emFill = "#FDE8E8"
    private var emStroke = "#E57373"

    /** One place sets all three circles at once, so
     *  the face, the outer ring and the rotating rim
     *  can never drift apart across states. */
    private fun glassButton(fill: String, stroke: String, textColor: String) {
        emFill = fill; emStroke = stroke
        btn.background = HydraUi.emergencyBtnBg(Color.parseColor(fill), Color.parseColor(stroke), this)
        btn.elevation = 8 * resources.displayMetrics.density
        btn.setTextColor(Color.parseColor(textColor))
        try {
            outerRingView.background = HydraUi.RingShadeDrawable(
                HydraUi.lighten(Color.parseColor(stroke), 0.45f),
                Color.parseColor(stroke), 3f, this)
        } catch (_: Exception) {}
        try { sweepDrawable.setBase(Color.parseColor(stroke)) } catch (_: Exception) {}
    }

    /** 1.82: soft pulse while connecting — the single button
     *  breathes instead of sitting flat. Stopped the moment
     *  the session is up, down, or cancelled. */
    private fun startPulse() {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = android.animation.ObjectAnimator.ofFloat(btn, "alpha", 1f, 0.6f, 1f).apply {
            duration = 1600
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        btn.alpha = 1f
    }

    /** The button is "on" ONLY for an emergency
     *  session. A v2ray (or any other section's)
     *  session used to light this button up, because
     *  this only checked isRunning — that cross-talk
     *  is the bug being fixed. */
    private fun isEmergencySession(): Boolean =
        VpnClientService.isRunning && VpnClientService.sessionTag == "emergency"


    private fun refreshButton() {
        if (isEmergencySession()) {
            glassButton("#E8F5E9", "#66BB6A", "#1B5E20")
            btn.text = L("قطع اتصال اضطراری", "Disconnect emergency")
        } else if (busy) {
            // Waiting: soft yellow, as soft as the
            // disconnected red — never a harsh yellow.
            glassButton("#FDEEB8", "#E3B93E", "#7A5A00")
            btn.text = L("اتصال اضطراری", "Emergency connect")
        } else {
            glassButton("#FDE8E8", "#E57373", "#333333")
            btn.text = L("اتصال اضطراری", "Emergency connect")
        }
    }

    private fun refreshAll() {
        val emNow = isEmergencySession()
        if (emNow && !appLogEmergencyWasConnected && appLogEmergencyPageAttempt) {
            appLogEmergencyPageAttempt = false
            AppLog.append(this, "emergency",
                "وصل شد (بدون نام کانفیگ).",
                "Connected (no config name).")
            // 2.45: the headless trigger usually fired
            // already for this session; the dup-guard
            // makes this second call a no-op then.
            ConnVerify.verifyXray(this, "emergency", "emergency", "", VpnClientService.sessionSocksPort.takeIf { it > 0 } ?: VpnConfigBuilder.socksPort(this)) { isEmergencySession() }
        }
        appLogEmergencyWasConnected = emNow
        refreshButton()
        if (!busy) stopPulse()
        if (isEmergencySession() && VpnClientService.emergencyConfig != null) {
            infoText.visibility = android.view.View.VISIBLE
            infoText.text = L("وصل شد با سابسکریبشن، کلیک کنید برای دیدن پینگ", "Connected via subscription, tap to see ping")
            infoText.isClickable = true
            infoText.setOnClickListener { measurePing() }
        } else if (isEmergencySession()) {
            infoText.visibility = android.view.View.VISIBLE
            infoText.text = L("وصل شد با سابسکریبشن", "Connected via subscription")
            infoText.isClickable = false
            infoText.setOnClickListener(null)
        } else if (VpnClientService.isRunning) {
            // Another section's session: say so
            // plainly, but never claim emergency
            // itself is connected.
            infoText.visibility = android.view.View.VISIBLE
            infoText.text = L("اتصال «${VpnClientService.sectionLabel(this, VpnClientService.sessionTag)}» فعال است؛ اول آن را قطع کن.", "A «${VpnClientService.sectionLabel(this, VpnClientService.sessionTag)}» connection is active; stop it first.")
            infoText.isClickable = false
            infoText.setOnClickListener(null)
        } else if (!busy) {
            infoText.visibility = android.view.View.GONE
            infoText.isClickable = false
            infoText.setOnClickListener(null)
        }
        // 2.61: a sticky message (auto-reconnect
        // give-up, or "no healthy config") survives
        // refreshAll while nothing runs and nothing
        // is connecting; it clears on the next tap.
        if (stickyInfo != null && !isEmergencySession() && !busy && !VpnClientService.isRunning) {
            infoText.visibility = android.view.View.VISIBLE
            infoText.text = stickyInfo
            infoText.isClickable = false
            infoText.setOnClickListener(null)
        }
    }

    private fun measurePing() {
        val cfg = VpnClientService.emergencyConfig ?: return
        infoText.text = L("در حال اندازه‌گیری پینگ...", "Measuring ping...")
        infoText.isClickable = false
        Thread {
            val d = try {
                if (!CoreInit.init(applicationContext)) -1L
                else XraySpeedtest.measureOneWithError(cfg).first
            } catch (_: Throwable) {
                -1L
            }
            runOnUiThread {
                infoText.text = if (d >= 0) L("وصل شد با سابسکریبشن، پینگ: ", "Connected via subscription, ping: ") + "$d ms"
                                else L("وصل شد با سابسکریبشن، پینگ: -1 ms", "Connected via subscription, ping: -1 ms")
                infoText.isClickable = true
                infoText.setOnClickListener { measurePing() }
            }
        }.start()
    }


    // 2.43: central-log flip state for the page.
    // PageAttempt marks a connect started from this
    // page (so a Smart-driven emergency session
    // is not double-logged here — the headless
    // path logs it).
    private var appLogEmergencyWasConnected = false
    private var appLogEmergencyPageAttempt = false

    private fun onButton() {
        // 1.29 (order 6b): tap while ON stops — never
        // restarts or reconnects.
        if (isEmergencySession()) {
            emergencyAttemptActive = false
            emergencyRetryRunnable?.let { handler.removeCallbacks(it) }
            emergencyRetryRunnable = null
            pendingSorted = emptyList()
            confirmedEmergency = false
            manualStop = true
            autoReconnectActive = false
            autoReconnectRounds = 0
            stickyInfo = null
            connectGeneration++
            val stopIntent = Intent(this, VpnClientService::class.java).apply {
                action = "STOP"
                putExtra("stop_gen", VpnClientService.sessionGen)
            }
            try { startService(stopIntent) } catch (_: Exception) {}
            VpnClientService.isRunning = false
            VpnClientService.currentName = null
            VpnClientService.sessionTag = null
            VpnClientService.emergencyConfig = null
            stopPulse()
            refreshAll()
            appLogEmergencyPageAttempt = false
            AppLog.append(this, "emergency",
                "قطع دستی.",
                "Stopped manually.")
            return
        }
        // 1.29 (order 6b): tap while CONNECTING cancels
        // the in-flight flow — it must not complete over
        // whatever the user connected to meanwhile.
        if (busy || emergencyAttemptActive) {
            cancelInFlight()
            return
        }
        // 1.29 (order 6a): another section's session is
        // up (any section, WG/OVPN included): NEVER
        // stop it from here, and never start Emergency
        // over it. Just say which one it is.
        if (anotherVpnActive()) {
            infoText.visibility = android.view.View.VISIBLE
            infoText.isClickable = false
            infoText.setOnClickListener(null)
            infoText.text = L("اتصال «${activeOtherVpnLabel()}» فعال است؛ اول آن را قطع کن.", "A «${activeOtherVpnLabel()}» connection is active; stop it first.")
            return
        }
        // 2.61: a manual tap resets the auto state
        // (a fresh manual round, not a continuation
        // of any auto round) and runs the shared
        // flow: fetch, drop dead, ping race, try.
        manualStop = false
        autoReconnectRounds = 0
        autoReconnectActive = false
        stickyInfo = null
        startConnectFlow(auto = false)
    }


    /** The subscription flow found nothing usable: give up
     *  (a manual round just refreshes; an auto round chains
     *  the next round or gives up via finishConnectFlow). */
    private fun subFailed(auto: Boolean, showSticky: Boolean = true) {
        busy = false
        if (showSticky) {
            stickyInfo = L("هیچ کانفیگ سالمی پیدا نشد؛ بعداً دوباره امتحان کن.", "No healthy config found; try again later.")
        }
        if (auto) finishConnectFlow(auto = true, success = false) else refreshAll()
    }


    /** 2.61: one connect round, manual or automatic.
     *  Fetch fresh, drop dead-listed configs, run the
     *  ping race, try up to 3 healthy configs. Shared
     *  by the button and by auto-reconnect, so both
     *  obey the same rules (never a -1 config, never
     *  a dead-listed one). */
    private fun startConnectFlow(auto: Boolean) {
        // 1.29 (order 6a): never start over another
        // section's live VPN (any section, WG/OVPN
        // included) — the VPN slot belongs to it.
        if (anotherVpnActive()) {
            busy = false
            stickyInfo = L("اتصال «${activeOtherVpnLabel()}» فعال است؛ اول آن را قطع کن.", "A «${activeOtherVpnLabel()}» connection is active; stop it first.")
            refreshAll()
            return
        }
        busy = true
        stickyInfo = null
        startPulse()
        refreshButton()
        infoText.visibility = android.view.View.VISIBLE
        infoText.isClickable = false
        infoText.setOnClickListener(null)
        appLogEmergencyPageAttempt = true
        if (auto) {
            AppLog.append(this, "emergency",
                "اتصال خودکار آغاز شد (دور $autoReconnectRounds).",
                "Auto-reconnect started (round $autoReconnectRounds).")
            infoText.text = L("اتصال قطع شد؛ اتصال خودکار به بهترین کانفیگ...", "Disconnected; auto-connecting to the best config...")
        } else {
            AppLog.append(this, "emergency",
                "تلاش اتصال آغاز شد.",
                "Connect attempt started.")
            infoText.text = L("در حال اتصال به بهترین کانفیگ...", "Connecting to the best config...")
        }
        // 1.29 (order 6): this flow's generation — a
        // tap-while-connecting bumps it, and every
        // late completion below checks it first.
        val flowGen = ++connectGeneration
        Thread {
            try {
                // 2.63: a manual tap consumes a fresh
                // pre-ping cache (one-shot); auto
                // rounds always fetch fresh, and a
                // dead-filtered cache falls back to a
                // fresh fetch too.
                val cached = if (!auto) prePingOrdered?.takeIf {
                    System.currentTimeMillis() - prePingAt < 5 * 60 * 1000L
                } else null
                if (!auto) prePingOrdered = null
                val fetched: List<String>
                val configs: List<String>
                if (cached != null) {
                    val live = filterDead(cached)
                    if (live.isNotEmpty()) {
                        fetched = emptyList()
                        configs = live
                    } else {
                        fetched = fetchEmergencyConfigs()
                        configs = filterDead(fetched)
                    }
                } else {
                    fetched = fetchEmergencyConfigs()
                    configs = filterDead(fetched)
                }
                if (configs.isEmpty()) {
                    runOnUiThread {
                        // 1.29 (order 6): a cancelled flow
                        // must not report or reconnect.
                        if (flowGen != connectGeneration) return@runOnUiThread
                        subFailed(auto)
                    }
                    return@Thread
                }
                // Approximate time, from the fetched count
                // and the ping-test time. The count itself
                // is never shown, only the time. Manual
                // rounds only: an auto round keeps its
                // "reconnecting" text.
                if (!auto) {
                    val batches = (configs.size + XraySpeedtest.poolSize() - 1) /
                        XraySpeedtest.poolSize()
                    val estSeconds = (batches * 3 + 5).coerceAtLeast(10)
                    val estText = if (estSeconds < 60) L("تقریبا ", "About ") + "$estSeconds " + L("ثانیه", "seconds")
                                  else L("تقریبا ", "About ") + "${(estSeconds + 59) / 60} " + L("دقیقه", "minutes")
                    runOnUiThread {
                        infoText.text = L("در حال اتصال به بهترین کانفیگ... ", "Connecting to the best config... ") + estText
                    }
                }
                val ordered = orderEmergencyConfigs(configs)
                runOnUiThread {
                    // 1.29 (order 6): a cancelled flow
                    // must not start anything — the user
                    // tapped to stop, or moved on.
                    if (flowGen != connectGeneration) return@runOnUiThread
                    busy = false
                    if (ordered.isEmpty()) {
                        subFailed(auto)
                        return@runOnUiThread
                    }
                    // 1.29 (order 6): the fetch+ping race
                    // took seconds — another VPN may have
                    // come up meanwhile. Never start over it.
                    if (anotherVpnActive()) {
                        stickyInfo = L("اتصال «${activeOtherVpnLabel()}» فعال است؛ اول آن را قطع کن.", "A «${activeOtherVpnLabel()}» connection is active; stop it first.")
                        refreshAll()
                        return@runOnUiThread
                    }
                    // Try in order, up to 3 attempts:
                    // if a connect does not actually come
                    // up, the next config is tried.
                    pendingSorted = ordered
                    tryEmergencyAt(0)
                }
            } catch (_: Exception) {
                // A failed sub flow gives up without inventing a
                // "no healthy config" message for a fetch error.
                runOnUiThread {
                    // 1.29 (order 6): a cancelled flow
                    // must not report or reconnect.
                    if (flowGen != connectGeneration) return@runOnUiThread
                    subFailed(auto, showSticky = false)
                }
            }
        }.start()
    }

    /** 2.61: dead list, emergency-only. Keys
     *  "dead_<sha256>" -> timestamp in the
     *  emergency settings file. Only a fingerprint
     *  is ever stored, never a config. Entries older
     *  than 24h are ignored and pruned, so a
     *  recovered server returns to the pool. */
    private fun deadPrefs() = getSharedPreferences("emergency_settings", MODE_PRIVATE)

    private fun markDead(cfg: String) {
        try {
            val h = shaHex(XraySpeedtest.dedupKey(cfg))
            if (h.isBlank()) return
            deadPrefs().edit().putLong("dead_$h", System.currentTimeMillis()).apply()
        } catch (_: Exception) {}
    }

    private fun unmarkDead(cfg: String) {
        try {
            val h = shaHex(XraySpeedtest.dedupKey(cfg))
            if (h.isBlank()) return
            deadPrefs().edit().remove("dead_$h").apply()
        } catch (_: Exception) {}
    }

    private fun isDead(cfg: String): Boolean {
        return try {
            val h = shaHex(XraySpeedtest.dedupKey(cfg))
            if (h.isBlank()) return false
            val t = deadPrefs().getLong("dead_$h", 0L)
            t > 0L && System.currentTimeMillis() - t < 24L * 60L * 60L * 1000L
        } catch (_: Exception) { false }
    }

    /** Drop dead-listed configs from a fresh fetch;
     *  also prune expired dead entries. */
    private fun filterDead(configs: List<String>): List<String> {
        try {
            val p = deadPrefs()
            val now = System.currentTimeMillis()
            val expired = p.all.keys.filter { it.startsWith("dead_") &&
                now - p.getLong(it, 0L) >= 24L * 60L * 60L * 1000L }
            if (expired.isNotEmpty()) {
                val e = p.edit()
                for (k in expired) e.remove(k)
                e.apply()
            }
        } catch (_: Exception) {}
        return configs.filter { !isDead(it) }
    }

    /** Fetch fresh from the main link + every custom link.
     *  Returns deduped configs, in memory only. */
    private fun fetchEmergencyConfigs(): List<String> {
        if (!CoreInit.init(applicationContext)) return emptyList()
        // 2.95: only the checked subs (see enabledSubUrls).
        val urls = enabledSubUrls(this)
        // Fetch all subscription URLs IN PARALLEL
        // (was: one by one, 15s timeout each, so
        // several slow subs stacked into a long
        // dead-looking wait before the first connect
        // attempt). 8s per URL, 12s for all of them
        // together; whatever arrived in time is used.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(maxOf(1, urls.size))
        try {
            val futures = urls.map { url ->
                pool.submit(java.util.concurrent.Callable<List<String>> {
                    val seen = mutableSetOf<String>()
                    val out = mutableListOf<String>()
                    try {
                        val conn = java.net.URL(url).openConnection()
                            as java.net.HttpURLConnection
                        conn.connectTimeout = 8000; conn.readTimeout = 8000
                        var body = conn.inputStream.bufferedReader().readText().trim()
                        if (!body.contains("://")) {
                            try {
                                var b = body.replace('-', '+').replace('_', '/')
                                while (b.length % 4 != 0) b += "="
                                body = String(android.util.Base64.decode(b, android.util.Base64.DEFAULT), Charsets.UTF_8)
                            } catch (_: Exception) {}
                        }
                        for (line in body.lines()) {
                            val l = line.trim()
                            if (l.isBlank() || !l.contains("://")) continue
                            if (XraySpeedtest.isGroup(l)) continue
                            if (seen.add(XraySpeedtest.dedupKey(l))) out += l
                        }
                    } catch (_: Exception) {}
                    out
                })
            }
            val deadline = System.currentTimeMillis() + 12_000
            val perUrl = mutableListOf<List<String>>()
            for (f in futures) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) { perUrl.add(emptyList()); continue }
                perUrl.add(try { f.get(remaining, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    catch (_: Exception) { emptyList() })
            }
            val seen = mutableSetOf<String>()
            val out = mutableListOf<String>()
            for (list in perUrl) {
                for (l in list) {
                    if (seen.add(XraySpeedtest.dedupKey(l))) out += l
                }
            }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    /** 2.61: emergency-only selection, separate from
     *  v2ray and from every other section. NO fast
     *  path: a config that connected before is never
     *  tried first. The ping race decides every time:
     *  first result under 200ms wins at once, else
     *  the whole list is tested to the end and the
     *  lowest ping leads. Only fingerprints (SHA-256
     *  of the dedup key) and timestamps are ever
     *  remembered — never a config itself, which is
     *  still never saved anywhere. */
    private fun orderEmergencyConfigs(configs: List<String>): List<String> =
        pingFirstUnder200Order(configs)

    /** 2.61: ping all configs in parallel, HEALTHY
     *  configs only. The first result under 200ms
     *  wins at once and the rest of the test is
     *  abandoned — no waiting for the test to
     *  finish. If nothing is under 200ms, the test
     *  runs TO THE END of the list and the lowest
     *  ping leads. A config whose ping measured -1
     *  (failed) is NEVER returned: the old fallback
     *  to fetched order, and the appended unmeasured
     *  tail, are what connected -1 configs, and both
     *  are gone. Empty result means no healthy
     *  config: the caller connects nothing. The
     *  120s cap is only a hang guard for a stuck
     *  measure task; any realistic list finishes
     *  long before it. */
    private fun pingFirstUnder200Order(configs: List<String>, isCancelled: () -> Boolean = { false }): List<String> {
        if (configs.isEmpty()) return emptyList()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(XraySpeedtest.poolSize())
        val completion = java.util.concurrent.ExecutorCompletionService<Pair<Long, String>>(pool)
        try {
            for (cfg in configs) {
                completion.submit(java.util.concurrent.Callable {
                    val d = try { XraySpeedtest.measureOneWithError(cfg).first }
                        catch (_: Throwable) { -1L }
                    d to cfg
                })
            }
            val deadline = System.currentTimeMillis() + 120_000
            val healthy = mutableListOf<Pair<Long, String>>()
            var remainingTasks = configs.size
            var winner: String? = null
            while (remainingTasks > 0) {
                // 2.63: sliced poll so a pre-ping
                // cancel wakes the race up within
                // 500ms instead of at the deadline.
                // The manual flow passes no cancel
                // check, so its behavior is unchanged.
                if (isCancelled()) return emptyList()
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val slice = minOf(remaining, 500L)
                val future = completion.poll(slice, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (future == null) continue
                remainingTasks--
                val result = try { future.get() } catch (_: Exception) { null }
                if (result != null && result.first >= 0) {
                    healthy += result
                    if (winner == null && result.first < 200) {
                        winner = result.second
                        break
                    }
                }
            }
            val out = mutableListOf<String>()
            if (winner != null) out += winner
            out += healthy.filter { it.second != winner }.sortedBy { it.first }.map { it.second }
            return out
        } finally {
            pool.shutdownNow()
        }
    }

    private fun shaHex(s: String): String {
        return try {
            val d = java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.toByteArray(Charsets.UTF_8))
            d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        } catch (_: Exception) { "" }
    }

    /** Start attempt [index] of the current ordered list
     *  (max 3 attempts). Runs on the UI thread. When
     *  attempts run out, a manual round just
     *  refreshes; an auto round chains the next
     *  round (or gives up) via finishConnectFlow. */
    private fun tryEmergencyAt(index: Int) {
        // 1.29 (order 6): never try over another
        // section's live VPN (any section, WG/OVPN
        // included) — the VPN slot belongs to it.
        if (anotherVpnActive()) {
            emergencyAttemptActive = false
            pendingSorted = emptyList()
            pendingBest = null
            refreshAll()
            return
        }
        if (index >= pendingSorted.size || index >= 3) {
            emergencyAttemptActive = false
            pendingSorted = emptyList()
            pendingBest = null
            VpnClientService.emergencyConfig = null
            if (autoReconnectActive) finishConnectFlow(auto = true, success = false) else refreshAll()
            return
        }
        pendingIndex = index
        pendingBest = pendingSorted[index]
        emergencyAttemptActive = true
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, 100)
        } else {
            startEmergencyVpn(pendingSorted[index])
        }
    }

    private fun startEmergencyVpn(cfg: String) {
        // 1.29: direct connect — the engine is gone.
        startEmergencyVpnInner(cfg)
    }

    private fun startEmergencyVpnInner(cfg: String) {
        val effective = cfg
        // Emergency builds with its OWN TUN prefs file,
        // not v2ray's (per-section isolation).
        var vpnConfig = VpnConfigBuilder.build(this, effective, prefsName = TunPrefs.EMERGENCY)
        if (vpnConfig == null) {
            // This config cannot even be built: it is
            // dead by construction — mark it and try
            // the next best one instead of giving up.
            if (emergencyAttemptActive) {
                try { markDead(pendingSorted.getOrNull(pendingIndex) ?: cfg) } catch (_: Exception) {}
                tryEmergencyAt(pendingIndex + 1)
            } else refreshAll()
            return
        }
        VpnClientService.emergencyConfig = effective
        val intent = Intent(this, VpnClientService::class.java).apply {
            putExtra("config", vpnConfig)
            // Generic name only, never the real config name.
            putExtra("name", L("اتصال اضطراری", "Emergency connect"))
            putExtra("session_tag", "emergency")
        }
        startService(intent)
        handler.postDelayed({ refreshAll() }, 1500)
        // Accuracy check: if the VPN is not actually up
        // after 6 seconds, stop and try the next best
        // config (up to 3 attempts total). Silent, as
        // before: no config is ever named or shown.
        emergencyRetryRunnable?.let { handler.removeCallbacks(it) }
        val attemptIndex = pendingIndex
        val runnable = Runnable {
            if (!emergencyAttemptActive) return@Runnable
            // 1.29 (order 6): another VPN came up while
            // we were waiting — stop trying over it.
            if (anotherVpnActive()) {
                emergencyAttemptActive = false
                refreshAll()
                return@Runnable
            }
            if (VpnClientService.isRunning) {
                if (VpnClientService.sessionTag != "emergency") {
                    // Another section took over: not our success,
                    // and we must not keep trying over it.
                    emergencyAttemptActive = false
                    refreshAll()
                    return@Runnable
                }
                // 1.82: up is not enough — a tunnel that carries
                // no traffic is a dead session, not a success.
                // Two dead probes (like the old engine watchdog)
                // before falling to the next best config.
                Thread {
                    val ok1 = probeThroughTunnel()
                    var ok = ok1
                    if (!ok) {
                        try { Thread.sleep(4000) } catch (_: Exception) {}
                        ok = probeThroughTunnel()
                    }
                    runOnUiThread {
                        if (!emergencyAttemptActive) return@runOnUiThread
                        // 1.29 (order 6): another VPN came up
                        // during the probe — stop trying over it.
                        if (anotherVpnActive()) {
                            emergencyAttemptActive = false
                            refreshAll()
                            return@runOnUiThread
                        }
                        if (ok) {
                            // Confirmed up: this config proved
                            // healthy, so it leaves the dead
                            // list at once, and the session
                            // becomes the watched one: if it
                            // drops on its own later, the page
                            // reconnects by itself.
                            try { unmarkDead(cfg) } catch (_: Exception) {}
                            confirmedEmergency = true
                            autoReconnectActive = false
                            stickyInfo = null
                            emergencyAttemptActive = false
                            refreshAll()
                        } else {
                            // Two dead probes: this config
                            // is proven dead — remember it
                            // and try the next best one.
                            try { markDead(cfg) } catch (_: Exception) {}
                            VpnClientService.isRunning = false
                            VpnClientService.sessionTag = null
                            VpnClientService.emergencyConfig = null
                            tryEmergencyAt(attemptIndex + 1)
                        }
                    }
                }.start()
                return@Runnable
            }
            // 1.29 (order 6): another VPN came up while the
            // session never came up — do not try the next
            // config over it.
            if (anotherVpnActive()) {
                emergencyAttemptActive = false
                refreshAll()
                return@Runnable
            }
            // Session never came up: proven dead too.
            try { pendingSorted.getOrNull(attemptIndex)?.let { markDead(it) } } catch (_: Exception) {}
            VpnClientService.isRunning = false
            VpnClientService.sessionTag = null
            VpnClientService.emergencyConfig = null
            tryEmergencyAt(attemptIndex + 1)
        }
        emergencyRetryRunnable = runnable
        handler.postDelayed(runnable, 6000)
    }

    /** 1.29: local replacement for the removed engine's
     *  probeThroughTunnel — SOCKS probe through the
     *  running session's real port (or the default
     *  v2ray port); true when there is no port to
     *  probe or the probe itself throws. */
    private fun probeThroughTunnel(): Boolean {
        return try {
            val port = VpnClientService.sessionSocksPort.takeIf { it > 0 }
                ?: VpnConfigBuilder.socksPort(this)
            if (port <= 0) true else ConnVerify.socksProbe(this, port)
        } catch (_: Exception) { true }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            pendingBest?.let { startEmergencyVpn(it) }
            pendingBest = null
        } else if (requestCode == 100) {
            // Permission denied: do not silently try the
            // next config, it would hit the same gate.
            // An auto round ends here too, not chained:
            // without permission no round can succeed.
            confirmedEmergency = false
            autoReconnectActive = false
            emergencyAttemptActive = false
            pendingSorted = emptyList()
            pendingBest = null
            VpnClientService.emergencyConfig = null
            refreshAll()
        }
    }
}
