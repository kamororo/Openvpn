package com.renamer

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.floor
import kotlin.math.tan

/** 1.73 (worker A): the «تست سرعت» section — pure logic, no Android
 *  imports, so it is JVM-testable standalone (see tests/TestMain173A.kt).
 *
 *  The section replaces the 1.72 home speed-test box (the 1.72 home speed-test box file,
 *  deleted): tapping the new home tile opens a small floating page
 *  (SpeedTestPanel.kt) with a real speed test + real IP/geo + a real
 *  map. The formatting + JSON helpers below are moved VERBATIM from
 *  the 1.72 home speed-test box file; the geo parsing is extended to the five fields the
 *  panel shows (city, postal code, timezone, latitude, longitude).
 *
 *  GEO ENDPOINTS (verified against the providers' own docs):
 *   - PRIMARY: https://api.ip.sb/geoip — the app's own connection-info
 *     endpoint (same one GeoFlag.measureEgress uses). Its response
 *     carries ALL five fields: "city", "postal_code", "timezone",
 *     "latitude", "longitude" (plus "ip", "country", "country_code").
 *     Missing fields are left OUT of the response, never empty.
 *   - FALLBACK: https://ip-api.com/json/?fields=status,country,
 *     countryCode,city,zip,lat,lon,timezone — the app's existing
 *     alternative (GeoFlag.queryIpApi). With no IP in the path it
 *     geo-locates the caller; "zip" is its postal code, "lat"/"lon"
 *     are numbers. Only used when the primary answers nothing usable.
 *  A field the endpoints do not return is shown as «—», never faked.
 *
 *  MAP: OpenStreetMap raster tiles, no SDK, no API key, no WebView:
 *  https://tile.openstreetmap.org/{z}/{x}/{y}.png, zoom 13 (city
 *  streets, as mocked). The panel stitches the 3×2 tiles covering the
 *  map view and crops the exact view-sized region around the measured
 *  lat/lon, so the pin sits at the true location. If any tile fails
 *  to load, the panel falls back to a plain drawn placeholder (never
 *  a broken image, never fake streets).
 *
 *  1.74 (item A): the map is ZOOMABLE + COMPLETE. Zoom range 11–16
 *  (default 13); the stitched area is the view PLUS one full tile
 *  of padding on every side, so the map reads as a complete map, not
 *  a cropped partial, and a short drag never shows a blank edge.
 *  The pin is NEVER pinned to a fixed screen point: its screen
 *  position is recomputed from the true lat/lon on every transform
 *  (zoom step, pan, re-centre) — see ZoomTilePlan.pinX/pinY,
 *  panForZoomChange and centreLatLonAfterPan. Panning past
 *  MAP_PAN_RECENTER_PX re-centres the map on the lat/lon now at the
 *  view centre (the anchor moves, the pin stays true). No new map
 *  SDK dependency: still raw OSM raster tiles + ScaleGestureDetector
 *  in the view. */
object SpeedTestPanelPure {

    // ---------- panel geometry (mockup-measured, variant C floating) ----------
    /** Mockup-measured card size: 310×375dp (variant-c-floating-open,
     *  720px phone = 360dp @2x, measured pixel-by-pixel). */
    const val PANEL_WIDTH_DP = 310
    const val PANEL_HEIGHT_DP = 371
    /** Side margins, each side — the card floats, clearly smaller than
     *  the screen on all four sides. */
    const val SIDE_MARGIN_DP = 24
    /** Map height on top of the card, as mocked. */
    const val MAP_HEIGHT_DP = 110
    /** Corner radius of the floating card, as mocked. */
    const val CORNER_RADIUS_DP = 24
    /** Dim of the home page behind the floating card (~60%). */
    const val DIM_AMOUNT = 0.6f

    // ---------- geo ----------
    const val GEO_PRIMARY_URL = "https://api.ip.sb/geoip"
    /** ip-api.com fallback; no IP in the path = the caller's own IP. */
    const val GEO_FALLBACK_URL =
        "https://ip-api.com/json/?fields=status,country,countryCode,city,zip,lat,lon,timezone"

