package com.v2ray.ang.ui

import android.content.Context
import android.net.Network
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.DirectPingManager
import com.v2ray.ang.util.HttpUtil
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Resolves source/server IP locations without accidentally using the VPN tunnel. */
object IpGeoLocationResolver {
    data class Result(
        val latitude: Double,
        val longitude: Double,
        val country: String,
        val countryCode: String
    )

    private data class CacheEntry(val result: Result, val storedAt: Long)
    private data class StoredSource(val result: Result, val storedAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val failedUntil = ConcurrentHashMap<String, Long>()
    private val lookupGate = Semaphore(1, true)
    @Volatile private var lastLookupAt = 0L

    /**
     * Reads the public IP over the physical network first, then keys GeoIP cache
     * by that exact IP. A Wi-Fi/mobile/IP change can no longer reuse stale coords.
     */
    fun currentPublicLocation(context: Context): Result? {
        val stored = loadStoredSource()
        if (stored != null && System.currentTimeMillis() - stored.storedAt < SOURCE_REFRESH_MS) {
            return stored.result
        }
        val network = DirectPingManager.directNetwork(context.applicationContext) ?: return null
        val client = directClient(network)
        val publicIp = fetchPublicIp(client) ?: return stored?.result
        val fresh = lookupCached("public:$publicIp", publicIp, client, PUBLIC_CACHE_TTL_MS)
        if (fresh != null) saveStoredSource(fresh)
        return fresh ?: stored?.result
    }

    fun serverLocation(context: Context, host: String?): Result? {
        val value = host?.trim().orEmpty()
        if (value.isEmpty()) return null
        val network = DirectPingManager.directNetwork(context.applicationContext)
        val ip = try {
            network?.getAllByName(value)?.firstOrNull()?.hostAddress
                ?: HttpUtil.resolveHostToIP(value)?.firstOrNull()
                ?: value
        } catch (_: Exception) {
            value
        }
        val client = network?.let(::directClient) ?: defaultClient()
        return lookupCached("server:${ip.lowercase()}", ip, client, SERVER_CACHE_TTL_MS)
    }

    private fun lookupCached(
        key: String,
        ip: String,
        client: OkHttpClient,
        ttl: Long
    ): Result? {
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { now - it.storedAt < ttl }?.let { return it.result }
        if ((failedUntil[key] ?: 0L) > now) return null

        lookupGate.acquire()
        try {
            val lockedNow = System.currentTimeMillis()
            cache[key]?.takeIf { lockedNow - it.storedAt < ttl }?.let { return it.result }
            if ((failedUntil[key] ?: 0L) > lockedNow) return null
            val spacing = MIN_LOOKUP_SPACING_MS - (lockedNow - lastLookupAt)
            if (spacing > 0) Thread.sleep(spacing)

            lastLookupAt = System.currentTimeMillis()
            val primary = lookupIpWho(client, ip)
            val fallback = if (primary == null) lookupIpApi(client, ip) else null
            val result = primary ?: fallback
            return if (result != null) {
                cache[key] = CacheEntry(result, System.currentTimeMillis())
                failedUntil.remove(key)
                result
            } else {
                failedUntil[key] = System.currentTimeMillis() + FAILURE_BACKOFF_MS
                null
            }
        } finally {
            lookupGate.release()
        }
    }

    private fun fetchPublicIp(client: OkHttpClient): String? {
        val endpoints = arrayOf(
            "https://api64.ipify.org",
            "https://icanhazip.com"
        )
        for (url in endpoints) {
            val value = getText(client, url)?.trim()
            if (value != null && isPublicIpText(value)) return value
        }
        return null
    }

    private fun lookupIpWho(client: OkHttpClient, ip: String): Result? = try {
        val json = JSONObject(getText(client, "https://ipwho.is/$ip") ?: return null)
        if (!json.optBoolean("success", false)) return null
        resultFrom(
            json.optDouble("latitude", Double.NaN),
            json.optDouble("longitude", Double.NaN),
            json.optString("country", "Unknown"),
            json.optString("country_code", "")
        )
    } catch (_: Exception) {
        null
    }

    private fun lookupIpApi(client: OkHttpClient, ip: String): Result? = try {
        val json = JSONObject(getText(client, "https://ipapi.co/$ip/json/") ?: return null)
        if (json.has("error")) return null
        resultFrom(
            json.optDouble("latitude", Double.NaN),
            json.optDouble("longitude", Double.NaN),
            json.optString("country_name", "Unknown"),
            json.optString("country_code", "")
        )
    } catch (_: Exception) {
        null
    }

    private fun resultFrom(
        latitude: Double,
        longitude: Double,
        country: String,
        countryCode: String
    ): Result? = Result(latitude, longitude, country, countryCode.uppercase())
        .takeIf {
            it.latitude.isFinite() && it.longitude.isFinite() &&
                it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 &&
                it.countryCode.length == 2
        }

    private fun getText(client: OkHttpClient, url: String): String? = try {
        client.newCall(
            Request.Builder().url(url).header("Accept", "application/json,text/plain").build()
        ).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }

    private fun directClient(network: Network) = clientBuilder()
        .socketFactory(network.socketFactory)
        .dns(Dns { hostname -> network.getAllByName(hostname).toList() })
        .build()

    private fun defaultClient() = clientBuilder().build()

    private fun clientBuilder() = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)

    private fun isPublicIpText(value: String): Boolean =
        value.length in 3..64 &&
            value.none { it.isWhitespace() } &&
            (value.contains('.') || value.contains(':'))

    private fun loadStoredSource(): StoredSource? = try {
        val raw = MmkvManager.decodeSettingsString(SOURCE_CACHE_KEY).orEmpty()
        if (raw.isBlank()) return null
        val json = JSONObject(raw)
        val result = resultFrom(
            json.optDouble("latitude", Double.NaN),
            json.optDouble("longitude", Double.NaN),
            json.optString("country", "Unknown"),
            json.optString("countryCode", "")
        ) ?: return null
        StoredSource(result, json.optLong("storedAt", 0L))
    } catch (_: Exception) {
        null
    }

    private fun saveStoredSource(result: Result) {
        val json = JSONObject()
            .put("latitude", result.latitude)
            .put("longitude", result.longitude)
            .put("country", result.country)
            .put("countryCode", result.countryCode)
            .put("storedAt", System.currentTimeMillis())
        MmkvManager.encodeSettings(SOURCE_CACHE_KEY, json.toString())
    }

    private const val SOURCE_CACHE_KEY = "source_geoip_daily_cache"
    private const val SOURCE_REFRESH_MS = 24 * 60 * 60 * 1000L
    private const val PUBLIC_CACHE_TTL_MS = SOURCE_REFRESH_MS
    private const val SERVER_CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val FAILURE_BACKOFF_MS = 15_000L
    private const val MIN_LOOKUP_SPACING_MS = 200L
}
