package com.renamer

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView

/**
 * Minimal theme for the standalone Openvpn app (mockup v4).
 * Day = pearl aurora (#EAF6F8 -> #F6F1E7, teal #0F766E accent);
 * night = #070912 aurora (teal #2DD4BF + violet #A78BFA accents).
 * Theme mode is a 3-state cycle on the header circle:
 * day -> night -> auto (follows the phone), persisted.
 * Only the helpers the OVPN pages need live here — nothing
 * from the main app's shared theme classes is referenced.
 */
object OvpnTheme {
    private const val PREFS = "renamer_settings"
    private const val KEY_MODE = "ovpn_theme_mode"

    fun mode(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, "auto") ?: "auto"

    fun cycleMode(ctx: Context): String {
        val next = when (mode(ctx)) {
            "day" -> "night"
            "night" -> "auto"
            else -> "day"
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, next).apply()
        return next
    }

    fun modeGlyph(ctx: Context): String = when (mode(ctx)) {
        "day" -> "☀️"
        "night" -> "🌙"
        else -> "◐"
    }

    fun modeTitle(ctx: Context): String = when (mode(ctx)) {
        "day" -> AppLanguage.t(ctx, "روز", "Day")
        "night" -> AppLanguage.t(ctx, "شب", "Night")
        else -> AppLanguage.t(ctx, "خودکار — با گوشی هماهنگ می‌شود", "Auto — follows the phone")
    }

