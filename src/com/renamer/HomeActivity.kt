package com.renamer

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*

/** Main menu, as it was before v1.39. The Android 16
 *  targetSdk fix from v1.40 is kept, and the original
 *  icons from v1.39 are kept. */
class HomeActivity : Activity() {
    private data class MenuItem(val key: String, val name: String, val iconAsset: String?, val target: Class<*>?)

    // it can be rebuilt on resume (a saved reorder then
    // shows at once on return, instead of waiting for a
    // full activity recreate).
    private lateinit var gridContainer: LinearLayout
    // 2.80: the Aurora quick-connect card above the tiles + its 1s poller
    // (the card reflects the live connection state while Home is open).
    private var quickCard: QuickConnect.QuickConnectCard? = null
    // 1.73 (worker C): the update-checker box slot — between the
    // quick-connect card and the tile grid; GONE until the
    // once-per-launch auto-check finds a newer release.
    private lateinit var updateBoxSlot: LinearLayout
    // 2.81: home tile cells by section key, so the connected tile's
    // border can turn green live (fix 6) without rebuilding the grid.
    private val tileCells = mutableMapOf<String, LinearLayout>()
    private val quickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val quickPoller = object : Runnable {
        override fun run() {
            try { quickCard?.refresh(this@HomeActivity) } catch (_: Exception) {}
            // 2.81: the connected tile's green border lives with the
            // card's state, so it updates on the same 1s tick.
            try { refreshTileBorders() } catch (_: Exception) {}
            quickHandler.postDelayed(this, 1000)
        }
    }
    private var gridDark = false
    private var gridFg = Color.BLACK
    private var gridIsHidden = false
    private var homeGridBuilt = false
    private var lastHomeOrder = ""

    companion object {
        fun themeMode(ctx: android.content.Context): String {
            return ctx.getSharedPreferences("renamer_settings", MODE_PRIVATE)
                .getString("theme_mode", "") ?: ""
        }
        /** 2.84: ONE night decision for the whole app — this
         *  delegates to Theme.isDark, so the home background,
         *  the quick-connect card and every tap-opened page
         *  can never disagree about the mode again. (2.83's
         *  pearl card: the card background was applied only
         *  once at build time and refresh() never re-applied
         *  it, so a card built before the mode took effect
         *  stayed pearl forever. QuickConnect.refresh now
         *  re-applies the background on every tick.) */
        fun isDarkMode(ctx: android.content.Context): Boolean {
            return Theme.isDark(ctx)
        }
    }

