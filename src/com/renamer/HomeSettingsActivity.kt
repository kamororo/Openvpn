package com.renamer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File

/** Settings page opened from the Home (apps) page.
 *  Each row opens one settings section; the sections
 *  themselves live in their own activities (created by
 *  other slices / the parent), this page only links to
 *  them. */
class HomeSettingsActivity : Activity() {
    // 2.80: the language section is collapsed by default; tapping its
    // header expands it, and picking a language recreates (collapsed again).
    private var langExpanded = false

    // 1.75: the persistent update-download box (BELOW the
    // «بررسی بروزرسانی» row). The box is rebuilt only when its
    // identity changes (version + state + action); while a
    // download runs, the 500 ms poll only moves the bar, so the
    // box never flickers or loses its place.
    private var downloadBoxSlot: LinearLayout? = null
    private var downloadBoxView: LinearLayout? = null
    private var downloadBoxBar: ProgressBar? = null
    private var downloadBoxPercent: TextView? = null
    private var downloadBoxState: TextView? = null
    private var downloadBoxKey: String? = null
    private val downloadBoxHandler = Handler(Looper.getMainLooper())
    private var downloadBoxPolling = false
    private val downloadBoxPoll = object : Runnable {
        override fun run() {
            if (!downloadBoxPolling) return
            try {
                refreshUpdateDownloadBox()
            } catch (_: Exception) {
            }
            if (downloadBoxPolling) downloadBoxHandler.postDelayed(this, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            refreshUpdateDownloadBox()
        } catch (_: Exception) {
        }
        downloadBoxPolling = true
        downloadBoxHandler.removeCallbacks(downloadBoxPoll)
        downloadBoxHandler.postDelayed(downloadBoxPoll, 500)
    }

    override fun onPause() {
        downloadBoxPolling = false
        try {
            downloadBoxHandler.removeCallbacks(downloadBoxPoll)
        } catch (_: Exception) {
        }
        super.onPause()
    }

    /** 1.75: (re)build or refresh the persistent download box
     *  from the stored pending record. The box STAYS until the
     *  new version is installed:
     *   - DOWNLOADING + a live download -> live bar + percent.
     *   - DOWNLOADING + NO live download -> the process died
     *     mid-download: "interrupted", with a retry button.
     *   - READY + file on disk, right size -> [نصب].
     *   - FAILED -> [تلاش دوباره].
     *   - No record, installed already, or a READY record whose
     *     file is gone/wrong size -> no box (stale records are
     *     cleared, never offered as a phantom install). */
    private fun refreshUpdateDownloadBox() {
        val slot = downloadBoxSlot ?: return
        var pending = try {
            UpdateChecker.loadPending(this)
        } catch (_: Exception) {
            null
        }
        if (pending != null) {
            val installed = try {
                UpdateChecker.installedVersionName(this)
            } catch (_: Exception) {
                ""
            }
            if (UpdateDownloadPure.shouldClearAsInstalled(installed, pending)) {
                try {
                    UpdateChecker.clearPending(this)
                } catch (_: Exception) {
                }
                pending = null
            }
        }
        if (pending == null) {
            slot.visibility = View.GONE
            if (downloadBoxView != null) {
                slot.removeAllViews()
                downloadBoxView = null
                downloadBoxKey = null
            }
            return
        }
        val file = try {
            UpdateChecker.pendingApkFile(this, pending)
        } catch (_: Exception) {
            null
        }
        val action = UpdateDownloadPure.resolveAction(
            pending, file != null, file?.length() ?: 0L
        )
        if (action == UpdateDownloadPure.PendingAction.NONE) {
            try {
                UpdateChecker.clearPending(this)
            } catch (_: Exception) {
            }
            slot.visibility = View.GONE
            slot.removeAllViews()
            downloadBoxView = null
            downloadBoxKey = null
            return
        }
        val live = UpdateChecker.liveDownloading &&
            UpdateChecker.liveDownloadTag == pending.tag
        val interrupted = pending.state == UpdateDownloadPure.PendingState.DOWNLOADING && !live
        val key = pending.tag + "|" + pending.state.name + "|" + action.name + "|" + interrupted
        if (downloadBoxView == null || downloadBoxKey != key) {
            buildUpdateDownloadBox(slot, pending, action, interrupted, file)
            downloadBoxKey = key
        }
        updateUpdateDownloadBoxProgress(pending, interrupted)
        slot.visibility = View.VISIBLE
    }

    /** Build the box for one (version, state, action) — called
     *  only when the key changes. Progress values are filled in
     *  by [updateUpdateDownloadBoxProgress]. */
    private fun buildUpdateDownloadBox(
        slot: LinearLayout,
        pending: UpdateDownloadPure.PendingDownload,
        action: UpdateDownloadPure.PendingAction,
        interrupted: Boolean,
        file: File?
    ) {
        val fg = Theme.fg(this)
        val subFg = Theme.subFg(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.glassCard(this@HomeSettingsActivity, 18f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), 0, dp(8)) }
        }
        val versionText = if (AppLanguage.isEnglish(this)) pending.tag
        else SpeedTestPanelPure.faDigits(UpdateCheckerPure.displayVersion(pending.tag))
            .replace('.', '٫')
        box.addView(TextView(this).apply {
            text = L("دانلود نسخه " + versionText, "Downloading version " + pending.tag)
            textSize = 15f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(fg)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
        })
        val stateText = TextView(this).apply {
            textSize = 13f
            setTextColor(subFg)
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, dp(4), 0, 0)
        }
        box.addView(stateText)
        downloadBoxState = stateText
        // Downloading: a progress bar + percent, IN THIS BOX.
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility =
                if (action == UpdateDownloadPure.PendingAction.DOWNLOADING) View.VISIBLE
                else View.GONE
            setPadding(0, dp(12), 0, 0)
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val percentText = TextView(this).apply {
            textSize = 13f
            setTextColor(subFg)
            setPadding(dp(8), 0, 0, 0)
        }
        progressRow.addView(bar)
        progressRow.addView(percentText)
        box.addView(progressRow)
        downloadBoxBar = bar
        downloadBoxPercent = percentText
        // Ready: the file name, tap [نصب] to install.
        if (action == UpdateDownloadPure.PendingAction.INSTALL) {
            box.addView(TextView(this).apply {
                text = UpdateCheckerPure.sanitizeApkName(pending.apkName)
                textSize = 12f
                setTextColor(subFg)
                textDirection = View.TEXT_DIRECTION_LOCALE
                gravity = Gravity.START
                setPadding(0, dp(2), 0, 0)
            })
        }
        // The action row: [نصب] when ready, [دانلود دوباره] when
        // failed or interrupted, nothing while it downloads.
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = if (AppLanguage.isEnglish(this@HomeSettingsActivity))
                View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
            setPadding(0, dp(12), 0, 0)
        }
        when (action) {
            UpdateDownloadPure.PendingAction.INSTALL -> {
                val target = file
                buttons.addView(Button(this).apply {
                    text = L("نصب", "Install")
                    textSize = 15f
                    textDirection = View.TEXT_DIRECTION_LOCALE
                    setPadding(dp(20), dp(8), dp(20), dp(8))
                    minimumWidth = dp(96)
                    background = Theme.neonRect(this@HomeSettingsActivity, 14f)
                    setTextColor(Theme.neonTextColor(this@HomeSettingsActivity))
                    setOnClickListener {
                        val f = target ?: return@setOnClickListener
                        UpdateUi.installDownloaded(this@HomeSettingsActivity, f)
                    }
                })
            }
            UpdateDownloadPure.PendingAction.RETRY -> {
                buttons.addView(Button(this).apply {
                    text = L("تلاش دوباره", "Try again")
                    textSize = 15f
                    textDirection = View.TEXT_DIRECTION_LOCALE
                    setPadding(dp(20), dp(8), dp(20), dp(8))
                    minimumWidth = dp(96)
                    background = Theme.neonRect(this@HomeSettingsActivity, 14f)
                    setTextColor(Theme.neonTextColor(this@HomeSettingsActivity))
                    setOnClickListener { retryUpdateDownload(pending) }
                })
            }
            UpdateDownloadPure.PendingAction.DOWNLOADING -> {
                if (interrupted) {
                    buttons.addView(Button(this).apply {
                        text = L("دانلود دوباره", "Download again")
                        textSize = 15f
                        textDirection = View.TEXT_DIRECTION_LOCALE
                        setPadding(dp(20), dp(8), dp(20), dp(8))
                        minimumWidth = dp(96)
                        background = Theme.neonRect(this@HomeSettingsActivity, 14f)
                        setTextColor(Theme.neonTextColor(this@HomeSettingsActivity))
                        setOnClickListener { retryUpdateDownload(pending) }
                    })
                }
            }
            else -> {
            }
        }
        if (buttons.childCount > 0) box.addView(buttons)
        slot.removeAllViews()
        slot.addView(box)
        downloadBoxView = box
    }

    /** Move the bar + state line for the current progress. Live
     *  numbers come from the running download when there is one;
     *  otherwise the last saved counts in the pending record. */
    private fun updateUpdateDownloadBoxProgress(
        pending: UpdateDownloadPure.PendingDownload,
        interrupted: Boolean
    ) {
        val live = UpdateChecker.liveDownloading &&
            UpdateChecker.liveDownloadTag == pending.tag
        val downloaded =
            if (live) UpdateChecker.liveDownloaded else pending.downloadedBytes
        val total =
            if (live) UpdateChecker.liveTotal else pending.totalBytes
        val percent = UpdateCheckerPure.percent(downloaded, total)
        val bar = downloadBoxBar
        val percentText = downloadBoxPercent
        if (bar != null && percentText != null) {
            if (percent < 0) {
                bar.isIndeterminate = true
                percentText.text = ""
            } else {
                bar.isIndeterminate = false
                bar.progress = percent
                percentText.text = if (AppLanguage.isEnglish(this)) "$percent%"
                else SpeedTestPanelPure.faDigits(percent.toString()) + "٪"
            }
        }
        downloadBoxState?.text = when {
            interrupted -> L(
                "دانلود ناتمام ماند؛ برای ادامه دوباره دانلود کن.",
                "The download was interrupted; download again to continue."
            )
            pending.state == UpdateDownloadPure.PendingState.READY ->
                L("دانلود شد — آماده نصب است", "Downloaded — ready to install")
            pending.state == UpdateDownloadPure.PendingState.FAILED ->
                L("دانلود ناموفق بود؛ دوباره تلاش کن.", "Download failed; try again.")
            else -> L("در حال دانلود…", "Downloading…")
        }
    }

    /** Restart a failed / interrupted download from scratch (a
     *  half-written file is never resumed). The pending record
     *  flips back to DOWNLOADING at once, so the box shows the
     *  fresh progress on the next poll. */
    private fun retryUpdateDownload(pending: UpdateDownloadPure.PendingDownload) {
        val rel = pending.toRelease()
        try {
            UpdateChecker.downloadApk(
                this, rel,
                onProgress = { _, _, _ -> },
                onDone = {
                    try {
                        refreshUpdateDownloadBox()
                    } catch (_: Exception) {
                    }
                },
                origin = pending.origin
            )
        } catch (_: Exception) {
        }
        try {
            refreshUpdateDownloadBox()
        } catch (_: Exception) {
        }
    }

    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 2.84: the window behind the content follows the mode,
        // so no white strips remain at the top or bottom.
        try { Theme.applyPage(this) } catch (_: Exception) {}
        val bg = Theme.bg(this)
        val fg = Theme.fg(this)
        val subFg = Theme.subFg(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        // 2.89: the background lives on the scroll (full-screen),
        // not on this content-height root — one uniform background
        // to the bottom edge, no seam below the Back button.
        val scroll = ScrollView(this).apply {
            background = Theme.pageBackground(this@HomeSettingsActivity)
            isFillViewport = true
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = L("تنظیمات", "Settings"); textSize = 22f
            setTextColor(fg)
            setPadding(0, 0, 0, dp(8))
        })

        // Compact icon rows (1.79): one tappable row
        // per setting — a small tinted icon circle, a
        // 15sp title and a short 12sp description that
        // wraps to at most two lines, plus a chevron.
        // The old layout (a full-width 17sp button +
        // a description block under it) took a whole
        // screen for five rows.
        fun settingRow(icon: String, tint: String, title: String, desc: String, target: Class<*>? = null, onClick: (() -> Unit)? = null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(12), dp(8), dp(12))
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    if (onClick != null) { onClick(); return@setOnClickListener }
                    val t = target ?: return@setOnClickListener
                    val i = Intent(this@HomeSettingsActivity, t)
                    if (t == GuideActivity::class.java || t == LoginPersonalizationActivity::class.java) {
                        i.putExtra(PinActivity.EXTRA_MODE,
                            intent.getStringExtra(PinActivity.EXTRA_MODE) ?: PinActivity.MODE_PUBLIC)
                    }
                    startActivity(i)
                }
            }
            // 2.84: the row icon in the new icon style — the same
            // cyan→violet gradient as the 2.83 home icons, soft glow
            // in night, flat in day (drawn, no new asset needed).
            row.addView(android.widget.ImageView(this).apply {
                setImageBitmap(Theme.gradientGlyphBitmap(this@HomeSettingsActivity, icon, 36, Theme.isDark(this@HomeSettingsActivity)))
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#22$tint"))
                }
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            })
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(12), 0, dp(12), 0)
                }
            }
            col.addView(TextView(this).apply {
                text = title; textSize = 15f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                setTextColor(fg)
            })
            col.addView(TextView(this).apply {
                text = desc; textSize = 12f
                setTextColor(subFg)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(col)
            row.addView(TextView(this).apply {
                text = "‹"; textSize = 20f
                setTextColor(subFg)
            })
            root.addView(row)
            root.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { setMargins(0, dp(4), 0, dp(4)) }
setBackgroundColor(Theme.divider(this@HomeSettingsActivity))
            })
        }

        settingRow(
            "⇅", "2196F3",
            L("تنظیمات صفحه اصلی", "Home settings"),
            L("جای برنامه‌ها را در صفحه اصلی عوض کن.", "Reorder the apps on Home."),
            ReorderActivity::class.java
        )
        // 1.29: Widgets — only for sections this app has
        // (v2ray, WireGuard & Amnezia, OpenVPN, Emergency,
        // Smart Connect, Cloudflare, Monitoring).
        settingRow(
            "▦", "4CAF50",
            L("ویجت صفحه اصلی", "Home screen widgets"),
            L("یک بخش را به صفحه اصلی ببر تا بدون باز کردن برنامه وصل شوی.", "Put a section on the home screen to connect without opening the app."),
            WidgetStudioActivity::class.java
        )
        settingRow(
            "ⓘ", "009688",
            L("راهنمایی", "Guide"),
            L("توضیح هر بخش، جدا جدا.", "Each section explained, one by one."),
            GuideActivity::class.java
        )
        // 1.75 (E): «درباره ما» moved to directly above
        // «بررسی بروزرسانی» (the last option); it already existed,
        // so it is only reordered, not recreated.

        // 2.79: language moved here from the Home top bar (the FA button is
        // gone). A separate section with the same two choices the FA sheet
        // had: tapping a row switches the language and recreates.
        // 2.80: COLLAPSED by default (user report: both languages always
        // expanded). The header shows only the current language; tap it to
        // expand, tap a language to apply it and collapse again.
        val isEn = AppLanguage.isEnglish(this)
        val langBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val langChevron = TextView(this).apply {
            text = "▸"
            textSize = 16f
            setTextColor(fg)
        }
        val langHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(16), dp(8), dp(4))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                langExpanded = !langExpanded
                langBox.visibility = if (langExpanded) View.VISIBLE else View.GONE
                langChevron.text = if (langExpanded) "▾" else "▸"
            }
        }
        // 2.95: the language row gets an icon circle like the
        // other rows ("فا", the same glyph the Guide uses).
        langHeader.addView(android.widget.ImageView(this).apply {
            setImageBitmap(Theme.gradientGlyphBitmap(this@HomeSettingsActivity, "فا", 36, Theme.isDark(this@HomeSettingsActivity)))
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#22FF9800"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        })
        langHeader.addView(TextView(this).apply {
            text = L("زبان برنامه", "App language")
            textSize = 15f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(fg)
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(dp(12), 0, dp(12), 0) }
        })
        langHeader.addView(TextView(this).apply {
            text = if (isEn) "English" else "فارسی"
            textSize = 14f
            setTextColor(fg)
            setPadding(dp(8), 0, dp(8), 0)
        })
        langHeader.addView(langChevron)
        root.addView(langHeader)
        root.addView(langBox)
        val langSelBg = if (Theme.isDark(this)) Theme.darken(Color.parseColor("#1E3A24")) else Color.parseColor("#E6F4EA")
        val langSelFg = if (Theme.isDark(this)) Color.parseColor("#A5D6A7") else Color.parseColor("#14532D")
        fun langRow(label: String, selected: Boolean, lang: String) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(if (selected) langSelBg else Color.TRANSPARENT)
                }
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(4), 0, 0) }
                setOnClickListener {
                    AppLanguage.set(this@HomeSettingsActivity, lang)
                    recreate()
                }
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(if (selected) langSelFg else fg)
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            row.addView(TextView(this).apply {
                text = if (selected) "✓" else ""
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(langSelFg)
            })
            // 2.80: language rows live in the collapsible box, not on root.
            langBox.addView(row)
        }
        langRow("فارسی", !isEn, "fa")
        langRow("English", isEn, "en")

        // 2.79: night/day toggle moved here from the Home top bar (the ☾
        // button is gone). Same behavior: flips the dark_mode pref and
        // recreates, like the old button did.
        // 1.75 (D): the «ظاهر» section label above the night-mode
        // row is deleted (user order); the night-mode row itself
        // stays, directly under the language section.
        // 2.95: no on/off toggle anymore. The row opens an
        // app-styled menu (night / day / follow the phone);
        // the icon follows the choice (sun / moon / combined
        // half-sun-half-moon) and the row shows the current
        // choice as its value text.
        val mode = Theme.displayMode(this)
        val modeLabel = when (mode) {
            "day" -> L("روز", "Day")
            "system" -> L("هماهنگ با ساعت شب و روز گوشی", "Follow the phone's day/night")
            else -> L("شب", "Night")
        }
        val nightRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(12))
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { showThemeModeDialog() }
        }
        // 2.84: same gradient treatment as the other row icons.
        nightRow.addView(android.widget.ImageView(this).apply {
            setImageBitmap(Theme.modeGlyphBitmap(this@HomeSettingsActivity, mode, 36, Theme.isDark(this@HomeSettingsActivity)))
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#22FF8A65"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        })
        val nightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(12), 0, dp(12), 0)
            }
        }
        nightCol.addView(TextView(this).apply {
            text = L("حالت شب", "Night mode")
            textSize = 15f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(fg)
        })
        nightCol.addView(TextView(this).apply {
            text = modeLabel
            textSize = 12f
            setTextColor(subFg)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        nightRow.addView(nightCol)
        nightRow.addView(TextView(this).apply {
            text = "‹"; textSize = 20f
            setTextColor(subFg)
        })
        root.addView(nightRow)
        // 1.75 (E): «درباره ما» — directly above «بررسی بروزرسانی».
        // It already existed (2.95, opens the app's Telegram channel);
        // only its position changed. 2.96: the icon is an info
        // circle, not a paper plane (a plane has nothing to do with
        // About); the tap opens the system chooser titled "open with
        // Telegram", so Telegram is offered when it is installed
        // instead of the system silently picking a default handler.
        settingRow(
            "ⓘ", "229ED9",
            L("درباره ما", "About us"),
            L("کانال تلگرام برنامه", "The app's Telegram channel."),
            null
        ) {
            try {
                val view = Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://t.me/internet_azad_2026"))
                startActivity(Intent.createChooser(view,
                    L("باز کردن با تلگرام", "Open with Telegram")))
            } catch (_: Exception) {}
        }
        // 1.75: the persistent download box lives BELOW the
        // «بررسی بروزرسانی» row — it is filled in by
        // refreshUpdateDownloadBox() (from the stored pending
        // record) and stays there until the new version is
        // installed. The slot itself is created here so the box
        // keeps its place even when empty (GONE).
        downloadBoxSlot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        // 1.73 (worker C): «بررسی بروزرسانی» — the LAST option in
        // Settings. Tapping it checks GitHub in the background:
        // latest -> «برنامه شما اخرین ورژن هست» for exactly 4s;
        // update -> the new version + in-app download, then the
        // file to tap to install. A failed check shows a brief
        // error only. (UpdateUi.showManualCheck.)
        settingRow(
            "↓", "7C4DFF",
            L("بررسی بروزرسانی", "Check for updates"),
            L("نسخه جدید را از گیت‌هاب بررسی کن.", "Check GitHub for a new version."),
            null
        ) {
            // 1.75: the callback refreshes the persistent box
            // below this row when the dialog's download starts
            // or finishes — the download outlives the dialog.
            try {
                UpdateUi.showManualCheck(this) {
                    try {
                        refreshUpdateDownloadBox()
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {}
        }
        // 1.75: the box slot sits directly under the check row.
        downloadBoxSlot?.let { root.addView(it) }
        try {
            refreshUpdateDownloadBox()
        } catch (_: Exception) {
        }
        setContentView(scroll)
        scroll.fitsSystemWindows = true
    }

    /** 2.95: the night-mode menu — night / day / follow the
     *  phone. Tapping a choice saves theme_mode and
     *  recreates this page; Home recreates itself on
     *  resume when the mode changed, and "system" is
     *  re-evaluated from the phone's UI mode on every
     *  isDark() call, so it really follows the phone. */
    private fun showThemeModeDialog() {
        val labels = arrayOf(
            L("شب", "Night"),
            L("روز", "Day"),
            L("هماهنگ با ساعت شب و روز گوشی", "Follow the phone's day/night")
        )
        val values = arrayOf("night", "day", "system")
        val checked = when (Theme.displayMode(this)) {
            "day" -> 1
            "system" -> 2
            else -> 0
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(L("حالت شب", "Night mode"))
            .setSingleChoiceItems(labels, checked) { dlg, which ->
                getSharedPreferences("renamer_settings", MODE_PRIVATE)
                    .edit().putString("theme_mode", values[which]).apply()
                try { dlg.dismiss() } catch (_: Exception) {}
                recreate()
            }
            .setNegativeButton(L("لغو", "Cancel"), null)
            .show().also { Theme.styleDialog(it, this) }
    }
}