    /** The measured exit IP + its geo. lat/lon are null when the
     *  endpoint did not return them (then there is no map pin). */
    data class GeoInfo(
        val ip: String,
        val country: String,
        val countryCode: String,
        val city: String,
        val postal: String,
        val timezone: String,
        val lat: Double?,
        val lon: Double?
    )

    /** A value for the UI: blank/missing is «—», never a fake value. */
    fun displayOrDash(s: String?): String {
        val v = s?.trim().orEmpty()
        return if (v.isBlank()) "—" else v
    }

    /** The first string value for "key" in a flat JSON object
     *  ({"ip": "1.2.3.4", ...}), or null. Hand-rolled so the pure
     *  logic stays JVM-testable without org.json; only the flat
     *  top-level string form is needed here. (Verbatim from
     *  the 1.72 box's extractJsonString.) */
    fun extractJsonString(json: String, key: String): String? {
        val needle = "\"$key\""
        var i = json.indexOf(needle)
        while (i >= 0) {
            var j = i + needle.length
            while (j < json.length && json[j].isWhitespace()) j++
            if (j < json.length && json[j] == ':') {
                j++
                while (j < json.length && json[j].isWhitespace()) j++
                if (j < json.length && json[j] == '"') {
                    val out = StringBuilder()
                    j++
                    while (j < json.length) {
                        val c = json[j]
                        if (c == '\\' && j + 1 < json.length) {
                            val n = json[j + 1]
                            when (n) {
                                '"', '\\', '/' -> out.append(n)
                                'n' -> out.append('\n')
                                't' -> out.append('\t')
                                'r' -> out.append('\r')
                                else -> { out.append('\\'); out.append(n) }
                            }
                            j += 2
                        } else if (c == '"') {
                            return out.toString()
                        } else {
                            out.append(c)
                            j++
                        }
                    }
                    return null
                }
                return null
            }
            i = json.indexOf(needle, i + 1)
        }
        return null
    }

    /** The first NUMBER value for "key" in a flat JSON object
     *  ({"latitude": 40.7126, ...}), or null. Numbers are unquoted;
     *  a quoted number is accepted too. */
    fun extractJsonNumber(json: String, key: String): Double? {
        val needle = "\"$key\""
        var i = json.indexOf(needle)
        while (i >= 0) {
            var j = i + needle.length
            while (j < json.length && json[j].isWhitespace()) j++
            if (j < json.length && json[j] == ':') {
                j++
                while (j < json.length && json[j].isWhitespace()) j++
                if (j < json.length && json[j] == '"') {
                    // Quoted number: read the string, then parse it.
                    val s = extractJsonString(json.substring(i), key)
                    return s?.toDoubleOrNull()
                }
                val start = j
                while (j < json.length && (json[j].isDigit() || json[j] in "+-.eE")) j++
                if (j > start) return json.substring(start, j).toDoubleOrNull()
                return null
            }
            i = json.indexOf(needle, i + 1)
        }
        return null
    }

    /** Parse an api.ip.sb/geoip response. Null when the IP is missing
     *  (a response with no usable IP is no result, never partial).
     *  city / postal_code / timezone / latitude / longitude are
     *  optional — absent means "—" / no pin, never a fake value. */
    fun parseGeoIpSb(json: String): GeoInfo? {
        val ip = extractJsonString(json, "ip")?.trim().orEmpty()
        if (ip.isBlank()) return null
        return GeoInfo(
            ip = ip,
            country = extractJsonString(json, "country")?.trim().orEmpty(),
            countryCode = extractJsonString(json, "country_code")?.trim().orEmpty(),
            city = extractJsonString(json, "city")?.trim().orEmpty(),
            postal = extractJsonString(json, "postal_code")?.trim().orEmpty(),
            timezone = extractJsonString(json, "timezone")?.trim().orEmpty(),
            lat = extractJsonNumber(json, "latitude"),
            lon = extractJsonNumber(json, "longitude")
        )
    }

