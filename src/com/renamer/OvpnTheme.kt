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
 * Minimal theme for the standalone Openvpn app.
 * v1.13 Midnight Sapphire (approved mockup v4r2):
 * day = pale ice blue (#EDF2F8 -> #E6ECF4 -> #EFF3F8,
 * sapphire #2563EB accent); night = deep navy #0A0F1E
 * with subtle blue/teal tints (the vivid #070912 aurora
 * + teal/violet of v1.12 is gone).
 * Theme mode is a 3-state cycle on the header circle:
 * day -> night -> auto (follows the phone), persisted.
 * Only the helpers the OVPN pages need live here — nothing
 * from the main app's shared theme classes is referenced.
 */
object OvpnTheme {
    private const val PREFS = "renamer_settings"
    private const val KEY_MODE = "ovpn_theme_mode"

    /** 1.31: NO separate OVPN theme any more — the section
     *  follows the app's GLOBAL mode (Theme.themeMode):
     *  the app's night mode used to leave this section
     *  in day mode, because this pref ("ovpn_theme_mode",
     *  default "auto" = follow the PHONE) never saw the
     *  app's own night choice. mode() now mirrors the
     *  global display mode ("day" / "night" / "auto" for
     *  the phone-following "system"); the old pref is
     *  never read again. */
    fun mode(ctx: Context): String = when (Theme.displayMode(ctx)) {
        "day" -> "day"
        "night" -> "night"
        else -> "auto"
    }

    /** 1.31: cycles the GLOBAL app mode (day -> night ->
     *  auto/system -> day) so a tap can never disagree
     *  with the rest of the app again. */
    fun cycleMode(ctx: Context): String {
        val next = when (mode(ctx)) {
            "day" -> "night"
            "night" -> "auto"
            else -> "day"
        }
        setMode(ctx, next)
        return next
    }

    /** 1.31: writes the GLOBAL app mode ("auto" =
     *  the global "system", follow the phone). */
    fun setMode(ctx: Context, m: String) {
        val v = when (m) { "day" -> "day"; "night" -> "night"; else -> "system" }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("theme_mode", v).apply()
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

    /** 1.31: the section is dark exactly when the APP is
     *  dark (Theme.isDark reads the global theme_mode). */
    fun isDark(ctx: Context): Boolean = Theme.isDark(ctx)

    fun fg(ctx: Context): Int =
        if (isDark(ctx)) Color.parseColor("#E6EBF5") else Color.parseColor("#1A2436")

    fun subFg(ctx: Context): Int =
        if (isDark(ctx)) Color.parseColor("#8A94A8") else Color.parseColor("#43536E")

    /** v1.13: sapphire blue accent (was teal/violet). */
    fun accent(ctx: Context): Int =
        if (isDark(ctx)) Color.parseColor("#60A5FA") else Color.parseColor("#2563EB")

    fun accent2(ctx: Context): Int =
        if (isDark(ctx)) Color.parseColor("#93C5FD") else Color.parseColor("#3B82F6")

    /** v1.13: the orb's own text colours (mockup v4r2) —
     *  the big label inside the circle + the chip. */
    fun orbText(ctx: Context): Int =
        if (isDark(ctx)) Color.parseColor("#93C5FD") else Color.parseColor("#1E40AF")

    fun orbSubText(ctx: Context): Int =
        if (isDark(ctx)) Color.parseColor("#BFDBFE") else Color.parseColor("#4A6390")

    /** Glass card (mockup .card / .country-panel): white .88 in day,
     *  rgba(18,26,52,.82) + teal border in night. */
    fun glassCard(ctx: Context, radiusDp: Float = 18f): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * ctx.resources.displayMetrics.density
        if (isDark(ctx)) {
            setColor(Theme.darken(Color.parseColor("#D2121A34")))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Theme.darken(Color.parseColor("#5960A5FA")))
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
            setColor(Theme.darken(Color.parseColor("#232840")))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Theme.darken(Color.parseColor("#44FFFFFF")))
        } else {
            setColor(Color.WHITE)
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Color.parseColor("#1A000000"))
        }
    }

    /** v1.13: active tab = sapphire blue gradient (mockup
     *  v4r2 — was teal -> violet). */
    fun tabBackground(ctx: Context, active: Boolean): Drawable {
        val d = ctx.resources.displayMetrics.density
        return if (active) {
            GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(if (isDark(ctx)) Theme.darken(Color.parseColor("#1E3A8A")) else Color.parseColor("#1E40AF"),
                    if (isDark(ctx)) Theme.darken(Color.parseColor("#1D4ED8")) else Color.parseColor("#3B82F6"))).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 15f * d
            }
        } else glassCard(ctx, 15f)
    }

    /** v1.13: the tab pill itself (outer glass, fully round)
     *  and one segment inside it — active = sapphire pill,
     *  inactive = transparent (the pill glass shows). */
    fun tabPillBackground(ctx: Context): Drawable = glassCard(ctx, 999f)

    fun tabSegmentBackground(ctx: Context, active: Boolean): Drawable {
        val d = ctx.resources.displayMetrics.density
        return if (active) {
            GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(if (isDark(ctx)) Theme.darken(Color.parseColor("#1E3A8A")) else Color.parseColor("#1E40AF"),
                    if (isDark(ctx)) Theme.darken(Color.parseColor("#1D4ED8")) else Color.parseColor("#3B82F6"))).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * d
            }
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 999f * d
                setColor(Color.TRANSPARENT)
            }
        }
    }

    /** v1.13: the «راهنمای اتصال» button — the SAME colour
     *  as the orb (sapphire radial, like the orb's glass),
     *  not green anymore (v4r2). Text colour = orbText. */
    fun guideButtonBackground(ctx: Context): Drawable {
        val d = ctx.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f * d
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(0.32f, 0.22f)
            gradientRadius = 70f * d
            colors = if (isDark(ctx)) intArrayOf(
                Theme.darken(Color.parseColor("#1E3A5F")), Theme.darken(Color.parseColor("#12233D")), Theme.darken(Color.parseColor("#0B1526")))
            else intArrayOf(
                Color.parseColor("#FFFFFF"), Color.parseColor("#DBEAFE"), Color.parseColor("#93C5FD"))
        }
    }

    /** 1.41 (A/E): the free box's sapphire — the
     *  SAME texture as guideButtonBackground (day
     *  #FFFFFF/#DBEAFE/#93C5FD, night
     *  #1E3A5F/#12233D/#0B1526), as a linear
     *  top-left → bottom-right gradient with a
     *  20dp radius and a 1dp stroke (day #93C5FD,
     *  night #2DD4BF). `pale` mixes every stop 50%
     *  toward white (day #FFFFFF/#EDF5FF/#C9E2FE,
     *  night #8F9DAF/#89919E/#858A93, Math.round)
     *  for the free rows — the same texture,
     *  lighter. */
    fun sapphireColors(ctx: Context, pale: Boolean): IntArray {
        val base = if (isDark(ctx)) intArrayOf(
            Theme.darken(Color.parseColor("#1E3A5F")), Theme.darken(Color.parseColor("#12233D")), Theme.darken(Color.parseColor("#0B1526")))
        else intArrayOf(
            Color.parseColor("#FFFFFF"), Color.parseColor("#DBEAFE"), Color.parseColor("#93C5FD"))
        if (!pale) return base
        return IntArray(base.size) { i -> mixTowardWhite(base[i]) }
    }

    private fun mixTowardWhite(color: Int): Int {
        val r = Math.round((Color.red(color) + 255) / 2f)
        val g = Math.round((Color.green(color) + 255) / 2f)
        val b = Math.round((Color.blue(color) + 255) / 2f)
        return Color.rgb(r, g, b)
    }

    fun freeBoxBackground(ctx: Context): Drawable {
        val d = ctx.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            orientation = GradientDrawable.Orientation.TL_BR
            cornerRadius = 20f * d
            colors = sapphireColors(ctx, pale = false)
            setStroke((1 * d).toInt(),
                if (isDark(ctx)) Theme.darken(Color.parseColor("#2DD4BF")) else Color.parseColor("#93C5FD"))
        }
    }

    /** Small glass circle (header buttons, 34dp): chip fill + border. */
    fun glassCircle(ctx: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (isDark(ctx)) {
            setColor(Theme.darken(Color.parseColor("#14FFFFFF")))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Theme.darken(Color.parseColor("#6B60A5FA")))
        } else {
            setColor(Color.parseColor("#E0FFFFFF"))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Color.parseColor("#4D2563EB"))
        }
    }

    /** Glass pill (status pill, country chip). */
    fun glassPill(ctx: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 20f * ctx.resources.displayMetrics.density
        if (isDark(ctx)) {
            setColor(Theme.darken(Color.parseColor("#14FFFFFF")))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Theme.darken(Color.parseColor("#6B60A5FA")))
        } else {
            setColor(Color.parseColor("#E0FFFFFF"))
            setStroke((1 * ctx.resources.displayMetrics.density).toInt(), Color.parseColor("#4D2563EB"))
        }
    }

    /** v1.1: the + FAB matches the app — the same
     *  sapphire gradient as the active tab (v1.13:
     *  was teal -> violet), NOT gold (v1.0 defect:
     *  gold clashed). */
    fun fabBackground(ctx: Context): Drawable {
        val d = ctx.resources.displayMetrics.density
        return GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(if (isDark(ctx)) Theme.darken(Color.parseColor("#1E3A8A")) else Color.parseColor("#1E40AF"),
                if (isDark(ctx)) Theme.darken(Color.parseColor("#1D4ED8")) else Color.parseColor("#3B82F6"))).apply {
            shape = GradientDrawable.OVAL
            cornerRadius = 29f * d
        }
    }

    fun fabTextColor(ctx: Context): Int = Color.WHITE

    /** Page background (mockup .phone.day / .phone.night):
     *  day = pearl linear gradient + warm top-right + cool
     *  bottom-left tints; night = #070912 + teal/violet
     *  aurora tints + faint stars. Never pure black. */
    fun pageBackground(ctx: Context): Drawable =
        OvpnPageDrawable(isDark(ctx), ctx.resources.displayMetrics.density)

    /** Window chrome for the page: the window behind the
     *  content follows the same background, so no white
     *  strips remain at the top or bottom. Call in onCreate,
     *  before setContentView. */
    fun applyPage(activity: Activity) {
        try {
            val dark = isDark(activity)
            activity.window?.setBackgroundDrawable(pageBackground(activity))
            val edge = if (dark) Theme.darken(Color.parseColor("#0A0F1E")) else Color.parseColor("#EDF2F8")
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
            else if (isDark(ctx)) Theme.darken(Color.parseColor("#2DD4BF")) else Color.parseColor("#2DD4BF"))

    /** v1.13: ONE smooth ring (Midnight Sapphire, mockup
     *  v4r2) — a full circle whose colour fades SOFTLY
     *  from saturated to pale and back around the sweep
     *  (no abrupt segment jumps like the old arc). The
     *  gradient is derived from the orb state tint
     *  (blue / red / green), so the ring follows the
     *  connect state. Rotate the view 14s/rev. The
     *  green dashed ring is GONE (user order). */
    fun ringArcDrawable(ctx: Context): Drawable =
        OvpnRingArcDrawable(if (isDark(ctx)) Theme.darken(Color.parseColor("#60A5FA")) else Color.parseColor("#3B82F6"),
            ctx.resources.displayMetrics.density)

    /** v1.1: the ring in the free-tab state colour, so
     *  the ring/glow follow the orb tint. */
    fun ringArcDrawableTinted(ctx: Context, color: Int): Drawable =
        OvpnRingArcDrawable(color, ctx.resources.displayMetrics.density)

    /** v1.13: a small DRAWN lightning bolt (Smart Connect
     *  row) — filled polygon, never an emoji. */
    fun boltBitmap(ctx: Context, colorHex: String, sizeDp: Int): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val s = (sizeDp * d).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val f = s / 96f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(colorHex)
            style = Paint.Style.FILL
            strokeJoin = Paint.Join.ROUND
        }
        val bolt = Path().apply {
            moveTo(56f * f, 4f * f)
            lineTo(28f * f, 54f * f)
            lineTo(46f * f, 54f * f)
            lineTo(38f * f, 92f * f)
            lineTo(70f * f, 40f * f)
            lineTo(50f * f, 40f * f)
            close()
        }
        c.drawPath(bolt, paint)
        return bmp
    }

    /** v1.26: a small map pin (country row) — filled
     *  when pinned, outline when not. Same drawn-bitmap
     *  style as the bolt: never an emoji, never a photo. */
    fun pinBitmap(ctx: Context, colorHex: String, sizeDp: Int, filled: Boolean): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val s = (sizeDp * d).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val f = s / 96f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(colorHex)
            style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = 7f * f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        // Head: circle at (48, 36) r 22; tail: triangle
        // from the head's sides down to (48, 92).
        val pin = Path().apply {
            addCircle(48f * f, 36f * f, 22f * f, Path.Direction.CW)
            moveTo(29f * f, 50f * f)
            lineTo(48f * f, 92f * f)
            lineTo(67f * f, 50f * f)
            close()
        }
        c.drawPath(pin, paint)
        if (filled) {
            // Punch the hole out of the head so the pin
            // reads as a pin, not a blob.
            val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            c.drawCircle(48f * f, 36f * f, 9f * f, hole)
        }
        return bmp
    }
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

    /** v1.13 page background (Midnight Sapphire, mockup
     *  v4r2): day = pale ice blue #EDF2F8 -> #E6ECF4 ->
     *  #EFF3F8; night = deep navy #0A0F1E with SUBTLE
     *  blue/teal tints — the vivid #070912 aurora,
     *  violet and stars are gone.
     *  1.43: every stop/anchor is an ABSOLUTE distance
     *  from the top (880dp span, CLAMP below it) — NEVER
     *  a fraction of the view height h. Three instances
     *  of this drawable paint three different heights
     *  (window/root/scroll = full screen, inner page =
     *  content height), and the 1.42 height-relative
     *  gradient made the same screen Y a different
     *  colour in each — the two-tone page. Now colour
     *  is a pure function of screen Y, identical in
     *  every instance, so no seam is possible no matter
     *  which view paints a given pixel. */
