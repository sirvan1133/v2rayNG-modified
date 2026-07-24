package com.v2ray.ang.market

import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MarketRatesController(
    private val owner: LifecycleOwner,
    private val view: MarketRatesView
) : DefaultLifecycleObserver {

    data class Asset(val id: String, val label: String, val patterns: List<String>)

    private data class PageCache(
        var body: String? = null,
        var etag: String? = null,
        var lastModified: String? = null
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()
    private val currencyCache = PageCache()
    private val goldCache = PageCache()
    private var job: Job? = null
    private var scene = false
    private var ratesCache = emptyList<MarketRate>()

    init {
        owner.lifecycle.addObserver(this)
        view.visibility = View.INVISIBLE
        view.alpha = 0f
    }

    fun selected(): Set<String> =
        MmkvManager.decodeSettingsString(KEY_SELECTED, "usd,eur")
            .orEmpty()
            .split(',')
            .filter { it.isNotBlank() }
            .toSet()
            .ifEmpty { setOf("usd", "eur") }

    fun setSelected(value: Set<String>) {
        MmkvManager.encodeSettings(KEY_SELECTED, value.joinToString(","))
        refresh()
    }

    fun isEnabled() = MmkvManager.decodeSettingsBool(KEY_ENABLED, true)

    fun setEnabled(value: Boolean) {
        MmkvManager.encodeSettings(KEY_ENABLED, value)
        if (!value) setSceneVisible(false) else refresh()
    }

    fun setSceneVisible(value: Boolean) {
        scene = value && isEnabled()
        view.animate().cancel()
        if (scene) {
            view.visibility = View.VISIBLE
            view.animate().alpha(.9f).setDuration(600).start()
            start()
        } else {
            view.animate().alpha(0f).setDuration(350).withEndAction {
                if (!scene) view.visibility = View.INVISIBLE
            }.start()
        }
    }

    fun refresh() {
        if (scene) start()
    }

    override fun onResume(owner: LifecycleOwner) {
        if (scene) start()
    }

    override fun onPause(owner: LifecycleOwner) {
        job?.cancel()
    }

    private fun start() {
        job?.cancel()
        job = owner.lifecycleScope.launch(Dispatchers.IO) {
            while (currentCoroutineContext().isActive) {
                val rates = fetch(selected())
                withContext(Dispatchers.Main) {
                    if (rates.isNotEmpty()) {
                        ratesCache = rates
                        view.submit(rates)
                    } else if (ratesCache.isNotEmpty()) {
                        view.submit(ratesCache, true)
                    }
                }
                delay(REFRESH_INTERVAL)
            }
        }
    }

    private fun fetch(selected: Set<String>): List<MarketRate> {
        val currencyHtml = normalize(get(CURRENCY_URL, currencyCache) ?: return emptyList())
        val output = mutableListOf<MarketRate>()

        ASSETS.filter { it.id in selected && it.id != "gold" }.forEach { asset ->
            extract(currencyHtml, asset.patterns)?.let { rial ->
                output += MarketRate(asset.id, asset.label, rial / 10L)
            }
        }

        if ("gold" in selected) {
            get(GOLD_URL, goldCache)
                ?.let(::normalize)
                ?.let { extract(it, listOf("طلای 18 عیار", "طلای ۱۸ عیار", "طلا 18", "طلا ۱۸")) }
                ?.let { rial ->
                    output += MarketRate("gold", "طلای ۱۸", rial / 10L)
                }
        }
        return output
    }

    private fun get(url: String, cache: PageCache): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 Android v2rayNG Market")
            .apply {
                cache.etag?.let { header("If-None-Match", it) }
                cache.lastModified?.let { header("If-Modified-Since", it) }
            }
            .build()

        client.newCall(request).execute().use { response ->
            when {
                response.code == 304 -> cache.body
                response.isSuccessful -> response.body?.string()?.also {
                    cache.body = it
                    cache.etag = response.header("ETag")
                    cache.lastModified = response.header("Last-Modified")
                }
                else -> null
            }
        }
    } catch (_: Exception) {
        cache.body
    }

    private fun normalize(html: String): String =
        html.replace(Regex("(?is)<script.*?</script>|<style.*?</style>"), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&zwnj;", "‌")
            .map { char ->
                when (char) {
                    '۰' -> '0'; '۱' -> '1'; '۲' -> '2'; '۳' -> '3'; '۴' -> '4'
                    '۵' -> '5'; '۶' -> '6'; '۷' -> '7'; '۸' -> '8'; '۹' -> '9'
                    else -> char
                }
            }
            .joinToString("")
            .replace(Regex("\\s+"), " ")

    private fun extract(text: String, labels: List<String>): Long? {
        for (label in labels) {
            Regex("${Regex.escape(label)}\\s+([0-9][0-9,]{2,})")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(",", "")
                ?.toLongOrNull()
                ?.let { return it }
        }
        return null
    }

    companion object {
        const val KEY_ENABLED = "market_widget_enabled"
        const val KEY_SELECTED = "market_widget_selected"
        private const val CURRENCY_URL = "https://www.tgju.org/currency"
        private const val GOLD_URL = "https://www.tgju.org/gold-chart"
        private const val REFRESH_INTERVAL = 300_000L

        val ASSETS = listOf(
            Asset("usd", "دلار", listOf("دلار آمریکا", "دلار")),
            Asset("eur", "یورو", listOf("یورو")),
            Asset("gold", "طلای ۱۸", listOf("طلای 18 عیار", "طلای ۱۸ عیار")),
            Asset("gbp", "پوند انگلیس", listOf("پوند انگلیس", "پوند")),
            Asset("try", "لیر ترکیه", listOf("لیر ترکیه", "لیر")),
            Asset("iqd", "دینار عراق", listOf("دینار عراق", "دینار"))
        )
    }
}
