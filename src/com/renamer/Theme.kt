package com.renamer

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView

/**
 * Shared day/night theme, copied from HomeActivity.isDarkMode
 * so every page uses the same decision (theme_mode, with
 * empty falling back to the old dark_mode boolean).
 */
object Theme {
    /** 1.73 (worker B): NIGHT ~30% DARKER — the one pure helper for
     *  the whole night darkening. Multiplies the RGB channels by
     *  [factor] (default 0.7), alpha untouched, so glass fills keep
     *  their translucency and nothing new goes pure black (black
     *  stays black: 0 * 0.7 = 0). The default factor is applied as
     *  exact integer maths (v*7+5)/10 — 0.7 rounded half-up — so the
     *  result is identical on every device and in the JVM tests,
     *  with no float error at .5 boundaries; a non-default factor
     *  falls back to Math.round. Pure int maths, no Android call,
     *  so the JVM tests prove it without a Context. */
    const val NIGHT_DARKEN_FACTOR = 0.7f
    fun darken(color: Int, factor: Float = NIGHT_DARKEN_FACTOR): Int {
        fun dim(v: Int): Int = if (factor == NIGHT_DARKEN_FACTOR) (v * 7 + 5) / 10
        else Math.round(v * factor).coerceIn(0, 255)
        val a = color ushr 24 and 0xFF
        return (a shl 24) or (dim((color ushr 16) and 0xFF) shl 16) or
            (dim((color ushr 8) and 0xFF) shl 8) or dim(color and 0xFF)
    }
    fun themeMode(ctx: Context): String {
        return ctx.getSharedPreferences("renamer_settings", Context.MODE_PRIVATE)
            .getString("theme_mode", "") ?: ""
    }