    /** Parse an ip-api.com/json response (fallback). Null when
     *  status != "success" or the IP ("query") is missing. */
    fun parseGeoIpApi(json: String): GeoInfo? {
        if (extractJsonString(json, "status") != "success") return null
        val ip = extractJsonString(json, "query")?.trim().orEmpty()
        if (ip.isBlank()) return null
        return GeoInfo(
            ip = ip,
            country = extractJsonString(json, "country")?.trim().orEmpty(),
            countryCode = extractJsonString(json, "countryCode")?.trim().orEmpty(),
            city = extractJsonString(json, "city")?.trim().orEmpty(),
            postal = extractJsonString(json, "zip")?.trim().orEmpty(),
            timezone = extractJsonString(json, "timezone")?.trim().orEmpty(),
            lat = extractJsonNumber(json, "lat"),
            lon = extractJsonNumber(json, "lon")
        )
    }

    // ---------- formatting (verbatim from the 1.72 home speed-test box) ----------

    /** Latin digits → Persian digits (same mapping as
     *  QuickConnect.faDigits). */
    fun faDigits(s: String): String {
        val fa = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        val out = StringBuilder()
        for (c in s) out.append(if (c in '0'..'9') fa[c - '0'] else c)
        return out.toString()
    }

    /** One-decimal number, Persian digits + «٫» separator in
     *  Persian mode («۴۵٫۲»), Latin + «.» otherwise («45.2»). */
    fun formatNumber(value: Double, persian: Boolean): String {
        val s = String.format(java.util.Locale.US, "%.1f", value)
        return if (persian) faDigits(s).replace('.', '٫') else s
    }

    /** A speed value as (number, unit) for the UI: («۴۵٫۲», «Mbps»)
     *  or («—», «») before the first test / after a failure.
     *  The unit stays Latin in both modes, as mocked. */
    fun formatSpeedParts(mbps: Double?, persian: Boolean): Pair<String, String> {
        if (mbps == null || mbps.isNaN()) return "—" to ""
        return formatNumber(mbps, persian) to "Mbps"
    }

    /** A ping value as (number, unit): («۳۸», «ms») or («—», «»). */
    fun formatPingParts(ms: Long?, persian: Boolean): Pair<String, String> {
        if (ms == null || ms < 0) return "—" to ""
        val n = if (persian) faDigits(ms.toString()) else ms.toString()
        return n to "ms"
    }

    /** Persian country name for a country code, falling back to
     *  the endpoint's English name for an unknown code, and to
     *  «—» when there is no name at all. Spellings are
     *  CloudflareActivity.FA_NAMES'. In English mode the
     *  English name is always used. */
    fun countryFa(cc: String, english: String, persian: Boolean): String {
        val en = english.trim()
        if (!persian) return if (en.isBlank()) "—" else en
        val fa = FA_NAMES[cc.trim().lowercase()]
        if (fa != null) return fa
        return if (en.isBlank()) "—" else en
    }

