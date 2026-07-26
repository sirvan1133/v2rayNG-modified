package com.v2ray.ang.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.v2ray.ang.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class DrawerCalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var monthData: CalendarMonthData? = null
    private var occasionLayout: StaticLayout? = null
    private val density = resources.displayMetrics.density
    private val baseHeight = 166f

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val occasionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        textSize = 24f * density
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val weekdays = arrayOf("ش", "ی", "د", "س", "چ", "پ", "ج")

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        occasionLayout = buildOccasionLayout(measuredWidth)
        val extra = occasionLayout?.let { 34f * density + it.height } ?: 0f
        val desiredHeight = (baseHeight * density + extra + if (extra > 0) 13f * density else 0f).toInt()
        setMeasuredDimension(measuredWidth, resolveSize(desiredHeight, heightMeasureSpec))
    }

    fun setMonthData(value: CalendarMonthData) {
        monthData = value
        requestLayout()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val onSurface = ContextCompat.getColor(context, R.color.drawer_calendar_title)
        val muted = ContextCompat.getColor(context, R.color.drawer_calendar_muted)
        val accent = ContextCompat.getColor(context, R.color.drawer_calendar_accent)
        val error = ContextCompat.getColor(context, R.color.tg_error)
        val outline = ContextCompat.getColor(context, R.color.tg_outlineVariant)

        titlePaint.color = onSurface
        titlePaint.textSize = 15 * density
        dayPaint.textSize = 11 * density
        linePaint.color = outline
        linePaint.strokeWidth = .7f * density
        accentPaint.color = accent

        val calendar = currentCalendar()
        canvas.drawText(monthTitle(calendar), width / 2f, 22 * density, titlePaint)
        canvas.drawLine(18 * density, 32 * density, width - 18 * density, 32 * density, linePaint)

        val cellWidth = width / 7f
        dayPaint.color = muted
        weekdays.forEachIndexed { index, label ->
            canvas.drawText(label, width - cellWidth * (index + .5f), 50 * density, dayPaint)
        }

        val today = calendar.get(dayOfMonthField())
        calendar.set(dayOfMonthField(), 1)
        val offset = calendar.get(dayOfWeekField()) % 7
        val maxDay = calendar.getActualMaximum(dayOfMonthField())
        val top = 61 * density
        val rowHeight = 20 * density
        val holidays = monthData?.holidays.orEmpty()

        for (day in 1..maxDay) {
            val slot = offset + day - 1
            val column = slot % 7
            val row = slot / 7
            val cx = width - cellWidth * (column + .5f)
            val cy = top + row * rowHeight + 10 * density
            if (day == today) {
                canvas.drawRoundRect(
                    RectF(cx - 13 * density, cy - 12 * density, cx + 13 * density, cy + 6 * density),
                    9 * density,
                    9 * density,
                    accentPaint
                )
                dayPaint.color = ContextCompat.getColor(context, R.color.tg_onPrimary)
                dayPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            } else {
                dayPaint.color = if (column == 6 || day in holidays) error else onSurface
                dayPaint.typeface = android.graphics.Typeface.DEFAULT
            }
            canvas.drawText(toPersianDigits(day.toString()), cx, cy + density, dayPaint)
        }

        occasionLayout?.let { layout ->
            val sectionTop = baseHeight * density
            canvas.drawLine(
                18 * density,
                sectionTop,
                width - 18 * density,
                sectionTop,
                linePaint
            )
            titlePaint.textSize = 11 * density
            titlePaint.color = accent
            canvas.drawText("مناسبت‌های ماه", width / 2f, sectionTop + 20 * density, titlePaint)
            occasionPaint.color = onSurface
            canvas.save()
            canvas.translate(28 * density, sectionTop + 29 * density)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun buildOccasionLayout(viewWidth: Int): StaticLayout? {
        val events = monthData?.events.orEmpty()
        if (events.isEmpty() || viewWidth <= 56 * density) return null
        val holidays = monthData?.holidays.orEmpty()
        val lines = events.toSortedMap().entries.map { (day, descriptions) ->
            day to "${toPersianDigits(day.toString())} — ${descriptions.joinToString(" • ")}"
        }
        val text = lines.joinToString("\n") { it.second }
        val styledText = SpannableString(text)
        val holidayColor = ContextCompat.getColor(context, R.color.tg_error)
        var cursor = 0
        lines.forEach { (day, line) ->
            if (day in holidays) {
                styledText.setSpan(
                    ForegroundColorSpan(holidayColor),
                    cursor,
                    cursor + line.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            cursor += line.length + 1
        }
        val availableWidth = (viewWidth - 56 * density).toInt().coerceAtLeast(1)
        occasionPaint.color = ContextCompat.getColor(context, R.color.drawer_calendar_title)
        return StaticLayout.Builder.obtain(
            styledText,
            0,
            styledText.length,
            occasionPaint,
            availableWidth
        )
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(6f * density, 1f)
            .setTextDirection(android.text.TextDirectionHeuristics.RTL)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun currentCalendar(): AnyCalendar =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            AnyCalendar.Icu(
                android.icu.util.Calendar.getInstance(Locale.forLanguageTag("fa-IR-u-ca-persian"))
            )
        } else {
            AnyCalendar.Java(Calendar.getInstance())
        }

    private fun monthTitle(calendar: AnyCalendar): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && calendar is AnyCalendar.Icu) {
            android.icu.text.SimpleDateFormat(
                "MMMM yyyy",
                Locale.forLanguageTag("fa-IR-u-ca-persian")
            ).format(calendar.value.time)
        } else {
            SimpleDateFormat("MMMM yyyy", Locale("fa", "IR")).format(Date())
        }

    private fun dayOfMonthField() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) android.icu.util.Calendar.DAY_OF_MONTH else Calendar.DAY_OF_MONTH

    private fun dayOfWeekField() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) android.icu.util.Calendar.DAY_OF_WEEK else Calendar.DAY_OF_WEEK

    private fun toPersianDigits(value: String): String =
        value.map { if (it in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[it - '0'] else it }.joinToString("")

    private sealed class AnyCalendar {
        abstract fun get(field: Int): Int
        abstract fun set(field: Int, newValue: Int)
        abstract fun getActualMaximum(field: Int): Int

        class Java(val value: Calendar) : AnyCalendar() {
            override fun get(field: Int) = value.get(field)
            override fun set(field: Int, newValue: Int) = value.set(field, newValue)
            override fun getActualMaximum(field: Int) = value.getActualMaximum(field)
        }

        @androidx.annotation.RequiresApi(Build.VERSION_CODES.N)
        class Icu(val value: android.icu.util.Calendar) : AnyCalendar() {
            override fun get(field: Int) = value.get(field)
            override fun set(field: Int, newValue: Int) = value.set(field, newValue)
            override fun getActualMaximum(field: Int) = value.getActualMaximum(field)
        }
    }
}
