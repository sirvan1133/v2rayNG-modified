package com.v2ray.ang.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemSubGroupBinding

class SubGroupAdapter(
    private val items: MutableList<String> = mutableListOf(),
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<SubGroupAdapter.VH>() {

    private var selectedPos = 0

    fun submitList(newItems: List<String>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setSelected(position: Int) {
        val old = selectedPos
        selectedPos = position
        notifyItemChanged(old)
        notifyItemChanged(position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSubGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val isSelected = position == selectedPos
        holder.bind(items[position], isSelected)
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun onViewRecycled(holder: VH) {
        holder.stopPulse()
        super.onViewRecycled(holder)
    }

    inner class VH(private val b: ItemSubGroupBinding) : RecyclerView.ViewHolder(b.root) {
        private var pulseAnim: AnimatorSet? = null

        fun bind(name: String, selected: Boolean) {
            b.tvName.text = name
            stopPulse()
            if (selected) {
                b.card.setBackgroundResource(R.drawable.tg_sub_glass_selected)
                b.dot.setBackgroundResource(R.drawable.tg_sub_dot_on)
                b.tvArrow.setTextColor(0x8CFFFFFF.toInt())
                startPulse()
            } else {
                b.card.setBackgroundResource(R.drawable.tg_sub_glass_normal)
                b.dot.setBackgroundResource(R.drawable.tg_sub_dot_off)
                b.tvArrow.setTextColor(0x59FFFFFF.toInt())
            }
        }

        private fun startPulse() {
            val dot: View = b.dot
            val alphaAnim = ObjectAnimator.ofFloat(dot, View.ALPHA, 0.4f, 1f).apply {
                duration = 1800
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
            }
            val scaleX = ObjectAnimator.ofFloat(dot, View.SCALE_X, 0.85f, 1.15f).apply {
                duration = 1800
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
            }
            val scaleY = ObjectAnimator.ofFloat(dot, View.SCALE_Y, 0.85f, 1.15f).apply {
                duration = 1800
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
            }
            pulseAnim = AnimatorSet().apply {
                playTogether(alphaAnim, scaleX, scaleY)
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }

        fun stopPulse() {
            pulseAnim?.cancel()
            pulseAnim = null
            b.dot.alpha = 1f
            b.dot.scaleX = 1f
            b.dot.scaleY = 1f
        }
    }
}