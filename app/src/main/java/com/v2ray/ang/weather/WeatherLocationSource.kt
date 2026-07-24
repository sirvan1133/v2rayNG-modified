package com.v2ray.ang.weather

import android.content.Context
import com.google.gson.Gson
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.IpGeoLocationResolver

/**
 * Resolves the coordinates for the weather scene using IP geolocation only.
 * The fix is cached on first success so subsequent refreshes are instant.
 */
object WeatherLocationSource {
    private const val CACHE_KEY = "weather_location_fix"
    const val SOURCE_IP = "ip"

    data class LocationFix(val latitude: Double, val longitude: Double, val source: String)

    @Volatile
    private var memory: LocationFix? = null

    suspend fun resolve(context: Context): LocationFix? {
        memory?.let { return it }
        val stored = load()
        if (stored != null) {
            memory = stored
            return stored
        }
        IpGeoLocationResolver.currentPublicLocation(context.applicationContext)?.let {
            return remember(LocationFix(it.latitude, it.longitude, SOURCE_IP))
        }
        return stored ?: memory
    }

    private fun remember(fix: LocationFix): LocationFix {
        memory = fix
        runCatching { MmkvManager.encodeSettings(CACHE_KEY, Gson().toJson(fix)) }
        return fix
    }

    private fun load(): LocationFix? = try {
        MmkvManager.decodeSettingsString(CACHE_KEY)?.let { Gson().fromJson(it, LocationFix::class.java) }
    } catch (_: Exception) {
        null
    }
}
