package com.renamer

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch

/** 1.73 (worker A): the «تست سرعت» section as a SMALL FLOATING PAGE.
 *
 *  Tapping the home tile opens a centred card over the dimmed home
 *  (NOT a full-screen page, NOT an inline card): mockup-measured
 *  310×375dp, side margins ~24dp, vertically centred, home dimmed
 *  ~60% behind it, solid opaque background (white day / #232840
 *  night) with a teal→purple gradient border in night, close (×) at
 *  the top corner + refresh icon in the header (pulse icon + «تست
 *  سرعت» title). Closing (× or tapping the dimmed area) returns to
 *  the home page; the panel never navigates anywhere.
 *
 *  Content, top to bottom, exactly the confirmed mockup
 *  (variant-c-floating-open-*.png): small MAP on top (~110dp, wide)
 *  with a red pin at the measured location + city label pill; speed
 *  row (دانلود / آپلود / پینگ, download blue); five full-width info
 *  rows (label right, value left): آیپی، کشور (Persian name + flag),
 *  شهر، پستال کد، تایم زون.
 *
 *  REAL DATA, never mockup samples: speed via SpeedTest.kt UNCHANGED
 *  (the speedtest.net flow, ~20s bound); IP + geo via
 *  https://api.ip.sb/geoip with ip-api.com as fallback (see
 *  SpeedTestPanelPure for the endpoint verification). An unmeasured
 *  field shows «—». The map is real OpenStreetMap raster tiles
 *  centred on the measured lat/lon (no SDK, no API key, no WebView);
 *  if tiles fail to load, a plain drawn placeholder shows instead.
 *  1.74 (item A): the map is ZOOMABLE + COMPLETE — a ZoomableMapView
 *  (pinch zoom + drag pan + +/- buttons, zoom 11–16, default 13)
 *  over a LARGER stitched tile area (view + one tile of padding per
 *  side), re-fetching tiles at the new zoom; the red pin is
 *  recomputed from the true lat/lon on every transform, never
 *  pinned to a fixed screen point. The flag is the app's existing
 *  text (same as CloudflareActivity's country rows); a country with
 *  no flag shows its name alone, never a wrong flag.
 *
 *  1.75 (F): MAP FIRST, THEN TEST — opening the panel loads the map
 *  first (geo lookup, then map tiles) and starts the test ONLY once
 *  the map has finished loading, or definitively failed and the
 *  placeholder is shown; the refresh icon re-runs the same sequence
 *  (see SpeedTestSequencePure, the gate the panel drives). The runner
 *  logic is the 1.72 home speed-test box's, moved verbatim in
 *  behaviour: generation guard, Canceller, and a per-run
 *  NetworkCallback that cancels the test the moment the default
 *  network changes (VPN on/off, network switch, IP change).
 *  RTL rule: every row sets layoutDirection from the language;
 *  Persian text uses TEXT_DIRECTION_LOCALE + gravity START, numbers
 *  / IP / timezone use TEXT_DIRECTION_LTR. No supportsRtl. */
object SpeedTestPanel {

    private const val UA = "InternetAzad2026"