    /** 2.12: after a full rollback install, offer
     *  to restore the data backup once. */
    private fun maybeOfferRestore() {
        try {
            val found = RollbackManager.findBackup(this) ?: return
            val (dateAdded, json) = found
            val seen = getSharedPreferences("renamer_rollback", MODE_PRIVATE)
                .getString("restore_seen", null)
            if (seen == dateAdded.toString()) return
            getSharedPreferences("renamer_rollback", MODE_PRIVATE).edit()
                .putString("restore_seen", dateAdded.toString()).apply()
            android.app.AlertDialog.Builder(this)
                .setTitle(AppLanguage.t(this, "بازگرداندن داده‌ها", "Restore data"))
                .setMessage(AppLanguage.t(this,
                    "پشتیبان داده‌های نسخه قبلی پیدا شد. برگردد؟",
                    "A data backup from the previous version was found. Restore it?"))
                .setPositiveButton(AppLanguage.t(this, "بله", "Yes")) { _, _ ->
                    try {
                        // Only claim success when restore really ran;
                        // a rejected file (e.g. a main-app backup) must not
                        // show the false "restored" toast.
                        if (RollbackManager.restorePrefsJson(this, json)) {
                            android.widget.Toast.makeText(this,
                                AppLanguage.t(this, "داده‌ها برگشت.", "Data restored."),
                                android.widget.Toast.LENGTH_SHORT).show()
                            recreate()
                        }
                    } catch (_: Exception) {}
                }
                .setNegativeButton(AppLanguage.t(this, "نه", "No"), null)
                .show()
        } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2.84: the window behind the content (status-bar +
        // nav-bar strips) follows the mode, so no white
        // strips remain around the home aurora.
        try { Theme.applyPage(this) } catch (_: Exception) {}
        // 2.12: remove the removed Tor section's leftover
        // on-device data dirs (binaries + state). Nothing
        // in the app reads them anymore; this runs once.
        try {
            val spTor = getSharedPreferences("renamer_settings", MODE_PRIVATE)
            if (!spTor.getBoolean("tor_leftover_cleaned_212", false)) {
                for (d in arrayOf("tor_bin", "tor_data", "geoip")) {
                    try { java.io.File(filesDir, d).deleteRecursively() } catch (_: Exception) {}
                }
                spTor.edit().putBoolean("tor_leftover_cleaned_212", true).apply()
            }
        } catch (_: Exception) {}
        try { RollbackManager.ensureSnapshot(this) } catch (_: Exception) {}
        try { maybeOfferRestore() } catch (_: Exception) {}
        // Re-arm Smart Connect on every Home open, so a
        // killed service comes back without the user
        // having to re-save it.
        try {
            val sp = getSharedPreferences("renamer_smart", MODE_PRIVATE)
            val apps = sp.getString("smart_apps", "")?.lines()?.filter { it.isNotBlank() } ?: emptyList()
            if (sp.getBoolean("smart_enabled", false) && apps.isNotEmpty()) {
                val svc = Intent(this, SmartConnectService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
            }
        } catch (_: Exception) {}
        val mode = intent.getStringExtra(PinActivity.EXTRA_MODE) ?: PinActivity.MODE_PUBLIC
        val isHidden = mode == PinActivity.MODE_HIDDEN
        gridIsHidden = isHidden
        val dark = isDarkMode(this)
        // 2.82: the home now follows the night-mode setting (it was always
        // pearl since 2.78). Night = the picked «حرفه‌ای ۲» design: dark
        // navy aurora + the new icon set in neon-line turquoise; day =
        // exactly the pearl home, only the icons change (flat, no neon).
        gridDark = dark
        gridFg = if (dark) Color.parseColor("#F2F3FA") else Color.parseColor("#23232E")
        val homeFg = gridFg
        val homeFgGray = if (dark) Color.parseColor("#8A8FA3") else Color.parseColor("#9A9AA5")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.TRANSPARENT)
        }
        // 2.79: the top row is now just the version label.
        // 1.29: the engine pill is gone with the engine.
        // Night, language, settings and monitoring all moved out of the top
        // bar (user order): night + language live in Settings, settings and
        // monitoring live in the bottom box.
        val density = resources.displayMetrics.density
        fun dpPx(v: Int) = (v * density + 0.5f).toInt()
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Version label, always visible, left side,
        // light grey (1.79, user request): the other
        // buttons sit on the right.
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }
        topRow.addView(TextView(this).apply {
            text = if (versionName.isBlank()) "" else "V.$versionName"
            textSize = 12f
            setTextColor(homeFgGray)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            // 2.22: tapping the version opens the
            // version-history page (last 10 versions).
            setOnClickListener {
                startActivity(Intent(this@HomeActivity, VersionHistoryActivity::class.java))
            }
        })
        root.addView(topRow)
        // 2.80: Aurora quick-connect card above the tiles (user pick:
        // mockup variant 4). ONLY this card is added — nothing else on
        // the home page moves or changes.
        quickCard = QuickConnect.buildCard(this) { onQuickConnectTap() }
        root.addView(quickCard!!.root)
        // 1.73 (worker C): the update-checker box slot — GONE until
        // the once-per-launch auto-check finds a newer release.
        updateBoxSlot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(updateBoxSlot)
        // No title and no mode line at the top, at the user's request:
        // the page opens straight into the grid.

        gridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(gridContainer)
        migrateSmartToRow1Right()
        rebuildHomeGrid()