    /** Persian country names (CloudflareActivity.FA_NAMES). */
    private val FA_NAMES = mapOf("us" to "آمریکا", "gb" to "بریتانیا", "de" to "آلمان", "nl" to "هلند", "fr" to "فرانسه", "it" to "ایتالیا", "es" to "اسپانیا", "se" to "سوئد", "no" to "نروژ", "dk" to "دانمارک", "fi" to "فنلاند", "pl" to "لهستان", "ch" to "سوئیس", "at" to "اتریش", "be" to "بلژیک", "ie" to "ایرلند", "pt" to "پرتغال", "cz" to "چک", "sk" to "اسلواکی", "gr" to "یونان", "tr" to "ترکیه", "ru" to "روسیه", "ua" to "اوکراین", "ca" to "کانادا", "mx" to "مکزیک", "br" to "برزیل", "ar" to "آرژانتین", "cl" to "شیلی", "co" to "کلمبیا", "pe" to "پرو", "au" to "استرالیا", "nz" to "نیوزیلند", "jp" to "ژاپن", "kr" to "کره جنوبی", "sg" to "سنگاپور", "hk" to "هنگ‌کنگ", "tw" to "تایوان", "in" to "هند", "id" to "اندونزی", "ph" to "فیلیپین", "vn" to "ویتنام", "th" to "تایلند", "my" to "مالزی", "ae" to "امارات", "sa" to "عربستان", "il" to "اسرائیل", "eg" to "مصر", "za" to "آفریقای جنوبی", "cn" to "چین", "kz" to "قزاقستان", "pk" to "پاکستان", "bd" to "بنگلادش", "ir" to "ایران", "iq" to "عراق", "af" to "افغانستان", "ge" to "گرجستان", "am" to "ارمنستان", "az" to "آذربایجان", "by" to "بلاروس", "hu" to "مجارستان", "ro" to "رومانی", "bg" to "بلغارستان", "hr" to "کرواسی", "si" to "اسلوونی", "rs" to "صربستان", "ee" to "استونی", "lv" to "لتونی", "lt" to "لیتوانی", "is" to "ایسلند", "lu" to "لوکزامبورگ", "mt" to "مالت", "cy" to "قبرس", "jo" to "اردن", "lb" to "لبنان", "kw" to "کویت", "qa" to "قطر", "bh" to "بحرین", "om" to "عمان")

    // ---------- map tiles (OpenStreetMap, no SDK / key / WebView) ----------

    /** City-street zoom, as mocked. */
    const val MAP_ZOOM = 13
    const val TILE_SIZE_PX = 256
    private const val TILE_URL_PATTERN = "https://tile.openstreetmap.org/%d/%d/%d.png"

    fun tileUrl(z: Int, x: Int, y: Int): String =
        TILE_URL_PATTERN.format(java.util.Locale.US, z, x, y)

    /** Global pixel coordinates of a lat/lon at a zoom (Slippy-map
     *  maths: x = (lon+180)/360 * 2^z * 256, y from the Mercator
     *  projection). Null for out-of-range coordinates. */
    fun lonLatToPixel(lat: Double, lon: Double, zoom: Int): Pair<Double, Double>? {
        if (lat < -85.05112878 || lat > 85.05112878 || lon < -180.0 || lon > 180.0) return null
        val n = (1 shl zoom).toDouble() * TILE_SIZE_PX
        val x = (lon + 180.0) / 360.0 * n
        val latRad = lat * PI / 180.0
        val y = (1.0 - asinh(tan(latRad)) / PI) / 2.0 * n
        return x to y
    }

    /** One tile of the stitched map: its x/y plus where its top-left
     *  lands inside the stitched bitmap (dx, dy in px). */
    data class TileFetch(val x: Int, val y: Int, val dx: Int, val dy: Int)

    /** The tile plan for a map view: which tiles to fetch, where each
     *  lands in the stitched bitmap, and the crop rectangle that
     *  centres the view exactly on the measured lat/lon. The stitched
     *  bitmap is (tilesX * 256) × (tilesY * 256); the crop is always
     *  fully inside it. Null when the coordinates are unusable. */
    data class TilePlan(
        val tiles: List<TileFetch>,
        val stitchedW: Int,
        val stitchedH: Int,
        val cropX: Int,
        val cropY: Int,
        val cropW: Int,
        val cropH: Int
    )

