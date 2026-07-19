package com.v2ray.ang.ui

import com.v2ray.ang.util.HttpUtil
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Resolves the public egress IP or an outbound server IP to a map endpoint. */
object IpGeoLocationResolver {
    data class Result(val latitude: Double, val longitude: Double, val country: String, val countryCode: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    fun currentPublicLocation(): Result? = lookup(null)

    fun serverLocation(host: String?): Result? {
        val value = host?.trim().orEmpty()
        if (value.isEmpty()) return null
        // Resolve first so the lookup represents the actual endpoint, not a label/TLD guess.
        val ip = HttpUtil.resolveHostToIP(value)?.firstOrNull() ?: value
        return lookup(ip)
    }

    private fun lookup(ip: String?): Result? = try {
        val url = if (ip.isNullOrBlank()) "https://ipwho.is/" else "https://ipwho.is/$ip"
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) return null
            Result(
                latitude = json.optDouble("latitude", Double.NaN),
                longitude = json.optDouble("longitude", Double.NaN),
                country = json.optString("country", "Unknown"),
                countryCode = json.optString("country_code", "")
            ).takeIf { it.latitude.isFinite() && it.longitude.isFinite() }
        }
    } catch (_: Exception) {
        null
    }
}
