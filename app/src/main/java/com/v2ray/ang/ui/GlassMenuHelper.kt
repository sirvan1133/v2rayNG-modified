package com.v2ray.ang.ui

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemGlassMenuBinding

data class GlassMenuItem(
    val iconRes: Int = 0,
    val label: String,
    val checkable: Boolean = false,
    var selected: Boolean = false,
    val dismissOnClick: Boolean = true,
    val onClick: () -> Unit
)

object GlassMenuHelper {

    private var currentPopup: PopupWindow? = null
    private var currentScrim: View? = null
    private var scrimAnimator: ValueAnimator? = null
    private var blurredContent: View? = null

    private fun clearBackdropBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blurredContent?.setRenderEffect(null)
        }
        blurredContent = null
    }

    fun dismiss() {
        scrimAnimator?.cancel()
        clearBackdropBlur()
        currentScrim?.let { scrim ->
            val animator = ValueAnimator.ofFloat(scrim.alpha, 0f).setDuration(150)
            animator.interpolator = DecelerateInterpolator()
            animator.addUpdateListener { anim ->
                scrim.alpha = anim.animatedValue as Float
            }
            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    removeScrim(scrim)
                }
            })
            animator.start()
        }
        currentPopup?.dismiss()
        currentPopup = null
        currentScrim = null
    }

    private fun removeScrim(scrim: View) {
        (scrim.parent as? ViewGroup)?.removeView(scrim)
    }

    fun show(
        anchor: View,
        items: List<GlassMenuItem>,
        widthPx: Int = (143 * anchor.resources.displayMetrics.density).toInt()
    ) {
        dismiss()

        val context = anchor.context
        val activity = context as? android.app.Activity ?: return
        val contentView = activity.findViewById<ViewGroup>(android.R.id.content)

        // PopupWindow lives in its own window, so blurring the activity content
        // creates a true frosted backdrop without softening the menu itself.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            contentView.setRenderEffect(
                RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP)
            )
            blurredContent = contentView
        }

        val scrim = View(context)
        scrim.setBackgroundColor(Color.parseColor("#5208111F"))
        scrim.alpha = 0f
        scrim.setOnClickListener { dismiss() }
        val scrimParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        contentView.addView(scrim, scrimParams)
        currentScrim = scrim

        scrimAnimator?.cancel()
        scrimAnimator = ValueAnimator.ofFloat(0f, 1f).setDuration(200)
        scrimAnimator!!.interpolator = DecelerateInterpolator()
        scrimAnimator!!.addUpdateListener { anim ->
            scrim.alpha = anim.animatedValue as Float
        }
        scrimAnimator!!.start()

        val menuView = LayoutInflater.from(context).inflate(R.layout.popup_glass_menu, null)
        val rv = menuView.findViewById<RecyclerView>(R.id.rv_menu)

        val adapter = GlassMenuAdapter(items) { item, position ->
            if (item.checkable) item.selected = !item.selected
            item.onClick()
            if (item.dismissOnClick) dismiss() else rv.adapter?.notifyItemChanged(position)
        }
        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = adapter

        val dm = anchor.resources.displayMetrics
        val margin = (12 * dm.density).toInt()
        val itemH = (36 * dm.density).toInt()
        val visibleFrame = Rect()
        anchor.rootView.getWindowVisibleDisplayFrame(visibleFrame)
        if (visibleFrame.width() <= 0 || visibleFrame.height() <= 0) {
            visibleFrame.set(0, 0, dm.widthPixels, dm.heightPixels)
        }
        val safeWidth = widthPx.coerceAtMost((visibleFrame.width() - margin * 2).coerceAtLeast(1))
        menuView.measure(
            View.MeasureSpec.makeMeasureSpec(safeWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val desiredHeight = menuView.measuredHeight
            .takeIf { it > 0 }
            ?: (items.size * itemH + (12 * dm.density).toInt())
        val safeHeight = desiredHeight.coerceAtMost(
            (visibleFrame.height() - margin * 2).coerceAtLeast(itemH)
        )

        val popup = PopupWindow(menuView, safeWidth, safeHeight, true)
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        popup.elevation = 0f
        popup.isOutsideTouchable = true
        popup.isClippingEnabled = true
        popup.setOnDismissListener {
            currentPopup = null
            clearBackdropBlur()
            scrimAnimator?.cancel()
            if (currentScrim != null) {
                val fadeOut = ValueAnimator.ofFloat(scrim.alpha, 0f).setDuration(150)
                fadeOut.interpolator = DecelerateInterpolator()
                fadeOut.addUpdateListener { anim ->
                    scrim.alpha = anim.animatedValue as Float
                }
                fadeOut.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        removeScrim(scrim)
                        currentScrim = null
                    }
                })
                fadeOut.start()
            }
        }

        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        val minX = visibleFrame.left + margin
        val maxX = (visibleFrame.right - safeWidth - margin).coerceAtLeast(minX)
        val popupX = (anchorLoc[0] + anchor.width - safeWidth).coerceIn(minX, maxX)
        val belowY = anchorLoc[1] + anchor.height + (4 * dm.density).toInt()
        val aboveY = anchorLoc[1] - safeHeight - (4 * dm.density).toInt()
        val minY = visibleFrame.top + margin
        val maxY = (visibleFrame.bottom - safeHeight - margin).coerceAtLeast(minY)
        val popupY = when {
            belowY <= maxY -> belowY
            aboveY >= minY -> aboveY
            else -> ((visibleFrame.top + visibleFrame.bottom - safeHeight) / 2)
                .coerceIn(minY, maxY)
        }
        popup.showAtLocation(
            anchor.rootView,
            android.view.Gravity.TOP or android.view.Gravity.START,
            popupX,
            popupY
        )
        currentPopup = popup

        // Match the supplied HTML motion: lift + scale from 85% to full size.
        menuView.alpha = 0f
        menuView.scaleX = .96f
        menuView.scaleY = .96f
        menuView.translationY = -8f * dm.density
        menuView.post {
            menuView.pivotX = menuView.width.toFloat()
            menuView.pivotY = 0f
            menuView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(350)
                .setInterpolator(android.view.animation.PathInterpolator(.2f, .8f, .2f, 1f))
                .start()
        }
    }

    private class GlassMenuAdapter(
        private val items: List<GlassMenuItem>,
        private val onClick: (GlassMenuItem, Int) -> Unit
    ) : RecyclerView.Adapter<GlassMenuAdapter.VH>() {

        class VH(val b: ItemGlassMenuBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemGlassMenuBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.b.tvMenuItem.text =
                if (item.checkable && item.selected) "✓  ${item.label}" else item.label
            holder.b.tvMenuItem.alpha = if (item.checkable && !item.selected) .72f else 1f
            if (item.iconRes != 0) {
                holder.b.tvMenuItem.setCompoundDrawablesRelativeWithIntrinsicBounds(item.iconRes, 0, 0, 0)
            } else {
                holder.b.tvMenuItem.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            }
            holder.itemView.setOnClickListener { view ->
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onClick(item, holder.bindingAdapterPosition)
            }
        }
    }
}
