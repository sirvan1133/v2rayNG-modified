package com.v2ray.ang.market

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import com.v2ray.ang.R
import java.text.NumberFormat
import java.util.Locale

data class MarketRate(val id: String, val label: String, val toman: Long)

class MarketRatesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val surface = themeColor(com.google.android.material.R.attr.colorSurface)
    private val lightTheme = ColorUtils.calculateLuminance(surface) > .5
    private val primary = if (lightTheme) Color.rgb(43, 42, 55) else Color.WHITE
    private val secondary = ColorUtils.setAlphaComponent(primary, 160)
    private val accent = if (lightTheme) Color.rgb(65, 102, 177) else Color.rgb(101, 209, 255)
    private val cardColor = if (lightTheme) Color.argb(38, 75, 83, 112) else Color.argb(42, 255, 255, 255)
    private val borderColor = ColorUtils.setAlphaComponent(accent, 75)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numberFormat = NumberFormat.getIntegerInstance(Locale.US)
    private var rows = emptyList<MarketRate>()
    private var stale = false

    fun submit(value: List<MarketRate>, isStale: Boolean = false) {
        rows = value
        stale = isStale
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (rows.isEmpty()) return
        val widthPx = 218f * density
        // Weather owns the right side; prices stay left so the two scenes never overlap.
        val left = 18f * density
        var top = height * .19f

        drawHeader(canvas, left, top)
        top += 34f * density
        rows.forEach { rate ->
            drawRateCard(canvas, rate, left, top, widthPx)
            top += 48f * density
        }

        paint.typeface = Typeface.DEFAULT
        paint.textSize = 9f * scaledDensity
        paint.color = secondary
        val footer = if (stale) context.getString(R.string.market_cached)
        else context.getString(R.string.market_source)
        canvas.drawText(footer, left + 4f * density, top + 3f * density, paint)
    }

    private fun drawHeader(canvas: Canvas, left: Float, centerY: Float) {
        val iconCenter = left + 14f * density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.7f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = accent
        canvas.drawCircle(iconCenter, centerY, 12f * density, paint)
        canvas.drawPath(Path().apply {
            moveTo(iconCenter - 7f * density, centerY + 4f * density)
            lineTo(iconCenter - 2f * density, centerY - 1f * density)
            lineTo(iconCenter + 3f * density, centerY + 2f * density)
            lineTo(iconCenter + 8f * density, centerY - 6f * density)
        }, paint)
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 13f * scaledDensity
        paint.color = primary
        canvas.drawText(context.getString(R.string.market_today), left + 34f * density, centerY + 5f * density, paint)
    }

    private fun drawRateCard(canvas: Canvas, rate: MarketRate, left: Float, top: Float, widthPx: Float) {
        val rect = RectF(left, top, left + widthPx, top + 40f * density)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            left, top, left + widthPx, top,
            intArrayOf(cardColor, ColorUtils.setAlphaComponent(cardColor, 18), Color.TRANSPARENT),
            floatArrayOf(0f, .62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, 11f * density, 11f * density, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = .8f * density
        paint.shader = LinearGradient(
            left, top, left + widthPx, top,
            borderColor, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, 11f * density, 11f * density, paint)
        paint.shader = null
        paint.style = Paint.Style.FILL

        val iconX = left + 21f * density
        val iconY = top + 20f * density
        paint.color = ColorUtils.setAlphaComponent(assetColor(rate.id), if (lightTheme) 38 else 52)
        canvas.drawCircle(iconX, iconY, 13f * density, paint)
        drawAssetIcon(canvas, rate.id, iconX, iconY, assetColor(rate.id))

        paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 11f * scaledDensity
        paint.color = primary
        canvas.drawText(localizedLabel(rate.id), left + 42f * density, top + 16f * density, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 10.5f * scaledDensity
        paint.color = secondary
        val value = context.getString(R.string.market_toman, numberFormat.format(rate.toman))
        canvas.drawText(value, left + 42f * density, top + 32f * density, paint)
    }

    private fun drawAssetIcon(canvas: Canvas, id: String, x: Float, y: Float, color: Int) {
        paint.color = color
        paint.strokeWidth = 1.6f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        when (id) {
            "gold" -> {
                val path = Path().apply {
                    moveTo(x - 7f * density, y + 5f * density)
                    lineTo(x - 4f * density, y - 5f * density)
                    lineTo(x + 4f * density, y - 5f * density)
                    lineTo(x + 7f * density, y + 5f * density)
                    close()
                }
                canvas.drawPath(path, paint)
                canvas.drawLine(x - 7f * density, y + 5f * density, x + 7f * density, y + 5f * density, paint)
            }
            "eur" -> drawSymbol(canvas, "€", x, y, color)
            "gbp" -> drawSymbol(canvas, "£", x, y, color)
            "try" -> drawSymbol(canvas, "₺", x, y, color)
            "iqd" -> drawIqdLogo(canvas, x, y, color)
            else -> drawSymbol(canvas, "$", x, y, color)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawSymbol(canvas: Canvas, symbol: String, x: Float, y: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 14f * scaledDensity
        paint.textAlign = Paint.Align.CENTER
        val baseline = y - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(symbol, x, baseline, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawIqdLogo(canvas: Canvas, x: Float, y: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.25f * density
        paint.color = color
        canvas.drawRoundRect(
            x - 9f * density, y - 6f * density,
            x + 9f * density, y + 6f * density,
            3f * density, 3f * density, paint
        )
        paint.style = Paint.Style.FILL
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 6.5f * scaledDensity
        paint.textAlign = Paint.Align.CENTER
        val baseline = y - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText("IQD", x, baseline, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun localizedLabel(id: String) = context.getString(
        when (id) {
            "eur" -> R.string.asset_eur
            "gold" -> R.string.asset_gold
            "gbp" -> R.string.asset_gbp
            "try" -> R.string.asset_try
            "iqd" -> R.string.asset_iqd
            else -> R.string.asset_usd
        }
    )

    private fun assetColor(id: String) = when (id) {
        "eur" -> Color.rgb(90, 137, 255)
        "gold" -> Color.rgb(239, 183, 62)
        "gbp" -> Color.rgb(180, 104, 221)
        "try" -> Color.rgb(236, 92, 102)
        "iqd" -> Color.rgb(83, 190, 132)
        else -> Color.rgb(72, 193, 143)
    }

    private fun themeColor(attribute: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) value.data else Color.BLACK
    }
}