    /** The card background: SOLID fill (white day / #232840 night,
     *  fully opaque, as mocked) + 1dp border — teal→purple gradient
     *  in night, faint dark in day — 24dp corners. */
    private class PanelBackgroundDrawable(
        private val dark: Boolean,
        private val radiusPx: Float,
        private val strokePx: Float
    ) : android.graphics.drawable.Drawable() {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val rect = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
            fillPaint.color = if (dark) Color.parseColor("#232840") else Color.WHITE
            canvas.drawRoundRect(rect, radiusPx, radiusPx, fillPaint)
            if (dark) {
                strokePaint.shader = LinearGradient(
                    b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                    intArrayOf(Color.parseColor("#2DD4BF"), Color.parseColor("#A78BFA")),
                    null, Shader.TileMode.CLAMP
                )
            } else {
                strokePaint.shader = null
                strokePaint.color = Color.parseColor("#22000000")
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
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }

    /** The close (×), drawn in code (no font glyph — «✕» is missing
     *  from some fonts and would show tofu). */
    private class CloseXView(ctx: Context, private val colorHex: String) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(colorHex)
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = resources.displayMetrics.density
            val pad = 3f * d
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawLine(pad, pad, w - pad, h - pad, paint)
            canvas.drawLine(w - pad, pad, pad, h - pad, paint)
        }
    }

    /** The refresh icon, drawn in code: a near-full arc + arrowhead. */
    private class RefreshView(ctx: Context, private val colorHex: String) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(colorHex)
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = resources.displayMetrics.density
            val pad = 3f * d
            val r = (width.toFloat() - 2 * pad) / 2f
            val cx = width.toFloat() / 2f
            val cy = height.toFloat() / 2f
            // Arc from ~40° to ~340° (gap at the top-right, where the
            // arrowhead sits).
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 40f, 300f, false, paint)
            // Arrowhead at the arc's start (top-right).
            val ax = cx + r * 0.77f
            val ay = cy - r * 0.64f
            val s = 4.5f * d
            val path = Path()
            path.moveTo(ax + s, ay - s * 0.4f)
            path.lineTo(ax - s * 0.7f, ay - s * 0.2f)
            path.lineTo(ax + s * 0.2f, ay + s)
            path.close()
            paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)
        }
    }

    /** Rounded-corner placeholder for the map while tiles load or
     *  when they fail: a plain solid fill, never fake streets.
     *  (Also the map frame's background behind the ZoomableMapView,
     *  which draws the same fill itself when it has no tiles.) */
    private class MapPlaceholderDrawable(
        private val dark: Boolean,
        private val radiusPx: Float
    ) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.parseColor("#1A2036") else Color.parseColor("#E8EAF0")
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            canvas.drawRoundRect(
                RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()),
                radiusPx, radiusPx, paint
            )
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }

    private class Panel internal constructor(
        private val activity: Activity,
        private val dialog: Dialog
    ) {
        private val handler = Handler(Looper.getMainLooper())
        @Volatile private var testing = false
        private var gen = 0
        @Volatile private var canceller: SpeedTest.Canceller? = null
        private var netSig: String? = null
        private var netCallback: ConnectivityManager.NetworkCallback? = null
        private var geo: SpeedTestPanelPure.GeoInfo? = null
        // 1.74 (item A) map state: the measured anchor + current
        // zoom. mapGen guards tile loads (only the latest zoom /
        // re-centre load may display); mapShownKey skips a reload
        // for the exact state already on screen.
        private var mapLat: Double? = null
        private var mapLon: Double? = null
        private var mapZoom: Int = SpeedTestPanelPure.MAP_ZOOM_DEFAULT
        private var mapGen = 0
        private var mapShownKey: String? = null
        // 1.75 (F): MAP FIRST, THEN TEST — the sequencing gate
        // (SpeedTestSequencePure.Sequence, driven on the main
        // thread) + the run the gate is holding the test for.
        // pendingGen/pendingCanc are set in startTest and cleared
        // when the test starts, finishes or is cancelled, so a
        // stale map settle can never start a dead run's test.
        private val sequence = SpeedTestSequencePure.Sequence()
        private var pendingGen = -1
        private var pendingCanc: SpeedTest.Canceller? = null
        /** Tile cache: stitched bitmaps per zoom (max 2, RGB_565),
         *  so zooming back to a visited level is instant instead
         *  of a re-fetch. Keyed by zoom only — cleared whenever
         *  the anchor moves (new geo / re-centre / new test), so a
         *  cached bitmap can never belong to another location. */
        private val tileCache = LinkedHashMap<Int, CachedTiles>()
        private data class CachedTiles(
            val stitched: Bitmap,
            val plan: SpeedTestPanelPure.TilePlan,
            val fetchPanX: Double,
            val fetchPanY: Double
        )

        private lateinit var valDl: TextView
        private lateinit var valUl: TextView
        private lateinit var valPing: TextView
        private lateinit var valIp: TextView
        private lateinit var countryName: TextView
        private lateinit var countryFlag: TextView
        private lateinit var valCity: TextView
        private lateinit var valPostal: TextView
        private lateinit var valTimezone: TextView
        private lateinit var mapView: ZoomableMapView
        private lateinit var cityPill: TextView
        private lateinit var zoomInBtn: TextView
        private lateinit var zoomOutBtn: TextView
        private lateinit var refreshView: View
        private var mapWpx = 0
        private var mapHpx = 0

        private val dark = HomeActivity.isDarkMode(activity)
        private val persian = !AppLanguage.isEnglish(activity)

        private fun dp(v: Int) = (v * activity.resources.displayMetrics.density + 0.5f).toInt()

        /** A speed value with a smaller unit («۴۵٫۲ Mbps», «۳۸ ms») —
         *  the number stays full size, the unit drops to 55%.
         *  Same shape as the old home box's speedValueText. */
        private fun speedValueText(number: String, unit: String): CharSequence {
            if (unit.isBlank()) return number
            val sb = android.text.SpannableStringBuilder()
            sb.append(number)
            val start = sb.length
            sb.append(" $unit")
            sb.setSpan(android.text.style.RelativeSizeSpan(0.55f), start, sb.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sb
        }

        fun buildContent(): View {
            val titleColor = if (dark) "#F2F3FA" else "#23232E"
            val labelColor = if (dark) "#9CA3AF" else "#6B7280"
            val valueColor = titleColor
            val dlColor = if (dark) "#93C5FD" else "#2563EB"
            val iconGrey = if (dark) "#9CA3AF" else "#6B7280"
            val dividerColor = if (dark) Color.argb(38, 255, 255, 255) else Color.parseColor("#E2E2E2")
            val mapRadius = dp(16).toFloat()

            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = PanelBackgroundDrawable(dark, dp(SpeedTestPanelPure.CORNER_RADIUS_DP).toFloat(), dp(1).toFloat())
                setPadding(dp(18), dp(14), dp(18), dp(18))
            }

            // Header: [pulse icon][«تست سرعت»] ... [refresh][×] in RTL
            // (Persian: title rightmost, × far left — as mocked).
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = if (persian) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            // 1.74 (merger): the header icon is the same speedometer/
            // gauge art as the home tile — the user's order was that
            // the speed-test icon must be a speed-test icon, and the
            // header is part of the section. Isolated swap: only this
            // ImageView changes. The bottom pill (HomeActivity) and
            // the widget monitoring icon (SectionWidgetProvider) keep
            // loading monitor.png in their own code and are untouched.
            // The gauge is full-colour art (night / _day variants,
            // same convention as HomeIcons), so NO colour filter is
            // applied — tinting it blue would wash it out — and it
            // is square (24×24) instead of the pulse's 24×17.
            val pulse = ImageView(activity).apply {
                try {
                    val asset = if (dark) "home_speedometer.png" else "home_speedometer_day.png"
                    val bmp = BitmapFactory.decodeStream(activity.assets.open(asset))
                    setImageBitmap(bmp)
                } catch (_: Exception) {}
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            }
            val title = TextView(activity).apply {
                text = AppLanguage.t(activity, "تست سرعت", "Speed test")
                textSize = 17f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(titleColor))
                textDirection = View.TEXT_DIRECTION_LOCALE
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8)
                }
            }
            val spacer = View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            }
            refreshView = RefreshView(activity, iconGrey).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                    marginEnd = dp(14)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { if (!testing) startTest() }
            }
            val closeView = CloseXView(activity, iconGrey).apply {
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog.dismiss() }
            }
            header.addView(pulse)
            header.addView(title)
            header.addView(spacer)
            header.addView(refreshView)
            header.addView(closeView)
            card.addView(header)

            // Map on top (~110dp, wide): 1.74 zoomable — pinch +
            // drag + +/- buttons over a larger stitched tile area
            // (ZoomableMapView); a plain drawn placeholder until
            // tiles land / when they fail. The pin is drawn by the
            // view itself, recomputed from the true lat/lon.
            val screenWpx = activity.resources.displayMetrics.widthPixels
            val panelWpx = screenWpx - dp(SpeedTestPanelPure.SIDE_MARGIN_DP) * 2
            mapWpx = panelWpx - dp(18) * 2
            mapHpx = dp(SpeedTestPanelPure.MAP_HEIGHT_DP)
            val mapFrame = FrameLayout(activity).apply {
                background = MapPlaceholderDrawable(dark, mapRadius)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, mapHpx).apply {
                    topMargin = dp(12)
                }
                clipChildren = true
            }
            // Map on top (~110dp, wide): 1.74 zoomable — pinch +
            // drag + +/- buttons over a larger stitched tile area
            // (ZoomableMapView); a plain drawn placeholder until
            // tiles land / when they fail. The pin is drawn by the
            // view itself, recomputed from the true lat/lon.
            mapView = ZoomableMapView(activity).apply {
                dark = this@Panel.dark
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT)
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, mapRadius)
                    }
                }
                listener = object : ZoomableMapView.MapListener {
                    override fun onZoomStep(newZoom: Int, newPanX: Double, newPanY: Double) {
                        mapZoom = newZoom
                        refreshZoomButtons()
                        val lat = mapLat ?: return
                        val lon = mapLon ?: return
                        loadMapAt(lat, lon, newZoom, newPanX, newPanY)
                    }

                    override fun onRecenter(newLat: Double, newLon: Double) {
                        mapLat = newLat
                        mapLon = newLon
                        // The anchor moved: cached bitmaps belong
                        // to the old anchor.
                        clearTileCache()
                        loadMapAt(newLat, newLon, mapZoom, 0.0, 0.0)
                    }
                }
            }
            mapFrame.addView(mapView)
            // +/- zoom buttons, bottom corner (city pill is top):
            // small round buttons, semi-transparent so the map
            // stays readable underneath.
            fun zoomButton(label: String, onTap: () -> Unit): TextView {
                return TextView(activity).apply {
                    text = label
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor(if (dark) "#F2F3FA" else "#23232E"))
                    gravity = Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor(if (dark) "#CC2A3148" else "#E6FFFFFF"))
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onTap() }
                }
            }
            zoomInBtn = zoomButton("+") { mapView.zoomIn() }
            zoomOutBtn = zoomButton("-") { mapView.zoomOut() }
            val zoomColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(dp(8), dp(8), dp(8), dp(8))
                }
            }
            zoomColumn.addView(zoomInBtn)
            zoomColumn.addView(zoomOutBtn.apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
            })
            mapFrame.addView(zoomColumn)
            cityPill = TextView(activity).apply {
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#23232E"))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 1000f
                    setColor(Color.parseColor("#E6FFFFFF"))
                }
                setPadding(dp(10), dp(4), dp(10), dp(4))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                    setMargins(dp(8), dp(8), dp(8), dp(8))
                }
                visibility = View.GONE
            }
            mapFrame.addView(cityPill)
            card.addView(mapFrame)

            // Speed row: دانلود / آپلود / پینگ (download blue).
            fun speedColumn(faLabel: String, enLabel: String, valueColorHex: String): TextView {
                val col = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                col.addView(TextView(activity).apply {
                    text = AppLanguage.t(activity, faLabel, enLabel)
                    textSize = 13f
                    setTextColor(Color.parseColor(labelColor))
                    textDirection = View.TEXT_DIRECTION_LOCALE
                    gravity = Gravity.CENTER
                })
                val v = TextView(activity).apply {
                    text = "—"
                    textSize = 19f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor(valueColorHex))
                    textDirection = View.TEXT_DIRECTION_LTR
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(6)
                    }
                }
                col.addView(v)
                return v
            }
            val speedRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = if (persian) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(12)
                }
            }
            // RTL: first column lands on the RIGHT (دانلود), as mocked.
            valDl = speedColumn("دانلود", "Download", dlColor)
            valUl = speedColumn("آپلود", "Upload", valueColor)
            valPing = speedColumn("پینگ", "Ping", valueColor)
            // speedColumn returns the value view; the column layout
            // is its parent.
            fun columnOf(v: TextView): View = v.parent as View
            speedRow.addView(columnOf(valDl))
            speedRow.addView(columnOf(valUl))
            speedRow.addView(columnOf(valPing))
            card.addView(speedRow)

            // Divider.
            card.addView(View(activity).apply {
                setBackgroundColor(dividerColor)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(12)
                }
            })

            // Five full-width info rows: label right, value left.
            fun infoRow(faLabel: String, enLabel: String, valueView: View): LinearLayout {
                return LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = if (persian) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(10)
                    }
                    addView(TextView(activity).apply {
                        text = AppLanguage.t(activity, faLabel, enLabel)
                        textSize = 15f
                        setTextColor(Color.parseColor(labelColor))
                        textDirection = View.TEXT_DIRECTION_LOCALE
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT)
                    })
                    addView(valueView)
                }
            }
            fun valueText(ltr: Boolean): TextView {
                return TextView(activity).apply {
                    text = "—"
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor(valueColor))
                    textDirection = if (ltr) View.TEXT_DIRECTION_LTR else View.TEXT_DIRECTION_LOCALE
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            }
            valIp = valueText(true)
            card.addView(infoRow("آیپی", "IP", valIp))
            // Country value: Persian name + flag emoji (the app's
            // existing flag rendering); no flag asset, never a wrong
            // flag — the name alone when the flag is unavailable.
            val countryValue = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = if (persian) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            countryName = TextView(activity).apply {
                text = "—"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor(valueColor))
                textDirection = View.TEXT_DIRECTION_LOCALE
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            countryFlag = TextView(activity).apply {
                text = ""
                textSize = 18f
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(6)
                }
            }
            countryValue.addView(countryName)
            countryValue.addView(countryFlag)
            card.addView(infoRow("کشور", "Country", countryValue))
            valCity = valueText(false)
            card.addView(infoRow("شهر", "City", valCity))
            valPostal = valueText(true)
            card.addView(infoRow("پستال کد", "Postal code", valPostal))
            valTimezone = valueText(true)
            card.addView(infoRow("تایم زون", "Time zone", valTimezone))
            return card
        }

        private fun setDownload(mbps: Double?) {
            try {
                val (n, u) = SpeedTestPanelPure.formatSpeedParts(mbps, persian)
                valDl.text = speedValueText(n, u)
            } catch (_: Exception) {}
        }

        private fun setUpload(mbps: Double?) {
            try {
                val (n, u) = SpeedTestPanelPure.formatSpeedParts(mbps, persian)
                valUl.text = speedValueText(n, u)
            } catch (_: Exception) {}
        }

        private fun setPing(ms: Long?) {
            try {
                val (n, u) = SpeedTestPanelPure.formatPingParts(ms, persian)
                valPing.text = speedValueText(n, u)
            } catch (_: Exception) {}
        }

        private fun setGeo(g: SpeedTestPanelPure.GeoInfo?) {
            geo = g
            try {
                valIp.text = SpeedTestPanelPure.displayOrDash(g?.ip)
                val name = SpeedTestPanelPure.countryFa(g?.countryCode ?: "", g?.country ?: "", persian)
                countryName.text = name
                val flag = if ((g?.countryCode ?: "").length == 2) GeoFlag.flagEmoji(g!!.countryCode) else ""
                // GeoFlag.flagEmoji returns "🌐" for a bad code; only
                // show a real two-letter flag, never the globe.
                countryFlag.text = if ((g?.countryCode ?: "").length == 2 && flag != "🌐") flag else ""
                valCity.text = SpeedTestPanelPure.displayOrDash(g?.city)
                valPostal.text = SpeedTestPanelPure.displayOrDash(g?.postal)
                valTimezone.text = SpeedTestPanelPure.displayOrDash(g?.timezone)
                val city = g?.city?.trim().orEmpty()
                if (city.isNotBlank()) {
                    cityPill.text = city
                    cityPill.visibility = View.VISIBLE
                } else {
                    cityPill.visibility = View.GONE
                }
                if (g?.lat != null && g.lon != null) {
                    mapLat = g.lat
                    mapLon = g.lon
                    mapZoom = SpeedTestPanelPure.MAP_ZOOM_DEFAULT
                    clearTileCache()
                    mapView.setAnchor(g.lat, g.lon)
                    displayedStitched = null
                    refreshZoomButtons()
                    loadMapAt(g.lat, g.lon, mapZoom, 0.0, 0.0)
                } else {
                    mapLat = null
                    mapLon = null
                }
            } catch (_: Exception) {}
        }

        /** Start a run: MAP FIRST, THEN TEST (1.75, F). The geo
         *  lookup runs first; when it lands, the map loads (or the
         *  placeholder stays when there is no map); ONLY once the
         *  map has finished loading — or definitively failed — does
         *  the speed measurement start, via maybeStartSpeedTest.
         *  Runner logic moved from the 1.72 home speed-test box (v1.16/v1.17):
         *  same generation guard, same Canceller, same
         *  network-change cancel — the measurement itself is
         *  SpeedTest.run, untouched (it lives in
         *  startSpeedMeasurement now, reached only through the gate). */
        fun startTest() {
            if (testing) return
            testing = true
            val g = ++gen
            val canc = SpeedTest.Canceller()
            canceller = canc
            pendingGen = g
            pendingCanc = canc
            sequence.openPanel()
            netSig = try { activeNetworkSignature() } catch (_: Exception) { null }
            registerNetCallback()
            setDownload(null); setUpload(null); setPing(null)
            setGeo(null)
            mapLat = null
            mapLon = null
            mapZoom = SpeedTestPanelPure.MAP_ZOOM_DEFAULT
            mapShownKey = null
            mapGen++
            clearTileCache()
            try { mapView.clearTiles() } catch (_: Exception) {}
            displayedStitched = null
            try { refreshView.alpha = 0.5f } catch (_: Exception) {}
            // Step 1 — geo lookup ONLY. The speed test does NOT
            // start here anymore: geoArrived is told first (so a
            // cached map settling inside setGeo cannot open the gate
            // early), then setGeo loads the map, then
            // maybeStartSpeedTest starts the measurement iff the
            // gate is open (no map to wait for).
            Thread {
                try {
                    val geo = fetchOwnGeo()
                    handler.post {
                        if (g == gen) {
                            val hasMap = geo?.lat != null && geo?.lon != null
                            sequence.geoArrived(hasMap)
                            setGeo(geo)
                            maybeStartSpeedTest()
                        }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()
        }

        /** Start the measurement iff the sequencing gate is open
         *  (geo done AND map settled or skipped) and it has not
         *  started yet for this run. The only caller that starts
         *  a measurement. */
        private fun maybeStartSpeedTest() {
            val g = pendingGen
            val canc = pendingCanc ?: return
            if (g < 0 || g != gen) return
            if (!sequence.tryStartTest()) return
            startSpeedMeasurement(g, canc)
        }

        /** One map load has settled — tiles shown (loaded=true)
         *  or the placeholder left in place (loaded=false, the
         *  load definitively failed). Opens the sequencing gate:
         *  if this run is still waiting on the map, the speed test
         *  may start now. A settle from a zoom/pan after the test
         *  already started, or from a cancelled run, is a no-op
         *  (the gate only opens once, from MAP_LOADING). */
        private fun onMapSettled(loaded: Boolean) {
            if (loaded) sequence.mapLoaded() else sequence.mapFailed()
            maybeStartSpeedTest()
        }

        /** The speed measurement itself (download/upload/ping via
         *  SpeedTest.run, untouched): reached ONLY through
         *  maybeStartSpeedTest, i.e. only after the map has
         *  settled. Same generation guard / Canceller / completion
         *  handling as the 1.72 home box. */
        private fun startSpeedMeasurement(g: Int, canc: SpeedTest.Canceller) {
            Thread {
                val cb = object : SpeedTest.Callback {
                    override fun onPing(ms: Long) {
                        handler.post { if (g == gen) setPing(ms) }
                    }

                    override fun onDownload(mbps: Double) {
                        handler.post { if (g == gen) setDownload(mbps) }
                    }

                    override fun onUpload(mbps: Double) {
                        handler.post { if (g == gen) setUpload(mbps) }
                    }
                }
                val r = try { SpeedTest.run(cb, canc) } catch (_: Exception) { null }
                handler.post {
                    if (g != gen) return@post
                    testing = false
                    sequence.finishTest()
                    if (canceller === canc) canceller = null
                    pendingGen = -1
                    pendingCanc = null
                    netSig = null
                    unregisterNetCallback()
                    try { refreshView.alpha = 1f } catch (_: Exception) {}
                    try {
                        if (r != null) {
                            setDownload(r.downloadMbps)
                            setUpload(r.uploadMbps)
                            setPing(r.pingMs)
                        }
                        // On failure the values stay «—»; the refresh
                        // icon is the retry.
                    } catch (_: Exception) {}
                }
            }.apply { isDaemon = true }.start()
        }

        /** Stop a running test at once (dialog dismissed or panel
         *  closed): cancel the run, drop the network callback. */
        fun cancelTest() {
            try {
                canceller?.cancel()
                canceller = null
                gen++
                // 1.75 (F): close the gate — a map settle landing
                // after the cancel must not start a test.
                sequence.cancel()
                pendingGen = -1
                pendingCanc = null
                testing = false
                netSig = null
                unregisterNetCallback()
            } catch (_: Exception) {}
        }

        /** Own IP + geo: primary api.ip.sb/geoip, fallback
         *  ip-api.com (the app's existing alternative). Null when
         *  neither answers with a usable IP. */
        private fun fetchOwnGeo(): SpeedTestPanelPure.GeoInfo? {
            return try {
                val primary = getText(SpeedTestPanelPure.GEO_PRIMARY_URL, 8000, 8000)
                val fromPrimary = primary?.let { SpeedTestPanelPure.parseGeoIpSb(it) }
                if (fromPrimary != null) return fromPrimary
                val fallback = getText(SpeedTestPanelPure.GEO_FALLBACK_URL, 8000, 8000)
                fallback?.let { SpeedTestPanelPure.parseGeoIpApi(it) }
            } catch (_: Exception) { null }
        }

        private fun getText(url: String, connectTimeout: Int, readTimeout: Int): String? {
            var conn: HttpURLConnection? = null
            return try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = connectTimeout
                conn.readTimeout = readTimeout
                conn.setRequestProperty("User-Agent", UA)
                if (conn.responseCode != 200) return null
                conn.inputStream.bufferedReader().readText()
            } catch (_: Exception) { null } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }

        /** Grey out the +/- zoom buttons at the zoom limits
         *  (11 / 16); full opacity otherwise. */
        private fun refreshZoomButtons() {
            try {
                if (!::zoomInBtn.isInitialized) return
                zoomInBtn.alpha = if (mapView.isAtMaxZoom()) 0.35f else 1f
                zoomOutBtn.alpha = if (mapView.isAtMinZoom()) 0.35f else 1f
            } catch (_: Exception) {}
        }

        /** Drop the tile cache, recycling its bitmaps — EXCEPT the
         *  one the view is currently displaying (recycling that
         *  would crash the next draw; it is orphaned instead and
         *  its native memory is freed by the Bitmap finalizer —
         *  at most one such bitmap, panel-lifetime). The view drops
         *  its reference on the next showTiles / clearTiles. */
        private var displayedStitched: Bitmap? = null
        private fun clearTileCache() {
            try {
                for ((_, cached) in tileCache) {
                    try {
                        if (cached.stitched !== displayedStitched &&
                            !cached.stitched.isRecycled
                        ) cached.stitched.recycle()
                    } catch (_: Exception) {}
                }
                tileCache.clear()
            } catch (_: Exception) {}
        }

        /** Load the real map for one (anchor, zoom, pan): the OSM
         *  tiles covering the view PLUS one tile of padding per
         *  side (so the map reads complete, not cropped), stitched
         *  into one bitmap the ZoomableMapView crops + pans.
         *  Any tile failure leaves the plain placeholder (never a
         *  broken image, never fake streets). A cached zoom is
         *  shown at once, no re-fetch. Only the latest load (mapGen)
         *  may display, so a slow zoom-out can never overwrite a
         *  newer zoom-in. Every load settles exactly once through
         *  onMapSettled (1.75, F) — tiles shown, or the placeholder
         *  on any failure — so the speed test waiting on the gate
         *  always starts. */
        private fun loadMapAt(lat: Double, lon: Double, zoom: Int, panX: Double, panY: Double) {
            val key = "$lat|$lon|$zoom|$panX|$panY"
            if (key == mapShownKey) return
            val cached = tileCache[zoom]
            if (cached != null) {
                mapShownKey = key
                try {
                    displayedStitched = cached.stitched
                    mapView.showTiles(cached.stitched, cached.plan, zoom, cached.fetchPanX, cached.fetchPanY)
                    refreshZoomButtons()
                } catch (_: Exception) {}
                // 1.75 (F): cached tiles count as loaded — the map
                // is on screen, so the test may start.
                onMapSettled(true)
                return
            }
            val zoomPlan = SpeedTestPanelPure.planZoomableTiles(
                lat, lon, zoom, mapWpx, mapHpx, panX, panY) ?: run {
                try { mapView.onTilesFailed() } catch (_: Exception) {}
                // 1.75 (F): no usable plan — the map definitively
                // failed (placeholder stays), so the test may start.
                onMapSettled(false)
                return
            }
            val plan = zoomPlan.plan
            val mg = ++mapGen
            Thread {
                try {
                    val opts = BitmapFactory.Options().apply {
                        // Map tiles are opaque: RGB_565 halves the
                        // stitched bitmap's memory (one padded
                        // stitch is ~2.6 MB instead of ~5.2 MB).
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bitmaps = arrayOfNulls<Bitmap>(plan.tiles.size)
                    val latch = CountDownLatch(plan.tiles.size)
                    for ((idx, t) in plan.tiles.withIndex()) {
                        Thread {
                            try {
                                val url = SpeedTestPanelPure.tileUrl(zoom, t.x, t.y)
                                var conn: HttpURLConnection? = null
                                try {
                                    conn = URL(url).openConnection() as HttpURLConnection
                                    conn.connectTimeout = 8000
                                    conn.readTimeout = 8000
                                    conn.setRequestProperty("User-Agent", UA)
                                    if (conn.responseCode == 200) {
                                        bitmaps[idx] = BitmapFactory.decodeStream(conn.inputStream, null, opts)
                                    }
                                } catch (_: Exception) {
                                } finally {
                                    try { conn?.disconnect() } catch (_: Exception) {}
                                }
                            } catch (_: Exception) {
                            } finally {
                                latch.countDown()
                            }
                        }.apply { isDaemon = true }.start()
                    }
                    latch.await()
                    if (bitmaps.any { it == null }) {
                        handler.post {
                            if (mg != mapGen) return@post
                            try { mapView.onTilesFailed() } catch (_: Exception) {}
                            onMapSettled(false)
                        }
                        return@Thread
                    }
                    val stitched = Bitmap.createBitmap(
                        plan.stitchedW, plan.stitchedH, Bitmap.Config.RGB_565)
                    val cv = Canvas(stitched)
                    for ((idx, t) in plan.tiles.withIndex()) {
                        val b = bitmaps[idx] ?: return@Thread
                        cv.drawBitmap(b, t.dx.toFloat(), t.dy.toFloat(), null)
                    }
                    handler.post {
                        if (mg != mapGen) return@post
                        mapShownKey = key
                        // Cache the stitch (max 2 zooms): evict the
                        // eldest NON-displayed entry — never recycle
                        // the bitmap the view is showing.
                        tileCache[zoom] = CachedTiles(stitched, plan, panX, panY)
                        while (tileCache.size > 2) {
                            val victim = tileCache.entries.firstOrNull {
                                it.value.stitched !== displayedStitched
                            } ?: break
                            tileCache.remove(victim.key)
                            try {
                                if (!victim.value.stitched.isRecycled) {
                                    victim.value.stitched.recycle()
                                }
                            } catch (_: Exception) {}
                        }
                        displayedStitched = stitched
                        try {
                            mapView.showTiles(stitched, plan, zoom, panX, panY)
                            refreshZoomButtons()
                        } catch (_: Exception) {}
                        onMapSettled(true)
                    }
                } catch (_: Exception) {
                    // Tiles failed: the placeholder stays.
                    handler.post {
                        if (mg != mapGen) return@post
                        try { mapView.onTilesFailed() } catch (_: Exception) {}
                        onMapSettled(false)
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        /** Signature of the CURRENT default network (verbatim from
         *  the 1.72 home speed-test box): the network object + its
         *  interface + its addresses + whether it is a VPN. Any real
         *  change (VPN on/off, WiFi<->mobile, DHCP IP change)
         *  changes it; a capability flap on the same network
         *  does not. */
        private fun activeNetworkSignature(): String {
            return try {
                val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val net = cm?.activeNetwork ?: return "none"
                val lp = try { cm.getLinkProperties(net) } catch (_: Exception) { null }
                val caps = try { cm.getNetworkCapabilities(net) } catch (_: Exception) { null }
                val addrs = lp?.linkAddresses?.joinToString(",") { it.address.hostAddress ?: "" } ?: ""
                net.toString() + "|" + (lp?.interfaceName ?: "") + "|" + addrs + "|" +
                    (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
            } catch (_: Exception) { "?" }
        }

        /** Watch the default network only while a test runs:
         *  registered at test start, unregistered at test end,
         *  so no callback outlives the panel. */
        private fun registerNetCallback() {
            try {
                unregisterNetCallback()
                val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) { onDefaultNetworkMaybeChanged() }
                    override fun onLost(network: android.net.Network) { onDefaultNetworkMaybeChanged() }
                    override fun onCapabilitiesChanged(network: android.net.Network, caps: NetworkCapabilities) { onDefaultNetworkMaybeChanged() }
                    override fun onLinkPropertiesChanged(network: android.net.Network, lp: android.net.LinkProperties) { onDefaultNetworkMaybeChanged() }
                }
                netCallback = cb
                cm.registerNetworkCallback(
                    android.net.NetworkRequest.Builder().build(), cb)
            } catch (_: Exception) {}
        }

        private fun unregisterNetCallback() {
            try {
                val cb = netCallback ?: return
                netCallback = null
                val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
                cm.unregisterNetworkCallback(cb)
            } catch (_: Exception) {}
        }

        private fun onDefaultNetworkMaybeChanged() {
            if (!testing) return
            val now = try { activeNetworkSignature() } catch (_: Exception) { return }
            val atStart = netSig ?: return
            if (now != atStart) cancelTest()
        }
    }

    /** Open the floating speed-test page over Home. The Dialog's
     *  window is transparent with a 60% dim, centred, side margins
     *  ~24dp; tapping the dimmed area (or ×) dismisses it and
     *  returns to the home page. Opening loads the map first, then starts
 *  the test once the map has settled (1.75, F). */
    fun show(activity: Activity) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d + 0.5f).toInt()
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = Panel(activity, dialog)
        val content = panel.buildContent()
        dialog.setContentView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { panel.cancelTest() }
        val window = dialog.window
        if (window != null) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(SpeedTestPanelPure.DIM_AMOUNT)
            val lp = window.attributes
            lp.width = activity.resources.displayMetrics.widthPixels -
                dp(SpeedTestPanelPure.SIDE_MARGIN_DP) * 2
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.CENTER
            window.attributes = lp
        }
        dialog.show()
        panel.startTest()
    }
}
