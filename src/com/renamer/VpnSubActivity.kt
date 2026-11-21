package com.renamer

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Subscription group setting, v2rayNG-style:
 *  one row per subscription with its name, URL,
 *  last-update date, share / edit / delete icons
 *  and an enable toggle. Deleting a row removes
 *  the subscription only — its configs stay in the
 *  list (never auto-deleted). */
class VpnSubActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.v2rayT(this, fa, en)
    private lateinit var listBox: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2.87: this page had NO night mode at all (white in
        // night, user screenshot 7). It now uses the shared
        // page background + window chrome like every other
        // tap-opened page.
        Theme.applyPage(this)
        val dark = Theme.isDark(this)
        val d = resources.displayMetrics.density
        val scroll = ScrollView(this)
        scroll.background = Theme.pageBackground(this)
        // 2.88: the header sat cramped under the status bar (the 2.87
        // 24dp top padding was not enough — the scroll view never
        // applied the system insets). Real top padding now, and the
        // insets are applied, like the other pages' headers.
        scroll.fitsSystemWindows = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (40 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
        }
        scroll.addView(root)
        // Header: a themed glass back circle + the title.
        // (The old plain grey buttons sat half under the
        // status bar and the title floated unaligned.)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Button(this@VpnSubActivity).apply {
                text = "←"
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.fg(this@VpnSubActivity))
                // 2.88: the same refined glass circle as the card
                // actions elsewhere (thin gradient border + glow).
                background = Theme.gradientGlowCircle(this@VpnSubActivity,
                    if (dark) Theme.darken(Color.parseColor("#CC232840")) else Color.parseColor("#F5FFFFFF"), dark)
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt())
                setOnClickListener { finish() }
            })
            addView(TextView(this@VpnSubActivity).apply {
                text = L("تنظیمات گروه سابسکریبشن", "Subscription group setting")
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.fg(this@VpnSubActivity))
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * d).toInt(), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Theme.subFg(this@VpnSubActivity))
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        }
        root.addView(status)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 2.90: space between the buttons and the box below (were stuck together).
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (14 * d).toInt()
            }
            addView(Button(this@VpnSubActivity).apply {
                text = L("+ افزودن ساب", "+ Add subscription")
                textDirection = View.TEXT_DIRECTION_LOCALE
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                // 2.88: glass primary buttons — glass fill, teal→
                // purple gradient border, soft glow, like the big
                // teal buttons on the Subscriptions page. White
                // text on the dark teal glass at night, dark teal
                // text on the pale teal glass in day.
                // 2.90: low-alpha Theme.primaryFill, so the aurora
                // shows through (was 80%-opaque teal).
                setTextColor(if (dark) Color.WHITE else Color.parseColor("#0F766E"))
                background = Theme.gradientGlowRect(this@VpnSubActivity,
                    Theme.primaryFill(this@VpnSubActivity), dark, 12f)
                setPadding((18 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (8 * d).toInt()
                }
                setOnClickListener {
                    startActivity(Intent(this@VpnSubActivity, VpnSubEditActivity::class.java))
                }
            })
            addView(Button(this@VpnSubActivity).apply {
                text = L("به‌روزرسانی همه", "Update all")
                textDirection = View.TEXT_DIRECTION_LOCALE
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(if (dark) Color.WHITE else Color.parseColor("#0F766E"))
                background = Theme.gradientGlowRect(this@VpnSubActivity,
                    Theme.primaryFill(this@VpnSubActivity), dark, 12f)
                setPadding((18 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (8 * d).toInt()
                }
                setOnClickListener { updateAll() }
            })
        })
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)
        setContentView(scroll)
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun fmtDate(ms: Long): String {
        if (ms <= 0) return L("هرگز", "Never")
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }

    private fun refreshList() {
        listBox.removeAllViews()
        val d = resources.displayMetrics.density
        val items = VpnSubs.loadItems(this)
        if (items.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = L("هنوز سابی اضافه نشده است.", "No subscription added yet.")
                setTextColor(Theme.subFg(this@VpnSubActivity))
                setPadding(0, (16 * d).toInt(), 0, 0)
            })
            return
        }
        for (item in items) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
                // 2.87: the card follows the mode — dark glass
                // in night (light text), the same soft card as
                // before in day.
                background = if (Theme.isDark(this@VpnSubActivity)) Theme.pageCard(this@VpnSubActivity, 16f)
                else android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 16 * resources.displayMetrics.density
                    setColor(Color.parseColor("#F2F5F9"))
                    setStroke(1, Color.parseColor("#4A5A7A"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, (8 * d).toInt()) }
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(TextView(this).apply {
                text = item.displayName(); textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.fg(this@VpnSubActivity))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            top.addView(android.widget.ImageView(this).apply {
                // 2.88: the enable toggle matches the themed toggle
                // on the WG/OVPN pages (teal ON, grey OFF) instead
                // of the flat framework Switch.
                setImageBitmap(Theme.modeToggleBitmap(this@VpnSubActivity, item.enabled))
                layoutParams = LinearLayout.LayoutParams((49 * d).toInt(), (25 * d).toInt())
                setOnClickListener {
                    item.enabled = !item.enabled
                    val all = VpnSubs.loadItems(this@VpnSubActivity)
                    val idx = all.indexOfFirst { it.url == item.url }
                    if (idx >= 0) all[idx] = item
                    VpnSubs.saveItems(this@VpnSubActivity, all)
                    setImageBitmap(Theme.modeToggleBitmap(this@VpnSubActivity, item.enabled))
                }
            })
            card.addView(top)
            card.addView(TextView(this).apply {
                text = item.url; textSize = 13f
                setTextColor(Theme.subFg(this@VpnSubActivity))
                ellipsize = TextUtils.TruncateAt.END
                maxLines = 1
            })
            card.addView(TextView(this).apply {
                text = L("آخرین به‌روزرسانی: ${fmtDate(item.lastUpdated)}  •  ${item.configKeys.size} کانفیگ",
                    "Last update: ${fmtDate(item.lastUpdated)}  •  ${item.configKeys.size} configs")
                textSize = 13f
                setTextColor(Theme.subFg(this@VpnSubActivity))
            })
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            // 2.88: the same refined glass circles as the card
            // actions elsewhere (thin gradient border + glow).
            fun actionBtn(glyph: String, onClick: () -> Unit) {
                actions.addView(Button(this).apply {
                    text = glyph
                    textSize = 16f
                    setTextColor(Theme.fg(this@VpnSubActivity))
                    background = Theme.gradientGlowCircle(this@VpnSubActivity,
                        if (Theme.isDark(this@VpnSubActivity)) Theme.darken(Color.parseColor("#CC232840")) else Color.parseColor("#F5FFFFFF"),
                        Theme.isDark(this@VpnSubActivity))
                    setPadding(0, 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply {
                        marginStart = (8 * d).toInt()
                    }
                    setOnClickListener { onClick() }
                })
            }
            actionBtn("↗") {
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("sub", item.url))
                    status.text = L("لینک ساب کپی شد.", "Subscription link copied.")
                } catch (_: Exception) {}
            }
            actionBtn("✎") {
                startActivity(Intent(this@VpnSubActivity, VpnSubEditActivity::class.java)
                    .putExtra("url", item.url))
            }
            actionBtn("✕") {
                android.app.AlertDialog.Builder(this@VpnSubActivity)
                    .setTitle(L("حذف سابسکریبشن", "Delete subscription"))
                    .setMessage(L("ساب «${item.displayName()}» حذف شود؟ کانفیگ‌هایش در لیست باقی می‌مانند.",
                        "Delete subscription “${item.displayName()}”? Its configs stay in the list."))
                    .setPositiveButton(L("حذف", "Delete")) { _, _ ->
                        val all = VpnSubs.loadItems(this@VpnSubActivity)
                        all.removeAll { it.url == item.url }
                        VpnSubs.saveItems(this@VpnSubActivity, all)
                        status.text = L("ساب حذف شد.", "Subscription deleted.")
                        refreshList()
                    }
                    .setNegativeButton(L("لغو", "Cancel"), null)
                    .show().also { Theme.styleDialog(it, this@VpnSubActivity) }
            }
            card.addView(actions)
            listBox.addView(card)
        }
    }

    private fun updateAll() {
        val items = VpnSubs.loadItems(this).filter { it.enabled }
        if (items.isEmpty()) {
            status.text = L("ساب فعالی برای به‌روزرسانی نیست.", "No enabled subscription to update.")
            return
        }
        status.text = L("در حال به‌روزرسانی ساب‌ها...", "Updating subscriptions...")
        Thread {
            var added = 0
            var received = 0
            var failed = 0
            val lines = mutableListOf<String>()
            for (item in items) {
                try {
                    val r = VpnSubs.fetchOne(this, item)
                    added += r.added
                    received += r.received
                    val label = VpnSubs.displayLabel(item)
                    if (r.message == "ok") {
                        lines += L("ساب $label: ${r.received} دریافت، ${r.added} جدید",
                            "Sub $label: ${r.received} received, ${r.added} new")
                    } else {
                        failed++
                        lines += L("ساب $label ناموفق: ${VpnSubs.reasonLabel(this, r.message)}",
                            "Sub $label failed: ${VpnSubs.reasonLabel(this, r.message)}")
                    }
                } catch (_: Exception) {
                    failed++
                    val label = VpnSubs.displayLabel(item)
                    lines += L("ساب $label ناموفق: خطای نامشخص",
                        "Sub $label failed: unknown error")
                }
            }
            runOnUiThread {
                val head = L("به‌روزرسانی انجام شد: $received کانفیگ دریافت شد، $added جدید اضافه شد.",
                    "Update done: $received configs received, $added new added.") +
                    if (failed > 0) L(" ($failed ساب ناموفق بود.)", " ($failed subs failed.)") else ""
                status.text = (listOf(head) + lines).joinToString("\n")
                refreshList()
            }
        }.start()
    }
}
