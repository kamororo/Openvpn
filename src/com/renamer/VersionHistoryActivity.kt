package com.renamer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*

/**
 * Version history (2.22): opened by tapping the
 * version label on Home. Shows the LATEST version's
 * changes first; a glass button opens a version
 * picker (a beautiful bottom sheet listing the
 * versions), and picking one closes the sheet and
 * shows ONLY that version's changes, numbered.
 *
 * Only the CURRENT version is kept (1.73, user
 * order): previous versions' info was deleted.
 * When a new version is added, replace this
 * entry — do not re-add old ones.
 */
class VersionHistoryActivity : Activity() {

    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private data class VersionEntry(
        val version: String,
        val code: Int,
        val highlight: String,
        val changesFa: List<String>,
        val changesEn: List<String>
    )

    // UPDATE WITH EVERY BUILD — keep ONLY the current version's entry.
    // با هر بیلد به‌روز کن — فقط همین یک ورژن بماند؛ ورژن قبلی را حذف کن.
    // 1.73 (user order): previous versions' info deleted; history holds
    // this version only.
    private val VERSIONS = listOf(
        VersionEntry("1.73", 74,
            "بخش تست سرعت به صفحه اصلی آمد، باکس تست سرعت حذف شد، حالت شب حدود ۳۰٪ تیره‌تر شد، بررسی بروزرسانی اضافه شد و باگ مصرف نشست وارپ رفع شد",
            listOf(
                "بخش «تست سرعت» به صفحه اصلی اضافه شد: کاشی هفتم در شبکه اصلی که یک پنل شناور باز می‌کند؛ سرعت دانلود و آپلود و پینگ را روی همان تونلی که وصل است اندازه می‌گیرد",
                "باکس «تست سرعت و مانیتورینگ» از صفحه اصلی برداشته شد؛ تست سرعت حالا فقط از کاشی جدید انجام می‌شود",
                "حالت شب حدود ۳۰٪ تیره‌تر شد: همه صفحه‌ها — شامل صفحه اصلی، هاله شب، کاشی‌ها و نوار پایینی — با همان نسبت تیره شدند",
                "بررسی بروزرسانی: گزینه جدید در انتهای تنظیمات و بررسی خودکار هنگام باز شدن برنامه؛ اگر نسخه جدید در گیت‌هاب باشد، دانلود و نصب از داخل برنامه انجام می‌شود.",
                "رفع باگ مصرف نشست وارپ: مصرف نشست‌های وارپ (MASQUE) صفر نشان داده می‌شد چون شمارنده‌های تونل (tun0) ترافیک موتور aether را نمی‌بینند؛ حالا مصرف از شمارنده‌های خود برنامه خوانده می‌شود و نمودار سرعت زنده هم از همان عدد می‌آید."
            ),
            listOf(
                "A «Speed test» section was added to the home page: a seventh tile in the main grid that opens a floating panel; it measures download and upload speed and ping over the tunnel that is connected",
                "The «Speed test & monitoring» box was removed from the home page; speed testing now happens only from the new tile",
                "Night mode is about 30% darker: every page — including the home page, the night aurora, the tiles and the bottom bar — was darkened by the same factor",
                "Update checker: a new option at the end of Settings plus an automatic check on launch; when a newer version is on GitHub, it downloads and installs from inside the app.",
                "Fixed warp session usage showing 0: MASQUE sessions read 0 because the tunnel counters (tun0) never see the aether engine's traffic; usage now comes from the app's own UID counters, and the live speed graph is fed by the same numbers."
            )),
    )
    private lateinit var contentBox: LinearLayout
    private lateinit var pickerBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2.84: shared background + no white window strips.
        try { Theme.applyPage(this) } catch (_: Exception) {}
        // 2.89: the background lives on the scroll (full-screen),
        // not on the content-height root — one uniform background.
        val scroll = ScrollView(this).apply {
            background = Theme.pageBackground(this@VersionHistoryActivity)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            text = L("تاریخچه ورژن‌ها", "Version history")
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.fg(this@VersionHistoryActivity))
        })
        root.addView(TextView(this).apply {
            text = L(
                "تغییرات هر ورژن، مختصر و شماره‌دار. اول تغییرات آخرین ورژن را می‌بینی؛ با دکمه پایین ورژن دیگری را انتخاب کن.",
                "Each version's changes, short and numbered. You see the latest version first; pick another one with the button below.")
            textSize = 13f
            setTextColor(Theme.subFg(this@VersionHistoryActivity))
            setPadding(0, dp(6), 0, dp(16))
        })
        pickerBtn = Button(this).apply {
            textSize = 14f
            isAllCaps = false
            // 2.92: the version picker button is the neon button.
            setTextColor(Theme.neonTextColor(this@VersionHistoryActivity))
            background = Theme.neonRect(this@VersionHistoryActivity, 24f)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
            setOnClickListener { showVersionPicker() }
        }
        root.addView(pickerBtn)
        contentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(contentBox)
        // 2.23: no Back button on this page (user
        // order); the system back gesture/button
        // still leaves it. fitsSystemWindows keeps
        // the bottom content above the phone's
        // navigation buttons — this page was the
        // one full page missing it.
        scroll.fitsSystemWindows = true
        setContentView(scroll)
        // Before any pick: the latest version's info.
        render(VERSIONS.first())
    }

    private fun faNum(n: Int): String {
        val map = mapOf('0' to '۰', '1' to '۱', '2' to '۲', '3' to '۳', '4' to '۴',
            '5' to '۵', '6' to '۶', '7' to '۷', '8' to '۸', '9' to '۹')
        return n.toString().map { map[it] ?: it }.joinToString("")
    }

    private fun render(v: VersionEntry) {
        pickerBtn.text = L("انتخاب ورژن ▾  (الان: ${v.version})", "Pick a version ▾  (now: ${v.version})")
        contentBox.removeAllViews()
        contentBox.addView(TextView(this).apply {
            text = L("ورژن ${v.version}", "Version ${v.version}")
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            // 2.85: #1B365D was dark-on-dark in night (page root).
            setTextColor(Color.parseColor(if (Theme.isDark(context)) "#A5C8FF" else "#1B365D"))
        })
        contentBox.addView(TextView(this).apply {
            text = L("کد ${faNum(v.code)}  •  ${v.highlight}", "Code ${v.code}  •  ${v.highlight}")
            textSize = 13f
            // 2.85: GRAY was dark-on-dark in night (page root).
            setTextColor(Theme.subFg(context))
            setPadding(0, dp(2), 0, dp(14))
        })
        val changes = if (AppLanguage.isEnglish(this)) v.changesEn else v.changesFa
        for ((i, c) in changes.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                // 2.92: change rows were fixed white in night.
                background = Theme.glassCard(this@VersionHistoryActivity, 16f)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            row.addView(TextView(this).apply {
                text = faNum(i + 1) + "."
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(if (Theme.isDark(this@VersionHistoryActivity)) Color.parseColor("#7ED491") else Color.parseColor("#2E7D32"))
                setPadding(0, 0, dp(10), 0)
            })
            row.addView(TextView(this).apply {
                text = c
                textSize = 14f
                setTextColor(Theme.fg(this@VersionHistoryActivity))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            contentBox.addView(row)
        }
    }

    /** Bottom sheet listing the versions, newest
     *  first. Tapping one closes the sheet and shows
     *  only that version's changes on the page. */
    private fun showVersionPicker() {
        val dlg = android.app.Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 2.84: shared dialog card (dark glass in night).
            background = Theme.dialogCard(context)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        card.addView(TextView(this).apply {
            text = L("انتخاب ورژن", "Pick a version")
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        })
        // 2.95: the rows live inside a ScrollView capped at 65%
        // of the screen height — before, they were added straight
        // to the card with WRAP_CONTENT dialog height, so when
        // the 10 rows overflowed the screen the dialog was cut
        // off and could not scroll at all.
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        for ((i, v) in VERSIONS.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            row.addView(TextView(this).apply {
                text = L("ورژن ${v.version}", "Version ${v.version}")
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(10)
                }
            })
            // 2.95: the highlight was WRAP_CONTENT with no width
            // limit, so long highlight text wrapped to many lines
            // and made the row very tall. Now it takes the
            // remaining width, max 2 lines, ellipsised, in the
            // theme's sub-text colour.
            row.addView(TextView(this).apply {
                text = v.highlight
                textSize = 12f
                setTextColor(Theme.subFg(this@VersionHistoryActivity))
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                textDirection = if (AppLanguage.isEnglish(this@VersionHistoryActivity))
                    View.TEXT_DIRECTION_LTR else View.TEXT_DIRECTION_RTL
                gravity = Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.setOnClickListener {
                dlg.dismiss()
                render(v)
            }
            rows.addView(row)
            if (i < VERSIONS.size - 1) {
                rows.addView(android.view.View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(Theme.divider(context))
                })
            }
        }
        val scroller = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.65).toInt())
            isVerticalScrollBarEnabled = true
        }
        scroller.addView(rows)
        card.addView(scroller)
        dlg.setContentView(card)
        val win = dlg.window
        if (win != null) {
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            val lp = win.attributes
            lp.gravity = Gravity.BOTTOM
            lp.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
            lp.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
            win.attributes = lp
        }
        dlg.show()
        // 2.84: readable text on the dark card in night.
        Theme.styleDialog(dlg, this)
    }
}
