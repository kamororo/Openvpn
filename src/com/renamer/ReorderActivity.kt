package com.renamer

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray

/** Reorder the Home (apps) page sections — laid out
 *  like Home, with the layout itself adjustable
 *  here: tiles per row (2–5) and a size slider,
 *  both previewed live in this very grid and
 *  applied to Home on Save. A tile is tapped to
 *  select it (green glass ring), then the
 *  ‹ قبلی / بعدی › glass buttons move it, or it
 *  is long-pressed and dragged onto another tile.
 *  Order AND layout are kept in memory until Save
 *  is tapped, and only then written to prefs
 *  "renamer_settings" (keys "home_order" plus
 *  HomeLayout's cols/scale). Back discards
 *  unsaved changes. The Home page itself reads
 *  the same prefs. */
class ReorderActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS_NAME = "renamer_settings"
        const val KEY_ORDER = "home_order"
        // 2.79: Smart sits at the rightmost slot of row 1 (index 2 at the
        // default 3 columns), matching Home's new default order.
        // 1.29: "subs" is gone (section removed); "wireguard" joins
        // after the existing sections. loadOrder() drops any stale
        // saved "subs" key automatically (it filters by this list).
        // 1.31: "renamer" is gone with its section.
        // 1.73: "speedtest" joins last (row 3 alone, rightmost in RTL).
        val DEFAULT_ORDER = listOf("smart", "v2ray", "openvpn", "emergency", "cloudflare", "wireguard", "speedtest")
    }

    private fun sectionName(key: String): String = when (key) {
        "v2ray" -> "v2ray"
        "wireguard" -> L("وایرگارد و امنزیا", "WireGuard & Amnezia")
        "openvpn" -> L("اوپن‌وی‌پی‌ان", "OpenVPN")
        "emergency" -> L("اتصال اضطراری", "Emergency")
        "smart" -> L("اتصال هوشمند", "Smart Connect")
        "cloudflare" -> L("کلودفلر", "Cloudflare")
        "speedtest" -> L("تست سرعت", "Speed test")
        // through to `else` and show the raw key.
        else -> key
    }

    private val order = mutableListOf<String>()
    private lateinit var gridContainer: LinearLayout
    private lateinit var moveHint: TextView
    private var selected: String? = null

    // 2.82: the preview follows the same day/night mode as Home.
    private var previewDark = false

    // Layout draft (unsaved until Save): tiles per
    // row + tile size. The grid below IS the
    // preview — every change rebuilds it at once,
    // so what is seen here is what Home shows.
    private var layoutCols = 3
    private var layoutScale = 0.7f
    private lateinit var layoutValueText: TextView
    private lateinit var colsBox: TextView

    private fun layoutValueLabel(): String {
        val pct = (layoutScale * 100).toInt()
        return L("$layoutCols در ردیف • $pct٪", "$layoutCols per row • $pct%")
    }

    private fun refreshLayoutControls() {
        if (!::layoutValueText.isInitialized) return
        layoutValueText.text = layoutValueLabel()
        if (::colsBox.isInitialized) colsBox.text = "$layoutCols ▾"
    }

    /** Small popup (anchored, never fullscreen)
     *  with the per-row counts 2–5. */
    private fun showColsPopup(anchor: View) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 2.84: shared dialog card (dark glass in night).
            background = Theme.dialogCard(this@ReorderActivity)
            elevation = 8f
        }
        for (n in 2..5) {
            list.addView(TextView(this).apply {
                text = n.toString()
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(7), dp(18), dp(7))
                setTextColor(if (n == layoutCols) (if (Theme.isDark(this@ReorderActivity)) Color.parseColor("#5EEAD4") else Color.parseColor("#FF00695C")) else (if (Theme.isDark(this@ReorderActivity)) Color.parseColor("#EDEFF5") else Color.parseColor("#FF1A237E")))
                if (n == layoutCols) setTypeface(null, android.graphics.Typeface.BOLD)
                setOnClickListener {
                    layoutCols = n
                    rebuildGrid()
                    refreshLayoutControls()
                    colsPopup?.dismiss()
                }
            })
            if (n < 5) list.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(Theme.divider(this@ReorderActivity))
            })
        }
        colsPopup = PopupWindow(list,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            showAsDropDown(anchor)
        }
    }

    private var colsPopup: PopupWindow? = null

    // 2.83: the same real icon assets as Home (HomeIcons), cached per
    // key + size + mode — the preview shows exactly what Home shows.
    private val iconCache = mutableMapOf<String, android.graphics.Bitmap?>()
    private fun iconFor(key: String, sizePx: Int): android.graphics.Bitmap? {
        val cacheKey = "$key|$sizePx|$previewDark"
        if (!iconCache.containsKey(cacheKey)) {
            iconCache[cacheKey] = HomeIcons.iconBitmap(this, key, sizePx, previewDark)
        }
        return iconCache[cacheKey]
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun loadOrder(): MutableList<String> {
        val result = mutableListOf<String>()
        try {
            val raw = prefs().getString(KEY_ORDER, null)
            if (!raw.isNullOrBlank()) {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val k = arr.optString(i)
                    if (k in DEFAULT_ORDER && k !in result) result += k
                }
            }
        } catch (_: Exception) {}
        // Any section missing from a stored order (e.g. a
        // section added later) goes at the end, so nothing
        // ever disappears from the Home page.
        for (k in DEFAULT_ORDER) if (k !in result) result += k
        return result
    }

    private fun saveOrder() {
        val arr = JSONArray()
        for (k in order) arr.put(k)
        prefs().edit().putString(KEY_ORDER, arr.toString()).apply()
    }

    /** Glass tile card: white normally (dark tile in
     *  dark mode, so the theme-coloured label stays
     *  readable), soft green when selected. The drag
     *  highlight is a third state, restored from these
     *  two on drag exit. The selected tile is unchanged. */
    /** 2.79: the preview shows the NEW home model — glass rounded cards on
     *  the pearl aurora, exactly like Home (always pearl since 2.78).
     *  2.82: Home now follows the night-mode setting, so the preview
     *  does too — dark glass tiles on the dark aurora in night mode.
     *  Selected stays soft green. The drag highlight is a third state,
     *  restored from these two on drag exit. */
    private fun tileBg(selected: Boolean): android.graphics.drawable.Drawable =
        HomeIcons.TileBackgroundDrawable(previewDark, dp(20).toFloat(), dp(1).toFloat()).apply {
            setSelected(selected)
        }

    private fun dragBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(20).toFloat()
        setColor(if (previewDark) Theme.darken(Color.parseColor("#CC1E3A4C")) else Color.parseColor("#E3F2FD"))
        setStroke(dp(1), if (previewDark) Color.parseColor("#5EEAD4") else Color.parseColor("#90CAF9"))
    }

    private fun updateMoveHint() {
        val key = selected
        moveHint.text = if (key == null) {
            L("روی یک برنامه بزن تا انتخاب شود.", "Tap an app to select it.")
        } else {
            L("انتخاب شده: ", "Selected: ") + sectionName(key)
        }
    }

    private fun moveSelected(delta: Int) {
        val key = selected ?: run {
            Toast.makeText(this, L("اول یک برنامه را بزن تا انتخاب شود.", "Tap an app first to select it."), Toast.LENGTH_SHORT).show()
            return
        }
        val i = order.indexOf(key)
        val j = i + delta
        if (i < 0 || j !in order.indices) return
        order.removeAt(i)
        order.add(j, key)
        rebuildGrid()
    }

    private fun rebuildGrid() {
        val iconDp = HomeLayout.iconDp(layoutCols, layoutScale, HomeLayout.screenWidthDp(this))
        val labelSp = HomeLayout.textSp(layoutCols, layoutScale)
        gridContainer.removeAllViews()
        for (rowStart in order.indices step layoutCols) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                // 2.79: same direction as Home — Home's rows are RTL on
                // device (Persian), so without this the preview mirrors
                // the real home (1st tile on the left instead of the
                // right). English stays LTR.
                layoutDirection = if (AppLanguage.isEnglish(this@ReorderActivity))
                    View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
            }
            for (col in 0 until layoutCols) {
                val idx = rowStart + col
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    // 2.79: same geometry as a real Home tile — same padding,
                    // same 8dp margins, same elevation — so the preview shows
                    // exactly what Home will look like.
                    setPadding(8, 20, 8, 20)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        val m = dp(8)
                        setMargins(m, m, m, m)
                    }
                }
                if (idx < order.size) {
                    val key = order[idx]
                    val isSel = key == selected
                    cell.background = tileBg(isSel)
                    cell.elevation = if (isSel) 6f else 4f
                    cell.addView(TextView(this).apply {
                        text = (idx + 1).toString(); textSize = 11f
                        setTextColor(if (isSel) Color.parseColor("#2E7D32")
                            else if (previewDark) Color.parseColor("#8A8FA3")
                            else Color.parseColor("#9A9AA5"))
                        gravity = Gravity.CENTER
                    })
                    val bmp = iconFor(key, dp(iconDp))
                    if (bmp != null) {
                        cell.addView(ImageView(this).apply {
                            // 2.81: same icon normalization as Home,
                            // so the preview shows exactly what Home
                            // will show (same content box per tile).
                            setImageBitmap(HomeLayout.normalizeIcon(bmp, dp(iconDp)))
                            layoutParams = LinearLayout.LayoutParams(dp(iconDp), dp(iconDp))
                        })
                    }
                    cell.addView(TextView(this).apply {
                        text = sectionName(key); textSize = labelSp; gravity = Gravity.CENTER
                        setTextColor(if (previewDark) Color.parseColor("#F2F3FA") else Color.parseColor("#23232E"))
                        // 2.79: the label always takes two lines of height,
                        // like Home — equal tile heights in the preview too.
                        minLines = 2
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, 12, 0, 0)
                    })
                    cell.setOnClickListener {
                        selected = key
                        rebuildGrid()
                        updateMoveHint()
                    }
                    // Long-press and drag this tile onto
                    // another tile to move it there. The
                    // drag carries the source index; tiles
                    // only highlight during the drag and
                    // the grid is rebuilt once, on drop,
                    // so the drag is never interrupted
                    // by a rebuild.
                    cell.setOnLongClickListener { v ->
                        val data = android.content.ClipData.newPlainText("index", idx.toString())
                        v.startDragAndDrop(data, View.DragShadowBuilder(v), null, 0)
                        true
                    }
                    cell.setOnDragListener { v, event ->
                        when (event.action) {
                            android.view.DragEvent.ACTION_DRAG_STARTED -> true
                            android.view.DragEvent.ACTION_DRAG_ENTERED -> {
                                v.background = dragBg()
                                true
                            }
                            android.view.DragEvent.ACTION_DRAG_EXITED -> {
                                v.background = tileBg(isSel)
                                true
                            }
                            android.view.DragEvent.ACTION_DROP -> {
                                val from = try {
                                    event.clipData.getItemAt(0).text.toString().toInt()
                                } catch (_: Exception) { -1 }
                                if (from in order.indices && from != idx) {
                                    val item = order.removeAt(from)
                                    // Insert at the target tile's
                                    // position, so the dragged item
                                    // ends up exactly where it was
                                    // dropped, in both directions.
                                    order.add(idx.coerceAtMost(order.size), item)
                                    rebuildGrid()
                                } else {
                                    v.background = tileBg(isSel)
                                }
                                true
                            }
                            android.view.DragEvent.ACTION_DRAG_ENDED -> {
                                v.background = tileBg(isSel)
                                true
                            }
                            else -> true
                        }
                    }
                } else {
                    // Empty cell keeps the last row aligned.
                    cell.visibility = View.INVISIBLE
                }
                row.addView(cell)
            }
            // 2.79: no row dividers — Home has none; the glass cards define
            // the grid.
            gridContainer.addView(row)
        }
    }

    /** 2.95: the app's neon button (was a glass pill). */
    private fun glassPill(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 15f
        isAllCaps = false
        minimumWidth = 0
        minimumHeight = 0
        setTextColor(Theme.neonTextColor(context))
        background = Theme.neonRect(context, 20f)
        elevation = 2f
        setPadding(dp(20), dp(10), dp(20), dp(10))
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2.84: shared background + no white window strips.
        try { Theme.applyPage(this) } catch (_: Exception) {}
        order.clear()
        order.addAll(loadOrder())
        layoutCols = HomeLayout.cols(this)
        layoutScale = HomeLayout.scale(this)
        previewDark = HomeActivity.isDarkMode(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 2.96: the side inset lives in the children's
            // MARGINS now, not in this root's padding. The
            // 2.95 fix (root padding 16→20dp) never reached
            // the device: root had fitsSystemWindows=true,
            // and the framework re-applies the system window
            // insets as the view's padding — the side insets
            // are 0, so the 20dp side padding was wiped and
            // the full-width cards sat flush to the edges.
            // fitsSystemWindows is gone; only the bottom nav
            // inset is added back, by an explicit listener
            // that keeps the 20dp top padding intact.
            setPadding(0, dp(20), 0, dp(20))
            // 2.84: one shared background — the home night aurora
            // (slightly darker) in night, the pearl day home in day.
            background = Theme.pageBackground(this@ReorderActivity)
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            root.setPadding(0, dp(20), 0, dp(20) + insets.systemWindowInsetBottom)
            insets
        }
        root.addView(TextView(this).apply {
            text = L("تنظیمات صفحه اصلی", "Home settings"); textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.fg(this@ReorderActivity))
            setPadding(0, 0, 0, dp(12))
            // 2.96: the title's side inset is a margin now
            // (see the root note above).
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(20); rightMargin = dp(20) }
        })

        // Layout card (glass): tiles per row via a
        // small dropdown box (default 3) + a size
        // slider. Every change rebuilds the grid
        // below at once, so the grid IS the preview
        // — nothing is saved until Save. In Persian
        // the card reads right-to-left, so labels sit
        // on the right as they should.
        val layoutCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 2.84: the white card becomes dark glass in night
            // (the user's screenshot: a white card on the dark page).
            // 2.95: shared glass, like the other pages.
            background = Theme.glassCard(this@ReorderActivity, 18f)
            elevation = 3f
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                // 2.96: explicit 20dp side margins — this card
                // is full-width, so margins (not the root's
                // padding, which fitsSystemWindows wiped) are
                // what keep it off the screen edges.
                leftMargin = dp(20); rightMargin = dp(20); bottomMargin = dp(16) }
            layoutDirection = if (AppLanguage.isEnglish(this@ReorderActivity))
                View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
        }
        layoutCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 2.59: explicit row direction — the
            // card's RTL did not reach the rows
            // on device (00:34 screenshot: the
            // labels stayed on the left).
            layoutDirection = if (AppLanguage.isEnglish(this@ReorderActivity))
                View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
            addView(TextView(this@ReorderActivity).apply {
                text = L("چیدمان صفحه اصلی", "Home layout")
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.fg(this@ReorderActivity))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            layoutValueText = TextView(this@ReorderActivity).apply {
                textSize = 13f
                setTextColor(Theme.subFg(this@ReorderActivity))
            }
            addView(layoutValueText)
        })
        layoutCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))
            // 2.59: explicit row direction (see
            // header row note above).
            layoutDirection = if (AppLanguage.isEnglish(this@ReorderActivity))
                View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
            addView(TextView(this@ReorderActivity).apply {
                text = L("در هر ردیف:", "Per row:")
                textSize = 14f
                setTextColor(Theme.subFg(this@ReorderActivity))
                setPaddingRelative(0, 0, dp(10), 0)
            })
            colsBox = TextView(this@ReorderActivity).apply {
                text = "$layoutCols ▾"
                // 2.59: a TextView pill, not a
                // Button — Button's own default
                // metrics kept inflating this box
                // far past text+padding on device
                // (the 00:34 screenshot). Sized to
                // ~60% of the old on-screen box:
                // 11sp, 10/4dp padding, 8dp corners.
                textSize = 11f
                gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#FF00695C"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.parseColor("#FFE0F2F1"))
                    setStroke(dp(1), Color.parseColor("#FF80CBC4"))
                }
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setOnClickListener { showColsPopup(this) }
            }
            addView(colsBox)
        })
        layoutCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 2.59: explicit row direction (see
            // header row note above).
            layoutDirection = if (AppLanguage.isEnglish(this@ReorderActivity))
                View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
            addView(TextView(this@ReorderActivity).apply {
                text = L("اندازه:", "Size:")
                textSize = 13f
                setTextColor(Theme.subFg(this@ReorderActivity))
                setPaddingRelative(0, 0, dp(8), 0)
            })
            addView(android.widget.SeekBar(this@ReorderActivity).apply {
                max = 70
                progress = ((layoutScale * 100).toInt() - 70).coerceIn(0, 70)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                        layoutScale = (70 + p) / 100f
                        rebuildGrid()
                        refreshLayoutControls()
                    }
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
                })
            })
        })
        layoutCard.addView(TextView(this).apply {
            text = L("چیدمان انتخابی روی صفحه اصلی هم اعمال می‌شود؛ تا «ذخیره» نزنی ثبت نمی‌شود.",
                "The chosen layout applies to Home too; it is saved only when you tap Save.")
            textSize = 12f
            setTextColor(Theme.subFg(this@ReorderActivity))
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(layoutCard)
        refreshLayoutControls()

        // Scrollable grid; the controls live below it,
        // outside the scroll, so they are always visible.
        // 2.79: the preview scrolls on the pearl aurora,
        // exactly like Home — glass cards on the same
        // drawn background.
        // 2.82: the dark aurora in night mode, like Home.
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            background = if (previewDark) AuroraNightDrawable() else AuroraPearlDrawable()
        }
        gridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(gridContainer)
        root.addView(scroll)
        rebuildGrid()
        try {
                    } catch (_: Exception) {}

        // Move controls: two neon pills + the selection line.
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 2.95: shared glass (was hard-coded white).
            background = Theme.glassCard(this@ReorderActivity, 16f)
            elevation = 2f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                // 2.96: explicit 20dp side margins, same as
                // the layout card above — the old 0-margin
                // full-width bar touched both screen edges.
                leftMargin = dp(20); rightMargin = dp(20); topMargin = dp(12) }
        }
        controls.addView(glassPill(L("‹ قبلی", "‹ Previous")) { moveSelected(-1) })
        moveHint = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Theme.subFg(this@ReorderActivity))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(8), 0, dp(8), 0)
            }
        }
        controls.addView(moveHint)
        controls.addView(glassPill(L("بعدی ›", "Next ›")) { moveSelected(1) })
        root.addView(controls)
        updateMoveHint()

        // 2.46: smaller, centered pill (was a
        // full-width tall bar).
        val saveBtn = Button(this).apply {
            text = L("ذخیره", "Save")
            textSize = 15f
            isAllCaps = false
            setTypeface(null, android.graphics.Typeface.BOLD)
            // 2.95: the app's neon button (was a teal gradient).
            setTextColor(Theme.neonTextColor(this@ReorderActivity))
            background = Theme.neonRect(this@ReorderActivity, 24f)
            elevation = 4f
            setPadding(dp(32), dp(10), dp(32), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12); gravity = android.view.Gravity.CENTER_HORIZONTAL }
            setOnClickListener {
                saveOrder()
                HomeLayout.save(this@ReorderActivity, layoutCols, layoutScale)
                text = L("ذخیره شد ✓", "Saved ✓")
                Toast.makeText(this@ReorderActivity, L("ترتیب و چیدمان ذخیره شد", "Order and layout saved"), Toast.LENGTH_SHORT).show()
                // Return the button to its normal label after
                // a short delay, so a further reorder + save
                // still reads as a save.
                postDelayed({ text = L("ذخیره", "Save") }, 2000)
            }
        }
        root.addView(saveBtn)
        setContentView(root)
    }
}
