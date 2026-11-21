package com.renamer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.view.animation.TranslateAnimation
import android.widget.*

/** Short loading page shown after a correct entry
 *  password, before the Home page. 2.18: white page,
 *  a pulsing glass orb, a big navy welcome line, a
 *  soft tagline, a spinner and, under it, three small
 *  dots bouncing in turn — no buttons and no lock
 *  wording. 2.94: background synced with the mode
 *  (pearl day / dark night aurora, no more white
 *  flash at night) and the orb replaced by the
 *  animated tunnel (pulse rings + rotating orbit +
 *  signal-waves icon). After about 3s it starts
 *  HomeActivity with the SAME mode extra and finishes
 *  itself, so Back never returns here. The page can
 *  be switched off from Personalization (pref
 *  "show_welcome"). */
class WelcomeActivity : Activity() {
    private fun L(fa: String, en: String): String = AppLanguage.t(this, fa, en)

    private val handler = Handler(Looper.getMainLooper())
    private var navigated = false

    private val goHome = Runnable {
        if (navigated) return@Runnable
        navigated = true
        val mode = intent.getStringExtra(PinActivity.EXTRA_MODE) ?: PinActivity.MODE_PUBLIC
        val home = Intent(this, HomeActivity::class.java)
        home.putExtra(PinActivity.EXTRA_MODE, mode)
        startActivity(home)
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First entry only: ask for the notification
        // permission, so the connection notification
        // (speed / country / disconnect-connect-restart)
        // can actually be shown. Asked once, never nagged.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val sp = getSharedPreferences("renamer_settings", MODE_PRIVATE)
            if (!sp.getBoolean("notif_perm_asked", false) &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                sp.edit().putBoolean("notif_perm_asked", true).apply()
                android.app.AlertDialog.Builder(this)
                    .setTitle(AppLanguage.t(this, "اعلان اتصال", "Connection notification"))
                    .setMessage(AppLanguage.t(this, "برای اینکه وقتی وصل می‌شوی، سرعت و کشور کانفیگ و دکمه‌های قطع/وصل/ری‌استارت در اعلان دیده شود، اجازه اعلان لازم است.", "To show speed, config country and disconnect/connect/restart buttons in a notification while connected, notification permission is needed."))
                    .setPositiveButton(AppLanguage.t(this, "اجازه بده", "Allow")) { _, _ ->
                        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
                    }
                    .setNegativeButton(AppLanguage.t(this, "فعلاً نه", "Not now"), null)
                    .show()
            }
        }
        // 2.94: window chrome + background follow the mode
        // (pearl day / dark night aurora) like every other
        // tap-opened page — no white strips, no white flash.
        Theme.applyPage(this)
        val dark = Theme.isDark(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Theme.pageBackground(this@WelcomeActivity)
            fitsSystemWindows = true
        }

        // 2.94: animated tunnel (WelcomeTunnelView) instead
        // of the breathing glass orb. 230dp so the soft
        // glow (115dp radius) is not clipped; the tunnel
        // art itself is 170dp, as in the approved mockup.
        val tunnel = WelcomeTunnelView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(230), dp(230))
        }
        root.addView(tunnel)

        val welcomeText = TextView(this).apply {
            text = L("خوش آمدید", "Welcome"); textSize = 38f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (dark) Color.WHITE else Color.parseColor("#1A237E"))
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(8))
        }
        root.addView(welcomeText)
        // Slide-in from the reading direction: Persian
        // enters from the right, English from the left,
        // fading in at the same time.
        val fromX = if (AppLanguage.isEnglish(this)) -1f else 1f
        val enter = AnimationSet(true).apply {
            addAnimation(TranslateAnimation(
                Animation.RELATIVE_TO_SELF, fromX,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f
            ).apply { duration = 700 })
            addAnimation(AlphaAnimation(0f, 1f).apply { duration = 700 })
        }
        welcomeText.startAnimation(enter)

        root.addView(TextView(this).apply {
            text = L("اتصال سریع، امن و بدون دردسر", "Fast, secure, effortless connection")
            textSize = 15f
            setTextColor(if (dark) Color.parseColor("#B0BEC5") else Color.parseColor("#455A64"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(32))
        })
        root.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0F766E"))
        })
        setContentView(root)
        // 2.18: about 3 seconds on screen.
        handler.postDelayed(goHome, 3000)
        // Small loading animation under the spinner:
        // three teal dots bouncing in turn.
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        }
        val dotColors = listOf("#0F766E", "#26A69A", "#80CBC4")
        for ((i, hex) in dotColors.withIndex()) {
            val dot = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor(hex))
                }
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                    setMargins(dp(6), 0, dp(6), 0)
                }
            }
            dot.startAnimation(ScaleAnimation(
                0.6f, 1f, 0.6f, 1f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 500
                startOffset = (i * 150).toLong()
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            })
            dots.addView(dot)
        }
        root.addView(dots)
    }

    override fun onDestroy() {
        handler.removeCallbacks(goHome)
        super.onDestroy()
    }
}
