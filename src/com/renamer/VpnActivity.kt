package com.renamer

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import go.Seq
import libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * A v2rayNG-style main page, hidden mode only:
 * a server list (name, protocol, address, ping, selected
 * marker), bulk add (paste many configs at once, or from
 * clipboard), per-server ping exactly like v2rayNG, and a
 * connect button that actually routes the device through
 * the selected server via VpnService + the Xray core
 * (TUN mode, like v2rayNG's VPN mode).
 */
class VpnActivity : Activity() {
    /** The old top status line is gone at the user's
     *  word: the top of the page must not say
     *  connected/disconnected — the FAB itself shows
     *  that. This sink keeps every existing
     *  `statusText.text = ...` assignment compiling:
     *  the message now lands on the single-line glass
     *  strip next to the FAB, where the old 2-second
     *  poller can no longer wipe it. It is NOT added
     *  to the history; only addStatusLine() writes
     *  history. */
    private val statusText = object {
        var text: String = ""
            set(v) { field = v; if (v.isNotBlank()) setStripLine(v) }
    }
    // 1.29: the FAB is the OpenVPN orb (VpnOrbButtonView):
    // a sapphire gradient orb with a thin ring that
    // rotates 1 rev / 14 s. 2.88: the glyph is the white
    // bold connect-mode letter (N = Normal, B = Best-ping)
    // instead of the ⏻ glyph; the home button keeps ⏻.
    // 1.38: the IDLE colour is the selected mode's own
    // colour — gold in normal mode, blue in best-ping
    // mode (the old red idle is gone); connecting stays
    // amber, connected stays green (see refreshFab).
    private lateinit var connectBtn: VpnOrbButtonView
    private lateinit var listContainer: LinearLayout
    private lateinit var tabBar: LinearLayout
    // 2.92: tabScroll is a field now — refreshTabBar() re-applies
    // the RTL direction + scrolls to the start edge on every
    // rebuild, and it needs the scroll view in scope.
    private lateinit var tabScroll: android.widget.HorizontalScrollView
    private lateinit var addBtn: Button
    private lateinit var menuBtn: Button
    // 1.30: «دریافت کانفیگ رایگان» button; 1.33: below the tabs.
    // 1.41 (A): the LEFT ~2/3 of the combined free box
    // (see the creation block) — a TextView, not a
    // Button. The 1.39 minimumWidth pinning is gone:
    // the box's weights keep the widths stable when
    // the label swaps to «لغو دریافت» while fetching.
    private lateinit var freeBtn: android.widget.TextView
    private var freeFetchRunning = false
    @Volatile private var freeFetchCancelled = false
    // 1.44 (E): which free-fetch rule this run uses —
    // picked in the options dialog on EVERY tap of
    // «دریافت کانفیگ رایگان»; the choice applies to
    // that fetch only (not persisted).
    private enum class FreeFetchMode { UNDER_200, ANY_GREEN }
    private var freeFetchMode = FreeFetchMode.UNDER_200
    // 1.44 (F): live fetch percentage, 0..100 — the
    // waiting line shows «کمی صبر کنید ... ٪X» with
    // this number on the same line. Progress callbacks
    // (fetch / pre-check / ping) move it; the ticker
    // below re-renders the line every second with the
    // current value.
    private var freeFetchPct = 0
    // 1.40 (B): the waiting line under the free
    // button while a fetch runs — black, 11sp.
    // 1.44 (F): the animated dots are gone — the line
    // now reads «کمی صبر کنید ... ٪X» with the live
    // percentage on the same line (mockup frame 5).
    private var freeWaitView: android.widget.TextView? = null
    private val freeWaitTicker = object : Runnable {
        override fun run() {
            if (!freeFetchRunning) return
            freeWaitView?.text = freeWaitText()
            handler.postDelayed(this, 1000)
        }
    }

    /** 1.44 (F): the waiting-line text — the live
     *  percentage on the SAME line as «کمی صبر
     *  کنید», Persian digits, exactly as mocked. */
    private fun freeWaitText(): String =
        "کمی صبر کنید ... ٪" + faNum(freeFetchPct)

    /** 1.44 (F): move the percentage (0..100) and
     *  repaint the waiting line at once. Called from
     *  the worker thread — the text update posts to
     *  the UI thread. */
    private fun updateFreeWaitPct(pct: Int) {
        freeFetchPct = pct.coerceIn(0, 100)
        runOnUiThread { try { freeWaitView?.text = freeWaitText() } catch (_: Exception) {} }
    }

    /** 1.44 (F): map one pipeline progress report to
     *  a percentage. The sub fetch covers 0..10, the
     *  TCP pre-check 10..20, the ping test 20..100 —
     *  monotonic through the run, 100 when the ping
     *  stage has dispositioned every survivor. A zero
     *  total means nothing to test, so the run is
     *  already done: 100. */
    private fun onFreeFetchProgress(phase: Int, processed: Int, total: Int) {
        val pct = when {
            total <= 0 -> 100
            phase == 0 -> 10 + (10 * processed / total)
            else -> 20 + (80 * processed / total)
        }
        updateFreeWaitPct(pct)
    }
    private var dragFrom = -1
    // "" = All configs (the default tab); otherwise a
    // sub URL, or a "GROUP::..." header when the
    // show-all-groups setting adds group tabs.
    private var selectedTab = ""
    // Previous /proc/net/dev tun totals, for the
    // live speed shown while connected.
    private var speedPrev: Pair<Long, Long>? = null
    private lateinit var scrollView: ScrollView
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerScrim: View
    private lateinit var hintText: TextView
    private lateinit var modeDim: View
    // 2.87: dim layer for the + / ⋮ popup menus — the page
    // behind a menu MUST be dimmed in both modes (user
    // order); tapping it dismisses the open menu.
    private lateinit var menuDim: View
    private var activeMenuPopup: android.widget.PopupWindow? = null
    private lateinit var modeRow: LinearLayout
    // 1.41 (A): the combined free box — ONE box the
    // size of a config card, sapphire texture. Left
    // ~2/3 = freeBtn (fetch / cancel); right ~1/3 =
    // the guide part («راهنمای اتصال», gently
    // blinking), separated by a 1dp divider. The
    // guide part is GONE for good once the first
    // fetch adds ≥1 config (persisted
    // v2ray_first_free_fetched); the old separate
    // guide row (1.36) is removed.
    private var freeBox: LinearLayout? = null
    private var freeGuidePart: LinearLayout? = null
    private var freeGuideDivider: View? = null
    private var freeGuidePulseAnim: android.animation.ValueAnimator? = null
    // 1.36: interactive spotlight tutorial for the
    // v2ray free flow (replaces the static 5-step
    // dialog). 0 = off, 1 = tap the free button,
    // 2 = waiting (fetch + ping test), 3 = pick one
    // of the 10 configs, 4 = tap the connect orb.
    private var v2rayTutStep = 0
    private var v2rayTutOverlay: android.widget.FrameLayout? = null
    private var v2rayTutDim: GuideOverlayView? = null
    private var v2rayTutBubble: TextView? = null
    // Hole rects in SCREEN coordinates, for the
    // overlay's touch pass-through test.
    private var v2rayTutHolesScreen: List<android.graphics.RectF> = emptyList()
    private lateinit var frameView: android.widget.FrameLayout
    private var selectedRowView: View? = null
    // Geo flag overlay: a temporary TextView shown on the
    // root frame for exactly 3 seconds when a flag circle
    // is tapped (see showGeoMessage).
    private var geoOverlay: TextView? = null
    private val geoOverlayRemover = Runnable {
        geoOverlay?.let { frameView.removeView(it) }
        geoOverlay = null
    }
    // Connect mode: "" (not chosen yet), "normal" or "best".
    private var fabLongPressed = false
    private var fabDownX = 0f
    private var fabDownY = 0f
    private val fabLongPressRunnable = Runnable { onFabLongPress() }
    private var drawerDownX = 0f

    private fun connectMode(): String {
        return prefs().getString("connect_mode", "") ?: ""
    }
    private fun modeDone(): Boolean {
        return prefs().getBoolean("connect_mode_done", false)
    }

    // ---------- 2.86: v2ray page theme ----------
    // The page follows the home day/night theme (Theme.isDark
    // — the same check as the home page). Night = the shared
    // dark aurora page background, cards a bit LIGHTER glass
    // than the mockup and than the 2.84 shared night card
    // (#CC2A3048), thin teal→purple gradient borders; day =
    // pearl aurora, white glass cards, soft lavender borders.
    // pageDark is fixed at onCreate; a theme change while the
    // page is away recreates it in onResume.
    private var pageDark = false

    /** Rounded glass with a gradient border: teal→purple in
     *  night (the 2.83 home-tile gradient), soft lavender in
     *  day. `glow` leaves a halo ring between the fill and the
     *  view edge — a real shadow layer does not render under
     *  hardware acceleration, so the halo is layered strokes
     *  drawn inside the bounds. `borderAlpha` lets the FAB
     *  blink dim the ring. `solidBorder` overrides the gradient
     *  (the green selected border). A huge radius draws a
     *  circle (FAB, mode circles, round buttons). */
    private class GradientGlassDrawable(
        private val fill: Int,
        private val night: Boolean,
        private val radiusPx: Float,
        private val strokePx: Float,
        private val glow: Boolean = false,
        private val solidBorder: Int? = null,
        private val borderColors: IntArray? = null,
        private val borderAlpha: Int = 255,
        // 1.41 (A): an optional linear-gradient fill
        // (top-left → bottom-right) for the free rows'
        // paler sapphire — when set it replaces the
        // solid fill colour.
        private val fillColors: IntArray? = null
    ) : android.graphics.drawable.Drawable() {
        private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = fill; style = android.graphics.Paint.Style.FILL
        }
        private val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
        }
        private val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
        }

        private fun borderShader(b: android.graphics.Rect): android.graphics.Shader {
            val colors = borderColors ?: if (night)
                intArrayOf(Theme.darken(Color.parseColor("#2DD4BF")), Theme.darken(Color.parseColor("#A78BFA")))
            else
                intArrayOf(Color.parseColor("#C9B8F0"), Color.parseColor("#EFEAFB"))
            return android.graphics.LinearGradient(
                b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                colors, null, android.graphics.Shader.TileMode.CLAMP)
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val rOut = minOf(radiusPx, minOf(b.width(), b.height()) / 2f)
            // Glow mode: the fill sits inset so the halo ring
            // fits between the fill edge and the view edge.
            val pad = if (glow && night) strokePx * 3f else 0f
            val fillRect = android.graphics.RectF(
                b.left + pad, b.top + pad, b.right - pad, b.bottom - pad)
            val rFill = maxOf(0f, rOut - pad)
            if (fillColors != null) {
                fillPaint.shader = android.graphics.LinearGradient(
                    fillRect.left, fillRect.top, fillRect.right, fillRect.bottom,
                    fillColors, null, android.graphics.Shader.TileMode.CLAMP)
            } else {
                fillPaint.shader = null
            }
            canvas.drawRoundRect(fillRect, rFill, rFill, fillPaint)
            val inset = strokePx / 2f
            val inner = android.graphics.RectF(
                fillRect.left + inset, fillRect.top + inset,
                fillRect.right - inset, fillRect.bottom - inset)
            val rIn = maxOf(0f, rFill - inset)
            if (solidBorder != null) {
                strokePaint.shader = null
                strokePaint.color = solidBorder
                strokePaint.alpha = borderAlpha
                strokePaint.strokeWidth = strokePx
                canvas.drawRoundRect(inner, rIn, rIn, strokePaint)
                return
            }
            if (glow && night) {
                glowPaint.shader = borderShader(b)
                val mid = android.graphics.RectF(
                    b.left + pad / 2f, b.top + pad / 2f,
                    b.right - pad / 2f, b.bottom - pad / 2f)
                glowPaint.alpha = (borderAlpha * 0.30f).toInt()
                glowPaint.strokeWidth = strokePx * 3f
                canvas.drawRoundRect(mid, maxOf(0f, rOut - pad / 2f),
                    maxOf(0f, rOut - pad / 2f), glowPaint)
                glowPaint.alpha = (borderAlpha * 0.55f).toInt()
                glowPaint.strokeWidth = strokePx * 1.5f
                canvas.drawRoundRect(inner, rIn, rIn, glowPaint)
            }
            strokePaint.shader = borderShader(b)
            strokePaint.alpha = borderAlpha
            strokePaint.strokeWidth = strokePx
            canvas.drawRoundRect(inner, rIn, rIn, strokePaint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** Night card fill — LIGHTER than the 2.84 shared night
     *  card (#CC2A3048): #B33A415C is paler (lower alpha,
     *  lighter base) so the cards read light against the
     *  dark aurora. Day = near-white glass over the pearl. */
    private fun vpnCardFill(): Int =
        if (pageDark) Theme.darken(Color.parseColor("#B33A415C")) else Color.parseColor("#F5FFFFFF")

    /** The card background for a config row (selected = the
     *  same green border as before, in both themes). */
    private fun vpnCardBg(selected: Boolean): android.graphics.drawable.Drawable {
        val d = resources.displayMetrics.density
        return if (selected)
            GradientGlassDrawable(vpnCardFill(), pageDark, 20f * d, 2f * d,
                solidBorder = Color.parseColor("#2E9E5B"))
        else
            GradientGlassDrawable(vpnCardFill(), pageDark, 20f * d, 1.25f * d)
    }

    /** 1.41 (A): free rows wear the free box's
     *  sapphire background at 50% paler (the same
     *  texture, lighter — OvpnTheme.sapphireColors
     *  pale = true), with the card's own border
     *  intact. Non-free rows are unchanged. */
    private fun freeRowBg(selected: Boolean): android.graphics.drawable.Drawable {
        val d = resources.displayMetrics.density
        return if (selected)
            GradientGlassDrawable(vpnCardFill(), pageDark, 20f * d, 2f * d,
                solidBorder = Color.parseColor("#2E9E5B"),
                fillColors = OvpnTheme.sapphireColors(this, pale = true))
        else
            GradientGlassDrawable(vpnCardFill(), pageDark, 20f * d, 1.25f * d,
                fillColors = OvpnTheme.sapphireColors(this, pale = true))
    }

    /** Cloudflare rows keep their own tint in both themes:
     *  night = violet glass with a violet gradient border,
     *  day = the same soft lavender as before. A selected
     *  Cloudflare row keeps the green border (2.86: the old
     *  override used to drop it). */
    private fun vpnCfCardBg(selected: Boolean): android.graphics.drawable.Drawable {
        val d = resources.displayMetrics.density
        return if (pageDark)
            GradientGlassDrawable(Theme.darken(Color.parseColor("#B34E3F8F")), true, 20f * d,
                if (selected) 2f * d else 1.25f * d,
                solidBorder = if (selected) Color.parseColor("#2E9E5B") else null,
                borderColors = if (selected) null
                    else intArrayOf(Theme.darken(Color.parseColor("#8B7CF6")), Theme.darken(Color.parseColor("#B388FF"))))
        else if (selected)
            lightRect(Color.parseColor("#ECE9FF"), Color.parseColor("#2E9E5B"), 2f, 20f)
        else
            lightRect(Color.parseColor("#ECE9FF"), Color.parseColor("#7B7FE0"), 1f, 20f)
    }

    /** Row text colours by mode (the fixed dark colours
     *  below were unreadable on the dark cards). */
    private fun vpnNameColor(): Int =
        if (pageDark) Color.WHITE else Color.parseColor("#1A1A1A")
    private fun vpnHostColor(): Int =
        if (pageDark) Color.parseColor("#B0BEC5") else Color.GRAY
    private fun vpnGreen(): Int =
        if (pageDark) Color.parseColor("#66BB6A") else Color.parseColor("#0B6E4F")
    private fun vpnMuted(): Int =
        if (pageDark) Color.parseColor("#9AA0B0") else Color.parseColor("#666666")
    private fun L(fa: String, en: String): String = AppLanguage.v2rayT(this, fa, en)
    private var servers: MutableList<String> = mutableListOf()
    private var selected: String? = null
    // 2.43: central-log attempt context, consumed
    // once by the refreshStatus connected/error
    // one-shots. The name is kept for the session
    // so the dead-traffic stop can name it too.
    private var appLogPending = false
    private var appLogName = ""
    private var appLogType = ""
    private var appLogMode = ""

    /** 2.43: record a v2ray attempt (mode, type)
     *  and arm the one-shot outcome logs.
     *  1.29: the engine is gone — the attempt is
     *  always a direct connect now. */
    /** 2.45: a config's last measured ping (page
     *  map first, then the ping service's persisted
     *  ping_delays map). Null = never measured;
     *  -1 = failed in the last test. */
    private fun persistedPingMs(cfg: String): Long? {
        return try {
            val j = org.json.JSONObject(getSharedPreferences("renamer_subs", MODE_PRIVATE).getString("ping_delays", "{}") ?: "{}")
            val k = XraySpeedtest.dedupKey(cfg)
            if (j.has(k)) j.optLong(k) else null
        } catch (_: Exception) { null }
    }

    private fun appLogBegin(cfg: String) {
        testLogConnectFixes(cfg)
        appLogName = XraySpeedtest.displayName(cfg)
        appLogType = AppLog.cfgType(this, cfg)
        appLogMode = connectMode()
        appLogPending = true
        val modeFa = if (appLogMode == "best") "بهترین پینگ" else "عادی"
        val modeEn = if (appLogMode == "best") "best ping" else "normal"
        // 2.45: the config's last ping, so the log
        // shows whether its ping test had answered.
        val pingMs = pingMap[XraySpeedtest.dedupKey(cfg)] ?: persistedPingMs(cfg)
        val pingFa = when {
            pingMs != null && pingMs >= 0 -> "؛ پینگ: $pingMs میلی‌ثانیه"
            pingMs != null -> "؛ پینگ: در آخرین تست ناموفق بود"
            else -> "؛ پینگ: اندازه‌گیری نشده"
        }
        val pingEn = when {
            pingMs != null && pingMs >= 0 -> "; ping: $pingMs ms"
            pingMs != null -> "; ping: failed in the last test"
            else -> "; ping: not measured"
        }
        AppLog.append(this, "v2ray",
            "تلاش اتصال «$appLogName»؛ نوع: $appLogType؛ حالت: $modeFa$pingFa.",
            "Connect attempt “$appLogName”; type: $appLogType; mode: $modeEn$pingEn.")
    }

    /** 2.43: outcome line for the armed attempt. */
    private fun appLogEvent(suffixFa: String, suffixEn: String) {
        val modeFa = if (appLogMode == "best") "بهترین پینگ" else "عادی"
        val modeEn = if (appLogMode == "best") "best ping" else "normal"
        AppLog.append(this, "v2ray",
            "«$appLogName»؛ نوع: $appLogType؛ حالت: $modeFa؛ $suffixFa",
            "“$appLogName”; type: $appLogType; mode: $modeEn; $suffixEn")
    }

    /** 2.43: the refreshStatus connected one-shot,
     *  for this page's own sessions only (smart
     *  sessions are logged by the service). */
    private fun appLogConnected() {
        if (!appLogPending) return
        val tag = VpnClientService.sessionTag
        if (ProxyOnlyService.isRunning && !VpnClientService.isRunning) {
            appLogEvent("وصل شد (حالت فقط پروکسی)", "connected (proxy-only mode)")
        } else if (tag == "v2ray") {
            appLogEvent("وصل شد", "connected")
            // 2.45: background real-connection check
            // (only when its toggle is ON).
            ConnVerify.verifyXray(this, "v2ray", "v2ray", VpnClientService.currentName ?: appLogName, VpnClientService.sessionSocksPort.takeIf { it > 0 } ?: VpnConfigBuilder.socksPort(this)) { VpnClientService.isRunning && VpnClientService.sessionTag == "v2ray" }
        } else {
            return
        }
        appLogPending = false
    }
    private val pingMap = mutableMapOf<String, Long>()
    // CLOUDFLARE imports (1.94): dedupKey -> country code, read
    // from the Cloudflare section's prefs at refreshList(). Rows
    // for those configs show a Cloudflare label, a soft galaxy
    // tint, a name in the current language, and a flag that
    // alternates every 2s between the country flag and the
    // Cloudflare section icon.
    private var cfMap: Map<String, String> = emptyMap()
    private val cfFlagViews = mutableListOf<Triple<android.widget.ImageView, android.graphics.Bitmap, android.graphics.Bitmap>>()
    // 2.26 alien imports: dedupKeys transferred
    // from the Alien Language section, plus the
    // alternating alien/flag circles (same 2s
    // trick as Cloudflare's flag/cloud circle).
    private var alienSet: Set<String> = emptySet()
    private val alienFlagViews = mutableListOf<AlienFlagState>()
    private var alienFlagShowAlien = true
    private var alienBitmapCache: android.graphics.Bitmap? = null
    private class AlienFlagState(val alienBmp: android.graphics.Bitmap, var flagBmp: android.graphics.Bitmap) {
        lateinit var view: android.widget.ImageView
        var country: String = ""
        var city: String = ""
    }
    private val alienFlagToggle = object : Runnable {
        override fun run() {
            alienFlagShowAlien = !alienFlagShowAlien
            for (h in alienFlagViews) {
                try { h.view.setImageBitmap(if (alienFlagShowAlien) h.alienBmp else h.flagBmp) } catch (_: Exception) {}
            }
            handler.postDelayed(this, 2000)
        }
    }
    private var cfFlagShowCloud = false
    private var cfCloudBitmap: android.graphics.Bitmap? = null
    private val cfFlagToggle = object : Runnable {
        override fun run() {
            cfFlagShowCloud = !cfFlagShowCloud
            for ((view, flagBmp, cloudBmp) in cfFlagViews) {
                view.setImageBitmap(if (cfFlagShowCloud) cloudBmp else flagBmp)
            }
            handler.postDelayed(this, 2000)
        }
    }
    private var coreInitDone = false
    // Bottom status panel (v2rayNG-style): live
    // progress + short history lines, fixed at the
    // bottom so it stays visible while tests run.
    private val statusLines = mutableListOf<String>()
    private lateinit var stripLine: TextView
    private lateinit var bottomRow: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    /** True between tapping connect and the core
     *  actually coming up (or failing): the FAB
     *  shows an amber "working" look at once, so a
     *  slow DNS pre-resolve or ping test never
     *  looks like a dead tap. Cleared when the
     *  session comes up, on failure, or after a
     *  15s failsafe. */
    private var fabBusy = false
    private val statusPoller = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2000)
        }
    }

    private fun prefs() = getSharedPreferences("renamer_vpn", MODE_PRIVATE)

    /** 1.38 (A4): the persistent set of free-config
     *  dedupKeys fetched in ANY earlier run. A re-tap
     *  never re-fetches — or re-tests — a config whose
     *  key is already here. */
    private fun freeFetchedKeys(): MutableSet<String> {
        return try {
            prefs().getStringSet("free_fetched_keys", emptySet())
                ?.toMutableSet() ?: mutableSetOf()
        } catch (_: Exception) {
            mutableSetOf()
        }
    }

    private fun saveFreeFetchedKeys(keys: Set<String>) {
        try {
            prefs().edit().putStringSet("free_fetched_keys", keys).apply()
        } catch (_: Exception) {}
    }

    /** 1.40 (A2): prune the persistent set for
     *  removed configs. Deleting ALL free rows
     *  empties the set, so a re-tap fetches fresh
     *  again. Called from every path that removes
     *  rows (row delete, delete-no-ping,
     *  delete-invalid). */
    private fun pruneFreeFetchedKeys(removed: Collection<String>) {
        try {
            if (removed.isEmpty()) return
            val keys = freeFetchedKeys()
            var changed = false
            for (cfg in removed) {
                if (FreeConfigs.isFreeConfig(cfg) &&
                    keys.remove(XraySpeedtest.dedupKey(cfg))) changed = true
            }
            if (changed) saveFreeFetchedKeys(keys)
        } catch (_: Exception) {}
    }

    /** 1.40 (A1 healing): the set must equal the
     *  keys of the free configs actually present.
     *  A set carried over from 1.39 holds EVERY
     *  config ever fetched (~600 keys after one
     *  run) — most never added. Reconcile on load:
     *  drop any key whose config is no longer in
     *  the current free list, so a poisoned set
     *  (including one restored by auto-backup)
     *  heals itself instead of blocking every
     *  later fetch. Runs once, right after the
     *  server list is loaded. */
    private fun reconcileFreeFetchedKeys() {
        try {
            val present = servers.filter { FreeConfigs.isFreeConfig(it) }
                .map { XraySpeedtest.dedupKey(it) }.toSet()
            val keys = freeFetchedKeys()
            if (keys.retainAll(present)) saveFreeFetchedKeys(keys)
        } catch (_: Exception) {}
    }

    /** 1.38: Latin digits → Persian digits, so the
     *  bottom message reads exactly like the user's
     *  own words («۱۰ کانفیگ رایگان اضافه شد»). */
    private fun faNum(n: Int): String {
        val fa = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        return n.toString().map { c -> if (c in '0'..'9') fa[c - '0'] else c }.joinToString("")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2.86: the v2ray page follows the home day/night
        // theme — the shared aurora page background, same
        // check as the home page (Theme.isDark).
        pageDark = Theme.isDark(this)
        Theme.applyPage(this)
        // Apply the user's delay-test settings to every ping path.
        try {
            val sp = getSharedPreferences("renamer_vpn", MODE_PRIVATE)
            XraySpeedtest.testUrlOverride = sp.getString("delay_test_url", null)?.takeIf { it.isNotBlank() }
            XraySpeedtest.concurrencyOverride = sp.getString("delay_test_concurrency", null)?.toIntOrNull()
        } catch (_: Exception) {}
        scrollView = ScrollView(this)
        val scroll = scrollView
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scroll.addView(root)

        // Top bar, like v2rayNG's main page: hamburger (drawer)
        // + title + add + menu.
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(glassTopButton("☰") { toggleDrawer() })
        topRow.addView(TextView(this).apply {
            text = "v2ray"; textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.05f
            setTextColor(if (pageDark) Color.WHITE else Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins((12 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            }
        })
        addBtn = glassTopButton("+") { showAddMenu() }
        topRow.addView(addBtn)
        menuBtn = glassTopButton("⋮") { showMainMenu() }
        topRow.addView(menuBtn)
        root.addView(topRow)

        // No status line at the top anymore (the user's
        // word): the FAB itself shows connected /
        // disconnected. Messages go to the single-line
        // glass strip next to the FAB, at the bottom.
        // 1.29: the FAB is the OpenVPN orb (VpnOrbButtonView) —
        // same texture and type as the OpenVPN orb button,
        // kept at this small 60dp size; the state comes from
        // refreshFab(). The HOME quick-connect button is a
        // different view and is untouched.
        connectBtn = VpnOrbButtonView(this)
        // 2.88: the v2ray FAB shows the connect-mode letter (N/B)
        // instead of the ⏻ glyph; the home button keeps ⏻.
        connectBtn.showModeLetter = true
        connectBtn.setModeLetter(if (connectMode() == "best") "B" else "N")
        // 1.38 (B2): the idle orb is the selected mode's
        // colour from the very first frame (gold normal,
        // blue best-ping) — refreshFab keeps it in sync.
        connectBtn.setIdleTint(if (connectMode() == "best") "#0EA5E9" else "#F59E0B")
        connectBtn.setState(QuickConnect.STATE_DISCONNECTED)
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, 16, 0, 16) }
            setBackgroundColor(if (pageDark) Theme.darken(Color.parseColor("#33FFFFFF")) else Color.parseColor("#DDDDDD"))
        })

        // 1.41 (A): ONE combined box (minHeight 53dp —
        // 1.71 item 5: the SAME height as the WireGuard
        // free box — 1.60 cut the WG box 30% from 76dp;
        // radius 20dp, the REAL app's
        // dimensions, NOT the mockup's oversized
        // drawing), sapphire texture. Left ~2/3 =
        // «دریافت کانفیگ رایگان» (tap = start/cancel
        // the fetch, same as the old free button);
        // right ~1/3 = «راهنمای اتصال», separated by
        // a 1dp vertical line, blinking gently and
        // softly (alpha 1→0.6→1 over 2 s); tap =
        // the interactive tutorial. The old separate
        // guide row (1.36) is REMOVED — its function
        // lives in the box now. After the first fetch
        // that adds ≥1 config the guide third is gone
        // for good (renderFreeBox + the persisted
        // v2ray_first_free_fetched flag). While a
        // fetch runs, the left part becomes the cancel
        // path (same view, text swap only — the
        // weights keep the widths stable, so the
        // 1.39 minimumWidth pinning is gone).
        // (1.38 A1: cancelling writes no status
        // anywhere — it only sets the flag.)
        val dBox = resources.displayMetrics.density
        freeBtn = android.widget.TextView(this).apply {
            text = L("دریافت کانفیگ رایگان", "Get free configs")
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.orbText(this@VpnActivity))
            layoutDirection = rtlDir()
            textDirection = appTextDir()
            gravity = Gravity.CENTER
            val d = resources.displayMetrics.density
            setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 2f
            )
            setOnClickListener {
                if (freeFetchRunning) {
                    freeFetchCancelled = true
                } else {
                    // 1.44 (E): EVERY tap opens the
                    // options dialog first; the fetch
                    // starts only after the user picks
                    // a mode (the choice is per-fetch).
                    showFreeFetchOptionsDialog()
                }
            }
        }
        val guideDivider = View(this).apply {
            setBackgroundColor(if (pageDark) Theme.darken(Color.parseColor("#33507A")) else Color.parseColor("#BFD9F5"))
            layoutParams = LinearLayout.LayoutParams(
                (1 * dBox).toInt(), LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        freeGuideDivider = guideDivider
        val guidePart = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                // Right corners follow the box's 20dp
                // radius (the box has no vertical
                // padding, so the part spans its full
                // height); left corners square against
                // the divider.
                cornerRadii = floatArrayOf(
                    0f, 0f, 20f * dBox, 20f * dBox,
                    20f * dBox, 20f * dBox, 0f, 0f)
                setColor(if (pageDark) Theme.darken(Color.parseColor("#14FFFFFF")) else Color.parseColor("#26FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
            isClickable = true
            setOnClickListener { startV2rayTutorial() }
        }
        guidePart.addView(TextView(this).apply {
            text = L("راهنمای اتصال", "Connection guide")
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.orbText(this@VpnActivity))
            textDirection = appTextDir()
            gravity = Gravity.CENTER
            val d = resources.displayMetrics.density
            setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        freeGuidePart = guidePart
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = OvpnTheme.freeBoxBackground(this@VpnActivity)
            // No vertical padding: the parts span the
            // full 53dp height, so the guide part's
            // 20dp right corners sit exactly on the
            // box's 20dp outer corners (and the
            // divider spans the full height, as in
            // the mockup). Text stays centred by the
            // parts' own gravity.
            // 1.71 item 5: 53dp — the WireGuard free
            // box's height (was 76dp).
            minimumHeight = (53 * dBox).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, (12 * dBox).toInt(), 0, 0)
            }
            addView(freeBtn)
            addView(guideDivider)
            addView(guidePart)
        }
        freeBox = box
        // «سرورها» header removed at the user's
        // request (1.79): the page opens straight
        // into the tabs + list.
        // Subscription tab bar (v2rayNG-style): "All
        // configs" first and default, then one tab per
        // subscription (name + count). Tapping a tab
        // filters the list to that sub's configs.
        tabScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            // 2.91 RTL: tabs scroll right-to-left in Persian.
            layoutDirection = rtlDir()
        }
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // 2.91 RTL: first tab («تمام کانفیگ‌ها») sits on the right.
            layoutDirection = rtlDir()
        }
        tabScroll.addView(tabBar)
        root.addView(tabScroll)
        // 1.33: the free button goes BELOW the tabs —
        // order from the top: page header, «تمام کانفیگ‌ها»,
        // free button, list. 1.39: when «تمام کانفیگ‌ها»
        // is the only tab, refreshTabBar hides the tab
        // bar, so the page starts directly with the
        // free button (see refreshTabBar).
        root.addView(box)
        renderFreeBox()
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // A little air between the tab bar and the first config.
        listContainer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, (12 * resources.displayMetrics.density).toInt(), 0, 0) }
        root.addView(listContainer)
        // The drawer, like v2rayNG's side menu: now a WHITE
        // panel. A transparent scrim covers the rest of the
        // page while it is open, so tapping outside the
        // panel (or swiping left, handled in
        // dispatchTouchEvent) closes it; Back is no longer
        // the only way out.
        val frame = android.widget.FrameLayout(this)
        frameView = frame
        frame.addView(scroll)

        drawerScrim = View(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            // 2.87: a REAL dim — the old TRANSPARENT scrim left
            // the page behind the drawer fully visible.
            setBackgroundColor(Color.parseColor("#99000000"))
            isClickable = true
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        frame.addView(drawerScrim)
        drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 2.92 RTL: drawer items start from the right in Persian.
            // (The panel itself stays anchored left — out of scope.)
            layoutDirection = rtlDir()
            // 2.87: the drawer is the THEMED aurora — the home
            // night aurora in night, the pearl aurora in day
            // (the old solid white / dark-navy panel did not
            // match the app at all).
            background = Theme.pageBackground(this@VpnActivity)
            setPadding(32, 48, 32, 32)
            visibility = View.GONE
            layoutParams = android.widget.FrameLayout.LayoutParams(
                (240 * resources.displayMetrics.density).toInt(),
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START
            )
        }
        // The NEW v2ray icon from the 2.83 set (the V +
        // check-flick; neon glow in night, flat in day),
        // instead of the old galaxy V (user order 2.86).
        drawerPanel.addView(android.widget.ImageView(this).apply {
            try {
                val bmp = HomeIcons.iconBitmap(
                    this@VpnActivity, "v2ray",
                    (48 * resources.displayMetrics.density).toInt(), pageDark)
                if (bmp != null) setImageBitmap(bmp)
            } catch (_: Exception) {
            }
            layoutParams = LinearLayout.LayoutParams(
                (48 * resources.displayMetrics.density).toInt(),
                (48 * resources.displayMetrics.density).toInt()
            ).apply { setMargins(0, 0, 0, 16) }
        })
        val drawerItems = listOf(
            L("تنظیمات گروه سابسکریبشن", "Subscription group setting") to { showSubGroupSetting() },
            L("تنظیمات هر اپ", "Per-app settings") to { showPerAppSettings() },
            L("تنظیمات مسیریابی", "Routing Settings") to { showRoutingSettings() },
            L("فایل‌های asset", "Asset files") to { showAssetFiles() },
            L("تنظیمات", "Settings") to {
                startActivity(Intent(this, VpnSettingsActivity::class.java))
            },
            L("لاگ", "Logcat") to { showLogcat() },
            L("بررسی به‌روزرسانی", "Check for update") to { showCheckUpdate() },
            L("پشتیبان‌گیری و بازیابی", "Backup & Restore") to { showBackupRestore() },
            L("درباره", "About") to { showAbout() }
        )
        for ((label, action) in drawerItems) {
            val itemBtn = glassMenuButton(label) {
                closeDrawer()
                action()
            }
            // One line per option, never wrapped to two.
            itemBtn.isSingleLine = true
            itemBtn.ellipsize = android.text.TextUtils.TruncateAt.END
            drawerPanel.addView(itemBtn)
        }
        frame.addView(drawerPanel)
        // Dim layer for the connect-mode picker: ~60%
        // black, covering everything except the FAB and
        // the two mode circles (which are brought to the
        // front when the picker opens).
        modeDim = View(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#99000000"))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { closeModePicker() }
        }
        frame.addView(modeDim)
        // 2.87: dim layer for the + / ⋮ popup menus (same
        // 60% black as the mode picker dim, both modes).
        // It sits above the list and the drawer, below the
        // mode row and the bottom row (FAB + status strip),
        // so the menu — a separate window — floats above
        // it while the page behind goes dark. Tapping it
        // dismisses the open menu.
        menuDim = View(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#99000000"))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { activeMenuPopup?.dismiss() }
        }
        frame.addView(menuDim)
        // v2rayNG-style FAB: fixed at the bottom-right,
        // above the scrolling list, not inside it.
        val fabSize = (60 * resources.displayMetrics.density).toInt()
        val fabMargin = (24 * resources.displayMetrics.density).toInt()
        setupFabTouch()
        // Mode picker row: two circles to the LEFT of the
        // FAB, same bottom margin, hidden until long-press.
        // DURABLE RULE (1.37): the mode orbs are
        // LONG-PRESS-ONLY — they appear ONLY while the
        // user long-presses the connect orb, exactly
        // like the main app. They are NEVER always
        // visible: the 1.36 always-visible change was a
        // misreading (the 1.32 screenshot showed them
        // only because he had long-pressed in that
        // moment) and is reverted here. Do NOT "fix"
        // their absence again.
        modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = rtlDir()
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(fabMargin, fabMargin, fabMargin, fabMargin + fabSize + 12)
            }
        }
        modeRow.addView(modeOption(
            L("نرمال", "Normal"), "#FDE68A", "#F59E0B", "#FBBF24",
            L("با انتخاب این کلید شما به کانفیگ انتخابی خودتون وصل میشید",
              "With this key you connect to the config you selected")
        ) { selectConnectMode("normal") })
        modeRow.addView(modeOption(
            L("بهترین\nپینگ", "Best\nping"), "#A5F3FC", "#0EA5E9", "#22D3EE",
            L("با انتخاب این دکمه برنامه بصورت خودکار تست پینگ میگیره و به بهترین کانفیگ وصل میشه",
              "With this button the app automatically tests ping and connects to the best config")
        ) { selectConnectMode("best") })
        frame.addView(modeRow)
        // Hint next to the FAB, shown for 5 seconds on
        // each visit until the first long-press.
        // 1.41 (D): the wording is the user's own —
        // hold the connect button (N) 2 seconds to
        // connect smartly.
        hintText = TextView(this).apply {
            text = L("برای اتصال بصورت هوشمند، دکمه اتصال (N) را دو ثانیه نگه دارید.",
                     "To connect smartly, hold the connect button (N) for two seconds.")
            textSize = 13f
            setTextColor(Color.parseColor(if (pageDark) "#EDEFF5" else "#333333"))
            setPadding(24, 16, 24, 16)
            background = if (pageDark)
                GradientGlassDrawable(Theme.darken(Color.parseColor("#CC232840")), true,
                    24f * resources.displayMetrics.density,
                    1.25f * resources.displayMetrics.density)
            else android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(Color.parseColor("#E8FFFFFF"))
                setStroke(2, Color.parseColor("#DDDDDD"))
            }
            visibility = View.GONE
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(fabMargin, fabMargin, fabMargin, fabMargin + fabSize + 12)
            }
        }
        frame.addView(hintText)
        // Bottom row, per the user's sketch: ONE
        // single-line glass strip BESIDE the start
        // button — not a separate row, not a big
        // panel. The strip is a pill, so its end near
        // the button curves around the circle, and the
        // gap to the button is small (8dp). It shows
        // only the latest line (ping progress, test
        // report, action counts); tapping it opens
        // the full history.
        bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                setMargins(fabMargin, 0, fabMargin, fabMargin)
            }
        }
        stripLine = TextView(this).apply {
            text = L("آماده", "Ready")
            textSize = 13f
            setTextColor(Color.parseColor(if (pageDark) "#EDEFF5" else "#333333"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 2.91 RTL: Persian status starts from the right.
            layoutDirection = rtlDir()
            textDirection = appTextDir()
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(28, 0, 28, 0)
            background = if (pageDark)
                GradientGlassDrawable(Theme.darken(Color.parseColor("#CC232840")), true,
                    28 * resources.displayMetrics.density,
                    1.25f * resources.displayMetrics.density)
            else android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 28 * resources.displayMetrics.density
                setColor(Color.parseColor("#E8FFFFFF"))
                setStroke(2, Color.parseColor("#DDDDDD"))
            }
            elevation = 3 * resources.displayMetrics.density
            layoutParams = LinearLayout.LayoutParams(0, (56 * resources.displayMetrics.density).toInt(), 1f)
        }
        stripLine.setOnClickListener { showStatusHistoryDialog() }
        bottomRow.addView(stripLine)
        bottomRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams((8 * resources.displayMetrics.density).toInt(), 1)
        })
        connectBtn.layoutParams = LinearLayout.LayoutParams(fabSize, fabSize)
        bottomRow.addView(connectBtn)
        frame.addView(bottomRow)

        // Leave room at the bottom of the list so the FAB
        // never hides the last server row.
        root.setPadding(32, 32, 32, fabSize + fabMargin + (24 * resources.displayMetrics.density).toInt())
        setContentView(frame)
        // Widget tap: auto-connect shortly after the page opens.
        if (intent.getBooleanExtra("widget_auto_connect", false) && !VpnClientService.isRunning) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { if (!VpnClientService.isRunning) toggleConnect() } catch (_: Exception) {}
            }, 600)
        }
        frame.fitsSystemWindows = true

        servers = prefs().getString("servers", "")?.lines()
            ?.map { it.trim() }?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
        selected = prefs().getString("selected", null)?.takeIf { it.isNotBlank() }
        if (selected != null && !servers.contains(selected)) selected = null
        // 1.40 (A1 healing): a poisoned 1.39 set
        // (every config ever fetched) is reconciled
        // against the rows actually present.
        reconcileFreeFetchedKeys()
        refreshList()
        refreshStatus()
        // Automatic subscription update: overdue
        // enabled auto-update subs refresh in the
        // background when the page opens.
        Thread {
            try {
                val updated = VpnSubs.checkAutoUpdate(this)
                if (updated > 0) {
                    servers = VpnSubs.loadServers(this)
                    if (selected != null && !servers.contains(selected)) selected = null
                    runOnUiThread { refreshList() }
                }
            } catch (_: Exception) {}
        }.start()
        // Automatic connect at startup, only when the
        // user turned it on, a config is selected, and
        // VPN permission was already granted once
        // (prepare == null) — never prompt at startup.
        if (prefs().getBoolean("auto_connect_startup", false) &&
            selected != null && !VpnClientService.isRunning) {
            try {
                if (android.net.VpnService.prepare(this) == null) {
                    handler.postDelayed({
                        try {
                            if (!VpnClientService.isRunning && selected != null) {
                                toggleConnect()
                            }
                        } catch (_: Exception) {}
                    }, 1000)
                }
            } catch (_: Exception) {}
        }
    }

    /** Small round button for a server row (share / delete).
     *  Pale colors now: a light background with a dark
     *  icon color, instead of a saturated solid button. */
    // Glass circle button: monochrome, no colours.
    // A translucent white circle with a thin grey
    // rim and a little elevation, so it reads as
    // glass on the pale card. 30dp (was 34dp) and
    // 5dp side gaps, so buttons never touch.
    private fun lightOval(fill: Int, stroke: Int, widthDp: Float): android.graphics.drawable.Drawable {
        val d = resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(fill)
            setStroke((widthDp * d).toInt(), stroke)
        }
    }

    private fun lightRect(fill: Int, stroke: Int, widthDp: Float, radiusDp: Float): android.graphics.drawable.Drawable {
        val d = resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * d
            setColor(fill)
            setStroke((widthDp * d).toInt(), stroke)
        }
    }

    private fun glassCircleButton(label: String, onClick: () -> Unit): Button {
        val d = resources.displayMetrics.density
        val size = (30 * d).toInt()
        return Button(this).apply {
            text = label; textSize = 14f
            setTextColor(Color.parseColor(if (pageDark) "#EDEFF5" else "#333333"))
            background = if (pageDark)
                GradientGlassDrawable(Theme.darken(Color.parseColor("#CC232840")), true,
                    999f * d, 1f * d)
            else
                lightOval(Color.parseColor("#E8FFFFFF"), Color.parseColor("#55000000"), 1f)
            elevation = 3 * d
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins((5 * d).toInt(), 0, (5 * d).toInt(), 0)
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }
    }

    /** 1.33 (items 5 + 9): the SMALL circle button for
     *  a server row — 24dp (was 30dp) with 2dp side
     *  gaps, so four of them fit in a 2x2 grid in the
     *  same row height as before (24 + 2 + 24 = 50dp
     *  of buttons + 8dp of row padding = 58dp, under
     *  the 60dp row minimum). */
    private fun smallGlassCircleButton(label: String, textSizeSp: Float = 11f, onClick: () -> Unit): Button {
        val d = resources.displayMetrics.density
        val size = (24 * d).toInt()
        return Button(this).apply {
            text = label; textSize = textSizeSp
            setTextColor(Color.parseColor(if (pageDark) "#EDEFF5" else "#333333"))
            background = if (pageDark)
                GradientGlassDrawable(Theme.darken(Color.parseColor("#CC232840")), true,
                    999f * d, 1f * d)
            else
                lightOval(Color.parseColor("#E8FFFFFF"), Color.parseColor("#55000000"), 1f)
            elevation = 3 * d
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins((2 * d).toInt(), 0, (2 * d).toInt(), 0)
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }
    }

    /** 1.33 (item 9/11): an invisible slot the same
     *  size as a small circle, so a free row (no share,
     *  no edit) keeps the same 2x2 grid and the same
     *  row height as an own row. */
    private fun emptyCircleSlot(): View {
        val d = resources.displayMetrics.density
        val size = (24 * d).toInt()
        return View(this).apply {
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins((2 * d).toInt(), 0, (2 * d).toInt(), 0)
            }
        }
    }

    /** 1.33 (item 9): the row's four circles as a 2x2
     *  grid — first line flag + share, second line
     *  delete + edit — in the same row height as
     *  before. Callers pass null for a missing button
     *  (free rows: no share/edit) and the empty slot
     *  keeps the grid's shape. */
    private fun actionGrid(
        flag: View, share: View?, delete: View, edit: View?
    ): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = rtlDir()
            val line1 = LinearLayout(this@VpnActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = rtlDir()
                addView(flag)
                addView(share ?: emptyCircleSlot())
            }
            val line2 = LinearLayout(this@VpnActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = rtlDir()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (2 * d).toInt() }
                addView(delete)
                addView(edit ?: emptyCircleSlot())
            }
            addView(line1)
            addView(line2)
        }
    }

    /** 1.39 (item 2): a free row's actions are ONE
     *  horizontal line — flag then delete — on the
     *  RIGHT side of the card (RTL: the first child
     *  is rightmost). Own rows keep the 2x2
     *  actionGrid above. Same 24dp circles and same
     *  2dp margins, so the row is the same width
     *  (56dp) as the grid, and the row's 60dp
     *  minimum height keeps the card exactly as
     *  tall as before. */
    private fun freeActionRow(flag: View, delete: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = rtlDir()
            addView(flag)
            addView(delete)
        }
    }

    // The card behind one server row: 2.86 — lighter glass
    // in night than the mockup (see vpnCardFill), white glass
    // in day, thin teal→purple gradient border in night and
    // a soft lavender border in day, generous rounding.
    // Compact (8dp vertical padding, 8dp gap between cards),
    // and the whole border turns bright green while the card
    // is selected, in both themes.
    private fun serverCard(isSelected: Boolean = false): LinearLayout {
        val d = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 2.93 RTL: the card follows the app language too, so
            // the vertical protocol label mirrors with the row.
            layoutDirection = rtlDir()
            background = vpnCardBg(isSelected)
            // 1.33: NO horizontal padding — the protocol
            // pill must sit flush against the card's edge
            // (see protocolEdgeLabel). The breathing room
            // moved into the row itself (12dp sides).
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, (8 * d).toInt()) }
        }
    }

    // Vertical protocol label on the card's edge,
    // like the reference card: rotated so it reads
    // bottom-to-top.
    // 1.33 (item 8): NARROWER (20dp, was 30dp),
    // SMALLER font (9sp/8sp, was 12sp/10sp), a
    // LIGHT-GREY background (was pale yellow), and
    // the box sits INSIDE the card but FLUSH against
    // its edge — no gap: the card has no horizontal
    // padding anymore, and only the pill's INNER
    // corners are rounded (the edge side is square,
    // so it meets the card's edge cleanly).
    // 1.39 (item 3): two fixes, card untouched.
    // (a) The pill's OUTER corners now match the
    // card's corner radius EXACTLY — 20dp
    // (vpnCardBg), was 10dp — so the pill's curve
    // follows the card's border instead of cutting
    // inside it. (b) The text no longer clips:
    // the pill is only as tall as the card (row min
    // 60dp + 16dp card padding = 76dp), but the
    // label used to be sized by a length guess
    // (len*7+20, capped at 170dp) — longer than
    // the card, so its ends were cut off. Now the
    // font is sized to the text itself: the largest
    // size up to 9sp whose measured width fits a
    // 68dp budget (76dp minus 4dp breathing room
    // at each end), and the label is exactly that
    // measured width — «VLESS / reality» and
    // «VLESS / raw / reality» both show in full.
    // Only the pill adapts; the card's radius,
    // border, height and the pill's 20dp width
    // are unchanged.
    // 1.43 (E): the 1.39 budget was STILL WRONG —
    // the pill is MATCH_PARENT inside the card, so
    // it is the ROW height (60dp), not the card
    // height (76dp): the card's own 8dp+8dp padding
    // sits OUTSIDE it. A label sized to the 68dp
    // budget, plus the +4dp, is up to 72dp tall
    // when rotated — 12dp more than the 60dp pill —
    // so its ends are clipped by the pill/card.
    // SHADOWSOCKS (all caps, the widest short label)
    // sized to 8.5sp at that budget and was cut top
    // and bottom; «VLESS / reality» is narrower and
    // only just fit. The budget is now the REAL pill
    // height minus breathing room: 60dp - 4dp - 4dp
    // = 52dp, the label is exactly its measured
    // width (no +4dp), and the size floor is 3sp so
    // even the longest possible label fits (measured
    // bold, NotoSans-Bold: SHADOWSOCKS 69.4dp @9sp
    // -> 6.5sp; «VLESS / raw / reality» 89.3dp @9sp
    // -> 5sp; «VLESS / raw / tls insecure» 111.8dp
    // @9sp -> 4sp; TROJAN/VMESS/SOCKS stay 9sp).
    private fun protocolEdgeLabel(protocol: String): LinearLayout {
        val d = resources.displayMetrics.density
        val r = 20f * d
        // 1.43 (E): largest size (<= 9sp) that fits
        // the REAL 52dp vertical budget — the 60dp
        // pill minus 4dp breathing room at each end
        // — measured with a bold paint at the user's
        // font scale. The rotated label's vertical
        // span IS its measured width, so the budget
        // must be the pill height, not the card's.
        val budgetPx = 52f * d
        val scaled = resources.displayMetrics.scaledDensity
        val measure = android.graphics.Paint().apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        var sizeSp = 9f
        // 1.43 (E): step down to 3sp in 0.25sp steps —
        // the longest possible label still fits 52dp
        // at 3-4sp, so NO label is ever clipped.
        while (sizeSp > 3f) {
            measure.textSize = sizeSp * scaled
            if (measure.measureText(protocol) <= budgetPx) break
            sizeSp -= 0.25f
        }
        measure.textSize = sizeSp * scaled
        // 1.43 (E): exactly the measured width — the
        // old +4dp pushed the rotated label past the
        // budget it had just been fitted to.
        val textW = measure.measureText(protocol).toInt().coerceAtLeast(1)
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                // Corner order: top-left, top-right,
                // bottom-right, bottom-left. 1.41: the
                // pill is ALWAYS the last child on the
                // card's RIGHT edge — the manifest has
                // no supportsRtl, so the framework lays
                // out LTR even in Persian (the user's
                // 01:50 screenshot proves it: ○ left,
                // pill right), and the 1.39 rtl branch
                // rounded the INNER corners, leaving
                // the outer corners square where they
                // poke past the card's 20dp curve. So
                // the RIGHT corners are rounded
                // unconditionally. 1.39: outer radius
                // = the card's 20dp.
                cornerRadii = floatArrayOf(
                    0f, 0f, r, r, r, r, 0f, 0f
                )
                setColor(Color.parseColor("#DCDCDC"))
            }
            layoutParams = LinearLayout.LayoutParams((20 * d).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
            addView(TextView(this@VpnActivity).apply {
                text = protocol
                textSize = sizeSp
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#4A4A4A"))
                gravity = Gravity.CENTER
                rotation = -90f
                layoutParams = LinearLayout.LayoutParams(textW, (20 * d).toInt())
            })
        }
    }

    // Edit one config in place: the link itself is
    // editable, validated the same way as a new add.
    private fun editConfig(oldCfg: String) {
        val input = android.widget.EditText(this).apply {
            setText(oldCfg)
            // 2.92 RTL: typed Persian text starts from the right.
            layoutDirection = rtlDir()
            textDirection = appTextDir()
            gravity = Gravity.START
            setPadding(16, 16, 16, 16)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("ویرایش کانفیگ", "Edit config"))
            .setView(input)
            .setPositiveButton(L("ذخیره", "Save")) { _, _ ->
                val nc = input.text.toString().trim()
                if ((!nc.contains("://") && !XraySpeedtest.isCustom(nc)) || (XraySpeedtest.outboundFor(nc) == null && !XraySpeedtest.isChain(nc))) {
                    statusText.text = L("کانفیگ واردشده معتبر نیست.", "The entered config is not valid.")
                    return@setPositiveButton
                }
                val idx = servers.indexOf(oldCfg)
                if (idx >= 0) servers[idx] = nc
                if (selected == oldCfg) selected = nc
                saveServers(); refreshList(); refreshStatus()
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show().also { styleDialogRtl(it) }
    }

    private fun toggleDrawer() {
        if (drawerPanel.visibility == View.VISIBLE) closeDrawer()
        else {
            drawerScrim.visibility = View.VISIBLE
            drawerPanel.visibility = View.VISIBLE
            // 2.87: the bottom row (status strip + FAB) is
            // drawn ABOVE the drawer panel, so while the
            // drawer was open its wide, mostly-empty strip
            // showed over the drawer's bottom as a stray
            // empty pill (user's night screenshot). Hide
            // the whole row while the drawer is open.
            if (::bottomRow.isInitialized) bottomRow.visibility = View.GONE
        }
    }

    private fun closeDrawer() {
        drawerPanel.visibility = View.GONE
        drawerScrim.visibility = View.GONE
        if (::bottomRow.isInitialized) bottomRow.visibility = View.VISIBLE
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // Swipe left on the open drawer closes it.
        if (::drawerPanel.isInitialized && drawerPanel.visibility == View.VISIBLE) {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> drawerDownX = ev.x
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = drawerDownX - ev.x
                    if (dx > 120 * resources.displayMetrics.density) {
                        closeDrawer()
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onBackPressed() {
        // 1.36: Back exits the spotlight tutorial
        // first (it never hides the guide button).
        if (v2rayTutStep > 0) {
            endV2rayTutorial()
            return
        }
        if (::modeRow.isInitialized && modeRow.visibility == View.VISIBLE) {
            closeModePicker()
            return
        }
        if (::drawerPanel.isInitialized && drawerPanel.visibility == View.VISIBLE) {
            closeDrawer()
            return
        }
        super.onBackPressed()
    }

    // --- Glass buttons ---
    /** A small round glass button for the top bar. On
     *  touch it turns pale blue for 2 seconds, then
     *  returns to glass. */
    private fun glassTopButton(label: String, onClick: () -> Unit): Button {
        val d = resources.displayMetrics.density
        val size = (40 * d).toInt()
        // 2.86: dark glass circle with a gradient border in
        // night (white glyph), the same pale glass as before
        // in day. On touch it turns pale blue for 2 seconds
        // in day, a lighter dark glass in night, then returns.
        val glass = if (pageDark)
            GradientGlassDrawable(Theme.darken(Color.parseColor("#CC232840")), true, 999f * d, 1f * d)
        else
            lightOval(Color.parseColor("#E0FFFFFF"), Color.parseColor("#DDDDDD"), 1f)
        val pale = if (pageDark)
            GradientGlassDrawable(Theme.darken(Color.parseColor("#2E3A55")), true, 999f * d, 1f * d)
        else
            lightOval(Color.parseColor("#E3F2FD"), Color.parseColor("#BBDEFB"), 1f)
        return Button(this).apply {
            text = label; textSize = 14f
            setTextColor(Color.parseColor(if (pageDark) "#EDEFF5" else "#333333"))
            background = glass
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(4, 0, 4, 0)
            }
            setPadding(0, 0, 0, 0)
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    background = pale
                    handler.postDelayed({ background = glass }, 2000)
                }
                false
            }
            setOnClickListener { onClick() }
        }
    }

    /** A glass menu button: very pale blue, rounded,
     *  separated from its neighbours by margins. */
    // 2.88 — STANDING RTL RULE: Persian UI text starts from the RIGHT,
    // English from the LEFT, everywhere, every build, no exceptions.
    // The manifest has NO supportsRtl (adding it would flip the drawer
    // side and other START-anchored layout — out of scope, risky), so
    // every programmatic text row sets its direction from the language:
    // textDirection = appTextDir() (app language, NOT the phone locale)
    // + gravity START on the text, and layoutDirection on the container
    // so START-anchored
    // children (indicators, icons) sit on the right in Persian and the
    // left in English.
    private fun rtlDir(): Int =
        if (AppLanguage.v2rayIsEnglish(this)) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL

    // 2.92: text direction must follow the APP language
    // (v2ray_language), not the phone's system locale.
    // TEXT_DIRECTION_LOCALE resolves LTR on an English-locale
    // phone even when the app UI is Persian — that is why
    // Persian text still started from the left in 2.91.
    private fun appTextDir(): Int =
        if (AppLanguage.v2rayIsEnglish(this)) View.TEXT_DIRECTION_LTR else View.TEXT_DIRECTION_RTL

    // 2.91 RTL: AlertDialog title/message start from the right in
    // Persian. Theme.styleDialog already themes the panel; this adds
    // the direction pass on the framework title/message TextViews
    // after show() — the title/message views only exist then. Every
    // AlertDialog in this file goes through here. layoutDirection is
    // set on the TextViews themselves: the dialog decor has no RTL
    // direction (the manifest has no supportsRtl), so gravity START
    // alone would still resolve to the left.
    private fun styleDialogRtl(dialog: android.app.AlertDialog) {
        Theme.styleDialog(dialog, this)
        // 2.92: the dialog button bar follows the window decor
        // direction, which is LTR without supportsRtl — set it
        // from the app language so the buttons sit on the right
        // in Persian.
        try { dialog.window?.decorView?.layoutDirection = rtlDir() } catch (_: Exception) {}
        try {
            dialog.findViewById<TextView>(android.R.id.title)?.apply {
                layoutDirection = rtlDir()
                textDirection = appTextDir()
                gravity = Gravity.START
            }
            dialog.findViewById<TextView>(android.R.id.message)?.apply {
                layoutDirection = rtlDir()
                textDirection = appTextDir()
                gravity = Gravity.START
            }
        } catch (_: Exception) {}
    }

    private fun glassMenuButton(label: String, onClick: () -> Unit): Button {
        val d = resources.displayMetrics.density
        // 2.87: the drawer pills match the mockups — dark
        // glass with a teal→purple gradient border and light
        // text in night; WHITE glass with a soft lavender
        // gradient border and dark text in day (the old pale
        // blue pills on plain white were the pre-theme look
        // the user rejected).
        val pale = if (pageDark)
            GradientGlassDrawable(Theme.darken(Color.parseColor("#CC232840")), true, 20f * d, 1.25f * d)
        else
            GradientGlassDrawable(Color.parseColor("#F5FFFFFF"), false, 20f * d, 1.25f * d)
        val stronger = if (pageDark)
            GradientGlassDrawable(Theme.darken(Color.parseColor("#3A4666")), true, 20f * d, 1.25f * d)
        else
            GradientGlassDrawable(Color.parseColor("#EDE7F6"), false, 20f * d, 1.25f * d)
        return Button(this).apply {
            text = label; textSize = 16f
            // 2.88 RTL rule: text starts from the right in Persian.
            layoutDirection = rtlDir()
            textDirection = appTextDir()
            gravity = Gravity.START
            setTextColor(Color.parseColor(if (pageDark) "#EDEFF5" else "#333333"))
            background = pale
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 8) }
            setPadding(24, 20, 24, 20)
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    background = stronger
                    handler.postDelayed({ background = pale }, 2000)
                }
                false
            }
            setOnClickListener { onClick() }
        }
    }

    /** A glass menu dialog: a vertical list of separate
     *  pale-blue glass buttons, with the same spacing
     *  the old list dialogs had. */
    private fun showGlassMenuDialog(title: String, items: Array<String>, onSelect: (Int) -> Unit) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 2.92 RTL: menu buttons hug the right edge in Persian.
            layoutDirection = rtlDir()
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this)
        scroll.addView(col)
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(L("بستن", "Close"), null)
            .create()
        for ((i, label) in items.withIndex()) {
            col.addView(glassMenuButton(label) {
                dialog.dismiss()
                onSelect(i)
            })
        }
        dialog.show()
        styleDialogRtl(dialog)
    }

    /** A plain themed list adapter for AlertDialog.setAdapter:
     *  the framework's setItems rows keep the ACTIVITY's
     *  light-theme dark text — and Theme.styleDialog's walk
     *  runs before the ListView creates its rows — so in
     *  night mode they render dark-on-dark (the 2.86 bug,
     *  user screenshot 8). This adapter sets the text colour
     *  explicitly in getView, so every row — including
     *  recycled ones while scrolling — is readable in both
     *  modes. */
    private fun themedListAdapter(labels: Array<String>): android.widget.ArrayAdapter<String> {
        return object : android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.apply {
                    setTextColor(Color.parseColor(if (pageDark) "#EDEFF5" else "#333333"))
                    textSize = 16f
                    // 2.88 RTL rule: text starts from the right in Persian.
                    layoutDirection = rtlDir()
                    textDirection = appTextDir()
                    gravity = Gravity.START
                    setPadding(24, 20, 24, 20)
                }
                return v
            }
        }
    }

    /** Single/multi-choice rows with a drawn indicator and
     *  explicit text colour (same 2.86 root cause as
     *  themedListAdapter). The indicator is drawn in code —
     *  a hollow circle / rounded square, filled teal with a
     *  white mark when checked — so it never depends on the
     *  theme's listChoiceIndicator drawables. `checked` is
     *  shared with the caller; the dialog's click listener
     *  mutates it and calls notifyDataSetChanged. */
    private class ThemedChoiceAdapter(
        private val ctx: Context,
        private val labels: Array<String>,
        private val single: Boolean,
        private val checked: BooleanArray,
        private val dark: Boolean
    ) : android.widget.BaseAdapter() {
        override fun getCount(): Int = labels.size
        override fun getItem(position: Int): Any = labels[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val d = ctx.resources.displayMetrics.density
            val row = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((24 * d).toInt(), (14 * d).toInt(), (24 * d).toInt(), (14 * d).toInt())
            }
            row.removeAllViews()
            // 2.88 RTL rule: the indicator sits at the START side — right
            // in Persian, left in English.
            row.layoutDirection = if (AppLanguage.v2rayIsEnglish(ctx)) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
            val isChecked = checked.getOrNull(position) == true
            val teal = Color.parseColor("#26A69A")
            val indicator = android.widget.FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()).apply {
                    marginEnd = (12 * d).toInt()
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = if (single) android.graphics.drawable.GradientDrawable.OVAL
                        else android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = if (single) 0f else 6f * d
                    setColor(if (isChecked) teal else Color.TRANSPARENT)
                    setStroke(2, if (isChecked) teal else Color.parseColor(if (dark) "#8A93A8" else "#9E9E9E"))
                }
            }
            if (isChecked) {
                if (single) {
                    indicator.addView(View(ctx).apply {
                        layoutParams = android.widget.FrameLayout.LayoutParams((10 * d).toInt(), (10 * d).toInt(), Gravity.CENTER)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(Color.WHITE)
                        }
                    })
                } else {
                    indicator.addView(TextView(ctx).apply {
                        text = "✓"
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        gravity = Gravity.CENTER
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
                    })
                }
            }
            row.addView(indicator)
            row.addView(TextView(ctx).apply {
                text = labels[position]
                textSize = 16f
                // 2.92 RTL: direction follows the APP language, not the
                // phone locale (nested class: no access to appTextDir()).
                layoutDirection = if (AppLanguage.v2rayIsEnglish(ctx)) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
                textDirection = if (AppLanguage.v2rayIsEnglish(ctx)) View.TEXT_DIRECTION_LTR else View.TEXT_DIRECTION_RTL
                gravity = Gravity.START
                setTextColor(Color.parseColor(if (dark) "#EDEFF5" else "#333333"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            return row
        }
    }

    /** A multi-choice list dialog with themed rows (see
     *  ThemedChoiceAdapter). `checked` is mutated in place;
     *  onSave reads it. Used for the app picker and the
     *  chain-server picker. */
    private fun showMultiChoiceDialog(title: String, labels: Array<String>, checked: BooleanArray, positiveLabel: String, onSave: () -> Unit) {
        val adapter = ThemedChoiceAdapter(this, labels, single = false, checked, pageDark)
        val listView = android.widget.ListView(this).apply {
            this.adapter = adapter
            divider = null
            setPadding(16, 8, 16, 8)
            setOnItemClickListener { _, _, pos, _ ->
                checked[pos] = !checked[pos]
                adapter.notifyDataSetChanged()
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(listView)
            .setPositiveButton(positiveLabel) { _, _ -> onSave() }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show().also { styleDialogRtl(it) }
    }

    // --- Connect mode picker ---
    // 2.87: a mode circle is the SAME shape as the home
    // quick-connect button — a saturated disc, a soft glow
    // and a thin dotted ring rotating 1 rev / 14 s — but
    // with its own colours and a white bold label instead
    // of the ⏻ glyph: warm amber/gold for «نرمال», cool
    // blue/teal for «بهترین پینگ». Same shape, clearly
    // different from each other and from the FAB.
    // 1.32: the disc is now the OpenVPN orb itself
    // (VpnOrbButtonView / OvpnOrbDrawable) in the mode's
    // own colours — offset gradient, top gloss, bottom
    // shade, sparkles, thin white rim. 1.38 (B1): NO
    // rotating ring around the mode orbs anymore — the
    // dotted ring + its ticker are gone; the orbs are
    // plain. ONLY the mode orbs lose their ring: the
    // connect FAB (VpnOrbButtonView) keeps its own
    // rotating ring, untouched.
    private class VpnModeCircleView(
        ctx: Context,
        private val label: String,
        private val lightHex: String,
        private val baseHex: String,
        private val ringHex: String
    ) : View(ctx) {
        private val density = resources.displayMetrics.density
        private val haloPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val discPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // 1.32: the orb's own paints (gloss / shade /
        // sparkles / white rim — see OvpnOrbDrawable).
        private val glossPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val shadePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val sparkPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val rimPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 12f * density
        }
        private fun withAlpha(hex: String, alpha: Float): Int {
            val c = Color.parseColor(hex)
            return (c and 0x00FFFFFF) or (((alpha * 255).toInt() and 0xFF) shl 24)
        }
        private fun shadeOf(hex: String, f: Float): Int {
            val c = Color.parseColor(hex)
            return Color.argb(Color.alpha(c),
                (Color.red(c) * f).toInt().coerceIn(0, 255),
                (Color.green(c) * f).toInt().coerceIn(0, 255),
                (Color.blue(c) * f).toInt().coerceIn(0, 255))
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) / 2f
            if (r <= 0f) return
            haloPaint.shader = android.graphics.RadialGradient(cx, cy, r,
                intArrayOf(withAlpha(ringHex, 0.38f), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, haloPaint)
            // 1.32: the orb disc (OvpnOrbDrawable recipe) in
            // this mode's own colours — gradient offset to the
            // top-left, light -> base -> shaded base.
            val discR = r * 0.74f
            discPaint.shader = android.graphics.RadialGradient(
                cx - discR * 0.36f, cy - discR * 0.48f, discR * 1.15f,
                intArrayOf(Color.parseColor(lightHex), Color.parseColor(baseHex),
                    shadeOf(baseHex, 0.55f)),
                floatArrayOf(0f, 0.62f, 1f), android.graphics.Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, discR, discPaint)
            // Top gloss ellipse, like the orb's.
            glossPaint.shader = android.graphics.RadialGradient(
                cx, cy - discR * 0.62f, discR * 0.55f,
                intArrayOf(Color.argb(140, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy - discR * 0.62f, discR * 0.55f, glossPaint)
            // Bottom inner shade in the mode's base colour.
            val baseCol = Color.parseColor(baseHex)
            shadePaint.shader = android.graphics.RadialGradient(
                cx, cy + discR * 0.75f, discR * 0.8f,
                intArrayOf(Color.argb(71, Color.red(baseCol), Color.green(baseCol),
                    Color.blue(baseCol)), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy + discR * 0.75f, discR * 0.8f, shadePaint)
            // Sparkles (fixed positions, like the orb's).
            sparkPaint.color = Color.argb(200, 255, 255, 255)
            canvas.drawCircle(cx - discR * 0.56f, cy - discR * 0.72f, 2.5f * density, sparkPaint)
            canvas.drawCircle(cx + discR * 0.52f, cy - discR * 0.60f, 2f * density, sparkPaint)
            canvas.drawCircle(cx + discR * 0.64f, cy + discR * 0.40f, 2.5f * density, sparkPaint)
            canvas.drawCircle(cx - discR * 0.44f, cy + discR * 0.68f, 2f * density, sparkPaint)
            // Thin white rim, like the orb's.
            rimPaint.color = Color.argb(140, 255, 255, 255)
            rimPaint.style = android.graphics.Paint.Style.STROKE
            rimPaint.strokeWidth = 1.5f * density
            canvas.drawCircle(cx, cy, discR - 1f, rimPaint)
            // 1.38 (B1): no rotating ring here anymore
            // (the ring circle + its 12 dots are gone).
            val lines = label.split("\n")
            val fm = textPaint.fontMetrics
            val lineH = textPaint.textSize * 1.25f
            // Centre the block: the first baseline sits so the
            // whole block is vertically centred on the disc.
            var y = cy - (lines.size - 1) * lineH / 2f - (fm.ascent + fm.descent) / 2f
            for (line in lines) {
                canvas.drawText(line, cx, y, textPaint)
                y += lineH
            }
        }
    }

    private fun modeOption(label: String, lightHex: String, baseHex: String, ringHex: String, explanation: String, onSelect: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 0, 8, 0)
        }
        val d = resources.displayMetrics.density
        val size = (88 * d).toInt()
        val circle = VpnModeCircleView(this, label, lightHex, baseHex, ringHex).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            isClickable = true
            isFocusable = true
            setOnClickListener { onSelect() }
        }
        row.addView(circle)
        val infoSize = (28 * d).toInt()
        row.addView(Button(this).apply {
            text = "!"; textSize = 14f
            setTextColor(Color.parseColor(if (pageDark) "#EDEFF5" else "#333333"))
            background = if (pageDark)
                GradientGlassDrawable(Theme.darken(Color.parseColor("#CC232840")), true, 999f * d, 1f * d)
            else android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#E8FFFFFF"))
                setStroke(2, Color.parseColor("#DDDDDD"))
            }
            layoutParams = LinearLayout.LayoutParams(infoSize, infoSize).apply {
                setMargins(4, 0, 0, 0)
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                android.app.AlertDialog.Builder(this@VpnActivity)
                    .setMessage(explanation)
                    .setPositiveButton(L("بستن", "Close"), null)
                    .show().also { styleDialogRtl(it) }
            }
        })
        return row
    }

    private fun onFabLongPress() {
        fabLongPressed = true
        // First long-press ever: the hint never shows again.
        prefs().edit().putBoolean("connect_mode_done", true).apply()
        hintText.visibility = View.GONE
        closeDrawer()
        modeDim.visibility = View.VISIBLE
        modeRow.visibility = View.VISIBLE
        frameView.bringChildToFront(bottomRow)
        frameView.bringChildToFront(modeRow)
        refreshFab()
    }

    private fun closeModePicker() {
        modeDim.visibility = View.GONE
        modeRow.visibility = View.GONE
    }

    private fun selectConnectMode(mode: String) {
        prefs().edit().putString("connect_mode", mode)
            .putBoolean("connect_mode_done", true).apply()
        closeModePicker()
        refreshFab()
    }

    private fun setupFabTouch() {
        connectBtn.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    fabLongPressed = false
                    fabDownX = ev.x
                    fabDownY = ev.y
                    handler.removeCallbacks(fabLongPressRunnable)
                    // 1 second (was 2): the mode picker
                    // must open fast.
                    handler.postDelayed(fabLongPressRunnable, 1000)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val moved = kotlin.math.hypot(ev.x - fabDownX, ev.y - fabDownY)
                    if (moved > 30 * resources.displayMetrics.density) {
                        handler.removeCallbacks(fabLongPressRunnable)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(fabLongPressRunnable)
                    if (fabLongPressed) {
                        fabLongPressed = false
                        true
                    } else if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        toggleConnect()
                        true
                    } else true
                }
                else -> true
            }
        }
    }

    /** The FAB itself: 1.29 — the OpenVPN orb
     *  (VpnOrbButtonView), same texture and type as the
     *  OpenVPN orb button, at this small 60dp size: a
     *  sapphire gradient orb with a thin sweep-gradient
     *  ring rotating 1 rev / 14 s (the FAB keeps its
     *  ring in 1.38 — only the mode orbs lost theirs).
     *  Connected = green, connecting/busy = amber.
     *  1.38 (B2): DISCONNECTED is no longer red — it
     *  is the selected mode's own colour: gold/amber
     *  (#F59E0B, the normal orb's base) in normal
     *  mode, blue (#0EA5E9, the best-ping orb's base)
     *  in best-ping mode. A DEAD verdict (2.45: the
     *  tunnel is up but carries no traffic) also shows
     *  the idle colour — green would be a lie.
     *  2.88 (B3, verified in 1.38): the glyph IS the
     *  connect-mode letter again (white bold N =
     *  Normal, B = Best-ping, read from the saved
     *  connect_mode on every refresh); selectConnectMode
     *  calls refreshFab, so a mode pick updates the
     *  letter immediately. Tapping still disconnects
     *  (toggleConnect keys on isRunning, not on this
     *  color); the session itself is never stopped by
     *  a verdict alone. */
    private fun refreshFab() {
        // The FAB is "connected" ONLY for a v2ray
        // session (manual, widget, or smart's v2ray
        // config). Another section's session must
        // never light this button up.
        val ownSession = VpnClientService.isRunning &&
            VpnClientService.sessionTag in arrayOf("v2ray", "smart", null)
        val verifyDead = ownSession && ConnVerify.isDeadV2ray(this, VpnClientService.sessionTag, VpnClientService.currentName)
        val connected = (ownSession && !verifyDead) || ProxyOnlyService.isRunning
        if (connected) fabBusy = false
        // 1.38 (B2): idle tint follows the mode, set
        // BEFORE setState so the first frame is right.
        connectBtn.setIdleTint(if (connectMode() == "best") "#0EA5E9" else "#F59E0B")
        connectBtn.setState(when {
            connected -> QuickConnect.STATE_CONNECTED
            fabBusy -> QuickConnect.STATE_CONNECTING
            else -> QuickConnect.STATE_DISCONNECTED
        })
        // 2.88: the glyph follows the saved connect mode on every
        // refresh — and selectConnectMode() refreshes, so a mode pick
        // updates the letter immediately.
        connectBtn.setModeLetter(if (connectMode() == "best") "B" else "N")
    }

    /** Subscription groups: the sub links now live here,
     *  not on the main page (like v2rayNG's drawer). */
    // Subscription group setting is now a real page
    // (list + per-sub settings, v2rayNG-style),
    // not the old add-a-link dialog.
    private fun showSubGroupSetting() {
        startActivity(Intent(this, VpnSubActivity::class.java))
    }

    private fun showPerAppSettings() {
        val mode = prefs().getString("per_app_mode", "bypass") ?: "bypass"
        val saved = prefs().getString("per_app_set", "")?.lines()
            ?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        // 2.87: setItems is GONE here — its framework rows kept
        // the light theme's dark text on the dark dialog card
        // (user screenshot 8). The themed adapter sets the
        // colour explicitly (see themedListAdapter).
        android.app.AlertDialog.Builder(this)
            .setTitle(L("تنظیمات هر اپ", "Per-app settings"))
            .setAdapter(themedListAdapter(arrayOf(
                L("Bypass selected apps (پروکسی نشوند)", "Bypass selected apps (do not proxy)"),
                L("Only selected apps (فقط این‌ها پروکسی شوند)", "Only selected apps (only these proxied)"),
                L("Disabled (همه اپ‌ها پروکسی شوند)", "Disabled (all apps proxied)")
            ))) { _, which ->
                val newMode = when (which) {
                    0 -> "bypass"; 1 -> "only"; else -> "disabled"
                }
                if (newMode == "disabled") {
                    prefs().edit().putString("per_app_mode", "disabled")
                        .putString("per_app_set", "").apply()
                    statusText.text = L("Per-app غیرفعال شد.", "Per-app disabled.")
                    return@setAdapter
                }
                // 2.92: the app list loads on a background
                // thread (AppListLoader, cached in memory) — the
                // old code queried PackageManager and called
                // getApplicationLabel() per app, all on the UI
                // thread, on every open.
                val loading = android.app.AlertDialog.Builder(this)
                    .setMessage(L("در حال بارگذاری…", "Loading…"))
                    .setCancelable(false)
                    .create()
                loading.show()
                styleDialogRtl(loading)
                AppListLoader.loadAsync(this) { pairs ->
                    try { loading.dismiss() } catch (_: Exception) {}
                    if (isFinishing || isDestroyed) return@loadAsync
                    val apps = pairs.map { it.first }
                    val names = pairs.map { it.second }.toTypedArray()
                    val checked = BooleanArray(apps.size) { i ->
                        saved.contains(apps[i])
                    }
                    showMultiChoiceDialog(
                        L("اپ‌ها را انتخاب کن", "Select apps"), names, checked,
                        L("ذخیره", "Save")
                    ) {
                        val chosen = apps.filterIndexed { i, _ -> checked[i] }
                        prefs().edit().putString("per_app_mode", newMode)
                            .putString("per_app_set", chosen.joinToString("\n")).apply()
                        statusText.text = L("Per-app ذخیره شد: ${chosen.size} اپ (${if (newMode == "bypass") "bypass" else "only"}).", "Per-app saved: ${chosen.size} apps (${if (newMode == "bypass") "bypass" else "only"}).")
                    }
                }
            }
            .show().also { styleDialogRtl(it) }
    }

    private fun showRoutingSettings() {
        val cur = prefs().getString("routing_mode", "bypass_private") ?: "bypass_private"
        val labels = arrayOf(
            L("Global (همه از پروکسی)", "Global (all via proxy)"),
            L("Bypass private (خصوصی مستقیم، بقیه پروکسی)", "Bypass private (private direct, rest proxy)")
        )
        // 2.87: setSingleChoiceItems is GONE — same dark-on-dark
        // root cause as the per-app dialog. The themed choice
        // adapter draws the radio itself and sets the text
        // colour explicitly.
        val checked = booleanArrayOf(cur == "global", cur != "global")
        val adapter = ThemedChoiceAdapter(this, labels, single = true, checked, pageDark)
        android.app.AlertDialog.Builder(this)
            .setTitle(L("تنظیمات مسیریابی", "Routing Settings"))
            .setSingleChoiceItems(adapter, if (cur == "global") 0 else 1) { _, which ->
                checked[0] = which == 0
                checked[1] = which == 1
                adapter.notifyDataSetChanged()
            }
            .setPositiveButton(L("ذخیره", "Save")) { _, _ ->
                val chosen = if (checked[0]) "global" else "bypass_private"
                prefs().edit().putString("routing_mode", chosen).apply()
                statusText.text = L("Routing ذخیره شد: ${if (chosen == "global") "Global" else "Bypass private"}.", "Routing saved: ${if (chosen == "global") "Global" else "Bypass private"}.")
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show().also { styleDialogRtl(it) }
    }

    private fun showAssetFiles() {
        val dir = getDir("assets", MODE_PRIVATE)
        val geoip = java.io.File(dir, "geoip.dat")
        val geosite = java.io.File(dir, "geosite.dat")
        val text = L("geoip.dat: ${if (geoip.exists()) "${geoip.length()} بایت" else "وجود ندارد"}\n", "geoip.dat: ${if (geoip.exists()) "${geoip.length()} bytes" else "missing"}\n") +
            L("geosite.dat: ${if (geosite.exists()) "${geosite.length()} بایت" else "وجود ندارد"}\n", "geosite.dat: ${if (geosite.exists()) "${geosite.length()} bytes" else "missing"}\n") +
            L("پوشه: ${dir.absolutePath}", "Folder: ${dir.absolutePath}")
        android.app.AlertDialog.Builder(this)
            .setTitle(L("فایل‌های asset", "Asset files"))
            .setMessage(text)
            .setPositiveButton(L("بستن", "Close"), null)
            .setNeutralButton(L("کپی دوباره از داخل اپ", "Copy again from inside the app")) { _, _ ->
                try {
                    CoreInit.init(this)
                    statusText.text = L("فایل‌های asset دوباره کپی شدند.", "Asset files copied again.")
                } catch (e: Exception) {
                    statusText.text = L("کپی asset ناموفق: ${e.message}", "Failed to copy assets: ${e.message}")
                }
            }
            .show().also { styleDialogRtl(it) }
    }

    private fun showAppSettings() {
        // Settings now has two sections: connection
        // settings, and app language (فارسی / English).
        showGlassMenuDialog("Settings", arrayOf(
            L("تنظیمات اتصال (MTU / DNS / SOCKS)", "Connection settings (MTU / DNS / SOCKS)"),
            L("زبان v2ray / v2ray language", "v2ray language / زبان v2ray")
        )) { which ->
            if (which == 1) {
                showGlassMenuDialog(L("زبان v2ray", "v2ray language"), arrayOf("فارسی", "English")) { langWhich ->
                    AppLanguage.setV2rayLang(this, if (langWhich == 0) "fa" else "en")
                    statusText.text = L("زبان v2ray تغییر کرد.", "v2ray language changed.")
                    recreate()
                }
                return@showGlassMenuDialog
            }
            val curMtu = prefs().getString("mtu", "1500") ?: "1500"
            val curDns = prefs().getString("dns", "1.1.1.1,8.8.8.8") ?: "1.1.1.1,8.8.8.8"
            val curPort = prefs().getString("socks_port", "10808") ?: "10808"
            showForm("Settings", listOf(
                "MTU" to curMtu, L("DNS (با کاما جدا کن)", "DNS (comma-separated)") to curDns, L("پورت SOCKS", "SOCKS port") to curPort
            )) { v ->
                prefs().edit()
                    .putString("mtu", v["MTU"] ?: "1500")
                    .putString("dns", v[L("DNS (با کاما جدا کن)", "DNS (comma-separated)")] ?: "1.1.1.1,8.8.8.8")
                    .putString("socks_port", v[L("پورت SOCKS", "SOCKS port")] ?: "10808")
                    .apply()
                statusText.text = L("تنظیمات ذخیره شد (برای اتصال بعدی اعمال می‌شود).", "Settings saved (applied on next connect).")
            }
        }
    }

    private fun showPromotion() {
        android.app.AlertDialog.Builder(this)
            .setTitle(L("معرفی", "Promotion"))
            .setMessage(L("در این اپ تبلیغی وجود ندارد.", "There is no promotion in this app."))
            .setPositiveButton(L("بستن", "Close"), null)
            .show().also { styleDialogRtl(it) }
    }

    private fun showLogcat() {
        val log = try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "200"))
            val out = proc.inputStream.bufferedReader().readText()
            out.lines().filter { it.contains("com.renamer") || it.contains("libv2ray") }
                .takeLast(100).joinToString("\n").ifBlank { L("لاگی برای این اپ ثبت نشده است.", "No log recorded for this app.") }
        } catch (e: Exception) {
            L("خواندن لاگ ناموفق: ${e.message}\nآخرین خطا: ${VpnClientService.lastError ?: "—"}", "Failed to read log: ${e.message}\nLast error: ${VpnClientService.lastError ?: "—"}")
        }
        val tv = TextView(this).apply {
            text = log; textSize = 12f
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this)
        scroll.addView(tv)
        android.app.AlertDialog.Builder(this)
            .setTitle(L("لاگ", "Logcat"))
            .setView(scroll)
            .setPositiveButton(L("بستن", "Close"), null)
            .setNeutralButton(L("کپی لاگ", "Copy log")) { _, _ ->
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("log", log))
                    statusText.text = L("لاگ کپی شد.", "Log copied.")
                } catch (e: Exception) {
                    statusText.text = L("کپی ناموفق: ${e.message}", "Copy failed: ${e.message}")
                }
            }
            .show().also { styleDialogRtl(it) }
    }

    private fun showCheckUpdate() {
        val info = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (_: Exception) { null }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("بررسی به‌روزرسانی", "Check for update"))
            .setMessage(L("نسخه فعلی: ${info?.versionName ?: "?"} (کد ${info?.versionCode ?: "?"})\nبه‌روزرسانی این اپ از طریق لینک دانلودی که سازنده می‌فرستد انجام می‌شود.", "Current version: ${info?.versionName ?: "?"} (code ${info?.versionCode ?: "?"})\nUpdates for this app come through the download link sent by the creator."))
            .setPositiveButton(L("بستن", "Close"), null)
            .show().also { styleDialogRtl(it) }
    }

    private fun showBackupRestore() {
        // 2.87: setItems replaced by the themed adapter
        // (same dark-on-dark root cause as per-app).
        android.app.AlertDialog.Builder(this)
            .setTitle(L("پشتیبان‌گیری و بازیابی", "Backup & Restore"))
            .setAdapter(themedListAdapter(arrayOf(L("Backup (ذخیره در فایل)", "Backup (save to file)"), L("Restore (خواندن از فایل)", "Restore (read from file)")))) { _, which ->
                if (which == 0) {
                    val json = JSONObject().apply {
                        put("servers", JSONArray(servers))
                        put("selected", selected ?: "")
                        put("vpn_subs", prefs().getString("vpn_subs", ""))
                        put("per_app_mode", prefs().getString("per_app_mode", ""))
                        put("per_app_set", prefs().getString("per_app_set", ""))
                        put("routing_mode", prefs().getString("routing_mode", ""))
                    }.toString()
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "renamer-backup.json")
                    }
                    pendingBackupJson = json
                    startActivityForResult(intent, 400)
                } else {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    startActivityForResult(
                        Intent.createChooser(intent, L("انتخاب فایل بکاپ", "Choose a backup file")), 401)
                }
            }
            .show().also { styleDialogRtl(it) }
    }

    private var pendingBackupJson: String? = null

    private fun showAbout() {
        val info = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (_: Exception) { null }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("درباره", "About"))
            .setMessage(L("اینترنت آزاد 2026\nنسخه ${info?.versionName ?: "?"}\nهسته: Xray (مانند v2rayNG)\nموتور MASQUE رایگان: aether 2.3.0 (AGPL-3.0) — github.com/mbm110/MSN-GUARD\ncreated by : kamyar", "Internet Azad 2026\nVersion ${info?.versionName ?: "?"}\nCore: Xray (like v2rayNG)\nFree MASQUE engine: aether 2.3.0 (AGPL-3.0) — github.com/mbm110/MSN-GUARD\ncreated by : kamyar"))
            .setPositiveButton(L("بستن", "Close"), null)
            .show().also { styleDialogRtl(it) }
    }

    override fun onResume() {
        super.onResume()
        // 2.86: the theme is built once in onCreate; if the
        // user flipped day/night in Settings while away,
        // rebuild the page instead of showing stale colours.
        if (::frameView.isInitialized && Theme.isDark(this) != pageDark) {
            recreate()
            return
        }
        handler.post(statusPoller)
        handler.post(cfFlagToggle)
        handler.post(alienFlagToggle)
        // 1.41: re-evaluate the free box's guide
        // third (and restart its gentle pulse) on
        // return.
        renderFreeBox()
        // Hint next to the FAB, for 5 seconds, until the
        // first long-press has ever happened.
        if (!modeDone() && ::hintText.isInitialized) {
            hintText.visibility = View.VISIBLE
            handler.postDelayed({
                if (!modeDone()) hintText.visibility = View.GONE
            }, 5000)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusPoller)
        handler.removeCallbacks(cfFlagToggle)
        handler.removeCallbacks(alienFlagToggle)
        // 1.36: the tutorial overlay must not outlive
        // the visible page (the guide third stays
        // until the first fetch adds a config, so the
        // user can restart it; the fetch itself keeps
        // running in the background).
        endV2rayTutorial()
        // 1.41: stop the free box's guide pulse while
        // away (onResume restarts it if the guide
        // third shows).
        try { freeGuidePulseAnim?.cancel() } catch (_: Exception) {}
        freeGuidePulseAnim = null
    }

    private fun saveServers() {
        prefs().edit()
            .putString("servers", servers.joinToString("\n"))
            .putString("selected", selected ?: "")
            .apply()
    }

    // toTop = true only for clipboard import: the
    // user asked clipboard configs to land at the
    // TOP of the list, not the bottom. Every other
    // import path keeps appending.
    private fun addFromText(text: String, toTop: Boolean = false) {
        val seen = servers.map { XraySpeedtest.dedupKey(it) }.toMutableSet()
        val fresh = mutableListOf<String>()
        for (l in XraySpeedtest.splitConfigs(text)) {
            val c = l.trim()
            if (c.isBlank()) continue
            if (!c.contains("://") && !XraySpeedtest.isCustom(c)) continue
            if (seen.add(XraySpeedtest.dedupKey(c))) fresh += c
        }
        if (toTop) servers.addAll(0, fresh) else servers += fresh
        val added = fresh.size
        if (added == 0) {
            statusText.text = L("کانفیگ جدیدی اضافه نشد.", "No new config added.")
        } else {
            if (selected == null) selected = servers.firstOrNull()
            saveServers(); refreshList()
            statusText.text = L("$added کانفیگ اضافه شد.", "$added configs added.")
        }
        autoDedupIfEnabled()
    }

    /** Shows a message on screen for exactly 3 seconds
     *  as a temporary overlay TextView on the root frame
     *  (not a Toast, so the duration is exact). A new
     *  message replaces the old one and restarts the
     *  3-second timer. */
    private fun showGeoMessage(text: String) {
        if (!::frameView.isInitialized) return
        handler.removeCallbacks(geoOverlayRemover)
        geoOverlay?.let { frameView.removeView(it) }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.parseColor(if (pageDark) "#FFFFFF" else "#333333"))
            setPadding(32, 24, 32, 24)
            gravity = Gravity.CENTER
            // 2.86: the toast pill follows the theme — dark
            // glass with a gradient border in night, pearl
            // glass in day.
            background = if (pageDark)
                GradientGlassDrawable(Theme.darken(Color.parseColor("#CC232840")), true,
                    24f * resources.displayMetrics.density,
                    1.25f * resources.displayMetrics.density)
            else android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(Color.parseColor("#F2FFFFFF"))
                setStroke(2, Color.parseColor("#DDDDDD"))
            }
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(32, 0, 32, 200)
            }
        }
        geoOverlay = tv
        frameView.addView(tv)
        frameView.bringChildToFront(tv)
        handler.postDelayed(geoOverlayRemover, 3000)
    }

    /** Configs visible under the current tab:
     *  all servers, one sub's configs (by the
     *  membership recorded at its last update), or
     *  one manual group's range. Configs fetched
     *  before the tab feature have no membership and
     *  show under All only, until their sub updates. */
    private fun visibleConfigs(): List<String> {
        if (selectedTab.isBlank()) return servers
        if (selectedTab == "CF::CLOUDFLARE") {
            val cm = loadCfMap()
            return servers.filter { cfg ->
                !XraySpeedtest.isGroup(cfg) &&
                    cm.containsKey(XraySpeedtest.dedupKey(cfg))
            }
        }
        if (selectedTab.startsWith("GROUP::")) {
            return VpnSubs.groupMembers(servers, selectedTab)
        }
        val items = VpnSubs.loadItems(this)
        val item = items.firstOrNull { it.url == selectedTab } ?: return servers
        return servers.filter { cfg ->
            !XraySpeedtest.isGroup(cfg) &&
                item.configKeys.contains(XraySpeedtest.dedupKey(cfg))
        }
    }

    /** Rebuild the tab bar: "All configs" first,
     *  then one tab per subscription (name + count),
     *  then manual group tabs when the show-all-groups
     *  setting is on. The selection survives when its
     *  tab still exists, otherwise it falls back to All. */
    private fun refreshTabBar() {
        if (!::tabBar.isInitialized) return
        val items = VpnSubs.loadItems(this)
        val showGroups = prefs().getBoolean("show_all_groups", false)
        val tabs = mutableListOf<Pair<String, String>>()
        tabs += "" to L("تمام کانفیگ‌ها", "All configs")
        // Cloudflare tab (NOT a subscription: it never
        // appears in the subscriptions section, and it
        // cannot be edited there — it vanishes when its
        // last config is deleted.
        val cm = loadCfMap()
        val cfCount = servers.count { cfg ->
            !XraySpeedtest.isGroup(cfg) &&
                cm.containsKey(XraySpeedtest.dedupKey(cfg))
        }
        if (cfCount > 0) {
            tabs += "CF::CLOUDFLARE" to "${L("کلادفلر", "Cloudflare")} ($cfCount)"
        }
        for (item in items) {
            val count = servers.count { cfg ->
                !XraySpeedtest.isGroup(cfg) &&
                    item.configKeys.contains(XraySpeedtest.dedupKey(cfg))
            }
            tabs += item.url to "${item.displayName()} ($count)"
        }
        if (showGroups) {
            for (cfg in servers) {
                if (XraySpeedtest.isGroup(cfg)) {
                    tabs += cfg to XraySpeedtest.groupName(cfg)
                }
            }
        }
        if (selectedTab.isNotBlank() && tabs.none { it.first == selectedTab }) {
            selectedTab = ""
        }
        // 1.39 (item 4): when «تمام کانفیگ‌ها» is
        // the ONLY tab, the tab bar is just a header
        // row (title + orange underline) — hide it,
        // so the page starts directly with the free
        // button. With subscription / Cloudflare /
        // group tabs present the bar stays: then it
        // is navigation, not a header. Filtering is
        // untouched (selectedTab "" still means All),
        // and the list itself is unchanged.
        if (tabs.size <= 1) {
            tabBar.removeAllViews()
            tabScroll.visibility = View.GONE
            return
        }
        tabScroll.visibility = View.VISIBLE
        tabBar.removeAllViews()
        // 2.92 RTL: re-apply on every rebuild — refreshTabBar
        // recreates every tab, so the onCreate direction alone
        // never reached the visible tabs. First tab rightmost.
        tabScroll.layoutDirection = rtlDir()
        tabBar.layoutDirection = rtlDir()
        // 2.93 RTL: scroll to the START edge in a direction-aware
        // way. scrollTo(0, 0) is a physical offset, so in Persian
        // it can land on the wrong end of the strip; fullScroll
        // shows the first tab (rightmost in Persian, leftmost in
        // English) whatever the framework does with scrollTo.
        tabScroll.post { try {
            if (rtlDir() == View.LAYOUT_DIRECTION_RTL) tabScroll.fullScroll(View.FOCUS_RIGHT)
            else tabScroll.fullScroll(View.FOCUS_LEFT)
        } catch (_: Exception) {} }
        val d = resources.displayMetrics.density
        for ((key, label) in tabs) {
            val isSel = key == selectedTab
            val tv = TextView(this).apply {
                text = label; textSize = 14f
                setTypeface(null, if (isSel) android.graphics.Typeface.BOLD
                    else android.graphics.Typeface.NORMAL)
                setTextColor(if (isSel) Color.parseColor("#E8913A")
                    else vpnMuted())
                // 2.91 RTL: Persian tab text starts from the right.
                layoutDirection = rtlDir()
                textDirection = appTextDir()
                gravity = Gravity.START
                setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
                setOnClickListener { selectedTab = key; refreshList() }
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = rtlDir()
                addView(tv)
                addView(View(this@VpnActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (2 * d).toInt())
                    setBackgroundColor(if (isSel) Color.parseColor("#E8913A")
                        else Color.TRANSPARENT)
                })
            }
            tabBar.addView(col)
        }
    }

    private fun confirmDelete(name: String, onConfirm: () -> Unit) {
        if (!prefs().getBoolean("confirm_delete", false)) {
            onConfirm()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("حذف کانفیگ", "Delete config"))
            .setMessage(L("کانفیگ «$name» حذف شود؟", "Delete config “$name”?"))
            .setPositiveButton(L("حذف", "Delete")) { _, _ -> onConfirm() }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show().also { styleDialogRtl(it) }
    }

    /** Country code for a Cloudflare-imported config, or null. */
    private fun cfCountryOf(cfg: String): String? {
        return cfMap[XraySpeedtest.dedupKey(cfg)]
    }

    private fun cfDisplayName(code: String): String {
        return when (code) {
            "de" -> L("کلودفلر - آلمان", "Cloudflare - Germany")
            "nl" -> L("کلودفلر - هلند", "Cloudflare - Netherlands")
            "fr" -> L("کلودفلر - فرانسه", "Cloudflare - France")
            "cn" -> L("کلودفلر - چین", "Cloudflare - China")
            "us" -> L("کلودفلر - آمریکا", "Cloudflare - United States")
            "ca" -> L("کلودفلر - کانادا", "Cloudflare - Canada")
            "gb" -> L("کلودفلر - انگلیس", "Cloudflare - United Kingdom")
            "nop" -> L("کلودفلر - بدون پروکسی", "Cloudflare - No proxy")
            else -> L("کلودفلر", "Cloudflare")
        }
    }

    private fun cfCountryName(code: String): String {
        return when (code) {
            "de" -> L("آلمان", "Germany")
            "nl" -> L("هلند", "Netherlands")
            "fr" -> L("فرانسه", "France")
            "cn" -> L("چین", "China")
            "us" -> L("آمریکا", "United States")
            "ca" -> L("کانادا", "Canada")
            "gb" -> L("انگلیس", "United Kingdom")
            "nop" -> L("بدون پروکسی", "No proxy")
            else -> L("نامشخص", "unknown")
        }
    }

    /** Flag emoji drawn onto a bitmap, so the row's ImageView can
     *  alternate between the flag and the Cloudflare icon. */
    private fun flagBitmap(flag: String): android.graphics.Bitmap {
        val size = 96
        val bmp = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 64f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(flag, size / 2f, y, paint)
        return bmp
    }

    /** Alien-imported dedupKeys, fresh from
     *  prefs every time, like loadCfMap. */
    private fun loadAlienSet(): Set<String> {
        return try {
            val arr = org.json.JSONArray(
                getSharedPreferences("renamer_vpn", MODE_PRIVATE)
                    .getString("alien_configs", "[]") ?: "[]")
            val s = mutableSetOf<String>()
            for (i in 0 until arr.length()) s += arr.optString(i)
            s
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun alienBitmap(): android.graphics.Bitmap {
        alienBitmapCache?.let { return it }
        val bmp = try {
            val raw = android.graphics.BitmapFactory.decodeStream(assets.open("alien.png"))
            android.graphics.Bitmap.createScaledBitmap(raw, 96, 96, true)
        } catch (_: Exception) {
            flagBitmap("\uD83D\uDC7D")
        }
        alienBitmapCache = bmp
        return bmp
    }

    /** Deep galaxy card for alien rows (darker
     *  than Cloudflare's soft lavender tint). */
    private fun galaxyCardBg(selected: Boolean = false): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#1A237E"),
                Color.parseColor("#4A148C"),
                Color.parseColor("#006064")
            )).apply {
            cornerRadius = 20f * resources.displayMetrics.density
            // 2.86: a selected Alien row keeps the green
            // border (the old override used to drop it).
            setStroke(((if (selected) 2 else 1) * resources.displayMetrics.density).toInt(),
                Color.parseColor(if (selected) "#2E9E5B" else "#7E57C2"))
        }
    }

    /** Light text for a galaxy card: name white,
     *  host pale grey, ping light green (or pale
     *  grey when unmeasured). Children order is
     *  fixed by the row builder: dot, info, ping. */
    private fun recolorRowForGalaxy(row: android.widget.LinearLayout) {
        try {
            val info = row.getChildAt(1)
            if (info is android.view.ViewGroup) {
                for (i in 0 until info.childCount) {
                    val tv = info.getChildAt(i)
                    if (tv is android.widget.TextView) {
                        tv.setTextColor(if (i == 0) Color.WHITE else Color.parseColor("#CFD8DC"))
                    }
                }
            }
            val ping = row.getChildAt(2)
            if (ping is android.widget.TextView) {
                ping.setTextColor(
                    if (ping.text.toString() == "—") Color.parseColor("#B0BEC5")
                    else Color.parseColor("#A5D6A7"))
            }
        } catch (_: Exception) {}
    }

    /** Light text for a dark card that is NOT the galaxy card
     *  (Cloudflare rows in night mode): name white, host pale
     *  grey, ping light green (or pale grey when unmeasured).
     *  Children order is fixed by the row builder: dot, info,
     *  ping — same shape as recolorRowForGalaxy. */
    private fun recolorRowForDark(row: android.widget.LinearLayout) {
        try {
            val info = row.getChildAt(1)
            if (info is android.view.ViewGroup) {
                for (i in 0 until info.childCount) {
                    val tv = info.getChildAt(i)
                    if (tv is android.widget.TextView) {
                        tv.setTextColor(if (i == 0) Color.WHITE else Color.parseColor("#B0BEC5"))
                    }
                }
            }
            val ping = row.getChildAt(2)
            if (ping is android.widget.TextView) {
                ping.setTextColor(
                    if (ping.text.toString() == "—") Color.parseColor("#B0BEC5")
                    else Color.parseColor("#A5D6A7"))
            }
        } catch (_: Exception) {}
    }

    private fun cloudBitmap(): android.graphics.Bitmap {
        cfCloudBitmap?.let { return it }
        val bmp = try {
            val raw = android.graphics.BitmapFactory.decodeStream(assets.open("cf.png"))
            android.graphics.Bitmap.createScaledBitmap(raw, 96, 96, true)
        } catch (_: Exception) {
            flagBitmap("☁️")
        }
        cfCloudBitmap = bmp
        return bmp
    }

    /** Cloudflare import map (dedupKey -> country code), fresh
     *  from prefs every time, so the tab count never goes stale. */
    private fun loadCfMap(): Map<String, String> {
        return try {
            val o = org.json.JSONObject(
                getSharedPreferences("renamer_cf", MODE_PRIVATE)
                    .getString("cf_imported", "") ?: ""
            )
            val m = mutableMapOf<String, String>()
            for (k in o.keys()) m[k] = o.optString(k, "")
            m
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Flag emoji for a Cloudflare country code: the fixed
     *  four, or a scanned country's stored flag. */
    private fun cfFlagEmoji(code: String): String {
        when (code) {
            "de" -> return "🇩🇪"
            "nl" -> return "🇳🇱"
            "fr" -> return "🇫🇷"
            "cn" -> return "🇨🇳"
            "us" -> return "🇺🇸"
            "ca" -> return "🇨🇦"
            "gb" -> return "🇬🇧"
        }
        return try {
            val arr = org.json.JSONArray(
                getSharedPreferences("renamer_cf", MODE_PRIVATE)
                    .getString("cf_proxy_countries", "[]") ?: "[]"
            )
            var out = ""
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("code", "") == code) {
                    out = o.optString("flag", "")
                    break
                }
            }
            out
        } catch (_: Exception) {
            ""
        }
    }

    // rows for configs this core cannot connect
    // (hysteria2/hy2/tuic have no outbound) must not
    // look normal — they get an honest suffix, and
    // the first sighting is logged once (generic).
    private val testLoggedUnsupported = mutableSetOf<String>()
    private fun unsupportedSuffix(cfg: String): String {
        val scheme = cfg.substringBefore("://").lowercase()
        if (scheme != "hysteria2" && scheme != "hy2" && scheme != "tuic") return ""
        val unsupported = try { XraySpeedtest.outboundFor(cfg) == null } catch (_: Exception) { false }
        if (!unsupported) return ""
        if (testLoggedUnsupported.add(XraySpeedtest.dedupKey(cfg))) {
                    }
        return L(" (پشتیبانی نمی‌شود)", " (not supported)")
    }

    /** one central-log line per connect attempt when a TEST parser
     *  fix actually applies to this config (Trojan password fallback,
     *  WS path from the link's own path). Generic: never the config. */
    private fun testLogConnectFixes(cfg: String) {
        try {
            val raw = cfg.substringBefore("#")
            if (raw.startsWith("trojan://") && raw.contains("@")) {
                val ui = raw.removePrefix("trojan://").substringBefore("@")
                if (ui.isNotBlank() && !ui.contains(":")) {
                                    }
            }
            val scheme = raw.substringBefore("://").lowercase()
            if (scheme == "vless" || scheme == "trojan") {
                val uri = java.net.URI(raw)
                val params = (uri.rawQuery ?: "").split("&").mapNotNull {
                    val kv = it.split("=", limit = 2)
                    if (kv.size == 2) kv[0] to kv[1] else null
                }.toMap()
                val p = uri.path ?: ""
                if (params["type"] == "ws" && params["path"] == null && p.isNotBlank() && p != "/") {
                                    }
            }
        } catch (_: Exception) {}
    }

    private fun refreshList() {
        cfFlagViews.clear()
        cfMap = loadCfMap()
        alienFlagViews.clear()
        alienSet = loadAlienSet()
        refreshTabBar()
        listContainer.removeAllViews()
        val visible = visibleConfigs()
        if (visible.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = if (selectedTab.isBlank())
                    L("هنوز کانفیگی اضافه نشده است.", "No config added yet.")
                else
                    L("این زبانه کانفیگی ندارد.", "This tab has no configs.")
                setTextColor(vpnMuted())
                // 2.91 RTL: Persian text starts from the right.
                layoutDirection = rtlDir()
                textDirection = appTextDir()
                gravity = Gravity.START
            })
            return
        }
        val doubleCol = prefs().getBoolean("double_column", false)
        val cards = mutableListOf<View>()
        for ((vi, cfg) in visible.withIndex()) {
            val isGroup = XraySpeedtest.isGroup(cfg)
            val srv = XraySpeedtest.parseServer(cfg)
            val cfCountry = cfCountryOf(cfg)
            val name = cfCountry?.let { cfDisplayName(it) } ?: XraySpeedtest.displayName(cfg)
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            // 1.36: tag the card with its config so the
            // tutorial can find the first free row.
            col.tag = cfg
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 2.93 RTL: the row itself follows the app language —
                // in Persian the row starts from the right (radio
                // rightmost, name/address right-aligned, action
                // buttons leftmost). The 2.92 text-only direction
                // left the row LTR, so the whole card still started
                // from the left on device.
                layoutDirection = rtlDir()
                // 1.33: 12dp sides (the card has no horizontal
                // padding anymore — the protocol pill must sit
                // flush on its edge) and 4dp top/bottom, so the
                // 2x2 button grid (24 + 2 + 24 = 50dp) still
                // fits the 60dp row minimum.
                setPadding(
                    (12 * resources.displayMetrics.density).toInt(),
                    (4 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(),
                    (4 * resources.displayMetrics.density).toInt()
                )
                // Uniform row height (1.79): every
                // row is at least 60dp, so a group
                // header (one line) is exactly as
                // tall as a config row (two lines).
                minimumHeight = (60 * resources.displayMetrics.density).toInt()
            }
            if (isGroup) {
                // Group header: selectable now (tapping
                // it selects the group, and connecting
                // with a group selected balances across
                // its members — see startVpn).
                val isSel = cfg == selected
                row.addView(TextView(this).apply {
                    text = "▾  $name"; textSize = 16f
                    setTextColor(vpnGreen())
                    // 2.91 RTL: Persian group name starts from the
                    // right. (layoutDirection on the TextView itself —
                    // the row keeps LTR child order.)
                    layoutDirection = rtlDir()
                    textDirection = appTextDir()
                    gravity = Gravity.START
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(glassCircleButton("✕") {
                    confirmDelete(name) {
                        servers.remove(cfg)
                        if (selected == cfg) selected = null
                        saveServers(); refreshList()
                    }
                })
                row.setOnClickListener {
                    selected = cfg; saveServers(); refreshList()
                }
                row.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val card = serverCard(isSel)
                card.addView(row)
                card.addView(protocolEdgeLabel("GROUP"))
                col.addView(card)
                cards += col
                continue
            }
            val isSel = cfg == selected
            // 1.33 (items 10 + 11): a free row is identified
            // by the @internet_azad_2026( prefix — it shows
            // no server address under its name and no share
            // or edit button. Own rows keep everything.
            val isFree = FreeConfigs.isFreeConfig(cfg)
            row.addView(TextView(this).apply {
                text = if (isSel) "●" else "○"; textSize = 20f
                setTextColor(if (isSel) vpnGreen()
                    else if (pageDark) Color.parseColor("#8A90A0") else Color.GRAY)
                setPadding(0, 0, 12, 0)
            })
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // 2.91 RTL: name/host text starts from the right.
                layoutDirection = rtlDir()
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = name + unsupportedSuffix(cfg); textSize = 16f
                // 2.86: white on the dark night card, dark on
                // the white day card (the 1.79 fix was dark
                // text on the old pale card).
                // Single line + ellipsis keeps every
                // row the same height: a long name
                // used to wrap and make its row taller.
                setTextColor(vpnNameColor())
                // 2.91 RTL: Persian name starts from the right.
                layoutDirection = rtlDir()
                textDirection = appTextDir()
                gravity = Gravity.START
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            // 1.33 (item 10): a free row shows NO server
            // IP/address line under its name. Own rows
            // keep it.
            if (!isFree) {
                info.addView(TextView(this).apply {
                    text = "${srv?.host ?: "?"}:${srv?.port ?: "?"}"
                    textSize = 13f; setTextColor(vpnHostColor())
                    // 2.91 RTL: Persian host text starts from the right.
                    layoutDirection = rtlDir()
                    textDirection = appTextDir()
                    gravity = Gravity.START
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            row.addView(info)
            // 1.41 (C): pingMap first, persisted
            // ping_delays as the fallback (see
            // addOneFree) — without it a page
            // re-entry left every row at "—". A
            // stored negative value is not a ping.
            val ping = pingMap[XraySpeedtest.dedupKey(cfg)]
                ?: persistedPingMs(cfg)?.takeIf { it >= 0 }
            row.addView(TextView(this).apply {
                text = if (ping == null) "—" else "$ping ms"
                textSize = 14f
                setTextColor(if (ping != null && ping >= 0) vpnGreen() else vpnHostColor())
                setPadding(12, 0, 12, 0)
            })
            // Geo flag circle: for the connected config
            // the country is measured through the tunnel
            // (see GeoFlag); for a free config it is the
            // country determined during the ping test (see
            // GeoFlag.lookupForConfig); otherwise it is
            // the host's country. While the lookup runs it
            // shows 🌐.
            val host = srv?.host ?: ""
            // 1.33 (item 9): the flag is one cell of the
            // 2x2 action grid (see actionGrid below) — the
            // three branches only BUILD it now, they no
            // longer add it to the row themselves. Flag
            // circles are 24dp, like the other small ones.
            val flagView: View
            if (cfCountry != null) {
                // Cloudflare import: an ImageView alternating
                // every 2s between the country flag and the
                // Cloudflare section icon (see cfFlagToggle).
                val flagEmoji = cfFlagEmoji(cfCountry)
                val flagBmp = flagBitmap(flagEmoji)
                val cloudBmp = cloudBitmap()
                val fv = android.widget.ImageView(this).apply {
                    val d = resources.displayMetrics.density
                    val size = (24 * d).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        setMargins((2 * d).toInt(), 0, (2 * d).toInt(), 0)
                    }
                    background = lightOval(
                        Color.parseColor("#E8FFFFFF"),
                        Color.parseColor("#55000000"), 1f
                    )
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    setPadding((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
                    setImageBitmap(if (cfFlagShowCloud) cloudBmp else flagBmp)
                    setOnClickListener {
                        showGeoMessage(
                            L(
                                "این کانفیگ کلودفلر برای کشور ${cfCountryName(cfCountry)} است",
                                "This Cloudflare config is for ${cfCountryName(cfCountry)}"
                            )
                        )
                    }
                }
                cfFlagViews += Triple(fv, flagBmp, cloudBmp)
                flagView = fv
            } else if (alienSet.contains(XraySpeedtest.dedupKey(cfg))) {
                // Alien import: a circle alternating
                // every 2s between the geo flag and
                // the alien section icon — the same
                // trick as Cloudflare's flag/cloud.
                val alienBmp = alienBitmap()
                val holder = AlienFlagState(alienBmp, flagBitmap("🌐"))
                val alienView = android.widget.ImageView(this).apply {
                    val d = resources.displayMetrics.density
                    val size = (24 * d).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        setMargins((2 * d).toInt(), 0, (2 * d).toInt(), 0)
                    }
                    background = lightOval(
                        Color.parseColor("#E8FFFFFF"),
                        Color.parseColor("#55000000"), 1f
                    )
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    setPadding((2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt(), (2 * d).toInt())
                    setImageBitmap(if (alienFlagShowAlien) alienBmp else holder.flagBmp)
                    setOnClickListener {
                        showGeoMessage(
                            if (holder.country.isNotBlank()) L(
                                "این کانفیگ از بخش زبان بیگانه آمده است؛ کشور ${holder.country} و شهر ${holder.city}",
                                "This config came from the Alien Language section; ${holder.country}, ${holder.city}"
                            ) else L(
                                "این کانفیگ از بخش زبان بیگانه آمده است",
                                "This config came from the Alien Language section"
                            )
                        )
                    }
                }
                holder.view = alienView
                alienFlagViews += holder
                if (host.isNotBlank()) {
                    GeoFlag.lookupForConfig(this, cfg, host) { cc, country, city ->
                        holder.flagBmp = flagBitmap(GeoFlag.flagEmoji(cc))
                        holder.country = country
                        holder.city = city
                        if (!alienFlagShowAlien) alienView.setImageBitmap(holder.flagBmp)
                    }
                }
                flagView = alienView
            } else {
                var geoCountry = ""
                var geoCity = ""
                val flagBtn = smallGlassCircleButton("🌐", 12f) {
                    if (geoCountry.isBlank() && geoCity.isBlank()) {
                        showGeoMessage(L("کشور این کانفیگ مشخص نشد", "This config's country is unknown"))
                    } else {
                        val countryPart = if (geoCountry.isNotBlank()) geoCountry else L("نامشخص", "unknown")
                        val cityPart = if (geoCity.isNotBlank()) geoCity else L("نامشخص", "unknown")
                        showGeoMessage(L("این کانفیگ برای کشور $countryPart و شهر $cityPart است", "This config is for $countryPart, city $cityPart"))
                    }
                }
                if (host.isNotBlank()) {
                    GeoFlag.lookupForConfig(this, cfg, host) { cc, country, city ->
                        geoCountry = country
                        geoCity = city
                        flagBtn.text = GeoFlag.flagEmoji(cc)
                    }
                }
                flagView = flagBtn
            }
            // 1.33 (items 9 + 11): the four circles as a
            // 2x2 grid — first line flag + share, second
            // line delete + edit. A free row has NO share
            // and NO edit button; the empty slots keep the
            // grid and the row height. Chains never had a
            // share button. Own rows keep all four.
            val deleteBtn = smallGlassCircleButton("✕") {
                confirmDelete(name) {
                    val wasSelected = cfg == selected
                    servers.remove(cfg)
                    // 1.40 (A2): a deleted free row
                    // leaves the fetched set, so a
                    // re-tap can fetch it again.
                    pruneFreeFetchedKeys(listOf(cfg))
                    if (wasSelected) selected = servers.firstOrNull()
                    saveServers(); refreshList(); refreshStatus()
                }
            }
            val shareBtn: View? =
                if (!isFree && !XraySpeedtest.isChain(cfg))
                    smallGlassCircleButton("↗") { showQrFor(cfg) } else null
            val editBtn: View? =
                if (!isFree) smallGlassCircleButton("✎") { editConfig(cfg) } else null
            // 1.39 (item 2): free rows show flag + delete
            // on ONE horizontal line at the right side
            // (freeActionRow); own rows keep the 2x2 grid.
            row.addView(if (isFree) freeActionRow(flagView, deleteBtn)
                else actionGrid(flagView, shareBtn, deleteBtn, editBtn))
            row.setOnClickListener {
                selected = cfg; saveServers(); refreshList()
                // 1.36: a free config picked in tutorial
                // step 3 moves the spotlight to the orb.
                onV2rayTutRowSelected(cfg)
            }
            // Drag to reorder (v2rayNG-style): long-press a
            // row, drop it on another row. Only in the All
            // tab, where the visible list IS the saved list.
            row.setOnLongClickListener {
                if (selectedTab.isNotBlank()) {
                    showGeoMessage(L("جابجایی فقط در «تمام کانفیگ‌ها» ممکن است.", "Reordering is only possible in All configs."))
                    false
                } else {
                    dragFrom = vi
                    val data = android.content.ClipData.newPlainText("index", vi.toString())
                    row.startDragAndDrop(data, View.DragShadowBuilder(row), null, 0)
                    true
                }
            }
            row.setOnDragListener { _, ev ->
                when (ev.action) {
                    android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                        row.setBackgroundColor(if (pageDark) Theme.darken(Color.parseColor("#2E3A55")) else Color.parseColor("#E3F2FD"))
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_EXITED,
                    android.view.DragEvent.ACTION_DRAG_ENDED -> {
                        row.background = null
                        if (ev.action == android.view.DragEvent.ACTION_DRAG_ENDED) dragFrom = -1
                        true
                    }
                    android.view.DragEvent.ACTION_DROP -> {
                        val from = dragFrom
                        dragFrom = -1
                        row.background = null
                        if (from >= 0 && from != vi && selectedTab.isBlank()) {
                            val list = servers.toMutableList()
                            val item = list.removeAt(from)
                            // Drop position: upper half ->
                            // before this row, lower half ->
                            // after it.
                            var to = if (ev.y < row.height / 2f) vi else vi + 1
                            if (from < to) to -= 1
                            if (to < 0) to = 0
                            if (to > list.size) to = list.size
                            list.add(to, item)
                            servers.clear()
                            servers.addAll(list)
                            saveServers()
                        }
                        refreshList()
                        true
                    }
                    else -> true
                }
            }
            row.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            val card = serverCard(isSel)
            if (cfCountry != null) {
                // Soft galaxy tint, so Cloudflare imports stand
                // apart from ordinary configs at a glance — in
                // night a violet glass (see vpnCfCardBg) with
                // light text, in day the same lavender as before.
                card.background = vpnCfCardBg(isSel)
                if (pageDark) recolorRowForDark(row)
            } else if (alienSet.contains(XraySpeedtest.dedupKey(cfg))) {
                // Alien import: deep galaxy card with
                // light text, apart from both ordinary
                // and Cloudflare rows.
                card.background = galaxyCardBg(isSel)
                recolorRowForGalaxy(row)
            } else if (isFree) {
                // 1.41 (A): free rows wear the free
                // box's background at 50% paler.
                card.background = freeRowBg(isSel)
            }
            card.addView(row)
            card.addView(
                protocolEdgeLabel(
                    cfCountry?.let { L("کلودفلر", "Cloudflare") }
                        ?: XraySpeedtest.fullType(cfg)
                )
            )
            col.addView(card)
            if (isSel) selectedRowView = col
            cards += col
        }
        if (doubleCol && cards.size > 1) {
            // Two cards per row: each card takes half
            // the width, with a small gap between them.
            val d = resources.displayMetrics.density
            var i = 0
            while (i < cards.size) {
                val pairRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                val first = cards[i]
                first.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins(0, 0, (4 * d).toInt(), 0) }
                pairRow.addView(first)
                if (i + 1 < cards.size) {
                    val second = cards[i + 1]
                    second.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { setMargins((4 * d).toInt(), 0, 0, 0) }
                    pairRow.addView(second)
                }
                listContainer.addView(pairRow)
                i += 2
            }
        } else {
            for (c in cards) listContainer.addView(c)
        }
    }

    // True once the strip has announced the
    // current connection. The poller must NEVER
    // rewrite the strip every 2s (that overwrite
    // is what used to wipe every message before
    // the user could read it, which is why the
    // auto toggles looked fake) — but it MAY
    // announce the connect TRANSITION once: after
    // v1.58 the strip stayed on "Connecting..."
    // forever, because nothing ever wrote the
    // success. One transition line, then silence.
    private var stripAnnouncedConnected = false
    // 2.13: last service error already shown in the strip.
    private var lastShownServiceError: String? = null

    private fun refreshStatus() {
        refreshFab()
        val connectedNow = (VpnClientService.isRunning &&
            VpnClientService.sessionTag in setOf("v2ray", "smart", null)) ||
            ProxyOnlyService.isRunning
        if (connectedNow && !stripAnnouncedConnected) {
            stripAnnouncedConnected = true
            appLogConnected()
            val nm = VpnClientService.currentName
            val ping = selected?.let { pingMap[XraySpeedtest.dedupKey(it)] }
            val pingPart = if (ping != null && ping >= 0) "؛ پینگ $ping ms" else ""
            val pingPartEn = if (ping != null && ping >= 0) "; ping $ping ms" else ""
            if (ProxyOnlyService.isRunning && !VpnClientService.isRunning) {
                addStatusLine("با موفقیت متصل شد (حالت فقط پروکسی)$pingPart",
                    "Connected successfully (proxy-only mode)$pingPartEn")
            } else if (nm != null) {
                addStatusLine("با موفقیت به «$nm» متصل شد$pingPart",
                    "Connected successfully to “$nm”$pingPartEn")
            } else {
                addStatusLine("با موفقیت متصل شد$pingPart",
                    "Connected successfully$pingPartEn")
            }
        } else if (!connectedNow) {
            stripAnnouncedConnected = false
            // 2.13: a failed connect used to die silently (the
            // strip stayed on Connecting...). Show the service
            // stored reason once, instead of silence.
            val err = VpnClientService.lastError
            if (err != null && err != lastShownServiceError) {
                lastShownServiceError = err
                addStatusLine("خطا در اتصال: $err", "Connect error: $err")
                if (appLogPending) { appLogEvent("ناموفق بود: $err", "failed: $err"); appLogPending = false }
            }
        }
    }

    /** Manual disconnect: stops this page's session and writes the
     *  smart_manual_stop_until suppression — that suppression belongs
     *  ONLY to a manual disconnect (Smart regression guard). */

    private fun disconnectVpn() {
        val stopName = VpnClientService.currentName
        val stopTag = VpnClientService.sessionTag
        try { ProxyOnlyService.stop(this) } catch (_: Exception) {}
        val stopIntent = Intent(this, VpnClientService::class.java).apply {
            action = "STOP"
            putExtra("stop_gen", VpnClientService.sessionGen)
        }
        try { startService(stopIntent) } catch (_: Exception) {}
        VpnClientService.isRunning = false
        VpnClientService.currentName = null
        VpnClientService.sessionTag = null
        try { getSharedPreferences("renamer_hydra", MODE_PRIVATE).edit().putBoolean("hydra_active", false).apply() } catch (_: Exception) {}
        // Manual disconnect must stick: smart connect must not
        // immediately reconnect while the selected app is still foreground.
        try {
            getSharedPreferences("renamer_smart", MODE_PRIVATE).edit()
                .putLong("smart_manual_stop_until", System.currentTimeMillis() + 5 * 60 * 1000).apply()
        } catch (_: Exception) {}
        VpnClientService.emergencyConfig = null
        refreshStatus()
        addStatusLine("اتصال قطع شد.", "Disconnected.")
        if (stopName != null && (stopTag == null || stopTag == "v2ray" || stopTag == "smart")) {
            AppLog.append(this, stopTag ?: "v2ray",
                "«$stopName»؛ دستی قطع شد.", "“$stopName”; stopped manually.")
        }
        appLogPending = false
    }

    /** Direct connect, always: the engine is gone (1.29), so there is no
     *  selector step any more — the chosen config goes through the
     *  exact old connect path below. */
    private fun connectSelected(cfg: String) {
        // 1.36: the connection starting ends the
        // spotlight tutorial. 1.41: connecting no
        // longer hides the guide third — that hides
        // on the first FETCH that adds a config
        // (addOneFree), not on connect.
        endV2rayTutorial()
        appLogBegin(cfg)
        connectSelectedDirect(cfg)
    }



    /** 1.41 (A): the guide third of the free box is
     *  gone for good once the first fetch adds ≥1
     *  config (persisted v2ray_first_free_fetched
     *  in renamer_vpn). While it shows, it blinks
     *  gently and softly (alpha 1→0.6→1 over 2 s —
     *  the same slow cycle as the old guide ring).
     *  Called from onCreate, onResume and addOneFree. */
    private fun hasFirstFreeFetched(): Boolean = try {
        prefs().getBoolean("v2ray_first_free_fetched", false)
    } catch (_: Exception) { false }

    private fun renderFreeBox() {
        try {
            val show = !hasFirstFreeFetched()
            freeGuidePart?.visibility = if (show) View.VISIBLE else View.GONE
            freeGuideDivider?.visibility = if (show) View.VISIBLE else View.GONE
            freeGuidePulseAnim?.cancel()
            freeGuidePulseAnim = null
            if (show) {
                val part = freeGuidePart ?: return
                freeGuidePulseAnim = android.animation.ValueAnimator.ofFloat(1f, 0.6f, 1f).apply {
                    duration = 2000
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    addUpdateListener { part.alpha = it.animatedValue as Float }
                    start()
                }
            }
        } catch (_: Exception) {}
    }

    /** 1.35: step-by-step free-config guide — the
     *  v2ray answer to the OpenVPN guide. Same
     *  presentation as showOvpnGuide (titled
     *  sections, big clear text, day/night synced),
     *  written so simply that anyone can follow it:
     *  tap the free button, wait, pick one of the
     *  10 configs, tap the red orb, try the next
     *  one if it does not connect. */
    // ---------- 1.36: interactive spotlight tutorial (v2ray free flow) ----------
    // Replaces the static 5-step dialog (1.35 — the user
    // called it general and unprofessional). One continuous
    // overlay session: the user taps «راهنمای اتصال» once
    // and the tutorial stays on screen until the connection
    // happens — never a second tap on the guide button.
    // Mechanics: the page is dimmed 80% (GuideOverlayView,
    // dimColor #CC000000) EXCEPT the part the user must tap,
    // which stays bright in a spotlight hole; a short step
    // text bubble sits next to the hole; taps inside a hole
    // PASS THROUGH the overlay to the real button underneath
    // (the overlay's touch listener returns false there,
    // so the frame tries the child below it), taps anywhere
    // else are swallowed. Steps:
    //   1. spotlight the «دریافت کانفیگ رایگان» button —
    //      «روی دکمه دریافت کانفیگ رایگان بزنید» + (1.40)
    //      «فیلترشکن خود را خاموش کنید» underneath.
    //   2. (auto, when the fetch starts) waiting text while
    //      the fetch + ping test runs; the holes keep the
    //      «لغو دریافت» button AND the progress strip bright,
    //      so the live progress/ETA stays visible and the
    //      user can still cancel. Cancel → back to step 1;
    //      zero configs → a short message + back to step 1.
    //   3. (auto, when the 10 configs arrive) spotlight the
    //      first free config row — «یکی از کانفیگ‌ها را
    //      انتخاب کنید»; once one is selected, the spotlight
    //      moves to the red connect orb — «حالا روی دکمه
    //      اتصال بزنید». When the connection starts, the
    //      tutorial ends and the guide third hides (the
    //      1.41 hide rule: first fetch that adds a
    //      config, persisted flag
    //      v2ray_first_free_fetched).
    // A small «✕ رد کردن» skips out at any step; exiting
    // does NOT hide the guide button (it stays until the
    // first free connection). Back also exits. onPause
    // removes the overlay (the fetch itself keeps running).
    private fun startV2rayTutorial() {
        try {
            if (v2rayTutStep > 0) return
            if (hasFirstFreeFetched()) return
            // The free 10 only show in the All tab.
            if (selectedTab.isNotBlank()) {
                selectedTab = ""
                refreshList()
            }
            buildV2rayTutOverlay()
            // The free button lives near the top of the
            // scroll — bring it into view first.
            try { scrollView.scrollTo(0, 0) } catch (_: Exception) {}
            showV2rayTutStep(if (freeFetchRunning) 2 else 1)
        } catch (_: Exception) {}
    }

    private fun buildV2rayTutOverlay() {
        val overlay = android.widget.FrameLayout(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val dim = GuideOverlayView(this).apply {
            dimColor = Color.parseColor("#CC000000") // 80% dim
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
        }
        v2rayTutDim = dim
        overlay.addView(dim)
        // Step text bubble: short, one or two lines, the
        // same dialog card as the OpenVPN guide box.
        val bubble = TextView(this).apply {
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (pageDark) Color.parseColor("#EDEFF5") else Color.parseColor("#1B365D"))
            layoutDirection = rtlDir()
            textDirection = appTextDir()
            gravity = Gravity.CENTER
            setPadding(dpGuide(16), dpGuide(12), dpGuide(16), dpGuide(12))
            background = OvpnTheme.dialogCard(this@VpnActivity)
            visibility = View.INVISIBLE
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        v2rayTutBubble = bubble
        overlay.addView(bubble)
        // Skip / close: exits at any step, never hides the
        // guide button.
        val skip = TextView(this).apply {
            text = L("✕ رد کردن", "✕ Skip")
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (pageDark) Color.parseColor("#EDEFF5") else Color.parseColor("#1B365D"))
            layoutDirection = rtlDir()
            textDirection = appTextDir()
            gravity = Gravity.CENTER
            setPadding(dpGuide(12), dpGuide(6), dpGuide(12), dpGuide(6))
            background = OvpnTheme.dialogCard(this@VpnActivity)
            isClickable = true
            setOnClickListener { endV2rayTutorial() }
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(dpGuide(16), dpGuide(16), dpGuide(16), dpGuide(16))
            }
        }
        overlay.addView(skip)
        // Taps inside a spotlight hole pass through to the
        // real button underneath (return false → the frame
        // tries the next child below the overlay); taps
        // anywhere else are swallowed by the tutorial.
        // Children (the skip button) get the touch first,
        // so skipping still works.
        overlay.setOnTouchListener { _, ev ->
            val inHole = v2rayTutHolesScreen.any { r ->
                ev.rawX >= r.left && ev.rawX <= r.right &&
                    ev.rawY >= r.top && ev.rawY <= r.bottom
            }
            !inHole
        }
        frameView.addView(overlay)
        frameView.bringChildToFront(overlay)
        v2rayTutOverlay = overlay
    }

    private fun showV2rayTutStep(n: Int) {
        if (v2rayTutOverlay == null) return
        v2rayTutStep = n
        // Step 3 needs the list scrolled to the first free
        // row before its rect can be measured.
        if (n == 3) {
            scrollToFirstFreeRow { positionV2rayTutStep() }
            return
        }
        v2rayTutOverlay?.post { positionV2rayTutStep() }
    }

    /** Cut the spotlight hole(s) for the current step and
     *  place the bubble next to them. Below the target
     *  when it fits, else above; never covering the
     *  target itself. */
    private fun positionV2rayTutStep() {
        val overlay = v2rayTutOverlay ?: return
        val dim = v2rayTutDim ?: return
        val bubble = v2rayTutBubble ?: return
        val step = v2rayTutStep
        if (step <= 0) return
        if (overlay.width <= 0 || overlay.height <= 0) {
            overlay.post { positionV2rayTutStep() }
            return
        }
        val targets = mutableListOf<View>()
        when (step) {
            1 -> if (::freeBtn.isInitialized) targets += freeBtn
            2 -> {
                if (::freeBtn.isInitialized) targets += freeBtn
                if (::bottomRow.isInitialized) targets += bottomRow
            }
            3 -> findFirstFreeRowView()?.let { targets += it }
            4 -> targets += connectBtn
        }
        if (targets.isEmpty()) {
            // Step 3 with no free row left (deleted mid
            // tutorial): back to step 1 instead of a
            // hole-less dim.
            if (step == 3) { showV2rayTutStep(1); return }
            return
        }
        val oloc = IntArray(2)
        overlay.getLocationOnScreen(oloc)
        val holeRects = mutableListOf<android.graphics.RectF>()
        val screenRects = mutableListOf<android.graphics.RectF>()
        for (t in targets) {
            val loc = IntArray(2)
            t.getLocationOnScreen(loc)
            val r = android.graphics.RectF(
                loc[0].toFloat(), loc[1].toFloat(),
                (loc[0] + t.width).toFloat(), (loc[1] + t.height).toFloat())
            screenRects += r
            holeRects += android.graphics.RectF(
                r.left - oloc[0], r.top - oloc[1],
                r.right - oloc[0], r.bottom - oloc[1])
        }
        v2rayTutHolesScreen = screenRects
        dim.holes = holeRects
        dim.invalidate()
        bubble.text = when (step) {
            // 1.40 (C): fetch with the VPN OFF —
            // the second line is the user's order.
            1 -> L("روی دکمه دریافت کانفیگ رایگان بزنید\nفیلترشکن خود را خاموش کنید",
                "Tap the “Get free configs” button\nTurn off your VPN")
            2 -> L("کمی صبر کنید، برنامه در حال دریافت کانفیگ و تست کانفیگ‌هاست",
                "Please wait — the app is fetching configs and testing them")
            3 -> L("یکی از کانفیگ‌ها را انتخاب کنید",
                "Pick one of the configs")
            else -> L("حالا روی دکمه اتصال بزنید",
                "Now tap the connect button")
        }
        val wSpec = View.MeasureSpec.makeMeasureSpec(dpGuide(280), View.MeasureSpec.AT_MOST)
        val hSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        bubble.measure(wSpec, hSpec)
        val bw = bubble.measuredWidth
        val bh = bubble.measuredHeight
        val ow = overlay.width
        val oh = overlay.height
        if (bw <= 0 || bh <= 0) return
        val primary = holeRects.first()
        val margin = dpGuide(16)
        val gap = dpGuide(14)
        val bx = (primary.centerX() - bw / 2f).toInt()
            .coerceIn(margin, (ow - bw - margin).coerceAtLeast(margin))
        var by = run {
            val below = (primary.bottom + gap).toInt()
            if (below + bh <= oh - dpGuide(24)) below
            else (primary.top - gap - bh).toInt().coerceAtLeast(dpGuide(24))
        }
        by = by.coerceIn(dpGuide(24), (oh - bh - dpGuide(24)).coerceAtLeast(dpGuide(24)))
        (bubble.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
            it.width = bw
            it.height = bh
            it.leftMargin = bx
            it.topMargin = by
            it.gravity = Gravity.TOP or Gravity.START
            bubble.layoutParams = it
        }
        bubble.visibility = View.VISIBLE
    }

    /** The first free config's card (tagged with its
     *  config string in refreshList). Searches
     *  recursively — in two-column mode the cards sit
     *  inside pair rows. */
    private fun findFirstFreeRowView(): View? {
        if (!::listContainer.isInitialized) return null
        fun scan(parent: android.view.ViewGroup): View? {
            for (i in 0 until parent.childCount) {
                val c = parent.getChildAt(i)
                val tag = c.tag
                if (tag is String && FreeConfigs.isFreeConfig(tag)) return c
                if (c is android.view.ViewGroup) {
                    scan(c)?.let { return it }
                }
            }
            return null
        }
        return try { scan(listContainer) } catch (_: Exception) { null }
    }

    private fun scrollToFirstFreeRow(after: () -> Unit) {
        val row = findFirstFreeRowView()
        if (row == null) {
            showV2rayTutStep(1)
            return
        }
        try {
            val r = android.graphics.Rect()
            row.getDrawingRect(r)
            scrollView.offsetDescendantRectToMyCoords(row, r)
            scrollView.scrollTo(0, (r.top - dpGuide(8)).coerceAtLeast(0))
        } catch (_: Exception) {}
        // The scroll is instant — one post is enough for
        // the row's new on-screen rect.
        v2rayTutOverlay?.post { after() }
    }

    /** Fetch started: step 1 → waiting step 2. Called
     *  from startFreeFetch (UI thread). */
    private fun onV2rayTutFetchStarted() {
        if (v2rayTutStep == 1) showV2rayTutStep(2)
    }

    /** Fetch ended. ok = the 10 configs arrived (→ step
     *  3, or straight to step 4 when a free config is
     *  already selected). Not ok = cancelled (silent,
     *  back to step 1) or zero configs (short message,
     *  back to step 1). */
    private fun onV2rayTutFetchEnded(ok: Boolean, failed: Boolean = false) {
        if (v2rayTutStep != 2) return
        if (!ok) {
            if (failed) {
                try {
                    android.widget.Toast.makeText(this,
                        L("کانفیگی پیدا نشد؛ دوباره امتحان کن",
                            "No configs found; try again"),
                        android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
            showV2rayTutStep(1)
            return
        }
        val sel = selected
        if (sel != null && FreeConfigs.isFreeConfig(sel)) showV2rayTutStep(4)
        else showV2rayTutStep(3)
    }

    /** A row was tapped: a free config picked in step 3
     *  moves the spotlight to the connect orb. */
    private fun onV2rayTutRowSelected(cfg: String) {
        if (v2rayTutStep == 3 && FreeConfigs.isFreeConfig(cfg)) showV2rayTutStep(4)
    }

    /** End the tutorial. Exiting NEVER hides the guide
     *  button — it stays until the first free connection
     *  (the 1.35 persisted rule). */
    private fun endV2rayTutorial() {
        if (v2rayTutStep <= 0 && v2rayTutOverlay == null) return
        v2rayTutStep = 0
        v2rayTutHolesScreen = emptyList()
        try { v2rayTutOverlay?.let { frameView.removeView(it) } } catch (_: Exception) {}
        v2rayTutOverlay = null
        v2rayTutDim = null
        v2rayTutBubble = null
    }

    private fun dpGuide(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun connectSelectedDirect(cfg: String) {
        // Proxy-only mode runs the core without a TUN,
        // so it needs no VPN permission prompt.
        if (prefs().getString("vpn_mode", "VPN") == "Proxy only") {
            startVpn(cfg)
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, 100)
        } else {
            startVpn(cfg)
        }
    }

    private fun toggleConnect() {
        // Another section's session is up: NEVER
        // stop it from here, and never pretend this
        // button is connected. Say which section is
        // running and let the user stop it there.
        // (Manual control DOES override a smart
        // session: smart is automation over v2ray
        // configs, not a section of its own.)
        if (VpnClientService.isRunning) {
            when (VpnClientService.sessionTag) {
                "emergency", "hydra", "chain" -> {
                    statusText.text = L("اتصال «${VpnClientService.sectionLabel(this, VpnClientService.sessionTag)}» فعال است؛ اول آن را قطع کن.", "A «${VpnClientService.sectionLabel(this, VpnClientService.sessionTag)}» connection is active; stop it first.")
                    return
                }
            }
            disconnectVpn()
            return
        }
        if (ProxyOnlyService.isRunning) {
            disconnectVpn()
            return
        }
        if (connectMode() == "best") {
            fabBusy = true; refreshFab()
            handler.postDelayed({ if (fabBusy) { fabBusy = false; refreshFab() } }, 15000)
            connectBestPing()
            return
        }
        val cfg = selected
        if (cfg == null) {
            statusText.text = L("اول یک کانفیگ را انتخاب کن.", "Select a config first.")
            return
        }
        fabBusy = true; refreshFab()
        handler.postDelayed({ if (fabBusy) { fabBusy = false; refreshFab() } }, 15000)
        connectSelected(cfg)
    }

    private fun fabIdle() {
        fabBusy = false
        refreshFab()
    }

    /** Best-ping mode: ping all servers first, then
     *  connect to the lowest-ping healthy one. */
    /** Best-ping mode: ping all servers first, then
     *  connect to the lowest-ping healthy one.
     *  Fixed after the "smart does not work" report:
     *  every result now shows live (before, the page
     *  sat on "testing..." with zero feedback until
     *  everything finished, so a slow test looked
     *  dead); each measurement has a 30s timeout so
     *  one hung config cannot stall the whole run;
     *  chains and customs are excluded (they cannot
     *  be delay-tested as single outbounds, they only
     *  wasted pool slots). */
    /** Best-ping mode ("smart"): tap the FAB and the
     *  app ping-tests and connects to the lowest-ping
     *  healthy config — fast. Two earlier defects are
     *  fixed here. (1) Results used to be awaited in
     *  LIST order with a 30s timeout each, so one hung
     *  config stalled everything behind it while the
     *  page showed nothing (the 2s poller also wiped
     *  the "testing..." line). Now results are taken
     *  in COMPLETION order under a single 60s budget,
     *  and every finished result updates the strip
     *  live. (2) If the configs were already pinged
     *  this session, those results are reused at once
     *  and only untested ones are measured — the
     *  usual test-then-connect tap is instant.
     *  Cached all--1 (a previous fully-failed run)
     *  never counts as an answer: everything is
     *  re-tested, because that usually meant the test
     *  itself failed, not the configs. */
    private fun connectBestPing() {
        // Customs ARE testable: pingAll measures them
        // (their ms values show on the cards), so
        // excluding them here — as v1.57 wrongly did
        // with the comment "not delay-testable" — made
        // best-ping say "no config to test" for a
        // list of only customs. Only chains stay out:
        // a chain is multi-hop, not a single
        // outbound to delay-test.
        val targets = servers.filter {
            !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it)
        }
        if (targets.isEmpty()) {
            statusText.text = L("کانفیگی برای تست نیست.", "No config to test.")
            fabIdle()
            return
        }
        val cachedHealthy = targets.filter { (pingMap[XraySpeedtest.dedupKey(it)] ?: -1L) >= 0 }
        val untested = targets.filter { !pingMap.containsKey(XraySpeedtest.dedupKey(it)) }
        // Instant path: everything already tested this
        // session AND at least one healthy result.
        if (untested.isEmpty() && cachedHealthy.isNotEmpty()) {
            val best = cachedHealthy.minByOrNull { pingMap[XraySpeedtest.dedupKey(it)] ?: Long.MAX_VALUE }!!
            val bestDelay = pingMap[XraySpeedtest.dedupKey(best)] ?: -1L
            selected = best
            saveServers(); refreshList()
            statusText.text = L("بهترین کانفیگ: ${XraySpeedtest.displayName(best)} ($bestDelay ms)، در حال اتصال...", "Best config: ${XraySpeedtest.displayName(best)} ($bestDelay ms), connecting...")
            addStatusLine("بهترین کانفیگ: ${XraySpeedtest.displayName(best)} ($bestDelay ms)", "Best config: ${XraySpeedtest.displayName(best)} ($bestDelay ms)")
            connectSelected(best)
            return
        }
        if (!initCore()) {
            statusText.text = L("تست پینگ شروع نشد: هسته Xray راه‌اندازی نشد.", "Ping test did not start: Xray core failed to start.")
            fabIdle()
            return
        }
        statusText.text = L("در حال تست پینگ برای بهترین کانفیگ...", "Testing ping for the best config...")
        Thread {
            val pool = Executors.newFixedThreadPool(XraySpeedtest.poolSize())
            // untested ones are measured when a healthy
            // cache exists, AND configs that scored -1 are
            // re-tested too — so auto-delete below never
            // deletes a config that was not just re-tested.
            // With no healthy cache at all, everything is
            // re-tested (old -1s included) so a recovered
            // network is not missed.
            val failed = targets.filter { pingMap[XraySpeedtest.dedupKey(it)] == -1L }
            val toTest = if (cachedHealthy.isEmpty()) targets else (untested + failed).distinct()
            val completion = java.util.concurrent.ExecutorCompletionService<Pair<String, Long>>(pool)
            try {
                for (cfg in toTest) {
                    completion.submit(java.util.concurrent.Callable {
                        val d = try { XraySpeedtest.measureOneWithError(cfg).first }
                            catch (_: Throwable) { -1L }
                        cfg to d
                    })
                }
                var done = targets.size - toTest.size
                val total = targets.size
                val deadline = System.currentTimeMillis() + 60_000
                var remainingTasks = toTest.size
                while (remainingTasks > 0) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    val future = completion.poll(remaining, java.util.concurrent.TimeUnit.MILLISECONDS) ?: break
                    remainingTasks--
                    val result = try { future.get() } catch (_: Exception) { null }
                    if (result != null) {
                        val (cfg, d) = result
                        synchronized(pingMap) { pingMap[XraySpeedtest.dedupKey(cfg)] = d }
                        done++
                        runOnUiThread {
                            refreshList()
                            setStatusProgress("از $total کانفیگ $done تست گرفته شد", "$done of $total configs tested")
                        }
                    }
                }
            } finally {
                pool.shutdownNow()
            }
            runOnUiThread {
                refreshList()
                var bestCfg: String? = null
                var bestDelay = Long.MAX_VALUE
                for (cfg in targets) {
                    val d = pingMap[XraySpeedtest.dedupKey(cfg)] ?: -1L
                    if (d >= 0 && d < bestDelay) { bestDelay = d; bestCfg = cfg }
                }
                val chosen = bestCfg
                if (chosen == null) {
                    statusText.text = L("کانفیگ سالمی پیدا نشد.", "No healthy config found.")
                    setStatusProgress("کانفیگ سالمی پیدا نشد", "No healthy config found")
                    fabIdle()
                } else {
                    selected = chosen
                    saveServers(); refreshList()
                    statusText.text = L("بهترین کانفیگ پیدا شد، در حال اتصال...", "Best config found, connecting...")
                    addStatusLine("بهترین کانفیگ: ${XraySpeedtest.displayName(chosen)} ($bestDelay ms)", "Best config: ${XraySpeedtest.displayName(chosen)} ($bestDelay ms)")
                    if (failed.isNotEmpty()) {
                                            }
                    val removed = deleteNoPingIfEnabled(targets)
                    if (removed > 0) addStatusLine("$removed کانفیگ بدون پینگ حذف شد", "$removed no-ping configs removed")
                    connectSelected(chosen)
                }
            }
        }.start()
    }

    /** The + menu, exactly like v2rayNG's add menu,
     *  now as separate pale-blue glass buttons. */
    /** A small v2rayNG-style overflow popup: matte-white,
     *  anchored under the tapped button, one line per
     *  option, dismissed by tapping anywhere else. Text
     *  colors stay the app's dark ones (readable on the
     *  light popup); the main page is untouched. */
    private fun showSmallPopupMenu(anchor: View, items: Array<String>, onSelect: (Int) -> Unit) {
        val d = resources.displayMetrics.density
        // 2.87: the menus are COMPACT — wrap-content width
        // (widest item + padding only, never the old fixed
        // 300dp that left a huge empty side in RTL) and
        // FULLY OPAQUE panels (the 2.86 90%-alpha glass let
        // the list bleed through in night mode). Pearl glass
        // in day, dark glass in night, same options.
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 2.88 RTL rule: rows hug the right edge in Persian.
            layoutDirection = rtlDir()
            background = GradientGlassDrawable(
                Color.parseColor(if (pageDark) "#FF1F2636" else "#FFFFFFFF"),
                pageDark, 16f * d, 1.25f * d)
            setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
        }
        val popup = android.widget.PopupWindow(
            col,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.elevation = 8f * d
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        for ((i, label) in items.withIndex()) {
            col.addView(TextView(this).apply {
                text = label
                textSize = 15f
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                // 2.88 RTL rule: Persian text starts from the right.
                layoutDirection = rtlDir()
                textDirection = appTextDir()
                gravity = Gravity.START
                setTextColor(Color.parseColor(if (pageDark) "#EDEFF5" else "#333333"))
                setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
                // Wrap to the text (capped at 320dp so a very
                // long label can never stretch the menu).
                maxWidth = (320 * d).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { popup.dismiss(); onSelect(i) }
            })
            if (i < items.size - 1) {
                col.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(if (pageDark) Theme.darken(Color.parseColor("#33FFFFFF")) else Color.parseColor("#E0E0E0"))
                })
            }
        }
        // 2.87: dim the page behind the menu (both modes)
        // and clear the dim when the menu goes away, however
        // it is dismissed (tap, back, outside tap).
        activeMenuPopup = popup
        popup.setOnDismissListener {
            activeMenuPopup = null
            if (::menuDim.isInitialized) menuDim.visibility = View.GONE
        }
        try {
            if (::menuDim.isInitialized) {
                frameView.bringChildToFront(menuDim)
                menuDim.visibility = View.VISIBLE
            }
            popup.showAsDropDown(anchor)
        } catch (_: Exception) {
            activeMenuPopup = null
            if (::menuDim.isInitialized) menuDim.visibility = View.GONE
        }
    }

    private fun showAddMenu() {
        val items = arrayOf(
            L("وارد کردن از QRcode", "Import from QRcode"),
            L("وارد کردن از کلیپ‌بورد", "Import from Clipboard"),
            L("وارد کردن از فایل محلی", "Import from locally"),
            L("افزودن [گروه سیاستی]", "Add [Policy group]"),
            L("افزودن [زنجیره پروکسی]", "Add [Proxy chain]"),
            L("افزودن [VMess]", "Add [VMess]"),
            L("افزودن [VLESS]", "Add [VLESS]"),
            L("افزودن [Shadowsocks]", "Add [Shadowsocks]"),
            L("افزودن [SOCKS]", "Add [SOCKS]"),
            L("افزودن [HTTP]", "Add [HTTP]")
        )
        showSmallPopupMenu(addBtn, items) { which ->
            when (which) {
                0 -> startActivityForResult(
                    Intent(this@VpnActivity, ScanActivity::class.java), 200)
                1 -> importFromClipboard()
                2 -> {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    startActivityForResult(
                        Intent.createChooser(intent, L("انتخاب فایل کانفیگ", "Choose a config file")), 300)
                }
                3 -> addPolicyGroup()
                4 -> addProxyChain()
                5 -> addVMessForm()
                6 -> addVLESSForm()
                7 -> addShadowsocksForm()
                8 -> addSocksForm()
                9 -> addHttpForm()
            }
        }
    }

    private fun importFromClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (clip.isBlank()) {
                statusText.text = L("کلیپ‌بورد خالی است.", "Clipboard is empty.")
                return
            }
            addFromText(clip, toTop = true)
        } catch (e: Exception) {
            statusText.text = L("خواندن کلیپ‌بورد ناموفق: ${e.message}", "Failed to read clipboard: ${e.message}")
        }
    }

    // A policy group is now connectable: connecting
    // with a group selected balances across its
    // members (Observatory strategy below). The
    // strategy is stored on the header itself, so
    // old groups without a suffix default to leastPing.
    private fun addPolicyGroup() {
        showForm("Add [Policy group]", listOf(L("نام گروه", "Group name") to "")) { v ->
            val name = v[L("نام گروه", "Group name")] ?: ""
            if (name.isBlank()) {
                statusText.text = L("نام گروه را بنویس.", "Write the group name.")
                return@showForm
            }
            android.app.AlertDialog.Builder(this)
                .setTitle(L("استراتژی گروه", "Group strategy"))
                .setAdapter(themedListAdapter(arrayOf(
                    L("کمترین پینگ (leastPing)", "Least ping (leastPing)"),
                    L("کمترین بار (leastLoad)", "Least load (leastLoad)")
                ))) { _, which ->
                    val strategy = if (which == 1) "leastLoad" else "leastPing"
                    val entry = "GROUP::$name|||$strategy"
                    if (!servers.contains(entry)) servers += entry
                    saveServers(); refreshList()
                    statusText.text = L("گروه «$name» اضافه شد؛ با انتخاب آن و اتصال، ترافیک بین اعضای گروه پخش می‌شود.", "Group “$name” added; select it and connect to balance traffic across its members.")
                }
                .setNegativeButton(L("لغو", "Cancel"), null)
                .show().also { styleDialogRtl(it) }
        }
    }

    private fun addProxyChain() {
        val real = servers.filter {
            !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it)
        }
        if (real.size < 2) {
            statusText.text = L("برای زنجیره حداقل ۲ سرور لازم است.", "At least 2 servers are needed for a chain.")
            return
        }
        val names = real.map { XraySpeedtest.displayName(it) }.toTypedArray()
        val checked = BooleanArray(real.size)
        // 2.87: setMultiChoiceItems replaced by the themed
        // multi-choice dialog (same dark-on-dark root cause
        // as the per-app dialog).
        showMultiChoiceDialog(
            L("سرورهای زنجیره را به ترتیب انتخاب کن", "Select the chain servers in order"),
            names, checked, L("ساخت زنجیره", "Build chain")
        ) {
            val chosen = real.filterIndexed { i, _ -> checked[i] }
            if (chosen.size < 2) {
                statusText.text = L("برای زنجیره حداقل ۲ سرور انتخاب کن.", "Select at least 2 servers for a chain.")
                return@showMultiChoiceDialog
            }
            showForm(L("نام زنجیره", "Chain name"), listOf(L("نام زنجیره", "Chain name") to "")) { v ->
                val name = v[L("نام زنجیره", "Chain name")]?.takeIf { it.isNotBlank() } ?: L("زنجیره", "Chain")
                val entry = "CHAIN::$name|||" + chosen.joinToString("|||")
                servers += entry
                saveServers(); refreshList()
                statusText.text = L("زنجیره «$name» با ${chosen.size} سرور ساخته شد.", "Chain “$name” with ${chosen.size} servers built.")
            }
        }
    }

    /** Generic manual-add form: a vertical list of labeled
     *  EditTexts in a dialog, values returned by label. */
    private fun showForm(title: String, fields: List<Pair<String, String>>,
                         onSubmit: (Map<String, String>) -> Unit) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 2.91 RTL: labels and typed text start from the right.
            layoutDirection = rtlDir()
            setPadding(32, 16, 32, 16)
        }
        val inputs = mutableMapOf<String, EditText>()
        for ((label, def) in fields) {
            col.addView(TextView(this).apply {
                text = label; textSize = 14f
                // 2.91 RTL: Persian label starts from the right.
                layoutDirection = rtlDir()
                textDirection = appTextDir()
                gravity = Gravity.START
            })
            val et = EditText(this).apply {
                hint = label
                setText(def)
                // 2.91 RTL: typed Persian text starts from the right.
                layoutDirection = rtlDir()
                textDirection = appTextDir()
                gravity = Gravity.START
            }
            inputs[label] = et
            col.addView(et)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(col)
            .setPositiveButton(L("افزودن", "Add")) { _, _ ->
                val values = inputs.mapValues { it.value.text.toString().trim() }
                onSubmit(values)
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show().also { styleDialogRtl(it) }
    }

    private fun addOne(cfg: String) {
        if (!cfg.contains("://")) {
            statusText.text = L("کانفیگ ساخته‌شده معتبر نیست.", "The built config is not valid.")
            return
        }
        if (XraySpeedtest.outboundFor(cfg) == null && !XraySpeedtest.isChain(cfg)) {
            statusText.text = L("این کانفیگ پشتیبانی نمی‌شود.", "This config is not supported.")
            return
        }
        if (!servers.contains(cfg)) servers += cfg
        if (selected == null) selected = servers.firstOrNull()
        saveServers(); refreshList()
        statusText.text = L("کانفیگ «${XraySpeedtest.displayName(cfg)}» اضافه شد.", "Config \"${XraySpeedtest.displayName(cfg)}\" added.")
    }

    private fun encName(name: String): String {
        return try {
            java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        } catch (_: Exception) { name }
    }

    private fun addVMessForm() {
        showForm("Add [VMess]", listOf(
            L("نام", "Name") to "", L("آدرس", "Address") to "", L("پورت", "Port") to "443",
            "UUID" to "", "alterId" to "0",
            L("شبکه (tcp/ws/grpc)", "Network (tcp/ws/grpc)") to "tcp", L("مسیر (برای ws)", "Path (for ws)") to "/",
            L("tls (tls یا خالی)", "tls (tls or empty)") to ""
        )) { v ->
            val name = v[L("نام", "Name")] ?: ""
            val addr = v[L("آدرس", "Address")] ?: ""
            val port = v[L("پورت", "Port")] ?: "443"
            val id = v["UUID"] ?: ""
            if (addr.isBlank() || id.isBlank()) {
                statusText.text = L("آدرس و UUID لازم است.", "Address and UUID are required.")
                return@showForm
            }
            val j = JSONObject().apply {
                put("v", "2"); put("ps", name); put("add", addr); put("port", port)
                put("id", id); put("aid", v["alterId"] ?: "0")
                put("net", v[L("شبکه (tcp/ws/grpc)", "Network (tcp/ws/grpc)")] ?: "tcp")
                put("type", "none"); put("host", "")
                put("path", v[L("مسیر (برای ws)", "Path (for ws)")] ?: "/")
                put("tls", v[L("tls (tls یا خالی)", "tls (tls or empty)")] ?: "")
                put("sni", "")
            }
            val b64 = android.util.Base64.encodeToString(
                j.toString().toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            ).trimEnd('=')
            addOne("vmess://$b64#${encName(name)}")
        }
    }

    private fun addVLESSForm() {
        showForm("Add [VLESS]", listOf(
            L("نام", "Name") to "", L("آدرس", "Address") to "", L("پورت", "Port") to "443",
            "UUID" to "", L("شبکه (tcp/ws/grpc/xhttp)", "Network (tcp/ws/grpc/xhttp)") to "tcp",
            L("امنیت (tls/reality/none)", "Security (tls/reality/none)") to "tls", "SNI" to "",
            "fingerprint" to "chrome", "publicKey (reality)" to "",
            "shortId (reality)" to "", "flow" to "",
            L("مسیر (ws/xhttp)", "Path (ws/xhttp)") to "/", "host (ws)" to ""
        )) { v ->
            val name = v[L("نام", "Name")] ?: ""
            val addr = v[L("آدرس", "Address")] ?: ""
            val port = v[L("پورت", "Port")] ?: "443"
            val id = v["UUID"] ?: ""
            if (addr.isBlank() || id.isBlank()) {
                statusText.text = L("آدرس و UUID لازم است.", "Address and UUID are required.")
                return@showForm
            }
            val q = mutableListOf<String>()
            fun putQ(k: String, value: String?) {
                if (!value.isNullOrBlank()) q += "$k=${encName(value)}"
            }
            putQ("type", v[L("شبکه (tcp/ws/grpc/xhttp)", "Network (tcp/ws/grpc/xhttp)")])
            putQ("security", v[L("امنیت (tls/reality/none)", "Security (tls/reality/none)")])
            putQ("sni", v["SNI"])
            putQ("fp", v["fingerprint"])
            putQ("pbk", v["publicKey (reality)"])
            putQ("sid", v["shortId (reality)"])
            putQ("flow", v["flow"])
            putQ("path", v[L("مسیر (ws/xhttp)", "Path (ws/xhttp)")])
            putQ("host", v["host (ws)"])
            val query = if (q.isEmpty()) "" else "?" + q.joinToString("&")
            addOne("vless://$id@$addr:$port$query#${encName(name)}")
        }
    }

    private fun addShadowsocksForm() {
        showForm("Add [Shadowsocks]", listOf(
            L("نام", "Name") to "", L("آدرس", "Address") to "", L("پورت", "Port") to "8388",
            L("روش (aes-256-gcm و...)", "Method (aes-256-gcm etc.)") to "aes-256-gcm", L("رمز", "Password") to ""
        )) { v ->
            val name = v[L("نام", "Name")] ?: ""
            val addr = v[L("آدرس", "Address")] ?: ""
            val port = v[L("پورت", "Port")] ?: "8388"
            val method = v[L("روش (aes-256-gcm و...)", "Method (aes-256-gcm etc.)")] ?: "aes-256-gcm"
            val pass = v[L("رمز", "Password")] ?: ""
            if (addr.isBlank() || pass.isBlank()) {
                statusText.text = L("آدرس و رمز لازم است.", "Address and password are required.")
                return@showForm
            }
            val raw = "$method:$pass@$addr:$port"
            val b64 = android.util.Base64.encodeToString(
                raw.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            ).trimEnd('=')
            addOne("ss://$b64#${encName(name)}")
        }
    }

    private fun addSocksForm() {
        showForm("Add [SOCKS]", listOf(
            L("نام", "Name") to "", L("آدرس", "Address") to "", L("پورت", "Port") to "1080",
            L("نام کاربری (اختیاری)", "Username (optional)") to "", L("رمز (اختیاری)", "Password (optional)") to ""
        )) { v ->
            val name = v[L("نام", "Name")] ?: ""
            val addr = v[L("آدرس", "Address")] ?: ""
            val port = v[L("پورت", "Port")] ?: "1080"
            val user = v[L("نام کاربری (اختیاری)", "Username (optional)")] ?: ""
            val pass = v[L("رمز (اختیاری)", "Password (optional)")] ?: ""
            if (addr.isBlank()) {
                statusText.text = L("آدرس لازم است.", "Address is required.")
                return@showForm
            }
            val auth = if (user.isNotBlank()) "$user:$pass@" else ""
            addOne("socks://$auth$addr:$port#${encName(name)}")
        }
    }

    private fun addHttpForm() {
        showForm("Add [HTTP]", listOf(
            L("نام", "Name") to "", L("آدرس", "Address") to "", L("پورت", "Port") to "8080",
            L("نام کاربری (اختیاری)", "Username (optional)") to "", L("رمز (اختیاری)", "Password (optional)") to ""
        )) { v ->
            val name = v[L("نام", "Name")] ?: ""
            val addr = v[L("آدرس", "Address")] ?: ""
            val port = v[L("پورت", "Port")] ?: "8080"
            val user = v[L("نام کاربری (اختیاری)", "Username (optional)")] ?: ""
            val pass = v[L("رمز (اختیاری)", "Password (optional)")] ?: ""
            if (addr.isBlank()) {
                statusText.text = L("آدرس لازم است.", "Address is required.")
                return@showForm
            }
            val auth = if (user.isNotBlank()) "$user:$pass@" else ""
            addOne("http://$auth$addr:$port#${encName(name)}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            selected?.let { startVpn(it) }
        } else if (requestCode == 100) {
            statusText.text = L("دسترسی VPN تأیید نشد.", "VPN permission was not confirmed.")
        } else if (requestCode == 400 && resultCode == RESULT_OK) {
            try {
                val uri = data?.data
                val json = pendingBackupJson ?: ""
                if (uri != null && json.isNotBlank()) {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    }
                    statusText.text = L("بکاپ ذخیره شد.", "Backup saved.")
                }
            } catch (e: Exception) {
                statusText.text = L("ذخیره بکاپ ناموفق: ${e.message}", "Failed to save backup: ${e.message}")
            }
        } else if (requestCode == 401 && resultCode == RESULT_OK) {
            try {
                val uri = data?.data
                val text = if (uri != null) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                } else ""
                val j = JSONObject(text)
                val arr = j.optJSONArray("servers")
                if (arr != null) {
                    servers = mutableListOf()
                    for (i in 0 until arr.length()) servers += arr.getString(i)
                }
                selected = j.optString("selected").takeIf { it.isNotBlank() }
                prefs().edit()
                    .putString("vpn_subs", j.optString("vpn_subs"))
                    .putString("per_app_mode", j.optString("per_app_mode"))
                    .putString("per_app_set", j.optString("per_app_set"))
                    .putString("routing_mode", j.optString("routing_mode"))
                    .apply()
                saveServers(); refreshList()
                statusText.text = L("بکاپ بازیابی شد: ${servers.size} کانفیگ.", "Backup restored: ${servers.size} configs.")
            } catch (e: Exception) {
                statusText.text = L("خواندن بکاپ ناموفق: ${e.message}", "Failed to read backup: ${e.message}")
            }
        } else if (requestCode == 300 && resultCode == RESULT_OK) {
            try {
                val uri = data?.data
                if (uri == null) {
                    statusText.text = L("فایلی انتخاب نشد.", "No file selected.")
                    return
                }
                val text = contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText() ?: ""
                if (text.isBlank()) {
                    statusText.text = L("فایل خالی بود.", "The file was empty.")
                } else {
                    addFromText(text)
                }
            } catch (e: Exception) {
                statusText.text = L("خواندن فایل ناموفق: ${e.message}", "Failed to read file: ${e.message}")
            }
        } else if (requestCode == 200 && resultCode == RESULT_OK) {
            val scanned = data?.getStringExtra("scanned") ?: ""
            if (scanned.contains("://")) {
                addFromText(scanned)
                statusText.text = L("کانفیگ از QR اضافه شد.", "Config added from QR.")
            } else {
                statusText.text = L("QR خوانده‌شده کانفیگ نبود.", "The scanned QR was not a config.")
            }
        }
    }

    private fun loadVpnSubs(): MutableList<String> {
        return prefs().getString("vpn_subs", "")?.lines()
            ?.map { it.trim() }?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
    }

    // Update all enabled subscriptions, honouring
    // each sub's own options (see VpnSubs.fetchOne).
    private fun updateVpnSubs() {
        val items = VpnSubs.loadItems(this).filter { it.enabled }
        if (items.isEmpty()) {
            statusText.text = L("اول یک سابسکریبشن فعال اضافه کن.", "Add an enabled subscription first.")
            return
        }
        statusText.text = L("در حال به‌روزرسانی ساب‌ها...", "Updating subscriptions...")
        Thread {
            var added = 0
            var received = 0
            var failed = 0
            // One line per sub, so a failing sub
            // names its own cause instead of hiding
            // behind one aggregate "failed" count.
            val lines = mutableListOf<Pair<String, String>>()
            for (item in items) {
                try {
                    val r = VpnSubs.fetchOne(this, item)
                    added += r.added
                    received += r.received
                    val label = VpnSubs.displayLabel(item)
                    if (r.message == "ok") {
                        // Raw FA/EN pair: addStatusLine
                        // picks by language itself, so
                        // do NOT wrap these in L().
                        lines += ("ساب $label: ${r.received} دریافت، ${r.added} جدید"
                            to "Sub $label: ${r.received} received, ${r.added} new")
                    } else {
                        failed++
                        val (whyFa, whyEn) = VpnSubs.reasonFaEn(r.message)
                        lines += ("ساب $label ناموفق: $whyFa"
                            to "Sub $label failed: $whyEn")
                    }
                } catch (_: Exception) {
                    failed++
                    val label = VpnSubs.displayLabel(item)
                    lines += ("ساب $label ناموفق: خطای نامشخص"
                        to "Sub $label failed: unknown error")
                }
            }
            runOnUiThread {
                servers = VpnSubs.loadServers(this)
                if (selected == null) selected = servers.firstOrNull()
                if (selected != null && !servers.contains(selected)) selected = servers.firstOrNull()
                saveServers(); refreshList()
                for ((fa, en) in lines) addStatusLine(fa, en)
                statusText.text = L("به‌روزرسانی ساب‌ها انجام شد: $received کانفیگ دریافت شد، $added جدید اضافه شد.", "Subscription update done: $received configs received, $added new added.") +
                    if (failed > 0) L(" ($failed ساب ناموفق بود.)", " ($failed subs failed.)") else ""
                autoDedupIfEnabled()
            }
        }.start()
    }

    private fun showQrFor(cfg: String) {
        try {
            val writer = com.google.zxing.qrcode.QRCodeWriter()
            val matrix = writer.encode(cfg, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
            val bmp = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888)
            for (x in 0 until 512) for (y in 0 until 512) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
            val iv = ImageView(this).apply { setImageBitmap(bmp) }
            android.app.AlertDialog.Builder(this)
                .setTitle(XraySpeedtest.displayName(cfg))
                .setView(iv)
                .setPositiveButton(L("بستن", "Close"), null)
                .setNeutralButton(L("کپی لینک", "Copy link")) { _, _ ->
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("server", cfg))
                        statusText.text = L("لینک سرور کپی شد.", "Server link copied.")
                    } catch (e: Exception) {
                        statusText.text = L("کپی ناموفق: ${e.message}", "Copy failed: ${e.message}")
                    }
                }
                .show().also { styleDialogRtl(it) }
        } catch (e: Exception) {
            statusText.text = L("ساخت QR ناموفق: ${e.message}", "Failed to build QR: ${e.message}")
        }
    }

    // Pre-resolve runs DNS off the UI thread, then
    // the real connect runs with the results: the
    // "add to DNS Hosts" mode passes them into the
    // config's dns.hosts, the "Resolve only" mode
    // rewrites outbound addresses to the IPs.
    private fun startVpn(cfg: String) {
        // 1.43 (F): connecting stops a running free fetch
        // AT ONCE — the same flag as the cancel path, so
        // the worker winds down at its next check and
        // keeps only the configs added so far; the
        // waiting line and the box text are restored
        // immediately instead of waiting for the
        // worker's finish callback. freeFetchRunning
        // stays true until the worker's finishFreeFetch
        // runs, so a new fetch cannot start on top of
        // the dying one and its finish cannot clobber it.
        if (freeFetchRunning) {
            freeFetchCancelled = true
            try { hideFreeWaitIndicator() } catch (_: Exception) {}
            try { if (::freeBtn.isInitialized) freeBtn.text = L("دریافت کانفیگ رایگان", "Get free configs") } catch (_: Exception) {}
        }
        // A hand-started connect is not smart connect's.
        try { getSharedPreferences("renamer_smart", MODE_PRIVATE).edit().putBoolean("smart_owns_vpn", false).apply() } catch (_: Exception) {}
        if (VpnConfigBuilder.preResolveMode(this) == "Do not resolve") {
            doStartVpn(cfg, emptyMap())
            return
        }
        statusText.text = L("در حال حل دامنه...", "Resolving domain...")
        Thread {
            val resolved = try { VpnConfigBuilder.preResolveHosts(this, cfg) }
                catch (_: Exception) { emptyMap<String, String>() }
            runOnUiThread { doStartVpn(cfg, resolved) }
        }.start()
    }

    private fun doStartVpn(cfg: String, resolved: Map<String, String>) {
        val mode = VpnConfigBuilder.preResolveMode(this)
        val hostsArg = if (mode == "Resolve and add to DNS Hosts") resolved else emptyMap()
        // Proxy-only mode: the core runs with local
        // proxy inbounds only, no TUN, no VPN service.
        if (prefs().getString("vpn_mode", "VPN") == "Proxy only") {
            val builtProxy = VpnConfigBuilder.buildProxyOnly(this, cfg, hostsArg)
            val proxyCfg = if (mode == "Resolve only") {
                VpnConfigBuilder.applyResolvedIp(builtProxy ?: "", resolved)
            } else builtProxy
            if (proxyCfg == null) {
                if (appLogPending) { appLogEvent("ناموفق بود: کانفیگ پشتیبانی نمی‌شود", "failed: config not supported"); appLogPending = false }
                statusText.text = L("این کانفیگ برای اتصال پشتیبانی نمی‌شود.", "This config is not supported for connecting.")
                fabIdle()
                return
            }
            if (!initCore()) {
                if (appLogPending) { appLogEvent("ناموفق بود: هسته راه‌اندازی نشد", "failed: core failed to start"); appLogPending = false }
                statusText.text = L("هسته Xray راه‌اندازی نشد.", "Xray core failed to start.")
                fabIdle()
                return
            }
            ProxyOnlyService.start(this, proxyCfg, XraySpeedtest.displayName(cfg))
            statusText.text = L("پروکسی فعال شد (بدون TUN).", "Proxy active (no TUN).")
            handler.postDelayed({ refreshStatus() }, 1500)
            return
        }
        // A config that belongs to a sub with prev/next
        // set connects as that sub's chain (device ->
        // prev -> config -> next -> destination).
        val effective = VpnSubs.chainForConfig(this, cfg) ?: cfg
        val built = buildVpnConfig(effective, hostsArg)
        val vpnConfig = if (mode == "Resolve only") {
            VpnConfigBuilder.applyResolvedIp(built ?: "", resolved)
        } else built
        if (vpnConfig == null) {
            if (appLogPending) { appLogEvent("ناموفق بود: کانفیگ پشتیبانی نمی‌شود", "failed: config not supported"); appLogPending = false }
            statusText.text = L("این کانفیگ برای اتصال پشتیبانی نمی‌شود.", "This config is not supported for connecting.")
            fabIdle()
            return
        }
        if (!initCore()) {
            if (appLogPending) { appLogEvent("ناموفق بود: هسته راه‌اندازی نشد", "failed: core failed to start"); appLogPending = false }
            statusText.text = L("هسته Xray راه‌اندازی نشد.", "Xray core failed to start.")
            fabIdle()
            return
        }
        val intent = Intent(this, VpnClientService::class.java).apply {
            putExtra("config", vpnConfig)
            putExtra("name", XraySpeedtest.displayName(cfg))
            putExtra("link", effective)
            putExtra("session_tag", "v2ray")
        }
        // A normal connect is not an emergency connect.
        VpnClientService.emergencyConfig = null
        startService(intent)
        statusText.text = L("در حال اتصال...", "Connecting...")
        handler.postDelayed({ refreshStatus() }, 1500)
    }

    private fun initCore(): Boolean {
        if (coreInitDone) return true
        val ok = CoreInit.init(applicationContext)
        if (ok) coreInitDone = true
        return ok
    }

    /** Full VPN-mode Xray config, shared with the
     *  emergency page (see VpnConfigBuilder). */
    private fun buildVpnConfig(cfg: String, extraHosts: Map<String, String> = emptyMap()): String? {
        return VpnConfigBuilder.build(this, cfg, extraHosts)
    }

    /** The strip shows exactly one line: the latest
     *  message. Nothing here ever grows the strip. */
    private fun setStripLine(msg: String) {
        if (::stripLine.isInitialized) stripLine.text = msg
    }

    private fun addStatusLine(fa: String, en: String) {
        val line = L(fa, en)
        statusLines += line
        while (statusLines.size > 40) statusLines.removeAt(0)
        setStripLine(line)
    }

    private fun setStatusProgress(fa: String, en: String) {
        setStripLine(L(fa, en))
    }

    private fun showStatusHistoryDialog() {
        val tv = TextView(this).apply {
            text = if (statusLines.isEmpty()) L("هنوز گزارشی نیست.", "No report yet.")
                   else statusLines.joinToString("\n")
            textSize = 14f
            // 2.91 RTL: Persian text starts from the right.
            layoutDirection = rtlDir()
            textDirection = appTextDir()
            gravity = Gravity.START
            setPadding(24, 16, 24, 16)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("گزارش وضعیت", "Status report"))
            .setView(sv)
            .setPositiveButton(L("باشه", "OK"), null)
            .show().also { styleDialogRtl(it) }
    }

    /** The ⋮ menu: Service restart removed at the user's
     *  request; Delete config now deletes ALL configs;
     *  Real delay now pings ALL configs (the old separate
     *  "تست پینگ همه" button is gone). */
    private fun showMainMenu() {
        val autoDedupOn = prefs().getBoolean("auto_dedup", false)
        val autoNopingOn = prefs().getBoolean("auto_noping", false)
        val items = arrayOf(
            L("حذف کانفیگ", "Delete config"),
            L("حذف کانفیگ‌های تکراری", "Delete duplicate config"),
            L("حذف کانفیگ‌های نامعتبر", "Delete invalid config"),
            L("خروجی کانفیگ‌ها در کلیپ‌بورد", "Export configs to clipboard"),
            L("پیدا کردن کانفیگ انتخاب‌شده", "Locate the selected config"),
            L("مرتب‌سازی بر اساس نتایج تست", "Sorting by test results"),
            L("تست TCP کانفیگ", "Tcping config"),
            L("تست پینگ همه", "Real delay config"),
            L("به‌روزرسانی اشتراک", "Update subscription"),
            L("حذف خودکار تکراری‌ها: ", "Auto-delete duplicates: ") + if (autoDedupOn) L("روشن", "On") else L("خاموش", "Off"),
            L("حذف خودکار بدون‌پینگ‌ها: ", "Auto-delete no-ping: ") + if (autoNopingOn) L("روشن", "On") else L("خاموش", "Off")
        )
        showSmallPopupMenu(menuBtn, items) { which ->
            when (which) {
                0 -> deleteAllConfigs()
                1 -> deleteDuplicates()
                2 -> deleteInvalid()
                3 -> exportToClipboard()
                4 -> locateSelected()
                5 -> sortByTestResults()
                6 -> tcpingSelected()
                7 -> pingAll()
                8 -> updateVpnSubs()
                9 -> {
                    val on = !prefs().getBoolean("auto_dedup", false)
                    prefs().edit().putBoolean("auto_dedup", on).apply()
                    if (on) {
                        // Run at once AND say how many:
                        // a silent toggle is exactly what
                        // looked fake before.
                        val removed = dedupServers()
                        statusText.text = if (removed > 0)
                            L("حذف خودکار تکراری‌ها روشن شد؛ $removed تکراری حذف شد.", "Auto-delete duplicates on; $removed duplicates removed.")
                        else
                            L("حذف خودکار تکراری‌ها روشن شد؛ تکراری‌ای نبود.", "Auto-delete duplicates on; no duplicates.")
                        if (removed > 0) addStatusLine("$removed کانفیگ تکراری حذف شد", "$removed duplicate configs removed")
                    } else {
                        statusText.text = L("حذف خودکار تکراری‌ها خاموش شد.", "Auto-delete duplicates off.")
                    }
                }
                10 -> {
                    val on = !prefs().getBoolean("auto_noping", false)
                    prefs().edit().putBoolean("auto_noping", on).apply()
                    if (on) {
                        // Clean the -1s that are ALREADY
                        // on screen right now, not only
                        // after the next test: turning
                        // the toggle on must do visible
                        // work at once. (The old build
                        // only cleaned after a fresh
                        // test, so with old -1s on
                        // screen it looked dead.)
                        val targetsNow = servers.filter { !XraySpeedtest.isGroup(it) }
                        val hadNegative = targetsNow.any { (pingMap[XraySpeedtest.dedupKey(it)] ?: 1L) < 0 }
                        val hadHealthy = targetsNow.any { (pingMap[XraySpeedtest.dedupKey(it)] ?: -1L) >= 0 }
                        val removed = deleteNoPingIfEnabled(targetsNow)
                        statusText.text = when {
                            removed > 0 -> L("حذف خودکار بدون‌پینگ‌ها روشن شد؛ $removed کانفیگ حذف شد.", "Auto-delete no-ping on; $removed configs removed.")
                            pingMap.isEmpty() -> L("حذف خودکار بدون‌پینگ‌ها روشن شد؛ هنوز تستی انجام نشده.", "Auto-delete no-ping on; no test run yet.")
                            hadNegative && !hadHealthy -> L("حذف خودکار بدون‌پینگ‌ها روشن شد؛ همه تست‌ها -۱ بودند، پس چیزی حذف نشد (تست خودش خراب بوده، نه کانفیگ‌ها).", "Auto-delete no-ping on; all tests were -1, so nothing was removed (the test itself failed, not the configs).")
                            else -> L("حذف خودکار بدون‌پینگ‌ها روشن شد؛ کانفیگ بدون پینگی نبود.", "Auto-delete no-ping on; no no-ping configs.")
                        }
                        if (removed > 0) addStatusLine("$removed کانفیگ بدون پینگ حذف شد", "$removed no-ping configs removed")
                    } else {
                        statusText.text = L("حذف خودکار بدون‌پینگ‌ها خاموش شد.", "Auto-delete no-ping off.")
                    }
                }
            }
        }
    }

    /** Delete config = delete ALL configs, with a confirm
     *  dialog so a stray tap cannot wipe the list. */
    private fun deleteAllConfigs() {
        if (servers.isEmpty()) {
            statusText.text = L("کانفیگی برای حذف نیست.", "No config to delete.")
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("حذف کانفیگ", "Delete config"))
            .setMessage(L("همه ${servers.size} کانفیگ حذف شود؟", "All ${servers.size} configs be deleted?"))
            .setPositiveButton(L("بله، حذف همه", "Yes, delete all")) { _, _ ->
                servers.clear()
                pingMap.clear()
                selected = null
                saveServers(); refreshList()
                statusText.text = L("همه کانفیگ‌ها حذف شد.", "All configs deleted.")
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show().also { styleDialogRtl(it) }
    }

    /** Shared dedup logic (manual + auto): returns
     *  how many were removed. Group headers are
     *  never duplicates. Ping entries are only
     *  cleared for keys with no remaining config. */
    private fun dedupServers(): Int {
        val seen = mutableSetOf<String>()
        val kept = mutableListOf<String>()
        var removed = 0
        for (cfg in servers) {
            if (XraySpeedtest.isGroup(cfg)) { kept += cfg; continue }
            if (seen.add(XraySpeedtest.dedupKey(cfg))) kept += cfg else removed++
        }
        val remainingKeys = kept.map { XraySpeedtest.dedupKey(it) }.toSet()
        val removedKeys = servers.filter { !kept.contains(it) }
            .map { XraySpeedtest.dedupKey(it) }.filter { it !in remainingKeys }
        servers = kept
        for (k in removedKeys) pingMap.remove(k)
        if (selected != null && !servers.contains(selected)) selected = servers.firstOrNull()
        saveServers(); refreshList()
        return removed
    }

    private fun deleteDuplicates() {
        val removed = dedupServers()
        statusText.text = if (removed == 0) L("کانفیگ تکراری‌ای نبود.", "No duplicate config.")
                         else L("$removed کانفیگ تکراری حذف شد.", "$removed duplicate configs removed.")
        if (removed > 0) addStatusLine("$removed کانفیگ تکراری حذف شد", "$removed duplicate configs removed")
    }

    private fun autoDedupIfEnabled() {
        if (!prefs().getBoolean("auto_dedup", false)) return
        val removed = dedupServers()
        if (removed > 0) addStatusLine("$removed کانفیگ تکراری حذف شد", "$removed duplicate configs removed")
    }

    /** Auto-delete configs that were tested in this
     *  run and got no ping — only when the toggle is
     *  on, never the selected config, and NEVER when
     *  the whole run failed (that means the network
     *  itself is down, not that the configs are
     *  dead). Customs count here: pingAll genuinely
     *  measures them, so a custom with -1 is really
     *  dead. (v1.57 wrongly excluded customs, which
     *  is why -1 customs survived with the toggle
     *  on.) Only groups and chains stay out: groups
     *  are headers, chains are never delay-tested. */
    private fun deleteNoPingIfEnabled(targets: List<String>): Int {
        if (!prefs().getBoolean("auto_noping", false)) return 0
        val results = targets.associateWith { pingMap[XraySpeedtest.dedupKey(it)] ?: -1L }
        if (results.values.none { it >= 0 }) return 0
        val victims = targets.filter { cfg ->
            !XraySpeedtest.isGroup(cfg) && !XraySpeedtest.isChain(cfg) &&
                cfg != selected &&
                (results[cfg] ?: -1L) < 0
        }
        if (victims.isEmpty()) return 0
        servers.removeAll(victims.toSet())
        // 1.40 (A2): removed free rows leave the
        // fetched set too.
        pruneFreeFetchedKeys(victims)
        for (cfg in victims) pingMap.remove(XraySpeedtest.dedupKey(cfg))
        if (selected != null && !servers.contains(selected)) selected = servers.firstOrNull()
        saveServers(); refreshList()
        return victims.size
    }

    /** Why delete-invalid used to "not work": outboundFor
     *  can THROW (malformed URI / JSON / base64) instead of
     *  returning null, and a config with a blank host or an
     *  out-of-range port used to pass parseServer. Both are
     *  now invalid, and any exception means invalid. */
    /** Invalid = cannot connect, judged by the
     *  real connect config. The old checks (parse +
     *  outbound + speedtest config) passed customs
     *  whose first outbound looked fine but whose
     *  full JSON (routing/dns) was broken — so
     *  those stayed and delete-invalid looked dead.
     *  build() assembles exactly what a connect
     *  uses, so build() == null is the honest test
     *  for links and customs alike. */
    private fun isInvalid(cfg: String): Boolean {
        if (XraySpeedtest.isGroup(cfg)) return false
        return try {
            if (XraySpeedtest.isChain(cfg)) {
                val parts = XraySpeedtest.chainParts(cfg)
                if (parts.isEmpty()) return true
                parts.any { isInvalid(it) }
            } else {
                val srv = XraySpeedtest.parseServer(cfg) ?: return true
                if (srv.host.isBlank() || srv.port <= 0 || srv.port > 65535) return true
                VpnConfigBuilder.build(this, cfg) == null
            }
        } catch (_: Throwable) {
            true
        }
    }

    private fun deleteInvalid() {
        // Compute once: the old code ran isInvalid
        // twice per config (two filters).
        val removedList = servers.filter { isInvalid(it) }
        if (removedList.isNotEmpty()) {
            servers.removeAll(removedList.toSet())
            // 1.40 (A2): removed free rows leave
            // the fetched set too.
            pruneFreeFetchedKeys(removedList)
            for (cfg in removedList) pingMap.remove(XraySpeedtest.dedupKey(cfg))
            if (selected != null && !servers.contains(selected)) selected = servers.firstOrNull()
            saveServers(); refreshList()
        }
        val removed = removedList.size
        statusText.text = if (removed == 0) L("کانفیگ نامعتبری نبود.", "No invalid config.")
                         else L("$removed کانفیگ نامعتبر حذف شد.", "$removed invalid configs removed.")
        if (removed > 0) addStatusLine("$removed کانفیگ نامعتبر حذف شد", "$removed invalid configs removed")
    }

    private fun exportToClipboard() {
        val real = servers.filter {
            !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it)
        }
        if (real.isEmpty()) {
            statusText.text = L("کانفیگی برای خروجی نیست.", "No config to export.")
            return
        }
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                "configs", real.joinToString("\n")))
            statusText.text = L("${real.size} کانفیگ در کلیپ‌بورد کپی شد.", "${real.size} configs copied to clipboard.")
        } catch (e: Exception) {
            statusText.text = L("کپی ناموفق: ${e.message}", "Copy failed: ${e.message}")
        }
    }

    private fun locateSelected() {
        val row = selectedRowView
        if (selected == null || row == null) {
            statusText.text = L("اول یک سرور را انتخاب کن.", "Select a server first.")
            return
        }
        scrollView.post {
            scrollView.smoothScrollTo(0, row.top)
        }
        statusText.text = L("سرور انتخاب‌شده پیدا شد.", "Selected server found.")
    }

    private fun sortByTestResults() {
        servers.sortWith(compareBy { cfg ->
            val p = pingMap[XraySpeedtest.dedupKey(cfg)]
            if (p == null || p < 0) Long.MAX_VALUE else p
        })
        saveServers(); refreshList()
        statusText.text = L("سرورها بر اساس نتیجه تست مرتب شدند (سالم‌ها اول، تست‌نشده‌ها آخر).", "Servers sorted by test result (healthy first, untested last).")
    }

    private fun tcpingSelected() {
        val cfg = selected
        if (cfg == null) {
            statusText.text = L("اول یک سرور را انتخاب کن.", "Select a server first.")
            return
        }
        val srv = XraySpeedtest.parseServer(cfg)
        if (srv == null) {
            statusText.text = L("آدرس این سرور خوانده نشد.", "This server's address could not be read.")
            return
        }
        Thread {
            val t = XraySpeedtest.tcpConnectTime(srv.host, srv.port)
            runOnUiThread {
                pingMap[XraySpeedtest.dedupKey(cfg)] = t
                refreshList()
                statusText.text = if (t >= 0) "Tcping: $t ms"
                                 else L("Tcping ناموفق بود.", "Tcping failed.")
            }
        }.start()
    }

    private fun realDelaySelected() {
        val cfg = selected
        if (cfg == null) {
            statusText.text = L("اول یک سرور را انتخاب کن.", "Select a server first.")
            return
        }
        if (!initCore()) {
            statusText.text = L("تست شروع نشد: هسته Xray راه‌اندازی نشد.", "Test did not start: Xray core failed to start.")
            return
        }
        statusText.text = L("در حال تست Real delay...", "Testing Real delay...")
        Thread {
            val (d, err) = try {
                XraySpeedtest.measureOneWithError(cfg)
            } catch (t: Throwable) {
                -1L to (t.message ?: t.javaClass.simpleName)
            }
            runOnUiThread {
                pingMap[XraySpeedtest.dedupKey(cfg)] = d
                refreshList()
                statusText.text = if (d >= 0) "Real delay: $d ms"
                                 else L("Real delay ناموفق بود: $err", "Real delay failed: $err")
            }
        }.start()
    }

    /** 1.38 (free-fetch redesign): tap «دریافت
     *  کانفیگ رایگان» → fetch the 4 subs → quick TCP
     *  ping first, drop the dead ones fast → drop
     *  duplicates (across subs AND against configs
     *  already ADDED, via the persistent
     *  `free_fetched_keys` set) → ping-test the rest
     *  STREAMING, in fetch order: each survivor is
     *  screened on 2 routes and only confirmed on
     *  the full 5 routes when its screen is under
     *  250 ms, and it is added to the list the moment
     *  its full 5-route minimum is UNDER 200 ms —
     *  up to 10 configs, named @internet_azad_2026(N)
     *  continuing from the existing free count. The
     *  moment the 10th is added the whole run STOPS
     *  (see FreeConfigs.pingStreamingFree); with
     *  fewer than 10 passers the run ends with what
     *  passed. A re-tap NEVER deletes the previous
     *  free rows (the 1.30–1.37 replace-first
     *  behaviour is gone). 1.40 (A1/A2): the set
     *  holds ONLY configs actually added — a
     *  fetched-but-never-added config is NOT in it,
     *  so the next tap tests it and adds the rest
     *  (11..20 after a first 10); deleting a free
     *  row prunes its key, and a poisoned 1.39 set
     *  is reconciled against the rows present on
     *  load. 1.40 (A3) finish messages: 0 fetched
     *  → «دریافت ناموفق بود»; fetched but all
     *  duplicates of added → «کانفیگ جدیدی پیدا
     *  نشد»; fetched fresh but none passed →
     *  «کانفیگ سالمی پیدا نشد». 1.40 (B): the ONLY
     *  mid-run output is the waiting line under the
     *  free button — no logs, no other status text,
     *  no progress/ETA — the button still switches
     *  to «لغو دریافت» while fetching (the cancel
     *  control), and the ONLY output at the end is
     *  one Toast at the bottom with the real count
     *  (plus the added rows). Cancelling adds
     *  nothing new and leaves every existing free
     *  row untouched. No sub name or URL ever
     *  appears in any message: every failure is one
     *  generic line (see FreeConfigs). The tutorial
     *  hooks (1.36) are untouched: step 1 ->
     *  waiting step 2 on start, and the end call
     *  on finish. */
    /** 1.44 (E): the free-fetch options dialog (mockup
     *  frame 4) — two options, each with its note; tapping
     *  one starts the fetch in that mode at once. No «لغو»
     *  button: Back closes the dialog (an AlertDialog is
     *  cancelable). It opens on EVERY tap of «دریافت
     *  کانفیگ رایگان»; the choice applies to that fetch
     *  only and is not persisted. Style: the app's
     *  sapphire/glass dialog (styleDialogRtl), compact,
     *  radio circles drawn in code. */
    private fun showFreeFetchOptionsDialog() {
        val d = resources.displayMetrics.density
        val dark = pageDark
        var dialog: android.app.AlertDialog? = null
        fun radioCircle(): android.widget.ImageView {
            val s = (22 * d).toInt()
            return android.widget.ImageView(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke((2 * d).toInt(), Color.parseColor(if (dark) "#7FB3FF" else "#3B82F6"))
                }
                layoutParams = LinearLayout.LayoutParams(s, s)
            }
        }
        fun optionRow(title: String, note: String, onPick: () -> Unit): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = rtlDir()
                isClickable = true
                setPadding(0, (12 * d).toInt(), 0, (12 * d).toInt())
            }
            // Radio at the START side — right in Persian.
            row.addView(radioCircle())
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (12 * d).toInt()
                }
            }
            texts.addView(TextView(this).apply {
                text = title
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(if (dark) "#EDEFF5" else "#333333"))
                textDirection = appTextDir()
                gravity = Gravity.START
            })
            texts.addView(TextView(this).apply {
                text = note
                textSize = 12f
                setTextColor(Color.parseColor(if (dark) "#B9C0CE" else "#666666"))
                textDirection = appTextDir()
                gravity = Gravity.START
                setPadding(0, (4 * d).toInt(), 0, 0)
            })
            row.addView(texts)
            row.setOnClickListener {
                try { dialog?.dismiss() } catch (_: Exception) {}
                onPick()
            }
            return row
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = rtlDir()
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), (16 * d).toInt())
        }
        col.addView(optionRow(
            L("فقط کانفیگ‌های با پینگ زیر ۲۰۰", "Only configs with ping under 200"),
            L("(ممکن است کمی بیشتر طول بکشد، پینگ کانفیگ‌ها زیر ۲۰۰)", "(May take a little longer; config pings are under 200)")
        ) {
            freeFetchMode = FreeFetchMode.UNDER_200
            startFreeFetch()
        })
        col.addView(View(this).apply {
            setBackgroundColor(if (dark) Theme.darken(Color.parseColor("#33FFFFFF")) else Color.parseColor("#DDDDDD"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })
        col.addView(optionRow(
            L("هر کانفیگی که پینگش سبز بشه", "Any config whose ping turns green"),
            L("(کانفیگ‌ها سریع‌تر دریافت می‌شود، ممکن است پینگ کانفیگ‌ها بالا باشد)", "(Configs arrive faster; their pings may be high)")
        ) {
            freeFetchMode = FreeFetchMode.ANY_GREEN
            startFreeFetch()
        })
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle(L("دریافت کانفیگ رایگان", "Get free configs"))
            .setView(col)
            .create()
        dialog = dlg
        dlg.show()
        styleDialogRtl(dlg)
    }

    private fun startFreeFetch() {
        if (!initCore()) {
            try {
                android.widget.Toast.makeText(this,
                    L("دریافت شروع نشد: هسته Xray راه‌اندازی نشد.",
                        "Fetch did not start: Xray core failed to start."),
                    android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
            return
        }
        freeFetchRunning = true
        freeFetchCancelled = false
        // 1.36: tutorial step 1 -> waiting step 2.
        onV2rayTutFetchStarted()
        freeBtn.text = L("لغو دریافت", "Cancel fetch")
        // 1.40 (B): the waiting line under the button.
        showFreeWaitIndicator()
        // 1.40 (A1): snapshot the persistent fetched
        // set BEFORE this run, and the free count the
        // new numbers continue from — both on the UI
        // thread, before the worker starts. The set
        // holds ONLY configs actually ADDED (see
        // addOneFree) — never everything fetched.
        // That is the 1.38/1.39 bug: the set held all
        // ~600 fetched keys, so run 2 found nothing
        // fresh and showed «کانفیگ جدیدی پیدا نشد»
        // even after delete-all. Now a later tap
        // tests the healthy configs run 1 fetched
        // but never added, and adds the rest.
        // 1.39 (item 5) numbering, unchanged: a
        // second tap numbers 11..20 — nextFreeNumber
        // starts at the existing free count (10) and
        // increments per add. One real gap WAS found
        // and fixed in 1.39: if the user deleted a
        // middle free row, the COUNT is below the
        // highest number still in the list, so
        // count-only numbering would re-use an
        // existing number. Start from the higher of
        // the two — identical to 1.38 when nothing
        // was deleted (max == count).
        val prevFetchedKeys = freeFetchedKeys()
        val baseFreeCount = maxOf(
            servers.count { FreeConfigs.isFreeConfig(it) },
            FreeConfigs.maxFreeNumber(servers)
        )
        // A fetched config the user already has (same
        // server, their own name) is never added again
        // under a free name.
        val ownKeys = servers.filter { !FreeConfigs.isFreeConfig(it) }
            .map { XraySpeedtest.dedupKey(it) }.toSet()
        // 1.30: the route URL is the page's own delay-test
        // URL (gstatic generate_204 by default, the user's
        // override when set — exactly like pingAll).
        val routeUrl = XraySpeedtest.testUrl(this@VpnActivity)
        // Adds ONE proven config to the list (UI thread
        // only): numbered continuing from the existing
        // free rows, in add order. The geo lookup runs
        // AFTER the row is in (fire-and-forget, never
        // blocking the stream): the row falls back to
        // its host lookup until the geo lands, exactly
        // as the 1.33 batch lookup's failure path did.
        var nextFreeNumber = baseFreeCount
        fun addOneFree(cfg: String, ping: Long) {
            if (freeFetchCancelled) return
            nextFreeNumber += 1
            val renamed = FreeConfigs.renameFree(cfg, nextFreeNumber)
            servers += renamed
            // 1.40 (A1): ONLY an actually added
            // config joins the persistent set — at
            // once, so a cancel or a kill right
            // after the add cannot lose it. A
            // fetched-but-never-added config stays
            // out of the set, so the next tap can
            // still add it.
            try {
                val keys = freeFetchedKeys()
                if (keys.add(XraySpeedtest.dedupKey(renamed))) saveFreeFetchedKeys(keys)
            } catch (_: Exception) {}
            pingMap[XraySpeedtest.dedupKey(renamed)] = ping
            // 1.41 (C): pingMap is in-memory only, so a
            // page re-entry lost every measured ping
            // and the rows fell back to "—" (the 1.40
            // regression). Persist it the same way
            // PingTestService.mergeDelays does (merge,
            // never replace) into renamer_subs
            // ping_delays, keyed by dedupKey; the row
            // reads pingMap first and falls back to
            // persistedPingMs (see the row builder).
            try {
                val sp = getSharedPreferences("renamer_subs", MODE_PRIVATE)
                val obj = org.json.JSONObject(sp.getString("ping_delays", null) ?: "{}")
                obj.put(XraySpeedtest.dedupKey(renamed), ping)
                sp.edit().putString("ping_delays", obj.toString()).apply()
            } catch (_: Exception) {}
            // 1.41 (A): the first fetch that adds ≥1
            // config hides the guide third of the free
            // box for good (persisted flag).
            try {
                if (!hasFirstFreeFetched()) {
                    prefs().edit().putBoolean("v2ray_first_free_fetched", true).apply()
                    renderFreeBox()
                }
            } catch (_: Exception) {}
            if (selected == null) selected = servers.firstOrNull()
            saveServers(); refreshList()
            Thread {
                try {
                    val host = XraySpeedtest.parseServer(renamed)?.host
                        ?: return@Thread
                    val (geo, _) = GeoFlag.lookupSync(host) ?: return@Thread
                    GeoFlag.storePingGeo(
                        this@VpnActivity, renamed,
                        geo.first, geo.second, geo.third
                    )
                    runOnUiThread { try { refreshList() } catch (_: Exception) {} }
                } catch (_: Throwable) {}
            }.start()
        }
        Thread {
            // 1.44 (F): the sub fetch covers 0..10% —
            // one report per finished sub (4 subs).
            // Named arguments: the trailing lambda must
            // bind to onSub, not to cancelled.
            val fetched = FreeConfigs.fetchAll(
                cancelled = { freeFetchCancelled },
                onSub = { done, total -> updateFreeWaitPct(done * 10 / total) }
            )
            if (freeFetchCancelled) {
                runOnUiThread { finishFreeFetch("دریافت لغو شد.", "Fetch cancelled."); onV2rayTutFetchEnded(false) }
                return@Thread
            }
            if (fetched.isEmpty()) {
                runOnUiThread { finishFreeFetch("دریافت ناموفق بود", "Fetch failed"); onV2rayTutFetchEnded(false) }
                return@Thread
            }
            // 1.40 (A1): the persistent set is NOT
            // extended here. 1.38/1.39 saved EVERY
            // fetched key at once — that is the bug:
            // run 1 fetched ~600, added 10, and run
            // 2 then skipped all ~600. Only configs
            // NOT in the previous set are tested now
            // (the set holds added configs only, see
            // addOneFree), and the user's own configs
            // are never added under a free name.
            val fresh = fetched.filter { XraySpeedtest.dedupKey(it) !in prevFetchedKeys }
            val candidates = fresh.filter { XraySpeedtest.dedupKey(it) !in ownKeys }
            if (candidates.isEmpty()) {
                runOnUiThread { finishFreeFetch("کانفیگ جدیدی پیدا نشد", "No new configs found"); onV2rayTutFetchEnded(false) }
                return@Thread
            }
            // 1.38: stream — each passer is added the
            // moment its full 5-route minimum proves
            // under 200 ms, and the run stops at 10.
            // 1.44 (E): in ANY_GREEN mode the <200 ms
            // gate (and the 250 ms screen gate) are
            // dropped — any config whose ping test
            // returns a measurement is added, first 10.
            // 1.44 (F): the run reports live progress
            // (fetch / pre-check / ping) to the waiting
            // line; nothing else is written while testing.
            val result = FreeConfigs.pingStreamingFree(
                candidates,
                { c -> XraySpeedtest.measureOneWithUrl(c, routeUrl) },
                onAdded = { cfg, ping ->
                    runOnUiThread { addOneFree(cfg, ping) }
                },
                cancelled = { freeFetchCancelled },
                // 1.44 (E): ANY_GREEN drops both gates
                // (Long.MAX_VALUE) — the ping test itself
                // still runs and only measured configs pass.
                pingLimitMs = if (freeFetchMode == FreeFetchMode.ANY_GREEN) Long.MAX_VALUE else FreeConfigs.FREE_PING_LIMIT_MS,
                screenGateMs = if (freeFetchMode == FreeFetchMode.ANY_GREEN) Long.MAX_VALUE else FreeConfigs.SCREEN_GATE_MS,
                // 1.44 (F): pre-check (phase 0) and ping
                // test (phase 1) progress, live.
                onProgress = { phase, processed, total -> onFreeFetchProgress(phase, processed, total) }
            )
            if (freeFetchCancelled) {
                runOnUiThread {
                    // Rows added before the cancel stay
                    // (they already passed); nothing new
                    // is added after it.
                    finishFreeFetch("دریافت لغو شد.", "Fetch cancelled.")
                    onV2rayTutFetchEnded(false)
                }
                return@Thread
            }
            val n = result.added.size
            runOnUiThread {
                if (n > 0) {
                    finishFreeFetch("${faNum(n)} کانفیگ رایگان اضافه شد.", "$n free configs added.")
                    onV2rayTutFetchEnded(true)
                } else {
                    // 1.40 (A3): fresh configs WERE
                    // fetched but none passed — 0
                    // survivors after the TCP
                    // pre-check, or none under 200 ms.
                    // That is NOT «کانفیگ جدیدی پیدا
                    // نشد»: that line is only for the
                    // all-duplicates case (candidates
                    // empty, above). On a fresh
                    // install this is the branch the
                    // user actually hit after his
                    // reinstall — the old message
                    // blamed duplicates that did not
                    // exist. The fetch itself did not
                    // fail (fetched non-empty, above),
                    // so «دریافت ناموفق بود» is wrong
                    // here too: no healthy config was
                    // found.
                    finishFreeFetch("کانفیگ سالمی پیدا نشد", "No healthy config found")
                    onV2rayTutFetchEnded(false)
                }
            }
        }.start()
    }

    /** 1.38 (A1/A2): the ONLY user-visible output of
     *  a free run is one Toast at the bottom of the
     *  page with the real count — no status text, no
     *  strip line, no status-history line, no log.
     *  (The tutorial's own failure toast is NOT
     *  fired as well: onV2rayTutFetchEnded is always
     *  called here with failed = false, so a run
     *  produces exactly one bottom message.) */
    private fun finishFreeFetch(fa: String, en: String) {
        freeFetchRunning = false
        freeFetchCancelled = false
        // 1.40 (B): the waiting line goes away with
        // the run — finished or cancelled alike.
        hideFreeWaitIndicator()
        if (::freeBtn.isInitialized) freeBtn.text = L("دریافت کانفیگ رایگان", "Get free configs")
        try {
            android.widget.Toast.makeText(this, L(fa, en),
                android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    /** 1.40 (B): the waiting line — BLACK, the
     *  SAME 11sp as the free button text, DIRECTLY
     *  under the free button (inserted at
     *  indexOfChild(freeBtn)+1, so the guide
     *  button below it is pushed down while
     *  fetching). 1.44 (F): «کمی صبر کنید ... ٪X»
     *  — the live fetch percentage on the same line,
     *  from 0 to 100 as the pipeline advances. No
     *  other status text — just this line. This one
     *  indicator is the only exception to the 1.38
     *  no-status order, by the user's explicit order. */
    private fun showFreeWaitIndicator() {
        try {
            hideFreeWaitIndicator()
            if (freeBox == null) return
            val parent = freeBox?.parent as? android.view.ViewGroup ?: return
            freeFetchPct = 0
            val tv = android.widget.TextView(this).apply {
                text = freeWaitText()
                textSize = 11f
                setTextColor(android.graphics.Color.BLACK)
                textDirection = appTextDir()
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    setMargins(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
                }
            }
            freeWaitView = tv
            parent.addView(tv, parent.indexOfChild(freeBox) + 1)
            handler.postDelayed(freeWaitTicker, 1000)
        } catch (_: Exception) {}
    }

    private fun hideFreeWaitIndicator() {
        try {
            handler.removeCallbacks(freeWaitTicker)
            val tv = freeWaitView ?: return
            freeWaitView = null
            (tv.parent as? android.view.ViewGroup)?.removeView(tv)
        } catch (_: Exception) {}
    }

    private fun pingAll() {
        // Groups are headers, not servers: never ping
        // them. Chains are multi-hop, not single
        // outbounds: measuring them always yields -1,
        // which is a lie (they are not "dead"), so
        // they are left untested ("—") instead.
        val targets = servers.filter { !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it) }
        if (targets.isEmpty()) {
            statusText.text = L("کانفیگی برای تست نیست.", "No config to test.")
            return
        }
        // Root cause of the previous "-1 for everything":
        // this page never initialized the Xray core (Seq
        // context + initCoreEnv) before measuring, unlike
        // the subscription page which does. Without it,
        // measureOutboundDelay fails for every config.
        if (!initCore()) {
            statusText.text = L("تست پینگ شروع نشد: هسته Xray راه‌اندازی نشد.", "Ping test did not start: Xray core failed to start.")
            return
        }
        statusText.text = L("در حال تست پینگ همه سرورها (${targets.size} تا)...", "Testing ping for all servers (${targets.size} servers)...")
        setStatusProgress("در حال تست پینگ همه سرورها...", "Testing ping for all servers...")
        Thread {
            val pool = Executors.newFixedThreadPool(XraySpeedtest.poolSize())
            var firstError: String? = null
            val errLock = Any()
            val tested = java.util.concurrent.atomic.AtomicInteger(0)
            val total = targets.size
            val futures = targets.map { cfg ->
                pool.submit {
                    val (d, err) = try {
                        XraySpeedtest.measureOneWithError(cfg)
                    } catch (t: Throwable) {
                        -1L to (t.message ?: t.javaClass.simpleName)
                    }
                    if (err != null) {
                        synchronized(errLock) { if (firstError == null) firstError = err }
                    }
                    synchronized(pingMap) { pingMap[XraySpeedtest.dedupKey(cfg)] = d }
                    // Stream: show this row's result the
                    // moment it arrives, instead of
                    // waiting for the whole test to end.
                    val done = tested.incrementAndGet()
                    runOnUiThread {
                        refreshList()
                        setStatusProgress("از $total کانفیگ $done تست گرفته شد", "$done of $total configs tested")
                    }
                }
            }
            for (f in futures) { try { f.get() } catch (_: Throwable) {} }
            pool.shutdown()
            runOnUiThread {
                refreshList()
                val healthy = targets.count { (pingMap[XraySpeedtest.dedupKey(it)] ?: -1L) >= 0 }
                val allBad = targets.all { (pingMap[XraySpeedtest.dedupKey(it)] ?: -1L) < 0 }
                statusText.text = if (allBad && firstError != null)
                    L("تست پینگ تمام شد، همه -۱ شدند. خطای اول: $firstError", "Ping test finished, all were -1. First error: $firstError")
                else
                    L("تست پینگ تمام شد: $healthy سالم از ${targets.size}.", "Ping test finished: $healthy healthy of ${targets.size}.")
                setStatusProgress("تست پینگ انجام شد", "Ping test finished")
                addStatusLine("از ${targets.size} کانفیگ $healthy کانفیگ سالم بود", "$healthy of ${targets.size} configs were healthy")
                val removed = deleteNoPingIfEnabled(targets)
                if (removed > 0) addStatusLine("$removed کانفیگ بدون پینگ حذف شد", "$removed no-ping configs removed")
            }
        }.start()
    }
}