    /** 2.95: the Settings night-mode menu saves day / night /
     *  system. "day" = always light, "night" = always dark,
     *  "system" = follow the phone's UI mode (re-evaluated on
     *  every isDark call, so a phone that flips day/night
     *  flips the app too — no configChanges in the manifest,
     *  so the system recreates the activities on a flip).
     *  Legacy values keep working: "dark" = night, "light" =
     *  day, "auto" = system. The old clock-based "night"
     *  branch (18:00-06:00) had no writer anywhere, so the
     *  value now means always-dark, like "dark". */
    fun isDark(ctx: Context): Boolean {
        return when (themeMode(ctx)) {
            "dark", "night" -> true
            "light", "day" -> false
            "auto", "system" -> (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> ctx.getSharedPreferences("renamer_settings", Context.MODE_PRIVATE)
                .getBoolean("dark_mode", false)
        }
    }

    /** 2.95: the stored night-mode choice, normalised to the
     *  three menu values for the Settings row icon/value. */
    fun displayMode(ctx: Context): String {
        return when (themeMode(ctx)) {
            "day", "light" -> "day"
            "night", "dark" -> "night"
            "system", "auto" -> "system"
            else -> if (ctx.getSharedPreferences("renamer_settings", Context.MODE_PRIVATE)
                .getBoolean("dark_mode", false)) "night" else "day"
        }
    }

    fun bg(ctx: Context): Int =
        if (isDark(ctx)) darken(Color.parseColor("#121212")) else Color.WHITE

    fun fg(ctx: Context): Int =
        if (isDark(ctx)) Color.WHITE else Color.BLACK

    fun subFg(ctx: Context): Int =
        if (isDark(ctx)) Color.parseColor("#BBBBBB") else Color.parseColor("#555555")

    fun divider(ctx: Context): Int =
        if (isDark(ctx)) darken(Color.parseColor("#333333")) else Color.parseColor("#DDDDDD")

    fun card(ctx: Context): Int =
        if (isDark(ctx)) darken(Color.parseColor("#1E1E1E")) else Color.parseColor("#F5F5F5")

    // ---------- 2.84: ONE shared page background ----------
    // Every tap-opened page (Settings + its sub-pages, help /
    // guide / tutorial / log dialogs) uses the SAME background:
    // the home night aurora, slightly darker, in night mode;
    // the pearl day-home background in day mode. Never pure
    // black — light text must stay readable on it.
    fun pageBackground(ctx: Context): Drawable =
        if (isDark(ctx)) SharedNightDrawable() else AuroraPearlDrawable()

    /** Window chrome for a tap-opened page: the window behind
     *  the content (the strips behind the status bar and the
     *  navigation buttons) follows the same background, so no
     *  white strips remain at the top or bottom. Call in
     *  onCreate, before setContentView. */
    fun applyPage(activity: Activity) {
        try {
            val dark = isDark(activity)
            activity.window?.setBackgroundDrawable(pageBackground(activity))
            val edge = if (dark) darken(Color.parseColor("#0B0E1A")) else Color.parseColor("#F5F3FA")
            activity.window?.statusBarColor = edge
            activity.window?.navigationBarColor = edge
        } catch (_: Exception) {}
    }

    /** Rounded card for a dialog panel: dark glass in night,
     *  white in day. */
    fun dialogCard(ctx: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 24f * ctx.resources.displayMetrics.density
        if (isDark(ctx)) {
            setColor(darken(Color.parseColor("#232840")))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), darken(Color.parseColor("#44FFFFFF")))
        } else {
            setColor(Color.WHITE)
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Color.parseColor("#1A000000"))
        }
    }

    /** 2.90: TRUE glass card, matching the approved mockups —
     *  the aurora / pearl shows through in both modes. Night:
     *  white 8% fill + white 16% stroke. Day: white 78% fill
     *  + dark 8% stroke. Use for widget/login rows and cards
     *  that were solid white before. */
    fun glassCard(ctx: Context, radiusDp: Float = 18f): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * ctx.resources.displayMetrics.density
        if (isDark(ctx)) {
            setColor(darken(Color.parseColor("#14FFFFFF")))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), darken(Color.parseColor("#29FFFFFF")))
        } else {
            setColor(Color.parseColor("#C7FFFFFF"))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Color.parseColor("#14000000"))
        }
    }

    /** 2.90: dark-glass input box (Rename, Subs, Alien):
     *  night = white 10% fill + white 22% stroke, light text;
     *  day = white 92% fill + #DCDCDC stroke, dark text. */
    fun glassInput(ctx: Context, radiusDp: Float = 16f): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * ctx.resources.displayMetrics.density
        if (isDark(ctx)) {
            setColor(darken(Color.parseColor("#1AFFFFFF")))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), darken(Color.parseColor("#38FFFFFF")))
        } else {
            setColor(Color.parseColor("#EBFFFFFF"))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Color.parseColor("#FFDCDCDC"))
        }
    }

    /** 2.90: the low-alpha fill for gradientGlowRect primaries
     *  (Subs, sub-settings, Cloudflare, Rename): night = teal
     *  25% so the aurora shows through instead of a solid
     *  green pill; day = white 70%. Text stays white in night,
     *  #0F766E in day (callers already do this). */
    fun primaryFill(ctx: Context): Int =
        if (isDark(ctx)) darken(Color.parseColor("#400F766E")) else Color.parseColor("#B3FFFFFF")

    /** 2.90: the shared page background, 20% darker in night
     *  (Emergency only) — the connect button is untouched. */
    fun pageBackgroundDarker(ctx: Context): Drawable =
        if (isDark(ctx)) DarkenedDrawable(SharedNightDrawable(), 0.20f) else AuroraPearlDrawable()

    /** Dark-glass card for a page-level white card (a layout
     *  card, a log box): dark glass in night, the same white
     *  as before in day. */
    fun pageCard(ctx: Context, radiusDp: Float = 18f): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * ctx.resources.displayMetrics.density
        if (isDark(ctx)) {
            setColor(darken(Color.parseColor("#CC2A3048")))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), darken(Color.parseColor("#44FFFFFF")))
        } else {
            setColor(Color.WHITE)
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Color.parseColor("#FFE0E0E0"))
        }
    }

    /** Style an AlertDialog / Dialog AFTER show(): the panel
     *  becomes the shared dialog card, dark body text becomes
     *  light in night mode, and the framework buttons get a
     *  teal that reads on both the dark card and the Smart
     *  blink's light-green button background. Call AFTER
     *  show() — the buttons only exist then. Safe to call in
     *  day mode too (white card, dark text, teal buttons). */
    fun styleDialog(dialog: Dialog, ctx: Context) {
        try {
            val dark = isDark(ctx)
            dialog.window?.setBackgroundDrawable(dialogCard(ctx))
            val decor = dialog.window?.decorView as? ViewGroup ?: return
            fun luminance(c: Int): Double {
                fun f(v: Int): Double {
                    val s = v / 255.0
                    return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
                }
                return 0.2126 * f(Color.red(c)) + 0.7152 * f(Color.green(c)) + 0.0722 * f(Color.blue(c))
            }
            // Recolor near-black body text in night mode. The
            // framework action buttons are skipped here — they
            // get their own teal below — but CheckBox / Radio /
            // Switch labels ARE body text and are recolored.
            fun walk(v: View) {
                if (v is TextView) {
                    val isActionBtn = v is android.widget.Button && v !is android.widget.CompoundButton
                    if (!isActionBtn && dark && luminance(v.currentTextColor) < 0.25) {
                        v.setTextColor(Color.parseColor("#EDEFF5"))
                    }
                    // 2.85: EditText hints are grey by default — dim
                    // to the point of unreadable on the dark card.
                    // (The text itself is covered above: an EditText
                    // IS a TextView.)
                    if (v is EditText && dark) {
                        v.setHintTextColor(Color.parseColor("#9AA0B0"))
                    }
                }
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
            }
            walk(decor)
            // 2.87: re-run the walk after layout. The walk
            // above runs before a ListView creates its rows
            // (that is why the 2.86 per-app dialog stayed
            // dark-on-dark in night mode — the rows did not
            // exist yet). The custom adapters are the real
            // fix; this post is the safety net for any
            // framework row created during layout.
            decor.post { try { walk(decor) } catch (_: Exception) {} }
            val btnColor = if (dark) Color.parseColor("#26A69A") else Color.parseColor("#0F766E")
            val alert = dialog as? android.app.AlertDialog
            if (alert != null) {
                try { alert.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(btnColor) } catch (_: Exception) {}
                try { alert.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(btnColor) } catch (_: Exception) {}
                try { alert.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.setTextColor(btnColor) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** 2.92: the compact dialog size — the OpenVPN guide dialog is
     *  the reference: a compact centered card (340dp wide, 560dp
     *  tall) that never takes the whole screen, so the underlying
     *  page stays visible above and below it. Call AFTER show()
     *  for AlertDialogs (the window only exists then); for raw
     *  Dialogs call before or after show().
     *  2.93: this helper MUST NEVER touch the window background.
     *  The 2.92 version set it to TRANSPARENT, which wiped the
     *  dialogCard that styleDialog had just set — every compact
     *  dialog lost its box and its text overlapped the page
     *  behind. The background stays exactly as styleDialog left
     *  it: the opaque themed dialog card (dark glass in night,
     *  white glass in day). Only the size changes here. */
    fun applyCompactDialogSize(dialog: Dialog, ctx: Context) {
        try {
            val d = ctx.resources.displayMetrics.density
            val win = dialog.window ?: return
            // 2.93: the window background is NOT touched here —
            // styleDialog already set it to Theme.dialogCard, and
            // clearing it (as 2.92 did) removes the dialog's box.
            val lp = win.attributes
            lp.width = (340 * d).toInt()
            lp.height = (560 * d).toInt()
            win.attributes = lp
        } catch (_: Exception) {}
    }

    /** 2.92: a DRAWN state glyph (never emoji) for the Cloudflare
     *  step keys: a check when the step is done, a cross when it
     *  is not. Night: NEON — bright color with a soft glow
     *  (done = neon green, undone = neon red). Day: a beautiful
     *  flat green / normal red, no glow. */
    fun stateGlyphBitmap(ctx: Context, done: Boolean, sizeDp: Int = 20): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val s = (sizeDp * d).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val dark = isDark(ctx)
        val color = when {
            done && dark -> Color.parseColor("#69F0AE")
            done -> Color.parseColor("#2E7D32")
            dark -> Color.parseColor("#FF5252")
            else -> Color.parseColor("#D32F2F")
        }
        val stroke = s * 0.16f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            if (dark) setShadowLayer(s * 0.22f, 0f, 0f, color)
        }
        val pad = s * 0.24f
        if (done) {
            val p = Path().apply {
                moveTo(pad, s * 0.52f)
                lineTo(s * 0.44f, s * 0.74f)
                lineTo(s - pad, s * 0.28f)
            }
            c.drawPath(p, paint)
        } else {
            c.drawLine(pad, pad, s - pad, s - pad, paint)
            c.drawLine(s - pad, pad, pad, s - pad, paint)
        }
        return bmp
    }

    /** A Settings-row glyph in the new icon style: the same
     *  cyan→violet gradient as the 2.83 home icons, soft glow
     *  in night, flat in day. Drawn, so no new asset is needed
     *  for glyphs that have no section icon (⇅ ▦ ✎ ⓘ ☾). */
    fun gradientGlyphBitmap(ctx: Context, glyph: String, sizeDp: Int, night: Boolean): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val s = (sizeDp * d).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = s * 0.52f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            shader = LinearGradient(0f, 0f, s.toFloat(), s.toFloat(),
                intArrayOf(Color.parseColor("#22D3EE"), Color.parseColor("#8B5CF6")),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            if (night) setShadowLayer(3f * d, 0f, 0f, darken(Color.parseColor("#6622D3EE")))
        }
        val fm = paint.fontMetrics
        c.drawText(glyph, s / 2f, s / 2f - (fm.ascent + fm.descent) / 2f, paint)
        return bmp
    }

    /** 2.95: the night-mode row icon per choice — a sun for
     *  day, a moon for night, and a combined half-sun /
     *  half-moon for "system" (follow the phone): the sun
     *  glyph on the left half, the moon glyph on the right
     *  half, same cyan→violet gradient as the other rows. */
    fun modeGlyphBitmap(ctx: Context, mode: String, sizeDp: Int, night: Boolean): Bitmap {
        return when (mode) {
            "day" -> gradientGlyphBitmap(ctx, "☀", sizeDp, night)
            "system", "auto" -> {
                val d = ctx.resources.displayMetrics.density
                val s = (sizeDp * d).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = s * 0.44f
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                    shader = LinearGradient(0f, 0f, s.toFloat(), s.toFloat(),
                        intArrayOf(Color.parseColor("#22D3EE"), Color.parseColor("#8B5CF6")),
                        floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
                    if (night) setShadowLayer(3f * d, 0f, 0f, darken(Color.parseColor("#6622D3EE")))
                }
                val fm = paint.fontMetrics
                val cy = s / 2f - (fm.ascent + fm.descent) / 2f
                c.save()
                c.clipRect(0f, 0f, s / 2f, s.toFloat())
                c.drawText("☀", s * 0.28f, cy, paint)
                c.restore()
                c.save()
                c.clipRect(s / 2f, 0f, s.toFloat(), s.toFloat())
                c.drawText("☾", s * 0.72f, cy, paint)
                c.restore()
                bmp
            }
            else -> gradientGlyphBitmap(ctx, "☾", sizeDp, night)
        }
    }

    // ---------- 2.88: WG / OVPN themes ----------
    // Pill toggle bitmap (WG + OVPN rows): a rounded track with a round
    // knob and a small glass highlight. Colours are passed in so night
    // and day each match their mockup exactly — night ON is teal in
    // both sections; night OFF is dark grey (WG) / pale blue-grey
    // (OVPN); day OFF is light grey (WG) / cream (OVPN).
    fun pillToggleBitmap(
        ctx: Context, wDp: Int, hDp: Int, on: Boolean,
        offTrack: String, offKnob: String, onTrack: String, onKnob: String
    ): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt().coerceAtLeast(1)
        val h = (hDp * d).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(if (on) onTrack else offTrack)
            style = Paint.Style.FILL
        }
        val r = h / 2f
        c.drawRoundRect(android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, trackPaint)
        val knobR = h / 2f - 2.5f * d
        val knobCx = if (on) w - knobR - 2.5f * d else knobR + 2.5f * d
        val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(if (on) onKnob else offKnob)
            style = Paint.Style.FILL
        }
        c.drawCircle(knobCx, h / 2f, knobR, knobPaint)
        // Small glass highlight on the knob.
        val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 255, 255, 255)
            style = Paint.Style.FILL
        }
        c.drawCircle(knobCx - knobR * 0.3f, h / 2f - knobR * 0.35f, knobR * 0.45f, hiPaint)
        return bmp
    }

    /** Glass circle with a teal→purple gradient border and a soft glow
     *  halo (the OVPN + button, 2.88): the same layered-stroke glow
     *  technique as VpnActivity's GradientGlassDrawable, drawn as a
     *  circle. The border gradient is teal→purple in BOTH modes, per
     *  the mockup; only the fill differs. */
    fun gradientGlowCircle(ctx: Context, fill: Int, night: Boolean): Drawable =
        GradientGlowCircleDrawable(fill, night, ctx.resources.displayMetrics.density)

    /** Glass primary button (the subscription pages, 2.88): a glass
     *  fill with a teal→purple gradient border and a soft glow halo,
     *  like the big teal buttons on the Subscriptions page — but
     *  glass, not a flat solid fill. */
    fun gradientGlowRect(ctx: Context, fill: Int, night: Boolean, radiusDp: Float): Drawable =
        GradientGlowRectDrawable(fill, night, ctx.resources.displayMetrics.density, radiusDp)

    /** 2.91: the NEON BUTTON («دکمه نئونی») — the Rename section's
     *  «تغییر نام‌ها» button (gradientGlowRect + primaryFill) with
     *  three exact adjustments: (1) the inner fill 10% DARKER
     *  (RGB scaled by 0.9, alpha kept); (2) the neon gradient line
     *  around it kept but 10% THINNER (1.125dp instead of 1.25dp);
     *  (3) the extra outer line / glow halo around the neon line
     *  REMOVED. One style, used by every button the 2.91 order
     *  names neon. Text stays white in night, #0F766E in day —
     *  same as the primaryFill callers. */
    fun neonFill(ctx: Context): Int {
        val c = primaryFill(ctx)
        fun dim(v: Int): Int = (v * 0.9f).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(c), dim(Color.red(c)), dim(Color.green(c)), dim(Color.blue(c)))
    }

    fun neonTextColor(ctx: Context): Int =
        if (isDark(ctx)) Color.WHITE else Color.parseColor("#0F766E")

    /** Neon button background. greenBorder = true gives the Smart
     *  report button its soft-green neon border line; same fill,
     *  same thickness, no halo. */
    fun neonRect(ctx: Context, radiusDp: Float = 20f, greenBorder: Boolean = false): Drawable =
        NeonRectDrawable(neonFill(ctx), ctx.resources.displayMetrics.density, radiusDp, greenBorder)

    /** 2.88: the themed pill toggle shared by the WG rows and the
     *  subscription pages — teal ON in both modes, dark grey OFF in
     *  night, light grey OFF in day. */
    fun modeToggleBitmap(ctx: Context, on: Boolean): Bitmap {
        return if (isDark(ctx))
            pillToggleBitmap(ctx, 76, 37, on, "#292C35", "#4B505A", "#1B746C", "#B2DFDB")
        else
            pillToggleBitmap(ctx, 76, 37, on, "#E0E0E0", "#FFFFFF", "#26A69A", "#B2DFDB")
    }

    /** Line-style trash-can glyph (the OVPN delete circle, 2.88): a
     *  lid with a handle, a tapered body and two inner lines, drawn
     *  in code so it never depends on a font emoji. */
    fun trashGlyphBitmap(ctx: Context, colorHex: String, sizeDp: Int): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val s = (sizeDp * d).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val f = s / 96f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(colorHex)
            style = Paint.Style.STROKE
            strokeWidth = 6f * f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // Lid + handle.
        c.drawLine(26f * f, 30f * f, 70f * f, 30f * f, paint)
        c.drawLine(40f * f, 30f * f, 40f * f, 22f * f, paint)
        c.drawLine(56f * f, 30f * f, 56f * f, 22f * f, paint)
        c.drawLine(40f * f, 22f * f, 56f * f, 22f * f, paint)
        // Tapered body.
        val body = android.graphics.Path().apply {
            moveTo(32f * f, 38f * f)
            lineTo(64f * f, 38f * f)
            lineTo(60f * f, 82f * f)
            lineTo(36f * f, 82f * f)
            close()
        }
        c.drawPath(body, paint)
        // Inner lines.
        c.drawLine(44f * f, 48f * f, 44f * f, 72f * f, paint)
        c.drawLine(52f * f, 48f * f, 52f * f, 72f * f, paint)
        return bmp
    }
}