internal class OvpnPageDrawable(private val dark: Boolean, private val density: Float) : OvpnBitmapCachedDrawable() {
    override fun render(c: Canvas, w: Int, h: Int) {
        // 1.43: absolute span — the gradient's full run
        // is 880dp from the top of the screen, whatever
        // this instance's height is.
        val spanPx = 880f * density
        if (dark) {
            c.drawColor(Theme.darken(Color.parseColor("#0A0F1E")))
            // Subtle blue tint, top right.
            val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(w * 0.85f, 0f, w * 0.9f,
                    intArrayOf(Theme.darken(Color.argb(26, 96, 165, 250)), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), blue)
            // Faint teal tint, bottom left — 1.43: anchored
            // at the absolute span end, never at h.
            val teal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(w * 0.10f, spanPx, w * 0.8f,
                    intArrayOf(Theme.darken(Color.argb(18, 45, 212, 191)), Color.TRANSPARENT),
                    floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), teal)
        } else {
            val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                // 1.43: the gradient vector runs 0..spanPx
                // (absolute), CLAMP holds #EFF3F8 below it.
                shader = LinearGradient(0f, 0f, 0f, spanPx,
                    intArrayOf(Color.parseColor("#EDF2F8"), Color.parseColor("#E6ECF4"),
                        Color.parseColor("#EFF3F8")),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), base)
            // Faint cool tint, top right.
            val cool = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(w * 0.85f, 0f, w * 1.2f,
                    intArrayOf(Color.parseColor("#FFFFFF"), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.6f), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), cool)
            // Faint ice tint, bottom left — 1.43: anchored
            // at the absolute span end, never at h.
            val ice = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(w * 0.05f, spanPx, w.toFloat(),
                    intArrayOf(Color.parseColor("#DBEAFE"), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.55f), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), ice)
        }
    }
}

    /** v1.13: the glass connect orb, Midnight Sapphire.
     *  Untinted fill is the mockup's sapphire radial —
     *  day: white -> #DBEAFE -> #93C5FD -> #60A5FA;
     *  night: #1E3A5F -> #12233D -> #0B1526 -> #070D18.
     *  tint (v1.1) still recolours the glass in the
     *  state colour (light blue / light red / light
     *  green) with the same radial structure. The
     *  green halo is GONE (user order, v4r2). */
