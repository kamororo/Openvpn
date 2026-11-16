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
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.widget.*

/**
 * Standalone Openvpn app v1.2 — the OpenVPN section of Config
 * Renamer, alone. Opens DIRECTLY into this page: no calculator
 * disguise, no lock, no PIN. Design = approved mockup v4, as
 * revised by the v1.1 order and the v1.2 fixes: channel-logo
 * header + ☰ at the top-LEFT opening a LEFT side drawer (log,
 * guide, day/night, language fa/en/ru, about), two tabs (Free
 * open vpn / Open vpn), one round glass connect orb (14s ring +
 * 40s dashed ring + glow). NO status pill under the orb, NO
 * «TunnelBear» anywhere user-visible (log included), NO on-page
 * connect-error text (log only). Free tab: the orb IS the free
 * flow — one tap creates TWO accounts in the background (first
 * ready connects, second is the backup, with auto-failover +
 * ~1900 MB usage failover); the country chip reads «انتخاب کشور»
 * until a country is picked, then shows its FLAG + NAME, and
 * opens a NARROW anchored popup. Open tab: smaller orb (162dp,
 * 29dp top gap), fixed above a scrolling list; tap a card to
 * SELECT it (light green), then the orb connects it; + FAB is
 * teal→violet and sits above the nav bar. NO Smart engine, NO
 * chain, NO v2ray/WG — every connect here is one DIRECT ovpn3
 * connect (plus the internal split relay for the free entry,
 * exactly as the main app).
 */
class OpenVpnActivity : Activity() {
    companion object {
        const val NAVY = "#1B365D"
        const val ORANGE = "#ED7D31"
    }

    // ---------- state ----------
    private var profiles: MutableList<OvpnProfile> = mutableListOf()
    private var tab = "free"
    private var selectedProfile: String? = null

    // Free-account box state (3.03-3.06, main app).
    @Volatile private var tbCreating = false
    private var tbCountryMenuOpen = false
    // v1.1 dual accounts: one failover attempt per user
    // tap (connect failure), and one usage-failover
    // backup spawn per session while no backup exists.
    private var freeFailoverTried = false
    private var backupSpawnedForUsage = false
    private val tbCountryRowCircles = HashMap<String, LinearLayout>()
    private val tbCountryHosts = HashMap<String, String>()
    private var tbCountryProbeGen = 0
    private var tbCountryProbePool: java.util.concurrent.ExecutorService? = null
    private var tbWaitDialog: android.app.AlertDialog? = null
    private var tbWaitDotsView: TextView? = null
    private var tbWaitProgressView: android.widget.ProgressBar? = null
    private var tbWaitCountdownView: TextView? = null
    private var tbWaitDots = 0
    private val tbWaitDotsLoop = object : Runnable {
        override fun run() {
            try {
                val v = tbWaitDotsView
                if (tbWaitDialog?.isShowing == true && v != null) {
                    tbWaitDots = (tbWaitDots % 3) + 1
                    v.text = L("لطفا کمی صبر کنید", "Please wait a moment") + ".".repeat(tbWaitDots)
                    orbSmall?.text = ".".repeat(tbWaitDots)
                    handler.postDelayed(this, 1000)
                }
            } catch (_: Exception) {}
        }
    }

