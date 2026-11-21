package com.renamer

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.*
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import org.amnezia.awg.backend.Tunnel as AwgTunnel
import org.amnezia.awg.config.Config as AwgConfig
import java.io.BufferedReader
import java.io.StringReader
import org.json.JSONArray
import org.json.JSONObject

/**
 * WireGuard section, exactly like the official
 * WireGuard app:
 * - A list of tunnels, each row with a name, its
 *   endpoint underneath, and a Switch on the right
 *   to connect to exactly that tunnel (only one
 *   tunnel active at a time).
 * - A + button at the bottom-right: import a .conf
 *   file from storage, or import from the clipboard.
 *
 * Connection uses the OFFICIAL WireGuard Go backend
 * (com.wireguard.android:tunnel, wg-go), the same
 * library the official app uses — NOT Xray. The
 * .conf text is parsed by the official
 * com.wireguard.config.Config parser, and GoBackend
 * builds the TUN itself (addresses, routes from
 * AllowedIPs, DNS, MTU) and runs wg-go on it. That
 * is why the previous Xray-based WireGuard path,
 * which hung the app, could not turn off, and
 * connected without traffic, is gone: there is no
 * second TUN, no Xray routing, and no stopLoop on
 * the main thread any more.
 *
 * All GoBackend calls run on a background thread:
 * setState blocks waiting for its VpnService and
 * resolving the peer endpoint, and calling it on
 * the main thread would hang the UI. The Switch
 * listener only starts that background work, and
 * the list is only rebuilt when the running state
 * actually changes, so tapping a switch can never
 * be lost to a 2-second list rebuild.
 *
 * The private key is never shown in the UI (masked
 * in the details dialog) and is stored only in this
 * app's private preferences, as part of the
 * tunnel's .conf text.
 */
/** 1.57 — pure decision for the dead-card cleanup in
 *  tryWireGuardRung: the rung's "WARP (WireGuard)" tunnel may be
 *  deleted on verify failure only when no WG-rung WarpRecord is
 *  saved (a previously verified WARP config keeps its tunnel).
 *  Top-level so the JVM test can call it without an Activity
 *  instance. */
internal fun shouldDeleteDeadWarpTunnel(savedWgRecordCount: Int): Boolean =
    savedWgRecordCount == 0

/** 1.70 — CONNECT-BUTTON CONNECTING STATE (user order
 *  2026-11-21 ~00:12): when the free-warp card's connect
 *  button is tapped it must enter CONNECTING — the circle
 *  inside it slides left→right while the inside stays
 *  YELLOW — and turn GREEN only on the real connected
 *  verdict (real traffic passed), never when the tunnel
 *  merely reports UP. On failure / manual disconnect it
 *  returns to the idle appearance.
 *
 *  The three visual states, in priority order:
 *  CONNECTED wins over CONNECTING (a verified row can
 *  never show yellow), CONNECTING wins over IDLE. */
enum class WarpConnectVisualState { IDLE, CONNECTING, CONNECTED }

/** 1.70 — pure decision logic for the connect button.
 *  No Android dependency — JVM-testable. The Activity
 *  feeds it only two facts it already owns:
 *  isVerifiedActive = isWarpActive(rec) (already
 *  verify-gated since 1.47: bare interface-UP is NOT
 *  active) and connecting = warpConnecting (plain
 *  connect in flight). 1.71: the relay chain-test
 *  phase (and its relayConnecting flag) is gone —
 *  the button connects plain free warp only. */
object WarpConnectButtonState {

    /** One left→right slide of the knob, in ms. */
    const val SLIDE_PERIOD_MS = 900L

    /** The visual state for one row. */
    fun resolve(isVerifiedActive: Boolean, connecting: Boolean): WarpConnectVisualState =
        when {
            isVerifiedActive -> WarpConnectVisualState.CONNECTED
            connecting -> WarpConnectVisualState.CONNECTING
            else -> WarpConnectVisualState.IDLE
        }

    /** Knob position inside the pill, 0f (left edge) to 1f
     *  (right edge): a sawtooth — the knob travels
     *  left→right, then jumps back to the left and
     *  repeats. PHYSICAL left→right in every locale,
     *  exactly as ordered; never mirrored in RTL.
     *  elapsedMs is ms since the CONNECTING state began
     *  (any monotonic clock). */
    fun slideFraction(elapsedMs: Long, periodMs: Long = SLIDE_PERIOD_MS): Float {
        if (periodMs <= 0L) return 0f
        val e = ((elapsedMs % periodMs) + periodMs) % periodMs
        return e.toFloat() / periodMs.toFloat()
    }

    /** A tap while CONNECTING must be ignored — it must
     *  never start a second connect (warpConnect already
     *  no-ops on warpConnecting, and the tap handler
     *  checks this too). */
    fun tapIsIgnoredWhileConnecting(state: WarpConnectVisualState): Boolean =
        state == WarpConnectVisualState.CONNECTING
}

/** 1.70 — the free-warp card's connect control, drawn
 *  directly (same 49x25dp pill shape as the old
 *  wgToggleBitmap toggle, same geometry: knob radius =
 *  half height − 2.5dp, knob inset 2.5dp from the edge,
 *  same glass highlight on the knob).
 *  - IDLE: the old OFF pill, unchanged (grey track,
 *    knob parked left).
 *  - CONNECTING: YELLOW track (#FFC107, both modes)
 *    with the knob sliding left→right on a 900ms loop.
 *  - CONNECTED: solid GREEN track (#43A047, the green
 *    already used for active rows on this page), knob
 *    parked right, no motion.
 *  The slide is physical left→right regardless of
 *  locale (layoutDirection forced LTR). The animator
 *  runs only while CONNECTING and is cancelled on
 *  state change and on detach, so a row rebuilt by
 *  refreshList() never leaks an animator. */
class WarpConnectToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var state: WarpConnectVisualState = WarpConnectVisualState.IDLE
        set(value) {
            if (field == value) return
            field = value
            applyState()
            invalidate()
        }

    private var slideFraction: Float = 0f
    private var slideAnim: ValueAnimator? = null
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val trackRect = RectF()

    init {
        // 1.70 — the slide is PHYSICAL left→right,
        // exactly as ordered, in every locale: never
        // mirror it in RTL.
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        isClickable = true
        isFocusable = true
        applyState()
    }

    private fun applyState() {
        slideAnim?.cancel()
        slideAnim = null
        if (state == WarpConnectVisualState.CONNECTING) {
            slideAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = WarpConnectButtonState.SLIDE_PERIOD_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    // The animator itself is the 0→1 slide;
                    // slideFraction() (pure, JVM-tested)
                    // is the same sawtooth for non-View use.
                    slideFraction = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            slideFraction =
                if (state == WarpConnectVisualState.CONNECTED) 1f else 0f
        }
    }

    override fun onDetachedFromWindow() {
        slideAnim?.cancel()
        slideAnim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val d = resources.displayMetrics.density
        val dark = Theme.isDark(context)
        val (track, knob) = when (state) {
            // IDLE: the old OFF pill, exactly as before.
            WarpConnectVisualState.IDLE ->
                if (dark) "#3A3F4B" to "#6B7280" else "#E0E0E0" to "#FFFFFF"
            // CONNECTING: yellow inside, knob slides.
            WarpConnectVisualState.CONNECTING ->
                "#FFC107" to "#FFFFFF"
            // CONNECTED: solid green, knob parked right.
            WarpConnectVisualState.CONNECTED ->
                "#43A047" to "#C8E6C9"
        }
        trackPaint.color = Color.parseColor(track)
        knobPaint.color = Color.parseColor(knob)
        val r = h / 2f
        trackRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(trackRect, r, r, trackPaint)
        val knobR = h / 2f - 2.5f * d
        val inset = 2.5f * d
        val travel = (w - 2f * inset - 2f * knobR).coerceAtLeast(0f)
        val knobCx = inset + knobR + slideFraction * travel
        canvas.drawCircle(knobCx, h / 2f, knobR, knobPaint)
        canvas.drawCircle(knobCx - knobR * 0.3f, h / 2f - knobR * 0.35f, knobR * 0.45f, hiPaint)
    }
}

class WireGuardActivity : Activity() {
    private data class WgTunnel(
        val name: String,
        val conf: String,
        val privateKey: String,
        val address: List<String>,
        val dns: List<String>,
        val mtu: Int?,
        val peers: List<WgPeer>
    )
    private data class WgPeer(
        val publicKey: String,
        val preSharedKey: String?,
        val endpoint: String,
        val allowedIPs: List<String>,
        val keepAlive: Int?
    )

    /** Tunnel object for GoBackend. Its interface
     *  name must be a valid wg name (max 15 chars,
     *  [a-zA-Z0-9_=+.-]), so the user's display name
     *  is sanitised here; the UI always shows the
     *  original display name. */
    private inner class SimpleTunnel(val displayName: String) : Tunnel {
        val ifaceName: String = sanitizeName(displayName)
        override fun getName(): String = ifaceName
        override fun onStateChange(state: Tunnel.State) {
            runOnUiThread {
                if (state == Tunnel.State.UP) {
                    activeName = displayName
                    lastError = null
                    // 1.46: UP is the interface coming up, NOT
                    // a connection — a blackholed endpoint also
                    // shows UP. Never log "connected" here; the
                    // "connected" log (and QuickConnect + the
                    // last-ok stamp) happen only after the
                    // handshake verify proves the tunnel, in
                    // startWgInBackground's onDone below.
                    // 1.47: for a WARP WireGuard tunnel the
                    // bare UP line is suppressed entirely —
                    // it read as "connected" while no traffic
                    // passed. The warp log carries the real
                    // verdict (traffic test) instead.
                    if (!isWarpWgTunnel(displayName))
                        WgLog.add(L("تونل بالا آمد: ", "Tunnel up: ") + displayName)
                } else if (activeName == displayName) {
                    activeName = null
                }
                if (state == Tunnel.State.DOWN) WgLog.add(L("قطع شد: ", "Disconnected: ") + displayName)
                if (state == Tunnel.State.DOWN && WgTunnelRegistry.wgTunnel === this)
                    WgTunnelRegistry.wgTunnel = null
                saveTunnels()
                refreshStatus()
                refreshList()
            }
        }
    }

    /** Tunnel object for the AmneziaWG backend: same
     *  sanitising and state handling as SimpleTunnel. */
    private inner class AwgSimpleTunnel(val displayName: String) : AwgTunnel {
        val ifaceName: String = sanitizeName(displayName)
        override fun getName(): String = ifaceName
        override fun onStateChange(state: AwgTunnel.State) {
            runOnUiThread {
                if (state == AwgTunnel.State.UP) {
                    activeName = displayName
                    lastError = null
                    // 1.46: see the note above (SimpleTunnel) —
                    // UP is not a connection.
                    // 1.47: suppressed for WARP WG tunnels
                    // (same reason as above).
                    if (!isWarpWgTunnel(displayName))
                        WgLog.add(L("تونل بالا آمد: ", "Tunnel up: ") + displayName)
                } else if (activeName == displayName) {
                    activeName = null
                }
                if (state == AwgTunnel.State.DOWN) WgLog.add(L("قطع شد: ", "Disconnected: ") + displayName)
                if (state == AwgTunnel.State.DOWN && WgTunnelRegistry.awgTunnel === this)
                    WgTunnelRegistry.awgTunnel = null
                saveTunnels()
                refreshStatus()
                refreshList()
            }
        }
    }


