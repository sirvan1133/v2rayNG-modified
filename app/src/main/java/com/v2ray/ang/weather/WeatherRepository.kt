package com.v2ray.ang.weather

import com.v2ray.ang.handler.MmkvManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Fetches current conditions from Open-Meteo (free, key-less) and keeps the
 * last good snapshot in MMKV.  All calls are blocking and must run on IO.
 *
 * Cache policy: a snapshot is fresh for [CACHE_TTL_MS] as long as the device
 * has not moved more than ~2 km; otherwise the network is consulted and the
 * cache is only used as an offline fallback (returned with stale = true).
 */
object WeatherRepository {
    private const val CACHE_KEY = "weather_snapshot_cache"
    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private const val SAME_PLACE_DEGREES = 0.02

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    fun cached(): WeatherSnapshot? = WeatherSnapshot.fromJson(MmkvManager.decodeSettingsString(CACHE_KEY))

    fun current(latitude: Double, longitude: Double): WeatherSnapshot? {
        cached()?.let {
            val samePlace = abs(it.latitude - latitude) < SAME_PLACE_DEGREES && abs(it.longitude - longitude) < SAME_PLACE_DEGREES
            if (samePlace && System.currentTimeMillis() - it.updatedAt < CACHE_TTL_MS) return it
        }
        val fetched = fetch(latitude, longitude)
        if (fetched != null) {
            MmkvManager.encodeSettings(CACHE_KEY, fetched.toJson())
            return fetched
        }
        // Offline: surface the last reading rather than nothing at all.
        return cached()?.copy(stale = true)
    }

    private fun fetch(latitude: Double, longitude: Double): WeatherSnapshot? = try {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,is_day"
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val current = JSONObject(response.body?.string() ?: return null).getJSONObject("current")
            WeatherSnapshot(
                latitude = latitude,
                longitude = longitude,
                temperature = current.optDouble("temperature_2m", Double.NaN),
                feelsLike = current.optDouble("apparent_temperature", Double.NaN),
                humidity = current.optInt("relative_humidity_2m", 0),
                windSpeed = current.optDouble("wind_speed_10m", 0.0),
                weatherCode = current.optInt("weather_code", 3),
                isDay = current.optInt("is_day", 1) == 1,
                city = cityName(latitude, longitude),
                updatedAt = System.currentTimeMillis()
            ).takeIf { it.temperature.isFinite() }
        }
    } catch (_: Exception) {
        null
    }

    /** Key-less reverse geocode; the city is cosmetic, so failure degrades to "". */
    private fun cityName(latitude: Double, longitude: Double): String = try {
        val url = "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$latitude&longitude=$longitude&localityLanguage=en"
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return ""
            val json = JSONObject(response.body?.string() ?: return "")
            json.optString("city").ifBlank { json.optString("locality") }.ifBlank { json.optString("principalSubdivision") }
        }
    } catch (_: Exception) {
        ""
    }
}
