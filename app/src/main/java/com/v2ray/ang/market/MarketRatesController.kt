package com.v2ray.ang.market

import android.util.Log
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

    data class Asset(
        val id: String,
        val label: String,
        val marketSlug: String,
        val patterns: List<String>
    )

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
    private var job: Job? = null
    private var scene = false
    private var ratesCache = emptyList<MarketRate>()

    init {
        if (!MmkvManager.decodeSettingsBool(KEY_DEFAULTS_272_APPLIED, false)) {
            MmkvManager.encodeSettings(KEY_ENABLED, true)
            MmkvManager.encodeSettings(KEY_DEFAULTS_272_APPLIED, true)
        }
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
        val currencyHtml = get(CURRENCY_URL, currencyCache) ?: return emptyList()
        val normalizedHtml by lazy { normalize(currencyHtml) }
        val output = mutableListOf<MarketRate>()

        ASSETS.filter { it.id in selected }.forEach { asset ->
            (extractBySlug(currencyHtml, asset.marketSlug)
                ?: extract(normalizedHtml, asset.patterns))?.let { rial ->
                output += MarketRate(asset.id, asset.label, rial / 10L)
            }
        }
        Log.d(TAG, "Parsed ${output.size}/${selected.size} selected market rates")
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
                else -> {
                    Log.w(TAG, "TGJU request failed with HTTP ${response.code}")
                    null
                }
            }
        }
    } catch (error: Exception) {
        Log.w(TAG, "TGJU request failed: ${error.javaClass.simpleName}: ${error.message}")
        cache.body
    }

    private fun extractBySlug(html: String, slug: String): Long? {
        val row = Regex(
            """(?is)<tr\b[^>]*\bdata-market-(?:nameslug|row)\s*=\s*["']${Regex.escape(slug)}["'][^>]*>"""
        ).find(html)?.value ?: return null
        return Regex("""\bdata-price\s*=\s*["']([0-9۰-۹٠-٩][0-9۰-۹٠-٩,٬]*)["']""")
            .find(row)
            ?.groupValues
            ?.getOrNull(1)
            ?.toAsciiDigits()
            ?.replace(",", "")
            ?.replace("٬", "")
            ?.toLongOrNull()
    }

    private fun normalize(html: String): String =
        html.replace(Regex("(?is)<script.*?</script>|<style.*?</style>"), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&zwnj;", "\u200c")
            .toAsciiDigits()
            .replace(Regex("\\s+"), " ")

    private fun String.toAsciiDigits(): String = map { char ->
        when (char) {
            in '۰'..'۹' -> '0' + (char - '۰')
            in '٠'..'٩' -> '0' + (char - '٠')
            else -> char
        }
    }.joinToString("")

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
        private const val KEY_DEFAULTS_272_APPLIED = "market_widget_defaults_272_applied"
        private const val CURRENCY_URL = "https://www.tgju.org/currency"
        private const val REFRESH_INTERVAL = 300_000L
        private const val TAG = "MarketRates"

        val ASSETS = listOf(
            Asset("usd", "دلار", "price_dollar_rl", listOf("دلار آمریکا", "دلار")),
            Asset("eur", "یورو", "price_eur", listOf("یورو")),
            Asset("gold", "طلای ۱۸", "geram18", listOf("طلای 18 عیار", "طلای ۱۸ عیار")),
            Asset("gbp", "پوند انگلیس", "price_gbp", listOf("پوند انگلیس", "پوند")),
            Asset("try", "لیر ترکیه", "price_try", listOf("لیر ترکیه", "لیر")),
            Asset("iqd", "دینار عراق", "price_iqd", listOf("دینار عراق", "دینار"))
        )
    }
}
