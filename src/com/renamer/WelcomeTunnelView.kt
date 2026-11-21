package com.renamer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/** 2.94: animated "tunnel" for the welcome page, replacing the
 *  breathing glass orb (the user asked for a VPN-related
 *  animated element instead of the blinking circle).
 *  Drawn exactly from the approved mockups
 *  (mockup-welcome-night-day.html + the winning icon from
 *  mockup-welcome-icon-options.html, option 6 «امواج سیگنال»):
 *   - 3 pulse rings expanding from the centre, staggered
 *     0 / 0.8 / 1.6 s over a 2.4 s cycle, alpha ~0.9 -> 0.
 *   - a dashed orbit circle rotating once per 6 s, with two
 *     packet dots at opposite ends.
 *   - the signal-waves icon in the centre (two upward arcs +
 *     a dot below), gently floating +/-3.5dp over 2.2 s.
 *   - a soft teal glow behind the whole element.
 *  The animation runs on the invalidate loop and stops when
 *  the view is detached (postInvalidateOnAnimation is a
 *  no-op off-screen, plus an explicit running flag). */
class WelcomeTunnelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dark = Theme.isDark(context)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private var running = false
    private var startMs = 0L

    // Colours from the approved mockup.
    private val ringColor = if (dark) Theme.darken(Color.parseColor("#2DD4BF")) else Color.parseColor("#0F766E")
    private val orbitColor = if (dark) Theme.darken(Color.argb(115, 0x2D, 0xD4, 0xBF)) else Color.argb(89, 0x0F, 0x76, 0x6E)
    private val dot1Color = if (dark) Color.WHITE else Color.parseColor("#0F766E")
    private val dot2Color = if (dark) Theme.darken(Color.parseColor("#A78BFA")) else Color.parseColor("#7C3AED")
    private val iconTop = if (dark) Theme.darken(Color.parseColor("#5EEAD4")) else Color.parseColor("#26A69A")
    private val iconMid = if (dark) Theme.darken(Color.parseColor("#2DD4BF")) else Color.parseColor("#0F766E")
    private val iconBot = if (dark) Theme.darken(Color.parseColor("#0D9488")) else Color.parseColor("#0E7490")

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = ringColor
        if (dark) setShadowLayer(dp(6f), 0f, 0f, Theme.darken(Color.argb(140, 0x2D, 0xD4, 0xBF)))
    }
    private val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = orbitColor
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(6f)), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4.7f)
        strokeCap = Paint.Cap.ROUND
        shader = LinearGradient(0f, 0f, 100f, 100f,
            intArrayOf(iconTop, iconMid, iconBot),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        if (dark) setShadowLayer(dp(5f), 0f, 0f, Theme.darken(Color.argb(166, 0x2D, 0xD4, 0xBF)))
    }
    private val iconDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = LinearGradient(0f, 0f, 100f, 100f,
            intArrayOf(iconTop, iconMid, iconBot),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    }

    private val arc1Rect = RectF(19f, 27f, 81f, 89f)
    private val arc2Rect = RectF(5f, 30.6f, 95f, 120.6f)
    private var glowShader: Shader? = null

    init {
        // Shadow layers only render on a software canvas.
        if (dark) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Soft teal glow behind everything (mockup: 230px disc).
        glowShader = RadialGradient(w / 2f, h / 2f, dp(115f),
            intArrayOf(Theme.darken(Color.argb(51, 0x2D, 0xD4, 0xBF)), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        startMs = System.currentTimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val t = (System.currentTimeMillis() - startMs).toFloat()

        glowPaint.shader = glowShader
        canvas.drawCircle(cx, cy, dp(115f), glowPaint)

        // 3 pulse rings: base radii 38/55/72dp, scale 0.55->1.22,
        // alpha 0.9->0, 2.4s cycle, staggered 0/0.8/1.6s.
        val baseR = floatArrayOf(dp(38f), dp(55f), dp(72f))
        val delays = floatArrayOf(0f, 800f, 1600f)
        for (i in 0..2) {
            val p = (((t - delays[i]) % 2400f) + 2400f) % 2400f / 2400f
            val scale = 0.55f + 0.67f * p
            ringPaint.alpha = ((1f - p) * 0.9f * 255f).toInt()
            canvas.drawCircle(cx, cy, baseR[i] * scale, ringPaint)
        }

        // Dashed orbit, 78dp radius, one revolution per 6s,
        // with a packet dot at each end.
        val orbitR = dp(78f)
        val angle = (t % 6000f) / 6000f * 360f
        canvas.save()
        canvas.rotate(angle, cx, cy)
        canvas.drawCircle(cx, cy, orbitR, orbitPaint)
        val dotR = dp(4.5f)
        if (dark) dotPaint.setShadowLayer(dp(5f), 0f, 0f, Theme.darken(Color.argb(242, 0x2D, 0xD4, 0xBF)))
        dotPaint.color = dot1Color
        canvas.drawCircle(cx, cy - orbitR, dotR, dotPaint)
        if (dark) dotPaint.setShadowLayer(dp(5f), 0f, 0f, Theme.darken(Color.argb(242, 0xA7, 0x8B, 0xFA)))
        dotPaint.color = dot2Color
        canvas.drawCircle(cx, cy + orbitR, dotR, dotPaint)
        dotPaint.clearShadowLayer()
        canvas.restore()

        // Signal-waves icon (option 6), 78dp, floating +/-3.5dp
        // over 2.2s. Drawn in the mockup's 100x100 space, scaled.
        val floatY = sin(t / 2200f * 2f * Math.PI.toFloat()) * dp(3.5f)
        val scale = dp(78f) / 100f
        canvas.save()
        canvas.translate(cx, cy + floatY)
        canvas.scale(scale, scale)
        canvas.translate(-50f, -50f)
        // Arc 1: M28 58 A31 31 0 0 1 72 58 -> upper semicircle
        // of the circle centred (50,58), r=31.
        canvas.drawArc(arc1Rect, 180f, 180f, false, iconPaint)
        // Arc 2: M18 44 A45 45 0 0 1 82 44 -> circle centred
        // (50,75.6), r=45; from 224.6 deg, sweep 90.8 deg.
        canvas.drawArc(arc2Rect, 224.6f, 90.8f, false, iconPaint)
        // Dot: circle (50,70) r=7.
        canvas.drawCircle(50f, 70f, 7f, iconDotPaint)
        canvas.restore()

        if (running) postInvalidateOnAnimation()
    }
}