        // Scrollable on purpose: tiles are added over
        // time, and a fixed page cut the last row off
        // (the Hydra icon showed half) once the grid
        // grew past the screen. With a ScrollView, any
        // number of future tiles just scrolls.
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            isFillViewport = true
        }
        scroll.addView(root)
        // 2.17: lock & exit lives in a fixed bottom bar,
        // not inside the scroll, so it always sits at
        // the bottom of the page. Same action (finish).
        // 2.22: the bar paints the page background
        // instead of staying transparent, so the strip
        // behind the button is exactly the same white
        // (or dark) as the options area above it —
        // no darker frame anymore.
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }
        val barRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpPx(16), dpPx(16), dpPx(16), dpPx(20))
        }
        // 2.79: the bottom bar is now one glass pill with three icons and no
        // text: settings (gear), monitoring (pulse), lock — in that order.
        // 1.31: the lock is back with its exact pre-1.29 behaviour (finish).
        // Monitoring is ALWAYS visible (this app has no hidden mode, so the
        // old hidden-only rule cannot apply).
        val bottomPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpPx(28).toFloat()
                // 2.82: dark glass in night, the same pearl glass in day.
                // 1.73 (home-night gap): night fill + stroke ×0.7.
                setColor(if (dark) Theme.darken(Color.parseColor("#CC252B40")) else Color.parseColor("#E6FFFFFF"))
                setStroke(dpPx(1), if (dark) Theme.darken(Color.parseColor("#33FFFFFF")) else Color.parseColor("#22000000"))
            }
            elevation = 4f
            setPadding(dpPx(10), dpPx(8), dpPx(10), dpPx(8))
        }
        // 1.31: drawn padlock glyph (no emoji), restored verbatim
        // from v1.28 — the same drawing as the old lock button.
        fun padlockBitmap(sizePx: Int): android.graphics.Bitmap? {
            return try {
                val d = resources.displayMetrics.density
                val s = sizePx
                val bmp = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(bmp)
                val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = homeFg
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 2f * d
                    strokeCap = android.graphics.Paint.Cap.ROUND
                }
                cv.drawArc(android.graphics.RectF(s * 0.28f, s * 0.06f, s * 0.72f, s * 0.52f), 180f, 180f, false, pt)
                pt.style = android.graphics.Paint.Style.FILL
                cv.drawRoundRect(android.graphics.RectF(s * 0.16f, s * 0.46f, s * 0.84f, s * 0.94f), 3f * d, 3f * d, pt)
                pt.color = Color.WHITE
                cv.drawCircle(s * 0.5f, s * 0.66f, 2f * d, pt)
                bmp
            } catch (_: Exception) { null }
        }
        fun pillIcon(boxDp: Int): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(dpPx(boxDp), dpPx(boxDp)).apply {
                setMargins(dpPx(10), 0, dpPx(10), 0)
            }
        bottomPill.addView(TextView(this).apply {
            text = "⚙"
            // 2.96: the SAME visual size as the monitor icon in its 32dp
            // box — 18sp made the gear read smaller and fainter than it.
            // 24sp + full opacity matches it.
            textSize = 24f
            alpha = 1f
            gravity = Gravity.CENTER
            setTextColor(homeFg)
            layoutParams = pillIcon(32)
            isClickable = true
            setOnClickListener {
                val s = Intent(this@HomeActivity, HomeSettingsActivity::class.java)
                s.putExtra(PinActivity.EXTRA_MODE, intent.getStringExtra(PinActivity.EXTRA_MODE) ?: PinActivity.MODE_PUBLIC)
                startActivity(s)
            }
        })
        // 1.29: Monitoring is always visible (no hidden mode in this app).
        bottomPill.addView(android.widget.ImageView(this).apply {
                try {
                    val bmp = BitmapFactory.decodeStream(assets.open("monitor.png"))
                    setImageBitmap(bmp)
                    // 2.82: the pulse SHAPE is unchanged — only its ink
                    // lightens in night so it still reads on the dark pill.
                    // 2.95: in day the ink is BLACK (the asset's own
                    // teal was too light on the white pill).
                    if (dark) setColorFilter(Color.parseColor("#5EEAD4"))
                    else setColorFilter(Color.BLACK)
                } catch (_: Exception) {}
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                layoutParams = pillIcon(32)
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@HomeActivity, AppLogActivity::class.java))
                }
            })
        // 1.31: the lock is back, third in the pill — pre-1.29
        // behaviour: tapping it closes the app (finish).
        bottomPill.addView(android.widget.ImageView(this).apply {
            val bmp = padlockBitmap((22 * resources.displayMetrics.density).toInt())
            if (bmp != null) setImageBitmap(bmp)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            layoutParams = pillIcon(32)
            isClickable = true
            setOnClickListener { finish() }
        })
        barRow.addView(bottomPill)
        bottomBar.addView(barRow)
        // 2.18: lift the bar above the phone's own
        // navigation buttons (back/home/recents) by
        // the system nav inset, so it never hides
        // behind them. The insets are passed on, so
        // the scroll keeps its own behaviour.
        bottomBar.setOnApplyWindowInsetsListener { _, insets ->
            barRow.setPadding(dpPx(16), dpPx(16), dpPx(16), dpPx(16) + insets.systemWindowInsetBottom)
            insets
        }
        // 2.89: ONE shared background for the whole page — the same
        // pageBackground the window gets from applyPage — so the strip
        // behind the bottom bar can no longer read as a separate band.
        // (Night is the shared aurora, slightly darker than the old
        // AuroraNightDrawable; day is the same pearl.)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.pageBackground(this@HomeActivity)
        }
        outer.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        outer.addView(bottomBar)
        setContentView(outer)
        // 1.75 (worker A): the update auto-check is NOT called
        // here any more — it runs from onResume below, on EVERY
        // entry (cold start, since onCreate is always followed by
        // onResume, AND every return to the foreground, which
        // onCreate never sees). Calling it here as well would
        // stack a second fetch behind onResume's.
    }

    // reorder saved on the sort page is visible at
    // once instead of only after a full recreate.
    override fun onResume() {
        super.onResume()
        // 2.82: the night switch in Settings only recreates the settings
        // page — if the mode changed while Home was behind it, rebuild
        // Home in the new mode instead of showing the stale one.
        if (::gridContainer.isInitialized && isDarkMode(this) != gridDark) {
            recreate()
            return
        }
        if (::gridContainer.isInitialized) {
            val orderNow = try {
                getSharedPreferences("renamer_settings", MODE_PRIVATE).getString("home_order", "") ?: ""
            } catch (_: Exception) { "" }
            rebuildHomeGrid()
            if (homeGridBuilt && orderNow != lastHomeOrder) {
                try {
                                    } catch (_: Exception) {}
            }
            lastHomeOrder = orderNow
            homeGridBuilt = true
        }
        // 2.80: the card reflects the live connection state.
        try { quickCard?.refresh(this) } catch (_: Exception) {}
        try { refreshTileBorders() } catch (_: Exception) {}
        quickHandler.removeCallbacks(quickPoller)
        quickHandler.post(quickPoller)
        // 1.75 (worker A): the update auto-check on EVERY entry —
        // cold start (onCreate is always followed by onResume) AND
        // every return to the foreground. The 1.73 once-per-launch
        // latch meant a check that ran before a release existed
        // (or failed offline, silently) never ran again until the
        // process died, so the box never appeared on re-entry.
        // Silent when there is nothing to show (latest / error /
        // dismissed-this-release); when a newer release exists the
        // box appears in updateBoxSlot with [دانلود] [بعدا].
        // (After a recreate() above, this new instance's onResume
        // runs the check — the early return is safe.)
        try { UpdateUi.autoCheckOnEntry(this, updateBoxSlot) } catch (_: Exception) {}
    }

    override fun onPause() {
        quickHandler.removeCallbacks(quickPoller)
        super.onPause()
    }

    /** 2.80: the quick-connect card's tap. While a connect is in progress
     *  the tap is ignored; while connected it disconnects; otherwise it
     *  connects to the last method (or Emergency when none was ever used). */
    private fun onQuickConnectTap() {
        val st = try { QuickConnect.state(this) } catch (_: Exception) { QuickConnect.STATE_DISCONNECTED }
        if (st == QuickConnect.STATE_CONNECTING) return
        if (st == QuickConnect.STATE_CONNECTED) {
            try { QuickConnect.disconnect(this) } catch (_: Exception) {}
        } else {
            try { QuickConnect.connect(this) } catch (_: Exception) {}
        }
        try { quickCard?.refresh(this) } catch (_: Exception) {}
        try { refreshTileBorders() } catch (_: Exception) {}
    }

    /** 2.79: one-time move of the Smart tile to the rightmost slot of row 1
     *  (the user's order). The home grid is RTL on device (Persian), so
     *  list index 0 IS the rightmost slot — at 3 or 5 columns alike.
     *  (An earlier draft used index cols-1; that is the LEFT corner in
     *  RTL — caught by reading the user's screenshots before delivery.)
     *  Runs once; the Sort page stays fully functional afterwards. */
    private fun migrateSmartToRow1Right() {
        try {
            val sp = getSharedPreferences("renamer_settings", MODE_PRIVATE)
            if (sp.getBoolean("smart_row1_right_279", false)) return
            val raw = sp.getString("home_order", "") ?: ""
            val keys = mutableListOf<String>()
            if (raw.isNotBlank()) {
                try {
                    val a = org.json.JSONArray(raw)
                    for (i in 0 until a.length()) {
                        val k = a.optString(i)
                        if (k.isNotBlank() && k !in keys) keys += k
                    }
                } catch (_: Exception) {}
            }
            for (k in ReorderActivity.DEFAULT_ORDER) if (k !in keys) keys += k
            keys.remove("smart")
            keys.add(0, "smart")
            val out = org.json.JSONArray()
            for (k in keys) out.put(k)
            sp.edit().putString("home_order", out.toString())
                .putBoolean("smart_row1_right_279", true).apply()
        } catch (_: Exception) {}
    }

    /** The home grid, extracted from onCreate unchanged:
     *  same items, same saved order, same three-per-row
     *  layout, same icons and labels. Only the target
     *  container moved from the page root into
     *  gridContainer so onResume can rebuild it. */
    private fun rebuildHomeGrid() {
        val items = mutableListOf<MenuItem>()
        // Public sections: v2ray, WireGuard, OpenVPN and emergency connect.
        // 2.79: Smart is FIRST in the list — the home grid is RTL on device
        // (Persian), so list index 0 is the rightmost slot of row 1, near
        // the right corner (the user's order). A one-time migration moves
        // it there in any saved order too (see migrateSmartToRow1Right).
        items += MenuItem("smart", AppLanguage.t(this, "اتصال هوشمند", "Smart Connect"), "smart.png", SmartConnectActivity::class.java)
        items += MenuItem("v2ray", "v2ray", "vpn.png", VpnActivity::class.java)
        items += MenuItem("openvpn", "OpenVPN", "ovpn.png", OpenVpnActivity::class.java)
        items += MenuItem("emergency", AppLanguage.t(this, "اتصال اضطراری", "Emergency connect"), "emergency.png", EmergencyActivity::class.java)
        items += MenuItem("cloudflare", AppLanguage.t(this, "کلودفلر", "Cloudflare"), "cf.png", CloudflareActivity::class.java)
        // 1.29: WireGuard & Amnezia is a home section now, exactly like
        // the main app (config list, add/import, connect). It sits after
        // the existing sections. The old "subs" tile is gone (1.29): the
        // Subscriptions & Ping Test section is removed; v2ray's OWN
        // subscription features (its ⋮ menu) stay untouched.
        items += MenuItem("wireguard", AppLanguage.t(this, "وایرگارد و امنزیا", "WireGuard & Amnezia"), "wg.png", WireGuardActivity::class.java)
        // 1.73 (worker A): the «تست سرعت» section — a 7th tile, same
        // tile style as the other six. It has no Activity target:
        // tapping it opens the small floating speed-test page
        // (SpeedTestPanel) over Home. It sits last in the list, so
        // with 3 columns it is row 3 alone, rightmost in RTL.
        items += MenuItem("speedtest", AppLanguage.t(this, "تست سرعت", "Speed test"), "speedtest.png", null)
        // Apply the user's saved order from Settings > Sort apps.
        try {
            val orderRaw = getSharedPreferences("renamer_settings", MODE_PRIVATE).getString("home_order", "") ?: ""
            if (orderRaw.isNotBlank()) {
                val order = org.json.JSONArray(orderRaw).let { a -> (0 until a.length()).map { a.optString(it) } }
                val rank = order.withIndex().associate { it.value to it.index }
                items.sortWith(compareBy { rank[it.key] ?: Int.MAX_VALUE })
            }
        } catch (_: Exception) {}
        // Grid: tiles per row and tile size come
        // from the Sort page's layout settings
        // (HomeLayout, default 3 per row, 100%).
        // 2.78: no row dividers — the glass cards define the grid.
        // Icons and labels are smaller than the old
        // two-column grid so cells fit a phone
        // width; long names wrap to a second line
        // instead of clipping, never more.
        val dp = resources.displayMetrics.density
        val cols = HomeLayout.cols(this)
        val scale = HomeLayout.scale(this)
        val iconDp = HomeLayout.iconDp(cols, scale, HomeLayout.screenWidthDp(this))
        val labelSp = HomeLayout.textSp(cols, scale)
        gridContainer.removeAllViews()
        tileCells.clear()
        for (rowStart in items.indices step cols) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                // 2.79: explicit direction — same as the device locale
                // (RTL in Persian). Locks in the right-to-left grid the
                // user sees; without this the direction is left to
                // inheritance, which proved unreliable in ReorderActivity.
                layoutDirection = if (AppLanguage.isEnglish(this@HomeActivity))
                    View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
            }
            for (col in 0 until cols) {
                val idx = rowStart + col
                // 2.78 Aurora Luxe: each tile is a glass card — semi-transparent
                // white, thin light border, rounded corners, soft shadow. Same
                // icon, same label, same order; only the container changed.
                // 2.79: empty slots are INVISIBLE — plain empty space, no card,
                // no border, no outline.
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        val m = (8 * dp + 0.5f).toInt()
                        setMargins(m, m, m, m)
                    }
                }
                if (idx < items.size) {
                    cell.setPadding(8, 20, 8, 20)
                    // 2.82: one tile drawable for both modes — the 2.78 glass
                    // card in day, dark glass with a teal → purple gradient
                    // border in night. It also carries the green connected
                    // border (see refreshTileBorders).
                    cell.background = HomeIcons.TileBackgroundDrawable(
                        gridDark, (20 * dp + 0.5f), (1 * dp + 0.5f))
                    cell.elevation = 4f
                    val item = items[idx]
                    if (item.iconAsset != null) {
                        try {
                            // 2.83: the new coordinated set as REAL
                            // image assets (HomeIcons, assets/home_*.png
                            // in night, home_*_day.png flat in day).
                            // The 2.82 code-drawn paths are gone — they
                            // came out crude on device.
                            // 2.81: normalize the icon so its CONTENT
                            // fills the same box on every tile.
                            val bmp = HomeLayout.normalizeIcon(
                                HomeIcons.iconBitmap(this, item.key, (iconDp * dp).toInt(), gridDark),
                                (iconDp * dp).toInt())
                            cell.addView(ImageView(this).apply {
                                setImageBitmap(bmp)
                                layoutParams = LinearLayout.LayoutParams(
                                    (iconDp * dp).toInt(), (iconDp * dp).toInt()
                                )
                            })
                        } catch (_: Exception) {}
                    }
                    cell.addView(TextView(this).apply {
                        text = item.name; textSize = labelSp; gravity = Gravity.CENTER
                        setTextColor(gridFg)
                        // 2.79: the label always takes two lines of height, so
                        // one-line tiles are exactly as tall as two-line ones.
                        minLines = 2
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, 12, 0, 0)
                    })
                    cell.setOnClickListener {
                        // 1.73: the speed-test tile opens the floating
                        // panel (no Activity); every other tile opens
                        // its section as before.
                        if (item.key == "speedtest") {
                            try { SpeedTestPanel.show(this@HomeActivity) } catch (_: Exception) {}
                        } else if (item.target != null) {
                            startActivity(Intent(this@HomeActivity, item.target))
                        }
                    }
                    // 2.81: remember the cell for the live green border.
                    tileCells[item.key] = cell
                } else {
                    cell.visibility = View.INVISIBLE
                }
                row.addView(cell)
            }
            gridContainer.addView(row)
        }
        // 2.81: paint the connected tile's border right away (the
        // poller keeps it live afterwards).
        refreshTileBorders()
    }

    /** 2.81 (fix 6): the tile of the currently-connected method gets a
     *  GREEN border — exactly the card button's green (shared constant
     *  QuickConnect.GREEN_HEX) — instead of its faint dark border.
     *  On disconnect every tile returns to the faint border. Only the
     *  five VPN methods can ever get it; the other tiles never do.
     *  The existing cell drawables are mutated in place (no rebuild),
     *  so this runs on the card's 1s tick and on every resume/tap. */
    private fun refreshTileBorders() {
        val live = try { QuickConnect.isLive(this) } catch (_: Exception) { false }
        val method = try { QuickConnect.lastMethod(this) } catch (_: Exception) { null }
        val dp = resources.displayMetrics.density
        val w = (1 * dp + 0.5f).toInt()
        val green = Color.parseColor(QuickConnect.GREEN_HEX)
        val faint = Color.parseColor("#22000000")
        val vpnKeys = setOf("smart", "v2ray", "wireguard", "openvpn", "emergency")
        for ((key, cell) in tileCells) {
            if (key !in vpnKeys) continue
            // 2.82: the tile background is now a TileBackgroundDrawable
            // (gradient border in night); the GradientDrawable branch is
            // kept only as a defensive fallback.
            // 1.74 (C): free warp lives in the WireGuard
            // page, so a live warp connection greens the
            // wireguard tile (method "warp" has no tile
            // of its own). Every other method keeps its
            // own tile exactly as before.
            val connected = live && (method == key ||
                (key == "wireguard" && method == QuickConnect.M_WARP))
            when (val bg = cell.background) {
                is HomeIcons.TileBackgroundDrawable -> bg.setConnected(connected)
                is android.graphics.drawable.GradientDrawable ->
                    bg.setStroke(w, if (connected) green else faint)
            }
        }
    }
}

