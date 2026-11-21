package com.renamer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Monitoring + App Log, one page (2.56).
 *  The Home log button now opens this page: the
 *  top half is App Monitoring — a compact,
 *  glanceable state board for every section,
 *  computed only from state the app already
 *  keeps (see Monitoring.kt) — and below a thin
 *  grey divider sits the app log itself.
 *
 *  The log half keeps its name, its description
 *  and the background real-connection check with
 *  its descriptions, but nothing else from the
 *  old advanced view: no search box, no stats
 *  line, no ordering button, no section chips
 *  and no "all" option. The log box shows at
 *  most 5 lines and scrolls inside itself, so
 *  long logs never push the page around; Copy
 *  and Clear sit BELOW the box. */
class AppLogActivity : Activity() {
    private fun L(fa: String, en: String) = AppLanguage.t(this, fa, en)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private lateinit var logBox: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var monitorGrid: LinearLayout
    private lateinit var summaryText: TextView
    private lateinit var summaryDot: android.view.View
    private lateinit var winningText: TextView
    private lateinit var progressText: TextView
    private lateinit var copyBtn: Button

    // 1.72: live session + history (see
    // refreshSessionMonitor()). All updated by the
    // same 2s poller as the cards above.
    private lateinit var liveTitle: TextView
    private lateinit var liveDetail: TextView
    private lateinit var liveStats: TextView
    private lateinit var liveBytes: TextView
    private lateinit var graphView: ImageView
    private lateinit var graphEmpty: TextView
    private lateinit var countersText: TextView
    private lateinit var historyList: LinearLayout
    private lateinit var historyEmpty: TextView
    private lateinit var shareCurrentBtn: Button
    private var lastHistoryRev = -1
    private var lastLatencyProbeMs = 0L
    private var exitFetchedForStartMs = 0L
    // 1.73 (D): previous live-bytes sample for the
    // graph speed — the delta of the open session's
    // own bytes between 2s polls. Keyed to the
    // session's startMs, so a new session never
    // inherits the previous session's sample.
    private var lastSpeedRx = -1L
    private var lastSpeedTx = -1L
    private var lastSpeedMs = 0L
    private var lastSpeedStartMs = 0L

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val poller = object : Runnable {
        override fun run() {
            try { refreshMonitoring() } catch (_: Exception) {}
            try { refreshSessionMonitor() } catch (_: Exception) {}
            handler.postDelayed(this, 2000)
        }
    }

    private fun glassCard(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(Color.parseColor("#CCFFFFFF"))
        setStroke(dp(1), Color.parseColor("#FFE0E0E0"))
    }

    private fun glassPill(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(20).toFloat()
        setColor(Color.parseColor("#FFFFFF"))
        setStroke(dp(1), Color.parseColor("#FFDDDDDD"))
    }

