package com.renamer

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*

/**
 * Login personalization page ("شخصی‌سازی ورود").
 *
 * It ONLY writes prefs — it does not restyle PinActivity.
 * PinActivity reads the same prefs on its next open.
 * 2.48: this page IS the login theme studio
 * (background design, neon colour, display colour,
 * key effects) — the old fixed themes are deleted.
 * Shape, size and font pickers are kept.
 * See CalcTheme.kt for the full read API.
 *
 * 2.17 layout: a LIVE keypad preview sits at the
 * top and shows every choice immediately; colour,
 * style (shape/size/font) and unlock method are each
 * ONE glass button that opens a bottom sheet, so the
 * preview stays visible while choosing. The change-
 * entry-password row moved here from Settings.
 */
class LoginPersonalizationActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private lateinit var root: LinearLayout
    private var previewBox: LinearLayout? = null
    // 2.95: the options live in their own scrolling
    // container below the fixed preview header.
    private lateinit var optionsRoot: LinearLayout
    private lateinit var optionsScroll: ScrollView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // 2.90: chrome text on the shared glass — light in night, dark in day.
    private fun rowFg(): Int =
        if (Theme.isDark(this)) Color.parseColor("#EDEFF5") else Color.parseColor("#333333")
    private fun rowSub(): Int =
        if (Theme.isDark(this)) Color.parseColor("#BBBBBB") else Color.GRAY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2.84: shared background + no white window strips.
        try { Theme.applyPage(this) } catch (_: Exception) {}
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            // 2.84: one shared background — the home night aurora
            // (slightly darker) in night, the pearl day home in day.
            background = Theme.pageBackground(this@LoginPersonalizationActivity)
        }

        root.addView(TextView(this).apply {
            text = L("شخصی‌سازی ورود", "Login personalization"); textSize = 22f
            setTextColor(Theme.fg(context))
            // 2.90 RTL: Persian text starts from the right.
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            // 3.11: own side padding — root's 20dp padding is wiped
            // by fitsSystemWindows (2.96 lesson), so the title sat
            // flush to the screen edge with no margin at all.
            setPadding(dp(20), 0, dp(20), dp(8))
        })
        // 2.95 item 10: the description lines under the
        // title are removed (the page is self-evident).
        // 2.95 item 11: the live preview is FIXED at the
        // top — a half-size exact miniature — while the
        // options below it scroll (no outer ScrollView
        // any more; only the options area scrolls).
        root.addView(sectionHeader(L("پیش‌نمایش زنده", "Live preview")))
        previewBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(previewBox)
        renderPreview()
        optionsRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        optionsScroll = ScrollView(this).apply {
            addView(optionsRoot)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(optionsScroll)

        rebuild()
        setContentView(root)
        root.fitsSystemWindows = true
        try {
                    } catch (_: Exception) {}
    }

    private fun fontFor(font: String): Typeface = when (font) {
        "mono" -> Typeface.MONOSPACE
        "normal" -> Typeface.DEFAULT
        else -> Typeface.DEFAULT_BOLD
    }

    /** Faithful miniature of the real PinActivity login page
     *  (2.32): same page background, same two-line display card
     *  (expression on top, big main below), same 5x4 key grid with
     *  the REAL labels (C, backspace, %, /, 7-9, x, 4-6, minus,
     *  1-3, +, +/-, 0, ., =), same key backgrounds, same footer
     *  line — only smaller (~0.40 scale, ~140dp wide, centred).
     *  The old preview had 3 columns, Persian digits and a single
     *  display line, none of which the real page has. */
    /** True proportional miniature of the real PinActivity page
     *  (2.33). The 2.32 preview fixed the display at 52dp, so it
     *  never looked like the real page, whose display EXPANDS
     *  (weight 1, roughly half the page). Here the card keeps the
     *  phone's own aspect ratio, the display uses weight 1, and
     *  every size is the real size times the card-to-screen ratio
     *  — so the preview IS the real page, smaller, inside a subtle
     *  phone-frame border. */
    private fun calcPreview(): LinearLayout {
        val shape = CalcTheme.shape(this)
        val scale = CalcTheme.keyScale(this)
        val font = CalcTheme.displayFont(this)
        val dm = resources.displayMetrics
        // 3.11: SMALLER miniature — 40% of the screen
        // width (~140dp on a phone), centred, per the
        // user's "60% smaller" order. The 2.96 55% size
        // DID render (measured 54.6% of screen width on
        // the user's screenshot) — it was simply too big.
        // At 40% the same drawing code reads like the
        // real login page, only smaller.
        val screenWdp = dm.widthPixels / dm.density
        val cardWdp = (screenWdp * 0.40f).toInt().coerceIn(120, 160)
        val cardW = dp(cardWdp)
        val ratio = cardWdp / screenWdp
        val cardHdp = (cardWdp * dm.heightPixels / dm.widthPixels.toFloat()).toInt()
        val frame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setShape(GradientDrawable.RECTANGLE)
                cornerRadius = dp(24).toFloat()
                setStroke(dp(2), Color.parseColor("#B0BEC5"))
            }
            setPadding(dp(5), dp(5), dp(5), dp(5))
            layoutParams = LinearLayout.LayoutParams(dp(cardWdp), dp(cardHdp)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        // Same root padding as PinActivity (24px raw), scaled.
        val padPx = (24 * ratio).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = CalcTheme.background(this@LoginPersonalizationActivity)
            setPadding(padPx, padPx, padPx, (16 * ratio).toInt())
            // 3.11: clip the card to rounded corners matching
            // the frame's inner arc (frame radius 24dp minus
            // 5dp padding = 19dp). The card's own background
            // is a square rectangle, so without this its sharp
            // corners poked out past the frame's rounded corners.
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(19).toFloat())
                }
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT)
        }
        frame.addView(card)
        // Display card, like PinActivity's: white glass, big
        // main number only, right-aligned — and it EXPANDS
        // expression line is gone here too: the real lock
        // shows only the large number since 2.51, and the
        // preview used to draw a second line it never had.
        val display = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.END
            background = GradientDrawable().apply {
                setShape(GradientDrawable.RECTANGLE)
                cornerRadius = 32f * ratio
                // 2.95: the display background is user-settable.
                setColor(CalcTheme.displayBgColor(this@LoginPersonalizationActivity))
                setStroke((2 * ratio).toInt().coerceAtLeast(1), Color.parseColor("#CCFFFFFF"))
            }
            val dpad = (28 * ratio).toInt()
            setPadding(dpad, dpad, dpad, dpad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        display.addView(TextView(this).apply {
            text = "45"
            textSize = 52f * ratio
            typeface = fontFor(font)
            setTextColor(CalcTheme.displayColor(this@LoginPersonalizationActivity))
            gravity = Gravity.END
        })
        card.addView(display)
        // The real key grid, all five rows, real labels, real
        // size formula (mirrored with scaled constants).
        val rows = listOf(
            listOf("C", "⌫", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("±", "0", ".", "=")
        )
        val keyMargin = (10 * ratio).toInt().coerceAtLeast(1)
        val keyPx = (((cardW - 2 * dp(24) * ratio - 80 * ratio) / 4) * scale).toInt()
            .coerceAtLeast(dp(20))
            // 3.11: at 40% width, "large" keys (scale 1.15)
            // would make the 4-key row wider than the card;
            // cap the key so the row always fits inside it.
            .coerceAtMost(((cardW - 2 * padPx - 8 * keyMargin) / 4).coerceAtLeast(dp(20)))
        for (rowKeys in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (k in rowKeys) {
                val accent = k == "="
                row.addView(TextView(this).apply {
                    text = k
                    textSize = 24f * ratio
                    typeface = fontFor(font)
                    gravity = Gravity.CENTER
                    // 2.95: operators and digits have their
                    // own colours (CalcTheme.keyTextColorFor).
                    setTextColor(if (accent) CalcTheme.accentTextColor(this@LoginPersonalizationActivity) else CalcTheme.keyTextColorFor(this@LoginPersonalizationActivity, k))
                    background = CalcTheme.keyBackground(shape, accent, this@LoginPersonalizationActivity)
                    layoutParams = LinearLayout.LayoutParams(keyPx, keyPx).apply {
                        setMargins(keyMargin, keyMargin, keyMargin, keyMargin)
                    }
                })
            }
            card.addView(row)
        }
        card.addView(TextView(this).apply {
            text = "created by : kamyar"
            textSize = 13f * ratio
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, (8 * ratio).toInt(), 0, 0)
        })
        return frame
    }

    /** Re-render the page preview from the
     *  saved studio prefs. */
    private fun renderPreview() {
        val box = previewBox ?: return
        box.removeAllViews()
        box.addView(calcPreview())
    }

    /** One glass option row: a 48dp preview, a bold
     *  title, a grey subtitle and a chevron. */
    private fun glassOptionRow(preview: View, title: String, sub: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 2.90: shared glass (was solid white). Row order stays
            // LTR (first child leftmost) per the 2.90 row-direction rule.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = Theme.glassCard(this@LoginPersonalizationActivity, 16f)
            elevation = 2f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            isClickable = true
            setOnClickListener { onClick() }
        }
        preview.layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            setMargins(0, 0, dp(12), 0)
        }
        row.addView(preview)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = title; textSize = 16f
            setTypeface(null, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowFg())
        })
        col.addView(TextView(this).apply {
            text = sub; textSize = 13f
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowSub())
        })
        row.addView(col)
        row.addView(TextView(this).apply {
            text = "‹"; textSize = 20f
            textDirection = View.TEXT_DIRECTION_LOCALE
            setTextColor(rowSub())
        })
        return row
    }

    private fun rebuild() {
        // 2.95: the preview is fixed in onCreate; only the
        // options below it are rebuilt here.
        optionsRoot.removeAllViews()
        renderPreview()

        // 2.50 studio: each part is one glass row
        // that opens its own sheet — the page
        // itself stays clean (widget-studio style).
        optionsRoot.addView(sectionHeader(L("استودیوی صفحه ورود", "Login studio")))
        optionsRoot.addView(glassOptionRow(
            View(this).apply { background = CalcTheme.backgroundForDesign(this@LoginPersonalizationActivity, CalcTheme.bgDesign(this@LoginPersonalizationActivity)) },
            L("تم‌های آماده", "Ready themes"),
            CalcTheme.presetById(CalcTheme.presetId(this))?.let { if (AppLanguage.isEnglish(this)) it.nameEn else it.nameFa } ?: L("سفارشی", "Custom")
        ) { showThemeSheet() })
        optionsRoot.addView(glassOptionRow(
            View(this).apply { background = CalcTheme.backgroundForDesign(this@LoginPersonalizationActivity, CalcTheme.bgDesign(this@LoginPersonalizationActivity)) },
            L("طرح پس‌زمینه", "Background design"),
            designName(CalcTheme.bgDesign(this))
        ) { showBgSheet() })
        optionsRoot.addView(glassOptionRow(
            TextView(this).apply {
                text = "="; textSize = 20f; gravity = Gravity.CENTER
                setTextColor(CalcTheme.accentTextColor(this@LoginPersonalizationActivity))
                background = CalcTheme.keyBackground(CalcTheme.shape(this@LoginPersonalizationActivity), true, this@LoginPersonalizationActivity)
            },
            L("رنگ دکمه‌ی مساوی", "\"=\" key colour"),
            hexOf(CalcTheme.eqColor(this))
        ) { showWheelSheet(L("رنگ دکمه‌ی مساوی", "\"=\" key colour"), CalcTheme.eqColor(this), "eq") { c -> CalcTheme.setEqColor(this, c); rebuild() } })
        optionsRoot.addView(glassOptionRow(
            TextView(this).apply {
                text = "۱۲۳"; textSize = 18f; gravity = Gravity.CENTER
                setTextColor(CalcTheme.displayColor(this@LoginPersonalizationActivity))
            },
            L("رنگ نوشته‌ی نمایشگر", "Display text colour"),
            hexOf(CalcTheme.displayColor(this))
        ) { showWheelSheet(L("رنگ نوشته‌ی نمایشگر", "Display text colour"), CalcTheme.displayColor(this), "display") { c -> CalcTheme.setDisplayColor(this, c); rebuild() } })
        // 2.95: per-part colours — operators, digits,
        // display background — each with its own wheel,
        // plus the gradient "=" key option.
        optionsRoot.addView(glassOptionRow(
            TextView(this).apply {
                text = "÷"; textSize = 20f; gravity = Gravity.CENTER
                setTextColor(CalcTheme.opColor(this@LoginPersonalizationActivity))
                background = CalcTheme.keyBackground(CalcTheme.shape(this@LoginPersonalizationActivity), false, this@LoginPersonalizationActivity)
            },
            L("رنگ دکمه‌های عملگر", "Operator key colour"),
            if (CalcTheme.opColorRaw(this) == -1) L("پیش‌فرض", "Default") else hexOf(CalcTheme.opColor(this))
        ) { showWheelSheet(L("رنگ دکمه‌های عملگر", "Operator key colour"), CalcTheme.opColor(this), "op") { c -> CalcTheme.setOpColor(this, c); rebuild() } })
        optionsRoot.addView(glassOptionRow(
            TextView(this).apply {
                text = "۷"; textSize = 18f; gravity = Gravity.CENTER
                setTextColor(CalcTheme.digitColor(this@LoginPersonalizationActivity))
                background = CalcTheme.keyBackground(CalcTheme.shape(this@LoginPersonalizationActivity), false, this@LoginPersonalizationActivity)
            },
            L("رنگ دکمه‌های عدد", "Digit key colour"),
            if (CalcTheme.digitColorRaw(this) == -1) L("پیش‌فرض", "Default") else hexOf(CalcTheme.digitColor(this))
        ) { showWheelSheet(L("رنگ دکمه‌های عدد", "Digit key colour"), CalcTheme.digitColor(this), "digit") { c -> CalcTheme.setDigitColor(this, c); rebuild() } })
        optionsRoot.addView(glassOptionRow(
            TextView(this).apply {
                text = "۱۲۳"; textSize = 18f; gravity = Gravity.CENTER
                setTextColor(CalcTheme.displayColor(this@LoginPersonalizationActivity))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    setColor(CalcTheme.displayBgColor(this@LoginPersonalizationActivity))
                    setStroke(dp(1), Color.parseColor("#CCFFFFFF"))
                }
            },
            L("پس‌زمینه‌ی نمایشگر", "Display background"),
            if (CalcTheme.displayBgRaw(this) == -1) L("پیش‌فرض", "Default") else hexOf(CalcTheme.displayBgColor(this))
        ) { showWheelSheet(L("پس‌زمینه‌ی نمایشگر", "Display background"), CalcTheme.displayBgColor(this), "displaybg") { c -> CalcTheme.setDisplayBg(this, c); rebuild() } })
        optionsRoot.addView(toggleRow(
            L("گرادیان دکمه‌ی مساوی", "Gradient \"=\" key"),
            L("دکمه‌ی مساوی با دو رنگ گرادیان می‌شود؛ رنگ دوم را در ردیف بعدی انتخاب کن.", "The \"=\" key gets a two-colour gradient; pick its second colour in the next row."),
            CalcTheme.eqGradient(this)
        ) { CalcTheme.setEqGradient(this, !CalcTheme.eqGradient(this)); rebuild() })
        if (CalcTheme.eqGradient(this)) {
            optionsRoot.addView(glassOptionRow(
                TextView(this).apply {
                    text = "="; textSize = 20f; gravity = Gravity.CENTER
                    setTextColor(CalcTheme.accentTextColor(this@LoginPersonalizationActivity))
                    background = CalcTheme.keyBackground(CalcTheme.shape(this@LoginPersonalizationActivity), true, this@LoginPersonalizationActivity)
                },
                L("رنگ دوم گرادیان مساوی", "Gradient \"=\" second colour"),
                hexOf(CalcTheme.eqColor2(this))
            ) { showWheelSheet(L("رنگ دوم گرادیان مساوی", "Gradient \"=\" second colour"), CalcTheme.eqColor2(this), "eq2") { c -> CalcTheme.setEqColor2(this, c); rebuild() } })
        }
        optionsRoot.addView(glassOptionRow(
            TextView(this).apply {
                text = "1"; textSize = 17f; gravity = Gravity.CENTER
                setTextColor(CalcTheme.keyTextColor(this@LoginPersonalizationActivity))
                background = CalcTheme.keyBackground(CalcTheme.shape(this@LoginPersonalizationActivity), false, this@LoginPersonalizationActivity)
            },
            L("حاشیه‌ی دور دکمه‌ها", "Key border"),
            if (CalcTheme.neonBorder(this)) L("روشن", "On") + " • " + fmtDp(CalcTheme.borderWidthDp(this)) else L("خاموش", "Off")
        ) { showBorderSheet() })
        optionsRoot.addView(glassOptionRow(
            TextView(this).apply {
                text = "1"; textSize = 17f; gravity = Gravity.CENTER
                setTextColor(CalcTheme.keyTextColor(this@LoginPersonalizationActivity))
                background = CalcTheme.keyBackground(CalcTheme.shape(this@LoginPersonalizationActivity), false, this@LoginPersonalizationActivity)
            },
            L("شفافیت دکمه‌ها", "Key transparency"),
            L("شفافیت", "Transparency") + " " + CalcTheme.keyTransparency(this) + "%"
        ) { showOpacitySheet() })
        optionsRoot.addView(toggleRow(
            L("عمق سه‌بعدی دکمه‌ها", "3D key depth"),
            L("دکمه‌ها با گرادیان روشن-تیره، عمق‌دار و برجسته می‌شوند.", "Keys get a light-to-dark gradient so they look deep and raised."),
            CalcTheme.depth3d(this)
        ) { CalcTheme.setDepth3d(this, !CalcTheme.depth3d(this)); rebuild() })
        optionsRoot.addView(Button(this).apply {
            text = L("بازنشانی طرح و رنگ‌ها", "Reset design and colours")
            textSize = 14f; isAllCaps = false
            textDirection = View.TEXT_DIRECTION_LOCALE
            // 2.95: neon button, like the rest of the app.
            setTextColor(Theme.neonTextColor(this@LoginPersonalizationActivity))
            background = Theme.neonRect(this@LoginPersonalizationActivity, 24f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4); bottomMargin = dp(8) }
            setPadding(dp(24), dp(10), dp(24), dp(10))
            setOnClickListener { CalcTheme.resetStudio(this@LoginPersonalizationActivity); rebuild() }
        })

        // Shape, size and font: one glass button.
        optionsRoot.addView(sectionHeader(L("شکل، اندازه و فونت دکمه‌ها", "Button shape, size and font")))
        val shapeName = when (CalcTheme.shape(this)) {
            "square" -> L("مربعی", "Square")
            "circle" -> L("دایره‌ای", "Circle")
            "pill" -> L("کپسولی", "Pill")
            "soft" -> L("نرم", "Soft")
            else -> L("گرد", "Round")
        }
        val sizeName = when (CalcTheme.keyScale(this)) {
            0.85f -> L("کوچک", "Small")
            1.15f -> L("بزرگ", "Large")
            else -> L("معمولی", "Normal")
        }
        val fontName = when (CalcTheme.displayFont(this)) {
            "normal" -> L("معمولی", "Normal")
            "mono" -> L("تک‌فاصله‌ای", "Monospace")
            else -> L("پررنگ", "Bold")
        }
        optionsRoot.addView(glassOptionRow(
            TextView(this).apply {
                text = "۷"; textSize = 17f; gravity = Gravity.CENTER
                setTextColor(CalcTheme.keyTextColor(this@LoginPersonalizationActivity))
                background = CalcTheme.keyBackground(CalcTheme.shape(this@LoginPersonalizationActivity), false, this@LoginPersonalizationActivity)
            },
            L("شکل، اندازه و فونت دکمه‌ها", "Button shape, size and font"),
            "$shapeName • $sizeName • $fontName"
        ) { showStyleSheet() })

        // How the app opens: one glass button.
        // 1.82: in the PUBLIC section this whole block is
        // hidden: public settings must never mention the private
        // section. (Mode arrives from HomeSettingsActivity; an
        // unknown mode fails closed to hidden.)
        val showUnlock = intent.getStringExtra(PinActivity.EXTRA_MODE) != PinActivity.MODE_PUBLIC
        if (showUnlock) {
            optionsRoot.addView(sectionHeader(L("نحوه باز شدن برنامه", "How the app opens")))
            val curUnlock = getSharedPreferences("renamer_settings", MODE_PRIVATE).getString("unlock_mode", "password") ?: "password"
            val curUnlockName = when (curUnlock) {
                "password_shake" -> L("با رمز + لرزش گوشی", "With password + phone shake")
                "shake_only" -> L("فقط لرزش گوشی (بدون رمز)", "Phone shake only (no password)")
                "passwordless" -> L("بدون رمز", "Without password")
                else -> L("با رمز", "With password")
            }
            var unlockSub: TextView? = null
            val unlockRow = glassOptionRow(
                TextView(this).apply {
                    text = "🔓"; textSize = 24f; gravity = Gravity.CENTER
                },
                L("نحوه باز شدن برنامه", "How the app opens"),
                curUnlockName
            ) { showUnlockSheet { name -> unlockSub?.text = name } }
            // The subtitle view is the second child of the
            // text column (index 1 of the column at row index 1).
            val col = unlockRow.getChildAt(1) as LinearLayout
            unlockSub = col.getChildAt(1) as TextView
            optionsRoot.addView(unlockRow)
        }

        // Change entry password: moved here from Settings.
        optionsRoot.addView(sectionHeader(L("رمز ورود", "Entry password")))
        optionsRoot.addView(glassOptionRow(
            TextView(this).apply {
                text = "🔑"; textSize = 24f; gravity = Gravity.CENTER
            },
            L("تغییر رمز ورود", "Change entry password"),
            L("رمز ورود را به رمز دلخواهت عوض کن.", "Change the entry password to one of your choice.")
        ) { startActivity(Intent(this, ChangePublicPinActivity::class.java)) })

        // Welcome page on/off (2.18): when off, a
        // correct entry goes straight to Home.
        optionsRoot.addView(sectionHeader(L("صفحه خوش‌آمدگویی", "Welcome page")))
        val welcomePrefs = getSharedPreferences("renamer_settings", MODE_PRIVATE)
        var welcomePill: TextView? = null
        fun refreshWelcomePill() {
            val on = welcomePrefs.getBoolean("show_welcome", true)
            welcomePill?.text = if (on) L("روشن", "On") else L("خاموش", "Off")
            // 2.95: neon pill when on, glass when off — synced
            // with night/day (was a fixed light-green pill).
            if (on) {
                welcomePill?.setTextColor(Theme.neonTextColor(this@LoginPersonalizationActivity))
                welcomePill?.background = Theme.neonRect(this@LoginPersonalizationActivity, 20f)
            } else {
                welcomePill?.setTextColor(rowSub())
                welcomePill?.background = Theme.glassCard(this@LoginPersonalizationActivity, 20f)
            }
        }
        val welcomeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 2.90: shared glass (was #F5F5F5). Row order stays LTR.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = Theme.glassCard(this@LoginPersonalizationActivity, 16f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            isClickable = true
            setOnClickListener {
                val on = !welcomePrefs.getBoolean("show_welcome", true)
                welcomePrefs.edit().putBoolean("show_welcome", on).apply()
                refreshWelcomePill()
            }
        }
        val welcomeCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        welcomeCol.addView(TextView(this).apply {
            text = L("صفحه خوش‌آمدگویی", "Welcome page"); textSize = 16f
            setTypeface(null, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowFg())
        })
        welcomeCol.addView(TextView(this).apply {
            text = L("بعد از ورود درست نشان داده می‌شود؛ خاموش یعنی مستقیم به صفحه اصلی بروی.", "Shown after a correct entry; off means going straight to Home.")
            textSize = 13f
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowSub())
        })
        welcomeRow.addView(welcomeCol)
        welcomePill = TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        welcomeRow.addView(welcomePill)
        refreshWelcomePill()
        optionsRoot.addView(welcomeRow)

        optionsRoot.addView(TextView(this).apply {
            text = L(
                "توجه: این تنظیم فقط ظاهر صفحه ورود را عوض می‌کند؛ رمزها و قفل برنامه با آن عوض نمی‌شوند.",
                "Note: this only changes the look of the login page; it does not change any password or the app lock."
            )
            textSize = 13f; setTextColor(Theme.subFg(this@LoginPersonalizationActivity))
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, dp(8), 0, dp(16))
        })
        // 2.95 item 12: the bottom "Back" button is removed;
        // the system back key / gesture closes the page.
    }

    // ---------- 2.48 studio builders ----------

    /** Bottom sheet dialog (kept: the style and
     *  unlock sheets still use it). 2.96: the window
     *  background is the OPAQUE themed dialog card —
     *  the old transparent window + translucent glass
     *  sheet let the page behind show through (the
     *  ghosted text the user saw in every picker). */
    private fun bottomDialog(content: View): android.app.Dialog {
        val dlg = android.app.Dialog(this)
        dlg.window?.setBackgroundDrawable(Theme.dialogCard(this))
        dlg.setContentView(content)
        dlg.window?.setLayout(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        dlg.window?.setGravity(Gravity.BOTTOM)
        return dlg
    }

    private fun designName(d: CalcTheme.BgDesign): String =
        if (AppLanguage.isEnglish(this)) d.nameEn else d.nameFa

    private fun hexOf(color: Int): String =
        "#%06X".format(0xFFFFFF and color)

    private fun fmtDp(dp: Float): String =
        if (dp == dp.toInt().toFloat()) "${dp.toInt()}" else "$dp"

    private fun closeButton(dlg: android.app.Dialog): Button =
        Button(this).apply {
            text = L("بستن", "Close")
            textSize = 14f; isAllCaps = false
            textDirection = View.TEXT_DIRECTION_LOCALE
            // 2.95: neon button, like the rest of the app.
            setTextColor(Theme.neonTextColor(this@LoginPersonalizationActivity))
            background = Theme.neonRect(this@LoginPersonalizationActivity, 24f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8); bottomMargin = dp(8) }
            setPadding(dp(32), dp(10), dp(32), dp(10))
            setOnClickListener { dlg.dismiss() }
        }

    /** Theme sheet: a live mini-keypad preview
     *  on top, then one row per theme. Tapping a
     *  theme applies it AND re-renders the preview
     *  inside this same sheet, so the user sees
     *  exactly what the keypad will look like. */
    private fun showThemeSheet() {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            // 2.96: OPAQUE solid themed background (dialogCard) — the translucent glass let the page behind show through.
            background = Theme.dialogCard(this@LoginPersonalizationActivity)
        }
        val dlg = bottomDialog(ScrollView(this).apply { addView(sheet) })
        sheet.addView(TextView(this).apply {
            text = L("تم‌های آماده", "Ready themes"); textSize = 20f
            setTypeface(null, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowFg())
            setPadding(0, 0, 0, dp(12))
        })
        val previewHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        sheet.addView(previewHolder)
        fun refreshPreview() {
            previewHolder.removeAllViews()
            previewHolder.addView(calcPreview())
        }
        refreshPreview()
        val rowsHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        sheet.addView(rowsHolder)
        fun refreshRows() {
            rowsHolder.removeAllViews()
            val cur = CalcTheme.presetId(this)
            for (preset in CalcTheme.presets()) {
                val sel = preset.id == cur
                val tileBg: android.graphics.drawable.Drawable =
                    if (preset.bgId == "colorful" && preset.bgC1 != null && preset.bgC2 != null) {
                        GradientDrawable(
                            GradientDrawable.Orientation.TL_BR,
                            intArrayOf(preset.bgC1, CalcTheme.mixA(preset.bgC1, preset.bgC2, 0.5f), preset.bgC2)
                        )
                    } else {
                        val d = CalcTheme.bgDesigns().firstOrNull { it.id == preset.bgId } ?: CalcTheme.bgDesign(this)
                        CalcTheme.backgroundForDesign(this, d)
                    }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    if (sel) background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(16).toFloat()
                        // 2.90: dark-green highlight in night (was light green even in night).
                        setColor(if (Theme.isDark(this@LoginPersonalizationActivity)) Theme.darken(Color.parseColor("#1E3A24")) else Color.parseColor("#E8F5E9"))
                    }
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    isClickable = true
                    setOnClickListener {
                        CalcTheme.applyPreset(this@LoginPersonalizationActivity, preset)
                        rebuild()
                        refreshPreview()
                        refreshRows()
                    }
                }
                row.addView(View(this).apply {
                    background = tileBg
                    layoutParams = LinearLayout.LayoutParams(dp(56), dp(36)).apply {
                        setMargins(0, 0, dp(12), 0)
                    }
                })
                row.addView(TextView(this).apply {
                    text = if (AppLanguage.isEnglish(this@LoginPersonalizationActivity)) preset.nameEn else preset.nameFa
                    textSize = 16f
                    textDirection = View.TEXT_DIRECTION_LOCALE
                    gravity = Gravity.START
                    setTextColor(rowFg())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (sel) row.addView(TextView(this).apply {
                    text = "✓"; textSize = 20f
                    setTextColor(Color.parseColor("#2E9E5B"))
                })
                rowsHolder.addView(row)
            }
        }
        refreshRows()
        sheet.addView(closeButton(dlg))
        dlg.show()
    }

    /** Background-design sheet: a tile grid plus,
     *  only for the colorful design, its two
     *  user-settable colours (each opens the
     *  wheel sheet). */
    private fun showBgSheet() {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            // 2.96: OPAQUE solid themed background (dialogCard) — the translucent glass let the page behind show through.
            background = Theme.dialogCard(this@LoginPersonalizationActivity)
        }
        val dlg = bottomDialog(ScrollView(this).apply { addView(sheet) })
        sheet.addView(TextView(this).apply {
            text = L("طرح پس‌زمینه", "Background design"); textSize = 20f
            setTypeface(null, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowFg())
            setPadding(0, 0, 0, dp(12))
        })
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        sheet.addView(holder)
        fun refresh() {
            holder.removeAllViews()
            val designs = CalcTheme.bgDesigns()
            val cur = CalcTheme.bgId(this)
            var idx = 0
            while (idx < designs.size) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                for (colIdx in 0 until 3) {
                    if (idx >= designs.size) break
                    val d = designs[idx]
                    idx++
                    val sel = d.id == cur
                    val tile = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            setMargins(dp(4), dp(4), dp(4), dp(4))
                        }
                        isClickable = true
                        setOnClickListener { CalcTheme.setBg(this@LoginPersonalizationActivity, d.id); rebuild(); refresh() }
                    }
                    val tileGrad: GradientDrawable =
                        if (d.id == "colorful") {
                            val c1 = CalcTheme.bgColor1(this@LoginPersonalizationActivity)
                            val c2 = CalcTheme.bgColor2(this@LoginPersonalizationActivity)
                            GradientDrawable(
                                GradientDrawable.Orientation.TL_BR,
                                intArrayOf(c1, CalcTheme.mixA(c1, c2, 0.5f), c2)
                            )
                        } else {
                            GradientDrawable(
                                GradientDrawable.Orientation.TL_BR,
                                intArrayOf(d.top, d.mid, d.bottom)
                            )
                        }.apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(12).toFloat()
                            setStroke(if (sel) dp(3) else dp(1),
                                if (sel) CalcTheme.eqColor(this@LoginPersonalizationActivity) else Color.parseColor("#E0E0E0"))
                        }
                    // Frame: rounded gradient below, the
                    // drawn galaxy sky above it (only
                    // for the galaxy design; its own
                    // drawable is transparent, so the
                    // rounded gradient shows through).
                    val tileFrame = android.widget.FrameLayout(this).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
                    }
                    tileFrame.addView(View(this).apply {
                        background = tileGrad
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
                    })
                    if (d.id == "galaxy") {
                        tileFrame.addView(View(this).apply {
                            background = CalcGalaxyDrawable(this@LoginPersonalizationActivity.resources.displayMetrics.density)
                            layoutParams = android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
                        })
                    }
                    tile.addView(tileFrame)
                    tile.addView(TextView(this).apply {
                        text = designName(d); textSize = 12f; gravity = Gravity.CENTER
                        textDirection = View.TEXT_DIRECTION_LOCALE
                        setTextColor(rowFg())
                        setPadding(0, dp(4), 0, 0)
                    })
                    if (sel) tile.addView(TextView(this).apply {
                        text = "✓"; textSize = 14f
                        setTextColor(Color.parseColor("#2E9E5B")); gravity = Gravity.CENTER
                    })
                    row.addView(tile)
                }
                holder.addView(row)
            }
            if (cur == "colorful") {
                holder.addView(TextView(this).apply {
                    text = L("رنگ‌های طرح رنگارنگ را اینجا عوض کن:", "Change the colorful design's colours here:"); textSize = 14f
                    textDirection = View.TEXT_DIRECTION_LOCALE
                    gravity = Gravity.START
                    setTextColor(rowFg())
                    setPadding(0, dp(12), 0, dp(4))
                })
                holder.addView(glassOptionRow(
                    TextView(this).apply {
                        text = ""; background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(CalcTheme.bgColor1(this@LoginPersonalizationActivity))
                        }
                    },
                    L("رنگ اول رنگارنگ", "Colorful colour 1"),
                    hexOf(CalcTheme.bgColor1(this))
                ) { showWheelSheet(L("رنگ اول رنگارنگ", "Colorful colour 1"), CalcTheme.bgColor1(this), "bgc1") { c -> CalcTheme.setBgColor1(this, c); rebuild(); refresh() } })
                holder.addView(glassOptionRow(
                    TextView(this).apply {
                        text = ""; background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(CalcTheme.bgColor2(this@LoginPersonalizationActivity))
                        }
                    },
                    L("رنگ دوم رنگارنگ", "Colorful colour 2"),
                    hexOf(CalcTheme.bgColor2(this))
                ) { showWheelSheet(L("رنگ دوم رنگارنگ", "Colorful colour 2"), CalcTheme.bgColor2(this), "bgc2") { c -> CalcTheme.setBgColor2(this, c); rebuild(); refresh() } })
            }
        }
        refresh()
        sheet.addView(closeButton(dlg))
        dlg.show()
    }

    /** Spectrum wheel sheet: a circle spectrum
     *  (wheel + brightness bar) plus quick swatches
     *  and a live sample of what the colour will
     *  paint. "kind" selects the sample: eq, eq2,
     *  display, displaybg, border, bgc1, bgc2, op, digit. */
    private fun showWheelSheet(title: String, initial: Int, kind: String, onApply: (Int) -> Unit) {
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)
        var hue = hsv[0]
        var sat = hsv[1]
        var value = hsv[2]
        fun picked(): Int = Color.HSVToColor(floatArrayOf(hue, sat, value))

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            // 2.96: OPAQUE solid themed background (dialogCard) — the translucent glass let the page behind show through.
            background = Theme.dialogCard(this@LoginPersonalizationActivity)
        }
        val dlg = bottomDialog(ScrollView(this).apply { addView(sheet) })
        sheet.addView(TextView(this).apply {
            text = title; textSize = 20f
            setTypeface(null, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowFg())
            setPadding(0, 0, 0, dp(12))
        })
        val sampleHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        sheet.addView(sampleHolder)
        val hexView = TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LOCALE
            setTextColor(rowFg())
            setPadding(0, 0, 0, dp(8))
        }
        fun shapeRadius(): Float = when (CalcTheme.shape(this)) {
            "circle" -> 100f
            "square" -> 8f
            "pill" -> 100f
            "soft" -> 16f
            else -> 32f
        }
        fun sampleView(color: Int): View {
            return when (kind) {
                "display" -> LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = CalcTheme.backgroundForDesign(this@LoginPersonalizationActivity, CalcTheme.bgDesign(this@LoginPersonalizationActivity))
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    addView(TextView(this@LoginPersonalizationActivity).apply {
                        text = "۱۲۳"; textSize = 28f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(color)
                    })
                }
                "border" -> TextView(this).apply {
                    text = "1"; textSize = 20f; gravity = Gravity.CENTER
                    setTextColor(CalcTheme.keyTextColor(this@LoginPersonalizationActivity))
                    background = GradientDrawable().apply {
                        shape = if (CalcTheme.shape(this@LoginPersonalizationActivity) == "circle") GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                        cornerRadius = dp(shapeRadius().toInt()).toFloat()
                        setColor(Color.argb(CalcTheme.keyAlpha(this@LoginPersonalizationActivity), 255, 255, 255))
                        setStroke((CalcTheme.borderWidthDp(this@LoginPersonalizationActivity) * dp(1)).toInt().coerceAtLeast(1), color)
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                }
                "bgc1", "bgc2" -> {
                    val c1 = if (kind == "bgc1") color else CalcTheme.bgColor1(this@LoginPersonalizationActivity)
                    val c2 = if (kind == "bgc2") color else CalcTheme.bgColor2(this@LoginPersonalizationActivity)
                    View(this).apply {
                        background = GradientDrawable(
                            GradientDrawable.Orientation.TL_BR,
                            intArrayOf(c1, CalcTheme.mixA(c1, c2, 0.5f), c2)
                        ).apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dp(12).toFloat()
                        }
                        layoutParams = LinearLayout.LayoutParams(dp(200), dp(64))
                    }
                }
                "op" -> TextView(this).apply {
                    text = "÷"; textSize = 24f; gravity = Gravity.CENTER
                    setTextColor(color)
                    background = CalcTheme.keyBackground(CalcTheme.shape(this@LoginPersonalizationActivity), false, this@LoginPersonalizationActivity)
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                }
                "digit" -> TextView(this).apply {
                    text = "۷"; textSize = 24f; gravity = Gravity.CENTER
                    setTextColor(color)
                    background = CalcTheme.keyBackground(CalcTheme.shape(this@LoginPersonalizationActivity), false, this@LoginPersonalizationActivity)
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                }
                "displaybg" -> LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(12).toFloat()
                        setColor(color)
                        setStroke(dp(1), Color.parseColor("#CCFFFFFF"))
                    }
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    addView(TextView(this@LoginPersonalizationActivity).apply {
                        text = "۱۲۳"; textSize = 28f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(CalcTheme.displayColor(this@LoginPersonalizationActivity))
                    })
                }
                "eq2" -> TextView(this).apply {
                    text = "="; textSize = 24f; gravity = Gravity.CENTER
                    setTextColor(if (CalcTheme.isDark(CalcTheme.mixA(CalcTheme.eqColor(this@LoginPersonalizationActivity), color, 0.5f))) Color.WHITE else Color.parseColor("#333333"))
                    background = GradientDrawable().apply {
                        shape = if (CalcTheme.shape(this@LoginPersonalizationActivity) == "circle") GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                        cornerRadius = dp(shapeRadius().toInt()).toFloat()
                        orientation = GradientDrawable.Orientation.TL_BR
                        colors = intArrayOf(CalcTheme.eqColor(this@LoginPersonalizationActivity), color)
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                }
                else -> TextView(this).apply {
                    text = "="; textSize = 24f; gravity = Gravity.CENTER
                    setTextColor(if (CalcTheme.isDark(color)) Color.WHITE else Color.parseColor("#333333"))
                    background = GradientDrawable().apply {
                        shape = if (CalcTheme.shape(this@LoginPersonalizationActivity) == "circle") GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                        cornerRadius = dp(shapeRadius().toInt()).toFloat()
                        setColor(color)
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                }
            }
        }
        fun refreshSample() {
            sampleHolder.removeAllViews()
            sampleHolder.addView(sampleView(picked()))
            hexView.text = hexOf(picked())
        }
        refreshSample()
        sheet.addView(hexView)
        val bar = ValueBarView(this).apply {
            this.value = value
            this.baseColor = Color.HSVToColor(floatArrayOf(hue, sat, 1f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)
            ).apply { bottomMargin = dp(12) }
            onPick = { v ->
                value = v
                refreshSample()
            }
        }
        sheet.addView(bar)
        val wheel = ColorWheelView(this).apply {
            this.hue = hue
            this.sat = sat
            layoutParams = LinearLayout.LayoutParams(dp(280), dp(280)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
            onPick = { h, s ->
                hue = h; sat = s
                bar.baseColor = Color.HSVToColor(floatArrayOf(hue, sat, 1f))
                bar.invalidate()
                refreshSample()
            }
        }
        sheet.addView(wheel)
        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        val quick = if (kind == "display") CalcTheme.DISPLAY_SWATCHES else CalcTheme.NEON_SWATCHES
        for (c in quick) {
            quickRow.addView(swatch(c, c == initial) {
                val h2 = FloatArray(3)
                Color.colorToHSV(c, h2)
                hue = h2[0]; sat = h2[1]; value = h2[2]
                wheel.hue = hue; wheel.sat = sat
                wheel.invalidate()
                bar.value = value
                bar.baseColor = Color.HSVToColor(floatArrayOf(hue, sat, 1f))
                bar.invalidate()
                refreshSample()
            })
        }
        sheet.addView(quickRow)
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        btnRow.addView(Button(this).apply {
            text = L("اعمال", "Apply")
            textSize = 14f; isAllCaps = false
            // 2.95: neon button, like the rest of the app
            // (was a solid teal fill).
            setTextColor(Theme.neonTextColor(this@LoginPersonalizationActivity))
            background = Theme.neonRect(this@LoginPersonalizationActivity, 24f)
            setPadding(dp(32), dp(10), dp(32), dp(10))
            setOnClickListener { onApply(picked()); dlg.dismiss() }
        })
        btnRow.addView(Button(this).apply {
            text = L("لغو", "Cancel")
            textSize = 14f; isAllCaps = false
            textDirection = View.TEXT_DIRECTION_LOCALE
            // 2.95: neon button, like the rest of the app.
            setTextColor(Theme.neonTextColor(this@LoginPersonalizationActivity))
            background = Theme.neonRect(this@LoginPersonalizationActivity, 24f)
            setPadding(dp(32), dp(10), dp(32), dp(10))
            setOnClickListener { dlg.dismiss() }
        })
        sheet.addView(btnRow)
        dlg.show()
    }

    /** Border sheet: on/off, a thickness slider
     *  (1–6 dp) and its own colour (wheel sheet).
     *  Slider drags update the page live; the
     *  sheet's own views are updated in place
     *  (no rebuild mid-drag). */
    private fun showBorderSheet() {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            // 2.96: OPAQUE solid themed background (dialogCard) — the translucent glass let the page behind show through.
            background = Theme.dialogCard(this@LoginPersonalizationActivity)
        }
        val dlg = bottomDialog(ScrollView(this).apply { addView(sheet) })
        sheet.addView(TextView(this).apply {
            text = L("حاشیه‌ی دور دکمه‌ها", "Key border"); textSize = 20f
            setTypeface(null, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowFg())
            setPadding(0, 0, 0, dp(12))
        })
        var pill: TextView? = null
        val toggle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 2.90: shared glass (was #F5F5F5). Row order stays LTR.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = Theme.glassCard(this@LoginPersonalizationActivity, 16f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            isClickable = true
        }
        toggle.addView(TextView(this).apply {
            text = L("حاشیه نئونی دور دکمه‌ها", "Neon border around the keys"); textSize = 16f
            setTypeface(null, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowFg())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        pill = TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        toggle.addView(pill)
        fun refreshPill() {
            val on = CalcTheme.neonBorder(this)
            pill?.text = if (on) L("روشن", "On") else L("خاموش", "Off")
            // 2.95: neon pill when on, glass when off — synced
            // with night/day (was a fixed light-green pill).
            if (on) {
                pill?.setTextColor(Theme.neonTextColor(this@LoginPersonalizationActivity))
                pill?.background = Theme.neonRect(this@LoginPersonalizationActivity, 20f)
            } else {
                pill?.setTextColor(rowSub())
                pill?.background = Theme.glassCard(this@LoginPersonalizationActivity, 20f)
            }
        }
        toggle.setOnClickListener {
            CalcTheme.setNeonBorder(this, !CalcTheme.neonBorder(this))
            rebuild()
            refreshPill()
        }
        refreshPill()
        sheet.addView(toggle)
        // (same pattern as the opacity sheet's sample):
        // dragging updates this sample only, not the
        // whole page, so weak phones do not lag per tick.
        val sampleHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        sheet.addView(sampleHolder)
        fun refreshSample() {
            sampleHolder.removeAllViews()
            sampleHolder.addView(TextView(this).apply {
                text = "1"; textSize = 20f; gravity = Gravity.CENTER
                setTextColor(CalcTheme.keyTextColor(this@LoginPersonalizationActivity))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.parseColor("#E8F5F9"))
                    setStroke((CalcTheme.borderWidthDp(this@LoginPersonalizationActivity) * dp(1)).toInt().coerceAtLeast(1), CalcTheme.borderColor(this@LoginPersonalizationActivity))
                }
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            })
        }
        refreshSample()
        val thickValue = TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LOCALE
            setTextColor(rowFg())
            setPadding(0, 0, 0, dp(4))
        }
        sheet.addView(TextView(this).apply {
            text = L("کلفتی حاشیه", "Border thickness"); textSize = 16f
            setTypeface(null, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowFg())
        })
        sheet.addView(thickValue)
        val thickBar = SeekBar(this).apply {
            max = 10
            progress = ((CalcTheme.borderWidthDp(this@LoginPersonalizationActivity) - 1f) * 2f).toInt().coerceIn(0, 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val dpv = 1f + p * 0.5f
                    thickValue.text = fmtDp(dpv)
                    CalcTheme.setBorderWidthDp(this@LoginPersonalizationActivity, dpv)
                    // refreshes; the full page rebuild waits
                    // until the finger lifts.
                    refreshSample()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    rebuild()
                }
            })
        }
        thickValue.text = fmtDp(CalcTheme.borderWidthDp(this))
        sheet.addView(thickBar)
        var chip: View? = null
        var chipHex: TextView? = null
        val colorRow = glassOptionRow(
            TextView(this).apply {
                text = ""; background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(CalcTheme.borderColor(this@LoginPersonalizationActivity))
                }
            },
            L("رنگ حاشیه", "Border colour"),
            hexOf(CalcTheme.borderColor(this))
        ) { showWheelSheet(L("رنگ حاشیه", "Border colour"), CalcTheme.borderColor(this), "border") { c ->
            CalcTheme.setBorderColor(this, c)
            rebuild()
            chip?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
            }
            chipHex?.text = hexOf(c)
        } }
        chip = (colorRow.getChildAt(0) as View)
        val colorCol = colorRow.getChildAt(1) as LinearLayout
        chipHex = colorCol.getChildAt(1) as TextView
        sheet.addView(colorRow)
        sheet.addView(closeButton(dlg))
        try {
                    } catch (_: Exception) {}
        dlg.show()
    }

    /** Opacity sheet: one slider (0–100 %),
     *  live on the page and on its own sample. */
    private fun showOpacitySheet() {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            // 2.96: OPAQUE solid themed background (dialogCard) — the translucent glass let the page behind show through.
            background = Theme.dialogCard(this@LoginPersonalizationActivity)
        }
        val dlg = bottomDialog(ScrollView(this).apply { addView(sheet) })
        sheet.addView(TextView(this).apply {
            text = L("شفافیت دکمه‌ها", "Key transparency"); textSize = 20f
            setTypeface(null, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowFg())
            setPadding(0, 0, 0, dp(12))
        })
        val sampleHolder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }
        sheet.addView(sampleHolder)
        fun refreshSample() {
            sampleHolder.removeAllViews()
            sampleHolder.addView(TextView(this).apply {
                text = "1"; textSize = 20f; gravity = Gravity.CENTER
                setTextColor(CalcTheme.keyTextColor(this@LoginPersonalizationActivity))
                background = CalcTheme.keyBackground(CalcTheme.shape(this@LoginPersonalizationActivity), false, this@LoginPersonalizationActivity)
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            })
        }
        refreshSample()
        val opValue = TextView(this).apply {
            text = CalcTheme.keyTransparency(this@LoginPersonalizationActivity).toString() + "%"
            textSize = 14f; gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LOCALE
            setTextColor(rowFg())
            setPadding(0, 0, 0, dp(4))
        }
        sheet.addView(opValue)
        val opBar = SeekBar(this).apply {
            max = 20
            progress = (CalcTheme.keyTransparency(this@LoginPersonalizationActivity) / 5).coerceIn(0, 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val t = p * 5
                    opValue.text = "$t%"
                    CalcTheme.setKeyTransparency(this@LoginPersonalizationActivity, t)
                    rebuild()
                    refreshSample()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        sheet.addView(opBar)
        sheet.addView(TextView(this).apply {
            text = L("صفر یعنی دکمه‌ها کاملاً دیده می‌شوند؛ صد یعنی خیلی شفاف‌اند.", "Zero means the keys are fully visible; a hundred means they are very transparent.")
            textSize = 13f; setTextColor(rowSub())
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, dp(8), 0, 0)
        })
        sheet.addView(closeButton(dlg))
        dlg.show()
    }

    /** One round colour swatch with a check when selected. */
    private fun swatch(color: Int, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = if (selected) "✓" else ""
            textSize = 16f; gravity = Gravity.CENTER
            setTextColor(if (CalcTheme.isDark(color)) Color.WHITE else Color.parseColor("#333333"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(if (selected) dp(3) else dp(1),
                    if (selected) Color.parseColor("#333333") else Color.parseColor("#E0E0E0"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** One On/Off toggle row (mirrors the welcome row). */
    private fun toggleRow(title: String, sub: String, on: Boolean, onToggle: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 2.90: shared glass (was #F5F5F5). Row order stays LTR.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = Theme.glassCard(this@LoginPersonalizationActivity, 16f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            isClickable = true
            setOnClickListener { onToggle() }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = title; textSize = 16f
            setTypeface(null, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowFg())
        })
        col.addView(TextView(this).apply {
            text = sub; textSize = 13f
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowSub())
        })
        row.addView(col)
        row.addView(TextView(this).apply {
            text = if (on) L("روشن", "On") else L("خاموش", "Off")
            textSize = 14f; gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            // 2.95: neon pill when on, glass when off — synced
            // with night/day (was a fixed light-green pill).
            if (on) {
                setTextColor(Theme.neonTextColor(this@LoginPersonalizationActivity))
                background = Theme.neonRect(this@LoginPersonalizationActivity, 20f)
            } else {
                setTextColor(rowSub())
                background = Theme.glassCard(this@LoginPersonalizationActivity, 20f)
            }
        })
        return row
    }

    // ---------- Style sheet: shape + size + font ----------

    private fun showStyleSheet() {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            // 2.96: OPAQUE solid themed background (dialogCard) — the translucent glass let the page behind show through.
            background = Theme.dialogCard(this@LoginPersonalizationActivity)
        }
        val scroll = ScrollView(this).apply { addView(sheet) }
        val dlg = bottomDialog(scroll)
        sheet.addView(TextView(this).apply {
            text = L("شکل، اندازه و فونت دکمه‌ها", "Button shape, size and font"); textSize = 20f
            setTypeface(null, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowFg())
            setPadding(0, 0, 0, dp(12))
        })

        fun optionRow(preview: View, name: String, desc: String, selected: Boolean, onClick: () -> Unit): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 2.90: row order stays LTR.
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                if (selected) background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    // 2.90: dark-green highlight in night (was light green even in night).
                    setColor(if (Theme.isDark(this@LoginPersonalizationActivity)) Theme.darken(Color.parseColor("#1E3A24")) else Color.parseColor("#E8F5E9"))
                }
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                setOnClickListener { onClick() }
            }
            row.addView(preview)
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(12), 0)
                }
            }
            col.addView(TextView(this).apply {
                text = name; textSize = 16f
                textDirection = View.TEXT_DIRECTION_LOCALE
                gravity = Gravity.START
                setTextColor(rowFg())
            })
            col.addView(TextView(this).apply {
                text = desc; textSize = 13f
                textDirection = View.TEXT_DIRECTION_LOCALE
                gravity = Gravity.START
                setTextColor(rowSub())
            })
            row.addView(col)
            if (selected) {
                row.addView(TextView(this).apply {
                    text = "✓"; textSize = 20f
                    setTextColor(Color.parseColor("#2E9E5B"))
                })
            }
            return row
        }

        sheet.addView(TextView(this).apply {
            text = L("شکل دکمه‌ها", "Button shape"); textSize = 16f
            setTypeface(null, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowFg())
            setPadding(0, dp(8), 0, dp(4))
        })
        val shapes = listOf(
            Triple("round", L("گرد", "Round"), L("مستطیل با گوشه‌های گرد", "Rounded rectangle")),
            Triple("square", L("مربعی", "Square"), L("مستطیل با گوشه‌های کم‌گرد", "Slightly rounded rectangle")),
            Triple("circle", L("دایره‌ای", "Circle"), L("دکمه‌های کاملاً دایره‌ای", "Fully circular buttons")),
            Triple("pill", L("کپسولی", "Pill"), L("دکمه‌های کشیده و کاملاً گرد", "Stadium-shaped buttons")),
            Triple("soft", L("نرم", "Soft"), L("مستطیل با گوشه‌های نرم ۱۶", "Soft 16dp corners"))
        )
        val curShape = CalcTheme.shape(this)
        for ((id, name, desc) in shapes) {
            val pv = TextView(this).apply {
                text = "۷"; textSize = 17f; gravity = Gravity.CENTER
                setTextColor(CalcTheme.keyTextColor(this@LoginPersonalizationActivity))
                background = CalcTheme.keyBackground(id, false, this@LoginPersonalizationActivity)
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            }
            sheet.addView(optionRow(pv, name, desc, id == curShape) {
                CalcTheme.setShape(this@LoginPersonalizationActivity, id)
                rebuild()
                dlg.dismiss()
            })
        }

        sheet.addView(TextView(this).apply {
            text = L("اندازه دکمه‌ها", "Key size"); textSize = 16f
            setTypeface(null, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowFg())
            setPadding(0, dp(12), 0, dp(4))
        })
        val keySizes = listOf(
            Triple("small", L("کوچک", "Small"), L("دکمه‌های جمع‌وجورتر", "More compact keys")),
            Triple("normal", L("معمولی", "Normal"), L("اندازه استاندارد", "Standard size")),
            Triple("large", L("بزرگ", "Large"), L("دکمه‌های درشت‌تر برای لمس راحت‌تر", "Bigger keys, easier to tap"))
        )
        val curKeySize = getSharedPreferences("renamer_settings", MODE_PRIVATE).getString("calc_key_size", "normal") ?: "normal"
        for ((id, name, desc) in keySizes) {
            val scale = when (id) { "small" -> 0.85f; "large" -> 1.15f; else -> 1.0f }
            val pv = TextView(this).apply {
                text = "۷"; textSize = 17f * scale; gravity = Gravity.CENTER
                setTextColor(CalcTheme.keyTextColor(this@LoginPersonalizationActivity))
                background = CalcTheme.keyBackground(curShape, false, this@LoginPersonalizationActivity)
                layoutParams = LinearLayout.LayoutParams(dp(64), (dp(48) * scale).toInt())
            }
            sheet.addView(optionRow(pv, name, desc, id == curKeySize) {
                CalcTheme.setKeySize(this@LoginPersonalizationActivity, id)
                rebuild()
                dlg.dismiss()
            })
        }

        sheet.addView(TextView(this).apply {
            text = L("فونت نمایشگر", "Display font"); textSize = 16f
            setTypeface(null, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowFg())
            setPadding(0, dp(12), 0, dp(4))
        })
        val fonts = listOf(
            Triple("bold", L("پررنگ", "Bold"), L("خوانا و برجسته", "Clear and bold")),
            Triple("normal", L("معمولی", "Normal"), L("ساده", "Plain")),
            Triple("mono", L("تک‌فاصله‌ای", "Monospace"), L("مثل نمایشگرهای قدیمی", "Like old displays"))
        )
        val curFont = CalcTheme.displayFont(this)
        for ((id, name, desc) in fonts) {
            val pv = TextView(this).apply {
                text = "۱۲۳+۴۵"; textSize = 20f
                typeface = fontFor(id)
                setTextColor(CalcTheme.displayColor(this@LoginPersonalizationActivity))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            // The font sample IS the preview, so it takes the
            // text column's place: wrap it in a row manually.
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 2.90: row order stays LTR.
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding(0, dp(8), 0, dp(8))
                isClickable = true
                setOnClickListener {
                    CalcTheme.setDisplayFont(this@LoginPersonalizationActivity, id)
                    rebuild()
                    dlg.dismiss()
                }
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, dp(12), 0)
                }
            }
            col.addView(pv)
            col.addView(TextView(this).apply {
                text = name; textSize = 16f
                textDirection = View.TEXT_DIRECTION_LOCALE
                gravity = Gravity.START
                setTextColor(rowFg())
            })
            col.addView(TextView(this).apply {
                text = desc; textSize = 13f
                textDirection = View.TEXT_DIRECTION_LOCALE
                gravity = Gravity.START
                setTextColor(rowSub())
            })
            row.addView(col)
            if (id == curFont) {
                row.addView(TextView(this).apply {
                    text = "✓"; textSize = 20f
                    setTextColor(Color.parseColor("#2E9E5B"))
                })
            }
            sheet.addView(row)
        }
        dlg.show()
    }

    // ---------- Unlock sheet ----------

    private fun showUnlockSheet(onModeName: (String) -> Unit) {
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            // 2.96: OPAQUE solid themed background (dialogCard) — the translucent glass let the page behind show through.
            background = Theme.dialogCard(this@LoginPersonalizationActivity)
        }
        val scroll = ScrollView(this).apply { addView(sheet) }
        val dlg = bottomDialog(scroll)

        sheet.addView(TextView(this).apply {
            text = L("نحوه باز شدن برنامه", "How the app opens"); textSize = 20f
            setTypeface(null, Typeface.BOLD)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setTextColor(rowFg())
            setPadding(0, 0, 0, dp(4))
        })
        sheet.addView(TextView(this).apply {
            text = L(
                "همه این گزینه‌ها انتخابی‌اند؛ پیش‌فرض «با رمز» است. «بدون رمز» فقط بخش عمومی را باز می‌کند و رمزها پاک نمی‌شوند — با برگشتن به «با رمز»، همان رمزها دوباره کار می‌کنند.",
                "All of these are optional; the default is \"with password\". \"Without password\" opens only the public section and does not erase the PINs — switching back to \"with password\" restores them."
            )
            textSize = 13f; setTextColor(rowSub())
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, 0, 0, dp(12))
        })

        val modesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val shakeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheet.addView(modesBox)
        sheet.addView(shakeBox)

        val unlockModes = listOf(
            Triple("password", L("با رمز", "With password"), L("مثل قبل: فقط رمز کافی است", "As before: the PIN alone is enough")),
            Triple("password_shake", L("با رمز + لرزش گوشی", "With password + phone shake"), L("بعد از رمز درست، گوشی را به تعداد انتخاب‌شده در پایین تکان بده تا باز شود؛ فقط رمز کافی نیست", "After the correct PIN, shake the phone as many times as chosen below; the PIN alone is not enough")),
            Triple("shake_only", L("فقط لرزش گوشی (بدون رمز)", "Phone shake only (no password)"), L("بدون رمز: گوشی را به تعداد انتخاب‌شده تکان بده تا فقط بخش عمومی باز شود؛ بخش خصوصی فقط با رمز باز می‌شود", "No password: shake the phone as many times as chosen to open only the public section; the private section opens only with the password")),
            Triple("passwordless", L("بدون رمز", "Without password"), L("بدون صفحه رمز، مستقیم بخش عمومی باز می‌شود", "Opens the public section directly, no PIN screen"))
        )
        fun refresh() {
            val curUnlock = getSharedPreferences("renamer_settings", MODE_PRIVATE).getString("unlock_mode", "password") ?: "password"
            modesBox.removeAllViews()
            for ((id, name, desc) in unlockModes) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                    isClickable = true
                    setOnClickListener {
                        getSharedPreferences("renamer_settings", MODE_PRIVATE).edit().putString("unlock_mode", id).apply()
                        onModeName(name)
                        refresh()
                    }
                }
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                col.addView(TextView(this).apply {
                    text = name; textSize = 16f
                    textDirection = View.TEXT_DIRECTION_LOCALE
                    gravity = Gravity.START
                    setTextColor(rowFg())
                })
                col.addView(TextView(this).apply {
                    text = desc; textSize = 13f
                    textDirection = View.TEXT_DIRECTION_LOCALE
                    gravity = Gravity.START
                    setTextColor(rowSub())
                })
                row.addView(col)
                if (id == curUnlock) {
                    row.addView(TextView(this).apply {
                        text = "✓"; textSize = 20f
                        setTextColor(Color.parseColor("#2E9E5B"))
                    })
                }
                modesBox.addView(row)
                modesBox.addView(divider())
            }
            // Shake count is shown ONLY when a shake
            // method is chosen — never for the other modes.
            shakeBox.removeAllViews()
            if (curUnlock == "password_shake" || curUnlock == "shake_only") {
                shakeBox.addView(TextView(this).apply {
                    text = L("تعداد لرزش گوشی", "Phone shake count"); textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                    textDirection = View.TEXT_DIRECTION_LOCALE
                    gravity = Gravity.START
                    setTextColor(rowFg())
                    setPadding(0, dp(12), 0, dp(4))
                })
                shakeBox.addView(TextView(this).apply {
                    text = L(
                        "در حالت‌های «با رمز + لرزش» و «فقط لرزش»، گوشی باید چند بار تکان بخورد تا باز شود. «لرزش» یعنی تکان دادن خود گوشی، نه لرزیدن آن.",
                        "In \"with password + shake\" and \"shake only\" modes, this is how many times the phone must be shaken to open. \"Shake\" means shaking the phone itself, not the phone vibrating."
                    )
                    textSize = 13f; setTextColor(rowSub())
                    textDirection = View.TEXT_DIRECTION_LOCALE
                    gravity = Gravity.START
                    setPadding(0, 0, 0, dp(8))
                })
                val curVib = getSharedPreferences("renamer_settings", MODE_PRIVATE).getInt("unlock_shake_count", 3)
                for (count in 1..5) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(8), 0, dp(8))
                        isClickable = true
                        setOnClickListener {
                            getSharedPreferences("renamer_settings", MODE_PRIVATE).edit().putInt("unlock_shake_count", count).apply()
                            refresh()
                        }
                    }
                    row.addView(TextView(this).apply {
                        text = L("$count بار", "$count times"); textSize = 16f
                        textDirection = View.TEXT_DIRECTION_LOCALE
                        gravity = Gravity.START
                        setTextColor(rowFg())
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    if (count == curVib) {
                        row.addView(TextView(this).apply {
                            text = "✓"; textSize = 20f
                            setTextColor(Color.parseColor("#2E9E5B"))
                        })
                    }
                    shakeBox.addView(row)
                    shakeBox.addView(divider())
                }
            }
        }
        refresh()

        sheet.addView(Button(this).apply {
            text = L("بستن", "Close")
            textSize = 14f; isAllCaps = false
            textDirection = View.TEXT_DIRECTION_LOCALE
            // 2.95: neon button, like the rest of the app.
            background = Theme.neonRect(this@LoginPersonalizationActivity, 22f)
            setTextColor(Theme.neonTextColor(this@LoginPersonalizationActivity))
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { dlg.dismiss() }
        })
        dlg.show()
    }

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text; textSize = 18f
        // 2.90: orange header colour is unchanged; only the text direction is set.
        setTextColor(Color.parseColor("#FF8C00"))
        textDirection = View.TEXT_DIRECTION_LOCALE
        gravity = Gravity.START
        // 3.11: own side padding (same fitsSystemWindows cause
        // as the title — this helper draws every orange header,
        // including "Live preview" and "Login studio").
        setPadding(dp(20), dp(16), dp(20), dp(8))
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).apply { setMargins(0, dp(4), 0, dp(4)) }
        setBackgroundColor(Color.parseColor("#DDDDDD"))
    }
}