/** Aurora Luxe pearl background (2.78): drawn entirely in code, no image asset.
 *  Pearl-white base, soft lavender / mint / sky radial washes, a few tiny
 *  sparkles — the approved mockup's background, reproduced as gradients. */
// 2.79: internal (not private) so the Home settings preview in
// ReorderActivity can draw the exact same pearl background.
internal class AuroraPearlDrawable : android.graphics.drawable.Drawable() {
    private val basePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#FAF9F6")
    }
    private val blobPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val sparklePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        alpha = 150
    }
    // Deterministic sparkles: x, y as fractions of the bounds, radius in px.
    private val sparkles = arrayOf(
        floatArrayOf(0.10f, 0.06f, 2.2f), floatArrayOf(0.86f, 0.04f, 1.8f),
        floatArrayOf(0.70f, 0.22f, 1.5f), floatArrayOf(0.30f, 0.30f, 1.8f),
        floatArrayOf(0.95f, 0.42f, 2.0f), floatArrayOf(0.06f, 0.62f, 1.6f),
        floatArrayOf(0.55f, 0.70f, 1.8f), floatArrayOf(0.90f, 0.78f, 2.2f),
        floatArrayOf(0.22f, 0.86f, 1.5f), floatArrayOf(0.45f, 0.12f, 1.4f)
    )

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        canvas.drawRect(b, basePaint)
        // Soft washes: a translucent pastel centre fading out to clear.
        drawBlob(canvas, b, 0.12f, 0.08f, 0.55f, "#B5E4DEF2")
        drawBlob(canvas, b, 0.92f, 0.05f, 0.48f, "#B5D6EEDF")
        drawBlob(canvas, b, 0.04f, 0.45f, 0.50f, "#B5D9E8F6")
        drawBlob(canvas, b, 0.96f, 0.55f, 0.50f, "#B5F0DFEC")
        drawBlob(canvas, b, 0.28f, 0.94f, 0.55f, "#B5E2E4F5")
        drawBlob(canvas, b, 0.82f, 0.96f, 0.50f, "#B5D7EFE6")
        for (s in sparkles) {
            canvas.drawCircle(b.left + s[0] * b.width(), b.top + s[1] * b.height(), s[2], sparklePaint)
        }
    }

    private fun drawBlob(
        canvas: android.graphics.Canvas,
        b: android.graphics.Rect,
        cx: Float, cy: Float, rFrac: Float, colorHex: String
    ) {
        val cxPx = b.left + cx * b.width()
        val cyPx = b.top + cy * b.height()
        val r = rFrac * maxOf(b.width(), b.height())
        blobPaint.shader = android.graphics.RadialGradient(
            cxPx, cyPx, r,
            intArrayOf(android.graphics.Color.parseColor(colorHex), android.graphics.Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cxPx, cyPx, r, blobPaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}

/** Aurora night background (2.82): the picked «حرفه‌ای ۲» design, drawn
 *  entirely in code like the pearl one — dark navy base, soft purple /
 *  teal / pink aurora washes, a few tiny sparkles. Used only when the
 *  night-mode setting is ON; the pearl drawable stays the day one. */
internal class AuroraNightDrawable : android.graphics.drawable.Drawable() {
    // 1.73 (home-night gap): base ×0.7 like the other night pages.
    private val basePaint = android.graphics.Paint().apply {
        color = Theme.darken(android.graphics.Color.parseColor("#0B0E1A"))
    }
    private val blobPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val sparklePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        alpha = 170
    }
    // Same deterministic sparkle layout as the pearl background.
    private val sparkles = arrayOf(
        floatArrayOf(0.10f, 0.06f, 2.2f), floatArrayOf(0.86f, 0.04f, 1.8f),
        floatArrayOf(0.70f, 0.22f, 1.5f), floatArrayOf(0.30f, 0.30f, 1.8f),
        floatArrayOf(0.95f, 0.42f, 2.0f), floatArrayOf(0.06f, 0.62f, 1.6f),
        floatArrayOf(0.55f, 0.70f, 1.8f), floatArrayOf(0.90f, 0.78f, 2.2f),
        floatArrayOf(0.22f, 0.86f, 1.5f), floatArrayOf(0.45f, 0.12f, 1.4f)
    )

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        canvas.drawRect(b, basePaint)
        // Soft aurora washes: translucent violet / teal / pink, fading out.
        drawBlob(canvas, b, 0.12f, 0.08f, 0.55f, "#597C3AED")
        drawBlob(canvas, b, 0.92f, 0.05f, 0.48f, "#5914B8A6")
        drawBlob(canvas, b, 0.04f, 0.45f, 0.50f, "#591D4ED8")
        drawBlob(canvas, b, 0.96f, 0.55f, 0.50f, "#59EC4899")
        drawBlob(canvas, b, 0.28f, 0.94f, 0.55f, "#596D28D9")
        drawBlob(canvas, b, 0.82f, 0.96f, 0.50f, "#590D9488")
        for (s in sparkles) {
            canvas.drawCircle(b.left + s[0] * b.width(), b.top + s[1] * b.height(), s[2], sparklePaint)
        }
    }

    private fun drawBlob(
        canvas: android.graphics.Canvas,
        b: android.graphics.Rect,
        cx: Float, cy: Float, rFrac: Float, colorHex: String
    ) {
        val cxPx = b.left + cx * b.width()
        val cyPx = b.top + cy * b.height()
        val r = rFrac * maxOf(b.width(), b.height())
        blobPaint.shader = android.graphics.RadialGradient(
            cxPx, cyPx, r,
            // 1.73 (home-night gap): the aurora washes are darkened
            // ×0.7 like every other night page (Theme.darken).
            intArrayOf(Theme.darken(android.graphics.Color.parseColor(colorHex)), android.graphics.Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cxPx, cyPx, r, blobPaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}