/** 2.88: glass rounded rect with a teal→purple gradient border + glow halo. */
internal class GradientGlowRectDrawable(
    private val fill: Int,
    private val night: Boolean,
    private val d: Float,
    private val radiusDp: Float
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fill; style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private fun borderShader(b: Rect): Shader {
        return LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(Color.parseColor("#2DD4BF"), Color.parseColor("#A78BFA")),
            null, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val r = radiusDp * d
        val stroke = 1.25f * d
        val pad = stroke * 3f
        val fillRect = android.graphics.RectF(
            b.left + pad, b.top + pad, b.right - pad, b.bottom - pad)
        canvas.drawRoundRect(fillRect, maxOf(0f, r - pad), maxOf(0f, r - pad), fillPaint)
        // Glow halo: two wide translucent strokes.
        strokePaint.shader = borderShader(b)
        val mid = android.graphics.RectF(
            b.left + pad / 2f, b.top + pad / 2f,
            b.right - pad / 2f, b.bottom - pad / 2f)
        strokePaint.alpha = (255 * 0.30f).toInt()
        strokePaint.strokeWidth = stroke * 3f
        canvas.drawRoundRect(mid, maxOf(0f, r - pad / 2f), maxOf(0f, r - pad / 2f), strokePaint)
        strokePaint.alpha = (255 * 0.55f).toInt()
        strokePaint.strokeWidth = stroke * 1.5f
        val inner = android.graphics.RectF(
            fillRect.left + stroke / 2f, fillRect.top + stroke / 2f,
            fillRect.right - stroke / 2f, fillRect.bottom - stroke / 2f)
        canvas.drawRoundRect(inner, maxOf(0f, r - pad - stroke / 2f), maxOf(0f, r - pad - stroke / 2f), strokePaint)
        // Crisp border.
        strokePaint.alpha = 255
        strokePaint.strokeWidth = stroke
        canvas.drawRoundRect(inner, maxOf(0f, r - pad - stroke / 2f), maxOf(0f, r - pad - stroke / 2f), strokePaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** 2.91: the NEON BUTTON drawable — the Rename button minus the
 *  halo: a 10%-darker fill (passed in, see Theme.neonFill) with a
 *  single crisp gradient border, 10% thinner than the old glow
 *  rect (1.125dp), and NO outer halo line. Border gradient is
 *  teal→purple, or soft green when greenBorder is set (the Smart
 *  report button). */
internal class NeonRectDrawable(
    private val fill: Int,
    private val d: Float,
    private val radiusDp: Float,
    private val greenBorder: Boolean = false
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fill; style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private fun borderShader(b: Rect): Shader {
        val colors = if (greenBorder)
            intArrayOf(Color.parseColor("#66BB6A"), Color.parseColor("#A5D6A7"))
        else
            intArrayOf(Color.parseColor("#2DD4BF"), Color.parseColor("#A78BFA"))
        return LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            colors, null, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val r = radiusDp * d
        // 2.91: neon line 10% thinner than the old 1.25dp glow stroke.
        val stroke = 1.125f * d
        val fillRect = android.graphics.RectF(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        canvas.drawRoundRect(fillRect, r, r, fillPaint)
        // Single crisp neon border — no halo strokes around it.
        strokePaint.shader = borderShader(b)
        strokePaint.alpha = 255
        strokePaint.strokeWidth = stroke
        val inner = android.graphics.RectF(
            fillRect.left + stroke / 2f, fillRect.top + stroke / 2f,
            fillRect.right - stroke / 2f, fillRect.bottom - stroke / 2f)
        canvas.drawRoundRect(inner, maxOf(0f, r - stroke / 2f), maxOf(0f, r - stroke / 2f), strokePaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** 2.88: glass circle with a teal→purple gradient border + glow halo. */
internal class GradientGlowCircleDrawable(
    private val fill: Int,
    private val night: Boolean,
    private val d: Float
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fill; style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private fun borderShader(b: Rect): Shader {
        return LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(Color.parseColor("#2DD4BF"), Color.parseColor("#A78BFA")),
            null, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        val r = minOf(b.width(), b.height()) / 2f
        val stroke = 1.25f * d
        // Glass fill, inset so the halo fits between fill and edge.
        canvas.drawCircle(cx, cy, r - stroke * 3f, fillPaint)
        // Glow halo: two wide translucent strokes.
        strokePaint.shader = borderShader(b)
        strokePaint.alpha = (255 * 0.30f).toInt()
        strokePaint.strokeWidth = stroke * 3f
        canvas.drawCircle(cx, cy, r - stroke * 1.5f, strokePaint)
        strokePaint.alpha = (255 * 0.55f).toInt()
        strokePaint.strokeWidth = stroke * 1.5f
        canvas.drawCircle(cx, cy, r - stroke * 0.75f, strokePaint)
        // Crisp border.
        strokePaint.alpha = 255
        strokePaint.strokeWidth = stroke
        canvas.drawCircle(cx, cy, r - stroke / 2f, strokePaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** The home night aurora, slightly DARKER (2.84): the shared
 *  background of every tap-opened page in night mode. Deeper
 *  navy base, dimmer washes, a few more stars — never pure
 *  black, so light text stays readable on it. */
internal class SharedNightDrawable : Drawable() {
    private val basePaint = Paint().apply {
        color = Theme.darken(Color.parseColor("#070912"))
    }
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 150
    }
    private val sparkles = arrayOf(
        floatArrayOf(0.10f, 0.06f, 2.0f), floatArrayOf(0.86f, 0.04f, 1.6f),
        floatArrayOf(0.70f, 0.22f, 1.4f), floatArrayOf(0.30f, 0.30f, 1.6f),
        floatArrayOf(0.95f, 0.42f, 1.8f), floatArrayOf(0.06f, 0.62f, 1.5f),
        floatArrayOf(0.55f, 0.70f, 1.6f), floatArrayOf(0.90f, 0.78f, 2.0f),
        floatArrayOf(0.22f, 0.86f, 1.4f), floatArrayOf(0.45f, 0.12f, 1.3f),
        floatArrayOf(0.62f, 0.52f, 1.2f), floatArrayOf(0.38f, 0.58f, 1.3f)
    )

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        canvas.drawRect(b, basePaint)
        drawBlob(canvas, b, 0.12f, 0.08f, 0.55f, "#455729A6")
        drawBlob(canvas, b, 0.92f, 0.05f, 0.48f, "#450E8174")
        drawBlob(canvas, b, 0.04f, 0.45f, 0.50f, "#45143797")
        drawBlob(canvas, b, 0.96f, 0.55f, 0.50f, "#45A5326B")
        drawBlob(canvas, b, 0.28f, 0.94f, 0.55f, "#454C1C98")
        drawBlob(canvas, b, 0.82f, 0.96f, 0.50f, "#4509685F")
        for (s in sparkles) {
            canvas.drawCircle(b.left + s[0] * b.width(), b.top + s[1] * b.height(), s[2], sparklePaint)
        }
    }

    private fun drawBlob(
        canvas: Canvas,
        b: Rect,
        cx: Float, cy: Float, rFrac: Float, colorHex: String
    ) {
        val cxPx = b.left + cx * b.width()
        val cyPx = b.top + cy * b.height()
        val r = rFrac * maxOf(b.width(), b.height())
        blobPaint.shader = RadialGradient(
            cxPx, cyPx, r,
            intArrayOf(Color.parseColor(colorHex), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cxPx, cyPx, r, blobPaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}

/** 2.90: draws another drawable, then darkens it by the given
 *  fraction with a black overlay — exactly 20% darker for the
 *  Emergency night background, button untouched. */
internal class DarkenedDrawable(
    private val base: Drawable,
    private val fraction: Float
) : Drawable() {
    private val shadePaint = Paint().apply {
        color = Color.BLACK
        alpha = (255 * fraction).toInt()
    }

    override fun draw(canvas: Canvas) {
        base.bounds = bounds
        base.draw(canvas)
        canvas.drawRect(bounds, shadePaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
