package com.renamer

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Quick-connect card (2.80): an Aurora card on the Home page, above the
 * tiles, showing the LAST method the user connected with and connecting
 * to it with one tap. The user picked mockup variant 4 (aurora card with
 * status ring + ping) and ordered the colors exactly as in the mockup:
 * a faint aurora gradient (soft purple / teal / pink) over pearl, with a
 * circular power button in a dotted status ring.
 *
 * 2.81: the card also shows the NAME of the config it last connected to
 * (line 2) and live up/down speeds next to the ping (line 3), and it
 * reconnects to EXACTLY that config — never "first" or "best" (except
 * Emergency, which ping-tests and picks the best, like its own page).
 * The config identity is recorded at connect time next to the method:
 * the v2ray-family link, the WireGuard tunnel name, or the OpenVPN
 * profile name. Emergency records no config at all (its configs are
 * never saved); Hydra has no single config, so its line 2 stays empty.
 *
 * The last method is recorded at connect time by a one-line hook in each
 * section's own connect path (v2ray / WireGuard / OpenVPN / Emergency /
 * Smart / Hydra / Chain). The card never implements connection logic
 * itself: tapping it reuses each method's EXISTING connect/disconnect
 * path. Live state comes from the app's own composite
 * (VpnClientService.isRunning || WgDirect.isLive || OvpnEngine.isConnected).
 * The ping shown is the method's real last-measured ping when the app has
 * one; when unknown the ping line is hidden — never a fake number.
 * Live speeds come from the SAME source the Monitoring page uses
 * (VpnNotification.currentSpeedsKbps); when unknown they are hidden,
 * never invented.
 */
object QuickConnect {

    const val PREFS = "renamer_settings"
    const val KEY_METHOD = "last_connect_method"
    const val KEY_PING = "last_connect_ping_ms"
    const val KEY_CONNECTING_SINCE = "quick_connecting_since"
    /** 2.81: the identity of the exact config last connected to — a
     *  v2ray-family link, a WireGuard tunnel name, or an OpenVPN
     *  profile name. Empty for Emergency (never saved) and Hydra. */
    const val KEY_CONFIG = "last_connect_config"
    /** 2.81: the card button's green — ONE constant, shared with the
     *  connected tile's green border on Home (fix 6), so the two greens
     *  can never drift apart. */
    const val GREEN_HEX = "#10B981"

    const val M_V2RAY = "v2ray"
    const val M_WIREGUARD = "wireguard"
    const val M_OPENVPN = "openvpn"
    const val M_EMERGENCY = "emergency"
    const val M_SMART = "smart"
    const val M_HYDRA = "hydra"
    const val M_CHAIN = "chain"

    const val STATE_CONNECTED = 0
    const val STATE_CONNECTING = 1
    const val STATE_DISCONNECTED = 2

    /** A connect tap shows "connecting" for at most this long; a live
     *  session (or an immediate failure) clears it sooner. */
    const val CONNECTING_WINDOW_MS = 45_000L

    // ---------- recording ----------

