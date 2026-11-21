package com.renamer

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * NeonRingView (1.36) — the small slowly-blinking neon
 * ring around the «راهنمای اتصال» button, in v2ray and
 * in OpenVPN.
 *
 * WHY A SEPARATE VIEW, AND WHY IT CAN ONLY BE SIZED
 * EXPLICITLY: in 1.35 the ring was a plain View with
 * MATCH_PARENT layout params inside a WRAP_CONTENT
 * FrameLayout wrapper. A plain View's default onMeasure
 * returns the FULL spec size for an AT_MOST spec
 * (View.getDefaultSize returns specSize for AT_MOST and
 * EXACTLY alike), so the ring measured itself to the
 * whole screen; the WRAP_CONTENT wrapper then measured
 * itself to the ring — full-screen — and the ring drew
 * as a GIANT thin blue oval spanning the whole page,
 * while the button (gravity CENTER in that giant
 * wrapper) sat in the middle of the empty list area
 * instead of under the free button. Both 1.35 defects
 * had this one root cause.
 *
 * This view refuses that trap: its layout params are
 * ALWAYS explicit pixel sizes (never MATCH_PARENT,
 * never WRAP_CONTENT). It starts at a bounded explicit
 * size (150x36dp — close to the real button, so the
 * first frame is already small) and [fitTo] re-sizes
 * it to the real button's measured size + 6dp (3dp
 * of ring breathing room on each side), centred on
 * the button. Call [fitTo] whenever the button is
 * (re)laid out — the render function does it on every
 * show, so a language change (different text length)
 * is picked up too.
 */
class NeonRingView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null,
    strokeHex: String = "#2563EB"
) : View(ctx, attrs) {

    init {
        val d = ctx.resources.displayMetrics.density
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setStroke((2 * d).toInt(), Color.parseColor(strokeHex))
        }
        // Explicit size from the start — the wrapper
        // can never blow up to the page through this
        // view. fitTo() corrects it to the real button.
        layoutParams = FrameLayout.LayoutParams(
            (150 * d).toInt(), (36 * d).toInt()).apply {
            gravity = android.view.Gravity.CENTER
        }
    }

    /** Size this ring to hug [target] exactly: the
     *  target's measured size + [extraDp] total (3dp
     *  a side by default), centred on the target.
     *  Safe to call before layout — it re-posts
     *  itself until the target has a size. */
    fun fitTo(target: View, extraDp: Float = 6f) {
        post {
            val d = resources.displayMetrics.density
            val extra = (extraDp * d).toInt()
            val w = target.width
            val h = target.height
            if (w <= 0 || h <= 0) {
                // Not laid out yet — try again next frame.
                post { fitTo(target, extraDp) }
                return@post
            }
            val lp = layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(w + extra, h + extra)
            lp.width = w + extra
            lp.height = h + extra
            lp.gravity = android.view.Gravity.CENTER
            layoutParams = lp
            requestLayout()
        }
    }
}
