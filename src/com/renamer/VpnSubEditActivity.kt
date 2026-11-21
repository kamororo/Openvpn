package com.renamer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject

/** 2.88: the themed pill-toggle state for the subscription edit
 *  page — a small holder with the same `isChecked` read the old
 *  framework Switch had, so save() is unchanged. */
private class PillSwitch(var checked: Boolean) {
    val isChecked: Boolean get() = checked
}

/** Per-subscription settings, v2rayNG-style.
 *  Every option works: remarks, URL, user agent,
 *  request headers (JSON), remarks filter (regex,
 *  applied at import), enable update, automatic
 *  update + interval (min 15 min), allow insecure
 *  http, and prev/next proxy profiles (by remarks).
 *  Prev/next chain order at connect time is
 *  device -> prev -> config -> next -> destination
 *  (see VpnSubs.chainForConfig). */
class VpnSubEditActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.v2rayT(this, fa, en)

    private lateinit var status: TextView
    private lateinit var remarksEt: EditText
    private lateinit var urlEt: EditText
    private lateinit var uaEt: EditText
    private lateinit var headersEt: EditText
    private lateinit var filterEt: EditText
    private lateinit var intervalEt: EditText
    private lateinit var enabledSw: PillSwitch
    private lateinit var autoSw: PillSwitch
    private lateinit var insecureSw: PillSwitch
    private lateinit var prevSpinner: Spinner
    private lateinit var nextSpinner: Spinner
    private var originalUrl: String = ""
    private var profileOptions: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2.87: this page was unthemed too (white in night,
        // dark EditText text on dark cards). Same shared
        // page background + explicit theme colours as the
        // subscription list page.
        Theme.applyPage(this)
        val dark = Theme.isDark(this)
        originalUrl = intent.getStringExtra("url") ?: ""
        val items = VpnSubs.loadItems(this)
        val item = items.firstOrNull { it.url == originalUrl }
            ?: SubItem(url = originalUrl)
        val d = resources.displayMetrics.density
        val scroll = ScrollView(this)
        scroll.background = Theme.pageBackground(this)
        // 2.88: same header fix as the list page — the title sat
        // cramped under the status bar; real top padding now,
        // and the system insets are applied.
        scroll.fitsSystemWindows = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (40 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
        }
        scroll.addView(root)

        // Top bar: back, delete (existing only), save — themed
        // glass circles, aligned with the title.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(header)
        header.addView(Button(this@VpnSubEditActivity).apply {
            text = "←"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.fg(this@VpnSubEditActivity))
            // 2.88: refined glass circle (thin gradient border +
            // glow), like the card actions elsewhere.
            background = Theme.gradientGlowCircle(this@VpnSubEditActivity,
                if (dark) Theme.darken(Color.parseColor("#CC232840")) else Color.parseColor("#F5FFFFFF"), dark)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt())
            setOnClickListener { finish() }
        })
        header.addView(TextView(this@VpnSubEditActivity).apply {
            text = if (originalUrl.isBlank())
                L("افزودن سابسکریبشن", "Add subscription")
            else
                L("تنظیمات سابسکریبشن", "Subscription settings")
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.fg(this@VpnSubEditActivity))
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * d).toInt(), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (originalUrl.isNotBlank()) {
            header.addView(Button(this@VpnSubEditActivity).apply {
                text = "✕"
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Theme.fg(this@VpnSubEditActivity))
                background = Theme.gradientGlowCircle(this@VpnSubEditActivity,
                    if (dark) Theme.darken(Color.parseColor("#CC232840")) else Color.parseColor("#F5FFFFFF"), dark)
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()).apply {
                    marginEnd = (8 * d).toInt()
                }
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@VpnSubEditActivity)
                        .setTitle(L("حذف سابسکریبشن", "Delete subscription"))
                        .setMessage(L("این ساب حذف شود؟ کانفیگ‌هایش در لیست باقی می‌مانند.",
                            "Delete this subscription? Its configs stay in the list."))
                        .setPositiveButton(L("حذف", "Delete")) { _, _ ->
                            val all = VpnSubs.loadItems(this@VpnSubEditActivity)
                            all.removeAll { it.url == originalUrl }
                            VpnSubs.saveItems(this@VpnSubEditActivity, all)
                            finish()
                        }
                        .setNegativeButton(L("لغو", "Cancel"), null)
                        .show().also { Theme.styleDialog(it, this@VpnSubEditActivity) }
                }
            })
        }
        header.addView(Button(this@VpnSubEditActivity).apply {
            text = "✓"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            // 2.88: glass primary circle — teal glass fill with
            // the gradient border + glow, not a flat solid disc.
            background = Theme.gradientGlowCircle(this@VpnSubEditActivity,
                Color.parseColor("#CC0F766E"), dark)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt())
            setOnClickListener { save() }
        })
        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Theme.subFg(this@VpnSubEditActivity))
            setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
        }
        root.addView(status)

        fun label(text: String) {
            root.addView(TextView(this).apply {
                this.text = text; textSize = 14f
                setTextColor(Theme.fg(this@VpnSubEditActivity))
                setPadding(0, (12 * d).toInt(), 0, (4 * d).toInt())
            })
        }
        fun field(initial: String, hint: String = ""): EditText {
            return EditText(this).apply {
                setText(initial)
                if (hint.isNotBlank()) this.hint = hint
                setTextColor(Theme.fg(this@VpnSubEditActivity))
                setHintTextColor(Theme.subFg(this@VpnSubEditActivity))
                background = Theme.pageCard(this@VpnSubEditActivity, 12f)
                setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
                setSingleLine()
            }.also { root.addView(it) }
        }
        /** 2.88: a themed pill toggle (WG/OVPN style) in place of
         *  the flat framework Switch, so the edit page has no
         *  flat controls left. `isChecked` reads the state the
         *  way save() expects it. */
        fun switchRow(text: String, checked: Boolean): PillSwitch {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt())
            }
            row.addView(TextView(this).apply {
                this.text = text; textSize = 15f
                setTextColor(Theme.fg(this@VpnSubEditActivity))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val state = PillSwitch(checked)
            val toggle = android.widget.ImageView(this).apply {
                setImageBitmap(Theme.modeToggleBitmap(this@VpnSubEditActivity, checked))
                layoutParams = LinearLayout.LayoutParams((49 * d).toInt(), (25 * d).toInt())
                setOnClickListener {
                    state.checked = !state.checked
                    setImageBitmap(Theme.modeToggleBitmap(this@VpnSubEditActivity, state.checked))
                }
            }
            row.addView(toggle)
            root.addView(row)
            return state
        }

        label(L("نام ساب (Remarks)", "Remarks"))
        remarksEt = field(item.remarks)
        label("Optional URL")
        urlEt = field(item.url, "https://example.com/subscription")
        label("User Agent")
        uaEt = field(item.userAgent)
        label(L("هدرهای درخواست (JSON)", "Request Headers (JSON)"))
        headersEt = EditText(this).apply {
            setText(item.requestHeaders)
            hint = "{\"Authorization\": \"Bearer ...\"}"
            setTextColor(Theme.fg(this@VpnSubEditActivity))
            setHintTextColor(Theme.subFg(this@VpnSubEditActivity))
            background = Theme.pageCard(this@VpnSubEditActivity, 12f)
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        }.also { root.addView(it) }
        label(L("فیلتر نام (regex؛ فقط کانفیگ‌های هم‌نام نگه داشته می‌شوند)", "Remarks regular filter (regex; only matching configs are kept)"))
        filterEt = field(item.filter)
        enabledSw = switchRow(L("فعال‌سازی به‌روزرسانی (Enable update)", "Enable update"), item.enabled)
        autoSw = switchRow(L("به‌روزرسانی خودکار (Enable automatic update)", "Enable automatic update"), item.autoUpdate)
        label(L("فاصله به‌روزرسانی خودکار (دقیقه، حداقل ۱۵)", "Auto Update Interval (Minutes, min 15)"))
        intervalEt = EditText(this).apply {
            setText(item.updateInterval.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Theme.fg(this@VpnSubEditActivity))
            setHintTextColor(Theme.subFg(this@VpnSubEditActivity))
            background = Theme.pageCard(this@VpnSubEditActivity, 12f)
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            setSingleLine()
        }.also { root.addView(it) }
        insecureSw = switchRow(L("اجازه آدرس HTTP ناامن (Allow insecure HTTP address)", "Allow insecure HTTP address"), item.allowInsecure)

        // Prev/next profile dropdowns: remarks of
        // plain configs (v2rayNG getServerViaRemarks
        // skips custom configs; groups and chains
        // cannot be chain hops either).
        // 2.87: the spinner rows use an explicit-colour
        // adapter — the framework's simple_spinner_item
        // keeps the light theme's dark text, unreadable
        // on the dark page in night mode.
        val servers = VpnSubs.loadServers(this)
        profileOptions = listOf("—") + servers.filter {
            !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it) && !XraySpeedtest.isCustom(it)
        }.map { XraySpeedtest.displayName(it) }.distinct()
        fun spinner(labelText: String, current: String): Spinner {
            label(labelText)
            val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, profileOptions) {
                override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val v = super.getView(position, convertView, parent)
                    (v as? TextView)?.setTextColor(Theme.fg(this@VpnSubEditActivity))
                    return v
                }
                override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val v = super.getDropDownView(position, convertView, parent)
                    (v as? TextView)?.setTextColor(Theme.fg(this@VpnSubEditActivity))
                    v.setBackgroundColor(if (dark) Theme.darken(Color.parseColor("#232840")) else Color.WHITE)
                    return v
                }
            }
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            return Spinner(this).apply {
                this.adapter = adapter
                val idx = profileOptions.indexOf(current)
                if (idx >= 0) setSelection(idx)
            }.also { root.addView(it) }
        }
        prevSpinner = spinner(L("پروکسی قبلی (Previous proxy config remarks)", "Previous proxy config remarks"), item.prevProfile)
        nextSpinner = spinner(L("پروکسی بعدی (Next proxy config remarks)", "Next proxy config remarks"), item.nextProfile)

        root.addView(TextView(this).apply {
            text = L("ترتیب زنجیره در اتصال: دستگاه ← پروکسی قبلی ← کانفیگ ← پروکسی بعدی ← مقصد.",
                "Chain order at connect: device -> previous proxy -> config -> next proxy -> destination.")
            textSize = 13f
            setTextColor(Theme.subFg(this@VpnSubEditActivity))
            setPadding(0, (16 * d).toInt(), 0, 0)
        })
        setContentView(scroll)
    }

    private fun spinnerValue(sp: Spinner): String {
        val v = sp.selectedItem as? String ?: "—"
        return if (v == "—") "" else v
    }

    private fun save() {
        val url = urlEt.text.toString().trim()
        if (url.isBlank() || !url.startsWith("http")) {
            status.text = L("لینک ساب معتبر نیست.", "Subscription link is not valid.")
            return
        }
        val headers = headersEt.text.toString().trim()
        if (headers.isNotBlank()) {
            try {
                JSONObject(headers)
            } catch (_: Exception) {
                status.text = L("هدرهای درخواست باید یک JSON معتبر باشد.", "Request headers must be valid JSON.")
                return
            }
        }
        val interval = intervalEt.text.toString().trim().toIntOrNull()?.coerceAtLeast(15) ?: 1440
        val filter = filterEt.text.toString().trim()
        if (filter.isNotBlank()) {
            try {
                Regex(filter)
            } catch (_: Exception) {
                status.text = L("فیلتر regex معتبر نیست.", "The filter regex is not valid.")
                return
            }
        }
        val all = VpnSubs.loadItems(this)
        val existing = all.firstOrNull { it.url == originalUrl }
        val item = (existing ?: SubItem(url = url)).apply {
            this.url = url
            remarks = remarksEt.text.toString().trim()
            userAgent = uaEt.text.toString().trim()
            requestHeaders = headers
            this.filter = filter
            enabled = enabledSw.isChecked
            autoUpdate = autoSw.isChecked
            updateInterval = interval
            allowInsecure = insecureSw.isChecked
            prevProfile = spinnerValue(prevSpinner)
            nextProfile = spinnerValue(nextSpinner)
        }
        if (existing != null) {
            val idx = all.indexOf(existing)
            all[idx] = item
        } else {
            if (all.none { it.url == url }) all += item
        }
        VpnSubs.saveItems(this, all)
        finish()
    }
}
