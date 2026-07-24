package com.v2ray.ang.weather

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import androidx.core.graphics.ColorUtils
import com.v2ray.ang.R
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * A self-contained animated weather scene sharing the visual language of
 * [com.v2ray.ang.ui.CinematicWorldMapView]: theme-derived palette, soft
 * radial glows, top/bottom edge fades and a Choreographer-driven animation
 * loop that stops rendering while the view is invisible or detached.
 *
 * The scene enters in stages: the sky cross-fades in first, then the
 * celestial layer (sun/moon/clouds) glides into place with a short
 * motion-blur trail, and finally the readout cards rise in.
 */
class CinematicWeatherView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs), Choreographer.FrameCallback {

    private val surfaceColor = resolveThemeColor(com.google.android.material.R.attr.colorSurface)
    private val isLightTheme = ColorUtils.calculateLuminance(surfaceColor) > .5

    // The scene deliberately borrows the world-map palette (ocean/land/accent)
    // so both panes read as one product.  Everything stays in that single
    // muted family — the config list must remain legible on top of it.
    private val oceanColor = if (isLightTheme) Color.rgb(250, 249, 255) else Color.rgb(5, 16, 26)
    private val landColor = if (isLightTheme) Color.rgb(239, 237, 251) else Color.rgb(13, 42, 61)
    private val accentColor = if (isLightTheme) Color.rgb(100, 73, 177) else Color.rgb(94, 238, 255)

    // Sky gradient per weather family: the map's ocean at the top melting into
    // a faint accent-tinted horizon.  Weather changes shift the tint subtly
    // instead of swapping in loud blues/greys.
    private fun skyColors(kind: WeatherKind, isDayTime: Boolean): Pair<Int, Int> {
        val horizonStrength = when (kind) {
            WeatherKind.CLEAR -> if (isDayTime) .30f else .10f
            WeatherKind.PARTLY_CLOUDY -> .24f
            WeatherKind.CLOUDY, WeatherKind.FOG -> .16f
            WeatherKind.DRIZZLE, WeatherKind.RAIN -> .20f
            WeatherKind.SNOW -> .22f
            WeatherKind.THUNDER -> .12f
        }
        val horizon = ColorUtils.blendARGB(landColor, accentColor, horizonStrength * .4f)
        return oceanColor to ColorUtils.blendARGB(oceanColor, horizon, .8f)
    }