    fun planTiles(lat: Double, lon: Double, zoom: Int, viewWpx: Int, viewHpx: Int): TilePlan? {
        if (viewWpx <= 0 || viewHpx <= 0 || zoom < 0 || zoom > 19) return null
        val (cx, cy) = lonLatToPixel(lat, lon, zoom) ?: return null
        val nTiles = 1 shl zoom
        // Pixel range the view must cover, centred on the point.
        val left = cx - viewWpx / 2.0
        val top = cy - viewHpx / 2.0
        val right = cx + viewWpx / 2.0
        val bottom = cy + viewHpx / 2.0
        val x0 = floor(left / TILE_SIZE_PX).toInt()
        val x1 = floor(right / TILE_SIZE_PX).toInt()
        val y0 = floor(top / TILE_SIZE_PX).toInt()
        val y1 = floor(bottom / TILE_SIZE_PX).toInt()
        if (y0 < 0 || y1 >= nTiles) return null
        val tiles = ArrayList<TileFetch>()
        for (ty in y0..y1) {
            for (tx in x0..x1) {
                // Longitude wraps: tile x wraps around the world.
                val wrapped = ((tx % nTiles) + nTiles) % nTiles
                tiles += TileFetch(wrapped, ty, (tx - x0) * TILE_SIZE_PX, (ty - y0) * TILE_SIZE_PX)
            }
        }
        val stitchedW = (x1 - x0 + 1) * TILE_SIZE_PX
        val stitchedH = (y1 - y0 + 1) * TILE_SIZE_PX
        // Crop origin = view's top-left inside the stitched bitmap.
        val ox = (left - x0 * TILE_SIZE_PX).toInt()
        val oy = (top - y0 * TILE_SIZE_PX).toInt()
        return TilePlan(tiles, stitchedW, stitchedH, ox, oy, viewWpx, viewHpx)
    }

    // ---------- zoomable map (1.74 item A) ----------
    // Zoom range: 11 (region) .. 16 (street detail); 13 is the
    // 1.73 default and stays the default. Zooming re-fetches tiles
    // at the new level; the view never upscales one zoom's tiles and
    // calls it another zoom (except as a transient visual while the
    // new tiles load — the pin is always placed from the true
    // lat/lon, never from a scaled guess).

    /** Zoom limits + default (MAP_ZOOM == default, kept for 1.73
     *  callers). */
    const val MAP_ZOOM_MIN = 11
    const val MAP_ZOOM_MAX = 16
    const val MAP_ZOOM_DEFAULT = 13

    /** Tiles of padding around the view on every side: the stitched
     *  bitmap is the view plus this ring, so the map reads complete
     *  and a short drag never exposes a blank edge. */
    const val MAP_PAD_TILES = 1

    /** Drag distance (px, either axis) past which the map re-centres
     *  on the lat/lon now at the view centre instead of dragging
     *  further. Safely inside the padded area (a 1-tile pad gives
     *  >=220px of drag room in every direction for this panel's
     *  view size), so re-centring never shows a blank edge. */
    const val MAP_PAN_RECENTER_PX = 200

    /** A zoom clamped into the supported range. */
    fun clampZoom(z: Int): Int = z.coerceIn(MAP_ZOOM_MIN, MAP_ZOOM_MAX)

    /** The Slippy tile (x, y) containing a lat/lon at a zoom.
     *  Null for out-of-range coordinates. */
    fun tileXY(lat: Double, lon: Double, zoom: Int): Pair<Int, Int>? {
        val (px, py) = lonLatToPixel(lat, lon, zoom) ?: return null
        return floor(px / TILE_SIZE_PX).toInt() to floor(py / TILE_SIZE_PX).toInt()
    }

    /** Inverse Mercator: global pixel coordinates -> lat/lon at a
     *  zoom. Null for out-of-range pixels. Used when re-centring
     *  after a pan (the new anchor is the lat/lon now at the view
     *  centre). */
    fun pixelToLonLat(px: Double, py: Double, zoom: Int): Pair<Double, Double>? {
        if (zoom < 0 || zoom > 19) return null
        val n = (1 shl zoom).toDouble() * TILE_SIZE_PX
        if (px < 0.0 || px > n || py < 0.0 || py > n) return null
        val lon = px / n * 360.0 - 180.0
        val latRad = kotlin.math.atan(kotlin.math.sinh(PI * (1.0 - 2.0 * py / n)))
        return latRad * 180.0 / PI to lon
    }

