package com.v2ray.ang.weather

import com.google.gson.Gson

/**
 * A single observed weather state, cached as JSON in MMKV so the last
 * successful reading survives process death and network loss.
 */
data class WeatherSnapshot(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val temperature: Double = 0.0,
    val feelsLike: Double = 0.0,
    val humidity: Int = 0,
    val windSpeed: Double = 0.0,
    val weatherCode: Int = 0,
    val isDay: Boolean = true,
    val city: String = "",
    val updatedAt: Long = 0L,
    /** True when this snapshot came from cache because a live fetch failed. */
    @Transient val stale: Boolean = false
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String?): WeatherSnapshot? = try {
            if (json.isNullOrBlank()) null else Gson().fromJson(json, WeatherSnapshot::class.java)
        } catch (_: Exception) {
            null
        }
    }
}

/** WMO weather-code families used to pick icon, palette and label. */
enum class WeatherKind {
    CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDER;

    companion object {
        fun fromCode(code: Int): WeatherKind = when (code) {
            0 -> CLEAR
            1, 2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            in 51..57 -> DRIZZLE
            in 61..67, in 80..82 -> RAIN
            in 71..77, 85, 86 -> SNOW
            in 95..99 -> THUNDER
            else -> CLOUDY
        }
    }
}