    /** One muted ink for every celestial/particle shape, like the map's accent. */
    private val iconColor = ColorUtils.blendARGB(accentColor, if (isLightTheme) Color.BLACK else Color.WHITE, .25f)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgeFadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cardStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f }
    private val skyPaint = Paint()

    private val density = resources.displayMetrics.density
    init {
        setBackgroundColor(Color.TRANSPARENT)
    }


    private val scaledDensity = resources.displayMetrics.scaledDensity

    /** Same ink as the map's country label: dark plum on light, white on dark. */
    private val textColor = if (isLightTheme) Color.rgb(63, 53, 86) else Color.WHITE
    private fun textPaint(sizeSp: Float, bold: Boolean = false, alpha: Int = 255) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(textColor, alpha)
        textSize = sizeSp * scaledDensity
        typeface = if (bold) Typeface.create("sans-serif-light", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val temperaturePaint = textPaint(64f, bold = true, alpha = 190)
    private val conditionPaint = textPaint(17f, bold = true, alpha = 210)
    private val feelsLikePaint = textPaint(13f, alpha = 175)
    private val cityPaint = textPaint(15f, bold = true)
    private val metricValuePaint = textPaint(15f, bold = true, alpha = 210)
    private val metricLabelPaint = textPaint(11f, alpha = 165)
    private val staleBadgePaint = textPaint(11f, alpha = 220)
    private val placeholderPaint = textPaint(14f, alpha = 210).apply { textAlign = Paint.Align.CENTER }
    private val calDatePaint = textPaint(13f, bold = true, alpha = 210)
    private val calHijriPaint = textPaint(11f, alpha = 160)
    private val calOccasionPaint = textPaint(11f, bold = true, alpha = 185)
    private val calDayNumPaint = textPaint(13f, bold = true, alpha = 205)
    private val calWeekdayPaint = textPaint(10f, alpha = 155).apply { textAlign = Paint.Align.CENTER }

    private var snapshot: WeatherSnapshot? = null
    private var placeholderText = context.getString(R.string.weather_waiting_for_location)
    private var pulsePhase = 0f

    // Staged entrance: sky first, then the celestial layer, readout last.
    private var sceneBlend = 0f
    private var iconBlend = 0f
    /** Readout text has its own slower ramp for an extra-soft entrance. */
    private var textBlend = 0f
    private var lastFrameNanos = 0L
    private var lightningTimer = 0f
    private var lightningAlpha = 0f
    private var frameScheduled = false
    private var windowActive = false

    private var calendarDays: List<PersianCalendarHelper.PersianDayInfo> = emptyList()
    private var calendarEpochDay = -1L

    // Particles are pre-seeded with stable random offsets so rain/snow/star
    // fields do not shimmer on re-layout.  Positions are derived, not stored.
    private data class Particle(val x: Float, val phase: Float, val speed: Float, val size: Float)

    private val particles = List(90) {
        val r = Random(it * 7349 + 17)
        Particle(r.nextFloat(), r.nextFloat(), .55f + r.nextFloat() * .9f, .6f + r.nextFloat())
    }
    private data class CloudLayer(
        val widthRatio: Float,
        val heightRatio: Float,
        val topRatio: Float,
        val opacity: Float,
        val durationSeconds: Float,
        val startProgress: Float,
        val lobes: List<FloatArray>
    )

    // CSS reference translated to viewport-relative Android geometry:
    // big / 55s, medium / 75s and small / 90s.
    private val cloudLayers = listOf(
        CloudLayer(.67f, .25f, .10f, .35f, 55f, .30f, listOf(
            floatArrayOf(.30f, .63f, .45f, .75f),
            floatArrayOf(.60f, .47f, .62f, .94f),
            floatArrayOf(.88f, .68f, .43f, .63f)
        )),
        CloudLayer(.48f, .19f, .45f, .25f, 75f, .66f, listOf(
            floatArrayOf(.23f, .58f, .47f, .75f),
            floatArrayOf(.53f, .40f, .67f, 1f),
            floatArrayOf(.88f, .67f, .43f, .67f)
        )),
        CloudLayer(.35f, .14f, .75f, .18f, 90f, .16f, listOf(
            floatArrayOf(.23f, .61f, .46f, .78f),
            floatArrayOf(.66f, .40f, .68f, 1f)
        ))
    )

    fun showWeather(newSnapshot: WeatherSnapshot) {
        val changedKind = snapshot?.weatherCode != newSnapshot.weatherCode || snapshot?.isDay != newSnapshot.isDay
        snapshot = newSnapshot
        if (changedKind) {
            sceneBlend = 0f
            iconBlend = 0f
            textBlend = 0f
        }
        refreshCalendarIfNeeded()
        scheduleFrame()
        invalidate()
    }

    fun showPlaceholder(text: String) {
        placeholderText = text
        invalidate()
    }

    /**
     * Restarts the staged entrance (icons glide in, text drifts down).  The
     * overlay controller calls this on each slide-in, since the view itself
     * stays VISIBLE the whole time in overlay mode.
     */
    fun replayEntrance() {
        sceneBlend = 0f
        iconBlend = 0f
        textBlend = 0f
        refreshCalendarIfNeeded()
        scheduleFrame()
        invalidate()
    }

    private fun refreshCalendarIfNeeded() {
        val today = PersianCalendarHelper.todayEpochDay()
        if (today != calendarEpochDay) {
            calendarEpochDay = today
            calendarDays = PersianCalendarHelper.upcomingDays(5)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        windowActive = windowVisibility == VISIBLE
        refreshCalendarIfNeeded()
        scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        windowActive = false
        Choreographer.getInstance().removeFrameCallback(this)
        frameScheduled = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // The alternation controller toggles visibility every cycle; the frame
        // loop must fully stop while hidden so the map animation keeps its budget.
        if (visibility == VISIBLE) {
            // Re-run the staged entrance on every appearance of the pane.
            sceneBlend = 0f
            iconBlend = 0f
            textBlend = 0f
            refreshCalendarIfNeeded()
            scheduleFrame()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowActive = visibility == VISIBLE
        if (!windowActive) {
            Choreographer.getInstance().removeFrameCallback(this)
            frameScheduled = false
        } else {
            lastFrameNanos = 0L
            scheduleFrame()
        }
    }

    private fun scheduleFrame() {
        if (frameScheduled || !windowActive || !isAttachedToWindow || visibility != VISIBLE) return
        frameScheduled = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (!windowActive) return
        val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceAtMost(.1f)
        lastFrameNanos = frameTimeNanos
        pulsePhase += dt
        sceneBlend = (sceneBlend + dt * 1.5f).coerceAtMost(1f)
        // The celestial layer waits for the sky to be mostly present.
        if (sceneBlend > .55f) iconBlend = (iconBlend + dt * 1.15f).coerceAtMost(1f)
        // Text drifts in last and slowest, easing downward into place.
        if (iconBlend > .35f) textBlend = (textBlend + dt * .8f).coerceAtMost(1f)
        val kind = snapshot?.let { WeatherKind.fromCode(it.weatherCode) }
        if (kind == WeatherKind.THUNDER) {
            lightningTimer -= dt
            if (lightningTimer <= 0f) {
                lightningTimer = 2.5f + Random.nextFloat() * 4f
                lightningAlpha = 1f
            }
            lightningAlpha = (lightningAlpha - dt * 2.4f).coerceAtLeast(0f)
        }
        invalidate()
        if (windowActive && isAttachedToWindow && visibility == VISIBLE) scheduleFrame()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Same edge-fade recipe as the map: surface color at the very top and
        // bottom so the scene melts into the app chrome.
        edgeFadePaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(surfaceColor, Color.TRANSPARENT, surfaceColor),
            floatArrayOf(0f, .18f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        // super.onDraw(canvas) - intentionally skipped: overlay mode, background is transparent
        val data = snapshot
        if (data == null) {
            drawPlaceholder(canvas)
            return
        }
        val kind = WeatherKind.fromCode(data.weatherCode)
        drawSky(canvas, kind, data.isDay)
        val entrance = easeOut(iconBlend)
        when (kind) {
            WeatherKind.CLEAR -> if (data.isDay) drawCelestial(canvas) { drawSun(it, width * .84f, height * .28f, 34f * density) }
            else drawCelestial(canvas) { drawMoonAndStars(it) }
            WeatherKind.PARTLY_CLOUDY -> {
                if (data.isDay) drawCelestial(canvas) { drawSun(it, width * .85f, height * .25f, 26f * density) }
                else drawCelestial(canvas) { drawMoonAndStars(it) }
                drawClouds(canvas, count = 2, alpha = (205 * entrance).toInt())
            }
            WeatherKind.CLOUDY -> drawClouds(canvas, count = 3, alpha = (230 * entrance).toInt())
            WeatherKind.FOG -> { drawClouds(canvas, count = 2, alpha = (130 * entrance).toInt()); if (entrance > .3f) drawFog(canvas) }
            WeatherKind.DRIZZLE -> { drawClouds(canvas, count = 2, alpha = (220 * entrance).toInt()); if (entrance > .5f) drawRain(canvas, intensity = .35f) }
            WeatherKind.RAIN -> { drawClouds(canvas, count = 3, alpha = (235 * entrance).toInt()); if (entrance > .5f) drawRain(canvas, intensity = 1f) }
            WeatherKind.SNOW -> { drawClouds(canvas, count = 2, alpha = (210 * entrance).toInt()); if (entrance > .5f) drawSnow(canvas) }
            WeatherKind.THUNDER -> { drawClouds(canvas, count = 3, alpha = (240 * entrance).toInt()); if (entrance > .5f) { drawRain(canvas, intensity = .8f); drawLightning(canvas) } }
        }
        drawReadout(canvas, data, kind)
    }

    private fun drawSky(canvas: Canvas, kind: WeatherKind, isDayTime: Boolean) {
        // Overlay mode: the world map behind this view is the backdrop.  No
        // gradient or fill is painted, so only the weather information itself
        // fades in.  The brief lightning flash is kept — it is content.
        if (lightningAlpha > 0f) canvas.drawColor(ColorUtils.setAlphaComponent(iconColor, (lightningAlpha * 26).toInt()))
    }

    /** Glides one celestial body into place without motion-blur duplicates. */
    private fun drawCelestial(canvas: Canvas, draw: (Canvas) -> Unit) {
        if (iconBlend <= .01f) return
        val t = easeOut(iconBlend)
        fun offsetX(p: Float) = (1f - p) * 64f * density
        fun offsetY(p: Float) = -(1f - p) * 26f * density
        if (iconBlend < 1f) {
            val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (255 * iconBlend).toInt())
            canvas.translate(offsetX(t), offsetY(t))
            draw(canvas)
            canvas.restoreToCount(save)
        } else {
            draw(canvas)
        }
    }

    private fun drawSun(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val breath = (sin(pulsePhase * 1.2).toFloat() + 1f) / 2f
        val sunColor = iconColor
        glowPaint.shader = RadialGradient(
            centerX, centerY, radius * (3.2f + breath * .5f),
            intArrayOf(ColorUtils.setAlphaComponent(sunColor, 60), ColorUtils.setAlphaComponent(sunColor, 14), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, radius * 3.6f, glowPaint)
        glowPaint.shader = null
        // Slowly rotating rays behind the disc.
        strokePaint.color = ColorUtils.setAlphaComponent(sunColor, 80)
        strokePaint.strokeWidth = 2.4f * density
        for (i in 0 until 8) {
            val angle = pulsePhase * .35f + i * (Math.PI * 2 / 8).toFloat()
            val inner = radius * 1.35f
            val outer = radius * (1.62f + breath * .12f)
            canvas.drawLine(
                centerX + cos(angle) * inner, centerY + sin(angle) * inner,
                centerX + cos(angle) * outer, centerY + sin(angle) * outer, strokePaint
            )
        }
        // A translucent disc with a hairline ring — matte, not glowing.
        fillPaint.color = ColorUtils.setAlphaComponent(sunColor, 90)
        canvas.drawCircle(centerX, centerY, radius, fillPaint)
        strokePaint.color = ColorUtils.setAlphaComponent(sunColor, 130)
        strokePaint.strokeWidth = 1.4f * density
        canvas.drawCircle(centerX, centerY, radius, strokePaint)
    }

    private fun drawMoonAndStars(canvas: Canvas) {
        val centerX = width * .78f
        val centerY = height * .22f
        val radius = 26f * density
        for ((index, star) in particles.take(26).withIndex()) {
            val twinkle = (sin(pulsePhase * (1.1f + star.speed) + star.phase * 9f).toFloat() + 1f) / 2f
            fillPaint.color = ColorUtils.setAlphaComponent(iconColor, (18 + twinkle * 60).toInt())
            canvas.drawCircle(star.x * width, (star.phase * .5f + index * .013f) * height, star.size * 1.4f * density * .55f, fillPaint)
        }
        val sway = sin(pulsePhase * .4).toFloat() * 1.5f * density
        drawMoonVector(canvas, centerX + sway, centerY, radius)
    }

    /**
     * A full-moon vector: soft halo, a shaded sphere via an off-centre radial
     * gradient (terminator toward the lower-left), a hairline limb and three
     * matte craters.  Kept in the single muted ink so it never outshines the
     * config list; the crater relief comes from alpha, not colour.
     */
    private fun drawMoonVector(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // Halo.
        glowPaint.shader = RadialGradient(
            cx, cy, radius * 3.1f,
            intArrayOf(ColorUtils.setAlphaComponent(iconColor, 40), ColorUtils.setAlphaComponent(iconColor, 10), Color.TRANSPARENT),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 3.1f, glowPaint)
        glowPaint.shader = null

        // Calculate actual moon phase
        val phase = realMoonPhase()
        // Phase: 0=new, 0.25=first quarter, 0.5=full, 0.75=last quarter

        // Full moon base (sphere shading)
        fillPaint.shader = RadialGradient(
            cx + radius * .34f, cy - radius * .34f, radius * 1.7f,
            intArrayOf(
                ColorUtils.setAlphaComponent(iconColor, 150),
                ColorUtils.setAlphaComponent(iconColor, 96),
                ColorUtils.setAlphaComponent(iconColor, 54)
            ),
            floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, fillPaint)
        fillPaint.shader = null

        // Draw the dark (unlit) portion by clipping
        val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = skyColors(WeatherKind.CLEAR, false).first
        }

        // Determine terminator: an ellipse that sweeps across the moon
        val sweep = abs(abs(phase - 0.5f) * 2f - 1f) // 0 at new/full, 1 at quarters
        val terminatorWidth = radius * 2f * (1f - sweep * 0.95f)
        val isWaxing = phase < 0.5f
        val terminatorX = (if (isWaxing) phase * 2f else (1f - phase) * 2f) * radius * 2f - radius * 2f

        // Clip to the dark side of the terminator and draw a dark overlay
        canvas.save()
        val path = android.graphics.Path()
        path.addOval(
            cx + terminatorX - terminatorWidth / 2f,
            cy - radius,
            cx + terminatorX + terminatorWidth / 2f,
            cy + radius,
            android.graphics.Path.Direction.CW
        )
        if ((isWaxing && terminatorX < 0) || (!isWaxing && terminatorX >= 0)) {
            // Dark side is to the right of the terminator
            canvas.clipOutPath(path)
        } else {
            canvas.clipPath(path)
        }
        canvas.drawCircle(cx, cy, radius * 1.01f, darkPaint)
        canvas.restore()

        // Limb.
        strokePaint.color = ColorUtils.setAlphaComponent(iconColor, 150)
        strokePaint.strokeWidth = 1.1f * density
        canvas.drawCircle(cx, cy, radius, strokePaint)

        // Craters — darker than the surface via the sky tint, alpha-blended.
        val crater = skyColors(WeatherKind.CLEAR, false).first
        fun crater(dx: Float, dy: Float, cr: Float, alpha: Int) {
            fillPaint.color = ColorUtils.setAlphaComponent(crater, alpha)
            canvas.drawCircle(cx + dx * radius, cy + dy * radius, cr * radius, fillPaint)
            fillPaint.color = ColorUtils.setAlphaComponent(iconColor, (alpha * .5f).toInt())
            canvas.drawCircle(cx + dx * radius - cr * radius * .22f, cy + dy * radius - cr * radius * .22f, cr * radius * .7f, fillPaint)
        }
        crater(-.28f, -.20f, .20f, 70)
        crater(.22f, .10f, .26f, 60)
        crater(-.06f, .40f, .14f, 66)
    }

    private fun cloudPath(cx: Float, cy: Float, scale: Float): Path = Path().apply {
        val r = 38f * density * scale
        // Glass-like cloud: overlapping rounded ovals
        addRoundRect(RectF(cx - r * 1.6f, cy - r * .35f, cx + r * 1.6f, cy + r * .45f), r * .7f, r * .7f, Path.Direction.CW)
        addCircle(cx - r * .7f, cy - r * .15f, r * .45f, Path.Direction.CW)
        addCircle(cx + r * .6f, cy - r * .1f, r * .5f, Path.Direction.CW)
    }

    private fun drawClouds(canvas: Canvas, count: Int, alpha: Int) {
        if (alpha <= 2) return
        cloudLayers.take(count.coerceAtMost(cloudLayers.size)).forEach { cloud ->
            val cloudWidth = width * cloud.widthRatio
            val cloudHeight = width * cloud.heightRatio
            val travel = width + cloudWidth + 200f * density
            val progress =
                (cloud.startProgress + pulsePhase / cloud.durationSeconds) % 1f
            val left = -cloudWidth - 100f * density + progress * travel
            val top = height * cloud.topRatio
            val layerAlpha = (alpha * cloud.opacity).toInt().coerceIn(0, 255)

            cloud.lobes.forEach { lobe ->
                drawCloudOval(
                    canvas,
                    left + cloudWidth * lobe[0],
                    top + cloudHeight * lobe[1],
                    cloudWidth * lobe[2],
                    cloudHeight * lobe[3],
                    layerAlpha
                )
            }
        }
        fillPaint.shader = null
        fillPaint.maskFilter = null
    }

    private fun drawCloudOval(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        ovalWidth: Float,
        ovalHeight: Float,
        alpha: Int
    ) {
        val radius = ovalWidth / 2f
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(1f, ovalHeight / ovalWidth)
        fillPaint.shader = RadialGradient(
            0f,
            0f,
            radius,
            intArrayOf(
                ColorUtils.setAlphaComponent(Color.WHITE, alpha),
                ColorUtils.setAlphaComponent(Color.WHITE, (alpha * .10f).toInt()),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, .70f, 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.maskFilter = BlurMaskFilter(8f * density, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(0f, 0f, radius, fillPaint)
        canvas.restore()
    }

    private fun drawRain(canvas: Canvas, intensity: Float) {
        strokePaint.strokeWidth = 1.7f * density
        val dropCount = (particles.size * intensity).toInt()
        for (drop in particles.take(dropCount)) {
            val fall = ((drop.phase + pulsePhase * drop.speed * .9f) % 1f)
            val y = fall * height
            val x = drop.x * width - fall * 26f * density * .4f
            strokePaint.color = ColorUtils.setAlphaComponent(iconColor, (70 * (1f - fall * .35f)).toInt())
            canvas.drawLine(x, y, x - 3.4f * density, y + 13f * density * drop.size, strokePaint)
        }
    }

    private fun drawSnow(canvas: Canvas) {
        for (flake in particles.take(60)) {
            val fall = ((flake.phase + pulsePhase * flake.speed * .16f) % 1f)
            val sway = sin((pulsePhase * flake.speed + flake.phase * 12f) * 1.6).toFloat() * 14f * density
            fillPaint.color = ColorUtils.setAlphaComponent(iconColor, (85 * (1f - fall * .3f)).toInt())
            canvas.drawCircle(flake.x * width + sway, fall * height, flake.size * 2.1f * density * .8f, fillPaint)
        }
    }

    private fun drawFog(canvas: Canvas) {
        strokePaint.strokeWidth = 7f * density
        for (i in 0 until 4) {
            val y = height * (.42f + i * .1f)
            val sweep = sin(pulsePhase * .5 + i * 1.7).toFloat() * 18f * density
            strokePaint.color = ColorUtils.setAlphaComponent(iconColor, 20 + i * 5)
            canvas.drawLine(width * .08f + sweep, y, width * .92f + sweep, y, strokePaint)
        }
    }

    private fun drawLightning(canvas: Canvas) {
        if (lightningAlpha <= .02f) return
        val bolt = Path().apply {
            val x = width * .32f
            val y = height * .30f
            moveTo(x, y)
            lineTo(x - 9f * density, y + 34f * density)
            lineTo(x + 3f * density, y + 34f * density)
            lineTo(x - 7f * density, y + 66f * density)
            lineTo(x + 15f * density, y + 26f * density)
            lineTo(x + 3f * density, y + 26f * density)
            lineTo(x + 13f * density, y)
            close()
        }
        fillPaint.color = ColorUtils.setAlphaComponent(iconColor, (lightningAlpha * 150).toInt())
        fillPaint.maskFilter = BlurMaskFilter(3f * density, BlurMaskFilter.Blur.SOLID)
        canvas.drawPath(bolt, fillPaint)
        fillPaint.maskFilter = null
    }

    /** The frosted readout: hero temperature, calendar card and metric card. */
    private fun drawReadout(canvas: Canvas, data: WeatherSnapshot, kind: WeatherKind) {
        val reveal = easeOutQuint(textBlend)
        if (reveal <= .02f) return
        // Text enters from above its resting spot and eases gently downward.
        val slide = (1f - reveal) * -30f * density
        val leftEdge = 24f * density

        // City label is grouped with the temperature; no live location pin is drawn.
        val cityLabel = data.city.ifBlank { context.getString(R.string.weather_current_location) }
        cityPaint.alpha = (205 * reveal).toInt()
        cityPaint.textAlign = Paint.Align.RIGHT

        // Hero temperature on the right side — kept muted so config names stay legible over it.
        val rightEdge = width - 24f * density
        val temperatureText = "${data.temperature.roundToInt()}°"
        temperaturePaint.alpha = (190 * reveal).toInt()
        temperaturePaint.textAlign = Paint.Align.RIGHT
        val heroBaseline = height * .40f + slide
        canvas.drawText(temperatureText, rightEdge, heroBaseline, temperaturePaint)
        // Keep the location in the readout stack and away from the sun/moon artwork.
        canvas.drawText(cityLabel, rightEdge, heroBaseline + 69f * density, cityPaint)
        conditionPaint.alpha = (210 * reveal).toInt()
        conditionPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(conditionLabel(kind, data.isDay), rightEdge, heroBaseline + 26f * density, conditionPaint)
        feelsLikePaint.alpha = (175 * reveal).toInt()
        feelsLikePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            context.getString(R.string.weather_feels_like, data.feelsLike.roundToInt()),
            rightEdge, heroBaseline + 46f * density, feelsLikePaint
        )

        // Metrics, right-aligned, above the edge fade.
        val cardWidth = 180f * density
        val cardHeight = 72f * density
        val cardRect = RectF(rightEdge - cardWidth, height - cardHeight - 86f * density + slide, rightEdge, height - 86f * density + slide)
        val columns = listOf(
            context.getString(R.string.weather_humidity) to "${data.humidity}%",
            context.getString(R.string.weather_wind) to context.getString(R.string.weather_wind_value, data.windSpeed.roundToInt()),
            context.getString(R.string.weather_feels_like_label) to "${data.feelsLike.roundToInt()}°"
        )
        val columnWidth = cardRect.width() / columns.size
        for ((index, column) in columns.withIndex()) {
            val cx = cardRect.left + columnWidth * index + columnWidth / 2
            metricValuePaint.alpha = (210 * reveal).toInt()
            metricLabelPaint.alpha = (165 * reveal).toInt()
            val valueWidth = metricValuePaint.measureText(column.second)
            val labelWidth = metricLabelPaint.measureText(column.first)
            canvas.drawText(column.second, cx - valueWidth / 2, cardRect.centerY() - 4f * density, metricValuePaint)
            canvas.drawText(column.first, cx - labelWidth / 2, cardRect.centerY() + 18f * density, metricLabelPaint)
            if (index > 0) {
                strokePaint.color = ColorUtils.setAlphaComponent(textColor, (46 * reveal).toInt())
                strokePaint.strokeWidth = 1f
                canvas.drawLine(cardRect.left + columnWidth * index, cardRect.top + 14f * density, cardRect.left + columnWidth * index, cardRect.bottom - 14f * density, strokePaint)
            }
        }

        drawCalendarCard(canvas, RectF(rightEdge - cardWidth, cardRect.top - 104f * density, rightEdge, cardRect.top - 12f * density), reveal)

        // Offline badge when showing cached data.
        if (data.stale) {
            val badge = context.getString(R.string.weather_offline_cached, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(data.updatedAt)))
            staleBadgePaint.alpha = (200 * reveal).toInt()
            canvas.drawText(
                badge,
                width - leftEdge - staleBadgePaint.measureText(badge),
                heroBaseline - 92f * density,
                staleBadgePaint
            )
        }
    }

    /**
     * Jalali/Hijri calendar card.  Persian reads right-to-left, so today sits
     * at the right edge and upcoming days recede leftward, each dimmer than
     * the last; today carries a small glass marker.
     */
    private fun drawCalendarCard(canvas: Canvas, rect: RectF, reveal: Float) {
        if (calendarDays.isEmpty()) return
        val today = calendarDays.first()
        val pad = 14f * density

        // Header line: Jalali date on the right, Hijri counterpart on the left.
        val headerBaseline = rect.top + pad - calDatePaint.fontMetrics.top
        val jalaliText = "${today.weekdayFull} ${PersianCalendarHelper.toPersianDigits(today.jalaliDay)} ${today.monthName} ${PersianCalendarHelper.toPersianDigits(today.jalaliYear)}"
        calDatePaint.alpha = (255 * reveal).toInt()
        canvas.drawText(jalaliText, rect.right - pad - calDatePaint.measureText(jalaliText), headerBaseline, calDatePaint)
        val hijriText = "${PersianCalendarHelper.toPersianDigits(today.hijriDay)} ${today.hijriMonthName} ${PersianCalendarHelper.toPersianDigits(today.hijriYear)}"
        calHijriPaint.alpha = (195 * reveal).toInt()
        canvas.drawText(hijriText, rect.left + pad, headerBaseline, calHijriPaint)

        // Occasion line under the header, holiday-tinted when applicable.
        val occasion = today.occasion ?: if (today.isFriday) "جمعه" else null
        if (occasion != null) {
            val holidayColor = if (today.isHoliday || today.isFriday) Color.rgb(255, 138, 128) else Color.rgb(255, 213, 128)
            calOccasionPaint.color = ColorUtils.setAlphaComponent(holidayColor, (235 * reveal).toInt())
            canvas.drawText(occasion, rect.right - pad - calOccasionPaint.measureText(occasion), headerBaseline + 17f * density, calOccasionPaint)
        }

        // Five-day strip: right→left, alpha receding into the future.
        val stripTop = rect.top + 46f * density
        val stripBottom = rect.bottom - 8f * density
        val colWidth = (rect.width() - pad * 2) / calendarDays.size
        val dayAlphas = intArrayOf(255, 190, 150, 112, 78)
        for ((index, day) in calendarDays.withIndex()) {
            val cx = rect.right - pad - colWidth * index - colWidth / 2
            val alpha = (dayAlphas.getOrElse(index) { 70 } * reveal).toInt()
            if (index == 0) {
                // Just a tiny accent dot for today.
                fillPaint.color = ColorUtils.setAlphaComponent(textColor, (reveal * 40).toInt())
                canvas.drawCircle(cx, stripBottom - 4f * density, 3f * density, fillPaint)
            }
            val redDay = day.isHoliday || day.isFriday
            calWeekdayPaint.color = ColorUtils.setAlphaComponent(if (redDay) Color.rgb(255, 138, 128) else textColor, (alpha * .8f).toInt())
            canvas.drawText(day.weekdayShort, cx, stripTop + 12f * density, calWeekdayPaint)
            calDayNumPaint.color = ColorUtils.setAlphaComponent(if (redDay) Color.rgb(255, 138, 128) else textColor, alpha)
            canvas.drawText(PersianCalendarHelper.toPersianDigits(day.jalaliDay), cx, stripTop + 30f * density, calDayNumPaint)
        }
    }

    /** Translucent rounded panel with a 1px light border — canvas glassmorphism. */
    private fun drawGlassCard(canvas: Canvas, rect: RectF, radius: Float, alpha: Float) {
        cardPaint.color = if (isLightTheme) Color.argb((alpha * 120).toInt(), 255, 255, 255) else ColorUtils.setAlphaComponent(Color.rgb(17, 37, 55), (alpha * 90).toInt())
        canvas.drawRoundRect(rect, radius, radius, cardPaint)
        cardStrokePaint.color = ColorUtils.setAlphaComponent(if (isLightTheme) textColor else Color.WHITE, (alpha * 50).toInt())
        canvas.drawRoundRect(rect, radius, radius, cardStrokePaint)
    }

    private fun drawPlaceholder(canvas: Canvas) {
        // Overlay mode: no backdrop of its own — just the pulsing status text
        // floating over the map.
        val pulse = (sin(pulsePhase * 2.0).toFloat() + 1f) / 2f
        placeholderPaint.alpha = 150 + (pulse * 90).toInt()
        canvas.drawText(placeholderText, width / 2f, height / 2f, placeholderPaint)
    }

    private fun drawEdgeFade(canvas: Canvas) {
        // Overlay mode: the map underneath already draws its own edge fade;
        // painting another opaque fade here would bring a "background" along
        // with the weather info.
    }

    private fun conditionLabel(kind: WeatherKind, isDayTime: Boolean): String = context.getString(
        when (kind) {
            WeatherKind.CLEAR -> if (isDayTime) R.string.weather_clear_day else R.string.weather_clear_night
            WeatherKind.PARTLY_CLOUDY -> R.string.weather_partly_cloudy
            WeatherKind.CLOUDY -> R.string.weather_cloudy
            WeatherKind.FOG -> R.string.weather_fog
            WeatherKind.DRIZZLE -> R.string.weather_drizzle
            WeatherKind.RAIN -> R.string.weather_rain
            WeatherKind.SNOW -> R.string.weather_snow
            WeatherKind.THUNDER -> R.string.weather_thunder
        }
    )

    private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

    /** A gentler, longer-tailed ease for the text so it settles very softly. */
    private fun easeOutQuint(t: Float): Float {
        val inv = 1f - t
        return 1f - inv * inv * inv * inv * inv
    }

    private fun resolveThemeColor(attribute: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) value.data else Color.rgb(250, 249, 255)
    }

    /** Returns the moon phase for today: 0=new moon, 0.25=first qtr, 0.5=full, 0.75=last qtr. */
    private fun realMoonPhase(): Float {
        // Known new moon: 6 Jan 2000 18:14 UTC (JD 2451549.5)
        val knownNewMoonJD = 2451549.5
        val synodicMonth = 29.530587
        val now = java.util.Calendar.getInstance()
        now.set(java.util.Calendar.HOUR_OF_DAY, 12)
        now.set(java.util.Calendar.MINUTE, 0)
        now.set(java.util.Calendar.SECOND, 0)
        now.set(java.util.Calendar.MILLISECOND, 0)
        val millisInDay = 86400000.0
        // Approximate JD for today
        val jdToday = knownNewMoonJD + (now.timeInMillis - java.util.Date(2000 - 1900, 0, 6, 18, 14).time) / millisInDay
        val daysSince = jdToday - knownNewMoonJD
        val phase = ((daysSince % synodicMonth) / synodicMonth).toFloat()
        return if (phase < 0) phase + 1f else phase
    }
}
