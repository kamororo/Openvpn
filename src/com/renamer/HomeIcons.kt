package com.renamer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/** 2.83: the coordinated home icon set (user pick: mockup «حرفه‌ای ۲»,
 *  neon-line turquoise) as REAL image assets. The 2.82 code-drawn
 *  paths came out crude on device (WireGuard was a plain «S», Hydra
 *  three pins on a ring, Alien Language a blob), so the paths are
 *  gone — the tiles load generated PNGs from assets/ instead.
 *
 *  assets/home_<key>.png      — night: the generated art, glow baked in.
 *  assets/home_<key>_day.png  — day: the SAME shapes, flat diagonal
 *  cyan → violet gradient fill, NO glow, NO neon (user order: day
 *  icons must not be neon; the day home is otherwise exactly 2.81).
 *
 *  The night asset already carries its soft glow, so iconBitmap adds
 *  none in code. Both variants are decoded once per (key, mode) and
 *  scaled per request. The old galaxy PNGs are still used by other
 *  pages (Guide, Chain, Cloudflare, Alien pages), so these new files
 *  have their own home_ prefix and never overwrite them.
 *
 *  The quick-connect card's red power button is NOT drawn here and is
 *  never touched by this file; the bottom pill's pulse icon is not
 *  drawn here either. */
object HomeIcons {
    private val ASSET = mapOf(
        "smart" to "home_smart.png",
        "v2ray" to "home_v2ray.png",
        "openvpn" to "home_openvpn.png",
        "wireguard" to "home_wireguard.png",
        "emergency" to "home_emergency.png",
        "cloudflare" to "home_cloudflare.png",
        // 1.73: the speed-test tile reuses the pulse icon
        // (assets/monitor.png, the bottom pill's pulse) as its
        // tile art, in both modes.
        "speedtest" to "home_speedtest.png",
    )

    private val cache = mutableMapOf<String, Bitmap?>()

    /** Section key → icon bitmap of sizePx × sizePx, from the bundled
     *  asset for the current mode. Unknown keys fall back to the v2ray
     *  «V». Returns null only on an unexpected error. */
    fun iconBitmap(ctx: Context, key: String, sizePx: Int, night: Boolean): Bitmap? {
        if (sizePx <= 0) return null
        return try {
            val name = ASSET[key] ?: ASSET["v2ray"]!!
            val asset = if (night) name else name.replace(".png", "_day.png")
            val full = cache.getOrPut(asset) {
                try {
                    ctx.assets.open(asset).use { BitmapFactory.decodeStream(it) }
                } catch (_: Exception) { null }
            } ?: return null
            if (full.width == sizePx && full.height == sizePx) full
            else Bitmap.createScaledBitmap(full, sizePx, sizePx, true)
        } catch (_: Exception) { null }
    }

    /** 2.82: home tile background for both modes. Day: the 2.78 glass
     *  card (semi-transparent white, faint dark border) — unchanged.
     *  Night: dark glass with a thin teal → purple gradient border, as
     *  in the picked mockup. `connected` paints the 2.81 green border
     *  (the card button's green) in both modes; `selected` paints the
     *  sort-preview selection (soft green) in both modes. */
    internal class TileBackgroundDrawable(
        private val dark: Boolean,
        private val radiusPx: Float,
        private val strokePx: Float
    ) : android.graphics.drawable.Drawable() {
        private var connected = false
        private var selected = false

        fun setConnected(c: Boolean) {
            if (connected != c) { connected = c; invalidateSelf() }
        }

        fun setSelected(s: Boolean) {
            if (selected != s) { selected = s; invalidateSelf() }
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val rect = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            fillPaint.color = when {
                selected && dark -> Theme.darken(Color.parseColor("#CC1F3D2B"))
                selected -> Color.parseColor("#E8F5E9")
                dark -> Theme.darken(Color.parseColor("#CC232840"))
                else -> Color.parseColor("#E6FFFFFF")
            }
            canvas.drawRoundRect(rect, radiusPx, radiusPx, fillPaint)
            when {
                connected -> {
                    strokePaint.shader = null
                    strokePaint.color = Color.parseColor(QuickConnect.GREEN_HEX)
                }
                selected -> {
                    strokePaint.shader = null
                    strokePaint.color = Color.parseColor("#66BB6A")
                }
                dark -> {
                    // 1.73 (home-night gap): the tile's teal → purple
                    // night border ×0.7, like the same pair in VpnActivity.
                    strokePaint.shader = LinearGradient(
                        b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                        intArrayOf(Theme.darken(Color.parseColor("#2DD4BF")), Theme.darken(Color.parseColor("#A78BFA"))),
                        null, Shader.TileMode.CLAMP
                    )
                }
                else -> {
                    strokePaint.shader = null
                    strokePaint.color = Color.parseColor("#22000000")
                }
            }
            val inset = strokePx / 2f
            val inner = RectF(
                rect.left + inset, rect.top + inset,
                rect.right - inset, rect.bottom - inset
            )
            val r = maxOf(0f, radiusPx - inset)
            canvas.drawRoundRect(inner, r, r, strokePaint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }
}
