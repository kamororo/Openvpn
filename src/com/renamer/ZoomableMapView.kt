package com.renamer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.sqrt

/** 1.74 (item A): a ZOOMABLE + COMPLETE map view for the «تست سرعت»
 *  floating panel — no map SDK, no new dependency: the same raw
 *  OpenStreetMap raster tiles the 1.73 panel stitched, drawn from a
 *  LARGER stitched bitmap (the view plus one full tile of padding
 *  on every side, see SpeedTestPanelPure.planZoomableTiles), so the
 *  map reads as a complete map, not a cropped partial.
 *
 *  Gestures: pinch-to-zoom (ScaleGestureDetector) + drag-to-pan.
 *  Zooming re-fetches tiles at the new level (range 11–16, default
 *  13) through [MapListener.onZoomStep]; dragging past 200px
 *  re-centres on the lat/lon now at the view centre through
 *  [MapListener.onRecenter]. +/- buttons call [zoomIn]/[zoomOut]
 *  (focus = the pin, so the pin does not jump on screen).
 *
 *  THE PIN IS NEVER A FIXED SCREEN POINT. Its screen position is
 *  always view-centre + pan, where the pan is recomputed from the
 *  TRUE measured lat/lon on every transform (zoom step, drag,
 *  re-centre — see SpeedTestPanelPure.panForZoomChange /
 *  centreLatLonAfterPan), and the tile crop is shifted by the very
 *  same pan, so pin and streets can never disagree.
 *
 *  Deliberately simple about the transient: while a pinch is live,
 *  bitmap + pin are scaled together around the gesture focus; the
 *  moment a zoom step commits, the stale-zoom bitmap is cleared
 *  (placeholder + true pin) until the new level's tiles arrive —
 *  the view never shows old-zoom streets under a new-zoom pin.
 *  A drag re-centre keeps the bitmap (same zoom, still the right
 *  streets around the new centre) and only moves the anchor.
 *
 *  Tile failure still falls back gracefully: with no bitmap the
 *  view draws the plain placeholder fill and the pin on top of it
 *  (never a broken image, never fake streets) — the same fallback
 *  the 1.73 panel had. */
class ZoomableMapView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(ctx, attrs, defStyleAttr) {

    /** The panel implements this: tile (re-)fetching stays in
     *  SpeedTestPanel (it owns the geo, the generation guard and
     *  the tile cache); the view only reports what the user did. */
    interface MapListener {
        /** A zoom step was committed: fetch tiles at [newZoom] for
         *  the same anchor, with the given pan (already adjusted so
         *  the gesture focus stays fixed). */
        fun onZoomStep(newZoom: Int, newPanX: Double, newPanY: Double)

        /** The user dragged past the re-centre distance: the new
         *  anchor is the lat/lon now at the view centre; fetch
         *  tiles for it at the current zoom with pan reset to 0. */
        fun onRecenter(newLat: Double, newLon: Double)
    }

    var listener: MapListener? = null
    var dark: Boolean = false

    // The measured anchor: the lat/lon the map is centred on
    // (before the user's pan). Null until setAnchor.
    private var anchorLat: Double? = null
    private var anchorLon: Double? = null

    // Displayed tiles: the stitched bitmap + the plan it was
    // fetched for. The bitmap is OWNED by the panel's tile cache
    // (never recycled here).
    private var stitched: Bitmap? = null
    private var plan: SpeedTestPanelPure.TilePlan? = null
    // The pan the displayed plan was built for (same zoom as the
    // current zoom whenever a bitmap is shown).
    private var fetchPanX: Double = 0.0
    private var fetchPanY: Double = 0.0

    // Current zoom state + pan (content shift in screen px:
    // dragging the map right by +dx moves the pin right by +dx).
    private var zoom: Int = SpeedTestPanelPure.MAP_ZOOM_DEFAULT
    private var panX: Double = 0.0
    private var panY: Double = 0.0