    private lateinit var statusText: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private var tunnels: MutableList<WgTunnel> = mutableListOf()
    private var activeName: String? = null
    private var pendingTunnel: WgTunnel? = null
    private var currentTunnel: SimpleTunnel? = null
    private var currentAwgTunnel: AwgSimpleTunnel? = null
    private var lastError: String? = null
    private var switchUpdating = false
    private var lastShownState: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            // Only rebuild when the state actually
            // changed; rebuilding every 2 seconds used
            // to eat taps and flicker the switches.
            val state = (activeName ?: "") + "|" + isWgUp() + "|" +
                WarpFree.activeId(this@WireGuardActivity) + "|" +
                VpnClientService.isRunning + "|" + (VpnClientService.sessionTag ?: "")
            if (state != lastShownState) {
                lastShownState = state
                refreshStatus()
                refreshList()
            }
            handler.postDelayed(this, 2000)
        }
    }

    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private fun prefs() = getSharedPreferences("renamer_wg", MODE_PRIVATE)

    /** 2.67: WARP removal — one-time upgrade cleanup.
     *  Deletes ONLY the tunnel the free-WARP feature
     *  itself created (named "WARP" with a Cloudflare
     *  WARP endpoint), the feature's stored identity
     *  prefs, the engine's stored WARP account, and
     *  the WARP TUN prefs file. A hand-made tunnel
     *  merely named "WARP" (different endpoint) is
     *  left alone. Idempotent: a second run finds
     *  nothing to delete. */
    private fun removeWarpRemnants() {
        try {
            val sp = prefs()
            val raw = sp.getString("wg_list", "[]") ?: "[]"
            val arr = JSONArray(raw)
            var changed = false
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("name", "") == "WARP" && isWarpBuiltConf(o.optString("conf", ""))) {
                    changed = true
                    continue
                }
                out.put(o)
            }
            if (changed) {
                val e = sp.edit().putString("wg_list", out.toString())
                if (sp.getString("wg_active", "") == "WARP") e.putString("wg_active", "")
                e.apply()
            }
        } catch (_: Exception) {}
        try { getSharedPreferences("renamer_warp", MODE_PRIVATE).edit().clear().apply() } catch (_: Exception) {}
        try { getSharedPreferences("renamer_engine", MODE_PRIVATE).edit().remove("warp_account").apply() } catch (_: Exception) {}
        try { deleteSharedPreferences("renamer_tun_warp") } catch (_: Exception) {}
        try {
            getSharedPreferences("emergency_settings", MODE_PRIVATE).edit()
                .remove("connect_method").remove("warp_session").remove("warp_prev_wg_active").apply()
        } catch (_: Exception) {}
    }

    /** True when a conf text is one the free-WARP
     *  feature built: its endpoint is Cloudflare's
     *  WARP ingress (the registered host, one of the
     *  alternate ingress IPs, or the 2.64 JSON blob
     *  that still holds the host). */
    private fun isWarpBuiltConf(conf: String): Boolean {
        if (conf.isBlank()) return false
        return conf.contains("engage.cloudflareclient.com") ||
            conf.contains("162.159.193.1") || conf.contains("162.159.192.1") ||
            conf.contains("162.159.195.1") || conf.contains("188.114.96.1")
    }

    /** In-memory connection-event log for this page
     *  only (never persisted): connect, disconnect
     *  and error lines with times, shown only
     *  behind the top log icon. */
    private object WgLog {
        private val lines = mutableListOf<String>()
        fun add(s: String) {
            val tm = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            lines += "$tm  $s"
            while (lines.size > 100) lines.removeAt(0)
        }
        fun text(): String = lines.joinToString("\n")
        fun clear() { lines.clear() }
    }

    private fun sanitizeName(name: String): String {
        val clean = name.map { c ->
            if (c.isLetterOrDigit() || c in "_=+.-") c else '_'
        }.joinToString("").take(15)
        return clean.ifBlank { "wg" }
    }

    private fun backend(): GoBackend? {
        return try {
            WgBackend.get(applicationContext)
        } catch (e: Throwable) {
            lastError = "wg-go failed to load: ${e.message ?: e.javaClass.simpleName}"
            WgLog.add(L("خطا: ", "Error: ") + (e.message ?: e.javaClass.simpleName))
            null
        }
    }

    private fun awgBackend(): org.amnezia.awg.backend.GoBackend? {
        return try {
            AwgBackend.get(applicationContext)
        } catch (_: Throwable) { null }
    }

    private fun isWgUp(): Boolean {
        try {
            val b = WgBackend.get(applicationContext)
            val t = currentTunnel
            if (t != null && b.getState(t) == Tunnel.State.UP) return true
        } catch (_: Throwable) {}
        try {
            val b = AwgBackend.get(applicationContext)
            val t = currentAwgTunnel
            if (t != null && b.getState(t) == AwgTunnel.State.UP) return true
        } catch (_: Throwable) {}
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2.88: the WG page follows the home theme — the shared night
        // aurora / pearl day background, same as every tap-opened page.
        Theme.applyPage(this)
        val dark = Theme.isDark(this)
        scrollView = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scrollView.addView(root)
        // Log icon at the top: small, minimal, classy.
        // The log itself lives ONLY behind this icon,
        // never inline on the main page.
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(View(this).apply {
            // 1.71 (item 6): 28dp, like the OpenVPN
            // header's log icon (its spacer matches
            // the button size so the title stays centred).
            val s = (28 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
        })
        topRow.addView(TextView(this).apply {
            text = "وایرگارد و امنزیا"; textSize = 22f
            setTextColor(Theme.fg(this@WireGuardActivity))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 0, 0, 12)
        })
        // 1.71 (item 6) — header buttons EXACTLY like
        // the OpenVPN header (OpenVpnActivity.buildHeader):
        // "?" guide circle 28x28dp, 15.4sp bold,
        // OvpnTheme.glassCircle, marginEnd 8dp, then the
        // log icon 28x28dp (logIconBitmap 28, no
        // background, no padding). The old WG log icon
        // was 40x40 with an 8dp-padded glass background
        // and there was no header guide button at all.
        // ("?" is not Persian text, so LTR like
        // OpenVPN's; its colour is Theme.fg, this
        // page's own foreground, same as its title.)
        topRow.addView(TextView(this).apply {
            text = "?"
            textSize = 15.4f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.fg(this@WireGuardActivity))
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.CENTER
            background = OvpnTheme.glassCircle(this@WireGuardActivity)
            layoutParams = LinearLayout.LayoutParams(
                (28 * resources.displayMetrics.density).toInt(),
                (28 * resources.displayMetrics.density).toInt()).apply {
                marginEnd = (8 * resources.displayMetrics.density).toInt()
            }
            setOnClickListener { showWarpGuide() }
        })
        topRow.addView(ImageView(this).apply {
            setImageBitmap(HydraUi.logIconBitmap(this@WireGuardActivity, 28))
            val s = (28 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { showWgLogDialog() }
        })
        root.addView(topRow)
        // 1.69 — CONNECT-PAGE SILENCE (user order
        // 2026-11-20 ~22:40): the page shows NO
        // status text at all except while the WARP
        // account is being created (the fetch
        // progress UI) — so the line starts blank
        // and refreshStatus() never writes it (see
        // clearConnectStatus below).
        statusText = TextView(this).apply {
            text = ""; textSize = 17f
            setTextColor(Theme.subFg(this@WireGuardActivity))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }
        root.addView(statusText)
        // 1.60 — fetch progress: a horizontal bar + %
        // under the status line, shown only while a free
        // fetch runs, driven by the fetch's REAL stage
        // weights (WarpFree.FetchProgress) — never a
        // timer. The % moves as the work moves.
        warpProgressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, (8 * resources.displayMetrics.density).toInt()) }
        }
        warpProgressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            try {
                progressTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#2E9E5B"))
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (dark) Theme.darken(Color.parseColor("#33FFFFFF")) else Color.parseColor("#1A000000"))
            } catch (_: Exception) {}
            layoutParams = LinearLayout.LayoutParams(0, (6 * resources.displayMetrics.density).toInt(), 1f)
        }
        warpProgressPct = TextView(this).apply {
            text = "0%"
            textSize = 12f
            setTextColor(Theme.subFg(this@WireGuardActivity))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins((8 * resources.displayMetrics.density).toInt(), 0, 0, 0) }
        }
        warpProgressRow?.addView(warpProgressBar)
        warpProgressRow?.addView(warpProgressPct)
        root.addView(warpProgressRow)
        buildWarpFreeBox(root)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)
        val frame = android.widget.FrameLayout(this)
        frame.background = Theme.pageBackground(this)
        frame.addView(scrollView)
        val fabSize = (72 * resources.displayMetrics.density).toInt()
        val fabMargin = (24 * resources.displayMetrics.density).toInt()
        val fab = Button(this).apply {
            text = "+"; textSize = 32f
            // 2.91 (18): SAME as the OpenVPN + button — the
            // refined glass circle with a teal→purple gradient
            // border and soft glow (Theme.gradientGlowCircle,
            // 2.88), same fill, same colours, same 72dp size.
            // Position and behaviour are unchanged.
            setTextColor(Color.parseColor(if (dark) "#FFFFFF" else "#8B7CF6"))
            background = Theme.gradientGlowCircle(this@WireGuardActivity,
                if (dark) Theme.darken(Color.parseColor("#CC2A3048")) else Color.parseColor("#F2EEFB"), dark)
            layoutParams = android.widget.FrameLayout.LayoutParams(fabSize, fabSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(fabMargin, fabMargin, fabMargin, fabMargin)
            }
            setPadding(0, 0, 0, 0)
            setOnClickListener { showImportMenu() }
        }
        frame.addView(fab)
        root.setPadding(32, 32, 32, 32 + fabSize + fabMargin)
        setContentView(frame)
        frame.fitsSystemWindows = true
        // 2.67: WARP was removed — on first open after the
        // upgrade, delete the free-WARP tunnel and the
        // feature's stored identity (local only, no
        // network), before the list loads.
        try { removeWarpRemnants() } catch (_: Exception) {}
        loadTunnels()
        loadWarpRecords()
        renderWarpFreeBox()
        refreshList()
        refreshStatus()
        // 1.71 (item 2) — the WG widget with ONLY a free
        // config never connected: BOTH widget paths
        // resolve the "active" config from renamer_wg
        // wg_list / wg_active (WgDirect.connectActive on
        // the tap, then this block) — but a free-warp
        // record lives in renamer_warp_free
        // (WarpFree.loadAll) and is NEVER in wg_list,
        // so activeName is null and the tap opened the
        // page and did nothing. When no plain tunnel is
        // active, fall back to the free-warp record:
        // the last verified one (WarpFree.activeId),
        // else the first record — plain MASQUE, no
        // relay (item 9). Skipped while any warp is
        // already up, so a tap on a connected widget
        // never restarts it. The plain-tunnel branch
        // is unchanged.
        if (intent.getBooleanExtra("widget_auto_connect", false) && !isWgUp() && !isAnyWarpUp()) {
            val plain = activeName?.let { n -> tunnels.firstOrNull { it.name == n } }
            if (plain != null) connectTunnel(plain)
            else if (activeName == null) {
                val warpRec = WarpFree.activeId(this).takeIf { it.isNotBlank() }
                    ?.let { id -> warpRecords.firstOrNull { it.id == id } }
                    ?: warpRecords.firstOrNull()
                if (warpRec != null) warpConnect(warpRec)
            }
        }
        // 1.74 (C): the home last-connection card's reconnect
        // for free warp (QuickConnect M_WARP branch). Same
        // guard shape as the widget block above — never
        // restart a live session — and the EXACT record only:
        // the id first, then the recorded card name (a
        // re-fetch replaces the record's id but keeps its
        // name). Neither matches -> the page just opens and
        // the user picks; never connect a different record.
        // warpConnect itself is the page's own path (aether
        // session tunnel / IP replacement / traffic verify).
        val warpAutoId = intent.getStringExtra("warp_auto_connect_id")
        val warpAutoName = intent.getStringExtra("warp_auto_connect_name")
        if ((warpAutoId?.isNotBlank() == true || warpAutoName?.isNotBlank() == true) &&
            !isWgUp() && !isAnyWarpUp()
        ) {
            val warpRec = warpRecords.firstOrNull { it.id == warpAutoId }
                ?: WarpLastConnectPure.resolve(
                    warpRecords.map { WarpLastConnectPure.WarpRef(it.id, it.name) },
                    warpAutoName
                )?.let { ref -> warpRecords.firstOrNull { it.id == ref.id } }
            if (warpRec != null) warpConnect(warpRec)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poller)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
    }

    private fun loadTunnels() {
        tunnels = mutableListOf()
        try {
            val arr = JSONArray(prefs().getString("wg_list", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                val conf = o.optString("conf")
                val parsed = parseConf(conf) ?: continue
                tunnels += parsed.copy(name = name)
            }
        } catch (_: Exception) {}
        activeName = prefs().getString("wg_active", null)?.takeIf { it.isNotBlank() }
        if (activeName != null && tunnels.none { it.name == activeName }) activeName = null
        if (activeName != null) currentTunnel = SimpleTunnel(activeName!!)
    }

    /** A parse/connect failure must read as a human
     *  Persian sentence, never a raw exception class name. */
    private fun humanWgError(e: Throwable): String {
        val cn = e.javaClass.name
        val msg = e.message ?: ""
        return when {
            cn.contains("BadConfig") || cn.contains("ParseException") ||
                msg.contains("ParseException") || msg.contains("BadConfig") ->
                L("کانفیگ خراب است؛ فرمت آن معتبر نیست (نقطه پایانی یا کلید اشتباه است)",
                  "Invalid config: bad format (bad endpoint or key)")
            cn.contains("KeyFormat") || msg.contains("Invalid key") ->
                L("کلید کانفیگ معتبر نیست", "Invalid config key")
            cn.contains("UnknownHost") || msg.contains("Unable to resolve") ->
                L("نام سرور حل نشد؛ اینترنت را چک کن", "Could not resolve the server name; check your connection")
            msg.contains("VPN_NOT_AUTHORIZED") || msg.contains("not authorized") ->
                L("دسترسی VPN داده نشد", "VPN permission was not granted")
            msg.contains("TUN_CREATION_ERROR") ->
                L("ایجاد تونل VPN ممکن نشد؛ یک بار دیگر امتحان کن", "Could not create the VPN tunnel; try again")
            msg.isNotBlank() && !msg.contains("org.amnezia") && !msg.contains("Exception") && msg.length < 120 -> msg
            else -> L("وصل نشد؛ خطای نامشخص", "Could not connect; unknown error")
        }
    }

    private fun saveTunnels() {
        val arr = JSONArray()
        for (t in tunnels) {
            arr.put(JSONObject().apply {
                put("name", t.name)
                put("conf", t.conf)
            })
        }
        prefs().edit()
            .putString("wg_list", arr.toString())
            .putString("wg_active", activeName ?: "")
            .apply()
    }

    private fun parseConf(text: String): WgTunnel? {
        var section = ""
        var privateKey = ""
        val address = mutableListOf<String>()
        val dns = mutableListOf<String>()
        var mtu: Int? = null
        val peers = mutableListOf<WgPeer>()
        var curPeer: MutableMap<String, String>? = null
        fun flushPeer() {
            val m = curPeer ?: return
            val pub = m["publickey"] ?: ""
            val ep = m["endpoint"] ?: ""
            if (pub.isNotBlank() && ep.isNotBlank()) {
                peers += WgPeer(
                    publicKey = pub,
                    preSharedKey = m["presharedkey"]?.takeIf { it.isNotBlank() },
                    endpoint = ep,
                    allowedIPs = (m["allowedips"] ?: "0.0.0.0/0, ::/0")
                        .split(",").map { it.trim() }.filter { it.isNotBlank() },
                    keepAlive = m["persistentkeepalive"]?.toIntOrNull()
                )
            }
            curPeer = null
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                if (section.equals("Peer", ignoreCase = true)) flushPeer()
                section = line.removeSurrounding("[", "]").trim()
                if (section.equals("Peer", ignoreCase = true)) curPeer = mutableMapOf()
                continue
            }
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim()
            // 2.92: section/key compare is case-insensitive
            // ([interface] / privatekey= pasted from a chat
            // must parse exactly like the canonical casing).
            when (section.lowercase()) {
                "interface" -> when (key.lowercase()) {
                    "privatekey" -> privateKey = value
                    "address" -> address += value.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    "dns" -> dns += value.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    "mtu" -> mtu = value.toIntOrNull()
                }
                "peer" -> curPeer?.put(key.lowercase(), value)
            }
        }
        if (section == "Peer") flushPeer()
        if (privateKey.isBlank() || peers.isEmpty()) return null
        return WgTunnel(
            name = "",
            conf = text.trim(),
            privateKey = privateKey,
            address = address,
            dns = dns,
            mtu = mtu,
            peers = peers
        )
    }

    // 2.26 alien imports: sha256 of the trimmed
    // conf text, written by the Alien section's
    // transfer button. Loaded fresh on every
    // refreshList, like the tunnels themselves.
    private var alienWgSet: Set<String> = emptySet()
    private var alienWgBitmapCache: android.graphics.Bitmap? = null

    private fun sha256Hex(s: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder()
            for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xFF))
            sb.toString()
        } catch (_: Exception) {
            s.hashCode().toString()
        }
    }

    private fun loadAlienWgSet(): Set<String> {
        return try {
            val arr = org.json.JSONArray(
                prefs().getString("alien_tunnels", "[]") ?: "[]")
            val s = mutableSetOf<String>()
            for (i in 0 until arr.length()) s += arr.optString(i)
            s
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun alienWgBitmap(): android.graphics.Bitmap {
        alienWgBitmapCache?.let { return it }
        val bmp = try {
            val raw = android.graphics.BitmapFactory.decodeStream(assets.open("alien.png"))
            android.graphics.Bitmap.createScaledBitmap(raw, 96, 96, true)
        } catch (_: Exception) {
            android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        }
        alienWgBitmapCache = bmp
        return bmp
    }

    private fun galaxyWgBg(): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#1A237E"),
                Color.parseColor("#4A148C"),
                Color.parseColor("#006064")
            )).apply {
            cornerRadius = 18 * resources.displayMetrics.density
            setStroke((1 * resources.displayMetrics.density).toInt(), Color.parseColor("#7E57C2"))
        }
    }

    /** 2.88: the row toggle, per the mockup — the shared themed
     *  toggle (teal ON, dark grey OFF in night, light grey OFF
     *  in day). */
    private fun wgToggleBitmap(on: Boolean): android.graphics.Bitmap =
        Theme.modeToggleBitmap(this, on)

    private fun refreshList() {
        alienWgSet = loadAlienWgSet()
        val dark = Theme.isDark(this)
        listContainer.removeAllViews()
        for (rec in warpRecords) listContainer.addView(warpRow(rec))
        if (tunnels.isEmpty() && warpRecords.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = L("هنوز تونلی اضافه نشده است. با دکمه + پایین صفحه، فایل یا متن وایرگارد یا امنزیا را وارد کن.",
                         "No tunnel yet. Use the + button at the bottom to import a WireGuard or AmneziaWG file or text.")
                setTextColor(Theme.subFg(this@WireGuardActivity))
            })
            return
        }
        val up = isWgUp()
        for (t in tunnels) {
            // 1.60 — the fetch owns the "WARP (WireGuard)"
            // tunnel while it runs: the row must not
            // appear (and be tappable) until the fetch
            // decides — on success it shows as a "free
            // warp" record, on failure it is deleted.
            // The 1.59 run showed the row mid-fetch and
            // the user's tap on it cut instantly.
            if (warpFetchRunning && isWarpWgTunnel(t.name)) continue
            val isActive = t.name == activeName && up
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val d = resources.displayMetrics.density
                // 2.88: dark glass cards in night (#B33A415C, the same
                // lighter night fill as the v2ray cards), white glass
                // in day. The active row reads as "on" at a glance.
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 18 * d
                    if (dark) {
                        setColor(if (isActive) Theme.darken(Color.parseColor("#B32A3D5C")) else Theme.darken(Color.parseColor("#B33A415C")))
                        setStroke((1 * d).toInt(), if (isActive) Color.parseColor("#26A69A") else Theme.darken(Color.parseColor("#55FFFFFF")))
                    } else {
                        setColor(if (isActive) Color.parseColor("#E8F5E9") else Color.WHITE)
                        setStroke((1 * d).toInt(), if (isActive) Color.parseColor("#A5D6A7") else Color.parseColor("#E0E0E0"))
                    }
                }
                elevation = 2 * d
                setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, (14 * d).toInt()) }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = t.name; textSize = 17f
                setTextColor(if (dark) Color.WHITE else Color.parseColor("#1A1A1A"))
                if (isActive) setTypeface(null, android.graphics.Typeface.BOLD)
            })
            info.addView(TextView(this).apply {
                text = t.peers.firstOrNull()?.endpoint ?: ""
                textSize = 13f; setTextColor(Color.parseColor(if (dark) "#B0BEC5" else "#757575"))
            })
            row.addView(engineEdgeLabel(WgDirect.isAwgConf(t.conf), dark))
            row.addView(ImageView(this).apply {
                setImageBitmap(typeIconBitmap(WgDirect.isAwgConf(t.conf), dark))
                val s = (24 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    setMargins(0, 0, (4 * resources.displayMetrics.density).toInt(), 0)
                }
            })
                row.addView(View(this).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        // 2.88: in night the dot is the engine colour
                        // (WG teal / Amnezia purple, per the mockup);
                        // in day it is green when active, grey otherwise.
                        setColor(if (dark) Color.parseColor(if (WgDirect.isAwgConf(t.conf)) "#8B7CF6" else "#2BB673")
                            else if (isActive) Color.parseColor("#43A047") else Color.parseColor("#BDBDBD"))
                    }
                    val s = (12 * resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s).apply {
                        setMargins(0, 0, (8 * resources.displayMetrics.density).toInt(), 0)
                    }
                })
            if (alienWgSet.contains(sha256Hex(t.conf.trim()))) {
                // Alien import: deep galaxy card,
                // light text, alien icon after the
                // status dot (before the info).
                row.background = galaxyWgBg()
                try {
                    val edge = row.getChildAt(0)
                    if (edge is android.widget.LinearLayout && edge.childCount > 0) {
                        val lab = edge.getChildAt(0)
                        if (lab is android.widget.TextView) lab.setTextColor(Color.parseColor("#B0BEC5"))
                    }
                    info.getChildAt(0)?.let {
                        if (it is android.widget.TextView) it.setTextColor(Color.WHITE)
                    }
                    info.getChildAt(1)?.let {
                        if (it is android.widget.TextView) it.setTextColor(Color.parseColor("#CFD8DC"))
                    }
                } catch (_: Exception) {}
                row.addView(ImageView(this).apply {
                    setImageBitmap(alienWgBitmap())
                    val s = (30 * resources.displayMetrics.density).toInt()
                    layoutParams = LinearLayout.LayoutParams(s, s).apply {
                        setMargins(0, 0, (8 * resources.displayMetrics.density).toInt(), 0)
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor("#E8FFFFFF"))
                        setStroke(1, Color.parseColor("#55000000"))
                    }
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    setPadding((2 * resources.displayMetrics.density).toInt(), (2 * resources.displayMetrics.density).toInt(), (2 * resources.displayMetrics.density).toInt(), (2 * resources.displayMetrics.density).toInt())
                })
            }
            row.addView(info)
            val tog = ImageView(this).apply {
                // 2.88: mode-aware pill toggle — teal ON in both modes,
                // dark grey OFF in night, light grey OFF in day.
                setImageBitmap(wgToggleBitmap(isActive))
                val dd = resources.displayMetrics.density
                layoutParams = LinearLayout.LayoutParams((49 * dd).toInt(), (25 * dd).toInt()).apply {
                    setMargins(0, 0, (12 * dd).toInt(), 0)
                }
                setOnClickListener {
                    // 1.57 — plain-toggle double-tap guard (see
                    // WarpTapGuard.kt): same 3s window as the WARP
                    // row. The guard was only wired into the
                    // warp-records row in 1.56; this plain toggle
                    // had the same race (1.56 device log 13:16:23
                    // start→cut in the same second) and the guard
                    // never saw the tap.
                    if (isActive && WarpTapGuard.shouldIgnoreDisconnect(
                            plainConnectStartedMs, System.currentTimeMillis(), plainConnecting)) {
                        WgLog.add(L("ضربهٔ دوباره نادیده گرفته شد؛ اتصال در جریان است.",
                            "Double-tap ignored; a connect is in progress."))
                        return@setOnClickListener
                    }
                    if (isActive) disconnectWg() else connectTunnel(t)
                }
            }
            row.addView(tog)
            row.addView(Button(this).apply {
                text = "✕"; textSize = 11f
                // 2.88: refined soft-pink glass circle, exactly the
                // toggle's height (25dp) — a matching pair, per the
                // mockup.
                setTextColor(Color.parseColor(if (dark) "#E57373" else "#D32F2F"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (dark) Theme.darken(Color.parseColor("#E8FCE4EC")) else Color.parseColor("#FCE4EC"))
                    setStroke(1, Color.parseColor("#F8BBD0"))
                }
                val s = (25 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply { setMargins(4, 0, 12, 0) }
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    // first, like OpenVPN: one stray tap
                    // used to destroy the tunnel and its
                    // key with no way back.
                    android.app.AlertDialog.Builder(this@WireGuardActivity)
                        .setTitle(L("حذف تانل", "Delete tunnel"))
                        .setMessage(L("تانل «${t.name}» حذف شود؟", "Delete tunnel “${t.name}”?"))
                        .setPositiveButton(L("حذف", "Delete")) { _, _ ->
                            val wasActive = t.name == activeName
                            tunnels.remove(t)
                            if (wasActive) {
                                disconnectWg()
                                activeName = null
                                currentTunnel = null
                                currentAwgTunnel = null
                            }
                            saveTunnels(); refreshList(); refreshStatus()
                            try {
                                                            } catch (_: Exception) {}
                        }
                        .setNegativeButton(L("لغو", "Cancel"), null)
                        // 2.84: shared dialog card + readable text in night.
                        .show().also { Theme.styleDialog(it, this@WireGuardActivity) }
                }
            })
            row.setOnClickListener { showDetails(t) }
            listContainer.addView(row)
        }
    }

    // Slanted engine label on the row's edge, like
    // the v2ray protocol label: rotated so it reads
    // bottom-to-top. WireGuard green, Amnezia purple.
    private fun engineEdgeLabel(isAwg: Boolean, dark: Boolean): LinearLayout {
        val d = resources.displayMetrics.density
        val label = if (isAwg) L("امنزیا", "AmneziaWG") else L("وایرگارد", "WireGuard")
        val w = minOf(110, label.length * 8 + 20)
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((30 * d).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
            addView(TextView(this@WireGuardActivity).apply {
                text = label
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.ITALIC)
                // 2.88: night — WG teal / Amnezia purple, per the
                // mockup; day — plain grey-blue.
                setTextColor(Color.parseColor(if (dark) (if (isAwg) "#8B7CF6" else "#2BB673") else "#8A93A6"))
                gravity = Gravity.CENTER
                rotation = -90f
                layoutParams = LinearLayout.LayoutParams((w * d).toInt(), (30 * d).toInt())
            })
        }
    }

    /** Small type mark next to the vertical label:
     *  a shield for AmneziaWG, two linked rings for
     *  WireGuard — drawn in code, so no asset is
     *  needed, and the colors match the label. */
    private fun typeIconBitmap(isAwg: Boolean, dark: Boolean): android.graphics.Bitmap {
        val s = 96
        val bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 7f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            // 2.88: night — WG teal / Amnezia purple, per the mockup;
            // day — plain dark-grey line icons, never coloured.
            color = Color.parseColor(if (dark) (if (isAwg) "#8B7CF6" else "#2BB673") else "#8A8A8A")
        }
        if (isAwg) {
            val path = android.graphics.Path().apply {
                moveTo(48f, 10f)
                lineTo(80f, 24f)
                lineTo(80f, 52f)
                cubicTo(80f, 72f, 66f, 84f, 48f, 90f)
                cubicTo(30f, 84f, 16f, 72f, 16f, 52f)
                lineTo(16f, 24f)
                close()
            }
            c.drawPath(path, paint)
        } else {
            c.drawCircle(38f, 48f, 18f, paint)
            c.drawCircle(58f, 48f, 18f, paint)
        }
        return bmp
    }

    private fun showWgLogDialog() {
        val emptyText = L("هنوز گزارشی ثبت نشده است. اول یک بار وصل شو.", "No log yet. Connect once first.")
        val text = warpLogCombined().ifBlank { emptyText }
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            // 1.61 — same render-time styling as the
            // app log page: summary lines bold, raw
            // engine lines dimmer + smaller. The
            // stored text is unchanged; Copy still
            // copies the plain lines.
            this.text = if (text == emptyText) text
                else AppLog.styledLog(text.lines(), Theme.isDark(this@WireGuardActivity))
            textSize = 12f
            setTextColor(Color.BLACK)
            setPadding(32, 16, 32, 16)
        }
        scroll.addView(tv)
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(L("گزارش وایرگارد", "WireGuard log"))
            .setView(scroll)
            .setPositiveButton(L("کپی گزارش", "Copy log")) { _, _ ->
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("wg log", warpLogCombined()))
                    android.widget.Toast.makeText(this, L("گزارش کپی شد", "Log copied"), android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
            }
            .setNeutralButton(L("پاک کردن", "Clear"), null)
            .setNegativeButton(L("بستن", "Close"), null)
            // 2.84: shared dialog card + readable text in night.
            .show().also { Theme.styleDialog(it, this); Theme.applyCompactDialogSize(it, this) }
        // 1.52 — Clear used to call ONLY WgLog.clear()
        // (in-memory): the [warp] lines live in AppLog
        // (SharedPreferences) and came back on next open.
        // Now both are cleared — but ONLY the warp /
        // wireguard lines (AppLog.clear() would delete
        // every section's lines) — and the dialog refreshes
        // in place instead of dismissing.
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            try { WgLog.clear() } catch (_: Exception) {}
            try {
                AppLog.clearMatching(this) { line ->
                    line.contains("[warp]") || line.contains("wireguard")
                }
            } catch (_: Exception) {}
            tv.text = emptyText
            android.widget.Toast.makeText(this, L("گزارش پاک شد", "Log cleared"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDetails(t: WgTunnel) {
        val peer = t.peers.firstOrNull()
        val text = L("نام: ", "Name: ") + t.name + "\n" +
            L("آدرس: ", "Address: ") + t.address.joinToString(", ") + "\n" +
            L("DNS: ", "DNS: ") + (if (t.dns.isEmpty()) "-" else t.dns.joinToString(", ")) + "\n" +
            L("MTU: ", "MTU: ") + (t.mtu?.toString() ?: "-") + "\n" +
            L("کلید خصوصی: ", "Private key: ") + "••••••••\n" +
            L("سرور: ", "Endpoint: ") + (peer?.endpoint ?: "-") + "\n" +
            L("AllowedIPs: ", "AllowedIPs: ") + (peer?.allowedIPs?.joinToString(", ") ?: "-") + "\n" +
            L("Keepalive: ", "Keepalive: ") + (peer?.keepAlive?.toString() ?: "-") + "\n" +
            L("موتور: ", "Engine: ") + (if (WgDirect.isAwgConf(t.conf)) L("امنزیا", "AmneziaWG") else L("وایرگارد", "WireGuard"))
        android.app.AlertDialog.Builder(this)
            .setTitle("وایرگارد و امنزیا")
            .setMessage(text)
            .setPositiveButton(L("بستن", "Close"), null)
            // 2.84: shared dialog card + readable text in night.
            .show().also { Theme.styleDialog(it, this) }
    }

    private fun refreshStatus() {
        try { getSharedPreferences("renamer_widget", MODE_PRIVATE).edit().putBoolean("widget_state_wireguard", isWgUp()).apply() } catch (_: Exception) {}
        // 1.69 — CONNECT-PAGE SILENCE (user order
        // 2026-11-20 ~22:40): this function NO
        // LONGER writes any status text — not
        // "connecting", not "connected", not a
        // failure line, not "disconnected". The old
        // 1.46–1.48 guards above (never claim
        // "connected" before the real-traffic
        // verdict) are now subsumed: NOTHING is
        // claimed on the page at all. The card
        // rows + toggle show the state; the
        // connect's story lives ONLY in the log.
        // Status text on this page appears ONLY
        // while the WARP account is being created
        // (the fetch progress UI with the % bar
        // in startWarpFetch / tryWireGuardRung —
        // untouched). Every connect/disconnect
        // path clears the line via
        // clearConnectStatus() instead of writing
        // to it.
    }

    /** 1.69 — blank the page's status line: the
     *  connect/disconnect paths call this instead
     *  of writing any status text (see
     *  refreshStatus above). Call it on the UI
     *  thread (or inside runOnUiThread); the
     *  try/catch keeps an off-thread call a
     *  silent no-op instead of a crash. */
    private fun clearConnectStatus() {
        try { statusText.text = "" } catch (_: Exception) {}
    }

    private fun showImportMenu() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this)
        scroll.addView(col)
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("+")
            .setView(scroll)
            .setNegativeButton(L("بستن", "Close"), null)
            .create()
        fun glassBtn(label: String, onClick: () -> Unit) {
            col.addView(Button(this).apply {
                text = label; textSize = 16f
                // 2.88: dark glass in night (the old pale-blue pills on
                // the dark dialog card were unreadable); RTL rule —
                // Persian text starts from the right.
                textDirection = View.TEXT_DIRECTION_LOCALE
                gravity = Gravity.START
                val dark = Theme.isDark(this@WireGuardActivity)
                setTextColor(Color.parseColor(if (dark) "#EDEFF5" else "#333333"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 24f
                    if (dark) {
                        setColor(Theme.darken(Color.parseColor("#CC232840")))
                        setStroke(2, Theme.darken(Color.parseColor("#44FFFFFF")))
                    } else {
                        setColor(Color.parseColor("#E8F4FD"))
                        setStroke(2, Color.parseColor("#BBDEFB"))
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 8, 0, 8) }
                setPadding(24, 20, 24, 20)
                setOnClickListener { dialog.dismiss(); onClick() }
            })
        }
        glassBtn(L("وارد کردن فایل", "Import file")) { importFile() }
        glassBtn(L("وارد کردن از کلیپ‌بورد", "Import from clipboard")) { importClipboard() }
        dialog.show()
        // 2.84: shared dialog card + readable text in night.
        Theme.styleDialog(dialog, this)
    }

    private fun importFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, L("انتخاب فایل وایرگارد", "Choose a WireGuard file")), 500)
    }

    /** Percent-decode that leaves a literal '+' alone: the
     *  private key in the URI userinfo is base64, where '+'
     *  is significant (URLDecoder would turn it into a space). */
    private fun percentDecodeKeepPlus(s: String): String =
        try { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { s }

    /** 2.91 (24): convert a wireguard:// URI to the app's INI
     *  text, then send it through the EXISTING path (parseConf
     *  -> stripReserved / isAwgConf -> duplicate check -> name
     *  dialog). Returns null when malformed (no userinfo key,
     *  no host, no port, no public key) — the caller then shows
     *  the existing "not a WireGuard config" error.
     *  Mapping (every value URL-decoded):
     *  userinfo = [Interface] PrivateKey; host:port = [Peer]
     *  Endpoint; address= -> [Interface] Address; publickey=
     *  -> [Peer] PublicKey; mtu= -> [Interface] MTU;
     *  keepalive= -> [Peer] PersistentKeepalive; reserved=
     *  -> a Reserved line in the stored text (connect still
     *  uses the stripped form); AllowedIPs defaults to
     *  0.0.0.0/0, ::/0; DNS empty; PresharedKey null.
     *  The URI fragment (#...) is NEVER used as the name —
     *  the name always comes from the user in the dialog.
     *  Amnezia junk (client-only: Jc/Jmin/Jmax need not match
     *  the server, so defaults are safe):
     *  wnoisecount -> Jc (junk packet count);
     *  wpayloadsize -> Jmin = Jmax = that size (one fixed
     *  junk size — Amnezia allows a range to be a single
     *  fixed value); wnoise 1/true enables junk, 0/false
     *  turns it off even if count/size are present; with
     *  the switch on but no count/size, the documented
     *  typical values Jc=4, Jmin=50, Jmax=1000 are used.
     *  wnoisedelay has NO Amnezia [Interface] key in this
     *  core (the AWG parser throws on unknown attributes),
     *  so it is parsed and intentionally not emitted.
     *  Deliberately NOT XraySpeedtest's wireguard:// branch:
     *  that reads the key from a privateKey query param and
     *  hardcodes address/keepalive — the real link carries
     *  the key in userinfo. */
    /** 2.92: rewrite of the 2.91 converter. 2.91 failed on most real
     *  links for two base64 reasons it only half-fixed: '+' in the
     *  publickey (query params were raw-URLDecoder-decoded, so '+'
     *  became a space and Key.fromBase64 rejected the key) and '/'
     *  in the private key (java.net.URI silently mis-parses it:
     *  rawUserInfo comes back null). This version never trusts
     *  java.net.URI for the authority: it splits the URI by hand
     *  (fragment cut first — never used as the name; scheme
     *  case-insensitive; userinfo = before the LAST '@'; host:port
     *  with IPv6 [..]:port handled), and decodes EVERY value with
     *  percentDecodeKeepPlus. Query-style links (privateKey= /
     *  publicKey= in the query, no userinfo) are accepted as a
     *  fallback. Returns (conf, null) on success or (null, reason)
     *  with a SPECIFIC Persian reason — never a silent null.
     *  Mapping (every value URL-decoded):
     *  userinfo = [Interface] PrivateKey; host:port = [Peer]
     *  Endpoint; address= -> [Interface] Address; publickey=
     *  -> [Peer] PublicKey; mtu= -> [Interface] MTU;
     *  keepalive= -> [Peer] PersistentKeepalive; reserved=
     *  -> a Reserved line in the stored text (connect still
     *  uses the stripped form); AllowedIPs defaults to
     *  0.0.0.0/0, ::/0; DNS empty; PresharedKey null.
     *  The URI fragment (#...) is NEVER used as the name —
     *  the name always comes from the user in the dialog.
     *  Amnezia junk (client-only: Jc/Jmin/Jmax need not match
     *  the server, so defaults are safe):
     *  wnoisecount -> Jc (junk packet count);
     *  wpayloadsize -> Jmin = Jmax = that size (one fixed
     *  junk size — Amnezia allows a range to be a single
     *  fixed value); wnoise 1/true enables junk, 0/false
     *  turns it off even if count/size are present; with
     *  the switch on but no count/size, the documented
     *  typical values Jc=4, Jmin=50, Jmax=1000 are used.
     *  wnoisedelay has NO Amnezia [Interface] key in this
     *  core (the AWG parser throws on unknown attributes),
     *  so it is parsed and intentionally not emitted. */
    private fun wireguardUriToConf(link: String): Pair<String?, String?> {
        val noFrag = link.substringBefore("#").trim()
        val schemeEnd = noFrag.indexOf("://")
        if (schemeEnd < 0)
            return null to L("لینک وایرگارد معتبر نیست؛ با wireguard:// شروع نمی‌شود.", "Not a valid WireGuard link; it must start with wireguard://.")
        if (!noFrag.substring(0, schemeEnd).equals("wireguard", ignoreCase = true))
            return null to L("لینک وایرگارد معتبر نیست؛ با wireguard:// شروع نمی‌شود.", "Not a valid WireGuard link; it must start with wireguard://.")
        val rest = noFrag.substring(schemeEnd + 3)
        if (rest.isBlank())
            return null to L("لینک وایرگارد خالی است.", "The WireGuard link is empty.")
        val qIdx = rest.indexOf('?')
        val authority = if (qIdx >= 0) rest.substring(0, qIdx) else rest
        val query = if (qIdx >= 0) rest.substring(qIdx + 1) else ""
        val params = mutableMapOf<String, MutableList<String>>()
        for (pair in query.split("&")) {
            if (pair.isBlank()) continue
            val eq = pair.indexOf('=')
            val k = percentDecodeKeepPlus(if (eq < 0) pair else pair.substring(0, eq)).lowercase()
            val v = if (eq < 0) "" else percentDecodeKeepPlus(pair.substring(eq + 1))
            params.getOrPut(k) { mutableListOf() } += v
        }
        fun first(name: String): String? = params[name]?.firstOrNull()?.takeIf { it.isNotBlank() }
        // Private key: userinfo first (the real link form), else the
        // privateKey query param (query-style links).
        val atIdx = authority.lastIndexOf('@')
        var privateKey = if (atIdx >= 0) percentDecodeKeepPlus(authority.substring(0, atIdx)).trim() else ""
        if (privateKey.isBlank()) privateKey = first("privatekey") ?: ""
        if (privateKey.isBlank())
            return null to L("کلید خصوصی در لینک پیدا نشد.", "No private key found in the link.")
        val hostPort = if (atIdx >= 0) authority.substring(atIdx + 1) else authority
        if (hostPort.isBlank())
            return null to L("هاست در لینک پیدا نشد.", "No host found in the link.")
        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0)
                return null to L("هاست در لینک معتبر نیست.", "The host in the link is invalid.")
            host = hostPort.substring(0, close + 1)
            val after = hostPort.substring(close + 1)
            port = if (after.startsWith(":")) after.substring(1).toIntOrNull() ?: -1 else -1
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon < 0)
                return null to L("پورت در لینک نیست.", "No port in the link.")
            host = hostPort.substring(0, colon)
            port = hostPort.substring(colon + 1).toIntOrNull() ?: -1
        }
        if (host.isBlank())
            return null to L("هاست در لینک پیدا نشد.", "No host found in the link.")
        if (port <= 0)
            return null to L("پورت در لینک نیست یا معتبر نیست.", "No valid port in the link.")
        val endpoint = "$host:$port"
        val publicKey = first("publickey") ?: ""
        if (publicKey.isBlank())
            return null to L("کلید عمومی در لینک پیدا نشد.", "No public key found in the link.")
        val noiseOn = when (first("wnoise")?.lowercase()) {
            null -> params.containsKey("wnoisecount") || params.containsKey("wpayloadsize")
            "1", "true", "yes", "on" -> true
            else -> false
        }
        var jc: Int? = null
        var jmin: Int? = null
        var jmax: Int? = null
        if (noiseOn) {
            jc = first("wnoisecount")?.toIntOrNull() ?: 4
            val size = first("wpayloadsize")?.toIntOrNull()
            if (size != null) { jmin = size; jmax = size } else { jmin = 50; jmax = 1000 }
        }
        val sb = StringBuilder()
        sb.append("[Interface]\n")
        sb.append("PrivateKey = ").append(privateKey).append('\n')
        for (a in params["address"] ?: emptyList()) {
            if (a.isNotBlank()) sb.append("Address = ").append(a.trim()).append('\n')
        }
        first("mtu")?.toIntOrNull()?.let { sb.append("MTU = ").append(it).append('\n') }
        first("reserved")?.let { sb.append("Reserved = ").append(it).append('\n') }
        if (jc != null) sb.append("Jc = ").append(jc).append('\n')
        if (jmin != null) sb.append("Jmin = ").append(jmin).append('\n')
        if (jmax != null) sb.append("Jmax = ").append(jmax).append('\n')
        sb.append("[Peer]\n")
        sb.append("PublicKey = ").append(publicKey).append('\n')
        sb.append("Endpoint = ").append(endpoint).append('\n')
        sb.append("AllowedIPs = 0.0.0.0/0, ::/0\n")
        first("keepalive")?.toIntOrNull()?.let { sb.append("PersistentKeepalive = ").append(it).append('\n') }
        return sb.toString() to null
    }

    private fun importClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            // 2.92: the link may sit anywhere in the pasted text
            // (a label or a note around it) — extract the first
            // wireguard:// URI up to the next whitespace. If the
            // URI is malformed, fall through to the INI branch
            // instead of failing outright; the name is always
            // the user's choice (suggestedName null opens the
            // name dialog — never the URI fragment).
            val uriMatch = Regex("wireguard://\\S+", RegexOption.IGNORE_CASE).find(clip)
            if (uriMatch != null) {
                val (conf, err) = wireguardUriToConf(uriMatch.value)
                if (conf != null) { addTunnelFromText(conf, null); return }
                val ini = extractIni(clip)
                if (ini != null) { addTunnelFromText(ini, null); return }
                statusText.text = err ?: L("کلیپ‌بورد کانفیگ وایرگارد نیست.", "Clipboard does not contain a WireGuard config.")
                return
            }
            val ini = extractIni(clip)
            if (ini == null) {
                statusText.text = L("کلیپ‌بورد کانفیگ وایرگارد نیست.", "Clipboard does not contain a WireGuard config.")
                return
            }
            addTunnelFromText(ini, null)
        } catch (e: Exception) {
            statusText.text = L("خواندن کلیپ‌بورد ناموفق: ", "Reading clipboard failed: ") + (e.message ?: "")
        }
    }

    /** 2.92: cut a pasted INI config out of surrounding text.
     *  Keeps blank lines, comments, [section] headers and
     *  key=value lines from the first [Interface] header
     *  (case-insensitive) and stops at the first prose line
     *  after the config started, so labels/notes around a
     *  pasted config no longer break the strict AWG/Config
     *  parsers. Returns null when no [Interface] exists. */
    private fun extractIni(text: String): String? {
        val start = text.indexOf("[Interface]", ignoreCase = true)
        if (start < 0) return null
        val kept = StringBuilder()
        var seen = false
        for (raw in text.substring(start).lines()) {
            val line = raw.trim()
            if (line.isBlank()) continue
            if (line.startsWith("#") || line.startsWith(";")) { kept.append(line).append('\n'); continue }
            if (line.startsWith("[") && line.endsWith("]")) { kept.append(line).append('\n'); seen = true; continue }
            if (line.contains("=")) { kept.append(line).append('\n'); seen = true; continue }
            if (seen) break
        }
        return kept.toString().trim().takeIf { it.isNotBlank() && it.contains("[Interface]", ignoreCase = true) }
    }

    private fun addTunnelFromText(text: String, suggestedName: String?) {
        val parsed = parseConf(text)
        if (parsed == null) {
            statusText.text = L("کانفیگ وایرگارد معتبر نیست؛ PrivateKey و Peer لازم است.", "Invalid WireGuard config; PrivateKey and a Peer are required.")
            return
        }
        // Also validate with the matching parser, so a
        // bad key or endpoint is caught here, with its
        // real reason, instead of failing silently later.
        // AmneziaWG configs parse with the AWG parser
        // (the plain WireGuard parser rejects junk keys).
        try {
            val filtered = WgDirect.stripReserved(text)
            if (WgDirect.isAwgConf(filtered)) AwgConfig.parse(BufferedReader(StringReader(filtered.trim())))
            else Config.parse(BufferedReader(StringReader(filtered.trim())))
        } catch (e: Exception) {
            statusText.text = L("کانفیگ وایرگارد معتبر نیست: ", "Invalid WireGuard config: ") + humanWgError(e)
            return
        }
        val existing = tunnels.any {
            it.privateKey == parsed.privateKey &&
                it.peers.firstOrNull()?.endpoint == parsed.peers.firstOrNull()?.endpoint
        }
        if (existing) {
            statusText.text = L("این کانفیگ تکراری است.", "This config is a duplicate.")
            return
        }
        if (suggestedName != null) {
            tunnels += parsed.copy(name = uniqueName(suggestedName))
            saveTunnels(); refreshList()
            statusText.text = L("تونل وایرگارد اضافه شد.", "WireGuard tunnel added.")
            return
        }
        val input = EditText(this).apply {
            hint = L("نام تونل", "Tunnel name")
            setText(uniqueName(L("WireGuard", "WireGuard")))
            // 2.91 (26): RTL rule — Persian text starts from
            // the right; readable input text in night (set
            // explicitly, not only via styleDialog's walk).
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            val darkDlg = Theme.isDark(this@WireGuardActivity)
            setTextColor(Color.parseColor(if (darkDlg) "#EDEFF5" else "#212121"))
            setHintTextColor(Color.parseColor(if (darkDlg) "#9AA0B0" else "#9E9E9E"))
        }
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle(L("نام تونل", "Tunnel name"))
            .setView(input)
            .setPositiveButton(L("افزودن", "Add")) { _, _ ->
                val name = input.text.toString().trim().ifBlank { uniqueName("WireGuard") }
                tunnels += parsed.copy(name = uniqueName(name))
                saveTunnels(); refreshList()
                statusText.text = L("تونل وایرگارد اضافه شد.", "WireGuard tunnel added.")
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show()
        // 2.84: shared dialog card (dark glass in night) +
        // readable text in night. Applied BEFORE the neon
        // buttons below, so the neon background/text wins.
        Theme.styleDialog(dlg, this)
        // 2.91 (26): neon buttons in night — same style, text
        // and behaviour as before, only the look changes.
        if (Theme.isDark(this)) {
            for (which in listOf(android.app.AlertDialog.BUTTON_POSITIVE, android.app.AlertDialog.BUTTON_NEGATIVE)) {
                dlg.getButton(which)?.apply {
                    background = Theme.neonRect(this@WireGuardActivity)
                    setTextColor(Theme.neonTextColor(this@WireGuardActivity))
                }
            }
        }
    }

    private fun uniqueName(base: String): String {
        if (tunnels.none { it.name == base }) return base
        var i = 2
        while (tunnels.any { it.name == "$base $i" }) i++
        return "$base $i"
    }

    // --- Connect / disconnect, official GoBackend ---
    /** ADAPTIVE ENGINE (1.78), first priority:
     *  endpoint variants with real handshake proof,
     *  then the exact old connect. Runs only when
     *  permission is already granted and no other
     *  VPN is running; otherwise the old path
     *  handles the dialog / v2ray stop, and its
     *  re-post (or the next tap) reaches the engine. */
    /** 2.45: background real-connection check
     *  for a WG tunnel: a fresh handshake, not
     *  just UP state (a blackholed endpoint also
     *  shows UP). Aborts silently when the tunnel
     *  is replaced or stopped before the verdict. */
    private fun startWgVerify(name: String, handshake: () -> Long, alive: () -> Boolean, onDone: ((Boolean) -> Unit)? = null) {
        if (!ConnVerify.begin(this, "wireguard", "wireguard", name)) return
        Thread {
            var proven = false
            val startMs = System.currentTimeMillis()
            while (System.currentTimeMillis() - startMs < 15000) {
                try {
                    val hs = handshake()
                    if (hs > 0 && hs * 1000 >= System.currentTimeMillis() - 180000) { proven = true; break }
                    if (!alive()) break
                } catch (_: Exception) {}
                try { Thread.sleep(1000) } catch (_: Exception) {}
            }
            if (!alive()) return@Thread
            ConnVerify.finish(this, "wireguard", "wireguard", name,
                if (proven) "PROVEN" else "DEAD",
                if (proven) "واقعاً وصل است (دست‌تکان تازه)" else "وصل نیست — تونل بالا است ولی دست‌تکانی ندارد",
                if (proven) "really connected (fresh handshake)" else "not connected — the tunnel is up but has no handshake")
            try { onDone?.invoke(proven) } catch (_: Exception) {}
        }.start()
    }


    /** One WG variant attempt: UP, then up to 7s
     *  for a REAL handshake (getLastHandshake > 0
     *  for this tunnel object). UP state alone is
     *  not proof — a blackholed endpoint also shows
     *  UP. On failure the tunnel is taken DOWN so
     *  the next variant (or the normal connect)
     *  starts clean. */

    // 2.43: central-log flags. Attempted gates the
    // attempt line (1.29: always a direct connect now;
    // the engine is gone).
    private var appLogWgAttempted = false


    private fun connectTunnel(t: WgTunnel) {
        // 1.57 — record the plain-connect start for the
        // double-tap guard (same window as the WARP row).
        // Set before every early return (VPN-permission
        // dialog, 800ms stop-and-retry) so the guard
        // covers the whole pending connect.
        plainConnectStartedMs = System.currentTimeMillis()
        plainConnecting = true
        WgLog.add(L("شروع اتصال: ", "Connecting: ") + t.name)
        if (!appLogWgAttempted) {
            appLogWgAttempted = true
            val kindFa = if (WgDirect.isAwgConf(t.conf)) "امنزیا" else "وایرگارد"
            val kindEn = if (WgDirect.isAwgConf(t.conf)) "Amnezia" else "WireGuard"
            AppLog.append(this, "wireguard",
                "تلاش اتصال «${t.name}»؛ نوع: $kindFa؛ اتصال مستقیم.",
                "Connect attempt “${t.name}”; type: $kindEn; direct connect.")
        }
        // A hand-started tunnel is not smart connect's:
        // smart connect must never auto-off it later.
        try { getSharedPreferences("renamer_smart", MODE_PRIVATE).edit().putBoolean("smart_owns_vpn", false).apply() } catch (_: Exception) {}
        try { getSharedPreferences("renamer_widget", MODE_PRIVATE).edit().putBoolean("widget_state_wireguard", true).apply() } catch (_: Exception) {}
        try { WidgetRefresh.refreshAll(this) } catch (_: Exception) {}
        // Only one VPN at a time: if the v2ray VPN is
        // running, stop it first, then start WireGuard.
        if (VpnClientService.isRunning) {
            val stopIntent = Intent(this, VpnClientService::class.java).apply { action = "STOP" ; putExtra("stop_gen", VpnClientService.sessionGen) }
            try { startService(stopIntent) } catch (_: Exception) {}
            VpnClientService.isRunning = false
            handler.postDelayed({ connectTunnel(t) }, 800)
            return
        }
        // VPN permission, like the official app: if the
        // system dialog is needed, show it and continue
        // when it returns OK.
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingTunnel = t
            startActivityForResult(prepareIntent, 600)
            return
        }
        startWgInBackground(t)
    }

    private fun startWgInBackground(t: WgTunnel) {
        // 1.69 — silence: log only, never the page.
        clearConnectStatus()
        // ENGINE FALLBACK (1.76): the stock
        // WireGuard backend crashes on this
        // device — 1.72, 1.74 and 1.75 all threw
        // the app out, even fully isolated —
        // while the Amnezia backend connects
        // fine. So EVERY config, plain WireGuard
        // included, now goes through the Amnezia
        // engine, which accepts plain configs
        // unchanged. The slanted row label still
        // shows the config's own type.
        Thread {
            var upTunnel: AwgTunnel? = null
            try {
                val config = AwgConfig.parse(BufferedReader(StringReader(WgDirect.stripReserved(t.conf).trim())))
                val b = awgBackend()
                    ?: throw IllegalStateException(lastError ?: "awg-go unavailable")
                val tunnel = AwgSimpleTunnel(t.name)
                currentAwgTunnel = tunnel
                currentTunnel = null
                upTunnel = tunnel
                WgTunnelRegistry.awgTunnel = tunnel
                b.setState(tunnel, AwgTunnel.State.UP, config)
                runOnUiThread {
                    activeName = t.name
                    // 1.46: QuickConnect is recorded only AFTER
                    // the handshake verify proves the tunnel (in
                    // onDone below) — recording it on UP made a
                    // dead tunnel the "last method".
                    lastError = null
                    saveTunnels()
                    refreshStatus()
                    refreshList()
                    val kindFa = if (WgDirect.isAwgConf(t.conf)) "امنزیا" else "وایرگارد"
                    val kindEn = if (WgDirect.isAwgConf(t.conf)) "Amnezia" else "WireGuard"
                    appLogWgAttempted = false
                    // 2.45: handshake check for the direct tunnel.
                    // is written only AFTER this check proves
                    // a fresh handshake; a DEAD verdict logs
                    // that instead, so the log never claims
                    // a connection the tunnel did not prove.
                    val vtDir = upTunnel
                    startWgVerify(t.name,
                        { val b = awgBackend(); if (b != null && vtDir != null) b.getLastHandshake(vtDir) else 0L },
                        { vtDir != null && WgTunnelRegistry.awgTunnel === vtDir },
                        { proven ->
                            runOnUiThread {
                                if (proven) {
                                    // 1.47: for a WARP WG tunnel the
                                    // warp log carries the verdict
                                    // (traffic test) — the generic
                                    // "connected" line must not
                                    // claim it on handshake alone.
                                    if (!isWarpWgTunnel(t.name)) AppLog.append(this, "wireguard",
                                        "«${t.name}» وصل شد؛ نوع: $kindFa؛ اتصال مستقیم.",
                                        "“${t.name}” connected; type: $kindEn; direct connect.")
                                    // 1.46: only a PROVEN tunnel is the
                                    // last method / last-ok — see the
                                    // note on UP above.
                                    // 1.48: and NEVER a warp WG tunnel
                                    // on handshake alone — this verify
                                    // proves a fresh handshake only, no
                                    // real traffic; a handshake-only
                                    // warp tunnel must not become
                                    // QuickConnect's "last method" (the
                                    // WG rung's own traffic verdict
                                    // lives in tryWireGuardRung).
                                    if (!isWarpWgTunnel(t.name)) {
                                        try { QuickConnect.record(this@WireGuardActivity, "wireguard", t.name) } catch (_: Exception) {}
                                        try { prefs().edit().putLong("wg_last_ok_" + t.name, System.currentTimeMillis()).apply() } catch (_: Exception) {}
                                    }
                                                                    } else {
                                    AppLog.append(this, "wireguard",
                                        "«${t.name}»؛ وصل نیست — تونل بالا است ولی دست‌تکانی ندارد.",
                                        "“${t.name}” not connected — the tunnel is up but has no handshake.")
                                }
                            }
                        })
                }
            } catch (e: Throwable) {
                if (upTunnel != null && WgTunnelRegistry.awgTunnel === upTunnel)
                    WgTunnelRegistry.awgTunnel = null
                // This is the real reason, shown in the
                // status line so it can be reported — as a
                // human Persian sentence (2.65), never a raw
                // exception class name.
                runOnUiThread {
                    lastError = humanWgError(e)
                    if (activeName == t.name) activeName = null
                    clearConnectStatus()
                    refreshList()
                    refreshStatus()
                    appLogWgAttempted = false
                    // 1.57 — the plain connect failed, so the
                    // double-tap guard stops applying.
                    plainConnecting = false
                    AppLog.append(this, "wireguard",
                        "«${t.name}» وصل نشد: $lastError.",
                        "“${t.name}” did not connect: $lastError.")
                }
            }
        }.start()
    }

    private fun disconnectWg(fromFetch: Boolean = false) {
        // 1.57 — a disconnect ends the plain connect, so
        // the double-tap guard stops applying.
        plainConnecting = false
        appLogWgAttempted = false
        // 1.60 — a fetch tearing down its own failed
        // tunnel is not a manual cut: no "قطع دستی"
        // line for it (the 1.59 log showed one for a
        // cut the user never made).
        if (!fromFetch) {
            WgLog.add(L("قطع دستی", "Manual disconnect"))
            val wgStopName = activeName
            if (wgStopName != null) {
                AppLog.append(this, "wireguard",
                    "«$wgStopName»؛ دستی قطع شد.", "“$wgStopName”; stopped manually.")
            }
        }
        try { getSharedPreferences("renamer_smart", MODE_PRIVATE).edit().putBoolean("smart_owns_vpn", false).apply() } catch (_: Exception) {}
        try { getSharedPreferences("renamer_widget", MODE_PRIVATE).edit().putBoolean("widget_state_wireguard", false).apply() } catch (_: Exception) {}
        try { WidgetRefresh.refreshAll(this) } catch (_: Exception) {}
        val tunnel = currentTunnel
        val tunnelAwg = currentAwgTunnel
        Thread {
            try {
                if (tunnel != null) {
                    backend()?.setState(tunnel, Tunnel.State.DOWN, null)
                }
            } catch (_: Throwable) {
                // Turning off must never hang the UI:
                // even if GoBackend throws, the switch
                // and status are cleared below.
            }
            try {
                if (tunnelAwg != null) {
                    awgBackend()?.setState(tunnelAwg, AwgTunnel.State.DOWN, null)
                }
            } catch (_: Throwable) {}
            runOnUiThread {
                activeName = null
                if (tunnel != null && WgTunnelRegistry.wgTunnel === tunnel)
                    WgTunnelRegistry.wgTunnel = null
                if (tunnelAwg != null && WgTunnelRegistry.awgTunnel === tunnelAwg)
                    WgTunnelRegistry.awgTunnel = null
                currentTunnel = null
                currentAwgTunnel = null
                saveTunnels()
                refreshStatus()
                refreshList()
            }
        }.start()
        // Update the UI at once, do not wait for the
        // background thread, so the switch never looks
        // stuck on.
        activeName = null
        refreshStatus()
        refreshList()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 600) {
            val pending = pendingTunnel
            pendingTunnel = null
            if (resultCode == RESULT_OK && pending != null) {
                // Route through connectTunnel (not
                // straight to the background start)
                // so the engine gets its turn on the
                // permission-granted tap too; with
                // the engine off this is identical
                // to the old direct call.
                connectTunnel(pending)
            } else {
                // 1.69 — silence: the denial goes to
                // the log only, never to the page.
                clearConnectStatus()
                AppLog.append(this, "wireguard",
                    "دسترسی VPN تأیید نشد؛ اتصال انجام نشد.",
                    "VPN permission was not confirmed; did not connect.")
                refreshList()
            }
            return
        }
        if (requestCode == 601) {
            val pending = pendingWarpRecord
            pendingWarpRecord = null
            // 1.71 (item 9): no relay is ever carried
            // through the permission flow any more.
            if (resultCode == RESULT_OK && pending != null) {
                warpConnect(pending)
                // 1.70 — the resumed connect sets
                // warpConnecting; redraw so the button
                // turns yellow now (the permission dialog
                // kept it idle until the grant).
                if (warpConnecting) refreshList()
            } else {
                // 1.69 — silence: the denial goes to
                // the log only, never to the page.
                clearConnectStatus()
                AppLog.append(this, "warp",
                    "دسترسی VPN تأیید نشد؛ اتصال انجام نشد.",
                    "VPN permission was not confirmed; did not connect.")
                refreshList()
            }
            return
        }
        if (requestCode == 500 && resultCode == RESULT_OK) {
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
                val trimmed = text.trim()
                // 2.91 (24/25): a file holding a wireguard:// URI
                // converts the same way as the clipboard; the name
                // is always the user's choice (suggestedName null
                // opens the name dialog — never the file name or
                // the URI fragment). A plain .conf file keeps its
                // filename-based name below.
                // 2.92: same extraction as the clipboard — the
                // URI or INI may sit inside surrounding text.
                val uriMatch = Regex("wireguard://\\S+", RegexOption.IGNORE_CASE).find(text)
                if (uriMatch != null) {
                    val (conf, err) = wireguardUriToConf(uriMatch.value)
                    if (conf != null) { addTunnelFromText(conf, null); return }
                    val ini = extractIni(text)
                    if (ini != null) { addTunnelFromText(ini, null); return }
                    statusText.text = err ?: L("فایل انتخاب‌شده کانفیگ وایرگارد نیست.", "The selected file is not a WireGuard config.")
                    return
                }
                val ini = extractIni(text)
                if (ini == null) {
                    statusText.text = L("فایل انتخاب‌شده کانفیگ وایرگارد نیست.", "The selected file is not a WireGuard config.")
                    return
                }
                val base = fileName?.removeSuffix(".conf")?.removeSuffix(".CONF")
                    ?.takeIf { it.isNotBlank() }
                addTunnelFromText(ini, base)
            } catch (e: Exception) {
                statusText.text = L("خواندن فایل ناموفق: ", "Reading the file failed: ") + (e.message ?: "")
            }
        }
    }
    // ================= 1.45: free WARP (MASQUE) =================
    // The delivered WARP config is an Xray config, NOT a
    // .conf — it lives in its own pref (warp_free_configs)
    // and renders its own row type; it is never forced
    // into wg_list (parseConf would reject it). The
    // wireguard rung IS a real .conf and goes through
    // the existing WG path instead.
    private var warpRecords: MutableList<WarpFree.WarpRecord> = mutableListOf()
    private var warpFreeBtn: TextView? = null
    // 1.60 — fetch progress bar + % under the status
    // line, shown only while a free fetch runs.
    private var warpProgressRow: LinearLayout? = null
    private var warpProgressBar: android.widget.ProgressBar? = null
    private var warpProgressPct: TextView? = null
    private var warpGuidePart: LinearLayout? = null
    private var warpGuideDivider: View? = null
    private var warpGuidePulseAnim: android.animation.ValueAnimator? = null
    private var warpFetchRunning = false
    private var warpFetchCancelled = false
    private var pendingWarpRecord: WarpFree.WarpRecord? = null
    // 1.71 (item 9): the relay feature is retired —
    // pendingWarpRelay / relayCheckGen /
    // relayConnectGen / bundledDead / bundledPreferred
    // / warpPoolDialog* are deleted with it.
    private var warpHealthy: List<WarpFree.HealthyGateway> = emptyList()
    private var warpVerifyGen = 0
    // 1.46: a MASQUE connect is "connecting" from the
    // moment the service starts until the real-traffic
    // verify passes — refreshStatus must show neither
    // "connected" nor "disconnected" in that window,
    // and isAnyWarpUp must not claim "up" on the bare
    // service state (activeId is set only after the
    // verify passes, so isWarpActive already means
    // verified — the old bare-service clause is gone).
    private var warpConnecting: Boolean = false
    // 1.71 (item 9): relayConnecting is deleted with
    // the relay feature; the button's visual state is
    // resolve(isWarpActive, warpConnecting).
    // 1.56 — when the current warpConnect started
    // (System.currentTimeMillis). The WARP-row tap
    // handler ignores a disconnect tap inside the
    // first WarpTapGuard.GUARD_MS of a connect (see
    // WarpTapGuard.kt): the AWG backend reports UP in
    // under a second, flipping the row to active while
    // the real-traffic verdict is still up to 45s away
    // — a second tap in that window looked like an
    // instant self-cut ("شروع اتصال" → "قطع دستی" in
    // the same second, 1.55 device log 04:11:47) and
    // killed the verify before it could say "وصل
    // نشد". 0 when no connect has ever started.
    private var warpConnectStartedMs: Long = 0L
    // 1.57 — plain-toggle double-tap guard state: connectTunnel
    // records when a plain-tunnel connect started; the plain
    // toggle ignores a disconnect tap inside the first
    // WarpTapGuard.GUARD_MS, same as the WARP row (1.56). The
    // backend reports UP in under a second, flipping the row
    // active while the verdict is still pending — a second tap
    // in that window looked like an instant self-cut
    // ("شروع اتصال" → "قطع دستی" in the same second, 1.56
    // device log 13:16:23). 0 / false when no plain connect
    // has started.
    private var plainConnectStartedMs: Long = 0L
    private var plainConnecting: Boolean = false
    // 1.47: a WARP WireGuard record is "connected"
    // only after REAL TRAFFIC passed through it
    // (generate_204 via the tunnel) — the bare
    // interface-UP state is not a connection (a
    // blackholed endpoint also shows UP). Set only
    // by the warp WG verify thread below, cleared
    // on every warpConnect / warpDisconnect.
    @Volatile private var warpWgVerified: Boolean = false

    private fun hasFirstWarpFetched(): Boolean = try {
        prefs().getBoolean("wg_first_free_fetched", false)
    } catch (_: Exception) { false }

    private fun loadWarpRecords() {
        warpRecords = WarpFree.loadAll(this)
        warpHealthy = WarpFree.loadHealthy(this)
        WarpFree.identity = WarpIdentity.load(this, "main")
        WarpFree.goolIdentity = WarpIdentity.load(this, "gool")
    }

    // 1.47: the WG rung is active only after the
    // real-traffic verify passed (warpWgVerified) —
    // bare interface-UP is not a connection.
    private fun isWarpActive(rec: WarpFree.WarpRecord): Boolean =
        if (rec.rung == WarpFree.RUNG_WG) isWgUp() && activeName == rec.wgTunnelName && warpWgVerified
        else rec.id == WarpFree.activeId(this) &&
            VpnClientService.isRunning &&
            VpnClientService.sessionTag == "warp"

    /** 1.47: is this display name a WARP WireGuard
     *  tunnel? Its UP event must not be logged as a
     *  connection, and its status must never read
     *  "connected" before the traffic verify.
     *  1.48: the record-only predicate was
     *  STRUCTURALLY false during the fetch:
     *  tryWireGuardRung connects the tunnel under
     *  the FIXED name "WARP (WireGuard)" BEFORE
     *  the WarpRecord is saved (records are saved
     *  only after real traffic passes), so no
     *  record existed to match yet and "تونل بالا
     *  آمد: WARP (WireGuard)" printed on every
     *  fetch that reached the WG rung. The fixed
     *  name itself is the match; the record check
     *  (by wgTunnelName OR by the record's own
     *  name) covers tunnels saved by older builds
     *  or renamed later. */
    private fun isWarpWgTunnel(displayName: String): Boolean =
        displayName == "WARP (WireGuard)" ||
        warpRecords.any { it.rung == WarpFree.RUNG_WG &&
            (it.wgTunnelName == displayName || it.name == displayName) }

    // 1.46: "up" means a VERIFIED warp record — the
    // bare (isRunning && sessionTag == "warp") clause
    // is gone: it claimed "connected" from the moment
    // the service started, before any traffic passed.
    private fun isAnyWarpUp(): Boolean =
        warpRecords.any { isWarpActive(it) }

    /** Combined free box, EXACTLY like the v2ray free
     *  box: 53dp min height (1.60: 30% shorter than
     *  76dp, same content), sapphire freeBoxBackground,
     *  20dp radius, left ~2/3 free part (11sp bold,
     *  weight 2f), 1dp divider, right ~1/3 guide part
     *  (11sp bold, 2000ms pulse). While a fetch runs
     *  the free part becomes the cancel path (same
     *  view, text swap only). */
    private fun buildWarpFreeBox(root: LinearLayout) {
        val pageDark = Theme.isDark(this)
        val dBox = resources.displayMetrics.density
        warpFreeBtn = TextView(this).apply {
            text = L("دریافت کانفیگ رایگان", "Get free configs")
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.orbText(this@WireGuardActivity))
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.CENTER
            setPadding((12 * dBox).toInt(), (5 * dBox).toInt(), (12 * dBox).toInt(), (5 * dBox).toInt())
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2f)
            setOnClickListener {
                if (warpFetchRunning) warpFetchCancelled = true
                else startWarpFetch()
            }
        }
        val divider = View(this).apply {
            setBackgroundColor(if (pageDark) Theme.darken(Color.parseColor("#33507A")) else Color.parseColor("#BFD9F5"))
            layoutParams = LinearLayout.LayoutParams((1 * dBox).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        warpGuideDivider = divider
        val guidePart = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    0f, 0f, 20f * dBox, 20f * dBox,
                    20f * dBox, 20f * dBox, 0f, 0f)
                setColor(if (pageDark) Theme.darken(Color.parseColor("#14FFFFFF")) else Color.parseColor("#26FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            isClickable = true
            setOnClickListener { showWarpGuide() }
        }
        guidePart.addView(TextView(this).apply {
            text = L("راهنمای اتصال", "Connection guide")
            // 1.71 (item 6): 12.6sp bold, exactly like
            // the OpenVPN top-box guide third.
            textSize = 12.6f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(OvpnTheme.orbText(this@WireGuardActivity))
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.CENTER
            setPadding((12 * dBox).toInt(), (5 * dBox).toInt(), (12 * dBox).toInt(), (5 * dBox).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        warpGuidePart = guidePart
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = OvpnTheme.freeBoxBackground(this@WireGuardActivity)
            // 1.60 — 30% shorter (76dp → 53dp), same
            // content, just less height.
            minimumHeight = (53 * dBox).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // 1.60 — a clear gap below the fetch box,
                // before the created-config box and the
                // config cards.
                setMargins(0, (12 * dBox).toInt(), 0, (16 * dBox).toInt())
            }
            addView(warpFreeBtn)
            addView(divider)
            addView(guidePart)
        }
        root.addView(box)
    }

    /** After the first successful delivery the guide
     *  third hides forever (wg_first_free_fetched),
     *  like the v2ray box. */
    private fun renderWarpFreeBox() {
        try {
            val show = !hasFirstWarpFetched()
            warpGuidePart?.visibility = if (show) View.VISIBLE else View.GONE
            warpGuideDivider?.visibility = if (show) View.VISIBLE else View.GONE
            warpGuidePulseAnim?.cancel()
            warpGuidePulseAnim = null
            if (show) {
                val part = warpGuidePart ?: return
                warpGuidePulseAnim = android.animation.ValueAnimator.ofFloat(1f, 0.6f, 1f).apply {
                    duration = 2000
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    addUpdateListener { part.alpha = it.animatedValue as Float }
                    start()
                }
            }
        } catch (_: Exception) {}
    }

    private fun showWarpGuide() {
        val text = L(
            "۱) دکمه «دریافت کانفیگ رایگان» را بزن؛ برنامه خودش در کلودفلر ثبت‌نام می‌کند (بدون اینکه هیچ صفحه‌ای باز شود) و دروازه‌های وارپ را اسکن می‌کند.\n\n۲) اول aether تست می‌شود، اگر نشد MASQUE H3، بعد H2 روی همان دروازه‌ها، بعد GOOL و در آخر وایرگارد مستقیم؛ فقط مسیری که واقعاً ترافیک رد کند برنده می‌شود.\n\n۳) کانفیگ برنده با پرچم کشورش به لیست اضافه می‌شود؛ سوییچ آن را بزن تا وصل شوی.",
            "1) Tap “Get free configs”; the app registers with Cloudflare itself (no page ever opens) and scans the WARP gateways.\n\n2) MASQUE H3 is tried first, then H2 on the same gateways, then GOOL, then direct WireGuard; only a path that really carries traffic wins.\n\n3) The winning config joins the list with its country's flag; tap its switch to connect. Tap the country under its name to pick another country's gateway.")
        android.app.AlertDialog.Builder(this)
            .setTitle(L("راهنمای وارپ رایگان", "Free WARP guide"))
            .setMessage(text)
            .setPositiveButton(L("باشه", "OK"), null)
            .show().also { Theme.styleDialog(it, this) }
    }

    /** 1.60 — fetch progress bar: safe from any thread
     *  (the fetch reports from its worker thread). */
    private fun showWarpProgress(pct: Int) {
        runOnUiThread {
            val p = pct.coerceIn(0, 100)
            warpProgressRow?.visibility = View.VISIBLE
            warpProgressBar?.progress = p
            warpProgressPct?.text = "$p%"
        }
    }

    private fun hideWarpProgress() {
        runOnUiThread {
            warpProgressRow?.visibility = View.GONE
            warpProgressBar?.progress = 0
            warpProgressPct?.text = "0%"
        }
    }

    private fun startWarpFetch() {
        if (warpFetchRunning) return
        warpFetchRunning = true
        warpFetchCancelled = false
        warpFreeBtn?.text = L("لغو دریافت", "Cancel fetch")
        statusText.text = L("در حال دریافت وارپ رایگان…", "Fetching free WARP…")
        showWarpProgress(0)
        Thread {
            val result = try {
                WarpFree.runFetch(this,
                    { fa, en -> runOnUiThread { if (warpFetchRunning) statusText.text = L(fa, en) } },
                    { warpFetchCancelled },
                    { pct -> runOnUiThread { if (warpFetchRunning) showWarpProgress(pct) } })
            } catch (t: Throwable) {
                WarpFree.FetchResult.Failed("دریافت ناموفق بود.", "Fetch failed.")
            }
            runOnUiThread {
                warpFetchRunning = false
                warpFreeBtn?.text = L("دریافت کانفیگ رایگان", "Get free configs")
                when (result) {
                    is WarpFree.FetchResult.Success -> {
                        try { prefs().edit().putBoolean("wg_first_free_fetched", true).apply() } catch (_: Exception) {}
                        renderWarpFreeBox()
                        loadWarpRecords()
                        refreshList()
                        refreshStatus()
                        // 1.58 — one card per working
                        // protocol: say which ones landed.
                        val labels = result.records.map { it.rungLabel(this) }.distinct().joinToString("، ")
                        val labelsEn = result.records.map { it.rungLabel(this) }.distinct().joinToString(", ")
                        val n = result.records.size
                        statusText.text = if (n == 1)
                            L("کانفیگ وارپ رایگان اضافه شد: $labels.", "Free WARP config added: $labelsEn.")
                        else
                            L("$n کانفیگ وارپ رایگان اضافه شد: $labels.", "$n free WARP configs added: $labelsEn.")
                        // 1.60 — let the 100% sit a beat
                        // before the bar hides, so the
                        // finished state is actually seen.
                        handler.postDelayed({ hideWarpProgress() }, 800)
                    }
                    is WarpFree.FetchResult.NeedWireGuard -> tryWireGuardRung(result.identity)
                    is WarpFree.FetchResult.Failed -> {
                        hideWarpProgress()
                        statusText.text = L(result.fa, result.en)
                        refreshList()
                    }
                }
            }
        }.start()
    }

    /** Ladder rung 4: the direct WireGuard-UDP
     *  config. It is a REAL .conf, saved through
     *  the existing WG path and connected by the
     *  Amnezia engine; the rung wins only on a
     *  fresh handshake, never on UP alone. */
    /** 1.46: direct generate_204 with NO proxy. While
     *  the WG tunnel is the active VPN, the app's own
     *  traffic goes through it (the WG backend only
     *  excludes apps listed in the conf's
     *  ExcludedApplications — ours lists none), so a
     *  200/204 here is real traffic through the tunnel.
     *  Returns the HTTP code, or -1. */
    private fun directGenerate204(timeoutMs: Int): Int {
        return try {
            val conn = java.net.URL(XraySpeedtest.TEST_URL).openConnection()
                as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "InternetAzad2026")
            val code = conn.responseCode
            try { conn.disconnect() } catch (_: Exception) {}
            code
        } catch (_: Exception) { -1 }
    }

    private fun tryWireGuardRung(idn: WarpIdentity.Identity) {
        statusText.text = L("در حال تست وایرگارد مستقیم…", "Testing direct WireGuard…")
        // 1.60 — the WireGuard rung owns the 90–98%
        // slice of the fetch bar (see FetchProgress).
        showWarpProgress(WarpFree.FetchProgress.percent("wireguard-udp", 0f))
        val conf = WarpFree.buildWgConf(idn)
        val name = "WARP (WireGuard)"
        val parsed = parseConf(conf)
        if (parsed == null) {
            statusText.text = L("ساخت کانفیگ وایرگارد ناموفق بود.", "Building the WireGuard config failed.")
            AppLog.append(this, "warp",
                "ساخت کانفیگ وایرگارد ناموفق بود.",
                "Building the WireGuard config failed.")
            return
        }
        if (tunnels.none { it.name == name }) {
            tunnels += parsed.copy(name = name)
            saveTunnels()
        }
        val t = tunnels.firstOrNull { it.name == name } ?: return
        // 1.46: the handshake must come from THIS
        // connect — a handshake up to 3 minutes old
        // (the old window) could be a stale one.
        val connectMs = System.currentTimeMillis()
        connectTunnel(t)
        val gen = ++warpVerifyGen
        Thread {
            var proven = false
            val startMs = System.currentTimeMillis()
            while (System.currentTimeMillis() - startMs < 15000 && gen == warpVerifyGen) {
                try {
                    val b = awgBackend()
                    val awg = currentAwgTunnel
                    if (awg != null && b != null) {
                        val hs = b.getLastHandshake(awg)
                        if (hs > 0 && hs * 1000 >= connectMs - 2000) {
                            proven = true
                            break
                        }
                    }
                    if (!isWgUp()) break
                } catch (_: Exception) {}
                try { Thread.sleep(1000) } catch (_: Exception) {}
            }
            // 1.46: a handshake is NOT a traffic probe.
            // 1.45 saved the record on handshake alone —
            // that is how a "created but does not connect"
            // config was born on the second network. Only
            // a real generate_204 through the tunnel wins
            // the rung. (Direct, no proxy: the WG
            // backend routes the app's own traffic through
            // the tunnel — its ExcludedApplications list
            // is empty for our conf, verified in the
            // shipped awg jar.)
            var trafficOk = false
            if (proven && gen == warpVerifyGen) {
                showWarpProgress(95)
                val code = directGenerate204(10000)
                trafficOk = code == 200 || code == 204
                if (!trafficOk) {
                    AppLog.append(this, "warp",
                        "وایرگارد مستقیم: دست‌تکان تازه بود ولی ترافیک واقعی رد نشد (کد $code).",
                        "Direct WireGuard: fresh handshake but no real traffic passed (code $code).")
                }
            }
            val ok = proven && trafficOk && gen == warpVerifyGen
            runOnUiThread {
                if (ok) {
                    // 1.60 — the card name is the literal
                    // "free warp" (dedup "free warp 2"),
                    // like every other delivered card; the
                    // TUNNEL keeps its "WARP (WireGuard)"
                    // name (wgTunnelName below).
                    val recName = WarpFree.nextFreeWarpName(
                        WarpFree.loadAll(this).map { it.name })
                    val rec = WarpFree.WarpRecord(
                        id = "warp-${System.currentTimeMillis()}",
                        name = recName, rung = WarpFree.RUNG_WG, alpn = "",
                        gatewayIp = "engage.cloudflareclient.com", gatewayPort = 2408,
                        countryCode = "", colo = "",
                        serverSpkiB64 = "", serverSpkiSha256 = "",
                        pingMs = -1L, exitCountry = "", exitColo = "",
                        createdAt = System.currentTimeMillis(),
                        wgTunnelName = name)
                    val all = WarpFree.loadAll(this).toMutableList()
                    all.removeAll { it.rung == WarpFree.RUNG_WG }
                    all.add(0, rec)
                    WarpFree.saveAll(this, all)
                    WarpFree.saveLadderWin(this, WarpFree.RUNG_WG)
                    try { prefs().edit().putBoolean("wg_first_free_fetched", true).apply() } catch (_: Exception) {}
                    renderWarpFreeBox()
                    loadWarpRecords()
                    refreshList()
                    refreshStatus()
                    AppLog.append(this, "warp",
                        "[✓] برندهٔ نردبان: وایرگارد مستقیم (دست‌تکان تازه + ترافیک واقعی)؛ کانفیگ «$recName» ذخیره شد.",
                        "[✓] Ladder winner: direct WireGuard (fresh handshake + real traffic); config “$recName” saved.")
                    statusText.text = L("کانفیگ وارپ رایگان اضافه شد.", "Free WARP config added.")
                    showWarpProgress(100)
                    handler.postDelayed({ hideWarpProgress() }, 800)
                } else {
                    // 1.60 — the fetch tears down its OWN
                    // failed tunnel: not a manual cut, so
                    // it must not log "قطع دستی".
                    try { disconnectWg(fromFetch = true) } catch (_: Exception) {}
                    // 1.57 — the dead card must not stay: the rung
                    // added (or reused) the "WARP (WireGuard)"
                    // tunnel before the verify, and the verify
                    // failed — a config that never passed real
                    // traffic must not stay on the page as a
                    // tappable card (1.56 device log 13:38:
                    // "کانفیگ اضافه میشه، وصل میشه، کار نمیکنه").
                    // Only when no WG-rung record is saved (a
                    // previously verified WARP config keeps its
                    // tunnel).
                    if (shouldDeleteDeadWarpTunnel(
                            WarpFree.loadAll(this).count { it.rung == WarpFree.RUNG_WG })) {
                        tunnels.removeAll { it.name == name }
                        saveTunnels()
                        AppLog.append(this, "warp",
                            "تونل «$name» حذف شد؛ ترافیک واقعی رد نشد.",
                            "Tunnel “$name” removed; no real traffic passed.")
                    }
                    AppLog.append(this, "warp",
                        "وایرگارد مستقیم هم ترافیک واقعی را رد نکرد؛ هیچ کانفیگی اضافه نشد.",
                        "Direct WireGuard did not pass real traffic either; no config was added.")
                    statusText.text = L("هیچ مسیری ترافیک واقعی را رد نکرد؛ دوباره امتحان کن.", "No path passed real traffic; try again.")
                    // 1.60 — reload from disk, then rebuild
                    // the list at once: the deleted tunnel
                    // must not stay on screen as a stale,
                    // tappable row (1.59: the user tapped
                    // it and got an instant cut).
                    loadTunnels()
                    hideWarpProgress()
                    refreshList()
                }
            }
        }.start()
    }

    /** One delivered WARP row: pale sapphire
     *  freeRowBg (like the v2ray free rows), 24dp
     *  flag circle, the name ONLY ("free warp"), a
     *  country-picker line with no country in it, the
     *  same 49x25dp pill toggle and the same 25dp
     *  delete. NO subtitle line: no gateway country,
     *  no protocol type, no exit country, no ping on
     *  the card — all of that lives ONLY in the tap
     *  detail dialog (1.60). NO raw gateway IP
     *  anywhere on the row. */
    private fun warpRow(rec: WarpFree.WarpRecord): LinearLayout {
        val dark = Theme.isDark(this)
        val d = resources.displayMetrics.density
        val isActive = isWarpActive(rec)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 18 * d
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                colors = OvpnTheme.sapphireColors(this@WireGuardActivity, pale = true)
                setStroke((1 * d).toInt(), if (isActive) Color.parseColor("#2E9E5B") else if (dark) Theme.darken(Color.parseColor("#55FFFFFF")) else Color.parseColor("#E0E0E0"))
            }
            elevation = 2 * d
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, (14 * d).toInt()) }
        }
        row.addView(TextView(this).apply {
            text = if (rec.countryCode.length == 2) GeoFlag.flagEmoji(rec.countryCode) else "🌐"
            textSize = 13f
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (dark) Theme.darken(Color.parseColor("#CC232840")) else Color.parseColor("#E8F4FD"))
                setStroke((1 * d).toInt(), if (dark) Theme.darken(Color.parseColor("#44FFFFFF")) else Color.parseColor("#BBDEFB"))
            }
            val s = (24 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                setMargins(0, 0, (10 * d).toInt(), 0)
            }
        })
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(this).apply {
            text = rec.name; textSize = 17f
            setTextColor(if (dark) Color.WHITE else Color.parseColor("#1A1A1A"))
            if (isActive) setTypeface(null, android.graphics.Typeface.BOLD)
        })
        // 1.71 (item 9): the card shows ONLY the name
        // («free warp») and the connect button. The
        // selected-exit box («خروجی: …» + «تغییر ▾»)
        // and the country picker are removed: the
        // connect button connects plain free warp
        // (direct MASQUE, exit as Cloudflare gives).
        row.addView(info)
        row.addView(WarpConnectToggleView(this).apply {
            // 1.70 — the connect button's visual state:
            // CONNECTED only on the verified verdict
            // (isWarpActive is already verify-gated,
            // 1.47 — bare interface-UP never counts),
            // CONNECTING while a plain connect
            // (warpConnecting) is in flight, IDLE
            // otherwise. The view draws the pill itself
            // (same 49x25dp shape as the old toggle
            // bitmap): yellow track + a knob sliding
            // left→right while CONNECTING, solid green
            // with the knob parked right when CONNECTED.
            // No status text is written here — the
            // 1.69 connect-page silence stands.
            state = WarpConnectButtonState.resolve(
                isActive, warpConnecting)
            contentDescription = when (state) {
                WarpConnectVisualState.CONNECTED -> L("متصل", "Connected")
                WarpConnectVisualState.CONNECTING -> L("در حال اتصال", "Connecting")
                else -> L("اتصال", "Connect")
            }
            layoutParams = LinearLayout.LayoutParams((49 * d).toInt(), (25 * d).toInt()).apply {
                setMargins(0, 0, (12 * d).toInt(), 0)
            }
            setOnClickListener {
                // 1.70 — a second tap while a connect is
                // in flight is ignored: warpConnect
                // already no-ops on warpConnecting, and
                // this check is the single place that
                // covers the phase, so no second connect
                // ever starts.
                if (!isActive && WarpConnectButtonState.tapIsIgnoredWhileConnecting(
                        WarpConnectButtonState.resolve(
                            isActive, warpConnecting))) {
                    WgLog.add(L("ضربهٔ دوباره نادیده گرفته شد؛ اتصال در جریان است.",
                        "Double-tap ignored; a connect is in progress."))
                    return@setOnClickListener
                }
                // 1.56 — double-tap guard (see
                // WarpTapGuard.kt): a disconnect tap
                // inside the first 3s of a connect is
                // ignored — the row flips to active the
                // moment the backend says UP, long
                // before the real-traffic verdict, and
                // the tap would kill the verify and
                // look like an instant self-cut.
                if (isActive && WarpTapGuard.shouldIgnoreDisconnect(
                        warpConnectStartedMs, System.currentTimeMillis(), warpConnecting)) {
                    WgLog.add(L("ضربهٔ دوباره نادیده گرفته شد؛ اتصال در جریان است.",
                        "Double-tap ignored; a connect is in progress."))
                    return@setOnClickListener
                }
                if (isActive) warpDisconnect(rec) else {
                    // 1.71 (item 9): plain free warp only —
                    // direct MASQUE, no relay, no country
                    // selection (connectSelectedWarp and the
                    // relay connect are deleted).
                    warpConnect(rec)
                    // 1.70 — redraw at once: warpConnect
                    // sets its connecting flag
                    // synchronously, but never rebuilds the
                    // list on start (only on verdict), so
                    // without this the button would sit grey
                    // until the verdict lands. A connect
                    // that early-returns (missing tunnel)
                    // sets no flag, so this refresh
                    // correctly leaves the button idle.
                    if (warpConnecting) refreshList()
                }
            }
        })
        row.addView(Button(this).apply {
            text = "✕"; textSize = 11f
            setTextColor(Color.parseColor(if (dark) "#E57373" else "#D32F2F"))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (dark) Theme.darken(Color.parseColor("#E8FCE4EC")) else Color.parseColor("#FCE4EC"))
                setStroke(1, Color.parseColor("#F8BBD0"))
            }
            val s = (25 * d).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s).apply { setMargins(4, 0, 12, 0) }
            setPadding(0, 0, 0, 0)
            setOnClickListener {
                android.app.AlertDialog.Builder(this@WireGuardActivity)
                    .setTitle(L("حذف کانفیگ وارپ", "Delete WARP config"))
                    .setMessage(L("کانفیگ «${rec.name}» حذف شود؟", "Delete config “${rec.name}”?"))
                    .setPositiveButton(L("حذف", "Delete")) { _, _ ->
                        if (isWarpActive(rec)) warpDisconnect(rec)
                        if (rec.rung == WarpFree.RUNG_WG) {
                            tunnels.removeAll { it.name == rec.wgTunnelName }
                            saveTunnels()
                        }
                        WarpFree.deleteRecord(this@WireGuardActivity, rec.id)
                        loadWarpRecords()
                        refreshList()
                        refreshStatus()
                    }
                    .setNegativeButton(L("لغو", "Cancel"), null)
                    .show().also { Theme.styleDialog(it, this@WireGuardActivity) }
            }
        })
        row.setOnClickListener { showWarpDetails(rec) }
        return row
    }

    private fun showWarpDetails(rec: WarpFree.WarpRecord) {
        val cc = if (rec.countryCode.isNotBlank())
            WarpFree.countryName(this, rec.countryCode) else L("نامشخص", "Unknown")
        val exit = if (rec.exitCountry.isNotBlank())
            WarpFree.countryName(this, rec.exitCountry) +
                if (rec.exitColo.isNotBlank()) " (${rec.exitColo})" else ""
        else "-"
        // 1.60 — the card shows only "free warp"; the
        // details live ONLY here (type, gateway
        // country, ping, exit country, fingerprint).
        // The fingerprint row is hidden when the value
        // is unavailable, instead of showing "-".
        val fp = rec.serverSpkiSha256.takeIf { it.length >= 8 }?.substring(0, 8)
        val lines = mutableListOf(
            L("نام: ", "Name: ") + rec.name,
            L("نوع: ", "Type: ") + rec.rungLabel(this),
            L("کشور دروازه: ", "Gateway country: ") + cc +
                (if (rec.colo.isNotBlank()) " (${rec.colo})" else ""),
            L("پینگ: ", "Ping: ") + (if (rec.pingMs >= 0) "${rec.pingMs}ms" else "—"),
            L("کشور خروج: ", "Exit country: ") + exit
        )
        if (fp != null) lines += L("اثر انگشت سرور: ", "Server fingerprint: ") + fp
        val text = lines.joinToString("\n")
        android.app.AlertDialog.Builder(this)
            .setTitle(L("وارپ رایگان", "Free WARP"))
            .setMessage(text)
            // 1.52 — copy the config: the full .conf for the
            // wireguard rung (built from the registered
            // identity, the same builder the WG rung connects
            // with), the record link for GOOL.
            // 1.54 — MASQUE rungs copy a REAL masque:// link
            // (gateway + enrolled keys + tunnel addresses —
            // the app's own parser round-trips it into the
            // exact masque outbound). The old "WARP::<id>"
            // was an internal pointer, not a config.
            .setNeutralButton(L("کپی کانفیگ", "Copy config")) { _, _ ->
                try {
                    val toCopy: String? = if (rec.rung == WarpFree.RUNG_WG) {
                        val idn = WarpFree.identity
                            ?: WarpIdentity.load(this, "main")
                        idn?.let { WarpFree.buildWgConf(it) }
                    } else if (rec.rung == WarpFree.RUNG_GOOL) {
                        rec.link().takeIf { it.isNotBlank() }
                    } else {
                        WarpFree.buildMasqueLink(this, rec)
                    }
                    if (toCopy.isNullOrBlank()) {
                        android.widget.Toast.makeText(this,
                            L("کانفیگی برای کپی نیست.", "Nothing to copy."),
                            android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("warp config", toCopy))
                        android.widget.Toast.makeText(this, L("کانفیگ کپی شد", "Config copied"), android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {}
            }
            .setPositiveButton(L("بستن", "Close"), null)
            .show().also { Theme.styleDialog(it, this) }
    }

    // 1.71 (item 9): plain «free warp» only — direct
    // MASQUE (the 1.61 behaviour). The relay
    // parameters are gone with the relay feature.
    private fun warpConnect(
        rec: WarpFree.WarpRecord
    ) {
        // 1.54: a connect already in progress must never
        // start a second, overlapping session tunnel (the
        // 02:05–02:06 device log showed 4 "تلاش اتصال"
        // lines for one connect — a second tap cancels
        // the first attempt's tunnel mid-dial via
        // stopSessionTunnel). The row toggle is disabled
        // by warpConnecting until this attempt finishes.
        if (warpConnecting) return
        warpConnectStartedMs = System.currentTimeMillis()
        if (rec.rung == WarpFree.RUNG_WG) {
            val t = tunnels.firstOrNull { it.name == rec.wgTunnelName }
            if (t == null) {
                // 1.69 — silence: log only, never the page.
                clearConnectStatus()
                AppLog.append(this, "warp",
                    "«${rec.name}»؛ وصل نیست — تونل وایرگارد این کانفیگ پیدا نشد؛ دوباره دریافت را بزن.",
                    "“${rec.name}” not connected — this config's WireGuard tunnel is gone; fetch again.")
                return
            }
            // 1.47: the WG rung goes through the SAME
            // real-traffic gate as MASQUE/GOOL. 1.46
            // connected the tunnel and returned — the
            // row and the status line then claimed
            // "connected" on bare interface-UP while
            // no traffic passed. Until the verify
            // below passes, nothing claims connected.
            warpWgVerified = false
            warpConnecting = true
            AppLog.append(this, "warp",
                "تلاش اتصال «${rec.name}»؛ ${rec.rungLabel(this)}.",
                "Connect attempt “${rec.name}”; ${rec.rungLabel(this)}.")
            val alreadyUp = isWgUp() && activeName == t.name
            val connectMs = System.currentTimeMillis()
            if (!alreadyUp) connectTunnel(t)
            val gen = ++warpVerifyGen
            Thread {
                var up = alreadyUp
                var handshakeOk = alreadyUp
                var trafficOk = false
                var code = -1
                if (alreadyUp) {
                    // Already up (e.g. from the fetch):
                    // no fresh handshake to wait for —
                    // traffic is the verdict.
                    code = directGenerate204(10000)
                    trafficOk = code == 200 || code == 204
                } else {
                    // Wait for the tunnel to come up —
                    // covers connectTunnel's 800ms
                    // stop-and-retry and the VPN
                    // permission dialog (20s).
                    val upStart = System.currentTimeMillis()
                    while (!up && gen == warpVerifyGen &&
                        System.currentTimeMillis() - upStart < 20000) {
                        try { Thread.sleep(500) } catch (_: Exception) {}
                        up = isWgUp() && activeName == t.name
                    }
                    if (up && gen == warpVerifyGen) {
                        // Fresh handshake (same 15s wait
                        // as the fetch rung).
                        val hsStart = System.currentTimeMillis()
                        while (System.currentTimeMillis() - hsStart < 15000 && gen == warpVerifyGen) {
                            try {
                                val b = awgBackend()
                                val awg = currentAwgTunnel
                                if (awg != null && b != null) {
                                    val hs = b.getLastHandshake(awg)
                                    if (hs > 0 && hs * 1000 >= connectMs - 2000) {
                                        handshakeOk = true
                                        break
                                    }
                                }
                                if (!isWgUp()) break
                            } catch (_: Exception) {}
                            try { Thread.sleep(1000) } catch (_: Exception) {}
                        }
                        if (handshakeOk && gen == warpVerifyGen) {
                            code = directGenerate204(10000)
                            trafficOk = code == 200 || code == 204
                        }
                    }
                }
                val ok = up && handshakeOk && trafficOk && gen == warpVerifyGen
                if (ok) {
                    warpWgVerified = true
                    WarpFree.setActiveId(this, rec.id)
                    // 1.74 (C): the WG rung's real-traffic
                    // verdict passed — free warp is the card's
                    // last connection now, with the record's
                    // card name ("free warp"). The plain-WG
                    // verify above deliberately never records
                    // a warp tunnel (1.48), and this branch
                    // recorded nothing at all, so the card
                    // never saw a WG-rung warp connect.
                    try { QuickConnect.record(this, QuickConnect.M_WARP, rec.name) } catch (_: Exception) {}
                    warpConnecting = false
                    AppLog.append(this, "warp",
                        "[✓] «${rec.name}» وصل شد؛ ترافیک واقعی رد شد.",
                        "[✓] “${rec.name}” connected; real traffic passed.")
                    runOnUiThread {
                        loadWarpRecords()
                        refreshList()
                        refreshStatus()
                    }
                } else if (gen == warpVerifyGen) {
                    val reasonFa = if (!up) "تونل بالا نیامد"
                        else if (!handshakeOk) "دست‌تکانی تازه‌ای نبود"
                        else "تونل بالا است ولی ترافیک واقعی رد نمی‌شود (کد $code)"
                    val reasonEn = if (!up) "the tunnel never came up"
                        else if (!handshakeOk) "no fresh handshake"
                        else "the tunnel is up but no real traffic passes (code $code)"
                    AppLog.append(this, "warp",
                        "«${rec.name}»؛ وصل نیست — $reasonFa؛ تونلِ مرده متوقف شد.",
                        "“${rec.name}” not connected — $reasonEn; the dead tunnel was stopped.")
                    try { disconnectWg() } catch (_: Exception) {}
                    WarpFree.setActiveId(this, "")
                    warpWgVerified = false
                    warpConnecting = false
                    runOnUiThread {
                        clearConnectStatus()
                        refreshList()
                        refreshStatus()
                    }
                }
            }.start()
            return
        }
        // Only one VPN at a time: a running WG tunnel
        // or a different Xray session stops first,
        // then this call re-runs (the same 800ms
        // pattern as connectTunnel).
        if (isWgUp()) {
            disconnectWg()
            handler.postDelayed({
                warpConnect(rec)
                // 1.70 — the re-run sets warpConnecting;
                // redraw so the button turns yellow now
                // instead of sitting grey until the verdict.
                if (warpConnecting) refreshList()
            }, 800)
            return
        }
        if (VpnClientService.isRunning &&
            (VpnClientService.sessionTag != "warp" || WarpFree.activeId(this) != rec.id)) {
            val stopIntent = Intent(this, VpnClientService::class.java).apply {
                action = "STOP"
                putExtra("stop_gen", VpnClientService.sessionGen)
            }
            try { startService(stopIntent) } catch (_: Exception) {}
            VpnClientService.isRunning = false
            handler.postDelayed({
                warpConnect(rec)
                // 1.70 — see the isWgUp() retry above.
                if (warpConnecting) refreshList()
            }, 800)
            return
        }
        try { getSharedPreferences("renamer_smart", MODE_PRIVATE).edit().putBoolean("smart_owns_vpn", false).apply() } catch (_: Exception) {}
        AppLog.append(this, "warp",
            "تلاش اتصال «${rec.name}»؛ ${rec.rungLabel(this)}.",
            "Connect attempt “${rec.name}”; ${rec.rungLabel(this)}.")
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingWarpRecord = rec
            startActivityForResult(prepareIntent, 601)
            return
        }
        startWarpService(rec)
    }

    // 1.71 (item 9): plain «free warp» only — the TUN
    // config is the record's own link (useRec.link());
    // the CHAIN::relay link is gone with the relay
    // feature.
    private fun startWarpService(
        rec: WarpFree.WarpRecord
    ) {
        // 1.69 — silence: log only, never the page.
        clearConnectStatus()
        warpConnecting = true
        Thread {
            // 1.46: NO fresh-SPKI refresh any more — the
            // leaf SPKI is not a pin (see WarpFree), so
            // re-fetching it on every connect only slowed
            // the connect down by up to 6s.
            var useRec = rec
            // 1.50: an aether record dials through the engine, not
            // Xray. Start the session tunnel FIRST (it publishes
            // SOCKS on 127.0.0.1:1819), then build the TUN→SOCKS
            // Xray config. If the tunnel does not come up, fail
            // here — never start a TUN with nowhere to send it.
            // 1.54: the session connect logs its full timeline
            // (carrier, gateway, ms, VPN state) into the [warp]
            // log, and the failure carries the ACCUMULATED
            // attempts, not only the last leg's error.
            // 1.58 — AETHER and GOOL records both dial
            // through the CLI session tunnel (GOOL via
            // --protocol gool); the winning transport
            // goes first — an H2 winner never waits on
            // H3. Xray/WG records build through the core.
            val isSessionRung = useRec.rung == WarpFree.RUNG_AETHER || useRec.rung == WarpFree.RUNG_GOOL
            val aetherBuilt: String? = if (isSessionRung) {
                val isGool = useRec.rung == WarpFree.RUNG_GOOL
                // 1.58 (Part B) — WARM ATTACH: if the fetch
                // left this record's validated CLI session
                // warm, attach to it with only a ~1–2s
                // health probe (real generate_204 through
                // the warm SOCKS) instead of a fresh
                // handshake+validation. ANY failure here
                // falls through to the fresh connect below
                // — a warm session is a shortcut, never a
                // commitment. (The stale warm process is
                // killed by startSessionTunnel's own stop,
                // so nothing is left running.) The
                // post-TUN verify below still runs: the
                // health probe proves the SOCKS, only the
                // TUN verify proves the TUN path the user
                // actually uses.
                var warmPort = -1
                val warm = AetherBridge.takeWarmSession(useRec.gatewayIp, useRec.alpn, useRec.rung)
                if (warm != null) {
                    val warmCode = try {
                        WarpIdentity.generate204ThroughSocks(warm.port, 2000)
                    } catch (_: Exception) { -1 }
                    if (warmCode == 200 || warmCode == 204) {
                        warmPort = warm.port
                        AppLog.append(this, "warp",
                            "اتصال گرم: جلسهٔ «${useRec.name}» روی ${warm.gatewayIp} هنوز زنده است (کد $warmCode)؛ بدون دست‌تکون تازه وصل می‌شوم.",
                            "Warm attach: the “${useRec.name}” session on ${warm.gatewayIp} is still alive (code $warmCode); connecting without a fresh handshake.")
                    } else {
                        AppLog.append(this, "warp",
                            "جلسهٔ گرم «${useRec.name}» جواب نداد (کد $warmCode)؛ اتصال تازه.",
                            "Warm session for “${useRec.name}” did not answer (code $warmCode); fresh connect.")
                    }
                }
                if (warmPort <= 0) {
                    // 1.59 (PART 3) — CONNECT-TIME AUTO IP
                    // REPLACEMENT: a config fetched on one
                    // network (rec.buildNetwork) that fails
                    // on the CURRENT network is NOT cut —
                    // its gateway IP is swapped IN PLACE on
                    // the same config to the next clean IP
                    // for the current network (the
                    // per-network clean list first, then a
                    // quick TCP scan) and retried within
                    // this same connect attempt (at most
                    // MAX_WARP_REPLACEMENTS swaps, one
                    // session leg each — the current
                    // network's winning transport, so a
                    // swap costs at most one 20s/40s leg).
                    // Only when every replacement fails is
                    // the failure reported. An identity
                    // failure is never replaced (the IP is
                    // not the problem — the registration
                    // is). A replacement that works is
                    // saved back into the record (its IP
                    // updated to the working one for the
                    // current network).
                    val netKey = NetworkProfile.currentKey(this)
                    if (rec.buildNetwork.isNotBlank() && rec.buildNetwork != netKey) {
                        AppLog.append(this, "warp",
                            "«${useRec.name}» با شبکهٔ «${rec.buildNetwork}» ساخته شده، الان روی «$netKey» هستی؛ اگر وصل نشد، آی‌پی را همان‌جا عوض می‌کنم.",
                            "“${useRec.name}” was built on network “${rec.buildNetwork}”, now on “$netKey”; if it fails, its IP is swapped in place.")
                    }
                    val triedIps = mutableSetOf(useRec.gatewayIp)
                    var replacements = 0
                    var sessionOk = false
                    while (!sessionOk) {
                        val carrier = if (replacements == 0) useRec.alpn
                            else NetworkProfile.winnerCarrier(this, netKey).ifBlank { useRec.alpn }
                        val ok = AetherBridge.startSessionTunnel(this, useRec.gatewayIp, { line ->
                            AppLog.append(this, "warp", line, line)
                        }, preferCarrier = carrier,
                            rung = useRec.rung,
                            goolPeer = if (isGool) WarpFree.goolInnerPeer(this) else null,
                            maxAttempts = if (replacements == 0) Int.MAX_VALUE else 1)
                        if (ok) { sessionOk = true; break }
                        // Identity failure: the IP is not
                        // the problem — never replace.
                        if (AetherBridge.lastIdentityError != null) break
                        if (replacements >= WarpFree.MAX_WARP_REPLACEMENTS) break
                        val next = NetworkProfile.nextWarpCleanIp(this, netKey, triedIps)
                            ?: SharedScanner.quickScanForConnect(this, netKey, triedIps).firstOrNull()
                        if (next == null) break
                        triedIps += next
                        replacements++
                        AppLog.append(this, "warp",
                            "«${useRec.name}» روی ${useRec.gatewayIp} وصل نشد؛ آی‌پی را همان‌جا به $next عوض می‌کنم (جایگزینی $replacements از ${WarpFree.MAX_WARP_REPLACEMENTS}).",
                            "“${useRec.name}” failed on ${useRec.gatewayIp}; swapping its IP in place to $next (replacement $replacements of ${WarpFree.MAX_WARP_REPLACEMENTS}).")
                        useRec = useRec.copy(gatewayIp = next)
                    }
                    if (!sessionOk) {
                        warpConnecting = false
                        val why = AetherBridge.lastSessionError
                            ?: AetherBridge.lastTunnelError ?: AetherBridge.lastIdentityError
                            ?: "تونل aether بالا نیامد"
                        runOnUiThread {
                            clearConnectStatus()
                            AppLog.append(this, "warp",
                                "«${useRec.name}» وصل نشد: $why.",
                                "“${useRec.name}” did not connect: $why.")
                            refreshList()
                        }
                        return@Thread
                    }
                    // 1.55: the session loop may have hunted to
                    // a different gateway than the record's (the
                    // forced peer was dead) — the record, and
                    // its masque:// link, must carry the gateway
                    // that actually works. 1.59: a replacement
                    // that worked is saved the same way (its IP
                    // updated to the working one for the current
                    // network, and the build network stamped
                    // to it).
                    val selGw = AetherBridge.lastSessionGateway
                    val finalGw = selGw ?: useRec.gatewayIp
                    if (selGw != null && selGw != useRec.gatewayIp) {
                        AppLog.append(this, "warp",
                            "اتصال: دروازهٔ رکورد عوض شد: $selGw (به‌جای ${useRec.gatewayIp}).",
                            "Connect: record gateway switched: $selGw (instead of ${useRec.gatewayIp}).")
                    }
                    if (useRec.gatewayIp != rec.gatewayIp) {
                        AppLog.append(this, "warp",
                            "اتصال: آی‌پی «${useRec.name}» روی شبکهٔ «$netKey» به ${useRec.gatewayIp} عوض و ذخیره شد.",
                            "Connect: “${useRec.name}”’s IP was swapped to ${useRec.gatewayIp} on network “$netKey” and saved.")
                    }
                    useRec = useRec.copy(gatewayIp = finalGw, buildNetwork = netKey)
                    if (finalGw != rec.gatewayIp || netKey != rec.buildNetwork) {
                        WarpFree.updateRecord(this, useRec)
                    }
                }
                try { VpnConfigBuilder.buildAetherTun(this, socksPort = if (warmPort > 0) warmPort else AetherBridge.SESSION_SOCKS_PORT) } catch (_: Exception) { null }
            } else null
            val link = useRec.link()
            val built = aetherBuilt ?: try { VpnConfigBuilder.build(this, link) } catch (_: Exception) { null }
            if (built == null) {
                warpConnecting = false
                // 1.50: a tunnel started above has no consumer now.
                if (isSessionRung) AetherBridge.stopSessionTunnel()
                runOnUiThread {
                    clearConnectStatus()
                    AppLog.append(this, "warp",
                        "«${useRec.name}» وصل نشد: ساخت کانفیگ ناموفق بود.",
                        "“${useRec.name}” did not connect: config build failed.")
                    refreshList()
                }
                return@Thread
            }
            if (!CoreInit.init(applicationContext)) {
                warpConnecting = false
                if (isSessionRung) AetherBridge.stopSessionTunnel()
                runOnUiThread {
                    clearConnectStatus()
                    AppLog.append(this, "warp",
                        "«${useRec.name}» وصل نشد: هسته Xray راه‌اندازی نشد.",
                        "“${useRec.name}” did not connect: the Xray core failed to start.")
                    // 1.70 — this path previously never
                    // rebuilt the list, so the button
                    // would stay yellow after the failure.
                    refreshList()
                }
                return@Thread
            }
            val intent = Intent(this, VpnClientService::class.java).apply {
                putExtra("config", built)
                putExtra("name", useRec.name)
                putExtra("link", link)
                putExtra("session_tag", "warp")
            }
            VpnClientService.emergencyConfig = null
            try {
                startService(intent)
            } catch (e: Exception) {
                warpConnecting = false
                if (isSessionRung) AetherBridge.stopSessionTunnel()
                runOnUiThread {
                    clearConnectStatus()
                    AppLog.append(this, "warp",
                        "«${useRec.name}» وصل نشد: ${e.message ?: ""}.",
                        "“${useRec.name}” did not connect: ${e.message ?: ""}.")
                    // 1.70 — same missing rebuild as the
                    // CoreInit path above: return the
                    // button to idle on this failure.
                    refreshList()
                }
                return@Thread
            }
            // 1.46: activeId is NOT set here any more.
            // Nothing may claim "connected" — not the row
            // toggle, not the status line, not the
            // notification — until the real-traffic verify
            // below passes.
            runOnUiThread {
                clearConnectStatus()
                handler.postDelayed({ refreshStatus() }, 1500)
            }
            // Real-traffic verification through the
            // tunnel (generate_204 via the session
            // SOCKS), then the real exit country
            // from trace through the tunnel.
            // 1.58 (Part B) — no fixed 4s wait: poll the
            // verdict (same 12s budget, 500ms cadence,
            // 5s per attempt) so a fast TUN verifies in
            // ~1s instead of always paying 4s + one 10s
            // attempt. The verdict is UNCHANGED: real
            // generate_204 through the tunnel, 200/204
            // or the connect fails — polling only retries
            // while the TUN is still coming up.
            val gen = ++warpVerifyGen
            val verifyDeadline = System.currentTimeMillis() + 12_000L
            var port = -1
            var code = -2
            var proven = false
            while (System.currentTimeMillis() < verifyDeadline && gen == warpVerifyGen) {
                port = VpnClientService.sessionSocksPort
                if (port <= 0) port = try { VpnConfigBuilder.socksPort(this) } catch (_: Exception) { -1 }
                code = if (port > 0) WarpIdentity.generate204ThroughSocks(port, 5000) else -2
                if (code == 200 || code == 204) { proven = true; break }
                try { Thread.sleep(500) } catch (_: Exception) { break }
            }
            if (!proven || gen != warpVerifyGen) {
                if (gen == warpVerifyGen) {
                    val reasonFa = if (port <= 0)
                        "پورت SOCKS جلسه مشخص نیست (۰)؛ ترافیک واقعی بررسی نشد"
                    else "تونل بالا است ولی ترافیک واقعی رد نمی‌شود (کد $code)"
                    val reasonEn = if (port <= 0)
                        "session SOCKS port unknown (0); real traffic was not checked"
                    else "the tunnel is up but no real traffic passes (code $code)"
                    AppLog.append(this, "warp",
                        "«${useRec.name}»؛ وصل نیست — $reasonFa؛ تونلِ مرده متوقف شد.",
                        "“${useRec.name}” not connected — $reasonEn; the dead tunnel was stopped.")
                    // 1.46: STOP the dead tunnel — never
                    // leave it up with the toggle on and a
                    // "connected" notification behind.
                    try {
                        val stop = Intent(this, VpnClientService::class.java).apply {
                            action = "STOP"
                            putExtra("stop_gen", VpnClientService.sessionGen)
                        }
                        startService(stop)
                    } catch (_: Exception) {}
                    VpnClientService.isRunning = false
                    WarpFree.setActiveId(this, "")
                    warpConnecting = false
                    runOnUiThread {
                        clearConnectStatus()
                        refreshList()
                        refreshStatus()
                    }
                }
                return@Thread
            }
            // Verified: only NOW may the row, the status
            // line and the notification claim "connected".
            // 1.58 — the exit-country lookup NO LONGER
            // blocks this: the tunnel is validated and
            // SOCKS is listening, so the row flips to
            // connected NOW (the 14:10 device log showed
            // ~3.6s of the wait was just the geo lookup).
            // The exit country is fetched in the
            // background and the row updated when it
            // arrives. The validation probe above is NOT
            // deferred — only the display of the exit.
            WarpFree.setActiveId(this, useRec.id)
            warpConnecting = false
            try {
                val verified = Intent(this, VpnClientService::class.java).apply { action = "WARP_VERIFIED" }
                startService(verified)
            } catch (_: Exception) {}
            AppLog.append(this, "warp",
                "[✓] «${useRec.name}» وصل شد؛ ترافیک واقعی رد شد.",
                "[✓] “${useRec.name}” connected; real traffic passed.")
            runOnUiThread {
                loadWarpRecords()
                refreshList()
                refreshStatus()
            }
            // Exit country in the background (up to 10s):
            // the record + the row are updated when it
            // lands; nothing waits on it.
            val exitRec = useRec
            val exitPort = port
            Thread {
                // 1.69 — (D) DEDICATED CONNECTED-EXIT
                // LOG (user order 2026-11-20 ~22:40):
                // after a connect, a LIVE IP test
                // through the tunnel (the full trace
                // — ip + country + colo) logs which
                // country the connected config is for,
                // WITH the tested exit IP: a «خروج:
                // <country> — <ip>» line. When the
                // live test answers nothing at all,
                // the entry says exactly that instead
                // of staying silent.
                val exit = if (exitPort > 0) WarpIdentity.fetchTraceFullThroughSocks(exitPort, 10000) else null
                val exitIp = exit?.first ?: ""
                val exitCc = exit?.second ?: ""
                val exitColo = exit?.third ?: ""
                if (exitCc.isNotBlank()) {
                    WarpFree.updateRecord(this, exitRec.copy(exitCountry = exitCc, exitColo = exitColo))
                    val exitSuffix = if (exitColo.isNotBlank()) " ($exitColo)" else ""
                    val ipPart = if (exitIp.isNotBlank()) " — $exitIp" else ""
                    AppLog.append(this, "warp",
                        "خروج: ${WarpFree.countryName(this, exitCc)}$exitSuffix$ipPart («${exitRec.name}»).",
                        "Exit: ${WarpFree.countryName(this, exitCc)}$exitSuffix$ipPart (“${exitRec.name}”).")
                    if (WarpFree.activeId(this) == exitRec.id) {
                        runOnUiThread {
                            try {
                                loadWarpRecords()
                                refreshList()
                                refreshStatus()
                            } catch (_: Exception) {}
                        }
                    }
                } else {
                    AppLog.append(this, "warp",
                        "خروج «${exitRec.name}»: تست آی‌پی زنده از داخل تونل جواب نداد؛ کشور خروج مشخص نشد.",
                        "Exit for “${exitRec.name}”: the live IP test through the tunnel did not answer; the exit country is unknown.")
                }
            }.start()
        }.start()
    }

    private fun warpDisconnect(rec: WarpFree.WarpRecord) {
        warpVerifyGen++
        warpConnecting = false
        AppLog.append(this, "warp",
            "«${rec.name}»؛ دستی قطع شد.",
            "“${rec.name}”; stopped manually.")
        try { getSharedPreferences("renamer_smart", MODE_PRIVATE).edit().putBoolean("smart_owns_vpn", false).apply() } catch (_: Exception) {}
        if (rec.rung == WarpFree.RUNG_WG) {
            disconnectWg()
            warpWgVerified = false
            WarpFree.setActiveId(this, "")
            refreshList()
            refreshStatus()
            return
        }
        val stopIntent = Intent(this, VpnClientService::class.java).apply {
            action = "STOP"
            putExtra("stop_gen", VpnClientService.sessionGen)
        }
        try { startService(stopIntent) } catch (_: Exception) {}
        VpnClientService.isRunning = false
        WarpFree.setActiveId(this, "")
        refreshList()
        refreshStatus()
    }

    // 1.71 (item 9): the relay feature is retired (user order 2026-11-21 ~01:14) —
    // showWarpCountryDialog / rebuildBundledRows / relayIranRow / relayBundledRow /
    // selectBundledCountry / connectSelectedWarp / connectRelayCountry /
    // testChainLinkWithBudget / runBounded / ensureRelayCheckSession /
    // cleanupRelayCheckSession / warpConnectRelay are all deleted with it. The
    // free-warp card connects plain free warp (direct MASQUE) only, via warpConnect.

    /** The WG page log, plus the warp section
     *  lines from the central log (copy copies
     *  both). */
    private fun warpLogCombined(): String {
        val wg = WgLog.text()
        val warpLines = try {
            AppLog.text(this).lines().filter { it.contains("[warp]") }
        } catch (_: Exception) { emptyList() }
        val warp = warpLines.joinToString("\n")
        return when {
            wg.isNotBlank() && warp.isNotBlank() -> "$wg\n\n— WARP —\n$warp"
            warp.isNotBlank() -> warp
            else -> wg
        }
    }

}