    /** Remember the last method used, with the identity of the exact
     *  config it connected to (null = none, e.g. Emergency / Hydra).
     *  The ping is cleared here on purpose: a new method has no known
     *  ping until its own path resolves one. The config is ALWAYS
     *  written (value or cleared) so a stale config from an older
     *  session can never be shown under a different method. */
    fun record(ctx: Context, method: String, config: String? = null) {
        try {
            val ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_METHOD, method)
                .remove(KEY_PING)
            if (config.isNullOrBlank()) ed.remove(KEY_CONFIG) else ed.putString(KEY_CONFIG, config)
            ed.apply()
        } catch (_: Exception) {}
    }

    /** Remember the last method with its real measured ping (ms >= 0).
     *  A negative ping clears the stored ping (unknown — never fake). */
    fun recordWithPing(ctx: Context, method: String, pingMs: Long, config: String? = null) {
        try {
            val ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_METHOD, method)
            if (pingMs >= 0) ed.putLong(KEY_PING, pingMs) else ed.remove(KEY_PING)
            if (config.isNullOrBlank()) ed.remove(KEY_CONFIG) else ed.putString(KEY_CONFIG, config)
            ed.apply()
        } catch (_: Exception) {}
    }

    /** 2.81: update ONLY the recorded config identity, leaving the
     *  method alone. For paths that connect on someone else's behalf
     *  (Smart's Hydra target has no single config name, so it records
     *  nothing here — Smart's own outcome log clears it instead). */
    fun recordConfig(ctx: Context, config: String?) {
        try {
            val ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (config.isNullOrBlank()) ed.remove(KEY_CONFIG) else ed.putString(KEY_CONFIG, config)
            ed.apply()
        } catch (_: Exception) {}
    }

    fun lastConfig(ctx: Context): String? = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONFIG, null)
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    fun lastMethod(ctx: Context): String? = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_METHOD, null)
    } catch (_: Exception) { null }

    fun lastPingMs(ctx: Context): Long = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_PING, -1L)
    } catch (_: Exception) { -1L }

    // ---------- live state ----------

    fun isLive(ctx: Context): Boolean = try {
        VpnClientService.isRunning || WgDirect.isLive(ctx) || OvpnEngine.isConnected
    } catch (_: Exception) { false }

    fun state(ctx: Context): Int {
        if (isLive(ctx)) return STATE_CONNECTED
        // OVPN's own connecting flag means "working on it".
        try {
            if (OvpnEngine.isConnecting) return STATE_CONNECTING
        } catch (_: Exception) {}
        val since = try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_CONNECTING_SINCE, 0L)
        } catch (_: Exception) { 0L }
        if (since > 0L) {
            if (System.currentTimeMillis() - since < CONNECTING_WINDOW_MS) return STATE_CONNECTING
            // Stale: the connect never came up; clear it.
            try {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_CONNECTING_SINCE).apply()
            } catch (_: Exception) {}
        }
        return STATE_DISCONNECTED
    }

    fun setConnecting(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_CONNECTING_SINCE, System.currentTimeMillis()).apply()
        } catch (_: Exception) {}
    }

    fun clearConnecting(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_CONNECTING_SINCE).apply()
        } catch (_: Exception) {}
    }

    // ---------- ping lookup for a v2ray-family link ----------

    /** The stored ping (ms) for a config link, or -1 when the app has never
     *  measured it. Keys are XraySpeedtest.dedupKey, like ping_delays. */
    fun pingForLink(ctx: Context, link: String?): Long {
        if (link.isNullOrBlank()) return -1L
        return try {
            val key = XraySpeedtest.dedupKey(link)
            val ms = ctx.getSharedPreferences("renamer_subs", Context.MODE_PRIVATE).getLong(key, -1L)
            if (ms >= 0) ms else -1L
        } catch (_: Exception) { -1L }
    }

    /** The VpnClientService start hook: remember the method that just
     *  started, with its real ping when the started link has one, and
     *  with the link itself as the exact config identity (2.81).
     *  Hydra is recorded by HydraService.start instead (its sessions also
     *  run on this service, under tag "hydra").
     *  Two exceptions: Emergency never stores a config (its configs are
     *  never saved, by design), and Chain keeps the "hop → hop" detail
     *  its own page already recorded — the hop-1 link alone would be
     *  less informative, and Smart's outcome log clears it for its own
     *  chain target anyway. */
    fun recordServiceStart(ctx: Context, tag: String?, currentLink: String?, origLink: String?) {
        val t = tag ?: M_V2RAY
        if (t == M_HYDRA) return
        // The engine's original link is the ping key when the session link
        // was engine-mutated; else the session link itself; else (plain
        // v2ray, no link extra) the user's selected config.
        val link = origLink ?: currentLink ?: if (t == M_V2RAY) {
            try { ctx.getSharedPreferences("renamer_vpn", Context.MODE_PRIVATE).getString("selected", null) } catch (_: Exception) { null }
        } else null
        val cfg = when (t) {
            M_EMERGENCY -> null
            M_CHAIN -> lastConfig(ctx)
            else -> link
        }
        recordWithPing(ctx, t, pingForLink(ctx, link), cfg)
    }

    /** Persian digits for the ping number in Persian mode ("۱۲۴ms"). */
    fun pingText(ctx: Context, ms: Long): String {
        val s = "${ms}ms"
        return if (AppLanguage.isEnglish(ctx)) s else faDigits(s)
    }

    /** 2.81: the SHORT method name for line 1 — «آخرین اتصال <method>».
     *  WireGuard is just «وایرگارد» here, not «وایرگارد و امنزیا»
     *  (the user's own wording). */
    fun shortMethodName(ctx: Context, method: String?): String {
        val en = AppLanguage.isEnglish(ctx)
        return when (method) {
            M_WIREGUARD -> if (en) "WireGuard" else "وایرگارد"
            M_OPENVPN -> "OpenVPN"
            M_EMERGENCY -> if (en) "Emergency" else "اتصال اضطراری"
            M_SMART -> if (en) "Smart Connect" else "اتصال هوشمند"
            M_HYDRA -> if (en) "Hydra" else "هیدرا"
            M_CHAIN -> if (en) "Double chain" else "زنجیره دوتایی"
            else -> "v2ray"
        }
    }

    /** 2.81: line 1 — «آخرین اتصال <method>» in one line. */
    fun line1Text(ctx: Context, method: String): String {
        return if (AppLanguage.isEnglish(ctx)) "Last connection: ${shortMethodName(ctx, method)}"
        else "آخرین اتصال ${shortMethodName(ctx, method)}"
    }

    /** 2.81: line 1 when nothing was ever connected — never
     *  «آخرین اتصال به ...», always the tap-to-connect prompt. */
    fun neverConnectedText(ctx: Context): String {
        return if (AppLanguage.isEnglish(ctx)) "Tap to connect to the emergency config"
        else "برای اتصال به کانفیگ اضطراری کلیک کنید"
    }

    /** 2.81: the display name for line 2 from a recorded config
     *  identity. A v2ray-family link becomes its display name (the
     *  same name the v2ray list shows); a tunnel/profile name, or a
     *  chain's "hop → hop" detail, is shown as-is. Null when there
     *  is no config to show (Emergency, Hydra). */
    fun configDisplayName(ctx: Context, config: String?): String? {
        if (config.isNullOrBlank()) return null
        return try {
            val c = config.trim()
            if (c.startsWith("vless://") || c.startsWith("vmess://") ||
                c.startsWith("trojan://") || c.startsWith("ss://") ||
                c.startsWith("ssr://") || c.startsWith("GROUP::") ||
                c.startsWith("CHAIN::") || c.trimStart().startsWith("{")) {
                XraySpeedtest.displayName(c).takeIf { it.isNotBlank() }
            } else if (c == TunnelBear.SINGLE_NAME) TunnelBear.DISPLAY_NAME
            else c
        } catch (_: Exception) { config }
    }

    /** 2.81: one speed value, KB/s in — same shape as the widgets
     *  ("1.2 MB/s" / "340 KB/s"), Persian digits in Persian mode. */
    fun formatSpeed(ctx: Context, kbps: Long): String {
        val s = if (kbps >= 1024) String.format(java.util.Locale.US, "%.1f MB/s", kbps / 1024f)
        else "$kbps KB/s"
        return if (AppLanguage.isEnglish(ctx)) s else faDigits(s).replace('.', '٫')
    }

    /** 2.81: line 3's speeds part — "↓ 1.2 MB/s   ↑ 340 KB/s".
     *  Null when the live speeds are unknown (never fake numbers). */
    fun speedsText(ctx: Context): String? {
        return try {
            val (down, up) = VpnNotification.currentSpeedsKbps(ctx)
            if (down < 0 || up < 0) null
            else "↓ ${formatSpeed(ctx, down)}   ↑ ${formatSpeed(ctx, up)}"
        } catch (_: Exception) { null }
    }

    private fun faDigits(s: String): String {
        val fa = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        val out = StringBuilder()
        for (c in s) out.append(if (c in '0'..'9') fa[c - '0'] else c)
        return out.toString()
    }

    // ---------- tap: connect / disconnect (existing paths only) ----------

    /** Tap while disconnected: connect to the last method (or Emergency
     *  when nothing was ever connected). Each branch reuses that method's
     *  existing connect path; nothing here reimplements a connection.
     *  2.81: the card reconnects to EXACTLY the last config — the same
     *  one it last connected to — never "first" or "best". Only
     *  Emergency ping-tests and picks the best, exactly like its own
     *  page. When the recorded config is gone (deleted), the section's
     *  own page opens instead of connecting a different config. */
    fun connect(activity: Activity) {
        val method = when (lastMethod(activity)) {
            M_V2RAY, M_WIREGUARD, M_OPENVPN, M_EMERGENCY, M_SMART -> lastMethod(activity)!!
            else -> M_EMERGENCY
        }
        setConnecting(activity)
        when (method) {
            M_V2RAY -> {
                // 2.81: the exact last-connected link, not the
                // currently-selected config. The receiver falls back
                // to the selected config only when no link was ever
                // recorded (pre-2.81 records).
                try {
                    activity.sendBroadcast(Intent(activity, VpnActionReceiver::class.java).apply {
                        action = VpnNotification.ACTION_CONNECT
                        lastConfig(activity)?.takeIf { it.isNotBlank() }?.let { putExtra("link", it) }
                    })
                } catch (_: Exception) { clearConnecting(activity) }
            }
            M_WIREGUARD -> {
                // 2.81: the exact last-connected tunnel by name; else
                // the saved active tunnel (the last one used); never
                // the first tunnel. Nothing found -> open the page.
                val conf = try {
                    val name = lastConfig(activity)
                    (if (!name.isNullOrBlank()) WgDirect.confByName(activity, name) else null)
                        ?: WgDirect.activeConf(activity)
                } catch (_: Exception) { null }
                if (conf == null) {
                    clearConnecting(activity)
                    try { activity.startActivity(Intent(activity, WireGuardActivity::class.java)) } catch (_: Exception) {}
                } else {
                    val ok = try { WgDirect.connectConfText(activity, conf.first, conf.second) } catch (_: Exception) { false }
                    if (!ok) {
                        clearConnecting(activity)
                        try { activity.startActivity(Intent(activity, WireGuardActivity::class.java)) } catch (_: Exception) {}
                    }
                }
            }
            M_OPENVPN -> {
                val profiles = try { OvpnStore.load(activity) } catch (_: Exception) { emptyList() }
                // No profile, or the VPN permission dialog is still
                // needed: the OVPN page handles both (its own flow).
                if (profiles.isEmpty() || android.net.VpnService.prepare(activity) != null) {
                    clearConnecting(activity)
                    try { activity.startActivity(Intent(activity, OpenVpnActivity::class.java)) } catch (_: Exception) {}
                } else {
                    try {
                        // 2.81: the exact last-connected profile — the
                        // recorded name, else the page's own last-used
                        // profile (ovpn_last). NEVER the first profile:
                        // when the recorded profile is gone, the OVPN
                        // page opens instead of connecting another one.
                        val last = lastConfig(activity)
                            ?: try { activity.getSharedPreferences("renamer_ovpn", Context.MODE_PRIVATE).getString("ovpn_last", null)?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
                        val profile = if (last != null) profiles.firstOrNull { it.name == last } else null
                        if (profile == null) {
                            clearConnecting(activity)
                            try { activity.startActivity(Intent(activity, OpenVpnActivity::class.java)) } catch (_: Exception) {}
                        } else {
                            OvpnEngine.connect(activity, profile) { ok ->
                                if (!ok) clearConnecting(activity)
                            }
                        }
                    } catch (_: Exception) { clearConnecting(activity) }
                }
            }
            M_EMERGENCY -> {
                // Fetch + ping runs on the caller's thread: off the UI.
                Thread {
                    val ok = try { EmergencyConnect.connect(activity) } catch (_: Exception) { false }
                    if (!ok) {
                        clearConnecting(activity)
                        activity.runOnUiThread {
                            try { Toast.makeText(activity, AppLanguage.t(activity, "اتصال اضطراری وصل نشد", "Emergency connect failed"), Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                        }
                    }
                }.start()
            }
            M_SMART -> {
                // Arm Smart Connect exactly like the Smart page's enable
                // tap: enable, clear any manual-stop suppression, start
                // the service. It connects when a selected app opens.
                try {
                    activity.getSharedPreferences("renamer_smart", Context.MODE_PRIVATE).edit()
                        .putBoolean("smart_enabled", true)
                        .remove("smart_manual_stop_until")
                        .apply()
                    val svc = Intent(activity, SmartConnectService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) activity.startForegroundService(svc) else activity.startService(svc)
                } catch (_: Exception) { clearConnecting(activity) }
            }
        }
    }

    /** Tap while connected: stop the live session, whatever it is, reusing
     *  each method's existing disconnect path. Smart Connect itself is NOT
     *  disabled — but a manual disconnect sets its 5-minute suppression
     *  (the same rule the v2ray page's disconnect follows), so Smart does
     *  not instantly reconnect a session the user just stopped. */
    fun disconnect(ctx: Context) {
        try {
            if (VpnClientService.isRunning) {
                try {
                    val stop = Intent(ctx, VpnClientService::class.java).apply { action = "STOP"; putExtra("stop_gen", VpnClientService.sessionGen) }
                    ctx.startService(stop)
                } catch (_: Exception) {}
                VpnClientService.isRunning = false
            }
        } catch (_: Exception) {}
        try { if (WgDirect.isLive(ctx)) WgDirect.disconnect(ctx) } catch (_: Exception) {}
        try { if (OvpnEngine.isConnected || OvpnEngine.isConnecting) OvpnEngine.disconnect() } catch (_: Exception) {}
        try {
            val sp = ctx.getSharedPreferences("renamer_smart", Context.MODE_PRIVATE)
            if (sp.getBoolean("smart_enabled", false)) {
                sp.edit().putLong("smart_manual_stop_until", System.currentTimeMillis() + 5 * 60 * 1000).apply()
            }
        } catch (_: Exception) {}
        clearConnecting(ctx)
    }

    // ---------- the card ----------

    class QuickConnectCard internal constructor(
        val root: LinearLayout,
        private val line1Tv: TextView,
        private val line2Tv: TextView,
        private val line3Tv: TextView,
        private val statusTv: TextView,
        private val statusDot: View,
        private val button: QuickConnectButtonView,
        private val dotGreen: android.graphics.drawable.GradientDrawable,
        private val dotAmber: android.graphics.drawable.GradientDrawable,
        private val dotRed: android.graphics.drawable.GradientDrawable,
        private val density: Float,
        private var builtDark: Boolean
    ) {
        /** The last good speeds line. currentSpeedsKbps returns (-1,-1)
         *  when called less than 1s after the previous call (its own
         *  baseline rule), so a too-early tick must keep showing the
         *  previous speeds instead of blanking the line for a tick. */
        private var lastSpeeds: String? = null
        /** Refresh everything from the live state + the recorded method.
         *  2.81: re-reads the method AND the config on every tick, so the
         *  card can never show a stale method after a connect elsewhere.
         *  Line 1: «آخرین اتصال <method>» (or the tap-to-connect prompt
         *  when nothing was ever connected). Line 2: the connected
         *  config's name, hidden when there is none. Line 3: live
         *  speeds + the real ping, hidden when neither is known. */
        fun refresh(ctx: Context) {
            // 2.84: SELF-HEALING mode — the background and the line
            // colours are re-applied whenever the mode differs from
            // the mode the card was built with, so the card can never
            // stay pearl in night mode (or dark in day mode) no
            // matter how the activity was restored or when the mode
            // took effect. The 1s poller calls refresh constantly, so
            // a stale card corrects itself within a second. (2.83's
            // bug: the background was applied only once at build time,
            // and refresh() never re-applied it.) 2.85: the BUTTON is
            // NOT re-applied here — it is identical in both modes.
            val dark = HomeActivity.isDarkMode(ctx)
            if (dark != builtDark) {
                builtDark = dark
                root.background = QuickConnectCardDrawable(density, dark)
                line1Tv.setTextColor(Color.parseColor(if (dark) "#F2F3FA" else "#23232E"))
                line2Tv.setTextColor(Color.parseColor(if (dark) "#B9BECD" else "#4B5563"))
                line3Tv.setTextColor(Color.parseColor(if (dark) "#5EEAD4" else "#0F766E"))
            }
            val st = state(ctx)
            val method = lastMethod(ctx)
            if (method == null) {
                line1Tv.text = neverConnectedText(ctx)
                line2Tv.visibility = View.GONE
            } else {
                line1Tv.text = line1Text(ctx, method)
                val cfgName = configDisplayName(ctx, lastConfig(ctx))
                if (cfgName.isNullOrBlank()) {
                    line2Tv.visibility = View.GONE
                } else {
                    line2Tv.text = cfgName
                    line2Tv.visibility = View.VISIBLE
                }
            }
            // Line 3: live speeds (same source as Monitoring) + the
            // real ping next to them. Either part may be absent; the
            // line hides only when BOTH are unknown — never fake.
            if (st == STATE_CONNECTED) {
                speedsText(ctx)?.let { lastSpeeds = it }
            } else {
                lastSpeeds = null
            }
            val ping = lastPingMs(ctx)
            val parts = ArrayList<String>()
            if (lastSpeeds != null) parts += lastSpeeds!!
            if (ping >= 0) parts += pingText(ctx, ping)
            if (parts.isEmpty()) {
                line3Tv.visibility = View.GONE
            } else {
                line3Tv.text = parts.joinToString("   •   ")
                line3Tv.visibility = View.VISIBLE
            }
            // 2.83: on the dark card the status text must be the light
            // variant of each state colour, or it is unreadable.
            // (dark is read once at the top of refresh, 2.84.)
            val en = AppLanguage.isEnglish(ctx)
            when (st) {
                STATE_CONNECTED -> {
                    statusTv.text = if (en) "Connected" else "متصل"
                    statusTv.setTextColor(Color.parseColor(if (dark) "#4ADE80" else "#15803D"))
                    statusDot.background = dotGreen
                    button.setState(STATE_CONNECTED)
                }
                STATE_CONNECTING -> {
                    statusTv.text = if (en) "Connecting" else "در حال اتصال"
                    statusTv.setTextColor(Color.parseColor(if (dark) "#FBBF24" else "#B45309"))
                    statusDot.background = dotAmber
                    button.setState(STATE_CONNECTING)
                }
                else -> {
                    statusTv.text = if (en) "Disconnected" else "قطع"
                    statusTv.setTextColor(Color.parseColor(if (dark) "#F87171" else "#B91C1C"))
                    statusDot.background = dotRed
                    button.setState(STATE_DISCONNECTED)
                }
            }
        }
    }

    private fun dotBg(colorHex: String) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(Color.parseColor(colorHex))
    }

    fun buildCard(activity: Activity, onTap: () -> Unit): QuickConnectCard {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d + 0.5f).toInt()
        val en = AppLanguage.isEnglish(activity)
        // 2.83: the card follows the mode too — dark glass in night,
        // the pearl card in day. The red power button is NOT touched.
        val dark = HomeActivity.isDarkMode(activity)
        // 2.81 fix 1 — direction, done DETERMINISTICALLY: 2.80 set
        // layoutDirection=RTL in Persian and added texts first, which
        // SHOULD have put the button on the left — but on this device
        // the button still landed on the RIGHT (the user's screenshots),
        // and the same RTL setting fails to flip the grid, the top row,
        // the bottom pill and the Settings rows too. So the card no
        // longer relies on layoutDirection at all: the root is forced
        // LTR, the children are added in an explicit per-language
        // order, and all text uses ABSOLUTE gravity (RIGHT in Persian,
        // LEFT in English). Persian: button column first (LEFT),
        // texts after it (RIGHT). English: mirrored.
        val textGravity = if (en) Gravity.LEFT else Gravity.RIGHT
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = QuickConnectCardDrawable(d, dark)
            elevation = 6f
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // 2.81 fix 2 — the card is exactly as wide as the tile
                // rows below it: the same 8dp side margins the tile
                // cells use (2.80 had 0 here, so the card stuck out
                // ~1mm past the tiles on each side).
                setMargins(dp(8), dp(12), dp(8), dp(4))
            }
        }
        // Texts column. Every line is full-width with ABSOLUTE gravity,
        // so line 2 (the config name) aligns exactly like line 1 — its
        // last Latin letter sits as far from the card's right edge as
        // line 1's does, even for a Latin name in Persian mode.
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = textGravity or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun lineTv(sizeSp: Float, colorHex: String, bold: Boolean, topMarginDp: Int): TextView {
            return TextView(activity).apply {
                textSize = sizeSp
                if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(colorHex))
                gravity = textGravity
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (topMarginDp > 0) topMargin = dp(topMarginDp) }
            }
        }
        // Line 1: «آخرین اتصال <method>» — one line, method inside it.
        // 2.83: light ink on the dark card, dark ink on the pearl card.
        val line1Tv = lineTv(17f, if (dark) "#F2F3FA" else "#23232E", true, 0)
        line1Tv.visibility = View.VISIBLE
        // Line 2: the connected config's name.
        val line2Tv = lineTv(13f, if (dark) "#B9BECD" else "#4B5563", false, 2)
        // Line 3: live ↑/↓ speeds + the real ping. Its gap above it
        // (6dp) is deliberately larger than the 1–2 gap (2dp).
        val line3Tv = lineTv(12f, if (dark) "#5EEAD4" else "#0F766E", true, 6)
        texts.addView(line1Tv)
        texts.addView(line2Tv)
        texts.addView(line3Tv)
        // Button column: status above the circular button. The gap
        // between the texts and the button sits on the side facing
        // the texts: right margin in Persian (button on the left),
        // left margin in English (button on the right).
        val btnCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (en) marginStart = dp(12) else marginEnd = dp(12)
            }
        }
        val statusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        val statusDot = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(5) }
        }
        val statusTv = TextView(activity).apply {
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        statusRow.addView(statusDot)
        statusRow.addView(statusTv)
        val button = QuickConnectButtonView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            isClickable = true
            isFocusable = true
            // 2.85: the button is identical in both modes (the 2.81
            // red disc, white glyph, rotating ring) — no night style.
            setOnClickListener { onTap() }
        }
        btnCol.addView(statusRow)
        btnCol.addView(button)
        // Explicit child order — Persian: button LEFT, texts RIGHT.
        if (en) {
            root.addView(texts)
            root.addView(btnCol)
        } else {
            root.addView(btnCol)
            root.addView(texts)
        }
        val card = QuickConnectCard(
            root, line1Tv, line2Tv, line3Tv, statusTv, statusDot, button,
            dotBg("#22C55E"), dotBg("#D97706"), dotBg("#DC2626"),
            d, dark
        )
        card.refresh(activity)
        return card
    }
}