    fun isDark(ctx: Context): Boolean = when (mode(ctx)) {
        "night" -> true
        "day" -> false
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun fg(ctx: Context): Int =
        if (isDark(ctx)) Color.parseColor("#ECEFF1") else Color.parseColor("#1A237E")

    fun subFg(ctx: Context): Int =
        if (isDark(ctx)) Color.parseColor("#90A4AE") else Color.parseColor("#546E7A")

    fun accent(ctx: Context): Int =
        if (isDark(ctx)) Color.parseColor("#2DD4BF") else Color.parseColor("#0F766E")

    fun accent2(ctx: Context): Int =
        if (isDark(ctx)) Color.parseColor("#A78BFA") else Color.parseColor("#7C6CFF")

    /** Glass card (mockup .card / .country-panel): white .88 in day,
     *  rgba(18,26,52,.82) + teal border in night. */
    fun glassCard(ctx: Context, radiusDp: Float = 18f): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * ctx.resources.displayMetrics.density
        if (isDark(ctx)) {
            setColor(Color.parseColor("#D2121A34"))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Color.parseColor("#592DD4BF"))
        } else {
            setColor(Color.parseColor("#E0FFFFFF"))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Color.parseColor("#F2FFFFFF"))
        }
    }

    /** Rounded card for a dialog panel: dark glass in night, white in day. */
    fun dialogCard(ctx: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 24f * ctx.resources.displayMetrics.density
        if (isDark(ctx)) {
            setColor(Color.parseColor("#232840"))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Color.parseColor("#44FFFFFF"))
        } else {
            setColor(Color.WHITE)
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Color.parseColor("#1A000000"))
        }
    }

    /** Active tab: teal -> violet gradient (mockup .tab.on). */
    fun tabBackground(ctx: Context, active: Boolean): Drawable {
        val d = ctx.resources.displayMetrics.density
        return if (active) {
            GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(accent(ctx), accent2(ctx))).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 15f * d
            }
        } else glassCard(ctx, 15f)
    }

    /** Small glass circle (header buttons, 34dp): chip fill + border. */
    fun glassCircle(ctx: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (isDark(ctx)) {
            setColor(Color.parseColor("#14FFFFFF"))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Color.parseColor("#6B2DD4BF"))
        } else {
            setColor(Color.parseColor("#E0FFFFFF"))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Color.parseColor("#4D0F766E"))
        }
    }

    /** Glass pill (status pill, country chip). */
    fun glassPill(ctx: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 20f * ctx.resources.displayMetrics.density
        if (isDark(ctx)) {
            setColor(Color.parseColor("#14FFFFFF"))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Color.parseColor("#6B2DD4BF"))
        } else {
            setColor(Color.parseColor("#E0FFFFFF"))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Color.parseColor("#4D0F766E"))
        }
    }

    /** v1.1: the + FAB matches the app — the same
     *  teal -> violet gradient as the active tab,
     *  NOT gold (v1.0 defect: gold clashed). */
    fun fabBackground(ctx: Context): Drawable {
        val d = ctx.resources.displayMetrics.density
        return GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(accent(ctx), accent2(ctx))).apply {
            shape = GradientDrawable.OVAL
            cornerRadius = 29f * d
        }
    }

    fun fabTextColor(ctx: Context): Int = Color.WHITE

    /** Page background (mockup .phone.day / .phone.night):
     *  day = pearl linear gradient + warm top-right + cool
     *  bottom-left tints; night = #070912 + teal/violet
     *  aurora tints + faint stars. Never pure black. */
    fun pageBackground(ctx: Context): Drawable = OvpnPageDrawable(isDark(ctx))

    /** Window chrome for the page: the window behind the
     *  content follows the same background, so no white
     *  strips remain at the top or bottom. Call in onCreate,
     *  before setContentView. */
    fun applyPage(activity: Activity) {
        try {
            val dark = isDark(activity)
            activity.window?.setBackgroundDrawable(pageBackground(activity))
            val edge = if (dark) Color.parseColor("#070912") else Color.parseColor("#EAF6F8")
            activity.window?.statusBarColor = edge
            activity.window?.navigationBarColor = edge
        } catch (_: Exception) {}
    }

    /** Dialog styling: opaque themed card + night-readable body
     *  text + teal action buttons. The window background is set
     *  here; applyCompactDialogSize only ever changes the size. */
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
            fun walk(v: View) {
                if (v is TextView) {
                    val isActionBtn = v is android.widget.Button && v !is android.widget.CompoundButton
                    if (!isActionBtn && dark && luminance(v.currentTextColor) < 0.25) {
                        v.setTextColor(Color.parseColor("#EDEFF5"))
                    }
                    if (v is EditText && dark) {
                        v.setHintTextColor(Color.parseColor("#9AA0B0"))
                    }
                }
                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
            }
            walk(decor)
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

    /** Compact dialog size (guide / log dialogs): 340dp wide,
     *  560dp tall, centred. NEVER touches the window background. */
    fun applyCompactDialogSize(dialog: Dialog, ctx: Context) {
        try {
            val d = ctx.resources.displayMetrics.density
            val win = dialog.window ?: return
            val lp = win.attributes
            lp.width = (340 * d).toInt()
            lp.height = (560 * d).toInt()
            win.attributes = lp
        } catch (_: Exception) {}
    }

    /** Pill toggle bitmap (profile rows): a rounded track with a
     *  round knob and a small glass highlight. Colours are passed
     *  in so night and day each match the mockup. */
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
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, trackPaint)
        val knobR = h / 2f - 2.5f * d
        val knobCx = if (on) w - knobR - 2.5f * d else knobR + 2.5f * d
        val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(if (on) onKnob else offKnob)
            style = Paint.Style.FILL
        }
        c.drawCircle(knobCx, h / 2f, knobR, knobPaint)
        val hiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 255, 255, 255)
            style = Paint.Style.FILL
        }
        c.drawCircle(knobCx - knobR * 0.3f, h / 2f - knobR * 0.35f, knobR * 0.45f, hiPaint)
        return bmp
    }

    /** Line-style trash-can glyph (the delete circle): a lid with
     *  a handle, a tapered body and two inner lines, drawn in code
     *  so it never depends on a font emoji. */
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
        c.drawLine(26f * f, 30f * f, 70f * f, 30f * f, paint)
        c.drawLine(40f * f, 30f * f, 40f * f, 22f * f, paint)
        c.drawLine(56f * f, 30f * f, 56f * f, 22f * f, paint)
        c.drawLine(40f * f, 22f * f, 56f * f, 22f * f, paint)
        val body = Path().apply {
            moveTo(32f * f, 38f * f)
            lineTo(64f * f, 38f * f)
            lineTo(60f * f, 82f * f)
            lineTo(36f * f, 82f * f)
            close()
        }
        c.drawPath(body, paint)
        c.drawLine(44f * f, 48f * f, 44f * f, 72f * f, paint)
        c.drawLine(52f * f, 48f * f, 52f * f, 72f * f, paint)
        return bmp
    }

    /** Simple glass rect (name popup). */
    fun glassBg(fill: Int, stroke: Int, radiusDp: Float, ctx: Context): GradientDrawable {
        val d = (ctx.resources.displayMetrics.density * radiusDp).toInt()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = d.toFloat()
            setColor(fill)
            setStroke(2, stroke)
        }
    }

    /** The connect orb (mockup .orb): glass radial gradient +
     *  top gloss + sparkles + thin white rim. Green halo when
     *  connected (mockup .orb.connected). */
    /** v1.1: tint is the free-tab state colour
     *  (light blue / light red / light green) — the
     *  SAME glass texture, only the tint changes. */
    fun orbBackground(ctx: Context, connected: Boolean, tint: Int? = null): Drawable =
        OvpnOrbDrawable(isDark(ctx), connected, tint)

    /** Soft radial glow behind the orb (mockup .orbglow). */
    fun glowDrawable(ctx: Context, connected: Boolean, tint: Int? = null): Drawable =
        OvpnGlowDrawable(tint ?: if (connected) Color.parseColor("#2E9E6B")
            else if (isDark(ctx)) Color.parseColor("#2DD4BF") else Color.parseColor("#2DD4BF"))

    /** Thin rotating arc ring (mockup .orbring::before): a
     *  conic-gradient arc — transparent 0-248deg, accent
     *  248-318deg — on a faint full ring. Rotate the view
     *  14s/rev. */
    fun ringArcDrawable(ctx: Context): Drawable =
        OvpnRingArcDrawable(accent(ctx), if (isDark(ctx)) Color.parseColor("#522DD4BF") else Color.parseColor("#470F766E"))

    /** v1.1: the rotating arc in the free-tab state
     *  colour, so the ring/glow follow the orb tint. */
    fun ringArcDrawableTinted(ctx: Context, color: Int): Drawable =
        OvpnRingArcDrawable(color, if (isDark(ctx)) Color.parseColor("#522DD4BF") else Color.parseColor("#470F766E"))

    /** Slow dashed ring (mockup .orbring::after). Rotate the
     *  view -40s/rev. */
    fun dashedRingDrawable(ctx: Context): Drawable =
        OvpnDashedRingDrawable(if (isDark(ctx)) Color.parseColor("#522DD4BF") else Color.parseColor("#470F766E"))
}