    /** A zoomable tile plan: like planTiles, but the fetched area is
     *  the view PLUS MAP_PAD_TILES of padding on every side, and the
     *  plan carries the pin's position inside the cropped view.
     *
     *  Pan convention: panX/panY shift the MAP CONTENT in screen px
     *  relative to the centred anchor (dragging the map right by
     *  +dx moves the pin right by +dx). The crop origin follows the
     *  window (left = anchorPx - view/2 - pan), so the crop is
     *  always fully inside the stitched bitmap for ANY pan — the
     *  fetch range is built around the panned window.
     *
     *  pinX/pinY are computed the long way — tile offset + the
     *  point's position inside its tile, minus the crop origin —
     *  so they track the TRUE lat/lon, not a stored screen point.
     *  For the anchor itself pinX == viewW/2 + panX (and likewise
     *  Y) at every zoom; the test asserts both the identity and the
     *  independent tile-math recomputation. */
    data class ZoomTilePlan(
        val plan: TilePlan,
        /** Pin tip position inside the cropped view, px. */
        val pinX: Double,
        val pinY: Double
    )

    fun planZoomableTiles(
        lat: Double, lon: Double, zoom: Int,
        viewWpx: Int, viewHpx: Int,
        panXPx: Double = 0.0, panYPx: Double = 0.0,
        padTiles: Int = MAP_PAD_TILES
    ): ZoomTilePlan? {
        if (viewWpx <= 0 || viewHpx <= 0 || zoom < 0 || zoom > 19 || padTiles < 0) return null
        val (cx, cy) = lonLatToPixel(lat, lon, zoom) ?: return null
        val nTiles = 1 shl zoom
        // Window the view must cover, centred on the anchor, shifted
        // by the pan (content shift: window moves opposite).
        val left = cx - viewWpx / 2.0 - panXPx
        val top = cy - viewHpx / 2.0 - panYPx
        val right = cx + viewWpx / 2.0 - panXPx
        val bottom = cy + viewHpx / 2.0 - panYPx
        val padPx = padTiles * TILE_SIZE_PX
        val x0 = floor((left - padPx) / TILE_SIZE_PX).toInt()
        val x1 = floor((right + padPx) / TILE_SIZE_PX).toInt()
        val y0 = floor((top - padPx) / TILE_SIZE_PX).toInt()
        val y1 = floor((bottom + padPx) / TILE_SIZE_PX).toInt()
        if (y0 < 0 || y1 >= nTiles) return null
        val tiles = ArrayList<TileFetch>()
        for (ty in y0..y1) {
            for (tx in x0..x1) {
                val wrapped = ((tx % nTiles) + nTiles) % nTiles
                tiles += TileFetch(wrapped, ty, (tx - x0) * TILE_SIZE_PX, (ty - y0) * TILE_SIZE_PX)
            }
        }
        val stitchedW = (x1 - x0 + 1) * TILE_SIZE_PX
        val stitchedH = (y1 - y0 + 1) * TILE_SIZE_PX
        // Crop origin (Double precision; TilePlan stores the Int
        // truncation — under 1px apart, invisible).
        val cropXD = left - x0 * TILE_SIZE_PX
        val cropYD = top - y0 * TILE_SIZE_PX
        val base = TilePlan(tiles, stitchedW, stitchedH, cropXD.toInt(), cropYD.toInt(), viewWpx, viewHpx)
        // Pin via tile math: its tile's offset in the stitched
        // bitmap + its position inside that tile, minus the crop.
        val (pinTileX, pinTileY) = tileXY(lat, lon, zoom) ?: return null
        val pinTile = tiles.firstOrNull { it.x == pinTileX && it.y == pinTileY } ?: return null
        val pinInStitchedX = pinTile.dx + (cx - pinTileX * TILE_SIZE_PX)
        val pinInStitchedY = pinTile.dy + (cy - pinTileY * TILE_SIZE_PX)
        return ZoomTilePlan(base, pinInStitchedX - cropXD, pinInStitchedY - cropYD)
    }

    /** The pin's screen position for an anchor lat/lon, computed
     *  straight from the world pixels (the identity the zoomable
     *  plan must satisfy: centre + pan, at every zoom). */
    fun pinScreenPosition(
        lat: Double, lon: Double, zoom: Int,
        viewWpx: Int, viewHpx: Int,
        panXPx: Double = 0.0, panYPx: Double = 0.0
    ): Pair<Double, Double>? {
        if (lonLatToPixel(lat, lon, zoom) == null) return null
        return viewWpx / 2.0 + panXPx to viewHpx / 2.0 + panYPx
    }

