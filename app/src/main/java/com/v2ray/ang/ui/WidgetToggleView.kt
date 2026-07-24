package com.v2ray.ang.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Exact 52x28 toggle architecture:
 * grey/green rounded track, 24dp white thumb, 24dp animated travel.
 */
class WidgetToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var listener: ((Boolean) -> Unit)? = null
    private var immediateChange = false

    var isChecked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (immediateChange) {
                progress = if (value) 1f else 0f
                invalidate()
            } else {
                animateTo(if (value) 1f else 0f)
                listener?.invoke(value)
            }
        }

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { isChecked = !isChecked }
    }

    fun setCheckedImmediately(value: Boolean) {
        animator?.cancel()
        immediateChange = true
        isChecked = value
        progress = if (value) 1f else 0f
        immediateChange = false
        invalidate()
    }

    fun setOnCheckedChangeListener(block: (Boolean) -> Unit) {
        listener = block
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 250L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((52f * density).toInt(), (28f * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val trackColor = blendColor(Color.rgb(85, 85, 85), Color.rgb(52, 199, 89), progress)
        paint.color = trackColor
        canvas.drawRoundRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            14f * density, 14f * density, paint
        )

        val thumbRadius = 12f * density
        val startX = 2f * density + thumbRadius
        val thumbX = startX + 24f * density * progress
        paint.color = Color.WHITE
        paint.setShadowLayer(2.5f * density, 0f, 1f * density, Color.argb(70, 0, 0, 0))
        setLayerType(LAYER_TYPE_SOFTWARE, paint)
        canvas.drawCircle(thumbX, height / 2f, thumbRadius, paint)
        paint.clearShadowLayer()
    }

    private fun blendColor(from: Int, to: Int, amount: Float): Int {
        fun channel(a: Int, b: Int) = (a + (b - a) * amount).toInt()
        return Color.rgb(
            channel(Color.red(from), Color.red(to)),
            channel(Color.green(from), Color.green(to)),
            channel(Color.blue(from), Color.blue(to))
        )
    }
}