    /** Small glass "?" button beside a section
     *  title: a brief explanation dialog, nothing
     *  more — the titles stay clean. */
    private fun helpButton(messageFa: String, messageEn: String): Button {
        return Button(this).apply {
            text = "?"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#FF0F766E"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CCFFFFFF"))
                setStroke(dp(1), Color.parseColor("#FFB2DFDB"))
            }
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                // 2.91 (20/22): space between the title
                // and the "?" so they no longer stick.
                marginStart = dp(10)
            }
            setOnClickListener {
                android.app.AlertDialog.Builder(this@AppLogActivity)
                    .setMessage(L(messageFa, messageEn))
                    .setPositiveButton(L("باشه", "OK"), null)
                    // 2.84: shared dialog card + readable text in night.
                    .show().also { Theme.styleDialog(it, this@AppLogActivity) }
            }
        }
    }

    /** A title row: bold title on one side, the
     *  "?" explainer on the other. */
    private fun titleRow(titleFa: String, titleEn: String, helpFa: String, helpEn: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@AppLogActivity).apply {
                text = L(titleFa, titleEn)
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                // 2.84: readable on the dark aurora too.
                setTextColor(if (Theme.isDark(this@AppLogActivity)) Color.parseColor("#A5C8FF") else Color.parseColor("#FF1A237E"))
                // 2.91 (20): Persian title starts from
                // the right; the row itself stays LTR.
                textDirection = android.view.View.TEXT_DIRECTION_LOCALE
                gravity = Gravity.START
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(helpButton(helpFa, helpEn))
        }
    }

    private fun dotView(color: Int): android.view.View {
        return android.view.View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }
    }

    private fun monitorCard(card: Monitoring.Card): LinearLayout {
        val isEn = AppLanguage.isEnglish(this)
        // Uniform size: every card is exactly the
        // same height, whatever its text wraps to,
        // so the board reads as one clean grid.
        // The border tints with the state (amber =
        // last event failed, teal = a switch that is
        // on); green and grey keep the plain glass
        // border. 2.91: the old white vertical wash
        // is gone — the card is glass now, per the
        // mockup, in both modes.
        // 2.91 (20): dark glass in night / white
        // glass on pearl in day, per the mockup —
        // the state tint survives only as the
        // border, amber and teal only (green and
        // grey keep the plain glass border).
        val stateBorder = when (card.dot) {
            Monitoring.DOT_AMBER -> Color.parseColor("#8CFFD54F")
            Monitoring.DOT_TEAL -> Color.parseColor("#8C4DB6AC")
            else -> null
        }
        val cardBg = Theme.glassCard(this).apply {
            if (stateBorder != null) setStroke(dp(1), stateBorder)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            elevation = 3f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            // 2.92: height FITS the content — the fixed 140dp
            // still clipped «تست دوباره» on the user's phone
            // (two-line status + inventory + time + retest).
            // minHeight keeps short cards uniform; a long card
            // grows instead of clipping its last line.
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            minimumHeight = dp(140)
            addView(LinearLayout(this@AppLogActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(dotView(card.dot))
                addView(TextView(this@AppLogActivity).apply {
                    text = if (isEn) card.titleEn else card.titleFa
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    // 2.91 (20): mockup title — light blue
                    // in night, indigo in day — and the
                    // title text sits at the card's right
                    // edge (dot stays leftmost, row is LTR).
                    setTextColor(if (Theme.isDark(this@AppLogActivity)) Color.parseColor("#A5C8FF") else Color.parseColor("#FF1A237E"))
                    textDirection = android.view.View.TEXT_DIRECTION_LOCALE
                    gravity = Gravity.START
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            })
            addView(TextView(this@AppLogActivity).apply {
                text = if (isEn) card.lineEn else card.lineFa
                textSize = 12f
                setTextColor(if (Theme.isDark(this@AppLogActivity)) Color.parseColor("#C7FFFFFF") else Color.parseColor("#FF37474F"))
                textDirection = android.view.View.TEXT_DIRECTION_LOCALE
                gravity = Gravity.START
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                // 2.92: line-height inside the card a touch lower
                // (0.92) so the lines sit closer without touching.
                setLineSpacing(0f, 0.92f)
                setPadding(0, dp(2), 0, 0)
            })
            val inv = if (isEn) card.inventoryEn else card.inventoryFa
            if (inv.isNotBlank()) {
                addView(TextView(this@AppLogActivity).apply {
                    text = inv
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(if (Theme.isDark(this@AppLogActivity)) Color.parseColor("#FF7ED491") else Color.parseColor("#FF00695C"))
                    textDirection = android.view.View.TEXT_DIRECTION_LOCALE
                    gravity = Gravity.START
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setLineSpacing(0f, 0.92f)
                    setPadding(0, dp(1), 0, 0)
                })
            }
            if (card.time.isNotBlank()) {
                addView(TextView(this@AppLogActivity).apply {
                    text = L("آخرین رویداد ${card.time}", "Last event ${card.time}")
                    textSize = 11f
                    setTextColor(Theme.subFg(this@AppLogActivity))
                    textDirection = android.view.View.TEXT_DIRECTION_LOCALE
                    gravity = Gravity.START
                    setLineSpacing(0f, 0.92f)
                    setPadding(0, dp(1), 0, 0)
                })
            }
            // 2.91 (21d): per-card retest — v2ray and
            // WG only — re-tests just that section.
            // The child consumes the tap, so the card's
            // own open-section click does not fire.
            if (card.retest) {
                addView(TextView(this@AppLogActivity).apply {
                    text = L("↻ تست دوباره", "↻ Test again")
                    textSize = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(if (Theme.isDark(this@AppLogActivity)) Color.parseColor("#FF2DD4BF") else Color.parseColor("#FF0F766E"))
                    textDirection = android.view.View.TEXT_DIRECTION_LOCALE
                    gravity = Gravity.START
                    // 2.93: full line height on the retest line — the
                    // 0.92 squeeze can clip tall Persian glyphs inside
                    // their own TextView, so the last line looked
                    // half-cut at the card's bottom edge.
                    setLineSpacing(0f, 1.0f)
                    setPadding(0, dp(2), 0, 0)
                    isClickable = true
                    setOnClickListener { startSectionSweep(card.key) }
                })
                // 2.93: 4dp clearance below the retest line so it can
                // never sit on the card's bottom edge.
                addView(android.view.View(this@AppLogActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(4))
                })
            }
            // Tap a card to open its section — the
            // board is a launcher, not just a display.
            // 1.29: subs is gone with its section;
            // 1.31: chain and hydra are gone too —
            // wireguard opens its page, like the main app.
            val target = when (card.key) {
                "v2ray" -> VpnActivity::class.java
                "wireguard" -> WireGuardActivity::class.java
                "openvpn" -> OpenVpnActivity::class.java
                "emergency" -> EmergencyActivity::class.java
                "smart" -> SmartConnectActivity::class.java
                "cloudflare" -> CloudflareActivity::class.java
                else -> null
            }
            if (target != null) {
                isClickable = true
                setOnClickListener {
                    startActivity(android.content.Intent(this@AppLogActivity, target))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2.84: shared background + no white window strips.
        try { Theme.applyPage(this) } catch (_: Exception) {}
        val scroll = ScrollView(this).apply {
            background = Theme.pageBackground(this@AppLogActivity)
            isFillViewport = true
        }
        scroll.fitsSystemWindows = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(root)

        // ---- Monitoring (top half) ----
        root.addView(titleRow(
            "مانیتورینگ برنامه", "App monitoring",
            "مانیتورینگ، وضعیت لحظه‌ای همه بخش‌ها را با یک نگاه نشان می‌دهد: کدام بخش در حال اتصال است، کدام خاموش است، هر بخش چند کانفیگ دارد و چندتایش در آخرین تست سالم بوده. پایین همین صفحه، «اتصال زنده و نشست‌ها» سرعت زنده، پینگ و مصرف اتصال فعلی را نشان می‌دهد و نشست‌های گذشته را نگه می‌دارد: سرعت از روی شمارنده‌های رابط تونل خوانده می‌شود، پینگ هر ۱۵ ثانیه با یک درخواست سبک اندازه گرفته می‌شود، و هیچ عددی که اندازه گرفته نشده نمایش داده نمی‌شود. روی هر کارت بزن تا همان بخش باز شود.",
            "Monitoring shows every section's live state at a glance: which section is connected, which is off, how many configs each section has and how many were healthy at the last test. Lower on this page, “Live connection & sessions” shows the current connection's live speed, latency and data use, and keeps past sessions: speed is read from the tunnel interface counters, latency is probed every 15 seconds with one light request, and no number that was not measured is ever shown. Tap a card to open its section."))
        root.addView(TextView(this).apply {
            text = L("وضعیت زنده بخش‌ها؛ تست‌های v2ray و وایرگارد هم دوره‌ای به‌صورت خودکار تازه می‌شوند. نشست‌ها پایین همین صفحه‌اند.",
                "Live section states; v2ray and WireGuard tests are also refreshed automatically from time to time. Sessions are lower on this page.")
            textSize = 13f
            setTextColor(Theme.subFg(context))
            textDirection = android.view.View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, dp(8), 0, dp(12))
        })
        // Verdict line: one dot + one sentence for
        // the whole board (green = connected and no
        // recent failure, amber = some section's
        // last event was a failure, grey = idle).
        // 2.59 beauty: the verdict rides in a
        // soft teal glass pill, not bare text.
        val summaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 2.91 (20): dark glass in night (the
            // amber accent comes from the amber dot +
            // amber text in the amber state), light
            // green glass in day, as mocked.
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                if (Theme.isDark(this@AppLogActivity)) {
                    setColor(Theme.darken(Color.parseColor("#14FFFFFF")))
                    setStroke(dp(1), Theme.darken(Color.parseColor("#29FFFFFF")))
                } else {
                    setColor(Color.parseColor("#FFE0F2F1"))
                    setStroke(dp(1), Color.parseColor("#FFB2DFDB"))
                }
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        summaryDot = dotView(Monitoring.DOT_GREY)
        summaryRow.addView(summaryDot)
        summaryText = TextView(this).apply {
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#FF0F766E"))
            textDirection = android.view.View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        summaryRow.addView(summaryText)
        // 2.93: a 12dp gap to the test buttons below. winningText
        // (the only spacer before) is GONE when there is no
        // winning method, so the buttons stuck to the status box.
        root.addView(summaryRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })
        winningText = TextView(this).apply {
            textSize = 12f
            setTextColor(Theme.subFg(context))
            textDirection = android.view.View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(winningText)
        // Test-all row: a manual sweep button +
        // live progress while a sweep runs. The
        // sweep is the subs page's PingTestService
        // (foreground service, never touches a
        // live VPN session); it only re-measures
        // the v2ray section's own saved configs.
        val testRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        testRow.addView(Button(this).apply {
            text = L("تست همه بخش‌ها", "Test all sections")
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            // 2.91 (20): neon button.
            setTextColor(Theme.neonTextColor(this@AppLogActivity))
            background = Theme.neonRect(this@AppLogActivity)
            elevation = 3f
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setOnClickListener { startSweep(force = true) }
        })
        // 2.58: copy a full monitoring report
        // (verdict + every card + winning
        // methods) as plain text, for sending
        // to the developer or keeping.
        testRow.addView(Button(this).apply {
            text = L("کپی گزارش", "Copy report")
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            // 2.91 (20): neon button, same as Test-all.
            setTextColor(Theme.neonTextColor(this@AppLogActivity))
            background = Theme.neonRect(this@AppLogActivity)
            elevation = 3f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), 0, 0, 0)
            }
            setOnClickListener { copyMonitoringReport() }
        })
        progressText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#FF00695C"))
            textDirection = android.view.View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(dp(10), 0, 0, 0)
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        testRow.addView(progressText)
        root.addView(testRow)
        monitorGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(monitorGrid)

        // ---- 1.72: Live connection & sessions ----
        // Same page, below the cards, above the log
        // divider. Everything here is read from state
        // the app already keeps plus the session
        // records SessionHistory writes on every real
        // connect/disconnect (see that file).
        root.addView(titleRow(
            "اتصال زنده و نشست‌ها", "Live connection & sessions",
            "اتصال فعلی با سرعت زنده (دانلود/آپلود)، پینگ، مدت اتصال و مصرفش نشان داده می‌شود؛ نمودار، سرعت ۵ دقیقه گذشته را می‌کشد. هر نشستِ تمام‌شده با زمان شروع، مدت، مصرف و کشور خروج ذخیره می‌شود (حداکثر ۳۰ نشست) — روی هر ردیف بزن تا جزئیاتش را ببینی و خلاصه‌اش را به اشتراک بگذاری. سرعت از شمارنده‌های رابط تونل و پینگ از یک درخواست سبک به gstatic می‌آید؛ اگر چیزی اندازه گرفته نشده باشد، به‌جای عدد، «اندازه‌گیری نشده» نوشته می‌شود. لینک کانفیگ و رمز هیچ‌وقت ذخیره یا به اشتراک گذاشته نمی‌شود.",
            "The current connection is shown with live speed (download/upload), latency, uptime and data use; the graph draws the last 5 minutes of speed. Each finished session is stored with its start time, duration, data use and exit country (at most 30 sessions) — tap a row to see its details and share its summary. Speed comes from the tunnel interface counters and latency from one light request to gstatic; anything not measured is shown as “not measured”, never as a number. Config links and passwords are never stored or shared."))
        val liveCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.glassCard(this@AppLogActivity)
            elevation = 3f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(8), 0, dp(4))
            }
        }
        liveTitle = TextView(this).apply {
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (Theme.isDark(this@AppLogActivity)) Color.parseColor("#A5C8FF") else Color.parseColor("#FF1A237E"))
            textDirection = android.view.View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
        }
        liveCard.addView(liveTitle)
        liveDetail = TextView(this).apply {
            textSize = 12f
            setTextColor(if (Theme.isDark(this@AppLogActivity)) Color.parseColor("#C7FFFFFF") else Color.parseColor("#FF37474F"))
            textDirection = android.view.View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, dp(2), 0, 0)
        }
        liveCard.addView(liveDetail)
        liveStats = TextView(this).apply {
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (Theme.isDark(this@AppLogActivity)) Color.parseColor("#FF7ED491") else Color.parseColor("#FF00695C"))
            textDirection = android.view.View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, dp(4), 0, 0)
        }
        liveCard.addView(liveStats)
        liveBytes = TextView(this).apply {
            textSize = 12f
            setTextColor(if (Theme.isDark(this@AppLogActivity)) Color.parseColor("#C7FFFFFF") else Color.parseColor("#FF37474F"))
            textDirection = android.view.View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, dp(2), 0, 0)
        }
        liveCard.addView(liveBytes)
        liveCard.addView(TextView(this).apply {
            text = L("نمودار سرعت زنده — دانلود (سبز) / آپلود (کهربایی)",
                "Live speed graph — download (green) / upload (amber)")
            textSize = 11f
            setTextColor(Theme.subFg(this@AppLogActivity))
            textDirection = android.view.View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, dp(8), 0, dp(4))
        })
        graphView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(96))
            scaleType = android.widget.ImageView.ScaleType.FIT_XY
            visibility = android.view.View.GONE
        }
        liveCard.addView(graphView)
        graphEmpty = TextView(this).apply {
            textSize = 12f
            setTextColor(Theme.subFg(this@AppLogActivity))
            textDirection = android.view.View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
        }
        liveCard.addView(graphEmpty)
        shareCurrentBtn = Button(this).apply {
            text = L("اشتراک‌گذاری خلاصه نشست", "Share session summary")
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.neonTextColor(this@AppLogActivity))
            background = Theme.neonRect(this@AppLogActivity)
            elevation = 3f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(8), 0, 0)
            }
            visibility = android.view.View.GONE
            setOnClickListener { shareCurrentSession() }
        }
        liveCard.addView(shareCurrentBtn)
        root.addView(liveCard)
        // Today / total / drops counters, one line.
        countersText = TextView(this).apply {
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (Theme.isDark(this@AppLogActivity)) Color.parseColor("#FF7ED491") else Color.parseColor("#FF00695C"))
            textDirection = android.view.View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(countersText)
        // Session history.
        root.addView(titleRow(
            "نشست‌های گذشته", "Past sessions",
            "هر ردیف یک نشستِ تمام‌شده است: زمان شروع، مدت، مصرف و کشور خروج. روی ردیف بزن تا جزئیات کامل و دکمه‌های اشتراک‌گذاری و کپی را ببینی. فقط ۳۰ نشست آخر نگه داشته می‌شود.",
            "Each row is one finished session: start time, duration, data use and exit country. Tap a row for the full detail and the share / copy buttons. Only the last 30 sessions are kept."))
        historyEmpty = TextView(this).apply {
            textSize = 12f
            setTextColor(Theme.subFg(this@AppLogActivity))
            textDirection = android.view.View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(historyEmpty)
        historyList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(historyList)

        // ---- Thin grey divider between the halves ----
        root.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, dp(12), 0, dp(12)) }
            setBackgroundColor(if (Theme.isDark(this@AppLogActivity)) Theme.darken(Color.parseColor("#14FFFFFF")) else Color.parseColor("#FFDDDDDD"))
        })

        // ---- App log (bottom half) ----
        root.addView(titleRow(
            "لاگ برنامه", "App log",
            "لاگ، همه تلاش‌های اتصال و رویدادهای بخش‌ها را دقیق و به‌ترتیب زمان ثبت می‌کند. برای ارسال لاگ دقیق به سازنده: اول دکمه «پاک کردن لاگ» پایین صفحه را بزن، بعد روی کانفیگی که وصل نمی‌شود بزن، و همان لاگ تازه را کپی کن و بفرست.",
            "The log records every connection attempt and section event, precise and in time order. To send an accurate log to the developer: first tap “Clear log” at the bottom of this page, then tap the config that fails to connect, and copy and send that fresh log."))
        root.addView(TextView(this).apply {
            text = L(
                "همه‌ی تلاش‌های اتصال و رویدادهای بخش‌های برنامه، دقیق و به‌ترتیب زمان، اینجا ثبت می‌شود: نوع کانفیگ چه بوده، و نتیجه چه شد. برای ارسال لاگ دقیق به سازنده: اول دکمه «پاک کردن لاگ» پایین صفحه را بزن، بعد روی کانفیگی که وصل نمی‌شود بزن، و همان لاگ تازه را کپی کن و بفرست تا عیب‌یابی دقیق باشد.",
                "Every connection attempt and section event, precise and in time order, is recorded here: what kind of config it was, and what the result was. To send an accurate log to the developer: first tap “Clear log” at the bottom of this page, then tap the config that fails to connect, and copy and send that fresh log so debugging is precise.")
            textSize = 13f
            setTextColor(Theme.subFg(context))
            textDirection = android.view.View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, dp(8), 0, dp(12))
        })

        // Background real-connection check, unchanged.
        val verifyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val verifyTexts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        verifyTexts.addView(TextView(this).apply {
            text = L("بررسی واقعی اتصال در پس‌زمینه", "Background real-connection check")
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            // 2.85: #1A237E was dark-on-dark in night (transparent row).
            setTextColor(Color.parseColor(if (Theme.isDark(context)) "#A5C8FF" else "#FF1A237E"))
        })
        verifyTexts.addView(TextView(this).apply {
            text = L("وقتی روشن است، بعد از هر اتصال در پس‌زمینه تست می‌شود که تونل واقعاً ترافیک می‌برد یا نه، و نتیجه در همین لاگ ثبت می‌شود.",
                "When on, every new connection is probed in the background for real traffic, and the verdict is written into this log.")
            textSize = 12f
            // 2.85: GRAY was dark-on-dark in night (transparent row).
            setTextColor(Theme.subFg(context))
        })
        verifyRow.addView(verifyTexts)
        verifyRow.addView(android.widget.Switch(this).apply {
            isChecked = ConnVerify.isEnabled(this@AppLogActivity)
            setOnCheckedChangeListener { _, on ->
                ConnVerify.setEnabled(this@AppLogActivity, on)
                Toast.makeText(this@AppLogActivity,
                    if (on) L("بررسی واقعی اتصال روشن شد", "Real-connection check turned on")
                    else L("بررسی واقعی اتصال خاموش شد", "Real-connection check turned off"),
                    Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(verifyRow)

        // Log box: a fixed 5-line window that scrolls
        // inside itself. A plain TextView with
        // maxLines did NOT scroll here: the outer
        // page ScrollView stole every vertical drag,
        // so lines below the window (e.g. the newest
        // openvpn/wireguard lines) were unreachable
        // and the box looked like it only had v2ray.
        // A real nested ScrollView + disallow-intercept
        // on touch fixes that, and the box opens
        // scrolled to the bottom (newest lines first
        // seen) while the stored order stays oldest
        // first, exactly like the old full log.
        logBox = TextView(this).apply {
            textSize = 13f
            // 2.91 (20/23): light text in night, dark in day.
            setTextColor(if (Theme.isDark(this@AppLogActivity)) Color.parseColor("#FFE8EAF6") else Color.parseColor("#FF1A1A1A"))
            // 2.58: text selection stays OFF on
            // purpose. A selectable TextView
            // consumes drag gestures for text
            // selection and starves this inner
            // ScrollView, which is exactly why
            // 2.57's box still would not scroll.
            // Copying is done by the Copy button
            // below, which copies the full log.
            setTextIsSelectable(false)
            isLongClickable = false
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val logHeight = logBox.lineHeight * 5 + dp(20)
        logScroll = ScrollView(this).apply {
            // 2.91 (20/23): Settings night theme in
            // night (slightly darker dark glass),
            // white glass in day.
            background = Theme.pageCard(this@AppLogActivity, 16f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, logHeight)
            isVerticalScrollBarEnabled = true
            // Keep the outer page ScrollView from
            // stealing this box's own scrolls.
            // 2.58: re-assert on MOVE too, so no
            // child gesture can hand the drag
            // back to the outer ScrollView.
            setOnTouchListener { v, ev ->
                if (ev.action == android.view.MotionEvent.ACTION_DOWN ||
                    ev.action == android.view.MotionEvent.ACTION_MOVE) {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
        }
        logScroll.addView(logBox)
        root.addView(logScroll)

        // Copy + Clear BELOW the box.
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        // 2.59 beauty: Copy is the primary
        // action (teal gradient pill), Clear the
        // secondary (white outline pill) — the
        // same pair language as Test/Report
        // above, so the whole page reads as one
        // design.
        copyBtn = Button(this).apply {
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            // 2.91 (20): neon button.
            setTextColor(Theme.neonTextColor(this@AppLogActivity))
            background = Theme.neonRect(this@AppLogActivity)
            elevation = 3f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener {
                val lines = allLines()
                if (lines.isEmpty()) {
                    Toast.makeText(this@AppLogActivity,
                        L("چیزی برای کپی نیست", "Nothing to copy"), Toast.LENGTH_SHORT).show()
                } else {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("app-log", lines.joinToString("\n")))
                    Toast.makeText(this@AppLogActivity,
                        L("لاگ کپی شد", "Log copied"), Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnRow.addView(copyBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        btnRow.addView(Button(this).apply {
            text = L("پاک کردن لاگ", "Clear log")
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            // 2.91 (20): neon button, same as Copy log.
            setTextColor(Theme.neonTextColor(this@AppLogActivity))
            background = Theme.neonRect(this@AppLogActivity)
            elevation = 3f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener {
                AppLog.clear(this@AppLogActivity)
                refresh()
                Toast.makeText(this@AppLogActivity,
                    L("لاگ پاک شد", "Log cleared"), Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(btnRow)

        refresh()
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        maybeAutoSweep()
        handler.removeCallbacks(poller)
        handler.post(poller)
    }

    override fun onPause() {
        handler.removeCallbacks(poller)
        super.onPause()
    }

    /** Start a sweep via the subs page's
     *  PingTestService (v2ray) and WgTestService
     *  (WireGuard): both measure their section's
     *  own saved configs in the background and
     *  never touch a live VPN session.
     *  Auto-sweeps are throttled to one per 10
     *  minutes; the button forces one (unless a
     *  sweep is already running). */
    /** v2ray configs the ping sweep would test
     *  (saved servers, deduped, groups/chains out). */
    private fun v2rayConfigsToTest(): List<String> {
        return try {
            val servers = getSharedPreferences("renamer_vpn", MODE_PRIVATE)
                .getString("servers", "")?.lines()
                ?.filter { it.isNotBlank() && !XraySpeedtest.isGroup(it) && !XraySpeedtest.isChain(it) }
                ?: emptyList()
            val seen = mutableSetOf<String>()
            servers.filter { seen.add(XraySpeedtest.dedupKey(it)) }
        } catch (_: Exception) { emptyList() }
    }

    /** Saved WireGuard tunnel names. */
    private fun wgTunnelNames(): List<String> {
        return try {
            val arr = org.json.JSONArray(getSharedPreferences("renamer_wg", MODE_PRIVATE)
                .getString("wg_list", "[]") ?: "[]")
            (0 until arr.length()).mapNotNull {
                arr.optJSONObject(it)?.optString("name", "")?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Ping-test only (the v2ray section): the same
     *  PingTestService run the sweep button starts,
     *  scoped to v2ray alone. Returns true when the
     *  service actually started. */
    private fun startPingOnly(
        force: Boolean, emptyToast: Boolean = true,
        checkCore: Boolean = true, announce: Boolean = false
    ): Boolean {
        return try {
            val subs = getSharedPreferences("renamer_subs", MODE_PRIVATE)
            if (subs.getBoolean("ping_running", false)) {
                if (force) Toast.makeText(this,
                    L("تستی در حال اجراست؛ صبر کن.", "A test is already running."),
                    Toast.LENGTH_SHORT).show()
                return false
            }
            val toTest = v2rayConfigsToTest()
            if (toTest.isEmpty()) {
                if (force && emptyToast) Toast.makeText(this,
                    L("چیزی برای تست نیست.", "Nothing to test."),
                    Toast.LENGTH_SHORT).show()
                return false
            }
            if (checkCore && !CoreInit.init(applicationContext)) {
                if (force) Toast.makeText(this,
                    L("تست شروع نشد: هسته Xray راه‌اندازی نشد.", "Test did not start: Xray core failed to start."),
                    Toast.LENGTH_SHORT).show()
                return false
            }
            subs.edit()
                .putString("ping_test_list", toTest.joinToString("\n"))
                .putString("ping_healthy_live", "")
                .putInt("ping_progress_done", 0)
                .putInt("ping_progress_total", toTest.size)
                .putInt("ping_progress_healthy", 0)
                .putBoolean("ping_done", false)
                .putBoolean("ping_applied", false)
                .putBoolean("ping_running", true)
                .putString("ping_error", "")
                .putString("ping_live_sub", "{}")
                .apply()
            try {
                val intent = android.content.Intent(this, PingTestService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                if (force && announce) Toast.makeText(this,
                    L("تست پینگ ${toTest.size} کانفیگ شروع شد.", "Ping test started for ${toTest.size} configs."),
                    Toast.LENGTH_SHORT).show()
                true
            } catch (_: Exception) {
                subs.edit().putBoolean("ping_running", false).apply()
                if (force) Toast.makeText(this,
                    L("تست پینگ شروع نشد.", "Ping test did not start."),
                    Toast.LENGTH_SHORT).show()
                false
            }
        } catch (_: Exception) {
            if (force) Toast.makeText(this,
                L("تست شروع نشد.", "Test did not start."),
                Toast.LENGTH_SHORT).show()
            false
        }
    }

    /** WireGuard-test only: the same WgTestService
     *  run the sweep button starts, scoped to the
     *  WG section alone. */
    private fun startWgOnly(
        force: Boolean, emptyToast: Boolean = true,
        checkCore: Boolean = true, announce: Boolean = false
    ): Boolean {
        return try {
            val wgTest = getSharedPreferences("renamer_wg_test", MODE_PRIVATE)
            if (wgTest.getBoolean("wg_test_running", false)) {
                if (force) Toast.makeText(this,
                    L("تستی در حال اجراست؛ صبر کن.", "A test is already running."),
                    Toast.LENGTH_SHORT).show()
                return false
            }
            val wgNames = wgTunnelNames()
            if (wgNames.isEmpty()) {
                if (force && emptyToast) Toast.makeText(this,
                    L("چیزی برای تست نیست.", "Nothing to test."),
                    Toast.LENGTH_SHORT).show()
                return false
            }
            if (checkCore && !CoreInit.init(applicationContext)) {
                if (force) Toast.makeText(this,
                    L("تست شروع نشد: هسته Xray راه‌اندازی نشد.", "Test did not start: Xray core failed to start."),
                    Toast.LENGTH_SHORT).show()
                return false
            }
            wgTest.edit()
                .putBoolean("wg_test_running", true)
                .putBoolean("wg_test_done", false)
                .putInt("wg_test_progress_done", 0)
                .putInt("wg_test_total", wgNames.size)
                .putInt("wg_test_healthy", 0)
                .putInt("wg_test_untestable", 0)
                .putString("wg_test_error", "")
                .apply()
            try {
                val intent = android.content.Intent(this, WgTestService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                if (force && announce) Toast.makeText(this,
                    L("تست ${wgNames.size} تونل وایرگارد شروع شد.", "WireGuard test started for ${wgNames.size} tunnels."),
                    Toast.LENGTH_SHORT).show()
                true
            } catch (_: Exception) {
                wgTest.edit().putBoolean("wg_test_running", false).apply()
                if (force) Toast.makeText(this,
                    L("تست وایرگارد شروع نشد.", "WireGuard test did not start."),
                    Toast.LENGTH_SHORT).show()
                false
            }
        } catch (_: Exception) {
            if (force) Toast.makeText(this,
                L("تست شروع نشد.", "Test did not start."),
                Toast.LENGTH_SHORT).show()
            false
        }
    }

    /** 2.91 (21d): per-card retest — the "↻ تست
     *  دوباره" line on the v2ray / WG cards runs
     *  ONLY that section's test, never the other. */
    private fun startSectionSweep(section: String) {
        try {
            val started = when (section) {
                "v2ray" -> startPingOnly(force = true, announce = true)
                "wireguard" -> startWgOnly(force = true, announce = true)
                else -> false
            }
            if (started) {
                getSharedPreferences("renamer_settings", MODE_PRIVATE).edit()
                    .putLong("monitoring_sweep_at", System.currentTimeMillis()).apply()
            }
        } catch (_: Exception) {}
    }

    private fun startSweep(force: Boolean) {
        try {
            val subs = getSharedPreferences("renamer_subs", MODE_PRIVATE)
            val wgTest = getSharedPreferences("renamer_wg_test", MODE_PRIVATE)
            if (subs.getBoolean("ping_running", false) || wgTest.getBoolean("wg_test_running", false)) {
                // 2.59: either test running blocks a
                // new sweep; auto-sweeps stay silent.
                if (force) Toast.makeText(this,
                    L("تستی در حال اجراست؛ صبر کن.", "A test is already running."),
                    Toast.LENGTH_SHORT).show()
                return
            }
            val toTest = v2rayConfigsToTest()
            val wgNames = wgTunnelNames()
            if (toTest.isEmpty() && wgNames.isEmpty()) {
                if (force) Toast.makeText(this,
                    L("چیزی برای تست نیست.", "Nothing to test."),
                    Toast.LENGTH_SHORT).show()
                return
            }
            if (!CoreInit.init(applicationContext)) {
                if (force) Toast.makeText(this,
                    L("تست شروع نشد: هسته Xray راه‌اندازی نشد.", "Test did not start: Xray core failed to start."),
                    Toast.LENGTH_SHORT).show()
                return
            }
            // 2.91: same two runs as before, now via
            // the section-scoped starters (core already
            // checked above, empty toasts suppressed —
            // the combined toast below speaks for both).
            var startedAny = false
            if (toTest.isNotEmpty()) {
                if (startPingOnly(force, emptyToast = false, checkCore = false)) startedAny = true
            }
            if (wgNames.isNotEmpty()) {
                if (startWgOnly(force, emptyToast = false, checkCore = false)) startedAny = true
            }
            if (startedAny) {
                getSharedPreferences("renamer_settings", MODE_PRIVATE).edit()
                    .putLong("monitoring_sweep_at", System.currentTimeMillis()).apply()
                if (force) Toast.makeText(this,
                    when {
                        toTest.isNotEmpty() && wgNames.isNotEmpty() ->
                            L("تست پینگ ${toTest.size} کانفیگ و ${wgNames.size} تونل وایرگارد شروع شد.",
                                "Ping test started for ${toTest.size} configs and ${wgNames.size} WireGuard tunnels.")
                        toTest.isNotEmpty() ->
                            L("تست پینگ ${toTest.size} کانفیگ شروع شد.", "Ping test started for ${toTest.size} configs.")
                        else ->
                            L("تست ${wgNames.size} تونل وایرگارد شروع شد.", "WireGuard test started for ${wgNames.size} tunnels.")
                    },
                    Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            if (force) Toast.makeText(this,
                L("تست شروع نشد.", "Test did not start."),
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun maybeAutoSweep() {
        try {
            val last = getSharedPreferences("renamer_settings", MODE_PRIVATE)
                .getLong("monitoring_sweep_at", 0L)
            if (System.currentTimeMillis() - last < 10 * 60 * 1000L) return
            startSweep(force = false)
        } catch (_: Exception) {}
    }

    /** Raw stored lines, oldest first, as AppLog keeps them. */
    private fun allLines(): List<String> {
        val t = AppLog.text(this)
        if (t.isBlank()) return emptyList()
        return t.lines().filter { it.isNotBlank() }
    }

    private fun refresh() {
        refreshMonitoring()
        refreshSessionMonitor()
        refreshLog()
    }

    /** Monitoring half only — cheap enough for
     *  the 2s poller, and it never touches the
     *  log box, so it cannot yank the user's
     *  scroll position while they read the log. */
    private fun refreshMonitoring() {
        if (!::monitorGrid.isInitialized) return
        // Monitoring cards, two per row.
        val cards = Monitoring.collect(this)
        monitorGrid.removeAllViews()
        for (rowStart in cards.indices step 2) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            row.addView(monitorCard(cards[rowStart]))
            if (rowStart + 1 < cards.size) {
                row.addView(monitorCard(cards[rowStart + 1]))
            } else {
                row.addView(LinearLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                })
            }
            monitorGrid.addView(row)
        }
        val live = Monitoring.liveCount(cards)
        val amber = cards.count { it.dot == Monitoring.DOT_AMBER }
        val latest = Monitoring.latestTime(cards)
        val latestSuffix = if (latest.isNotBlank()) L(" • آخرین رویداد $latest", " • latest event $latest") else ""
        when {
            amber > 0 -> {
                summaryDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Monitoring.DOT_AMBER)
                }
                // 2.91 (20): amber accent text in night.
                summaryText.setTextColor(if (Theme.isDark(this)) Color.parseColor("#FFFFD9A0") else Color.parseColor("#FFE65100"))
                summaryText.text = L("$amber بخش آخرین رویدادش خطا بود" +
                    (if (live > 0) " • $live بخش متصل است" else "") + latestSuffix,
                    "$amber sections' last event was a failure" +
                    (if (live > 0) " • $live connected" else "") + latestSuffix)
            }
            live > 0 -> {
                summaryDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Monitoring.DOT_GREEN)
                }
                summaryText.setTextColor(if (Theme.isDark(this)) Color.parseColor("#FF81C784") else Color.parseColor("#FF0F766E"))
                summaryText.text = L("وضعیت خوب است — $live بخش متصل است$latestSuffix",
                    "All good — $live sections connected$latestSuffix")
            }
            else -> {
                summaryDot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Monitoring.DOT_GREY)
                }
                summaryText.setTextColor(if (Theme.isDark(this)) Color.parseColor("#FFBDBDBD") else Color.parseColor("#FF757575"))
                summaryText.text = L("هیچ اتصالی فعال نیست$latestSuffix",
                    "No connection active$latestSuffix")
            }
        }
        val wins = Monitoring.winningMethods(this)
        if (wins.isEmpty()) {
            winningText.visibility = android.view.View.GONE
        } else {
            winningText.visibility = android.view.View.VISIBLE
            winningText.text = L("آخرین روش‌های برنده: " + wins.joinToString("، "),
                "Recent winning methods: " + wins.joinToString(", "))
        }
        // Live sweep progress, if one is running.
        // 2.59: ping and WG progress combine into
        // one block (each on its own line).
        try {
            val subs = getSharedPreferences("renamer_subs", MODE_PRIVATE)
            val parts = mutableListOf<String>()
            if (subs.getBoolean("ping_running", false)) {
                val d = subs.getInt("ping_progress_done", 0)
                val t = subs.getInt("ping_progress_total", 0)
                val h = subs.getInt("ping_progress_healthy", 0)
                parts += L("در حال تست پینگ… $d از $t تست شد، $h سالم",
                    "Testing ping… $d of $t tested, $h healthy")
            }
            val wg = getSharedPreferences("renamer_wg_test", MODE_PRIVATE)
            if (wg.getBoolean("wg_test_running", false)) {
                val d = wg.getInt("wg_test_progress_done", 0)
                val t = wg.getInt("wg_test_total", 0)
                val h = wg.getInt("wg_test_healthy", 0)
                parts += L("در حال تست وایرگارد… $d از $t تست شد، $h سالم",
                    "Testing WireGuard… $d of $t tested, $h healthy")
            }
            if (parts.isNotEmpty()) {
                progressText.visibility = android.view.View.VISIBLE
                progressText.text = parts.joinToString("\n")
            } else {
                progressText.visibility = android.view.View.GONE
            }
        } catch (_: Exception) {}
    }

    // -----------------------------------------------------------------------------------------
    // 1.72: live connection & sessions.
    // -----------------------------------------------------------------------------------------

    /** The live tunnel right now, as (section, name):
     *  same sources of truth as Monitoring.collect —
     *  v2ray-family service flag + tag, WG tunnel
     *  objects, OpenVPN engine flag. Null when nothing
     *  is live. */
    private fun liveSectionNow(): Pair<String, String>? {
        return try {
            if (VpnClientService.isRunning) {
                val tag = VpnClientService.sessionTag ?: "v2ray"
                val name = VpnClientService.currentName ?: ""
                return tag to name
            }
            if (WgDirect.isReallyUp(this)) {
                val names = try { WgDirect.liveTunnelNames(this) } catch (_: Exception) { emptySet<String>() }
                return "wireguard" to (names.firstOrNull() ?: "")
            }
            if (OvpnEngine.isConnected) {
                return "openvpn" to (OvpnEngine.activeName ?: "")
            }
            null
        } catch (_: Exception) { null }
    }

    private fun fmtKbps(kbps: Long): String =
        if (kbps >= 1024) String.format(java.util.Locale.US, "%.1f MB/s", kbps / 1024f)
        else "$kbps KB/s"

    /** One light latency probe THROUGH the tunnel
     *  (the app's own traffic rides the TUN while a
     *  session is live): GET generate_204, timed.
     *  Null when it does not answer. Runs on a worker
     *  thread only — never on the poller thread. */
    private fun probeLatencyMs(): Int? {
        return try {
            val start = android.os.SystemClock.elapsedRealtime()
            val c = java.net.URL("https://www.gstatic.com/generate_204")
                .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            c.requestMethod = "GET"
            val code = c.responseCode
            try { c.disconnect() } catch (_: Exception) {}
            if (code == 204) (android.os.SystemClock.elapsedRealtime() - start).toInt() else null
        } catch (_: Exception) { null }
    }

    /** The 2s poller's session half: reconcile the open
     *  record with the live tunnel, sample live speed
     *  into the graph, probe latency + exit IP on
     *  worker threads, refresh counters and history. */
    private fun refreshSessionMonitor() {
        if (!::liveTitle.isInitialized) return
        val isEn = AppLanguage.isEnglish(this)
        val fa = !isEn
        val live = liveSectionNow()
        // Reconcile the open record (engine hooks do
        // this on the normal path; this is the safety
        // net for any path they miss).
        try {
            SessionHistory.noteLive(this, live?.first, live?.second, null, null)
        } catch (_: Exception) {}
        val open = try { SessionHistory.open(this) } catch (_: Exception) { null }
        // ---- Live card ----
        if (live == null) {
            liveTitle.text = L("اتصالی فعال نیست", "No connection active")
            liveDetail.text = L("وقتی اتصالی برقرار شود، جزئیاتش اینجا می‌آید.",
                "When a connection is up, its details appear here.")
            liveStats.text = ""
            liveBytes.text = ""
            graphView.visibility = android.view.View.GONE
            graphEmpty.text = L("نموداری نیست — وقتی اتصال برقرار است، سرعت زنده اینجا رسم می‌شود.",
                "No graph — live speed is drawn here while a connection is up.")
            graphEmpty.visibility = android.view.View.VISIBLE
        } else {
            val (sec, name) = live
            val secFa = SessionHistory.sectionDisplay(sec, true)
            val secEn = SessionHistory.sectionDisplay(sec, false)
            liveTitle.text = (if (fa) secFa else secEn) +
                (if (name.isNotBlank()) " • $name" else "")
            // Detail: protocol / gateway / exit, each
            // only when actually known.
            val detailParts = mutableListOf<String>()
            val proto = open?.protocol ?: ""
            if (proto.isNotBlank()) detailParts += (if (fa) "پروتکل: " else "Protocol: ") + proto
            val gw = open?.gateway ?: ""
            if (gw.isNotBlank()) detailParts += (if (fa) "سرور: " else "Gateway: ") + gw
            val exit = open
            if (exit != null && (exit.exitCountry.isNotBlank() || exit.exitIp.isNotBlank())) {
                var e = if (fa) "خروج: " else "Exit: "
                if (exit.exitCountry.isNotBlank()) e += exit.exitCountry + " "
                if (exit.exitIp.isNotBlank()) e += exit.exitIp
                detailParts += e.trim()
            }
            liveDetail.text = if (detailParts.isEmpty()) {
                L("جزئیات خروج هنوز اندازه‌گیری نشده است.",
                    "Exit details not measured yet.")
            } else detailParts.joinToString(" • ")
            // Live speed: the delta of THIS session's
            // own bytes (openBytesNow — the same
            // source as the «مصرف این نشست» line)
            // between 2s polls.
            // 1.73 (D): VpnNotification
            // .currentSpeedsKbps reads only the
            // /proc/net/dev TUN counters, which a
            // MASQUE session never moves (the aether
            // CLI's traffic runs under the app UID
            // and bypasses tun0) — so the graph
            // stayed empty while the tunnel carried
            // real traffic. The session bytes ARE
            // the live speed's source now, so the
            // graph moves exactly when they do.
            // (The live-bytes line below reuses this
            // same read instead of reading twice.)
            var down = -1L
            var up = -1L
            val (rxNow, txNow) = try { SessionHistory.openBytesNow(this) } catch (_: Exception) { 0L to 0L }
            try {
                val sStartMs = open?.startMs ?: 0L
                val nowMs = System.currentTimeMillis()
                if (sStartMs > 0 && sStartMs == lastSpeedStartMs && lastSpeedMs > 0) {
                    val sp = SessionHistory.speedFromSamples(
                        lastSpeedRx, lastSpeedTx, lastSpeedMs, rxNow, txNow, nowMs)
                    down = sp.first
                    up = sp.second
                }
                lastSpeedRx = rxNow
                lastSpeedTx = txNow
                lastSpeedMs = nowMs
                lastSpeedStartMs = sStartMs
            } catch (_: Exception) {}
            if (down >= 0) {
                try {
                    WidgetCard.chartAppend(this, sec, down, up)
                    SessionHistory.recordPeak(this, down, up)
                } catch (_: Exception) {}
            }
            val statParts = mutableListOf<String>()
            if (down >= 0) statParts += "↓ ${fmtKbps(down)} • ↑ ${fmtKbps(up)}"
            val latSamples = open?.latencySamples ?: emptyList()
            if (latSamples.isNotEmpty()) {
                val last = latSamples.last()
                val avg = open?.latencyAvgMs ?: 0L
                statParts += (if (fa) "پینگ: ${last}ms (میانگین ${avg}ms)"
                    else "Latency: ${last}ms (avg ${avg}ms)")
            }
            val startMs = open?.startMs ?: 0L
            if (startMs > 0) {
                statParts += (if (fa) "مدت: " else "Uptime: ") +
                    SessionHistory.formatDuration(System.currentTimeMillis() - startMs)
            }
            liveStats.text = if (statParts.isEmpty()) {
                L("سرعت هنوز خوانده نشده است…", "Speed not read yet…")
            } else statParts.joinToString(" • ")
            // 1.73 (D): same read as the speed sample
            // above — one source for bytes and graph.
            val (rx, tx) = rxNow to txNow
            liveBytes.text = (if (fa) "مصرف این نشست: " else "This session: ") +
                SessionHistory.formatBytes(rx + tx) +
                " (↓ " + SessionHistory.formatBytes(rx) +
                " / ↑ " + SessionHistory.formatBytes(tx) + ")"
            // Graph: the section's chart samples (the
            // widget's store — same data, same painter).
            try {
                val (dSamples, uSamples) = WidgetCard.chartSamples(this, sec)
                if (dSamples.isEmpty() && uSamples.isEmpty()) {
                    graphView.visibility = android.view.View.GONE
                    graphEmpty.text = L("نمودار هنوز داده‌ای ندارد — چند ثانیه صبر کن.",
                        "The graph has no data yet — wait a few seconds.")
                    graphEmpty.visibility = android.view.View.VISIBLE
                } else {
                    val w = resources.displayMetrics.widthPixels - dp(56)
                    val bmp = WidgetCard.paintChart(w, dp(96), dSamples, uSamples,
                        Color.parseColor("#FF0F766E"), Color.parseColor("#FFF9A825"),
                        resources.displayMetrics.density)
                    graphView.setImageBitmap(bmp)
                    graphView.visibility = android.view.View.VISIBLE
                    graphEmpty.visibility = android.view.View.GONE
                }
            } catch (_: Exception) {
                graphView.visibility = android.view.View.GONE
                graphEmpty.visibility = android.view.View.GONE
            }
            // Latency probe, at most once per 15s.
            val now = System.currentTimeMillis()
            if (now - lastLatencyProbeMs > 15_000) {
                lastLatencyProbeMs = now
                Thread {
                    try {
                        val ms = probeLatencyMs()
                        if (ms != null) SessionHistory.recordLatency(this, ms)
                    } catch (_: Exception) {}
                }.start()
            }
            // Exit IP + country, once per session.
            if (open != null && open.exitIp.isBlank() && open.startMs != exitFetchedForStartMs) {
                exitFetchedForStartMs = open.startMs
                Thread {
                    try {
                        val ip = ConnVerify.exitIpProbe()
                        if (!ip.isNullOrBlank()) {
                            val geo = try { GeoFlag.lookupSync(ip) } catch (_: Exception) { null }
                            val triple = geo?.first
                            SessionHistory.setExit(this, ip,
                                triple?.first ?: "", triple?.second ?: "", triple?.third ?: "")
                        }
                    } catch (_: Exception) {}
                }.start()
            }
        }
        // ---- Counters (today / total / drops) ----
        try {
            val t = SessionHistory.totals(this)
            val (drops, reconnects) = SessionHistory.counters(this)
            val liveBytesNow = try { SessionHistory.openBytesNow(this) } catch (_: Exception) { 0L to 0L }
            val todayShown = t[0] + t[1] + liveBytesNow.first + liveBytesNow.second
            countersText.text =
                (if (fa) "امروز: " else "Today: ") + SessionHistory.formatBytes(todayShown) +
                (if (fa) " • کل: " else " • Total: ") + SessionHistory.formatBytes(t[2] + t[3]) +
                (if (fa) " • قطع: " else " • Drops: ") + drops +
                (if (fa) " • وصل مجدد: " else " • Reconnects: ") + reconnects
        } catch (_: Exception) {}
        // ---- Share button: open session, else the
        // last finished one. ----
        val shareTarget = open ?: try { SessionHistory.list(this).firstOrNull() } catch (_: Exception) { null }
        shareCurrentBtn.visibility =
            if (shareTarget != null) android.view.View.VISIBLE else android.view.View.GONE
        // ---- History list (rebuild only on change) ----
        try {
            val r = SessionHistory.rev(this)
            if (r != lastHistoryRev) {
                lastHistoryRev = r
                rebuildHistory()
            }
        } catch (_: Exception) {}
    }

    /** Rebuild the past-sessions list (newest first,
     *  at most 8 rows shown). */
    private fun rebuildHistory() {
        if (!::historyList.isInitialized) return
        val fa = !AppLanguage.isEnglish(this)
        val sessions = try { SessionHistory.list(this) } catch (_: Exception) { emptyList() }
        historyList.removeAllViews()
        if (sessions.isEmpty()) {
            historyEmpty.visibility = android.view.View.VISIBLE
            historyEmpty.text = L("هنوز نشستی ثبت نشده — وقتی اتصالی برقرار شود، اینجا می‌آید.",
                "No sessions recorded yet — when a connection is up, it appears here.")
            return
        }
        historyEmpty.visibility = android.view.View.GONE
        for (s in sessions.take(8)) {
            historyList.addView(historyRow(s, fa))
        }
    }

    private fun historyRow(s: SessionHistory.Session, fa: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.glassCard(this@AppLogActivity)
            elevation = 3f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(4), 0, dp(4))
            }
            isClickable = true
            setOnClickListener { showSessionDetail(s) }
        }
        val title = SessionHistory.sectionDisplay(s.section, fa) +
            (if (s.name.isNotBlank()) " • ${s.name}" else "") +
            " • " + SessionHistory.formatDuration(s.durationMs)
        row.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (Theme.isDark(this@AppLogActivity)) Color.parseColor("#A5C8FF") else Color.parseColor("#FF1A237E"))
            textDirection = android.view.View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
        })
        val line2 = mutableListOf<String>()
        line2 += SessionHistory.formatDayTime(s.startMs)
        line2 += SessionHistory.formatBytes(s.bytes)
        val exit = when {
            s.exitCountry.isNotBlank() -> s.exitCountry + (if (s.exitIp.isNotBlank()) " ${s.exitIp}" else "")
            s.exitIp.isNotBlank() -> s.exitIp
            else -> if (fa) "خروج: —" else "Exit: —"
        }
        line2 += exit
        row.addView(TextView(this).apply {
            text = line2.joinToString(" • ")
            textSize = 12f
            setTextColor(if (Theme.isDark(this@AppLogActivity)) Color.parseColor("#C7FFFFFF") else Color.parseColor("#FF37474F"))
            textDirection = android.view.View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, dp(2), 0, 0)
        })
        val flags = mutableListOf<String>()
        if (s.quickRestart) flags += if (fa) "وصل مجدد" else "Reconnected"
        if (s.endedWithError) flags += if (fa) "پایان با خطا" else "Ended with error"
        if (flags.isNotEmpty()) {
            row.addView(TextView(this).apply {
                text = flags.joinToString(" • ")
                textSize = 11f
                setTextColor(Color.parseColor("#FFE65100"))
                textDirection = android.view.View.TEXT_DIRECTION_LOCALE
                gravity = Gravity.START
                setPadding(0, dp(2), 0, 0)
            })
        }
        return row
    }

    /** Full detail of one session, with share + copy.
     *  The text never contains a config link or key —
     *  the record never stores one. */
    private fun showSessionDetail(s: SessionHistory.Session) {
        try {
            val fa = !AppLanguage.isEnglish(this)
            val text = SessionHistory.exportText(s, fa)
            android.app.AlertDialog.Builder(this)
                .setMessage(text)
                .setPositiveButton(L("اشتراک‌گذاری", "Share")) { _, _ -> shareSessionText(text) }
                .setNeutralButton(L("کپی", "Copy")) { _, _ ->
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("session-summary", text))
                        Toast.makeText(this,
                            L("خلاصه نشست کپی شد.", "Session summary copied."),
                            Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }
                .setNegativeButton(L("باشه", "OK"), null)
                .show().also { Theme.styleDialog(it, this) }
        } catch (_: Exception) {}
    }

    private fun shareSessionText(text: String) {
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(i,
                L("اشتراک‌گذاری خلاصه نشست", "Share session summary")))
        } catch (_: Exception) {}
    }

    /** Share the open session, else the last finished one. */
    private fun shareCurrentSession() {
        try {
            val fa = !AppLanguage.isEnglish(this)
            val target = SessionHistory.open(this)
                ?: SessionHistory.list(this).firstOrNull()
            if (target == null) {
                Toast.makeText(this,
                    L("نشستی برای اشتراک نیست.", "No session to share."),
                    Toast.LENGTH_SHORT).show()
                return
            }
            // Fill in this session's live bytes before
            // exporting (the stored record only gets
            // them when it ends).
            val live = if (target.isOpen) {
                val (rx, tx) = SessionHistory.openBytesNow(this)
                target.copy(rxBytes = rx, txBytes = tx)
            } else target
            shareSessionText(SessionHistory.exportText(live, fa))
        } catch (_: Exception) {}
    }

    /** 2.58: one plain-text monitoring report:
     *  verdict + every card (state, line,
     *  inventory, last-event time) + winning
     *  methods. Computed only from existing
     *  state, like the cards themselves. */
    private fun copyMonitoringReport() {
        try {
            val cards = Monitoring.collect(this)
            val stamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(java.util.Date())
            val sb = StringBuilder()
            sb.append(L("گزارش مانیتورینگ برنامه — $stamp",
                "App monitoring report — $stamp")).append("\n")
            if (::summaryText.isInitialized) sb.append(summaryText.text).append("\n")
            for (c in cards) {
                val title = L(c.titleFa, c.titleEn)
                val line = L(c.lineFa, c.lineEn)
                val inv = L(c.inventoryFa, c.inventoryEn)
                val state = when (c.dot) {
                    Monitoring.DOT_GREEN -> L("متصل", "connected")
                    Monitoring.DOT_AMBER -> L("خطا", "failure")
                    Monitoring.DOT_TEAL -> L("فعال", "on")
                    else -> L("خاموش", "off")
                }
                sb.append("• $title: $state — $line")
                if (inv.isNotBlank()) sb.append(" ($inv)")
                if (c.time.isNotBlank()) sb.append(" [${c.time}]")
                sb.append("\n")
            }
            if (::winningText.isInitialized && winningText.text.isNotBlank()) {
                sb.append(winningText.text).append("\n")
            }
            // 1.72: session appendix — counters + the
            // open (else last) session's summary.
            try {
                val t = SessionHistory.totals(this)
                sb.append(L("مصرف امروز: ", "Today: "))
                    .append(SessionHistory.formatBytes(t[0] + t[1]))
                    .append(L(" • کل: ", " • total: "))
                    .append(SessionHistory.formatBytes(t[2] + t[3])).append("\n")
                val (drops, reconnects) = SessionHistory.counters(this)
                sb.append(L("قطع: ", "Drops: ")).append(drops)
                    .append(L(" • وصل مجدد: ", " • reconnects: ")).append(reconnects).append("\n")
                val target = SessionHistory.open(this)
                    ?: SessionHistory.list(this).firstOrNull()
                if (target != null) {
                    sb.append(SessionHistory.exportText(target, !AppLanguage.isEnglish(this)))
                        .append("\n")
                }
            } catch (_: Exception) {}
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("monitoring-report", sb.toString()))
            android.widget.Toast.makeText(this,
                L("گزارش مانیتورینگ کپی شد.", "Monitoring report copied."),
                android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun refreshLog() {
        if (!::logBox.isInitialized) return
        // Log: everything, in stored order (oldest
        // first, like the old full log). The box
        // opens at the bottom so the newest lines
        // are seen at once; scroll up for older.
        val all = allLines()
        copyBtn.text = L("کپی لاگ", "Copy log")
        logBox.text = if (all.isEmpty()) L(
            "هنوز چیزی ثبت نشده است. وقتی در بخش‌های برنامه اتصالی انجام شود، اینجا می‌نویسد چه شد.",
            "Nothing recorded yet. When a connection happens in any section, it will be written here.")
        else AppLog.styledLog(all, Theme.isDark(this))
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

}
