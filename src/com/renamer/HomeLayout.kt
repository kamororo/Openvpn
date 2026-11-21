package com.renamer

import android.content.Context
import kotlin.math.pow
import kotlin.math.sqrt

/** Shared home-grid layout: how many tiles per row
 *  (2–5, default 3 — the approved look) and how big
 *  tiles render (slider 70–140%, default 70% — the
 *  smallest size, 1.32).
 *  One source of truth for Home AND the Sort page,
 *  so the sort preview is exactly what Home shows.
 *  The math is defensive on purpose: whatever the
 *  user picks, icons can never overflow their cell
 *  and labels can never grow the row without bound.
 *  Unsaved changes live only in the Sort page's
 *  local state; Save writes them here, Back drops
 *  them — the same model as the tile order. */
object HomeLayout {
    private const val PREFS = "renamer_settings"
    private const val KEY_COLS = "home_cols"
    private const val KEY_SCALE = "home_tile_scale"

    fun cols(ctx: Context): Int = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_COLS, 3).coerceIn(2, 5)
    } catch (_: Exception) { 3 }

    // 1.32: fresh installs start at the SMALLEST size
    // (0.7 = 70%, the slider minimum). Saved values are
    // untouched — this is the default only.
    fun scale(ctx: Context): Float = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_SCALE, 0.7f).coerceIn(0.7f, 1.4f)
    } catch (_: Exception) { 0.7f }

    fun save(ctx: Context, cols: Int, scale: Float) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_COLS, cols.coerceIn(2, 5))
                .putFloat(KEY_SCALE, scale.coerceIn(0.7f, 1.4f))
                .apply()
        } catch (_: Exception) {}
    }

    /** Icon size in dp for a cell. Wider grids get
     *  smaller icons (square-root falloff, so 2 cols
     *  is not twice as big as 4), the slider scales
     *  on top, and the result is always clamped to
     *  fit inside its own cell with room to spare. */
    fun iconDp(cols: Int, scale: Float, screenWidthDp: Float): Int {
        val c = cols.coerceIn(2, 5)
        val s = scale.coerceIn(0.7f, 1.4f)
        val cellW = (screenWidthDp - 32f) / c
        val cap = cellW - 20f
        val base = 64f * sqrt(3f / c) * s
        return base.coerceIn(36f, 96f).coerceAtMost(cap.coerceAtLeast(36f)).toInt()
    }

    /** Label size in sp. Gentler falloff than icons
     *  (fourth root), so 5 columns stays readable,
     *  clamped so no choice makes text huge or tiny. */
    fun textSp(cols: Int, scale: Float): Float {
        val c = cols.coerceIn(2, 5)
        val s = scale.coerceIn(0.7f, 1.4f)
        return (13f * (3f / c).toDouble().pow(0.25).toFloat() * s).coerceIn(10f, 17f)
    }

    fun screenWidthDp(ctx: Context): Float = try {
        val m = ctx.resources.displayMetrics
        m.widthPixels / m.density
    } catch (_: Exception) { 360f }

    /** 2.81: normalize a tile icon so its non-transparent CONTENT fills
     *  the same box for every tile. Galaxy icons carry different
     *  transparent padding (the Alien Language icon's content is small
     *  inside a big transparent frame, so it used to render tiny next
     *  to icons that fill their frame). The content bounds are measured
     *  (alpha > 16, same threshold as the Guide's cropTransparent),
     *  the content is cropped, scaled uniformly — aspect kept — so its
     *  LONGER side fills the box, and centred on a transparent
     *  boxPx × boxPx canvas. Returns the original bitmap when there is
     *  nothing to crop (opaque or fully transparent) or on any error. */
    fun normalizeIcon(src: android.graphics.Bitmap?, boxPx: Int): android.graphics.Bitmap? {
        if (src == null || boxPx <= 0) return src
        return try {
            val w = src.width
            val h = src.height
            if (w <= 0 || h <= 0) return src
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            var left = w
            var top = h
            var right = -1
            var bottom = -1
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val a = (px[y * w + x] ushr 24) and 0xFF
                    if (a > 16) {
                        if (x < left) left = x
                        if (x > right) right = x
                        if (y < top) top = y
                        if (y > bottom) bottom = y
                    }
                }
            }
            if (right < left || bottom < top) return src
            val cw = right - left + 1
            val ch = bottom - top + 1
            // Already filling the frame: nothing to normalize.
            if (cw == w && ch == h) return src
            val scale = boxPx.toFloat() / maxOf(cw, ch)
            val dw = maxOf(1, (cw * scale + 0.5f).toInt())
            val dh = maxOf(1, (ch * scale + 0.5f).toInt())
            val out = android.graphics.Bitmap.createBitmap(boxPx, boxPx, android.graphics.Bitmap.Config.ARGB_8888)
            val cv = android.graphics.Canvas(out)
            val cropped = android.graphics.Bitmap.createBitmap(src, left, top, cw, ch)
            val scaled = android.graphics.Bitmap.createScaledBitmap(cropped, dw, dh, true)
            cv.drawBitmap(scaled, (boxPx - dw) / 2f, (boxPx - dh) / 2f, null)
            out
        } catch (_: Exception) { src }
    }
}
