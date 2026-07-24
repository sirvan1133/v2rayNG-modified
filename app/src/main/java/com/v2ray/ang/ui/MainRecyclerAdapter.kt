
package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.ItemRecyclerFooterBinding
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.extension.isComplexType
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.util.DirectPingManager
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class MainRecyclerAdapter(
    private val mainViewModel: MainViewModel,
    private val adapterListener: MainAdapterListener?,
    private val lifecycleOwner: LifecycleOwner
) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>(), ItemTouchHelperAdapter {
    companion object {
        private data class PingSnapshot(val value: Long, val measuredAt: Long)

        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2
        private const val LIVE_PING_INTERVAL_MS = 5_000L
        private const val PING_CACHE_TTL_MS = 30_000L
        private val flagCache = ConcurrentHashMap<String, String>()
        private val pingCache = ConcurrentHashMap<String, PingSnapshot>()

        fun bestLiveServer(candidates: List<ServersCache>): Pair<String, Long>? {
            val now = System.currentTimeMillis()
            return candidates.mapNotNull { item ->
                val key = "${item.profile.server.orEmpty()}:${item.profile.serverPort.orEmpty()}"
                val ping = pingCache[key]
                    ?.takeIf { now - it.measuredAt <= PING_CACHE_TTL_MS && it.value > 0L }
                    ?.value
                ping?.let { item.guid to it }
            }.minByOrNull { it.second }
        }
    }

    private val doubleColumnDisplay = MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)
    private var data: MutableList<ServersCache> = mutableListOf()

    @SuppressLint("NotifyDataSetChanged")
    fun setData(newData: MutableList<ServersCache>?, position: Int = -1) {
        data = newData?.toMutableList() ?: mutableListOf()

        if (position >= 0 && position in data.indices) {
            notifyItemChanged(position)
        } else {
            notifyDataSetChanged()
        }
    }

    override fun getItemCount() = data.size + 1

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder is MainViewHolder) {
            val context = holder.itemMainBinding.root.context
            val guid = data[position].guid
            val profile = data[position].profile

            //Name address
            holder.itemMainBinding.tvName.text = removeFlags(profile.remarks)
            holder.itemMainBinding.tvStatistics.text = getAddress(profile)
            holder.itemMainBinding.tvType.text = profile.configType.name
            holder.itemMainBinding.tvSubscription.text = "🌐"
            holder.itemMainBinding.tvSubscription.visibility = View.VISIBLE

            val isSelected = guid == MmkvManager.getSelectServer()
            val card = holder.itemMainBinding.infoContainer
            val outerGlow = holder.itemMainBinding.selectionGlow
            card.animate().cancel()
            outerGlow.animate().cancel()
            holder.itemMainBinding.layoutIndicator.visibility = View.GONE
            card.setBackgroundResource(R.drawable.bg_server_card_glass)
            if (isSelected) {
                card.setBackgroundResource(R.drawable.bg_server_card_selected)
                outerGlow.alpha = 0f
                outerGlow.scaleX = .96f
                outerGlow.scaleY = .92f
                outerGlow.animate()
                    .alpha(.58f)
                    .scaleX(1.015f)
                    .scaleY(1.04f)
                    .setDuration(380)
                    .setInterpolator(android.view.animation.PathInterpolator(.16f, 1f, .3f, 1f))
                    .start()
                card.alpha = .92f
                card.scaleX = .995f
                card.scaleY = .995f
                card.elevation = 8f * holder.itemView.resources.displayMetrics.density
                card.animate()
                    .alpha(1f)
                    .scaleX(1.006f)
                    .scaleY(1.006f)
                    .setDuration(360)
                    .setInterpolator(android.view.animation.PathInterpolator(.16f, 1f, .3f, 1f))
                    .start()
            } else {
                outerGlow.animate()
                    .alpha(0f)
                    .scaleX(.96f)
                    .scaleY(.92f)
                    .setDuration(180)
                    .start()
                card.animate()
                    .alpha(.98f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .start()
                card.elevation = 6f * holder.itemView.resources.displayMetrics.density
            }
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.itemMainBinding.tvCountry.apply {
                // The endpoint label belongs to the animated node on the map, not the list row.
                visibility = View.GONE
            }

            startLiveDirectPing(holder, profile)

            //layoutIndicator
            //subscription remarks — show first char of sub name in avatar
            //layout: Telegram-style collapses share/edit/delete into a single "more" action.
            // The doubleColumnDisplay distinction is preserved (the adapter still toggles
            // the hidden layouts for tooling/inspection) but the visible UI always uses
            // the overflow button, which triggers the same share dialog for both modes.
            if (doubleColumnDisplay) {
                holder.itemMainBinding.layoutShare.visibility = View.GONE
                holder.itemMainBinding.layoutEdit.visibility = View.GONE
                holder.itemMainBinding.layoutRemove.visibility = View.GONE
                holder.itemMainBinding.layoutMore.visibility = View.VISIBLE

                holder.itemMainBinding.layoutMore.setOnClickListener {
                    adapterListener?.onShare(guid, profile, position, true, holder.itemMainBinding.layoutMore)
                }
            } else {
                holder.itemMainBinding.layoutShare.visibility = View.GONE
                holder.itemMainBinding.layoutEdit.visibility = View.GONE
                holder.itemMainBinding.layoutRemove.visibility = View.GONE
                holder.itemMainBinding.layoutMore.visibility = View.VISIBLE

                holder.itemMainBinding.layoutMore.setOnClickListener {
                    adapterListener?.onShare(guid, profile, position, false, holder.itemMainBinding.layoutMore)
                }
            }

            holder.itemMainBinding.infoContainer.setOnClickListener {
                adapterListener?.onSelectServer(guid)
            }
        }

    }

    override fun onViewRecycled(holder: BaseViewHolder) {
        holder.livePingJob?.cancel()
        holder.livePingJob = null
        super.onViewRecycled(holder)
    }

    private fun startLiveDirectPing(holder: MainViewHolder, profile: ProfileItem) {
        holder.livePingJob?.cancel()
        val binding = holder.itemMainBinding
        val pingKey = "${profile.server.orEmpty()}:${profile.serverPort.orEmpty()}"
        val cachedPing = pingCache[pingKey]
            ?.takeIf { System.currentTimeMillis() - it.measuredAt <= PING_CACHE_TTL_MS }
        if (cachedPing != null) {
            renderPing(binding, cachedPing.value)
        } else {
            binding.tvStatus.text = "Checking…"
            binding.tvStatus.setTextColor(Color.parseColor("#B3FFFFFF"))
            binding.tvTestResult.text = "—"
            binding.statusDot.alpha = .38f
            binding.statusDot.clearAnimation()
        }

        holder.livePingJob = lifecycleOwner.lifecycleScope.launch {
            // Cancels the flag lookup and every live-ping loop while the app is
            // backgrounded or the screen is locked. Cached values remain visible.
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                coroutineScope {
                    launch {
                        val host = profile.server.orEmpty()
                        val flag = flagCache[host] ?: withContext(Dispatchers.IO) {
                            flagForCountryCode(
                                IpGeoLocationResolver.serverLocation(
                                    binding.root.context.applicationContext,
                                    host
                                )?.countryCode
                            )
                        }.also { if (host.isNotBlank() && it != "🌐") flagCache[host] = it }
                        if (isActive) binding.tvSubscription.text = flag
                    }
                    while (isActive) {
                        val ping = DirectPingManager.measure(
                            binding.root.context.applicationContext,
                            profile.server,
                            profile.serverPort
                        )
                        if (!isActive) return@coroutineScope
                        pingCache[pingKey] = PingSnapshot(ping, System.currentTimeMillis())
                        renderPing(binding, ping)
                        delay(LIVE_PING_INTERVAL_MS)
                    }
                }
            }
        }
    }

    private fun renderPing(binding: ItemRecyclerMainBinding, ping: Long) {
        if (ping >= 0L) {
            binding.tvStatus.text = "Online"
            binding.tvStatus.setTextColor(Color.parseColor("#34C759"))
            binding.tvTestResult.text = "$ping ms"
            binding.tvTestResult.setTextColor(
                ContextCompat.getColor(binding.root.context, R.color.colorPing)
            )
            binding.statusDot.alpha = 1f
            if (binding.statusDot.animation == null) {
                binding.statusDot.startAnimation(
                    android.view.animation.AnimationUtils.loadAnimation(
                        binding.root.context,
                        R.anim.live_ping_pulse
                    )
                )
            }
        } else {
            binding.tvStatus.text = "Offline"
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(binding.root.context, R.color.colorPingRed)
            )
            binding.tvTestResult.text = "—"
            binding.tvTestResult.setTextColor(
                ContextCompat.getColor(binding.root.context, R.color.colorPingRed)
            )
            binding.statusDot.clearAnimation()
            binding.statusDot.alpha = .25f
        }
    }

    private fun removeFlags(label: String): String {
        val cleaned = label.codePoints()
            .filter { it !in 0x1F1E6..0x1F1FF }
            .toArray()
        return String(cleaned, 0, cleaned.size)
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    private fun flagForCountryCode(code: String?): String {
        val normalized = code?.trim()?.uppercase().orEmpty()
        if (normalized.length != 2 || normalized.any { it !in 'A'..'Z' }) return "🌐"
        val points = normalized.map { 0x1F1E6 + (it - 'A') }.toIntArray()
        return String(points, 0, points.size)
    }

    /**
     * Gets the server address information
     * Hides part of IP or domain information for privacy protection
     * @param profile The server configuration
     * @return Formatted address string
     */
    private fun getAddress(profile: ProfileItem): String {
        return profile.description.nullIfBlank() ?: AngConfigManager.generateDescription(profile)
    }

    /**
     * Gets the subscription remarks information
     * @param profile The server configuration
     * @return Subscription remarks string, or empty string if none
     */
    private fun getSubscriptionRemarks(profile: ProfileItem): String {
        val subRemarks =
            if (mainViewModel.subscriptionId.isEmpty())
                MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks?.firstOrNull()
            else
                null
        return subRemarks?.toString() ?: ""
    }

    private fun getProtocolDescription(profile: ProfileItem): String {
        if (profile.configType.isComplexType()) {
            return profile.configType.name
        }

        val parts = mutableListOf<String>()
        parts.add(profile.configType.name)

        // Transport: hide tcp or blank
        profile.network?.let { net ->
            if (net.isNotBlank() && !net.equals("tcp", ignoreCase = true)) {
                parts.add(net)
            }
        }

        // Security: hide blank or tls
        profile.security?.let { sec ->
            if (sec.isNotBlank()) {
                if (profile.insecure == true && sec.equals("tls", ignoreCase = true)) {
                    parts.add("$sec insecure") // TODO
                } else {
                    parts.add(sec)
                }
            }
        }

        return parts.joinToString(" / ")
    }

    fun removeServerSub(guid: String, position: Int) {
        val idx = data.indexOfFirst { it.guid == guid }
        if (idx >= 0) {
            data.removeAt(idx)
            notifyItemRemoved(idx)
            notifyItemRangeChanged(idx, data.size - idx)
        }
    }

    fun setSelectServer(fromPosition: Int, toPosition: Int) {
        notifyItemChanged(fromPosition)
        notifyItemChanged(toPosition)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return when (viewType) {
            VIEW_TYPE_ITEM ->
                MainViewHolder(ItemRecyclerMainBinding.inflate(LayoutInflater.from(parent.context), parent, false))

            else ->
                FooterViewHolder(ItemRecyclerFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == data.size) {
            VIEW_TYPE_FOOTER
        } else {
            VIEW_TYPE_ITEM
        }
    }

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var livePingJob: Job? = null

        fun onItemSelected() {
            itemView.background = androidx.core.content.ContextCompat.getDrawable(
                itemView.context,
                R.drawable.bg_config_selected_glass
            )
            itemView.animate().scaleX(1.018f).scaleY(1.018f).setDuration(180).start()
            itemView.elevation = 12f * itemView.resources.displayMetrics.density
        }

        fun onItemClear() {
            itemView.background = null
            itemView.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            itemView.elevation = 0f
        }
    }

    class MainViewHolder(val itemMainBinding: ItemRecyclerMainBinding) :
        BaseViewHolder(itemMainBinding.root), ItemTouchHelperViewHolder

    class FooterViewHolder(val itemFooterBinding: ItemRecyclerFooterBinding) :
        BaseViewHolder(itemFooterBinding.root)

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        mainViewModel.swapServer(fromPosition, toPosition)
        if (fromPosition < data.size && toPosition < data.size) {
            Collections.swap(data, fromPosition, toPosition)
        }
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
        // do nothing
    }

    override fun onItemDismiss(position: Int) {
    }
}
