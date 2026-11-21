package com.renamer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * GuideOverlayView (v1.8) — the «راهنمای اتصال» coach-marks
 * layer, drawn entirely in code (never a photo crop):
 * a dim veil over the whole screen with a HOLE cut
 * exactly around the REAL target — the target stays
 * full-bright while everything else is dimmed. The
 * bright target against the dimmed page IS the
 * pointer: there is no drawn shape of any kind —
 * no frame, no ring, no stroke, no outline, and the
 * old drawn pointer is gone entirely (v1.7's thin
 * line only showed as a tiny mark at the orb's edge,
 * because the box hid most of it behind itself).
 *
 * The hole is EXACTLY the target's own bounds, zero
 * margin: a circle for the orb (its view is square)
 * and the chip's own pill shape. The corner radius
 * is half the smaller side, computed from the target
 * rect at draw time, so it always matches the real
 * shape. The view itself never consumes touches —
 * the overlay container decides what a tap does
 * (target = perform its click, box = its own
 * buttons, anywhere else = swallowed).
 */
class GuideOverlayView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    /** Target rect in this view's coordinates (the hole). */
    var targetRect: RectF? = null

    /** 1.36: several holes at once (the v2ray tutorial's
     *  waiting step keeps BOTH the «لغو دریافت» button
     *  and the progress strip bright). When non-empty
     *  it wins over [targetRect]; null/empty falls back
     *  to the single [targetRect] (OpenVPN's flow). */
    var holes: List<RectF>? = null

    /** 1.36: dim strength. Default #99000000 (60% — the
     *  OpenVPN tutorial's old veil). The interactive
     *  tutorials set #CC000000 (80% per the user's
     *  order: the page goes dark except the spotlight). */
    var dimColor: Int = Color.parseColor("#99000000")
        set(v) {
            field = v
            dimPaint.color = v
            invalidate()
        }

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dimColor }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val holeList: List<RectF>? = holes?.takeIf { it.isNotEmpty() }
            ?: targetRect?.let { listOf(it) }
        if (holeList == null) {
            // No target (should not happen): dim everything.
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            return
        }
        // Dim everything EXCEPT the target(s): one path, the
        // full-screen rect plus each target's own shape as
        // a hole (EVEN_ODD), zero margin, nothing drawn
        // around it. Radius = half the smaller side, so
        // the orb (square) becomes a circle and the chip
        // becomes its own pill.
        val path = Path().apply {
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            for (target in holeList) {
                val r = minOf(target.width(), target.height()) / 2f
                addRoundRect(target, r, r, Path.Direction.CW)
            }
            fillType = Path.FillType.EVEN_ODD
        }
        canvas.drawPath(path, dimPaint)
    }
}
