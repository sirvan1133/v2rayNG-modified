package com.v2ray.ang.ui

import android.animation.ValueAnimator
import android.graphics.Color
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
        widthPx: Int = (220 * anchor.resources.displayMetrics.density).toInt()
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

        val itemH = (48 * anchor.resources.displayMetrics.density).toInt()
        val maxH = ((items.size * itemH) + (16 * anchor.resources.displayMetrics.density)).toInt()

        val popup = PopupWindow(menuView, widthPx, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        popup.elevation = 24f
        popup.isOutsideTouchable = true
        popup.isClippingEnabled = false
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

        val dm = anchor.resources.displayMetrics
        val screenH = dm.heightPixels
        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)

        val spaceAbove = anchorLoc[1]
        val spaceBelow = screenH - anchorLoc[1] - anchor.height
        val popupH = maxH.coerceAtMost(screenH - 16 * dm.density.toInt())

        if (spaceBelow >= popupH + 8) {
            popup.showAsDropDown(anchor, 0, 4)
        } else if (spaceAbove >= popupH + 8) {
            popup.showAsDropDown(anchor, 0, -(anchor.height + popupH + 4))
        } else {
            popup.showAtLocation(anchor.rootView, android.view.Gravity.CENTER, 0, 0)
        }

        popup.width = widthPx
        currentPopup = popup

        // Match the supplied HTML motion: lift + scale from 85% to full size.
        menuView.alpha = 0f
        menuView.scaleX = .85f
        menuView.scaleY = .85f
        menuView.translationY = -15f * dm.density
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
            holder.itemView.setOnClickListener { onClick(item, holder.bindingAdapterPosition) }
        }
    }
}