/** Renders once into a bitmap per bounds size, then draws it. */
internal abstract class OvpnBitmapCachedDrawable : Drawable() {
    private var bmp: Bitmap? = null
    private var bw = 0
    private var bh = 0
    protected abstract fun render(c: Canvas, w: Int, h: Int)
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        if (bmp == null || bw != b.width() || bh != b.height()) {
            bmp?.recycle()
            bw = b.width(); bh = b.height()
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            render(Canvas(bmp!!), bw, bh)
        }
        canvas.drawBitmap(bmp!!, b.left.toFloat(), b.top.toFloat(), null)
    }
    override fun setAlpha(a: Int) {}
    override fun setColorFilter(f: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Page background: day pearl aurora / night #070912 aurora + stars. */
internal class OvpnPageDrawable(private val dark: Boolean) : OvpnBitmapCachedDrawable() {
    override fun render(c: Canvas, w: Int, h: Int) {
        if (dark) {
            c.drawColor(Color.parseColor("#070912"))
            // Teal tint, top right.
            val teal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(w * 0.85f, 0f, w * 0.9f,
                    intArrayOf(Color.argb(61, 45, 212, 191), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), teal)
            // Violet tint, bottom left.
            val violet = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(w * 0.10f, h.toFloat(), w * 0.8f,
                    intArrayOf(Color.argb(56, 167, 139, 250), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), violet)
            // Faint stars (fixed seed, designed not noisy).
            val rnd = java.util.Random(11)
            val star = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(128, 255, 255, 255)
            }
            for (i in 0 until 26) {
                val x = rnd.nextFloat() * w
                val y = rnd.nextFloat() * h
                val r = 1f + rnd.nextFloat() * 1.2f
                c.drawCircle(x, y, r, star)
            }
        } else {
            val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, w * 0.4f, h.toFloat(),
                    intArrayOf(Color.parseColor("#EAF6F8"), Color.parseColor("#F6F1E7")),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), base)
            // Warm tint, top right.
            val warm = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(w * 0.85f, 0f, w * 1.2f,
                    intArrayOf(Color.parseColor("#FDF6EC"), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.6f), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), warm)
            // Cool tint, bottom left.
            val cool = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(w * 0.05f, h.toFloat(), w.toFloat(),
                    intArrayOf(Color.parseColor("#CFEBF3"), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.55f), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), cool)
        }
    }
}

/** The glass connect orb. tint (v1.1) recolours the
 *  glass in a light state colour without changing
 *  the texture: no account = light blue, account
 *  but not connected = light red, connected =
 *  light green. */
