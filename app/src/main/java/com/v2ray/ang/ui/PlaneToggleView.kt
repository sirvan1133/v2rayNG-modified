package com.v2ray.ang.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.sin

class PlaneToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val planePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val planePath = Path()
    private var stateProgress = 0f
    private var floatPhase = 0f
    private var checked = false
    private var stateAnimator: ValueAnimator? = null
    private var floatAnimator: ValueAnimator? = null
    private var onCheckedChange: ((Boolean) -> Unit)? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener {
            setChecked(!checked, true)
            onCheckedChange?.invoke(checked)
        }
    }

    fun setOnCheckedChangeListener(listener: (Boolean) -> Unit) {
        onCheckedChange = listener
    }

    fun setChecked(value: Boolean, animate: Boolean = true) {
        if (checked == value && stateProgress == if (value) 1f else 0f) return
        checked = value
        stateAnimator?.cancel()
        val target = if (value) 1f else 0f
        if (!animate) {
            stateProgress = target
            updateFloatingAnimation()
            invalidate()
            return
        }
        stateAnimator = ValueAnimator.ofFloat(stateProgress, target).apply {
            duration = 350L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                stateProgress = it.animatedValue as Float
                invalidate()
            }
            doOnEnd { updateFloatingAnimation() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val radius = 31f * density
        val cx = width / 2f
        val floatOffset = if (checked) sin(floatPhase * Math.PI * 2).toFloat() * 2f * density else 0f
        val cy = height / 2f + floatOffset

        circlePaint.color = ColorUtils.blendARGB(Color.rgb(55, 71, 79), Color.rgb(0, 191, 165), stateProgress)
        val shadowAlpha = (115 + 25 * stateProgress).toInt()
        val shadowColor = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(Color.BLACK, Color.rgb(0, 191, 165), stateProgress),
            shadowAlpha
        )
        circlePaint.setShadowLayer((8f + 4f * stateProgress) * density, 0f, 4f * density, shadowColor)
        canvas.drawCircle(cx, cy, radius, circlePaint)

        val iconScale = 26f * density / 24f
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(35f * (1f - stateProgress))
        canvas.scale(iconScale, iconScale)
        canvas.translate(-12f, -12f)
        planePath.reset()
        planePath.moveTo(2f, 21f)
        planePath.lineTo(23f, 12f)
        planePath.lineTo(2f, 3f)
        planePath.lineTo(2f, 10f)
        planePath.lineTo(17f, 12f)
        planePath.lineTo(2f, 14f)
        planePath.close()
        planePaint.color = ColorUtils.blendARGB(Color.rgb(144, 164, 174), Color.WHITE, stateProgress)
        canvas.drawPath(planePath, planePaint)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        stateAnimator?.cancel()
        floatAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun updateFloatingAnimation() {
        floatAnimator?.cancel()
        floatAnimator = null
        floatPhase = 0f
        if (!checked) {
            invalidate()
            return
        }
        floatAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                floatPhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = action()
        })
    }
}