    // Health dots (2.23-2.24, 3.07): upper = TLS flight / direct
    // test on this line, lower = session (AUTH + PUSH_REPLY).
    private val localHealth = HashMap<String, Int>()
    private val localDotViews = HashMap<String, View>()
    private val remoteDotViews = HashMap<String, View>()
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
            handler.postDelayed(this, 2000)
        }
    }

    // ---------- view refs (built once, updated in place) ----------
    private lateinit var rootFrame: FrameLayout
    private var tabFreeBtn: TextView? = null
    private var tabOpenBtn: TextView? = null
    private var glowView: View? = null
    private var ringArcView: View? = null
    private var ringDashedView: View? = null
    private var orbView: FrameLayout? = null
    private var orbIcon: TextView? = null
    private var orbBig: TextView? = null
    private var orbSmall: TextView? = null
    private var orbPct: TextView? = null
    private var countryChip: TextView? = null
    private var statusLine: TextView? = null
    private var openSection: LinearLayout? = null
    // v1.1: left drawer + about overlay + layout refs.
    private var pageView: LinearLayout? = null
    private var zoneView: FrameLayout? = null
    private var freeBelow: LinearLayout? = null
    private var openScroll: ScrollView? = null
    private var drawerScrim: View? = null
    private var drawerPanel: LinearLayout? = null
    private var drawerWidthPx = 0
    private var drawerOpen = false
    private var aboutOverlay: FrameLayout? = null
    private var countryPopup: android.widget.PopupWindow? = null
    private var cardsContainer: LinearLayout? = null
    private var emptyHint: TextView? = null
    private var fab: Button? = null
    private var ringArcAnim: ObjectAnimator? = null
    private var ringDashedAnim: ObjectAnimator? = null

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OvpnTheme.applyPage(this)
        tab = getSharedPreferences("renamer_ovpn", MODE_PRIVATE).getString("ovpn_tab", "free") ?: "free"
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
    }

    override fun onResume() {
        super.onResume()
        profiles = OvpnStore.load(this)
        // The phone's day/night may have flipped while we were
        // away (auto mode): rebuild if the effective mode changed.
        if (OvpnTheme.isDark(this) != lastDark) recreate()
        handler.post(poller)
        startRingAnims()
        refreshAll()
        val toConnect = OvpnStore.pendingConnect
        if (toConnect != null) {
            OvpnStore.pendingConnect = null
            if (!toConnect.tunnelBear) setTab("open")
            connectProfile(toConnect)
        }
        startHealthSweep()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
        stopRingAnims()
        healthSweepGen++
    }

    private var lastDark = false

    private fun refreshAll() {
        lastDark = OvpnTheme.isDark(this)
        refreshStatus()
        refreshList()
    }

    private fun setTab(t: String) {
        tab = t
        try {
            getSharedPreferences("renamer_ovpn", MODE_PRIVATE).edit().putString("ovpn_tab", t).apply()
        } catch (_: Exception) {}
        renderTabs()
        renderOrb()
        renderStatus()
        renderExtrasVisibility()
    }

    private fun startRingAnims() {
        try {
            ringArcAnim?.cancel()
            ringDashedAnim?.cancel()
            ringArcAnim = ObjectAnimator.ofFloat(ringArcView, "rotation", 0f, 360f).apply {
                duration = 14000
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
            }
            ringDashedAnim = ObjectAnimator.ofFloat(ringDashedView, "rotation", 0f, -360f).apply {
                duration = 40000
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
            }
            ringArcAnim?.start()
            ringDashedAnim?.start()
        } catch (_: Exception) {}
    }

    private fun stopRingAnims() {
        try { ringArcAnim?.cancel() } catch (_: Exception) {}
        try { ringDashedAnim?.cancel() } catch (_: Exception) {}
    }

    // ---------- UI construction (mockup v4, v1.1 layout) ----------
    // The header, tabs and orb are FIXED; only the Open
    // tab's config list scrolls (openScroll, weight 1),
    // so the orb is always visible. The free tab shows
    // only the status line below the orb.
    private fun buildUi() {
        val dark = OvpnTheme.isDark(this)
        rootFrame = FrameLayout(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = OvpnTheme.pageBackground(this@OpenVpnActivity)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
        }
        pageView = page
        buildHeader(page, dark)
        buildTabs(page, dark)
        // Hairline divider.
        page.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                setMargins(dp(28), dp(18), dp(28), 0)
            }
            setBackgroundColor(Color.parseColor(if (dark) "#17FFFFFF" else "#E3EAF0"))
        })
        buildOrbZone(page, dark)
        buildStatusLine(page, dark)
        buildOpenSection(page, dark)
        rootFrame.addView(page)
        // + FAB (58dp), Open tab only — teal→violet like
        // the active tab (v1.1: NOT gold), positioned
        // ABOVE the nav bar via the insets listener.
        fab = Button(this).apply {
            text = "+"
            textSize = 30f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.fabTextColor(this@OpenVpnActivity))
            background = OvpnTheme.fabBackground(this@OpenVpnActivity)
            layoutParams = FrameLayout.LayoutParams(dp(58), dp(58)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(dp(22), dp(22), dp(22), dp(22))
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener { importFile() }
        }
        rootFrame.addView(fab)
        buildDrawer()
        // Swipe: left-to-right opens the drawer,
        // right-to-left closes it (return false so the
        // page keeps its own touch handling).
        var downX = 0f
        var downY = 0f
        rootFrame.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (kotlin.math.abs(dx) > dp(90) &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                        if (dx > 0 && !drawerOpen) openDrawer()
                        else if (dx < 0 && drawerOpen) closeDrawer()
                    }
                }
            }
            false
        }
        setContentView(rootFrame)
        // Safe area: status-bar inset on top, nav-bar
        // inset under the FAB (never fitsSystemWindows,
        // which wipes padding).
        rootFrame.setOnApplyWindowInsetsListener { _, insets ->
            try {
                val top = insets.systemWindowInsetTop
                val bottom = insets.systemWindowInsetBottom
                page.setPadding(0, top, 0, 0)
                (fab?.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                    lp.bottomMargin = dp(22) + bottom
                    fab?.layoutParams = lp
                }
                // v1.2: the drawer panel is added straight
                // to rootFrame (not inside `page`), so it
                // never got the status-bar inset — its logo
                // sat flush against the top edge and
                // collided with the Dynamic Island. Give it
                // the status-bar inset + 24dp.
                drawerPanel?.setPadding(0, top + dp(24), 0, dp(16))
            } catch (_: Exception) {}
            insets
        }
        renderExtrasVisibility()
    }

    // v1.2 header: FORCED LTR row [☰] [logo] [Openvpn].
    // The manifest has no supportsRtl, so the framework
    // forces LTR layout everywhere — the v1.1 header
    // relied on RTL (☰ added last) and the ☰ landed at
    // the TOP-RIGHT on device. LTR + ☰ first puts it at
    // the LEFT end in EVERY language, as ordered. The
    // log / guide / day-night buttons live ONLY in the
    // drawer.
    private fun buildHeader(page: LinearLayout, dark: Boolean) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }
        // ☰ FIRST (leftmost): opens the drawer.
        header.addView(TextView(this).apply {
            text = "☰"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            gravity = Gravity.CENTER
            background = OvpnTheme.glassCircle(this@OpenVpnActivity)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setPadding(0, 0, 0, 0)
            setOnClickListener { openDrawer() }
        })
        // Channel logo: the trimmed circle (never the
        // rectangular photo, never the old shield).
        header.addView(ImageView(this).apply {
            try {
                val bmp = BitmapFactory.decodeStream(assets.open("logo_circle.png"))
                setImageBitmap(bmp)
            } catch (_: Exception) {
                setImageBitmap(null)
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginStart = dp(10)
            }
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Openvpn"
        })
        header.addView(TextView(this).apply {
            text = "Openvpn"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
        })
        page.addView(header)
    }

    private fun buildTabs(page: LinearLayout, dark: Boolean) {
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = rtlDir()
            setPadding(dp(20), dp(16), dp(20), 0)
        }
        fun makeTab(text: String, t: String): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(11), 0, dp(11))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = if (t == "open") dp(10) else 0
                }
                setOnClickListener { setTab(t) }
            }
        }
        tabFreeBtn = makeTab("Free open vpn", "free")
        tabOpenBtn = makeTab("Open vpn", "open")
        tabs.addView(tabFreeBtn)
        tabs.addView(tabOpenBtn)
        page.addView(tabs)
        renderTabs()
    }

    private fun renderTabs() {
        val dark = OvpnTheme.isDark(this)
        tabFreeBtn?.background = OvpnTheme.tabBackground(this, tab == "free")
        tabFreeBtn?.setTextColor(if (tab == "free") Color.WHITE else OvpnTheme.subFg(this))
        tabOpenBtn?.background = OvpnTheme.tabBackground(this, tab == "open")
        tabOpenBtn?.setTextColor(if (tab == "open") Color.WHITE else OvpnTheme.subFg(this))
    }

    private fun buildOrbZone(page: LinearLayout, dark: Boolean) {
        // 340dp zone: the glow overflows the 264dp ring area,
        // so the zone is sized for the glow, orb centred.
        val zone = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(340), dp(340)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(58)
            }
        }
        zoneView = zone
        glowView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(340), dp(340)).apply {
                gravity = Gravity.CENTER
            }
        }
        zone.addView(glowView)
        ringArcView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(258), dp(258)).apply {
                gravity = Gravity.CENTER
            }
            background = OvpnTheme.ringArcDrawable(this@OpenVpnActivity)
        }
        zone.addView(ringArcView)
        ringDashedView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(246), dp(246)).apply {
                gravity = Gravity.CENTER
            }
            background = OvpnTheme.dashedRingDrawable(this@OpenVpnActivity)
        }
        zone.addView(ringDashedView)
        orbView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(232), dp(232)).apply {
                gravity = Gravity.CENTER
            }
            setOnClickListener { onOrbTap() }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        orbIcon = TextView(this).apply {
            text = "🛡️"
            textSize = 32f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        content.addView(orbIcon)
        orbBig = TextView(this).apply {
            textSize = 28f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        content.addView(orbBig)
        orbSmall = TextView(this).apply {
            textSize = 12.5f
            setTextColor(OvpnTheme.subFg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.CENTER
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        content.addView(orbSmall)
        orbPct = TextView(this).apply {
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.accent(this@OpenVpnActivity))
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        content.addView(orbPct)
        // Country chip: BELOW «اتصال» inside the orb, never above.
        countryChip = TextView(this).apply {
            textSize = 12.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER
            background = OvpnTheme.glassPill(this@OpenVpnActivity)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(9)
            }
            setPadding(dp(11), dp(6), dp(11), dp(6))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            isClickable = true
            setOnClickListener { toggleCountryPanel() }
        }
        content.addView(countryChip)
        orbView?.addView(content)
        zone.addView(orbView)
        page.addView(zone)
    }

    // v1.1: NO status pill under the orb (removed).
    // Only the status line, on the free tab — and it
    // NEVER shows a connect error (log only).
    private fun buildStatusLine(page: LinearLayout, dark: Boolean) {
        freeBelow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        statusLine = TextView(this).apply {
            textSize = 12.5f
            setTextColor(OvpnTheme.subFg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.CENTER
            minHeight = dp(42)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(30), dp(14), dp(30), 0)
            }
        }
        freeBelow?.addView(statusLine)
        page.addView(freeBelow)
    }

    // v1.1: the Open tab's list lives in its own
    // ScrollView (weight 1) BELOW the fixed orb: a gap
    // equal to the orb's top gap, a divider line, then
    // the list. ONLY the list scrolls; the orb stays
    // visible. Bottom padding clears the + FAB.
    private fun buildOpenSection(page: LinearLayout, dark: Boolean) {
        openSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp(96))
        }
        // Gap equal to the orb's top gap (29dp).
        openSection?.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(29))
        })
        // Divider, same style as the line under the tabs.
        openSection?.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                setMargins(dp(28), 0, dp(28), 0)
            }
            setBackgroundColor(Color.parseColor(if (dark) "#17FFFFFF" else "#E3EAF0"))
        })
        openSection?.addView(TextView(this).apply {
            text = L("پروفایل‌های من", "My profiles")
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.subFg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(24), dp(20), dp(24), 0)
            }
        })
        cardsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        openSection?.addView(cardsContainer)
        emptyHint = TextView(this).apply {
            text = L("هنوز پروفایلی اضافه نشده است. با دکمه + یک فایل .ovpn وارد کن.",
                "No profile yet. Tap the + button to import an .ovpn file.")
            textSize = 14f
            setTextColor(OvpnTheme.subFg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(24), dp(16), dp(24), 0)
            }
        }
        openSection?.addView(emptyHint)
        openScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = true
            addView(openSection)
        }
        page.addView(openScroll)
    }

    private fun renderExtrasVisibility() {
        val isFree = tab == "free"
        freeBelow?.visibility = if (isFree) View.VISIBLE else View.GONE
        openScroll?.visibility = if (isFree) View.GONE else View.VISIBLE
        fab?.visibility = if (isFree) View.GONE else View.VISIBLE
        if (!isFree) {
            try { countryPopup?.dismiss() } catch (_: Exception) {}
            countryPopup = null
            tbCountryMenuOpen = false
        }
        applyOrbSize()
    }

    // v1.1 orb sizes: free tab 232dp orb / 58dp top gap
    // (as v1.0); open tab 30% smaller (162dp) with a
    // 50% smaller top gap (29dp).
    private fun applyOrbSize() {
        val zone = zoneView ?: return
        val isFree = tab == "free"
        val zoneSize = if (isFree) 340 else 240
        val topGap = if (isFree) 58 else 29
        val orbSize = if (isFree) 232 else 162
        val ringSize = if (isFree) 258 else 182
        val dashSize = if (isFree) 246 else 174
        (zone.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.width = dp(zoneSize)
            it.height = dp(zoneSize)
            it.topMargin = dp(topGap)
            zone.layoutParams = it
        }
        (glowView?.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = dp(zoneSize); it.height = dp(zoneSize)
            glowView?.layoutParams = it
        }
        (ringArcView?.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = dp(ringSize); it.height = dp(ringSize)
            ringArcView?.layoutParams = it
        }
        (ringDashedView?.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = dp(dashSize); it.height = dp(dashSize)
            ringDashedView?.layoutParams = it
        }
        (orbView?.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = dp(orbSize); it.height = dp(orbSize)
            orbView?.layoutParams = it
        }
    }

    // ---------- left side drawer (v1.1) ----------
    // Slides in from the LEFT (Gravity.LEFT — the user
    // explicitly said left, even though the app is RTL).
    // Contents, top to bottom: channel logo (trimmed
    // circle) first, then app log, guide, day/night,
    // app language, about.
    private fun buildDrawer() {
        val root = rootFrame
        drawerScrim = View(this).apply {
            setBackgroundColor(Color.parseColor("#80000000"))
            alpha = 0f
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { closeDrawer() }
        }
        root.addView(drawerScrim)
        // v1.2: NARROWER drawer — ~60% of the screen
        // width, max 300dp (v1.1's 280dp was ~72% and
        // hid too much of the page). The hidden offset
        // follows the width.
        drawerWidthPx = minOf(dp(300),
            (resources.displayMetrics.widthPixels * 0.6).toInt())
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = rtlDir()
            background = OvpnTheme.dialogCard(this@OpenVpnActivity)
            layoutParams = FrameLayout.LayoutParams(drawerWidthPx,
                FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.LEFT
            }
            // v1.2: top padding is corrected by the
            // insets listener (status bar + 24dp) once
            // it fires; 28dp is only the pre-insets
            // fallback so the logo never sits flush
            // against the top edge.
            setPadding(0, dp(28), 0, dp(16))
            translationX = -(drawerWidthPx + dp(20)).toFloat()
        }
        drawerPanel = panel
        // Channel logo FIRST (circular, white trimmed).
        panel.addView(ImageView(this).apply {
            try {
                val bmp = BitmapFactory.decodeStream(assets.open("logo_circle.png"))
                setImageBitmap(bmp)
            } catch (_: Exception) {
                setImageBitmap(null)
            }
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(18)
            }
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        })
        fun row(icon: String, fa: String, en: String, onClick: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = rtlDir()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                setPadding(dp(20), dp(13), dp(20), dp(13))
                isClickable = true
                setOnClickListener { onClick() }
            }
            r.addView(TextView(this).apply {
                text = icon
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
                layoutParams = LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            r.addView(TextView(this).apply {
                text = L(fa, en)
                textSize = 15.5f
                setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
                textDirection = appTextDir()
                gravity = Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(6)
                }
            })
            panel.addView(r)
        }
        row("📋", "لاگ برنامه", "App log") { closeDrawer(); showLogDialog() }
        row("؟", "راهنما", "Guide") { closeDrawer(); showOvpnGuide() }
        row(OvpnTheme.modeGlyph(this), "حالت شب/روز", "Day / night") {
            OvpnTheme.cycleMode(this)
            recreate()
        }
        row("🌐", "زبان برنامه", "App language") { showLanguageDialog() }
        row("ℹ️", "درباره ما", "About") { showAboutOverlay() }
        root.addView(panel)
    }

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        drawerScrim?.visibility = View.VISIBLE
        drawerScrim?.animate()?.alpha(0.55f)?.setDuration(200)?.start()
        drawerPanel?.animate()?.translationX(0f)?.setDuration(250)?.start()
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        drawerScrim?.animate()?.alpha(0f)?.setDuration(200)
            ?.withEndAction { drawerScrim?.visibility = View.GONE }?.start()
        drawerPanel?.animate()?.translationX(-(drawerWidthPx + dp(20)).toFloat())?.setDuration(250)?.start()
    }

    /** Language chooser: English / فارسی / Русский,
     *  checkmark on the current one; tap switches
     *  the WHOLE app immediately (activity recreates). */
    private fun showLanguageDialog() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val cur = AppLanguage.lang(this)
        val langs = listOf(
            "fa" to "فارسی",
            "en" to "English",
            "ru" to "Русский"
        )
        for ((code, name) in langs) {
            col.addView(TextView(this).apply {
                text = (if (code == cur) "✓ " else "    ") + name
                textSize = 16f
                setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
                textDirection = View.TEXT_DIRECTION_LOCALE
                gravity = Gravity.START
                setPadding(dp(16), dp(13), dp(16), dp(13))
                setOnClickListener {
                    try { AppLanguage.set(this@OpenVpnActivity, code) } catch (_: Exception) {}
                    try { recreate() } catch (_: Exception) {}
                }
            })
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("زبان برنامه", "App language"))
            .setView(col)
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show().also { OvpnTheme.styleDialog(it, this) }
    }

    /** About page over the main page + opened menu,
     *  with a BLURRED background (RenderEffect where
     *  available, else a dim scrim). Tapping the
     *  channel ID opens the Telegram channel. */
    private fun showAboutOverlay() {
        if (aboutOverlay != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                pageView?.setRenderEffect(
                    RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP))
            } catch (_: Exception) {}
            try {
                drawerPanel?.setRenderEffect(
                    RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP))
            } catch (_: Exception) {}
        }
        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#99000000"))
            setOnClickListener { hideAboutOverlay() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = rtlDir()
            background = OvpnTheme.dialogCard(this@OpenVpnActivity)
            // v1.2: WIDER card — the 300dp card made the
            // «channel + ID» line wrap to two lines. Full
            // width minus 16dp margins, so the one-line
            // channel row has maximum room.
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                setMargins(dp(16), 0, dp(16), 0)
            }
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setOnClickListener { /* consume: tap on the card does not close */ }
        }
        card.addView(TextView(this).apply {
            text = L("درباره ما", "About")
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.START
            setPadding(0, 0, 0, dp(16))
        })
        card.addView(TextView(this).apply {
            text = L("سازنده: کامیار", "Made by: Kamyar")
            textSize = 15f
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.START
            setPadding(0, 0, 0, dp(12))
        })
        // v1.2: label + channel ID on ONE line, the ID
        // underlined as a link. The v1.1 two-TextView row
        // wrapped the ID to two lines («@internet_azad» /
        // «_2026»). One TextView, single line, and the text
        // shrinks (down to 12sp) until the whole line fits
        // the card — measured at runtime, so it holds on
        // any screen width.
        val channelId = "@internet_azad_2026"
        val channelLabel = T("کانال تلگرام و ارتباط با ما: ",
            "Telegram channel and contact: ",
            "Канал Telegram и связь с нами: ")
        val channelView = TextView(this).apply {
            textSize = 14f
            setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.START
            setSingleLine(true)
            setPadding(0, dp(10), 0, dp(10))
            val span = android.text.SpannableStringBuilder().apply {
                append(channelLabel)
                val start = length
                append(channelId)
                setSpan(android.text.style.UnderlineSpan(), start, length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) { openTelegramChannel() }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        super.updateDrawState(ds)
                        ds.color = OvpnTheme.accent(this@OpenVpnActivity)
                        ds.isUnderlineText = true
                    }
                }, start, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text = span
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
        }
        card.addView(channelView)
        channelView.post {
            try {
                val avail = (card.width - card.paddingLeft - card.paddingRight).toFloat()
                var guard = 0
                while (guard++ < 8 && avail > 0) {
                    val labelW = channelView.paint.measureText(channelLabel)
                    val boldPaint = android.text.TextPaint(channelView.paint).apply {
                        typeface = android.graphics.Typeface.create(channelView.typeface, android.graphics.Typeface.BOLD)
                    }
                    val idW = boldPaint.measureText(channelId)
                    if (labelW + idW <= avail) break
                    val sp = channelView.textSize / resources.displayMetrics.density - 0.5f
                    if (sp < 12f) break
                    channelView.textSize = sp
                }
            } catch (_: Exception) {}
        }
        card.addView(TextView(this).apply {
            text = L("بستن", "Close")
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.accent(this@OpenVpnActivity))
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
            setOnClickListener { hideAboutOverlay() }
        })
        overlay.addView(card)
        rootFrame.addView(overlay)
        aboutOverlay = overlay
    }

    private fun hideAboutOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { pageView?.setRenderEffect(null) } catch (_: Exception) {}
            try { drawerPanel?.setRenderEffect(null) } catch (_: Exception) {}
        }
        val ov = aboutOverlay ?: return
        aboutOverlay = null
        try { rootFrame.removeView(ov) } catch (_: Exception) {}
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

    private fun openConnected(): Boolean {
        val sel = selectedProfileObj() ?: return false
        return OvpnEngine.isConnected && OvpnEngine.activeName == sel.name
    }

    private fun onOrbTap() {
        if (tab == "free") {
            val p = tbProfile()
            if (!TunnelBear.hasAccount(this)) {
                startTbAutoCreation()
                return
            }
            if (p == null) return
            if (freeConnected()) {
                OvpnEngine.disconnect()
                refreshStatus(); refreshList()
            } else {
                connectProfile(p)
            }
            return
        }
        val sel = selectedProfileObj()
        // v1.1: no profile selected -> the orb does
        // nothing (it shows «No profile selected»).
        if (sel == null) return
        if (openConnected()) {
            OvpnEngine.disconnect()
            refreshStatus(); refreshList()
        } else {
            connectProfile(sel)
        }
    }

    // v1.1 free-orb tint (same glass texture, only
    // the tint changes, all light/pale): no account
    // yet = light blue, account but not connected =
    // light red, connected = light green. The ring
    // and glow follow the same state colour.
    private fun freeOrbTint(): Int = when {
        freeConnected() -> Color.parseColor("#8ED6A8")
        TunnelBear.hasAccount(this) -> Color.parseColor("#F2A0A0")
        else -> Color.parseColor("#8EC9EE")
    }

    // v1.2 open-orb tint: the SAME state tints as the
    // free orb (same glass texture, ring/glow follow):
    // no profile selected = light blue, profile
    // selected but not connected = light red,
    // connected = light green.
    private fun openOrbTint(): Int = when {
        openConnected() -> Color.parseColor("#8ED6A8")
        selectedProfileObj() != null -> Color.parseColor("#F2A0A0")
        else -> Color.parseColor("#8EC9EE")
    }

    private fun renderOrb() {
        val dark = OvpnTheme.isDark(this)
        val connecting = OvpnEngine.isConnecting
        val connected = if (tab == "free") freeConnected() else openConnected()
        applyOrbSize()
        val tint = if (tab == "free") freeOrbTint() else openOrbTint()
        orbView?.background = OvpnTheme.orbBackground(this, connected, tint)
        glowView?.background = OvpnTheme.glowDrawable(this, connected, tint)
        ringArcView?.background = if (tint != null) OvpnTheme.ringArcDrawableTinted(this, tint)
            else OvpnTheme.ringArcDrawable(this)
        orbPct?.visibility = View.GONE
        if (tab == "free") {
            val hasAccount = TunnelBear.hasAccount(this)
            if (tbCreating) {
                orbIcon?.visibility = View.GONE
                orbBig?.textSize = 19f
                orbBig?.text = L("لطفا کمی صبر کنید", "Please wait a moment")
                orbSmall?.text = "."
                orbPct?.visibility = View.VISIBLE
                orbPct?.text = "0٪"
                countryChip?.visibility = View.GONE
            } else if (!hasAccount) {
                orbIcon?.visibility = View.VISIBLE
                orbBig?.textSize = 28f
                orbBig?.text = L("دریافت", "Get")
                orbSmall?.text = L("اکانت رایگان", "Free account")
                countryChip?.visibility = View.GONE
            } else {
                orbIcon?.visibility = View.GONE
                orbBig?.textSize = 28f
                val country = TunnelBear.selectedCountry(this)
                if (connecting && OvpnEngine.activeName == TunnelBear.SINGLE_NAME) {
                    orbBig?.text = L("در حال اتصال", "Connecting")
                    orbSmall?.text = OvpnEngine.lastState ?: ""
                } else {
                    orbBig?.text = if (connected) L("توقف", "Stop") else L("اتصال", "Connect")
                    orbSmall?.text = if (connected) L("متصل — ", "Connected — ") + TunnelBear.displayCountry(country)
                        else L("برای اتصال روی دایره بزن", "Tap the circle to connect")
                }
                countryChip?.visibility = View.VISIBLE
                // v1.2: the chip NEVER shows a provider
                // name. Until a country is picked it
                // reads «انتخاب کشور»; ONCE picked it
                // shows the selected country's FLAG +
                // NAME (this corrects the v1.1 literal
                // «always انتخاب کشور» reading).
                countryChip?.text = if (TunnelBear.hasSelectedCountry(this))
                    TunnelBear.displayCountry(country) + "  ▾"
                else
                    T("انتخاب کشور", "Choose country", "Выбрать страну") + "  ▾"
            }
        } else {
            val sel = selectedProfileObj()
            orbIcon?.visibility = View.GONE
            countryChip?.visibility = View.GONE
            orbBig?.textSize = 28f
            if (sel == null) {
                orbBig?.text = L("اتصال", "Connect")
                orbSmall?.text = L("پروفایلی انتخاب نشده", "No profile selected")
            } else if (connecting && OvpnEngine.activeName == sel.name) {
                orbBig?.text = L("در حال اتصال", "Connecting")
                orbSmall?.text = OvpnEngine.lastState ?: ""
            } else {
                orbBig?.text = if (connected) L("توقف", "Stop") else L("اتصال", "Connect")
                orbSmall?.text = if (connected) L("متصل — ", "Connected — ") + sel.name else sel.name
            }
        }
    }

    // v1.1: status line only (the pill is gone).
    // Connect errors NEVER appear here — not even
    // «VPN permission denied»: they go to the app
    // log only (refreshStatus logs the failure).
    private fun renderStatus() {
        val connecting = OvpnEngine.isConnecting
        val connected = if (tab == "free") freeConnected() else openConnected()
        val anyConnected = OvpnEngine.isConnected
        val sl = statusLine ?: return
        sl.setTextColor(OvpnTheme.subFg(this))
        if (tab == "free") {
            if (connected) {
                sl.text = L("متصل به ", "Connected to ") + TunnelBear.displayCountry(TunnelBear.selectedCountry(this))
            } else if (connecting && OvpnEngine.activeName == TunnelBear.SINGLE_NAME) {
                sl.text = L("در حال اتصال به «", "Connecting to “") + TunnelBear.DISPLAY_NAME + "»…"
            } else if (anyConnected) {
                sl.text = "«" + TunnelBear.displayNameForStored(OvpnEngine.activeName) + "» " + L("وصل است", "is connected")
            } else if (!TunnelBear.hasAccount(this)) {
                sl.text = L("برای ساخت اکانت رایگان فیلترشکن خود را روشن کنید", "Turn on your VPN to create a free account")
            } else {
                sl.text = L("حساب آماده است — کشور را از داخل دایره انتخاب کن", "Account ready — pick the country from inside the circle")
            }
        } else {
            val sel = selectedProfileObj()
            if (connected && sel != null) {
                sl.text = L("متصل به پروفایل «", "Connected to profile “") + sel.name + "»"
            } else if (connecting && sel != null && OvpnEngine.activeName == sel.name) {
                sl.text = L("در حال اتصال به «", "Connecting to “") + sel.name + "»…"
            } else if (anyConnected) {
                sl.text = "«" + TunnelBear.displayNameForStored(OvpnEngine.activeName) + "» " + L("وصل است", "is connected")
            } else if (sel == null) {
                sl.text = if (ownProfiles().isEmpty())
                    L("هنوز پروفایلی اضافه نشده است", "No profile yet")
                else
                    T("پروفایلی انتخاب نشده — روی یک پروفایل بزن", "No profile selected — tap a profile", "Профиль не выбран — нажмите на профиль")
            } else {
                sl.text = L("پروفایل «", "Profile “") + sel.name + L("» انتخاب شده — برای اتصال روی دایره بزن", "” selected — tap the circle to connect")
            }
        }
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

    private fun refreshStatus() {
        // Central-log flip detector (2.43): direct connects,
        // direct failures and manual stops. No engine, no chain.
        val ovpnNow = OvpnEngine.isConnected && OvpnEngine.activeName != null
        if (ovpnNow && !appLogOvpnWasConnected) {
            if (appLogOvpnPending) {
                val dispName = logDisplayName(OvpnEngine.activeName)
                AppLog.append(this, "openvpn",
                    "«$dispName» وصل شد؛ اتصال مستقیم.",
                    "“$dispName” connected; direct connect.",
                    "«$dispName» подключён; прямое подключение.")
                try { recordDial(OvpnEngine.activeName ?: "", true, null); refreshList() } catch (_: Exception) {}
                appLogOvpnPending = false
            }
        } else if (!ovpnNow && appLogOvpnWasConnected) {
            if (appLogOvpnLastName.isNotBlank()) {
                AppLog.append(this, "openvpn",
                    "«${logDisplayName(appLogOvpnLastName)}»؛ قطع شد.",
                    "“${logDisplayName(appLogOvpnLastName)}”; disconnected.",
                    "«${logDisplayName(appLogOvpnLastName)}»; отключён.")
            }
        }
        if (appLogOvpnPending && !ovpnNow && !OvpnEngine.isConnecting && OvpnEngine.lastError != null && OvpnEngine.lastError != appLogOvpnLastError) {
            appLogOvpnLastError = OvpnEngine.lastError
            AppLog.append(this, "openvpn",
                "«${logDisplayName(appLogOvpnPendingName)}» وصل نشد: ${OvpnEngine.lastError}.",
                "“${logDisplayName(appLogOvpnPendingName)}” did not connect: ${OvpnEngine.lastError}.",
                "«${logDisplayName(appLogOvpnPendingName)}» не подключился: ${OvpnEngine.lastError}.")
            try { recordDial(appLogOvpnPendingName, false, OvpnEngine.lastError); refreshList() } catch (_: Exception) {}
            val failedName = appLogOvpnPendingName
            appLogOvpnPending = false
            // v1.1 free failover: the active free account
            // failed to connect — try the backup
            // automatically, once per tap. A fresh backup
            // is created silently afterwards.
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
        renderOrb()
        renderStatus()
    }

    private fun refreshList() {
        val dark = OvpnTheme.isDark(this)
        // Drop health state of deleted profiles.
        try {
            val names = profiles.map { it.name }.toSet()
            localDotViews.keys.retainAll(names)
            remoteDotViews.keys.retainAll(names)
            localHealth.keys.retainAll(names)
        } catch (_: Exception) {}
        // Country popup: rows are built once when opened;
        // later refreshes only repaint the circles in
        // place (never re-sort or rebuild while open).
        if (tbCountryMenuOpen && countryPopup?.isShowing == true) {
            for (c in tbCountryRowCircles.keys.toList()) refreshTbCountryRow(c)
        }
        renderCards(dark)
    }

    private fun renderCards(dark: Boolean) {
        val container = cardsContainer ?: return
        container.removeAllViews()
        val own = ownProfiles()
        emptyHint?.visibility = if (own.isEmpty()) View.VISIBLE else View.GONE
        val connected = OvpnEngine.isConnected || OvpnEngine.isConnecting
        fun circleBtn(glyph: String, sizeSp: Float, bold: Boolean, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = glyph
                textSize = sizeSp
                if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(OvpnTheme.subFg(this@OpenVpnActivity))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { onClick() }
            }
        }
        for ((index, p) in profiles.withIndex()) {
            // The free entry never renders as a normal row —
            // it lives on the Free tab's orb.
            if (p.tunnelBear) continue
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
                    setColor(if (isSel) Color.parseColor("#CC1E3A2F")
                        else if (isActive) Color.parseColor("#B32A3D5C") else Color.parseColor("#D2121A34"))
                    setStroke(((if (isSel) 1.5f else 1f) * resources.displayMetrics.density).toInt(),
                        if (isSel) OvpnTheme.accent(this@OpenVpnActivity)
                        else if (isActive) Color.parseColor("#26A69A") else Color.parseColor("#592DD4BF"))
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
                    selectedProfile = p.name
                    try {
                        getSharedPreferences("renamer_ovpn", MODE_PRIVATE).edit().putString("ovpn_last", p.name).apply()
                    } catch (_: Exception) {}
                    renderCards(OvpnTheme.isDark(this@OpenVpnActivity))
                    renderOrb()
                    renderStatus()
                }
            }
            // Two health dots, stacked (3.07 session probe).
            val trulyConnected = OvpnEngine.isConnected && OvpnEngine.activeName == p.name
            val dotsCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(10)
                }
            }
            val upperDot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (trulyConnected) Color.parseColor("#43A047") else dotColor(probeDots(p.name).first))
                }
                layoutParams = LinearLayout.LayoutParams(dp(9), dp(9))
            }
            val lowerDot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (trulyConnected) Color.parseColor("#43A047") else dotColor(probeDots(p.name).second))
                }
                layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                    topMargin = dp(4)
                }
            }
            dotsCol.addView(upperDot)
            dotsCol.addView(lowerDot)
            localDotViews[p.name] = upperDot
            remoteDotViews[p.name] = lowerDot
            dotsCol.setOnClickListener { showDotInfo(p.name) }
            row.addView(dotsCol)
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = L("پروفایل OpenVPN", "OpenVPN Profile")
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
                setOnClickListener { showNamePopup(row, p.name) }
            })
            info.setOnClickListener { editProfile(index) }
            row.addView(info)
            row.addView(circleBtn("ⓘ", 15f, false) { showDiagnosis(index) })
            row.addView(circleBtn("✎", 15f, false) { editProfile(index) })
            row.addView(TextView(this).apply {
                text = "✕"
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#E5484D"))
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { confirmDelete(index) }
            })
            container.addView(row)
        }
    }

    // ---------- country popup (Free tab, anchored to the chip) ----------
    // v1.1: a NARROW floating card just below the chip —
    // not full-screen, not a bottom sheet — scrollable,
    // flags + health circles per country, checkmark on
    // the selected one. Tapping a country selects it
    // and closes the popup.
    private fun toggleCountryPanel() {
        if (countryPopup?.isShowing == true) {
            try { countryPopup?.dismiss() } catch (_: Exception) {}
            countryPopup = null
            tbCountryMenuOpen = false
            return
        }
        showCountryPopup()
    }

    private fun showCountryPopup() {
        val anchor = countryChip ?: return
        val dark = OvpnTheme.isDark(this)
        tbCountryRowCircles.clear()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = OvpnTheme.glassCard(this@OpenVpnActivity, 18f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        panel.addView(TextView(this).apply {
            text = L("انتخاب کشور — ۴۷ کشور با پرچم", "Choose country — 47 countries with flags")
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.subFg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.START
            setPadding(dp(15), dp(11), dp(15), dp(5))
        })
        // Sorted ONCE at open from the probe cache + real-dial
        // outcomes (2-green first, then 1-green, then green+red,
        // then red); live probe results update the circles IN
        // PLACE and never re-sort while the list is open.
        val countries = sortedTbCountries()
        val country = TunnelBear.selectedCountry(this)
        val menuList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = rtlDir()
        }
        for (c in countries) {
            val isSel = c.equals(country, ignoreCase = true)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = rtlDir()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                isClickable = true
            }
            row.addView(TextView(this).apply {
                text = if (isSel) "✓ ${TunnelBear.displayCountry(c)}" else TunnelBear.displayCountry(c)
                textSize = 13.5f
                if (isSel) setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(if (isSel) Color.parseColor("#2E9E6B") else OvpnTheme.fg(this@OpenVpnActivity))
                textDirection = View.TEXT_DIRECTION_LTR
                gravity = Gravity.START
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(15), dp(10), dp(4), dp(10))
            })
            val circles = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(16)
                    marginEnd = dp(15)
                }
            }
            tbCountryRowCircles[c] = circles
            row.addView(circles)
            refreshTbCountryRow(c)
            row.setOnClickListener {
                try {
                    TunnelBear.saveSelectedCountry(this@OpenVpnActivity, c)
                    TunnelBear.ensureSingleEntry(this@OpenVpnActivity)
                    profiles = OvpnStore.load(this@OpenVpnActivity)
                } catch (_: Exception) {}
                try { countryPopup?.dismiss() } catch (_: Exception) {}
                countryPopup = null
                tbCountryMenuOpen = false
                refreshList()
                refreshStatus()
            }
            menuList.addView(row)
        }
        startTbCountryProbe()
        // Fixed-height scroll: ~5 rows visible, all 47
        // reachable.
        panel.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(280))
            isVerticalScrollBarEnabled = true
            addView(menuList)
        })
        val pw = android.widget.PopupWindow(panel, dp(240),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT, true)
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        // Centre the narrow popup under the chip.
        val xOff = (anchor.width - dp(240)) / 2
        pw.showAsDropDown(anchor, xOff, dp(8))
        pw.setOnDismissListener {
            tbCountryMenuOpen = false
            countryPopup = null
        }
        countryPopup = pw
        tbCountryMenuOpen = true
    }

    /** Sort tier for one country, from the SESSION probe when
     *  one exists (2nd green = AUTH + PUSH_REPLY with an IP),
     *  else the probe cache + last 7 days of REAL dials:
     *  0 = TLS green + session proven, 1 = TLS green, session
     *  untested, 2 = TLS green + session failed, 3 = TLS not green.
     *  Alphabetical inside a tier. */
    private fun tbCountryTier(c: String): Int {
        val host = tbCountryHosts.getOrPut(c) { TunnelBear.remoteHostForCountry(this, c) ?: "" }
        val session = if (host.isBlank()) null else TunnelBear.cachedSession(this, host)
        if (session != null) {
            if (session.tls != 2) return 3
            return when (session.session) {
                1 -> 0
                -1 -> 2
                else -> 1
            }
        }
        val probe = if (host.isBlank()) null else TunnelBear.cachedProbe(this, host)
        if (probe != 2) return 3
        return when (TunnelBear.dialOutcome(this, c)) {
            1 -> 0
            -1 -> 2
            else -> 1
        }
    }

    private fun sortedTbCountries(): List<String> {
        return TunnelBear.countries(this)
            .sortedWith(compareBy({ tbCountryTier(it) }, { it }))
    }

    /** Paints one row's circles IN PLACE from the SESSION
     *  probe when one exists (never re-sorts the open list).
     *  Green = server TLS-healthy, second green = AUTH accepted
     *  + PUSH_REPLY with an assigned IP, red = probe not
     *  TLS-healthy, or green + red = healthy now but the session
     *  failed; grey hollow = still testing / unknown. */
    private fun refreshTbCountryRow(country: String) {
        val circles = tbCountryRowCircles[country] ?: return
        val host = tbCountryHosts.getOrPut(country) { TunnelBear.remoteHostForCountry(this, country) ?: "" }
        val session = if (host.isBlank()) null else TunnelBear.cachedSession(this, host)
        val probe = if (host.isBlank()) null else TunnelBear.cachedProbe(this, host)
        val dial = TunnelBear.dialOutcome(this, country)
        fun dot(color: Int?, hollow: Boolean) {
            val v = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (hollow) {
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(1), Color.parseColor("#9E9E9E"))
                    } else {
                        setColor(color ?: Color.parseColor("#9E9E9E"))
                    }
                }
                layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
            }
            circles.addView(v)
        }
        circles.removeAllViews()
        val green = Color.parseColor("#43A047")
        val red = Color.parseColor("#D32F2F")
        when {
            session != null && session.tls == 2 && session.session == 1 -> { dot(green, false); dot(green, false) }
            session != null && session.tls == 2 && session.session == 0 -> dot(green, false)
            session != null && session.tls == 2 && session.session == -1 -> { dot(green, false); dot(red, false) }
            session != null -> dot(red, false)
            probe == 2 && dial == 1 -> { dot(green, false); dot(green, false) }
            probe == 2 && dial == 0 -> dot(green, false)
            probe == 2 && dial == -1 -> { dot(green, false); dot(red, false) }
            probe == null -> dot(null, true)
            else -> dot(red, false)
        }
    }

    /** Background re-probe of the stale / unknown countries on
     *  a bounded 8-thread pool — the SESSION probe (TLS + AUTH
     *  + PUSH), not just the TLS flight; each result is cached
     *  (10 min TTL) and its row's circles are updated live.
     *  Plain sockets only — never touches the VPN. */
    private fun startTbCountryProbe() {
        val gen = ++tbCountryProbeGen
        try { tbCountryProbePool?.shutdownNow() } catch (_: Exception) {}
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        tbCountryProbePool = pool
        for (c in tbCountryRowCircles.keys.toList()) {
            val host = tbCountryHosts[c] ?: continue
            if (host.isBlank()) continue
            if (TunnelBear.cachedSession(this, host) != null) continue
            pool.submit {
                try {
                    TunnelBear.probeCountrySession(this@OpenVpnActivity, host)
                    handler.post {
                        if (gen == tbCountryProbeGen && tbCountryMenuOpen) refreshTbCountryRow(c)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ---------- connect ----------
    private fun connectProfile(p: OvpnProfile) {
        // v1.1: a fresh user tap is a fresh failover
        // allowance (one backup try per tap).
        if (TunnelBear.isTunnelBear(p)) freeFailoverTried = false
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
                return
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
            return
        }
        connectDirect(p)
    }

    /** One DIRECT ovpn3 connect. No engine ladder, no chain:
     *  this app has no Smart engine, so OFF = the only mode. */
    private fun connectDirect(p: OvpnProfile) {
        AppLog.append(this, "openvpn",
            "تلاش اتصال «${logDisplayName(p.name)}»؛ اتصال مستقیم.",
            "Connect attempt “${logDisplayName(p.name)}”; direct connect.",
            "Попытка подключения «${logDisplayName(p.name)}»; прямое подключение.")
        appLogOvpnPending = true
        appLogOvpnPendingName = p.name
        OvpnEngine.connect(this, p) { runOnUiThread { refreshStatus(); refreshList() } }
    }

    private fun editProfile(index: Int) {
        OvpnStore.pendingIndex = index
        OvpnStore.pending = profiles[index].copy()
        startActivity(Intent(this, OpenVpnImportActivity::class.java))
    }

    private fun confirmDelete(index: Int) {
        val p = profiles[index]
        android.app.AlertDialog.Builder(this)
            .setTitle(L("حذف پروفایل", "Delete profile"))
            .setMessage(L("پروفایل «", "Profile \"") + p.name + L("» حذف شود؟", "\" will be deleted."))
            .setPositiveButton(L("حذف", "Delete")) { _, _ ->
                val wasActive = OvpnEngine.activeName == p.name
                // v1.1: free accounts are never deleted —
                // the free entry never renders as a row, so
                // this branch is unreachable for it.
                profiles.removeAt(index)
                OvpnStore.save(this, profiles)
                if (wasActive) OvpnEngine.disconnect()
                refreshList()
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show().also { OvpnTheme.styleDialog(it, this) }
    }

    // ---------- free-account auto-create (exactly as the main app) ----------
    private fun showTbWaitingDialog() {
        dismissTbWaitingDialog()
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
        tbWaitProgressView = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(4))
        }
        col.addView(tbWaitProgressView)
        tbWaitCountdownView = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(OvpnTheme.subFg(this@OpenVpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        col.addView(tbWaitCountdownView)
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
        try { tbWaitDialog?.dismiss() } catch (_: Exception) {}
        tbWaitDialog = null
        tbWaitDotsView = null
        tbWaitProgressView = null
        tbWaitCountdownView = null
    }

    /** v1.1 dual creation — ONLY behind the VPN gate:
     *  with no VPN active the option does nothing but
     *  a ~3-second message; there is no auto-Emergency
     *  here (this app has no Emergency at all). With a
     *  VPN on, ONE tap starts TWO background creations
     *  (two temp mails, two signups, two verifications,
     *  provider rotation intact). The waiting screen
     *  covers creation until the FIRST account is ready
     *  — then it connects immediately, and the second
     *  continues silently as the backup. Only if BOTH
     *  fail does the failure dialog appear. */
    private fun startTbAutoCreation() {
        if (tbCreating) return
        if (!anyVpnUp()) {
            android.widget.Toast.makeText(this,
                L("برای ساخت اکانت رایگان فیلترشکن خود را روشن کنید", "Turn on your VPN to create a free account"),
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        tbCreating = true
        try { AppLog.append(this, "freeovpn", "شروع ساخت حساب — VPN وصل است", "Account creation started — VPN is up") } catch (_: Exception) {}
        showTbWaitingDialog()
        renderOrb()
        renderStatus()
        DualFree.createDual(this, object : DualFree.DualCallback {
            override fun onProgress(pct: Int, secondsLeft: Int) {
                if (isFinishing || isDestroyed) return
                try { tbWaitProgressView?.progress = pct } catch (_: Exception) {}
                try {
                    tbWaitCountdownView?.text = T("حدود $secondsLeft ثانیه مانده", "About $secondsLeft s left", "Осталось около $secondsLeft с")
                } catch (_: Exception) {}
                try { orbPct?.text = "$pct٪" } catch (_: Exception) {}
            }
            override fun onFirstReady() {
                tbCreating = false
                dismissTbWaitingDialog()
                if (isFinishing || isDestroyed) return
                try { TunnelBear.ensureSingleEntry(this@OpenVpnActivity, restoreDeleted = true) } catch (_: Exception) {}
                try { profiles = OvpnStore.load(this@OpenVpnActivity) } catch (_: Exception) {}
                refreshList()
                refreshStatus()
                android.widget.Toast.makeText(this@OpenVpnActivity,
                    L("حساب ساخته و تایید شد؛ حالا می‌توانی وصل شوی", "Account created and verified; you can connect now"),
                    android.widget.Toast.LENGTH_LONG).show()
                // Connect to the first account as soon as
                // it is ready.
                val p = tbProfile()
                if (p != null) connectProfile(p)
            }
            override fun onBackupReady() {
                // Silent: nothing shown, nothing logged
                // on the page. The backup just exists.
            }
            override fun onBothFailed(fa: String, en: String) {
                tbCreating = false
                dismissTbWaitingDialog()
                if (isFinishing || isDestroyed) return
                refreshList()
                refreshStatus()
                showTbAutoFailDialog(fa, en)
            }
        })
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
    private fun showTbAutoFailDialog(fa: String, en: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle(L("ساخت حساب ناموفق بود", "Account creation failed"))
            .setMessage(L(fa, en))
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

    /** OpenVPN guide (v1.1): drawer, tap-to-select,
     *  dual free accounts, language menu. (No Smart
     *  engine section — this app has none; the free
     *  section describes the VPN gate, not the old
     *  auto-Emergency.) */
    private fun showOvpnGuide() {
        try {
            fun sec(faT: String, faB: String, enT: String, enB: String, ruT: String, ruB: String): Pair<String, String> {
                return when (AppLanguage.lang(this)) {
                    "en" -> enT to enB
                    "ru" -> ruT to ruB
                    else -> faT to faB
                }
            }
            val sections = listOf(
                sec("کشوی کناری",
                    "دکمه ☰ بالای صفحه، سمت چپ، کشوی کناری را باز می‌کند؛ با کشیدن انگشت از چپ به راست هم باز می‌شود و با کشیدن از راست به چپ یا زدن بیرون از منو بسته می‌شود. داخل کشو: لوگوی کانال، بعد «لاگ برنامه» (متن کامل لاگ‌ها)، «راهنما» (همین صفحه)، «حالت شب/روز» (با هر بار زدن بین روز، شب و خودکار می‌چرخد)، «زبان برنامه» (فارسی، English، Русский — کل برنامه همان لحظه عوض می‌شود)، و «درباره ما».",
                    "Side drawer",
                    "The ☰ button at the top-left opens the side drawer; swiping left-to-right opens it too, and swiping right-to-left or tapping outside closes it. Inside: the channel logo, then “App log” (the full log text), “Guide” (this page), “Day / night” (each tap cycles day, night, auto), “App language” (فارسی, English, Русский — the whole app switches at once), and “About”.",
                    "Боковое меню",
                    "Кнопка ☰ вверху слева открывает боковое меню; свайп слева направо тоже открывает, а свайп справа налево или касание вне меню закрывает его. Внутри: логотип канала, затем «Журнал» (полный текст журнала), «Руководство» (эта страница), «День / ночь» (каждое касание переключает день, ночь, авто), «Язык приложения» (فارسی, English, Русский — всё приложение переключается сразу) и «О приложении»."),
                sec("دو زبانه",
                    "بالای صفحه دو زبانه هست: «Free open vpn» برای حساب رایگان، و «Open vpn» برای پروفایل‌های شخصی خودت. دایره وسط صفحه در هر زبانه به همان بخش وصل می‌شود.",
                    "Two tabs",
                    "There are two tabs at the top: “Free open vpn” for the free account, and “Open vpn” for your own personal profiles. The circle in the middle connects to that tab's section.",
                    "Две вкладки",
                    "Вверху две вкладки: «Free open vpn» для бесплатного аккаунта и «Open vpn» для ваших личных профилей. Круг в середине подключается к разделу своей вкладки."),
                sec("دایره اتصال",
                    "دایره وسط صفحه دکمه اتصال است. در زبانه Free، اگر هنوز حسابی نداری، روی آن بزن تا برنامه خودش در پس‌زمینه برایت حساب بسازد و تاییدش کند؛ فقط کمی صبر کن (حدود یک دقیقه). وقتی حساب آماده شد، زیر نوشته «اتصال» داخل خود دایره دکمه کشور را می‌بینی — تا وقتی کشوری انتخاب نکرده‌ای «انتخاب کشور» می‌نویسد، و وقتی کشوری انتخاب کنی پرچم و اسم همان کشور را نشان می‌دهد؛ با زدن روی آن، یک کارت کوچک زیر همان دکمه باز می‌شود با فهرست ۴۷ کشور با پرچم. در زبانه Open، اول روی یک پروفایل بزن تا انتخاب شود (پس‌زمینه‌اش سبز روشن می‌شود)، بعد دایره به همان پروفایل وصل می‌شود؛ اگر پروفایلی انتخاب نشده باشد، دایره کاری نمی‌کند. زیر دایره هم همیشه وضعیت را می‌بینی.",
                    "Connect circle",
                    "The circle in the middle is the connect button. On the Free tab, if you have no account yet, tap it and the app creates and verifies one for you in the background; just wait a little (about a minute). Once the account is ready, you see the country button below “Connect” inside the circle itself — it reads “Choose country” until you pick one, and shows the flag and name of the picked country after that; tapping it opens a small card right under the button with the list of 47 countries with flags. On the Open tab, first tap a profile to select it (its background turns light green), then the circle connects to that profile; if no profile is selected, the circle does nothing. Below the circle you always see the status.",
                    "Круг подключения",
                    "Круг в середине — кнопка подключения. На вкладке Free, если аккаунта ещё нет, нажмите на него, и приложение само создаст и подтвердит его в фоне; просто немного подождите (около минуты). Когда аккаунт готов, под словом «Подключить» внутри круга видна кнопка страны — пока страна не выбрана, там написано «Выбрать страну», а после выбора показываются флаг и название выбранной страны; касание открывает небольшую карточку прямо под кнопкой со списком 47 стран с флагами. На вкладке Open сначала нажмите на профиль, чтобы выбрать его (фон станет светло-зелёным), затем круг подключится к нему; если профиль не выбран, круг ничего не делает. Под кругом всегда виден статус."),
                sec("دایره‌های سلامت",
                    "دو دایره کنار هر پروفایل، نتیجه‌ی تست نشست همان پروفایل را نشان می‌دهند: دایره اول سبز یعنی سرور با دست‌تکان کامل TLS جواب داد؛ دایره دوم سبز یعنی نام کاربری و رمز قبول شد و سرور یک آدرس IP داد — روی همین پروفایل بزنی وصل می‌شود. خاکستری یعنی هنوز تست نشده یا سرور از روی طراحی به تست جواب نمی‌دهد (tls-auth)؛ قرمز یعنی سرور جواب نداد یا نشست رد شد. با لمس دایره‌ها، توضیح هر کدام را می‌بینی.",
                    "Health dots",
                    "The two dots beside each profile show that profile's session test: first green means the server answered a full TLS handshake; second green means the username and password were accepted and the server assigned an IP — tap this profile and it will connect. Grey means not tested yet, or the server stays silent by design (tls-auth); red means the server did not answer or the session was rejected. Tap the dots to see what each one means.",
                    "Точки состояния",
                    "Две точки рядом с профилем показывают результат теста сессии: первая зелёная — сервер ответил полным TLS-рукопожатием; вторая зелёная — имя пользователя и пароль приняты и сервер выдал IP: нажмите на этот профиль, и он подключится. Серая — ещё не проверено или сервер молчит по замыслу (tls-auth); красная — сервер не ответил или сессия отклонена. Коснитесь точек, чтобы увидеть пояснение."),
                sec("دو حساب رایگان",
                    "با یک بار زدن روی دایره در زبانه Free، برنامه در پس‌زمینه دو حساب رایگان می‌سازد: اولی که آماده شد وصل می‌شود و دومی حساب پشتیبان است. اگر حساب فعال وصل نشد، برنامه خودش سراغ حساب پشتیبان می‌رود و یک پشتیبان تازه می‌سازد. هر حساب ماهانه ۲ گیگابایت دارد؛ وقتی مصرف حساب فعال به حدود ۱۹۰۰ مگابایت در ۳۱ روز رسید، برنامه خودش به حساب پشتیبان وصل می‌شود (حداکثر یک قطع لحظه‌ای) و پشتیبان تازه می‌سازد. حساب‌های رایگان هیچ‌وقت حذف نمی‌شوند؛ حسابی که تمام شد استراحت می‌کند و بعد از ۳۱ روز دوباره شارژ می‌شود و می‌تواند پشتیبان شود. هر دو حساب برای همان کشوری ساخته می‌شوند که انتخاب کرده‌ای.",
                    "Two free accounts",
                    "One tap on the circle in the Free tab creates TWO free accounts in the background: the first one ready connects, and the second is the backup. If the active account fails to connect, the app switches to the backup by itself and creates a fresh backup. Each account has 2 GB per month; when the active account reaches about 1900 MB in 31 days, the app switches to the backup itself (at most a momentary cut) and creates a fresh backup. Free accounts are never deleted; a used-up account rests, and after 31 days it recharges and can become a backup again. Both accounts are created for the country you picked.",
                    "Два бесплатных аккаунта",
                    "Одно касание круга на вкладке Free создаёт в фоне ДВА бесплатных аккаунта: первый готовый подключается, а второй становится резервным. Если активный аккаунт не подключается, приложение само переходит на резервный и создаёт новый резервный. У каждого аккаунта 2 ГБ в месяц; когда активный достигает около 1900 МБ за 31 день, приложение само переключается на резервный (максимум мгновенный обрыв) и создаёт новый резервный. Бесплатные аккаунты никогда не удаляются; исчерпанный аккаунт отдыхает, а через 31 день пополняется и снова может стать резервным. Оба аккаунта создаются для выбранной вами страны."),
                sec("Free OpenVpn config",
                    "در زبانه Free یک حساب رایگان برایت ساخته می‌شود. برای ساخت حساب، اول فیلترشکن خودت را روشن کن؛ اگر هیچ VPNای وصل نباشد، برنامه فقط یک پیام کوتاه نشان می‌دهد و کاری نمی‌کند. اگر ساخت حساب نشد، برنامه خودش با ایمیل تازه دوباره تلاش می‌کند و فقط بعد از آن پیامش را می‌بینی و می‌توانی «تلاش دوباره» بزنی؛ ورود دستی وجود ندارد، همه‌چیز خودکار است. وقتی حساب آماده شد، کشورش را از داخل دایره عوض می‌کنی (۴۷ کشور با پرچم). اسم و رمز حساب هیچ‌وقت نشان داده نمی‌شود. وصل شدن از راه یک رله داخلی انجام می‌شود: هسته مستقیم به سرور وصل نمی‌شود، چون از این خط دست‌تکان همان اول قطع می‌شود؛ سرورها اول با دست‌تکان کامل TLS روی پورت ۷۰۱۱ تست می‌شوند تا سراغ آن‌هایی برود که واقعاً جواب می‌دهند.",
                    "Free OpenVpn config",
                    "On the Free tab a free account is created for you. To create an account, first turn on your own VPN; if no VPN is connected, the app only shows a short message and does nothing. If creating the account fails, the app retries by itself with a fresh email and only then shows the message, with a “Try again” button; there is no manual entry — everything is automatic. Once the account is ready, you change its country from inside the circle (47 countries with flags). The account's name and password are never shown. Connecting goes through an internal relay: the core never dials the server directly, because on this line the handshake is cut right at the start; servers are first tested with a full TLS handshake on port 7011 so the ones that really answer are tried first.",
                    "Free OpenVpn config",
                    "На вкладке Free для вас создаётся бесплатный аккаунт. Чтобы создать аккаунт, сначала включите свой VPN; если VPN не подключён, приложение только покажет короткое сообщение и ничего не сделает. Если создать аккаунт не удалось, приложение само попробует снова с новым адресом и только потом покажет сообщение с кнопкой «Попробовать снова»; ручного ввода нет — всё автоматически. Когда аккаунт готов, страну можно сменить внутри круга (47 стран с флагами). Имя и пароль аккаунта никогда не показываются. Подключение идёт через внутреннее реле: ядро никогда не звонит серверу напрямую, потому что на этой линии рукопожатие обрывается в самом начале; серверы сначала проверяются полным TLS-рукопожатием на порту 7011, чтобы пробовать те, что действительно отвечают."),
                sec("دکمه +",
                    "وارد کردن فایل .ovpn. هر فایل یک پروفایل می‌شود و اسمش از اسم فایل می‌آید. دکمه بالای نوار پایین گوشی می‌ایستد و هیچ‌وقت زیر آن پنهان نمی‌شود.",
                    "Add button",
                    "Import an .ovpn file. Each file becomes one profile, named after the file. The button sits above the phone's bottom bar and is never hidden under it.",
                    "Кнопка +",
                    "Импорт файла .ovpn. Каждый файл становится профилем с именем файла. Кнопка находится над нижней панелью телефона и никогда не скрывается под ней."),
                sec("علامت ⓘ (تشخیص)",
                    "تشخیص فایل؛ فقط می‌گوید فایل چه بخش‌ها و گزینه‌هایی دارد و هیچ مقداری را نشان نمی‌دهد.",
                    "Diagnosis info",
                    "File diagnosis; it only says which blocks and options the file has and never shows any values.",
                    "Диагностика",
                    "Диагностика файла: только какие блоки и опции в нём есть, без значений."),
                sec("مداد ✎ و لمس اسم",
                    "مداد پروفایل را ویرایش می‌کند. لمس اسم پروفایل، اسم کامل را در پنجره کوچکی نشان می‌دهد.",
                    "Edit and name tap",
                    "The pencil edits the profile. Tapping the profile name shows the full name in a small popup.",
                    "Карандаш и имя",
                    "Карандаш редактирует профиль. Касание имени показывает полное имя в маленьком окне."),
                sec("علامت ✕ (حذف)",
                    "حذف با پنجره تأیید انجام می‌شود، هیچ‌وقت فوری نیست؛ حذف پروفایلی که وصل است، اتصال را قطع می‌کند. حساب‌های رایگان حذف نمی‌شوند.",
                    "Delete",
                    "Deletion asks for confirmation and is never instant; deleting the connected profile disconnects it. Free accounts are never deleted.",
                    "Удаление",
                    "Удаление — с подтверждением, никогда не мгновенное; удаление подключённого профиля отключает его. Бесплатные аккаунты не удаляются.")
            )
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(20))
            }
            box.addView(TextView(this).apply {
                text = L("راهنمای OpenVPN", "OpenVPN guide")
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
                setPadding(0, 0, 0, dp(14))
            })
            for ((t, b) in sections) {
                box.addView(TextView(this).apply {
                    text = t; textSize = 15f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(if (OvpnTheme.isDark(this@OpenVpnActivity)) Color.parseColor("#A5C8FF") else Color.parseColor("#1B365D"))
                    setPadding(0, dp(8), 0, dp(2))
                })
                box.addView(TextView(this).apply {
                    text = b; textSize = 13f
                    setTextColor(if (OvpnTheme.isDark(this@OpenVpnActivity)) Color.parseColor("#D8DEEA") else Color.parseColor("#37474F"))
                    setPadding(0, 0, 0, dp(6))
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
                lp.width = dp(340)
                lp.height = dp(560)
                win.attributes = lp
            }
            dlg.show()
        } catch (_: Exception) {}
    }

    // ---------- health dots ----------
    private fun dotColor(state: Int): Int = when (state) {
        1 -> Color.parseColor("#43A047")
        -1 -> Color.parseColor("#E57373")
        else -> Color.parseColor("#BDBDBD")
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

    private fun dialDotState(name: String): Int {
        return try {
            val p = getSharedPreferences("renamer_ovpn", MODE_PRIVATE)
            if (!p.contains("ovpn_dial_time_$name")) 0
            else if (p.getBoolean("ovpn_dial_ok_$name", false)) 1 else -1
        } catch (_: Exception) { 0 }
    }

    /** The two dots from the SESSION probe (3.07).
     *  UPPER: the TLS flight — 1 green = the server answered a
     *  full TLS handshake, -1 red = TCP answered but no TLS
     *  flight, or TCP dead, 0 grey = untested, or silent by
     *  design (tls-auth/tls-crypt, UDP silent).
     *  LOWER: the session — 1 green = AUTH accepted + PUSH_REPLY
     *  with an assigned IP (will connect), -1 red = AUTH_FAILED /
     *  no PUSH / TLS cut after the flight, 0 grey = untested,
     *  no stored credentials, or TLS-probe-only class.
     *  Until a profile has a fresh (10-min) probe cache, the dots
     *  fall back to the in-memory sweep result (UDP profiles) and
     *  then to the last real dial. */
    private fun probeDots(name: String): Pair<Int, Int> {
        val v = OvpnSessionProbe.cachedProbe(this, name)
        if (v != null) {
            val upper = when {
                v.tls == 2 -> 1
                v.tls == 1 -> -1
                v.detail == "tcp_dead" -> -1
                else -> 0
            }
            val lower = when (v.session) { 1 -> 1; -1 -> -1; else -> 0 }
            return upper to lower
        }
        val lh = localHealth[name]
        if (lh != null) return lh to 0
        val d = dialDotState(name)
        return d to d
    }

    /** Human text for a probe verdict (dot-tap popup). No hosts,
     *  IPs or ports — only what each dot means and why it shows
     *  its colour. */
    private fun probeLineText(name: String): String {
        val v = OvpnSessionProbe.cachedProbe(this, name)
            ?: return L("هنوز تست نشده است.", "Not tested yet.")
        val upper = when {
            v.tls == 2 -> L("دایره اول سبز: سرور با دست‌تکان کامل TLS جواب داد.", "First circle green: the server answered a full TLS handshake.")
            v.tls == 1 -> L("دایره اول قرمز: سرور TCP را جواب داد ولی دست‌تکان TLS را قطع کرد.", "First circle red: the server answered TCP but cut the TLS handshake.")
            v.detail == "tcp_dead" -> L("دایره اول قرمز: سرور اصلاً جواب نداد.", "First circle red: the server did not answer at all.")
            v.detail == "tls_auth_silent" -> L("دایره اول خاکستری: این پروفایل tls-auth یا tls-crypt دارد و سرورش از روی طراحی به تست جواب نمی‌دهد؛ فقط با زدن دکمه اتصال معلوم می‌شود.", "First circle grey: this profile uses tls-auth or tls-crypt, and its server stays silent by design; only tapping connect gives the answer.")
            v.detail == "udp_only" -> L("دایره اول از تست UDP می‌آید (این پروفایل فقط UDP است).", "The first circle comes from the UDP test (this profile is UDP-only).")
            else -> L("دایره اول خاکستری: هنوز تست نشده است.", "First circle grey: not tested yet.")
        }
        val lower = when (v.session) {
            1 -> L("دایره دوم سبز: نام کاربری و رمز قبول شد و سرور یک آدرس IP داد — وصل می‌شود.", "Second circle green: the username and password were accepted and the server assigned an IP — it will connect.")
            -1 -> when (v.detail) {
                "auth_failed" -> L("دایره دوم قرمز: سرور نام کاربری یا رمز را رد کرد.", "Second circle red: the server rejected the username or password.")
                "no_push" -> L("دایره دوم قرمز: سرور بعد از قبول کردن، آدرسی نفرستاد.", "Second circle red: the server accepted but never sent an address.")
                "no_keyex" -> L("دایره دوم قرمز: سرور تبادل کلید را کامل نکرد.", "Second circle red: the server did not finish the key exchange.")
                else -> L("دایره دوم قرمز: نشست کامل نشد.", "Second circle red: the session did not complete.")
            }
            else -> if (v.detail == "no_creds") L("دایره دوم خاکستری: این پروفایل نام کاربری و رمز می‌خواهد ولی ذخیره نشده است؛ اول آن‌ها را در ویرایش پروفایل بنویس.", "Second circle grey: this profile needs a username and password, but none is saved; enter them in the profile edit first.")
                else L("دایره دوم خاکستری: نشست هنوز تست نشده است.", "Second circle grey: the session has not been tested yet.")
        }
        return "$upper\n$lower"
    }

    private fun dialLineText(name: String): Pair<String, String> {
        try {
            val p = getSharedPreferences("renamer_ovpn", MODE_PRIVATE)
            if (!p.contains("ovpn_dial_time_$name")) {
                return L("هنوز به‌صورت مستقیم وصل نشده", "Never connected directly") to "#9E9E9E"
            }
            val ok = p.getBoolean("ovpn_dial_ok_$name", false)
            val t = p.getLong("ovpn_dial_time_$name", 0)
            val cal = java.util.Calendar.getInstance()
            val now = java.util.Calendar.getInstance()
            cal.timeInMillis = t
            val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(t))
            val today = cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
            val whenStr = when (AppLanguage.lang(this)) {
                "en" -> if (today) "today, $hm"
                    else "${cal.get(java.util.Calendar.MONTH) + 1}/${cal.get(java.util.Calendar.DAY_OF_MONTH)} $hm"
                "ru" -> if (today) "сегодня, $hm"
                    else "${cal.get(java.util.Calendar.DAY_OF_MONTH)}.${cal.get(java.util.Calendar.MONTH) + 1} $hm"
                else -> if (today) "امروز، $hm"
                    else "${cal.get(java.util.Calendar.DAY_OF_MONTH)}/${cal.get(java.util.Calendar.MONTH) + 1} $hm"
            }
            return if (ok) {
                T("آخرین وصل واقعی: وصل شد ($whenStr)", "Last real connect: succeeded ($whenStr)", "Последнее реальное подключение: удалось ($whenStr)") to "#2E7D32"
            } else {
                T("آخرین وصل واقعی: ناموفق ($whenStr)", "Last real connect: failed ($whenStr)", "Последнее реальное подключение: не удалось ($whenStr)") to "#C62828"
            }
        } catch (_: Exception) {
            return L("هنوز به‌صورت مستقیم وصل نشده", "Never connected directly") to "#9E9E9E"
        }
    }

    /** Repaints both dots of every row from the session probe.
     *  A connected profile is green on both by definition. */
    private fun paintDots() {
        try {
            for ((name, v) in localDotViews) {
                val c = if (OvpnEngine.isConnected && OvpnEngine.activeName == name)
                    Color.parseColor("#43A047") else dotColor(probeDots(name).first)
                (v.background as? GradientDrawable)?.setColor(c)
            }
            for ((name, v) in remoteDotViews) {
                val c = if (OvpnEngine.isConnected && OvpnEngine.activeName == name)
                    Color.parseColor("#43A047") else dotColor(probeDots(name).second)
                (v.background as? GradientDrawable)?.setColor(c)
            }
        } catch (_: Exception) {}
    }

    /** Tap the dots: plain-language reasons for both dots.
     *  No hosts, IPs or ports. */
    private fun showDotInfo(name: String) {
        try {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(20))
            }
            fun addLine(title: String, body: String) {
                box.addView(TextView(this).apply {
                    text = title; textSize = 14f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
                })
                box.addView(TextView(this).apply {
                    text = body; textSize = 13f
                    setTextColor(OvpnTheme.subFg(this@OpenVpnActivity))
                    setPadding(0, 0, 0, dp(12))
                })
            }
            val connectedNow = OvpnEngine.isConnected && OvpnEngine.activeName == name
            val (dtxt, _) = dialLineText(name)
            val derr = try { getSharedPreferences("renamer_ovpn", MODE_PRIVATE).getString("ovpn_dial_err_$name", null) } catch (_: Exception) { null }
            val dbody = if (!derr.isNullOrBlank() && dtxt.contains(L("ناموفق", "failed"))) "$dtxt؛ علت: $derr" else dtxt
            box.addView(TextView(this).apply {
                text = name; textSize = 17f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(OvpnTheme.fg(this@OpenVpnActivity))
                setPadding(0, 0, 0, dp(12))
            })
            addLine(L("دو دایره — تست نشست", "Both dots — session test"),
                probeLineText(name))
            addLine(L("آخرین وصل واقعی", "Last real connect"),
                if (connectedNow) L("وصل است", "Connected") else dbody)
            box.addView(TextView(this).apply {
                text = L("سبز اول یعنی سرور با دست‌تکان کامل TLS جواب داده؛ سبز دوم یعنی نام کاربری و رمز قبول شد و سرور آدرس داد — روی همین پروفایل بزنی وصل می‌شود. خاکستری یعنی هنوز تست نشده یا سرور از روی طراحی به تست جواب نمی‌دهد (tls-auth). قرمز یعنی سرور جواب نداد یا نشست رد شد.",
                    "First green means the server answered a full TLS handshake; second green means the username and password were accepted and the server assigned an address — tap this profile and it will connect. Grey means not tested yet, or the server stays silent by design (tls-auth). Red means the server did not answer or the session was rejected.")
                textSize = 12f
                setTextColor(OvpnTheme.subFg(this@OpenVpnActivity))
            })
            val dlg = android.app.Dialog(this).apply {
                requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                setContentView(box)
                setCanceledOnTouchOutside(true)
            }
            val win = dlg.window
            if (win != null) {
                win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                box.background = OvpnTheme.dialogCard(this)
            }
            dlg.show()
        } catch (_: Exception) {}
    }

    // ---------- page-entry health sweep (phase 1 only: session probe) ----------
    /** Phase 1 fills the dots from the SESSION probe: TCP
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
                                runOnUiThread { paintDots() }
                                return@submit
                            }
                            if (anyVpnUp()) {
                                runOnUiThread { paintDots() }
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
                            runOnUiThread { paintDots() }
                        } catch (_: Exception) {} finally { done.countDown() }
                    }
                }
                done.await(30, java.util.concurrent.TimeUnit.SECONDS)
                try { pool.shutdownNow() } catch (_: Exception) {}
            } catch (_: Exception) {}
        }.start()
    }

    /** LIVE "is any tunnel up" check. Engine volatile flags plus
     *  the active network's TRANSPORT_VPN capability are live for
     *  every backend. */
    private fun anyVpnUp(): Boolean {
        return try {
            if (OvpnEngine.isConnected || OvpnEngine.isConnecting) return true
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = try { cm?.getNetworkCapabilities(cm.activeNetwork) } catch (_: Exception) { null }
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
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

    /** Local dot verdict for one profile: green if ANY remote
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