internal class OvpnOrbDrawable(private val dark: Boolean, private val connected: Boolean, private val tint: Int? = null) : OvpnBitmapCachedDrawable() {
    private fun shadeOf(color: Int, f: Float): Int {
        return Color.argb(Color.alpha(color),
            (Color.red(color) * f).toInt().coerceIn(0, 255),
            (Color.green(color) * f).toInt().coerceIn(0, 255),
            (Color.blue(color) * f).toInt().coerceIn(0, 255))
    }
    override fun render(c: Canvas, w: Int, h: Int) {
        val cx = w / 2f; val cy = h / 2f
        val r = minOf(w, h) / 2f
        if (r <= 0) return
        val stops: IntArray
        val pos: FloatArray
        if (tint != null) {
            stops = if (dark) intArrayOf(
                Color.argb(140, 190, 245, 255),
                Color.argb(56, Color.red(tint), Color.green(tint), Color.blue(tint)),
                shadeOf(tint, 0.45f),
                Color.argb(199, 10, 16, 40))
            else intArrayOf(
                Color.argb(242, 255, 255, 255),
                Color.argb(107, 255, 255, 255),
                Color.argb(107, Color.red(tint), Color.green(tint), Color.blue(tint)),
                Color.argb(133, Color.red(shadeOf(tint, 0.55f)), Color.green(shadeOf(tint, 0.55f)), Color.blue(shadeOf(tint, 0.55f))))
            pos = floatArrayOf(0f, 0.22f, 0.58f, 1f)
        } else if (dark) {
            stops = intArrayOf(
                Color.argb(140, 190, 245, 255),
                Color.argb(56, 45, 212, 191),
                Color.argb(140, 30, 60, 120),
                Color.argb(199, 10, 16, 40))
            pos = floatArrayOf(0f, 0.30f, 0.70f, 1f)
        } else {
            stops = intArrayOf(
                Color.argb(242, 255, 255, 255),
                Color.argb(107, 255, 255, 255),
                Color.argb(107, 140, 190, 240),
                Color.argb(133, 70, 120, 190))
            pos = floatArrayOf(0f, 0.22f, 0.58f, 1f)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx - r * 0.36f, cy - r * 0.48f, r * 1.15f, stops, pos, Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, cy, r, fill)
        // Top gloss ellipse.
        val gloss = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy - r * 0.62f, r * 0.55f,
                intArrayOf(Color.argb(140, 255, 255, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, cy - r * 0.62f, r * 0.55f, gloss)
        // Bottom inner shade.
        val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val shadeColor = tint?.let { Color.argb(71, Color.red(it), Color.green(it), Color.blue(it)) }
                ?: if (connected) Color.argb(71, 46, 158, 107) else Color.argb(41, 70, 120, 190)
            shader = RadialGradient(cx, cy + r * 0.75f, r * 0.8f,
                intArrayOf(shadeColor, Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, cy + r * 0.75f, r * 0.8f, shade)
        // Sparkles (fixed positions, like the mockup).
        val spark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(if (dark) 160 else 200, 255, 255, 255)
        }
        c.drawCircle(cx - r * 0.56f, cy - r * 0.72f, 2.5f, spark)
        c.drawCircle(cx + r * 0.52f, cy - r * 0.60f, 2f, spark)
        c.drawCircle(cx + r * 0.64f, cy + r * 0.40f, 2.5f, spark)
        c.drawCircle(cx - r * 0.44f, cy + r * 0.68f, 2f, spark)
        // Thin white rim.
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        c.drawCircle(cx, cy, r - 1f, rim)
        if (connected) {
            // Green halo ring just outside the orb.
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(56, 46, 158, 107)
                style = Paint.Style.STROKE
                strokeWidth = 10f
            }
            c.drawCircle(cx, cy, r + 5f, halo)
        }
    }
}

/** Soft radial glow. */
internal class OvpnGlowDrawable(private val color: Int) : OvpnBitmapCachedDrawable() {
    override fun render(c: Canvas, w: Int, h: Int) {
        val cx = w / 2f; val cy = h / 2f
        val r = minOf(w, h) / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, r,
                intArrayOf(Color.argb(71, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT),
                floatArrayOf(0f, 0.68f), Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, cy, r, p)
    }
}

/** Thin arc ring: faint full ring + a bright accent arc
 *  (248-318deg of the sweep), like the mockup's conic ring. */
internal class OvpnRingArcDrawable(private val accent: Int, private val faint: Int) : OvpnBitmapCachedDrawable() {
    override fun render(c: Canvas, w: Int, h: Int) {
        val cx = w / 2f; val cy = h / 2f
        val r = minOf(w, h) / 2f - 3f
        if (r <= 0) return
        val faintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = faint
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        c.drawCircle(cx, cy, r, faintPaint)
        val sweep = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = SweepGradient(cx, cy,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, accent, Color.TRANSPARENT),
                floatArrayOf(0f, 0.688f, 0.883f, 1f))
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
        }
        c.drawCircle(cx, cy, r, sweep)
    }
}

/** Dashed circle ring. */
internal class OvpnDashedRingDrawable(private val color: Int) : OvpnBitmapCachedDrawable() {
    override fun render(c: Canvas, w: Int, h: Int) {
        val cx = w / 2f; val cy = h / 2f
        val r = minOf(w, h) / 2f - 2f
        if (r <= 0) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(7f, 7f), 0f)
        }
        c.drawCircle(cx, cy, r, p)
    }
}
