package com.renamer

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** 1.73 (worker C): the update checker's two faces, both drawn with
 *  the app's own theme helpers (Theme.glassCard / neonRect /
 *  dialogCard, Theme.fg/subFg) so the 1.73 night darkening applies
 *  to them like any other section — no colours are hardcoded here
 *  beyond the shared theme.
 *
 *  1. HOME BOX (auto-check, 1.75: on EVERY entry — cold start
 *     AND every return to the foreground, via
 *     autoCheckOnEntry called from HomeActivity.onResume; the
 *     1.73 once-per-launch latch is gone): a glass card between
 *     the quick-connect card and the tile grid saying «ورژن جدید
 *     منتشر شده و قابل دریافت است», with [دانلود] [بعدا] below.
 *     [دانلود] swaps the buttons for a progress bar IN THE SAME
 *     BOX; when the download finishes the box shows the file —
 *     tap it to install. [بعدا] dismisses for this session FOR
 *     THAT RELEASE ONLY (a newer release still shows its box).
 *     A failed auto-check shows nothing at all (silent), and a
 *     check already running is never stacked — the entry just
 *     does nothing until the running fetch posts.
 *  2. MANUAL CHECK (Settings > «بررسی بروزرسانی», the LAST option):
 *     a compact dialog. Latest -> «برنامه شما اخرین ورژن هست» for
 *     exactly 4 seconds, then gone. Update -> the new version +
 *     [دانلود]; when the download finishes the dialog shows the
 *     file with [نصب]. A failed check shows a brief error only.
 *     1.75: a download started here ALSO lives on as a
 *     persistent box BELOW the «بررسی بروزرسانی» row in Settings
 *     (HomeSettingsActivity) — same download, same file — so the
 *     user can close this dialog, leave Settings, come back, and
 *     still find the progress / the [نصب] button waiting.
 *
 *  RTL: every programmatic TextView sets textDirection LOCALE +
 *  gravity START, and every button row sets its layoutDirection
 *  from the current language (RTL in Persian, like HomeActivity's
 *  grid) — Persian starts from the right, English from the left. */
object UpdateUi {

