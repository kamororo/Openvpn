package com.renamer

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.SeekBar
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Widget Studio, "Home screen widgets" (2.34, tabs like
 *  MLM's studio since 2.36). Gated by design: until a section
 *  is picked in the first box, nothing else is shown. The
 *  section widgets get four tabs: template (MLM's eight
 *  presets), colour (accent/background swatches, opacity,
 *  corner, text scale), info (shown rows) and size (the
 *  widget to pin: small/medium/large). Icon and button type
 *  are single glass buttons that open dedicated customize
 *  pages with a full widget preview and a Save button. Every
 *  change on this page itself is saved to the draft at once,
 *  so the only action is "Build and add to home screen". */
class WidgetStudioActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun L(fa: String, en: String) = AppLanguage.t(this, fa, en)

    // 2.90: chrome text on the shared glass — light in night, dark in day.
    private fun rowFg(): Int =
        if (Theme.isDark(this)) Color.parseColor("#EDEFF5") else Color.parseColor("#333333")
    private fun rowSub(): Int =
        if (Theme.isDark(this)) Color.parseColor("#BBBBBB") else Color.GRAY
    // 2.90: selected pill stays solid teal #0F766E; unselected pill is shared glass.
    // 2.95: the selected pill is the app's neon button (night darker
    // teal fill + neon border; day Hydra texture + neon frame).
    private fun pillBackground(selected: Boolean): android.graphics.drawable.Drawable =
        if (selected) Theme.neonRect(this, 20f) else Theme.glassCard(this, 20f)
    private fun pillTextColor(selected: Boolean): Int =
        if (selected) Theme.neonTextColor(this)
        else if (Theme.isDark(this)) Color.parseColor("#EDEFF5") else Color.parseColor("#1A237E")

    private lateinit var root: LinearLayout
    private lateinit var previewBox: LinearLayout

    // Editor state (draft-backed; survives leaving and returning).
    private var section = ""
    private var style = "modern"
    // MLM template (section widgets): one of the eight presets.
    // Blank/legacy style path is kept for chat widgets.
    private var template = "glass"
    // Preview state tab: 0 off, 1 connecting, 2 on.
    // Preview-only: never saved to the widget.
    private var previewState = 0
    // Studio tab: template / color / info / size.
    private var studioTab = "template"
    // Widget size to pin: small / medium / large.
    private var widgetSize = "medium"
    private var showSpeeds = true
    private var showStatus = true
    private var showIcon = true
    private var showMethod = true
    private var showUsage = false
    private var showTime = false
    private var showChart = false
    // Design overrides (-1 = the preset's value).
    private var bgColor = -1
    private var opacity = -1
    private var corner = -1
    private var textScale = 100
    // 2.35 connection mode: "" = nothing picked (behaves as "last"),
    // "last" = the last config, "any" = whichever config connects.
    private var connMode = ""
    private var iconBg = Color.parseColor("#0F766E")
    private var btnShape = "ring"
    private var btnColor = Color.parseColor("#39E58C")
    // 2.95: gradient button colour id ("" = solid colour) and
    // card border thickness in dp (-1 = the painter's default).
    private var btnGradient = ""
    private var borderWidth = -1f
    private var titleText = ""

    // (it used to be 6; the rest could not be created or
    // configured through the studio at all).
    // 1.29: only widgets for sections THIS app has:
    // v2ray, WireGuard & Amnezia, OpenVPN, Emergency,
    // Smart Connect, Cloudflare, Monitoring — plus the
    // last-connection widget (the home quick-connect
    // card). NO subs, Chain, Hydra or Alien widgets.
    private val sections = listOf("v2ray", "wireguard", "openvpn",
        "emergency", "cloudflare", "lastconnect")
    private val styles = listOf(
        "modern", "neon", "galaxy",
        "gold", "ocean", "sunset", "forest", "midnight")
    // MLM's four button shapes; legacy keys are still
    // accepted from old drafts (they keep rendering).
    private val shapes = listOf("ring", "pill", "solid", "glow")
    private val legacyShapes = listOf("circle", "rounded", "diamond", "hexagon",
        "glowring", "wide")

    private fun sectionName(key: String): String = when (key) {
        "wireguard" -> L("وایرگارد و امنزیا", "WireGuard & Amnezia")
        "openvpn" -> "OpenVPN"
        "emergency" -> L("اتصال اضطراری", "Emergency")
        "v2ray" -> "v2ray"
        "monitoring" -> L("مانیتورینگ", "Monitoring")
        // wording as SectionWidgetProvider's own names).
        "smart" -> L("اتصال هوشمند", "Smart Connect")
        "cloudflare" -> L("کلودفلر", "Cloudflare")
        "lastconnect" -> L("ویجت آخرین اتصال", "Last connection widget")
        else -> L("انتخاب بخش…", "Choose a section…")
    }

    private fun styleName(key: String): String = when (key) {
        "modern" -> L("مدرن گرادیان", "Modern gradient")
        "glass" -> L("شیشه‌ای", "Glass")
        "minimal" -> L("مینیمال", "Minimal")
        "neon" -> L("نئون", "Neon")
        "galaxy" -> L("کهکشانی", "Galaxy")
        "gold" -> L("طلایی", "Gold")
        "ocean" -> L("اقیانوسی", "Ocean")
        "sunset" -> L("غروب", "Sunset")
        "forest" -> L("جنگلی", "Forest")
        "midnight" -> L("نیمه‌شب", "Midnight")
        else -> key
    }

    private fun templateName(key: String): String = when (key) {
        "glass" -> L("شیشه‌ای", "Glass")
        "minimal" -> L("مینیمال", "Minimal")
        "neon" -> L("نئونی", "Neon")
        "colorful" -> L("رنگارنگ", "Colorful")
        "compact" -> L("جمع‌وجور", "Compact")
        "dark_pro" -> L("تیرهٔ حرفه‌ای", "Dark Pro")
        "ios" -> L("سبک iOS", "iOS Style")
        "wave" -> L("موج", "Wave")
        "aurora" -> L("شیشه شفق", "Aurora glass")
        "neon_outline" -> L("قاب نئونی", "Neon outline")
        else -> key
    }

    private fun shapeName(key: String): String = when (key) {
        "ring" -> L("حلقه", "Ring")
        "pill" -> L("دکمه کشیده", "Pill")
        "solid" -> L("دایره پر", "Solid")
        "glow" -> L("حلقه درخشان", "Glow")
        "rounded" -> L("مربع گرد", "Rounded square")
        "diamond" -> L("لوزی", "Diamond")
        "hexagon" -> L("شش‌ضلعی", "Hexagon")
        "glowring" -> L("حلقه درخشان", "Glowing ring")
        "wide" -> L("دکمه کشیده", "Stretched button")
        else -> L("دایره", "Circle")
    }

    private fun defaultTitle(): String =
        if (section == "lastconnect") {
            // The live last method (the widget itself ignores a
            // stored title and shows the live line 1).
            try {
                val m = QuickConnect.lastMethod(this)
                if (m.isNullOrBlank()) QuickConnect.neverConnectedText(this)
                else QuickConnect.line1Text(this, m)
            } catch (_: Exception) { sectionName(section) }
        } else sectionName(section)

    private fun shownTitle(): String =
        if (titleText.isBlank()) defaultTitle() else titleText

    private fun saveDraft() {
        try {
            val o = org.json.JSONObject()
            o.put("section", section)
            o.put("style", style)
            o.put("template", template)
            o.put("showspeeds", showSpeeds)
            o.put("showstatus", showStatus)
            o.put("showicon", showIcon)
            o.put("showmethod", showMethod)
            o.put("showusage", showUsage)
            o.put("showtime", showTime)
            o.put("showchart", showChart)
            o.put("bgcolor", bgColor)
            o.put("opacity", opacity)
            o.put("corner", corner)
            o.put("textscale", textScale)
            o.put("size", widgetSize)
            o.put("connmode", connMode)
            o.put("iconbg", iconBg)
            o.put("btnshape", btnShape)
            o.put("btncolor", btnColor)
            o.put("btngradient", btnGradient)
            o.put("borderwidth", borderWidth.toDouble())
            o.put("title", titleText)
            WidgetStyle.setDraft(this, o.toString())
        } catch (_: Exception) {}
    }

    private fun loadDraft() {
        try {
            val raw = WidgetStyle.getDraft(this) ?: return
            val o = org.json.JSONObject(raw)
            val s = o.optString("section")
            if (s in sections) section = s
            val st = o.optString("style")
            if (st in styles) style = st
            val connm = o.optString("connmode")
            if (connm == "last" || connm == "any") connMode = connm
            val tpl = o.optString("template")
            if (tpl in WidgetStyle.PRESET_KEYS) template = tpl
            showSpeeds = o.optBoolean("showspeeds", true)
            showStatus = o.optBoolean("showstatus", true)
            showIcon = o.optBoolean("showicon", true)
            showMethod = o.optBoolean("showmethod", true)
            showUsage = o.optBoolean("showusage", false)
            showTime = o.optBoolean("showtime", false)
            showChart = o.optBoolean("showchart", false)
            bgColor = o.optInt("bgcolor", -1)
            opacity = o.optInt("opacity", -1)
            corner = o.optInt("corner", -1)
            textScale = o.optInt("textscale", 100)
            val sz = o.optString("size")
            if (sz in listOf("small", "medium", "large")) widgetSize = sz
            val ib = o.optInt("iconbg", -1)
            if (ib != -1) iconBg = ib
            val bs = o.optString("btnshape")
            if (bs in shapes || bs in legacyShapes) btnShape = bs
            val bc = o.optInt("btncolor", -1)
            if (bc != -1) btnColor = bc
            val bgd = o.optString("btngradient")
            if (bgd in listOf("teal_purple", "teal_blue", "pink_orange")) btnGradient = bgd
            val bw = o.optDouble("borderwidth", -1.0)
            if (bw in 0.0..4.0) borderWidth = bw.toFloat()
            titleText = o.optString("title")
        } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2.84: shared background + no white window strips.
        try { Theme.applyPage(this) } catch (_: Exception) {}
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            background = Theme.pageBackground(this@WidgetStudioActivity)
            isFillViewport = true
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        scroll.addView(root)
        setContentView(scroll)
        loadDraft()
        buildPage()
        try {
                    } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        // A customize page may have saved new icon/button values.
        loadDraft()
        buildPage()
    }

    private fun label(text: String) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            // 2.84: readable on the dark aurora too.
            setTextColor(if (Theme.isDark(this@WidgetStudioActivity)) Color.parseColor("#A5C8FF") else Color.parseColor("#1A237E"))
            // 2.90 RTL: Persian text starts from the right.
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, dp(16), 0, dp(6))
        })
    }

    private fun glassButton(text: String, onClick: () -> Unit) {
        root.addView(Button(this).apply {
            this.text = text
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            // 2.95: the app's neon button, like the other
            // sections (was shared glass).
            background = Theme.neonRect(this@WidgetStudioActivity, 22f)
            elevation = 2f
            textDirection = View.TEXT_DIRECTION_LOCALE
            setTextColor(Theme.neonTextColor(this@WidgetStudioActivity))
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener { onClick() }
        })
    }

    private fun glassRow(preview: View, title: String, sub: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 2.90: shared glass (was solid white). Row order stays
            // LTR (first child leftmost) per the 2.90 row-direction rule.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = Theme.glassCard(this@WidgetStudioActivity, 20f)
            elevation = 2f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
        preview.layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            setMargins(0, 0, dp(12), 0)
        }
        row.addView(preview)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowFg())
        })
        col.addView(TextView(this).apply {
            text = sub
            textSize = 12f
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowSub())
        })
        row.addView(col)
        row.addView(TextView(this).apply {
            text = "‹"
            textSize = 20f
            textDirection = View.TEXT_DIRECTION_LOCALE
            setTextColor(rowSub())
        })
        root.addView(row)
    }

    private class DialogRow(
        val icon: Bitmap?, val bgRes: Int?, val label: String, val selected: Boolean)

    /** Professional glass list dialog: white rounded card, a
     *  preview (icon or style swatch) per row, green check on
     *  the selected row. Replaces the plain system list. */
    private fun glassListDialog(title: String, rows: List<DialogRow>, onPick: (Int) -> Unit) {
        val dlg = Dialog(this)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 2.84: shared dialog card (dark glass in night).
            background = Theme.dialogCard(context)
            elevation = 8f
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (Theme.isDark(context)) Color.parseColor("#A5C8FF") else Color.parseColor("#1A237E"))
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })
        // 2.61: the section list (11+ rows) grew taller
        // than the screen and the dialog had no scroll,
        // so bottom rows were cut off and unreachable.
        // Rows now live in a ScrollView capped at 70%
        // of the screen height; short lists (like the
        // style picker) stay compact, because the cap
        // only binds when the estimate exceeds it.
        val rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((i, r) in rows.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 2.90: row order stays LTR (first child leftmost).
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                if (r.selected) background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(if (Theme.isDark(context)) Theme.darken(Color.parseColor("#1E3A24")) else Color.parseColor("#E8F5E9"))
                }
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true
                setOnClickListener {
                    dlg.dismiss()
                    onPick(i)
                    saveDraft()
                    buildPage()
                }
            }
            if (r.icon != null) {
                row.addView(ImageView(this).apply {
                    setImageBitmap(r.icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        setMargins(0, 0, dp(12), 0)
                    }
                })
            } else if (r.bgRes != null) {
                row.addView(View(this).apply {
                    setBackgroundResource(r.bgRes)
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(32)).apply {
                        setMargins(0, 0, dp(12), 0)
                    }
                })
            }
            row.addView(TextView(this).apply {
                text = r.label
                textSize = 15f
                textDirection = View.TEXT_DIRECTION_LOCALE
                gravity = Gravity.START
                setTextColor(rowFg())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (r.selected) row.addView(TextView(this).apply {
                text = "✓"
                textSize = 18f
                setTextColor(Color.parseColor("#2E9E5B"))
            })
            rowsBox.addView(row)
        }
        val screenH = resources.displayMetrics.heightPixels
        val estH = rows.size * dp(64) + dp(48)
        val capH = (screenH * 0.7).toInt()
        box.addView(ScrollView(this).apply {
            addView(rowsBox)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (estH > capH) capH else LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        dlg.setContentView(box)
        dlg.show()
        // 2.84: shared dialog card + readable text in night.
        Theme.styleDialog(dlg, this)
        dlg.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    /** A labelled slider card: the value label updates in
     *  place and the preview refreshes live (no page rebuild,
     *  so the drag is not interrupted). */
    private fun sliderRow(labelText: String, min: Int, max: Int, value: Int,
        onChange: (Int) -> Unit): LinearLayout {
        val valueView = TextView(this).apply {
            text = "$value"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (Theme.isDark(context)) Color.parseColor("#A5C8FF") else Color.parseColor("#1A237E"))
        }
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = (value - min).coerceIn(0, max - min)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = min + p
                    valueView.text = "$v"
                    onChange(v)
                    saveDraft()
                    refreshPreview()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 2.90: shared glass (was solid white).
            background = Theme.glassCard(this@WidgetStudioActivity, 20f)
            elevation = 2f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            }
            addView(LinearLayout(this@WidgetStudioActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(this@WidgetStudioActivity).apply {
                    text = labelText
                    textSize = 15f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    textDirection = View.TEXT_DIRECTION_LOCALE
                    gravity = Gravity.START
                    setTextColor(rowFg())
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(valueView)
            })
            addView(bar)
        }
    }

    private fun buildPage() {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = L("ویجت صفحه اصلی", "Home screen widgets")
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            // 2.84: readable on the dark aurora too.
            setTextColor(if (Theme.isDark(context)) Color.parseColor("#A5C8FF") else Color.parseColor("#1A237E"))
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, 0, 0, dp(4))
        })

        // ---- section: the gate. Nothing else shows until picked.
        label(L("برای چه بخشی نیاز به ویجت دارید؟", "Which section do you need a widget for?"))
        glassButton(sectionName(section) + " ▾") {
            glassListDialog(L("انتخاب بخش", "Choose a section"),
                sections.map {
                    DialogRow(SectionWidgetProvider.sectionIconBitmap(this, it), null,
                        sectionName(it), it == section)
                }) { idx ->
                section = sections[idx]
                titleText = ""
            }
        }
        if (section.isBlank()) return

        // ---- connection mode (2.35): two pills in one row, above
        // the rest of the form. Not for chat widgets or Emergency.
        // Nothing picked behaves as "last" (the 2.33/2.34 way).
        // 2.95: also not for the last-connection widget — it
        // connects to the last method by definition.
        // 1.29: also not for Monitoring — it has no tunnel
        // to connect to; tapping it just opens Monitoring.
        if (section != "messenger" && section != "emergency" && section != "lastconnect" && section != "monitoring") {
            label(L("نحوه اتصال", "Connection method"))
            val modeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            for ((modeKey, modeLabel) in listOf(
                "last" to L("وصل به آخرین کانفیگ", "Last config"),
                "any" to L("هر کانفیگی که وصل شد", "Any working config"))) {
                val selected = modeKey == connMode
                val b = Button(this).apply {
                    text = modeLabel
                    textSize = 13f
                    // 2.90: selected pill stays solid teal; unselected is shared glass.
                    background = pillBackground(selected)
                    textDirection = View.TEXT_DIRECTION_LOCALE
                    setTextColor(pillTextColor(selected))
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    }
                    setOnClickListener {
                        // Tapping the selected pill clears it (back
                        // to the default: last config).
                        connMode = if (connMode == modeKey) "" else modeKey
                        saveDraft()
                        buildPage()
                    }
                }
                modeRow.addView(b)
            }
            root.addView(modeRow)
        }


        // ---- display name ----
        // 2.95: not for the last-connection widget — its name is
        // the live last method, so a stored title makes no sense.
        if (section != "lastconnect") {
        label(L("اسم نمایشی ویجت", "Widget display name"))
        val nameField = EditText(this).apply {
            setText(titleText)
            hint = defaultTitle()
            textSize = 15f
            // 2.90: dark-glass input (was solid white).
            background = Theme.glassInput(this@WidgetStudioActivity, 16f)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(if (Theme.isDark(this@WidgetStudioActivity)) Color.parseColor("#EDEFF5") else Color.parseColor("#333333"))
            setHintTextColor(if (Theme.isDark(this@WidgetStudioActivity)) Color.parseColor("#9AA0B0") else Color.parseColor("#999999"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        nameField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                titleText = s?.toString() ?: ""
                saveDraft()
                refreshPreview()
            }
        })
        root.addView(nameField)
        }

        // ---- icon + button: single buttons to customize pages ----
        if (section != "messenger") {
            label(L("آیکون", "Icon"))
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(iconBg)
                }
            }
            glassRow(dot, L("آیکون", "Icon"), L("رنگ دایره پشت آیکون", "Colour of the circle behind the icon")) {
                startActivity(Intent(this, WidgetCustomizeActivity::class.java)
                    .putExtra("mode", "icon"))
            }
            label(L("نوع دکمه اتصال", "Connect button type"))
            val btnMini = ImageView(this).apply {
                setImageBitmap(SectionWidgetProvider.buttonBitmap(this@WidgetStudioActivity,
                    btnShape, btnColor, 108, SectionWidgetProvider.STATE_OFF, false, btnGradient))
            }
            glassRow(btnMini, L("نوع دکمه اتصال", "Connect button type"), shapeName(btnShape)) {
                startActivity(Intent(this, WidgetCustomizeActivity::class.java)
                    .putExtra("mode", "button"))
            }
        }


        if (section != "messenger") {
            // ---- Studio tabs (MLM: قالب / رنگ / اطلاعات / اندازه) ----
            label(L("تنظیمات ویجت", "Widget settings"))
            val tabRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            for ((tabKey, tabLabel) in listOf(
                "template" to L("قالب", "Template"),
                "color" to L("رنگ", "Colour"),
                "info" to L("اطلاعات", "Info"),
                "size" to L("اندازه", "Size"))) {
                val selected = tabKey == studioTab
                val b = Button(this).apply {
                    text = tabLabel
                    textSize = 13f
                    // 2.90: selected pill stays solid teal; unselected is shared glass.
                    background = pillBackground(selected)
                    textDirection = View.TEXT_DIRECTION_LOCALE
                    setTextColor(pillTextColor(selected))
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    }
                    setOnClickListener {
                        studioTab = tabKey
                        buildPage()
                    }
                }
                tabRow.addView(b)
            }
            root.addView(tabRow)

            when (studioTab) {
                "color" -> {
                    // Accent swatches (MLM's 11).
                    label(L("رنگ دکمه و چراغ", "Button and lamp colour"))
                    val accents = listOf("#39E58C", "#34C759", "#0A84FF", "#64D2FF",
                        "#8B5CF6", "#B46CFF", "#FF4F8B", "#FF9F0A", "#FFD60A",
                        "#FF453A", "#E9EBEE")
                    val accScroll = HorizontalScrollView(this).apply {
                        isHorizontalScrollBarEnabled = false
                    }
                    val accRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val accCells = mutableListOf<Pair<FrameLayout, Int>>()
                    // 2.95: gradient cells (declared here so the
                    // solid-colour tap can clear their rings).
                    val gradCells = mutableListOf<Pair<FrameLayout, String>>()
                    for (hex in accents) {
                        val col = Color.parseColor(hex)
                        val cell = FrameLayout(this).apply {
                            setPadding(dp(3), dp(3), dp(3), dp(3))
                            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                                setMargins(dp(4), dp(4), dp(4), dp(4))
                            }
                            isClickable = true
                            setOnClickListener {
                                btnColor = col
                                // 2.95: a solid colour clears the gradient.
                                btnGradient = ""
                                for ((c, _) in gradCells) c.background = null
                                for ((c, cc) in accCells) {
                                    c.background = if (cc == btnColor) GradientDrawable().apply {
                                        shape = GradientDrawable.OVAL
                                        setStroke(dp(2), Color.parseColor("#2E9E5B"))
                                    } else null
                                }
                                saveDraft()
                                refreshPreview()
                            }
                        }
                        cell.addView(View(this).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(col)
                            }
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT)
                        })
                        cell.background = if (col == btnColor) GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setStroke(dp(2), Color.parseColor("#2E9E5B"))
                        } else null
                        accCells += cell to col
                        accRow.addView(cell)
                    }
                    accScroll.addView(accRow)
                    root.addView(accScroll)
                    // 2.95: gradient button colours, next to the solids.
                    label(L("رنگ گرادیانی دکمه", "Gradient button colour"))
                    val gradients = listOf(
                        Triple("teal_purple", "#14B8A6", "#8B5CF6"),
                        Triple("teal_blue", "#14B8A6", "#0A84FF"),
                        Triple("pink_orange", "#FF4F8B", "#FF9F0A"))
                    val gradScroll = HorizontalScrollView(this).apply {
                        isHorizontalScrollBarEnabled = false
                    }
                    val gradRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    for ((gid, c1, c2) in gradients) {
                        val cell = FrameLayout(this).apply {
                            setPadding(dp(3), dp(3), dp(3), dp(3))
                            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                                setMargins(dp(4), dp(4), dp(4), dp(4))
                            }
                            isClickable = true
                            setOnClickListener {
                                btnGradient = gid
                                // The gradient's first colour is also
                                // the button colour, so accents
                                // elsewhere (speeds, chart) keep working.
                                btnColor = Color.parseColor(c1)
                                for ((c, g) in gradCells) {
                                    c.background = if (g == btnGradient) GradientDrawable().apply {
                                        shape = GradientDrawable.OVAL
                                        setStroke(dp(2), Color.parseColor("#2E9E5B"))
                                    } else null
                                }
                                for ((c, cc) in accCells) {
                                    c.background = if (cc == btnColor) GradientDrawable().apply {
                                        shape = GradientDrawable.OVAL
                                        setStroke(dp(2), Color.parseColor("#2E9E5B"))
                                    } else null
                                }
                                saveDraft()
                                refreshPreview()
                            }
                        }
                        cell.addView(View(this).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                orientation = GradientDrawable.Orientation.TL_BR
                                colors = intArrayOf(Color.parseColor(c1), Color.parseColor(c2))
                            }
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT)
                        })
                        cell.background = if (gid == btnGradient) GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setStroke(dp(2), Color.parseColor("#2E9E5B"))
                        } else null
                        gradCells += cell to gid
                        gradRow.addView(cell)
                    }
                    gradScroll.addView(gradRow)
                    root.addView(gradScroll)
                    // Card colours (MLM's 10).
                    label(L("رنگ کارت", "Card colour"))
                    val bgs = listOf("#0F2236", "#131518", "#06080B", "#0B0718",
                        "#171447", "#1C1C1E", "#102A22", "#2A1020", "#7A4DFF", "#F2F2F7")
                    val bgScroll = HorizontalScrollView(this).apply {
                        isHorizontalScrollBarEnabled = false
                    }
                    val bgRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val effBg = if (bgColor != -1) bgColor
                        else WidgetStyle.presetBg(template)
                    val bgCells = mutableListOf<Pair<FrameLayout, Int>>()
                    for (hex in bgs) {
                        val col = Color.parseColor(hex)
                        val cell = FrameLayout(this).apply {
                            setPadding(dp(3), dp(3), dp(3), dp(3))
                            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                                setMargins(dp(4), dp(4), dp(4), dp(4))
                            }
                            isClickable = true
                            setOnClickListener {
                                bgColor = col
                                for ((c, cc) in bgCells) {
                                    c.background = if (cc == bgColor) GradientDrawable().apply {
                                        shape = GradientDrawable.OVAL
                                        setStroke(dp(2), Color.parseColor("#2E9E5B"))
                                    } else null
                                }
                                saveDraft()
                                refreshPreview()
                            }
                        }
                        cell.addView(View(this).apply {
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(col)
                            }
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT)
                        })
                        cell.background = if (col == effBg) GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setStroke(dp(2), Color.parseColor("#2E9E5B"))
                        } else null
                        bgCells += cell to col
                        bgRow.addView(cell)
                    }
                    bgScroll.addView(bgRow)
                    root.addView(bgScroll)
                    // Sliders: opacity / corner / text scale.
                    root.addView(sliderRow(L("میزان شفافیت کارت", "Card opacity"),
                        0, 100, if (opacity != -1) opacity
                        else WidgetStyle.presetOpacity(template)) { opacity = it })
                    root.addView(sliderRow(L("گردی گوشه‌ها", "Corner radius"),
                        8, 40, if (corner != -1) corner
                        else WidgetStyle.presetCorner(template)) { corner = it })
                    // 2.95: card border thickness, 0–4dp (0 = no rim).
                    root.addView(sliderRow(L("ضخامت قاب کارت", "Card border thickness"),
                        0, 4, if (borderWidth != -1f) borderWidth.toInt() else 1) { borderWidth = it.toFloat() })
                    root.addView(sliderRow(L("اندازه نوشته‌ها", "Text size"),
                        85, 125, textScale) { textScale = it })
                }
                "info" -> {
                    label(L("اطلاعات نمایشی", "Shown info"))
                    for ((flag, flagLabel, flagSub) in listOf(
                        Triple("method", L("نام بخش", "Section name"), ""),
                        Triple("status", L("وضعیت اتصال", "Connection status"), ""),
                        Triple("speeds", L("سرعت دانلود و آپلود", "Download and upload speeds"), ""),
                        Triple("chart", L("نمودار زنده", "Live chart"),
                            if (widgetSize == "small") L("در اندازه کوچک نشان داده نمی‌شود", "Not shown on small widgets") else ""),
                        Triple("usage", L("مصرف نشست", "Session usage"),
                            if (widgetSize != "large") L("فقط در اندازه بزرگ", "Large widgets only") else ""),
                        Triple("time", L("مدت اتصال", "Time connected"),
                            if (widgetSize != "large") L("فقط در اندازه بزرگ", "Large widgets only") else ""),
                        Triple("icon", L("آیکون بخش", "Section icon"), ""))) {
                        val on = when (flag) {
                            "method" -> showMethod
                            "status" -> showStatus
                            "speeds" -> showSpeeds
                            "chart" -> showChart
                            "usage" -> showUsage
                            "time" -> showTime
                            else -> showIcon
                        }
                        val blocked = (flag == "chart" && widgetSize == "small") ||
                            ((flag == "usage" || flag == "time") && widgetSize != "large")
                        val mark = TextView(this).apply {
                            text = if (on) "✓" else "○"
                            textSize = 20f
                            setTextColor(if (on) Color.parseColor("#2E9E5B") else Color.GRAY)
                            gravity = Gravity.CENTER
                        }
                        glassRow(mark, flagLabel,
                            if (flagSub.isNotBlank()) flagSub
                            else if (on) L("نمایش داده می‌شود", "Shown")
                            else L("پنهان است", "Hidden")) {
                            if (blocked) {
                                Toast.makeText(this, flagSub, Toast.LENGTH_SHORT).show()
                                return@glassRow
                            }
                            when (flag) {
                                "method" -> showMethod = !showMethod
                                "status" -> showStatus = !showStatus
                                "speeds" -> showSpeeds = !showSpeeds
                                "chart" -> showChart = !showChart
                                "usage" -> showUsage = !showUsage
                                "time" -> showTime = !showTime
                                else -> showIcon = !showIcon
                            }
                            saveDraft()
                            buildPage()
                        }
                    }
                }
                "size" -> {
                    label(L("اندازه ویجت", "Widget size"))
                    for ((sizeKey, sizeTitle, sizeSub) in listOf(
                        Triple("small", L("مربع کوچک", "Small square"),
                            L("110×110، آیکون و دکمه", "110x110, icon and button")),
                        Triple("medium", L("پهن", "Wide"),
                            L("250×110، اندازه فعلی ویجت‌ها", "250x110, the current widget size")),
                        Triple("large", L("بزرگ", "Large"),
                            L("250×250، نمودار و آمار نشست", "250x250, chart and session stats")))) {
                        val selected = sizeKey == widgetSize
                        val mark = TextView(this).apply {
                            text = if (selected) "✓" else "○"
                            textSize = 20f
                            setTextColor(if (selected) Color.parseColor("#2E9E5B") else Color.GRAY)
                            gravity = Gravity.CENTER
                        }
                        glassRow(mark, sizeTitle + if (selected) " ✓" else "", sizeSub) {
                            widgetSize = sizeKey
                            saveDraft()
                            buildPage()
                        }
                    }
                }
                else -> {
                    // Template gallery: MLM's eight presets.
                    label(L("قالب ظاهری", "Appearance template"))
                    val tplScroll = HorizontalScrollView(this).apply {
                        isHorizontalScrollBarEnabled = false
                    }
                    val tplRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    for (tpl in WidgetStyle.PRESET_KEYS) {
                        val selected = tpl == template
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            // 2.90: shared glass; selected keeps the green stroke.
                            background = if (selected) Theme.glassCard(this@WidgetStudioActivity, 18f).apply {
                                setStroke(dp(2), Color.parseColor("#2E9E5B"))
                            } else Theme.glassCard(this@WidgetStudioActivity, 18f)
                            elevation = 2f
                            setPadding(dp(8), dp(10), dp(8), dp(10))
                            layoutParams = LinearLayout.LayoutParams(dp(132),
                                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                setMargins(dp(4), dp(4), dp(4), dp(4))
                            }
                            isClickable = true
                            setOnClickListener {
                                // Picking a preset applies its whole
                                // design; the user may then change
                                // any part on the colour/info tabs.
                                template = tpl
                                btnColor = WidgetStyle.presetAccent(tpl)
                                btnShape = WidgetStyle.presetButton(tpl)
                                btnGradient = ""
                                borderWidth = -1f
                                bgColor = -1
                                opacity = -1
                                corner = -1
                                textScale = 100
                                val shown = WidgetStyle.presetShow(tpl)
                                showMethod = "method" in shown
                                showStatus = "status" in shown
                                showSpeeds = "speed" in shown
                                showUsage = "usage" in shown
                                showTime = "time" in shown
                                showChart = "chart" in shown
                                showIcon = "icon" in shown
                                saveDraft()
                                buildPage()
                            }
                        }
                        card.addView(ImageView(this).apply {
                            setImageBitmap(WidgetPreview.templateThumbBitmap(
                                this@WidgetStudioActivity, tpl))
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT, dp(66))
                        })
                        card.addView(TextView(this).apply {
                            text = templateName(tpl) + if (selected) " ✓" else ""
                            textSize = 12f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            textDirection = View.TEXT_DIRECTION_LOCALE
                            gravity = Gravity.CENTER
                            setPadding(0, dp(6), 0, 0)
                            setTextColor(if (selected) Color.parseColor("#2E9E5B") else rowFg())
                        })
                        tplRow.addView(card)
                    }
                    tplScroll.addView(tplRow)
                    root.addView(tplScroll)
                }
            }

            // ---- preview state tabs (قطع / در حال اتصال / متصل) ----
            label(L("وضعیت پیش‌نمایش", "Preview state"))
            val stateRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            for ((idx, sl) in listOf(
                L("قطع", "Off"),
                L("در حال اتصال", "Connecting"),
                L("متصل", "On")).withIndex()) {
                val selected = idx == previewState
                val b = Button(this).apply {
                    text = sl
                    textSize = 13f
                    // 2.90: selected pill stays solid teal; unselected is shared glass.
                    background = pillBackground(selected)
                    textDirection = View.TEXT_DIRECTION_LOCALE
                    setTextColor(pillTextColor(selected))
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    }
                    setOnClickListener {
                        previewState = idx
                        buildPage()
                    }
                }
                stateRow.addView(b)
            }
            root.addView(stateRow)
        }

        // ---- live preview: the full widget, fixed size ----
        label(L("پیش‌نمایش زنده", "Live preview"))
        previewBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(
                    Color.parseColor("#E8EAF6"), Color.parseColor("#CFD8DC"))
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(previewBox)
        refreshPreview()

        // ---- the only action ----
        val action = Button(this).apply {
            text = L("ساخت و افزودن به صفحه اصلی", "Build and add to home screen")
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            // 2.95: the app's neon button (was a glass primary).
            background = Theme.neonRect(this@WidgetStudioActivity, 22f)
            elevation = 4f
            textDirection = View.TEXT_DIRECTION_LOCALE
            setTextColor(Theme.neonTextColor(this@WidgetStudioActivity))
            setPadding(dp(12), dp(16), dp(12), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(20)
                bottomMargin = dp(8)
            }
            setOnClickListener { onAdd() }
        }
        root.addView(action)
    }

    private fun refreshPreview() {
        if (!::previewBox.isInitialized) return
        previewBox.removeAllViews()
        val view = layoutInflater.inflate(WidgetPreview.layout(this, widgetSize), previewBox, false)
        WidgetPreview.populate(this, view, section, style, iconBg, btnShape, btnColor,
            titleText, template, previewState, showSpeeds, showStatus, showIcon,
            showMethod, showUsage, showTime, showChart, widgetSize,
            btnColor, bgColor, opacity, corner, textScale, btnGradient, borderWidth)
        val previewH = when (widgetSize) { "small" -> 120; "large" -> 320; else -> 120 }
        previewBox.addView(view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(previewH)).apply {
            gravity = Gravity.CENTER
        })
    }


    private fun onAdd() {
            val o = org.json.JSONObject()
            o.put("provider", "section")
            o.put("section", section)
            o.put("title", titleText.ifBlank { defaultTitle() })
            o.put("style", style)
            o.put("color", "blue")
            o.put("icon", "icon")
            o.put("showname", true)
            o.put("iconbg", iconBg)
            o.put("btnshape", btnShape)
            o.put("btncolor", btnColor)
            o.put("connmode", connMode)
            o.put("template", template)
            o.put("showspeeds", showSpeeds)
            o.put("showstatus", showStatus)
            o.put("showicon", showIcon)
            o.put("showmethod", showMethod)
            o.put("showusage", showUsage)
            o.put("showtime", showTime)
            o.put("showchart", showChart)
            o.put("bgcolor", bgColor)
            o.put("opacity", opacity)
            o.put("corner", corner)
            o.put("textscale", textScale)
            o.put("btngradient", btnGradient)
            o.put("borderwidth", borderWidth.toDouble())
            WidgetStyle.setPending(this, o.toString())
            val mgr = AppWidgetManager.getInstance(this)
            val providerClass = when (widgetSize) {
                "small" -> SectionWidgetSmall::class.java
                "large" -> SectionWidgetLarge::class.java
                else -> SectionWidgetProvider::class.java
            }
            // 1.32: the pin request IS the placement — the old
            // code already called it, but only a toast told the
            // user, so a launcher that swallowed the system
            // dialog left no widget and no trace. Now: (1) check
            // the launcher supports pinning at all; (2) pass the
            // config in the pin extras too (the provider stashes
            // it as pending, so a pinned widget can never lose
            // its config); (3) a success callback confirms the
            // widget really landed on the home screen; (4) when
            // pinning is not supported or refused, show the real
            // fallback — the system's widget picker path — not a
            // bare toast.
            if (!mgr.isRequestPinAppWidgetSupported) {
                showWidgetPickerFallback()
                return
            }
            try {
                val extras = android.os.Bundle().apply {
                    putString("widget_config", o.toString())
                }
                val cb = PendingIntent.getBroadcast(this, 0,
                    Intent(this, WidgetPinReceiver::class.java).apply {
                        action = WidgetPinReceiver.ACTION_PINNED
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val ok = mgr.requestPinAppWidget(
                    ComponentName(this, providerClass), extras, cb)
                if (!ok) {
                    showWidgetPickerFallback()
                    return
                }
                Toast.makeText(this, L("پنجره افزودن ویجت باز شد؛ «افزودن» را بزن تا روی صفحه اصلی بیاید",
                    "The add-widget window opened; tap “Add” to place it on your home screen"), Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                showWidgetPickerFallback()
            }
    }

    /** Launcher cannot pin: the only real path is the
     *  system's widget picker (long-press the home
     *  screen → Widgets). A regular app has no API to
     *  open the launcher's picker directly
     *  (ACTION_APPWIDGET_PICK needs a host-allocated id
     *  and binds the widget to the caller, not the
     *  launcher), so this dialog shows the exact steps
     *  and offers to go home, where the long-press works. */
    private fun showWidgetPickerFallback() {
        AlertDialog.Builder(this)
            .setTitle(L("افزودن خودکار ویجت ممکن نشد", "Auto-adding the widget is not possible"))
            .setMessage(L("لانچر گوشی افزودن خودکار ویجت را نمی‌پذیرد. برای گذاشتن ویجت روی صفحه اصلی: روی یک جای خالی صفحه اصلی انگشت را نگه دار ← «ویجت‌ها» ← «اینترنت آزاد 2026» را پیدا کن و آن را روی صفحه بکش.",
                "This launcher does not accept auto-adding widgets. To place the widget on your home screen: long-press an empty spot on the home screen → “Widgets” → find “اینترنت آزاد 2026” and drag it onto the screen."))
            .setPositiveButton(L("رفتن به صفحه اصلی", "Go to home screen")) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                } catch (_: Exception) {}
            }
            .setNegativeButton(L("باشه", "OK"), null)
            .show()
    }
}
