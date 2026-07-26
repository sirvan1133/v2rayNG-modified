package com.v2ray.ang.ui

import android.os.Build
import android.util.Log
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

data class CalendarMonthData(
    val year: Int,
    val month: Int,
    val events: Map<Int, List<String>>,
    val holidays: Set<Int>
)

object CalendarOccasionRepository {
    private const val SOURCE_URL = "https://www.bahesab.ir/time/calendar/"
    private const val CACHE_KEY = "bahesab_calendar_month_data"
    private const val CACHE_MONTH = "bahesab_calendar_month_key"
    private const val CACHE_TIME = "bahesab_calendar_updated"
    private const val DAY_MS = 86_400_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .build()

    suspend fun currentMonth(): CalendarMonthData = withContext(Dispatchers.IO) {
        val (year, month) = jalaliCurrentMonth()
        val monthKey = "$year/$month"
        val cached = decode(MmkvManager.decodeSettingsString(CACHE_KEY, "").orEmpty())
        val cachedMonth = MmkvManager.decodeSettingsString(CACHE_MONTH, "").orEmpty()
        val updated = MmkvManager.decodeSettingsLong(CACHE_TIME, 0L)

        if (cached != null && cachedMonth == monthKey &&
            System.currentTimeMillis() - updated < DAY_MS
        ) {
            return@withContext cached
        }

        val fresh = fetchAndParse(year, month)
        if (fresh != null) {
            MmkvManager.encodeSettings(CACHE_KEY, encode(fresh))
            MmkvManager.encodeSettings(CACHE_MONTH, monthKey)
            MmkvManager.encodeSettings(CACHE_TIME, System.currentTimeMillis())
            fresh
        } else {
            cached?.takeIf { cachedMonth == monthKey }
                ?: CalendarMonthData(year, month, emptyMap(), emptySet())
        }
    }

    private fun fetchAndParse(year: Int, month: Int): CalendarMonthData? {
        val html = get(SOURCE_URL) ?: return null
        val block = Regex(
            """(?is)<p[^>]*\bid\s*=\s*["']monasebat2["'][^>]*>(.*?)</p>"""
        ).find(html)?.groupValues?.get(1) ?: run {
            Log.w("BahesabCalendar", "Monthly occasion block was not found")
            return null
        }

        val events = linkedMapOf<Int, MutableList<String>>()
        val holidays = linkedSetOf<Int>()
        val dayRegex = Regex(
            """(?is)<span[^>]*\bclass\s*=\s*["'][^"']*\bM2\b[^"']*["'][^>]*>\s*([۰-۹٠-٩0-9]{1,2})[^<]*</span>"""
        )

        block.split(Regex("""(?i)<br\s*/?>""")).forEach { entry ->
            val match = dayRegex.find(entry) ?: return@forEach
            val day = normalizeDigits(match.groupValues[1]).toIntOrNull() ?: return@forEach
            val tail = entry.substring(match.range.last + 1)
            val description = decodeHtml(tail)
                .replace("«تعطیل»", "")
                .trim()
                .trimStart('-', '–', '—', ' ')
                .trim()
            if (description.isNotBlank()) {
                events.getOrPut(day) { mutableListOf() }.add(description)
            }
            val classValue = Regex(
                """(?is)\bclass\s*=\s*["']([^"']*)["']"""
            ).find(match.value)?.groupValues?.get(1).orEmpty()
            if (classValue.split(Regex("\\s+")).any { it.equals("ho", true) } ||
                entry.contains("تعطیل")
            ) {
                holidays += day
            }
        }

        return CalendarMonthData(
            year = year,
            month = month,
            events = events.mapValues { it.value.toList() },
            holidays = holidays
        ).takeIf { it.events.isNotEmpty() }
    }

    private fun decodeHtml(value: String): String = value
        .replace(Regex("""(?is)<script.*?</script>|<style.*?</style>"""), " ")
        .replace(Regex("""(?s)<[^>]+>"""), " ")
        .replace("&nbsp;", " ")
        .replace("&#160;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun normalizeDigits(value: String): String = buildString {
        value.forEach { char ->
            append(
                when (char) {
                    in '۰'..'۹' -> '0' + (char - '۰')
                    in '٠'..'٩' -> '0' + (char - '٠')
                    else -> char
                }
            )
        }
    }

    private fun encode(data: CalendarMonthData): String {
        val root = JSONObject()
            .put("year", data.year)
            .put("month", data.month)
            .put("holidays", JSONArray(data.holidays.toList()))
        val eventArray = JSONArray()
        data.events.forEach { (day, descriptions) ->
            eventArray.put(
                JSONObject()
                    .put("day", day)
                    .put("items", JSONArray(descriptions))
            )
        }
        return root.put("events", eventArray).toString()
    }

    private fun decode(raw: String): CalendarMonthData? = runCatching {
        if (raw.isBlank()) return@runCatching null
        val root = JSONObject(raw)
        val events = linkedMapOf<Int, List<String>>()
        val eventArray = root.optJSONArray("events") ?: JSONArray()
        for (index in 0 until eventArray.length()) {
            val item = eventArray.optJSONObject(index) ?: continue
            val descriptions = buildList {
                val array = item.optJSONArray("items") ?: JSONArray()
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf(String::isNotBlank)?.let(::add)
                }
            }
            if (descriptions.isNotEmpty()) events[item.optInt("day")] = descriptions
        }
        val holidays = buildSet {
            val array = root.optJSONArray("holidays") ?: JSONArray()
            for (index in 0 until array.length()) add(array.optInt(index))
        }
        CalendarMonthData(root.getInt("year"), root.getInt("month"), events, holidays)
    }.getOrNull()

    private fun get(url: String): String? = runCatching {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) v2rayNG Calendar")
                .header("Accept-Language", "fa-IR,fa;q=0.9")
                .build()
        ).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.onFailure {
        Log.w("BahesabCalendar", "Calendar download failed", it)
    }.getOrNull()

    private fun jalaliCurrentMonth(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val calendar =
                android.icu.util.Calendar.getInstance(Locale.forLanguageTag("fa-IR-u-ca-persian"))
            return calendar.get(android.icu.util.Calendar.YEAR) to
                calendar.get(android.icu.util.Calendar.MONTH) + 1
        }
        val now = java.util.Calendar.getInstance()
        return now.get(java.util.Calendar.YEAR) to now.get(java.util.Calendar.MONTH) + 1
    }
}