    /** The pan that keeps the world point under a gesture focus
     *  (focusX, focusY in view px) exactly under that focus after a
     *  zoom step from oldZoom to newZoom. Derivation: screen pos of
     *  a world point w is w(z) - anchor(z) + view/2 + pan, and
     *  w(z') = w(z) * 2^(z'-z); solving for the pan that leaves the
     *  focus point's screen pos unchanged gives
     *  pan' = (f - view/2) - (f - view/2 - pan) * 2^(z'-z).
     *  NOTE: with the focus at the view centre the pan SCALES by
     *  2^delta (a pin off-centre is twice as far off-centre at one
     *  zoom in) — it does NOT stay equal. With the focus ON the
     *  pin, pan' == pan and the pin does not move on screen. */
    fun panForZoomChange(
        anchorLat: Double, anchorLon: Double,
        oldZoom: Int, newZoom: Int,
        viewWpx: Int, viewHpx: Int,
        panX: Double, panY: Double,
        focusX: Double, focusY: Double
    ): Pair<Double, Double> {
        // The anchor itself is unused beyond validation: the
        // transform is purely geometric once the focus is known.
        if (lonLatToPixel(anchorLat, anchorLon, oldZoom) == null ||
            lonLatToPixel(anchorLat, anchorLon, newZoom) == null
        ) return panX to panY
        val scale = Math.pow(2.0, (newZoom - oldZoom).toDouble())
        val newPanX = (focusX - viewWpx / 2.0) - (focusX - viewWpx / 2.0 - panX) * scale
        val newPanY = (focusY - viewHpx / 2.0) - (focusY - viewHpx / 2.0 - panY) * scale
        return newPanX to newPanY
    }

    /** The lat/lon now at the view centre after panning by
     *  (panX, panY): the centre shows the world pixel
     *  (anchorPx - pan). Null when the anchor or the result is
     *  out of range. */
    fun centreLatLonAfterPan(
        lat: Double, lon: Double, zoom: Int,
        panXPx: Double, panYPx: Double
    ): Pair<Double, Double>? {
        val (cx, cy) = lonLatToPixel(lat, lon, zoom) ?: return null
        return pixelToLonLat(cx - panXPx, cy - panYPx, zoom)
    }

    /** True when a pan has gone far enough to re-centre the map
     *  (the anchor moves to the lat/lon now at the view centre). */
    fun panNeedsRecenter(panXPx: Double, panYPx: Double): Boolean =
        kotlin.math.abs(panXPx) > MAP_PAN_RECENTER_PX ||
            kotlin.math.abs(panYPx) > MAP_PAN_RECENTER_PX

    /** Clamp a pan so the cropped view stays fully inside a
     *  stitched bitmap fetched for (fetchPanX, fetchPanY).
     *  The crop origin for pan p is plan.crop - (p - fetchPan);
     *  it must stay in [0, stitched - view] on both axes. Returns
     *  the clamped pan (unchanged when already inside). */
    fun clampPanToPlan(
        panX: Double, panY: Double,
        fetchPanX: Double, fetchPanY: Double,
        plan: TilePlan, viewWpx: Int, viewHpx: Int
    ): Pair<Double, Double> {
        fun clampAxis(pan: Double, fetchPan: Double, crop: Int, stitched: Int, view: Int): Double {
            val slack = stitched - view // >= 0 for a padded plan
            // crop - (pan - fetchPan) in [0, slack]
            val lo = fetchPan + crop - slack
            val hi = fetchPan + crop
            return pan.coerceIn(lo.toDouble(), hi.toDouble())
        }
        return clampAxis(panX, fetchPanX, plan.cropX, plan.stitchedW, viewWpx) to
            clampAxis(panY, fetchPanY, plan.cropY, plan.stitchedH, viewHpx)
    }
}