internal class OvpnOrbDrawable(private val dark: Boolean, private val connected: Boolean, private val tint: Int? = null) : OvpnBitmapCachedDrawable() {
    private fun shadeOf(color: Int, f: Float): Int {
        return Color.argb(Color.alpha(color),
            (Color.red(color) * f).toInt().coerceIn(0, 255),
            (Color.green(color) * f).toInt().coerceIn(0, 255),
            (Color.blue(color) * f).toInt().coerceIn(0, 255))
    }
    private fun lighten(color: Int, f: Float): Int {
        return Color.argb(Color.alpha(color),
            (Color.red(color) + (255 - Color.red(color)) * f).toInt().coerceIn(0, 255),
            (Color.green(color) + (255 - Color.green(color)) * f).toInt().coerceIn(0, 255),
            (Color.blue(color) + (255 - Color.blue(color)) * f).toInt().coerceIn(0, 255))
    }
    override fun render(c: Canvas, w: Int, h: Int) {
        val cx = w / 2f; val cy = h / 2f
        val r = minOf(w, h) / 2f
        if (r <= 0) return
        val stops: IntArray
        val pos: FloatArray
        if (tint != null) {
            stops = if (dark) intArrayOf(
                Color.argb(150, Color.red(lighten(tint, 0.45f)), Color.green(lighten(tint, 0.45f)), Color.blue(lighten(tint, 0.45f))),
                Color.argb(70, Color.red(tint), Color.green(tint), Color.blue(tint)),
                shadeOf(tint, 0.45f),
                Theme.darken(Color.argb(199, 10, 16, 40)))
            else intArrayOf(
                Color.argb(242, 255, 255, 255),
                Color.argb(140, Color.red(lighten(tint, 0.70f)), Color.green(lighten(tint, 0.70f)), Color.blue(lighten(tint, 0.70f))),
                Color.argb(150, Color.red(tint), Color.green(tint), Color.blue(tint)),
                shadeOf(tint, 0.55f))
            pos = floatArrayOf(0f, 0.30f, 0.62f, 1f)
        } else if (dark) {
            stops = intArrayOf(
                Theme.darken(Color.parseColor("#1E3A5F")),
                Theme.darken(Color.parseColor("#12233D")),
                Theme.darken(Color.parseColor("#0B1526")),
                Theme.darken(Color.parseColor("#070D18")))
            pos = floatArrayOf(0f, 0.35f, 0.70f, 1f)
        } else {
            stops = intArrayOf(
                Color.argb(242, 255, 255, 255),
                Color.parseColor("#DBEAFE"),
                Color.parseColor("#93C5FD"),
                Color.parseColor("#60A5FA"))
            pos = floatArrayOf(0f, 0.30f, 0.62f, 1f)
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
                ?: if (dark) Theme.darken(Color.argb(60, 96, 165, 250)) else Color.argb(71, 30, 64, 175)
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

    /** v1.13: the ONE ring (Midnight Sapphire, mockup v4r2).
     *  A full circle, ~3.5dp thick, ~2dp off the orb edge,
     *  whose colour fades SOFTLY from saturated to pale
     *  and back around the sweep — a SweepGradient with
     *  matching start/end stops, so there is no sudden
     *  jump from strong blue to pale blue like the old
     *  border-segment arc. The gradient is derived from
     *  the orb state tint (blue / red / green). Strong
     *  starts at the top; the view rotates 14s/rev. */
internal class OvpnRingArcDrawable(private val tint: Int, private val density: Float) : OvpnBitmapCachedDrawable() {
    private fun lighten(color: Int, f: Float): Int {
        return Color.argb(Color.alpha(color),
            (Color.red(color) + (255 - Color.red(color)) * f).toInt().coerceIn(0, 255),
            (Color.green(color) + (255 - Color.green(color)) * f).toInt().coerceIn(0, 255),
            (Color.blue(color) + (255 - Color.blue(color)) * f).toInt().coerceIn(0, 255))
    }
    override fun render(c: Canvas, w: Int, h: Int) {
        val cx = w / 2f; val cy = h / 2f
        val stroke = 3.5f * density
        val r = minOf(w, h) / 2f - (2f * density) - stroke / 2f
        if (r <= 0) return
        val strong = tint
        val mid = lighten(tint, 0.45f)
        val pale = lighten(tint, 0.82f)
        val shader = SweepGradient(cx, cy,
            intArrayOf(strong, mid, pale, mid, strong),
            floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f))
        // SweepGradient starts at 3 o'clock; rotate it so the
        // saturated point starts at the top, like the mockup.
        val m = Matrix()
        m.setRotate(-90f, cx, cy)
        shader.setLocalMatrix(m)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.shader = shader
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        c.drawCircle(cx, cy, r, paint)
    }
}
