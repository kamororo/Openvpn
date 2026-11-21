package com.renamer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*

/**
 * Guide page ("راهنمایی").
 *
 * One row per app section; tapping a row opens a GLASS-style
 * detail dialog: a rounded translucent panel (light translucent
 * white, or dark translucent when night mode is on, detected
 * via Theme.isDark) with a scrollable explanation and a Close
 * button, so long texts are fully readable.
 *
 * Every explanation describes REAL features of this app,
 * including the newest ones. If a feature is later removed,
 * its row must be removed here too.
 *
 * STANDING RULE (2.81, user order): in EVERY future update, if
 * something changes and its guide text needs it, update the guide
 * too — both inside the section itself (HelpUi) and here in
 * Settings' Guide — proactively, without asking the user.
 */
class GuideActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    /** Crops a bitmap to the bounding box of its
     *  opaque pixels (alpha > 16). Galaxy icons carry
     *  different transparent padding, which used to
     *  make rows look unevenly spaced; after cropping,
     *  every glyph fills the same 40dp box evenly.
     *  Returns the original when there is nothing to
     *  crop (opaque or fully transparent). */
    private fun cropTransparent(src: android.graphics.Bitmap): android.graphics.Bitmap {
        return try {
            if (!src.hasAlpha()) return src
            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            var left = w
            var top = h
            var right = -1
            var bottom = -1
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val a = (px[y * w + x] ushr 24) and 0xFF
                    if (a > 16) {
                        if (x < left) left = x
                        if (x > right) right = x
                        if (y < top) top = y
                        if (y > bottom) bottom = y
                    }
                }
            }
            if (right < left || bottom < top) src
            else android.graphics.Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1)
        } catch (_: Exception) { src }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private data class Section(val titleFa: String, val titleEn: String, val summaryFa: String, val summaryEn: String, val bodyFa: String, val bodyEn: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2.84: shared background + no white window strips.
        try { Theme.applyPage(this) } catch (_: Exception) {}
        // 2.89: the background lives on the scroll (full-screen),
        // not on the content-height root — one uniform background.
        val scroll = ScrollView(this).apply {
            background = Theme.pageBackground(this@GuideActivity)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = L("راهنمایی", "Guide"); textSize = 22f
            setTextColor(Theme.fg(this@GuideActivity))
            gravity = if (AppLanguage.isEnglish(this@GuideActivity)) Gravity.LEFT else Gravity.RIGHT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = L(
                "روی هر بخش بزن تا توضیح کاملش را ببینی.",
                "Tap a section to see its full explanation."
            )
            textSize = 13f; setTextColor(Theme.subFg(this@GuideActivity))
            gravity = if (AppLanguage.isEnglish(this@GuideActivity)) Gravity.LEFT else Gravity.RIGHT
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(0, 0, 0, dp(16))
        })

        // New app: no hidden mode — every row below is shown.
        // 1.29: engine + subs rows are gone with those
        // sections; wireguard + widgets rows join.
        val rows = mutableListOf(
            Triple("home", L("صفحه اصلی", "Home page"), L("سه کاشی در هر ردیف و نسخه برنامه", "Three tiles per row and app version")),
            Triple("quickconnect", L("کارت اتصال سریع", "Quick-connect card"), L("یک لمس به آخرین روشی که وصل شده بودی", "One tap back to the last method you connected with")),
            Triple("v2ray", L("v2ray", "v2ray"), L("لیست سرورها، دکمه اتصال و پرچم کشور؛ زبان v2ray فقط همین بخش را عوض می‌کند", "Server list, connect button and country flag; the v2ray language changes only this section")),
            Triple("wireguard", L("وایرگارد و امنزیا", "WireGuard & Amnezia"), L("لیست تونل‌ها، افزودن و وارد کردن کانفیگ، اتصال", "Tunnel list, add and import configs, connect")),
            Triple("warp", L("وارپ رایگان", "Free WARP"), L("دریافت کانفیگ رایگان وارپ با MASQUE", "Free WARP configs over MASQUE")),
            Triple("openvpn", L("OpenVPN", "OpenVPN"), L("تونل رسمی OpenVPN3 با فایل .ovpn", "Official OpenVPN3 tunnel with .ovpn files")),
            Triple("emergency", L("اتصال اضطراری", "Emergency connect"), L("یک دکمه؛ کمی صبر تا به بهترین سرور وصل شود", "One button; wait a little for the best server")),
            Triple("smart", L("اتصال هوشمند", "Smart Connect"), L("وصل خودکار با باز شدن اپ، قطع خودکار با بسته شدنش", "Auto-connect when an app opens, auto-off when it closes")),
            Triple("cloudflare", L("کلودفلر", "Cloudflare"), L("مدیریت پنل نهان خودتان: کاربرها، آی‌پی‌ها، کشور پروکسی و اشتراک؛ تم تازه: شفق در شب، مروارید در روز", "Manage your own Nahan panel: users, IPs, proxy country and subscription; fresh theme: aurora at night, pearl in day")),
            Triple("widgets", L("ویجت صفحه اصلی", "Home screen widgets"), L("یک بخش را به صفحه اصلی ببر تا بدون باز کردن برنامه وصل شوی", "Put a section on the home screen to connect without opening the app")),
            Triple("notification", L("اعلان اتصال", "Connection notification"), L("سرعت، کشور و دکمه‌های قطع/وصل/ری‌استارت", "Speed, country and disconnect/connect/restart buttons")),
            Triple("homesettings", L("تنظیمات صفحه اصلی", "Home page settings"), L("ردیف‌های فشرده آیکون‌دار: مرتب‌سازی، راهنما، زبان و حالت شب", "Compact icon rows: sorting, guide, language and night mode")),
            Triple("language", L("زبان برنامه", "App language"), L("فارسی یا انگلیسی؛ از تنظیمات عوض می‌شود", "Persian or English; switched from Settings")),
            Triple("night", L("حالت شب", "Night mode"), L("تم تیره صفحه‌ها؛ صفحه اصلی هم در شب تیره و در روز مرواریدی است", "Dark theme for the pages; the home page is dark at night and pearl in day"))
        )
        // 2.90: guide icons are the 2.83 pro-2 home assets
        // where one exists for the section (night art; the
        // _day variant is picked below in day mode). Rows
        // with no section asset (home, quick-connect,
        // widgets, notification, home settings, language,
        // night) use the same new-style gradient glyph as
        // the Settings rows instead of the old sub/vpn art.
        val glyphs = mapOf(
            "home" to "⌂", "quickconnect" to "⏻",
            "widgets" to "▦", "notification" to "✉",
            "homesettings" to "⚙", "language" to "فا", "night" to "☾"
        )
        val icons = mapOf(
            "home" to "sub.png", "quickconnect" to "vpn.png", "v2ray" to "home_v2ray.png",
            "wireguard" to "home_wireguard.png", "openvpn" to "home_openvpn.png",
            "emergency" to "home_emergency.png", "smart" to "home_smart.png", "widgets" to "sub.png",
            "notification" to "vpn.png", "homesettings" to "sub.png",
            "cloudflare" to "home_cloudflare.png",
            "language" to "sub.png", "night" to "sub.png"
        )

    // 2.22: cropTransparent is defined below and used
    // for every row icon, so PNGs with different
    // transparent padding all sit evenly.
        val night = Theme.isDark(this)
        for ((key, title, summary) in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                isClickable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    val i = Intent(this@GuideActivity, GuideDetailActivity::class.java)
                    i.putExtra("section", key)
                    startActivity(i)
                }
            }
            // Icon ALWAYS on the right, text on its left, in both
            // languages: the row is forced LTR, the text column
            // comes first (weight 1), the icon last. FA text is
            // right-aligned (toward the icon), EN text starts at
            // the left edge — so Latin titles no longer jump to
            // the opposite side from Persian ones.
            val textGravity = if (AppLanguage.isEnglish(this)) Gravity.LEFT else Gravity.RIGHT
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = title; textSize = 15f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                setTextColor(Theme.fg(this@GuideActivity))
                gravity = textGravity
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            col.addView(TextView(this).apply {
                text = summary; textSize = 12f
                setTextColor(Theme.subFg(this@GuideActivity))
                gravity = textGravity
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            row.addView(col)
            try {
                val assetName = icons[key] ?: "vpn.png"
                val bmp: android.graphics.Bitmap = if (assetName.startsWith("home_")) {
                    val assetFile = if (night) assetName else assetName.replace(".png", "_day.png")
                    cropTransparent(android.graphics.BitmapFactory.decodeStream(assets.open(assetFile)))
                } else {
                    Theme.gradientGlyphBitmap(this, glyphs[key] ?: "✦", 40, night)
                }
                row.addView(android.widget.ImageView(this).apply {
                    setImageBitmap(bmp)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        setMargins(dp(12), 0, 0, 0)
                    }
                })
            } catch (_: Exception) {}
            root.addView(row)
            root.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(0, dp(4), 0, dp(4)) }
                setBackgroundColor(Theme.divider(this@GuideActivity))
            })
        }

        // 2.95: no Back button on this page (user order); the
        // system back button/gesture closes it, like the
        // Settings page since 2.91.
        setContentView(scroll)
        scroll.fitsSystemWindows = true
    }

}
