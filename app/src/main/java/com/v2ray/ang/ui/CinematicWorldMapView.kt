package com.v2ray.ang.ui

import android.content.Context
import android.app.ActivityManager
import android.graphics.*
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import androidx.core.graphics.ColorUtils
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/**
 * A self-contained, offline map renderer.  Country geometry is Natural Earth 1:110m
 * public-domain data (bundled in assets) rather than a decorative bitmap.
 *
 * Coastlines are rasterized once into a world texture; only endpoint/trail layers
 * animate. This prevents edge gaps and re-render flashes while the camera moves.
 */
class CinematicWorldMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {
    private data class GeoPoint(val lat: Double, val lon: Double)
    private data class Endpoint(val point: GeoPoint, val country: String, val flag: String)
    private data class Country(val name: String, val path: Path)

    private val countries = mutableListOf<Country>()
    private var atlasRenderNode: RenderNode? = null
    private var atlasPicture: Picture? = null
    private val lowRamMode = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
        ?.isLowRamDevice == true
    private val surfaceColor = resolveThemeColor(com.google.android.material.R.attr.colorSurface)
    private val isLightTheme = ColorUtils.calculateLuminance(surfaceColor) > .5
    private var oceanColor = if (isLightTheme) Color.rgb(250, 249, 255) else Color.rgb(5, 16, 26)
    private var landColor = if (isLightTheme) Color.rgb(239, 237, 251) else Color.rgb(13, 42, 61)
    private var borderColor = if (isLightTheme) Color.rgb(210, 202, 241) else Color.rgb(73, 164, 192)
    private var accentColor = if (isLightTheme) Color.rgb(100, 73, 177) else Color.rgb(94, 238, 255)
    private val activeColor = Color.rgb(132, 242, 112)
    private val coastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = landColor }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 0.7f; color = ColorUtils.setAlphaComponent(borderColor, if (isLightTheme) 165 else 120)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val activationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgeFadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPath = Path()
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isLightTheme) Color.rgb(63, 53, 86) else Color.WHITE
        textSize = 11f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private var camera = GeoPoint(35.69, 51.39) // a neutral default near the user's region
    private var source = camera
    private var destination = camera
    private var markerPosition = camera
    private var markerStart = camera
    private var endpoint = Endpoint(camera, "Iran", "🇮🇷")
    // The label follows the node that is actually visible.  A new destination
    // becomes visible only near arrival, avoiding label/marker teleportation.
    private var pendingEndpoint = endpoint
    private var labelReady = false
    private var cameraZoom = 1.25f
    private var startZoom = cameraZoom
    private var targetZoom = 1.0f
    private var animationStart = 0L
    private var animationDuration = 1L
    private var isAnimating = false
    private enum class MarkerState { IDLE, MOVING, ARRIVING, CONNECTED }
    private var markerState = MarkerState.IDLE
    private var connected = false
    private var connectionBlend = 0f
    private var activationWave = 1f
    private var hasEndpoint = false
    private var lastFrameNanos = 0L
    private var pulsePhase = 0f
    // Geometry is parsed in init(), before Android has measured this View.  It must
    // therefore use a stable world-space scale rather than width/height (which are 0
    // at that time).  A 4096px Mercator world also keeps the camera genuinely zoomed.
    private val worldScale = 4096f
    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        // Preload and parse all Natural Earth vector geometry once. No bitmap
        // tiles or raster snapshots are created at any stage.
        loadCountries()
        preloadVectorAtlas()
    }

    /** Called by MainActivity whenever the selected server changes. */
    fun focusServer(label: String?, host: String?, active: Boolean) {
        setConnectionTarget(active)
        val target = resolveLocation("${label.orEmpty()} ${host.orEmpty()}")
        if (target.point == destination && hasEndpoint) return
        beginTransition(target, active)
    }

    /** Uses a verified IP-geolocation result instead of the fast local name/TLD fallback. */
    fun focusLocation(latitude: Double, longitude: Double, country: String, countryCode: String, active: Boolean) {
        setConnectionTarget(active)
        val target = Endpoint(GeoPoint(latitude, longitude), country, flagFor(countryCode))
        // Service state publishes more than once during a server change.  Compare
        // with the queued endpoint, not the old on-screen label, or Germany →
        // Finland starts the same flight repeatedly before it has arrived.
        if (target.point == destination && pendingEndpoint.country == country && hasEndpoint) return
        beginTransition(target, active)
    }

    /** Removes stale source/server coordinates while a fresh GeoIP lookup runs. */
    fun clearLocation() {
        hasEndpoint = false
        labelReady = false
        isAnimating = false
        markerState = MarkerState.IDLE
        invalidate()
    }

    /** Cancels any in-flight route by starting the next route from the visible state. */
    private fun beginTransition(target: Endpoint, active: Boolean) {
        source = camera
        markerStart = markerPosition
        destination = target.point
        pendingEndpoint = target
        if (!hasEndpoint) labelReady = false
        hasEndpoint = true
        startZoom = cameraZoom
        targetZoom = if (active) 3.0f else .82f
        animationStart = System.nanoTime()
        val distance = haversine(source, destination)
        // A longer camera flight and a delayed marker make the route feel like a
        // camera leading a live signal rather than two objects teleporting.
        animationDuration = (3_000L + (distance / 20_000.0 * 2_200).toLong())
            .coerceIn(3_000L, 5_400L) * 1_000_000
        isAnimating = true
        markerState = MarkerState.MOVING
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun setConnectionTarget(active: Boolean) {
        if (active && !connected) activationWave = 0f
        connected = active
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        alpha = 1f
        lastFrameNanos = 0L
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            alpha = 1f
            lastFrameNanos = 0L
            // A backgrounded hardware window may lose its GPU display list.
            // Re-record from already parsed vector Paths; no file/network reload.
            preloadVectorAtlas()
            invalidate()
            Choreographer.getInstance().removeFrameCallback(this)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (lowRamMode && lastFrameNanos != 0L && frameTimeNanos - lastFrameNanos < 30_000_000L) {
            Choreographer.getInstance().postFrameCallback(this)
            return
        }
        val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f)
        lastFrameNanos = frameTimeNanos
        pulsePhase += dt * 0.9f
        val targetBlend = if (connected) 1f else 0f
        connectionBlend += (targetBlend - connectionBlend) * min(1f, dt * 3.2f)
        if (connected) activationWave = (activationWave + dt * .42f).coerceAtMost(1f)
        if (isAnimating) {
            val raw = ((frameTimeNanos - animationStart).toDouble() / animationDuration).toFloat().coerceIn(0f, 1f)
            val eased = cinematicEase(raw)
            camera = interpolate(source, destination, eased)
            cameraZoom = startZoom + (targetZoom - startZoom) * eased
            val markerRaw = markerProgress(raw)
            markerPosition = interpolate(markerStart, destination, cinematicEase(markerRaw))
            markerState = if (markerRaw < .86f) MarkerState.MOVING else MarkerState.ARRIVING
            if (markerRaw >= .86f) {
                endpoint = pendingEndpoint
                labelReady = true
            }
            if (raw >= 1f) { camera = destination; markerPosition = destination; endpoint = pendingEndpoint; labelReady = true; isAnimating = false; markerState = if (connected) MarkerState.CONNECTED else MarkerState.IDLE }
        }
        invalidate()
        if (isAttachedToWindow && (isAnimating || connected)) Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        edgeFadePaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(surfaceColor, Color.TRANSPARENT, surfaceColor),
            floatArrayOf(0f, .18f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(oceanColor)
        drawVectorMap(canvas)

        val progress = if (isAnimating) ((System.nanoTime() - animationStart).toDouble() / animationDuration).toFloat().coerceIn(0f, 1f) else 1f
        val markerProgress = markerProgress(progress)
        if (isAnimating) drawPacket(canvas, markerProgress)
        // While connecting, do not tint the user's original location green.
        // The live node appears only after it has actually left the source.
        if (hasEndpoint && (!connected || !isAnimating || markerProgress > .055f)) {
            drawEndpoint(canvas, markerPosition, isAnimating)
        }
        drawActivationSignal(canvas)
        drawEdgeFade(canvas)
    }

    /**
     * Pure vector renderer. Geometry is preloaded, while pan and zoom are only
     * GPU matrix transforms. This avoids tile loading and bitmap resampling
     * during the animated marker flight.
     */
    private fun drawVectorMap(canvas: Canvas) {
        val current = project(camera)
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(cameraZoom, cameraZoom)
        canvas.translate(-current.x, -current.y)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canvas.isHardwareAccelerated) {
            atlasRenderNode?.let(canvas::drawRenderNode)
        } else {
            atlasPicture?.let(canvas::drawPicture)
        }
        canvas.restore()
    }

    /**
     * Records the complete vector atlas once. RenderNode/Picture store drawing
     * commands and paths, not pixels, so zoom remains sharp without repeating
     * country-path traversal on the UI thread every frame.
     */
    private fun preloadVectorAtlas() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            atlasRenderNode = RenderNode("classic-vector-atlas").apply {
                setPosition(0, 0, worldScale.toInt(), worldScale.toInt())
                val recordingCanvas = beginRecording()
                for (country in countries) {
                    recordingCanvas.drawPath(country.path, coastPaint)
                    recordingCanvas.drawPath(country.path, borderPaint)
                }
                endRecording()
            }
        } else {
            atlasPicture = Picture().apply {
                val recordingCanvas = beginRecording(worldScale.toInt(), worldScale.toInt())
                for (country in countries) {
                    recordingCanvas.drawPath(country.path, coastPaint)
                    recordingCanvas.drawPath(country.path, borderPaint)
                }
                endRecording()
            }
        }
    }

    private fun drawPacket(canvas: Canvas, raw: Float) {
        // The comet's head and the live endpoint are deliberately the very same
        // position.  Keeping two independently eased heads made a second green
        // dot appear below the destination during a country change.
        val packetT = raw
        val p = screenPoint(markerPosition)
        val tailSteps = 8
        trailPath.reset()
        var tailX = p.x
        var tailY = p.y
        for (i in tailSteps downTo 0) {
            val t = (packetT - i / tailSteps.toFloat() * .28f).coerceAtLeast(0f)
            val a = screenPoint(interpolate(markerStart, destination, cinematicEase(t)))
            if (i == tailSteps) { trailPath.moveTo(a.x, a.y); tailX = a.x; tailY = a.y } else trailPath.lineTo(a.x, a.y)
        }
        linePaint.shader = LinearGradient(tailX, tailY, p.x, p.y, Color.TRANSPARENT, ColorUtils.setAlphaComponent(endpointColor(), 205), Shader.TileMode.CLAMP)
        linePaint.strokeWidth = 2.4f
        canvas.drawPath(trailPath, linePaint)
        linePaint.shader = null
        // A few inexpensive particles give the trail texture without allocating
        // a gradient for every segment.
        glowPaint.color = ColorUtils.setAlphaComponent(endpointColor(), 120)
        if (!lowRamMode) {
            for (i in 3..9 step 3) {
                val t = (packetT - i / tailSteps.toFloat() * .28f).coerceAtLeast(0f)
                val particle = screenPoint(interpolate(markerStart, destination, cinematicEase(t)))
                canvas.drawCircle(particle.x, particle.y, 1.6f, glowPaint)
            }
        }
    }

    private fun drawEndpoint(canvas: Canvas, point: GeoPoint, arriving: Boolean) {
        val p = screenPoint(point)
        val breath = (sin(pulsePhase * Math.PI * 2).toFloat() + 1f) / 2f
        val arrival = if (arriving) ((System.nanoTime() - animationStart).toDouble() / animationDuration).toFloat().coerceIn(0f, 1f) else 1f
        val activeStrength = .72f + connectionBlend * .28f
        val color = endpointColor()
        val base = 18f + breath * 8f
        if (!lowRamMode) {
            glowPaint.shader = RadialGradient(p.x, p.y, base * 3.5f, intArrayOf(ColorUtils.setAlphaComponent(color, (105 * activeStrength).toInt()), ColorUtils.setAlphaComponent(color, 16), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(p.x, p.y, base * 3.2f, glowPaint)
            glowPaint.shader = null
        }
        val ringCount = if (lowRamMode) 1 else 3
        for (ring in 0 until ringCount) {
            val phase = (breath + ring / ringCount.toFloat()) % 1f
            linePaint.color = ColorUtils.setAlphaComponent(color, ((1f - phase) * 155 * arrival * activeStrength).toInt())
            linePaint.strokeWidth = 1.3f
            canvas.drawCircle(p.x, p.y, base + phase * 36f, linePaint)
        }
        glowPaint.color = color; canvas.drawCircle(p.x, p.y, 7f + breath * 2f, glowPaint)
        glowPaint.color = Color.WHITE; canvas.drawCircle(p.x, p.y, 3.5f, glowPaint)
        if (labelReady) drawCountryLabel(canvas, p, color)
    }

    /** Country label is anchored to the live node itself, always on its right. */
    private fun drawCountryLabel(canvas: Canvas, p: PointF, color: Int) {
        val paddingX = 10f * resources.displayMetrics.density
        val paddingY = 6f * resources.displayMetrics.density
        val label = "${endpoint.flag}  ${endpoint.country}"
        val w = labelTextPaint.measureText(label) + paddingX * 2
        val h = labelTextPaint.fontMetrics.run { bottom - top } + paddingY * 2
        val left = (p.x + 22f).coerceAtMost(width - w - 8f)
        val top = (p.y - h / 2f).coerceIn(8f, height - h - 8f)
        glowPaint.color = if (isLightTheme) Color.argb(232, 255, 255, 255) else Color.argb(232, 17, 37, 55)
        canvas.drawRoundRect(left, top, left + w, top + h, 8f, 8f, glowPaint)
        linePaint.color = ColorUtils.setAlphaComponent(color, 105); linePaint.strokeWidth = 1f
        canvas.drawRoundRect(left, top, left + w, top + h, 8f, 8f, linePaint)
        canvas.drawText(label, left + paddingX, top + paddingY - labelTextPaint.fontMetrics.top, labelTextPaint)
    }

    private fun endpointColor() = ColorUtils.blendARGB(accentColor, activeColor, connectionBlend)

    /** One-shot encrypted-network activation wave, then a barely visible connected tint. */
    private fun drawActivationSignal(canvas: Canvas) {
        if (connectionBlend <= .01f || !hasEndpoint) return
        val p = screenPoint(markerPosition)
        val radius = hypot(width.toFloat(), height.toFloat()) * activationWave
        activationPaint.shader = RadialGradient(p.x, p.y, radius.coerceAtLeast(1f), intArrayOf(
            ColorUtils.setAlphaComponent(activeColor, ((1f - activationWave) * 70).toInt()),
            ColorUtils.setAlphaComponent(activeColor, 10), Color.TRANSPARENT
        ), floatArrayOf(0f, .72f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(p.x, p.y, radius, activationPaint)
        activationPaint.shader = null
        activationPaint.color = ColorUtils.setAlphaComponent(activeColor, (connectionBlend * 9).toInt())
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), activationPaint)
    }

    /* private fun drawCountryLabel(canvas: Canvas, p: PointF) {
        val text = "${endpoint.flag}  ${endpoint.country}"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLightTheme) Color.rgb(76, 61, 117) else Color.WHITE
            textSize = 14f * resources.displayMetrics.scaledDensity
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val padX = 13f * resources.displayMetrics.density
        val padY = 8f * resources.displayMetrics.density
        val w = textPaint.measureText(text) + padX * 2
        val h = textPaint.fontMetrics.run { bottom - top } + padY * 2
        // Keep the label out of the server-row action area on the right side.
        // When a destination is already near that area, flip the bubble left of its node.
        val safeRight = width * .60f
        val preferredLeft = p.x + 22f
        val left = if (preferredLeft + w <= safeRight) preferredLeft
            else (p.x - w - 22f).coerceAtLeast(12f)
        val top = (p.y - h - 18f).coerceAtLeast(12f)
        glowPaint.color = if (isLightTheme) Color.argb(238, 255, 255, 255) else Color.argb(228, 17, 37, 55)
        canvas.drawRoundRect(left, top, left + w, top + h, 10f, 10f, glowPaint)
        linePaint.color = ColorUtils.setAlphaComponent(accentColor, 100); linePaint.strokeWidth = 1f
        canvas.drawRoundRect(left, top, left + w, top + h, 10f, 10f, linePaint)
        canvas.drawText(text, left + padX, top + padY - textPaint.fontMetrics.top, textPaint)
    } */

    private fun drawEdgeFade(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), edgeFadePaint)
    }

    private fun loadCountries() {
        try {
            val root = JSONObject(context.assets.open("world_countries_110m.geojson").bufferedReader().readText())
            root.getJSONArray("features").let { features ->
                for (i in 0 until features.length()) {
                    val feature = features.getJSONObject(i); val geometry = feature.getJSONObject("geometry")
                    val path = Path(); appendGeometry(path, geometry)
                    countries += Country(feature.getJSONObject("properties").optString("NAME"), path)
                }
            }
        } catch (_: Exception) { /* Map remains gracefully empty if an asset is damaged. */ }
    }

    private fun appendGeometry(path: Path, geometry: JSONObject) {
        val coords = geometry.getJSONArray("coordinates")
        fun ring(points: JSONArray) {
            for (i in 0 until points.length()) { val p = points.getJSONArray(i); val q = project(GeoPoint(p.getDouble(1), p.getDouble(0))); if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }
            path.close()
        }
        if (geometry.getString("type") == "Polygon") for (i in 0 until coords.length()) ring(coords.getJSONArray(i))
        else for (i in 0 until coords.length()) { val polygon = coords.getJSONArray(i); for (j in 0 until polygon.length()) ring(polygon.getJSONArray(j)) }
    }

    // Coordinates are projected once into world pixels; camera movement is a canvas translation.
    private fun project(p: GeoPoint): PointF {
        val x = ((p.lon + 180.0) / 360.0 * worldScale).toFloat()
        val lat = p.lat.coerceIn(-85.0, 85.0) * Math.PI / 180.0
        val y = ((1.0 - ln(tan(lat) + 1.0 / cos(lat)) / Math.PI) / 2.0 * worldScale).toFloat()
        return PointF(x, y)
    }
    private fun screenPoint(p: GeoPoint): PointF { val a = project(p); val b = project(camera); return PointF(width / 2f + (a.x - b.x) * cameraZoom, height / 2f + (a.y - b.y) * cameraZoom) }
    private fun cinematicEase(t: Float): Float = if (t < .5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f
    /** The camera gets a head start; the live endpoint follows a moment later. */
    private fun markerProgress(cameraProgress: Float): Float = ((cameraProgress - .24f) / .76f).coerceIn(0f, 1f)
    private fun haversine(a: GeoPoint, b: GeoPoint): Double { val dLat = Math.toRadians(b.lat-a.lat); val dLon = Math.toRadians(b.lon-a.lon); val h = sin(dLat/2).pow(2)+cos(Math.toRadians(a.lat))*cos(Math.toRadians(b.lat))*sin(dLon/2).pow(2); return 12742.0 * asin(sqrt(h)) }
    private fun interpolate(a: GeoPoint, b: GeoPoint, t: Float) = GeoPoint(a.lat + (b.lat-a.lat)*t, a.lon + (b.lon-a.lon)*t)

    // Server configs contain no authoritative geographic coordinates.  This conservative offline resolver
    // recognizes common location tags/TLDs; unrecognized endpoints retain a stable neutral focus.
    private fun resolveLocation(text: String): Endpoint {
        val s = text.lowercase()
        val table = listOf(
            "germany|deutschland|\\.de\\b" to Endpoint(GeoPoint(51.16,10.45), "Germany", "🇩🇪"),
            "turkey|türkiye|istanbul|\\.tr\\b" to Endpoint(GeoPoint(39.00,35.32), "Türkiye", "🇹🇷"),
            "iran|tehran|\\.ir\\b" to Endpoint(GeoPoint(32.43,53.69), "Iran", "🇮🇷"),
            "netherlands|amsterdam|\\.nl\\b" to Endpoint(GeoPoint(52.13,5.29), "Netherlands", "🇳🇱"),
            "france|paris|\\.fr\\b" to Endpoint(GeoPoint(46.23,2.21), "France", "🇫🇷"),
            "united kingdom|london|\\.co\\.uk\\b" to Endpoint(GeoPoint(55.38,-3.44), "United Kingdom", "🇬🇧"),
            "united states|usa|new york|los angeles|\\.us\\b" to Endpoint(GeoPoint(39.83,-98.58), "United States", "🇺🇸"),
            "canada|toronto|\\.ca\\b" to Endpoint(GeoPoint(56.13,-106.35), "Canada", "🇨🇦"),
            "japan|tokyo|\\.jp\\b" to Endpoint(GeoPoint(36.20,138.25), "Japan", "🇯🇵"),
            "singapore|\\.sg\\b" to Endpoint(GeoPoint(1.35,103.82), "Singapore", "🇸🇬"),
            "russia|moscow|\\.ru\\b" to Endpoint(GeoPoint(61.52,105.32), "Russia", "🇷🇺"),
            "sweden|\\.se\\b" to Endpoint(GeoPoint(60.13,18.64), "Sweden", "🇸🇪"),
            "finland|\\.fi\\b" to Endpoint(GeoPoint(61.92,25.75), "Finland", "🇫🇮")
        )
        return table.firstOrNull { Regex(it.first).containsMatchIn(s) }?.second ?: Endpoint(GeoPoint(47.5, 15.0), "Europe", "🌐")
    }

    private fun resolveThemeColor(attribute: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) value.data else Color.rgb(250, 249, 255)
    }

    private fun flagFor(countryCode: String): String {
        val code = countryCode.uppercase().takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } } ?: return "🌐"
        return String(Character.toChars(0x1F1E6 + code[0].code - 'A'.code)) + String(Character.toChars(0x1F1E6 + code[1].code - 'A'.code))
    }
}