    // Live pinch state (only while a pinch is in progress; the
    // bitmap + pin are scaled together around the focus, so they
    // can never disagree mid-gesture).
    private var liveScale: Float = 1f
    private var scaleFocusX: Float = 0f
    private var scaleFocusY: Float = 0f
    private var scaling: Boolean = false
    private var recentering: Boolean = false

    // Drag state (single pointer only; a second finger hands over
    // to the pinch detector).
    private var activePointerId: Int = -1
    private var lastX: Float = 0f
    private var lastY: Float = 0f

    private val pinRedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EA4335")
        style = Paint.Style.FILL
    }
    private val pinWhitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val pinShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8EAF0")
    }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaling = true
                scaleFocusX = detector.focusX
                scaleFocusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFocusX = detector.focusX
                scaleFocusY = detector.focusY
                liveScale *= detector.scaleFactor
                // Commit a whole zoom step the moment the pinch
                // passes a full level, so a long pinch keeps
                // stepping instead of stalling at the threshold.
                if (liveScale >= 2f) {
                    if (commitZoom(1)) liveScale = 1f else liveScale = 2f
                } else if (liveScale <= 0.5f) {
                    if (commitZoom(-1)) liveScale = 1f else liveScale = 0.5f
                }
                invalidate()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scaling = false
                // Snap: a clear pinch past half a level commits a
                // step, anything smaller springs back to 1.
                val halfStep = sqrt(2.0).toFloat()
                if (liveScale > halfStep) {
                    commitZoom(1)
                } else if (liveScale < 1f / halfStep) {
                    commitZoom(-1)
                }
                liveScale = 1f
                invalidate()
            }
        })

    /** The measured anchor for this map. Resets zoom to the
     *  default, clears any displayed tiles (they belong to the old
     *  anchor) and the pan. */
    fun setAnchor(lat: Double, lon: Double) {
        anchorLat = lat
        anchorLon = lon
        zoom = SpeedTestPanelPure.MAP_ZOOM_DEFAULT
        panX = 0.0
        panY = 0.0
        fetchPanX = 0.0
        fetchPanY = 0.0
        recentering = false
        liveScale = 1f
        stitched = null
        plan = null
        invalidate()
    }

    /** Show a freshly fetched (or cached) stitched bitmap for the
     *  given zoom and pan. The plan must be the one the bitmap was
     *  stitched for; [fetchPan] is the pan it was built for. The
     *  current pan is KEPT (the user may have dragged while the
     *  fetch was in flight) and clamped into the plan's range, so
     *  the crop can never run off the bitmap. */
    fun showTiles(
        bitmap: Bitmap,
        tilePlan: SpeedTestPanelPure.TilePlan,
        atZoom: Int,
        fetchPanX: Double,
        fetchPanY: Double
    ) {
        stitched = bitmap
        plan = tilePlan
        zoom = atZoom
        this.fetchPanX = fetchPanX
        this.fetchPanY = fetchPanY
        if (width > 0 && height > 0) {
            val clamped = SpeedTestPanelPure.clampPanToPlan(
                panX, panY, fetchPanX, fetchPanY, tilePlan, width, height)
            panX = clamped.first
            panY = clamped.second
        }
        recentering = false
        liveScale = 1f
        invalidate()
    }

    /** Drop the displayed tiles (new test run / panel reset): the
     *  placeholder + pin show until tiles arrive. */
    fun clearTiles() {
        stitched = null
        plan = null
        recentering = false
        liveScale = 1f
        invalidate()
    }

    fun currentZoom(): Int = zoom
    fun isAtMinZoom(): Boolean = zoom <= SpeedTestPanelPure.MAP_ZOOM_MIN
    fun isAtMaxZoom(): Boolean = zoom >= SpeedTestPanelPure.MAP_ZOOM_MAX

    /** +/- button API: zoom one step with the focus on the pin, so
     *  the pin stays exactly where it is on screen. */
    fun zoomIn() {
        focusOnPin()
        commitZoom(1)
        liveScale = 1f
        invalidate()
    }

    fun zoomOut() {
        focusOnPin()
        commitZoom(-1)
        liveScale = 1f
        invalidate()
    }

    private fun focusOnPin() {
        scaleFocusX = (width / 2.0 + panX).toFloat()
        scaleFocusY = (height / 2.0 + panY).toFloat()
    }

    /** Commit one zoom step around the current scale focus.
     *  Returns false when already at the zoom limit (no-op, the
     *  pinch snaps back). The view updates its own zoom + pan
     *  immediately so the pin never jumps, clears the stale-zoom
     *  bitmap (placeholder + true pin until the new tiles land),
     *  and asks the listener to re-fetch the tiles. */
    private fun commitZoom(delta: Int): Boolean {
        val lat = anchorLat ?: return false
        val lon = anchorLon ?: return false
        if (width <= 0 || height <= 0) return false
        val newZoom = SpeedTestPanelPure.clampZoom(zoom + delta)
        if (newZoom == zoom) return false
        val newPan = SpeedTestPanelPure.panForZoomChange(
            lat, lon, zoom, newZoom, width, height,
            panX, panY, scaleFocusX.toDouble(), scaleFocusY.toDouble())
        zoom = newZoom
        panX = newPan.first
        panY = newPan.second
        // Stale-zoom streets must never show under a new-zoom pin.
        stitched = null
        plan = null
        listener?.onZoomStep(newZoom, panX, panY)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger takes over (pinch): stop the drag
                // so the two gestures never fight.
                activePointerId = -1
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaling && activePointerId != -1) {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx >= 0) {
                        val dx = (event.getX(idx) - lastX).toDouble()
                        val dy = (event.getY(idx) - lastY).toDouble()
                        lastX = event.getX(idx)
                        lastY = event.getY(idx)
                        if (dx != 0.0 || dy != 0.0) applyPan(dx, dy)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = -1
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val upId = event.getPointerId(event.actionIndex)
                if (upId == activePointerId) activePointerId = -1
            }
        }
        return true
    }

    private fun applyPan(dx: Double, dy: Double) {
        val lat = anchorLat ?: return
        val lon = anchorLon ?: return
        var nx = panX + dx
        var ny = panY + dy
        val p = plan
        if (p != null && width > 0 && height > 0) {
            val clamped = SpeedTestPanelPure.clampPanToPlan(
                nx, ny, fetchPanX, fetchPanY, p, width, height)
            nx = clamped.first
            ny = clamped.second
        }
        // No tiles to clamp against (loading / failed): leave the
        // pan unclamped — the re-centre below still bounds it, and
        // clamping here would snap a post-zoom pan (which may
        // legitimately exceed 200px) back on the first drag frame.
        panX = nx
        panY = ny
        invalidate()
        // Past the re-centre distance: move the anchor to the
        // lat/lon now at the view centre (once per drag, until the
        // new tiles arrive and clear the flag). The bitmap is KEPT
        // (same zoom, still the right streets around the new
        // centre); only the anchor — and the pin with it — moves.
        if (!recentering && SpeedTestPanelPure.panNeedsRecenter(panX, panY)) {
            val centre = SpeedTestPanelPure.centreLatLonAfterPan(
                lat, lon, zoom, panX, panY) ?: return
            recentering = true
            anchorLat = centre.first
            anchorLon = centre.second
            // The window on screen does NOT move — only the anchor
            // does (the new anchor IS the old view centre) — so the
            // crop origin must stay exactly where it was. The pan
            // resets to 0, therefore the plan's effective fetch pan
            // (relative to the NEW anchor) shifts by the old pan:
            // crop - (0 - (fetchPan - panOld)) == old crop.
            fetchPanX -= panX
            fetchPanY -= panY
            panX = 0.0
            panY = 0.0
            listener?.onRecenter(centre.first, centre.second)
        }
    }

    /** A tile fetch failed (or produced no plan): clear the
     *  re-centre guard so a later drag can re-centre (and retry)
     *  again. The placeholder + pin stay on screen. */
    fun onTilesFailed() {
        recentering = false
        liveScale = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        // The pin's screen position, from the true anchor + the
        // current pan — recomputed on every draw, never stored.
        val pinX0 = w / 2.0 + panX
        val pinY0 = h / 2.0 + panY

        val bitmap = stitched
        val tilePlan = plan
        if (bitmap == null || tilePlan == null) {
            // No tiles (yet / failed / zooming): plain placeholder,
            // pin on top — never a broken image, never fake streets.
            placeholderPaint.color = if (dark) Color.parseColor("#1A2036")
                else Color.parseColor("#E8EAF0")
            canvas.drawRoundRect(
                RectF(0f, 0f, w.toFloat(), h.toFloat()), 16f, 16f, placeholderPaint)
            drawPin(canvas, pinX0.toFloat(), pinY0.toFloat())
            return
        }
        // Crop = the view's window inside the stitched bitmap,
        // shifted by how far the pan moved since the fetch. The
        // pan was clamped on every change, so the crop is always
        // fully inside the bitmap (coerced defensively anyway).
        val dx = (panX - fetchPanX).toInt()
        val dy = (panY - fetchPanY).toInt()
        val srcLeft = (tilePlan.cropX - dx).coerceIn(0, (tilePlan.stitchedW - w).coerceAtLeast(0))
        val srcTop = (tilePlan.cropY - dy).coerceIn(0, (tilePlan.stitchedH - h).coerceAtLeast(0))
        val src = Rect(srcLeft, srcTop, srcLeft + w, srcTop + h)
        val dst = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val scalingNow = scaling || liveScale != 1f
        if (scalingNow) {
            // Live pinch: bitmap + pin scaled together around the
            // gesture focus, so they can never disagree mid-gesture.
            canvas.save()
            canvas.clipRect(dst)
            canvas.translate(scaleFocusX, scaleFocusY)
            canvas.scale(liveScale, liveScale)
            canvas.translate(-scaleFocusX, -scaleFocusY)
            canvas.drawBitmap(bitmap, src, dst, null)
            canvas.restore()
            val pinX = (scaleFocusX + liveScale * (pinX0 - scaleFocusX)).toFloat()
            val pinY = (scaleFocusY + liveScale * (pinY0 - scaleFocusY)).toFloat()
            drawPin(canvas, pinX, pinY)
        } else {
            canvas.save()
            canvas.clipRect(dst)
            canvas.drawBitmap(bitmap, src, dst, null)
            canvas.restore()
            drawPin(canvas, pinX0.toFloat(), pinY0.toFloat())
        }
    }

    /** The red map pin (same teardrop as the 1.73 PinView), drawn
     *  with its tip exactly at (tipX, tipY). */
    private fun drawPin(canvas: Canvas, tipX: Float, tipY: Float) {
        val d = resources.displayMetrics.density
        val r = 11f * d
        val cy = tipY - r * 1.15f
        canvas.drawOval(
            RectF(tipX - r * 0.9f, tipY - 1.5f * d, tipX + r * 0.9f, tipY + 1.5f * d),
            pinShadowPaint)
        val path = Path()
        path.moveTo(tipX, tipY)
        path.lineTo(tipX - r * 0.82f, cy + r * 0.35f)
        path.addArc(RectF(tipX - r, cy - r, tipX + r, cy + r), 160f, 220f)
        path.lineTo(tipX, tipY)
        path.close()
        canvas.drawPath(path, pinRedPaint)
        canvas.drawCircle(tipX, cy, r * 0.38f, pinWhitePaint)
    }
}