    private fun L(ctx: Activity, fa: String, en: String): String = AppLanguage.t(ctx, fa, en)
    private fun dp(ctx: Activity, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** The version as the user reads it: Persian digits + «٫» in
     *  Persian mode ("۱٫۷۴"), Latin otherwise ("1.74"). */
    private fun faVersion(ctx: Activity, tag: String): String {
        val v = UpdateCheckerPure.displayVersion(tag)
        return if (AppLanguage.isEnglish(ctx)) v
        else SpeedTestPanelPure.faDigits(v).replace('.', '٫')
    }

    /** A themed button: neon for the primary action ([دانلود] /
     *  [نصب]), plain glass for the secondary ([بعدا] / [بستن]). */
    private fun actionButton(ctx: Activity, text: String, primary: Boolean): Button {
        return Button(ctx).apply {
            this.text = text
            textSize = 15f
            textDirection = View.TEXT_DIRECTION_LOCALE
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
            minimumWidth = dp(ctx, 96)
            background = if (primary) Theme.neonRect(ctx, 14f)
            else Theme.glassCard(ctx, 14f)
            setTextColor(
                if (primary) Theme.neonTextColor(ctx)
                else Theme.fg(ctx)
            )
        }
    }

    /** A horizontal button row whose direction follows the current
     *  language (RTL in Persian: [دانلود] sits right, [بعدا] left). */
    private fun buttonRow(ctx: Activity): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = if (AppLanguage.isEnglish(ctx))
                View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
            setPadding(0, dp(ctx, 12), 0, 0)
        }
    }

    /** Tap-to-install for a downloaded APK. NEEDS_PERMISSION opens
     *  the system "allow from this source" setting with an
     *  explanation first — the user comes back and taps again. */
    private fun tapInstall(activity: Activity, file: File) {
        installDownloaded(activity, file)
    }

    /** 1.75: the same tap-to-install, public so the Settings
     *  download box (HomeSettingsActivity) can offer [نصب] for
     *  the same downloaded file without duplicating the
     *  permission flow. */
    fun installDownloaded(activity: Activity, file: File) {
        when (UpdateChecker.installApk(activity, file)) {
            UpdateChecker.InstallResult.STARTED -> {
            }
            UpdateChecker.InstallResult.NEEDS_PERMISSION -> {
                Toast.makeText(
                    activity,
                    L(
                        activity,
                        "برای نصب، اجازه نصب از این برنامه را در تنظیمات گوشی بده.",
                        "To install, allow installs from this app in the phone settings."
                    ),
                    Toast.LENGTH_LONG
                ).show()
                UpdateChecker.openInstallPermissionSettings(activity)
            }
            UpdateChecker.InstallResult.FAILED -> {
                Toast.makeText(
                    activity,
                    L(activity, "نصب شروع نشد.", "Install could not start."),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ---------- 1. the home box (auto-check) ----------

    /** 1.75 (worker A): the auto-check on EVERY entry — cold
     *  start AND every return to the foreground. HomeActivity
     *  calls this from onResume (onCreate is always followed by
     *  onResume, so the cold start is covered too; onResume is
     *  also where a foreground return lands, which onCreate
     *  never sees). Silent when there is nothing to show
     *  (latest / error / dismissed-this-release).
     *
     *  Order matters:
     *   1. A release found earlier in this launch, not dismissed,
     *      is shown again without a new network call (Home
     *      recreates on a night/day flip) — and when the box is
     *      ALREADY on screen for that release (a download may be
     *      in progress) it is never rebuilt.
     *   2. No pending release, OR the pending one was dismissed
     *      with [بعدا]: fetch FRESH — a release NEWER than the
     *      dismissed one still shows its box. A fetch already
     *      running is never stacked (tryBeginAutoCheck).
     *   3. Latest / Error / same-dismissed-tag: silent, no box,
     *      no nagging — and nothing is latched, so the NEXT entry
     *      checks again (the 1.73 bug: a check that ran before
     *      the release existed, or failed offline, latched
     *      "done" for the whole process and the box never
     *      appeared on re-entry). */
    fun autoCheckOnEntry(activity: Activity, slot: LinearLayout) {
        try {
            val pending = UpdateChecker.lastKnownUpdate
            if (pending != null && !UpdateChecker.isDismissed(pending.tag)) {
                if (slot.childCount > 0 && slot.visibility == View.VISIBLE) return
                showHomeBox(activity, slot, pending)
                return
            }
            if (!UpdateChecker.tryBeginAutoCheck()) return
            UpdateChecker.checkLatest(activity) { result ->
                try {
                    if (activity.isFinishing || activity.isDestroyed) return@checkLatest
                    if (result is UpdateChecker.CheckResult.Update &&
                        !UpdateChecker.isDismissed(result.release.tag)
                    ) {
                        showHomeBox(activity, slot, result.release)
                    }
                    // Latest / Error / dismissed same tag: silent.
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    /** The home update box, built into the slot. One box, three
     *  states in place: offer -> downloading -> downloaded file. */
    private fun showHomeBox(
        activity: Activity,
        slot: LinearLayout,
        release: UpdateCheckerPure.ReleaseInfo
    ) {
        val ctx = activity
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.glassCard(ctx, 18f)
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(ctx, 12)) }
        }
        box.addView(TextView(ctx).apply {
            text = L(ctx, "ورژن جدید منتشر شده و قابل دریافت است", "A new version is out and ready to download.")
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.fg(ctx))
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
        })
        box.addView(TextView(ctx).apply {
            text = L(ctx, "نسخه " + faVersion(ctx, release.tag), "Version " + release.displayVersion)
            textSize = 13f
            setTextColor(Theme.subFg(ctx))
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, dp(ctx, 4), 0, 0)
        })
        // Offer: [دانلود] [بعدا].
        val buttons = buttonRow(ctx)
        val downloadBtn = actionButton(ctx, L(ctx, "دانلود", "Download"), primary = true)
        val laterBtn = actionButton(ctx, L(ctx, "بعدا", "Later"), primary = false)
        buttons.addView(downloadBtn)
        buttons.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 10), 1)
        })
        buttons.addView(laterBtn)
        box.addView(buttons)
        // Downloading: a progress bar + percent, IN THE SAME BOX.
        val progressRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(ctx, 12), 0, 0)
        }
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val percentText = TextView(ctx).apply {
            textSize = 13f
            setTextColor(Theme.subFg(ctx))
            setPadding(dp(ctx, 8), 0, 0, 0)
        }
        progressRow.addView(bar)
        progressRow.addView(percentText)
        box.addView(progressRow)
        // Downloaded: the file, tap to install.
        val doneRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(ctx, 12), 0, 0)
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        doneRow.addView(TextView(ctx).apply {
            text = L(ctx, "دانلود شد — برای نصب بزن", "Downloaded — tap to install")
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.fg(ctx))
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
        })
        doneRow.addView(TextView(ctx).apply {
            text = UpdateCheckerPure.sanitizeApkName(release.apkName)
            textSize = 12f
            setTextColor(Theme.subFg(ctx))
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, dp(ctx, 2), 0, 0)
        })
        box.addView(doneRow)
        // Download failed: brief text, the offer comes back.
        val errorText = TextView(ctx).apply {
            text = L(ctx, "دانلود ناموفق بود؛ دوباره تلاش کن.", "Download failed; try again.")
            textSize = 13f
            setTextColor(Theme.subFg(ctx))
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            visibility = View.GONE
            setPadding(0, dp(ctx, 12), 0, 0)
        }
        box.addView(errorText)

        var downloadedFile: File? = null
        downloadBtn.setOnClickListener {
            buttons.visibility = View.GONE
            errorText.visibility = View.GONE
            progressRow.visibility = View.VISIBLE
            bar.isIndeterminate = true
            percentText.text = ""
            UpdateChecker.downloadApk(ctx, release,
                onProgress = { _, _, percent ->
                    try {
                        if (percent < 0) {
                            bar.isIndeterminate = true
                            percentText.text = ""
                        } else {
                            bar.isIndeterminate = false
                            bar.progress = percent
                            percentText.text = if (AppLanguage.isEnglish(ctx)) "$percent%"
                            else SpeedTestPanelPure.faDigits(percent.toString()) + "٪"
                        }
                    } catch (_: Exception) {
                    }
                },
                onDone = { file ->
                    try {
                        if (activity.isFinishing || activity.isDestroyed) return@downloadApk
                        if (file != null) {
                            downloadedFile = file
                            progressRow.visibility = View.GONE
                            doneRow.visibility = View.VISIBLE
                        } else {
                            progressRow.visibility = View.GONE
                            errorText.visibility = View.VISIBLE
                            buttons.visibility = View.VISIBLE
                        }
                    } catch (_: Exception) {
                    }
                },
                origin = "home")
        }
        laterBtn.setOnClickListener {
            UpdateChecker.markDismissedThisSession(release.tag)
            slot.visibility = View.GONE
            slot.removeAllViews()
        }
        doneRow.setOnClickListener {
            val f = downloadedFile ?: return@setOnClickListener
            tapInstall(ctx, f)
        }

        slot.removeAllViews()
        slot.addView(box)
        slot.visibility = View.VISIBLE
    }

    // ---------- 2. the manual check (Settings) ----------

    /** Settings > «بررسی بروزرسانی»: a compact dialog with the same
     *  flow as the home box, plus the timed "latest" message.
     *
     *  1.75: [onDownloadStateChanged] is called when a download
     *  STARTS and when it FINISHES (or fails), so Settings can
     *  refresh the persistent download box below the
     *  «بررسی بروزرسانی» row — the download itself keeps running
     *  in the background when the dialog is closed, and the box
     *  is what the user comes back to. */
    fun showManualCheck(activity: Activity, onDownloadStateChanged: (() -> Unit)? = null) {
        val ctx = activity
        val dialog = Dialog(ctx)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 18))
        }
        content.addView(TextView(ctx).apply {
            text = L(ctx, "بررسی بروزرسانی", "Check for updates")
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.fg(ctx))
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, 0, 0, dp(ctx, 12))
        })
        // Checking…
        val checkingRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        checkingRow.addView(ProgressBar(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28))
        })
        checkingRow.addView(TextView(ctx).apply {
            text = L(ctx, "در حال بررسی…", "Checking…")
            textSize = 15f
            setTextColor(Theme.fg(ctx))
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(dp(ctx, 12), 0, 0, 0)
        })
        content.addView(checkingRow)
        // One message line, reused by latest / update / error / done.
        val message = TextView(ctx).apply {
            textSize = 15f
            setTextColor(Theme.fg(ctx))
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            visibility = View.GONE
        }
        content.addView(message)
        // Downloading: bar + percent.
        val progressRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(ctx, 12), 0, 0)
        }
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val percentText = TextView(ctx).apply {
            textSize = 13f
            setTextColor(Theme.subFg(ctx))
            setPadding(dp(ctx, 8), 0, 0, 0)
        }
        progressRow.addView(bar)
        progressRow.addView(percentText)
        content.addView(progressRow)
        // Downloaded file row (tap to install).
        val fileRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(ctx, 12), 0, 0)
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        fileRow.addView(TextView(ctx).apply {
            text = L(ctx, "دانلود شد — برای نصب بزن", "Downloaded — tap to install")
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Theme.fg(ctx))
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
        })
        val fileNameText = TextView(ctx).apply {
            textSize = 12f
            setTextColor(Theme.subFg(ctx))
            textDirection = View.TEXT_DIRECTION_LOCALE
            gravity = Gravity.START
            setPadding(0, dp(ctx, 2), 0, 0)
        }
        fileRow.addView(fileNameText)
        content.addView(fileRow)
        // Buttons: [دانلود] / [نصب] on the left state, [بستن] always.
        val buttons = buttonRow(ctx)
        val primaryBtn = actionButton(ctx, L(ctx, "دانلود", "Download"), primary = true)
        val closeBtn = actionButton(ctx, L(ctx, "بستن", "Close"), primary = false)
        primaryBtn.visibility = View.GONE
        buttons.addView(primaryBtn)
        buttons.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 10), 1)
        })
        buttons.addView(closeBtn)
        content.addView(buttons)

        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(Theme.dialogCard(ctx))
        try {
            val lp = dialog.window?.attributes
            if (lp != null) {
                lp.width = dp(ctx, 340)
                dialog.window?.attributes = lp
            }
        } catch (_: Exception) {
        }
        // The "latest" message lives exactly 4 seconds, then the
        // dialog is gone. Closing early cancels the timer.
        val main = Handler(Looper.getMainLooper())
        val latestDismiss = Runnable {
            try {
                dialog.dismiss()
            } catch (_: Exception) {
            }
        }
        dialog.setOnDismissListener { main.removeCallbacks(latestDismiss) }
        closeBtn.setOnClickListener {
            main.removeCallbacks(latestDismiss)
            try {
                dialog.dismiss()
            } catch (_: Exception) {
            }
        }

        var release: UpdateCheckerPure.ReleaseInfo? = null
        var downloadedFile: File? = null
        fun showError() {
            checkingRow.visibility = View.GONE
            progressRow.visibility = View.GONE
            message.visibility = View.VISIBLE
            message.text = L(ctx, "بررسی بروزرسانی ناموفق بود.", "Update check failed.")
            primaryBtn.visibility = View.GONE
        }
        primaryBtn.setOnClickListener {
            val rel = release ?: return@setOnClickListener
            // After the download finishes the SAME button becomes
            // [نصب] for the downloaded file.
            if (downloadedFile != null) {
                tapInstall(ctx, downloadedFile!!)
                return@setOnClickListener
            }
            message.visibility = View.GONE
            primaryBtn.visibility = View.GONE
            progressRow.visibility = View.VISIBLE
            bar.isIndeterminate = true
            percentText.text = ""
            // 1.75: tell Settings the download started, so the
            // persistent box below the option appears at once
            // (the download outlives this dialog).
            try {
                onDownloadStateChanged?.invoke()
            } catch (_: Exception) {
            }
            UpdateChecker.downloadApk(ctx, rel,
                onProgress = { _, _, percent ->
                    try {
                        if (percent < 0) {
                            bar.isIndeterminate = true
                            percentText.text = ""
                        } else {
                            bar.isIndeterminate = false
                            bar.progress = percent
                            percentText.text = if (AppLanguage.isEnglish(ctx)) "$percent%"
                            else SpeedTestPanelPure.faDigits(percent.toString()) + "٪"
                        }
                    } catch (_: Exception) {
                    }
                },
                onDone = { file ->
                    // 1.75: the pending record is already READY /
                    // FAILED by now — tell Settings so its box
                    // flips to [نصب] (or the retry) even if this
                    // dialog was closed mid-download.
                    try {
                        onDownloadStateChanged?.invoke()
                    } catch (_: Exception) {
                    }
                    try {
                        if (!dialog.isShowing) return@downloadApk
                        if (file != null) {
                            downloadedFile = file
                            progressRow.visibility = View.GONE
                            fileNameText.text =
                                UpdateCheckerPure.sanitizeApkName(rel.apkName)
                            fileRow.visibility = View.VISIBLE
                            primaryBtn.text = L(ctx, "نصب", "Install")
                            primaryBtn.visibility = View.VISIBLE
                        } else {
                            showError()
                            message.text = L(
                                ctx,
                                "دانلود ناموفق بود؛ دوباره تلاش کن.",
                                "Download failed; try again."
                            )
                        }
                    } catch (_: Exception) {
                    }
                },
                origin = "settings")
        }
        fileRow.setOnClickListener {
            val f = downloadedFile ?: return@setOnClickListener
            tapInstall(ctx, f)
        }

        dialog.show()
        UpdateChecker.checkLatest(ctx) { result ->
            try {
                if (!dialog.isShowing) return@checkLatest
                checkingRow.visibility = View.GONE
                when (result) {
                    is UpdateChecker.CheckResult.Latest -> {
                        message.visibility = View.VISIBLE
                        message.text = L(
                            ctx,
                            "برنامه شما اخرین ورژن هست",
                            "Your app is the latest version."
                        )
                        // Exactly 4 seconds, then gone.
                        main.postDelayed(
                            latestDismiss,
                            UpdateCheckerPure.LATEST_MESSAGE_MS
                        )
                    }
                    is UpdateChecker.CheckResult.Update -> {
                        release = result.release
                        message.visibility = View.VISIBLE
                        message.text = L(
                            ctx,
                            "نسخه " + faVersion(ctx, result.release.tag) +
                                " منتشر شده برای دانلود کلیک کنید",
                            "Version " + result.release.displayVersion +
                                " is out — tap to download."
                        )
                        primaryBtn.text = L(ctx, "دانلود", "Download")
                        primaryBtn.visibility = View.VISIBLE
                    }
                    is UpdateChecker.CheckResult.Error -> showError()
                }
            } catch (_: Exception) {
            }
        }
    }
}