/** The card's background (2.80): drawn entirely in code, no image asset.
 *  Pearl base with the picked mockup's faint aurora washes — soft purple
 *  top-left, teal centre, pink right — harmonious with the pearl home
 *  (AuroraPearlDrawable).
 *  2.83: in night mode the same washes on a DARK glass base, with a
 *  faint light border — the pearl card against the dark home looked
 *  broken (user's 2.82 screenshot). Same layout, same texts, same
 *  sizes; the red power button is never touched here.
 *  2.84: the night card is now EXACTLY like the night-pro-2 mockup —
 *  a dark aurora card (deep navy base, purple/teal/pink nebula washes
 *  and small stars, like the mockup's card), not just dark glass. */
internal class QuickConnectCardDrawable(density: Float, private val dark: Boolean = false) : android.graphics.drawable.Drawable() {
    private val radius = 24f * density
    private val basePaint = Paint().apply { color = if (dark) Theme.darken(Color.parseColor("#141828")) else Color.parseColor("#F8F7FC") }
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = if (dark) Theme.darken(Color.parseColor("#55FFFFFF")) else Color.parseColor("#22000000")
    }
    // 2.92: in night the card border is EXACTLY the home tiles'
    // thin neon line (TileBackgroundDrawable: 1dp teal→purple
    // gradient, TL→BR) — set as a shader in draw(), below. The
    // button itself (2.81) is never touched here.
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 160
    }
    // Deterministic stars on the night card, like the mockup.
    private val stars = arrayOf(
        floatArrayOf(0.08f, 0.12f, 1.8f), floatArrayOf(0.22f, 0.06f, 1.4f),
        floatArrayOf(0.38f, 0.16f, 1.6f), floatArrayOf(0.55f, 0.08f, 1.3f),
        floatArrayOf(0.72f, 0.14f, 1.8f), floatArrayOf(0.88f, 0.07f, 1.4f),
        floatArrayOf(0.94f, 0.30f, 1.5f), floatArrayOf(0.64f, 0.32f, 1.2f),
        floatArrayOf(0.30f, 0.42f, 1.4f), floatArrayOf(0.12f, 0.55f, 1.6f),
        floatArrayOf(0.48f, 0.68f, 1.3f), floatArrayOf(0.82f, 0.62f, 1.5f),
        floatArrayOf(0.20f, 0.82f, 1.4f), floatArrayOf(0.58f, 0.88f, 1.6f)
    )

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val rect = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val path = android.graphics.Path().apply {
            addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(rect, basePaint)
        if (dark) {
            // The mockup's night card: strong purple / teal / pink
            // nebula washes over the deep navy base, plus stars.
            drawBlob(canvas, b, 0.10f, 0.10f, 0.75f, "#8A5729A6")
            drawBlob(canvas, b, 0.55f, 0.45f, 0.70f, "#8A0E8174")
            drawBlob(canvas, b, 0.95f, 0.15f, 0.65f, "#8AA5326B")
            drawBlob(canvas, b, 0.30f, 0.95f, 0.60f, "#8A4C1C98")
            drawBlob(canvas, b, 0.85f, 0.85f, 0.55f, "#8A09685F")
            for (s in stars) {
                canvas.drawCircle(b.left + s[0] * b.width(), b.top + s[1] * b.height(), s[2], starPaint)
            }
        } else {
            // Faint aurora washes, same hues as the picked mockup.
            drawBlob(canvas, b, 0.08f, 0.10f, 0.75f, "#B5C7B9FF")
            drawBlob(canvas, b, 0.50f, 0.55f, 0.70f, "#B55EEAD4")
            drawBlob(canvas, b, 0.95f, 0.15f, 0.65f, "#B5F9A8D4")
            drawBlob(canvas, b, 0.30f, 0.95f, 0.60f, "#B5DDD6FE")
        }
        canvas.restore()
        // 2.92: night border = the tiles' thin neon line, drawn
        // inset by half the stroke exactly like the tiles.
        if (dark) {
            borderPaint.shader = LinearGradient(
                rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(),
                intArrayOf(Theme.darken(Color.parseColor("#2DD4BF")), Theme.darken(Color.parseColor("#A78BFA"))),
                null, Shader.TileMode.CLAMP)
        } else {
            borderPaint.shader = null
        }
        val inset = borderPaint.strokeWidth / 2f
        val inner = RectF(
            rect.left + inset, rect.top + inset,
            rect.right - inset, rect.bottom - inset)
        canvas.drawRoundRect(inner, maxOf(0f, radius - inset), maxOf(0f, radius - inset), borderPaint)
    }

    private fun drawBlob(
        canvas: Canvas,
        b: android.graphics.Rect,
        cx: Float, cy: Float, rFrac: Float, colorHex: String
    ) {
        val cxPx = b.left + cx * b.width()
        val cyPx = b.top + cy * b.height()
        val r = rFrac * maxOf(b.width(), b.height())
        blobPaint.shader = RadialGradient(
            cxPx, cyPx, r,
            intArrayOf(Color.parseColor(colorHex), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cxPx, cyPx, r, blobPaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}

/** The card's circular connect button (2.80): drawn entirely in code — a
 *  soft glow halo, a dotted status ring, a glossy disc and a white power
 *  glyph. The SAME drawing for all three states, only the colors change,
 *  so the red and yellow buttons are exactly as polished and glowing as
 *  the green one (user order).
 *  2.81: the dotted ring ROTATES slowly — one revolution per 14 seconds,
 *  like Emergency's rim (dots only; the disc and the glyph never move).
 *  The power glyph is a proper bold ⏻: a 60° gap at the TOP of the
 *  circle with a vertical bar breaking through it.
 *  2.85: the button is EXACTLY the same in BOTH modes — the 2.81
 *  button (status-coloured disc, white glyph, dotted rotating ring).
 *  The night button (dark disc, cyan glyph) is GONE — the user never
 *  ordered the button changed; only the card background differs by
 *  mode (dark aurora in night, pearl in day). */
internal class QuickConnectButtonView(ctx: Context) : View(ctx) {
    private var state = QuickConnect.STATE_DISCONNECTED
    // 2.88: v2ray FAB only — when true, the glyph shows the selected
    // connect mode (N = Normal, B = Best-ping) instead of the ⏻ power
    // glyph. The home quick-connect button never sets this and keeps ⏻.
    var showModeLetter = false
    var modeLetter = "N"
        private set
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    /** The v2ray FAB calls this on every refresh from the saved
     *  connect_mode pref, so the letter is always right — and it is
     *  applied immediately when the user picks a mode. */
    fun setModeLetter(letter: String) {
        if (modeLetter != letter) { modeLetter = letter; invalidate() }
    }
    private val density = resources.displayMetrics.density
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5.5f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    /** 2.81: slow ring rotation — one full turn per 14,000 ms. The
     *  ticker only runs while the view is attached, ~10 invalidations
     *  per second, so the ring turns smoothly without draining the
     *  battery while Home is closed. */
    private val ringTicker = object : Runnable {
        override fun run() {
            invalidate()
            handler?.postDelayed(this, 100)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler?.post(ringTicker)
    }

    override fun onDetachedFromWindow() {
        handler?.removeCallbacks(ringTicker)
        super.onDetachedFromWindow()
    }

    fun setState(s: Int) {
        if (state != s) { state = s; invalidate() }
    }

    private fun withAlpha(hex: String, alpha: Float): Int {
        val c = Color.parseColor(hex)
        return (c and 0x00FFFFFF) or (((alpha * 255).toInt() and 0xFF) shl 24)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        if (r <= 0f) return
        val (light, base, ring) = when (state) {
            QuickConnect.STATE_CONNECTED -> Triple("#6EE7B7", QuickConnect.GREEN_HEX, "#34D399")
            QuickConnect.STATE_CONNECTING -> Triple("#FDE68A", "#F59E0B", "#FBBF24")
            else -> Triple("#FECACA", "#EF4444", "#F87171")
        }
        // Halo: a soft glow around the disc.
        haloPaint.shader = RadialGradient(cx, cy, r,
            intArrayOf(withAlpha(ring, 0.38f), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, haloPaint)
        // Disc: light centre fading to the base colour at the edge.
        discPaint.shader = RadialGradient(cx, cy, r * 0.74f,
            intArrayOf(Color.parseColor(light), Color.parseColor(base)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 0.74f, discPaint)
        // Dotted status ring (the mockup's ring around the button) —
        // 2.81: it ROTATES, one revolution per 14 seconds.
        ringPaint.color = Color.parseColor(ring)
        canvas.drawCircle(cx, cy, r * 0.88f, ringPaint)
        dotPaint.color = Color.parseColor(ring)
        val dotR = 2.2f * density
        val rotDeg = ((android.os.SystemClock.uptimeMillis() % 14_000L) / 14_000f) * 360f
        for (i in 0 until 12) {
            val a = Math.toRadians((i * 30 + rotDeg).toDouble())
            val dx = (cx + r * 0.88f * Math.cos(a)).toFloat()
            val dy = (cy + r * 0.88f * Math.sin(a)).toFloat()
            canvas.drawCircle(dx, dy, dotR, dotPaint)
        }
        // Glyph: the home button draws the bold ⏻ (the arc leaves a
        // 60° gap at the TOP, start 300°, sweep 300°, and the vertical
        // bar runs from above the arc's top down to near the centre).
        // The v2ray FAB (showModeLetter) draws the white bold mode
        // letter instead — N for Normal, B for Best-ping — in the same
        // style and size. The glyph is white in both modes.
        if (showModeLetter) {
            letterPaint.textSize = r * 0.62f
            val fm = letterPaint.fontMetrics
            canvas.drawText(modeLetter, cx, cy - (fm.ascent + fm.descent) / 2f, letterPaint)
        } else {
            glyphPaint.color = Color.WHITE
            val gr = r * 0.38f
            canvas.drawArc(RectF(cx - gr, cy - gr, cx + gr, cy + gr), 300f, 300f, false, glyphPaint)
            canvas.drawLine(cx, cy - r * 0.58f, cx, cy - r * 0.02f, glyphPaint)
        }
    }
}

/**
 * 1.29 (order 9): the v2ray connect button, restyled to the OpenVPN
 * section's orb — same texture and type as the OpenVPN orb button
 * (OvpnTheme's sapphire radial-gradient orb + gloss + sparkles +
 * thin white rim, with the sweep-gradient ring around it), but kept
 * at the v2ray FAB's small size (60dp). The HOME quick-connect
 * button is NOT this class — it stays QuickConnectButtonView
 * (red disc, white ⏻ glyph, thin ring at 14s/rev), exactly as the
 * user locked it. This view keeps the same API as
 * QuickConnectButtonView (showModeLetter / setModeLetter / setState)
 * so the v2ray page's call sites work unchanged: the letter is the
 * connect-mode letter (white bold N = Normal, B = Best-ping).
 *
 * Composition (OpenVPN orb, scaled down): the ring view is the
 * full 60dp, the orb face is 54dp centred inside it (3dp each
 * side), the letter sits centred on the orb. The ring rotates
 * 0→360 in 14,000 ms, like the OpenVPN orb's ring.
 * Tints (OvpnTheme.glowDrawable defaults): 1.38 — disconnected
 * is the selected connect mode's colour (gold #F59E0B normal,
 * blue #0EA5E9 best-ping, via setIdleTint; the old red
 * #EF4444 is only the fallback when no idle tint was set),
 * connecting amber #F59E0B, connected green #2E9E6B.
 */
internal class VpnOrbButtonView(ctx: Context) : FrameLayout(ctx) {
    private var state = QuickConnect.STATE_DISCONNECTED
    // 1.38 (B2): the disconnected tint — the v2ray page
    // sets it from the saved connect_mode on every
    // refreshFab (gold normal, blue best-ping). Null
    // keeps the old red, so any other caller is
    // unaffected (there is none today — only the v2ray
    // page uses this view).
    private var idleTintHex: String? = null

    /** The v2ray page calls this from refreshFab (and
     *  once at construction) so the idle orb always
     *  wears the selected mode's colour. */
    fun setIdleTint(hex: String) {
        if (idleTintHex != hex) { idleTintHex = hex; applyState() }
    }
    // v2ray FAB only — when true, the glyph shows the selected
    // connect mode (N = Normal, B = Best-ping) instead of ⏻.
    // The page sets this right after construction, so the
    // setter refreshes the letter's visibility at once.
    var showModeLetter = false
        set(v) {
            field = v
            letterView.visibility = if (v) View.VISIBLE else View.GONE
        }
    var modeLetter = "N"
        private set

    private val density = resources.displayMetrics.density
    private val ringView = View(ctx).apply {
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        isClickable = false
    }
    private val orbView = View(ctx).apply {
        val s = (54 * density).toInt()
        layoutParams = LayoutParams(s, s).apply { gravity = Gravity.CENTER }
        isClickable = false
    }
    private val letterView = TextView(ctx).apply {
        textSize = 19f
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        isClickable = false
    }
    private var ringAnim: ObjectAnimator? = null

    init {
        addView(ringView)
        addView(orbView)
        addView(letterView)
        applyState()
    }

    fun setModeLetter(letter: String) {
        if (modeLetter != letter) {
            modeLetter = letter
            letterView.text = modeLetter
        }
    }

    fun setState(s: Int) {
        if (state != s) { state = s; applyState() }
    }

    private fun tintFor(s: Int): Int = when (s) {
        QuickConnect.STATE_CONNECTED -> Color.parseColor("#2E9E6B")
        QuickConnect.STATE_CONNECTING -> Color.parseColor("#F59E0B")
        else -> Color.parseColor(idleTintHex ?: "#EF4444")
    }

    private fun applyState() {
        val tint = tintFor(state)
        val connected = state == QuickConnect.STATE_CONNECTED
        orbView.background = OvpnTheme.orbBackground(context, connected, tint)
        ringView.background = OvpnTheme.ringArcDrawableTinted(context, tint)
        letterView.text = modeLetter
        letterView.visibility = if (showModeLetter) View.VISIBLE else View.GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ringAnim?.cancel()
        ringAnim = ObjectAnimator.ofFloat(ringView, "rotation", 0f, 360f).apply {
            duration = 14000
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
        }
        ringAnim?.start()
    }

    override fun onDetachedFromWindow() {
        try { ringAnim?.cancel() } catch (_: Exception) {}
        ringAnim = null
        super.onDetachedFromWindow()
    }
}
